package com.mmts.identity.api;

import com.mmts.identity.api.dto.LicenceDtos.ExpiryCheckResponse;
import com.mmts.identity.api.dto.LicenceDtos.LicenceResponse;
import com.mmts.identity.api.dto.LicenceDtos.SubmitLicenceRequest;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.service.LicenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Driving licence verification (M1.4, INT-06 driver onboarding) and the expiry check (M1.6). */
@RestController
@RequestMapping("/api/v1/licences")
@Tag(name = "3. Driving licence")
public class LicenceController {

    private final LicenceService licenceService;

    public LicenceController(LicenceService licenceService) {
        this.licenceService = licenceService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAVELLER','DRIVER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit my driving licence for verification",
            description = "Answers at once with PENDING; the DigiLocker check runs in the background. "
                    + "Sample: {\"licenceNumber\":\"KA-0120200054321\",\"dateOfBirth\":\"1998-01-15\",\"consent\":true}")
    public LicenceResponse submit(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody SubmitLicenceRequest request) {
        return licenceService.submit(user, request);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('TRAVELLER','DRIVER')")
    @Operation(summary = "My licence and its verification status")
    public LicenceResponse mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return licenceService.myLicence(user.userId());
    }

    @PostMapping("/expiry-check")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: run the nightly licence expiry check now")
    public ExpiryCheckResponse runExpiryCheck(@AuthenticationPrincipal AuthenticatedUser admin) {
        return licenceService.runExpiryCheck(admin);
    }
}
