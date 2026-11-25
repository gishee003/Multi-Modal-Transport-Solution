package com.mmts.payment.service;

import com.mmts.payment.api.FinanceDtos.CommissionRulesResponse;
import com.mmts.payment.api.FinanceDtos.ProviderRule;
import com.mmts.payment.common.events.DomainEvent;
import com.mmts.payment.config.PaymentSettings;
import com.mmts.payment.domain.CommissionStrategy;
import com.mmts.payment.domain.FlatFeeCommission;
import com.mmts.payment.domain.PercentageCommission;
import com.mmts.payment.domain.Provider;
import com.mmts.payment.repository.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses the commission Strategy for each provider (M4.4).
 * The rules come from application.yml (mmts.payment.commission): provider type → PERCENTAGE or FLAT_FEE.
 * The percentage can be changed while running by a PRICING_RULES_UPDATED event on "pricing.rules" (from Analytics service).
 *
 * Each TSP's commission comes from its contract: TSP_CONTRACT_ACTIVATED on "contract.events"
 * (from TSP Contract Management, M7) carries commissionPercent, which then overrides the default rule for that TSP.
 * A suspended or terminated contract removes the override (the TSP gets no new bookings anyway).
 */
@Service
public class CommissionPolicy {

    public static final String PRICING_TOPIC = "pricing.rules";
    public static final String CONTRACT_TOPIC = "contract.events";

    private static final Logger log = LoggerFactory.getLogger(CommissionPolicy.class);

    private final ProviderRepository providers;
    private final long flatFeePaise;
    private final String defaultRule;
    private final Map<String, String> ruleByProviderType = new TreeMap<>();
    private volatile int percentageBasisPoints;
    /** Commission agreed in each TSP's active contract (providerId -> basis points). */
    private final Map<String, Integer> contractBasisPoints = new ConcurrentHashMap<>();

    public CommissionPolicy(PaymentSettings settings, ProviderRepository providers) {
        PaymentSettings.Commission c = settings.commission();
        this.providers = providers;
        this.flatFeePaise = c.flatFeePaise();
        this.defaultRule = c.defaultRule().toUpperCase();
        this.percentageBasisPoints = new PercentageCommission(c.percentageBasisPoints()).basisPoints();
        c.ruleByProviderType().forEach((type, rule) -> ruleByProviderType.put(type.toUpperCase(), rule.toUpperCase()));
    }

    /** The rule for one provider. Unknown providers get the default rule. */
    public CommissionStrategy strategyFor(String providerId) {
        Integer agreed = providerId == null ? null : contractBasisPoints.get(providerId.toUpperCase());
        if (agreed != null) {
            return new PercentageCommission(agreed);
        }
        String type = providers.findById(providerId).map(Provider::getProviderType).orElse("");
        return strategyNamed(ruleByProviderType.getOrDefault(type.toUpperCase(), defaultRule));
    }

    private CommissionStrategy strategyNamed(String name) {
        return CommissionStrategy.FLAT_FEE.equals(name)
                ? new FlatFeeCommission(flatFeePaise)
                : new PercentageCommission(percentageBasisPoints);
    }

    public int percentageBasisPoints() {
        return percentageBasisPoints;
    }

    /**
     * Observer: listens for PRICING_RULES_UPDATED and takes the new commissionPercent (e.g. 7.5 → 750 basis points).
     * A bad value is logged and ignored, so the old rate stays in force.
     */
    @EventListener
    public void onPricingRules(DomainEvent event) {
        if (!PRICING_TOPIC.equals(event.topic()) || !"PRICING_RULES_UPDATED".equals(event.type())) {
            return;
        }
        Object percent = event.payload().get("commissionPercent");
        if (percent == null) {
            return;
        }
        try {
            int basisPoints = new BigDecimal(percent.toString()).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).intValueExact();
            percentageBasisPoints = new PercentageCommission(basisPoints).basisPoints();
            log.info("Commission percentage changed by pricing rules to {} basis points", basisPoints);
        } catch (RuntimeException e) {
            log.warn("Ignored invalid commissionPercent {} in pricing rules", percent);
        }
    }

    /** Observer: the commission agreed in a TSP's contract (M7) applies from the moment the contract is active. */
    @EventListener
    public void onContractEvent(DomainEvent event) {
        if (!CONTRACT_TOPIC.equals(event.topic()) || event.payload().get("tspId") == null) {
            return;
        }
        String tsp = event.payload().get("tspId").toString().toUpperCase();
        switch (event.type()) {
            case "TSP_CONTRACT_ACTIVATED" -> {
                Object percent = event.payload().get("commissionPercent");
                try {
                    int bp = new BigDecimal(String.valueOf(percent)).movePointRight(2)
                            .setScale(0, RoundingMode.HALF_UP).intValueExact();
                    contractBasisPoints.put(tsp, new PercentageCommission(bp).basisPoints());
                    log.info("Commission for TSP {} set by its contract to {} basis points", tsp, bp);
                } catch (RuntimeException e) {
                    log.warn("Ignored invalid commissionPercent {} in contract of {}", percent, tsp);
                }
            }
            case "TSP_CONTRACT_SUSPENDED", "TSP_CONTRACT_TERMINATED" -> contractBasisPoints.remove(tsp);
            default -> {
                // SLA events do not change the commission
            }
        }
    }

    @Transactional(readOnly = true)
    public CommissionRulesResponse describe() {
        var list = providers.findAll().stream()
                .map(p -> new ProviderRule(p.getProviderId(), p.getName(), p.getProviderType(),
                        strategyFor(p.getProviderId()).describe(), p.getLinkedAccountId()))
                .toList();
        return new CommissionRulesResponse(percentageBasisPoints, flatFeePaise, defaultRule,
                Map.copyOf(ruleByProviderType), list);
    }
}
