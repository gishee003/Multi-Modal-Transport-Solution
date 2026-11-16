package com.mmts.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;

/** Money given back for a payment (M4.3). The idempotency key is unique, so a retried refund never pays out twice. */
@Entity
@Table(name = "refunds")
@SequenceGenerator(name = "refund_seq", sequenceName = "refund_seq", initialValue = 70, allocationSize = 1)
public class Refund {

    public static final String STATUS_REFUNDED = "REFUNDED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "refund_seq")
    private Long id;

    @Column(unique = true, nullable = false)
    private String idempotencyKey;

    /** The payment's public id, e.g. "p-9001". */
    private String paymentId;

    private String bookingId;

    private long amountPaise;

    private String reason;

    private String status;

    private Instant createdAt;

    protected Refund() {
        // for JPA
    }

    public Refund(String idempotencyKey, String paymentId, String bookingId, long amountPaise, String reason,
                  Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amountPaise = amountPaise;
        this.reason = reason;
        this.status = STATUS_REFUNDED;
        this.createdAt = createdAt;
    }

    /** "r-77" is the id other applications see. */
    public String refundId() {
        return "r-" + id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getBookingId() {
        return bookingId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }
}
