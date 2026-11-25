package com.mmts.payment.service;

import com.mmts.payment.api.WalletDtos.TopUpRequest;
import com.mmts.payment.domain.Provider;
import com.mmts.payment.repository.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sample data for the demo (demo profile only):
 * four providers with their types (which decide the commission rule) and three wallets with money in them.
 * The wallets are filled through the normal top-up flow, so the ledger and trial balance are right from the start.
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final ProviderRepository providers;
    private final WalletService walletService;

    public DemoDataLoader(ProviderRepository providers, WalletService walletService) {
        this.providers = providers;
        this.walletService = walletService;
    }

    @Override
    public void run(ApplicationArguments args) {
        providers.saveAll(List.of(
                new Provider("DMRC", "Delhi Metro Rail Corporation", "METRO", "acc_DMRC01"),
                new Provider("DTC", "Delhi Transport Corporation", "BUS", "acc_DTC01"),
                new Provider("ZIPRIDE", "ZipRide Scooters", "SCOOTER", "acc_ZIPRIDE01"),
                new Provider("CITYCAB", "CityCab Fleet Services", "TAXI", "acc_CITYCAB01"),
                new Provider("DRIVEEASY", "DriveEasy Rentals", "CAR", "acc_DRIVEEASY01"),
                new Provider("HOPBIKE", "HopBike Sharing", "BIKE", "acc_HOPBIKE01")));

        topUp("u-101", 50_000);    // Rs 500
        topUp("u-102", 5_000);     // Rs 50: too little for most trips, to show INSUFFICIENT_BALANCE
        topUp("u-103", 200_000);   // Rs 2000
        log.info("Demo data loaded: 4 providers, 3 wallets");
    }

    private void topUp(String userId, long amountPaise) {
        walletService.topUp(userId, "demo-seed-" + userId, new TopUpRequest(amountPaise, "tok_demo_seed"),
                PaymentEvents.SYSTEM);
    }
}
