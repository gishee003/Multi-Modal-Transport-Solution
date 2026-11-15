package com.mmts.booking.service;

import com.mmts.booking.adapter.StubLicenceClient;
import com.mmts.booking.adapter.StubProviderHubClient;
import com.mmts.booking.common.events.EventPublisher;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static com.mmts.booking.TestJourneys.car;
import static com.mmts.booking.TestJourneys.heldButUnpaid;
import static com.mmts.booking.TestJourneys.journey;
import static com.mmts.booking.TestJourneys.newKey;
import static com.mmts.booking.TestJourneys.newTraveller;
import static com.mmts.booking.TestJourneys.taxi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Subscribed events: licence.events, pricing.rules and payment.events (Observer pattern). */
@SpringBootTest
@AutoConfigureMockMvc
class BookingEventListenerTest {

    @Autowired
    EventPublisher events;
    @Autowired
    BookingFacade facade;
    @Autowired
    BookingStore store;
    @Autowired
    StubLicenceClient licences;
    @Autowired
    StubProviderHubClient providerHub;
    @Autowired
    PricingRules pricing;
    @Autowired
    DemoControlService demo;

    @AfterEach
    void reset() {
        demo.reset();
        pricing.reset();
    }

    @Test
    void licenceEventDropsTheCachedYes() {
        AuthenticatedUser user = newTraveller();
        facade.book(user, journey(car()), newKey());              // "eligible" is now cached

        licences.markIneligible(user.userId(), "EXPIRED");        // APP-04's answer changes...
        events.publish("licence.events", "LICENCE_EXPIRED", Map.of("userId", user.userId(), "expiredOn", "2026-11-15"));

        // ...and because the event dropped the cached answer, the next CAR booking is refused
        assertThatThrownBy(() -> facade.book(user, journey(car()), newKey()))
                .hasFieldOrPropertyWithValue("code", "NOT_ELIGIBLE");
    }

    @Test
    void pricingRulesChangeTheTaxiFare() {
        events.publish("pricing.rules", "PRICING_RULES_UPDATED",
                Map.of("surgeFactor", 1.5, "commissionPercent", 10, "promotions", List.of()));

        long total = facade.book(newTraveller(), journey(taxi()), newKey()).booking().totalPaise();
        assertThat(total).isEqualTo(Math.round(35000 * 1.5));
    }

    @Test
    void paymentFailedEventCancelsOnlyUnpaidBookings() {
        Booking unpaid = heldButUnpaid(store, providerHub, 1);
        String paid = facade.book(newTraveller(), journey(taxi()), newKey()).booking().bookingId();

        events.publish("payment.events", "PAYMENT_FAILED", Map.of("paymentId", "p-1", "bookingId", unpaid.getId(), "reason", "GATEWAY_DECLINED"));
        events.publish("payment.events", "PAYMENT_FAILED", Map.of("paymentId", "p-2", "bookingId", paid, "reason", "LATE"));

        assertThat(store.get(unpaid.getId()).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(store.get(unpaid.getId()).getCancelReason()).isEqualTo("PAYMENT_FAILED: GATEWAY_DECLINED");
        assertThat(store.get(paid).getStatus()).isEqualTo(BookingStatus.CONFIRMED);   // a late notice changes nothing
    }
}
