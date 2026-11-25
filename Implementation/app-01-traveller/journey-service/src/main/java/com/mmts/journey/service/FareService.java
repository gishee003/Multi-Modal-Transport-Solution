package com.mmts.journey.service;

import com.mmts.journey.common.events.DomainEvent;
import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.journey.LegFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the fares that are valid right now (M2.4).
 * Start-up values come from application.yml; a PRICING_RULES_UPDATED event from Analytics service (topic pricing.rules)
 * replaces the surge factor and promotions while the app runs.
 */
@Service
public class FareService {

    private static final Logger log = LoggerFactory.getLogger(FareService.class);

    private final AtomicReference<FareRules> current;
    private final CacheManager cacheManager;

    public FareService(FareRules fromSettings, CacheManager cacheManager) {
        this.current = new AtomicReference<>(fromSettings);
        this.cacheManager = cacheManager;
    }

    public FareRules current() {
        return current.get();
    }

    /** A leg factory that prices legs with the current fares. */
    public LegFactory legFactory() {
        return new LegFactory(current.get());
    }

    /**
     * Receives pricing rules (INT-18). Payload: surgeFactor, commissionPercent, promotions[].
     * We read each promotion as {"mode": "BIKE", "discountPercent": 20}. commissionPercent is the platform's share
     * of the provider's money; it does not change what the traveller pays, so the planner ignores it.
     */
    @EventListener(condition = "#event.topic == 'pricing.rules' and #event.type == 'PRICING_RULES_UPDATED'")
    public void onPricingRulesUpdated(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        double surge = EventPayloads.number(payload, "surgeFactor", 1.0);
        Map<Mode, Integer> discounts = new EnumMap<>(Mode.class);
        for (Map<String, Object> promo : EventPayloads.objects(payload, "promotions")) {
            String mode = EventPayloads.text(promo, "mode");
            Double percent = EventPayloads.number(promo, "discountPercent");
            Mode known = mode == null ? null : java.util.Arrays.stream(Mode.values())
                    .filter(m -> m.name().equalsIgnoreCase(mode)).findFirst().orElse(null);
            if (known != null && percent != null) {   // unknown modes are skipped, not an error
                discounts.put(known, (int) Math.round(Math.max(0, Math.min(100, percent))));
            }
        }
        current.updateAndGet(rules -> rules.withPricing(surge, discounts));
        clearSearchCache();   // cached search results carry old prices
        log.info("Pricing rules updated: surgeFactor={} discounts={}", surge, discounts);
    }

    private void clearSearchCache() {
        Cache cache = cacheManager.getCache(RouteCandidateService.CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
}
