package com.mmts.booking.adapter.http;

import com.mmts.booking.adapter.PaymentClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Proof of concept: INT-11 as real calls to the Payment service (replaces StubPaymentClient). */
@Component
@Primary
@Profile("poc")
public class HttpPaymentClient implements PaymentClient {

    private final RemoteCalls remote;
    private final String baseUrl;

    public HttpPaymentClient(RemoteCalls remote, @Value("${mmts.poc.payment-url}") String baseUrl) {
        this.remote = remote;
        this.baseUrl = baseUrl;
    }

    @Override
    public PaymentResult charge(String idempotencyKey, PaymentRequest request) {
        return remote.post(baseUrl + "/api/v1/payments", request, idempotencyKey, PaymentResult.class);
    }

    @Override
    public RefundResult refund(String paymentId, String idempotencyKey, RefundRequest request) {
        return remote.post(baseUrl + "/api/v1/payments/" + paymentId + "/refunds", request, idempotencyKey,
                RefundResult.class);
    }
}
