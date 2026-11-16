package com.mmts.provider.domain;

import java.time.Instant;

/** A taxi ride offered to one driver (INT-08). Kept in memory; pushed to the driver over Server-Sent Events. */
public class RideOffer {

    public enum Status { OFFERED, ACCEPTED, REJECTED, CANCELLED }

    private final String offerId;
    private final String driverId;
    private final String holdId;
    private final Place pickup;
    private final Place drop;
    private final long farePaise;
    private final Instant createdAt;
    private Status status = Status.OFFERED;

    public RideOffer(String offerId, String driverId, String holdId, Place pickup, Place drop,
                     long farePaise, Instant createdAt) {
        this.offerId = offerId;
        this.driverId = driverId;
        this.holdId = holdId;
        this.pickup = pickup;
        this.drop = drop;
        this.farePaise = farePaise;
        this.createdAt = createdAt;
    }

    public boolean isPending() {
        return status == Status.OFFERED;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getOfferId() { return offerId; }
    public String getDriverId() { return driverId; }
    public String getHoldId() { return holdId; }
    public Place getPickup() { return pickup; }
    public Place getDrop() { return drop; }
    public long getFarePaise() { return farePaise; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
}
