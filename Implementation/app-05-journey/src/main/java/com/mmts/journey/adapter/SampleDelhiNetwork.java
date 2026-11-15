package com.mmts.journey.adapter;

import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.Stop.Kind;
import com.mmts.journey.domain.TransportGraph;
import com.mmts.journey.domain.VehicleState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the small sample Delhi network at start-up (stand-in for importing GTFS timetables, OpenStreetMap streets
 * and GBFS dock feeds). Real place names, plausible coordinates; some metro stations in between are left out.
 *
 * <pre>
 *  Blue Line:   Karol Bagh - Jhandewalan - RAJIV CHOWK - Barakhamba Rd - Mandi House - Pragati Maidan
 *                                              |
 *  Yellow Line: Hauz Khas - Green Park - AIIMS - INA - Central Secretariat - RAJIV CHOWK - New Delhi
 *  Bus 505:     Central Secretariat - India Gate - Pragati Maidan
 *  Bus 520:     AIIMS - Lodhi Road - Khan Market - India Gate
 *  Docks:       scooters (ZIPRIDE) and bikes (GREENBIKE) near IIT Delhi, Hauz Khas, Central Sectt., Rajiv Chowk,
 *               Khan Market and India Gate. Taxis (CITYCAB) go door to door.
 *  Walking:     added automatically between any two points less than 1.2 km apart.
 * </pre>
 */
@Configuration
public class SampleDelhiNetwork {

    static final double MAX_WALK_METRES = 1200;
    static final double MAX_SCOOTER_METRES = 8000;
    static final double MAX_BIKE_METRES = 5000;
    static final double WALK_DETOUR = 1.25;   // streets are longer than the straight line
    static final double RIDE_DETOUR = 1.3;
    static final double RAIL_DETOUR = 1.1;

    @Bean
    public TransportGraph transportGraph() {
        return build();
    }

    /** Builds the graph. Static so unit tests can use the same network without starting Spring. */
    public static TransportGraph build() {
        TransportGraph g = new TransportGraph();

        // Metro stations (DMRC)
        Stop hks = add(g, "DMRC-HKS", "Hauz Khas Metro", 28.5433, 77.2066, Kind.METRO_STATION);
        Stop grp = add(g, "DMRC-GRP", "Green Park Metro", 28.5598, 77.2066, Kind.METRO_STATION);
        Stop aiims = add(g, "DMRC-AIIMS", "AIIMS Metro", 28.5687, 77.2079, Kind.METRO_STATION);
        Stop ina = add(g, "DMRC-INA", "INA Metro", 28.5754, 77.2093, Kind.METRO_STATION);
        Stop csc = add(g, "DMRC-CSC", "Central Secretariat Metro", 28.6149, 77.2120, Kind.METRO_STATION);
        Stop rck = add(g, "DMRC-RCK", "Rajiv Chowk Metro", 28.6328, 77.2197, Kind.METRO_STATION);
        Stop ndls = add(g, "DMRC-NDLS", "New Delhi Metro", 28.6430, 77.2224, Kind.METRO_STATION);
        Stop kb = add(g, "DMRC-KB", "Karol Bagh Metro", 28.6440, 77.1886, Kind.METRO_STATION);
        Stop jdw = add(g, "DMRC-JDW", "Jhandewalan Metro", 28.6443, 77.2001, Kind.METRO_STATION);
        Stop bkr = add(g, "DMRC-BKR", "Barakhamba Road Metro", 28.6298, 77.2244, Kind.METRO_STATION);
        Stop mdh = add(g, "DMRC-MDH", "Mandi House Metro", 28.6258, 77.2340, Kind.METRO_STATION);
        Stop pgm = add(g, "DMRC-PGM", "Pragati Maidan Metro", 28.6236, 77.2425, Kind.METRO_STATION);

        // Bus stops (DTC)
        Stop bCsc = add(g, "DTC-CSC", "Central Secretariat Bus Stop", 28.6143, 77.2133, Kind.BUS_STOP);
        Stop bIg = add(g, "DTC-IG", "India Gate Bus Stop", 28.6135, 77.2280, Kind.BUS_STOP);
        Stop bPgm = add(g, "DTC-PGM", "Pragati Maidan Bus Stop", 28.6230, 77.2440, Kind.BUS_STOP);
        Stop bAiims = add(g, "DTC-AIIMS", "AIIMS Bus Stop", 28.5672, 77.2100, Kind.BUS_STOP);
        Stop bLodhi = add(g, "DTC-LDR", "Lodhi Road Bus Stop", 28.5918, 77.2197, Kind.BUS_STOP);
        Stop bKm = add(g, "DTC-KM", "Khan Market Bus Stop", 28.6000, 77.2265, Kind.BUS_STOP);

        // Scooter / bike docks
        Stop dIitd = add(g, "DOCK-IITD", "IIT Delhi Gate Dock", 28.5462, 77.1932, Kind.DOCK);
        Stop dHkv = add(g, "DOCK-HKV", "Hauz Khas Village Dock", 28.5530, 77.1950, Kind.DOCK);
        Stop dHks = add(g, "DOCK-HKS", "Hauz Khas Metro Dock", 28.5440, 77.2075, Kind.DOCK);
        Stop dCsc = add(g, "DOCK-CSC", "Central Secretariat Dock", 28.6150, 77.2135, Kind.DOCK);
        Stop dRck = add(g, "DOCK-RCK", "Rajiv Chowk Gate 7 Dock", 28.6335, 77.2185, Kind.DOCK);
        Stop dKm = add(g, "DOCK-KM", "Khan Market Dock", 28.6005, 77.2275, Kind.DOCK);
        Stop dIg = add(g, "DOCK-IG", "India Gate Dock", 28.6125, 77.2280, Kind.DOCK);

        // Well-known places (where trips usually start or end)
        add(g, "PL-IITD", "IIT Delhi Main Gate", 28.5459, 77.1926, Kind.PLACE);
        add(g, "PL-HKV", "Hauz Khas Village", 28.5535, 77.1940, Kind.PLACE);
        add(g, "PL-IG", "India Gate", 28.6129, 77.2295, Kind.PLACE);
        add(g, "PL-CP", "Connaught Place", 28.6315, 77.2167, Kind.PLACE);
        add(g, "PL-KM", "Khan Market", 28.6000, 77.2270, Kind.PLACE);
        add(g, "PL-NDRS", "New Delhi Railway Station", 28.6425, 77.2196, Kind.PLACE);
        add(g, "PL-BM", "Bharat Mandapam", 28.6177, 77.2433, Kind.PLACE);

        // Timetabled lines: minutes between consecutive stops
        line(g, Mode.METRO, "YL", "Yellow Line", "DMRC", List.of(hks, grp, aiims, ina, csc, rck, ndls), 3, 2, 2, 7, 4, 2);
        line(g, Mode.METRO, "BL", "Blue Line", "DMRC", List.of(kb, jdw, rck, bkr, mdh, pgm), 2, 4, 2, 2, 2);
        line(g, Mode.BUS, "505", "DTC 505", "DTC", List.of(bCsc, bIg, bPgm), 6, 6);
        line(g, Mode.BUS, "520", "DTC 520", "DTC", List.of(bAiims, bLodhi, bKm, bIg), 7, 4, 6);

        walkingLinks(g);
        rides(g, Mode.SCOOTER, "ZIPRIDE", MAX_SCOOTER_METRES, List.of(dIitd, dHkv, dHks, dCsc, dRck, dIg));
        rides(g, Mode.BIKE, "GREENBIKE", MAX_BIKE_METRES, List.of(dIitd, dHkv, dHks, dRck, dKm, dIg));
        return g;
    }

