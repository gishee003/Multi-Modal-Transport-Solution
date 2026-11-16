package com.mmts.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The double-entry golden rule: a movement is saved only if its debits equal its credits. */
class LedgerTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void balancedTripPaymentBecomesEntries() {
        List<LedgerEntry> entries = LedgerTransaction.of("PAYMENT:p-9001", "Trip payment")
                .debit("USER_WALLET:u-101", 18_500)
                .credit("PROVIDER_PAYABLE:DMRC", 3_800)
                .credit("PROVIDER_PAYABLE:ZIPRIDE", 14_000)
                .credit("PLATFORM_REVENUE", 700)
                .toEntries(NOW);

        assertThat(entries).hasSize(4);
        assertThat(entries).allMatch(e -> e.getReference().equals("PAYMENT:p-9001"));
        long debits = entries.stream().filter(e -> e.getDirection() == EntryDirection.DEBIT)
                .mapToLong(LedgerEntry::getAmountPaise).sum();
        long credits = entries.stream().filter(e -> e.getDirection() == EntryDirection.CREDIT)
                .mapToLong(LedgerEntry::getAmountPaise).sum();
        assertThat(debits).isEqualTo(credits).isEqualTo(18_500);
    }

    @Test
    void unbalancedMovementIsRefused() {
        LedgerTransaction wrong = LedgerTransaction.of("PAYMENT:p-1", "Wrong")
                .debit("USER_WALLET:u-101", 18_500)
                .credit("PROVIDER_PAYABLE:DMRC", 18_000);

        assertThatThrownBy(() -> wrong.toEntries(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void emptyOrNegativeMovementsAreRefusedAndZeroLinesSkipped() {
        assertThatThrownBy(() -> LedgerTransaction.of("X", "nothing").toEntries(NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LedgerTransaction.of("X", "negative").debit("A", -5))
                .isInstanceOf(IllegalArgumentException.class);

        LedgerTransaction noCommission = LedgerTransaction.of("X", "zero line")
                .debit("GATEWAY_CLEARING", 1_000).credit("PROVIDER_PAYABLE:DTC", 1_000).credit("PLATFORM_REVENUE", 0);
        assertThat(noCommission.lines()).hasSize(2);
    }
}
