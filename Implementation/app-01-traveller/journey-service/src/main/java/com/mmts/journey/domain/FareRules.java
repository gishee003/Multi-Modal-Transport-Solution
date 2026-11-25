package com.mmts.journey.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * All fare settings, read from application.yml (mmts.journey.fares), so prices change without changing code
 * (Externalization pattern). Money is always whole paise (100 paise = 1 rupee).
 *
 * @param metroSlabs      DMRC-style distance bands: the fare of the first band whose upToKm covers the ride
 * @param busSlabs        DTC-style distance bands
 * @param surgeFactor     multiplies on-demand fares (scooter, bike, taxi); 1.0 = no surge. Changed by PRICING_RULES_UPDATED
 * @param discountPercent promotion per mode (for example BIKE: 20 means 20% off). Changed by PRICING_RULES_UPDATED
 */
@ConfigurationProperties(prefix = "mmts.journey.fares")
public record FareRules(List<Slab> metroSlabs,
                        List<Slab> busSlabs,
                        long scooterUnlockPaise,
                        long scooterPerMinutePaise,
                        long bikeUnlockPaise,
                        long bikePerMinutePaise,
                        long taxiBasePaise,
                        long taxiPerKmPaise,
                        double surgeFactor,
                        Map<Mode, Integer> discountPercent) {

    /** One distance band: rides up to {@code upToKm} kilometres cost {@code farePaise}. */
    public record Slab(double upToKm, long farePaise) {
    }

    public FareRules {
        metroSlabs = metroSlabs == null ? List.of() : List.copyOf(metroSlabs);
        busSlabs = busSlabs == null ? List.of() : List.copyOf(busSlabs);
        surgeFactor = surgeFactor <= 0 ? 1.0 : surgeFactor;
        discountPercent = discountPercent == null || discountPercent.isEmpty()
                ? Map.of() : Map.copyOf(new EnumMap<>(discountPercent));
    }

    public long metroFare(double km) {
        return discounted(Mode.METRO, slabFare(metroSlabs, km));
    }

    public long busFare(double km) {
        return discounted(Mode.BUS, slabFare(busSlabs, km));
    }

    public long scooterFare(int minutes) {
        return discounted(Mode.SCOOTER, surged(scooterUnlockPaise + scooterPerMinutePaise * minutes));
    }

    public long bikeFare(int minutes) {
        return discounted(Mode.BIKE, surged(bikeUnlockPaise + bikePerMinutePaise * minutes));
    }

    public long taxiFare(double km) {
        return discounted(Mode.TAXI, surged(taxiBasePaise + Math.round(taxiPerKmPaise * km)));
    }

    /** A copy with a new surge factor and promotions (what a PRICING_RULES_UPDATED event changes). */
    public FareRules withPricing(double newSurgeFactor, Map<Mode, Integer> newDiscounts) {
        return new FareRules(metroSlabs, busSlabs, scooterUnlockPaise, scooterPerMinutePaise, bikeUnlockPaise,
                bikePerMinutePaise, taxiBasePaise, taxiPerKmPaise, newSurgeFactor, newDiscounts);
    }

    private static long slabFare(List<Slab> slabs, double km) {
        for (Slab slab : slabs) {
            if (km <= slab.upToKm()) {
                return slab.farePaise();
            }
        }
        return slabs.isEmpty() ? 0 : slabs.get(slabs.size() - 1).farePaise();
    }

    private long surged(long paise) {
        return Math.round(paise * surgeFactor);
    }

    private long discounted(Mode mode, long paise) {
        int percent = discountPercent.getOrDefault(mode, 0);
        return paise * (100 - percent) / 100;
    }
}
