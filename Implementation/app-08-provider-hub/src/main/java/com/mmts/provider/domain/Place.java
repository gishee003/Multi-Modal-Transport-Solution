package com.mmts.provider.domain;

import jakarta.validation.constraints.NotNull;

/**
 * A point on the map (shared "Place" value type in docs/CONTRACTS.md).
 * Example: { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" }
 */
public record Place(String name, @NotNull Double lat, @NotNull Double lon, String stopId) {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Straight-line distance between two points in km (haversine formula). */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    public double distanceKmTo(Place other) {
        return distanceKm(lat, lon, other.lat(), other.lon());
    }
}
