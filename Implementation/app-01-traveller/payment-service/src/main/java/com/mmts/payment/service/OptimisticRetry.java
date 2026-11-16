package com.mmts.payment.service;

import com.mmts.payment.common.error.ApiException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * Optimistic locking in practice: when two requests change the same wallet (or payment) at the same
 * moment, the database lets the first one win and the second one fails with an
 * {@link OptimisticLockingFailureException}. That second request is simply tried again on fresh data.
 * After {@value #MAX_ATTEMPTS} attempts we give up with 409 CONCURRENT_UPDATE so the caller can retry later.
 */
public final class OptimisticRetry {

    public static final int MAX_ATTEMPTS = 3;

    private OptimisticRetry() {
    }

    /** Runs {@code work} (which must start its own transaction) and retries it on an optimistic-lock conflict. */
    public static <T> T run(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ApiException.conflict("CONCURRENT_UPDATE",
                            "The wallet or payment was changed by another request at the same time; please retry");
                }
            }
        }
    }
}
