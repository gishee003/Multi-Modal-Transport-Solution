package com.mmts.provider.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A taxi driver of a taxi provider (M5.5 to M5.7).
 * Life cycle: REGISTERED ──activate (licence eligible)──► ACTIVE ──licence expired──► DEACTIVATED.
 * Only an ACTIVE driver can go online, and only an online driver who is not on a trip gets ride offers.
 */
@Entity
@Table(name = "drivers")
public class Driver {

    public enum Status { REGISTERED, ACTIVE, DEACTIVATED }

    @Id
    private String driverId;         // the same id as the user in Identity service
    private String name;
    private String vehicleNumber;
    private String providerId;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String statusReason;
    private boolean online;
    private boolean onTrip;
    private Double lat;
    private Double lon;
    private Instant lastSeenAt;

    protected Driver() {
        // for JPA
    }

    public Driver(String driverId, String name, String vehicleNumber, String providerId) {
        this.driverId = driverId;
        this.name = name;
        this.vehicleNumber = vehicleNumber;
        this.providerId = providerId;
        this.status = Status.REGISTERED;
        this.statusReason = "AWAITING_ACTIVATION";
    }

    public void activate() {
        this.status = Status.ACTIVE;
        this.statusReason = "VERIFIED";
    }

    public void deactivate(String reason) {
        this.status = Status.DEACTIVATED;
        this.statusReason = reason;
        this.online = false;
    }

    public void goOnline() {
        this.online = true;
    }

    public void goOffline() {
        this.online = false;
    }

    public void moveTo(double newLat, double newLon, Instant at) {
        this.lat = newLat;
        this.lon = newLon;
        this.lastSeenAt = at;
    }

    public void setOnTrip(boolean onTrip) {
        this.onTrip = onTrip;
    }

    /** Can this driver be given a new ride right now? */
    public boolean isFree() {
        return status == Status.ACTIVE && online && !onTrip && lat != null && lon != null;
    }

    public String getDriverId() { return driverId; }
    public String getName() { return name; }
    public String getVehicleNumber() { return vehicleNumber; }
    public String getProviderId() { return providerId; }
    public Status getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public boolean isOnline() { return online; }
    public boolean isOnTrip() { return onTrip; }
    public Double getLat() { return lat; }
    public Double getLon() { return lon; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
