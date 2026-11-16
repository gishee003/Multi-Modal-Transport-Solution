package com.mmts.payment.adapter;

/**
 * Adapter pattern for sending money to providers.
 * Real system: Razorpay Route, which transfers money to each provider's "linked account".
 * The demo uses {@link StubRazorpayRouteGateway}.
 */
public interface PayoutGateway {

    /**
     * Sends money to one provider.
     *
     * @param linkedAccountId the provider's account at the gateway (e.g. "acc_DMRC01"); null if it has none
     * @param reference       our reference, e.g. "payout:run-1a2b:DMRC"
     */
    TransferResult transfer(String linkedAccountId, long amountPaise, String reference);

    record TransferResult(boolean success, String transferId, String failureReason) {
    }
}
