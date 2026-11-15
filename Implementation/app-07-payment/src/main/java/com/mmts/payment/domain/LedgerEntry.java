package com.mmts.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One line of the double-entry ledger (M4.6).
 *
 * Append-only: there are no setters, and the JPA callbacks below refuse any update or delete.
 * A mistake is fixed by posting a new, correcting transaction, never by changing history.
 * (In production the database user of this service would also have no UPDATE / DELETE rights on this table.)
 */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "ix_ledger_reference", columnList = "reference"),
        @Index(name = "ix_ledger_account", columnList = "account")})
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which money movement this line belongs to, e.g. "PAYMENT:p-9001". All lines of one movement share it. */
    @Column(nullable = false)
    private String reference;

    /** e.g. "USER_WALLET:u-101", "PROVIDER_PAYABLE:DMRC", "PLATFORM_REVENUE". */
    @Column(nullable = false)
    private String account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryDirection direction;

    private long amountPaise;

    private String description;

    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    LedgerEntry(String reference, String account, EntryDirection direction, long amountPaise, String description,
                Instant createdAt) {
        this.reference = reference;
        this.account = account;
        this.direction = direction;
        this.amountPaise = amountPaise;
        this.description = description;
        this.createdAt = createdAt;
    }

    @PreUpdate
    @PreRemove
    void refuseChange() {
        throw new IllegalStateException(
                "Ledger entries are append-only: post a correcting entry instead of changing or deleting one");
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
