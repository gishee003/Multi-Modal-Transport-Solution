package com.mmts.provider.adapter.connector;

import com.mmts.provider.adapter.connector.ProviderConnector.ScheduleFeed;
import com.mmts.provider.adapter.connector.gbfs.GbfsFeedParser;
import com.mmts.provider.adapter.connector.gbfs.GbfsFeedParser.GbfsFeed;
import com.mmts.provider.adapter.connector.gbfs.GbfsFeedParser.GbfsVehicle;
import com.mmts.provider.adapter.connector.gtfs.GtfsBusConnector;
import com.mmts.provider.adapter.connector.gtfs.GtfsFeedParser;
import com.mmts.provider.adapter.connector.gtfs.GtfsFeedParser.GtfsFeed;
import com.mmts.provider.adapter.connector.gtfs.GtfsFeedParser.Stop;
import com.mmts.provider.domain.Mode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.mmts.provider.support.TestProviders.provider;
import static org.assertj.core.api.Assertions.assertThat;

/** The sample GTFS and GBFS feeds in src/main/resources are read correctly. */
class FeedParsersTest {

    @Test
    void gtfsSampleFeedIsParsed() {
        GtfsFeed feed = GtfsFeedParser.parse("gtfs/dtc");

        assertThat(feed.agencyName()).isEqualTo("Delhi Transport Corporation");
        assertThat(feed.feedVersion()).isEqualTo("2026-11-01");
        assertThat(feed.stops()).hasSize(6);
        assertThat(feed.routeIds()).containsExactly("DTC-505", "DTC-753");
        assertThat(feed.routes().get(1).longName()).isEqualTo("India Gate - ITO - Delhi Gate");   // quoted CSV value
        assertThat(feed.trips()).hasSize(3);
        assertThat(feed.stopsOfTrip("753-0815")).extracting(Stop::name)
                .containsExactly("India Gate", "ITO", "Delhi Gate");
    }

    @Test
    void gtfsConnectorTurnsTheFeedIntoAScheduleSummary() {
        ScheduleFeed schedule = new GtfsBusConnector()
                .loadSchedule(provider("DTC", "gtfs-bus", Mode.BUS, true, "feed", "gtfs/dtc")).orElseThrow();

        assertThat(schedule.feedVersion()).isEqualTo("2026-11-01");
        assertThat(schedule.routeIds()).containsExactly("DTC-505", "DTC-753");
        assertThat(schedule.stopCount()).isEqualTo(6);
        assertThat(schedule.tripCount()).isEqualTo(3);
    }

    @Test
    void gbfsVersion3VehicleStatusIsParsed() {
        GbfsFeed feed = GbfsFeedParser.parseClasspath("gbfs/zipride/vehicle_status.json");

        assertThat(feed.version()).isEqualTo("3.0");
        assertThat(feed.lastUpdated()).isEqualTo(Instant.parse("2026-11-15T09:59:30Z"));
        assertThat(feed.vehicles()).hasSize(7);
        GbfsVehicle first = feed.vehicles().get(0);
        assertThat(first.vehicleId()).isEqualTo("ZR-SC-0042");
        assertThat(first.batteryPct()).isEqualTo(82);                 // 0.82 → 82 %
        assertThat(feed.vehicles()).filteredOn(GbfsVehicle::disabled).extracting(GbfsVehicle::vehicleId)
                .containsExactly("ZR-SC-0013");
    }

    @Test
    void gbfsVersion2FreeBikeStatusIsParsed() {
        GbfsFeed feed = GbfsFeedParser.parseClasspath("gbfs/hopbike/free_bike_status.json");

        assertThat(feed.version()).isEqualTo("2.3");
        assertThat(feed.lastUpdated()).isEqualTo(Instant.ofEpochSecond(1794733170L));
        assertThat(feed.vehicles()).hasSize(4);
        assertThat(feed.vehicles()).allSatisfy(v -> assertThat(v.batteryPct()).isNull());   // pedal bikes
        assertThat(feed.vehicles()).filteredOn(GbfsVehicle::reserved).extracting(GbfsVehicle::vehicleId)
                .containsExactly("HB-BK-004");
    }
}
