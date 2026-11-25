package com.mmts.identity.service;

import com.mmts.identity.api.dto.AuthDtos.LoginRequest;
import com.mmts.identity.domain.LoginMethod;
import com.mmts.identity.domain.UserAccount;

/**
 * One way of proving who you are (Strategy pattern).
 * AuthService picks the strategy that matches the "method" of the login request, so adding a new login method
 * (for example Aadhaar OTP or Google sign-in) means adding one class, without changing AuthService.
 */
public interface LoginStrategy {

    /** Which login method this strategy handles. */
    LoginMethod method();

    /**
     * Checks the credentials and returns the account.
     *
     * @throws com.mmts.identity.common.error.ApiException with 401 if the credentials are wrong
     */
    UserAccount authenticate(LoginRequest request);
}
