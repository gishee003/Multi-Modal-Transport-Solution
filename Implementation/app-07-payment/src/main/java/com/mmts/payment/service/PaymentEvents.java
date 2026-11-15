package com.mmts.payment.service;

import com.mmts.payment.common.events.EventPublisher;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.common.security.Role;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Observer pattern: this service announces what happened, and whoever is interested listens
 * (APP-06 listens to payment.events, APP-09 to audit.events). We never call them directly.
 * Event names and payloads follow docs/CONTRACTS.md (INT-17 and INT-19).
 */
@Component
public class PaymentEvents {

    public static final String PAYMENT_TOPIC = "payment.events";
    public static final String AUDIT_TOPIC = "audit.events";

    /** The actor used for jobs that no person started (the daily scheduler, demo data). */
    public static final AuthenticatedUser SYSTEM = new AuthenticatedUser("system", java.util.Set.of());

    private final EventPublisher publisher;

    public PaymentEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void captured(String paymentId, String bookingId, long amountPaise) {
        publisher.publish(PAYMENT_TOPIC, "PAYMENT_CAPTURED",
                Map.of("paymentId", paymentId, "bookingId", bookingId, "amountPaise", amountPaise));
    }

    public void failed(String paymentId, String bookingId, String reason) {
        publisher.publish(PAYMENT_TOPIC, "PAYMENT_FAILED",
                Map.of("paymentId", paymentId, "bookingId", bookingId, "reason", reason));
    }

    public void refunded(String refundId, String paymentId, String bookingId, long amountPaise) {
        publisher.publish(PAYMENT_TOPIC, "PAYMENT_REFUNDED", Map.of("refundId", refundId, "paymentId", paymentId,
                "bookingId", bookingId, "amountPaise", amountPaise));
    }

    /** One audit record: who did what to which thing, and how it ended. No personal data, no money details. */
    public void audit(AuthenticatedUser actor, String action, String resourceType, String resourceId, String outcome) {
        AuthenticatedUser who = actor != null ? actor : SYSTEM;
        List<String> roles = who.roles().stream().sorted(Comparator.naturalOrder()).map(Role::name).toList();
        publisher.publish(AUDIT_TOPIC, "AUDIT", Map.of(
                "actorId", who.userId(),
                "actorRoles", roles,
                "action", action,
                "resourceType", resourceType,
                "resourceId", resourceId,
                "outcome", outcome));
    }
}
