package com.mmts.journey.service.ranking;

import com.mmts.journey.domain.journey.Journey;

import java.util.Comparator;
import java.util.List;

/**
 * Strategy pattern: one way of ordering journey options. The search service picks a strategy by name at run time
 * (FASTEST, CHEAPEST, FEWEST_CHANGES); adding a new ranking means adding one new class, nothing else changes.
 */
public interface RankingStrategy {

    /** The name used in the API, for example "FASTEST". */
    String name();

    /** How two options compare: the smaller one is better. */
    Comparator<Journey> comparator();

    /** Returns the options best first. */
    default List<Journey> rank(List<Journey> options) {
        return options.stream().sorted(comparator()).toList();
    }
}
