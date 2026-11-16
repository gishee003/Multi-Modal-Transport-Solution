package com.mmts.journey.service.ranking;

import com.mmts.journey.domain.journey.Journey;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/** Lowest total fare first; on a tie, the faster one. */
@Component
public class CheapestStrategy implements RankingStrategy {

    @Override
    public String name() {
        return "CHEAPEST";
    }

    @Override
    public Comparator<Journey> comparator() {
        return Comparator.comparingLong(Journey::farePaise).thenComparingInt(Journey::minutes);
    }
}
