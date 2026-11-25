package com.mmts.provider.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Proof-of-concept profile ("poc"): receives events from the real event broker (Kafka) and hands them to this
 * service's listeners as Spring events, so every existing {@code @EventListener} works unchanged.
 *
 * Each service is its own consumer group (its application name), so every service gets every event once.
 * Events this service published itself are skipped: they were already delivered locally.
 * Delivery is at-least-once, which is why every listener in MMTS is idempotent.
 */
@Component
@Profile("poc")
public class KafkaEventBridge {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBridge.class);

    private final ApplicationEventPublisher springEvents;
    private final ObjectMapper json;
    private final String self;

    public KafkaEventBridge(ApplicationEventPublisher springEvents, ObjectMapper json,
                            @Value("${spring.application.name}") String self) {
        this.springEvents = springEvents;
        this.json = json;
        this.self = self;
    }

    @KafkaListener(topicPattern = "${mmts.poc.topic-pattern:.*\\.(events|rules|metrics)}",
            groupId = "${spring.application.name}")
    public void onMessage(String message) {
        try {
            DomainEvent event = json.readValue(message, DomainEvent.class);
            if (self.equals(event.source())) {
                return;
            }
            log.info("Event received from the broker: topic={} type={} from={}", event.topic(), event.type(), event.source());
            springEvents.publishEvent(event);
        } catch (Exception e) {
            log.warn("Ignored an event that could not be handled: {}", e.getMessage());
        }
    }
}
