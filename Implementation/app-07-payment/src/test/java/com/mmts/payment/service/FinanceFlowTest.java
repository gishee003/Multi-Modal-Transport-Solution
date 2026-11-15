package com.mmts.payment.service;

import com.mmts.payment.api.FinanceDtos.PayoutResponse;
import com.mmts.payment.api.FinanceDtos.PayoutRunResponse;
import com.mmts.payment.api.FinanceDtos.SettlementResponse;
import com.mmts.payment.api.PaymentDtos.PaymentRequest;
import com.mmts.payment.api.PaymentDtos.SplitRequest;
import com.mmts.payment.domain.LedgerEntry;
import com.mmts.payment.domain.Payment;
import com.mmts.payment.domain.SettlementCsv;
import com.mmts.payment.domain.Wallet;
import com.mmts.payment.repository.LedgerEntryRepository;
import com.mmts.payment.repository.PaymentRepository;
import com.mmts.payment.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.mmts.payment.service.PaymentFlowTest.APP_06;
import static com.mmts.payment.service.PaymentFlowTest.newId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Payouts (M4.5), the settlement file (INT-20), the append-only ledger and optimistic locking. */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceFlowTest {

    @Autowired PaymentService paymentService;
    @Autowired PayoutService payoutService;
    @Autowired SettlementService settlementService;
    @Autowired LedgerService ledgerService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    /** A captured gateway payment for the given providers and amounts (no wallet needed). */
    String gatewayPayment(Map<String, Long> splits) {
        String booking = newId("b-");
        long total = splits.values().stream().mapToLong(Long::longValue).sum();
        List<SplitRequest> list = splits.entrySet().stream().map(e -> new SplitRequest(e.getKey(), e.getValue())).toList();
        return paymentService.charge(booking + "-pay",
                new PaymentRequest(booking, newId("t-"), total, Payment.Method.GATEWAY, list), APP_06).body().paymentId();
    }

    // ---- M4.5 payouts -------------------------------------------------------------------------------------

    @Test
    void payoutRunPaysEachProviderExactlyWhatTheLedgerSaysItIsOwed() {
        gatewayPayment(Map.of("DMRC", 4_000L, "ZIPRIDE", 14_500L));
        Map<String, Long> owedBefore = ledgerService.providerPayables();
        long expectedTotal = owedBefore.values().stream().filter(v -> v > 0).mapToLong(Long::longValue).sum();

        PayoutRunResponse run = payoutService.runNow("MANUAL", PaymentEvents.SYSTEM);

        assertThat(run.totalPaise()).isEqualTo(expectedTotal);                       // step 2: total
        assertThat(run.payouts()).extracting(PayoutResponse::amountPaise)
                .allMatch(amount -> amount > 0);
        PayoutResponse dmrc = run.payouts().stream().filter(p -> p.providerId().equals("DMRC")).findFirst().orElseThrow();
        assertThat(dmrc.amountPaise()).isEqualTo(owedBefore.get("DMRC"));
        assertThat(dmrc.status()).isEqualTo("PAID");
        assertThat(dmrc.transferReference()).startsWith("trf_stub_");
        assertThat(ledgerService.providerPayables().get("DMRC")).isZero();           // nothing owed any more
        assertThat(ledgerService.providerPayables().get("ZIPRIDE")).isZero();
        assertThat(ledgerService.trialBalance().differencePaise()).isZero();
    }

    @Test
    void providerWithoutLinkedAccountIsNotPaidAndStaysOwed() {
        String unknownProvider = newId("NEWCO-");
        gatewayPayment(Map.of(unknownProvider, 10_000L));                             // default 5%: owed 9500

        PayoutRunResponse run = payoutService.runNow("MANUAL", PaymentEvents.SYSTEM);

        PayoutResponse payout = run.payouts().stream().filter(p -> p.providerId().equals(unknownProvider))
                .findFirst().orElseThrow();
        assertThat(payout.status()).isEqualTo("FAILED");
        assertThat(payout.failureReason()).isEqualTo("NO_LINKED_ACCOUNT");
        assertThat(run.paidPaise()).isEqualTo(run.totalPaise() - 9_500);
        assertThat(ledgerService.providerPayables().get(unknownProvider)).isEqualTo(9_500);
    }

    // ---- INT-20 settlement file ---------------------------------------------------------------------------

    @Test
    void settlementFileAddsUpTodaysCapturedPayments() throws Exception {
        String provider = newId("SETL-");
        gatewayPayment(Map.of(provider, 10_000L));
        gatewayPayment(Map.of(provider, 6_000L, "DTC", 2_000L));
        LocalDate today = LocalDate.now(clock);

        SettlementResponse result = settlementService.export(today, PaymentEvents.SYSTEM);

        Path file = Path.of(result.file());
        assertThat(file.getFileName().toString()).isEqualTo("settlement-" + today + ".csv");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines.get(0)).isEqualTo(SettlementCsv.HEADER);
        assertThat(lines).contains(today + "," + provider + ",16000,800,15200,2");
        assertThat(result.rows()).allMatch(r -> r.commissionPaise() + r.netPaise() == r.grossPaise());
        Instant from = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        long capturedToday = paymentRepository.findInPeriod(Payment.Status.CAPTURED, from, from.plusSeconds(86_400))
                .stream().mapToLong(Payment::getAmountPaise).sum();
        assertThat(result.totalGrossPaise()).isEqualTo(capturedToday);              // file total = payments total
    }

    // ---- M4.6 append-only ledger --------------------------------------------------------------------------

    @Test
    void ledgerEntriesCannotBeChangedOrDeleted() {
        gatewayPayment(Map.of("DTC", 1_000L));
        long entryId = ledgerService.entries(null).get(0).entryId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            LedgerEntry entry = entityManager.find(LedgerEntry.class, entryId);
            ReflectionTestUtils.setField(entry, "amountPaise", 1L);   // try to rewrite history
            entityManager.flush();
        })).satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e)).hasMessageContaining("append-only"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                entityManager.remove(entityManager.find(LedgerEntry.class, entryId))))
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e)).hasMessageContaining("append-only"));

        assertThat(Arrays.stream(LedgerEntryRepository.class.getMethods()).map(m -> m.getName()))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update"));
        assertThat(ledgerService.entries(null).stream().filter(e -> e.entryId() == entryId).findFirst().orElseThrow()
                .amountPaise()).isNotEqualTo(1L);
    }

    // ---- M4.1 optimistic locking --------------------------------------------------------------------------

    @Test
    void twoChangesFromTheSameOldWalletCopyCannotBothBeSaved() {
        String user = newId("t-");
        walletRepository.save(new Wallet(user, clock.instant()));
        Wallet first = walletRepository.findById(user).orElseThrow();      // both copies have version 0
        Wallet second = walletRepository.findById(user).orElseThrow();

        first.credit(1_000, clock.instant());
        walletRepository.save(first);                                       // version becomes 1
        second.credit(2_000, clock.instant());

        assertThatThrownBy(() -> walletRepository.save(second))            // still says version 0: refused
                .isInstanceOf(OptimisticLockingFailureException.class);
        Wallet saved = walletRepository.findById(user).orElseThrow();
        assertThat(saved.getBalancePaise()).isEqualTo(1_000);           // only the first change was kept

        // This test changed a wallet directly (not through a ledger-posting service); undo it so the
        // wallet / ledger reconciliation checked by other tests stays true.
        saved.debit(1_000, clock.instant());
        walletRepository.save(saved);
    }
}
