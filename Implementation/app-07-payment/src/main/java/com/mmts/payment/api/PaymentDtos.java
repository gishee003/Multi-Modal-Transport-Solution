package com.mmts.payment.api;

import com.mmts.payment.domain.Payment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/** Request and response bodies of INT-11 (trip payment and refund), exactly as in docs/CONTRACTS.md. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PaymentRequest(@NotBlank String bookingId,
                                 @NotBlank String userId,
                                 @NotNull @Positive Long amountPaise,
                                 @NotNull Payment.Method method,
                                 @NotEmpty List<@Valid @NotNull SplitRequest> splits) {
    }

    public record SplitRequest(@NotBlank String providerId, @NotNull @Positive Long amountPaise) {
    }

    /** The INT-11 response. Field names and order match the contract. */
    public record PaymentResponse(String paymentId, String bookingId, String status, long amountPaise,
                                  String failureReason, Instant createdAt) {
    }

    public record RefundRequest(@NotNull @Positive Long amountPaise, String reason) {
    }

    public record RefundResponse(String refundId, String paymentId, String status, long amountPaise) {
    }

    /** Full detail of one payment, for APP-06 and finance staff. */
    public record PaymentDetailResponse(String paymentId, String bookingId, String userId, String method,
                                        String status, long amountPaise, long refundedPaise, String failureReason,
                                        String gatewayReference, Instant gatewayConfirmedAt, Instant createdAt,
                                        List<SplitResponse> splits) {
    }

    public record SplitResponse(String providerId, long amountPaise, long commissionPaise, long netPaise,
                                String commissionRule, long refundedPaise) {
    }

    /** What a service hands back: the body, and whether it was newly made (HTTP 201) or a repeat (HTTP 200). */
    public record Outcome<T>(T body, boolean created) {
    }
}
