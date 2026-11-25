package com.mmts.provider.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.common.security.JwtService;
import com.mmts.provider.common.security.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;

/**
 * Proof-of-concept profile: real HTTP calls to other MMTS services with a SERVICE login token.
 * An error answer from the other service is turned back into the same ApiException (status + code) that the stub
 * would have thrown, so the saga handles real and stub answers in exactly the same way.
 */
@Component
@Profile("poc")
public class RemoteCalls {

    private final RestClient rest;
    private final JwtService jwt;
    private final ObjectMapper json;
    private final String self;

    public RemoteCalls(RestClient.Builder builder, JwtService jwt, ObjectMapper json,
                       @Value("${spring.application.name}") String self) {
        this.rest = builder.build();
        this.jwt = jwt;
        this.json = json;
        this.self = self;
    }

    private String token() {
        return "Bearer " + jwt.issue(self, List.of(Role.SERVICE)).token();
    }

    public <T> T get(String url, Class<T> type) {
        return rest.get().uri(url).header("Authorization", token())
                .retrieve().onStatus(s -> s.isError(), (req, res) -> fail(res.getStatusCode().value(), res.getBody().readAllBytes()))
                .body(type);
    }

    public <T> T post(String url, Object body, String idempotencyKey, Class<T> type) {
        RestClient.RequestBodySpec spec = rest.post().uri(url).header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        RestClient.RequestBodySpec withBody = body == null ? spec : (RestClient.RequestBodySpec) spec.body(body);
        return withBody.retrieve()
                .onStatus(s -> s.isError(), (req, res) -> fail(res.getStatusCode().value(), res.getBody().readAllBytes()))
                .body(type);
    }

    public void delete(String url) {
        rest.delete().uri(url).header("Authorization", token())
                .retrieve().onStatus(s -> s.isError(), (req, res) -> fail(res.getStatusCode().value(), res.getBody().readAllBytes()))
                .toBodilessEntity();
    }

    private void fail(int status, byte[] body) throws IOException {
        String code = "REMOTE_ERROR";
        String message = "Remote call failed with HTTP " + status;
        try {
            JsonNode node = json.readTree(body);
            if (node.hasNonNull("code")) {
                code = node.get("code").asText();
            }
            if (node.hasNonNull("message")) {
                message = node.get("message").asText();
            }
        } catch (IOException ignored) {
            // not our error format: keep the generic code
        }
        HttpStatus httpStatus = HttpStatus.resolve(status);
        throw new ApiException(httpStatus == null ? HttpStatus.BAD_GATEWAY : httpStatus, code, message);
    }
}
