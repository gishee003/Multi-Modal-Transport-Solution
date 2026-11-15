package com.mmts.payment.domain;

/**
 * Names of the ledger accounts. Think of each account as a labelled box of money in the platform's books.
 *
 * <ul>
 *   <li>{@code GATEWAY_CLEARING}: money the gateway (Razorpay) has collected for us from cards / UPI.</li>
 *   <li>{@code USER_WALLET:<userId>}: money the platform holds for a traveller (their wallet balance).</li>
 *   <li>{@code PROVIDER_PAYABLE:<providerId>}: money the platform owes a provider but has not sent yet.</li>
 *   <li>{@code PLATFORM_REVENUE}: the platform's commission.</li>
 *   <li>{@code PROVIDER_SETTLED:<providerId>}: money already transferred to a provider's bank account.</li>
 * </ul>
 */
public final class LedgerAccounts {

    public static final String GATEWAY_CLEARING = "GATEWAY_CLEARING";
    public static final String PLATFORM_REVENUE = "PLATFORM_REVENUE";
    public static final String USER_WALLET_PREFIX = "USER_WALLET:";
    public static final String PROVIDER_PAYABLE_PREFIX = "PROVIDER_PAYABLE:";
    public static final String PROVIDER_SETTLED_PREFIX = "PROVIDER_SETTLED:";

    private LedgerAccounts() {
    }

    public static String userWallet(String userId) {
        return USER_WALLET_PREFIX + userId;
    }

    public static String providerPayable(String providerId) {
        return PROVIDER_PAYABLE_PREFIX + providerId;
    }

    public static String providerSettled(String providerId) {
        return PROVIDER_SETTLED_PREFIX + providerId;
    }
}
