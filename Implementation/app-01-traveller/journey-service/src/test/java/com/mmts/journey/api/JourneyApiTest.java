package com.mmts.journey.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.journey.common.events.EventPublisher;
import com.mmts.journey.common.security.JwtService;
import com.mmts.journey.common.security.Role;
import com.mmts.journey.service.RouteCandidateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint (contract) tests for INT-02 /api/v1/journeys/**, plus drivers D4 and the pricing / schedule events. */
@SpringBootTest
@AutoConfigureMockMvc
class JourneyApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired EventPublisher events;
    @Autowired RouteCandidateService searchCache;

    private String token(Role role) {
        return "Bearer " + jwt.issue("u-101", Set.of(role)).token();
    }

    private ResultActions search(String query) throws Exception {
        return mvc.perform(get("/api/v1/journeys/search?" + query).header("Authorization", token(Role.TRAVELLER)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @AfterEach
    void resetFares() {
        events.publish("pricing.rules", "PRICING_RULES_UPDATED", Map.of("surgeFactor", 1.0, "promotions", List.of()));
    }

    @Test
    void searchReturnsRankedOptionsWithLegsTimesAndFares() throws Exception {
        JsonNode body = body(search("from=PL-IITD&to=PL-IG&rankBy=CHEAPEST")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from.stopId").value("PL-IITD"))
                .andExpect(jsonPath("$.to.name").value("India Gate"))
                .andExpect(jsonPath("$.rankBy").value("CHEAPEST"))
                .andExpect(jsonPath("$.options[0].rank").value(1))
                .andExpect(jsonPath("$.options[0].optionId").isNotEmpty())
                .andExpect(jsonPath("$.options[0].legs[0].mode").isNotEmpty())
                .andExpect(jsonPath("$.options[0].legs[0].instruction").isNotEmpty())
                .andExpect(jsonPath("$.options[0].legs[0].farePaise").isNumber()));

        long previous = -1;
        for (JsonNode option : body.get("options")) {
            long fare = option.get("totalFarePaise").asLong();
            assertThat(fare).isGreaterThanOrEqualTo(previous);   // cheapest first
            long sumOfLegs = 0;
            for (JsonNode leg : option.get("legs")) {
                sumOfLegs += leg.get("farePaise").asLong();
            }
            assertThat(fare).isEqualTo(sumOfLegs);              // total = sum of legs (Composite)
            previous = fare;
        }
    }

    @Test
    void d4SearchIsWellUnderTwoSecondsAndRepeatIsServedFromCache() throws Exception {
        searchCache.clear();
        long start = System.nanoTime();
        JsonNode first = body(search("from=PL-HKV&to=PL-NDRS").andExpect(status().isOk()));
        long wallMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(first.get("servedFromCache").asBoolean()).isFalse();
        assertThat(first.get("computeMillis").asLong()).isLessThan(2000);
        assertThat(wallMillis).isLessThan(2000);

        JsonNode second = body(search("from=PL-HKV&to=PL-NDRS").andExpect(status().isOk()));
        assertThat(second.get("servedFromCache").asBoolean()).isTrue();
        assertThat(second.get("options")).isEqualTo(first.get("options"));
    }

    @Test
    void scheduleUpdatedEventEmptiesTheCache() throws Exception {
        search("from=PL-CP&to=PL-KM").andExpect(status().isOk());
        search("from=PL-CP&to=PL-KM").andExpect(jsonPath("$.servedFromCache").value(true));

        events.publish("schedule.events", "SCHEDULE_UPDATED",
                Map.of("providerId", "DMRC", "feedVersion", "2026-11-15", "routeIds", List.of("YL")));

        search("from=PL-CP&to=PL-KM").andExpect(jsonPath("$.servedFromCache").value(false));
    }

    @Test
    void pricingRulesEventChangesFares() throws Exception {
        JsonNode before = body(search("from=PL-KM&to=PL-IG&modes=BIKE").andExpect(status().isOk()));
        JsonNode bikeBefore = before.get("options").get(0).get("legs").get(1);
        assertThat(bikeBefore.get("mode").asText()).isEqualTo("BIKE");
        int minutes = bikeBefore.get("minutes").asInt();
        assertThat(bikeBefore.get("farePaise").asLong()).isEqualTo(500 + 100L * minutes);

        events.publish("pricing.rules", "PRICING_RULES_UPDATED", Map.of("surgeFactor", 1.5, "commissionPercent", 10,
                "promotions", List.of(Map.of("mode", "BIKE", "discountPercent", 20))));

        JsonNode after = body(search("from=PL-KM&to=PL-IG&modes=BIKE").andExpect(status().isOk()));
        assertThat(after.get("servedFromCache").asBoolean()).isFalse();
        assertThat(after.get("options").get(0).get("legs").get(1).get("farePaise").asLong())
                .isEqualTo(Math.round((500 + 100.0 * minutes) * 1.5) * 80 / 100);
    }

    @Test
    void placesListsTheSampleNetwork() throws Exception {
        mvc.perform(get("/api/v1/journeys/places").header("Authorization", token(Role.TRAVELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(32))
                .andExpect(jsonPath("$[*].stopId", hasItem("DMRC-RCK")))
                .andExpect(jsonPath("$[*].name", hasItem("India Gate")));
    }

    @Test
    void nearbyShowsFreeVehiclesWithLastSeenAndStaleFlag() throws Exception {
        mvc.perform(get("/api/v1/journeys/nearby?lat=28.6328&lon=77.2197&radiusMetres=800&mode=SCOOTER")
                        .header("Authorization", token(Role.TRAVELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vehicleId").value("ZR-SC-0043"))
                .andExpect(jsonPath("$[0].mode").value("SCOOTER"))
                .andExpect(jsonPath("$[0].distanceMetres").isNumber())
                .andExpect(jsonPath("$[0].lastSeenSeconds").isNumber())
                .andExpect(jsonPath("$[0].stale").isBoolean());
    }

    @Test
    void errorsUseClearCodes() throws Exception {
        search("from=Nowhere&to=PL-IG").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_PLACE"));
        search("from=PL-IITD&to=PL-IG&modes=WALK").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ROUTE_FOUND"));
        search("from=PL-IG&to=India Gate").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SAME_PLACE"));
        search("from=PL-IITD&to=PL-IG&rankBy=GREENEST").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_RANKING"));
        search("from=PL-IITD&to=PL-IG&modes=CAR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_MODE"));
        search("from=PL-IITD").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/v1/journeys/nearby?lat=28.6&lon=77.2&radiusMetres=99999").header("Authorization", token(Role.TRAVELLER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RADIUS"));
        mvc.perform(get("/api/v1/journeys/nearby?lat=128.6&lon=77.2").header("Authorization", token(Role.TRAVELLER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }

    @Test
    void searchNeedsTravellerLogin() throws Exception {
        mvc.perform(get("/api/v1/journeys/search?from=PL-IITD&to=PL-IG"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/journeys/search?from=PL-IITD&to=PL-IG").header("Authorization", token(Role.DRIVER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
