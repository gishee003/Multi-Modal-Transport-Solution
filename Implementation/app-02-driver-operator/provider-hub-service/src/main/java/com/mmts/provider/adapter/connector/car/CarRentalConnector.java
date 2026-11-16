package com.mmts.provider.adapter.connector.car;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.ProviderDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plug-in 4: self-drive car rental (e.g. DRIVEEASY) through the partner's reservation API (stubbed here).
 * The fleet and the parking hub come from application.yml settings "fleet", "parking-lat", "parking-lon".
 * hold = reservation, confirm = digital key (unlock code), unlock = remote unlock command to the car.
 */
@Component
public class CarRentalConnector implements ProviderConnector {

    private static final Logger log = LoggerFactory.getLogger(CarRentalConnector.class);

    /** providerId → set of reserved car ids (the partner system's view). */
    private final Map<String, Set<String>> reserved = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "car-rental";
    }

    private List<String> fleet(ProviderDefinition provider) {
        return Arrays.stream(provider.setting("fleet", "").split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    private Set<String> reservedOf(ProviderDefinition provider) {
        return reserved.computeIfAbsent(provider.getId(), id -> ConcurrentHashMap.newKeySet());
    }

    @Override
    public synchronized HoldQuote hold(ProviderDefinition provider, LegRequest leg) {
        Set<String> taken = reservedOf(provider);
        String car;
        if (leg.vehicleId() != null) {
            if (!fleet(provider).contains(leg.vehicleId()) || taken.contains(leg.vehicleId())) {
                throw ProviderConnector.notAvailable("Car " + leg.vehicleId() + " is not free");
            }
            car = leg.vehicleId();
        } else {
            car = fleet(provider).stream().filter(c -> !taken.contains(c)).findFirst()
                    .orElseThrow(() -> ProviderConnector.notAvailable("All cars of " + provider.getId() + " are booked"));
        }
        taken.add(car);
        return new HoldQuote(provider.priceFor(leg.from(), leg.to()), car, "rsv-" + leg.holdId());
    }

    @Override
    public AccessGrant confirm(ProviderDefinition provider, LegRequest leg, String providerRef) {
        String key = provider.setting("code-prefix", "CAR") + "-" + (100000 + ThreadLocalRandom.current().nextInt(900000));
        return AccessGrant.of("UNLOCK_CODE", key, leg.startTime(), provider.intSetting("rental-minutes", 240), null);
    }

    @Override
    public synchronized void release(ProviderDefinition provider, LegRequest leg, String providerRef) {
        reservedOf(provider).remove(leg.vehicleId());
    }

    @Override
    public void unlock(ProviderDefinition provider, LegRequest leg, String providerRef) {
        if (!reservedOf(provider).contains(leg.vehicleId())) {
            throw ProviderConnector.notAvailable("Car " + leg.vehicleId() + " is not reserved for this hold");
        }
        log.info("Remote unlock sent to car {} (reservation {})", leg.vehicleId(), providerRef);
    }

    /** The partner API reports every car parked at the hub; a reserved car is not available. */
    @Override
    public List<VehicleReading> pollVehicles(ProviderDefinition provider, Instant now) {
        double lat = provider.doubleSetting("parking-lat", 28.5562);
        double lon = provider.doubleSetting("parking-lon", 77.1000);
        Set<String> taken = reservedOf(provider);
        return fleet(provider).stream()
                .map(car -> new VehicleReading(car, Mode.CAR, lat, lon, 0.0, null, !taken.contains(car), now))
                .toList();
    }
}
