package com.mmts.payment.domain;

/**
 * Strategy pattern: one interface, several interchangeable ways to work out the platform's commission.
 * The payment code only asks "how much commission on this amount?" and does not care which rule answers.
 * A new rule (e.g. a tiered rate) is a new class, with no change to the payment code.
 */
public interface CommissionStrategy {

    String PERCENTAGE = "PERCENTAGE";
    String FLAT_FEE = "FLAT_FEE";

    /** The name used in application.yml, e.g. PERCENTAGE or FLAT_FEE. */
    String name();

    /** The commission in paise on one provider's share; always between 0 and {@code grossPaise}. */
    long commissionFor(long grossPaise);

    /** A short human-readable description, e.g. "PERCENTAGE 5.00%". Stored on each split. */
    String describe();
}
