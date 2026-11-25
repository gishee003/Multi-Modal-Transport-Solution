package com.mmts.journey.domain;

import com.mmts.journey.TestFares;
import com.mmts.journey.domain.journey.BikeLeg;
import com.mmts.journey.domain.journey.BusLeg;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.domain.journey.Leg;
import com.mmts.journey.domain.journey.LegFactory;
import com.mmts.journey.domain.journey.MetroLeg;
import com.mmts.journey.domain.journey.ScooterLeg;
import com.mmts.journey.domain.journey.TaxiLeg;
import com.mmts.journey.domain.journey.WalkLeg;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Composite totals, Factory Method, fare rules and the staleness rule. */
class JourneyDomainTest {

    private static final Stop A = new Stop("A", "Point A", 28.60, 77.20, Stop.Kind.PLACE);
    private static final Stop B = new Stop("B", "Point B", 28.61, 77.21, Stop.Kind.METRO_STATION);
    private final LegFactory factory = new LegFactory(TestFares.rules());

    @Test
    void factoryCreatesTheRightLegTypeForEachMode() {
        assertThat(factory.create(Mode.WALK, A, B, 500, 7, List.of(), null, null)).isInstanceOf(WalkLeg.class);
        assertThat(factory.create(Mode.METRO, A, B, 3000, 9, List.of("Yellow Line"), "DMRC", null)).isInstanceOf(MetroLeg.class);
        assertThat(factory.create(Mode.BUS, A, B, 3000, 12, List.of("DTC 505"), "DTC", null)).isInstanceOf(BusLeg.class);
        assertThat(factory.create(Mode.SCOOTER, A, B, 2000, 8, List.of(), "ZIPRIDE", "ZR-1")).isInstanceOf(ScooterLeg.class);
        assertThat(factory.create(Mode.BIKE, A, B, 2000, 12, List.of(), "GREENBIKE", "GB-1")).isInstanceOf(BikeLeg.class);
        assertThat(factory.create(Mode.TAXI, A, B, 5000, 18, List.of(), "CITYCAB", null)).isInstanceOf(TaxiLeg.class);
        assertThatThrownBy(() -> factory.create(Mode.CAR, A, B, 1, 1, List.of(), "X", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachLegPricesItselfWithItsOwnRule() {
        assertThat(factory.create(Mode.WALK, A, B, 500, 7, List.of(), null, null).farePaise()).isZero();
        assertThat(factory.create(Mode.METRO, A, B, 1500, 5, List.of("YL"), "DMRC", null).farePaise()).isEqualTo(1100); // up to 2 km
        assertThat(factory.create(Mode.METRO, A, B, 7000, 15, List.of("YL"), "DMRC", null).farePaise()).isEqualTo(3200); // 5-12 km
        assertThat(factory.create(Mode.BUS, A, B, 3000, 12, List.of("505"), "DTC", null).farePaise()).isEqualTo(1000);
        assertThat(factory.create(Mode.SCOOTER, A, B, 2000, 8, List.of(), "ZIPRIDE", null).farePaise()).isEqualTo(1000 + 8 * 300);
        assertThat(factory.create(Mode.BIKE, A, B, 2000, 10, List.of(), "GREENBIKE", null).farePaise()).isEqualTo(500 + 10 * 100);
        assertThat(factory.create(Mode.TAXI, A, B, 10_000, 30, List.of(), "CITYCAB", null).farePaise()).isEqualTo(5000 + 10 * 1800);
    }

    @Test
    void pricingRulesChangeSurgeAndPromotions() {
        FareRules updated = TestFares.rules().withPricing(1.5, Map.of(Mode.BIKE, 20));

        assertThat(updated.taxiFare(10)).isEqualTo(Math.round((5000 + 18_000) * 1.5));   // surge on taxi
        assertThat(updated.bikeFare(10)).isEqualTo(Math.round(1500 * 1.5) * 80 / 100);    // surge and 20% off
        assertThat(updated.metroFare(3)).isEqualTo(2100);                                  // metro: no surge
    }

    @Test
    void compositeTotalsWorkTheSameForOneLegOrMany() {
        Leg walk = factory.create(Mode.WALK, A, B, 400, 5, List.of(), null, null);
        Leg metro = factory.create(Mode.METRO, B, A, 6000, 15, List.of("Yellow Line", "Blue Line"), "DMRC", null);
        Leg scooter = factory.create(Mode.SCOOTER, A, B, 2000, 8, List.of(), "ZIPRIDE", null);

        Journey single = new Journey(List.of(scooter));
        Journey whole = new Journey(List.of(walk, metro, scooter));
        Journey nested = new Journey(List.of(new Journey(List.of(walk, metro)), scooter));   // a journey inside a journey

        assertThat(single.minutes()).isEqualTo(scooter.minutes());
        assertThat(single.farePaise()).isEqualTo(scooter.farePaise());
        assertThat(whole.minutes()).isEqualTo(5 + 15 + 8);
        assertThat(whole.farePaise()).isEqualTo(3200 + 3400);
        assertThat(whole.boardings()).isEqualTo(3);   // metro with one line change + scooter
        assertThat(whole.changes()).isEqualTo(2);
        assertThat(nested.minutes()).isEqualTo(whole.minutes());
        assertThat(nested.farePaise()).isEqualTo(whole.farePaise());
        assertThat(nested.legs()).hasSize(3);
        assertThat(nested.optionId()).isEqualTo(whole.optionId());
    }

    @Test
    void positionOlderThanTenSecondsIsStale() {
        Instant now = Instant.parse("2026-11-15T10:00:00Z");
        VehicleState v = new VehicleState("ZR-1", "ZIPRIDE", Mode.SCOOTER, 28.6, 77.2, 15.0, 80, true, now.minusSeconds(10));

        assertThat(v.isStale(now, 10)).isFalse();                 // exactly 10 s: still live
        assertThat(v.isStale(now.plusSeconds(1), 10)).isTrue();   // 11 s: stale
        assertThat(v.ageSeconds(now.plusSeconds(1))).isEqualTo(11);
    }
}
