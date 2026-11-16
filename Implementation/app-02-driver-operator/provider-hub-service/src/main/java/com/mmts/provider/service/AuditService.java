package com.mmts.provider.service;

import com.mmts.provider.common.events.EventPublisher;
import com.mmts.provider.common.security.AuthenticatedUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes AUDIT events on audit.events (INT-19) for Analytics service. No personal data, only ids. */
@Service
public class AuditService {

    private final EventPublisher events;

    public AuditService(EventPublisher events) {
        this.events = events;
    }

    /** actor is null for actions the hub does on its own (expiry, licence events). */
    public void record(AuthenticatedUser actor, String action, String resourceType, String resourceId, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorId", actor == null ? "system" : actor.userId());
        payload.put("actorRoles", actor == null ? List.of() : actor.roles().stream().map(Enum::name).sorted().toList());
        payload.put("action", action);
        payload.put("resourceType", resourceType);
        payload.put("resourceId", resourceId);
        payload.put("outcome", outcome);
        events.publish("audit.events", "AUDIT", payload);
    }
}
