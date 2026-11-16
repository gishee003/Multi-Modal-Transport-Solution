package com.mmts.payment.repository;

import com.mmts.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    boolean existsByBookingIdAndStatus(String bookingId, Payment.Status status);

    Optional<Payment> findByGatewayReference(String gatewayReference);

    /** Payments with a given status created in [from, to): used for the daily settlement file. */
    @Query("select p from Payment p where p.status = :status and p.createdAt >= :from and p.createdAt < :to order by p.id")
    List<Payment> findInPeriod(@Param("status") Payment.Status status, @Param("from") Instant from,
                               @Param("to") Instant to);
}
