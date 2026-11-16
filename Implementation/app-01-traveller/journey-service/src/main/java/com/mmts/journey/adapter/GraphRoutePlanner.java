package com.mmts.journey.adapter;

import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.TransportGraph;
import com.mmts.journey.domain.TransportGraph.Link;
import com.mmts.journey.domain.VehicleState;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.domain.journey.Leg;
import com.mmts.journey.domain.journey.LegFactory;
import com.mmts.journey.repository.VehiclePositionStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Our own route planner over the in-memory multimodal graph (demo stand-in for OpenTripPlanner).
 *
 * How it works: it runs Dijkstra's shortest-path search several times, each time with a different idea of "cost"
 * (a {@link Profile}): plain minutes, minutes plus a penalty per vehicle change, or minutes plus price. Each run gives
 * one good option; together they give the traveller real choices. A door-to-door taxi option is added separately.
 *
 * The search is "label-based": a search state is not just a stop, but a stop plus the vehicle you are on
 * (for example "Rajiv Chowk, on the Yellow Line"). That is how it knows when you change vehicle.
 */
@Component
public class GraphRoutePlanner implements RoutePlanner {

    private static final double DOCK_RADIUS_METRES = 300;     // a scooter/bike must be this close to the dock
    private static final double TAXI_RADIUS_METRES = 6000;    // a free taxi must be this close to the start
    private static final double ROAD_DETOUR = 1.35;           // roads are longer than the straight line

    /** One idea of "cost" for the search. */
    record Profile(String name, double changePenaltyMinutes, double minutesPerRupee, Set<Mode> modes) {
    }

    /** One search state: at a stop, riding a vehicle (ride key), with the cost so far and how we got here. */
    private record Label(String stopId, String ride, double cost, Link via, Label previous) {
    }

    private static final String ON_FOOT_AFTER_WALK = "WALK";
    private static final String START = "";

    private final TransportGraph graph;
    private final VehiclePositionStore vehicles;

    public GraphRoutePlanner(TransportGraph graph, VehiclePositionStore vehicles) {
        this.graph = graph;
        this.vehicles = vehicles;
    }

