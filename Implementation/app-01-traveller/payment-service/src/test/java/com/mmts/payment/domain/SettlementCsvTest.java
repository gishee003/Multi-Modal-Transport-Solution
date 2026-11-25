package com.mmts.payment.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The settlement file has exactly the agreed columns, one row per provider. */
class SettlementCsvTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-15");

    private static Payment payment(long id, String... providerAndAmount) {
        long total = 0;
        for (int i = 1; i < providerAndAmount.length; i += 2) {
            total += Long.parseLong(providerAndAmount[i]);
        }
        Payment p = new Payment("k-" + id, "b-" + id, "u-1", total, Payment.Method.WALLET,
                Instant.parse("2026-11-15T10:00:00Z"));
        ReflectionTestUtils.setField(p, "id", id);
        for (int i = 0; i < providerAndAmount.length; i += 2) {
            long gross = Long.parseLong(providerAndAmount[i + 1]);
            p.addSplit(providerAndAmount[i], gross, new PercentageCommission(500).commissionFor(gross), "PERCENTAGE");
        }
        p.markCaptured(null);
        return p;
    }

    @Test
    void rowsAreAddedUpPerProviderAndWrittenInTheAgreedFormat() {
        List<Payment> payments = List.of(
                payment(1, "DMRC", "4000", "DTC", "2000"),
                payment(2, "DMRC", "6000"),
                payment(3, "DMRC", "1000", "DMRC", "1000"));   // two metro legs in one payment count once

        String csv = SettlementCsv.format(SettlementCsv.summarise(DAY, payments));

        assertThat(csv).isEqualTo("""
                settlementDate,providerId,grossPaise,commissionPaise,netPaise,paymentCount
                2026-11-15,DMRC,12000,600,11400,3
                2026-11-15,DTC,2000,100,1900,1
                """);
    }

    @Test
    void aDayWithoutPaymentsHasOnlyTheHeader() {
        assertThat(SettlementCsv.format(SettlementCsv.summarise(DAY, List.of())))
                .isEqualTo(SettlementCsv.HEADER + "\n");
    }
}
