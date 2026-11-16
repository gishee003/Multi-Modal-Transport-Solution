package com.mmts.booking.service;

import com.mmts.booking.BookingProperties;
import com.mmts.booking.domain.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Holds must not block seats and vehicles for ever. Every minute (mmts.booking.expiry-check-ms) this job cancels
 * bookings that are still before payment (CREATED or LEGS_HELD) more than 10 minutes
 * (mmts.booking.hold-timeout-minutes) after they were created, and releases their holds.
 * Normally the saga finishes in milliseconds; this catches bookings left behind by a crash or a lost answer.
 */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final BookingStore store;
    private final BookingService bookings;
    private final Clock clock;
    private final Duration timeout;

    public HoldExpiryJob(BookingStore store, BookingService bookings, Clock clock, BookingProperties properties) {
        this.store = store;
        this.bookings = bookings;
        this.clock = clock;
        this.timeout = Duration.ofMinutes(properties.holdTimeoutMinutes());
    }

    /** Returns how many bookings were cancelled (handy in tests). */
    @Scheduled(fixedDelayString = "${mmts.booking.expiry-check-ms}", initialDelayString = "${mmts.booking.expiry-check-ms}")
    public int cancelExpiredHolds() {
        Instant cutoff = clock.instant().minus(timeout);
        int cancelled = 0;
        for (Booking booking : store.stuckBeforePayment(cutoff)) {
            try {
                if (bookings.cancelUnpaid(booking.getId(), "HOLD_EXPIRED")) {
                    cancelled++;
                    log.info("Booking {} was not paid within {} minutes and was cancelled", booking.getId(), timeout.toMinutes());
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire booking {}: {}", booking.getId(), e.getMessage());   // try again next run
            }
        }
        return cancelled;
    }
}
