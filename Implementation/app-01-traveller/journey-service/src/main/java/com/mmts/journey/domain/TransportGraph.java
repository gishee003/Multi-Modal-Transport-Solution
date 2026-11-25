package com.mmts.journey.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The multimodal network as a graph: stops are the points ("nodes"), links are the one-way connections between them
 * ("edges"). A link has a mode (walk, metro, bus, ...) and a travel time. Kept fully in memory so a search is fast (D4).
 */
public class TransportGraph {

    /**
     * One-way connection from one stop to another.
     *
     * @param routeId   which line or ride this link belongs to (for example "YL" for the Yellow Line). A change of
     *                  routeId while riding means the traveller changes vehicle.
     * @param routeName the name shown to travellers, for example "Yellow Line"
     */
    public record Link(Stop from, Stop to, Mode mode, double minutes, double metres,
                       String routeId, String routeName, String providerId) {
    }

    private final Map<String, Stop> stops = new LinkedHashMap<>();
    private final Map<String, List<Link>> outgoing = new HashMap<>();
    private int linkCount;

    public void addStop(Stop stop) {
        stops.put(stop.id(), stop);
        outgoing.putIfAbsent(stop.id(), new ArrayList<>());
    }

    public void addLink(Link link) {
        outgoing.get(link.from().id()).add(link);
        linkCount++;
    }

    /** Adds the link in both directions (A to B and B to A). */
    public void addBothWays(Stop a, Stop b, Mode mode, double minutes, double metres,
                            String routeId, String routeName, String providerId) {
        addLink(new Link(a, b, mode, minutes, metres, routeId, routeName, providerId));
        addLink(new Link(b, a, mode, minutes, metres, routeId, routeName, providerId));
    }

    public Optional<Stop> stop(String id) {
        return Optional.ofNullable(stops.get(id));
    }

    /** Finds a stop by its id or its name, ignoring upper/lower case. */
    public Optional<Stop> find(String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        String text = idOrName.trim();
        return stops.values().stream()
                .filter(s -> s.id().equalsIgnoreCase(text) || s.name().equalsIgnoreCase(text))
                .findFirst();
    }

    public Collection<Stop> stops() {
        return stops.values();
    }

    public List<Link> linksFrom(String stopId) {
        return outgoing.getOrDefault(stopId, List.of());
    }

    public int linkCount() {
        return linkCount;
    }
}
