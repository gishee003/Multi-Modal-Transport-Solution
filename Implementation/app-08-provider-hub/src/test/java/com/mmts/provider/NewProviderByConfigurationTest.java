package com.mmts.provider;

import com.mmts.provider.support.ApiTestBase;
import com.mmts.provider.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Driver D10: a new provider is added by configuration alone. The application starts with ONE changed setting
 * (HOPBIKE enabled = true, exactly what you would write in application.yml) and the bike-share provider can be
 * listed, held, confirmed and monitored, with no code change. In the default configuration (see HoldApiTest)
 * the same hold gives 404 PROVIDER_NOT_FOUND.
 */
@TestPropertySource(properties = "mmts.provider-hub.providers.HOPBIKE.enabled=true")
class NewProviderByConfigurationTest extends ApiTestBase {

    @Test
    void enablingTheDisabledProviderInConfigurationPlugsItIn() throws Exception {
        mvc.perform(get("/api/v1/providers").header("Authorization", bearer("admin-1", Role.ADMIN)))
                .andExpect(jsonPath("$[?(@.providerId=='HOPBIKE')].status", hasItem("ENABLED")));

        String holdId = createHold(holdBody("HOPBIKE", "BIKE", "HB-BK-001", 28.6139, 77.2090, 28.6129, 77.2295));
        mvc.perform(post("/api/v1/holds/{id}/confirm", holdId).header("Authorization", service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access.type").value("UNLOCK_CODE"))
                .andExpect(jsonPath("$.access.value", startsWith("HB-")));

        mvc.perform(get("/api/v1/providers/HOPBIKE/fleet").header("Authorization", bearer("op-hopbike", Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles[*].vehicleId", hasItem("HB-BK-002")));
    }
}
