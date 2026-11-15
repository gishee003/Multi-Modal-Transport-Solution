package com.mmts.identity.domain;

/** Why a caller asks whether a user may drive (CONTRACTS.md, INT-10 and INT-13). */
public enum DrivePurpose {
    SELF_DRIVE,   // APP-06: the traveller rents a car or a geared scooter (INT-10)
    TAXI_DRIVER   // APP-08: a driver is activated to take taxi rides (INT-13)
}
