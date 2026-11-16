package com.mmts.identity.adapter;

import com.mmts.identity.domain.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for the SMS gateway: it does not send anything. It keeps the last message per mobile number in memory,
 * so the demo can read the OTP at GET /dev/otp/{mobile}. The log shows only the masked number, never the message.
 */
@Component
public class StubSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(StubSmsGateway.class);

    private final Map<String, SentSms> lastMessage = new ConcurrentHashMap<>();
    private final Clock clock;

    public StubSmsGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void send(String mobile, String message) {
        lastMessage.put(mobile, new SentSms(message, clock.instant()));
        log.info("Stub SMS sent to {}", Masking.mobile(mobile));
    }

    /** Demo only: the last message "sent" to this mobile. */
    public Optional<SentSms> lastMessageTo(String mobile) {
        return Optional.ofNullable(lastMessage.get(mobile));
    }

    public record SentSms(String text, Instant sentAt) {
    }
}
