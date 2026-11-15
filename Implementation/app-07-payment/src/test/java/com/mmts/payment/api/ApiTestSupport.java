package com.mmts.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.payment.common.security.JwtService;
import com.mmts.payment.common.security.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Small helpers shared by the endpoint (contract) tests: login tokens and request builders. */
abstract class ApiTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtService jwtService;

    static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    String bearer(String userId, Role role) {
        return "Bearer " + jwtService.issue(userId, Set.of(role)).token();
    }

    MockHttpServletRequestBuilder postJson(String path, String auth, String json) {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        return auth == null ? request : request.header("Authorization", auth);
    }

    MockHttpServletRequestBuilder getAs(String path, String auth) {
        return get(path).header("Authorization", auth);
    }

    /** A traveller with money in the wallet, topped up through the real endpoint. */
    String travellerWith(long paise) throws Exception {
        String user = newId("t-");
        mvc.perform(postJson("/api/v1/wallets/me/top-ups", bearer(user, Role.TRAVELLER),
                "{\"amountPaise\":" + paise + ",\"paymentToken\":\"tok_demo_visa\"}"));
        return user;
    }

    /** The INT-11 request body from CONTRACTS.md, with the given booking, user and method. */
    static String tripJson(String booking, String user, String method) {
        return """
                {"bookingId":"%s","userId":"%s","amountPaise":18500,"method":"%s",
                 "splits":[{"providerId":"DMRC","amountPaise":4000},{"providerId":"ZIPRIDE","amountPaise":14500}]}"""
                .formatted(booking, user, method);
    }
}
