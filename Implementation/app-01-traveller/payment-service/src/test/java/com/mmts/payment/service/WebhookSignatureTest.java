package com.mmts.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.payment.config.PaymentSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** Webhook signatures: HMAC-SHA256 of the exact body with the shared secret, as Razorpay does. */
class WebhookSignatureTest {

    private static WebhookService withSecret(String secret) {
        PaymentSettings settings = new PaymentSettings(null, null, new PaymentSettings.Gateway(secret), null);
        return new WebhookService(settings, null, null, new ObjectMapper(), Clock.systemUTC());
    }

    @Test
    void signatureMatchesTheStandardHmacSha256TestValue() {
        // Well-known HMAC-SHA256 example (key "key"), so we know the algorithm is the real one.
        assertThat(withSecret("key").sign("The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void onlyTheRightSignatureForTheExactBodyIsValid() {
        WebhookService service = withSecret("demo-webhook-secret-change-me");
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\"}}}}";
        String signature = service.sign(body);

        assertThat(service.isValid(body, signature)).isTrue();
        assertThat(service.isValid(body, signature.toUpperCase())).isTrue();             // hex case does not matter
        assertThat(service.isValid(body.replace("pay_1", "pay_2"), signature)).isFalse(); // body was changed
        assertThat(service.isValid(body, withSecret("another-secret").sign(body))).isFalse();
        assertThat(service.isValid(body, null)).isFalse();
        assertThat(service.isValid(body, "")).isFalse();
    }
}
