package com.mmts.contract.domain;

import jakarta.persistence.Embeddable;

/**
 * Money side of a contract (M7.3). The Payment service applies these when it splits a fare and pays the TSP;
 * the Booking service applies the cancellation charge.
 *
 * @param commissionPercent   platform's share of every fare earned by this TSP (0-100)
 * @param bookingFeePaise     fixed platform fee per leg booked with this TSP
 * @param cancellationFeePaise charged to the traveller when a confirmed leg of this TSP is cancelled
 * @param payoutCycleDays     how often the TSP is paid (e.g. 7 = weekly)
 */
@Embeddable
public record CommercialTerms(double commissionPercent,
                              long bookingFeePaise,
                              long cancellationFeePaise,
                              int payoutCycleDays) {
}
