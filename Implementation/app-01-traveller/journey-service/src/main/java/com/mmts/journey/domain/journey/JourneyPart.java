package com.mmts.journey.domain.journey;

import java.util.List;

/**
 * Composite pattern: the common view of "one leg" and "a whole journey made of legs".
 * Totals are asked the same way for both, so callers never need to know which one they hold.
 */
public interface JourneyPart {

    /** Travel time in minutes, including waiting / unlocking. */
    int minutes();

    /** Price in paise. */
    long farePaise();

    /** How many vehicles the traveller boards (walking = 0, one metro ride with one interchange = 2). */
    int boardings();

    /** The single legs inside, in travel order. */
    List<Leg> legs();
}
