package com.mmts.journey.api;

import com.mmts.journey.common.security.AuthenticatedUser;
import com.mmts.journey.domain.TripView;
import com.mmts.journey.service.TrackingService;
import com.mmts.journey.service.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** INT-03 Live trip tracking: the traveller's trips, one trip, and its live stream (Server-Sent Events). */
@RestController
@RequestMapping("/api/v1/trips")
@Tag(name = "Trips (INT-03)", description = "Trips created from confirmed bookings, and their live stream")
@PreAuthorize("hasRole('TRAVELLER')")
public class TripController {

    private final TripService trips;
    private final TrackingService tracking;

    public TripController(TripService trips, TrackingService tracking) {
        this.trips = trips;
        this.tracking = tracking;
    }

    @GetMapping
    @Operation(summary = "My trips, newest first")
    public List<TripView> myTrips(@AuthenticationPrincipal AuthenticatedUser user) {
        return trips.tripsOf(user.userId());
    }

    @GetMapping("/{tripId}")
    @Operation(summary = "One of my trips, with the state of each leg")
    public TripView trip(@PathVariable String tripId, @AuthenticationPrincipal AuthenticatedUser user) {
        return trips.view(tripId, user);
    }

    /**
     * Live stream (text/event-stream). Swagger UI cannot show a stream well; use curl -N (see the module guide).
     * No "produces" on purpose: errors (404, 403, 409) must still come back as normal JSON.
     */
    @GetMapping("/{tripId}/live")
    @Operation(summary = "Live positions, ETA and alerts for my trip (Server-Sent Events, M2.5, M2.6)")
    public SseEmitter live(@PathVariable String tripId, @AuthenticationPrincipal AuthenticatedUser user,
                           HttpServletRequest request, HttpServletResponse response) {
        SseEmitter emitter = tracking.subscribe(tripId, user);
        // The stream ends later on another thread; keep the login for that final step (tokens have no session).
        new RequestAttributeSecurityContextRepository().saveContext(SecurityContextHolder.getContext(), request, response);
        return emitter;
    }
}
