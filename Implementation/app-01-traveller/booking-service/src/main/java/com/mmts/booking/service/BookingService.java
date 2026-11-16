package com.mmts.booking.service;

import com.mmts.booking.adapter.ProviderHubClient;
import com.mmts.booking.api.ActionRequests.ProblemRequest;
import com.mmts.booking.api.ActionRequests.RatingRequest;
import com.mmts.booking.api.ActionResponses.ProblemResponse;
import com.mmts.booking.api.ActionResponses.UnlockResponse;
import com.mmts.booking.api.ActionResponses.VerifyQrResponse;
import com.mmts.booking.api.BookingResponse;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.common.security.Role;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.domain.ProblemReport;
import com.mmts.booking.repository.ProblemReportRepository;
import com.mmts.booking.service.QrTicketService.QrTicket;
import com.mmts.booking.service.saga.ChargePaymentStep;
import com.mmts.booking.service.saga.HoldLegsStep;
import com.mmts.booking.service.saga.SagaContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Everything that happens to a booking after it is made: view, cancel (M3.6), complete, rate and report (M3.7),
 * unlock a vehicle (M3.4) and verify a QR at a gate (M3.3).
 *
 * Who may do what: a TRAVELLER only touches their own bookings; SUPPORT, ADMIN (and SERVICE for other apps)
 * may view and cancel any booking.
 */
@Service
public class BookingService {

    private final BookingStore store;
    private final ProblemReportRepository problems;
    private final ProviderHubClient providerHub;
    private final HoldLegsStep holdLegs;
    private final ChargePaymentStep chargePayment;
    private final QrTicketService qrTickets;
    private final BookingEvents bookingEvents;
    private final AuditLogger audit;
    private final Clock clock;

    public BookingService(BookingStore store, ProblemReportRepository problems, ProviderHubClient providerHub,
                          HoldLegsStep holdLegs, ChargePaymentStep chargePayment, QrTicketService qrTickets,
                          BookingEvents bookingEvents, AuditLogger audit, Clock clock) {
        this.store = store;
        this.problems = problems;
        this.providerHub = providerHub;
        this.holdLegs = holdLegs;
        this.chargePayment = chargePayment;
        this.qrTickets = qrTickets;
        this.bookingEvents = bookingEvents;
        this.audit = audit;
        this.clock = clock;
    }

    public BookingResponse get(AuthenticatedUser user, String bookingId) {
        return BookingMapper.toResponse(loadVisible(user, bookingId));
    }

    /** Travellers see their own bookings; staff may see anyone's (or the latest 50). */
    public List<BookingResponse> list(AuthenticatedUser user, String userIdFilter) {
        List<Booking> bookings;
        if (isStaff(user)) {
            bookings = userIdFilter == null || userIdFilter.isBlank() ? store.recent() : store.forUser(userIdFilter);
        } else {
            if (userIdFilter != null && !userIdFilter.isBlank() && !userIdFilter.equals(user.userId())) {
                throw ApiException.forbidden("NOT_YOUR_BOOKING", "Travellers can only list their own bookings");
            }
            bookings = store.forUser(user.userId());
        }
        return bookings.stream().map(BookingMapper::toResponse).toList();
    }

    /** M3.6: cancel, giving back every hold and the money. */
    public BookingResponse cancel(AuthenticatedUser user, String bookingId, String reason) {
        Booking booking = loadVisible(user, bookingId);
        if (!booking.getStatus().canMoveTo(BookingStatus.CANCELLED)) {
            throw ApiException.conflict("ILLEGAL_STATE", "Booking " + bookingId + " is " + booking.getStatus()
                    + " and can no longer be cancelled");
        }
        String why = reason == null || reason.isBlank() ? "CANCELLED_BY_USER" : reason;
        Booking cancelled = undoAndCancel(booking, why, "CANCELLED_BY_USER");
        audit.record(user, "BOOKING_CANCEL", bookingId, "SUCCESS");
        return BookingMapper.toResponse(cancelled);
    }

    /**
     * Cancels a booking that never got paid (used by the hold-expiry job and by a PAYMENT_FAILED event).
     * Returns false if the booking is already past payment, so it is left alone.
     */
    public boolean cancelUnpaid(String bookingId, String reason) {
        Booking booking = store.get(bookingId);
        if (!booking.getStatus().isBeforePayment()) {
            return false;
        }
        undoAndCancel(booking, reason, reason);
        audit.record(null, "BOOKING_AUTO_CANCEL", bookingId, "SUCCESS");
        return true;
    }

