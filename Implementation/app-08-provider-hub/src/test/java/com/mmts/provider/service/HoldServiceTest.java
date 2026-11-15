package com.mmts.provider.service;

import com.mmts.provider.api.dto.HoldDtos.ConfirmResponse;
import com.mmts.provider.api.dto.HoldDtos.HoldRequest;
import com.mmts.provider.api.dto.HoldDtos.HoldResponse;
import com.mmts.provider.api.dto.HoldDtos.UnlockRequest;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.common.security.Role;
import com.mmts.provider.domain.Driver;
import com.mmts.provider.domain.Hold;
import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.Place;
import com.mmts.provider.repository.DriverRepository;
import com.mmts.provider.repository.HoldRepository;
import com.mmts.provider.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.mmts.provider.support.TestProviders.INDIA_GATE;
import static com.mmts.provider.support.TestProviders.INDIA_GATE_BUS;
import static com.mmts.provider.support.TestProviders.RAJIV_CHOWK_BUS;
import static com.mmts.provider.support.TestProviders.RAJIV_CHOWK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** INT-12 rules in the core, with the real plug-ins and a clock the test can move forward. */
@SpringBootTest
@Import(MutableClock.Config.class)
class HoldServiceTest {

    static final AuthenticatedUser BOOKING = new AuthenticatedUser("app-06-booking", Set.of(Role.SERVICE));

    @Autowired HoldService holds;
    @Autowired HoldRepository holdRepository;
    @Autowired DriverRepository driverRepository;
    @Autowired FeedImportService feeds;
    @Autowired TelemetryService telemetry;
    @Autowired MutableClock clock;

    static HoldRequest request(String providerId, Mode mode, String vehicleId, Place from, Place to) {
        String booking = "b-" + UUID.randomUUID().toString().substring(0, 6);
        return new HoldRequest(booking, booking + "-L1", providerId, mode, from, to,
                Instant.parse("2026-11-15T10:20:00Z"), vehicleId);
    }

