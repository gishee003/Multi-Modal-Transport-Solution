package com.mmts.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How a refund is shared out between the providers and the platform, without losing a single paisa. */
class RefundAllocationTest {

    private static Payment contractExamplePayment() {
        Payment p = new Payment("b-5001-pay", "b-5001", "u-101", 18_500, Payment.Method.WALLET,
                Instant.parse("2026-11-15T10:00:02Z"));
        p.addSplit("DMRC", 4_000, 200, "PERCENTAGE 5.00%");
        p.addSplit("ZIPRIDE", 14_500, 500, "FLAT_FEE 500 paise");
        p.markCaptured(null);
        return p;
    }

    @Test
    void fullRefundGivesBackEverySplitExactly() {
        Payment p = contractExamplePayment();

        List<Payment.RefundPortion> portions = p.applyRefund(18_500);

        assertThat(portions).containsExactly(
                new Payment.RefundPortion("DMRC", 3_800, 200),
                new Payment.RefundPortion("ZIPRIDE", 14_000, 500));
        assertThat(p.refundablePaise()).isZero();
    }

    @Test
    void partialRefundsAddUpToTheRefundAndThenToTheWholePayment() {
        Payment p = contractExamplePayment();

        List<Payment.RefundPortion> first = p.applyRefund(10_001);
        List<Payment.RefundPortion> rest = p.applyRefund(8_499);

        long firstTotal = first.stream().mapToLong(r -> r.netPaise() + r.commissionPaise()).sum();
        assertThat(firstTotal).isEqualTo(10_001);
        long allNet = first.stream().mapToLong(Payment.RefundPortion::netPaise).sum()
                + rest.stream().mapToLong(Payment.RefundPortion::netPaise).sum();
        long allCommission = first.stream().mapToLong(Payment.RefundPortion::commissionPaise).sum()
                + rest.stream().mapToLong(Payment.RefundPortion::commissionPaise).sum();
        assertThat(allNet).isEqualTo(17_800);        // 3800 + 14000: each provider gives back exactly its net
        assertThat(allCommission).isEqualTo(700);    // the platform gives back exactly its commission
        assertThat(p.getSplits()).allMatch(s -> s.getRefundedPaise() == s.getGrossPaise());
    }

    @Test
    void refundAboveWhatIsLeftIsRefused() {
        Payment p = contractExamplePayment();
        p.applyRefund(18_000);

        assertThatThrownBy(() -> p.applyRefund(501)).isInstanceOf(IllegalArgumentException.class);
    }
}
