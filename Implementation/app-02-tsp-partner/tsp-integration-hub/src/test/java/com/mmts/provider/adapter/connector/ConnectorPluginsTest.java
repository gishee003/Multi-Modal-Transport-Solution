package com.mmts.provider.adapter.connector;

import com.mmts.provider.adapter.connector.ProviderConnector.AccessGrant;
import com.mmts.provider.adapter.connector.ProviderConnector.HoldQuote;
import com.mmts.provider.adapter.connector.ProviderConnector.LegRequest;
import com.mmts.provider.adapter.connector.ProviderConnector.VehicleReading;
import com.mmts.provider.adapter.connector.bike.BikeShareConnector;
import com.mmts.provider.adapter.connector.car.CarRentalConnector;
import com.mmts.provider.adapter.connector.gbfs.ScooterGbfsConnector;
import com.mmts.provider.adapter.connector.gtfs.GtfsBusConnector;
import com.mmts.provider.adapter.connector.ondc.OndcMetroConnector;
import com.mmts.provider.adapter.connector.ondc.StubOndcNetwork;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.Place;
import com.mmts.provider.domain.ProviderDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.mmts.provider.support.TestProviders.INDIA_GATE;
import static com.mmts.provider.support.TestProviders.INDIA_GATE_BUS;
import static com.mmts.provider.support.TestProviders.RAJIV_CHOWK_BUS;
import static com.mmts.provider.support.TestProviders.RAJIV_CHOWK;
import static com.mmts.provider.support.TestProviders.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each plug-in on its own, without Spring: hold / confirm / release / unlock behaviour. */
class ConnectorPluginsTest {

    private static final Instant START = Instant.parse("2026-11-15T10:20:00Z");

    private static LegRequest leg(String holdId, Mode mode, String vehicleId) {
        return new LegRequest(holdId, holdId + "-L1", mode, RAJIV_CHOWK, INDIA_GATE, START, vehicleId, 14500);
    }

