package com.mmts.payment.domain;

/**
 * A fixed commission per provider share, e.g. 500 paise (Rs 5) on every scooter ride, whatever its price.
 * A very cheap ride never pays more commission than its own price.
 */
public record FlatFeeCommission(long feePaise) implements CommissionStrategy {

    public FlatFeeCommission {
        if (feePaise < 0) {
            throw new IllegalArgumentException("Fee must not be negative");
        }
    }

    @Override
    public String name() {
        return FLAT_FEE;
    }

    @Override
    public long commissionFor(long grossPaise) {
        if (grossPaise < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        return Math.min(feePaise, grossPaise);
    }

    @Override
    public String describe() {
        return "FLAT_FEE " + feePaise + " paise";
    }
}
