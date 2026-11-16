package com.mmts.booking;

import com.mmts.booking.adapter.ProviderHubClient;
import com.mmts.booking.api.BookingRequest;
import com.mmts.booking.api.BookingRequest.Leg;
import com.mmts.booking.api.PlaceDto;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.common.security.Role;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.domain.Mode;
import com.mmts.booking.domain.Place;
import com.mmts.booking.service.BookingStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Sample legs with real Delhi places, shared by the tests. */
public final class TestJourneys {

    public static final PlaceDto RAJIV_CHOWK = new PlaceDto("Rajiv Chowk Metro", 28.6328, 77.2197, "DMRC-RCK");
    public static final PlaceDto INDIA_GATE = new PlaceDto("India Gate", 28.6129, 77.2295, null);
    public static final PlaceDto KHAN_MARKET = new PlaceDto("Khan Market", 28.6003, 77.2270, null);
    public static final PlaceDto AEROCITY = new PlaceDto("Aerocity", 28.5562, 77.1180, null);

    private TestJourneys() {
    }

    public static Instant soon() {
        return Instant.now().plus(Duration.ofMinutes(15));
    }

    public static Leg metro() {
        return new Leg(Mode.METRO, "DMRC", RAJIV_CHOWK, INDIA_GATE, soon(), null);
    }

    public static Leg scooter() {
        return new Leg(Mode.SCOOTER, "ZIPRIDE", INDIA_GATE, KHAN_MARKET, soon().plusSeconds(1200), "ZR-SC-0042");
    }

    public static Leg taxi() {
        return new Leg(Mode.TAXI, "MERU", KHAN_MARKET, AEROCITY, soon().plusSeconds(2400), null);
    }

    public static Leg car() {
        return new Leg(Mode.CAR, "ZOOMCAR", KHAN_MARKET, AEROCITY, soon().plusSeconds(2400), "ZC-CAR-0007");
    }

    public static Leg walk() {
        return new Leg(Mode.WALK, null, INDIA_GATE, KHAN_MARKET, soon(), null);
    }

    public static BookingRequest journey(Leg... legs) {
        return new BookingRequest(List.of(legs), "WALLET");
    }

    /** A traveller with a fresh user id, so tests never see each other's bookings. */
    public static AuthenticatedUser newTraveller() {
        return new AuthenticatedUser("u-" + UUID.randomUUID().toString().substring(0, 6), Set.of(Role.TRAVELLER));
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Saves a booking that got its bike hold but never got paid (LEGS_HELD), created the given minutes ago.
     * This is what a booking looks like if the app crashed between holding and paying.
     */
    public static Booking heldButUnpaid(BookingStore store, ProviderHubClient providerHub, int minutesAgo) {
        Instant created = Instant.now().minus(Duration.ofMinutes(minutesAgo));
        Booking b = new Booking("b-" + UUID.randomUUID().toString().substring(0, 8), "u-exp", newKey(), "-", "WALLET",
                created);
        BookingLeg leg = b.addLeg(Mode.BIKE, "YULU", new Place("Lodhi Garden", 28.5931, 77.2197, null),
                new Place("India Gate", 28.6129, 77.2295, null), Instant.now(), "YU-BK-0107");
        ProviderHubClient.HoldResult hold = providerHub.hold(new ProviderHubClient.HoldRequest(b.getId(), leg.getId(),
                "YULU", "BIKE", new ProviderHubClient.PlaceRef("Lodhi Garden", 28.5931, 77.2197, null),
                new ProviderHubClient.PlaceRef("India Gate", 28.6129, 77.2295, null), Instant.now(), "YU-BK-0107"));
        leg.markHeld(hold.holdId(), hold.pricePaise());
        b.moveTo(BookingStatus.LEGS_HELD, created);
        return store.save(b);
    }
}
