package com.mmts.booking.service.saga;

import com.mmts.booking.adapter.PaymentClient;
import com.mmts.booking.adapter.PaymentClient.PaymentRequest;
import com.mmts.booking.adapter.PaymentClient.PaymentResult;
import com.mmts.booking.adapter.PaymentClient.RefundRequest;
import com.mmts.booking.adapter.PaymentClient.RefundResult;
import com.mmts.booking.adapter.PaymentClient.Split;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.BookingStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 3: charge the traveller once for the whole journey (INT-11), split between the providers.
 * The Idempotency-Key "&lt;bookingId&gt;-pay" means a retry of this call can never charge twice.
 * Undo: refund the payment (key "&lt;bookingId&gt;-refund", so the refund can never happen twice either).
 */
@Component
public class ChargePaymentStep implements SagaStep {

    private final PaymentClient payments;

    public ChargePaymentStep(PaymentClient payments) {
        this.payments = payments;
    }

    @Override
    public String name() {
        return "CHARGE_PAYMENT";
    }

    @Override
    public String execute(SagaContext context) {
        Booking booking = context.booking();
        PaymentResult result;
        try {
            result = payments.charge(booking.getId() + "-pay", new PaymentRequest(booking.getId(), booking.getUserId(),
                    booking.getTotalPaise(), booking.getPaymentMethod(), splits(booking)));
        } catch (ApiException e) {
            throw new StepFailed("PAYMENT_FAILED", e.getCode());
        }
        if (!result.captured()) {
            throw new StepFailed("PAYMENT_FAILED", result.failureReason());
        }
        booking.recordPayment(result.paymentId());
        booking.moveTo(BookingStatus.PAID, context.now());
        return "payment " + result.paymentId() + " captured, " + result.amountPaise() + " paise";
    }

    @Override
    public String undo(SagaContext context) {
        Booking booking = context.booking();
        if (booking.getPaymentId() == null || booking.getRefundId() != null) {
            return null;                                   // nothing was charged, or already refunded
        }
        RefundResult refund = payments.refund(booking.getPaymentId(), booking.getId() + "-refund",
                new RefundRequest(booking.getTotalPaise(), context.refundReason()));
        booking.recordRefund(refund.refundId());
        return "refund " + refund.refundId() + " of " + refund.amountPaise() + " paise";
    }

    /** One split per provider: how much of the total each provider gets. */
    private static List<Split> splits(Booking booking) {
        Map<String, Long> perProvider = new LinkedHashMap<>();
        for (BookingLeg leg : booking.getLegs()) {
            if (leg.getMode().needsProvider()) {
                perProvider.merge(leg.getProviderId(), leg.getPricePaise(), Long::sum);
            }
        }
        return perProvider.entrySet().stream().map(e -> new Split(e.getKey(), e.getValue())).toList();
    }
}
