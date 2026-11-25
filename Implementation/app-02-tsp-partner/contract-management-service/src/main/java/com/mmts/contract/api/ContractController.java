package com.mmts.contract.api;

import com.mmts.contract.api.ContractDtos.ActiveTermsView;
import com.mmts.contract.api.ContractDtos.ContractView;
import com.mmts.contract.api.ContractDtos.CreateContractRequest;
import com.mmts.contract.api.ContractDtos.ReasonRequest;
import com.mmts.contract.api.ContractDtos.RegisterTspRequest;
import com.mmts.contract.api.ContractDtos.SlaMetricsRequest;
import com.mmts.contract.api.ContractDtos.SlaResultView;
import com.mmts.contract.api.ContractDtos.TspView;
import com.mmts.contract.common.security.AuthenticatedUser;
import com.mmts.contract.service.ContractService;
import com.mmts.contract.service.SlaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TSP Contract Management API (M7).
 * OPERATOR = a TSP's own staff (partner portal, APP-02); ADMIN = MMTS partner team (approves, from the APP-03 portal);
 * SERVICE = other MMTS services asking for active terms or sending SLA metrics.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "TSP contracts", description = "Business onboarding, contract life cycle, commercial terms, SLA")
public class ContractController {

    private final ContractService contracts;
    private final SlaService sla;

    public ContractController(ContractService contracts, SlaService sla) {
        this.contracts = contracts;
        this.sla = sla;
    }

    // ---------- TSPs ----------

    @PostMapping("/tsps")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "M7.1 Register a TSP (company, KYC documents, bank account reference, modes, operator users)")
    public TspView register(@Valid @RequestBody RegisterTspRequest req, @AuthenticationPrincipal AuthenticatedUser caller) {
        return TspView.of(contracts.registerTsp(req, caller));
    }

    @GetMapping("/tsps/{tspId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "A registered TSP (platform staff, services, or the TSP's own operators)")
    public TspView tsp(@PathVariable String tspId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return TspView.of(contracts.tsp(tspId, caller));
    }

    @GetMapping("/tsps/{tspId}/contracts")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "M7.7 Every contract revision of a TSP, newest first")
    public List<ContractView> history(@PathVariable String tspId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return contracts.history(tspId, caller).stream().map(ContractView::of).toList();
    }

    @GetMapping("/tsps/{tspId}/active-terms")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN','FINANCE')")
    @Operation(summary = "Is this TSP's contract active today, and on which terms? (Booking, Payment, Journey)")
    public ActiveTermsView activeTerms(@PathVariable String tspId) {
        return contracts.activeTerms(tspId);
    }

    // ---------- contracts ----------

    @PostMapping("/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "M7.2-M7.4 Draft a contract: modes, area, validity, commercial terms, service levels")
    public ContractView create(@Valid @RequestBody CreateContractRequest req, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.create(req, caller));
    }

    @GetMapping("/contracts/{contractId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One contract")
    public ContractView get(@PathVariable String contractId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.get(contractId, caller));
    }

    @PostMapping("/contracts/{contractId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "DRAFT -> UNDER_REVIEW")
    public ContractView submit(@PathVariable String contractId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.submit(contractId, caller));
    }

    @PostMapping("/contracts/{contractId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "UNDER_REVIEW -> ACTIVE (partner team). Publishes TSP_CONTRACT_ACTIVATED; ends older revisions")
    public ContractView approve(@PathVariable String contractId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.approve(contractId, caller));
    }

    @PostMapping("/contracts/{contractId}/send-back")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "UNDER_REVIEW -> DRAFT with a reason")
    public ContractView sendBack(@PathVariable String contractId, @Valid @RequestBody ReasonRequest req,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.sendBack(contractId, req.reason(), caller));
    }

    @PostMapping("/contracts/{contractId}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "ACTIVE -> SUSPENDED. Publishes TSP_CONTRACT_SUSPENDED (the TSP gets no new bookings)")
    public ContractView suspend(@PathVariable String contractId, @Valid @RequestBody ReasonRequest req,
                                @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.suspend(contractId, req.reason(), caller));
    }

    @PostMapping("/contracts/{contractId}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "SUSPENDED -> ACTIVE. Publishes TSP_CONTRACT_ACTIVATED")
    public ContractView reactivate(@PathVariable String contractId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.reactivate(contractId, caller));
    }

    @PostMapping("/contracts/{contractId}/terminate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Off-board: -> TERMINATED. Publishes TSP_CONTRACT_TERMINATED when it was live")
    public ContractView terminate(@PathVariable String contractId, @Valid @RequestBody ReasonRequest req,
                                  @AuthenticationPrincipal AuthenticatedUser caller) {
        return ContractView.of(contracts.terminate(contractId, req.reason(), caller));
    }

    // ---------- SLA ----------

    @PostMapping("/sla/measurements")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "M7.5 One period's metrics of a TSP. Breach -> TSP_SLA_BREACHED; repeated breaches suspend")
    public SlaResultView measure(@Valid @RequestBody SlaMetricsRequest req) {
        SlaService.Evaluation e = sla.record(req);
        return SlaResultView.of(e.measurement(), e.contract());
    }

    @GetMapping("/contracts/{contractId}/sla")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','REGULATOR','OPERATOR')")
    @Operation(summary = "SLA history of a contract, newest first")
    public List<SlaResultView> slaHistory(@PathVariable String contractId, @AuthenticationPrincipal AuthenticatedUser caller) {
        var contract = contracts.get(contractId, caller);
        return sla.history(contractId).stream().map(m -> SlaResultView.of(m, contract)).toList();
    }
}
