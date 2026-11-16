package com.mmts.booking.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** The shared "Place" value type: { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" }. */
public record PlaceDto(@NotBlank String name, @NotNull Double lat, @NotNull Double lon, String stopId) {
}
