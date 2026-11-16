package com.mmts.payment.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One trip payment for one booking (M4.2), with its provider splits.
 *
 * {@code idempotencyKey} is unique in the database: the same key can never create a second payment,
 * which is how "charged once" (driver D8) is guaranteed even if two retries arrive at the same moment.
 * {@code status} records the outcome of the charge (CAPTURED or FAILED) and never changes afterwards,
 * so a repeated request always gets the same answer. Refunds are tracked separately in {@code refundedPaise}.
 */
@Entity
@Table(name = "payments")
@SequenceGenerator(name = "payment_seq", sequenceName = "payment_seq", initialValue = 9001, allocationSize = 1)
public class Payment {

    public enum Method { WALLET, GATEWAY }

    public enum Status { CAPTURED, FAILED }

    /** The part of a refund that goes back from one provider (net) and from the platform (commission). */
    public record RefundPortion(String providerId, long netPaise, long commissionPaise) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_seq")
    private Long id;

    @Column(unique = true, nullable = false)
    private String idempotencyKey;

    private String bookingId;

    private String userId;

    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method")
    private Method method;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String failureReason;

    /** The gateway's own payment id (e.g. "pay_stub_0001"). Only references are stored, never card numbers. */
    private String gatewayReference;

    private long refundedPaise;

    private Instant createdAt;

    /** Set when the gateway's signed webhook confirms the payment. */
    private Instant gatewayConfirmedAt;

    @Version
    private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "payment_id")
    @OrderBy("id")
    private List<PaymentSplit> splits = new ArrayList<>();

    protected Payment() {
        // for JPA
    }

    public Payment(String idempotencyKey, String bookingId, String userId, long amountPaise, Method method,
                   Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.bookingId = bookingId;
        this.userId = userId;
        this.amountPaise = amountPaise;
        this.method = method;
        this.createdAt = createdAt;
    }

    /** "p-9001" is the id other applications see. */
    public String paymentId() {
        return "p-" + id;
    }

    /** Turns "p-9001" back into 9001; anything else gives -1 (which matches no payment). */
    public static long parseId(String paymentId) {
        try {
            return paymentId != null && paymentId.startsWith("p-") ? Long.parseLong(paymentId.substring(2)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void addSplit(String providerId, long grossPaise, long commissionPaise, String commissionRule) {
        splits.add(new PaymentSplit(providerId, grossPaise, commissionPaise, commissionRule));
    }

    public void markCaptured(String gatewayReference) {
        this.status = Status.CAPTURED;
        this.gatewayReference = gatewayReference;
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason;
    }

    public boolean isCaptured() {
        return status == Status.CAPTURED;
    }

    public void confirmByGateway(Instant at) {
        if (gatewayConfirmedAt == null) {
            gatewayConfirmedAt = at;
        }
    }

    public long totalCommissionPaise() {
        return splits.stream().mapToLong(PaymentSplit::getCommissionPaise).sum();
    }

    public long refundablePaise() {
        return isCaptured() ? amountPaise - refundedPaise : 0;
    }

    /**
     * Records a refund and works out how much comes back from each provider and from the platform.
     * The refund is shared out in proportion to each provider's part of the payment. Any paise left
     * over by rounding go to the first provider that still has something to refund. The last refund
     * (the one that brings the total to the full amount) takes exactly what is left, so nothing is lost.
     */
    public List<RefundPortion> applyRefund(long refundPaise) {
        if (refundPaise <= 0 || refundPaise > refundablePaise()) {
            throw new IllegalArgumentException("Refund must be between 1 and the refundable amount");
        }
        boolean lastRefund = refundPaise == refundablePaise();
        long[] gross = new long[splits.size()];
        if (lastRefund) {
            for (int i = 0; i < gross.length; i++) {
                gross[i] = splits.get(i).refundableGross();
            }
        } else {
            long allocated = 0;
            for (int i = 0; i < gross.length; i++) {
                PaymentSplit s = splits.get(i);
                gross[i] = Math.min(s.refundableGross(), refundPaise * s.getGrossPaise() / amountPaise);
                allocated += gross[i];
            }
            long leftover = refundPaise - allocated;
            for (int i = 0; i < gross.length && leftover > 0; i++) {
                long extra = Math.min(leftover, splits.get(i).refundableGross() - gross[i]);
                gross[i] += extra;
                leftover -= extra;
            }
        }
        List<RefundPortion> portions = new ArrayList<>();
        for (int i = 0; i < gross.length; i++) {
            PaymentSplit s = splits.get(i);
            long commission = lastRefund
                    ? s.refundableCommission()
                    : Math.min(s.refundableCommission(), gross[i] * s.getCommissionPaise() / s.getGrossPaise());
            s.addRefund(gross[i], commission);
            portions.add(new RefundPortion(s.getProviderId(), gross[i] - commission, commission));
        }
        refundedPaise += refundPaise;
        return portions;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getBookingId() {
        return bookingId;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public Method getMethod() {
        return method;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public long getRefundedPaise() {
        return refundedPaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getGatewayConfirmedAt() {
        return gatewayConfirmedAt;
    }

    public List<PaymentSplit> getSplits() {
        return List.copyOf(splits);
    }
}
