package com.mmts.contract.service;

import com.mmts.contract.api.ContractDtos.ActiveTermsView;
import com.mmts.contract.api.ContractDtos.CreateContractRequest;
import com.mmts.contract.api.ContractDtos.RegisterTspRequest;
import com.mmts.contract.common.error.ApiException;
import com.mmts.contract.common.events.EventPublisher;
import com.mmts.contract.common.security.AuthenticatedUser;
import com.mmts.contract.common.security.Role;
import com.mmts.contract.domain.Contract;
import com.mmts.contract.domain.ContractStatus;
import com.mmts.contract.domain.Tsp;
import com.mmts.contract.repository.ContractRepository;
import com.mmts.contract.repository.TspRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Business onboarding of TSPs and the life of their contracts (M7.1 - M7.4, M7.6, M7.7).
 *
 * Every change of a contract's state is published on contract.events, so the other parts react without being
 * called (Observer): the TSP Integration Hub switches the TSP's connector on or off, the Payment service uses the
 * commercial terms when it splits a fare, the Booking service uses the cancellation charge, and APP-03 reports on it.
 */
@Service
public class ContractService {

    public static final String CONTRACT_TOPIC = "contract.events";

    private final TspRepository tsps;
    private final ContractRepository contracts;
    private final EventPublisher events;
    private final Clock clock;

    public ContractService(TspRepository tsps, ContractRepository contracts, EventPublisher events, Clock clock) {
        this.tsps = tsps;
        this.contracts = contracts;
        this.events = events;
        this.clock = clock;
    }

    // ---------- M7.1 register a TSP ----------

    @Transactional
    public Tsp registerTsp(RegisterTspRequest req, AuthenticatedUser caller) {
        String id = req.tspId().toUpperCase(Locale.ROOT);
        if (!caller.hasRole(Role.ADMIN) && !req.operators().contains(caller.userId())) {
            throw ApiException.forbidden("NOT_YOUR_TSP", "An operator can only register a TSP they act for");
        }
        if (tsps.existsById(id)) {
            throw ApiException.conflict("TSP_EXISTS", "TSP " + id + " is already registered");
        }
        Tsp tsp = tsps.save(new Tsp(id, req.legalName(), req.registrationNumber(), req.bankAccountRef(),
                upper(req.modes()), req.operators(), req.documents(), clock.instant()));
        audit(caller, "TSP_REGISTERED", "TSP", id, "OK");
        return tsp;
    }

    @Transactional(readOnly = true)
    public Tsp tsp(String tspId, AuthenticatedUser caller) {
        Tsp tsp = requireTsp(tspId);
        requireStaffOrOperator(tsp, caller);
        return tsp;
    }

    // ---------- M7.2 - M7.4 draft a contract ----------

    @Transactional
    public Contract create(CreateContractRequest req, AuthenticatedUser caller) {
        Tsp tsp = requireTsp(req.tspId());
        requireAdminOrOperator(tsp, caller);
        if (!req.validTo().isAfter(req.validFrom())) {
            throw ApiException.badRequest("INVALID_DATES", "validTo must be after validFrom");
        }
        Set<String> modes = upper(req.modes());
        if (!tsp.getModes().containsAll(modes)) {
            throw ApiException.badRequest("MODE_NOT_REGISTERED",
                    "The contract can only cover modes the TSP registered: " + tsp.getModes());
        }
        int revision = contracts.findByTspIdOrderByRevisionDesc(tsp.getTspId()).stream()
                .mapToInt(Contract::getRevision).max().orElse(0) + 1;
        String contractId = "c-" + tsp.getTspId().toLowerCase(Locale.ROOT) + "-" + revision;
        Contract contract = contracts.save(new Contract(contractId, tsp.getTspId(), revision, req.validFrom(),
                req.validTo(), req.serviceArea(), modes, req.terms().toTerms(),
                req.serviceLevels().toServiceLevels(), clock.instant()));
        audit(caller, "CONTRACT_DRAFTED", "CONTRACT", contractId, "OK");
        return contract;
    }

