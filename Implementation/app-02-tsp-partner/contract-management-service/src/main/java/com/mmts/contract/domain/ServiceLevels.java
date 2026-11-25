package com.mmts.contract.domain;

import jakarta.persistence.Embeddable;

/**
 * Service levels the TSP promises (M7.4). Checked against the metrics of every measurement period (M7.5).
 *
 * @param maxCancellationRatePct  TSP-side cancellations as a % of confirmed legs, at most this
 * @param minAvailabilityPct      share of the period the TSP's systems answered, at least this
 * @param maxResponseMinutes      average pickup / response time, at most this (0 = not measured for this mode)
 * @param breachesBeforeSuspension consecutive breached periods that suspend the contract automatically
 */
@Embeddable
public record ServiceLevels(double maxCancellationRatePct,
                            double minAvailabilityPct,
                            double maxResponseMinutes,
                            int breachesBeforeSuspension) {
}
