package com.mmts.journey.service;

import com.mmts.journey.common.error.ApiException;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.TransportGraph;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.service.ranking.RankingStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Journey search (M2.1 and M2.3): checks the request, gets the options (cached), ranks them with the chosen
 * strategy and measures how long it took (driver D4).
 */
@Service
public class JourneySearchService {

    /** The modes this planner can suggest (CAR self-drive is booked in APP-06, not planned here). */
    public static final Set<Mode> PLANNED_MODES =
            EnumSet.of(Mode.WALK, Mode.METRO, Mode.BUS, Mode.SCOOTER, Mode.BIKE, Mode.TAXI);

    public record SearchResult(Stop from, Stop to, String rankBy, List<Journey> options,
                               long computeMillis, long responseMillis, boolean servedFromCache) {
    }

    private final TransportGraph graph;
    private final RouteCandidateService candidates;
    private final Map<String, RankingStrategy> strategies;
    private final int maxOptions;

    public JourneySearchService(TransportGraph graph, RouteCandidateService candidates,
                                List<RankingStrategy> strategies,
                                @Value("${mmts.journey.search.max-options}") int maxOptions) {
        this.graph = graph;
        this.candidates = candidates;
        this.strategies = strategies.stream().collect(Collectors.toMap(RankingStrategy::name, Function.identity()));
        this.maxOptions = maxOptions;
    }

    public SearchResult search(String fromText, String toText, String modesText, String rankByText) {
        Stop from = resolve(fromText);
        Stop to = resolve(toText);
        if (from.equals(to)) {
            throw ApiException.badRequest("SAME_PLACE", "Start and destination are the same place");
        }
        RankingStrategy strategy = strategy(rankByText);
        Set<Mode> modes = parseModes(modesText);
        String modesKey = modes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));

        long start = System.nanoTime();
        RouteCandidateService.CandidateSet set = candidates.candidates(from, to, modes, modesKey);
        boolean fromCache = set.computedAtNanos() < start;   // computed before this request began = cached
        if (set.journeys().isEmpty()) {
            throw ApiException.notFound("NO_ROUTE_FOUND",
                    "No route from " + from.name() + " to " + to.name() + " with modes " + modesKey);
        }
        List<Journey> ranked = strategy.rank(set.journeys()).stream().limit(maxOptions).toList();
        long responseMillis = (System.nanoTime() - start) / 1_000_000;
        return new SearchResult(from, to, strategy.name(), ranked, set.computeMillis(), responseMillis, fromCache);
    }

    /** Every point a search can start or end at, sorted by name. */
    public List<Stop> places() {
        return graph.stops().stream().sorted(Comparator.comparing(Stop::name)).toList();
    }

    public Stop resolve(String idOrName) {
        return graph.find(idOrName).orElseThrow(() -> ApiException.notFound("UNKNOWN_PLACE",
                "Unknown place '" + idOrName + "'. See GET /api/v1/journeys/places for the list."));
    }

    private RankingStrategy strategy(String rankBy) {
        String name = rankBy == null || rankBy.isBlank() ? "FASTEST" : rankBy.trim().toUpperCase();
        RankingStrategy strategy = strategies.get(name);
        if (strategy == null) {
            throw ApiException.badRequest("UNKNOWN_RANKING",
                    "rankBy must be one of " + strategies.keySet().stream().sorted().toList());
        }
        return strategy;
    }

    private static Set<Mode> parseModes(String modesText) {
        if (modesText == null || modesText.isBlank()) {
            return EnumSet.copyOf(PLANNED_MODES);
        }
        Set<Mode> modes = EnumSet.of(Mode.WALK);
        for (String part : modesText.split(",")) {
            String name = part.trim().toUpperCase();
            Mode mode = Arrays.stream(Mode.values()).filter(m -> m.name().equals(name)).findFirst()
                    .filter(PLANNED_MODES::contains)
                    .orElseThrow(() -> ApiException.badRequest("UNKNOWN_MODE",
                            "Unknown mode '" + part.trim() + "'. Allowed: " + PLANNED_MODES));
            modes.add(mode);
        }
        return modes;
    }
}
