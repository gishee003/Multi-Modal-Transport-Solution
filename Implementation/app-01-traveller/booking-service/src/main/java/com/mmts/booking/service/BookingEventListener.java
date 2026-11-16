package com.mmts.booking.service;

import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.common.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Subscriber side of the Observer pattern: reacts to events from other applications.
 * - licence.events (Identity service): a licence changed, so forget the cached "may drive" answer for that user.
 * - payment.events (Payment service): PAYMENT_FAILED for a booking that is still waiting for payment cancels it.
 * - pricing.rules (Analytics service): PRICING_RULES_UPDATED changes the surge factor for taxi legs.
 * In the demo these arrive on the in-process bus; in production they would come from Kafka topics.
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final DriveEligibilityService eligibility;
    private final BookingService bookings;
    private final PricingRules pricing;

    public BookingEventListener(DriveEligibilityService eligibility, BookingService bookings, PricingRules pricing) {
        this.eligibility = eligibility;
        this.bookings = bookings;
        this.pricing = pricing;
    }

    @EventListener(condition = "#event.topic == 'licence.events'")
    public void onLicenceEvent(DomainEvent event) {
        Object userId = event.payload().get("userId");
        if (userId != null) {
            eligibility.forget(userId.toString());
            log.info("{} for {}: cached eligibility dropped", event.type(), userId);
        }
    }

    @EventListener(condition = "#event.topic == 'payment.events'")
    public void onPaymentEvent(DomainEvent event) {
        if (!"PAYMENT_FAILED".equals(event.type())) {
            return;    // CAPTURED and REFUNDED are already known from the synchronous INT-11 answer
        }
        Object bookingId = event.payload().get("bookingId");
        if (bookingId == null) {
            return;
        }
        try {
            bookings.cancelUnpaid(bookingId.toString(), "PAYMENT_FAILED: " + event.payload().get("reason"));
        } catch (ApiException e) {
            log.warn("PAYMENT_FAILED for unknown booking {}", bookingId);
        }
    }

    @EventListener(condition = "#event.topic == 'pricing.rules'")
    public void onPricingRules(DomainEvent event) {
        if ("PRICING_RULES_UPDATED".equals(event.type()) && event.payload().get("surgeFactor") instanceof Number n) {
            pricing.updateSurge(n.doubleValue());
            log.info("Surge factor for taxi legs is now {}", pricing.surgeFactor());
        }
    }
}
