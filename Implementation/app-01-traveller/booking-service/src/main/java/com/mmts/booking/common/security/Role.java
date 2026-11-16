package com.mmts.booking.common.security;

/**
 * The roles shared by every MMTS application (RBAC, lecture 07).
 * A login token carries one or more of these names.
 */
public enum Role {
    TRAVELLER,   // commuter using the traveller app
    DRIVER,      // taxi driver using the driver app
    OPERATOR,    // provider / fleet staff
    ADMIN,       // platform admin
    SUPPORT,     // support staff: complaints, refunds
    FINANCE,     // finance staff
    REGULATOR,   // city regulator, read-only
    SERVICE      // another MMTS application calling this one
}
