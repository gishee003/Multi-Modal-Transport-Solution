package com.mmts.booking.common.error;

import java.time.Instant;
import java.util.List;

/**
 * The one error format used by every MMTS application.
 * Example: {"code":"BOOKING_NOT_FOUND","message":"No booking with id 42","details":[],"timestamp":"..."}
 */
public record ApiError(String code, String message, List<String> details, Instant timestamp) {
}
