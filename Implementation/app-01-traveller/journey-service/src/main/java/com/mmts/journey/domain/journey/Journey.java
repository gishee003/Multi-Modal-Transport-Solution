package com.mmts.journey.domain.journey;

import com.mmts.journey.domain.Mode;

import java.util.List;

/**
 * A whole journey option (the "composite" of the Composite pattern): a list of parts, where each part is a leg or
 * even another journey. Totals are simply the sum of the parts, so the same code works for one leg or many.
 */
public class Journey implements JourneyPart {

    private final List<JourneyPart> parts;

    public Journey(List<? extends JourneyPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("A journey needs at least one leg");
        }
        this.parts = List.copyOf(parts);
    }

    @Override
    public int minutes() {
        return parts.stream().mapToInt(JourneyPart::minutes).sum();
    }

    @Override
    public long farePaise() {
        return parts.stream().mapToLong(JourneyPart::farePaise).sum();
    }

    @Override
    public int boardings() {
        return parts.stream().mapToInt(JourneyPart::boardings).sum();
    }

    @Override
    public List<Leg> legs() {
        return parts.stream().flatMap(p -> p.legs().stream()).toList();
    }

    /** Number of times the traveller changes vehicle: boarding 3 vehicles means 2 changes. */
    public int changes() {
        return Math.max(0, boardings() - 1);
    }

    /** The modes used, in order, without repeats, for example [WALK, METRO, SCOOTER]. */
    public List<Mode> modes() {
        return legs().stream().map(Leg::mode).distinct().toList();
    }

    /** A stable id for this option, made from its legs, so the same route always gets the same id. */
    public String optionId() {
        StringBuilder signature = new StringBuilder();
        for (Leg leg : legs()) {
            signature.append(leg.mode()).append(':').append(leg.from().id()).append('>').append(leg.to().id()).append(';');
        }
        return "opt-" + Integer.toHexString(signature.toString().hashCode());
    }
}
