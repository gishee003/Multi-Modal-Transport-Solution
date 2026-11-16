package com.mmts.booking.api;

import com.mmts.booking.domain.AccessType;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.domain.LegStatus;
import com.mmts.booking.domain.Mode;

import java.time.Instant;
import java.util.List;

/**
 * A booking as the traveller app sees it. {@code sagaLog} lists every step that was done and undone,
 * which makes the saga visible in the demo. {@code access} on a leg is null until the booking is paid.
 */
public record BookingResponse(String bookingId,
                              String userId,
                              BookingStatus status,
                              long totalPaise,
                              String paymentMethod,
                              String paymentId,
                              String refundId,
                              String cancelReason,
                              Integer ratingStars,
                              String ratingComment,
                              Instant createdAt,
                              Instant updatedAt,
                              Long version,
                              List<LegView> legs,
                              List<String> sagaLog) {

    public record LegView(String legId,
                          Mode mode,
                          String providerId,
                          PlaceDto from,
                          PlaceDto to,
                          Instant startTime,
                          String vehicleId,
                          LegStatus status,
                          String holdId,
                          long pricePaise,
                          AccessView access,
                          DriverView driver) {
    }

    /** type QR_TICKET (value = signed QR text), UNLOCK_CODE (value = code) or DRIVER_ASSIGNED (value = driver id). */
    public record AccessView(AccessType type, String value, Instant validFrom, Instant validUntil) {
    }

    public record DriverView(String driverId, String name, String vehicleNumber, Integer etaMinutes) {
    }
}
