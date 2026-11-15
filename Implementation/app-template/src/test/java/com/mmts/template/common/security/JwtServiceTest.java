package com.mmts.template.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "mmts-group11-demo-signing-key-change-me-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private JwtService serviceAt(Instant now) {
        return new JwtService(SECRET, "mmts-identity", 60, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void tokenRoundTripKeepsUserAndRoles() {
        JwtService service = serviceAt(NOW);
        JwtService.IssuedToken issued = service.issue("u-7", Set.of(Role.DRIVER, Role.SERVICE));

        AuthenticatedUser user = service.verify(issued.token());

        assertThat(user.userId()).isEqualTo("u-7");
        assertThat(user.roles()).containsExactlyInAnyOrder(Role.DRIVER, Role.SERVICE);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void expiredTokenIsRefused() {
        String token = serviceAt(NOW).issue("u-7", Set.of(Role.TRAVELLER)).token();

        JwtService twoHoursLater = serviceAt(NOW.plusSeconds(7200));

        assertThatThrownBy(() -> twoHoursLater.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRefused() {
        JwtService other = new JwtService("a-completely-different-signing-key-0123456789", "mmts-identity", 60,
                Clock.fixed(NOW, ZoneOffset.UTC));
        String forged = other.issue("u-7", Set.of(Role.ADMIN)).token();

        assertThatThrownBy(() -> serviceAt(NOW).verify(forged)).isInstanceOf(JwtException.class);
    }
}
