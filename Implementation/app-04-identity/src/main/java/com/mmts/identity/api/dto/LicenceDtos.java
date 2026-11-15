package com.mmts.identity.api.dto;

import com.mmts.identity.domain.DrivePurpose;
import com.mmts.identity.domain.EligibilityReason;
import com.mmts.identity.domain.LicenceStatus;
import com.mmts.identity.domain.VehicleClass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Request and response bodies for licence verification (INT-06) and drive eligibility (INT-10 / INT-13). */
public final class LicenceDtos {

    private LicenceDtos() {
    }

    public record SubmitLicenceRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}-?[0-9]{13}$",
                    message = "must look like DL-0420190012345 (state code + 13 digits)") String licenceNumber,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Boolean consent) {
    }

    /** The licence as the owner sees it. The number is masked; the full number never leaves the service. */
    public record LicenceResponse(String maskedLicenceNumber,
                                  LicenceStatus status,
                                  Set<VehicleClass> vehicleClasses,
                                  LocalDate expiresOn,
                                  String rejectionReason,
                                  Instant submittedAt,
                                  Instant checkedAt) {
    }

    /** Exactly the response of CONTRACTS.md, INT-10 and INT-13. */
    public record EligibilityResponse(String userId,
                                      VehicleClass vehicleClass,
                                      DrivePurpose purpose,
                                      boolean eligible,
                                      EligibilityReason reason,
                                      LocalDate licenceExpiresOn) {
    }

    public record ExpiryCheckResponse(LocalDate checkedOn, int expiredCount) {
    }
}
