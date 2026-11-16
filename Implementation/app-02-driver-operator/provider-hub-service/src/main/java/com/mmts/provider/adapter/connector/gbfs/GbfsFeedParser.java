package com.mmts.provider.adapter.connector.gbfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a GBFS feed (General Bikeshare Feed Specification): the JSON that shared scooter and bike operators
 * publish about their vehicles. Supports both
 * - GBFS 3.0 "vehicle_status.json": data.vehicles[], vehicle_id, current_fuel_percent (0.0 to 1.0)
 * - GBFS 2.x "free_bike_status.json": data.bikes[], bike_id (no battery for pedal bikes)
 */
public final class GbfsFeedParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record GbfsVehicle(String vehicleId, double lat, double lon, boolean reserved, boolean disabled,
                              Integer batteryPct) {
    }

    public record GbfsFeed(String version, Instant lastUpdated, int ttlSeconds, List<GbfsVehicle> vehicles) {
    }

    private GbfsFeedParser() {
    }

    public static GbfsFeed parseClasspath(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read GBFS feed " + path, e);
        }
    }

    public static GbfsFeed parse(InputStream in) throws IOException {
        JsonNode root = JSON.readTree(in);
        JsonNode updated = root.path("last_updated");
        Instant lastUpdated = updated.isNumber()
                ? Instant.ofEpochSecond(updated.asLong())      // GBFS 2.x: seconds since 1970
                : Instant.parse(updated.asText());            // GBFS 3.0: ISO-8601 text
        JsonNode list = root.path("data").has("vehicles") ? root.path("data").path("vehicles")
                : root.path("data").path("bikes");
        List<GbfsVehicle> vehicles = new ArrayList<>();
        for (JsonNode v : list) {
            String id = v.has("vehicle_id") ? v.get("vehicle_id").asText() : v.path("bike_id").asText();
            Integer battery = v.has("current_fuel_percent")
                    ? (int) Math.round(v.get("current_fuel_percent").asDouble() * 100) : null;
            vehicles.add(new GbfsVehicle(id, v.path("lat").asDouble(), v.path("lon").asDouble(),
                    v.path("is_reserved").asBoolean(false), v.path("is_disabled").asBoolean(false), battery));
        }
        return new GbfsFeed(root.path("version").asText("2.3"), lastUpdated, root.path("ttl").asInt(60), vehicles);
    }
}
