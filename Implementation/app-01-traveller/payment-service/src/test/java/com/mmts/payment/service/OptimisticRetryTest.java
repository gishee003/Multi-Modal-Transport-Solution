package com.mmts.payment.service;

import com.mmts.payment.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What happens after an optimistic-lock conflict: try again on fresh data, give up with 409 after 3 tries. */
class OptimisticRetryTest {

    @Test
    void aConflictIsRetriedAndTheSecondTryWins() {
        AtomicInteger attempts = new AtomicInteger();

        String result = OptimisticRetry.run(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new ObjectOptimisticLockingFailureException("Wallet", "u-101");
            }
            return "saved";
        });

        assertThat(result).isEqualTo("saved");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void afterThreeConflictsTheCallerGets409() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> OptimisticRetry.run(() -> {
            attempts.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Wallet", "u-101");
        }))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("CONCURRENT_UPDATE");
        assertThat(attempts).hasValue(OptimisticRetry.MAX_ATTEMPTS);
    }
}
