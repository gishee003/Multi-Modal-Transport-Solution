package com.mmts.identity.api.dto;

import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.AccountStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Request and response bodies for the profile, saved places, consent and admin user management. */
public final class UserDtos {

    private UserDtos() {
    }

    /** A saved place; same fields as the shared "Place" type in CONTRACTS.md, plus a label such as "Home". */
    public record SavedPlaceDto(@NotBlank @Size(max = 30) String label,
                                @NotBlank String name,
                                double lat,
                                double lon,
                                String stopId) {
    }

    public record ProfileResponse(String userId,
                                  String fullName,
                                  String mobile,
                                  String email,
                                  Set<Role> roles,
                                  List<SavedPlaceDto> savedPlaces,
                                  boolean licenceCheckConsent,
                                  Instant consentRecordedAt,
                                  Instant createdAt) {
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 100) String fullName,
                                       @NotBlank @Email String email) {
    }

    public record SavedPlacesRequest(@NotNull @Size(max = 10) List<@Valid SavedPlaceDto> places) {
    }

    public record ConsentRequest(@NotNull Boolean licenceCheck) {
    }

    /** What an admin sees in the user list: the mobile number is masked (data minimisation). */
    public record UserSummary(String userId, String fullName, String maskedMobile, Set<Role> roles,
                              AccountStatus status) {
    }

    public record RoleRequest(@NotNull Role role) {
    }
}
