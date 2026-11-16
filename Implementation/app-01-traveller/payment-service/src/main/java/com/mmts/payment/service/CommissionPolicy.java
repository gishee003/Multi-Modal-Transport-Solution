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

/**
 * Chooses the commission Strategy for each provider (M4.4).
 * The rules come from application.yml (mmts.payment.commission): provider type → PERCENTAGE or FLAT_FEE.
 * The percentage can be changed while running by a PRICING_RULES_UPDATED event on "pricing.rules" (from Analytics service).
 */
@Service
public class CommissionPolicy {

    public static final String PRICING_TOPIC = "pricing.rules";

    private static final Logger log = LoggerFactory.getLogger(CommissionPolicy.class);

    private final ProviderRepository providers;
    private final long flatFeePaise;
    private final String defaultRule;
    private final Map<String, String> ruleByProviderType = new TreeMap<>();
    private volatile int percentageBasisPoints;

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
