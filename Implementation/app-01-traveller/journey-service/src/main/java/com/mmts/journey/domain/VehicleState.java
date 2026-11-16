package com.mmts.journey.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The latest known state of one vehicle (scooter, bike, taxi, bus, train), built from vehicle.events.
 *
 * @param speedKmph  may be null when the provider does not send it
 * @param batteryPct may be null (only electric vehicles report it)
 * @param recordedAt when the vehicle reported this position (not when we received it)
 */
public record VehicleState(String vehicleId, String providerId, Mode mode, double lat, double lon,
                           Double speedKmph, Integer batteryPct, boolean available, Instant recordedAt) {

    /** How many whole seconds ago the position was recorded. */
    public long ageSeconds(Instant now) {
        return Math.max(0, Duration.between(recordedAt, now).getSeconds());
    }

    /**
     * Staleness rule (driver D5): a position older than the limit is no longer "live". The app then shows
     * "last seen N s ago" instead of pretending the vehicle is there now.
     */
    public boolean isStale(Instant now, long staleAfterSeconds) {
        return ageSeconds(now) > staleAfterSeconds;
    }

    public double metresTo(double otherLat, double otherLon) {
        return Stop.metresBetween(lat, lon, otherLat, otherLon);
    }

    public VehicleState withAvailable(boolean nowAvailable) {
        return new VehicleState(vehicleId, providerId, mode, lat, lon, speedKmph, batteryPct, nowAvailable, recordedAt);
    }
}
