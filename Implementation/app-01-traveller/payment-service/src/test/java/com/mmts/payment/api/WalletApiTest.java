package com.mmts.payment.api;

import com.mmts.payment.adapter.StubRazorpayGateway;
import com.mmts.payment.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint tests for INT-05 Wallet, including the "no card data" rule (driver D9). */
@SpringBootTest
@AutoConfigureMockMvc
class WalletApiTest extends ApiTestSupport {

    private static final String ME = "/api/v1/wallets/me";
    private static final String TOP_UPS = "/api/v1/wallets/me/top-ups";

    @Autowired
    StubRazorpayGateway gateway;

    @Test
    void walletIsCreatedOnFirstUseWithZeroBalance() throws Exception {
        mvc.perform(getAs(ME, bearer(newId("t-"), Role.TRAVELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void topUpAddsMoneyAndAppearsInHistory() throws Exception {
        String me = bearer(newId("t-"), Role.TRAVELLER);

        mvc.perform(postJson(TOP_UPS, me, "{\"amountPaise\":20000,\"paymentToken\":\"tok_demo_visa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TOP_UP"))
                .andExpect(jsonPath("$.amountPaise").value(20000))
                .andExpect(jsonPath("$.balanceAfterPaise").value(20000))
                .andExpect(jsonPath("$.reference").value(startsWith("pay_stub_")));
        mvc.perform(getAs(ME, me)).andExpect(jsonPath("$.balancePaise").value(20000));
        mvc.perform(getAs(ME + "/transactions", me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("TOP_UP"));
    }

    @Test
    void topUpWithTheSameKeyIsAppliedOnce() throws Exception {
        String me = bearer(newId("t-"), Role.TRAVELLER);
        String key = newId("topup-");
        String body = "{\"amountPaise\":20000,\"paymentToken\":\"tok_demo_visa\"}";

        String first = mvc.perform(postJson(TOP_UPS, me, body).header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(postJson(TOP_UPS, me, body).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString()).isEqualTo(first));
        mvc.perform(getAs(ME, me)).andExpect(jsonPath("$.balancePaise").value(20000));
    }

    @Test
    void cardDetailsAreRefusedAndNothingIsCharged() throws Exception {
        String me = bearer(newId("t-"), Role.TRAVELLER);

        mvc.perform(postJson(TOP_UPS, me,
                        "{\"amountPaise\":20000,\"paymentToken\":\"tok_x\",\"cardNumber\":\"4111111111111111\",\"cvv\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CARD_DATA_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(containsString("$.cardNumber")));
        mvc.perform(postJson(TOP_UPS, me, "{\"amountPaise\":20000,\"paymentToken\":\"4111 1111 1111 1111\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CARD_DATA_NOT_ALLOWED"));
        mvc.perform(postJson(TOP_UPS, me, "{\"amountPaise\":20000,\"paymentToken\":\"my-card\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_TOKEN"));
        mvc.perform(getAs(ME, me)).andExpect(jsonPath("$.balancePaise").value(0));
    }

    @Test
    void declinedOrTooLargeTopUpsAddNothing() throws Exception {
        String me = bearer(newId("t-"), Role.TRAVELLER);
        gateway.declineNextCharge();

        mvc.perform(postJson(TOP_UPS, me, "{\"amountPaise\":20000,\"paymentToken\":\"tok_demo_visa\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("TOP_UP_DECLINED"));
        mvc.perform(postJson(TOP_UPS, me, "{\"amountPaise\":2000000,\"paymentToken\":\"tok_demo_visa\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOP_UP_LIMIT_EXCEEDED"));
        mvc.perform(getAs(ME, me)).andExpect(jsonPath("$.balancePaise").value(0));
    }

    @Test
    void travellersSeeOnlyTheirOwnWalletAndStaffCanLookUpAny() throws Exception {
        String user = travellerWith(30_000);

        mvc.perform(getAs("/api/v1/wallets/" + user, bearer("f-1", Role.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(30000));
        mvc.perform(getAs("/api/v1/wallets/" + user + "/transactions", bearer("s-1", Role.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amountPaise").value(30000));
        mvc.perform(getAs("/api/v1/wallets/" + user, bearer("u-other", Role.TRAVELLER)))
                .andExpect(status().isForbidden());
        mvc.perform(getAs(ME, bearer("booking-service", Role.SERVICE)))
                .andExpect(status().isForbidden());
        mvc.perform(getAs("/api/v1/wallets/nobody-" + newId(""), bearer("a-1", Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }
}
