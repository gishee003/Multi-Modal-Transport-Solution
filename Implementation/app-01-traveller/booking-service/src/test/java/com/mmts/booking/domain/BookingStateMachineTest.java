package com.mmts.booking.domain;

import com.mmts.booking.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests of the booking state machine (State pattern) and driver D7. No Spring, no database. */
class BookingStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private static Booking booking(Mode mode) {
        Booking b = new Booking("b-test", "u-101", "key-1", "hash", "WALLET", NOW);
        b.addLeg(mode, "ZIPRIDE", new Place("Rajiv Chowk Metro", 28.6328, 77.2197, "DMRC-RCK"),
                new Place("India Gate", 28.6129, 77.2295, null), NOW, "ZR-SC-0042");
        return b;
    }

    @Test
    void happyPathTransitionsAreAllowed() {
        Booking b = booking(Mode.SCOOTER);
        b.moveTo(BookingStatus.LEGS_HELD, NOW);
        b.moveTo(BookingStatus.PAID, NOW);
        b.moveTo(BookingStatus.CONFIRMED, NOW);
        b.moveTo(BookingStatus.COMPLETED, NOW);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void illegalTransitionsAreRefused() {
        assertThat(BookingStatus.CREATED.canMoveTo(BookingStatus.PAID)).isFalse();         // cannot skip the holds
        assertThat(BookingStatus.LEGS_HELD.canMoveTo(BookingStatus.CONFIRMED)).isFalse();  // cannot skip payment
        assertThat(BookingStatus.CANCELLED.canMoveTo(BookingStatus.CONFIRMED)).isFalse();  // cancelled is final
        assertThat(BookingStatus.COMPLETED.canMoveTo(BookingStatus.CANCELLED)).isFalse();  // finished trip stays finished

        Booking b = booking(Mode.SCOOTER);
        assertThatThrownBy(() -> b.moveTo(BookingStatus.CONFIRMED, NOW))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ILLEGAL_STATE");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CREATED);
    }

    @Test
    void cancelledBookingCannotBeCancelledAgain() {
        Booking b = booking(Mode.SCOOTER);
        b.cancel("CANCELLED_BY_USER", NOW);
        assertThatThrownBy(() -> b.cancel("again", NOW)).hasFieldOrPropertyWithValue("code", "ILLEGAL_STATE");
    }

    @Test
    void noTicketOrUnlockCodeBeforePayment() {
        Booking b = booking(Mode.SCOOTER);
        b.moveTo(BookingStatus.LEGS_HELD, NOW);
        BookingLeg leg = b.getLegs().get(0);

        assertThatThrownBy(() -> b.issueAccess(leg, AccessType.UNLOCK_CODE, "ZR-7731", NOW, NOW))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ILLEGAL_STATE");
        assertThat(leg.getAccessValue()).isNull();

        b.moveTo(BookingStatus.PAID, NOW);
        b.issueAccess(leg, AccessType.UNLOCK_CODE, "ZR-7731", NOW, NOW);
        assertThat(leg.getAccessValue()).isEqualTo("ZR-7731");
    }

    @Test
    void cancellingWithdrawsTickets() {
        Booking b = booking(Mode.SCOOTER);
        b.moveTo(BookingStatus.LEGS_HELD, NOW);
        b.moveTo(BookingStatus.PAID, NOW);
        b.issueAccess(b.getLegs().get(0), AccessType.UNLOCK_CODE, "ZR-7731", NOW, NOW);
        b.cancel("CANCELLED_BY_USER", NOW);
        assertThat(b.getLegs().get(0).getAccessValue()).isNull();
        assertThat(b.getCancelReason()).isEqualTo("CANCELLED_BY_USER");
    }

    @Test
    void unlockOnlyForVehicleLegsOfConfirmedBookings() {
        Booking scooter = booking(Mode.SCOOTER);
        scooter.moveTo(BookingStatus.LEGS_HELD, NOW);
        scooter.moveTo(BookingStatus.PAID, NOW);
        BookingLeg leg = scooter.getLegs().get(0);
        assertThatThrownBy(() -> scooter.unlockLeg(leg, NOW)).hasFieldOrPropertyWithValue("code", "ILLEGAL_STATE");

        scooter.moveTo(BookingStatus.CONFIRMED, NOW);
        scooter.unlockLeg(leg, NOW);
        assertThat(leg.getStatus()).isEqualTo(LegStatus.UNLOCKED);

        Booking metro = booking(Mode.METRO);
        metro.moveTo(BookingStatus.LEGS_HELD, NOW);
        metro.moveTo(BookingStatus.PAID, NOW);
        metro.moveTo(BookingStatus.CONFIRMED, NOW);
        assertThatThrownBy(() -> metro.unlockLeg(metro.getLegs().get(0), NOW))
                .hasFieldOrPropertyWithValue("code", "NOT_UNLOCKABLE");
    }

    @Test
    void ratingOnlyOnceAndOnlyAfterCompletion() {
        Booking b = booking(Mode.SCOOTER);
        assertThatThrownBy(() -> b.rate(5, "Great", NOW)).hasFieldOrPropertyWithValue("code", "ILLEGAL_STATE");
        b.moveTo(BookingStatus.LEGS_HELD, NOW);
        b.moveTo(BookingStatus.PAID, NOW);
        b.moveTo(BookingStatus.CONFIRMED, NOW);
        b.moveTo(BookingStatus.COMPLETED, NOW);
        b.rate(5, "Great", NOW);
        assertThat(b.getRatingStars()).isEqualTo(5);
        assertThatThrownBy(() -> b.rate(1, "Changed my mind", NOW)).hasFieldOrPropertyWithValue("code", "ALREADY_RATED");
    }
}
