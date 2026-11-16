package com.mmts.template.common.events;

import java.time.Instant;
import java.util.Map;

/**
 * The shared event envelope (see docs/CONTRACTS.md).
 * In production this is the message written to a Kafka topic; in the demo it travels on Spring's in-process event bus.
 *
 * Receive events with:
 * <pre>
 * &#64;EventListener(condition = "#event.topic == 'licence.events'")
 * public void onLicenceEvent(DomainEvent event) { ... }
 * </pre>
 */
public record DomainEvent(String eventId,
                          String topic,
                          String type,
                          String source,
                          Instant occurredAt,
                          Map<String, Object> payload) {
}
