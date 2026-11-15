package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.FareRules;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/**
 * A metro ride. Like a real DMRC ticket, one ride is priced by total distance even if the traveller changes line
 * inside the metro (for example Yellow Line to Blue Line at Rajiv Chowk). Each line change counts as one more boarding.
 */
public class MetroLeg extends Leg {

    public MetroLeg(Stop from, Stop to, double metres, int minutes, List<String> lines, String providerId,
                    FareRules rules) {
        super(Mode.METRO, from, to, metres, minutes, lines, providerId, null, rules.metroFare(metres / 1000.0));
    }

    @Override
    public int boardings() {
        return Math.max(1, routeNames().size());
    }

    @Override
    public String instruction() {
        String lines = String.join(", then change to the ", routeNames());
        return "Take the " + lines + " from " + from().name() + " to " + to().name();
    }
}
