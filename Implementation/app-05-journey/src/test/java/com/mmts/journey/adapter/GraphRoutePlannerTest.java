package com.mmts.journey.adapter;

import com.mmts.journey.TestFares;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.TransportGraph;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.domain.journey.Leg;
import com.mmts.journey.domain.journey.LegFactory;
import com.mmts.journey.domain.journey.MetroLeg;
import com.mmts.journey.repository.VehiclePositionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for our own route planner (Dijkstra over the sample Delhi network), no Spring needed. */
class GraphRoutePlannerTest {

    private static final Set<Mode> ALL = EnumSet.of(Mode.WALK, Mode.METRO, Mode.BUS, Mode.SCOOTER, Mode.BIKE, Mode.TAXI);

    private TransportGraph graph;
    private VehiclePositionStore store;
    private GraphRoutePlanner planner;
    private final LegFactory factory = new LegFactory(TestFares.rules());

    @BeforeEach
    void setUp() {
        graph = SampleDelhiNetwork.build();
        store = new VehiclePositionStore();
        SampleDelhiNetwork.sampleVehicles(Instant.now()).forEach(store::save);
        planner = new GraphRoutePlanner(graph, store);
    }

    private Stop stop(String id) {
        return graph.stop(id).orElseThrow();
    }

    @Test
    void findsMixedModeRouteWithWalkMetroAndScooter() {
        List<Journey> options = planner.plan(stop("PL-IITD"), stop("PL-IG"), ALL, factory);

        assertThat(options).anySatisfy(j ->
                assertThat(j.modes()).contains(Mode.WALK, Mode.METRO, Mode.SCOOTER));
        // every option really goes from A to B, leg after leg
        for (Journey j : options) {
            List<Leg> legs = j.legs();
            assertThat(legs.get(0).from().id()).isEqualTo("PL-IITD");
            assertThat(legs.get(legs.size() - 1).to().id()).isEqualTo("PL-IG");
            for (int i = 1; i < legs.size(); i++) {
                assertThat(legs.get(i).from()).isEqualTo(legs.get(i - 1).to());
            }
        }
    }

    @Test
    void offersSeveralDifferentOptionsIncludingTaxi() {
        List<Journey> options = planner.plan(stop("PL-IITD"), stop("PL-IG"), ALL, factory);

        assertThat(options.size()).isGreaterThanOrEqualTo(3);
        assertThat(options).extracting(Journey::optionId).doesNotHaveDuplicates();
        assertThat(options).anySatisfy(j -> assertThat(j.modes()).containsExactly(Mode.TAXI));
    }

    @Test
    void walkingOnlyBetweenFarPlacesFindsNothing() {
        assertThat(planner.plan(stop("PL-IITD"), stop("PL-IG"), EnumSet.of(Mode.WALK), factory)).isEmpty();
    }

    @Test
    void noScooterRouteWhenNoScooterIsParkedAtTheDock() {
        assertThat(planner.plan(stop("PL-CP"), stop("PL-IG"), ALL, factory))
                .anySatisfy(j -> assertThat(j.modes()).containsAnyOf(Mode.SCOOTER, Mode.BIKE));

        store.clear();   // no vehicles reported at all

        List<Journey> options = planner.plan(stop("PL-CP"), stop("PL-IG"), ALL, factory);

        assertThat(options).isNotEmpty();   // still reachable by walk + metro + bus
        assertThat(options).allSatisfy(j ->
                assertThat(j.modes()).doesNotContain(Mode.SCOOTER, Mode.BIKE, Mode.TAXI));
        // and IIT Delhi is too far from the metro to walk, so without a scooter or bike there is no route at all
        assertThat(planner.plan(stop("PL-IITD"), stop("PL-IG"), ALL, factory)).isEmpty();
    }

    @Test
    void metroLineChangeIsOneTicketButCountsAsAChange() {
        List<Journey> options = planner.plan(stop("DMRC-KB"), stop("DMRC-HKS"), EnumSet.of(Mode.METRO), factory);

        Journey metro = options.get(0);
        assertThat(metro.legs()).hasSize(1);
        MetroLeg leg = (MetroLeg) metro.legs().get(0);
        assertThat(leg.routeNames()).containsExactly("Blue Line", "Yellow Line");
        assertThat(metro.changes()).isEqualTo(1);
        assertThat(leg.farePaise()).isEqualTo(TestFares.rules().metroFare(leg.km()));
    }
}
