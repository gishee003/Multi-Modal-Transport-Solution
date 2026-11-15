package com.mmts.payment.api;

import com.mmts.payment.domain.SettlementCsv;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Response bodies of the finance endpoints: ledger, commission rules, payouts, settlement file, webhook. */
public final class FinanceDtos {

    private FinanceDtos() {
    }

    /**
     * Trial balance: all debits and all credits in the ledger. differencePaise must always be 0.
     * The wallet check compares the wallets' real balances with what the ledger says travellers are owed.
     */
    public record TrialBalanceResponse(long totalDebitsPaise, long totalCreditsPaise, long differencePaise,
                                       boolean balanced, long entryCount, long walletBalancesPaise,
                                       long walletLedgerPaise, boolean walletsReconciled,
                                       List<AccountBalance> accounts) {
    }

    /** balancePaise = credits - debits. */
    public record AccountBalance(String account, long debitsPaise, long creditsPaise, long balancePaise) {
    }

    public record LedgerEntryResponse(long entryId, String reference, String account, String direction,
                                      long amountPaise, String description, Instant createdAt) {
    }

    public record CommissionRulesResponse(int percentageBasisPoints, long flatFeePaise, String defaultRule,
                                          Map<String, String> ruleByProviderType, List<ProviderRule> providers) {
    }

    public record ProviderRule(String providerId, String name, String providerType, String rule,
                               String linkedAccountId) {
    }

    public record PayoutResponse(String payoutId, String runId, String providerId, long amountPaise, String status,
                                 String transferReference, String failureReason, Instant createdAt) {
    }

    public record PayoutRunResponse(String runId, String trigger, Instant ranAt, int providerCount, long totalPaise,
                                    long paidPaise, List<PayoutResponse> payouts) {
    }

    public record SettlementResponse(LocalDate settlementDate, String file, long totalGrossPaise,
                                     long totalCommissionPaise, long totalNetPaise, List<SettlementCsv.Row> rows,
                                     String csv) {
    }

    public record WebhookResponse(boolean received, String event, String matchedPaymentId) {
    }
}
