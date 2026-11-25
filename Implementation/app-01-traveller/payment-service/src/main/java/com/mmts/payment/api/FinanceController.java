package com.mmts.payment.api;

import com.mmts.payment.api.FinanceDtos.CommissionRulesResponse;
import com.mmts.payment.api.FinanceDtos.LedgerEntryResponse;
import com.mmts.payment.api.FinanceDtos.PayoutResponse;
import com.mmts.payment.api.FinanceDtos.PayoutRunResponse;
import com.mmts.payment.api.FinanceDtos.SettlementResponse;
import com.mmts.payment.api.FinanceDtos.TrialBalanceResponse;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.service.CommissionPolicy;
import com.mmts.payment.service.LedgerService;
import com.mmts.payment.service.PayoutService;
import com.mmts.payment.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Finance endpoints (FINANCE and ADMIN): ledger, commission rules, payouts (M4.5) and the daily settlement (event for Analytics, CSV for finance). */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
@Tag(name = "Finance: ledger, payouts, settlement")
public class FinanceController {

    private final LedgerService ledgerService;
    private final CommissionPolicy commissionPolicy;
    private final PayoutService payoutService;
    private final SettlementService settlementService;
    private final Clock clock;

    public FinanceController(LedgerService ledgerService, CommissionPolicy commissionPolicy,
                             PayoutService payoutService, SettlementService settlementService, Clock clock) {
        this.ledgerService = ledgerService;
        this.commissionPolicy = commissionPolicy;
        this.payoutService = payoutService;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    @GetMapping("/ledger/trial-balance")
    @Operation(summary = "Trial balance: total debits = total credits, and wallets match the ledger")
    public TrialBalanceResponse trialBalance() {
        return ledgerService.trialBalance();
    }

    @GetMapping("/ledger/entries")
    @Operation(summary = "Ledger lines of one movement (e.g. reference=PAYMENT:p-9001), or the latest 100")
    public List<LedgerEntryResponse> entries(@RequestParam(required = false) String reference) {
        return ledgerService.entries(reference);
    }

    @GetMapping("/commission-rules")
    @Operation(summary = "Commission rules in force and the rule each provider gets")
    public CommissionRulesResponse commissionRules() {
        return commissionPolicy.describe();
    }

    @PostMapping("/payouts/run")
    @Operation(summary = "Run the provider payout job now (it also runs daily on its own)")
    public PayoutRunResponse runPayouts(@AuthenticationPrincipal AuthenticatedUser user) {
        return payoutService.runNow("MANUAL", user);
    }

    @GetMapping("/payouts")
    @Operation(summary = "Latest payouts (newest first)")
    public List<PayoutResponse> payouts() {
        return payoutService.recent();
    }

    @PostMapping("/settlements/export")
    @Operation(summary = "Write exports/settlement-YYYY-MM-DD.csv for a day (default: today, UTC)")
    public SettlementResponse exportSettlement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return settlementService.export(date != null ? date : LocalDate.now(clock), user);
    }
}
