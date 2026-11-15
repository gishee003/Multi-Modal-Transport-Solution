package com.mmts.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.provider.common.security.JwtService;
import com.mmts.provider.common.security.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for endpoint (contract) tests: the whole application in demo mode, called through MockMvc.
 * Same annotations as SharedSetupTest, so Spring reuses one started application for all of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JwtService jwtService;

    /** "Bearer <token>" for a user with the given roles. */
    protected String bearer(String userId, Role... roles) {
        return "Bearer " + jwtService.issue(userId, Arrays.asList(roles)).token();
    }

    protected String service() {
        return bearer("app-06-booking", Role.SERVICE);
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Hold body in the exact shape of the INT-12 example. vehicleId may be null. */
    protected static String holdBody(String providerId, String mode, String vehicleId,
                                     double fromLat, double fromLon, double toLat, double toLon) {
        String leg = unique("b-");
        return """
                {"bookingId":"%s","legId":"%s-L1","providerId":"%s","mode":"%s",
                 "from":{"name":"Start","lat":%s,"lon":%s},
                 "to":{"name":"End","lat":%s,"lon":%s},
                 "startTime":"2026-11-15T10:20:00Z"%s}
                """.formatted(leg, leg, providerId, mode, fromLat, fromLon, toLat, toLon,
                vehicleId == null ? "" : ",\"vehicleId\":\"" + vehicleId + "\"");
    }

    /** Creates a hold as APP-06 would and returns its holdId. */
    protected String createHold(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/holds").header("Authorization", service())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("holdId").asText();
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
