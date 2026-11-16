package com.mmts.journey.domain;

/**
 * The ways of travelling (the shared "Mode" value in docs/CONTRACTS.md).
 * Each mode knows its typical speed and the usual wait before the ride starts
 * (waiting for a train, unlocking a scooter, a taxi arriving). These are model assumptions, not prices.
 */
public enum Mode {
    WALK(4.8, 0),
    METRO(34, 3),
    BUS(15, 6),
    SCOOTER(18, 2),
    BIKE(12, 2),
    CAR(25, 5),     // self-drive car: booked in Booking service, never suggested by this planner
    TAXI(22, 5);

    private final double kmph;
    private final int waitMinutes;

    Mode(double kmph, int waitMinutes) {
        this.kmph = kmph;
        this.waitMinutes = waitMinutes;
    }

    /** Average speed in km per hour. */
    public double kmph() {
        return kmph;
    }

    /** Typical minutes spent waiting or unlocking before the ride starts. */
    public int waitMinutes() {
        return waitMinutes;
    }

    /** Metres covered in one minute at the average speed. */
    public double metresPerMinute() {
        return kmph * 1000 / 60;
    }

    /** Shared vehicles picked up at a dock (micromobility). */
    public boolean isDocked() {
        return this == SCOOTER || this == BIKE;
    }
}
