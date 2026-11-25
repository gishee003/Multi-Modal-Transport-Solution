package com.mmts.contract.domain;

import java.util.Map;
import java.util.Set;

/**
 * Life cycle of a TSP contract (State pattern as a table of allowed moves).
 *
 * DRAFT -> UNDER_REVIEW -> ACTIVE <-> SUSPENDED
 *   any state except TERMINATED -> TERMINATED
 *   UNDER_REVIEW -> DRAFT (sent back for changes)
 */
public enum ContractStatus {
    DRAFT, UNDER_REVIEW, ACTIVE, SUSPENDED, TERMINATED;

    private static final Map<ContractStatus, Set<ContractStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(UNDER_REVIEW, TERMINATED),
            UNDER_REVIEW, Set.of(ACTIVE, DRAFT, TERMINATED),
            ACTIVE, Set.of(SUSPENDED, TERMINATED),
            SUSPENDED, Set.of(ACTIVE, TERMINATED),
            TERMINATED, Set.of());

    public boolean canMoveTo(ContractStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
