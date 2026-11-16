package com.mmts.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A traveller's prepaid wallet (M4.1). One wallet per user, created the first time it is used.
 * Money is always a whole number of paise (1 rupee = 100 paise), never a decimal.
 *
 * Optimistic locking: {@code version} goes up by one on every change. If two requests change the same
 * wallet at the same moment, the second one to save sees an old version and fails instead of
 * silently overwriting the first change (so a balance can never be "lost").
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private String userId;

    private long balancePaise;

    @Version
    private Long version;

    private Instant createdAt;

    private Instant updatedAt;

    protected Wallet() {
        // for JPA
    }

    public Wallet(String userId, Instant now) {
        this.userId = userId;
        this.balancePaise = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Adds money (top-up or refund). */
    public void credit(long amountPaise, Instant now) {
        requirePositive(amountPaise);
        balancePaise = Math.addExact(balancePaise, amountPaise);
        updatedAt = now;
    }

    /** Takes money out (trip payment). The caller checks {@link #canPay} first. */
    public void debit(long amountPaise, Instant now) {
        requirePositive(amountPaise);
        if (amountPaise > balancePaise) {
            throw new IllegalStateException("Insufficient wallet balance");
        }
        balancePaise -= amountPaise;
        updatedAt = now;
    }

    public boolean canPay(long amountPaise) {
        return balancePaise >= amountPaise;
    }

    private static void requirePositive(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public String getUserId() {
        return userId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
