package com.mmts.journey.repository;

import com.mmts.journey.domain.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Stores trips in the database (H2 in the demo, PostgreSQL DB-2 in production). Spring Data writes the SQL. */
public interface TripRepository extends JpaRepository<Trip, String> {

    Optional<Trip> findByBookingId(String bookingId);

    List<Trip> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Trip> findByStatus(Trip.Status status);
}
