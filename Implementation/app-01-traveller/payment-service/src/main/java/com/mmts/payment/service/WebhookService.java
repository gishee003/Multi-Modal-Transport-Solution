package com.mmts.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.payment.api.FinanceDtos.WebhookResponse;
import com.mmts.payment.common.error.ApiException;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.config.PaymentSettings;
import com.mmts.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;

/**
 * Gateway webhooks: the gateway calls us back to say "payment pay_xxx was captured".
 * Anyone on the internet could call this URL, so we accept a call only if its X-Razorpay-Signature header
 * equals HMAC-SHA256(request body, shared secret). Only Razorpay and we know the secret, so only Razorpay
 * can produce the right signature, and any change to the body breaks it.
 */
@Service
public class WebhookService {

    private static final String HMAC = "HmacSHA256";
    private static final AuthenticatedUser GATEWAY = new AuthenticatedUser("razorpay-webhook", Set.of());

    private final byte[] secret;
    private final PaymentRepository payments;
    private final PaymentEvents events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookService(PaymentSettings settings, PaymentRepository payments, PaymentEvents events,
                          ObjectMapper objectMapper, Clock clock) {
        this.secret = settings.gateway().webhookSecret().getBytes(StandardCharsets.UTF_8);
        this.payments = payments;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The signature the gateway would send for this body: HMAC-SHA256 as lower-case hex. */
    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    /** Compares in constant time, so an attacker cannot guess the signature one character at a time. */
    public boolean isValid(String body, String signature) {
        if (body == null || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = sign(body).getBytes(StandardCharsets.UTF_8);
        byte[] given = signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given);
    }

    /**
     * Checks the signature, then marks the matching payment as confirmed by the gateway.
     * Unknown events are accepted and ignored (the gateway expects a 2xx answer). Safe to receive twice.
     */
    @Transactional
    public WebhookResponse handle(String body, String signature) {
        if (!isValid(body, signature)) {
            events.audit(GATEWAY, "WEBHOOK_RECEIVED", "WEBHOOK", "razorpay", "REJECTED");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "The webhook signature is missing or wrong");
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (Exception e) {
            throw ApiException.badRequest("WEBHOOK_BODY_INVALID", "The webhook body is not valid JSON");
        }
        String event = json.path("event").asText("");
        String gatewayPaymentId = json.at("/payload/payment/entity/id").asText("");
        String matched = null;
        if ("payment.captured".equals(event) && !gatewayPaymentId.isEmpty()) {
            var payment = payments.findByGatewayReference(gatewayPaymentId);
            if (payment.isPresent()) {
                payment.get().confirmByGateway(clock.instant());
                matched = payment.get().paymentId();
            }
        }
        events.audit(GATEWAY, "WEBHOOK_RECEIVED", "WEBHOOK", event.isEmpty() ? "unknown" : event, "SUCCESS");
        return new WebhookResponse(true, event, matched);
    }
}
