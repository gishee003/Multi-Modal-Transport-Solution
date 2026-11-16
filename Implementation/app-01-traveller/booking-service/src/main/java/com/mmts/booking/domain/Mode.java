package com.mmts.booking.domain;

/** Transport modes shared by all MMTS applications (see docs/CONTRACTS.md). */
public enum Mode {
    METRO, BUS, WALK, SCOOTER, BIKE, CAR, TAXI;

    /** Metro and bus legs get a signed QR ticket. */
    public boolean usesQrTicket() {
        return this == METRO || this == BUS;
    }

    /** Scooter, bike and car legs get an unlock code for a vehicle. */
    public boolean usesUnlockCode() {
        return this == SCOOTER || this == BIKE || this == CAR;
    }

    /** Walking needs no provider, no hold and no payment. */
    public boolean needsProvider() {
        return this != WALK;
    }
}
