package com.mmts.booking.service;

import com.mmts.booking.adapter.StubPaymentClient;
import com.mmts.booking.adapter.StubProviderHubClient;
import com.mmts.booking.api.BookingRequest;
import com.mmts.booking.api.BookingResponse;
import com.mmts.booking.api.BookingResponse.LegView;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.common.events.DomainEvent;
import com.mmts.booking.common.events.EventPublisher;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.domain.AccessType;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.domain.LegStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static com.mmts.booking.TestJourneys.car;
import static com.mmts.booking.TestJourneys.journey;
import static com.mmts.booking.TestJourneys.metro;
import static com.mmts.booking.TestJourneys.newKey;
import static com.mmts.booking.TestJourneys.newTraveller;
import static com.mmts.booking.TestJourneys.scooter;
import static com.mmts.booking.TestJourneys.taxi;
import static com.mmts.booking.TestJourneys.walk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The booking saga end to end, with the real stubs and the H2 database (driver D6 and D7).
 * Same Spring set-up as the other app tests, so they all share one running application.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookingSagaTest {

    @Autowired
    BookingFacade facade;
    @Autowired
    BookingStore store;
    @Autowired
    StubProviderHubClient providerHub;
    @Autowired
    StubPaymentClient payments;
    @Autowired
    DemoControlService demo;
    @Autowired
    QrTicketService qrTickets;
    @Autowired
    EventPublisher events;

    @AfterEach
    void resetStubs() {
        demo.reset();
    }

    @Test
    void happyPathConfirmsEveryLegAndIssuesAccess() {
        AuthenticatedUser user = newTraveller();
        BookingFacade.BookResult result = facade.book(user, journey(walk(), metro(), scooter(), taxi()), newKey());

        BookingResponse b = result.booking();
        assertThat(result.created()).isTrue();
        assertThat(b.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.totalPaise()).isEqualTo(4000 + 14500 + 35000);           // fares from application.yml
        assertThat(payments.paymentsFor(b.bookingId())).isEqualTo(1);           // charged once for the total

        LegView walking = b.legs().get(0);
        LegView metroLeg = b.legs().get(1);
        LegView scooterLeg = b.legs().get(2);
        LegView taxiLeg = b.legs().get(3);
        assertThat(walking.status()).isEqualTo(LegStatus.NOT_NEEDED);
        assertThat(walking.access()).isNull();

        assertThat(metroLeg.access().type()).isEqualTo(AccessType.QR_TICKET);
        assertThat(qrTickets.verify(metroLeg.access().value()).valid()).isTrue();
        assertThat(scooterLeg.access().type()).isEqualTo(AccessType.UNLOCK_CODE);
        assertThat(scooterLeg.access().value()).startsWith("ZI-");
        assertThat(taxiLeg.access().type()).isEqualTo(AccessType.DRIVER_ASSIGNED);
        assertThat(taxiLeg.driver().name()).isNotBlank();
        assertThat(providerHub.statusOf(metroLeg.holdId())).isEqualTo("CONFIRMED");

        assertThat(b.sagaLog()).hasSize(5).allMatch(line -> line.contains(": done"));
        DomainEvent confirmed = events.recentEvents().stream()
                .filter(e -> e.type().equals("BOOKING_CONFIRMED")).findFirst().orElseThrow();
        assertThat(confirmed.payload().get("bookingId")).isEqualTo(b.bookingId());
    }

    @Test
    void holdRefusedReleasesTheEarlierHolds() {
        demo.refuseHolds("ZIPRIDE");
        AuthenticatedUser user = newTraveller();
        String key = newKey();

        assertThatThrownBy(() -> facade.book(user, journey(metro(), scooter(), taxi()), key))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "BOOKING_FAILED");

        Booking b = store.findByKey(user.userId(), key).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getCancelReason()).startsWith("HOLD_REFUSED");
        String metroHold = b.getLegs().get(0).getHoldId();
        assertThat(providerHub.statusOf(metroHold)).isEqualTo("RELEASED");     // the earlier hold was given back
        assertThat(b.getLegs().get(2).getHoldId()).isNull();                   // the later leg was never tried
        assertThat(payments.paymentsFor(b.getId())).isZero();                  // no money taken
    }

    @Test
    void paymentFailureReleasesHoldsAndIssuesNoTicket() {
        demo.failNextPayment("INSUFFICIENT_BALANCE");
        AuthenticatedUser user = newTraveller();
        String key = newKey();

        assertThatThrownBy(() -> facade.book(user, journey(metro(), scooter()), key))
                .hasFieldOrPropertyWithValue("code", "BOOKING_FAILED");

        Booking b = store.findByKey(user.userId(), key).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getCancelReason()).isEqualTo("PAYMENT_FAILED: INSUFFICIENT_BALANCE");
        assertThat(b.getLegs()).allSatisfy(leg -> {
            assertThat(leg.getStatus()).isEqualTo(LegStatus.RELEASED);
            assertThat(providerHub.statusOf(leg.getHoldId())).isEqualTo("RELEASED");
            assertThat(leg.getAccessValue()).isNull();                         // D7: no ticket without payment
        });
        assertThat(b.getRefundId()).isNull();                                  // nothing was captured
        assertThat(b.sagaLogLines()).contains("HOLD_LEGS: undone - released [" + b.getLegs().get(0).getHoldId()
                + ", " + b.getLegs().get(1).getHoldId() + "]");
    }

    @Test
    void confirmFailureRefundsAndReleases() {
        demo.failNextConfirm();
        AuthenticatedUser user = newTraveller();
        String key = newKey();

        assertThatThrownBy(() -> facade.book(user, journey(metro(), taxi()), key))
                .hasFieldOrPropertyWithValue("code", "BOOKING_FAILED");

        Booking b = store.findByKey(user.userId(), key).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getPaymentId()).isNotNull();
        assertThat(b.getRefundId()).isNotNull();
        assertThat(payments.refundedFor(b.getPaymentId())).isEqualTo(b.getTotalPaise());   // all money back
        assertThat(b.getLegs()).allSatisfy(leg ->
                assertThat(providerHub.statusOf(leg.getHoldId())).isEqualTo("RELEASED"));
        // undo runs newest first: the refund (step 3) is undone before the holds (step 2)
        int refundLine = indexOfLineStartingWith(b, "CHARGE_PAYMENT: undone");
        int releaseLine = indexOfLineStartingWith(b, "HOLD_LEGS: undone");
        assertThat(refundLine).isLessThan(releaseLine);
    }

    @Test
    void carLegNeedsAValidLicence() {
        AuthenticatedUser user = newTraveller();
        demo.makeIneligible(user.userId(), "EXPIRED");
        String key = newKey();

        assertThatThrownBy(() -> facade.book(user, journey(metro(), car()), key))
                .hasFieldOrPropertyWithValue("code", "NOT_ELIGIBLE");

        Booking b = store.findByKey(user.userId(), key).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getCancelReason()).isEqualTo("NOT_ELIGIBLE: EXPIRED");
        assertThat(b.getLegs()).allSatisfy(leg -> assertThat(leg.getHoldId()).isNull());   // stopped before any hold
    }

    @Test
    void carLegWithValidLicenceIsBooked() {
        BookingResponse b = facade.book(newTraveller(), journey(car()), newKey()).booking();
        assertThat(b.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.sagaLog().get(0)).isEqualTo("CHECK_LICENCE: done - licence VERIFIED");
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameBooking() {
        AuthenticatedUser user = newTraveller();
        String key = newKey();
        BookingRequest request = journey(metro(), scooter());
        BookingFacade.BookResult first = facade.book(user, request, key);
        BookingFacade.BookResult again = facade.book(user, request, key);           // e.g. the app retried after a timeout

        assertThat(again.created()).isFalse();
        assertThat(again.booking().bookingId()).isEqualTo(first.booking().bookingId());
        assertThat(payments.paymentsFor(first.booking().bookingId())).isEqualTo(1);       // never charged twice
        assertThat(store.forUser(user.userId())).hasSize(1);                              // never a second booking
    }

    @Test
    void sameKeyWithDifferentRequestIsRefused() {
        AuthenticatedUser user = newTraveller();
        String key = newKey();
        facade.book(user, journey(metro()), key);
        assertThatThrownBy(() -> facade.book(user, journey(taxi()), key))
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
    }

    private static int indexOfLineStartingWith(Booking b, String prefix) {
        for (int i = 0; i < b.sagaLogLines().size(); i++) {
            if (b.sagaLogLines().get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("No saga log line starts with " + prefix + ": " + b.sagaLogLines());
    }
}
