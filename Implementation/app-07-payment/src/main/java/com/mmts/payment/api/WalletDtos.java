package com.mmts.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/** Request and response bodies of the wallet endpoints (INT-05). All money in paise. */
public final class WalletDtos {

    private WalletDtos() {
    }

    public record WalletResponse(String userId, long balancePaise, String currency, Instant updatedAt) {
    }

    /**
     * A top-up. {@code paymentToken} comes from the gateway's checkout (e.g. "tok_demo_visa").
     * Card number / CVV / expiry fields are refused before this record is even built (see CardDataGuardAdvice).
     */
    public record TopUpRequest(@NotNull @Positive Long amountPaise, @NotBlank String paymentToken) {
    }

    public record WalletTransactionResponse(String transactionId, String type, long amountPaise,
                                            long balanceAfterPaise, String reference, Instant createdAt) {
    }
}
