package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/** A door-to-door taxi ride: base fare plus a price per km (surge applies). */
public class TaxiLeg extends Leg {

    public TaxiLeg(Stop from, Stop to, double metres, int minutes, String providerId, FareRules rules) {
        super(Mode.TAXI, from, to, metres, minutes, List.of(), providerId, null, rules.taxiFare(metres / 1000.0));
    }

    @Override
    public String instruction() {
        return "Taxi from " + from().name() + " to " + to().name();
    }
}
