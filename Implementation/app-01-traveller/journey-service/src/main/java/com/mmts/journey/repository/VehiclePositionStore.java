package com.mmts.journey.repository;

import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.VehicleState;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latest known position of every vehicle, kept in memory.
 * This is the stand-in for the Redis cache CACHE-1 in the architecture: only the newest position per vehicle is kept,
 * so reading "what is near me" never touches the database and stays fast.
 */
@Component
public class VehiclePositionStore {

    private final Map<String, VehicleState> latest = new ConcurrentHashMap<>();

    /**
     * Saves a new state. An update that is older than what we already have is ignored, because events can arrive
     * out of order and an old position must never overwrite a newer one.
     */
    public void save(VehicleState state) {
        latest.merge(state.vehicleId(), state,
                (old, incoming) -> incoming.recordedAt().isBefore(old.recordedAt()) ? old : incoming);
    }

    public Optional<VehicleState> find(String vehicleId) {
        return Optional.ofNullable(latest.get(vehicleId));
    }

    public List<VehicleState> all() {
        return List.copyOf(latest.values());
    }

    /** Available vehicles within the radius, nearest first. {@code mode} null means any mode. */
    public List<VehicleState> availableNear(Mode mode, double lat, double lon, double radiusMetres) {
        return latest.values().stream()
                .filter(VehicleState::available)
                .filter(v -> mode == null || v.mode() == mode)
                .filter(v -> v.metresTo(lat, lon) <= radiusMetres)
                .sorted(Comparator.comparingDouble(v -> v.metresTo(lat, lon)))
                .toList();
    }

    public void clear() {
        latest.clear();
    }
}
