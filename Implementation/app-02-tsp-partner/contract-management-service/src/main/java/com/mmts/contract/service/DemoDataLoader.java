package com.mmts.contract.service;

import com.mmts.contract.domain.CommercialTerms;
import com.mmts.contract.domain.Contract;
import com.mmts.contract.domain.ContractStatus;
import com.mmts.contract.domain.ServiceLevels;
import com.mmts.contract.domain.Tsp;
import com.mmts.contract.repository.ContractRepository;
import com.mmts.contract.repository.TspRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * Sample TSPs for the demo profile: the same six TSPs the TSP Integration Hub has connectors for.
 * Five have an ACTIVE contract; HOPBIKE (whose connector is disabled in the hub) has a contract UNDER_REVIEW,
 * so the demo can show business onboarding: approve it here, then enable its connector in the hub.
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final TspRepository tsps;
    private final ContractRepository contracts;
    private final Clock clock;

    public DemoDataLoader(TspRepository tsps, ContractRepository contracts, Clock clock) {
        this.tsps = tsps;
        this.contracts = contracts;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tsps.count() > 0) {
            return;
        }
        Instant now = clock.instant();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2027, 12, 31);
        ServiceLevels standard = new ServiceLevels(5.0, 98.0, 0, 2);
        ServiceLevels onDemand = new ServiceLevels(5.0, 97.0, 8.0, 2);

        add("DMRC", "Delhi Metro Rail Corporation", "METRO", "op-dmrc", from, to,
                new CommercialTerms(3.0, 0, 0, 7), standard, ContractStatus.ACTIVE, now);
        add("DTC", "Delhi Transport Corporation", "BUS", "op-dtc", from, to,
                new CommercialTerms(3.0, 0, 0, 7), standard, ContractStatus.ACTIVE, now);
        add("ZIPRIDE", "ZipRide Mobility Pvt Ltd", "SCOOTER", "op-zipride", from, to,
                new CommercialTerms(12.0, 500, 1000, 7), onDemand, ContractStatus.ACTIVE, now);
        add("DRIVEEASY", "DriveEasy Rentals Pvt Ltd", "CAR", "op-driveeasy", from, to,
                new CommercialTerms(15.0, 2000, 5000, 14), standard, ContractStatus.ACTIVE, now);
        add("CITYCAB", "CityCab Fleet Services", "TAXI", "op-citycab", from, to,
                new CommercialTerms(18.0, 0, 3000, 7), onDemand, ContractStatus.ACTIVE, now);
        add("HOPBIKE", "HopBike Sharing LLP", "BIKE", "op-hopbike", from, to,
                new CommercialTerms(10.0, 300, 500, 7), onDemand, ContractStatus.UNDER_REVIEW, now);
        log.info("Demo data: 6 TSPs, 5 ACTIVE contracts, HOPBIKE contract UNDER_REVIEW");
    }

    private void add(String id, String name, String mode, String operator, LocalDate from, LocalDate to,
                     CommercialTerms terms, ServiceLevels sla, ContractStatus target, Instant now) {
        tsps.save(new Tsp(id, name, "REG-" + id, "acc_" + id.toLowerCase(), Set.of(mode), Set.of(operator),
                Set.of("certificate-of-incorporation.pdf", "gst-certificate.pdf", "transport-permit.pdf"), now));
        Contract c = new Contract("c-" + id.toLowerCase() + "-1", id, 1, from, to, "Delhi NCR", Set.of(mode), terms, sla, now);
        c.moveTo(ContractStatus.UNDER_REVIEW, "Sample data", now);
        if (target == ContractStatus.ACTIVE) {
            c.moveTo(ContractStatus.ACTIVE, "Sample data", now);
        }
        contracts.save(c);
    }
}
