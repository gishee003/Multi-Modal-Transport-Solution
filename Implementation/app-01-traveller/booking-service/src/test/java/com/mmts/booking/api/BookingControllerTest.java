package com.mmts.booking.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.booking.common.security.JwtService;
import com.mmts.booking.common.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static com.mmts.booking.TestJourneys.car;
import static com.mmts.booking.TestJourneys.journey;
import static com.mmts.booking.TestJourneys.metro;
import static com.mmts.booking.TestJourneys.newKey;
import static com.mmts.booking.TestJourneys.scooter;
import static com.mmts.booking.TestJourneys.walk;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint (contract) tests with MockMvc, lecture 19: method, path, status code, JSON fields, roles and error codes
 * of every endpoint in INT-04 /api/v1/bookings/** and of the demo stub controls.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookingControllerTest {

    private static final String BOOKINGS = "/api/v1/bookings";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JwtService jwt;

    @AfterEach
    void resetStubs() throws Exception {
        mvc.perform(post("/dev/stubs/reset")).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private String token(String userId, Role... roles) {
        return "Bearer " + jwt.issue(userId, List.of(roles)).token();
    }

    private static String newUser() {
        return "u-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions book(String token, String key, BookingRequest body) throws Exception {
        return mvc.perform(as(post(BOOKINGS), token).header("Idempotency-Key", key).content(json.writeValueAsString(body)));
    }

    /** Books metro + scooter for the user and returns the response JSON. */
    private JsonNode bookOk(String token) throws Exception {
        String body = book(token, newKey(), journey(metro(), scooter()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    // ---------- POST /api/v1/bookings ----------

    @Test
    void createBookingReturns201WithLegsAndAccess() throws Exception {
        book(token(newUser(), Role.TRAVELLER), newKey(), journey(walk(), metro(), scooter()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.bookingId").value(startsWith("b-")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalPaise").value(18500))
                .andExpect(jsonPath("$.paymentId").value(startsWith("p-")))
                .andExpect(jsonPath("$.legs", hasSize(3)))
                .andExpect(jsonPath("$.legs[0].status").value("NOT_NEEDED"))
                .andExpect(jsonPath("$.legs[1].access.type").value("QR_TICKET"))
                .andExpect(jsonPath("$.legs[2].access.type").value("UNLOCK_CODE"))
                .andExpect(jsonPath("$.sagaLog", hasSize(5)));
    }

    @Test
    void repeatedIdempotencyKeyReturns200WithTheSameBooking() throws Exception {
        String token = token(newUser(), Role.TRAVELLER);
        String key = newKey();
        BookingRequest request = journey(metro());
        String first = json.readTree(book(token, key, request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("bookingId").asText();

        book(token, key, request)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.bookingId").value(first));
        mvc.perform(as(get(BOOKINGS), token)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void createBookingErrors() throws Exception {
        String token = token(newUser(), Role.TRAVELLER);
        mvc.perform(post(BOOKINGS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        book(token(newUser(), Role.SUPPORT), newKey(), journey(metro()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(post(BOOKINGS), token).content(json.writeValueAsString(journey(metro()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(as(post(BOOKINGS), token).header("Idempotency-Key", newKey()).content("{\"legs\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        book(token, newKey(), journey(walk()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTHING_TO_BOOK"));
        book(token, newKey(), journey(new BookingRequest.Leg(scooter().mode(), "ZIPRIDE", scooter().from(),
                scooter().to(), scooter().startTime(), null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VEHICLE_REQUIRED"));
        book(token, newKey(), journey(new BookingRequest.Leg(metro().mode(), "NO_SUCH_METRO", metro().from(),
                metro().to(), metro().startTime(), null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_FAILED"));
    }

    @Test
    void forcedPaymentFailureGives409AndTheBookingShowsTheUndo() throws Exception {
        String user = newUser();
        String token = token(user, Role.TRAVELLER);
        mvc.perform(post("/dev/stubs/fail-next-payment").param("reason", "INSUFFICIENT_BALANCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextPaymentFailsWith").value("INSUFFICIENT_BALANCE"));

        book(token, newKey(), journey(metro(), scooter()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_FAILED"));

        mvc.perform(as(get(BOOKINGS), token))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].cancelReason").value("PAYMENT_FAILED: INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$[0].legs[0].status").value("RELEASED"))
                .andExpect(jsonPath("$[0].legs[0].access").doesNotExist());
    }

    @Test
    void ineligibleDriverGets403NotEligible() throws Exception {
        String user = newUser();
        mvc.perform(post("/dev/stubs/ineligible").param("userId", user).param("reason", "EXPIRED"))
                .andExpect(status().isOk());
        book(token(user, Role.TRAVELLER), newKey(), journey(car()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_ELIGIBLE"));
    }

    // ---------- GET ----------

    @Test
    void getAndListRespectOwnership() throws Exception {
        String owner = newUser();
        String id = bookOk(token(owner, Role.TRAVELLER)).get("bookingId").asText();

        mvc.perform(as(get(BOOKINGS + "/" + id), token(owner, Role.TRAVELLER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(owner));
        mvc.perform(as(get(BOOKINGS + "/" + id), token(newUser(), Role.TRAVELLER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_YOUR_BOOKING"));
        mvc.perform(as(get(BOOKINGS + "/" + id), token("s-1", Role.SUPPORT)))
                .andExpect(status().isOk());
        mvc.perform(as(get(BOOKINGS + "/b-nope"), token(owner, Role.TRAVELLER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));

        mvc.perform(as(get(BOOKINGS), token("s-1", Role.SUPPORT)).param("userId", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].bookingId").value(id));
        mvc.perform(as(get(BOOKINGS), token(newUser(), Role.TRAVELLER)).param("userId", owner))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_YOUR_BOOKING"));
        mvc.perform(as(get(BOOKINGS), token("op-1", Role.OPERATOR)))
                .andExpect(status().isForbidden());
    }

    // ---------- cancel ----------

    @Test
    void cancelReleasesAndRefundsAndCannotBeRepeated() throws Exception {
        String owner = newUser();
        String token = token(owner, Role.TRAVELLER);
        String id = bookOk(token).get("bookingId").asText();

        mvc.perform(as(post(BOOKINGS + "/" + id + "/cancel"), token).content("{\"reason\":\"Plans changed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Plans changed"))
                .andExpect(jsonPath("$.refundId").value(startsWith("r-")))
                .andExpect(jsonPath("$.legs[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.legs[0].access").doesNotExist());
        mvc.perform(as(post(BOOKINGS + "/" + id + "/cancel"), token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }

    @Test
    void supportCanCancelAnyBooking() throws Exception {
        String id = bookOk(token(newUser(), Role.TRAVELLER)).get("bookingId").asText();
        mvc.perform(as(post(BOOKINGS + "/" + id + "/cancel"), token("s-1", Role.SUPPORT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason").value("CANCELLED_BY_USER"));
        String other = bookOk(token(newUser(), Role.TRAVELLER)).get("bookingId").asText();
        mvc.perform(as(post(BOOKINGS + "/" + other + "/cancel"), token(newUser(), Role.TRAVELLER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_YOUR_BOOKING"));
    }

    // ---------- complete, rating, problems ----------

    @Test
    void completeThenRateOnce() throws Exception {
        String token = token(newUser(), Role.TRAVELLER);
        String id = bookOk(token).get("bookingId").asText();

        mvc.perform(as(post(BOOKINGS + "/" + id + "/rating"), token).content("{\"stars\":5}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/complete"), token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/rating"), token).content("{\"stars\":6}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/rating"), token).content("{\"stars\":4,\"comment\":\"Smooth ride\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingStars").value(4))
                .andExpect(jsonPath("$.ratingComment").value("Smooth ride"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/rating"), token).content("{\"stars\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RATED"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/complete"), token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }

    @Test
    void reportAndListProblems() throws Exception {
        String token = token(newUser(), Role.TRAVELLER);
        JsonNode booking = bookOk(token);
        String id = booking.get("bookingId").asText();
        String scooterLeg = booking.get("legs").get(1).get("legId").asText();

        mvc.perform(as(post(BOOKINGS + "/" + id + "/problems"), token).content(
                        "{\"category\":\"VEHICLE_FAULT\",\"description\":\"Brake is loose\",\"legId\":\"" + scooterLeg + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problemId").value(startsWith("pr-")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.legId").value(scooterLeg));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/problems"), token).content(
                        "{\"category\":\"OTHER\",\"description\":\"x\",\"legId\":\"b-wrong-L9\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LEG_NOT_FOUND"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/problems"), token).content("{\"description\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details", hasSize(2)));
        mvc.perform(as(get(BOOKINGS + "/" + id + "/problems"), token("s-1", Role.SUPPORT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].category").value("VEHICLE_FAULT"));
    }

    // ---------- unlock ----------

    @Test
    void unlockOnlyTheVehicleLegOfAConfirmedBooking() throws Exception {
        String token = token(newUser(), Role.TRAVELLER);
        JsonNode booking = bookOk(token);
        String id = booking.get("bookingId").asText();
        String metroLeg = booking.get("legs").get(0).get("legId").asText();
        String scooterLeg = booking.get("legs").get(1).get("legId").asText();

        mvc.perform(as(post(BOOKINGS + "/" + id + "/legs/" + metroLeg + "/unlock"), token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_UNLOCKABLE"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/legs/" + scooterLeg + "/unlock"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNLOCKED"))
                .andExpect(jsonPath("$.vehicleId").value("ZR-SC-0042"));
        mvc.perform(as(post(BOOKINGS + "/" + id + "/legs/" + scooterLeg + "/unlock"), token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_UNLOCKED"));
    }

    @Test
    void noUnlockAfterCancel() throws Exception {
        String token = token(newUser(), Role.TRAVELLER);
        JsonNode booking = bookOk(token);
        String id = booking.get("bookingId").asText();
        mvc.perform(as(post(BOOKINGS + "/" + id + "/cancel"), token)).andExpect(status().isOk());
        mvc.perform(as(post(BOOKINGS + "/" + id + "/legs/" + booking.get("legs").get(1).get("legId").asText() + "/unlock"), token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ILLEGAL_STATE"));
    }

    // ---------- QR verify ----------

    @Test
    void gateVerifiesQrOfflineAndSpotsTampering() throws Exception {
        JsonNode booking = bookOk(token(newUser(), Role.TRAVELLER));
        String qr = booking.get("legs").get(0).get("access").get("value").asText();
        String gate = token("gate-rck-1", Role.OPERATOR);

        mvc.perform(as(post(BOOKINGS + "/tickets/verify"), gate).content("{\"qr\":\"" + qr + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reason").value("VALID"))
                .andExpect(jsonPath("$.bookingId").value(booking.get("bookingId").asText()))
                .andExpect(jsonPath("$.from").value("Rajiv Chowk Metro"));

        String tampered = "x" + qr.substring(1);
        mvc.perform(as(post(BOOKINGS + "/tickets/verify"), gate).content("{\"qr\":\"" + tampered + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        mvc.perform(as(post(BOOKINGS + "/tickets/verify"), token(newUser(), Role.TRAVELLER)).content("{\"qr\":\"" + qr + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- demo stub controls ----------

    @Test
    void stubControlsShowAndResetTheirState() throws Exception {
        mvc.perform(post("/dev/stubs/refuse-holds").param("providerId", "YULU"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providersRefusingHolds[0]").value("YULU"));
        mvc.perform(post("/dev/stubs/fail-next-confirm"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextConfirmFails").value(true));
        mvc.perform(post("/dev/stubs/ineligible").param("userId", "u-1").param("reason", "BANANA"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REASON"));
        mvc.perform(post("/dev/stubs/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providersRefusingHolds", hasSize(0)))
                .andExpect(jsonPath("$.nextConfirmFails").value(false));
        mvc.perform(get("/dev/stubs")).andExpect(status().isOk());
    }
}
