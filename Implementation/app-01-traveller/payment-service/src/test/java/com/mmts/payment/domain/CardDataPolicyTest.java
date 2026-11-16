package com.mmts.payment.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Driver D9: card data is recognised wherever it appears in a request body. */
class CardDataPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cardFieldsAreFoundWhateverTheirSpelling() throws Exception {
        String body = """
                {"amountPaise":1000,"paymentToken":"tok_x","cardNumber":"x","CVV":"123",
                 "billing":{"expiry_month":"12","card-expiry":"12/30"}}""";

        assertThat(CardDataPolicy.findCardData(mapper.readTree(body)))
                .containsExactlyInAnyOrder("$.cardNumber", "$.CVV", "$.billing.expiry_month", "$.billing.card-expiry");
    }

    @Test
    void aRealLookingCardNumberIsFoundEvenInAnInnocentField() throws Exception {
        String body = "{\"paymentToken\":\"4111 1111 1111 1111\",\"splits\":[{\"providerId\":\"5555555555554444\"}]}";

        assertThat(CardDataPolicy.findCardData(mapper.readTree(body)))
                .containsExactly("$.paymentToken", "$.splits[0].providerId");
    }

    @Test
    void normalPaymentBodiesAndTokensPass() throws Exception {
        String body = """
                {"bookingId":"b-5001","userId":"u-101","amountPaise":18500,"method":"WALLET",
                 "paymentToken":"tok_demo_visa_4242","splits":[{"providerId":"DMRC","amountPaise":4000}]}""";

        assertThat(CardDataPolicy.findCardData(mapper.readTree(body))).isEmpty();
        assertThat(CardDataPolicy.looksLikeCardNumber("4111111111111112")).isFalse();   // fails the Luhn check
        assertThat(CardDataPolicy.looksLikeCardNumber("1234")).isFalse();               // too short
    }
}
