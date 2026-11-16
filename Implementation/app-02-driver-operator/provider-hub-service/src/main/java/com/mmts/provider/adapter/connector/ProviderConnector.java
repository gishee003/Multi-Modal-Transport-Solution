package com.mmts.provider.adapter.connector;

import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.Place;
import com.mmts.provider.domain.ProviderDefinition;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * THE EXTENSION POINT of the microkernel.
 *
 * Every provider plug-in implements this one interface. It is an Adapter: the core speaks only this
 * language (hold, confirm, release, unlock, schedule, vehicles) and each plug-in translates it into its
 * provider's own format (ONDC/Beckn messages, GTFS files, GBFS JSON, a partner REST API ...).
 *
 * The core finds plug-ins by {@link #type()} and the "connector" name in application.yml,
 * so it never names a concrete plug-in class (Open/Closed principle, driver D10).
 */
public interface ProviderConnector {

    /** The name used in application.yml, e.g. "gbfs-scooter". */
    String type();

    /** Reserve capacity or a vehicle for one leg. Throw {@link #notAvailable(String)} if there is none. */
    HoldQuote hold(ProviderDefinition provider, LegRequest leg);

    /** Turn the reservation into a real ticket / unlock code / driver assignment. */
    AccessGrant confirm(ProviderDefinition provider, LegRequest leg, String providerRef);

    /** Undo a hold or a confirmation. Must be safe to call twice. */
    void release(ProviderDefinition provider, LegRequest leg, String providerRef);

    /** Unlock a vehicle when the leg starts. Plug-ins without vehicles (metro, bus, taxi) keep this default. */
    default void unlock(ProviderDefinition provider, LegRequest leg, String providerRef) {
        throw ApiException.conflict("UNLOCK_NOT_SUPPORTED", provider.getId() + " legs have no vehicle to unlock");
    }

    /** Static timetable (GTFS) if this provider publishes one. */
    default Optional<ScheduleFeed> loadSchedule(ProviderDefinition provider) {
        return Optional.empty();
    }

    /** Current vehicle readings from the provider's live feed (GBFS, GTFS-Realtime ...). */
    default List<VehicleReading> pollVehicles(ProviderDefinition provider, Instant now) {
        return List.of();
    }

    static ApiException notAvailable(String message) {
        return ApiException.conflict("NOT_AVAILABLE", message);
    }

    // ---- The small common data model the core and the plug-ins share ----

    /** One leg as the core sees it. pricePaise is 0 until the hold has been priced. */
    record LegRequest(String holdId, String legId, Mode mode, Place from, Place to, Instant startTime,
                      String vehicleId, long pricePaise) {
    }

    /** Answer to a hold: the price, the vehicle that was reserved (if any) and the provider's own reference. */
    record HoldQuote(long pricePaise, String vehicleId, String providerRef) {
    }

    /** What the traveller gets after confirm. type is QR_TICKET, UNLOCK_CODE or DRIVER_ASSIGNED. */
    record AccessGrant(String type, String value, Instant validFrom, Instant validUntil, AssignedDriver driver) {

        /** Valid from 5 minutes before the leg starts, for the given number of minutes. */
        public static AccessGrant of(String type, String value, Instant startTime, int validMinutes,
                                     AssignedDriver driver) {
            Instant from = startTime.minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.SECONDS);
            return new AccessGrant(type, value, from, from.plus(Duration.ofMinutes(validMinutes)), driver);
        }
    }

    record AssignedDriver(String driverId, String name, String vehicleNumber, int etaMinutes) {
    }

    /** One live reading in the common vehicle model. batteryPct and speedKmph may be null. */
    record VehicleReading(String vehicleId, Mode mode, double lat, double lon, Double speedKmph,
                          Integer batteryPct, boolean available, Instant recordedAt) {
    }

    /** Summary of an imported timetable. */
    record ScheduleFeed(String feedVersion, List<String> routeIds, int stopCount, int tripCount) {
    }

    /** Thrown by a plug-in when its provider is temporarily down; the core retries idempotent calls. */
    class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) {
            super(message);
        }
    }
}
