package com.mmts.booking.adapter;

import java.time.LocalDate;

/**
 * INT-10: asks the Identity service whether a user may drive a vehicle class.
 * Real call: GET /api/v1/users/{userId}/drive-eligibility?vehicleClass=LMV&amp;purpose=SELF_DRIVE
 * The record below has exactly the fields of the agreed response (docs/CONTRACTS.md).
 */
public interface LicenceClient {

    DriveEligibility checkDriveEligibility(String userId, String vehicleClass, String purpose);

    /** reason: VERIFIED, NO_LICENCE, PENDING_VERIFICATION, REJECTED, EXPIRED or CLASS_NOT_COVERED. */
    record DriveEligibility(String userId, String vehicleClass, String purpose, boolean eligible,
                            String reason, LocalDate licenceExpiresOn) {
    }
}
