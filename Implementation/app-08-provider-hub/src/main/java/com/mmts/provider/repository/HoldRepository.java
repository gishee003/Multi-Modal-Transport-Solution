package com.mmts.provider.repository;

import com.mmts.provider.domain.Hold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Stores holds (DAO pattern; Spring Data writes the SQL). */
public interface HoldRepository extends JpaRepository<Hold, String> {

    /** An existing live hold for the same leg, so a repeated hold request does not hold twice. */
    Optional<Hold> findFirstByLegIdAndStatusIn(String legId, Collection<Hold.Status> statuses);

    /** HELD holds whose expiry time has passed. */
    List<Hold> findByStatusAndExpiresAtLessThanEqual(Hold.Status status, Instant now);
}
