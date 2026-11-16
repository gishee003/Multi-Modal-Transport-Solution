package com.mmts.provider.service;

import com.mmts.provider.api.dto.FleetDtos.IngestResult;
import com.mmts.provider.api.dto.FleetDtos.Reading;
import com.mmts.provider.common.events.DomainEvent;
import com.mmts.provider.common.events.EventPublisher;
import com.mmts.provider.domain.DeadLetter;
import com.mmts.provider.domain.VehicleState;
import com.mmts.provider.repository.DeadLetterRepository;
import com.mmts.provider.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The live-data pipeline (driver D11): valid → store + events, invalid → dead letter, silence → offline. */
@SpringBootTest
@Import(MutableClock.Config.class)
class TelemetryServiceTest {

    @Autowired TelemetryService telemetry;
    @Autowired DeadLetterRepository deadLetters;
    @Autowired EventPublisher events;
    @Autowired FeedImportService feeds;
    @Autowired MutableClock clock;

    private String now() {
        return clock.instant().toString();
    }

    private Reading scooter(String id, Double lat, Double lon, Integer battery, String time) {
        return new Reading(id, "ZIPRIDE", "SCOOTER", lat, lon, 12.0, battery, true, time);
    }

    private static String newId() {
        return "ZR-T-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private List<DomainEvent> eventsFor(String vehicleId) {
        return events.recentEvents().stream().filter(e -> vehicleId.equals(e.payload().get("vehicleId"))).toList();
    }

    @Test
    void validReadingUpdatesLatestStateAndPublishesEvents() {
        String id = newId();

        IngestResult result = telemetry.ingest(List.of(scooter(id, 28.6330, 77.2195, 80, now())), "TEST", null);

        assertThat(result.accepted()).isEqualTo(1);
        VehicleState state = telemetry.latest(id).orElseThrow();
        assertThat(state.online()).isTrue();
        assertThat(state.batteryPct()).isEqualTo(80);
        assertThat(eventsFor(id)).extracting(DomainEvent::type)
                .containsExactlyInAnyOrder("VEHICLE_POSITION", "VEHICLE_AVAILABILITY");
        DomainEvent position = eventsFor(id).stream().filter(e -> e.type().equals("VEHICLE_POSITION")).findFirst().orElseThrow();
        assertThat(position.topic()).isEqualTo("vehicle.events");
        assertThat(position.payload()).containsKeys("vehicleId", "providerId", "mode", "lat", "lon", "speedKmph",
                "batteryPct", "recordedAt");
    }

    @Test
    void invalidReadingsGoToTheDeadLetterStoreWithAReason() {
        String good = newId();
        List<Reading> batch = List.of(
                scooter(newId(), 123.4, 77.2, 50, now()),                                       // bad latitude
                new Reading(newId(), "NO-SUCH", "SCOOTER", 28.6, 77.2, 1.0, 50, true, now()),     // unknown provider
                new Reading(newId(), "ZIPRIDE", "HOVERCRAFT", 28.6, 77.2, 1.0, 50, true, now()),  // bad mode
                scooter(newId(), 28.6, 77.2, 150, now()),                                       // battery 150 %
                scooter(newId(), 28.6, 77.2, 50, "yesterday"),                                  // bad time
                scooter(good, 28.6, 77.2, 50, now()));

        IngestResult result = telemetry.ingest(batch, "TEST", null);

        assertThat(result.received()).isEqualTo(6);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.deadLettered()).isEqualTo(5);                 // 6 in = 1 accepted + 5 kept: nothing lost
        assertThat(result.deadLetterIds()).allMatch(id -> id.startsWith("dl-"));
        assertThat(deadLetters.findTop100ByOrderByIdDesc()).extracting(DeadLetter::getReason)
                .contains("INVALID_COORDINATES", "UNKNOWN_PROVIDER", "INVALID_MODE", "INVALID_BATTERY", "INVALID_TIMESTAMP");
        assertThat(telemetry.latest(good)).isPresent();
    }

    @Test
    void olderReadingDoesNotMoveTheVehicleBack() {
        String id = newId();
        telemetry.ingest(List.of(scooter(id, 28.6330, 77.2195, 80, now())), "TEST", null);

        String older = clock.instant().minusSeconds(30).toString();
        IngestResult result = telemetry.ingest(List.of(scooter(id, 28.7, 77.3, 80, older)), "TEST", null);

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(telemetry.latest(id).orElseThrow().lat()).isEqualTo(28.6330);
    }

    @Test
    void vehicleSilentForSixtySecondsIsMarkedOffline() {
        String silent = newId();
        telemetry.ingest(List.of(scooter(silent, 28.6330, 77.2195, 80, now())), "TEST", null);

        clock.advance(Duration.ofSeconds(61));
        String talkative = newId();
        telemetry.ingest(List.of(scooter(talkative, 28.6331, 77.2196, 80, now())), "TEST", null);
        telemetry.markSilentVehiclesOffline();

        assertThat(telemetry.latest(silent).orElseThrow().online()).isFalse();
        assertThat(telemetry.latest(talkative).orElseThrow().online()).isTrue();
        assertThat(eventsFor(silent)).anyMatch(e -> e.type().equals("VEHICLE_AVAILABILITY")
                && Boolean.FALSE.equals(e.payload().get("available")));
    }

    @Test
    void gtfsImportPublishesScheduleUpdated() {
        feeds.importSchedules();

        DomainEvent event = events.recentEvents().stream()
                .filter(e -> e.type().equals("SCHEDULE_UPDATED")).findFirst().orElseThrow();
        assertThat(event.topic()).isEqualTo("schedule.events");
        assertThat(event.payload()).containsEntry("providerId", "DTC").containsEntry("feedVersion", "2026-11-01");
        assertThat(event.payload().get("routeIds")).isEqualTo(List.of("DTC-505", "DTC-753"));
    }

    @Test
    void feedPollingBringsProviderVehiclesIntoTheCommonModel() {
        assertThat(feeds.pollAllFeeds()).isGreaterThan(0);

        assertThat(telemetry.latest("DTC-BUS-505-0800")).isPresent();      // simulated GTFS-Realtime bus
        assertThat(telemetry.latest("ZR-SC-0042").orElseThrow().batteryPct()).isEqualTo(82);   // GBFS scooter
        assertThat(telemetry.latest("DE-CAR-101")).isPresent();            // car-rental partner API
        assertThat(telemetry.latest("HB-BK-001")).isEmpty();               // HOPBIKE is disabled
    }
}
