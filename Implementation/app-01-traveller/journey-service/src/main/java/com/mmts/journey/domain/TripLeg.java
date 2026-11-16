package com.mmts.journey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;

/** One booked leg of a trip, copied from the BOOKING_CONFIRMED event (stored inside the trip row's leg table). */
@Embeddable
public class TripLeg {

    private String legId;
    @Enumerated(EnumType.STRING)
    @Column(name = "leg_mode")
    private Mode mode;
    private String providerId;
    private String vehicleId;          // the vehicle we follow on the live stream; null = not tracked (walking)
    private String fromName;
    private double fromLat;
    private double fromLon;
    private String toName;
    private double toLat;
    private double toLon;
    private Instant startTime;
    private int plannedMinutes;

    protected TripLeg() {
        // for JPA
    }

    public TripLeg(String legId, Mode mode, String providerId, String vehicleId, String fromName, double fromLat,
                   double fromLon, String toName, double toLat, double toLon, Instant startTime, int plannedMinutes) {
        this.legId = legId;
        this.mode = mode;
        this.providerId = providerId;
        this.vehicleId = vehicleId;
        this.fromName = fromName;
        this.fromLat = fromLat;
        this.fromLon = fromLon;
        this.toName = toName;
        this.toLat = toLat;
        this.toLon = toLon;
        this.startTime = startTime;
        this.plannedMinutes = plannedMinutes;
    }

    /** A leg is tracked live when we know which vehicle carries the traveller. */
    public boolean isTracked() {
        return vehicleId != null && mode != Mode.WALK;
    }

    public String legId() { return legId; }
    public Mode mode() { return mode; }
    public String providerId() { return providerId; }
    public String vehicleId() { return vehicleId; }
    public String fromName() { return fromName; }
    public double fromLat() { return fromLat; }
    public double fromLon() { return fromLon; }
    public String toName() { return toName; }
    public double toLat() { return toLat; }
    public double toLon() { return toLon; }
    public Instant startTime() { return startTime; }
    public int plannedMinutes() { return plannedMinutes; }
}
