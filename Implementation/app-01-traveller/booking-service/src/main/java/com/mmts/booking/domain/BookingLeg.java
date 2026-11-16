package com.mmts.booking.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One leg of a journey (for example "metro from Rajiv Chowk to Hauz Khas").
 * Holds what the provider told us: the hold, the price and, after payment, the access (ticket, code or driver).
 * Access can only be set through {@link Booking#issueAccess}, which checks the booking state first (driver D7).
 */
@Entity
@Table(name = "booking_legs")
public class BookingLeg {

    @Id
    private String id;                 // e.g. "b-1a2b3c4d-L1"

    @ManyToOne(optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    private int sequence;

    @Enumerated(EnumType.STRING)
    private Mode mode;

    private String providerId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "from_name")),
            @AttributeOverride(name = "lat", column = @Column(name = "from_lat")),
            @AttributeOverride(name = "lon", column = @Column(name = "from_lon")),
            @AttributeOverride(name = "stopId", column = @Column(name = "from_stop_id"))})
    private Place fromPlace;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "to_name")),
            @AttributeOverride(name = "lat", column = @Column(name = "to_lat")),
            @AttributeOverride(name = "lon", column = @Column(name = "to_lon")),
            @AttributeOverride(name = "stopId", column = @Column(name = "to_stop_id"))})
    private Place toPlace;

    private Instant startTime;
    private String vehicleId;

    @Enumerated(EnumType.STRING)
    private LegStatus status;

    private String holdId;
    private long pricePaise;

    // Access: filled only from PAID onward
    @Enumerated(EnumType.STRING)
    private AccessType accessType;
    @Column(length = 2000)
    private String accessValue;
    private Instant accessValidFrom;
    private Instant accessValidUntil;

    // Taxi driver: filled only for TAXI legs
    private String driverId;
    private String driverName;
    private String driverVehicleNumber;
    private Integer driverEtaMinutes;

    private Instant unlockedAt;

    protected BookingLeg() {
        // for JPA
    }

    BookingLeg(Booking booking, int sequence, Mode mode, String providerId, Place from, Place to,
               Instant startTime, String vehicleId) {
        this.booking = booking;
        this.id = booking.getId() + "-L" + sequence;
        this.sequence = sequence;
        this.mode = mode;
        this.providerId = providerId;
        this.fromPlace = from;
        this.toPlace = to;
        this.startTime = startTime;
        this.vehicleId = vehicleId;
        this.status = mode.needsProvider() ? LegStatus.PENDING : LegStatus.NOT_NEEDED;
    }

    /** The provider is now holding this leg for us. */
    public void markHeld(String holdId, long pricePaise) {
        this.holdId = holdId;
        this.pricePaise = pricePaise;
        this.status = LegStatus.HELD;
    }

    public void markConfirmed() {
        this.status = LegStatus.CONFIRMED;
    }

    /** The hold was given back (undo step). */
    public void markReleased() {
        this.status = LegStatus.RELEASED;
    }

    void setAccess(AccessType type, String value, Instant validFrom, Instant validUntil) {
        this.accessType = type;
        this.accessValue = value;
        this.accessValidFrom = validFrom;
        this.accessValidUntil = validUntil;
    }

    void setDriver(String driverId, String name, String vehicleNumber, Integer etaMinutes) {
        this.driverId = driverId;
        this.driverName = name;
        this.driverVehicleNumber = vehicleNumber;
        this.driverEtaMinutes = etaMinutes;
    }

    /** Removes any ticket or code (used when the booking is cancelled). */
    void revokeAccess() {
        setAccess(null, null, null, null);
        setDriver(null, null, null, null);
    }

    void markUnlocked(Instant at) {
        this.status = LegStatus.UNLOCKED;
        this.unlockedAt = at;
    }

    /** True when the provider still has a hold for this leg that should be given back. */
    public boolean hasActiveHold() {
        return holdId != null
                && (status == LegStatus.HELD || status == LegStatus.CONFIRMED || status == LegStatus.UNLOCKED);
    }

    public String getId() {
        return id;
    }

    public Booking getBooking() {
        return booking;
    }

    public int getSequence() {
        return sequence;
    }

    public Mode getMode() {
        return mode;
    }

    public String getProviderId() {
        return providerId;
    }

    public Place getFromPlace() {
        return fromPlace;
    }

    public Place getToPlace() {
        return toPlace;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public LegStatus getStatus() {
        return status;
    }

    public String getHoldId() {
        return holdId;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public AccessType getAccessType() {
        return accessType;
    }

    public String getAccessValue() {
        return accessValue;
    }

    public Instant getAccessValidFrom() {
        return accessValidFrom;
    }

    public Instant getAccessValidUntil() {
        return accessValidUntil;
    }

    public String getDriverId() {
        return driverId;
    }

    public String getDriverName() {
        return driverName;
    }

    public String getDriverVehicleNumber() {
        return driverVehicleNumber;
    }

    public Integer getDriverEtaMinutes() {
        return driverEtaMinutes;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }
}
