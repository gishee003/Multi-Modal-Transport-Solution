package com.mmts.contract.service;

import com.mmts.contract.api.ContractDtos.SlaMetricsRequest;
import com.mmts.contract.common.error.ApiException;
import com.mmts.contract.common.events.EventPublisher;
import com.mmts.contract.domain.Contract;
import com.mmts.contract.domain.ContractStatus;
import com.mmts.contract.domain.ServiceLevels;
import com.mmts.contract.domain.SlaMeasurement;
import com.mmts.contract.repository.ContractRepository;
import com.mmts.contract.repository.SlaMeasurementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * SLA monitoring (M7.5): compares one period's operational metrics of a TSP with its contract.
 *
 * In production the metrics are aggregated from events (booking.events cancellations, vehicle.events availability,
 * the TSP Integration Hub's response times); in the demo another service or an admin posts them.
 * A breach is published as TSP_SLA_BREACHED; the configured number of consecutive breaches suspends the contract
 * automatically (example from the review: a TSP whose drivers keep cancelling is taken off the platform).
 */
@Service
public class SlaService {

    private final ContractRepository contracts;
    private final SlaMeasurementRepository measurements;
    private final ContractService contractService;
    private final EventPublisher events;
    private final Clock clock;

    public SlaService(ContractRepository contracts, SlaMeasurementRepository measurements,
                      ContractService contractService, EventPublisher events, Clock clock) {
        this.contracts = contracts;
        this.measurements = measurements;
        this.contractService = contractService;
        this.events = events;
        this.clock = clock;
    }

    /** Result of one evaluation, plus the contract it was measured against. */
    public record Evaluation(SlaMeasurement measurement, Contract contract) {
    }

    @Transactional
    public Evaluation record(SlaMetricsRequest req) {
        String tspId = req.tspId().toUpperCase(Locale.ROOT);
        Contract contract = contracts.findFirstByTspIdAndStatusOrderByRevisionDesc(tspId, ContractStatus.ACTIVE)
                .or(() -> contracts.findFirstByTspIdAndStatusOrderByRevisionDesc(tspId, ContractStatus.SUSPENDED))
                .orElseThrow(() -> ApiException.notFound("NO_LIVE_CONTRACT", "TSP " + tspId + " has no active contract"));

        Optional<SlaMeasurement> existing = measurements.findByContractIdAndPeriod(contract.getContractId(), req.period());
        if (existing.isPresent()) {
            return new Evaluation(existing.get(), contract);  // same period again: same answer, no second count
        }

        ServiceLevels sla = contract.getServiceLevels();
        double cancellationRate = req.legsConfirmed() == 0 ? 0
                : Math.round(10000.0 * req.legsCancelledByTsp() / req.legsConfirmed()) / 100.0;
        List<String> reasons = new ArrayList<>();
        if (cancellationRate > sla.maxCancellationRatePct()) {
            reasons.add("CANCELLATION_RATE");
        }
        if (req.availabilityPct() < sla.minAvailabilityPct()) {
            reasons.add("AVAILABILITY");
        }
        if (sla.maxResponseMinutes() > 0 && req.avgResponseMinutes() > sla.maxResponseMinutes()) {
            reasons.add("RESPONSE_TIME");
        }
        boolean breached = !reasons.isEmpty();

        SlaMeasurement m = measurements.save(new SlaMeasurement(contract.getContractId(), req.period(),
                req.legsConfirmed(), req.legsCancelledByTsp(), req.availabilityPct(), req.avgResponseMinutes(),
                cancellationRate, breached, String.join(",", reasons), clock.instant()));

        boolean suspend = contract.recordSlaResult(breached);
        if (breached) {
            events.publish(ContractService.CONTRACT_TOPIC, "TSP_SLA_BREACHED",
                    Map.of("tspId", tspId, "contractId", contract.getContractId(), "period", req.period(),
                            "reasons", reasons, "consecutiveBreaches", contract.getConsecutiveBreaches()));
        }
        if (suspend) {
            contractService.suspendInternal(contract, "SLA_BREACH");
            contractService.auditSystem("CONTRACT_SUSPENDED", "CONTRACT", contract.getContractId(), "SLA_BREACH");
        }
        return new Evaluation(m, contract);
    }

    @Transactional(readOnly = true)
    public List<SlaMeasurement> history(String contractId) {
        contractService.require(contractId);
        return measurements.findByContractIdOrderByMeasuredAtDesc(contractId);
    }
}
