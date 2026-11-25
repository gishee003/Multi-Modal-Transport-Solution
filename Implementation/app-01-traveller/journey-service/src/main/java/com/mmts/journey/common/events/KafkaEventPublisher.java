package com.mmts.journey.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Proof-of-concept profile ("poc"): publishes every event to the real event broker (Kafka), topic = event topic.
 *
 * It first delivers the event inside this service exactly as before (through {@link InProcessEventPublisher}, so
 * local listeners and GET /dev/events behave the same), then sends it to Kafka for the other services.
 * The message key keeps events about one thing in order (vehicleId, bookingId, tspId, userId).
 */
@Component
@Primary
@Profile("poc")
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private static final List<String> KEY_FIELDS = List.of("vehicleId", "bookingId", "tspId", "paymentId", "userId");

    private final InProcessEventPublisher local;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public KafkaEventPublisher(InProcessEventPublisher local, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.local = local;
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public DomainEvent publish(String topic, String type, Map<String, Object> payload) {
        DomainEvent event = local.publish(topic, type, payload);
        try {
            kafka.send(topic, key(event), json.writeValueAsString(event));
            log.info("Event sent to the broker: topic={} type={}", topic, type);
        } catch (JsonProcessingException e) {
            log.error("Event {} could not be serialised; kept locally only", event.eventId(), e);
        }
        return event;
    }

    @Override
    public List<DomainEvent> recentEvents() {
        return local.recentEvents();
    }

    private static String key(DomainEvent event) {
        for (String field : KEY_FIELDS) {
            Object value = event.payload().get(field);
            if (value != null) {
                return value.toString();
            }
        }
        return event.eventId();
    }
}
