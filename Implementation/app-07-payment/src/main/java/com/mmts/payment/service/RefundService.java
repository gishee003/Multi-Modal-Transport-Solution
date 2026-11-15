package com.mmts.payment.service;

import com.mmts.payment.adapter.PaymentGateway;
import com.mmts.payment.api.PaymentDtos.Outcome;
import com.mmts.payment.api.PaymentDtos.RefundRequest;
import com.mmts.payment.api.PaymentDtos.RefundResponse;
import com.mmts.payment.common.error.ApiException;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.domain.LedgerAccounts;
import com.mmts.payment.domain.LedgerTransaction;
import com.mmts.payment.domain.Payment;
import com.mmts.payment.domain.Refund;
import com.mmts.payment.domain.Wallet;
import com.mmts.payment.domain.WalletTransaction;
import com.mmts.payment.repository.PaymentRepository;
import com.mmts.payment.repository.RefundRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Refunds (M4.3, INT-11): give back part or all of a captured payment, once per Idempotency-Key.
 * The money goes back the way it came: to the wallet for WALLET payments, to the card / UPI for GATEWAY payments.
 */
@Service
public class RefundService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final WalletService walletService;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final PaymentEvents events;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RefundService(RefundRepository refunds, PaymentRepository payments, WalletService walletService,
                         PaymentGateway gateway, LedgerService ledger, PaymentEvents events, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.refunds = refunds;
        this.payments = payments;
        this.walletService = walletService;
        this.gateway = gateway;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 1. Seen this key before? Return the stored refund (HTTP 200).
     * 2. Load the payment; it must be CAPTURED, and the refund may not exceed what is left to refund.
     * 3. In one transaction: share the refund over the providers, give the money back, post the reversing ledger lines.
     * 4. Publish PAYMENT_REFUNDED and an audit event.
     */
    public Outcome<RefundResponse> refund(String paymentId, String idempotencyKey, RefundRequest request,
                                          AuthenticatedUser actor) {
        PaymentService.requireKey(idempotencyKey);
        var earlier = refunds.findByIdempotencyKey(idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), paymentId, request);
        }
        Refund refund;
        try {
            refund = OptimisticRetry.run(() -> tx.execute(status -> refundInTransaction(paymentId, idempotencyKey,
                    request)));
        } catch (DataIntegrityViolationException e) {
            return replay(refunds.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e), paymentId, request);
        }
        events.refunded(refund.refundId(), refund.getPaymentId(), refund.getBookingId(), refund.getAmountPaise());
        events.audit(actor, "PAYMENT_REFUND", "PAYMENT", refund.getPaymentId(), "SUCCESS");
        return new Outcome<>(toResponse(refund), true);
    }

    private Refund refundInTransaction(String paymentId, String idempotencyKey, RefundRequest request) {
        Payment payment = payments.findById(Payment.parseId(paymentId))
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "No payment with id " + paymentId));
        if (!payment.isCaptured()) {
            throw ApiException.conflict("PAYMENT_NOT_CAPTURED", "Only a captured payment can be refunded");
        }
        long amount = request.amountPaise();
        if (amount > payment.refundablePaise()) {
            throw ApiException.badRequest("REFUND_EXCEEDS_PAYMENT", "At most " + payment.refundablePaise()
                    + " paise of payment " + paymentId + " can still be refunded");
        }
        var portions = payment.applyRefund(amount);
        String reason = request.reason() == null || request.reason().isBlank() ? "UNSPECIFIED" : request.reason();
        Refund refund = refunds.save(new Refund(idempotencyKey, payment.paymentId(), payment.getBookingId(), amount,
                reason, now()));

        String destination;
        if (payment.getMethod() == Payment.Method.WALLET) {
            Wallet wallet = walletService.getOrCreate(payment.getUserId());
            wallet.credit(amount, now());
            walletService.recordMovement(wallet, WalletTransaction.Type.REFUND, amount, refund.refundId(), null);
            destination = LedgerAccounts.userWallet(payment.getUserId());
        } else {
            gateway.refund(payment.getGatewayReference(), amount, refund.refundId());
            destination = LedgerAccounts.GATEWAY_CLEARING;
        }

        // Reverse the original lines: providers and the platform give back their parts.
        LedgerTransaction books = LedgerTransaction.of("REFUND:" + refund.refundId(),
                "Refund of " + payment.paymentId() + " (" + reason + ")");
        long commissionBack = 0;
        for (Payment.RefundPortion portion : portions) {
            books.debit(LedgerAccounts.providerPayable(portion.providerId()), portion.netPaise());
            commissionBack += portion.commissionPaise();
        }
        books.debit(LedgerAccounts.PLATFORM_REVENUE, commissionBack).credit(destination, amount);
        ledger.post(books);
        return refund;
    }

    private Outcome<RefundResponse> replay(Refund earlier, String paymentId, RefundRequest request) {
        if (!earlier.getPaymentId().equals(paymentId) || earlier.getAmountPaise() != request.amountPaise()) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different refund");
        }
        return new Outcome<>(toResponse(earlier), false);
    }

    private static RefundResponse toResponse(Refund r) {
        return new RefundResponse(r.refundId(), r.getPaymentId(), r.getStatus(), r.getAmountPaise());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
