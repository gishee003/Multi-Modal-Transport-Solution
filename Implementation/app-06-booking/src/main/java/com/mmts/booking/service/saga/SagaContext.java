package com.mmts.booking.service.saga;

import com.mmts.booking.adapter.ProviderHubClient.ConfirmResult;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.service.BookingStore;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the saga steps share while one booking is being made: the booking itself, the provider confirmations
 * (handed from the confirm step to the issue-access step), and why a refund would be made.
 */
public class SagaContext {

    private Booking booking;
    private final BookingStore store;
    private final Clock clock;
    private final String refundReason;
    private final Map<String, ConfirmResult> confirmations = new LinkedHashMap<>();

    public SagaContext(Booking booking, BookingStore store, Clock clock, String refundReason) {
        this.booking = booking;
        this.store = store;
        this.clock = clock;
        this.refundReason = refundReason;
    }

    public Booking booking() {
        return booking;
    }

    /** Saves the booking and keeps the saved copy (it carries the new optimistic-lock version). */
    public void save() {
        booking = store.save(booking);
    }

    public Instant now() {
        return clock.instant();
    }

    public String refundReason() {
        return refundReason;
    }

    public Map<String, ConfirmResult> confirmations() {
        return confirmations;
    }
}
