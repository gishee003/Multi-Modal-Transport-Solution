package com.mmts.identity.service;

import com.mmts.identity.common.events.EventPublisher;
import com.mmts.identity.common.security.Role;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

/**
 * Publishes an AUDIT event on "audit.events" for every sensitive action (INT-19, read by APP-09).
 * The event holds ids, roles and outcomes only: never a name, mobile, email or licence number (driver D2).
 */
@Service
public class AuditService {

    public static final String TOPIC = "audit.events";

    private final EventPublisher events;

    public AuditService(EventPublisher events) {
        this.events = events;
    }

    public void record(String actorId, Collection<Role> actorRoles, String action,
                       String resourceType, String resourceId, String outcome) {
        events.publish(TOPIC, "AUDIT", Map.of(
                "actorId", actorId,
                "actorRoles", actorRoles.stream().map(Enum::name).sorted().toList(),
                "action", action,
                "resourceType", resourceType,
                "resourceId", resourceId,
                "outcome", outcome));
    }
}
