package com.mmts.provider.repository;

import com.mmts.provider.domain.VehicleState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The latest-state store: one entry per vehicle, overwritten by each new reading.
 * Production: Redis (CACHE-2 in the architecture document), key "vehicle:{vehicleId}".
 * Demo: {@link InMemoryVehicleStateStore}. Business code only sees this interface (principle P6).
 */
public interface VehicleStateStore {

    void save(VehicleState state);

    Optional<VehicleState> find(String vehicleId);

    List<VehicleState> findByProvider(String providerId);

    Collection<VehicleState> all();
}
