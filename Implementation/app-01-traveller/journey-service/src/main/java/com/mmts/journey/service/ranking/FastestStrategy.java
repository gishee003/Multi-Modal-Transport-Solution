package com.mmts.journey.service.ranking;

import com.mmts.journey.domain.journey.Journey;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/** Shortest total time first; on a tie, the cheaper one. */
@Component
public class FastestStrategy implements RankingStrategy {

    @Override
    public String name() {
        return "FASTEST";
    }

    @Override
    public Comparator<Journey> comparator() {
        return Comparator.comparingInt(Journey::minutes).thenComparingLong(Journey::farePaise);
    }
}
