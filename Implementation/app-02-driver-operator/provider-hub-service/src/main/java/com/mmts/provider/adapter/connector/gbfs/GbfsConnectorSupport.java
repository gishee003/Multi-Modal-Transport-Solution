package com.mmts.provider.adapter.connector.gbfs;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.adapter.connector.gbfs.GbfsFeedParser.GbfsVehicle;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.Place;
import com.mmts.provider.domain.ProviderDefinition;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared logic for dock-less vehicles published through GBFS (scooters, bikes).
 * Each plug-in that extends this keeps a small simulation of the operator's fleet: which vehicles are
 * reserved, their battery, and where they are. The feed file is named by the provider setting "feed".
 */
public abstract class GbfsConnectorSupport implements ProviderConnector {

    /** One vehicle as the operator sees it. */
    protected static final class Slot {
        final GbfsVehicle vehicle;
        volatile boolean reserved;

        Slot(GbfsVehicle vehicle) {
            this.vehicle = vehicle;
            this.reserved = vehicle.reserved();
        }
    }

    private final Map<String, Map<String, Slot>> fleets = new ConcurrentHashMap<>();

    /** The travel mode of this operator's vehicles. */
    protected abstract Mode mode();

    /** Can this vehicle be rented now? Scooters add a battery rule. */
    protected boolean usable(ProviderDefinition provider, Slot slot) {
        return !slot.reserved && !slot.vehicle.disabled();
    }

    protected Map<String, Slot> fleet(ProviderDefinition provider) {
        return fleets.computeIfAbsent(provider.getId(), id -> {
            Map<String, Slot> slots = new LinkedHashMap<>();
            for (GbfsVehicle v : GbfsFeedParser.parseClasspath(provider.setting("feed", "")).vehicles()) {
                slots.put(v.vehicleId(), new Slot(v));
            }
            return slots;
        });
    }

    @Override
    public synchronized HoldQuote hold(ProviderDefinition provider, LegRequest leg) {
        Map<String, Slot> fleet = fleet(provider);
        Slot chosen;
        if (leg.vehicleId() != null) {
            chosen = fleet.get(leg.vehicleId());
            if (chosen == null || !usable(provider, chosen)) {
                throw ProviderConnector.notAvailable("Vehicle " + leg.vehicleId() + " cannot be rented now");
            }
        } else {
            // No vehicle named: take the nearest usable one to the start of the leg.
            chosen = fleet.values().stream()
                    .filter(s -> usable(provider, s))
                    .min(Comparator.comparingDouble(s -> Place.distanceKm(s.vehicle.lat(), s.vehicle.lon(),
                            leg.from().lat(), leg.from().lon())))
                    .orElseThrow(() -> ProviderConnector.notAvailable("No free vehicle at " + provider.getId()));
        }
        chosen.reserved = true;
        return new HoldQuote(provider.priceFor(leg.from(), leg.to()), chosen.vehicle.vehicleId(),
                "gbfs-res-" + leg.holdId());
    }

    @Override
    public AccessGrant confirm(ProviderDefinition provider, LegRequest leg, String providerRef) {
        String code = provider.setting("code-prefix", provider.getId()) + "-"
                + (1000 + ThreadLocalRandom.current().nextInt(9000));
        return AccessGrant.of("UNLOCK_CODE", code, leg.startTime(), 60, null);
    }

    @Override
    public synchronized void release(ProviderDefinition provider, LegRequest leg, String providerRef) {
        Slot slot = fleet(provider).get(leg.vehicleId());
        if (slot != null) {
            slot.reserved = false;
        }
    }

    @Override
    public void unlock(ProviderDefinition provider, LegRequest leg, String providerRef) {
        Slot slot = fleet(provider).get(leg.vehicleId());
        if (slot == null || !slot.reserved) {
            throw ProviderConnector.notAvailable("Vehicle " + leg.vehicleId() + " is not reserved for this hold");
        }
        // Here the real operator API would send the unlock command to the vehicle over its SIM.
    }

    /** Re-reading the GBFS feed: the current status of every vehicle. */
    @Override
    public List<VehicleReading> pollVehicles(ProviderDefinition provider, Instant now) {
        return fleet(provider).values().stream()
                .map(s -> new VehicleReading(s.vehicle.vehicleId(), mode(), s.vehicle.lat(), s.vehicle.lon(), 0.0,
                        s.vehicle.batteryPct(), usable(provider, s), now))
                .toList();
    }

    /** For tests: is this vehicle reserved at the operator? */
    public boolean isReserved(ProviderDefinition provider, String vehicleId) {
        Slot slot = fleet(provider).get(vehicleId);
        return slot != null && slot.reserved;
    }
}
