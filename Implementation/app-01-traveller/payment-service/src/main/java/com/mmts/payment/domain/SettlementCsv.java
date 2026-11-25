package com.mmts.payment.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The daily settlement file for the finance team (one CSV per day); Analytics service gets the same figures as the
 * SETTLEMENT_COMPLETED event on payment.events (INT-17).
 * Format (fixed in docs/CONTRACTS.md):
 * <pre>
 * settlementDate,providerId,grossPaise,commissionPaise,netPaise,paymentCount
 * 2026-11-15,DMRC,400000,20000,380000,100
 * </pre>
 * One row per provider, sorted by provider id, covering the payments captured on that day (UTC).
 */
public final class SettlementCsv {

    public static final String HEADER = "settlementDate,providerId,grossPaise,commissionPaise,netPaise,paymentCount";

    public record Row(LocalDate settlementDate, String providerId, long grossPaise, long commissionPaise,
                      long netPaise, int paymentCount) {
    }

    private SettlementCsv() {
    }

    /** Adds up the captured payments of one day, provider by provider. */
    public static List<Row> summarise(LocalDate date, List<Payment> capturedPayments) {
        Map<String, long[]> totals = new TreeMap<>();           // providerId -> {gross, commission, net}
        Map<String, Set<String>> paymentsPerProvider = new TreeMap<>();
        for (Payment payment : capturedPayments) {
            for (PaymentSplit split : payment.getSplits()) {
                long[] t = totals.computeIfAbsent(split.getProviderId(), k -> new long[3]);
                t[0] += split.getGrossPaise();
                t[1] += split.getCommissionPaise();
                t[2] += split.getNetPaise();
                paymentsPerProvider.computeIfAbsent(split.getProviderId(), k -> new HashSet<>())
                        .add(payment.paymentId());
            }
        }
        List<Row> rows = new ArrayList<>();
        totals.forEach((providerId, t) -> rows.add(
                new Row(date, providerId, t[0], t[1], t[2], paymentsPerProvider.get(providerId).size())));
        return rows;
    }

    /** The file content: the header line, then one line per row. */
    public static String format(List<Row> rows) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (Row r : rows) {
            csv.append(r.settlementDate()).append(',')
                    .append(r.providerId()).append(',')
                    .append(r.grossPaise()).append(',')
                    .append(r.commissionPaise()).append(',')
                    .append(r.netPaise()).append(',')
                    .append(r.paymentCount()).append('\n');
        }
        return csv.toString();
    }
}
