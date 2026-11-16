package com.mmts.journey.adapter;

import com.mmts.journey.common.events.EventPublisher;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.VehicleState;
import com.mmts.journey.service.TripService;
import com.mmts.journey.service.TripService.MovingVehicle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo only: pretends to be Provider Hub service (Provider Integration Hub), which does not run in the demo.
 *
 * - At start-up it announces the sample vehicles (VEHICLE_AVAILABILITY).
 * - Every few seconds it publishes a VEHICLE_POSITION for every vehicle: parked ones stay put, taxis cruise,
 *   and the vehicle of every active trip moves a step along its leg until it arrives.
 *
 * Everything goes through the shared EventPublisher, so the events are visible at GET /dev/events and are received
 * by VehicleService exactly as real Kafka messages from Provider Hub service would be.
 */
@Component
@Profile("demo")
public class VehicleFeedSimulator {

    private static final String TOPIC = "vehicle.events";

    private final EventPublisher events;
    private final TripService trips;
    private final Clock clock;
    private final int stepsPerLeg;

    private final Map<String, VehicleState> fleet = new ConcurrentHashMap<>();
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    private final Set<String> inUse = ConcurrentHashMap.newKeySet();
    private long ticks;

    public VehicleFeedSimulator(EventPublisher events, TripService trips, Clock clock,
                                @Value("${mmts.journey.simulator.steps-per-leg}") int stepsPerLeg) {
        this.events = events;
        this.trips = trips;
        this.clock = clock;
        this.stepsPerLeg = stepsPerLeg;
    }

    /** Announces the sample vehicles once the application has started. */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        for (VehicleState v : SampleDelhiNetwork.sampleVehicles(clock.instant())) {
            fleet.put(v.vehicleId(), v);
            publishAvailability(v, true);
        }
    }

    /** One simulator step. Runs on a timer (switched off in tests by mmts.journey.scheduler.enabled=false). */
    @Scheduled(fixedRateString = "${mmts.journey.simulator.interval-ms}",
            initialDelayString = "${mmts.journey.simulator.interval-ms}")
    public void tick() {
        ticks++;
        Instant now = clock.instant();
        Set<String> moving = new HashSet<>();

        for (MovingVehicle m : trips.vehiclesInMotion()) {
            moving.add(m.vehicleId());
            if (paused.contains(m.vehicleId())) {
                continue;   // demo: this vehicle "lost signal"
            }
            VehicleState current = fleet.getOrDefault(m.vehicleId(), new VehicleState(m.vehicleId(), m.providerId(),
                    m.mode(), m.fromLat(), m.fromLon(), 0.0, null, false, now));
            if (inUse.add(m.vehicleId())) {
                publishAvailability(current, false);   // the booked vehicle is no longer free for others
            }
            VehicleState next = stepTowards(current, m, now);
            fleet.put(next.vehicleId(), next);
            publishPosition(next);
        }

        for (VehicleState v : List.copyOf(fleet.values())) {
            if (moving.contains(v.vehicleId()) || paused.contains(v.vehicleId())) {
                continue;
            }
            if (inUse.remove(v.vehicleId())) {
                if (v.mode() == Mode.METRO || v.mode() == Mode.BUS) {
                    fleet.remove(v.vehicleId());   // the train / bus for that leg is no longer followed
                    continue;
                }
                publishAvailability(v, true);      // ride finished: free again where it was left
            }
            VehicleState next = v.mode() == Mode.TAXI ? cruise(v, now) : parked(v, now);
            fleet.put(next.vehicleId(), next);
            publishPosition(next);
        }
    }

    /** Demo helper: stop (or restart) the feed of one vehicle, to show the "stale" flag and SWITCH_MODE alert. */
    public void setPaused(String vehicleId, boolean pause) {
        if (pause) {
            paused.add(vehicleId);
        } else {
            paused.remove(vehicleId);
        }
    }

    public Set<String> pausedVehicles() {
        return Set.copyOf(paused);
    }

    private VehicleState stepTowards(VehicleState v, MovingVehicle m, Instant now) {
        double legMetres = Stop.metresBetween(m.fromLat(), m.fromLon(), m.toLat(), m.toLon());
        double left = Stop.metresBetween(v.lat(), v.lon(), m.toLat(), m.toLon());
        double step = Math.max(legMetres / stepsPerLeg, 1);
        double fraction = left <= step ? 1.0 : step / left;
        double lat = v.lat() + (m.toLat() - v.lat()) * fraction;
        double lon = v.lon() + (m.toLon() - v.lon()) * fraction;
        return new VehicleState(v.vehicleId(), m.providerId(), m.mode(), lat, lon, m.mode().kmph(), v.batteryPct(),
                false, now);
    }

    private VehicleState cruise(VehicleState v, Instant now) {
        double offset = (ticks % 2 == 0 ? 1 : -1) * 0.0008;
        return new VehicleState(v.vehicleId(), v.providerId(), v.mode(), v.lat() + offset, v.lon(), 20.0,
                v.batteryPct(), v.available(), now);
    }

    private static VehicleState parked(VehicleState v, Instant now) {
        return new VehicleState(v.vehicleId(), v.providerId(), v.mode(), v.lat(), v.lon(), 0.0, v.batteryPct(),
                v.available(), now);
    }

    private void publishPosition(VehicleState v) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("vehicleId", v.vehicleId());
        payload.put("providerId", v.providerId());
        payload.put("mode", v.mode().name());
        payload.put("lat", v.lat());
        payload.put("lon", v.lon());
        payload.put("speedKmph", v.speedKmph() == null ? 0.0 : v.speedKmph());
        if (v.batteryPct() != null) {          // contract: may be null; the shared publisher does not take nulls
            payload.put("batteryPct", v.batteryPct());
        }
        payload.put("recordedAt", v.recordedAt().toString());
        events.publish(TOPIC, "VEHICLE_POSITION", payload);
    }

    private void publishAvailability(VehicleState v, boolean available) {
        events.publish(TOPIC, "VEHICLE_AVAILABILITY", Map.of(
                "vehicleId", v.vehicleId(),
                "providerId", v.providerId(),
                "mode", v.mode().name(),
                "available", available,
                "lat", v.lat(),
                "lon", v.lon()));
    }
}
