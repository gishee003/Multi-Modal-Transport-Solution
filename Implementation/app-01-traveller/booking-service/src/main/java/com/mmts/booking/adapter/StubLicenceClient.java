package com.mmts.booking.adapter;

import com.mmts.booking.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for Identity service (the "stub" from lecture 19). Answers like the real INT-10:
 * every user is VERIFIED unless the demo marked them ineligible with a reason.
 */
@Component
public class StubLicenceClient implements LicenceClient {

    public static final Set<String> REFUSAL_REASONS =
            Set.of("NO_LICENCE", "PENDING_VERIFICATION", "REJECTED", "EXPIRED", "CLASS_NOT_COVERED");

    private final Map<String, String> ineligibleUsers = new ConcurrentHashMap<>();

    @Override
    public DriveEligibility checkDriveEligibility(String userId, String vehicleClass, String purpose) {
        String reason = ineligibleUsers.get(userId);
        if (reason == null) {
            return new DriveEligibility(userId, vehicleClass, purpose, true, "VERIFIED", LocalDate.parse("2031-05-20"));
        }
        return new DriveEligibility(userId, vehicleClass, purpose, false, reason, null);
    }

    /** Demo control: from now on this user is refused with the given reason. */
    public void markIneligible(String userId, String reason) {
        if (!REFUSAL_REASONS.contains(reason)) {
            throw ApiException.badRequest("BAD_REASON", "Reason must be one of " + REFUSAL_REASONS);
        }
        ineligibleUsers.put(userId, reason);
    }

    public Map<String, String> ineligibleUsers() {
        return Map.copyOf(ineligibleUsers);
    }

    public void reset() {
        ineligibleUsers.clear();
    }
}
