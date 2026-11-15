package com.mmts.booking.adapter;

import java.time.Instant;
import java.util.List;

/**
 * INT-11: takes money for a booking and gives it back, through APP-07 (Payment).
 * Real calls: POST /api/v1/payments and POST /api/v1/payments/{paymentId}/refunds,
 * each with an Idempotency-Key header so that a retry never charges or refunds twice.
 */
public interface PaymentClient {

    PaymentResult charge(String idempotencyKey, PaymentRequest request);

    RefundResult refund(String paymentId, String idempotencyKey, RefundRequest request);

    /** method is WALLET or GATEWAY; the splits must add up to amountPaise. */
    record PaymentRequest(String bookingId, String userId, long amountPaise, String method, List<Split> splits) {
    }

    /** How much of the payment goes to which provider. */
    record Split(String providerId, long amountPaise) {
    }

    /** status is CAPTURED or FAILED (then failureReason says why, e.g. INSUFFICIENT_BALANCE). */
    record PaymentResult(String paymentId, String bookingId, String status, long amountPaise,
                         String failureReason, Instant createdAt) {

        public boolean captured() {
            return "CAPTURED".equals(status);
        }
    }

    record RefundRequest(long amountPaise, String reason) {
    }

    record RefundResult(String refundId, String paymentId, String status, long amountPaise) {
    }
}
