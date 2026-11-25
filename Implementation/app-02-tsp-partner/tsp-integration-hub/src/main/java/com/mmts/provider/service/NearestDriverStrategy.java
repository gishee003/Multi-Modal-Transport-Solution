package com.mmts.provider.service;

import com.mmts.provider.domain.Driver;
import com.mmts.provider.domain.Place;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Chooses the free driver closest to the pickup point (straight-line distance), within the maximum distance. */
@Component
public class NearestDriverStrategy implements DriverMatchingStrategy {

    @Override
    public Optional<Driver> choose(List<Driver> candidates, Place pickup, double maxPickupKm) {
        return candidates.stream()
                .filter(d -> distanceKm(d, pickup) <= maxPickupKm)
                .min(Comparator.comparingDouble(d -> distanceKm(d, pickup)));
    }

    static double distanceKm(Driver driver, Place pickup) {
        return Place.distanceKm(driver.getLat(), driver.getLon(), pickup.lat(), pickup.lon());
    }
}
