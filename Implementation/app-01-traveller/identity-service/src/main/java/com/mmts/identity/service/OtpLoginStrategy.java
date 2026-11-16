package com.mmts.identity.service;

import com.mmts.identity.adapter.SmsGateway;
import com.mmts.identity.api.dto.AuthDtos.LoginRequest;
import com.mmts.identity.common.error.ApiException;
import com.mmts.identity.domain.LoginMethod;
import com.mmts.identity.domain.Masking;
import com.mmts.identity.domain.OtpChallenge;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.repository.OtpChallengeRepository;
import com.mmts.identity.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Login with a one-time code sent by SMS. Step 1: {@link #sendOtp(String)}. Step 2: {@link #authenticate}.
 * Rules: 6 digits, valid for a few minutes (setting), used only once, and blocked after too many wrong tries.
 */
@Component
public class OtpLoginStrategy implements LoginStrategy {

    private static final Logger log = LoggerFactory.getLogger(OtpLoginStrategy.class);

    private final UserAccountRepository users;
    private final OtpChallengeRepository challenges;
    private final SmsGateway smsGateway;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration validity;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();

    public OtpLoginStrategy(UserAccountRepository users, OtpChallengeRepository challenges, SmsGateway smsGateway,
                            PasswordEncoder passwordEncoder, Clock clock,
                            @Value("${mmts.identity.otp.validity-minutes}") long validityMinutes,
                            @Value("${mmts.identity.otp.max-attempts}") int maxAttempts) {
        this.users = users;
        this.challenges = challenges;
        this.smsGateway = smsGateway;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.validity = Duration.ofMinutes(validityMinutes);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public LoginMethod method() {
        return LoginMethod.OTP;
    }

    /**
     * Creates a new code and sends it by SMS. If the mobile number is not registered nothing is sent, but the caller
     * gets the same answer, so the endpoint cannot be used to find out who has an account.
     */
    public Duration sendOtp(String mobile) {
        if (users.findByMobile(mobile).filter(UserAccount::isActive).isEmpty()) {
            log.info("OTP requested for unregistered mobile {}", Masking.mobile(mobile));
            return validity;
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        challenges.save(new OtpChallenge(mobile, passwordEncoder.encode(code), clock.instant().plus(validity)));
        smsGateway.send(mobile, "Your MMTS login code is " + code + ". It is valid for "
                + validity.toMinutes() + " minutes. Do not share it with anyone.");
        return validity;
    }

    @Override
    public UserAccount authenticate(LoginRequest request) {
        if (request.otp() == null || request.otp().isBlank()) {
            throw ApiException.badRequest("OTP_REQUIRED", "Send the otp when method is OTP");
        }
        OtpChallenge challenge = challenges.findById(request.mobile())
                .orElseThrow(() -> unauthorized("OTP_NOT_REQUESTED", "Ask for a code first at POST /api/v1/auth/otp"));
        Instant now = clock.instant();
        if (challenge.isExpired(now)) {
            challenges.delete(challenge);
            throw unauthorized("OTP_EXPIRED", "The code has expired; ask for a new one");
        }
        if (challenge.tooManyAttempts(maxAttempts)) {
            challenges.delete(challenge);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_TOO_MANY_ATTEMPTS",
                    "Too many wrong codes; ask for a new one");
        }
        if (!passwordEncoder.matches(request.otp(), challenge.getCodeHash())) {
            challenge.registerFailedAttempt();
            challenges.save(challenge);
            throw unauthorized("OTP_INVALID", "The code is wrong");
        }
        challenges.delete(challenge);   // a code works only once
        return users.findByMobile(request.mobile()).filter(UserAccount::isActive)
                .orElseThrow(() -> unauthorized("INVALID_CREDENTIALS", "No active account for this mobile number"));
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
