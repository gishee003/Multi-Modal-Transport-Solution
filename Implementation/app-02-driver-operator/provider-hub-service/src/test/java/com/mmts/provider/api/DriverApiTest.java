package com.mmts.provider.api;

import com.mmts.provider.adapter.eligibility.StubEligibilityClient;
import com.mmts.provider.common.security.Role;
import com.mmts.provider.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** INT-07 (status, location), M5.5 (onboarding, activation) and INT-08 (SSE ride offers). */
class DriverApiTest extends ApiTestBase {

    @Autowired
    StubEligibilityClient eligibility;

    private String admin() {
        return bearer("admin-1", Role.ADMIN);
    }

    /** Onboards, activates and puts online a new driver standing at lat/lon. Returns the driver id. */
    private String onlineDriverAt(double lat, double lon) throws Exception {
        String id = unique("t-");
        eligibility.setReason(id, "VERIFIED");
        mvc.perform(post("/api/v1/drivers").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":\"" + id + "\",\"name\":\"Test Driver\",\"vehicleNumber\":\"DL1T"
                                + id.substring(2) + "\",\"providerId\":\"CITYCAB\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REGISTERED"));
        mvc.perform(post("/api/v1/drivers/{id}/activate", id).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(put("/api/v1/drivers/{id}/status", id).header("Authorization", bearer(id, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ONLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true));
        mvc.perform(post("/api/v1/drivers/{id}/locations", id).header("Authorization", bearer(id, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":" + lat + ",\"lon\":" + lon + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lat").value(lat));
        return id;
    }

    @Test
    void onboardActivateGoOnlineAndSendLocation() throws Exception {
        String id = onlineDriverAt(28.5355, 77.3910);    // Noida Sector 18

        mvc.perform(get("/api/v1/drivers/{id}/status", id).header("Authorization", bearer("op-citycab", Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.lastSeenAt").isNotEmpty());
    }

    @Test
    void activationOfIneligibleDriverIs409() throws Exception {
        String id = unique("t-");
        eligibility.setReason(id, "PENDING_VERIFICATION");
        mvc.perform(post("/api/v1/drivers").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"driverId\":\"" + id + "\",\"name\":\"X\",\"vehicleNumber\":\"DL0\",\"providerId\":\"CITYCAB\"}"));

        mvc.perform(post("/api/v1/drivers/{id}/activate", id).header("Authorization", admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_NOT_ELIGIBLE"));
    }

    @Test
    void operatorOfAnotherProviderCannotOnboardTaxiDrivers() throws Exception {
        mvc.perform(post("/api/v1/drivers").header("Authorization", bearer("op-zipride", Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":\"t-zz\",\"name\":\"X\",\"vehicleNumber\":\"DL0\",\"providerId\":\"CITYCAB\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_PROVIDER"));
    }

    @Test
    void driverCannotUseAnotherDriversAccount() throws Exception {
        mvc.perform(put("/api/v1/drivers/d-201/status").header("Authorization", bearer("d-202", Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_DRIVER_ACCOUNT"));
        mvc.perform(get("/api/v1/drivers").header("Authorization", bearer("d-201", Role.DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void badLocationIs400AndKeptAsDeadLetter() throws Exception {
        mvc.perform(post("/api/v1/drivers/d-201/locations").header("Authorization", bearer("d-201", Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":999,\"lon\":77.2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"))
                .andExpect(jsonPath("$.message", startsWith("Location rejected and kept as dl-")));
    }

    @Test
    void offersArriveAsServerSentEventsAndCanBeAccepted() throws Exception {
        String driverId = onlineDriverAt(28.7041, 77.1025);          // Pitampura: far from the demo drivers
        String holdId = createHold(holdBody("CITYCAB", "TAXI", null, 28.7045, 77.1030, 28.6328, 77.2197));
        mvc.perform(post("/api/v1/holds/{id}/confirm", holdId).header("Authorization", service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.driverId").value(driverId));

        MvcResult stream = mvc.perform(get("/api/v1/drivers/{id}/offers", driverId)
                        .header("Authorization", bearer(driverId, Role.DRIVER)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("text/event-stream")))
                .andReturn();
        String events = stream.getResponse().getContentAsString();
        assertThat(events).contains("event:ride-offer").contains(holdId);

        Matcher id = Pattern.compile("id:(of-[0-9a-f]+)").matcher(events);
        assertThat(id.find()).isTrue();
        String offerId = id.group(1);

        mvc.perform(post("/api/v1/drivers/{d}/offers/{o}/accept", driverId, offerId)
                        .header("Authorization", bearer(driverId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.farePaise").isNumber());
        mvc.perform(post("/api/v1/drivers/{d}/offers/{o}/reject", driverId, offerId)
                        .header("Authorization", bearer(driverId, Role.DRIVER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_NOT_PENDING"));
        mvc.perform(post("/api/v1/drivers/d-201/offers/{o}/accept", offerId)
                        .header("Authorization", bearer("d-201", Role.DRIVER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OFFER_NOT_FOUND"));
    }

    @Test
    void licenceExpiredDemoControlDeactivatesTheDriver() throws Exception {
        String id = onlineDriverAt(28.4595, 77.0266);                 // Gurugram

        mvc.perform(post("/dev/provider-hub/licence-expired/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("licence.events"))
                .andExpect(jsonPath("$.type").value("LICENCE_EXPIRED"));

        mvc.perform(get("/api/v1/drivers/{id}/status", id).header("Authorization", bearer(id, Role.DRIVER)))
                .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.online").value(false));
        mvc.perform(put("/api/v1/drivers/{id}/status", id).header("Authorization", bearer(id, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ONLINE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_NOT_ACTIVE"));
    }
}
