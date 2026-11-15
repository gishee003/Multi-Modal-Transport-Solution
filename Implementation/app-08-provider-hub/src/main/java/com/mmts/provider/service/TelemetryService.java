package com.mmts.provider.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.provider.api.dto.FleetDtos.DeadLetterView;
import com.mmts.provider.api.dto.FleetDtos.FleetHealth;
import com.mmts.provider.api.dto.FleetDtos.IngestResult;
import com.mmts.provider.api.dto.FleetDtos.Reading;
import com.mmts.provider.api.dto.FleetDtos.VehicleView;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.common.events.EventPublisher;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.common.security.Role;
import com.mmts.provider.domain.DeadLetter;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.ProviderDefinition;
import com.mmts.provider.domain.VehicleState;
import com.mmts.provider.repository.DeadLetterRepository;
import com.mmts.provider.repository.VehicleStateStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The live-data pipeline (M5.3, M5.8, driver D11).
 *
 * For every reading in a batch:
 *   valid   → update the latest-state store, publish VEHICLE_POSITION (and VEHICLE_AVAILABILITY if it changed)
 *   invalid → save it in the dead-letter store with a reason (nothing is silently dropped)
 * A vehicle that sends nothing for 60 s (application.yml) is marked offline.
 */
@Service
public class TelemetryService {

    public static final String VEHICLE_TOPIC = "vehicle.events";

    private final ProviderHubProperties properties;
    private final VehicleStateStore store;
    private final DeadLetterRepository deadLetters;
    private final EventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;

