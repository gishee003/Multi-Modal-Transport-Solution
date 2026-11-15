package com.mmts.journey.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A read-only copy of a trip, safe to hand to the API layer (the Trip entity itself never leaves the service layer).
 */
public record TripView(String tripId, String bookingId, String userId, String status, long totalPaise,
                       int currentLegIndex, Instant plannedArrival, String liveUrl, List<LegView> legs) {

    public record Point(String name, double lat, double lon) {
    }

    /** @param state DONE, CURRENT or UPCOMING */
    public record LegView(int legNo, String legId, Mode mode, String providerId, String vehicleId,
                          Point from, Point to, Instant startTime, int plannedMinutes, String state) {
    }

    public static TripView of(Trip trip) {
        List<LegView> legs = new ArrayList<>();
        List<TripLeg> tripLegs = trip.legs();
        for (int i = 0; i < tripLegs.size(); i++) {
            TripLeg leg = tripLegs.get(i);
            legs.add(new LegView(i + 1, leg.legId(), leg.mode(), leg.providerId(), leg.vehicleId(),
                    new Point(leg.fromName(), leg.fromLat(), leg.fromLon()),
                    new Point(leg.toName(), leg.toLat(), leg.toLon()),
                    leg.startTime(), leg.plannedMinutes(), stateOf(trip, i)));
        }
        return new TripView(trip.id(), trip.bookingId(), trip.userId(), trip.status().name(), trip.totalPaise(),
                trip.currentLegIndex(), trip.plannedArrival(), "/api/v1/trips/" + trip.id() + "/live", legs);
    }

    private static String stateOf(Trip trip, int index) {
        if (trip.status() == Trip.Status.COMPLETED || index < trip.currentLegIndex()) {
            return "DONE";
        }
        if (trip.status() == Trip.Status.ACTIVE && index == trip.currentLegIndex()) {
            return "CURRENT";
        }
        return "UPCOMING";
    }
}
