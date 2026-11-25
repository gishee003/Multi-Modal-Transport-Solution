package com.mmts.identity.domain;

/**
 * The "reason" field of the drive-eligibility answer. Only VERIFIED means eligible.
 * The first six values are fixed by CONTRACTS.md. NOT_A_DRIVER is added by Identity service for purpose TAXI_DRIVER:
 * the licence is fine, but the account does not have the DRIVER role.
 */
public enum EligibilityReason {
    VERIFIED,
    NO_LICENCE,
    PENDING_VERIFICATION,
    REJECTED,
    EXPIRED,
    CLASS_NOT_COVERED,
    NOT_A_DRIVER
}
