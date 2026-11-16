package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/**
 * Factory Method pattern: the one place that decides which Leg subclass to create for a mode.
 * The planner only says "a SCOOTER leg from here to there"; it never calls "new ScooterLeg(...)" itself.
 * The factory holds the fare rules that are valid right now, so every new leg is priced with current fares.
 */
public class LegFactory {

    private final FareRules rules;

    public LegFactory(FareRules rules) {
        this.rules = rules;
    }

    public FareRules rules() {
        return rules;
    }

    /**
     * Creates the right kind of leg.
     *
     * @param routeNames line or route names (metro lines, bus route); empty for other modes
     * @param vehicleId  suggested vehicle for scooter / bike; null otherwise
     */
    public Leg create(Mode mode, Stop from, Stop to, double metres, int minutes, List<String> routeNames,
                      String providerId, String vehicleId) {
        return switch (mode) {
            case WALK -> new WalkLeg(from, to, metres, minutes);
            case METRO -> new MetroLeg(from, to, metres, minutes, routeNames, providerId, rules);
            case BUS -> new BusLeg(from, to, metres, minutes, routeNames.isEmpty() ? "bus" : routeNames.get(0),
                    providerId, rules);
            case SCOOTER -> new ScooterLeg(from, to, metres, minutes, providerId, vehicleId, rules);
            case BIKE -> new BikeLeg(from, to, metres, minutes, providerId, vehicleId, rules);
            case TAXI -> new TaxiLeg(from, to, metres, minutes, providerId, rules);
            case CAR -> throw new IllegalArgumentException("Self-drive cars are booked in Booking service, not planned here");
        };
    }

    /** Rough price of getting on a vehicle, used only to steer the "cheap" search (the real fare comes from the leg). */
    public long boardingFareEstimate(Mode mode) {
        return switch (mode) {
            case METRO -> rules.metroFare(0);
            case BUS -> rules.busFare(0);
            case SCOOTER -> rules.scooterFare(0);
            case BIKE -> rules.bikeFare(0);
            case TAXI -> rules.taxiFare(0);
            default -> 0;
        };
    }

    /** Rough price per minute of riding, used only to steer the "cheap" search. */
    public double perMinuteEstimate(Mode mode) {
        return switch (mode) {
            case SCOOTER -> rules.scooterFare(1) - rules.scooterFare(0);
            case BIKE -> rules.bikeFare(1) - rules.bikeFare(0);
            default -> 0;
        };
    }
}
