package com.mmts.provider.service;

import com.mmts.provider.adapter.connector.ProviderConnector.ScheduleFeed;
import com.mmts.provider.adapter.connector.ProviderConnector.VehicleReading;
import com.mmts.provider.api.dto.FleetDtos.Reading;
import com.mmts.provider.common.events.EventPublisher;
import com.mmts.provider.service.ProviderRegistry.RegisteredProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls data out of the plug-ins (M5.2 schedules, M5.3 live vehicles). The core is in charge:
 * it asks every enabled plug-in, then pushes the answers through the one shared pipeline.
 * A failing provider is logged and skipped, so it never stops the others (isolation).
 */
@Service
public class FeedImportService {

    private static final Logger log = LoggerFactory.getLogger(FeedImportService.class);

    private final ProviderRegistry registry;
    private final TelemetryService telemetry;
    private final EventPublisher events;
    private final Clock clock;

    public FeedImportService(ProviderRegistry registry, TelemetryService telemetry, EventPublisher events, Clock clock) {
        this.registry = registry;
        this.telemetry = telemetry;
        this.events = events;
        this.clock = clock;
    }

    /** At start-up: import timetables, then read every live feed once. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        importSchedules();
        pollAllFeeds();
    }

    /** M5.2: each provider with a GTFS timetable → one SCHEDULE_UPDATED event on schedule.events. */
    public List<ScheduleFeed> importSchedules() {
        List<ScheduleFeed> imported = new ArrayList<>();
        for (RegisteredProvider provider : registry.enabledProviders()) {
            try {
                provider.connector().loadSchedule(provider.definition()).ifPresent(feed -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("providerId", provider.id());
                    payload.put("feedVersion", feed.feedVersion());
                    payload.put("routeIds", feed.routeIds());
                    events.publish("schedule.events", "SCHEDULE_UPDATED", payload);
                    imported.add(feed);
                    log.info("Imported timetable of {}: version {}, {} routes, {} stops, {} trips", provider.id(),
                            feed.feedVersion(), feed.routeIds().size(), feed.stopCount(), feed.tripCount());
                });
            } catch (RuntimeException e) {
                log.warn("Timetable import failed for {}: {}", provider.id(), e.getMessage());
            }
        }
        return imported;
    }

    /** M5.3: read every provider's live feed (GBFS, GTFS-Realtime, partner API) into the pipeline. */
    public int pollAllFeeds() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        int accepted = 0;
        for (RegisteredProvider provider : registry.enabledProviders()) {
            try {
                List<Reading> readings = provider.connector().pollVehicles(provider.definition(), now).stream()
                        .map(v -> toReading(provider.id(), v))
                        .toList();
                if (!readings.isEmpty()) {
                    accepted += telemetry.ingest(readings, "FEED:" + provider.id(), null).accepted();
                }
            } catch (RuntimeException e) {
                log.warn("Live feed of {} failed: {}", provider.id(), e.getMessage());
            }
        }
        return accepted;
    }

    private static Reading toReading(String providerId, VehicleReading v) {
        return new Reading(v.vehicleId(), providerId, v.mode().name(), v.lat(), v.lon(), v.speedKmph(),
                v.batteryPct(), v.available(), v.recordedAt().toString());
    }
}
