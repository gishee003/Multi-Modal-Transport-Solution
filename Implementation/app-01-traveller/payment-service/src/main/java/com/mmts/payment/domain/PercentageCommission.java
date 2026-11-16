package com.mmts.payment.domain;

/**
 * Commission as a percentage of the amount, e.g. 5%.
 * The rate is kept in basis points (1 basis point = 0.01%, so 500 = 5%) to stay in whole numbers.
 * Rounding: half a paisa or more rounds up (4010 paise at 5% = 200.5 → 201).
 */
public record PercentageCommission(int basisPoints) implements CommissionStrategy {

    public PercentageCommission {
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("Commission must be between 0% and 100%");
        }
    }

    @Override
    public String name() {
        return PERCENTAGE;
    }

    @Override
    public long commissionFor(long grossPaise) {
        if (grossPaise < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        return (grossPaise * basisPoints + 5_000) / 10_000;
    }

    @Override
    public String describe() {
        return String.format("PERCENTAGE %d.%02d%%", basisPoints / 100, basisPoints % 100);
    }
}
