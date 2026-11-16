package com.mmts.provider.common.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Demo stand-in for Kafka: publishes events on Spring's in-process event bus.
 * Listeners inside this application receive them with @EventListener.
 * Keeps the last 100 events so the demo can show them at GET /dev/events.
 */
@Component
public class InProcessEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventPublisher.class);
    private static final int KEEP = 100;

    private final ApplicationEventPublisher springEvents;
    private final Clock clock;
    private final String source;
    private final Deque<DomainEvent> recent = new ConcurrentLinkedDeque<>();

    public InProcessEventPublisher(ApplicationEventPublisher springEvents, Clock clock,
                                   @Value("${spring.application.name}") String source) {
        this.springEvents = springEvents;
        this.clock = clock;
        this.source = source;
    }

    @Override
    public DomainEvent publish(String topic, String type, Map<String, Object> payload) {
        DomainEvent event = new DomainEvent(UUID.randomUUID().toString(), topic, type, source,
                clock.instant(), Map.copyOf(payload));
        recent.addFirst(event);
        while (recent.size() > KEEP) {
            recent.pollLast();
        }
        log.info("Event published: topic={} type={} id={}", topic, type, event.eventId());
        springEvents.publishEvent(event);
        return event;
    }

    @Override
    public List<DomainEvent> recentEvents() {
        return new ArrayList<>(recent);
    }
}
