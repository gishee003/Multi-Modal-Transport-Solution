package com.mmts.provider.api;

import com.mmts.provider.common.security.Role;
import com.mmts.provider.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** INT-12 contract tests: paths, status codes, JSON fields and error codes exactly as in docs/CONTRACTS.md. */
class HoldApiTest extends ApiTestBase {

    private static final double[] RCK = {28.6328, 77.2197};
    private static final double[] IG = {28.6129, 77.2295};

    private String scooterHold(String vehicleId) {
        return holdBody("ZIPRIDE", "SCOOTER", vehicleId, RCK[0], RCK[1], IG[0], IG[1]);
    }

    @Test
    void holdConfirmUnlockMatchTheContract() throws Exception {
        String holdId = mvc.perform(post("/api/v1/holds").header("Authorization", service())
                        .contentType(MediaType.APPLICATION_JSON).content(scooterHold("ZR-SC-0042")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.*", hasSize(6)))                      // exactly the 6 contract fields
                .andExpect(jsonPath("$.holdId", startsWith("h-")))
                .andExpect(jsonPath("$.legId", startsWith("b-")))
                .andExpect(jsonPath("$.providerId").value("ZIPRIDE"))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.pricePaise").isNumber())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"holdId\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/v1/holds/{id}/confirm", holdId).header("Authorization", service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.access.type").value("UNLOCK_CODE"))
                .andExpect(jsonPath("$.access.value", matchesPattern("ZR-\\d{4}")))
                .andExpect(jsonPath("$.access.validFrom").value("2026-11-15T10:15:00Z"))
                .andExpect(jsonPath("$.access.validUntil").value("2026-11-15T11:15:00Z"))
                .andExpect(jsonPath("$.driver", nullValue()));

        mvc.perform(post("/api/v1/vehicles/ZR-SC-0042/unlock").header("Authorization", service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + holdId + "\",\"userId\":\"u-101\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.vehicleId").value("ZR-SC-0042"))
                .andExpect(jsonPath("$.status").value("UNLOCKED"))
                .andExpect(jsonPath("$.unlockedAt").isNotEmpty());

        mvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", service()))
                .andExpect(status().isNoContent());
    }

    @Test
    void releaseReturns204TwiceAndConfirmAfterReleaseIs409HoldNotActive() throws Exception {
        String holdId = createHold(scooterHold("ZR-SC-0043"));

        mvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", service()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", service()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/holds/{id}/confirm", holdId).header("Authorization", service()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
    }

    @Test
    void unlockBeforeConfirmIs409HoldNotConfirmed() throws Exception {
        String holdId = createHold(scooterHold("ZR-SC-0044"));

        mvc.perform(post("/api/v1/vehicles/ZR-SC-0044/unlock").header("Authorization", service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + holdId + "\",\"userId\":\"u-101\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_CONFIRMED"));
        mvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", service()));
    }

    @Test
    void unusableVehicleIs409NotAvailable() throws Exception {
        for (String vehicle : new String[]{"ZR-SC-0012", "ZR-SC-0013"}) {        // low battery, disabled
            mvc.perform(post("/api/v1/holds").header("Authorization", service())
                            .contentType(MediaType.APPLICATION_JSON).content(scooterHold(vehicle)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        }
    }

    @Test
    void unknownAndDisabledProvidersAre404ProviderNotFound() throws Exception {
        for (String provider : new String[]{"NOPE", "HOPBIKE"}) {
            mvc.perform(post("/api/v1/holds").header("Authorization", service())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(holdBody(provider, "BIKE", null, RCK[0], RCK[1], IG[0], IG[1])))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROVIDER_NOT_FOUND"));
        }
        mvc.perform(post("/api/v1/holds/h-missing/confirm").header("Authorization", service()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    @Test
    void metroConfirmGivesQrTicket() throws Exception {
        String holdId = createHold(holdBody("DMRC", "METRO", null, RCK[0], RCK[1], IG[0], IG[1]));

        mvc.perform(post("/api/v1/holds/{id}/confirm", holdId).header("Authorization", service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access.type").value("QR_TICKET"))
                .andExpect(jsonPath("$.access.value", startsWith("ONDC|DMRC|")))
                .andExpect(jsonPath("$.driver", nullValue()));
    }

    @Test
    void taxiConfirmGivesDriverAssignedWithDriverDetails() throws Exception {
        // Pickup at Rajiv Chowk: demo drivers d-201 / d-202 are close by.
        String holdId = createHold(holdBody("CITYCAB", "TAXI", null, RCK[0], RCK[1], IG[0], IG[1]));

        mvc.perform(post("/api/v1/holds/{id}/confirm", holdId).header("Authorization", service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access.type").value("DRIVER_ASSIGNED"))
                .andExpect(jsonPath("$.driver.*", hasSize(4)))
                .andExpect(jsonPath("$.driver.driverId", startsWith("d-")))
                .andExpect(jsonPath("$.driver.name").isNotEmpty())
                .andExpect(jsonPath("$.driver.vehicleNumber", startsWith("DL")))
                .andExpect(jsonPath("$.driver.etaMinutes").isNumber());
        mvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", service()))
                .andExpect(status().isNoContent());
    }

    @Test
    void onlyServiceTokensMayHoldAndBadBodiesAre400() throws Exception {
        String body = scooterHold(null);
        mvc.perform(post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/holds").header("Authorization", bearer("u-101", Role.TRAVELLER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/holds").header("Authorization", service())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingId\":\"b-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
