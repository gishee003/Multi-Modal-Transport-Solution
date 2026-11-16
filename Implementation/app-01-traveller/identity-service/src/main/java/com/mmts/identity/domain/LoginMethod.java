package com.mmts.identity.domain;

/** The ways a user can log in. Each one has its own LoginStrategy class (Strategy pattern). */
public enum LoginMethod {
    PASSWORD,
    OTP
}
