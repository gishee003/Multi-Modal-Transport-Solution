package com.mmts.booking.repository;

import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Bookings table (DAO pattern). Spring Data writes the SQL from the method names. */
public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    List<Booking> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Booking> findTop50ByOrderByCreatedAtDesc();

    /** Bookings still in the given states that were created before the cut-off (used by the hold-expiry job). */
    List<Booking> findByStatusInAndCreatedAtBefore(Collection<BookingStatus> statuses, Instant cutoff);
}
