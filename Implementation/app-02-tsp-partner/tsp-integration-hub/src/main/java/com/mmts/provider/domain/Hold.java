package com.mmts.provider.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A hold on one leg of a booking (INT-12). Life cycle (a small state machine):
 * <pre>
 *   HELD ──confirm──► CONFIRMED ──unlock──► UNLOCKED
 *    │  └─10 min pass─► EXPIRED
 *    └──release (from HELD, CONFIRMED or UNLOCKED)──► RELEASED
 * </pre>
 */
@Entity
@Table(name = "holds")
public class Hold {

    public enum Status { HELD, CONFIRMED, UNLOCKED, RELEASED, EXPIRED }

    @Id
    private String holdId;
    private String bookingId;
    private String legId;
    private String providerId;
    @Enumerated(EnumType.STRING)
    private Mode mode;
    private String vehicleId;            // null for metro / bus / taxi legs
    private String fromName;
    private double fromLat;
    private double fromLon;
    private String fromStopId;
    private String toName;
    private double toLat;
    private double toLon;
    private String toStopId;
    private Instant startTime;
    private long pricePaise;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant createdAt;
    private Instant expiresAt;
    private String providerRef;          // the provider's own reference (e.g. ONDC transaction id)

    // Filled when confirmed
    private String accessType;           // QR_TICKET, UNLOCK_CODE or DRIVER_ASSIGNED
    private String accessCode;
    private Instant accessValidFrom;
    private Instant accessValidUntil;
    private String driverId;
    private String driverName;
    private String driverVehicleNumber;
    private Integer driverEtaMinutes;

    // Filled when unlocked
    private Instant unlockedAt;
    private String unlockedBy;

    protected Hold() {
        // for JPA
    }

    public Hold(String holdId, String bookingId, String legId, String providerId, Mode mode, String vehicleId,
                Place from, Place to, Instant startTime, long pricePaise, String providerRef,
                Instant createdAt, Instant expiresAt) {
        this.holdId = holdId;
        this.bookingId = bookingId;
        this.legId = legId;
        this.providerId = providerId;
        this.mode = mode;
        this.vehicleId = vehicleId;
        this.fromName = from.name();
        this.fromLat = from.lat();
        this.fromLon = from.lon();
        this.fromStopId = from.stopId();
        this.toName = to.name();
        this.toLat = to.lat();
        this.toLon = to.lon();
        this.toStopId = to.stopId();
        this.startTime = startTime;
        this.pricePaise = pricePaise;
        this.providerRef = providerRef;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = Status.HELD;
    }

    /** A HELD hold whose 10 minutes have passed. */
    public boolean isOverdue(Instant now) {
        return status == Status.HELD && !now.isBefore(expiresAt);
    }

    public boolean isConfirmedOrUnlocked() {
        return status == Status.CONFIRMED || status == Status.UNLOCKED;
    }

    public boolean isFinished() {
        return status == Status.RELEASED || status == Status.EXPIRED;
    }

    public void markConfirmed(String type, String code, Instant validFrom, Instant validUntil,
                              String driverId, String driverName, String driverVehicleNumber, Integer etaMinutes) {
        this.status = Status.CONFIRMED;
        this.accessType = type;
        this.accessCode = code;
        this.accessValidFrom = validFrom;
        this.accessValidUntil = validUntil;
        this.driverId = driverId;
        this.driverName = driverName;
        this.driverVehicleNumber = driverVehicleNumber;
        this.driverEtaMinutes = etaMinutes;
    }

    public void markUnlocked(String userId, Instant at) {
        this.status = Status.UNLOCKED;
        this.unlockedBy = userId;
        this.unlockedAt = at;
    }

    public void markReleased() {
        this.status = Status.RELEASED;
    }

    public void markExpired() {
        this.status = Status.EXPIRED;
    }

    public Place from() {
        return new Place(fromName, fromLat, fromLon, fromStopId);
    }

    public Place to() {
        return new Place(toName, toLat, toLon, toStopId);
    }

    public String getHoldId() { return holdId; }
    public String getBookingId() { return bookingId; }
    public String getLegId() { return legId; }
    public String getProviderId() { return providerId; }
    public Mode getMode() { return mode; }
    public String getVehicleId() { return vehicleId; }
    public Instant getStartTime() { return startTime; }
    public long getPricePaise() { return pricePaise; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getProviderRef() { return providerRef; }
    public String getAccessType() { return accessType; }
    public String getAccessCode() { return accessCode; }
    public Instant getAccessValidFrom() { return accessValidFrom; }
    public Instant getAccessValidUntil() { return accessValidUntil; }
    public String getDriverId() { return driverId; }
    public String getDriverName() { return driverName; }
    public String getDriverVehicleNumber() { return driverVehicleNumber; }
    public Integer getDriverEtaMinutes() { return driverEtaMinutes; }
    public Instant getUnlockedAt() { return unlockedAt; }
    public String getUnlockedBy() { return unlockedBy; }
}
