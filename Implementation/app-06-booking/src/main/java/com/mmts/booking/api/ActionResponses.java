package com.mmts.booking.api;

import com.mmts.booking.domain.ProblemReport;

import java.time.Instant;

/** Small response bodies for the actions on an existing booking. */
public final class ActionResponses {

    private ActionResponses() {
    }

    public record ProblemResponse(String problemId, String bookingId, String legId, ProblemReport.Category category,
                                  String description, String status, Instant createdAt) {
    }

    public record UnlockResponse(String bookingId, String legId, String vehicleId, String status, Instant unlockedAt) {
    }

    /** What a metro gate learns from a QR. reason: VALID, MALFORMED, BAD_SIGNATURE, NOT_YET_VALID or EXPIRED. */
    public record VerifyQrResponse(boolean valid, String reason, String ticketId, String bookingId, String legId,
                                   String mode, String providerId, String from, String to,
                                   Instant validFrom, Instant validUntil) {
    }
}
