package com.mmts.provider.api.dto;

import com.mmts.provider.domain.Place;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/** Bodies for driver onboarding (M5.5), status and location (INT-07) and ride offers (INT-08). */
public final class DriverDtos {

    private DriverDtos() {
    }

    /** POST /api/v1/drivers. driverId is the driver's user id in Identity service. */
    public record OnboardRequest(@NotBlank String driverId, @NotBlank String name,
                                 @NotBlank String vehicleNumber, @NotBlank String providerId) {
    }

    /** PUT /api/v1/drivers/{driverId}/status */
    public record StatusRequest(@NotBlank @Pattern(regexp = "ONLINE|OFFLINE") String status) {
    }

    /** POST /api/v1/drivers/{driverId}/locations. recordedAt is optional (now if missing). */
    public record LocationRequest(Double lat, Double lon, Double speedKmph, String recordedAt) {
    }

    public record DriverView(String driverId, String name, String vehicleNumber, String providerId,
                             String status, String statusReason, boolean online, boolean onTrip,
                             Double lat, Double lon, Instant lastSeenAt) {
    }

    public record OfferView(String offerId, String driverId, String holdId, Place pickup, Place drop,
                            long farePaise, String status, Instant createdAt) {
    }
}
