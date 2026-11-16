package com.mmts.provider.service;

import com.mmts.provider.adapter.eligibility.StubEligibilityClient;
import com.mmts.provider.api.dto.DriverDtos.DriverView;
import com.mmts.provider.api.dto.DriverDtos.OnboardRequest;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.common.events.EventPublisher;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.common.security.Role;
import com.mmts.provider.domain.Driver;
import com.mmts.provider.domain.Place;
import com.mmts.provider.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Driver onboarding and activation rules (M5.5) and the matching strategy. */
@SpringBootTest
@Import(MutableClock.Config.class)
class DriverServiceTest {

    static final AuthenticatedUser ADMIN = new AuthenticatedUser("admin-1", Set.of(Role.ADMIN));

    @Autowired DriverService drivers;
    @Autowired StubEligibilityClient eligibility;
    @Autowired EventPublisher events;

    private String newDriver(String licenceReason) {
        String id = "t-" + UUID.randomUUID().toString().substring(0, 6);
        eligibility.setReason(id, licenceReason);
        drivers.onboard(new OnboardRequest(id, "Test Driver", "DL1T" + id.substring(2), "CITYCAB"), ADMIN);
        return id;
    }

    private static void assertCode(String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void activationIsRefusedWhenTheLicenceIsNotEligible() {
        String pending = newDriver("PENDING_VERIFICATION");
        String notADriver = newDriver("NOT_A_DRIVER");      // INT-13 reason for a user without the DRIVER role

        assertCode("DRIVER_NOT_ELIGIBLE", () -> drivers.activate(pending, ADMIN));
        assertCode("DRIVER_NOT_ELIGIBLE", () -> drivers.activate(notADriver, ADMIN));
        assertThat(drivers.get(pending).status()).isEqualTo("REGISTERED");
        assertCode("DRIVER_NOT_ACTIVE", () -> drivers.setStatus(pending, "ONLINE", ADMIN));
    }

    @Test
    void eligibleDriverIsActivatedAndCanGoOnline() {
        String id = newDriver("VERIFIED");

        assertThat(drivers.activate(id, ADMIN).status()).isEqualTo("ACTIVE");
        assertThat(drivers.setStatus(id, "ONLINE", ADMIN).online()).isTrue();
    }

    @Test
    void goingOnlineChecksTheLicenceAgain() {
        String id = newDriver("VERIFIED");
        drivers.activate(id, ADMIN);
        eligibility.setReason(id, "EXPIRED");         // licence expired after activation

        assertCode("DRIVER_NOT_ELIGIBLE", () -> drivers.setStatus(id, "ONLINE", ADMIN));
    }

    @Test
    void licenceExpiredEventDeactivatesTheDriver() {
        String id = newDriver("VERIFIED");
        drivers.activate(id, ADMIN);
        drivers.setStatus(id, "ONLINE", ADMIN);

        events.publish("licence.events", "LICENCE_EXPIRED", Map.of("userId", id, "expiredOn", "2026-11-15"));

        DriverView after = drivers.get(id);
        assertThat(after.status()).isEqualTo("DEACTIVATED");
        assertThat(after.statusReason()).isEqualTo("LICENCE_EXPIRED");
        assertThat(after.online()).isFalse();
    }

    @Test
    void unknownUserAndDuplicateOnboardingAreRefused() {
        assertCode("USER_NOT_FOUND", () -> {
            drivers.onboard(new OnboardRequest("t-nobody", "Nobody", "DL0", "CITYCAB"), ADMIN);
            drivers.activate("t-nobody", ADMIN);
        });
        String id = newDriver("VERIFIED");
        assertCode("DRIVER_EXISTS", () -> drivers.onboard(new OnboardRequest(id, "Again", "DL0", "CITYCAB"), ADMIN));
        assertCode("NOT_A_TAXI_PROVIDER", () -> drivers.onboard(new OnboardRequest("t-x", "X", "DL0", "ZIPRIDE"), ADMIN));
    }

    @Test
    void nearestDriverStrategyPicksTheClosestDriverWithinRange() {
        Place pickup = new Place("Rajiv Chowk", 28.6328, 77.2197, null);
        Driver far = driverAt("far", 28.5677, 77.2433);        // Lajpat Nagar, about 7.5 km
        Driver mid = driverAt("mid", 28.6258, 77.2340);        // Mandi House, about 1.6 km
        Driver near = driverAt("near", 28.6330, 77.2190);      // next to the pickup

        NearestDriverStrategy strategy = new NearestDriverStrategy();

        assertThat(strategy.choose(List.of(far, mid, near), pickup, 5)).contains(near);
        assertThat(strategy.choose(List.of(far, mid), pickup, 5)).contains(mid);
        assertThat(strategy.choose(List.of(far), pickup, 5)).isEmpty();   // too far away
    }

    private static Driver driverAt(String id, double lat, double lon) {
        Driver d = new Driver(id, id, "DL-" + id, "CITYCAB");
        d.moveTo(lat, lon, Instant.parse("2026-11-15T10:00:00Z"));
        return d;
    }
}
