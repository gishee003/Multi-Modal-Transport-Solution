package com.mmts.payment.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for Razorpay Route transfers. Succeeds whenever the provider has a linked account;
 * a provider without one fails with NO_LINKED_ACCOUNT (its money stays owed until it is set up).
 */
@Component
public class StubRazorpayRouteGateway implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(StubRazorpayRouteGateway.class);

    private final AtomicLong counter = new AtomicLong();

    @Override
    public TransferResult transfer(String linkedAccountId, long amountPaise, String reference) {
        if (linkedAccountId == null || linkedAccountId.isBlank()) {
            return new TransferResult(false, null, "NO_LINKED_ACCOUNT");
        }
        String id = String.format("trf_stub_%04d", counter.incrementAndGet());
        log.info("Stub Route transfer {} paise to {} ({}) as {}", amountPaise, linkedAccountId, reference, id);
        return new TransferResult(true, id, null);
    }
}
