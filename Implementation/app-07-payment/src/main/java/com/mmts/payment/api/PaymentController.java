package com.mmts.payment.api;

import com.mmts.payment.api.PaymentDtos.Outcome;
import com.mmts.payment.api.PaymentDtos.PaymentDetailResponse;
import com.mmts.payment.api.PaymentDtos.PaymentRequest;
import com.mmts.payment.api.PaymentDtos.PaymentResponse;
import com.mmts.payment.api.PaymentDtos.RefundRequest;
import com.mmts.payment.api.PaymentDtos.RefundResponse;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.service.PaymentService;
import com.mmts.payment.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** INT-11 Trip payment and refund, called by APP-06 (role SERVICE). See docs/CONTRACTS.md. */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments (INT-11)")
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;

    public PaymentController(PaymentService paymentService, RefundService refundService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SERVICE')")
    @Operation(summary = "Charge once per booking",
            description = "Header Idempotency-Key: <bookingId>-pay. 201 when new; 200 with the same body on a repeat.")
    public ResponseEntity<PaymentResponse> charge(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return respond(paymentService.charge(idempotencyKey, request, caller));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN','FINANCE')")
    @Operation(summary = "One payment with its splits, commission and refunds so far")
    public PaymentDetailResponse detail(@PathVariable String paymentId) {
        return paymentService.detail(paymentId);
    }

    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("hasAnyRole('SERVICE','SUPPORT')")
    @Operation(summary = "Refund all or part of a payment",
            description = "Header Idempotency-Key: <bookingId>-refund. 201 when new; 200 with the same body on a repeat.")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable String paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RefundRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return respond(refundService.refund(paymentId, idempotencyKey, request, caller));
    }

    private static <T> ResponseEntity<T> respond(Outcome<T> outcome) {
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.body());
    }
}
