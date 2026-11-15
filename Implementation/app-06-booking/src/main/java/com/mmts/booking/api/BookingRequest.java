package com.mmts.booking.api;

import com.mmts.booking.domain.Mode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Body of POST /api/v1/bookings: every leg of the chosen journey (as returned by APP-05 trip search).
 * paymentMethod is WALLET (default) or GATEWAY.
 */
public record BookingRequest(@NotEmpty @Size(max = 6) List<@Valid @NotNull Leg> legs,
                             @Pattern(regexp = "WALLET|GATEWAY") String paymentMethod) {

    /** One leg. providerId is needed for every mode except WALK; vehicleId for SCOOTER, BIKE and CAR. */
    public record Leg(@NotNull Mode mode,
                      String providerId,
                      @NotNull @Valid PlaceDto from,
                      @NotNull @Valid PlaceDto to,
                      @NotNull Instant startTime,
                      String vehicleId) {
    }
}
