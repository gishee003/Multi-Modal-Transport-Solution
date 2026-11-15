package com.mmts.booking.service;

import com.mmts.booking.BookingProperties;
import com.mmts.booking.domain.Mode;
import org.springframework.stereotype.Component;

/**
 * Current pricing rules. Starts with the default surge factor from application.yml and is updated when
 * APP-09 publishes PRICING_RULES_UPDATED on pricing.rules (see {@link BookingEventListener}).
 * Surge applies only to on-demand taxi legs; public transport and rentals keep the provider's fare.
 */
@Component
public class PricingRules {

    private final double defaultSurge;
    private volatile double surgeFactor;

    public PricingRules(BookingProperties properties) {
        this.defaultSurge = properties.defaultSurgeFactor();
        this.surgeFactor = defaultSurge;
    }

    /** Final price in paise for a leg, given the provider's quoted price. */
    public long priceFor(Mode mode, long providerPricePaise) {
        if (mode == Mode.TAXI) {
            return Math.round(providerPricePaise * surgeFactor);
        }
        return providerPricePaise;
    }

    public void updateSurge(double newFactor) {
        if (newFactor >= 1.0 && newFactor <= 3.0) {    // ignore nonsense values
            this.surgeFactor = newFactor;
        }
    }

    public double surgeFactor() {
        return surgeFactor;
    }

    public void reset() {
        this.surgeFactor = defaultSurge;
    }
}
