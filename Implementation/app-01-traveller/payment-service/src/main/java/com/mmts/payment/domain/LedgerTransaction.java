package com.mmts.payment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One money movement, built line by line before it is saved (double-entry bookkeeping).
 *
 * Example, a wallet top-up of 50000 paise:
 * <pre>
 * LedgerTransaction.of("TOPUP:wt-1", "Wallet top-up")
 *         .debit(GATEWAY_CLEARING, 50000)
 *         .credit("USER_WALLET:u-101", 50000);
 * </pre>
 * The golden rule: total debits must equal total credits. {@link #toEntries} refuses anything else,
 * so an unbalanced movement can never reach the database.
 */
public final class LedgerTransaction {

    public record Line(String account, EntryDirection direction, long amountPaise) {
    }

    private final String reference;
    private final String description;
    private final List<Line> lines = new ArrayList<>();

    private LedgerTransaction(String reference, String description) {
        this.reference = reference;
        this.description = description;
    }

    public static LedgerTransaction of(String reference, String description) {
        return new LedgerTransaction(reference, description);
    }

    public LedgerTransaction debit(String account, long amountPaise) {
        return add(account, EntryDirection.DEBIT, amountPaise);
    }

    public LedgerTransaction credit(String account, long amountPaise) {
        return add(account, EntryDirection.CREDIT, amountPaise);
    }

    /** Lines of 0 paise are skipped (e.g. a split with no commission); negative amounts are a programming error. */
    private LedgerTransaction add(String account, EntryDirection direction, long amountPaise) {
        if (amountPaise < 0) {
            throw new IllegalArgumentException("Ledger amounts are never negative; use the other side instead");
        }
        if (amountPaise > 0) {
            lines.add(new Line(account, direction, amountPaise));
        }
        return this;
    }

    public long totalDebits() {
        return total(EntryDirection.DEBIT);
    }

    public long totalCredits() {
        return total(EntryDirection.CREDIT);
    }

    private long total(EntryDirection direction) {
        return lines.stream().filter(l -> l.direction() == direction)
                .mapToLong(Line::amountPaise).reduce(0, Math::addExact);
    }

    /** Checks the golden rule, then turns the lines into entries that can be saved. */
    public List<LedgerEntry> toEntries(Instant now) {
        if (totalDebits() == 0 || totalDebits() != totalCredits()) {
            throw new IllegalStateException("Unbalanced ledger transaction " + reference + ": debits "
                    + totalDebits() + " vs credits " + totalCredits());
        }
        return lines.stream()
                .map(l -> new LedgerEntry(reference, l.account(), l.direction(), l.amountPaise(), description, now))
                .toList();
    }

    public String reference() {
        return reference;
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }
}
