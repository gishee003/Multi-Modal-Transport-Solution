package com.mmts.payment.service;

import com.mmts.payment.adapter.PayoutGateway.TransferResult;
import com.mmts.payment.api.FinanceDtos.PayoutResponse;
import com.mmts.payment.api.FinanceDtos.PayoutRunResponse;
import com.mmts.payment.domain.Payout;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Template Method pattern for a payout run (M4.5).
 * {@link #run} fixes the order of the steps once: collect → total → transfer → record.
 * A subclass fills in how each step is done, but cannot change the order or skip one.
 * (A weekly payout, or one for a single provider, would be another subclass with the same skeleton.)
 */
public abstract class PayoutJobTemplate {

    /** One provider to pay in this run. */
    public record PayoutItem(String providerId, long amountPaise, String linkedAccountId) {
    }

    private final Clock clock;

    protected PayoutJobTemplate(Clock clock) {
        this.clock = clock;
    }

    /** The template method. It is final so the steps always happen in this order. */
    public final PayoutRunResponse run(String trigger) {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        List<PayoutItem> items = collect();                                 // 1. who is owed what
        long total = total(items);                                         // 2. add it up
        List<PayoutResponse> results = new ArrayList<>();
        for (PayoutItem item : items) {
            TransferResult transfer = transfer(item, runId);               // 3. send the money
            results.add(record(item, transfer, runId));                    // 4. write it down
        }
        long paid = results.stream().filter(r -> Payout.PAID.equals(r.status()))
                .mapToLong(PayoutResponse::amountPaise).sum();
        return new PayoutRunResponse(runId, trigger, clock.instant().truncatedTo(ChronoUnit.SECONDS), items.size(),
                total, paid, results);
    }

    /** Step 1: the providers to pay and how much. */
    protected abstract List<PayoutItem> collect();

    /** Step 2: the total of this run. A hook with a default that subclasses may replace. */
    protected long total(List<PayoutItem> items) {
        return items.stream().mapToLong(PayoutItem::amountPaise).reduce(0, Math::addExact);
    }

    /** Step 3: send one transfer through the payout gateway. */
    protected abstract TransferResult transfer(PayoutItem item, String runId);

    /** Step 4: save the result (and, if paid, the ledger lines). */
    protected abstract PayoutResponse record(PayoutItem item, TransferResult transfer, String runId);
}
