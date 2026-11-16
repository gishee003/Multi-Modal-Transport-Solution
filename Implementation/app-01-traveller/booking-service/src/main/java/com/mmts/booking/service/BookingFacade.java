package com.mmts.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.booking.api.BookingRequest;
import com.mmts.booking.api.BookingResponse;
import com.mmts.booking.api.PlaceDto;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.Place;
import com.mmts.booking.service.saga.BookingSaga;
import com.mmts.booking.service.saga.SagaContext;
import com.mmts.booking.service.saga.SagaStep;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade (M3.1): one call, {@link #book}, hides the whole multi-step booking.
 *
 * 1. Idempotency: the same Idempotency-Key from the same user always returns the same booking (never a second one).
 * 2. Create the booking (CREATED) with all its legs.
 * 3. Run the saga (licence check, holds, payment, confirm, access). All legs succeed, or everything is undone.
 * 4. Publish BOOKING_CONFIRMED or BOOKING_CANCELLED, plus an AUDIT event.
 */
@Service
public class BookingFacade {

    private final BookingStore store;
    private final BookingSaga saga;
    private final BookingEvents bookingEvents;
    private final AuditLogger audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BookingFacade(BookingStore store, BookingSaga saga, BookingEvents bookingEvents, AuditLogger audit,
                         ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.saga = saga;
        this.bookingEvents = bookingEvents;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The answer of {@link #book}: the booking, and whether it was made now (201) or found again (200). */
    public record BookResult(BookingResponse booking, boolean created) {
    }

    public BookResult book(AuthenticatedUser user, BookingRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header, e.g. a new UUID per booking");
        }
        validate(request);
        String hash = fingerprint(request);

        // 1. Same key again? Give back the booking we already made.
        Optional<Booking> earlier = store.findByKey(user.userId(), idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), hash);
        }

        // 2. New booking with all legs.
        Booking booking = new Booking("b-" + UUID.randomUUID().toString().substring(0, 8), user.userId(),
                idempotencyKey, hash, request.paymentMethod() == null ? "WALLET" : request.paymentMethod(), clock.instant());
        for (BookingRequest.Leg leg : request.legs()) {
            booking.addLeg(leg.mode(), leg.providerId(), place(leg.from()), place(leg.to()), leg.startTime(), leg.vehicleId());
        }
        try {
            booking = store.save(booking);
        } catch (DataIntegrityViolationException e) {
            // Two identical requests arrived at the same moment: the database's unique key let only one in.
            return store.findByKey(user.userId(), idempotencyKey).map(b -> replay(b, hash)).orElseThrow(() -> e);
        }

        // 3. Run the saga.
        SagaContext context = new SagaContext(booking, store, clock, "BOOKING_FAILED");
        try {
            Booking confirmed = saga.run(context);
            bookingEvents.confirmed(confirmed);
            audit.record(user, "BOOKING_CREATE", confirmed.getId(), "SUCCESS");
            return new BookResult(BookingMapper.toResponse(confirmed), true);
        } catch (SagaStep.StepFailed failure) {
            Booking cancelled = context.booking();
            bookingEvents.cancelled(cancelled);
            audit.record(user, "BOOKING_CREATE", cancelled.getId(), "FAILED");
            boolean licence = "NOT_ELIGIBLE".equals(failure.code());
            throw new ApiException(licence ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT,
                    licence ? "NOT_ELIGIBLE" : "BOOKING_FAILED",
                    "Booking " + cancelled.getId() + " was cancelled (" + failure.getMessage()
                            + "); every completed step was undone. See GET /api/v1/bookings/" + cancelled.getId());
        }
    }

    private BookResult replay(Booking earlier, String hash) {
        if (!earlier.getRequestHash().equals(hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different booking request");
        }
        return new BookResult(BookingMapper.toResponse(earlier), false);
    }

    private static void validate(BookingRequest request) {
        boolean somethingToBook = false;
        for (BookingRequest.Leg leg : request.legs()) {
            if (leg.mode().needsProvider()) {
                somethingToBook = true;
                if (leg.providerId() == null || leg.providerId().isBlank()) {
                    throw ApiException.badRequest("PROVIDER_REQUIRED", "A " + leg.mode() + " leg needs a providerId");
                }
            }
            if (leg.mode().usesUnlockCode() && (leg.vehicleId() == null || leg.vehicleId().isBlank())) {
                throw ApiException.badRequest("VEHICLE_REQUIRED", "A " + leg.mode() + " leg needs the vehicleId chosen in the app");
            }
        }
        if (!somethingToBook) {
            throw ApiException.badRequest("NOTHING_TO_BOOK", "The journey has only walking legs; nothing to book");
        }
    }

    /** SHA-256 of the request body, to notice a key being reused for a different request. */
    private String fingerprint(BookingRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint request", e);
        }
    }

    private static Place place(PlaceDto dto) {
        return new Place(dto.name(), dto.lat(), dto.lon(), dto.stopId());
    }
}
