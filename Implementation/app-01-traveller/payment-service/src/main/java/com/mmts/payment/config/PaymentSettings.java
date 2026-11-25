package com.mmts.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * All payment settings, read from the "mmts.payment" section of application.yml (Externalization pattern).
 * Changing a commission rate or a limit is a configuration change, not a code change.
 */
@ConfigurationProperties(prefix = "mmts.payment")
public record PaymentSettings(Commission commission, Wallet wallet, Gateway gateway, Settlement settlement) {

    /**
     * @param percentageBasisPoints rate for the PERCENTAGE rule in basis points (500 = 5%)
     * @param flatFeePaise          fee for the FLAT_FEE rule, per provider share
     * @param defaultRule           rule for provider types not listed below
     * @param ruleByProviderType    e.g. METRO: PERCENTAGE, SCOOTER: FLAT_FEE
     */
    public record Commission(int percentageBasisPoints, long flatFeePaise, String defaultRule,
                             Map<String, String> ruleByProviderType) {
    }

    /** @param maxTopUpPaise the largest single top-up allowed */
    public record Wallet(long maxTopUpPaise) {
    }

    /** @param webhookSecret the shared secret the gateway uses to sign webhooks (HMAC-SHA256) */
    public record Gateway(String webhookSecret) {
    }

    /** @param exportDir folder for the daily settlement CSV files */
    public record Settlement(String exportDir) {
    }
}
