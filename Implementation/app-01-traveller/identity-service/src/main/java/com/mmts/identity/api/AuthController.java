package com.mmts.identity.api;

import com.mmts.identity.api.dto.AuthDtos.LoginRequest;
import com.mmts.identity.api.dto.AuthDtos.LoginResponse;
import com.mmts.identity.api.dto.AuthDtos.OtpRequest;
import com.mmts.identity.api.dto.AuthDtos.OtpSentResponse;
import com.mmts.identity.api.dto.AuthDtos.RegisterRequest;
import com.mmts.identity.api.dto.UserDtos.ProfileResponse;
import com.mmts.identity.service.AccountService;
import com.mmts.identity.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Register and log in (M1.1, M1.2, INT-01). These endpoints need no token. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Register and log in", description = "No token needed")
public class AuthController {

    private final AccountService accountService;
    private final AuthService authService;

    public AuthController(AccountService accountService, AuthService authService) {
        this.accountService = accountService;
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a traveller or driver account")
    public ProfileResponse register(@Valid @RequestBody RegisterRequest request) {
        return accountService.register(request);
    }

    @PostMapping("/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a one-time login code by SMS",
            description = "Demo: read the code at GET /dev/otp/{mobile}")
    public OtpSentResponse requestOtp(@Valid @RequestBody OtpRequest request) {
        return authService.requestOtp(request.mobile());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with password or OTP and get a login token (JWT)")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
