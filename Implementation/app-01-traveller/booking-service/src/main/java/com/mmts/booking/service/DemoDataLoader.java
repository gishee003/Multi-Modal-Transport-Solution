package com.mmts.booking.service;

import com.mmts.booking.BookingProperties;
import com.mmts.booking.adapter.ProviderHubClient;
import com.mmts.booking.adapter.ProviderHubClient.HoldResult;
import com.mmts.booking.api.BookingRequest;
import com.mmts.booking.api.PlaceDto;
import com.mmts.booking.common.security.AuthenticatedUser;
import com.mmts.booking.common.security.Role;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.domain.Mode;
import com.mmts.booking.domain.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Demo sample data (demo profile only), with real Delhi places. Fares come from application.yml
 * (mmts.booking.stub.fares-paise). On start-up it creates:
 * 1. u-101: metro Rajiv Chowk -> Hauz Khas, then scooter Hauz Khas -> Qutub Minar (CONFIRMED, made through the real saga).
 * 2. u-102: bus Kashmere Gate -> Red Fort, then taxi Red Fort -> Connaught Place (COMPLETED, ready to be rated).
 * 3. u-103: bike Lodhi Garden -> India Gate, held 15 minutes ago and never paid (LEGS_HELD): the hold-expiry job
 *    cancels it about one minute after start-up.
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    static final PlaceDto RAJIV_CHOWK = new PlaceDto("Rajiv Chowk Metro", 28.6328, 77.2197, "DMRC-RCK");
    static final PlaceDto HAUZ_KHAS = new PlaceDto("Hauz Khas Metro", 28.5433, 77.2066, "DMRC-HKS");
    static final PlaceDto QUTUB_MINAR = new PlaceDto("Qutub Minar", 28.5245, 77.1855, null);
    static final PlaceDto KASHMERE_GATE = new PlaceDto("Kashmere Gate ISBT", 28.6675, 77.2282, "DTC-KGT");
    static final PlaceDto RED_FORT = new PlaceDto("Red Fort", 28.6562, 77.2410, "DTC-RFT");
    static final PlaceDto CONNAUGHT_PLACE = new PlaceDto("Connaught Place", 28.6315, 77.2167, null);

    private final BookingFacade facade;
    private final BookingService bookings;
    private final BookingStore store;
    private final ProviderHubClient providerHub;
    private final BookingProperties properties;
    private final Clock clock;

    public DemoDataLoader(BookingFacade facade, BookingService bookings, BookingStore store,
                          ProviderHubClient providerHub, BookingProperties properties, Clock clock) {
        this.facade = facade;
        this.bookings = bookings;
        this.store = store;
        this.providerHub = providerHub;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (store.count() > 0) {
            return;                                    // data already there (e.g. second start on PostgreSQL)
        }
        Instant start = clock.instant().plus(Duration.ofMinutes(20));
        log.info("Demo fares (paise): {}", properties.stub().faresPaise());

        // 1. Metro + scooter for u-101, made through the full saga
        facade.book(new AuthenticatedUser("u-101", Set.of(Role.TRAVELLER)), new BookingRequest(List.of(
                new BookingRequest.Leg(Mode.METRO, "DMRC", RAJIV_CHOWK, HAUZ_KHAS, start, null),
                new BookingRequest.Leg(Mode.SCOOTER, "ZIPRIDE", HAUZ_KHAS, QUTUB_MINAR, start.plus(Duration.ofMinutes(25)), "ZR-SC-0042")),
                "WALLET"), "demo-seed-1");

        // 2. Bus + taxi for u-102, then the trip is marked completed
        String trip = facade.book(new AuthenticatedUser("u-102", Set.of(Role.TRAVELLER)), new BookingRequest(List.of(
                new BookingRequest.Leg(Mode.BUS, "DTC", KASHMERE_GATE, RED_FORT, start.minus(Duration.ofHours(2)), null),
                new BookingRequest.Leg(Mode.TAXI, "MERU", RED_FORT, CONNAUGHT_PLACE, start.minus(Duration.ofMinutes(90)), null)),
                "GATEWAY"), "demo-seed-2").booking().bookingId();
        bookings.complete(new AuthenticatedUser("system", Set.of(Role.SERVICE)), trip);

        // 3. A bike booking stuck before payment for 15 minutes (the hold-expiry job will cancel it)
        Booking stuck = new Booking("b-stuck103", "u-103", "demo-seed-3", "-", "WALLET",
                clock.instant().minus(Duration.ofMinutes(15)));
        BookingLeg leg = stuck.addLeg(Mode.BIKE, "YULU", new Place("Lodhi Garden", 28.5931, 77.2197, null),
                new Place("India Gate", 28.6129, 77.2295, null), start, "YU-BK-0107");
        HoldResult hold = providerHub.hold(new ProviderHubClient.HoldRequest(stuck.getId(), leg.getId(), "YULU", "BIKE",
                new ProviderHubClient.PlaceRef("Lodhi Garden", 28.5931, 77.2197, null),
                new ProviderHubClient.PlaceRef("India Gate", 28.6129, 77.2295, null), start, "YU-BK-0107"));
        leg.markHeld(hold.holdId(), hold.pricePaise());
        stuck.recordTotal(hold.pricePaise());
        stuck.moveTo(BookingStatus.LEGS_HELD, stuck.getCreatedAt());
        stuck.log("HOLD_LEGS: done - held [" + hold.holdId() + "] (sample: payment never arrived)");
        store.save(stuck);
        log.info("Demo bookings loaded for u-101, u-102 and u-103");
    }
}
