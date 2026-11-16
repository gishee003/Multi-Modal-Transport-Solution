package com.mmts.journey.domain;

/**
 * One point (a "node") in the transport network: a metro station, bus stop, scooter/bike dock or a well-known place.
 *
 * @param id   short code, for example "DMRC-RCK"
 * @param kind what sort of point it is
 */
public record Stop(String id, String name, double lat, double lon, Kind kind) {

    public enum Kind { METRO_STATION, BUS_STOP, DOCK, PLACE }

    private static final double EARTH_RADIUS_METRES = 6_371_000;

    /** Straight-line distance to another stop, in metres. */
    public double metresTo(Stop other) {
        return metresBetween(lat, lon, other.lat, other.lon);
    }

    /** Straight-line ("as the crow flies") distance between two points, using the haversine formula. */
    public static double metresBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.sqrt(a));
    }
}
