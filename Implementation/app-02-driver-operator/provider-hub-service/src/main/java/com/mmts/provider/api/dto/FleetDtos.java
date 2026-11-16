package com.mmts.provider.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Bodies for the provider registry, telemetry ingestion, dead letters and fleet health. */
public final class FleetDtos {

    private FleetDtos() {
    }

    /** One provider in the registry (M5.1). status is ENABLED or DISABLED. */
    public record ProviderView(String providerId, String name, String connectorType, List<String> modes,
                               String status) {
    }

    /** POST /api/v1/telemetry: a batch of readings, up to 500 at a time. */
    public record TelemetryBatch(@NotEmpty @Size(max = 500) List<Reading> readings) {
    }

    /**
     * One reading as a provider sends it. Fields are deliberately loose (text for mode and time) so that a bad
     * reading reaches our validator and goes to the dead-letter store, instead of failing the whole batch.
     */
    public record Reading(String vehicleId, String providerId, String mode, Double lat, Double lon,
                          Double speedKmph, Integer batteryPct, Boolean available, String recordedAt) {
    }

    /** 202 answer to a batch: how many were accepted and where the rejected ones went. */
    public record IngestResult(int received, int accepted, int deadLettered, List<String> deadLetterIds) {
    }

    public record DeadLetterView(String deadLetterId, String source, String providerId, String vehicleId,
                                 String reason, String rawReading, Instant receivedAt) {
    }

    /** One vehicle in the fleet-health view (M5.8). */
    public record VehicleView(String vehicleId, String mode, double lat, double lon, Integer batteryPct,
                              boolean available, boolean online, boolean lowBattery, Instant lastSeenAt) {
    }

    public record FleetHealth(String providerId, int totalVehicles, int online, int offline, int lowBattery,
                              List<VehicleView> vehicles) {
    }
}
