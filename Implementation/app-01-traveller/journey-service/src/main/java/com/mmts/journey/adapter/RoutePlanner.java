package com.mmts.journey.adapter;

import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.domain.journey.LegFactory;

import java.util.List;
import java.util.Set;

/**
 * Finds journey options from A to B (Adapter pattern: the rest of the app only knows this interface).
 * The demo plugs in {@link GraphRoutePlanner}, our own search over a small in-memory network.
 * Production would plug in an adapter for OpenTripPlanner (fed with GTFS timetables, OpenStreetMap streets and GBFS
 * scooter/bike feeds) behind the same interface, without changing any service code (principles P3 and P6).
 */
public interface RoutePlanner {

    /**
     * @param allowedModes modes the traveller accepts (walking is always allowed)
     * @param legFactory   creates the legs, priced with the fares valid right now
     * @return different journey options (not yet ranked); empty when no route exists
     */
    List<Journey> plan(Stop from, Stop to, Set<Mode> allowedModes, LegFactory legFactory);
}
