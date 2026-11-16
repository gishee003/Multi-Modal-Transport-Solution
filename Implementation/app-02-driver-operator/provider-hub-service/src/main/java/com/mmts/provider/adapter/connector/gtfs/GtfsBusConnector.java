package com.mmts.provider.adapter.connector.gtfs;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.adapter.connector.gtfs.GtfsFeedParser.GtfsFeed;
import com.mmts.provider.adapter.connector.gtfs.GtfsFeedParser.Stop;
import com.mmts.provider.adapter.connector.gtfs.GtfsFeedParser.Trip;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.ProviderDefinition;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plug-in 2: city buses (DTC) described by a GTFS static feed.
 *
 * - loadSchedule: parses the sample GTFS files (setting "feed", a classpath folder); the core then
 *   publishes SCHEDULE_UPDATED.
 * - pollVehicles: simulates GTFS-Realtime "VehiclePosition" messages: one bus per trip, moving from
 *   stop to stop (one stop gap per minute). The core publishes them as VEHICLE_POSITION.
 * - hold / confirm: a bus seat is not reserved; confirm issues a QR ticket.
 */
@Component
public class GtfsBusConnector implements ProviderConnector {

    private static final int SECONDS_PER_STOP_GAP = 60;

    private final Map<String, GtfsFeed> feeds = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "gtfs-bus";
    }

    GtfsFeed feed(ProviderDefinition provider) {
        return feeds.computeIfAbsent(provider.getId(),
                id -> GtfsFeedParser.parse(provider.setting("feed", "gtfs/" + id.toLowerCase())));
    }

    @Override
    public Optional<ScheduleFeed> loadSchedule(ProviderDefinition provider) {
        GtfsFeed feed = feed(provider);
        return Optional.of(new ScheduleFeed(feed.feedVersion(), feed.routeIds(), feed.stops().size(),
                feed.trips().size()));
    }

    @Override
    public HoldQuote hold(ProviderDefinition provider, LegRequest leg) {
        GtfsFeed feed = feed(provider);
        for (String stopId : new String[]{leg.from().stopId(), leg.to().stopId()}) {
            if (stopId != null && !feed.stops().containsKey(stopId)) {
                throw ProviderConnector.notAvailable(provider.getId() + " does not serve stop " + stopId);
            }
        }
        return new HoldQuote(provider.priceFor(leg.from(), leg.to()), null, "dtc-" + leg.holdId());
    }

    @Override
    public AccessGrant confirm(ProviderDefinition provider, LegRequest leg, String providerRef) {
        String qr = "DTC|" + providerRef + "|" + leg.from().name() + ">" + leg.to().name() + "|" + leg.pricePaise();
        return AccessGrant.of("QR_TICKET", qr, leg.startTime(), provider.intSetting("ticket-valid-minutes", 120), null);
    }

    @Override
    public void release(ProviderDefinition provider, LegRequest leg, String providerRef) {
        // Nothing was reserved at the bus operator, so there is nothing to undo.
    }

    /** Simulated GTFS-Realtime: where is the bus of each trip right now? */
    @Override
    public List<VehicleReading> pollVehicles(ProviderDefinition provider, Instant now) {
        GtfsFeed feed = feed(provider);
        List<VehicleReading> readings = new ArrayList<>();
        for (Trip trip : feed.trips()) {
            List<Stop> stops = feed.stopsOfTrip(trip.tripId());
            if (stops.size() < 2) {
                continue;
            }
            int gaps = stops.size() - 1;
            long t = now.getEpochSecond() % ((long) gaps * SECONDS_PER_STOP_GAP);
            int gap = (int) (t / SECONDS_PER_STOP_GAP);
            double fraction = (t % SECONDS_PER_STOP_GAP) / (double) SECONDS_PER_STOP_GAP;
            Stop a = stops.get(gap);
            Stop b = stops.get(gap + 1);
            double lat = a.lat() + (b.lat() - a.lat()) * fraction;
            double lon = a.lon() + (b.lon() - a.lon()) * fraction;
            readings.add(new VehicleReading("DTC-BUS-" + trip.tripId(), Mode.BUS, lat, lon, 18.0, null, true, now));
        }
        return readings;
    }
}
