package com.mmts.booking.api;

import com.mmts.booking.domain.ProblemReport;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Small request bodies for the actions on an existing booking. */
public final class ActionRequests {

    private ActionRequests() {
    }

    /** POST /{bookingId}/cancel. The reason is optional. */
    public record CancelRequest(@Size(max = 200) String reason) {
    }

    /** POST /{bookingId}/rating: 1 to 5 stars and an optional comment. */
    public record RatingRequest(@NotNull @Min(1) @Max(5) Integer stars, @Size(max = 500) String comment) {
    }

    /** POST /{bookingId}/problems. legId is optional (the problem may concern the whole trip). */
    public record ProblemRequest(@NotNull ProblemReport.Category category,
                                 @NotBlank @Size(max = 1000) String description,
                                 String legId) {
    }

    /** POST /tickets/verify: the text read from the QR code. */
    public record VerifyQrRequest(@NotBlank String qr) {
    }
}
