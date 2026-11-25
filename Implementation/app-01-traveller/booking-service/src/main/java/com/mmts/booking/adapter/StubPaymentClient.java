package com.mmts.booking.adapter;

import com.mmts.booking.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stand-in for Payment service. Behaves like INT-11:
 * - The same Idempotency-Key always gives back the same answer, so a retry never charges twice.
 * - Splits must add up to the amount (400 SPLIT_MISMATCH); a refund may not exceed the payment (400 REFUND_EXCEEDS_PAYMENT).
 * - Payments succeed, unless the demo asked for the next one to fail.
 */
@Component
public class StubPaymentClient implements PaymentClient {

    private final Clock clock;
    private final AtomicLong paymentSeq = new AtomicLong(9000);
    private final AtomicLong refundSeq = new AtomicLong(76);
    private final Map<String, PaymentResult> paymentsByKey = new ConcurrentHashMap<>();
    private final Map<String, PaymentResult> paymentsById = new ConcurrentHashMap<>();
    private final Map<String, RefundResult> refundsByKey = new ConcurrentHashMap<>();
    private final Map<String, Long> refundedPaise = new ConcurrentHashMap<>();
    private final AtomicReference<String> failNextReason = new AtomicReference<>();

    public StubPaymentClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized PaymentResult charge(String idempotencyKey, PaymentRequest request) {
        PaymentResult earlier = paymentsByKey.get(idempotencyKey);
        if (earlier != null) {
            return earlier;                                   // same key: same answer, no second charge
        }
        long splitTotal = request.splits().stream().mapToLong(Split::amountPaise).sum();
        if (splitTotal != request.amountPaise()) {
            throw ApiException.badRequest("SPLIT_MISMATCH",
                    "Splits add up to " + splitTotal + " but the amount is " + request.amountPaise());
        }
        String paymentId = "p-" + paymentSeq.incrementAndGet();
        String failure = failNextReason.getAndSet(null);
        PaymentResult result = new PaymentResult(paymentId, request.bookingId(), failure == null ? "CAPTURED" : "FAILED",
                request.amountPaise(), failure, clock.instant());
        paymentsByKey.put(idempotencyKey, result);
        paymentsById.put(paymentId, result);
        return result;
    }

    @Override
    public synchronized RefundResult refund(String paymentId, String idempotencyKey, RefundRequest request) {
        RefundResult earlier = refundsByKey.get(idempotencyKey);
        if (earlier != null) {
            return earlier;
        }
        PaymentResult payment = paymentsById.get(paymentId);
        if (payment == null || !payment.captured()) {
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "No captured payment " + paymentId);
        }
        long alreadyRefunded = refundedPaise.getOrDefault(paymentId, 0L);
        if (alreadyRefunded + request.amountPaise() > payment.amountPaise()) {
            throw ApiException.badRequest("REFUND_EXCEEDS_PAYMENT",
                    "Refund of " + request.amountPaise() + " is more than what is left of " + paymentId);
        }
        refundedPaise.put(paymentId, alreadyRefunded + request.amountPaise());
        RefundResult result = new RefundResult("r-" + refundSeq.incrementAndGet(), paymentId, "REFUNDED",
                request.amountPaise());
        refundsByKey.put(idempotencyKey, result);
        return result;
    }

    /** Demo control: the next payment comes back FAILED with this reason (e.g. INSUFFICIENT_BALANCE). */
    public void failNextPayment(String reason) {
        failNextReason.set(reason);
    }

    public String pendingFailure() {
        return failNextReason.get();
    }

    /** For tests: how many distinct payments were taken for a booking. */
    public long paymentsFor(String bookingId) {
        return paymentsById.values().stream().filter(p -> p.bookingId().equals(bookingId)).count();
    }

    /** For tests: total refunded for a payment. */
    public long refundedFor(String paymentId) {
        return refundedPaise.getOrDefault(paymentId, 0L);
    }

    public void reset() {
        failNextReason.set(null);
    }
}
