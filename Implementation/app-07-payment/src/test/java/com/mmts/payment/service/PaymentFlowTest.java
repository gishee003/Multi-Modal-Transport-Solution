package com.mmts.payment.service;

import com.mmts.payment.adapter.StubRazorpayGateway;
import com.mmts.payment.api.FinanceDtos.LedgerEntryResponse;
import com.mmts.payment.api.FinanceDtos.TrialBalanceResponse;
import com.mmts.payment.api.PaymentDtos.Outcome;
import com.mmts.payment.api.PaymentDtos.PaymentDetailResponse;
import com.mmts.payment.api.PaymentDtos.PaymentRequest;
import com.mmts.payment.api.PaymentDtos.PaymentResponse;
import com.mmts.payment.api.PaymentDtos.RefundRequest;
import com.mmts.payment.api.PaymentDtos.RefundResponse;
import com.mmts.payment.api.PaymentDtos.SplitRequest;
import com.mmts.payment.api.WalletDtos.TopUpRequest;
import com.mmts.payment.common.error.ApiException;
import com.mmts.payment.common.events.EventPublisher;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.common.security.Role;
import com.mmts.payment.domain.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Business rules of M4.1 to M4.4 and M4.6, run against the real services and the H2 database.
 * Every test uses its own new user and booking ids, so tests do not affect each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentFlowTest {

    static final AuthenticatedUser APP_06 = new AuthenticatedUser("app-06", Set.of(Role.SERVICE));

    @Autowired PaymentService paymentService;
    @Autowired RefundService refundService;
    @Autowired WalletService walletService;
    @Autowired LedgerService ledgerService;
    @Autowired PayoutService payoutService;
    @Autowired CommissionPolicy commissionPolicy;
    @Autowired StubRazorpayGateway gateway;
    @Autowired EventPublisher eventPublisher;

    // ---- helpers -------------------------------------------------------------------------------------------

    static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A new traveller whose wallet has been topped up with the given amount. */
    String travellerWith(long paise) {
        String user = newId("t-");
        walletService.topUp(user, null, new TopUpRequest(paise, "tok_test"),
                new AuthenticatedUser(user, Set.of(Role.TRAVELLER)));
        return user;
    }

    /** The trip from CONTRACTS.md: 18500 paise, DMRC 4000 + ZIPRIDE 14500. */
    static PaymentRequest trip(String user, String booking, Payment.Method method) {
        return new PaymentRequest(booking, user, 18_500L, method,
                List.of(new SplitRequest("DMRC", 4_000L), new SplitRequest("ZIPRIDE", 14_500L)));
    }

    long balance(String user) {
        return walletService.myWallet(user).balancePaise();
    }

    void assertBooksBalance() {
        TrialBalanceResponse tb = ledgerService.trialBalance();
        assertThat(tb.differencePaise()).isZero();
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.walletsReconciled()).isTrue();
    }

    // ---- M4.2 charge once per booking ----------------------------------------------------------------------

    @Test
    void walletPaymentDebitsWalletAndSplitsByCommissionRule() {
        String user = travellerWith(50_000);
        String booking = newId("b-");

        Outcome<PaymentResponse> out = paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06);

        assertThat(out.created()).isTrue();
        assertThat(out.body().status()).isEqualTo("CAPTURED");
        assertThat(out.body().failureReason()).isNull();
        assertThat(balance(user)).isEqualTo(31_500);
        PaymentDetailResponse detail = paymentService.detail(out.body().paymentId());
        assertThat(detail.splits()).extracting("providerId", "commissionPaise", "netPaise")
                .containsExactly(tuple("DMRC", 200L, 3_800L), tuple("ZIPRIDE", 500L, 14_000L));
        List<LedgerEntryResponse> lines = ledgerService.entries("PAYMENT:" + out.body().paymentId());
        assertThat(lines).extracting("account", "direction", "amountPaise").containsExactly(
                tuple("USER_WALLET:" + user, "DEBIT", 18_500L),
                tuple("PROVIDER_PAYABLE:DMRC", "CREDIT", 3_800L),
                tuple("PROVIDER_PAYABLE:ZIPRIDE", "CREDIT", 14_000L),
                tuple("PLATFORM_REVENUE", "CREDIT", 700L));
    }

    @Test
    void insufficientBalanceGivesFailedAndMovesNoMoney() {
        String user = travellerWith(5_000);
        String booking = newId("b-");

        PaymentResponse out = paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06).body();

        assertThat(out.status()).isEqualTo("FAILED");
        assertThat(out.failureReason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(balance(user)).isEqualTo(5_000);
        assertThat(ledgerService.entries("PAYMENT:" + out.paymentId())).isEmpty();
        assertThat(eventPublisher.recentEvents()).anyMatch(e -> e.type().equals("PAYMENT_FAILED")
                && e.payload().get("paymentId").equals(out.paymentId())
                && e.payload().get("reason").equals("INSUFFICIENT_BALANCE"));
    }

    @Test
    void walletIsCreatedOnFirstUse() {
        String user = newId("t-");          // never topped up
        String booking = newId("b-");

        PaymentResponse out = paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06).body();

        assertThat(out.failureReason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(walletService.walletOf(user).balancePaise()).isZero();
    }

    @Test
    void sameIdempotencyKeyNeverChargesTwiceAndGivesTheSameBody() {
        String user = travellerWith(50_000);
        String booking = newId("b-");
        PaymentRequest request = trip(user, booking, Payment.Method.WALLET);

        Outcome<PaymentResponse> first = paymentService.charge(booking + "-pay", request, APP_06);
        Outcome<PaymentResponse> again = paymentService.charge(booking + "-pay", request, APP_06);

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(balance(user)).isEqualTo(31_500);                 // charged once only
        assertThat(ledgerService.entries("PAYMENT:" + first.body().paymentId())).hasSize(4);
    }

    @Test
    void sameKeyForADifferentRequestIsRefused() {
        String user = travellerWith(50_000);
        String booking = newId("b-");
        paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06);

        PaymentRequest different = new PaymentRequest(booking, user, 4_000L, Payment.Method.WALLET,
                List.of(new SplitRequest("DMRC", 4_000L)));

        assertThatThrownBy(() -> paymentService.charge(booking + "-pay", different, APP_06))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void splitsMustAddUpToTheAmount() {
        String user = travellerWith(50_000);
        PaymentRequest wrong = new PaymentRequest(newId("b-"), user, 18_500L, Payment.Method.WALLET,
                List.of(new SplitRequest("DMRC", 4_000L), new SplitRequest("ZIPRIDE", 14_000L)));

        assertThatThrownBy(() -> paymentService.charge(newId("k-"), wrong, APP_06))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SPLIT_MISMATCH");
        assertThat(balance(user)).isEqualTo(50_000);
    }

    @Test
    void aBookingCanBeCapturedOnlyOnceEvenWithAnotherKey() {
        String user = travellerWith(50_000);
        String booking = newId("b-");
        paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06);

        assertThatThrownBy(() -> paymentService.charge(booking + "-pay-2", trip(user, booking, Payment.Method.WALLET), APP_06))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BOOKING_ALREADY_PAID");
        assertThat(balance(user)).isEqualTo(31_500);
    }

    @Test
    void gatewayPaymentIsCapturedOrDeclined() {
        String user = newId("t-");
        String declinedBooking = newId("b-");
        gateway.declineNextCharge();

        PaymentResponse declined = paymentService.charge(declinedBooking + "-pay",
                trip(user, declinedBooking, Payment.Method.GATEWAY), APP_06).body();
        String booking = newId("b-");
        PaymentResponse captured = paymentService.charge(booking + "-pay",
                trip(user, booking, Payment.Method.GATEWAY), APP_06).body();

        assertThat(declined.status()).isEqualTo("FAILED");
        assertThat(declined.failureReason()).isEqualTo("GATEWAY_DECLINED");
        assertThat(captured.status()).isEqualTo("CAPTURED");
        assertThat(paymentService.detail(captured.paymentId()).gatewayReference()).startsWith("pay_stub_");
        assertThat(ledgerService.entries("PAYMENT:" + captured.paymentId()).get(0))
                .extracting("account", "direction").containsExactly("GATEWAY_CLEARING", "DEBIT");
    }

    // ---- M4.3 refunds -------------------------------------------------------------------------------------

    @Test
    void refundsCannotExceedTheCapturedAmount() {
        String user = travellerWith(50_000);
        String booking = newId("b-");
        String paymentId = paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06)
                .body().paymentId();

        refundService.refund(paymentId, booking + "-refund-1", new RefundRequest(10_000L, "LEG_CANCELLED"), APP_06);
        assertThatThrownBy(() -> refundService.refund(paymentId, booking + "-refund-2",
                new RefundRequest(9_000L, "TOO_MUCH"), APP_06))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REFUND_EXCEEDS_PAYMENT");
        refundService.refund(paymentId, booking + "-refund-3", new RefundRequest(8_500L, "BOOKING_FAILED"), APP_06);

        assertThat(balance(user)).isEqualTo(50_000);                 // everything came back to the wallet
        assertThat(paymentService.detail(paymentId).refundedPaise()).isEqualTo(18_500);
        assertBooksBalance();
    }

    @Test
    void sameRefundKeyRefundsOnlyOnce() {
        String user = travellerWith(50_000);
        String booking = newId("b-");
        String paymentId = paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06)
                .body().paymentId();
        RefundRequest request = new RefundRequest(18_500L, "BOOKING_FAILED");

        Outcome<RefundResponse> first = refundService.refund(paymentId, booking + "-refund", request, APP_06);
        Outcome<RefundResponse> again = refundService.refund(paymentId, booking + "-refund", request, APP_06);

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(first.body().status()).isEqualTo("REFUNDED");
        assertThat(balance(user)).isEqualTo(50_000);                 // not 68,500
    }

    @Test
    void failedOrUnknownPaymentsCannotBeRefunded() {
        String user = travellerWith(100);
        String booking = newId("b-");
        String failed = paymentService.charge(booking + "-pay", trip(user, booking, Payment.Method.WALLET), APP_06)
                .body().paymentId();

        assertThatThrownBy(() -> refundService.refund(failed, newId("k-"), new RefundRequest(100L, "X"), APP_06))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYMENT_NOT_CAPTURED");
        assertThatThrownBy(() -> refundService.refund("p-0", newId("k-"), new RefundRequest(100L, "X"), APP_06))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PAYMENT_NOT_FOUND");
    }

    // ---- M4.4 commission rules can change by event ------------------------------------------------------------

    @Test
    void pricingRulesEventChangesThePercentageCommission() {
        int before = commissionPolicy.percentageBasisPoints();
        try {
            eventPublisher.publish("pricing.rules", "PRICING_RULES_UPDATED",
                    Map.of("surgeFactor", 1.0, "commissionPercent", 10, "promotions", List.of()));

            assertThat(commissionPolicy.strategyFor("DMRC").commissionFor(4_000)).isEqualTo(400);
            assertThat(commissionPolicy.strategyFor("ZIPRIDE").commissionFor(14_500)).isEqualTo(500); // flat fee unchanged
        } finally {
            eventPublisher.publish("pricing.rules", "PRICING_RULES_UPDATED",
                    Map.of("commissionPercent", before / 100.0));
        }
        assertThat(commissionPolicy.percentageBasisPoints()).isEqualTo(before);
    }

    // ---- M4.6 the books always balance --------------------------------------------------------------------

    @Test
    void ledgerIsBalancedAfterEveryKindOfMoneyMovement() {
        assertBooksBalance();
        String user = travellerWith(50_000);                                                   // top-up
        assertBooksBalance();
        String b1 = newId("b-");
        String walletPayment = paymentService.charge(b1 + "-pay", trip(user, b1, Payment.Method.WALLET), APP_06)
                .body().paymentId();                                                         // wallet payment
        assertBooksBalance();
        String b2 = newId("b-");
        String gatewayPayment = paymentService.charge(b2 + "-pay", trip(user, b2, Payment.Method.GATEWAY), APP_06)
                .body().paymentId();                                                         // gateway payment
        assertBooksBalance();
        refundService.refund(walletPayment, b1 + "-refund", new RefundRequest(7_777L, "PARTIAL"), APP_06);
        assertBooksBalance();                                                                // partial wallet refund
        refundService.refund(gatewayPayment, b2 + "-refund", new RefundRequest(18_500L, "FULL"), APP_06);
        assertBooksBalance();                                                                // full gateway refund
        payoutService.runNow("MANUAL", PaymentEvents.SYSTEM);
        assertBooksBalance();                                                                // payout run
    }
}
