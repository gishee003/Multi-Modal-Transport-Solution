package com.mmts.payment.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The daily jobs. The times are in application.yml (mmts.payment.schedule), in UTC.
 * They only run when scheduling is switched on (see PaymentConfig); tests switch it off.
 */
@Component
public class FinanceScheduler {

    private final PayoutService payoutService;
    private final SettlementService settlementService;
    private final Clock clock;

    public FinanceScheduler(PayoutService payoutService, SettlementService settlementService, Clock clock) {
        this.payoutService = payoutService;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    @Scheduled(cron = "${mmts.payment.schedule.payout-cron}", zone = "UTC")
    public void dailyPayout() {
        payoutService.runNow("SCHEDULED", PaymentEvents.SYSTEM);
    }

    @Scheduled(cron = "${mmts.payment.schedule.settlement-cron}", zone = "UTC")
    public void dailySettlementFile() {
        settlementService.export(LocalDate.now(clock), PaymentEvents.SYSTEM);
    }
}
