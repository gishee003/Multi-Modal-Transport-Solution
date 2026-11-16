package com.mmts.booking.domain;

/** Where one leg of a booking stands with its provider. */
public enum LegStatus {
    PENDING,       // nothing asked from the provider yet
    NOT_NEEDED,    // walking leg: nothing to book
    HELD,          // provider is keeping a seat / vehicle for us
    CONFIRMED,     // paid and confirmed with the provider
    UNLOCKED,      // vehicle unlocked, the leg has started
    RELEASED       // hold given back (booking cancelled or failed)
}
