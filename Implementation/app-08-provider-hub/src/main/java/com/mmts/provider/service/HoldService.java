package com.mmts.provider.service;

import com.mmts.provider.adapter.connector.ProviderConnector.AccessGrant;
import com.mmts.provider.adapter.connector.ProviderConnector.AssignedDriver;
import com.mmts.provider.adapter.connector.ProviderConnector.HoldQuote;
import com.mmts.provider.adapter.connector.ProviderConnector.LegRequest;
import com.mmts.provider.api.dto.HoldDtos.Access;
import com.mmts.provider.api.dto.HoldDtos.ConfirmResponse;
import com.mmts.provider.api.dto.HoldDtos.DriverDto;
import com.mmts.provider.api.dto.HoldDtos.HoldRequest;
import com.mmts.provider.api.dto.HoldDtos.HoldResponse;
import com.mmts.provider.api.dto.HoldDtos.UnlockRequest;
import com.mmts.provider.api.dto.HoldDtos.UnlockResponse;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.domain.Hold;
import com.mmts.provider.repository.HoldRepository;
import com.mmts.provider.service.ProviderRegistry.RegisteredProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * INT-12 hold / confirm / release / unlock (M5.4). This is core code: it works the same for every provider
 * and reaches the provider only through the plug-in the registry returns.
 *
 * Rules: a hold lasts 10 minutes (application.yml); confirming an expired or released hold → 409 HOLD_NOT_ACTIVE;
 * releasing twice is safe (204 both times); unlocking a hold that is not confirmed → 409 HOLD_NOT_CONFIRMED.
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldRepository holds;
    private final ProviderRegistry registry;
    private final TelemetryService telemetry;
    private final AuditService audit;
    private final ProviderHubProperties properties;
    private final Clock clock;

    public HoldService(HoldRepository holds, ProviderRegistry registry, TelemetryService telemetry,
                       AuditService audit, ProviderHubProperties properties, Clock clock) {
        this.holds = holds;
        this.registry = registry;
        this.telemetry = telemetry;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public HoldResponse hold(HoldRequest request, AuthenticatedUser caller) {
        RegisteredProvider provider = registry.require(request.providerId());     // 404 PROVIDER_NOT_FOUND
        if (!provider.definition().getModes().contains(request.mode())) {
            throw ApiException.badRequest("MODE_NOT_SUPPORTED",
                    provider.id() + " does not offer mode " + request.mode());
        }
        // A retry of the same leg gets the same live hold back instead of holding twice.
        Optional<Hold> existing = holds.findFirstByLegIdAndStatusIn(request.legId(),
                EnumSet.of(Hold.Status.HELD, Hold.Status.CONFIRMED, Hold.Status.UNLOCKED));
        if (existing.isPresent()) {
            return holdResponse(existing.get());
        }
        // Core check on the common vehicle model: a vehicle we know is offline or taken cannot be held.
        if (request.vehicleId() != null) {
            telemetry.latest(request.vehicleId())
                    .filter(v -> !v.online() || !v.available())
                    .ifPresent(v -> {
                        throw ApiException.conflict("NOT_AVAILABLE", "Vehicle " + v.vehicleId() + " is "
                                + (v.online() ? "already taken" : "offline"));
                    });
        }
        String holdId = "h-" + UUID.randomUUID().toString().substring(0, 8);
        LegRequest leg = new LegRequest(holdId, request.legId(), request.mode(), request.from(), request.to(),
                request.startTime(), request.vehicleId(), 0);
        HoldQuote quote = registry.call(provider.id(), "hold", false,
                () -> provider.connector().hold(provider.definition(), leg));     // 409 NOT_AVAILABLE from plug-in

        Instant now = now();
        Hold hold = holds.save(new Hold(holdId, request.bookingId(), request.legId(), provider.id(), request.mode(),
                quote.vehicleId(), request.from(), request.to(), request.startTime(), quote.pricePaise(),
                quote.providerRef(), now, now.plus(Duration.ofMinutes(properties.getHoldMinutes()))));
        if (hold.getVehicleId() != null) {
            telemetry.setAvailability(hold.getVehicleId(), false);
        }
        audit.record(caller, "HOLD_CREATED", "HOLD", holdId, "SUCCESS");
        return holdResponse(hold);
    }

    /** noRollbackFor: when the hold turns out to be expired, the EXPIRED status is saved even though we answer 409. */
    @Transactional(noRollbackFor = ApiException.class)
    public ConfirmResponse confirm(String holdId, AuthenticatedUser caller) {
        Hold hold = find(holdId);
        if (hold.isConfirmedOrUnlocked()) {
            return confirmResponse(hold);      // repeat-safe: same answer again
        }
        expireIfOverdue(hold);
        if (hold.getStatus() != Hold.Status.HELD) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "Hold " + holdId + " is " + hold.getStatus());
        }
        RegisteredProvider provider = registry.require(hold.getProviderId());
        AccessGrant grant = registry.call(provider.id(), "confirm", false,
                () -> provider.connector().confirm(provider.definition(), leg(hold), hold.getProviderRef()));
        AssignedDriver d = grant.driver();
        hold.markConfirmed(grant.type(), grant.value(), grant.validFrom(), grant.validUntil(),
                d == null ? null : d.driverId(), d == null ? null : d.name(),
                d == null ? null : d.vehicleNumber(), d == null ? null : d.etaMinutes());
        audit.record(caller, "HOLD_CONFIRMED", "HOLD", holdId, "SUCCESS");
        return confirmResponse(hold);
    }

    /** Undo step. Safe to repeat: an already released or expired hold is left as it is. */
    @Transactional
    public void release(String holdId, AuthenticatedUser caller) {
        Hold hold = find(holdId);
        if (hold.isFinished()) {
            return;
        }
        RegisteredProvider provider = registry.require(hold.getProviderId());
        registry.run(provider.id(), "release", true,
                () -> provider.connector().release(provider.definition(), leg(hold), hold.getProviderRef()));
        hold.markReleased();
        if (hold.getVehicleId() != null) {
            telemetry.setAvailability(hold.getVehicleId(), true);
        }
        audit.record(caller, "HOLD_RELEASED", "HOLD", holdId, "SUCCESS");
    }

    @Transactional
    public UnlockResponse unlock(String vehicleId, UnlockRequest request, AuthenticatedUser caller) {
        Hold hold = find(request.holdId());
        if (hold.getStatus() == Hold.Status.UNLOCKED) {
            return new UnlockResponse(hold.getVehicleId(), "UNLOCKED", hold.getUnlockedAt());   // repeat-safe
        }
        if (hold.getStatus() != Hold.Status.CONFIRMED) {
            throw ApiException.conflict("HOLD_NOT_CONFIRMED", "Hold " + hold.getHoldId() + " is " + hold.getStatus());
        }
        if (!vehicleId.equals(hold.getVehicleId())) {
            throw ApiException.badRequest("VEHICLE_MISMATCH",
                    "Hold " + hold.getHoldId() + " is not for vehicle " + vehicleId);
        }
        RegisteredProvider provider = registry.require(hold.getProviderId());
        registry.run(provider.id(), "unlock", true,
                () -> provider.connector().unlock(provider.definition(), leg(hold), hold.getProviderRef()));
        hold.markUnlocked(request.userId(), now());
        audit.record(caller, "VEHICLE_UNLOCKED", "VEHICLE", vehicleId, "SUCCESS");
        return new UnlockResponse(vehicleId, "UNLOCKED", hold.getUnlockedAt());
    }

    /** Releases every HELD hold whose 10 minutes have passed (run by the scheduler). Returns how many. */
    @Transactional
    public int expireOverdueHolds() {
        int count = 0;
        for (Hold hold : holds.findByStatusAndExpiresAtLessThanEqual(Hold.Status.HELD, now())) {
            expireIfOverdue(hold);
            count++;
        }
        return count;
    }

    private void expireIfOverdue(Hold hold) {
        if (!hold.isOverdue(now())) {
            return;
        }
        try {
            RegisteredProvider provider = registry.require(hold.getProviderId());
            registry.run(provider.id(), "release", true,
                    () -> provider.connector().release(provider.definition(), leg(hold), hold.getProviderRef()));
        } catch (ApiException e) {
            log.warn("Could not release expired hold {} at its provider: {}", hold.getHoldId(), e.getMessage());
        }
        hold.markExpired();
        if (hold.getVehicleId() != null) {
            telemetry.setAvailability(hold.getVehicleId(), true);
        }
        audit.record(null, "HOLD_EXPIRED", "HOLD", hold.getHoldId(), "EXPIRED");
    }

    private Hold find(String holdId) {
        return holds.findById(holdId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "No hold with id " + holdId));
    }

    private static LegRequest leg(Hold h) {
        return new LegRequest(h.getHoldId(), h.getLegId(), h.getMode(), h.from(), h.to(), h.getStartTime(),
                h.getVehicleId(), h.getPricePaise());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static HoldResponse holdResponse(Hold h) {
        return new HoldResponse(h.getHoldId(), h.getLegId(), h.getProviderId(), h.getStatus().name(),
                h.getPricePaise(), h.getExpiresAt());
    }

    private static ConfirmResponse confirmResponse(Hold h) {
        Access access = new Access(h.getAccessType(), h.getAccessCode(), h.getAccessValidFrom(), h.getAccessValidUntil());
        DriverDto driver = h.getDriverId() == null ? null
                : new DriverDto(h.getDriverId(), h.getDriverName(), h.getDriverVehicleNumber(), h.getDriverEtaMinutes());
        return new ConfirmResponse(h.getHoldId(), h.getStatus().name(), access, driver);
    }
}
