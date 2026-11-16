package com.mmts.identity.service;

import com.mmts.identity.adapter.SmsGateway;
import com.mmts.identity.api.dto.AuthDtos.LoginRequest;
import com.mmts.identity.common.error.ApiException;
import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.LoginMethod;
import com.mmts.identity.domain.OtpChallenge;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.repository.OtpChallengeRepository;
import com.mmts.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two login strategies (password and OTP), tested alone. The database and SMS gateway are replaced by
 * Mockito mocks, and the clock is fixed so "expired" can be tested exactly.
 */
class LoginStrategyTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final String MOBILE = "9876543210";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserAccountRepository users;
    private OtpChallengeRepository challenges;
    private SmsGateway sms;
    private UserAccount priya;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        challenges = mock(OtpChallengeRepository.class);
        sms = mock(SmsGateway.class);
        priya = new UserAccount("u-101", "Priya Sharma", MOBILE, "priya@example.in", encoder.encode("Priya@123"),
                Set.of(Role.TRAVELLER), NOW);
        when(users.findByMobile(MOBILE)).thenReturn(Optional.of(priya));
    }

    private OtpLoginStrategy otpStrategyAt(Instant now) {
        return new OtpLoginStrategy(users, challenges, sms, encoder, Clock.fixed(now, ZoneOffset.UTC), 5, 5);
    }

    private static LoginRequest password(String pw) {
        return new LoginRequest(LoginMethod.PASSWORD, MOBILE, pw, null);
    }

    private static LoginRequest otp(String code) {
        return new LoginRequest(LoginMethod.OTP, MOBILE, null, code);
    }

    private static String codeOf(ApiException e) {
        return e.getCode();
    }

    // ---------- password ----------

    @Test
    void correctPasswordReturnsTheUser() {
        UserAccount user = new PasswordLoginStrategy(users, encoder).authenticate(password("Priya@123"));

        assertThat(user.getId()).isEqualTo("u-101");
    }

    @Test
    void wrongPasswordIsRefused() {
        PasswordLoginStrategy strategy = new PasswordLoginStrategy(users, encoder);

        assertThatThrownBy(() -> strategy.authenticate(password("wrong-password")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(codeOf(e)).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void unknownMobileGivesTheSameErrorAsWrongPassword() {
        when(users.findByMobile("9000000099")).thenReturn(Optional.empty());
        PasswordLoginStrategy strategy = new PasswordLoginStrategy(users, encoder);

        assertThatThrownBy(() -> strategy.authenticate(new LoginRequest(LoginMethod.PASSWORD, "9000000099", "x", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(codeOf(e)).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void passwordIsStoredOnlyAsBcryptHash() {
        assertThat(priya.getPasswordHash()).startsWith("$2").doesNotContain("Priya@123");
    }

    // ---------- OTP ----------

    @Test
    void otpIsSentBySmsAndOnlyItsHashIsStored() {
        otpStrategyAt(NOW).sendOtp(MOBILE);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sms).send(eq(MOBILE), message.capture());
        String code = message.getValue().replaceAll("\\D*(\\d{6}).*", "$1");
        ArgumentCaptor<OtpChallenge> saved = ArgumentCaptor.forClass(OtpChallenge.class);
        verify(challenges).save(saved.capture());
        assertThat(saved.getValue().getCodeHash()).doesNotContain(code);
        assertThat(encoder.matches(code, saved.getValue().getCodeHash())).isTrue();
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void noSmsForUnregisteredMobile() {
        when(users.findByMobile("9000000099")).thenReturn(Optional.empty());

        otpStrategyAt(NOW).sendOtp("9000000099");

        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void correctOtpLogsInAndCanBeUsedOnlyOnce() {
        OtpChallenge challenge = new OtpChallenge(MOBILE, encoder.encode("123456"), NOW.plusSeconds(300));
        when(challenges.findById(MOBILE)).thenReturn(Optional.of(challenge));

        UserAccount user = otpStrategyAt(NOW).authenticate(otp("123456"));

        assertThat(user.getId()).isEqualTo("u-101");
        verify(challenges).delete(challenge);
    }

    @Test
    void wrongOtpIsRefusedAndCounted() {
        OtpChallenge challenge = new OtpChallenge(MOBILE, encoder.encode("123456"), NOW.plusSeconds(300));
        when(challenges.findById(MOBILE)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> otpStrategyAt(NOW).authenticate(otp("654321")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(codeOf(e)).isEqualTo("OTP_INVALID"));
        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void expiredOtpIsRefused() {
        OtpChallenge challenge = new OtpChallenge(MOBILE, encoder.encode("123456"), NOW.plusSeconds(300));
        when(challenges.findById(MOBILE)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> otpStrategyAt(NOW.plusSeconds(301)).authenticate(otp("123456")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(codeOf(e)).isEqualTo("OTP_EXPIRED"));
    }

    @Test
    void tooManyWrongOtpsBlockTheCode() {
        OtpChallenge challenge = new OtpChallenge(MOBILE, encoder.encode("123456"), NOW.plusSeconds(300));
        for (int i = 0; i < 5; i++) {
            challenge.registerFailedAttempt();
        }
        when(challenges.findById(MOBILE)).thenReturn(Optional.of(challenge));

        // even the right code is refused now
        assertThatThrownBy(() -> otpStrategyAt(NOW).authenticate(otp("123456")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(codeOf(e)).isEqualTo("OTP_TOO_MANY_ATTEMPTS"));
    }

    @Test
    void otpLoginWithoutRequestingACodeIsRefused() {
        when(challenges.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> otpStrategyAt(NOW).authenticate(otp("123456")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(codeOf(e)).isEqualTo("OTP_NOT_REQUESTED"));
    }
}
