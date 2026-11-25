package com.mmts.contract.domain;

import com.mmts.contract.common.error.ApiException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One contract between MMTS and a TSP (M7.2): what it may offer, where, until when, on which terms and service levels.
 * Amending a contract creates a new contract with a higher {@code revision}; the old one is TERMINATED, so the
 * full history stays (M7.7).
 */
@Entity
@Table(name = "contract")
public class Contract {

    @Id
    private String contractId;

    @Column(nullable = false)
    private String tspId;

    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus status;

    private String statusReason;

    private LocalDate validFrom;
    private LocalDate validTo;
    private String serviceArea;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "contract_mode")
    @Column(name = "mode")
    private Set<String> modes = new LinkedHashSet<>();

    @Embedded
    private CommercialTerms terms;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "maxCancellationRatePct", column = @Column(name = "sla_max_cancellation_pct")),
            @AttributeOverride(name = "minAvailabilityPct", column = @Column(name = "sla_min_availability_pct")),
            @AttributeOverride(name = "maxResponseMinutes", column = @Column(name = "sla_max_response_min")),
            @AttributeOverride(name = "breachesBeforeSuspension", column = @Column(name = "sla_breaches_to_suspend"))})
    private ServiceLevels serviceLevels;

    private int consecutiveBreaches;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;  // optimistic locking: two admins cannot approve and suspend at the same moment

    protected Contract() {
    }

    public Contract(String contractId, String tspId, int revision, LocalDate validFrom, LocalDate validTo,
                    String serviceArea, Set<String> modes, CommercialTerms terms, ServiceLevels serviceLevels, Instant now) {
        this.contractId = contractId;
        this.tspId = tspId;
        this.revision = revision;
        this.status = ContractStatus.DRAFT;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.serviceArea = serviceArea;
        this.modes = new LinkedHashSet<>(modes);
        this.terms = terms;
        this.serviceLevels = serviceLevels;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Moves to the next state or refuses with 409 INVALID_TRANSITION. Returns the previous state. */
    public ContractStatus moveTo(ContractStatus next, String reason, Instant now) {
        if (!status.canMoveTo(next)) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    "Contract " + contractId + " cannot go from " + status + " to " + next);
        }
        ContractStatus previous = status;
        status = next;
        statusReason = reason;
        updatedAt = now;
        if (next == ContractStatus.ACTIVE) {
            consecutiveBreaches = 0;
        }
        return previous;
    }

    /** Records the result of one SLA period; returns true when the contract should now be suspended. */
    public boolean recordSlaResult(boolean breached) {
        consecutiveBreaches = breached ? consecutiveBreaches + 1 : 0;
        return breached && status == ContractStatus.ACTIVE
                && consecutiveBreaches >= serviceLevels.breachesBeforeSuspension();
    }

    public boolean isActiveOn(LocalDate day) {
        return status == ContractStatus.ACTIVE && !day.isBefore(validFrom) && !day.isAfter(validTo);
    }

    public String getContractId() { return contractId; }
    public String getTspId() { return tspId; }
    public int getRevision() { return revision; }
    public ContractStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getServiceArea() { return serviceArea; }
    public Set<String> getModes() { return Set.copyOf(modes); }
    public CommercialTerms getTerms() { return terms; }
    public ServiceLevels getServiceLevels() { return serviceLevels; }
    public int getConsecutiveBreaches() { return consecutiveBreaches; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
