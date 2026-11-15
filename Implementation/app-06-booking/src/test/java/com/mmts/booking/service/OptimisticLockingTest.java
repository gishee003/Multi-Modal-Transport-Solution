package com.mmts.booking.service;

import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mmts.booking.TestJourneys.journey;
import static com.mmts.booking.TestJourneys.metro;
import static com.mmts.booking.TestJourneys.newKey;
import static com.mmts.booking.TestJourneys.newTraveller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Driver D6: two updates of the same booking at the same time cannot both win (@Version optimistic locking). */
@SpringBootTest
@AutoConfigureMockMvc
class OptimisticLockingTest {

    @Autowired
    BookingFacade facade;
    @Autowired
    BookingStore store;

    @Test
    void secondUpdateFromAStaleCopyIsRefused() {
        String id = facade.book(newTraveller(), journey(metro()), newKey()).booking().bookingId();

        // Two requests load the same booking (same version number)...
        Booking first = store.get(id);
        Booking second = store.get(id);
        assertThat(first.getVersion()).isEqualTo(second.getVersion());

        // ...the first one saves: the version goes up by one.
        first.moveTo(BookingStatus.COMPLETED, Instant.now());
        Booking saved = store.save(first);
        assertThat(saved.getVersion()).isEqualTo(second.getVersion() + 1);

        // The second one still holds the old version, so its save is refused.
        second.cancel("CANCELLED_BY_USER", Instant.now());
        assertThatThrownBy(() -> store.save(second))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CONCURRENT_UPDATE");

        assertThat(store.get(id).getStatus()).isEqualTo(BookingStatus.COMPLETED);   // the first change survived
    }

    @Test
    void twoThreadsRacingOnlyOneWins() throws Exception {
        String id = facade.book(newTraveller(), journey(metro()), newKey()).booking().bookingId();
        Booking copyA = store.get(id);
        Booking copyB = store.get(id);
        copyA.moveTo(BookingStatus.COMPLETED, Instant.now());
        copyB.cancel("CANCELLED_BY_USER", Instant.now());

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> a = pool.submit(() -> trySave(go, copyA));
        Future<Boolean> b = pool.submit(() -> trySave(go, copyB));
        go.countDown();                                           // both threads save at the same moment
        int winners = (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0);
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
    }

    private boolean trySave(CountDownLatch go, Booking copy) throws InterruptedException {
        go.await();
        try {
            store.save(copy);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }
}
