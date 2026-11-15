package com.mmts.booking.adapter;

import java.time.Instant;

/**
 * INT-12: holds, confirms, releases and unlocks legs with transport providers, through APP-08 (Provider Hub).
 * Real calls: POST /api/v1/holds, POST /api/v1/holds/{holdId}/confirm, DELETE /api/v1/holds/{holdId},
 * POST /api/v1/vehicles/{vehicleId}/unlock. The records have the fields of docs/CONTRACTS.md.
 */
public interface ProviderHubClient {

    /** Errors: 409 NOT_AVAILABLE, 404 PROVIDER_NOT_FOUND. */
    HoldResult hold(HoldRequest request);

    /** Only after payment. Error: 409 HOLD_NOT_ACTIVE. */
    ConfirmResult confirm(String holdId);

    /** Undo step. Safe to repeat: releasing twice is not an error. */
    void release(String holdId);

    /** When the leg starts. Error: 409 HOLD_NOT_CONFIRMED. */
    UnlockResult unlock(String vehicleId, String holdId, String userId);

    record PlaceRef(String name, double lat, double lon, String stopId) {
    }

    record HoldRequest(String bookingId, String legId, String providerId, String mode, PlaceRef from, PlaceRef to,
                       Instant startTime, String vehicleId) {
    }

    record HoldResult(String holdId, String legId, String providerId, String status, long pricePaise,
                      Instant expiresAt) {
    }

    /** access.type is QR_TICKET, UNLOCK_CODE or DRIVER_ASSIGNED; driver is set only for taxis. */
    record ConfirmResult(String holdId, String status, Access access, Driver driver) {
    }

    record Access(String type, String value, Instant validFrom, Instant validUntil) {
    }

    record Driver(String driverId, String name, String vehicleNumber, int etaMinutes) {
    }

    record UnlockResult(String vehicleId, String status, Instant unlockedAt) {
    }
}
