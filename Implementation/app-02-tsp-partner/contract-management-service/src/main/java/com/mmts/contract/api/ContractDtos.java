package com.mmts.contract.api;

import com.mmts.contract.domain.CommercialTerms;
import com.mmts.contract.domain.Contract;
import com.mmts.contract.domain.ServiceLevels;
import com.mmts.contract.domain.SlaMeasurement;
import com.mmts.contract.domain.Tsp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Request and response shapes of the TSP Contract Management API. */
public final class ContractDtos {

    private ContractDtos() {
    }

    // ---------- TSP registration (M7.1) ----------

    public record RegisterTspRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{2,20}") String tspId,
            @NotBlank String legalName,
            @NotBlank String registrationNumber,
            @NotBlank String bankAccountRef,
            @NotEmpty Set<@NotBlank String> modes,
            @NotEmpty Set<@NotBlank String> operators,
            @NotEmpty Set<@NotBlank String> documents) {
    }

    public record TspView(String tspId, String legalName, String registrationNumber, Set<String> modes,
                          Set<String> operators, Set<String> documents, Instant registeredAt) {
        static TspView of(Tsp t) {
            return new TspView(t.getTspId(), t.getLegalName(), t.getRegistrationNumber(), t.getModes(),
                    t.getOperators(), t.getDocuments(), t.getRegisteredAt());
        }
    }

    // ---------- Contract (M7.2 - M7.4) ----------

    public record TermsRequest(@DecimalMin("0") @DecimalMax("100") double commissionPercent,
                               @PositiveOrZero long bookingFeePaise,
                               @PositiveOrZero long cancellationFeePaise,
                               @Min(1) int payoutCycleDays) {
        public CommercialTerms toTerms() {
            return new CommercialTerms(commissionPercent, bookingFeePaise, cancellationFeePaise, payoutCycleDays);
        }
    }

    public record ServiceLevelsRequest(@DecimalMin("0") @DecimalMax("100") double maxCancellationRatePct,
                                       @DecimalMin("0") @DecimalMax("100") double minAvailabilityPct,
                                       @PositiveOrZero double maxResponseMinutes,
                                       @Min(1) int breachesBeforeSuspension) {
        public ServiceLevels toServiceLevels() {
            return new ServiceLevels(maxCancellationRatePct, minAvailabilityPct, maxResponseMinutes, breachesBeforeSuspension);
        }
    }

    public record CreateContractRequest(@NotBlank String tspId,
                                        @NotNull LocalDate validFrom,
                                        @NotNull LocalDate validTo,
                                        @NotBlank String serviceArea,
                                        @NotEmpty Set<@NotBlank String> modes,
                                        @NotNull @Valid TermsRequest terms,
                                        @NotNull @Valid ServiceLevelsRequest serviceLevels) {
    }

    public record ReasonRequest(@NotBlank String reason) {
    }

    public record ContractView(String contractId, String tspId, int revision, String status, String statusReason,
                               LocalDate validFrom, LocalDate validTo, String serviceArea, Set<String> modes,
                               CommercialTerms terms, ServiceLevels serviceLevels, int consecutiveBreaches,
                               Instant updatedAt) {
        static ContractView of(Contract c) {
            return new ContractView(c.getContractId(), c.getTspId(), c.getRevision(), c.getStatus().name(),
                    c.getStatusReason(), c.getValidFrom(), c.getValidTo(), c.getServiceArea(), c.getModes(),
                    c.getTerms(), c.getServiceLevels(), c.getConsecutiveBreaches(), c.getUpdatedAt());
        }
    }

    /** What other services ask before they offer, price or pay a TSP (read by the portals; services keep a copy from contract.events). */
    public record ActiveTermsView(String tspId, String contractId, boolean active, Set<String> modes,
                                  String serviceArea, CommercialTerms terms) {
    }

    // ---------- SLA (M7.5) ----------

    public record SlaMetricsRequest(@NotBlank String tspId,
                                    @NotBlank String period,
                                    @PositiveOrZero long legsConfirmed,
                                    @PositiveOrZero long legsCancelledByTsp,
                                    @DecimalMin("0") @DecimalMax("100") double availabilityPct,
                                    @PositiveOrZero double avgResponseMinutes) {
    }

    public record SlaResultView(String contractId, String period, double cancellationRatePct, double availabilityPct,
                                double avgResponseMinutes, boolean breached, String breachReasons,
                                int consecutiveBreaches, String contractStatus) {
        static SlaResultView of(SlaMeasurement m, Contract c) {
            return new SlaResultView(m.getContractId(), m.getPeriod(), m.getCancellationRatePct(),
                    m.getAvailabilityPct(), m.getAvgResponseMinutes(), m.isBreached(), m.getBreachReasons(),
                    c.getConsecutiveBreaches(), c.getStatus().name());
        }
    }
}
