package com.mmts.payment.service;

import com.mmts.payment.adapter.PaymentGateway;
import com.mmts.payment.api.PaymentDtos.Outcome;
import com.mmts.payment.api.PaymentDtos.PaymentDetailResponse;
import com.mmts.payment.api.PaymentDtos.PaymentRequest;
import com.mmts.payment.api.PaymentDtos.PaymentResponse;
import com.mmts.payment.api.PaymentDtos.SplitRequest;
import com.mmts.payment.api.PaymentDtos.SplitResponse;
import com.mmts.payment.common.error.ApiException;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.domain.CommissionStrategy;
import com.mmts.payment.domain.LedgerAccounts;
import com.mmts.payment.domain.LedgerTransaction;
import com.mmts.payment.domain.Payment;
import com.mmts.payment.domain.PaymentSplit;
import com.mmts.payment.domain.Wallet;
import com.mmts.payment.domain.WalletTransaction;
import com.mmts.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * "Charge once per booking" (M4.2, INT-11). Called by Booking service with an Idempotency-Key header.
 */
@Service
public class PaymentService {

    public static final int MAX_KEY_LENGTH = 100;

    private final PaymentRepository payments;
    private final WalletService walletService;
    private final CommissionPolicy commissionPolicy;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final PaymentEvents events;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, WalletService walletService, CommissionPolicy commissionPolicy,
                          PaymentGateway gateway, LedgerService ledger, PaymentEvents events, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.walletService = walletService;
        this.commissionPolicy = commissionPolicy;
        this.gateway = gateway;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 1. Check the key is present and the splits add up to the amount.
     * 2. Seen this key before? Return the stored result (HTTP 200), never charge again.
     * 3. Otherwise charge in one database transaction (wallet or gateway, splits, ledger) and answer 201.
     * 4. After the transaction has committed, publish PAYMENT_CAPTURED or PAYMENT_FAILED and an audit event.
     */
    public Outcome<PaymentResponse> charge(String idempotencyKey, PaymentRequest request, AuthenticatedUser actor) {
        requireKey(idempotencyKey);
        checkSplits(request);

        var earlier = payments.findByIdempotencyKey(idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), request);
        }

        Payment payment;
        try {
            payment = OptimisticRetry.run(() -> tx.execute(status -> chargeInTransaction(idempotencyKey, request)));
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key arrived together; the unique key let only one in. Return its result.
            return replay(payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e), request);
        }

        if (payment.isCaptured()) {
            events.captured(payment.paymentId(), payment.getBookingId(), payment.getAmountPaise());
        } else {
            events.failed(payment.paymentId(), payment.getBookingId(), payment.getFailureReason());
        }
        events.audit(actor, "PAYMENT_CHARGE", "PAYMENT", payment.paymentId(), payment.getStatus().name());
        return new Outcome<>(toResponse(payment), true);
    }

    private Payment chargeInTransaction(String idempotencyKey, PaymentRequest request) {
        if (payments.existsByBookingIdAndStatus(request.bookingId(), Payment.Status.CAPTURED)) {
            throw ApiException.conflict("BOOKING_ALREADY_PAID",
                    "Booking " + request.bookingId() + " already has a captured payment");
        }
        long amount = request.amountPaise();
        Payment payment = new Payment(idempotencyKey, request.bookingId(), request.userId(), amount,
                request.method(), now());

        // Split: each provider's share minus the commission chosen by the Strategy for that provider.
        for (SplitRequest split : request.splits()) {
            CommissionStrategy rule = commissionPolicy.strategyFor(split.providerId());
            payment.addSplit(split.providerId(), split.amountPaise(), rule.commissionFor(split.amountPaise()),
                    rule.describe());
        }

        Wallet wallet = null;
        if (request.method() == Payment.Method.WALLET) {
            wallet = walletService.getOrCreate(request.userId());
            if (wallet.canPay(amount)) {
                wallet.debit(amount, now());
                payment.markCaptured(null);
            } else {
                payment.markFailed("INSUFFICIENT_BALANCE");
            }
        } else {
            // The traveller's saved gateway token (a reference held by Razorpay), never card details.
            var result = gateway.charge("tok_saved_" + request.userId(), amount, "booking:" + request.bookingId());
            if (result.captured()) {
                payment.markCaptured(result.gatewayPaymentId());
            } else {
                payment.markFailed(result.declineReason());
            }
        }
        payments.save(payment);

        if (payment.isCaptured()) {
            String source;
            if (wallet != null) {
                walletService.recordMovement(wallet, WalletTransaction.Type.PAYMENT, amount, payment.paymentId(), null);
                source = LedgerAccounts.userWallet(request.userId());
            } else {
                source = LedgerAccounts.GATEWAY_CLEARING;
            }
            LedgerTransaction books = LedgerTransaction.of("PAYMENT:" + payment.paymentId(),
                    "Trip payment for booking " + request.bookingId()).debit(source, amount);
            for (PaymentSplit split : payment.getSplits()) {
                books.credit(LedgerAccounts.providerPayable(split.getProviderId()), split.getNetPaise());
            }
            books.credit(LedgerAccounts.PLATFORM_REVENUE, payment.totalCommissionPaise());
            ledger.post(books);
        }
        return payment;
    }

    @Transactional(readOnly = true)
    public PaymentDetailResponse detail(String paymentId) {
        Payment p = payments.findById(Payment.parseId(paymentId))
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "No payment with id " + paymentId));
        return new PaymentDetailResponse(p.paymentId(), p.getBookingId(), p.getUserId(), p.getMethod().name(),
                p.getStatus().name(), p.getAmountPaise(), p.getRefundedPaise(), p.getFailureReason(),
                p.getGatewayReference(), p.getGatewayConfirmedAt(), p.getCreatedAt(),
                p.getSplits().stream().map(s -> new SplitResponse(s.getProviderId(), s.getGrossPaise(),
                        s.getCommissionPaise(), s.getNetPaise(), s.getCommissionRule(), s.getRefundedPaise())).toList());
    }

    /** Same key again: must be the same request, and gets exactly the same body back. */
    private Outcome<PaymentResponse> replay(Payment earlier, PaymentRequest request) {
        boolean same = earlier.getBookingId().equals(request.bookingId())
                && earlier.getUserId().equals(request.userId())
                && earlier.getAmountPaise() == request.amountPaise()
                && earlier.getMethod() == request.method();
        if (!same) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different payment request");
        }
        return new Outcome<>(toResponse(earlier), false);
    }

    private static void checkSplits(PaymentRequest request) {
        long sum = 0;
        for (SplitRequest split : request.splits()) {
            sum = Math.addExact(sum, split.amountPaise());
        }
        if (sum != request.amountPaise()) {
            throw ApiException.badRequest("SPLIT_MISMATCH",
                    "The splits add up to " + sum + " paise but amountPaise is " + request.amountPaise());
        }
    }

    static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID",
                    "The Idempotency-Key may be at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    static PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.paymentId(), p.getBookingId(), p.getStatus().name(), p.getAmountPaise(),
                p.getFailureReason(), p.getCreatedAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