    /** The shared vehicles parked or driving at start-up (what APP-08 would report first). */
    public static List<VehicleState> sampleVehicles(Instant now) {
        return List.of(
                vehicle("ZR-SC-0041", "ZIPRIDE", Mode.SCOOTER, 28.5463, 77.1933, 88, now),
                vehicle("ZR-SC-0042", "ZIPRIDE", Mode.SCOOTER, 28.5531, 77.1951, 76, now),
                vehicle("ZR-SC-0043", "ZIPRIDE", Mode.SCOOTER, 28.6336, 77.2186, 64, now),
                vehicle("ZR-SC-0044", "ZIPRIDE", Mode.SCOOTER, 28.6151, 77.2136, 91, now),
                vehicle("ZR-SC-0045", "ZIPRIDE", Mode.SCOOTER, 28.6126, 77.2281, 55, now),
                vehicle("ZR-SC-0046", "ZIPRIDE", Mode.SCOOTER, 28.5441, 77.2076, 82, now),
                vehicle("GB-BK-0101", "GREENBIKE", Mode.BIKE, 28.5461, 77.1931, null, now),
                vehicle("GB-BK-0102", "GREENBIKE", Mode.BIKE, 28.6006, 77.2276, null, now),
                vehicle("GB-BK-0103", "GREENBIKE", Mode.BIKE, 28.6124, 77.2279, null, now),
                vehicle("GB-BK-0104", "GREENBIKE", Mode.BIKE, 28.5439, 77.2074, null, now),
                vehicle("CC-TX-2201", "CITYCAB", Mode.TAXI, 28.6315, 77.2167, null, now),
                vehicle("CC-TX-2202", "CITYCAB", Mode.TAXI, 28.5490, 77.2000, null, now));
    }

    private static VehicleState vehicle(String id, String provider, Mode mode, double lat, double lon,
                                        Integer battery, Instant now) {
        return new VehicleState(id, provider, mode, lat, lon, 0.0, battery, true, now);
    }

    private static Stop add(TransportGraph g, String id, String name, double lat, double lon, Kind kind) {
        Stop stop = new Stop(id, name, lat, lon, kind);
        g.addStop(stop);
        return stop;
    }

    private static void line(TransportGraph g, Mode mode, String routeId, String routeName, String provider,
                             List<Stop> stops, int... minutes) {
        for (int i = 0; i < minutes.length; i++) {
            Stop a = stops.get(i);
            Stop b = stops.get(i + 1);
            g.addBothWays(a, b, mode, minutes[i], a.metresTo(b) * RAIL_DETOUR, routeId, routeName, provider);
        }
    }

    private static void walkingLinks(TransportGraph g) {
        List<Stop> all = new ArrayList<>(g.stops());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Stop a = all.get(i);
                Stop b = all.get(j);
                double straight = a.metresTo(b);
                if (straight <= MAX_WALK_METRES) {
                    double metres = straight * WALK_DETOUR;
                    g.addBothWays(a, b, Mode.WALK, metres / Mode.WALK.metresPerMinute(), metres, "WALK", null, null);
                }
            }
        }
    }

    /** A ride link between every pair of docks that are not too far apart (each pair is its own ride). */
    private static void rides(TransportGraph g, Mode mode, String provider, double maxMetres, List<Stop> docks) {
        for (Stop a : docks) {
            for (Stop b : docks) {
                double straight = a.metresTo(b);
                if (a != b && straight <= maxMetres) {
                    double metres = straight * RIDE_DETOUR;
                    g.addLink(new TransportGraph.Link(a, b, mode, metres / mode.metresPerMinute(), metres,
                            a.id() + ">" + b.id(), null, provider));
                }
            }
        }
    }
}
