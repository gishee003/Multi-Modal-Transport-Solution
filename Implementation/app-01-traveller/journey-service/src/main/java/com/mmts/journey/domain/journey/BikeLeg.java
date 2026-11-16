package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/** A shared bicycle ride from one dock to another: small unlock fee plus a price per minute (surge applies). */
public class BikeLeg extends Leg {

    public BikeLeg(Stop from, Stop to, double metres, int minutes, String providerId, String vehicleId,
                   FareRules rules) {
        super(Mode.BIKE, from, to, metres, minutes, List.of(), providerId, vehicleId, rules.bikeFare(minutes));
    }

    @Override
    public String instruction() {
        return "Pick up bike " + (vehicleId() == null ? "" : vehicleId() + " ") + "at " + from().name()
                + " and cycle to " + to().name();
    }
}
