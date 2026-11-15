package com.mmts.booking.service;

import com.mmts.booking.adapter.StubProviderHubClient;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static com.mmts.booking.TestJourneys.heldButUnpaid;
import static org.assertj.core.api.Assertions.assertThat;

/** Holds expire: a booking stuck before payment for more than 10 minutes is cancelled and its holds released. */
@SpringBootTest
@AutoConfigureMockMvc
class HoldExpiryJobTest {

    @Autowired
    HoldExpiryJob job;
    @Autowired
    BookingStore store;
    @Autowired
    StubProviderHubClient providerHub;

    @Test
    void unpaidBookingOlderThanTenMinutesIsCancelled() {
        Booking old = heldButUnpaid(store, providerHub, 15);
        Booking fresh = heldButUnpaid(store, providerHub, 2);

        int cancelled = job.cancelExpiredHolds();

        assertThat(cancelled).isGreaterThanOrEqualTo(1);
        Booking oldNow = store.get(old.getId());
        assertThat(oldNow.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(oldNow.getCancelReason()).isEqualTo("HOLD_EXPIRED");
        assertThat(providerHub.statusOf(old.getLegs().get(0).getHoldId())).isEqualTo("RELEASED");

        assertThat(store.get(fresh.getId()).getStatus()).isEqualTo(BookingStatus.LEGS_HELD);   // still within 10 minutes
    }
}
