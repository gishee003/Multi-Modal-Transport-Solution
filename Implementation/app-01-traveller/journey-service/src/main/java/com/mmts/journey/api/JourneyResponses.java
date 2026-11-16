package com.mmts.journey.api;

import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.domain.journey.Leg;
import com.mmts.journey.service.JourneySearchService.SearchResult;
import com.mmts.journey.service.VehicleService.NearbyVehicle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The JSON shapes returned by the journey endpoints (DTOs, as Java records). */
public final class JourneyResponses {

    private JourneyResponses() {
    }

    /** The shared "Place" value of docs/CONTRACTS.md, plus the kind of point. */
    public record PlaceView(String stopId, String name, double lat, double lon, String kind) {
        static PlaceView of(Stop stop) {
            return new PlaceView(stop.id(), stop.name(), stop.lat(), stop.lon(), stop.kind().name());
        }
    }

    public record LegView(int legNo, Mode mode, PlaceView from, PlaceView to, long distanceMetres, int minutes,
                          long farePaise, List<String> routeNames, String providerId, String vehicleId,
                          String instruction) {
    }

    public record OptionView(String optionId, int rank, int totalMinutes, long totalFarePaise, int changes,
                             List<Mode> modes, List<LegView> legs) {
    }

    /**
     * @param computeMillis   how long the route search itself took
     * @param responseMillis  how long this request took inside the service (tiny when served from cache)
     * @param servedFromCache true when the answer came from the cache (driver D4)
     */
    public record SearchResponse(PlaceView from, PlaceView to, String rankBy, long computeMillis, long responseMillis,
                                 boolean servedFromCache, int optionCount, List<OptionView> options) {
    }

    public record NearbyVehicleView(String vehicleId, String providerId, Mode mode, double lat, double lon,
                                    long distanceMetres, Integer batteryPct, Instant recordedAt,
                                    long lastSeenSeconds, boolean stale) {
    }

    static SearchResponse of(SearchResult result) {
        List<OptionView> options = new ArrayList<>();
        int rank = 1;
        for (Journey journey : result.options()) {
            options.add(of(journey, rank++));
        }
        return new SearchResponse(PlaceView.of(result.from()), PlaceView.of(result.to()), result.rankBy(),
                result.computeMillis(), result.responseMillis(), result.servedFromCache(), options.size(), options);
    }

    static OptionView of(Journey journey, int rank) {
        List<LegView> legs = new ArrayList<>();
        int legNo = 1;
        for (Leg leg : journey.legs()) {
            legs.add(new LegView(legNo++, leg.mode(), PlaceView.of(leg.from()), PlaceView.of(leg.to()),
                    Math.round(leg.metres()), leg.minutes(), leg.farePaise(), leg.routeNames(), leg.providerId(),
                    leg.vehicleId(), leg.instruction()));
        }
        return new OptionView(journey.optionId(), rank, journey.minutes(), journey.farePaise(), journey.changes(),
                journey.modes(), legs);
    }

    static NearbyVehicleView of(NearbyVehicle n) {
        var v = n.vehicle();
        return new NearbyVehicleView(v.vehicleId(), v.providerId(), v.mode(), v.lat(), v.lon(),
                Math.round(n.distanceMetres()), v.batteryPct(), v.recordedAt(), n.lastSeenSeconds(), n.stale());
    }
}
