package com.mmts.booking.domain;

import com.mmts.booking.common.error.ApiException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One booking: all legs of one journey, paid together (all-or-nothing).
 *
 * Two safety rules live here:
 * - The state machine ({@link BookingStatus}): every change of status goes through {@link #moveTo}, which refuses
 *   illegal steps, and tickets or codes can only be issued from PAID onward (driver D7).
 * - Optimistic locking: the {@code version} column goes up by one on every save. If two requests both loaded
 *   version 3 and both try to save, the second save is refused, so two concurrent updates cannot both win (D6).
 */
@Entity
@Table(name = "bookings",
        uniqueConstraints = @UniqueConstraint(name = "uk_booking_user_key", columnNames = {"user_id", "idempotency_key"}))
public class Booking {

    @Id
    private String id;                 // e.g. "b-1a2b3c4d"

    @Version
    private Long version;              // optimistic lock counter, managed by JPA

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String idempotencyKey;     // the client's Idempotency-Key header
    private String requestHash;        // fingerprint of the request body sent with that key

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    private String paymentMethod;      // WALLET or GATEWAY
    private long totalPaise;
    private String paymentId;
    private String refundId;
    private String cancelReason;

    @Column(length = 4000)
    private String sagaLog = "";       // one line per saga step, shown in the response

    private Integer ratingStars;
    private String ratingComment;

    private Instant createdAt;
    private Instant updatedAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sequence")
    private List<BookingLeg> legs = new ArrayList<>();

    protected Booking() {
        // for JPA
    }

    public Booking(String id, String userId, String idempotencyKey, String requestHash, String paymentMethod,
                   Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.paymentMethod = paymentMethod;
        this.status = BookingStatus.CREATED;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** Adds the next leg; its id is the booking id plus "-L1", "-L2" and so on. */
    public BookingLeg addLeg(Mode mode, String providerId, Place from, Place to, Instant startTime, String vehicleId) {
        BookingLeg leg = new BookingLeg(this, legs.size() + 1, mode, providerId, from, to, startTime, vehicleId);
        legs.add(leg);
        return leg;
    }

    /** The only way to change status: illegal transitions are refused with 409 ILLEGAL_STATE. */
    public void moveTo(BookingStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw ApiException.conflict("ILLEGAL_STATE",
                    "Booking " + id + " is " + status + " and cannot become " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    /** Driver D7: gives a leg its ticket or unlock code, but only once the booking is PAID or later. */
    public void issueAccess(BookingLeg leg, AccessType type, String value, Instant validFrom, Instant validUntil) {
        if (!status.canIssueAccess()) {
            throw ApiException.conflict("ILLEGAL_STATE",
                    "No ticket or unlock code before payment: booking " + id + " is " + status);
        }
        leg.setAccess(type, value, validFrom, validUntil);
    }

    /** Taxi legs: records the driver the provider assigned (also only from PAID onward). */
    public void assignDriver(BookingLeg leg, String driverId, String name, String vehicleNumber, Integer eta) {
        if (!status.canIssueAccess()) {
            throw ApiException.conflict("ILLEGAL_STATE", "No driver before payment: booking " + id + " is " + status);
        }
        leg.setDriver(driverId, name, vehicleNumber, eta);
    }

    /** Takes back every ticket and code (undo of the "issue access" step). */
    public void revokeAllAccess() {
        legs.forEach(BookingLeg::revokeAccess);
    }

    /** Moves to CANCELLED, records why, and makes sure no ticket or code is left. */
    public void cancel(String reason, Instant now) {
        moveTo(BookingStatus.CANCELLED, now);
        this.cancelReason = reason;
        revokeAllAccess();
    }

    /** Marks a scooter, bike or car leg as unlocked. Only on a CONFIRMED booking. */
    public void unlockLeg(BookingLeg leg, Instant now) {
        if (!status.canUnlock()) {
            throw ApiException.conflict("ILLEGAL_STATE",
                    "Booking " + id + " is " + status + "; only CONFIRMED bookings can unlock a vehicle");
        }
        if (!leg.getMode().usesUnlockCode()) {
            throw ApiException.conflict("NOT_UNLOCKABLE",
                    "Leg " + leg.getId() + " is " + leg.getMode() + ", not a vehicle to unlock");
        }
        if (leg.getStatus() == LegStatus.UNLOCKED) {
            throw ApiException.conflict("ALREADY_UNLOCKED", "Leg " + leg.getId() + " is already unlocked");
        }
        leg.markUnlocked(now);
        this.updatedAt = now;
    }

    /** M3.7: rate the trip once, after it is completed. */
    public void rate(int stars, String comment, Instant now) {
        if (!status.canRate()) {
            throw ApiException.conflict("ILLEGAL_STATE",
                    "Only a COMPLETED trip can be rated; booking " + id + " is " + status);
        }
        if (ratingStars != null) {
            throw ApiException.conflict("ALREADY_RATED", "Booking " + id + " has already been rated");
        }
        this.ratingStars = stars;
        this.ratingComment = comment;
        this.updatedAt = now;
    }

    public BookingLeg leg(String legId) {
        return legs.stream().filter(l -> l.getId().equals(legId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("LEG_NOT_FOUND", "Booking " + id + " has no leg " + legId));
    }

    /** A self-drive car leg needs a licence check first (INT-10). */
    public boolean hasSelfDriveCar() {
        return legs.stream().anyMatch(l -> l.getMode() == Mode.CAR);
    }

    public boolean isOwnedBy(String someUserId) {
        return userId.equals(someUserId);
    }

    /** Adds one line to the saga log (what was done and undone). */
    public void log(String line) {
        sagaLog = sagaLog == null || sagaLog.isEmpty() ? line : sagaLog + "\n" + line;
    }

    public List<String> sagaLogLines() {
        return sagaLog == null || sagaLog.isEmpty() ? List.of() : Arrays.asList(sagaLog.split("\n"));
    }

    public void recordTotal(long totalPaise) {
        this.totalPaise = totalPaise;
    }

    public void recordPayment(String paymentId) {
        this.paymentId = paymentId;
    }

    public void recordRefund(String refundId) {
        this.refundId = refundId;
    }

    public String getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public long getTotalPaise() {
        return totalPaise;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getRefundId() {
        return refundId;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Integer getRatingStars() {
        return ratingStars;
    }

    public String getRatingComment() {
        return ratingComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<BookingLeg> getLegs() {
        return legs;
    }
}
