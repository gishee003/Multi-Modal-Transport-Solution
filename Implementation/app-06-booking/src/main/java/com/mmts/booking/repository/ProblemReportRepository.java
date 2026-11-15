package com.mmts.booking.repository;

import com.mmts.booking.domain.ProblemReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Problem reports table. */
public interface ProblemReportRepository extends JpaRepository<ProblemReport, String> {

    List<ProblemReport> findByBookingIdOrderByCreatedAtAsc(String bookingId);
}
