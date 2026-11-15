package com.mmts.booking.service;

import com.mmts.booking.common.events.EventPublisher;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.common.security.Role;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Publishes an AUDIT event on audit.events (INT-19) for every important action. No personal data. */
@Component
public class AuditLogger {

    public static final String TOPIC = "audit.events";

    private final EventPublisher events;

    public AuditLogger(EventPublisher events) {
        this.events = events;
    }

    public void record(AuthenticatedUser actor, String action, String bookingId, String outcome) {
        String actorId = actor == null ? "system" : actor.userId();
        Object roles = actor == null ? java.util.List.of(Role.SERVICE.name())
                : actor.roles().stream().map(Enum::name).sorted().toList();
        events.publish(TOPIC, "AUDIT", Map.of(
                "actorId", actorId,
                "actorRoles", roles,
                "action", action,
                "resourceType", "BOOKING",
                "resourceId", bookingId,
                "outcome", outcome));
    }
}
