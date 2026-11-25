package com.mmts.provider.repository;

import com.mmts.provider.domain.VehicleState;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Demo stand-in for Redis: a thread-safe map from vehicleId to its latest state. */
@Repository
public class InMemoryVehicleStateStore implements VehicleStateStore {

    private final Map<String, VehicleState> states = new ConcurrentHashMap<>();

    @Override
    public void save(VehicleState state) {
        states.put(state.vehicleId(), state);
    }

    @Override
    public Optional<VehicleState> find(String vehicleId) {
        return vehicleId == null ? Optional.empty() : Optional.ofNullable(states.get(vehicleId));
    }

    @Override
    public List<VehicleState> findByProvider(String providerId) {
        return states.values().stream()
                .filter(s -> s.providerId().equals(providerId))
                .sorted(Comparator.comparing(VehicleState::vehicleId))
                .toList();
    }

    @Override
    public Collection<VehicleState> all() {
        return List.copyOf(states.values());
    }
}
