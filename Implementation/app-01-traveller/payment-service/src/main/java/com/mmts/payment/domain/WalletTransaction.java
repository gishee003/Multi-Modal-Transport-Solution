package com.mmts.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One line of a wallet's history: a top-up, a trip payment or a refund. */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    public enum Type { TOP_UP, PAYMENT, REFUND }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type")
    private Type type;

    private long amountPaise;

    private long balanceAfterPaise;

    /** What caused it: a payment id, a refund id, or the gateway's reference for a top-up. Never card data. */
    private String reference;

    /** Optional key sent by the client so a repeated top-up request is not applied twice. */
    @Column(unique = true)
    private String idempotencyKey;

    private Instant createdAt;

    protected WalletTransaction() {
        // for JPA
    }

    public WalletTransaction(String userId, Type type, long amountPaise, long balanceAfterPaise,
                             String reference, String idempotencyKey, Instant createdAt) {
        this.userId = userId;
        this.type = type;
        this.amountPaise = amountPaise;
        this.balanceAfterPaise = balanceAfterPaise;
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    /** "wt-12" is the id the traveller sees. */
    public String transactionId() {
        return "wt-" + id;
    }

    public String getUserId() {
        return userId;
    }

    public Type getType() {
        return type;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public long getBalanceAfterPaise() {
        return balanceAfterPaise;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
