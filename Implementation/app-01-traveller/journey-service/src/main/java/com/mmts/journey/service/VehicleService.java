package com.mmts.journey.service;

import com.mmts.journey.common.error.ApiException;
import com.mmts.journey.common.events.DomainEvent;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.VehicleState;
import com.mmts.journey.repository.VehiclePositionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Vehicle positions (M2.2).
 * Listens to vehicle.events from Provider Hub service (INT-14), keeps the newest position of each vehicle in the in-memory store,
 * tells live tracking about it straight away (push, driver D5), and answers "what is near me right now".
 */
@Service
public class VehicleService {

    /** One vehicle in a "nearby" answer, with how fresh its position is. */
    public record NearbyVehicle(VehicleState vehicle, double distanceMetres, long lastSeenSeconds, boolean stale) {
    }

    private static final int MAX_RADIUS_METRES = 5000;

    private final VehiclePositionStore store;
    private final TrackingService tracking;
    private final Clock clock;
    private final long staleAfterSeconds;

    public VehicleService(VehiclePositionStore store, TrackingService tracking, Clock clock,
                          @Value("${mmts.journey.tracking.stale-after-seconds}") long staleAfterSeconds) {
        this.store = store;
        this.tracking = tracking;
        this.clock = clock;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    /** Receives VEHICLE_POSITION and VEHICLE_AVAILABILITY events (Observer pattern via the event bus). */
    @EventListener(condition = "#event.topic == 'vehicle.events'")
    public void onVehicleEvent(DomainEvent event) {
        Map<String, Object> p = event.payload();
        String vehicleId = EventPayloads.text(p, "vehicleId");
        if (vehicleId == null) {
            return;
        }
        VehicleState previous = store.find(vehicleId).orElse(null);
        Mode mode = Mode.valueOf(EventPayloads.text(p, "mode"));
        double lat = EventPayloads.number(p, "lat", previous == null ? 0 : previous.lat());
        double lon = EventPayloads.number(p, "lon", previous == null ? 0 : previous.lon());

        if ("VEHICLE_POSITION".equals(event.type())) {
            Double battery = EventPayloads.number(p, "batteryPct");
            VehicleState state = new VehicleState(vehicleId, EventPayloads.text(p, "providerId"), mode, lat, lon,
                    EventPayloads.number(p, "speedKmph"), battery == null ? null : battery.intValue(),
                    previous == null || previous.available(),
                    EventPayloads.time(p, "recordedAt", event.occurredAt()));
            store.save(state);
            tracking.onVehicleUpdate(state);
        } else if ("VEHICLE_AVAILABILITY".equals(event.type())) {
            boolean available = EventPayloads.flag(p, "available", true);
            VehicleState state = new VehicleState(vehicleId, EventPayloads.text(p, "providerId"), mode, lat, lon,
                    previous == null ? null : previous.speedKmph(), previous == null ? null : previous.batteryPct(),
                    available, event.occurredAt());
            store.save(state);
        }
    }

    /** Free vehicles near a point, nearest first, each with "last seen" seconds and the stale flag. */
    public List<NearbyVehicle> nearby(double lat, double lon, int radiusMetres, String modeText) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw ApiException.badRequest("INVALID_LOCATION", "lat must be -90..90 and lon -180..180");
        }
        if (radiusMetres < 1 || radiusMetres > MAX_RADIUS_METRES) {
            throw ApiException.badRequest("INVALID_RADIUS", "radiusMetres must be 1.." + MAX_RADIUS_METRES);
        }
        Mode mode = null;
        if (modeText != null && !modeText.isBlank()) {
            String name = modeText.trim().toUpperCase();
            mode = Arrays.stream(Mode.values()).filter(m -> m.name().equals(name)).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("UNKNOWN_MODE", "Unknown mode '" + modeText + "'"));
        }
        Instant now = clock.instant();
        return store.availableNear(mode, lat, lon, radiusMetres).stream()
                .map(v -> new NearbyVehicle(v, v.metresTo(lat, lon), v.ageSeconds(now), v.isStale(now, staleAfterSeconds)))
                .toList();
    }
}
