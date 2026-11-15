package com.mmts.booking.service;

import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.repository.BookingRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * The one place that reads and writes bookings. Every save goes through {@link #save}, which turns an
 * optimistic-lock clash (someone else saved the booking first) into a clear 409 CONCURRENT_UPDATE.
 */
@Component
public class BookingStore {

    private final BookingRepository repository;

    public BookingStore(BookingRepository repository) {
        this.repository = repository;
    }

    public Booking get(String bookingId) {
        return repository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "No booking with id " + bookingId));
    }

    public Optional<Booking> findByKey(String userId, String idempotencyKey) {
        return repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    /**
     * Saves and returns the saved copy (with the new version number). Always continue with the returned object.
     * If the version in the database moved on since this copy was loaded, the save is refused.
     */
    public Booking save(Booking booking) {
        try {
            return repository.save(booking);
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.conflict("CONCURRENT_UPDATE",
                    "Booking " + booking.getId() + " was changed by another request at the same time; reload and try again");
        }
    }

    public List<Booking> forUser(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<Booking> recent() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }

    /** Bookings that never got paid and were created before the cut-off. */
    public List<Booking> stuckBeforePayment(Instant cutoff) {
        return repository.findByStatusInAndCreatedAtBefore(
                EnumSet.of(BookingStatus.CREATED, BookingStatus.LEGS_HELD), cutoff);
    }

    public long count() {
        return repository.count();
    }
}
