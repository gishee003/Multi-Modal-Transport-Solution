package com.mmts.provider.api;

import com.mmts.provider.api.dto.DriverDtos.DriverView;
import com.mmts.provider.api.dto.DriverDtos.LocationRequest;
import com.mmts.provider.api.dto.DriverDtos.OfferView;
import com.mmts.provider.api.dto.DriverDtos.OnboardRequest;
import com.mmts.provider.api.dto.DriverDtos.StatusRequest;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.common.security.Role;
import com.mmts.provider.service.DriverService;
import com.mmts.provider.service.OfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Drivers: onboarding (M5.5), status and location (INT-07, M5.7), ride offers (INT-08, M5.6). */
@RestController
@RequestMapping("/api/v1/drivers")
@Tag(name = "Drivers", description = "Onboarding, status, location and ride offers")
public class DriverController {

    private final DriverService drivers;
    private final OfferService offers;

    public DriverController(DriverService drivers, OfferService offers) {
        this.drivers = drivers;
        this.offers = offers;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Register a taxi driver (status REGISTERED)")
    public ResponseEntity<DriverView> onboard(@Valid @RequestBody OnboardRequest request,
                                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.CREATED).body(drivers.onboard(request, caller));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "All drivers")
    public List<DriverView> list() {
        return drivers.list();
    }

    @PostMapping("/{driverId}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Activate a driver after the licence check with Identity service (INT-13)")
    public DriverView activate(@PathVariable String driverId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return drivers.activate(driverId, caller);
    }

    @GetMapping("/{driverId}/status")
    @PreAuthorize("hasAnyRole('DRIVER','ADMIN','OPERATOR')")
    @Operation(summary = "A driver's status, online flag and last location")
    public DriverView status(@PathVariable String driverId, @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrAdmin(caller, driverId, true);
        return drivers.get(driverId);
    }

    @PutMapping("/{driverId}/status")
    @PreAuthorize("hasAnyRole('DRIVER','ADMIN')")
    @Operation(summary = "Go ONLINE or OFFLINE (ONLINE needs an active driver with an eligible licence)")
    public DriverView setStatus(@PathVariable String driverId, @Valid @RequestBody StatusRequest request,
                                @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrAdmin(caller, driverId, false);
        return drivers.setStatus(driverId, request.status(), caller);
    }

    @PostMapping("/{driverId}/locations")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Send the driver's current location (driver app, every few seconds)")
    public DriverView location(@PathVariable String driverId, @RequestBody LocationRequest request,
                               @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrAdmin(caller, driverId, false);
        return drivers.updateLocation(driverId, request);
    }

    @GetMapping(value = "/{driverId}/offers", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('DRIVER','ADMIN')")
    @Operation(summary = "Live stream of ride offers (Server-Sent Events). Try: curl -N -H 'Authorization: Bearer ...'")
    public SseEmitter offers(@PathVariable String driverId, @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrAdmin(caller, driverId, false);
        return offers.subscribe(driverId);
    }

    @PostMapping("/{driverId}/offers/{offerId}/accept")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Accept a ride offer")
    public OfferView accept(@PathVariable String driverId, @PathVariable String offerId,
                            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrAdmin(caller, driverId, false);
        return drivers.respondToOffer(driverId, offerId, true, caller);
    }

    @PostMapping("/{driverId}/offers/{offerId}/reject")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Reject a ride offer (the driver becomes free again)")
    public OfferView reject(@PathVariable String driverId, @PathVariable String offerId,
                            @AuthenticationPrincipal AuthenticatedUser caller) {
        requireSelfOrAdmin(caller, driverId, false);
        return drivers.respondToOffer(driverId, offerId, false, caller);
    }

    /** A DRIVER may act only on their own account (token user id = driverId). */
    private static void requireSelfOrAdmin(AuthenticatedUser caller, String driverId, boolean operatorMayRead) {
        if (caller.hasRole(Role.ADMIN) || (operatorMayRead && caller.hasRole(Role.OPERATOR))) {
            return;
        }
        if (!caller.userId().equals(driverId)) {
            throw ApiException.forbidden("NOT_YOUR_DRIVER_ACCOUNT", "A driver can only use their own driver account");
        }
    }
}
