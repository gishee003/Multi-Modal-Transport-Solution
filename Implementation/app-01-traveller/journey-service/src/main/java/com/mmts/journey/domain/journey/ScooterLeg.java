package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/** An e-scooter ride from one dock to another: unlock fee plus a price per minute (surge applies). */
public class ScooterLeg extends Leg {

    public ScooterLeg(Stop from, Stop to, double metres, int minutes, String providerId, String vehicleId,
                      FareRules rules) {
        super(Mode.SCOOTER, from, to, metres, minutes, List.of(), providerId, vehicleId, rules.scooterFare(minutes));
    }

    @Override
    public String instruction() {
        return "Unlock scooter " + (vehicleId() == null ? "" : vehicleId() + " ") + "at " + from().name()
                + " and ride to " + to().name();
    }
}
