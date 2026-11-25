package com.mmts.provider.service;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.adapter.connector.ProviderConnector.AssignedDriver;
import com.mmts.provider.adapter.eligibility.EligibilityClient;
import com.mmts.provider.adapter.eligibility.EligibilityClient.Eligibility;
import com.mmts.provider.api.dto.DriverDtos.DriverView;
import com.mmts.provider.api.dto.DriverDtos.LocationRequest;
import com.mmts.provider.api.dto.DriverDtos.OfferView;
import com.mmts.provider.api.dto.DriverDtos.OnboardRequest;
import com.mmts.provider.api.dto.FleetDtos.IngestResult;
import com.mmts.provider.api.dto.FleetDtos.Reading;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.common.events.DomainEvent;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.common.security.Role;
import com.mmts.provider.domain.Driver;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.Place;
import com.mmts.provider.domain.ProviderDefinition;
import com.mmts.provider.repository.DriverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Drivers (TSP staff) (M5.5 onboarding and activation, M5.6 offers, M5.7 status and location).
 *
 * Key rules:
 * - activation and going online need Identity service to say the licence is eligible (INT-13, purpose TAXI_DRIVER);
 * - a LICENCE_EXPIRED event from Identity service (licence.events) deactivates the driver at once;
 * - a ride goes to the nearest free driver (Strategy: {@link DriverMatchingStrategy}).
 */
@Service
public class DriverService {

    private static final Logger log = LoggerFactory.getLogger(DriverService.class);
    private static final String TAXI_DRIVER = "TAXI_DRIVER";
    private static final String LMV = "LMV";

    private final DriverRepository drivers;
    private final EligibilityClient eligibility;
    private final DriverMatchingStrategy matching;
    private final OfferService offers;
    private final TelemetryService telemetry;
    private final AuditService audit;
    private final ProviderHubProperties properties;
    private final Clock clock;

