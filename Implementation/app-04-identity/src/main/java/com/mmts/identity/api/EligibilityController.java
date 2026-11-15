package com.mmts.identity.api;

import com.mmts.identity.api.dto.LicenceDtos.EligibilityResponse;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.domain.DrivePurpose;
import com.mmts.identity.domain.VehicleClass;
import com.mmts.identity.service.EligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INT-10 (called by APP-06 before a self-drive booking) and INT-13 (called by APP-08 before a driver goes online).
 * The format is fixed in docs/CONTRACTS.md.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "4. Drive eligibility (INT-10, INT-13)")
public class EligibilityController {

    private final EligibilityService eligibilityService;

    public EligibilityController(EligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    @GetMapping("/{userId}/drive-eligibility")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "May this user drive this vehicle class?",
            description = "Example: /api/v1/users/u-101/drive-eligibility?vehicleClass=LMV&purpose=SELF_DRIVE")
    public EligibilityResponse check(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable String userId,
                                     @RequestParam VehicleClass vehicleClass,
                                     @RequestParam DrivePurpose purpose) {
        return eligibilityService.check(caller, userId, vehicleClass, purpose);
    }
}