    // ---------- M7.6 life cycle ----------

    @Transactional
    public Contract submit(String contractId, AuthenticatedUser caller) {
        Contract c = require(contractId);
        requireAdminOrOperator(requireTsp(c.getTspId()), caller);
        c.moveTo(ContractStatus.UNDER_REVIEW, "Submitted for review", clock.instant());
        audit(caller, "CONTRACT_SUBMITTED", "CONTRACT", contractId, "OK");
        return c;
    }

    /** Partner team approves: the contract becomes ACTIVE and any earlier revision of the same TSP ends. */
    @Transactional
    public Contract approve(String contractId, AuthenticatedUser caller) {
        Contract c = require(contractId);
        c.moveTo(ContractStatus.ACTIVE, "Approved by " + caller.userId(), clock.instant());
        for (Contract older : contracts.findByTspIdOrderByRevisionDesc(c.getTspId())) {
            boolean live = older.getStatus() == ContractStatus.ACTIVE || older.getStatus() == ContractStatus.SUSPENDED;
            if (!older.getContractId().equals(contractId) && live) {
                older.moveTo(ContractStatus.TERMINATED, "SUPERSEDED by " + contractId, clock.instant());
            }
        }
        publishActivated(c);
        audit(caller, "CONTRACT_APPROVED", "CONTRACT", contractId, "OK");
        return c;
    }

    @Transactional
    public Contract sendBack(String contractId, String reason, AuthenticatedUser caller) {
        Contract c = require(contractId);
        c.moveTo(ContractStatus.DRAFT, reason, clock.instant());
        audit(caller, "CONTRACT_SENT_BACK", "CONTRACT", contractId, reason);
        return c;
    }

    @Transactional
    public Contract suspend(String contractId, String reason, AuthenticatedUser caller) {
        Contract c = require(contractId);
        suspendInternal(c, reason);
        audit(caller, "CONTRACT_SUSPENDED", "CONTRACT", contractId, reason);
        return c;
    }

    @Transactional
    public Contract reactivate(String contractId, AuthenticatedUser caller) {
        Contract c = require(contractId);
        if (c.getStatus() != ContractStatus.SUSPENDED) {
            throw ApiException.conflict("INVALID_TRANSITION", "Only a SUSPENDED contract can be re-activated");
        }
        c.moveTo(ContractStatus.ACTIVE, "Re-activated by " + caller.userId(), clock.instant());
        publishActivated(c);
        audit(caller, "CONTRACT_REACTIVATED", "CONTRACT", contractId, "OK");
        return c;
    }

    /** Off-boarding: the TSP can no longer be offered; already confirmed legs are honoured by the Booking service. */
    @Transactional
    public Contract terminate(String contractId, String reason, AuthenticatedUser caller) {
        Contract c = require(contractId);
        ContractStatus before = c.moveTo(ContractStatus.TERMINATED, reason, clock.instant());
        if (before == ContractStatus.ACTIVE || before == ContractStatus.SUSPENDED) {
            events.publish(CONTRACT_TOPIC, "TSP_CONTRACT_TERMINATED",
                    Map.of("tspId", c.getTspId(), "contractId", c.getContractId(), "reason", reason));
        }
        audit(caller, "CONTRACT_TERMINATED", "CONTRACT", contractId, reason);
        return c;
    }

