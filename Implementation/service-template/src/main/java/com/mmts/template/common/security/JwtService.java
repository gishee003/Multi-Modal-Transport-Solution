package com.mmts.template.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates and checks login tokens (signed JWT, HS256).
 * Every MMTS application uses the same key and claims, so a token issued by Identity service works everywhere.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final Duration lifetime;
    private final Clock clock;

    public JwtService(@Value("${mmts.security.jwt-secret}") String secret,
                      @Value("${mmts.security.issuer}") String issuer,
                      @Value("${mmts.security.token-minutes}") long tokenMinutes,
                      Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.lifetime = Duration.ofMinutes(tokenMinutes);
        this.clock = clock;
    }

    /** Issues a token for a user with the given roles. */
    public IssuedToken issue(String userId, Collection<Role> roles) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        String token = Jwts.builder()
                .subject(userId)
                .issuer(issuer)
                .claim("roles", roles.stream().map(Enum::name).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Checks the signature, issuer and expiry, and returns who the token belongs to.
     *
     * @throws JwtException if the token is invalid or expired
     */
    public AuthenticatedUser verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthenticatedUser(claims.getSubject(), readRoles(claims.get("roles")));
    }

    private static Set<Role> readRoles(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return EnumSet.noneOf(Role.class);
        }
        return list.stream()
                .map(String::valueOf)
                .filter(name -> java.util.Arrays.stream(Role.values()).anyMatch(r -> r.name().equals(name)))
                .map(Role::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
