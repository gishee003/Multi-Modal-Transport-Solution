package com.mmts.provider.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background jobs. Switched off in tests (src/test/resources/config/application.yml sets
 * mmts.provider-hub.background-jobs=false), so tests call the same methods directly and control time.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mmts.provider-hub.background-jobs", havingValue = "true")
public class Schedulers {

    private final FeedImportService feeds;
    private final TelemetryService telemetry;
    private final HoldService holds;

    public Schedulers(FeedImportService feeds, TelemetryService telemetry, HoldService holds) {
        this.feeds = feeds;
        this.telemetry = telemetry;
        this.holds = holds;
    }

    /** Re-read GBFS / partner feeds and simulate GTFS-Realtime bus positions. */
    @Scheduled(initialDelayString = "${mmts.provider-hub.feed-poll-ms:15000}",
            fixedDelayString = "${mmts.provider-hub.feed-poll-ms:15000}")
    void pollFeeds() {
        feeds.pollAllFeeds();
    }

    /** Vehicles silent for 60 s become offline. */
    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    void detectOffline() {
        telemetry.markSilentVehiclesOffline();
    }

    /** Holds older than 10 minutes are released. */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    void expireHolds() {
        holds.expireOverdueHolds();
    }
}
