package com.mmts.journey.service;

import com.mmts.journey.common.error.ApiException;
import com.mmts.journey.common.events.DomainEvent;
import com.mmts.journey.common.events.EventPublisher;
import com.mmts.journey.common.security.AuthenticatedUser;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.Trip;
import com.mmts.journey.domain.TripLeg;
import com.mmts.journey.domain.TripView;
import com.mmts.journey.domain.VehicleState;
import com.mmts.journey.repository.TripRepository;
import com.mmts.journey.repository.VehiclePositionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Trips being travelled (M2.5).
 * A trip is created when APP-06 announces BOOKING_CONFIRMED on booking.events (INT-16); cancelled or completed
 * bookings end the trip. Only the trip's owner may see it.
 */
@Service
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);
    private static final double ROAD_DETOUR = 1.3;

    /** A vehicle that is carrying a traveller right now, and where the current leg goes (used by the demo simulator). */
    public record MovingVehicle(String tripId, String vehicleId, String providerId, Mode mode,
                                double fromLat, double fromLon, double toLat, double toLon) {
    }

    private final TripRepository trips;
    private final VehiclePositionStore vehicles;
    private final EventPublisher events;
    private final Clock clock;

    public TripService(TripRepository trips, VehiclePositionStore vehicles, EventPublisher events, Clock clock) {
        this.trips = trips;
        this.vehicles = vehicles;
        this.events = events;
        this.clock = clock;
    }

    /** Receives booking.events (Observer pattern via the event bus). */
    @EventListener(condition = "#event.topic == 'booking.events'")
    @Transactional
    public void onBookingEvent(DomainEvent event) {
        Map<String, Object> p = event.payload();
        String bookingId = EventPayloads.text(p, "bookingId");
        switch (event.type()) {
            case "BOOKING_CONFIRMED" -> createFromBooking(p, event.source());
            case "BOOKING_CANCELLED" -> trips.findByBookingId(bookingId).ifPresent(Trip::cancel);
            case "BOOKING_COMPLETED" -> trips.findByBookingId(bookingId).ifPresent(Trip::complete);
            default -> log.debug("Ignoring booking event {}", event.type());
        }
    }

    /**
     * Creates the trip for a confirmed booking. Safe to repeat: the same booking twice gives the same trip
     * (Kafka may deliver an event more than once).
     */
    @Transactional
    public Trip createFromBooking(Map<String, Object> payload, String actor) {
        String bookingId = EventPayloads.text(payload, "bookingId");
        Optional<Trip> existing = trips.findByBookingId(bookingId);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<TripLeg> legs = new ArrayList<>();
        for (Map<String, Object> leg : EventPayloads.objects(payload, "legs")) {
            legs.add(toTripLeg(leg));
        }
        Trip trip = new Trip("trip-" + bookingId, bookingId, EventPayloads.text(payload, "userId"),
                (long) EventPayloads.number(payload, "totalPaise", 0), clock.instant(), legs);
        trips.save(trip);
        audit(actor, "SERVICE", "TRIP_CREATED", trip.id(), "SUCCESS");
        log.info("Trip {} created for booking {} with {} legs", trip.id(), bookingId, legs.size());
        return trip;
    }

    /** The trip, if the caller owns it. 404 TRIP_NOT_FOUND / 403 NOT_TRIP_OWNER otherwise. */
    @Transactional(readOnly = true)
    public Trip requireOwned(String tripId, AuthenticatedUser user, String action) {
        Trip trip = trips.findById(tripId)
                .orElseThrow(() -> ApiException.notFound("TRIP_NOT_FOUND", "No trip with id " + tripId));
        if (!trip.userId().equals(user.userId())) {
            audit(user.userId(), "TRAVELLER", action, tripId, "DENIED");
            throw ApiException.forbidden("NOT_TRIP_OWNER", "This trip belongs to another traveller");
        }
        return trip;
    }

    @Transactional(readOnly = true)
    public TripView view(String tripId, AuthenticatedUser user) {
        return TripView.of(requireOwned(tripId, user, "VIEW_TRIP"));
    }

    @Transactional(readOnly = true)
    public List<TripView> tripsOf(String userId) {
        return trips.findByUserIdOrderByCreatedAtDesc(userId).stream().map(TripView::of).toList();
    }

    @Transactional(readOnly = true)
    public Optional<TripView> viewByBooking(String bookingId) {
        return trips.findByBookingId(bookingId).map(TripView::of);
    }

    @Transactional(readOnly = true)
    public Optional<Trip> find(String tripId) {
        return trips.findById(tripId);
    }

    @Transactional(readOnly = true)
    public List<Trip> activeTrips() {
        return trips.findByStatus(Trip.Status.ACTIVE);
    }

    /** The current leg's vehicle reached the end of its leg: move the trip on. */
    @Transactional
    public Trip legArrived(String tripId) {
        Trip trip = trips.findById(tripId).orElseThrow();
        trip.currentLegArrived();
        return trips.save(trip);
    }

    /** For every active trip: the vehicle of its current leg and where that leg goes. */
    @Transactional(readOnly = true)
    public List<MovingVehicle> vehiclesInMotion() {
        List<MovingVehicle> moving = new ArrayList<>();
        for (Trip trip : activeTrips()) {
            trip.currentLeg().filter(TripLeg::isTracked).ifPresent(leg -> moving.add(new MovingVehicle(trip.id(),
                    leg.vehicleId(), leg.providerId(), leg.mode(), leg.fromLat(), leg.fromLon(), leg.toLat(), leg.toLon())));
        }
        return moving;
    }

    private TripLeg toTripLeg(Map<String, Object> leg) {
        String legId = EventPayloads.text(leg, "legId");
        Mode mode = Mode.valueOf(EventPayloads.text(leg, "mode"));
        String providerId = EventPayloads.text(leg, "providerId");
        Map<String, Object> from = EventPayloads.object(leg, "from");
        Map<String, Object> to = EventPayloads.object(leg, "to");
        double fromLat = EventPayloads.number(from, "lat", 0);
        double fromLon = EventPayloads.number(from, "lon", 0);
        double toLat = EventPayloads.number(to, "lat", 0);
        double toLon = EventPayloads.number(to, "lon", 0);

        String vehicleId = EventPayloads.text(leg, "vehicleId");
        if (vehicleId == null) {
            vehicleId = assignVehicle(legId, mode, providerId, fromLat, fromLon);
        }
        Double planned = EventPayloads.number(leg, "plannedMinutes");
        int plannedMinutes = planned != null ? planned.intValue() : estimateMinutes(mode, fromLat, fromLon, toLat, toLon);
        Instant start = EventPayloads.time(leg, "startTime", null);
        return new TripLeg(legId, mode, providerId, vehicleId, EventPayloads.text(from, "name"), fromLat, fromLon,
                EventPayloads.text(to, "name"), toLat, toLon, start, plannedMinutes);
    }

    /**
     * Which vehicle to follow when the booking does not say. Scooter, bike, taxi: the nearest free one at the start.
     * Metro and bus: the train or bus running this leg (in production the real-time feed's trip id).
     */
    private String assignVehicle(String legId, Mode mode, String providerId, double lat, double lon) {
        return switch (mode) {
            case SCOOTER, BIKE, TAXI, CAR -> vehicles.availableNear(mode, lat, lon, mode == Mode.TAXI ? 6000 : 1000)
                    .stream().findFirst().map(VehicleState::vehicleId).orElse(null);
            case METRO, BUS -> providerId + "-" + legId;
            case WALK -> null;
        };
    }

    private static int estimateMinutes(Mode mode, double fromLat, double fromLon, double toLat, double toLon) {
        double metres = Stop.metresBetween(fromLat, fromLon, toLat, toLon) * ROAD_DETOUR;
        return (int) Math.ceil(mode.waitMinutes() + metres / mode.metresPerMinute());
    }

    /** Audit event for APP-09 (INT-19). Ids only, no personal data. */
    private void audit(String actorId, String role, String action, String tripId, String outcome) {
        events.publish("audit.events", "AUDIT", Map.of(
                "actorId", actorId == null ? "unknown" : actorId,
                "actorRoles", List.of(role),
                "action", action,
                "resourceType", "TRIP",
                "resourceId", tripId,
                "outcome", outcome));
    }
}
