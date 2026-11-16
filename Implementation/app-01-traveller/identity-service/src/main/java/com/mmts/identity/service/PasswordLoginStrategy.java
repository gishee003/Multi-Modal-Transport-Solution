package com.mmts.identity.service;

import com.mmts.identity.api.dto.AuthDtos.LoginRequest;
import com.mmts.identity.common.error.ApiException;
import com.mmts.identity.domain.LoginMethod;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Login with mobile number and password. The password is compared with the stored BCrypt hash. */
@Component
public class PasswordLoginStrategy implements LoginStrategy {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public PasswordLoginStrategy(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public LoginMethod method() {
        return LoginMethod.PASSWORD;
    }

    @Override
    public UserAccount authenticate(LoginRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw ApiException.badRequest("PASSWORD_REQUIRED", "Send a password when method is PASSWORD");
        }
        UserAccount user = users.findByMobile(request.mobile()).filter(UserAccount::isActive).orElse(null);
        // Same answer for "unknown mobile" and "wrong password", so nobody can find out which numbers are registered.
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Mobile number or password is wrong");
        }
        return user;
    }
}
