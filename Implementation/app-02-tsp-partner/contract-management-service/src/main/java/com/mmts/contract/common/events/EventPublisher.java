package com.mmts.contract.common.events;

import java.util.List;
import java.util.Map;

/**
 * Publishes events (Messaging interface, lecture 20).
 * Business code depends only on this interface. The demo uses {@link InProcessEventPublisher};
 * a Kafka-backed class implementing the same interface would replace it in production (principle P6).
 */
public interface EventPublisher {

    /** Publishes one event on a topic and returns the envelope that was sent. */
    DomainEvent publish(String topic, String type, Map<String, Object> payload);

    /** The most recent events this application published (newest first). Used by the demo page /dev/events. */
    List<DomainEvent> recentEvents();
}
