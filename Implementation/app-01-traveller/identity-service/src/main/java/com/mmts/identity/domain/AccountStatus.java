package com.mmts.identity.domain;

/** Life cycle of an account. A DELETED account keeps only its id; all personal data is gone. */
public enum AccountStatus {
    ACTIVE,
    DELETED
}
