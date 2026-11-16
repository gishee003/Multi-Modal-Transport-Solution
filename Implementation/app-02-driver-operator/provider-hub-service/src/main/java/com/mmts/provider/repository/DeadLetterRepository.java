package com.mmts.provider.repository;

import com.mmts.provider.domain.DeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** The dead-letter store: readings that could not be accepted, kept for inspection (driver D11). */
public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    List<DeadLetter> findTop100ByOrderByIdDesc();
}
