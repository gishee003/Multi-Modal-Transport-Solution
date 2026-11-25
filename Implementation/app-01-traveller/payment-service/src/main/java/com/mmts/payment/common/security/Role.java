package com.mmts.payment.common.security;

/**
 * The roles shared by every MMTS application (RBAC, lecture 07).
 * A login token carries one or more of these names.
 */
public enum Role {
    TRAVELLER,   // commuter using the traveller app
    DRIVER,      // TSP staff who drive (e.g. a cab fleet's drivers), using the TSP staff app
    OPERATOR,    // TSP staff acting for their TSP in the partner portal
    ADMIN,       // platform admin
    SUPPORT,     // support staff: complaints, refunds
    FINANCE,     // finance staff
    REGULATOR,   // city regulator, read-only
    SERVICE      // another MMTS application calling this one
}
