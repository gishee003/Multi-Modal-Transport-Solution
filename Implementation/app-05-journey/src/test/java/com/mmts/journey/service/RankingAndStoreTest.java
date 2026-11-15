package com.mmts.journey.service;

import com.mmts.journey.TestFares;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.VehicleState;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.domain.journey.LegFactory;
import com.mmts.journey.repository.VehiclePositionStore;
import com.mmts.journey.service.ranking.CheapestStrategy;
import com.mmts.journey.service.ranking.FastestStrategy;
import com.mmts.journey.service.ranking.FewestChangesStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Strategy pattern ranking, and the latest-position store (Redis stand-in). */
class RankingAndStoreTest {

    private static final Stop A = new Stop("A", "A", 28.60, 77.20, Stop.Kind.PLACE);
    private static final Stop B = new Stop("B", "B", 28.61, 77.21, Stop.Kind.PLACE);
    private final LegFactory f = new LegFactory(TestFares.rules());

    // taxi: 30 min, 23000 paise, 0 changes
    private final Journey taxi = new Journey(List.of(f.create(Mode.TAXI, A, B, 10_000, 30, List.of(), "CITYCAB", null)));
    // metro + bus: 45 min, 3200 + 1000 paise, 1 change
    private final Journey transit = new Journey(List.of(
            f.create(Mode.METRO, A, B, 7000, 25, List.of("YL"), "DMRC", null),
            f.create(Mode.BUS, B, A, 3000, 20, List.of("505"), "DTC", null)));
    // walk + scooter + metro with one line change: 40 min, 6600 paise, 2 changes
    private final Journey mixed = new Journey(List.of(
            f.create(Mode.WALK, A, B, 300, 4, List.of(), null, null),
            f.create(Mode.SCOOTER, B, A, 2000, 8, List.of(), "ZIPRIDE", null),
            f.create(Mode.METRO, A, B, 7000, 28, List.of("YL", "BL"), "DMRC", null)));
    private final List<Journey> options = List.of(transit, mixed, taxi);

    @Test
    void fastestPutsShortestTimeFirst() {
        assertThat(new FastestStrategy().rank(options)).containsExactly(taxi, mixed, transit);
    }

    @Test
    void cheapestPutsLowestFareFirst() {
        assertThat(new CheapestStrategy().rank(options)).containsExactly(transit, mixed, taxi);
    }

    @Test
    void fewestChangesPutsFewestVehicleChangesFirst() {
        assertThat(new FewestChangesStrategy().rank(options)).containsExactly(taxi, transit, mixed);
    }

    @Test
    void storeKeepsNewestPositionAndIgnoresOlderOnes() {
        VehiclePositionStore store = new VehiclePositionStore();
        Instant t = Instant.parse("2026-11-15T10:00:00Z");
        store.save(new VehicleState("ZR-1", "ZIPRIDE", Mode.SCOOTER, 28.60, 77.20, 0.0, 90, true, t));
        store.save(new VehicleState("ZR-1", "ZIPRIDE", Mode.SCOOTER, 28.50, 77.10, 0.0, 90, true, t.minusSeconds(5)));

        assertThat(store.find("ZR-1").orElseThrow().lat()).isEqualTo(28.60);   // late, older event ignored
    }

    @Test
    void storeFindsOnlyFreeVehiclesInsideTheRadiusNearestFirst() {
        VehiclePositionStore store = new VehiclePositionStore();
        Instant t = Instant.now();
        store.save(new VehicleState("near", "P", Mode.SCOOTER, 28.6001, 77.2001, 0.0, null, true, t));
        store.save(new VehicleState("nearer", "P", Mode.SCOOTER, 28.60001, 77.20001, 0.0, null, true, t));
        store.save(new VehicleState("busy", "P", Mode.SCOOTER, 28.6000, 77.2000, 0.0, null, false, t));
        store.save(new VehicleState("far", "P", Mode.SCOOTER, 28.7, 77.3, 0.0, null, true, t));
        store.save(new VehicleState("bike", "P", Mode.BIKE, 28.6, 77.2, 0.0, null, true, t));

        assertThat(store.availableNear(Mode.SCOOTER, 28.6, 77.2, 500))
                .extracting(VehicleState::vehicleId).containsExactly("nearer", "near");
    }
}
