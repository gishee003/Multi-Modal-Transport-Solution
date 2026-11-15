package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/** Walking: free, no vehicle. */
public class WalkLeg extends Leg {

    public WalkLeg(Stop from, Stop to, double metres, int minutes) {
        super(Mode.WALK, from, to, metres, minutes, List.of(), null, null, 0);
    }

    @Override
    public String instruction() {
        return "Walk " + Math.round(metres()) + " m to " + to().name();
    }
}
