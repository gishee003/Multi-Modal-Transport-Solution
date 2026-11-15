package com.mmts.journey.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Stub for Firebase Cloud Messaging: writes the notification to the log and remembers the last 50,
 * so tests and the demo can see what would have been sent to the phone.
 */
@Component
public class StubFcmPushNotifier implements PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(StubFcmPushNotifier.class);

    public record SentPush(String userId, String title, String body) {
    }

    private final Deque<SentPush> sent = new ConcurrentLinkedDeque<>();

    @Override
    public void send(String userId, String title, String body) {
        log.info("[FCM stub] push to user {}: {} - {}", userId, title, body);
        sent.addFirst(new SentPush(userId, title, body));
        while (sent.size() > 50) {
            sent.pollLast();
        }
    }

    /** Newest first. */
    public List<SentPush> sent() {
        return new ArrayList<>(sent);
    }
}
