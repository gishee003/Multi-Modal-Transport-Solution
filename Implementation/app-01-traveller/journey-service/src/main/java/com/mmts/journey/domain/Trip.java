package com.mmts.journey.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A trip that is being travelled right now. Created when a BOOKING_CONFIRMED event arrives.
 * The trip follows its legs one by one: when the vehicle of the current leg reaches the leg's end, the next tracked
 * leg becomes current. After the last one the trip is COMPLETED.
 */
@Entity
@Table(name = "trips")
public class Trip {

    public enum Status { ACTIVE, COMPLETED, CANCELLED }

    @Id
    private String id;
    private String bookingId;
    private String userId;
    @Enumerated(EnumType.STRING)
    private Status status;
    private long totalPaise;
    private Instant createdAt;
    private Instant plannedArrival;
    private int currentLegIndex;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trip_legs", joinColumns = @JoinColumn(name = "trip_id"))
    @OrderColumn(name = "leg_no")
    private List<TripLeg> legs = new ArrayList<>();

    protected Trip() {
        // for JPA
    }

    public Trip(String id, String bookingId, String userId, long totalPaise, Instant createdAt, List<TripLeg> legs) {
        this.id = id;
        this.bookingId = bookingId;
        this.userId = userId;
        this.totalPaise = totalPaise;
        this.createdAt = createdAt;
        this.legs = new ArrayList<>(legs);
        this.status = Status.ACTIVE;
        this.currentLegIndex = nextTrackedFrom(0);
        this.plannedArrival = computePlannedArrival();
        if (currentLegIndex >= legs.size()) {
            currentLegIndex = 0;   // nothing to track: keep pointing at the first leg
        }
    }

    /** The leg being travelled now (empty when the trip is over). */
    public Optional<TripLeg> currentLeg() {
        if (status != Status.ACTIVE || legs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(legs.get(currentLegIndex));
    }

    /** The legs still to come after the current one. */
    public List<TripLeg> legsAfterCurrent() {
        return legs.subList(Math.min(currentLegIndex + 1, legs.size()), legs.size());
    }

    /** The current leg's vehicle reached the end of the leg: move on to the next tracked leg, or finish. */
    public void currentLegArrived() {
        int next = nextTrackedFrom(currentLegIndex + 1);
        if (next >= legs.size()) {
            status = Status.COMPLETED;
            currentLegIndex = legs.size() - 1;
        } else {
            currentLegIndex = next;
        }
    }

    public void complete() {
        status = Status.COMPLETED;
    }

    public void cancel() {
        status = Status.CANCELLED;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    private int nextTrackedFrom(int start) {
        for (int i = start; i < legs.size(); i++) {
            if (legs.get(i).isTracked()) {
                return i;
            }
        }
        return legs.size();
    }

    /** Planned arrival = start of the last leg plus its planned minutes (or creation time plus all minutes). */
    private Instant computePlannedArrival() {
        if (legs.isEmpty()) {
            return createdAt;
        }
        TripLeg last = legs.get(legs.size() - 1);
        if (last.startTime() != null) {
            return last.startTime().plusSeconds(60L * last.plannedMinutes());
        }
        int total = legs.stream().mapToInt(TripLeg::plannedMinutes).sum();
        return createdAt.plusSeconds(60L * total);
    }

    public String id() { return id; }
    public String bookingId() { return bookingId; }
    public String userId() { return userId; }
    public Status status() { return status; }
    public long totalPaise() { return totalPaise; }
    public Instant createdAt() { return createdAt; }
    public Instant plannedArrival() { return plannedArrival; }
    public int currentLegIndex() { return currentLegIndex; }
    public List<TripLeg> legs() { return List.copyOf(legs); }
}
