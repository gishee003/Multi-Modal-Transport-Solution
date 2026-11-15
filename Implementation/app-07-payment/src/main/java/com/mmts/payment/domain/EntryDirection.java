package com.mmts.payment.domain;

/** The two sides of every ledger entry. Each money movement has debits and credits that add up to the same total. */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
