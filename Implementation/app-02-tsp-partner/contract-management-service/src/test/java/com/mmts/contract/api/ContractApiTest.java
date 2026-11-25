package com.mmts.contract.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.contract.common.events.DomainEvent;
import com.mmts.contract.common.events.EventPublisher;
import com.mmts.contract.common.security.JwtService;
import com.mmts.contract.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TSP Contract Management (M7) over HTTP: onboarding, life cycle, active terms, SLA and RBAC. */
@SpringBootTest
@AutoConfigureMockMvc
class ContractApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JwtService jwt;
    @Autowired
    EventPublisher events;

    private String bearer(String userId, Role... roles) {
        return "Bearer " + jwt.issue(userId, Set.of(roles)).token();
    }

    private String admin() {
        return bearer("partner-admin", Role.ADMIN);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** Registers a fresh TSP operated by op-<id> and drafts its first contract; returns {tspId, contractId}. */
    private String[] onboard(int breachesBeforeSuspension) throws Exception {
        String tsp = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String op = "op-" + tsp.toLowerCase();
        mvc.perform(post("/api/v1/tsps").header("Authorization", bearer(op, Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"tspId":"%s","legalName":"Test Mobility","registrationNumber":"U12345","bankAccountRef":"acc_1",
                                 "modes":["ferry"],"operators":["%s"],"documents":["gst.pdf"]}""".formatted(tsp, op)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modes[0]").value("FERRY"));
        MvcResult r = mvc.perform(post("/api/v1/contracts").header("Authorization", bearer(op, Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"tspId":"%s","validFrom":"2026-01-01","validTo":"2030-12-31","serviceArea":"Kochi",
                                 "modes":["FERRY"],
                                 "terms":{"commissionPercent":12.5,"bookingFeePaise":500,"cancellationFeePaise":1000,"payoutCycleDays":7},
                                 "serviceLevels":{"maxCancellationRatePct":5,"minAvailabilityPct":98,"maxResponseMinutes":0,
                                                  "breachesBeforeSuspension":%d}}""".formatted(tsp, breachesBeforeSuspension)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn();
        return new String[]{tsp, body(r).get("contractId").asText(), op};
    }

    private void activate(String contractId, String op) throws Exception {
        mvc.perform(post("/api/v1/contracts/{id}/submit", contractId).header("Authorization", bearer(op, Role.OPERATOR)))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        mvc.perform(post("/api/v1/contracts/{id}/approve", contractId).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private DomainEvent lastContractEvent() {
        return events.recentEvents().stream().filter(e -> "contract.events".equals(e.topic())).findFirst().orElseThrow();
    }

    @Test
    void anyNewModeCanBeOnboardedAndApprovalPublishesTheTerms() throws Exception {
        String[] t = onboard(2);   // a ferry operator: a mode no other part of MMTS knows about
        String svc = bearer("booking-service", Role.SERVICE);
        mvc.perform(get("/api/v1/tsps/{id}/active-terms", t[0]).header("Authorization", svc))
                .andExpect(jsonPath("$.active").value(false));

        activate(t[1], t[2]);

        DomainEvent event = lastContractEvent();
        assertThat(event.type()).isEqualTo("TSP_CONTRACT_ACTIVATED");
        assertThat(event.payload()).containsEntry("tspId", t[0]).containsEntry("commissionPercent", 12.5)
                .containsEntry("cancellationFeePaise", 1000L);
        mvc.perform(get("/api/v1/tsps/{id}/active-terms", t[0]).header("Authorization", svc))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.terms.commissionPercent").value(12.5))
                .andExpect(jsonPath("$.modes[0]").value("FERRY"));
    }

    @Test
    void lifeCycleRulesAreEnforced() throws Exception {
        String[] t = onboard(2);
        mvc.perform(post("/api/v1/contracts/{id}/approve", t[1]).header("Authorization", admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));   // DRAFT cannot jump to ACTIVE
        activate(t[1], t[2]);

        mvc.perform(post("/api/v1/contracts/{id}/suspend", t[1]).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"PERMIT_EXPIRED\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        assertThat(lastContractEvent().type()).isEqualTo("TSP_CONTRACT_SUSPENDED");

        mvc.perform(post("/api/v1/contracts/{id}/reactivate", t[1]).header("Authorization", admin()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/contracts/{id}/terminate", t[1]).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OFF_BOARDED\"}"))
                .andExpect(jsonPath("$.status").value("TERMINATED"));
        assertThat(lastContractEvent().type()).isEqualTo("TSP_CONTRACT_TERMINATED");
        mvc.perform(post("/api/v1/contracts/{id}/reactivate", t[1]).header("Authorization", admin()))
                .andExpect(status().isConflict());
    }

    @Test
    void anAmendmentReplacesTheOldRevision() throws Exception {
        String[] t = onboard(2);
        activate(t[1], t[2]);
        MvcResult r = mvc.perform(post("/api/v1/contracts").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"tspId":"%s","validFrom":"2026-01-01","validTo":"2031-12-31","serviceArea":"Kochi",
                                 "modes":["FERRY"],
                                 "terms":{"commissionPercent":10,"bookingFeePaise":0,"cancellationFeePaise":800,"payoutCycleDays":7},
                                 "serviceLevels":{"maxCancellationRatePct":5,"minAvailabilityPct":98,"maxResponseMinutes":0,
                                                  "breachesBeforeSuspension":2}}""".formatted(t[0])))
                .andExpect(jsonPath("$.revision").value(2)).andReturn();
        String second = body(r).get("contractId").asText();
        activate(second, t[2]);

        mvc.perform(get("/api/v1/tsps/{id}/contracts", t[0]).header("Authorization", admin()))
                .andExpect(jsonPath("$[0].revision").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("TERMINATED"));
        mvc.perform(get("/api/v1/tsps/{id}/active-terms", t[0]).header("Authorization", admin()))
                .andExpect(jsonPath("$.terms.commissionPercent").value(10.0));
    }

    @Test
    void repeatedSlaBreachesSuspendTheContract() throws Exception {
        String[] t = onboard(2);
        activate(t[1], t[2]);
        String svc = bearer("booking-service", Role.SERVICE);
        String bad = """
                {"tspId":"%s","period":"%s","legsConfirmed":200,"legsCancelledByTsp":30,
                 "availabilityPct":99.5,"avgResponseMinutes":0}""";

        mvc.perform(post("/api/v1/sla/measurements").header("Authorization", svc)
                        .contentType(MediaType.APPLICATION_JSON).content(bad.formatted(t[0], "2026-W47")))
                .andExpect(jsonPath("$.breached").value(true))
                .andExpect(jsonPath("$.cancellationRatePct").value(15.0))
                .andExpect(jsonPath("$.breachReasons").value("CANCELLATION_RATE"))
                .andExpect(jsonPath("$.contractStatus").value("ACTIVE"));
        assertThat(lastContractEvent().type()).isEqualTo("TSP_SLA_BREACHED");

        // the same period again counts once
        mvc.perform(post("/api/v1/sla/measurements").header("Authorization", svc)
                        .contentType(MediaType.APPLICATION_JSON).content(bad.formatted(t[0], "2026-W47")))
                .andExpect(jsonPath("$.contractStatus").value("ACTIVE"));

        mvc.perform(post("/api/v1/sla/measurements").header("Authorization", svc)
                        .contentType(MediaType.APPLICATION_JSON).content(bad.formatted(t[0], "2026-W48")))
                .andExpect(jsonPath("$.contractStatus").value("SUSPENDED"));
        DomainEvent suspended = lastContractEvent();
        assertThat(suspended.type()).isEqualTo("TSP_CONTRACT_SUSPENDED");
        assertThat(suspended.payload()).containsEntry("reason", "SLA_BREACH");
    }

    @Test
    void aGoodPeriodResetsTheBreachCount() throws Exception {
        String[] t = onboard(2);
        activate(t[1], t[2]);
        String svc = bearer("booking-service", Role.SERVICE);
        String metrics = """
                {"tspId":"%s","period":"%s","legsConfirmed":100,"legsCancelledByTsp":%d,
                 "availabilityPct":%s,"avgResponseMinutes":0}""";
        mvc.perform(post("/api/v1/sla/measurements").header("Authorization", svc).contentType(MediaType.APPLICATION_JSON)
                .content(metrics.formatted(t[0], "p1", 1, "90")))
                .andExpect(jsonPath("$.breachReasons").value("AVAILABILITY"));
        mvc.perform(post("/api/v1/sla/measurements").header("Authorization", svc).contentType(MediaType.APPLICATION_JSON)
                .content(metrics.formatted(t[0], "p2", 1, "99.9")))
                .andExpect(jsonPath("$.breached").value(false))
                .andExpect(jsonPath("$.consecutiveBreaches").value(0));
        mvc.perform(post("/api/v1/sla/measurements").header("Authorization", svc).contentType(MediaType.APPLICATION_JSON)
                .content(metrics.formatted(t[0], "p3", 1, "90")))
                .andExpect(jsonPath("$.contractStatus").value("ACTIVE"));
    }

    @Test
    void onlyThePartnerTeamApprovesAndOperatorsSeeOnlyTheirOwnTsp() throws Exception {
        String[] t = onboard(2);
        mvc.perform(post("/api/v1/contracts/{id}/submit", t[1]).header("Authorization", bearer(t[2], Role.OPERATOR)));
        mvc.perform(post("/api/v1/contracts/{id}/approve", t[1]).header("Authorization", bearer(t[2], Role.OPERATOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/contracts/{id}", t[1]).header("Authorization", bearer("op-someone-else", Role.OPERATOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_TSP"));
        mvc.perform(get("/api/v1/contracts/{id}", t[1]).header("Authorization", bearer("u-101", Role.TRAVELLER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void badInputIsRefused() throws Exception {
        String[] t = onboard(2);
        mvc.perform(post("/api/v1/contracts").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tspId":"%s","validFrom":"2026-01-01","validTo":"2030-12-31","serviceArea":"Kochi","modes":["METRO"],
                                 "terms":{"commissionPercent":10,"bookingFeePaise":0,"cancellationFeePaise":0,"payoutCycleDays":7},
                                 "serviceLevels":{"maxCancellationRatePct":5,"minAvailabilityPct":98,"maxResponseMinutes":0,
                                                  "breachesBeforeSuspension":2}}""".formatted(t[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODE_NOT_REGISTERED"));
        mvc.perform(post("/api/v1/tsps").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tspId":"%s","legalName":"x","registrationNumber":"y","bankAccountRef":"z",
                                 "modes":["FERRY"],"operators":["a"],"documents":["d"]}""".formatted(t[0])))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TSP_EXISTS"));
        mvc.perform(post("/api/v1/sla/measurements").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tspId\":\"NOPE\",\"period\":\"p\",\"legsConfirmed\":1,\"legsCancelledByTsp\":0,"
                                + "\"availabilityPct\":100,\"avgResponseMinutes\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_LIVE_CONTRACT"));
    }

    @Test
    void demoDataHasFiveActiveTspsAndOneUnderReview() throws Exception {
        mvc.perform(get("/api/v1/tsps/ZIPRIDE/active-terms").header("Authorization", admin()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.terms.commissionPercent").value(12.0));
        mvc.perform(get("/api/v1/tsps/HOPBIKE/contracts").header("Authorization", admin()))
                .andExpect(jsonPath("$[0].status").value("UNDER_REVIEW"));
    }
}
