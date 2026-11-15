package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/** A bus ride on one route, priced by DTC-style distance bands. */
public class BusLeg extends Leg {

    public BusLeg(Stop from, Stop to, double metres, int minutes, String routeName, String providerId,
                  FareRules rules) {
        super(Mode.BUS, from, to, metres, minutes, List.of(routeName), providerId, null, rules.busFare(metres / 1000.0));
    }

    @Override
    public String instruction() {
        return "Take bus " + routeNames().get(0) + " from " + from().name() + " to " + to().name();
    }
}
