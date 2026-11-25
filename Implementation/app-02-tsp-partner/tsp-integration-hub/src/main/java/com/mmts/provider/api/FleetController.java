package com.mmts.provider.api;

import com.mmts.provider.api.dto.FleetDtos.DeadLetterView;
import com.mmts.provider.api.dto.FleetDtos.FleetHealth;
import com.mmts.provider.api.dto.FleetDtos.IngestResult;
import com.mmts.provider.api.dto.FleetDtos.ProviderView;
import com.mmts.provider.api.dto.FleetDtos.TelemetryBatch;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.service.ProviderRegistry;
import com.mmts.provider.service.TelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Provider registry (M5.1), live-data ingestion and dead letters (M5.3), fleet health (M5.8). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Providers and fleet", description = "Registry, telemetry, dead letters, fleet health")
public class FleetController {

    private final ProviderRegistry registry;
    private final TelemetryService telemetry;

    public FleetController(ProviderRegistry registry, TelemetryService telemetry) {
        this.registry = registry;
        this.telemetry = telemetry;
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
    @Operation(summary = "All configured providers with their plug-in type and status")
    public List<ProviderView> providers() {
        return registry.describeAll();
    }

    @PostMapping("/telemetry")
    @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
    @Operation(summary = "Send a batch of vehicle readings (up to 500). Bad readings go to the dead-letter store")
    public ResponseEntity<IngestResult> ingest(@Valid @RequestBody TelemetryBatch batch,
                                               @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(telemetry.ingest(batch.readings(), "TELEMETRY_API", caller));
    }

    @GetMapping("/telemetry/dead-letters")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Rejected readings with the reason (newest first)")
    public List<DeadLetterView> deadLetters(@AuthenticationPrincipal AuthenticatedUser caller) {
        return telemetry.deadLetters(caller);
    }

    @GetMapping("/providers/{providerId}/fleet")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Fleet health of one provider: battery, online / offline, last seen")
    public FleetHealth fleet(@PathVariable String providerId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return telemetry.fleetHealth(providerId, caller);
    }
}
