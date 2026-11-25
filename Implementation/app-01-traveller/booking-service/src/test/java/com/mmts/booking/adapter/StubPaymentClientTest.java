package com.mmts.booking.adapter;

import com.mmts.booking.adapter.PaymentClient.PaymentRequest;
import com.mmts.booking.adapter.PaymentClient.PaymentResult;
import com.mmts.booking.adapter.PaymentClient.RefundRequest;
import com.mmts.booking.adapter.PaymentClient.Split;
import com.mmts.booking.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Payment service stub must behave like the INT-11 contract, or the saga tests would prove nothing. */
class StubPaymentClientTest {

    private final StubPaymentClient payments = new StubPaymentClient(Clock.systemUTC());

    private static PaymentRequest request(long amount) {
        return new PaymentRequest("b-5001", "u-101", amount, "WALLET",
                List.of(new Split("DMRC", 4000), new Split("ZIPRIDE", amount - 4000)));
    }

    @Test
    void sameIdempotencyKeyChargesOnlyOnce() {
        PaymentResult first = payments.charge("b-5001-pay", request(18500));
        PaymentResult again = payments.charge("b-5001-pay", request(18500));
        assertThat(again).isEqualTo(first);
        assertThat(payments.paymentsFor("b-5001")).isEqualTo(1);
    }

    @Test
    void splitsMustAddUpToTheAmount() {
        PaymentRequest bad = new PaymentRequest("b-5002", "u-101", 18500, "WALLET", List.of(new Split("DMRC", 4000)));
        assertThatThrownBy(() -> payments.charge("b-5002-pay", bad))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "SPLIT_MISMATCH");
    }

    @Test
    void refundCannotExceedThePayment() {
        PaymentResult paid = payments.charge("b-5003-pay", request(18500));
        payments.refund(paid.paymentId(), "b-5003-refund", new RefundRequest(18500, "BOOKING_FAILED"));
        assertThatThrownBy(() -> payments.refund(paid.paymentId(), "b-5003-refund-2", new RefundRequest(1, "again")))
                .hasFieldOrPropertyWithValue("code", "REFUND_EXCEEDS_PAYMENT");
    }

    @Test
    void forcedFailureAffectsOnlyTheNextPayment() {
        payments.failNextPayment("INSUFFICIENT_BALANCE");
        PaymentResult failed = payments.charge("b-5004-pay", request(18500));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(payments.charge("b-5005-pay", request(18500)).captured()).isTrue();
    }
}
