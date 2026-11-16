package com.mmts.payment.service;

import com.mmts.payment.api.FinanceDtos.AccountBalance;
import com.mmts.payment.api.FinanceDtos.LedgerEntryResponse;
import com.mmts.payment.api.FinanceDtos.TrialBalanceResponse;
import com.mmts.payment.domain.LedgerAccounts;
import com.mmts.payment.domain.LedgerEntry;
import com.mmts.payment.domain.LedgerTransaction;
import com.mmts.payment.repository.AccountTotal;
import com.mmts.payment.repository.LedgerEntryRepository;
import com.mmts.payment.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The double-entry ledger (M4.6): every money movement is saved as debit and credit lines that balance.
 * Lines are only ever added, never changed (see LedgerEntry).
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository entries;
    private final WalletRepository wallets;
    private final Clock clock;

    public LedgerService(LedgerEntryRepository entries, WalletRepository wallets, Clock clock) {
        this.entries = entries;
        this.wallets = wallets;
        this.clock = clock;
    }

    /** Saves one balanced movement. Called inside the caller's transaction, so money and books change together. */
    @Transactional
    public void post(LedgerTransaction transaction) {
        entries.saveAll(transaction.toEntries(clock.instant().truncatedTo(ChronoUnit.SECONDS)));
    }

    /** Adds up every account. Total debits must equal total credits (difference 0), whatever has happened. */
    @Transactional(readOnly = true)
    public TrialBalanceResponse trialBalance() {
        List<AccountTotal> totals = entries.totalsByAccount();
        long debits = 0;
        long credits = 0;
        long walletLedger = 0;
        for (AccountTotal t : totals) {
            debits = Math.addExact(debits, t.debitsPaise());
            credits = Math.addExact(credits, t.creditsPaise());
            if (t.account().startsWith(LedgerAccounts.USER_WALLET_PREFIX)) {
                walletLedger += t.creditBalancePaise();
            }
        }
        long walletBalances = wallets.totalBalancePaise();
        List<AccountBalance> accounts = totals.stream()
                .map(t -> new AccountBalance(t.account(), t.debitsPaise(), t.creditsPaise(), t.creditBalancePaise()))
                .toList();
        return new TrialBalanceResponse(debits, credits, debits - credits, debits == credits, entries.count(),
                walletBalances, walletLedger, walletBalances == walletLedger, accounts);
    }

    /** The lines of one movement (e.g. "PAYMENT:p-9001"), or the latest 100 lines if no reference is given. */
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> entries(String reference) {
        List<LedgerEntry> found = reference == null || reference.isBlank()
                ? entries.findTop100ByOrderByIdDesc()
                : entries.findByReferenceOrderByIdAsc(reference);
        return found.stream().map(e -> new LedgerEntryResponse(e.getId(), e.getReference(), e.getAccount(),
                e.getDirection().name(), e.getAmountPaise(), e.getDescription(), e.getCreatedAt())).toList();
    }

    /** Money still owed to each provider: credits minus debits of PROVIDER_PAYABLE:<providerId>. */
    @Transactional(readOnly = true)
    public Map<String, Long> providerPayables() {
        Map<String, Long> owed = new TreeMap<>();
        for (AccountTotal t : entries.totalsByAccount()) {
            if (t.account().startsWith(LedgerAccounts.PROVIDER_PAYABLE_PREFIX)) {
                owed.put(t.account().substring(LedgerAccounts.PROVIDER_PAYABLE_PREFIX.length()), t.creditBalancePaise());
            }
        }
        return owed;
    }
}
