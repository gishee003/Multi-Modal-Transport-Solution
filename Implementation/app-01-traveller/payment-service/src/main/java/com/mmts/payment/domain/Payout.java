package com.mmts.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One transfer of money to one provider, made by a payout run (M4.5). */
@Entity
@Table(name = "payouts")
public class Payout {

    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;

    private String providerId;

    private long amountPaise;

    /** PAID or FAILED. A failed payout leaves the money owed, so the next run tries again. */
    private String status;

    /** The gateway's transfer id, e.g. "trf_stub_0001". */
    private String transferReference;

    private String failureReason;

    private Instant createdAt;

    protected Payout() {
        // for JPA
    }

    public Payout(String runId, String providerId, long amountPaise, String status, String transferReference,
                  String failureReason, Instant createdAt) {
        this.runId = runId;
        this.providerId = providerId;
        this.amountPaise = amountPaise;
        this.status = status;
        this.transferReference = transferReference;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
    }

    /** "po-3" is the id finance staff see. */
    public String payoutId() {
        return "po-" + id;
    }

    public String getRunId() {
        return runId;
    }

    public String getProviderId() {
        return providerId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getStatus() {
        return status;
    }

    public String getTransferReference() {
        return transferReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