    private static void assertNotAvailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_AVAILABLE"));
    }

    // ---------- Scooter (GBFS 3.0) ----------

    private final ProviderDefinition zipride = provider("ZIPRIDE", "gbfs-scooter", Mode.SCOOTER, true,
            "feed", "gbfs/zipride/vehicle_status.json", "min-battery-pct", "20", "code-prefix", "ZR");

    @Test
    void scooterHoldConfirmUnlockReleaseGivesUnlockCode() {
        ScooterGbfsConnector scooters = new ScooterGbfsConnector();

        HoldQuote quote = scooters.hold(zipride, leg("h-1", Mode.SCOOTER, "ZR-SC-0042"));
        assertThat(quote.vehicleId()).isEqualTo("ZR-SC-0042");
        assertThat(quote.pricePaise()).isGreaterThan(5000);
        assertThat(scooters.isReserved(zipride, "ZR-SC-0042")).isTrue();
        assertNotAvailable(() -> scooters.hold(zipride, leg("h-2", Mode.SCOOTER, "ZR-SC-0042")));  // already held

        AccessGrant grant = scooters.confirm(zipride, leg("h-1", Mode.SCOOTER, "ZR-SC-0042"), quote.providerRef());
        assertThat(grant.type()).isEqualTo("UNLOCK_CODE");
        assertThat(grant.value()).matches("ZR-\\d{4}");
        assertThat(grant.validFrom()).isEqualTo(Instant.parse("2026-11-15T10:15:00Z"));
        assertThat(grant.validUntil()).isEqualTo(Instant.parse("2026-11-15T11:15:00Z"));

        scooters.unlock(zipride, leg("h-1", Mode.SCOOTER, "ZR-SC-0042"), quote.providerRef());
        scooters.release(zipride, leg("h-1", Mode.SCOOTER, "ZR-SC-0042"), quote.providerRef());
        scooters.release(zipride, leg("h-1", Mode.SCOOTER, "ZR-SC-0042"), quote.providerRef());   // twice is safe
        assertThat(scooters.isReserved(zipride, "ZR-SC-0042")).isFalse();
    }

    @Test
    void scooterWithLowBatteryOrDisabledCannotBeHeld() {
        ScooterGbfsConnector scooters = new ScooterGbfsConnector();
        assertNotAvailable(() -> scooters.hold(zipride, leg("h-3", Mode.SCOOTER, "ZR-SC-0012")));   // 12 % battery
        assertNotAvailable(() -> scooters.hold(zipride, leg("h-4", Mode.SCOOTER, "ZR-SC-0013")));   // disabled
        assertNotAvailable(() -> scooters.hold(zipride, leg("h-5", Mode.SCOOTER, "ZR-SC-9999")));   // unknown
    }

    @Test
    void scooterWithoutVehicleIdGetsTheNearestUsableOne() {
        HoldQuote quote = new ScooterGbfsConnector().hold(zipride, leg("h-6", Mode.SCOOTER, null));
        assertThat(quote.vehicleId()).isEqualTo("ZR-SC-0042");   // 25 m from Rajiv Chowk
    }

    // ---------- Bike share (GBFS 2.3), the plug-in that is disabled by default ----------

    @Test
    void bikeShareHoldsAFreeBikeAndRefusesAReservedOne() {
        ProviderDefinition hopbike = provider("HOPBIKE", "gbfs-bike", Mode.BIKE, true,
                "feed", "gbfs/hopbike/free_bike_status.json", "code-prefix", "HB");
        BikeShareConnector bikes = new BikeShareConnector();

        assertNotAvailable(() -> bikes.hold(hopbike, leg("h-7", Mode.BIKE, "HB-BK-004")));   // reserved in feed
        HoldQuote quote = bikes.hold(hopbike, leg("h-8", Mode.BIKE, "HB-BK-001"));
        assertThat(bikes.confirm(hopbike, leg("h-8", Mode.BIKE, "HB-BK-001"), quote.providerRef()).value())
                .startsWith("HB-");
    }

    // ---------- Car rental (partner API stub) ----------

    @Test
    void carRentalReservesUnlocksAndRunsOutOfCars() {
        ProviderDefinition driveEasy = provider("DRIVEEASY", "car-rental", Mode.CAR, true,
                "fleet", "DE-CAR-101,DE-CAR-102", "code-prefix", "DE");
        CarRentalConnector cars = new CarRentalConnector();

        HoldQuote first = cars.hold(driveEasy, leg("h-10", Mode.CAR, null));
        assertThat(first.vehicleId()).isEqualTo("DE-CAR-101");
        AccessGrant key = cars.confirm(driveEasy, leg("h-10", Mode.CAR, "DE-CAR-101"), first.providerRef());
        assertThat(key.type()).isEqualTo("UNLOCK_CODE");
        assertThat(key.value()).matches("DE-\\d{6}");
        cars.unlock(driveEasy, leg("h-10", Mode.CAR, "DE-CAR-101"), first.providerRef());

        cars.hold(driveEasy, leg("h-11", Mode.CAR, null));
        assertNotAvailable(() -> cars.hold(driveEasy, leg("h-12", Mode.CAR, null)));          // both cars taken

        cars.release(driveEasy, leg("h-10", Mode.CAR, "DE-CAR-101"), first.providerRef());
        assertNotAvailable(() -> cars.unlock(driveEasy, leg("h-10", Mode.CAR, "DE-CAR-101"), first.providerRef()));
        List<VehicleReading> readings = cars.pollVehicles(driveEasy, START);
        assertThat(readings).filteredOn(VehicleReading::available).extracting(VehicleReading::vehicleId)
                .containsExactly("DE-CAR-101");
    }

    // ---------- GTFS bus ----------

    @Test
    void gtfsBusIssuesQrTicketAndSimulatesRealtimePositions() {
        ProviderDefinition dtc = provider("DTC", "gtfs-bus", Mode.BUS, true, "feed", "gtfs/dtc");
        GtfsBusConnector buses = new GtfsBusConnector();

        Place unknownStop = new Place("Nowhere", 28.6, 77.2, "DTC-S99");
        assertNotAvailable(() -> buses.hold(dtc, new LegRequest("h-20", "l", Mode.BUS, unknownStop, INDIA_GATE,
                START, null, 0)));

        LegRequest busLeg = new LegRequest("h-21", "l", Mode.BUS, RAJIV_CHOWK_BUS, INDIA_GATE_BUS, START, null, 1500);
        HoldQuote quote = buses.hold(dtc, busLeg);
        AccessGrant ticket = buses.confirm(dtc, busLeg, quote.providerRef());
        assertThat(ticket.type()).isEqualTo("QR_TICKET");
        assertThat(ticket.value()).startsWith("DTC|dtc-h-21|");
        assertThatThrownBy(() -> buses.unlock(dtc, busLeg, quote.providerRef()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("UNLOCK_NOT_SUPPORTED"));

        List<VehicleReading> positions = buses.pollVehicles(dtc, START);
        assertThat(positions).hasSize(3);   // one bus per trip
        assertThat(positions).allSatisfy(r -> {
            assertThat(r.mode()).isEqualTo(Mode.BUS);
            assertThat(r.lat()).isBetween(28.60, 28.65);
            assertThat(r.lon()).isBetween(77.20, 77.25);
        });
    }

    // ---------- ONDC metro (Beckn request → callback) ----------

    private final ProviderDefinition dmrc = provider("DMRC", "ondc-metro", Mode.METRO, true,
            "callback-timeout-ms", "2000", "ticket-valid-minutes", "120");

    @Test
    void ondcHoldAndConfirmFollowTheBecknRequestCallbackPairs() {
        StubOndcNetwork network = new StubOndcNetwork();
        OndcMetroConnector metro = new OndcMetroConnector(network);

        HoldQuote quote = metro.hold(dmrc, leg("h-30", Mode.METRO, null));
        assertThat(network.messageLog()).containsExactly("select", "on_select", "init", "on_init");
        assertThat(quote.pricePaise()).isEqualTo(2100);            // 2.4 km → DMRC slab 2-5 km = Rs 21
        assertThat(quote.providerRef()).startsWith("txn-");

        AccessGrant ticket = metro.confirm(dmrc, leg("h-30", Mode.METRO, null), quote.providerRef());
        assertThat(network.messageLog()).endsWith("confirm", "on_confirm");
        assertThat(ticket.type()).isEqualTo("QR_TICKET");
        assertThat(ticket.value()).startsWith("ONDC|DMRC|" + quote.providerRef());

        metro.release(dmrc, leg("h-30", Mode.METRO, null), quote.providerRef());
        assertThat(network.messageLog()).endsWith("cancel", "on_cancel");
    }

    @Test
    void ondcWithoutCallbackTimesOutWith504() {
        StubOndcNetwork network = new StubOndcNetwork();
        network.setSilent(true);                                  // the BPP never answers
        OndcMetroConnector metro = new OndcMetroConnector(network);
        ProviderDefinition impatient = provider("DMRC", "ondc-metro", Mode.METRO, true, "callback-timeout-ms", "100");

        assertThatThrownBy(() -> metro.hold(impatient, leg("h-31", Mode.METRO, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PROVIDER_TIMEOUT");
                    assertThat(e.getStatus().value()).isEqualTo(504);
                });
    }
}
