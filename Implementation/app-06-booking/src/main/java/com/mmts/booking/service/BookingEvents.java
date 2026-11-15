package com.mmts.booking.service;

import com.mmts.booking.common.events.EventPublisher;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.Place;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes booking.events (INT-16) with the payloads agreed in docs/CONTRACTS.md (Observer pattern:
 * APP-05 listens to start live tracking; this app does not need to know who listens).
 */
@Component
public class BookingEvents {

    public static final String TOPIC = "booking.events";

    private final EventPublisher events;

    public BookingEvents(EventPublisher events) {
        this.events = events;
    }

    public void confirmed(Booking b) {
        List<Map<String, Object>> legs = b.getLegs().stream().map(BookingEvents::leg).toList();
        events.publish(TOPIC, "BOOKING_CONFIRMED", Map.of(
                "bookingId", b.getId(), "userId", b.getUserId(), "totalPaise", b.getTotalPaise(), "legs", legs));
    }

    public void cancelled(Booking b) {
        events.publish(TOPIC, "BOOKING_CANCELLED", Map.of(
                "bookingId", b.getId(), "userId", b.getUserId(), "reason", String.valueOf(b.getCancelReason())));
    }

    public void completed(Booking b) {
        events.publish(TOPIC, "BOOKING_COMPLETED", Map.of("bookingId", b.getId(), "userId", b.getUserId()));
    }

    private static Map<String, Object> leg(BookingLeg l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("legId", l.getId());
        m.put("mode", l.getMode().name());
        m.put("providerId", l.getProviderId());
        m.put("from", place(l.getFromPlace()));
        m.put("to", place(l.getToPlace()));
        m.put("startTime", l.getStartTime().toString());
        return m;
    }

    private static Map<String, Object> place(Place p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("lat", p.getLat());
        m.put("lon", p.getLon());
        if (p.getStopId() != null) {
            m.put("stopId", p.getStopId());
        }
        return m;
    }
}
