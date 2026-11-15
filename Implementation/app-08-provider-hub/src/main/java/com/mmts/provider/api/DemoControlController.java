package com.mmts.provider.api;

import com.mmts.provider.adapter.eligibility.StubEligibilityClient;
import com.mmts.provider.api.dto.FleetDtos.IngestResult;
import com.mmts.provider.api.dto.FleetDtos.Reading;
import com.mmts.provider.common.events.DomainEvent;
import com.mmts.provider.common.events.EventPublisher;
import com.mmts.provider.service.FeedImportService;
import com.mmts.provider.service.HoldService;
import com.mmts.provider.service.TelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Demo-only controls for the review (profile "demo", open without a token like the rest of /dev/**).
 * They stand in for things that happen outside this application: APP-04 changing a licence, a provider
 * sending garbage, or simply waiting 60 seconds / 10 minutes.
 */
@RestController
@RequestMapping("/dev/provider-hub")
@Profile("demo")
@Tag(name = "Demo controls (APP-08)", description = "Only in the demo profile")
public class DemoControlController {

    private final StubEligibilityClient eligibility;
    private final EventPublisher events;
    private final TelemetryService telemetry;
    private final FeedImportService feeds;
    private final HoldService holds;
    private final Clock clock;

    public DemoControlController(StubEligibilityClient eligibility, EventPublisher events, TelemetryService telemetry,
                                 FeedImportService feeds, HoldService holds, Clock clock) {
        this.eligibility = eligibility;
        this.events = events;
        this.telemetry = telemetry;
        this.feeds = feeds;
        this.holds = holds;
        this.clock = clock;
    }

    public record EligibilityChange(@NotBlank String reason) {
    }

    @PostMapping("/eligibility/{userId}")
    @Operation(summary = "Change what the APP-04 stub answers for a user, e.g. {\"reason\":\"EXPIRED\"}")
    public Map<String, Object> setEligibility(@PathVariable String userId, @Valid @RequestBody EligibilityChange change) {
        eligibility.setReason(userId, change.reason());
        return Map.of("userId", userId, "reason", change.reason(), "eligible", "VERIFIED".equals(change.reason()));
    }

    @PostMapping("/licence-expired/{userId}")
    @Operation(summary = "Simulate APP-04 publishing LICENCE_EXPIRED for a user (deactivates the driver)")
    public DomainEvent licenceExpired(@PathVariable String userId) {
        eligibility.setReason(userId, "EXPIRED");
        String today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        return events.publish("licence.events", "LICENCE_EXPIRED", Map.of("userId", userId, "expiredOn", today));
    }

    @PostMapping("/bad-telemetry")
    @Operation(summary = "Inject a bad reading (latitude 123.4) to show it landing in the dead-letter store")
    public IngestResult badTelemetry() {
        String now = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        Reading bad = new Reading("ZR-SC-0042", "ZIPRIDE", "SCOOTER", 123.4, 77.2195, 12.0, 80, true, now);
        return telemetry.ingest(List.of(bad), "DEMO", null);
    }

    @PostMapping("/jobs/offline-check")
    @Operation(summary = "Run offline detection now (normally every 10 s)")
    public Map<String, Integer> offlineCheck() {
        return Map.of("markedOffline", telemetry.markSilentVehiclesOffline());
    }

    @PostMapping("/jobs/expire-holds")
    @Operation(summary = "Run hold expiry now (normally every 30 s)")
    public Map<String, Integer> expireHolds() {
        return Map.of("expired", holds.expireOverdueHolds());
    }

    @PostMapping("/jobs/import-schedules")
    @Operation(summary = "Import the GTFS timetables again (publishes SCHEDULE_UPDATED; normally at start-up)")
    public Map<String, Integer> importSchedules() {
        return Map.of("imported", feeds.importSchedules().size());
    }

    @PostMapping("/jobs/poll-feeds")
    @Operation(summary = "Read every provider feed now (normally every 15 s)")
    public Map<String, Integer> pollFeeds() {
        return Map.of("accepted", feeds.pollAllFeeds());
    }
}
