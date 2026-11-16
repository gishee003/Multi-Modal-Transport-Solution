package com.mmts.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.identity.common.events.DomainEvent;
import com.mmts.identity.common.events.EventPublisher;
import com.mmts.identity.common.security.JwtService;
import com.mmts.identity.common.security.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Common setup for the tests that start the whole application (demo profile, H2, sample data loaded).
 * The licence check runs straight away instead of in the background (verification.async=false), so every
 * test sees the final result immediately and always behaves the same way.
 * All subclasses share one running application, so each test creates its own fresh users.
 */
@SpringBootTest(properties = {"mmts.identity.verification.async=false", "mmts.identity.bcrypt-strength=4"})
@AutoConfigureMockMvc
public abstract class IdentityTestBase {

    private static final AtomicLong NEXT_MOBILE = new AtomicLong(ThreadLocalRandom.current().nextLong(100_000_000L));

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected EventPublisher events;

    /** A mobile number nobody else in the test run uses. */
    protected static String freshMobile() {
        return "7" + String.format("%09d", NEXT_MOBILE.incrementAndGet());
    }

    protected String bearer(String userId, Role... roles) {
        return "Bearer " + jwtService.issue(userId, Set.of(roles)).token();
    }

    /** Registers a user through the API and returns its user id. */
    protected String register(String mobile, String password, String accountType) throws Exception {
        String body = """
                {"fullName":"Test User","mobile":"%s","email":"%s@example.in","password":"%s","accountType":"%s"}
                """.formatted(mobile, mobile, password, accountType);
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("userId").asText();
    }

    protected JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    /** Events published since the given list was taken (newest first). */
    protected List<DomainEvent> eventsOfType(String topic, String type) {
        return events.recentEvents().stream()
                .filter(e -> e.topic().equals(topic) && e.type().equals(type))
                .toList();
    }
}
