package com.mmts.booking.domain;

/** How the traveller gets access to a leg (see INT-12 in docs/CONTRACTS.md). */
public enum AccessType {
    QR_TICKET,        // metro / bus: signed QR shown at the gate
    UNLOCK_CODE,      // scooter / bike / car: code that unlocks the vehicle
    DRIVER_ASSIGNED   // taxi: a driver is on the way
}