    public DriverService(DriverRepository drivers, EligibilityClient eligibility, DriverMatchingStrategy matching,
                         OfferService offers, TelemetryService telemetry, AuditService audit,
                         ProviderHubProperties properties, Clock clock) {
        this.drivers = drivers;
        this.eligibility = eligibility;
        this.matching = matching;
        this.offers = offers;
        this.telemetry = telemetry;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------- M5.5 onboarding and activation ----------

    @Transactional
    public DriverView onboard(OnboardRequest request, AuthenticatedUser caller) {
        ProviderDefinition provider = properties.findEnabled(request.providerId())
                .orElseThrow(() -> ApiException.notFound("PROVIDER_NOT_FOUND", "No active provider " + request.providerId()));
        if (!provider.getModes().contains(Mode.TAXI)) {
            throw ApiException.badRequest("NOT_A_TAXI_PROVIDER", provider.getId() + " does not run taxis");
        }
        requireStaffOf(provider.getId(), caller);
        if (drivers.existsById(request.driverId())) {
            throw ApiException.conflict("DRIVER_EXISTS", "Driver " + request.driverId() + " is already registered");
        }
        Driver driver = drivers.save(new Driver(request.driverId(), request.name(), request.vehicleNumber(),
                provider.getId()));
        audit.record(caller, "DRIVER_ONBOARDED", "DRIVER", driver.getDriverId(), "SUCCESS");
        return view(driver);
    }

    /** Activation: only if Identity service says the licence allows taxi driving. 409 DRIVER_NOT_ELIGIBLE otherwise. */
    @Transactional
    public DriverView activate(String driverId, AuthenticatedUser caller) {
        Driver driver = find(driverId);
        requireStaffOf(driver.getProviderId(), caller);
        Eligibility answer = eligibility.check(driverId, LMV, TAXI_DRIVER);
        if (!answer.eligible()) {
            audit.record(caller, "DRIVER_ACTIVATE", "DRIVER", driverId, "REFUSED_" + answer.reason());
            throw ApiException.conflict("DRIVER_NOT_ELIGIBLE",
                    "Driver " + driverId + " cannot be activated: licence check says " + answer.reason());
        }
        driver.activate();
        audit.record(caller, "DRIVER_ACTIVATE", "DRIVER", driverId, "SUCCESS");
        return view(driver);
    }

    /** Observer: Identity service tells us a licence expired → the driver is deactivated and taken offline. */
    @EventListener
    @Transactional
    public void onLicenceEvent(DomainEvent event) {
        if (!"licence.events".equals(event.topic()) || !"LICENCE_EXPIRED".equals(event.type())) {
            return;
        }
        String userId = String.valueOf(event.payload().get("userId"));
        drivers.findById(userId).ifPresent(driver -> {
            driver.deactivate("LICENCE_EXPIRED");
            log.info("Driver {} deactivated: licence expired", userId);
            audit.record(null, "DRIVER_DEACTIVATED", "DRIVER", userId, "LICENCE_EXPIRED");
        });
    }

    // ---------- M5.7 status and location ----------

    @Transactional
    public DriverView setStatus(String driverId, String status, AuthenticatedUser caller) {
        Driver driver = find(driverId);
        if ("OFFLINE".equals(status)) {
            driver.goOffline();
        } else {
            if (driver.getStatus() != Driver.Status.ACTIVE) {
                throw ApiException.conflict("DRIVER_NOT_ACTIVE",
                        "Driver " + driverId + " is " + driver.getStatus() + " (" + driver.getStatusReason() + ")");
            }
            // Check the licence again: it may have expired since activation.
            Eligibility answer = eligibility.check(driverId, LMV, TAXI_DRIVER);
            if (!answer.eligible()) {
                audit.record(caller, "DRIVER_ONLINE", "DRIVER", driverId, "REFUSED_" + answer.reason());
                throw ApiException.conflict("DRIVER_NOT_ELIGIBLE",
                        "Driver " + driverId + " cannot go online: licence check says " + answer.reason());
            }
            driver.goOnline();
        }
        audit.record(caller, "DRIVER_" + status, "DRIVER", driverId, "SUCCESS");
        return view(driver);
    }

    /** A location from the TSP staff app. It goes through the same live-data pipeline as all other vehicles. */
    @Transactional
    public DriverView updateLocation(String driverId, LocationRequest location) {
        Driver driver = find(driverId);
        if (!driver.isOnline()) {
            throw ApiException.conflict("DRIVER_OFFLINE", "Go online before sending locations");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String recordedAt = location.recordedAt() != null ? location.recordedAt() : now.toString();
        Reading reading = new Reading(driver.getVehicleNumber(), driver.getProviderId(), Mode.TAXI.name(),
                location.lat(), location.lon(), location.speedKmph(), null, !driver.isOnTrip(), recordedAt);
        IngestResult result = telemetry.ingest(List.of(reading), "DRIVER_APP", null);
        if (result.deadLettered() > 0) {
            throw ApiException.badRequest("INVALID_LOCATION",
                    "Location rejected and kept as " + result.deadLetterIds().get(0) + " (see dead letters)");
        }
        driver.moveTo(location.lat(), location.lon(), now);
        return view(driver);
    }

    @Transactional(readOnly = true)
    public DriverView get(String driverId) {
        return view(find(driverId));
    }

    @Transactional(readOnly = true)
    public List<DriverView> list() {
        return drivers.findAll().stream().map(DriverService::view).toList();
    }

    // ---------- Used by the taxi plug-in ----------

    @Transactional(readOnly = true)
    public boolean hasFreeDriverNear(String providerId, Place pickup, double maxKm) {
        return matching.choose(freeDrivers(providerId), pickup, maxKm).isPresent();
    }

    /** Gives the ride to the nearest free driver and pushes them a ride offer (INT-08). */
    @Transactional
    public AssignedDriver assignNearest(String providerId, String holdId, Place pickup, Place drop, long farePaise,
                                        double maxKm, double avgSpeedKmph) {
        Driver driver = matching.choose(freeDrivers(providerId), pickup, maxKm)
                .orElseThrow(() -> ProviderConnector.notAvailable("No free driver near the pickup any more"));
        driver.setOnTrip(true);
        offers.create(driver.getDriverId(), holdId, pickup, drop, farePaise);
        double km = NearestDriverStrategy.distanceKm(driver, pickup);
        int eta = Math.max(1, (int) Math.ceil(km / avgSpeedKmph * 60));
        return new AssignedDriver(driver.getDriverId(), driver.getName(), driver.getVehicleNumber(), eta);
    }

    /** The hold was released: cancel the offer and free the driver. */
    @Transactional
    public void releaseAssignment(String holdId) {
        offers.cancelForHold(holdId).flatMap(drivers::findById).ifPresent(d -> d.setOnTrip(false));
    }

    // ---------- M5.6 offers ----------

    /** Driver accepts or rejects. A rejected ride frees the driver again. */
    @Transactional
    public OfferView respondToOffer(String driverId, String offerId, boolean accept, AuthenticatedUser caller) {
        OfferView answer = offers.respond(driverId, offerId, accept);
        if (!accept) {
            drivers.findById(driverId).ifPresent(d -> d.setOnTrip(false));
        }
        audit.record(caller, accept ? "OFFER_ACCEPTED" : "OFFER_REJECTED", "RIDE_OFFER", offerId, "SUCCESS");
        return answer;
    }

    // ---------- helpers ----------

    private List<Driver> freeDrivers(String providerId) {
        return drivers.findByProviderId(providerId).stream().filter(Driver::isFree).toList();
    }

    private Driver find(String driverId) {
        return drivers.findById(driverId)
                .orElseThrow(() -> ApiException.notFound("DRIVER_NOT_FOUND", "No driver with id " + driverId));
    }

    /** ADMIN may manage any driver; an OPERATOR only the drivers of their own provider. */
    private void requireStaffOf(String providerId, AuthenticatedUser caller) {
        if (caller == null || caller.hasRole(Role.ADMIN)) {
            return;
        }
        boolean ownProvider = properties.findEnabled(providerId).map(p -> p.isOperator(caller.userId())).orElse(false);
        if (!ownProvider) {
            throw ApiException.forbidden("NOT_YOUR_PROVIDER", "An operator can only manage their own provider's drivers");
        }
    }

    static DriverView view(Driver d) {
        return new DriverView(d.getDriverId(), d.getName(), d.getVehicleNumber(), d.getProviderId(),
                d.getStatus().name(), d.getStatusReason(), d.isOnline(), d.isOnTrip(), d.getLat(), d.getLon(),
                d.getLastSeenAt());
    }
}
