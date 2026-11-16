package com.mmts.identity.api.dto;

import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.LoginMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

/** Request and response bodies for registration and login (INT-01). */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** Indian mobile number: 10 digits starting with 6, 7, 8 or 9. */
    public static final String MOBILE_PATTERN = "^[6-9][0-9]{9}$";

    public enum AccountType { TRAVELLER, DRIVER }

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Pattern(regexp = MOBILE_PATTERN, message = "must be a 10-digit Indian mobile number") String mobile,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
            @NotNull AccountType accountType) {
    }

    @Schema(description = "PASSWORD needs mobile + password; OTP needs mobile + otp (ask for it first at /api/v1/auth/otp)")
    public record LoginRequest(
            @NotNull LoginMethod method,
            @NotBlank @Pattern(regexp = MOBILE_PATTERN, message = "must be a 10-digit Indian mobile number") String mobile,
            String password,
            String otp) {
    }

    public record OtpRequest(
            @NotBlank @Pattern(regexp = MOBILE_PATTERN, message = "must be a 10-digit Indian mobile number") String mobile) {
    }

    public record OtpSentResponse(String sentTo, long expiresInSeconds) {
    }

    public record LoginResponse(String token, Instant expiresAt, String userId, Set<Role> roles) {
    }
}
