package com.mmts.journey.service.ranking;

import com.mmts.journey.domain.journey.Journey;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/** Fewest vehicle changes first; on a tie, the faster one. */
@Component
public class FewestChangesStrategy implements RankingStrategy {

    @Override
    public String name() {
        return "FEWEST_CHANGES";
    }

    @Override
    public Comparator<Journey> comparator() {
        return Comparator.comparingInt(Journey::changes).thenComparingInt(Journey::minutes);
    }
}
