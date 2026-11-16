package com.mmts.booking.api;

import com.mmts.booking.api.ActionRequests.CancelRequest;
import com.mmts.booking.api.ActionRequests.ProblemRequest;
import com.mmts.booking.api.ActionRequests.RatingRequest;
import com.mmts.booking.api.ActionRequests.VerifyQrRequest;
import com.mmts.booking.api.ActionResponses.ProblemResponse;
import com.mmts.booking.api.ActionResponses.UnlockResponse;
import com.mmts.booking.api.ActionResponses.VerifyQrResponse;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.service.BookingFacade;
import com.mmts.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * INT-04 Booking and tickets: /api/v1/bookings/**.
 * Controllers only translate HTTP to service calls; all rules live in the service and domain layers.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Book a whole journey, tickets, unlock, cancel, rate")
public class BookingController {

    private final BookingFacade facade;
    private final BookingService bookings;

    public BookingController(BookingFacade facade, BookingService bookings) {
        this.facade = facade;
        this.bookings = bookings;
    }

    @PostMapping
    @PreAuthorize("hasRole('TRAVELLER')")
    @Operation(summary = "M3.1 Book every leg of a journey (all-or-nothing)",
            description = "Send a new Idempotency-Key per booking. The same key again returns the same booking (200).")
    public ResponseEntity<BookingResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                  @Valid @RequestBody BookingRequest request) {
        BookingFacade.BookResult result = facade.book(user, request, key);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header("Idempotent-Replay", String.valueOf(!result.created()))
                .body(result.booking());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAVELLER','SUPPORT','ADMIN')")
    @Operation(summary = "List bookings (travellers: their own; support / admin: anyone's, filter by userId)")
    public List<BookingResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                      @RequestParam(required = false) String userId) {
        return bookings.list(user, userId);
    }

    @GetMapping("/{bookingId}")
    @PreAuthorize("hasAnyRole('TRAVELLER','SUPPORT','ADMIN')")
    @Operation(summary = "One booking with its legs, tickets and saga log")
    public BookingResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String bookingId) {
        return bookings.get(user, bookingId);
    }

    @PostMapping("/{bookingId}/cancel")
    @PreAuthorize("hasAnyRole('TRAVELLER','SUPPORT','ADMIN')")
    @Operation(summary = "M3.6 Cancel: release every hold and refund the payment")
    public BookingResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String bookingId,
                                  @Valid @RequestBody(required = false) CancelRequest request) {
        return bookings.cancel(user, bookingId, request == null ? null : request.reason());
    }

    @PostMapping("/{bookingId}/complete")
    @PreAuthorize("hasAnyRole('TRAVELLER','SERVICE','ADMIN')")
    @Operation(summary = "Mark the trip as finished (CONFIRMED -> COMPLETED)")
    public BookingResponse complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String bookingId) {
        return bookings.complete(user, bookingId);
    }

    @PostMapping("/{bookingId}/rating")
    @PreAuthorize("hasRole('TRAVELLER')")
    @Operation(summary = "M3.7 Rate a completed trip (1-5 stars)")
    public BookingResponse rate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String bookingId,
                                @Valid @RequestBody RatingRequest request) {
        return bookings.rate(user, bookingId, request);
    }

    @PostMapping("/{bookingId}/problems")
    @PreAuthorize("hasRole('TRAVELLER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "M3.7 Report a problem with the trip or one leg")
    public ProblemResponse reportProblem(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String bookingId,
                                         @Valid @RequestBody ProblemRequest request) {
        return bookings.reportProblem(user, bookingId, request);
    }

    @GetMapping("/{bookingId}/problems")
    @PreAuthorize("hasAnyRole('TRAVELLER','SUPPORT','ADMIN')")
    @Operation(summary = "Problems reported for a booking")
    public List<ProblemResponse> problems(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String bookingId) {
        return bookings.problems(user, bookingId);
    }

    @PostMapping("/{bookingId}/legs/{legId}/unlock")
    @PreAuthorize("hasRole('TRAVELLER')")
    @Operation(summary = "M3.4 Unlock the scooter / bike / car of a leg when the leg starts")
    public UnlockResponse unlock(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String bookingId,
                                 @PathVariable String legId) {
        return bookings.unlock(user, bookingId, legId);
    }

    @PostMapping("/tickets/verify")
    @PreAuthorize("hasAnyRole('OPERATOR','SERVICE','ADMIN')")
    @Operation(summary = "M3.3 Check a QR ticket as a metro / bus gate would (offline: signature and time only)")
    public VerifyQrResponse verify(@Valid @RequestBody VerifyQrRequest request) {
        return bookings.verifyQr(request.qr());
    }
}
