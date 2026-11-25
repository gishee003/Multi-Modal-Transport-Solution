package com.mmts.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Checks the login token of every API call once, at the edge (OAuth 2.0 bearer token, signed with the shared key;
 * in production Keycloak issues it). Login itself (/api/v1/auth/**) is open. A call without a valid token never
 * reaches a service: 401 in the shared error format. Every request gets an X-Request-Id for tracing across services.
 */
@Component
public class TokenCheckFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenCheckFilter.class);

    private final SecretKey key;
    private final String issuer;

    public TokenCheckFilter(@Value("${mmts.security.jwt-secret}") String secret, @Value("${mmts.security.issuer}") String issuer) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        long started = System.currentTimeMillis();
        ServerWebExchange withId = exchange.mutate()
                .request(r -> r.header("X-Request-Id", requestId)).build();

        if (path.startsWith("/api/") && !path.startsWith("/api/v1/auth/")) {
            String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith("Bearer ")) {
                return reject(exchange.getResponse(), "UNAUTHENTICATED", "A login token is required");
            }
            try {
                Claims claims = Jwts.parser().verifyWith(key).requireIssuer(issuer).build()
                        .parseSignedClaims(header.substring(7)).getPayload();
                log.info("[{}] {} {} by {}", requestId, exchange.getRequest().getMethod(), path, claims.getSubject());
            } catch (JwtException | IllegalArgumentException e) {
                return reject(exchange.getResponse(), "INVALID_TOKEN", "The login token is not valid or has expired");
            }
        }
        return chain.filter(withId).doFinally(s -> log.info("[{}] {} -> {} in {} ms", requestId, path,
                exchange.getResponse().getStatusCode(), System.currentTimeMillis() - started));
    }

    private static Mono<Void> reject(ServerHttpResponse response, String code, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":[],\"timestamp\":\""
                + Instant.now() + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;   // before routing
    }
}
