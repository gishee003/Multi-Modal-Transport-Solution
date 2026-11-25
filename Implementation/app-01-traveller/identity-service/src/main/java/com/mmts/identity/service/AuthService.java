package com.mmts.identity.service;

import com.mmts.identity.api.dto.AuthDtos.LoginRequest;
import com.mmts.identity.api.dto.AuthDtos.LoginResponse;
import com.mmts.identity.api.dto.AuthDtos.OtpSentResponse;
import com.mmts.identity.common.error.ApiException;
import com.mmts.identity.common.security.JwtService;
import com.mmts.identity.domain.LoginMethod;
import com.mmts.identity.domain.Masking;
import com.mmts.identity.domain.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Login (M1.2). Picks the right {@link LoginStrategy}, then issues a signed login token (JWT) with the user's roles.
 * Nothing is stored on the server for a login: no session. Any copy of any MMTS app can check the token
 * on its own, which is what lets logins scale (driver D1).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final Map<LoginMethod, LoginStrategy> strategies = new EnumMap<>(LoginMethod.class);
    private final OtpLoginStrategy otpLogin;
    private final JwtService jwtService;
    private final AuditService audit;

    public AuthService(List<LoginStrategy> allStrategies, OtpLoginStrategy otpLogin, JwtService jwtService,
                       AuditService audit) {
        allStrategies.forEach(s -> strategies.put(s.method(), s));
        this.otpLogin = otpLogin;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    public LoginResponse login(LoginRequest request) {
        LoginStrategy strategy = strategies.get(request.method());
        UserAccount user;
        try {
            user = strategy.authenticate(request);
        } catch (ApiException e) {
            log.info("Login failed ({}) for {}: {}", request.method(), Masking.mobile(request.mobile()), e.getCode());
            audit.record("anonymous", Set.of(), "LOGIN", "USER_ACCOUNT", "unknown", "FAILED_" + e.getCode());
            throw e;
        }
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getRoles());
        audit.record(user.getId(), user.getRoles(), "LOGIN", "USER_ACCOUNT", user.getId(),
                "SUCCESS_" + request.method());
        return new LoginResponse(issued.token(), issued.expiresAt(), user.getId(), Set.copyOf(user.getRoles()));
    }

    public OtpSentResponse requestOtp(String mobile) {
        long seconds = otpLogin.sendOtp(mobile).toSeconds();
        return new OtpSentResponse(Masking.mobile(mobile), seconds);
    }
}
