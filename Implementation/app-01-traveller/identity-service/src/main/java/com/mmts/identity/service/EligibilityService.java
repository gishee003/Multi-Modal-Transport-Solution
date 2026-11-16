package com.mmts.identity.service;

import com.mmts.identity.api.dto.LicenceDtos.EligibilityResponse;
import com.mmts.identity.common.error.ApiException;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.DrivePurpose;
import com.mmts.identity.domain.DrivingLicence;
import com.mmts.identity.domain.EligibilityReason;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.domain.VehicleClass;
import com.mmts.identity.repository.DrivingLicenceRepository;
import com.mmts.identity.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drive-eligibility check (M1.5, INT-10 for Booking service and INT-13 for Provider Hub service), driver D3:
 * nobody may self-drive or be activated as a taxi driver without a verified, current licence of the right class.
 *
 * Rules, in order:
 * 1. Unknown or deleted user: 404 USER_NOT_FOUND.
 * 2. No licence: NO_LICENCE.
 * 3. The licence rule in {@link DrivingLicence#eligibilityFor}: PENDING_VERIFICATION, REJECTED, EXPIRED,
 *    CLASS_NOT_COVERED or VERIFIED.
 * 4. Extra rule for TAXI_DRIVER: the account must also have the DRIVER role, otherwise NOT_A_DRIVER.
 *    (A traveller with a car licence must not be switched on as a taxi driver by mistake.)
 */
@Service
public class EligibilityService {

    private final UserAccountRepository users;
    private final DrivingLicenceRepository licences;
    private final LicenceService licenceService;
    private final AuditService audit;

    public EligibilityService(UserAccountRepository users, DrivingLicenceRepository licences,
                              LicenceService licenceService, AuditService audit) {
        this.users = users;
        this.licences = licences;
        this.licenceService = licenceService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public EligibilityResponse check(AuthenticatedUser caller, String userId, VehicleClass vehicleClass,
                                     DrivePurpose purpose) {
        UserAccount user = users.findById(userId).filter(UserAccount::isActive)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No user with id " + userId));
        DrivingLicence licence = licences.findByUserId(userId).orElse(null);

        EligibilityReason reason = licence == null
                ? EligibilityReason.NO_LICENCE
                : licence.eligibilityFor(vehicleClass, licenceService.today());
        if (reason == EligibilityReason.VERIFIED && purpose == DrivePurpose.TAXI_DRIVER && !user.hasRole(Role.DRIVER)) {
            reason = EligibilityReason.NOT_A_DRIVER;
        }

        audit.record(caller.userId(), caller.roles(), "CHECK_DRIVE_ELIGIBILITY_" + purpose, "USER_ACCOUNT", userId,
                reason.name());
        return new EligibilityResponse(userId, vehicleClass, purpose, reason == EligibilityReason.VERIFIED, reason,
                licence == null ? null : licence.getExpiresOn());
    }
}
