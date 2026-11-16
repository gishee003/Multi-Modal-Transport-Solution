package com.mmts.identity.domain;

/**
 * State machine of a driving licence:
 * PENDING -> VERIFIED | REJECTED | EXPIRED (on submission), and VERIFIED -> EXPIRED (nightly expiry job).
 */
public enum LicenceStatus {
    PENDING,
    VERIFIED,
    REJECTED,
    EXPIRED
}
