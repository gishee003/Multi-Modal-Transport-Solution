package com.mmts.provider.domain;

import java.time.Instant;

/**
 * The common vehicle model: the latest known state of one vehicle, whatever provider it belongs to.
 * Every plug-in's own format (GBFS, GTFS-Realtime, driver app ...) is turned into this one shape.
 *
 * @param recordedAt when the provider measured the reading
 * @param lastSeenAt when the hub last received data for the vehicle (used for offline detection)
 */
public record VehicleState(String vehicleId, String providerId, Mode mode,
                           double lat, double lon, Double speedKmph, Integer batteryPct,
                           boolean available, boolean online,
                           Instant recordedAt, Instant lastSeenAt) {

    public VehicleState withAvailable(boolean newAvailable) {
        return new VehicleState(vehicleId, providerId, mode, lat, lon, speedKmph, batteryPct,
                newAvailable, online, recordedAt, lastSeenAt);
    }

    public VehicleState offline() {
        return new VehicleState(vehicleId, providerId, mode, lat, lon, speedKmph, batteryPct,
                available, false, recordedAt, lastSeenAt);
    }
}
