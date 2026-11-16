package com.mmts.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A one-time login code (OTP) that was sent to a mobile number. Only the BCrypt hash of the code is stored.
 * It is kept in the database (not in server memory), so any copy of the application can check it (driver D1).
 * In production this would be a short-lived Redis entry.
 */
@Entity
@Table(name = "otp_challenge")
public class OtpChallenge {

    @Id
    private String mobile;       // one active code per mobile number; a new request replaces the old code
    private String codeHash;
    private Instant expiresAt;
    private int failedAttempts;

    protected OtpChallenge() {
        // for JPA
    }

    public OtpChallenge(String mobile, String codeHash, Instant expiresAt) {
        this.mobile = mobile;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean tooManyAttempts(int maxAttempts) {
        return failedAttempts >= maxAttempts;
    }

    public void registerFailedAttempt() {
        failedAttempts++;
    }

    public String getMobile() { return mobile; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getFailedAttempts() { return failedAttempts; }
}
