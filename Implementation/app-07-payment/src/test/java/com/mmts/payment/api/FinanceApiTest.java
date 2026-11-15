package com.mmts.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mmts.payment.common.security.Role;
import com.mmts.payment.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint tests for the ledger, commission rules, payouts, settlement file, gateway webhook and demo controls. */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceApiTest extends ApiTestSupport {

    @Autowired
    WebhookService webhookService;

    private String finance() {
        return bearer("f-1", Role.FINANCE);
    }

    private String gatewayPayment() throws Exception {
        String booking = newId("b-");
        String body = mvc.perform(postJson("/api/v1/payments", bearer("app-06", Role.SERVICE),
                        tripJson(booking, newId("t-"), "GATEWAY")).header("Idempotency-Key", booking + "-pay"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("paymentId").asText();
    }

    @Test
    void trialBalanceShowsZeroDifferenceAndReconciledWallets() throws Exception {
        gatewayPayment();

        mvc.perform(getAs("/api/v1/ledger/trial-balance", finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differencePaise").value(0))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.walletsReconciled").value(true))
                .andExpect(jsonPath("$.accounts[*].account", hasItem("PLATFORM_REVENUE")));
    }

    @Test
    void ledgerEntriesOfOnePaymentCanBeListed() throws Exception {
        String paymentId = gatewayPayment();

        mvc.perform(getAs("/api/v1/ledger/entries?reference=PAYMENT:" + paymentId, bearer("a-1", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].account").value("GATEWAY_CLEARING"))
                .andExpect(jsonPath("$[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$[3].account").value("PLATFORM_REVENUE"))
                .andExpect(jsonPath("$[3].amountPaise").value(700));
    }

    @Test
    void financeEndpointsAreOnlyForFinanceAndAdmin() throws Exception {
        for (Role role : new Role[]{Role.TRAVELLER, Role.SERVICE, Role.SUPPORT}) {
            mvc.perform(getAs("/api/v1/ledger/trial-balance", bearer("x", role))).andExpect(status().isForbidden());
            mvc.perform(postJson("/api/v1/payouts/run", bearer("x", role), "")).andExpect(status().isForbidden());
            mvc.perform(postJson("/api/v1/settlements/export", bearer("x", role), "")).andExpect(status().isForbidden());
        }
        mvc.perform(postJson("/api/v1/payouts/run", null, "")).andExpect(status().isUnauthorized());
    }

    @Test
    void commissionRulesShowEachProvidersStrategy() throws Exception {
        mvc.perform(getAs("/api/v1/commission-rules", finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flatFeePaise").value(500))
                .andExpect(jsonPath("$.ruleByProviderType.SCOOTER").value("FLAT_FEE"))
                .andExpect(jsonPath("$.providers[?(@.providerId=='ZIPRIDE')].rule", hasItem("FLAT_FEE 500 paise")))
                .andExpect(jsonPath("$.providers[?(@.providerId=='DMRC')].rule", hasItem(startsWith("PERCENTAGE"))));
    }

    @Test
    void payoutsCanBeRunNowAndListed() throws Exception {
        gatewayPayment();

        mvc.perform(postJson("/api/v1/payouts/run", finance(), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(startsWith("run-")))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.payouts[?(@.providerId=='DMRC')].status", hasItem("PAID")));
        mvc.perform(getAs("/api/v1/payouts", finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payoutId").value(startsWith("po-")));
    }

    @Test
    void settlementExportWritesTheDailyFile() throws Exception {
        String body = mvc.perform(postJson("/api/v1/settlements/export?date=2026-01-02", finance(), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementDate").value("2026-01-02"))
                .andExpect(jsonPath("$.file").value(endsWith("settlement-2026-01-02.csv")))
                .andExpect(jsonPath("$.csv").value(startsWith("settlementDate,providerId,grossPaise,commissionPaise,netPaise,paymentCount")))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertThat(Files.readString(Path.of(json.get("file").asText()))).isEqualTo(json.get("csv").asText());

        mvc.perform(postJson("/api/v1/settlements/export?date=not-a-date", finance(), ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void webhookWithAValidSignatureConfirmsThePayment() throws Exception {
        String paymentId = gatewayPayment();
        String gatewayId = objectMapper.readTree(mvc.perform(getAs("/api/v1/payments/" + paymentId, finance()))
                .andReturn().getResponse().getContentAsString()).get("gatewayReference").asText();
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + gatewayId
                + "\",\"amount\":18500,\"status\":\"captured\"}}}}";

        mvc.perform(post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", webhookService.sign(body)))      // no login token needed
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.matchedPaymentId").value(paymentId));
        mvc.perform(getAs("/api/v1/payments/" + paymentId, finance()))
                .andExpect(jsonPath("$.gatewayConfirmedAt").value(notNullValue()));
    }

    @Test
    void webhookWithAWrongOrMissingSignatureIsRejected() throws Exception {
        String body = "{\"event\":\"payment.captured\",\"payload\":{}}";

        mvc.perform(post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", webhookService.sign(body + " ")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        mvc.perform(post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    }

    @Test
    void demoControlsDeclineTheNextChargeAndSignBodies() throws Exception {
        mvc.perform(post("/dev/gateway/decline-next")).andExpect(status().isOk());
        String booking = newId("b-");
        mvc.perform(postJson("/api/v1/payments", bearer("app-06", Role.SERVICE), tripJson(booking, newId("t-"), "GATEWAY"))
                        .header("Idempotency-Key", booking + "-pay"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("GATEWAY_DECLINED"));

        mvc.perform(post("/dev/webhooks/sign").contentType(MediaType.TEXT_PLAIN).content("abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signature").value(webhookService.sign("abc")));
    }
}
