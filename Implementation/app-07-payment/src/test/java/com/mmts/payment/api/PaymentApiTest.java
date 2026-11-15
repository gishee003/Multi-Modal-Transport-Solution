package com.mmts.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mmts.payment.common.events.EventPublisher;
import com.mmts.payment.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract tests for INT-11 (docs/CONTRACTS.md): paths, headers, status codes, JSON fields and error codes. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiTest extends ApiTestSupport {

    private static final String PAYMENTS = "/api/v1/payments";

    @Autowired
    EventPublisher eventPublisher;

    private String app06() {
        return bearer("app-06", Role.SERVICE);
    }

    private JsonNode pay(String booking, String user, String method) throws Exception {
        String body = mvc.perform(postJson(PAYMENTS, app06(), tripJson(booking, user, method))
                        .header("Idempotency-Key", booking + "-pay"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void chargeAnswers201WithExactlyTheContractFields() throws Exception {
        String user = travellerWith(50_000);
        String booking = newId("b-");

        mvc.perform(postJson(PAYMENTS, app06(), tripJson(booking, user, "WALLET")).header("Idempotency-Key", booking + "-pay"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(matchesPattern("p-\\d+")))
                .andExpect(jsonPath("$.bookingId").value(booking))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.amountPaise").value(18500))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")));

        JsonNode again = objectMapper.readTree(mvc.perform(postJson(PAYMENTS, app06(), tripJson(booking, user, "WALLET"))
                .header("Idempotency-Key", booking + "-pay")).andReturn().getResponse().getContentAsString());
        assertThat(fieldNames(again)).containsExactly("paymentId", "bookingId", "status", "amountPaise",
                "failureReason", "createdAt");
    }

    @Test
    void repeatWithTheSameKeyAnswers200WithTheSameBody() throws Exception {
        String user = travellerWith(50_000);
        String booking = newId("b-");
        var request = postJson(PAYMENTS, app06(), tripJson(booking, user, "WALLET")).header("Idempotency-Key", booking + "-pay");

        String first = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        mvc.perform(getAs("/api/v1/wallets/me", bearer(user, Role.TRAVELLER)))
                .andExpect(jsonPath("$.balancePaise").value(31_500));           // charged once
    }

    @Test
    void insufficientBalanceIs201WithStatusFailed() throws Exception {
        String user = travellerWith(5_000);

        JsonNode out = pay(newId("b-"), user, "WALLET");

        assertThat(out.get("status").asText()).isEqualTo("FAILED");
        assertThat(out.get("failureReason").asText()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void chargeErrorsUseTheAgreedCodes() throws Exception {
        String user = travellerWith(50_000);
        String booking = newId("b-");

        mvc.perform(postJson(PAYMENTS, app06(), tripJson(booking, user, "WALLET")))            // no Idempotency-Key
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(postJson(PAYMENTS, app06(), tripJson(booking, user, "WALLET").replace("18500", "18000"))
                        .header("Idempotency-Key", booking + "-pay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPLIT_MISMATCH"));
        mvc.perform(postJson(PAYMENTS, app06(), "{\"userId\":\"u-1\",\"amountPaise\":-5,\"method\":\"WALLET\",\"splits\":[]}")
                        .header("Idempotency-Key", newId("k-")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.length()").value(3));
        mvc.perform(postJson(PAYMENTS, app06(), tripJson(booking, user, "WALLET").replace("\"method\"", "\"cvv\":\"123\",\"method\""))
                        .header("Idempotency-Key", booking + "-pay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CARD_DATA_NOT_ALLOWED"));
    }

    @Test
    void onlyServicesMayCharge() throws Exception {
        String body = tripJson(newId("b-"), "u-101", "WALLET");

        mvc.perform(postJson(PAYMENTS, null, body).header("Idempotency-Key", newId("k-")))
                .andExpect(status().isUnauthorized());
        mvc.perform(postJson(PAYMENTS, bearer("u-101", Role.TRAVELLER), body).header("Idempotency-Key", newId("k-")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void capturedPaymentIsPublishedOnPaymentEvents() throws Exception {
        JsonNode out = pay(newId("b-"), travellerWith(50_000), "WALLET");

        assertThat(eventPublisher.recentEvents()).anyMatch(e -> e.topic().equals("payment.events")
                && e.type().equals("PAYMENT_CAPTURED")
                && e.payload().get("paymentId").equals(out.get("paymentId").asText())
                && e.payload().get("amountPaise").equals(18_500L));
        assertThat(eventPublisher.recentEvents()).anyMatch(e -> e.topic().equals("audit.events")
                && e.payload().get("action").equals("PAYMENT_CHARGE")
                && e.payload().get("resourceId").equals(out.get("paymentId").asText()));
    }

    @Test
    void refundAnswers201ThenRepeats200AndRespectsTheLimit() throws Exception {
        String booking = newId("b-");
        String paymentId = pay(booking, travellerWith(50_000), "WALLET").get("paymentId").asText();
        String path = PAYMENTS + "/" + paymentId + "/refunds";
        String body = "{\"amountPaise\":18500,\"reason\":\"BOOKING_FAILED\"}";

        String first = mvc.perform(postJson(path, app06(), body).header("Idempotency-Key", booking + "-refund"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").value(matchesPattern("r-\\d+")))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.amountPaise").value(18500))
                .andReturn().getResponse().getContentAsString();
        assertThat(fieldNames(objectMapper.readTree(first))).containsExactly("refundId", "paymentId", "status", "amountPaise");

        String again = mvc.perform(postJson(path, app06(), body).header("Idempotency-Key", booking + "-refund"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(again).isEqualTo(first);

        mvc.perform(postJson(path, app06(), "{\"amountPaise\":1,\"reason\":\"MORE\"}").header("Idempotency-Key", newId("k-")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_PAYMENT"));
        assertThat(eventPublisher.recentEvents()).anyMatch(e -> e.type().equals("PAYMENT_REFUNDED")
                && e.payload().get("paymentId").equals(paymentId) && e.payload().get("bookingId").equals(booking));
    }

    @Test
    void refundErrorsAndRoles() throws Exception {
        String body = "{\"amountPaise\":100,\"reason\":\"X\"}";

        mvc.perform(postJson(PAYMENTS + "/p-0/refunds", app06(), body).header("Idempotency-Key", newId("k-")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mvc.perform(postJson(PAYMENTS + "/p-0/refunds", bearer("u-101", Role.TRAVELLER), body)
                        .header("Idempotency-Key", newId("k-")))
                .andExpect(status().isForbidden());
        String failed = pay(newId("b-"), travellerWith(100), "WALLET").get("paymentId").asText();
        mvc.perform(postJson(PAYMENTS + "/" + failed + "/refunds", bearer("s-1", Role.SUPPORT), body)
                        .header("Idempotency-Key", newId("k-")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CAPTURED"));
    }

    @Test
    void paymentDetailIsReadableByFinanceButNotByTravellers() throws Exception {
        String paymentId = pay(newId("b-"), travellerWith(50_000), "WALLET").get("paymentId").asText();

        mvc.perform(getAs(PAYMENTS + "/" + paymentId, bearer("f-1", Role.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[0].providerId").value("DMRC"))
                .andExpect(jsonPath("$.splits[0].commissionPaise").value(200))
                .andExpect(jsonPath("$.splits[1].commissionRule").value("FLAT_FEE 500 paise"));
        mvc.perform(getAs(PAYMENTS + "/" + paymentId, bearer("u-101", Role.TRAVELLER)))
                .andExpect(status().isForbidden());
        mvc.perform(getAs(PAYMENTS + "/p-0", bearer("a-1", Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}
