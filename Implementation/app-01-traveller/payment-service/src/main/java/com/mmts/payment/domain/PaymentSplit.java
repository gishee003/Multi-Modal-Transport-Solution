package com.mmts.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One provider's share of a payment (M4.4).
 * gross = what the traveller paid for this provider's leg; commission = the platform's cut; net = what the provider gets.
 * Always: gross = commission + net.
 */
@Entity
@Table(name = "payment_splits")
public class PaymentSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String providerId;

    private long grossPaise;

    private long commissionPaise;

    private long netPaise;

    /** Which commission rule was used, e.g. "PERCENTAGE 5.00%" or "FLAT_FEE 500 paise". */
    private String commissionRule;

    /** How much of this split has been refunded so far (gross), and how much of that was commission. */
    private long refundedPaise;

    private long refundedCommissionPaise;

    protected PaymentSplit() {
        // for JPA
    }

    PaymentSplit(String providerId, long grossPaise, long commissionPaise, String commissionRule) {
        if (commissionPaise < 0 || commissionPaise > grossPaise) {
            throw new IllegalArgumentException("Commission must be between 0 and the split amount");
        }
        this.providerId = providerId;
        this.grossPaise = grossPaise;
        this.commissionPaise = commissionPaise;
        this.netPaise = grossPaise - commissionPaise;
        this.commissionRule = commissionRule;
    }

    long refundableGross() {
        return grossPaise - refundedPaise;
    }

    long refundableCommission() {
        return commissionPaise - refundedCommissionPaise;
    }

    void addRefund(long grossPart, long commissionPart) {
        refundedPaise += grossPart;
        refundedCommissionPaise += commissionPart;
    }

    public String getProviderId() {
        return providerId;
    }

    public long getGrossPaise() {
        return grossPaise;
    }

    public long getCommissionPaise() {
        return commissionPaise;
    }

    public long getNetPaise() {
        return netPaise;
    }

    public String getCommissionRule() {
        return commissionRule;
    }

    public long getRefundedPaise() {
        return refundedPaise;
    }
}