    static void assertCode(String code, Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void scooterHoldConfirmUnlockReturnsUnlockCode() {
        feeds.pollAllFeeds();       // fresh vehicle data at the current test time
        HoldResponse held = holds.hold(request("ZIPRIDE", Mode.SCOOTER, "ZR-SC-0044", RAJIV_CHOWK, INDIA_GATE), BOOKING);
        assertThat(held.status()).isEqualTo("HELD");
        assertThat(held.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(telemetry.latest("ZR-SC-0044").orElseThrow().available()).isFalse();   // held = not available

        ConfirmResponse confirmed = holds.confirm(held.holdId(), BOOKING);
        assertThat(confirmed.access().type()).isEqualTo("UNLOCK_CODE");
        assertThat(confirmed.driver()).isNull();

        assertThat(holds.unlock("ZR-SC-0044", new UnlockRequest(held.holdId(), "u-101"), BOOKING).status())
                .isEqualTo("UNLOCKED");
        holds.release(held.holdId(), BOOKING);
        assertThat(telemetry.latest("ZR-SC-0044").orElseThrow().available()).isTrue();
    }

    @Test
    void metroHoldGoesThroughOndcAndReturnsQrTicket() {
        HoldResponse held = holds.hold(request("DMRC", Mode.METRO, null, RAJIV_CHOWK, INDIA_GATE), BOOKING);
        assertThat(held.pricePaise()).isEqualTo(2100);

        ConfirmResponse confirmed = holds.confirm(held.holdId(), BOOKING);
        assertThat(confirmed.access().type()).isEqualTo("QR_TICKET");
        assertThat(confirmed.access().value()).startsWith("ONDC|DMRC|");
    }

    @Test
    void taxiConfirmAssignsTheNearestFreeDriver() {
        Place rohini = new Place("Rohini Sector 7", 28.7160, 77.1150, null);
        Driver near = new Driver("t-" + UUID.randomUUID().toString().substring(0, 6), "Test Driver", "DL1T0001", "CITYCAB");
        near.activate();
        near.goOnline();
        near.moveTo(28.7165, 77.1155, clock.instant());
        driverRepository.save(near);

        HoldResponse held = holds.hold(request("CITYCAB", Mode.TAXI, null, rohini, INDIA_GATE), BOOKING);
        ConfirmResponse confirmed = holds.confirm(held.holdId(), BOOKING);

        assertThat(confirmed.access().type()).isEqualTo("DRIVER_ASSIGNED");
        assertThat(confirmed.driver().driverId()).isEqualTo(near.getDriverId());
        assertThat(confirmed.driver().etaMinutes()).isEqualTo(1);
        assertThat(driverRepository.findById(near.getDriverId()).orElseThrow().isOnTrip()).isTrue();

        holds.release(held.holdId(), BOOKING);
        assertThat(driverRepository.findById(near.getDriverId()).orElseThrow().isOnTrip()).isFalse();
    }

    @Test
    void holdExpiresAfterTenMinutes() {
        HoldResponse held = holds.hold(request("DRIVEEASY", Mode.CAR, null, RAJIV_CHOWK, INDIA_GATE), BOOKING);

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertCode("HOLD_NOT_ACTIVE", () -> holds.confirm(held.holdId(), BOOKING));
        assertThat(holdRepository.findById(held.holdId()).orElseThrow().getStatus()).isEqualTo(Hold.Status.EXPIRED);
    }

    @Test
    void schedulerExpiresOverdueHolds() {
        HoldResponse held = holds.hold(request("DTC", Mode.BUS, null, RAJIV_CHOWK_BUS, INDIA_GATE_BUS), BOOKING);
        clock.advance(Duration.ofMinutes(11));

        assertThat(holds.expireOverdueHolds()).isGreaterThanOrEqualTo(1);
        assertThat(holdRepository.findById(held.holdId()).orElseThrow().getStatus()).isEqualTo(Hold.Status.EXPIRED);
    }

    @Test
    void releaseTwiceIsSafeAndAReleasedHoldCannotBeConfirmed() {
        HoldResponse held = holds.hold(request("DRIVEEASY", Mode.CAR, null, RAJIV_CHOWK, INDIA_GATE), BOOKING);

        holds.release(held.holdId(), BOOKING);
        holds.release(held.holdId(), BOOKING);        // no error the second time

        assertCode("HOLD_NOT_ACTIVE", () -> holds.confirm(held.holdId(), BOOKING));
    }

    @Test
    void unlockBeforeConfirmIsRefused() {
        feeds.pollAllFeeds();
        HoldResponse held = holds.hold(request("ZIPRIDE", Mode.SCOOTER, "ZR-SC-0045", RAJIV_CHOWK, INDIA_GATE), BOOKING);

        assertCode("HOLD_NOT_CONFIRMED",
                () -> holds.unlock("ZR-SC-0045", new UnlockRequest(held.holdId(), "u-101"), BOOKING));
        holds.release(held.holdId(), BOOKING);
    }

    @Test
    void aHeldVehicleIsNotAvailableToSomeoneElse() {
        feeds.pollAllFeeds();
        HoldResponse first = holds.hold(request("ZIPRIDE", Mode.SCOOTER, "ZR-SC-0046", RAJIV_CHOWK, INDIA_GATE), BOOKING);

        assertCode("NOT_AVAILABLE",
                () -> holds.hold(request("ZIPRIDE", Mode.SCOOTER, "ZR-SC-0046", RAJIV_CHOWK, INDIA_GATE), BOOKING));
        holds.release(first.holdId(), BOOKING);
    }

    @Test
    void repeatedHoldForTheSameLegReturnsTheSameHold() {
        HoldRequest request = request("DTC", Mode.BUS, null, RAJIV_CHOWK_BUS, INDIA_GATE_BUS);
        assertThat(holds.hold(request, BOOKING).holdId()).isEqualTo(holds.hold(request, BOOKING).holdId());
    }

    @Test
    void unknownOrDisabledProviderAndWrongModeAreRefused() {
        assertCode("PROVIDER_NOT_FOUND", () -> holds.hold(request("NOPE", Mode.BUS, null, RAJIV_CHOWK, INDIA_GATE), BOOKING));
        assertCode("PROVIDER_NOT_FOUND", () -> holds.hold(request("HOPBIKE", Mode.BIKE, null, RAJIV_CHOWK, INDIA_GATE), BOOKING));
        assertCode("MODE_NOT_SUPPORTED", () -> holds.hold(request("DTC", Mode.TAXI, null, RAJIV_CHOWK, INDIA_GATE), BOOKING));
        assertCode("HOLD_NOT_FOUND", () -> holds.confirm("h-missing", BOOKING));
    }
}
