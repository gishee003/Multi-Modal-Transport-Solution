package com.mmts.provider.adapter.eligibility;

import java.time.LocalDate;

/**
 * INT-13: asks Identity service whether a user may drive (here: purpose TAXI_DRIVER).
 * Real call: GET /api/v1/users/{userId}/drive-eligibility?vehicleClass=LMV&amp;purpose=TAXI_DRIVER
 * The demo uses {@link StubEligibilityClient}, which answers the same way (Adapter + stub, lecture 19).
 */
public interface EligibilityClient {

    /** @throws com.mmts.provider.common.error.ApiException 404 USER_NOT_FOUND for an unknown user */
    Eligibility check(String userId, String vehicleClass, String purpose);

    /** The response body of INT-13 (field names exactly as in docs/CONTRACTS.md). */
    record Eligibility(String userId, String vehicleClass, String purpose, boolean eligible,
                       String reason, LocalDate licenceExpiresOn) {
    }
}
