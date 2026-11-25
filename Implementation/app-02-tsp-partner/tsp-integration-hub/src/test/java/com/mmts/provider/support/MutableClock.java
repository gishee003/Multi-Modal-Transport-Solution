package com.mmts.provider.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that tests can move forward ("wait 10 minutes" in one line). It only moves forward, so tests that
 * share a Spring context never see time go backwards.
 */
public class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-15T10:00:00Z");

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    /** Import this in a @SpringBootTest to replace the real clock everywhere in the application. */
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }
}
