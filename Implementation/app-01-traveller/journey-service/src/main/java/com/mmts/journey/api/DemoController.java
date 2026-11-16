package com.mmts.journey.api;

import com.mmts.journey.adapter.StubFcmPushNotifier;
import com.mmts.journey.adapter.VehicleFeedSimulator;
import com.mmts.journey.common.error.ApiException;
import com.mmts.journey.common.events.DomainEvent;
import com.mmts.journey.common.events.EventPublisher;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.TripView;
import com.mmts.journey.domain.journey.Journey;
import com.mmts.journey.domain.journey.Leg;
import com.mmts.journey.service.JourneySearchService;
import com.mmts.journey.service.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo-only helpers (demo profile, no token needed, like /dev/token). They stand in for applications that do not run
 * in the demo: Booking service (booking events), Provider Hub service (vehicle events) and Analytics service (pricing rules). Each helper only
 * PUBLISHES an event through the shared EventPublisher; the normal listeners then react exactly as they would to Kafka.
 */
@RestController
@RequestMapping("/dev/journey")
@Profile("demo")
@Tag(name = "Demo helpers (journey)", description = "Only in the demo profile: simulate the other applications")
public class DemoController {

    public record DemoBookingRequest(@NotBlank String userId, @NotBlank String from, @NotBlank String to,
                                     String rankBy, String optionId) {
    }

    public record DemoBookingResponse(String bookingId, String tripId, String liveUrl, String optionId,
                                      TripView trip, DomainEvent bookingEvent) {
    }

    public record VehiclePositionRequest(@NotBlank String vehicleId, @NotBlank String providerId, @NotNull Mode mode,
                                         @NotNull Double lat, @NotNull Double lon, Double speedKmph) {
    }

    public record Promotion(@NotNull Mode mode, @NotNull Integer discountPercent) {
    }

    public record PricingRulesRequest(@NotNull Double surgeFactor, Double commissionPercent,
                                      List<@Valid Promotion> promotions) {
    }

    private final JourneySearchService search;
    private final TripService trips;
    private final EventPublisher events;
    private final VehicleFeedSimulator simulator;
    private final StubFcmPushNotifier pushStub;
    private final Clock clock;
    private final AtomicInteger bookingNumber = new AtomicInteger(5000);

    public DemoController(JourneySearchService search, TripService trips, EventPublisher events,
                          VehicleFeedSimulator simulator, StubFcmPushNotifier pushStub, Clock clock) {
        this.search = search;
        this.trips = trips;
        this.events = events;
        this.simulator = simulator;
        this.pushStub = pushStub;
        this.clock = clock;
    }

    @PostMapping("/bookings")
    @Operation(summary = "Act as Booking service: book a searched option and publish BOOKING_CONFIRMED",
            description = "Example body: {\"userId\":\"u-101\",\"from\":\"PL-IITD\",\"to\":\"PL-IG\",\"rankBy\":\"CHEAPEST\"}. "
                    + "Leave optionId out to book the best-ranked option.")
    public DemoBookingResponse book(@Valid @RequestBody DemoBookingRequest request) {
        List<Journey> options = search.search(request.from(), request.to(), null, request.rankBy()).options();
        Journey chosen = request.optionId() == null || request.optionId().isBlank() ? options.get(0)
                : options.stream().filter(j -> j.optionId().equals(request.optionId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("OPTION_NOT_FOUND",
                        "No option " + request.optionId() + " in this search"));

        String bookingId = "b-" + bookingNumber.incrementAndGet();
        DomainEvent event = events.publish("booking.events", "BOOKING_CONFIRMED",
                bookingPayload(bookingId, request.userId(), chosen));
        TripView trip = trips.viewByBooking(bookingId).orElseThrow();
        return new DemoBookingResponse(bookingId, trip.tripId(), trip.liveUrl(), chosen.optionId(), trip, event);
    }

    @PostMapping("/vehicle-positions")
    @Operation(summary = "Act as Provider Hub service: publish one VEHICLE_POSITION (e.g. a slow bus, to trigger a DELAY alert)")
    public DomainEvent vehiclePosition(@Valid @RequestBody VehiclePositionRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("vehicleId", request.vehicleId());
        payload.put("providerId", request.providerId());
        payload.put("mode", request.mode().name());
        payload.put("lat", request.lat());
        payload.put("lon", request.lon());
        payload.put("speedKmph", request.speedKmph() == null ? 0.0 : request.speedKmph());
        payload.put("recordedAt", clock.instant().toString());
        return events.publish("vehicle.events", "VEHICLE_POSITION", payload);
    }

    @PostMapping("/pricing-rules")
    @Operation(summary = "Act as Analytics service: publish PRICING_RULES_UPDATED",
            description = "Example body: {\"surgeFactor\":1.5,\"commissionPercent\":10,"
                    + "\"promotions\":[{\"mode\":\"BIKE\",\"discountPercent\":20}]}")
    public DomainEvent pricingRules(@Valid @RequestBody PricingRulesRequest request) {
        List<Map<String, Object>> promotions = new ArrayList<>();
        if (request.promotions() != null) {
            request.promotions().forEach(p -> promotions.add(
                    Map.of("mode", p.mode().name(), "discountPercent", p.discountPercent())));
        }
        return events.publish("pricing.rules", "PRICING_RULES_UPDATED", Map.of(
                "surgeFactor", request.surgeFactor(),
                "commissionPercent", request.commissionPercent() == null ? 0.0 : request.commissionPercent(),
                "promotions", promotions));
    }

    @PostMapping("/vehicles/{vehicleId}/pause")
    @Operation(summary = "Stop (paused=true) or restart (paused=false) the simulated feed of one vehicle")
    public Map<String, Object> pause(@PathVariable String vehicleId, @RequestParam(defaultValue = "true") boolean paused) {
        simulator.setPaused(vehicleId, paused);
        return Map.of("vehicleId", vehicleId, "paused", paused, "allPaused", simulator.pausedVehicles());
    }

    @GetMapping("/push-log")
    @Operation(summary = "Push notifications the FCM stub would have sent (newest first)")
    public List<StubFcmPushNotifier.SentPush> pushLog() {
        return pushStub.sent();
    }

    /** Builds the BOOKING_CONFIRMED payload exactly as in docs/CONTRACTS.md (plus vehicleId and plannedMinutes). */
    private Map<String, Object> bookingPayload(String bookingId, String userId, Journey journey) {
        List<Map<String, Object>> legs = new ArrayList<>();
        Instant start = clock.instant();
        int n = 1;
        for (Leg leg : journey.legs()) {
            Map<String, Object> l = new HashMap<>();
            l.put("legId", bookingId + "-L" + n++);
            l.put("mode", leg.mode().name());
            l.put("providerId", leg.providerId() == null ? "SELF" : leg.providerId());
            l.put("from", place(leg.from()));
            l.put("to", place(leg.to()));
            l.put("startTime", start.toString());
            l.put("plannedMinutes", leg.minutes());
            if (leg.vehicleId() != null) {
                l.put("vehicleId", leg.vehicleId());
            }
            legs.add(l);
            start = start.plusSeconds(60L * leg.minutes());
        }
        return Map.of("bookingId", bookingId, "userId", userId, "totalPaise", journey.farePaise(), "legs", legs);
    }

    private static Map<String, Object> place(Stop stop) {
        return Map.of("name", stop.name(), "lat", stop.lat(), "lon", stop.lon(), "stopId", stop.id());
    }
}
