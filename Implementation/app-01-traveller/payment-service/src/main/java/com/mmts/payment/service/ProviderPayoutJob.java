package com.mmts.payment.service;

import com.mmts.payment.adapter.PayoutGateway;
import com.mmts.payment.adapter.PayoutGateway.TransferResult;
import com.mmts.payment.api.FinanceDtos.PayoutResponse;
import com.mmts.payment.domain.LedgerAccounts;
import com.mmts.payment.domain.LedgerTransaction;
import com.mmts.payment.domain.Payout;
import com.mmts.payment.domain.Provider;
import com.mmts.payment.repository.PayoutRepository;
import com.mmts.payment.repository.ProviderRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The daily provider payout: pays every provider what the ledger says it is owed.
 *
 * This class has no @Transactional of its own (the final template method could not be wrapped by Spring);
 * {@link PayoutService} runs it inside one transaction.
 */
@Component
public class ProviderPayoutJob extends PayoutJobTemplate {

    private final LedgerService ledger;
    private final ProviderRepository providers;
    private final PayoutRepository payouts;
    private final PayoutGateway payoutGateway;
    private final Clock clock;

    public ProviderPayoutJob(LedgerService ledger, ProviderRepository providers, PayoutRepository payouts,
                             PayoutGateway payoutGateway, Clock clock) {
        super(clock);
        this.ledger = ledger;
        this.providers = providers;
        this.payouts = payouts;
        this.payoutGateway = payoutGateway;
        this.clock = clock;
    }

    /** Every PROVIDER_PAYABLE account with money in it. (A negative balance, after a late refund, waits.) */
    @Override
    protected List<PayoutItem> collect() {
        return ledger.providerPayables().entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new PayoutItem(e.getKey(), e.getValue(),
                        providers.findById(e.getKey()).map(Provider::getLinkedAccountId).orElse(null)))
                .toList();
    }

    @Override
    protected TransferResult transfer(PayoutItem item, String runId) {
        return payoutGateway.transfer(item.linkedAccountId(), item.amountPaise(),
                "payout:" + runId + ":" + item.providerId());
    }

    /** Saves the payout. Only a PAID payout moves the money in the books from "owed" to "settled". */
    @Override
    protected PayoutResponse record(PayoutItem item, TransferResult transfer, String runId) {
        Payout payout = payouts.save(new Payout(runId, item.providerId(), item.amountPaise(),
                transfer.success() ? Payout.PAID : Payout.FAILED, transfer.transferId(), transfer.failureReason(),
                clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        if (transfer.success()) {
            ledger.post(LedgerTransaction.of("PAYOUT:" + payout.payoutId(),
                            "Payout to " + item.providerId() + " via transfer " + transfer.transferId())
                    .debit(LedgerAccounts.providerPayable(item.providerId()), item.amountPaise())
                    .credit(LedgerAccounts.providerSettled(item.providerId()), item.amountPaise()));
        }
        return PayoutService.toResponse(payout);
    }
}
