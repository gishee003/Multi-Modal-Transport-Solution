package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;

import java.util.List;

/**
 * One leg of a journey, done with one mode (the "leaf" of the Composite pattern).
 * Each mode has its own subclass (WalkLeg, MetroLeg, ...) because each mode prices itself differently and describes
 * itself differently. {@link LegFactory} picks the right subclass (Factory Method pattern).
 */
public abstract class Leg implements JourneyPart {

    private final Mode mode;
    private final Stop from;
    private final Stop to;
    private final double metres;
    private final int minutes;
    private final List<String> routeNames;
    private final String providerId;
    private final String vehicleId;
    private final long farePaise;

    protected Leg(Mode mode, Stop from, Stop to, double metres, int minutes, List<String> routeNames,
                  String providerId, String vehicleId, long farePaise) {
        this.mode = mode;
        this.from = from;
        this.to = to;
        this.metres = metres;
        this.minutes = minutes;
        this.routeNames = routeNames == null ? List.of() : List.copyOf(routeNames);
        this.providerId = providerId;
        this.vehicleId = vehicleId;
        this.farePaise = farePaise;
    }

    /** A short sentence for the traveller, for example "Take the Yellow Line from Hauz Khas Metro to Rajiv Chowk Metro". */
    public abstract String instruction();

    @Override
    public int minutes() {
        return minutes;
    }

    @Override
    public long farePaise() {
        return farePaise;
    }

    /** Walking boards nothing; every vehicle leg boards one vehicle (MetroLeg adds its interchanges). */
    @Override
    public int boardings() {
        return mode == Mode.WALK ? 0 : 1;
    }

    @Override
    public List<Leg> legs() {
        return List.of(this);
    }

    public Mode mode() {
        return mode;
    }

    public Stop from() {
        return from;
    }

    public Stop to() {
        return to;
    }

    public double metres() {
        return metres;
    }

    public double km() {
        return metres / 1000.0;
    }

    public List<String> routeNames() {
        return routeNames;
    }

    public String providerId() {
        return providerId;
    }

    /** Suggested vehicle for scooter / bike legs (null for other modes). */
    public String vehicleId() {
        return vehicleId;
    }
}
