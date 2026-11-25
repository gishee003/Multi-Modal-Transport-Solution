package com.mmts.contract.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.contract.common.events.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the shared setup that every MMTS application must keep working:
 * health endpoint, token check, error format, demo token and event list.
 * Copy this test unchanged into every application.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedSetupTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EventPublisher eventPublisher;

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void apiWithoutTokenIsRejectedInSharedErrorFormat() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/info").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void demoTokenGivesAccessAndCarriesRoles() throws Exception {
        String token = token("u-101", "TRAVELLER");
        mvc.perform(get("/api/v1/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-101"))
                .andExpect(jsonPath("$.roles", hasItem("TRAVELLER")));
    }

    @Test
    void invalidTokenRequestListsBadFields() throws Exception {
        mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"\",\"roles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void publishedEventsAreListed() throws Exception {
        eventPublisher.publish("test.events", "SOMETHING_HAPPENED", Map.of("id", "42"));
        mvc.perform(get("/dev/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].topic").value("test.events"))
                .andExpect(jsonPath("$[0].type").value("SOMETHING_HAPPENED"))
                .andExpect(jsonPath("$[0].payload.id").value("42"));
    }

    private String token(String userId, String role) throws Exception {
        String body = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"roles\":[\"" + role + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("token").asText();
    }
}
