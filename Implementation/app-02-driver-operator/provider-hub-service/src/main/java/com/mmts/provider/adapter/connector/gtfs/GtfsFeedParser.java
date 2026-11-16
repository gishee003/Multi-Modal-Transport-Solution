package com.mmts.provider.adapter.connector.gtfs;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a GTFS static feed (General Transit Feed Specification): a folder of CSV text files
 * that describe a timetable. We read agency.txt, stops.txt, routes.txt, trips.txt, stop_times.txt
 * and the optional feed_info.txt (for the feed version).
 */
public final class GtfsFeedParser {

    public record Stop(String stopId, String name, double lat, double lon) {
    }

    public record Route(String routeId, String shortName, String longName) {
    }

    public record Trip(String tripId, String routeId, String headsign) {
    }

    public record StopTime(String tripId, String stopId, int sequence, String arrivalTime) {
    }

    public record GtfsFeed(String agencyName, String feedVersion, Map<String, Stop> stops,
                           List<Route> routes, List<Trip> trips, List<StopTime> stopTimes) {

        public List<String> routeIds() {
            return routes.stream().map(Route::routeId).toList();
        }

        /** The stops a trip visits, in order. */
        public List<Stop> stopsOfTrip(String tripId) {
            return stopTimes.stream()
                    .filter(st -> st.tripId().equals(tripId))
                    .sorted(Comparator.comparingInt(StopTime::sequence))
                    .map(st -> stops.get(st.stopId()))
                    .toList();
        }
    }

    private GtfsFeedParser() {
    }

    /** @param folder classpath folder, e.g. "gtfs/dtc" */
    public static GtfsFeed parse(String folder) {
        String agency = readCsv(folder + "/agency.txt").stream()
                .findFirst().map(r -> r.get("agency_name")).orElse("unknown");
        String version = readCsv(folder + "/feed_info.txt").stream()
                .findFirst().map(r -> r.get("feed_version")).orElse("unversioned");

        Map<String, Stop> stops = new LinkedHashMap<>();
        for (Map<String, String> r : readCsv(folder + "/stops.txt")) {
            stops.put(r.get("stop_id"), new Stop(r.get("stop_id"), r.get("stop_name"),
                    Double.parseDouble(r.get("stop_lat")), Double.parseDouble(r.get("stop_lon"))));
        }
        List<Route> routes = readCsv(folder + "/routes.txt").stream()
                .map(r -> new Route(r.get("route_id"), r.get("route_short_name"), r.get("route_long_name")))
                .toList();
        List<Trip> trips = readCsv(folder + "/trips.txt").stream()
                .map(r -> new Trip(r.get("trip_id"), r.get("route_id"), r.get("trip_headsign")))
                .toList();
        List<StopTime> stopTimes = readCsv(folder + "/stop_times.txt").stream()
                .map(r -> new StopTime(r.get("trip_id"), r.get("stop_id"),
                        Integer.parseInt(r.get("stop_sequence")), r.get("arrival_time")))
                .toList();
        return new GtfsFeed(agency, version, stops, routes, trips, stopTimes);
    }

    /** Reads one CSV file into rows of header → value. A missing optional file gives no rows. */
    static List<Map<String, String>> readCsv(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            List<String> headers = splitLine(headerLine.replace("﻿", ""));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = splitLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i).trim(), i < values.size() ? values.get(i).trim() : "");
                }
                rows.add(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read GTFS file " + path, e);
        }
        return rows;
    }

    /** Splits a CSV line on commas, honouring "quoted, values". */
    private static List<String> splitLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }
}