    /** The trip has ended (sent by the traveller app or by Journey service tracking). */
    public BookingResponse complete(AuthenticatedUser user, String bookingId) {
        Booking booking = loadVisible(user, bookingId);
        booking.moveTo(BookingStatus.COMPLETED, clock.instant());
        booking = store.save(booking);
        bookingEvents.completed(booking);
        audit.record(user, "BOOKING_COMPLETE", bookingId, "SUCCESS");
        return BookingMapper.toResponse(booking);
    }

    /** M3.7: rate a completed trip, 1 to 5 stars. */
    public BookingResponse rate(AuthenticatedUser user, String bookingId, RatingRequest request) {
        Booking booking = loadOwned(user, bookingId);
        booking.rate(request.stars(), request.comment(), clock.instant());
        booking = store.save(booking);
        audit.record(user, "BOOKING_RATE", bookingId, "SUCCESS");
        return BookingMapper.toResponse(booking);
    }

    /** M3.7: report a problem with the trip or one leg. */
    public ProblemResponse reportProblem(AuthenticatedUser user, String bookingId, ProblemRequest request) {
        Booking booking = loadOwned(user, bookingId);
        if (request.legId() != null && !request.legId().isBlank()) {
            booking.leg(request.legId());                       // 404 LEG_NOT_FOUND if it is not part of this booking
        }
        ProblemReport report = problems.save(new ProblemReport("pr-" + UUID.randomUUID().toString().substring(0, 8),
                bookingId, request.legId(), user.userId(), request.category(), request.description(), clock.instant()));
        audit.record(user, "PROBLEM_REPORT", bookingId, "SUCCESS");
        return BookingMapper.toProblem(report);
    }

    public List<ProblemResponse> problems(AuthenticatedUser user, String bookingId) {
        loadVisible(user, bookingId);
        return problems.findByBookingIdOrderByCreatedAtAsc(bookingId).stream().map(BookingMapper::toProblem).toList();
    }

    /** M3.4: unlock the scooter, bike or car of a leg when the traveller starts it (INT-12 unlock). */
    public UnlockResponse unlock(AuthenticatedUser user, String bookingId, String legId) {
        Booking booking = loadOwned(user, bookingId);
        BookingLeg leg = booking.leg(legId);
        booking.unlockLeg(leg, clock.instant());                // state machine checks first: CONFIRMED only
        ProviderHubClient.UnlockResult result = providerHub.unlock(leg.getVehicleId(), leg.getHoldId(), user.userId());
        store.save(booking);
        audit.record(user, "VEHICLE_UNLOCK", bookingId, "SUCCESS");
        return new UnlockResponse(bookingId, legId, result.vehicleId(), result.status(), result.unlockedAt());
    }

    /** M3.3: what a gate does with a scanned QR. Works offline: only the signature and the time are checked. */
    public VerifyQrResponse verifyQr(String qr) {
        QrTicketService.Verification check = qrTickets.verify(qr);
        QrTicket t = check.ticket();
        if (t == null) {
            return new VerifyQrResponse(false, check.reason(), null, null, null, null, null, null, null, null, null);
        }
        return new VerifyQrResponse(check.valid(), check.reason(), t.ticketId(), t.bookingId(), t.legId(), t.mode(),
                t.providerId(), t.from(), t.to(), t.validFrom(), t.validUntil());
    }

    /** Cancelling reuses the saga's undo commands: refund the payment, then release the holds. */
    private Booking undoAndCancel(Booking booking, String reason, String refundReason) {
        SagaContext context = new SagaContext(booking, store, clock, refundReason);
        String refund = chargePayment.undo(context);
        if (refund != null) {
            booking.log("CANCEL: " + refund);
        }
        String release = holdLegs.undo(context);
        if (release != null) {
            booking.log("CANCEL: " + release);
        }
        booking.cancel(reason, clock.instant());
        context.save();
        bookingEvents.cancelled(context.booking());
        return context.booking();
    }

    private Booking loadVisible(AuthenticatedUser user, String bookingId) {
        Booking booking = store.get(bookingId);
        if (!isStaff(user) && !booking.isOwnedBy(user.userId())) {
            throw ApiException.forbidden("NOT_YOUR_BOOKING", "Booking " + bookingId + " belongs to another traveller");
        }
        return booking;
    }

    private Booking loadOwned(AuthenticatedUser user, String bookingId) {
        Booking booking = store.get(bookingId);
        if (!booking.isOwnedBy(user.userId())) {
            throw ApiException.forbidden("NOT_YOUR_BOOKING", "Booking " + bookingId + " belongs to another traveller");
        }
        return booking;
    }

    private static boolean isStaff(AuthenticatedUser user) {
        return user.hasRole(Role.SUPPORT) || user.hasRole(Role.ADMIN) || user.hasRole(Role.SERVICE);
    }
}
