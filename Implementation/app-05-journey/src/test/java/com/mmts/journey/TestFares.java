package com.mmts.journey;

import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.FareRules.Slab;

import java.util.List;
import java.util.Map;

/** The same fares as application.yml, for unit tests that run without Spring. */
public final class TestFares {

    private TestFares() {
    }

    public static FareRules rules() {
        return new FareRules(
                List.of(new Slab(2, 1100), new Slab(5, 2100), new Slab(12, 3200), new Slab(21, 4300),
                        new Slab(32, 5400), new Slab(1000, 6400)),
                List.of(new Slab(4, 1000), new Slab(10, 1500), new Slab(1000, 2500)),
                1000, 300, 500, 100, 5000, 1800, 1.0, Map.of());
    }
}
