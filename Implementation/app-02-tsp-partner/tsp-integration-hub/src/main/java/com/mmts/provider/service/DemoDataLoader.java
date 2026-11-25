package com.mmts.provider.service;

import com.mmts.provider.api.dto.FleetDtos.Reading;
import com.mmts.provider.domain.Driver;
import com.mmts.provider.repository.DriverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Sample data for the demo profile. Providers themselves come from application.yml (DMRC via ONDC, DTC bus,
 * ZIPRIDE scooters, DRIVEEASY cars, CITYCAB taxis, HOPBIKE disabled); their vehicles come from the feeds.
 * Here we add drivers (TSP staff) with Delhi locations and one scooter that reports once and then goes silent
 * (so offline detection can be shown after 60 seconds).
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final DriverRepository drivers;
    private final TelemetryService telemetry;
    private final Clock clock;

    public DemoDataLoader(DriverRepository drivers, TelemetryService telemetry, Clock clock) {
        this.drivers = drivers;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (drivers.count() > 0) {
            return;
        }
        addDriver("d-201", "Ramesh Kumar", "DL1CAB1234", 28.6330, 77.2190, true);    // Rajiv Chowk
        addDriver("d-202", "Suresh Yadav", "DL1CAB5678", 28.6258, 77.2340, true);    // Mandi House
        addDriver("d-203", "Anil Sharma", "DL1CAB9012", 28.6519, 77.1909, false);    // Karol Bagh, offline
        addDriver("d-204", "Mohd. Imran", "DL1CAB3456", 28.5677, 77.2433, true);     // Lajpat Nagar
        drivers.save(new Driver("d-205", "Vikram Singh", "DL1CAB7890", "CITYCAB"));  // registered, licence pending

        String now = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        telemetry.ingest(List.of(new Reading("ZR-SC-0099", "ZIPRIDE", "SCOOTER", 28.6304, 77.2177, 0.0, 47, true, now)),
                "DEMO", null);
        log.info("Demo data loaded: 5 drivers, 1 silent scooter (ZR-SC-0099)");
    }

    private void addDriver(String id, String name, String vehicle, double lat, double lon, boolean online) {
        Driver driver = new Driver(id, name, vehicle, "CITYCAB");
        driver.activate();
        if (online) {
            driver.goOnline();
        }
        driver.moveTo(lat, lon, clock.instant().truncatedTo(ChronoUnit.SECONDS));
        drivers.save(driver);
    }
}
