package com.mmts.provider.api;

import com.mmts.provider.common.security.Role;
import com.mmts.provider.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Provider registry (M5.1), telemetry + dead letters (M5.3, D11), fleet health (M5.8) over HTTP. */
class FleetApiTest extends ApiTestBase {

    private static String reading(String vehicleId, String providerId, double lat) {
        return """
                {"vehicleId":"%s","providerId":"%s","mode":"SCOOTER","lat":%s,"lon":77.2195,
                 "speedKmph":14.5,"batteryPct":76,"available":true,"recordedAt":"%s"}
                """.formatted(vehicleId, providerId, lat, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void providersAreListedWithConnectorTypeAndStatus() throws Exception {
        mvc.perform(get("/api/v1/providers").header("Authorization", bearer("admin-1", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[?(@.providerId=='ZIPRIDE')].connectorType", hasItem("gbfs-scooter")))
                .andExpect(jsonPath("$[?(@.providerId=='DMRC')].connectorType", hasItem("ondc-metro")))
                .andExpect(jsonPath("$[?(@.providerId=='HOPBIKE')].status", hasItem("DISABLED")));
        mvc.perform(get("/api/v1/providers").header("Authorization", bearer("u-101", Role.TRAVELLER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void telemetryBatchAcceptsGoodReadingsAndDeadLettersBadOnes() throws Exception {
        String a = unique("ZR-API-");
        String b = unique("ZR-API-");
        String body = "{\"readings\":[" + reading(a, "ZIPRIDE", 28.6330) + "," + reading(b, "ZIPRIDE", 28.6340) + ","
                + reading(unique("ZR-BAD-"), "ZIPRIDE", 123.4) + ","
                + reading(unique("DTC-X-"), "DTC", 28.63) + "]}";      // another provider's vehicle

        mvc.perform(post("/api/v1/telemetry").header("Authorization", bearer("op-zipride", Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.received").value(4))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.deadLettered").value(2))
                .andExpect(jsonPath("$.deadLetterIds", hasSize(2)));

        // The operator sees their own provider's dead letters only; the admin sees all of them.
        mvc.perform(get("/api/v1/telemetry/dead-letters").header("Authorization", bearer("op-zipride", Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].reason", hasItem("INVALID_COORDINATES")))
                .andExpect(jsonPath("$[*].reason", not(hasItem("PROVIDER_MISMATCH"))));
        mvc.perform(get("/api/v1/telemetry/dead-letters").header("Authorization", bearer("admin-1", Role.ADMIN)))
                .andExpect(jsonPath("$[*].reason", hasItem("PROVIDER_MISMATCH")))
                .andExpect(jsonPath("$[0].rawReading").isNotEmpty());

        mvc.perform(get("/api/v1/providers/ZIPRIDE/fleet").header("Authorization", bearer("op-zipride", Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles[*].vehicleId", hasItem(a)))
                .andExpect(jsonPath("$.vehicles[?(@.vehicleId=='ZR-SC-0012')].lowBattery", hasItem(true)));
    }

    @Test
    void emptyBatchIs400AndTravellerCannotSendTelemetry() throws Exception {
        mvc.perform(post("/api/v1/telemetry").header("Authorization", bearer("app-x", Role.SERVICE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"readings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/telemetry").header("Authorization", bearer("u-101", Role.TRAVELLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"readings\":[" + reading("ZR-SC-0042", "ZIPRIDE", 28.633) + "]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorSeesOnlyTheirOwnFleet() throws Exception {
        mvc.perform(get("/api/v1/providers/DRIVEEASY/fleet").header("Authorization", bearer("op-driveeasy", Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("DRIVEEASY"))
                .andExpect(jsonPath("$.totalVehicles").value(3));
        mvc.perform(get("/api/v1/providers/ZIPRIDE/fleet").header("Authorization", bearer("op-driveeasy", Role.OPERATOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_PROVIDER"));
        mvc.perform(get("/api/v1/providers/NOPE/fleet").header("Authorization", bearer("admin-1", Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROVIDER_NOT_FOUND"));
    }

    @Test
    void demoControlInjectsABadReadingIntoTheDeadLetterStore() throws Exception {
        mvc.perform(post("/dev/provider-hub/bad-telemetry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.deadLettered").value(1));
        mvc.perform(post("/dev/provider-hub/eligibility/d-205").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"NOT_A_DRIVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));
    }
}
