package com.mmts.journey.api;

import com.mmts.journey.adapter.StubFcmPushNotifier;
import com.mmts.journey.common.events.EventPublisher;
import com.mmts.journey.common.security.JwtService;
import com.mmts.journey.common.security.Role;
import com.mmts.journey.domain.Trip;
import com.mmts.journey.service.TrackingService;
import com.mmts.journey.service.TrackingService.LiveUpdate;
import com.mmts.journey.service.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trips and live tracking (INT-03, M2.5, M2.6, driver D5): a BOOKING_CONFIRMED event creates a trip, only its owner
 * may see it, the live stream is Server-Sent Events, vehicle events move the trip on, and alerts fire.
 * Each test uses its own booking id, user id and test vehicle so tests never disturb each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TripTrackingTest {

    // one scooter leg from Central Secretariat Dock to India Gate Dock
    private static final double FROM_LAT = 28.6150, FROM_LON = 77.2135, TO_LAT = 28.6125, TO_LON = 77.2280;

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired EventPublisher events;
    @Autowired TripService trips;
    @Autowired TrackingService tracking;
    @Autowired StubFcmPushNotifier pushStub;

    private String token(String userId, Role role) {
        return "Bearer " + jwt.issue(userId, Set.of(role)).token();
    }

    /** Publishes BOOKING_CONFIRMED (as Booking service would): a walk leg, then a scooter leg on the given vehicle. */
    private void confirmBooking(String bookingId, String userId, String vehicleId) {
        String now = Instant.now().toString();
        Map<String, Object> walk = Map.of("legId", bookingId + "-L1", "mode", "WALK", "providerId", "SELF",
                "from", Map.of("name", "Central Secretariat Metro", "lat", 28.6149, "lon", 77.2120),
                "to", Map.of("name", "Central Secretariat Dock", "lat", FROM_LAT, "lon", FROM_LON),
                "startTime", now, "plannedMinutes", 2);
        Map<String, Object> scooter = Map.of("legId", bookingId + "-L2", "mode", "SCOOTER", "providerId", "ZIPRIDE",
                "vehicleId", vehicleId,
                "from", Map.of("name", "Central Secretariat Dock", "lat", FROM_LAT, "lon", FROM_LON),
                "to", Map.of("name", "India Gate Dock", "lat", TO_LAT, "lon", TO_LON),
                "startTime", now, "plannedMinutes", 8);
        events.publish("booking.events", "BOOKING_CONFIRMED", Map.of("bookingId", bookingId, "userId", userId,
                "totalPaise", 3400, "legs", List.of(walk, scooter)));
    }

    /** Publishes VEHICLE_POSITION (as TSP Integration Hub would). */
    private void position(String vehicleId, double lat, double lon, double speedKmph, Instant recordedAt) {
        Map<String, Object> p = new HashMap<>(Map.of("vehicleId", vehicleId, "providerId", "ZIPRIDE", "mode", "SCOOTER",
                "lat", lat, "lon", lon, "speedKmph", speedKmph, "recordedAt", recordedAt.toString()));
        events.publish("vehicle.events", "VEHICLE_POSITION", p);
    }

    @Test
    void bookingConfirmedEventCreatesTripForItsOwner() throws Exception {
        confirmBooking("b-T1", "u-t1", "TEST-SC-1");

        mvc.perform(get("/api/v1/trips/trip-b-T1").header("Authorization", token("u-t1", Role.TRAVELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value("trip-b-T1"))
                .andExpect(jsonPath("$.bookingId").value("b-T1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.totalPaise").value(3400))
                .andExpect(jsonPath("$.liveUrl").value("/api/v1/trips/trip-b-T1/live"))
                .andExpect(jsonPath("$.legs[0].mode").value("WALK"))
                .andExpect(jsonPath("$.legs[1].state").value("CURRENT"))   // walking is not tracked; scooter is
                .andExpect(jsonPath("$.legs[1].vehicleId").value("TEST-SC-1"));
        assertThat(events.recentEvents()).anySatisfy(e -> {
            assertThat(e.topic()).isEqualTo("audit.events");
            assertThat(e.payload()).containsEntry("action", "TRIP_CREATED").containsEntry("resourceId", "trip-b-T1");
        });
    }

    @Test
    void sameBookingEventTwiceGivesOneTrip() throws Exception {
        confirmBooking("b-T2", "u-t2", "TEST-SC-2");
        confirmBooking("b-T2", "u-t2", "TEST-SC-2");

        mvc.perform(get("/api/v1/trips").header("Authorization", token("u-t2", Role.TRAVELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tripId").value("trip-b-T2"));
    }

    @Test
    void onlyTheOwnerMaySeeATrip() throws Exception {
        confirmBooking("b-T3", "u-t3", "TEST-SC-3");

        mvc.perform(get("/api/v1/trips/trip-b-T3").header("Authorization", token("u-intruder", Role.TRAVELLER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_TRIP_OWNER"));
        mvc.perform(get("/api/v1/trips/trip-b-T3/live").header("Authorization", token("u-intruder", Role.TRAVELLER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_TRIP_OWNER"));
        mvc.perform(get("/api/v1/trips/trip-nope").header("Authorization", token("u-t3", Role.TRAVELLER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        mvc.perform(get("/api/v1/trips/trip-b-T3").header("Authorization", token("u-t3", Role.DRIVER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/trips/trip-b-T3/live"))
                .andExpect(status().isUnauthorized());
        assertThat(events.recentEvents()).anySatisfy(e ->
                assertThat(e.payload()).containsEntry("outcome", "DENIED").containsEntry("resourceId", "trip-b-T3"));
    }

    @Test
    void liveStreamIsServerSentEventsAndStartsWithSnapshot() throws Exception {
        confirmBooking("b-T4", "u-t4", "TEST-SC-4");
        position("TEST-SC-4", FROM_LAT, FROM_LON, 15, Instant.now());

        MvcResult result = mvc.perform(get("/api/v1/trips/trip-b-T4/live")
                        .header("Authorization", token("u-t4", Role.TRAVELLER)))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String stream = result.getResponse().getContentAsString();
        assertThat(stream).startsWith("event:snapshot").contains("\"tripId\":\"trip-b-T4\"")
                .contains("\"vehicleId\":\"TEST-SC-4\"").contains("\"stale\":false");
        assertThat(tracking.watcherCount("trip-b-T4")).isEqualTo(1);

        // a new position is pushed into the open stream at once (no polling)
        position("TEST-SC-4", 28.6140, 77.2200, 15, Instant.now());
        assertThat(result.getResponse().getContentAsString()).contains("event:position");
    }

    @Test
    void cancelledTripCannotBeStreamed() throws Exception {
        confirmBooking("b-T5", "u-t5", "TEST-SC-5");
        events.publish("booking.events", "BOOKING_CANCELLED",
                Map.of("bookingId", "b-T5", "userId", "u-t5", "reason", "USER_CANCELLED"));

        mvc.perform(get("/api/v1/trips/trip-b-T5/live").header("Authorization", token("u-t5", Role.TRAVELLER)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIP_NOT_ACTIVE"));
    }

    @Test
    void vehicleReachingTheEndOfTheLastLegCompletesTheTrip() {
        confirmBooking("b-T6", "u-t6", "TEST-SC-6");
        position("TEST-SC-6", 28.6140, 77.2200, 15, Instant.now());
        assertThat(trips.find("trip-b-T6").orElseThrow().status()).isEqualTo(Trip.Status.ACTIVE);

        position("TEST-SC-6", TO_LAT, TO_LON, 0, Instant.now());

        assertThat(trips.find("trip-b-T6").orElseThrow().status()).isEqualTo(Trip.Status.COMPLETED);
    }

    @Test
    void silentVehicleIsStaleAndTriggersSwitchModeAlert() {
        confirmBooking("b-T7", "u-t7", "TEST-SC-7");
        position("TEST-SC-7", 28.6140, 77.2200, 15, Instant.now().minusSeconds(40));   // last heard 40 s ago

        LiveUpdate update = tracking.liveStatus(trips.find("trip-b-T7").orElseThrow());
        assertThat(update.stale()).isTrue();
        assertThat(update.lastSeenSeconds()).isGreaterThanOrEqualTo(40);
        assertThat(pushStub.sent()).anySatisfy(p -> {
            assertThat(p.userId()).isEqualTo("u-t7");
            assertThat(p.title()).isEqualTo("Switch mode?");
        });
    }

    @Test
    void slowVehicleTriggersDelayAlert() {
        confirmBooking("b-T8", "u-t8", "TEST-SC-8");
        position("TEST-SC-8", FROM_LAT, FROM_LON, 2, Instant.now());   // crawling at 2 km/h: far behind plan

        LiveUpdate update = tracking.liveStatus(trips.find("trip-b-T8").orElseThrow());
        assertThat(update.stale()).isFalse();
        assertThat(update.delayMinutes()).isGreaterThanOrEqualTo(5);
        assertThat(pushStub.sent()).anySatisfy(p -> {
            assertThat(p.userId()).isEqualTo("u-t8");
            assertThat(p.title()).isEqualTo("Trip delayed");
        });
    }

    @Test
    void demoHelpersSimulateTheOtherApplications() throws Exception {
        mvc.perform(post("/dev/journey/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-demo\",\"from\":\"PL-HKV\",\"to\":\"PL-IG\",\"rankBy\":\"CHEAPEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").isNotEmpty())
                .andExpect(jsonPath("$.tripId").isNotEmpty())
                .andExpect(jsonPath("$.trip.status").value("ACTIVE"))
                .andExpect(jsonPath("$.trip.userId").value("u-demo"))
                .andExpect(jsonPath("$.bookingEvent.topic").value("booking.events"))
                .andExpect(jsonPath("$.bookingEvent.type").value("BOOKING_CONFIRMED"))
                .andExpect(jsonPath("$.bookingEvent.payload.legs[0].legId").isNotEmpty());
        mvc.perform(post("/dev/journey/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u-demo\",\"from\":\"PL-HKV\",\"to\":\"PL-IG\",\"optionId\":\"opt-none\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("OPTION_NOT_FOUND"));
        mvc.perform(post("/dev/journey/bookings").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(post("/dev/journey/vehicle-positions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"TEST-SC-9\",\"providerId\":\"ZIPRIDE\",\"mode\":\"SCOOTER\",\"lat\":28.6,\"lon\":77.2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("VEHICLE_POSITION"));
        mvc.perform(post("/dev/journey/pricing-rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surgeFactor\":1.0,\"promotions\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("PRICING_RULES_UPDATED"));
        mvc.perform(post("/dev/journey/vehicles/TEST-SC-9/pause?paused=true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
        mvc.perform(post("/dev/journey/vehicles/TEST-SC-9/pause?paused=false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(false));
        mvc.perform(get("/dev/journey/push-log")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }
}