    /** Used by the SLA monitor too (automatic suspension after repeated breaches). */
    void suspendInternal(Contract c, String reason) {
        c.moveTo(ContractStatus.SUSPENDED, reason, clock.instant());
        events.publish(CONTRACT_TOPIC, "TSP_CONTRACT_SUSPENDED",
                Map.of("tspId", c.getTspId(), "contractId", c.getContractId(), "reason", reason));
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public Contract get(String contractId, AuthenticatedUser caller) {
        Contract c = require(contractId);
        requireStaffOrOperator(requireTsp(c.getTspId()), caller);
        return c;
    }

    @Transactional(readOnly = true)
    public List<Contract> history(String tspId, AuthenticatedUser caller) {
        Tsp tsp = requireTsp(tspId);
        requireStaffOrOperator(tsp, caller);
        return contracts.findByTspIdOrderByRevisionDesc(tsp.getTspId());
    }

    /** Active terms (read by the portals; services keep a copy from contract.events): what Booking / Payment / Journey ask before they offer, price or pay a TSP. */
    @Transactional(readOnly = true)
    public ActiveTermsView activeTerms(String tspId) {
        String id = tspId.toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")));
        return contracts.findFirstByTspIdAndStatusOrderByRevisionDesc(id, ContractStatus.ACTIVE)
                .filter(c -> c.isActiveOn(today))
                .map(c -> new ActiveTermsView(id, c.getContractId(), true, c.getModes(), c.getServiceArea(), c.getTerms()))
                .orElse(new ActiveTermsView(id, null, false, Set.of(), null, null));
    }

    // ---------- helpers ----------

    Contract require(String contractId) {
        return contracts.findById(contractId)
                .orElseThrow(() -> ApiException.notFound("CONTRACT_NOT_FOUND", "No contract with id " + contractId));
    }

    private Tsp requireTsp(String tspId) {
        return tsps.findById(tspId.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("TSP_NOT_FOUND", "No TSP with id " + tspId));
    }

    private static void requireAdminOrOperator(Tsp tsp, AuthenticatedUser caller) {
        if (!caller.hasRole(Role.ADMIN) && !tsp.isOperatedBy(caller.userId())) {
            throw ApiException.forbidden("NOT_YOUR_TSP", "You do not act for TSP " + tsp.getTspId());
        }
    }

    /** Platform staff and other services may read any contract; an operator only their own TSP's. */
    private static void requireStaffOrOperator(Tsp tsp, AuthenticatedUser caller) {
        boolean staff = caller.hasRole(Role.ADMIN) || caller.hasRole(Role.FINANCE) || caller.hasRole(Role.SUPPORT)
                || caller.hasRole(Role.REGULATOR) || caller.hasRole(Role.SERVICE);
        if (!staff && !tsp.isOperatedBy(caller.userId())) {
            throw ApiException.forbidden("NOT_YOUR_TSP", "You do not act for TSP " + tsp.getTspId());
        }
    }

    private void publishActivated(Contract c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tspId", c.getTspId());
        payload.put("contractId", c.getContractId());
        payload.put("revision", c.getRevision());
        payload.put("modes", List.copyOf(c.getModes()));
        payload.put("serviceArea", c.getServiceArea());
        payload.put("validTo", c.getValidTo().toString());
        payload.put("commissionPercent", c.getTerms().commissionPercent());
        payload.put("bookingFeePaise", c.getTerms().bookingFeePaise());
        payload.put("cancellationFeePaise", c.getTerms().cancellationFeePaise());
        payload.put("payoutCycleDays", c.getTerms().payoutCycleDays());
        events.publish(CONTRACT_TOPIC, "TSP_CONTRACT_ACTIVATED", payload);
    }

    private void audit(AuthenticatedUser actor, String action, String type, String id, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorId", actor == null ? "system" : actor.userId());
        payload.put("actorRoles", actor == null ? List.of() : actor.roles().stream().map(Enum::name).sorted().toList());
        payload.put("action", action);
        payload.put("resourceType", type);
        payload.put("resourceId", id);
        payload.put("outcome", outcome);
        events.publish("audit.events", "AUDIT", payload);
    }

    void auditSystem(String action, String type, String id, String outcome) {
        audit(null, action, type, id, outcome);
    }

    private static Set<String> upper(Set<String> values) {
        Set<String> out = new HashSet<>();
        values.forEach(v -> out.add(v.toUpperCase(Locale.ROOT)));
        return out;
    }
}
