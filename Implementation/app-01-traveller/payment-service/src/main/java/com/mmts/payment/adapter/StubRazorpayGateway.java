package com.mmts.payment.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for Razorpay (real: Orders API, Checkout, then a signed "payment.captured" webhook).
 * It approves every charge, except:
 * - the token "tok_decline" is always declined (handy in tests), and
 * - after {@link #declineNextCharge()} (demo endpoint POST /dev/gateway/decline-next) the next charge is declined once.
 * Ids look like Razorpay's: "pay_stub_0001", "rfnd_stub_0001".
 */
@Component
public class StubRazorpayGateway implements PaymentGateway {

    public static final String DECLINE_TOKEN = "tok_decline";

    private static final Logger log = LoggerFactory.getLogger(StubRazorpayGateway.class);

    private final AtomicBoolean declineNext = new AtomicBoolean(false);
    private final AtomicLong counter = new AtomicLong();

    @Override
    public ChargeResult charge(String paymentToken, long amountPaise, String reference) {
        if (declineNext.getAndSet(false) || DECLINE_TOKEN.equals(paymentToken)) {
            log.info("Stub gateway DECLINED {} paise for {}", amountPaise, reference);
            return new ChargeResult(false, null, "GATEWAY_DECLINED");
        }
        String id = String.format("pay_stub_%04d", counter.incrementAndGet());
        log.info("Stub gateway captured {} paise for {} as {}", amountPaise, reference, id);
        return new ChargeResult(true, id, null);
    }

    @Override
    public RefundResult refund(String gatewayPaymentId, long amountPaise, String reference) {
        String id = String.format("rfnd_stub_%04d", counter.incrementAndGet());
        log.info("Stub gateway refunded {} paise on {} as {}", amountPaise, gatewayPaymentId, id);
        return new RefundResult(true, id);
    }

    /** Demo control: make the next charge fail, to show FAILED handling. */
    public void declineNextCharge() {
        declineNext.set(true);
    }
}
