package com.mmts.provider.api;

import com.mmts.provider.common.security.Role;
import com.mmts.provider.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Business onboarding gates technical onboarding: a TSP's connector is used only while TSP Contract Management (M7)
 * says its contract is active (contract.events).
 */
class ContractGatingApiTest extends ApiTestBase {

    private static final double[] RCK = {28.6328, 77.2197};
    private static final double[] IG = {28.6129, 77.2295};

    @AfterEach
    void reactivate() throws Exception {
        mvc.perform(post("/dev/provider-hub/contracts/DMRC/activate"));
    }

    private String metroHold() {
        return holdBody("DMRC", "METRO", null, RCK[0], RCK[1], IG[0], IG[1]);
    }

    @Test
    void suspendedContractBlocksNewHoldsAndShowsSuspended() throws Exception {
        mvc.perform(post("/dev/provider-hub/contracts/DMRC/suspend")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SLA_BREACH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("contract.events"))
                .andExpect(jsonPath("$.type").value("TSP_CONTRACT_SUSPENDED"));

        mvc.perform(post("/api/v1/holds").header("Authorization", service())
                        .contentType(MediaType.APPLICATION_JSON).content(metroHold()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TSP_CONTRACT_INACTIVE"));

        mvc.perform(get("/api/v1/providers").header("Authorization", bearer("admin-1", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.providerId=='DMRC')].status", hasItem("SUSPENDED")));
    }

    @Test
    void reactivatedContractMakesTheConnectorUsableAgain() throws Exception {
        mvc.perform(post("/dev/provider-hub/contracts/DMRC/suspend"));
        mvc.perform(post("/dev/provider-hub/contracts/DMRC/activate"))
                .andExpect(jsonPath("$.type").value("TSP_CONTRACT_ACTIVATED"));

        createHold(metroHold());
        mvc.perform(get("/api/v1/providers").header("Authorization", bearer("admin-1", Role.ADMIN)))
                .andExpect(jsonPath("$[?(@.providerId=='DMRC')].status", hasItem("ENABLED")));
    }
}
