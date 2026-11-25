package com.mmts.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * The measured service level of one TSP for one period, and whether it breached the contract (M7.5).
 * One row per contract and period: sending the same period again is answered with the stored result.
 */
@Entity
@Table(name = "sla_measurement", uniqueConstraints = @UniqueConstraint(columnNames = {"contractId", "period"}))
public class SlaMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String contractId;
    @Column(nullable = false)
    private String period;          // e.g. 2026-W48 or 2026-11-25

    private long legsConfirmed;
    private long legsCancelledByTsp;
    private double availabilityPct;
    private double avgResponseMinutes;

    private double cancellationRatePct;
    private boolean breached;
    @Column(length = 500)
    private String breachReasons;   // comma separated, empty when met
    private Instant measuredAt;

    protected SlaMeasurement() {
    }

    public SlaMeasurement(String contractId, String period, long legsConfirmed, long legsCancelledByTsp,
                          double availabilityPct, double avgResponseMinutes, double cancellationRatePct,
                          boolean breached, String breachReasons, Instant measuredAt) {
        this.contractId = contractId;
        this.period = period;
        this.legsConfirmed = legsConfirmed;
        this.legsCancelledByTsp = legsCancelledByTsp;
        this.availabilityPct = availabilityPct;
        this.avgResponseMinutes = avgResponseMinutes;
        this.cancellationRatePct = cancellationRatePct;
        this.breached = breached;
        this.breachReasons = breachReasons;
        this.measuredAt = measuredAt;
    }

    public Long getId() { return id; }
    public String getContractId() { return contractId; }
    public String getPeriod() { return period; }
    public long getLegsConfirmed() { return legsConfirmed; }
    public long getLegsCancelledByTsp() { return legsCancelledByTsp; }
    public double getAvailabilityPct() { return availabilityPct; }
    public double getAvgResponseMinutes() { return avgResponseMinutes; }
    public double getCancellationRatePct() { return cancellationRatePct; }
    public boolean isBreached() { return breached; }
    public String getBreachReasons() { return breachReasons; }
    public Instant getMeasuredAt() { return measuredAt; }
}
