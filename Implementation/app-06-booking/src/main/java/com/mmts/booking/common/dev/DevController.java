package com.mmts.booking.common.dev;

import com.mmts.booking.common.events.DomainEvent;
import com.mmts.booking.common.events.EventPublisher;
import com.mmts.booking.common.security.JwtService;
import com.mmts.booking.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Demo-only helpers (active only in the "demo" profile):
 * - POST /dev/token issues a test login token, so this app can be tested without APP-04 running.
 * - GET  /dev/events lists the events this app published (what would have gone to Kafka).
 */
@RestController
@RequestMapping("/dev")
@Profile("demo")
@Tag(name = "Demo helpers", description = "Only available in the demo profile")
public class DevController {

    private final JwtService jwtService;
    private final EventPublisher eventPublisher;

    public DevController(JwtService jwtService, EventPublisher eventPublisher) {
        this.jwtService = jwtService;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/token")
    @Operation(summary = "Issue a test token", description = "Example body: {\"userId\":\"u-101\",\"roles\":[\"TRAVELLER\"]}")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        JwtService.IssuedToken issued = jwtService.issue(request.userId(), request.roles());
        return new TokenResponse(issued.token(), issued.expiresAt());
    }

    @GetMapping("/events")
    @Operation(summary = "Events published by this application (newest first)")
    public List<DomainEvent> events() {
        return eventPublisher.recentEvents();
    }

    public record TokenRequest(@NotBlank String userId, @NotEmpty Set<Role> roles) {
    }

    public record TokenResponse(String token, Instant expiresAt) {
    }
}
