package com.mmts.provider.service;

import com.mmts.provider.domain.Driver;
import com.mmts.provider.domain.Place;

import java.util.List;
import java.util.Optional;

/**
 * Strategy pattern: how to choose a driver for a pickup. Today: nearest driver ({@link NearestDriverStrategy}).
 * Another rule (best rating, fairness between drivers, shortest ETA by road) would be a new class, not an edit.
 */
public interface DriverMatchingStrategy {

    /** @param candidates free drivers only */
    Optional<Driver> choose(List<Driver> candidates, Place pickup, double maxPickupKm);
}
