package com.mmts.payment.adapter;

/**
 * Adapter pattern: the one way this service talks to a card / UPI payment gateway.
 * Business code depends only on this interface. The demo plugs in {@link StubRazorpayGateway};
 * a real RazorpayGateway (Orders API + Checkout + webhooks) would implement the same two methods.
 *
 * Only tokens and references cross this interface. Card numbers never reach MMTS (driver D9).
 */
public interface PaymentGateway {

    /**
     * Takes money using a gateway token (made by the gateway's own checkout page, never by us).
     *
     * @param paymentToken e.g. "tok_demo_visa"
     * @param reference    our own reference, sent to the gateway as a note (e.g. "booking:b-5001")
     */
    ChargeResult charge(String paymentToken, long amountPaise, String reference);

    /** Gives money back on an earlier gateway payment. */
    RefundResult refund(String gatewayPaymentId, long amountPaise, String reference);

    /** captured = true with the gateway's payment id, or false with a decline reason. */
    record ChargeResult(boolean captured, String gatewayPaymentId, String declineReason) {
    }

    record RefundResult(boolean refunded, String gatewayRefundId) {
    }
}
