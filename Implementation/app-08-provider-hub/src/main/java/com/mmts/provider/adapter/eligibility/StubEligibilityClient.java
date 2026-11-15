package com.mmts.provider.adapter.eligibility;

import com.mmts.provider.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub of APP-04's drive-eligibility endpoint (INT-13). Behaves like the contract:
 * eligible is true only for reason VERIFIED; an unknown user gives 404 USER_NOT_FOUND.
 * The demo control POST /dev/eligibility/{userId} changes a user's answer.
 */
@Component
public class StubEligibilityClient implements EligibilityClient {

    private static final Set<String> REASONS = Set.of("VERIFIED", "NO_LICENCE", "PENDING_VERIFICATION",
            "REJECTED", "EXPIRED", "CLASS_NOT_COVERED",
            "NOT_A_DRIVER");   // TAXI_DRIVER only: licence fine, but the account has no DRIVER role

    private final Map<String, String> reasonByUser = new ConcurrentHashMap<>();

    public StubEligibilityClient() {
        // Sample users, matching the demo drivers.
        reasonByUser.put("d-201", "VERIFIED");
        reasonByUser.put("d-202", "VERIFIED");
        reasonByUser.put("d-203", "VERIFIED");
        reasonByUser.put("d-204", "VERIFIED");
        reasonByUser.put("d-205", "PENDING_VERIFICATION");
        reasonByUser.put("d-206", "VERIFIED");
        reasonByUser.put("d-207", "EXPIRED");
        reasonByUser.put("d-208", "NOT_A_DRIVER");
        reasonByUser.put("u-101", "VERIFIED");
    }

    @Override
    public Eligibility check(String userId, String vehicleClass, String purpose) {
        String reason = reasonByUser.get(userId);
        if (reason == null) {
            throw ApiException.notFound("USER_NOT_FOUND", "APP-04 has no user with id " + userId);
        }
        boolean eligible = "VERIFIED".equals(reason);
        LocalDate expires = eligible ? LocalDate.of(2031, 5, 20) : null;
        return new Eligibility(userId, vehicleClass, purpose, eligible, reason, expires);
    }

    /** Demo / test helper: what APP-04 should answer for this user from now on. */
    public void setReason(String userId, String reason) {
        if (!REASONS.contains(reason)) {
            throw ApiException.badRequest("UNKNOWN_REASON", "reason must be one of " + REASONS);
        }
        reasonByUser.put(userId, reason);
    }
}