    public TelemetryService(ProviderHubProperties properties, VehicleStateStore store,
                            DeadLetterRepository deadLetters, EventPublisher events, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.deadLetters = deadLetters;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param source where the batch came from (TELEMETRY_API, DRIVER_APP, FEED:ZIPRIDE ...)
     * @param caller the logged-in sender, or null when the hub itself pulled the data from a feed
     */
    @Transactional
    public IngestResult ingest(List<Reading> readings, String source, AuthenticatedUser caller) {
        Instant now = clock.instant();
        int accepted = 0;
        List<String> deadLetterIds = new ArrayList<>();
        for (Reading reading : readings) {
            String problem = problemWith(reading, caller, now);
            if (problem == null) {
                accept(reading, now);
                accepted++;
            } else {
                DeadLetter saved = deadLetters.save(new DeadLetter(source, reading.providerId(), reading.vehicleId(),
                        problem, toJson(reading), now));
                deadLetterIds.add(saved.publicId());
            }
        }
        return new IngestResult(readings.size(), accepted, deadLetterIds.size(), deadLetterIds);
    }

    /** Returns null for a good reading, otherwise the reason it is rejected. */
    String problemWith(Reading r, AuthenticatedUser caller, Instant now) {
        if (r.vehicleId() == null || r.vehicleId().isBlank()) {
            return "MISSING_VEHICLE_ID";
        }
        Optional<ProviderDefinition> provider = properties.findEnabled(r.providerId());
        if (provider.isEmpty()) {
            return "UNKNOWN_PROVIDER";
        }
        if (caller != null && isOnlyOperator(caller) && !provider.get().isOperator(caller.userId())) {
            return "PROVIDER_MISMATCH";
        }
        Mode mode = parseMode(r.mode());
        if (mode == null) {
            return "INVALID_MODE";
        }
        if (!provider.get().getModes().contains(mode)) {
            return "MODE_NOT_SERVED_BY_PROVIDER";
        }
        if (r.lat() == null || r.lon() == null || Math.abs(r.lat()) > 90 || Math.abs(r.lon()) > 180
                || (r.lat() == 0 && r.lon() == 0)) {
            return "INVALID_COORDINATES";
        }
        if (r.batteryPct() != null && (r.batteryPct() < 0 || r.batteryPct() > 100)) {
            return "INVALID_BATTERY";
        }
        if (r.speedKmph() != null && (r.speedKmph() < 0 || r.speedKmph() > 250)) {
            return "INVALID_SPEED";
        }
        Instant recordedAt = parseTime(r.recordedAt());
        if (recordedAt == null) {
            return "INVALID_TIMESTAMP";
        }
        if (recordedAt.isAfter(now.plus(Duration.ofMinutes(2)))) {
            return "TIMESTAMP_IN_FUTURE";
        }
        Optional<VehicleState> previous = store.find(r.vehicleId());
        if (previous.isPresent() && recordedAt.isBefore(previous.get().recordedAt())) {
            return "STALE_READING";   // older than what we already have: kept, but must not move the vehicle back
        }
        return null;
    }

    private void accept(Reading r, Instant now) {
        Optional<VehicleState> previous = store.find(r.vehicleId());
        boolean available = r.available() != null ? r.available() : previous.map(VehicleState::available).orElse(true);
        String providerId = properties.findEnabled(r.providerId()).orElseThrow().getId();
        VehicleState state = new VehicleState(r.vehicleId(), providerId, parseMode(r.mode()), r.lat(), r.lon(),
                r.speedKmph(), r.batteryPct(), available, true, parseTime(r.recordedAt()),
                now.truncatedTo(ChronoUnit.SECONDS));
        store.save(state);
        publishPosition(state);
        boolean changed = previous.isEmpty() || previous.get().available() != available || !previous.get().online();
        if (changed) {
            publishAvailability(state);
        }
    }

    /** Marks vehicles that have been silent for too long as offline. Returns how many changed. */
    public int markSilentVehiclesOffline() {
        Instant limit = clock.instant().minusSeconds(properties.getOfflineAfterSeconds());
        int changed = 0;
        for (VehicleState state : store.all()) {
            if (state.online() && state.lastSeenAt().isBefore(limit)) {
                VehicleState offline = state.offline();
                store.save(offline);
                publishAvailability(offline);
                changed++;
            }
        }
        return changed;
    }

    /** Called by the hold flow: a held vehicle is no longer available to others, a released one is again. */
    public void setAvailability(String vehicleId, boolean available) {
        store.find(vehicleId).filter(s -> s.available() != available).ifPresent(s -> {
            VehicleState updated = s.withAvailable(available);
            store.save(updated);
            publishAvailability(updated);
        });
    }

    public Optional<VehicleState> latest(String vehicleId) {
        return store.find(vehicleId);
    }

    /** M5.8: battery, offline and last-seen for one provider's vehicles. OPERATOR sees only their own provider. */
    public FleetHealth fleetHealth(String providerId, AuthenticatedUser caller) {
        ProviderDefinition provider = properties.findEnabled(providerId)
                .orElseThrow(() -> ApiException.notFound("PROVIDER_NOT_FOUND", "No active provider with id " + providerId));
        if (isOnlyOperator(caller) && !provider.isOperator(caller.userId())) {
            throw ApiException.forbidden("NOT_YOUR_PROVIDER", "An operator can only see their own provider's fleet");
        }
        int lowLimit = properties.getLowBatteryPct();
        List<VehicleView> vehicles = store.findByProvider(provider.getId()).stream()
                .map(s -> new VehicleView(s.vehicleId(), s.mode().name(), s.lat(), s.lon(), s.batteryPct(),
                        s.available(), s.online(), s.batteryPct() != null && s.batteryPct() < lowLimit, s.lastSeenAt()))
                .toList();
        int online = (int) vehicles.stream().filter(VehicleView::online).count();
        int low = (int) vehicles.stream().filter(VehicleView::lowBattery).count();
        return new FleetHealth(provider.getId(), vehicles.size(), online, vehicles.size() - online, low, vehicles);
    }

    /** The dead-letter store, newest first. An operator sees only their own providers' entries. */
    @Transactional(readOnly = true)
    public List<DeadLetterView> deadLetters(AuthenticatedUser caller) {
        return deadLetters.findTop100ByOrderByIdDesc().stream()
                .filter(d -> !isOnlyOperator(caller) || properties.findEnabled(d.getProviderId())
                        .map(p -> p.isOperator(caller.userId())).orElse(false))
                .map(d -> new DeadLetterView(d.publicId(), d.getSource(), d.getProviderId(), d.getVehicleId(),
                        d.getReason(), d.getRawReading(), d.getReceivedAt()))
                .toList();
    }

    private void publishPosition(VehicleState s) {
        Map<String, Object> payload = basePayload(s);
        payload.put("lat", s.lat());
        payload.put("lon", s.lon());
        // The shared publisher does not accept null values, so an unknown speed / battery is left out.
        if (s.speedKmph() != null) {
            payload.put("speedKmph", s.speedKmph());
        }
        if (s.batteryPct() != null) {
            payload.put("batteryPct", s.batteryPct());
        }
        payload.put("recordedAt", s.recordedAt().toString());
        events.publish(VEHICLE_TOPIC, "VEHICLE_POSITION", payload);
    }

    private void publishAvailability(VehicleState s) {
        Map<String, Object> payload = basePayload(s);
        payload.put("available", s.available() && s.online());
        payload.put("lat", s.lat());
        payload.put("lon", s.lon());
        events.publish(VEHICLE_TOPIC, "VEHICLE_AVAILABILITY", payload);
    }

    private static Map<String, Object> basePayload(VehicleState s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vehicleId", s.vehicleId());
        payload.put("providerId", s.providerId());
        payload.put("mode", s.mode().name());
        return payload;
    }

    /** OPERATOR without ADMIN or SERVICE: limited to their own provider. */
    private static boolean isOnlyOperator(AuthenticatedUser caller) {
        return caller != null && caller.hasRole(Role.OPERATOR)
                && !caller.hasRole(Role.ADMIN) && !caller.hasRole(Role.SERVICE);
    }

    private static Mode parseMode(String mode) {
        try {
            return mode == null ? null : Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseTime(String text) {
        try {
            return text == null ? null : Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String toJson(Reading reading) {
        try {
            return json.writeValueAsString(reading);
        } catch (JsonProcessingException e) {
            return String.valueOf(reading);
        }
    }
}
