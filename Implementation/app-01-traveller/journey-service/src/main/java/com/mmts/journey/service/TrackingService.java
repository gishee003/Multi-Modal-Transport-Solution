package com.mmts.journey.service;

import com.mmts.journey.adapter.PushNotifier;
import com.mmts.journey.common.error.ApiException;
import com.mmts.journey.common.security.AuthenticatedUser;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Trip;
import com.mmts.journey.domain.TripLeg;
import com.mmts.journey.domain.VehicleState;
import com.mmts.journey.repository.VehiclePositionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live trip tracking (M2.5) and alerts (M2.6), driver D5.
 *
 * The traveller's app opens one long HTTP response (Server-Sent Events, SSE) and the server pushes messages into it
 * whenever something happens, so the app never has to ask again and again ("polling").
 * Messages on the stream: snapshot (on connect), position (each new vehicle position), status (every few seconds,
 * shows when the position became stale), alert (DELAY / SWITCH_MODE), leg-completed, ended.
 */
@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);
    private static final double ROAD_DETOUR = 1.3;
    private static final double ARRIVED_METRES = 100;
    private static final double ALTERNATIVE_RADIUS_METRES = 1000;

    /** One live message about a trip. lat/lon/lastSeenSeconds are null until the vehicle has reported once. */
    public record LiveUpdate(String tripId, String status, int legIndex, String legId, Mode mode, String vehicleId,
                             Double lat, Double lon, Instant recordedAt, Long lastSeenSeconds, boolean stale,
                             int etaMinutes, int delayMinutes, Instant at) {
    }

    /** An alert pushed to the traveller. type is DELAY or SWITCH_MODE. */
    public record TripAlert(String tripId, String type, String message, String suggestion, Instant at) {
    }

    private final TripService trips;
    private final VehiclePositionStore vehicles;
    private final PushNotifier pushNotifier;
    private final Clock clock;
    private final long staleAfterSeconds;
    private final long switchModeAfterSeconds;
    private final int delayAlertMinutes;
    private final long streamTimeoutMillis;

    private final Map<String, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> alertsSent = new ConcurrentHashMap<>();

    public TrackingService(TripService trips, VehiclePositionStore vehicles, PushNotifier pushNotifier, Clock clock,
                           @Value("${mmts.journey.tracking.stale-after-seconds}") long staleAfterSeconds,
                           @Value("${mmts.journey.tracking.switch-mode-after-seconds}") long switchModeAfterSeconds,
                           @Value("${mmts.journey.tracking.delay-alert-minutes}") int delayAlertMinutes,
                           @Value("${mmts.journey.tracking.stream-timeout-minutes}") long streamTimeoutMinutes) {
        this.trips = trips;
        this.vehicles = vehicles;
        this.pushNotifier = pushNotifier;
        this.clock = clock;
        this.staleAfterSeconds = staleAfterSeconds;
        this.switchModeAfterSeconds = switchModeAfterSeconds;
        this.delayAlertMinutes = delayAlertMinutes;
        this.streamTimeoutMillis = Duration.ofMinutes(streamTimeoutMinutes).toMillis();
    }

    /** Opens a live stream for the trip's owner and sends the current state at once. */
    public SseEmitter subscribe(String tripId, AuthenticatedUser user) {
        Trip trip = trips.requireOwned(tripId, user, "TRACK_TRIP");
        if (!trip.isActive()) {
            throw ApiException.conflict("TRIP_NOT_ACTIVE", "Trip " + tripId + " is " + trip.status());
        }
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        streams.computeIfAbsent(tripId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(tripId, emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> remove(tripId, emitter));
        send(tripId, emitter, "snapshot", liveStatus(trip));
        return emitter;
    }

    /** Called for every new vehicle position: if a trip rides on this vehicle, push the news immediately. */
    public void onVehicleUpdate(VehicleState vehicle) {
        for (Trip trip : trips.activeTrips()) {
            Optional<TripLeg> current = trip.currentLeg();
            if (current.isEmpty() || !vehicle.vehicleId().equals(current.get().vehicleId())) {
                continue;
            }
            TripLeg leg = current.get();
            Trip updated = trip;
            if (vehicle.metresTo(leg.toLat(), leg.toLon()) <= ARRIVED_METRES) {
                updated = trips.legArrived(trip.id());
                push(trip.id(), "leg-completed", Map.of("tripId", trip.id(), "legId", leg.legId(), "to", leg.toName()));
            }
            if (!updated.isActive()) {
                finish(updated);
                continue;
            }
            LiveUpdate update = liveStatus(updated);
            push(trip.id(), "position", update);
            checkAlerts(updated, update);
        }
    }

    /**
     * Every few seconds, tell each watcher the current state. This is what makes a silent vehicle visible:
     * no new position means the age grows until the update says "stale".
     */
    @Scheduled(fixedRateString = "${mmts.journey.tracking.status-interval-ms}")
    public void publishStatusToWatchers() {
        for (String tripId : List.copyOf(streams.keySet())) {
            Optional<Trip> trip = trips.find(tripId);
            if (trip.isEmpty() || !trip.get().isActive()) {
                trip.ifPresent(this::finish);
                continue;
            }
            LiveUpdate update = liveStatus(trip.get());
            push(tripId, "status", update);
            checkAlerts(trip.get(), update);
        }
    }

    /** Where the trip's vehicle is, how fresh that is, and the expected arrival. */
    public LiveUpdate liveStatus(Trip trip) {
        Instant now = clock.instant();
        TripLeg leg = trip.currentLeg().orElse(null);
        if (leg == null) {
            return new LiveUpdate(trip.id(), trip.status().name(), trip.currentLegIndex(), null, null, null,
                    null, null, null, null, false, 0, 0, now);
        }
        VehicleState vehicle = leg.vehicleId() == null ? null : vehicles.find(leg.vehicleId()).orElse(null);
        int remainingThisLeg = leg.plannedMinutes();
        if (vehicle != null) {
            double speed = vehicle.speedKmph() != null && vehicle.speedKmph() > 1 ? vehicle.speedKmph() : leg.mode().kmph();
            double metresLeft = vehicle.metresTo(leg.toLat(), leg.toLon()) * ROAD_DETOUR;
            remainingThisLeg = (int) Math.ceil(metresLeft / (speed * 1000 / 60));
        }
        int eta = remainingThisLeg + trip.legsAfterCurrent().stream().mapToInt(TripLeg::plannedMinutes).sum();
        Instant expected = now.plusSeconds(60L * eta);
        int delay = (int) Math.max(0, Duration.between(trip.plannedArrival(), expected).toMinutes());
        return new LiveUpdate(trip.id(), trip.status().name(), trip.currentLegIndex(), leg.legId(), leg.mode(),
                leg.vehicleId(),
                vehicle == null ? null : vehicle.lat(), vehicle == null ? null : vehicle.lon(),
                vehicle == null ? null : vehicle.recordedAt(),
                vehicle == null ? null : vehicle.ageSeconds(now),
                vehicle == null || vehicle.isStale(now, staleAfterSeconds),
                eta, delay, now);
    }

    /**
     * Alert rules (M2.6), each sent once per leg, on the stream and as a push notification:
     * - DELAY: expected arrival is at least delay-alert-minutes later than planned.
     * - SWITCH_MODE: the vehicle has been silent for switch-mode-after-seconds; suggest the nearest free vehicle.
     */
    public List<TripAlert> checkAlerts(Trip trip, LiveUpdate update) {
        List<TripAlert> sent = new ArrayList<>();
        if (update.legId() == null) {
            return sent;
        }
        if (update.delayMinutes() >= delayAlertMinutes) {
            alert(trip, "DELAY", "Running about " + update.delayMinutes() + " min late",
                    "Expected arrival in " + update.etaMinutes() + " min").ifPresent(sent::add);
        }
        if (update.lastSeenSeconds() != null && update.lastSeenSeconds() >= switchModeAfterSeconds) {
            alert(trip, "SWITCH_MODE", "Your " + update.mode() + " " + update.vehicleId() + " has not reported for "
                    + update.lastSeenSeconds() + " s", alternativeNear(update)).ifPresent(sent::add);
        }
        return sent;
    }

    private String alternativeNear(LiveUpdate update) {
        return vehicles.availableNear(null, update.lat(), update.lon(), ALTERNATIVE_RADIUS_METRES).stream()
                .filter(v -> !v.vehicleId().equals(update.vehicleId()))
                .findFirst()
                .map(v -> "Switch mode: free " + v.mode() + " " + v.vehicleId() + " is "
                        + Math.round(v.metresTo(update.lat(), update.lon())) + " m away")
                .orElse("Switch mode: book a taxi from where you are");
    }

    private Optional<TripAlert> alert(Trip trip, String type, String message, String suggestion) {
        String key = type + ":" + trip.currentLegIndex();
        boolean firstTime = alertsSent.computeIfAbsent(trip.id(), id -> ConcurrentHashMap.newKeySet()).add(key);
        if (!firstTime) {
            return Optional.empty();
        }
        TripAlert alert = new TripAlert(trip.id(), type, message, suggestion, clock.instant());
        push(trip.id(), "alert", alert);
        pushNotifier.send(trip.userId(), type.equals("DELAY") ? "Trip delayed" : "Switch mode?", message + ". " + suggestion);
        return Optional.of(alert);
    }

    /** Number of open streams for a trip (used by tests and logs). */
    public int watcherCount(String tripId) {
        return streams.getOrDefault(tripId, List.of()).size();
    }

    private void finish(Trip trip) {
        push(trip.id(), "ended", Map.of("tripId", trip.id(), "status", trip.status().name()));
        List<SseEmitter> emitters = streams.remove(trip.id());
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
        alertsSent.remove(trip.id());
    }

    private void push(String tripId, String eventName, Object data) {
        for (SseEmitter emitter : streams.getOrDefault(tripId, List.of())) {
            send(tripId, emitter, eventName, data);
        }
    }

    private void send(String tripId, SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Stream for trip {} closed: {}", tripId, e.getMessage());
            remove(tripId, emitter);
        }
    }

    private void remove(String tripId, SseEmitter emitter) {
        List<SseEmitter> list = streams.get(tripId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
