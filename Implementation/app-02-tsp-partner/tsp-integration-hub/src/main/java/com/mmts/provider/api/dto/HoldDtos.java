package com.mmts.provider.api.dto;

import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.Place;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Request and response bodies of INT-12 (field names exactly as in docs/CONTRACTS.md). */
public final class HoldDtos {

    private HoldDtos() {
    }

    /** POST /api/v1/holds */
    public record HoldRequest(@NotBlank String bookingId,
                              @NotBlank String legId,
                              @NotBlank String providerId,
                              @NotNull Mode mode,
                              @NotNull @Valid Place from,
                              @NotNull @Valid Place to,
                              @NotNull Instant startTime,
                              String vehicleId) {
    }

    /** 201 answer to a hold. */
    public record HoldResponse(String holdId, String legId, String providerId, String status,
                               long pricePaise, Instant expiresAt) {
    }

    /** 200 answer to confirm. driver is null except for taxi legs. */
    public record ConfirmResponse(String holdId, String status, Access access, DriverDto driver) {
    }

    public record Access(String type, String value, Instant validFrom, Instant validUntil) {
    }

    public record DriverDto(String driverId, String name, String vehicleNumber, int etaMinutes) {
    }

    /** POST /api/v1/vehicles/{vehicleId}/unlock */
    public record UnlockRequest(@NotBlank String holdId, @NotBlank String userId) {
    }

    public record UnlockResponse(String vehicleId, String status, Instant unlockedAt) {
    }
}