    @Override
    public List<Journey> plan(Stop from, Stop to, Set<Mode> allowedModes, LegFactory legFactory) {
        Set<Mode> modes = EnumSet.copyOf(allowedModes);
        modes.add(Mode.WALK);
        Set<Mode> publicOnly = EnumSet.of(Mode.WALK, Mode.METRO, Mode.BUS);
        publicOnly.retainAll(modes);

        List<Profile> profiles = List.of(
                new Profile("fastest", 0, 0, modes),
                new Profile("few-changes", 20, 0, modes),
                new Profile("cheap", 0, 3, modes),
                new Profile("public-transport", 0, 0, publicOnly));

        // Several runs can find the same route; keep each route once (same legs = same option id).
        Map<String, Journey> options = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            search(from, to, profile, legFactory).ifPresent(j -> options.putIfAbsent(j.optionId(), j));
        }
        if (modes.contains(Mode.TAXI)) {
            taxi(from, to, legFactory).ifPresent(j -> options.putIfAbsent(j.optionId(), j));
        }
        return new ArrayList<>(options.values());
    }

    /** Dijkstra's algorithm: always continue from the cheapest state found so far, until we reach the destination. */
    Optional<Journey> search(Stop from, Stop to, Profile profile, LegFactory factory) {
        PriorityQueue<Label> queue = new PriorityQueue<>(Comparator.comparingDouble(Label::cost));
        Map<String, Double> bestCost = new HashMap<>();
        queue.add(new Label(from.id(), START, 0, null, null));
        bestCost.put(from.id() + "|" + START, 0.0);

        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (current.stopId().equals(to.id())) {
                return Optional.of(toJourney(current, factory));
            }
            if (current.cost() > bestCost.getOrDefault(current.stopId() + "|" + current.ride(), Double.MAX_VALUE)) {
                continue;   // an older, more expensive copy of a state we already improved
            }
            for (Link link : graph.linksFrom(current.stopId())) {
                if (!profile.modes().contains(link.mode())) {
                    continue;
                }
                if (link.mode() == Mode.WALK && ON_FOOT_AFTER_WALK.equals(current.ride())) {
                    continue;   // no two walks in a row: keeps walking short and legs realistic
                }
                if (link.mode().isDocked() && freeVehicleAt(link.mode(), link.from()).isEmpty()) {
                    continue;   // no scooter / bike parked at this dock right now (M2.2 data used by M2.1)
                }
                String ride = rideKey(link);
                double cost = current.cost() + link.minutes() + fareCost(profile, factory, link.mode(), link.minutes());
                boolean boarding = link.mode() != Mode.WALK && !ride.equals(current.ride());
                if (boarding) {
                    boolean metroInterchange = link.mode() == Mode.METRO && current.ride().startsWith("METRO:");
                    cost += link.mode().waitMinutes() + profile.changePenaltyMinutes();
                    if (!metroInterchange) {   // changing metro line needs no new ticket
                        cost += profile.minutesPerRupee() * factory.boardingFareEstimate(link.mode()) / 100.0;
                    }
                }
                String key = link.to().id() + "|" + ride;
                if (cost < bestCost.getOrDefault(key, Double.MAX_VALUE)) {
                    bestCost.put(key, cost);
                    queue.add(new Label(link.to().id(), ride, cost, link, current));
                }
            }
        }
        return Optional.empty();
    }

    private static double fareCost(Profile profile, LegFactory factory, Mode mode, double minutes) {
        return profile.minutesPerRupee() * factory.perMinuteEstimate(mode) * minutes / 100.0;
    }

    /** Which vehicle a link puts you on: the same key on two links means you stay seated. */
    private static String rideKey(Link link) {
        return link.mode() == Mode.WALK ? ON_FOOT_AFTER_WALK : link.mode() + ":" + link.routeId();
    }

    /** Walks back from the destination label to the start and turns the links into legs. */
    private Journey toJourney(Label end, LegFactory factory) {
        LinkedList<Link> links = new LinkedList<>();
        for (Label l = end; l.via() != null; l = l.previous()) {
            links.addFirst(l.via());
        }
        List<Leg> legs = new ArrayList<>();
        int i = 0;
        while (i < links.size()) {
            int j = i + 1;
            while (j < links.size() && sameLeg(links.get(j - 1), links.get(j))) {
                j++;
            }
            legs.add(toLeg(links.subList(i, j), factory));
            i = j;
        }
        return new Journey(legs);
    }

    /** Consecutive links form one leg when you stay on foot, stay inside the metro, or stay on the same bus. */
    private static boolean sameLeg(Link a, Link b) {
        if (a.mode() != b.mode()) {
            return false;
        }
        return switch (a.mode()) {
            case WALK, METRO -> true;
            case BUS -> a.routeId().equals(b.routeId());
            default -> false;   // every scooter / bike / taxi link is its own ride
        };
    }

    private Leg toLeg(List<Link> group, LegFactory factory) {
        Link first = group.get(0);
        Link last = group.get(group.size() - 1);
        Mode mode = first.mode();
        double metres = group.stream().mapToDouble(Link::metres).sum();
        double minutes = group.stream().mapToDouble(Link::minutes).sum();

        List<String> routeNames = new ArrayList<>();
        for (Link link : group) {
            if (link.routeName() != null && (routeNames.isEmpty() || !routeNames.get(routeNames.size() - 1).equals(link.routeName()))) {
                routeNames.add(link.routeName());
            }
        }
        if (mode != Mode.WALK) {
            // one wait to board, plus one more wait for every metro line change
            minutes += mode.waitMinutes() * Math.max(1, mode == Mode.METRO ? routeNames.size() : 1);
        }
        String vehicleId = mode.isDocked()
                ? freeVehicleAt(mode, first.from()).map(VehicleState::vehicleId).orElse(null) : null;
        return factory.create(mode, first.from(), last.to(), metres, (int) Math.ceil(minutes), routeNames,
                first.providerId(), vehicleId);
    }

    private Optional<Journey> taxi(Stop from, Stop to, LegFactory factory) {
        Optional<VehicleState> taxi = vehicles.availableNear(Mode.TAXI, from.lat(), from.lon(), TAXI_RADIUS_METRES)
                .stream().findFirst();
        if (taxi.isEmpty()) {
            return Optional.empty();
        }
        double metres = from.metresTo(to) * ROAD_DETOUR;
        int minutes = (int) Math.ceil(Mode.TAXI.waitMinutes() + metres / Mode.TAXI.metresPerMinute());
        return Optional.of(new Journey(List.of(
                factory.create(Mode.TAXI, from, to, metres, minutes, List.of(), taxi.get().providerId(), null))));
    }

    private Optional<VehicleState> freeVehicleAt(Mode mode, Stop dock) {
        return vehicles.availableNear(mode, dock.lat(), dock.lon(), DOCK_RADIUS_METRES).stream().findFirst();
    }
}
