package com.mmts.identity.service;

import com.mmts.identity.adapter.IdentityDocumentProvider;
import com.mmts.identity.adapter.IdentityDocumentProvider.LicenceLookup;
import com.mmts.identity.api.dto.LicenceDtos.ExpiryCheckResponse;
import com.mmts.identity.api.dto.LicenceDtos.LicenceResponse;
import com.mmts.identity.api.dto.LicenceDtos.SubmitLicenceRequest;
import com.mmts.identity.common.error.ApiException;
import com.mmts.identity.common.events.EventPublisher;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.DrivingLicence;
import com.mmts.identity.domain.LicenceStatus;
import com.mmts.identity.domain.Masking;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.repository.DrivingLicenceRepository;
import com.mmts.identity.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Driving licence verification (M1.4) and the licence expiry check (M1.6).
 *
 * Submitting only saves the licence as PENDING and hands the DigiLocker call to a background worker
 * (the "licenceVerificationExecutor"). The user gets an answer at once and logins are never slowed by
 * DigiLocker (driver D1). When the check finishes, an event goes out on "licence.events" (Observer pattern),
 * so APP-06 and APP-08 learn about it without asking.
 */
@Service
public class LicenceService {

    public static final String TOPIC = "licence.events";
    private static final Logger log = LoggerFactory.getLogger(LicenceService.class);

    private final DrivingLicenceRepository licences;
    private final UserAccountRepository users;
    private final IdentityDocumentProvider documentProvider;
    private final TaskExecutor verificationExecutor;
    private final EventPublisher events;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public LicenceService(DrivingLicenceRepository licences, UserAccountRepository users,
                          IdentityDocumentProvider documentProvider,
                          @Qualifier("licenceVerificationExecutor") TaskExecutor verificationExecutor,
                          EventPublisher events, AuditService audit, Clock clock,
                          @Value("${mmts.identity.zone}") String zone) {
        this.licences = licences;
        this.users = users;
        this.documentProvider = documentProvider;
        this.verificationExecutor = verificationExecutor;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /** "Today" in India, where licences are issued; used for every expiry comparison. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    // ---------- M1.4 Submit a licence ----------

    /**
     * Saves the licence as PENDING and starts the background check. Deliberately not one big transaction:
     * the PENDING row must be committed before the background worker reads it.
     */
    public LicenceResponse submit(AuthenticatedUser actor, SubmitLicenceRequest request) {
        if (!Boolean.TRUE.equals(request.consent())) {
            throw ApiException.badRequest("CONSENT_REQUIRED",
                    "You must agree that MMTS may fetch your licence from DigiLocker (consent=true)");
        }
        UserAccount user = users.findById(actor.userId()).filter(UserAccount::isActive)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No user with id " + actor.userId()));
        DrivingLicence licence = licences.findByUserId(user.getId()).orElseGet(() -> new DrivingLicence(user.getId()));
        if (licence.getStatus() == LicenceStatus.PENDING) {
            throw ApiException.conflict("VERIFICATION_IN_PROGRESS", "Your licence is already being checked");
        }
        String number = normalise(request.licenceNumber());
        Instant now = clock.instant();
        user.recordLicenceConsent(true, now);
        users.save(user);
        licence.submit(number, request.dateOfBirth(), now);
        DrivingLicence saved = licences.save(licence);
        LicenceResponse answer = toResponse(saved);   // what the user sees now: PENDING

        log.info("Licence {} submitted by user {}", Masking.licence(number), user.getId());
        audit.record(actor.userId(), actor.roles(), "SUBMIT_LICENCE", "DRIVING_LICENCE", user.getId(), "PENDING");

        Long licenceId = saved.getId();
        verificationExecutor.execute(() -> verify(licenceId));
        return answer;
    }

    /**
     * The background step: asks DigiLocker (through the adapter) and records the result.
     * If DigiLocker cannot be reached the licence simply stays PENDING (the user may be told to try later).
     */
    public void verify(Long licenceId) {
        DrivingLicence licence = licences.findById(licenceId).orElse(null);
        if (licence == null || licence.getStatus() != LicenceStatus.PENDING) {
            return;   // deleted meanwhile (delete-my-data) or already checked
        }
        LicenceLookup lookup;
        try {
            lookup = documentProvider.fetchDrivingLicence(licence.getLicenceNumber(), licence.getDateOfBirth());
        } catch (RuntimeException e) {
            log.warn("Licence check for user {} failed, licence stays PENDING: {}", licence.getUserId(), e.toString());
            return;
        }
        Instant now = clock.instant();
        String userId = licence.getUserId();
        switch (lookup.outcome()) {
            case FOUND -> {
                licence.markFound(lookup.vehicleClasses(), lookup.validUntil(), today(), now);
                licences.save(licence);
                if (licence.getStatus() == LicenceStatus.VERIFIED) {
                    events.publish(TOPIC, "LICENCE_VERIFIED", Map.of(
                            "userId", userId,
                            "vehicleClasses", licence.getVehicleClasses().stream().map(Enum::name).toList(),
                            "expiresOn", licence.getExpiresOn().toString()));
                } else {
                    events.publish(TOPIC, "LICENCE_EXPIRED", Map.of(
                            "userId", userId, "expiredOn", licence.getExpiresOn().toString()));
                }
            }
            case NOT_FOUND, DETAILS_MISMATCH -> {
                licence.markRejected(lookup.outcome().name(), now);
                licences.save(licence);
                events.publish(TOPIC, "LICENCE_REJECTED", Map.of("userId", userId, "reason", lookup.outcome().name()));
            }
        }
        log.info("Licence of user {} is now {}", userId, licence.getStatus());
        audit.record("mmts-identity", Set.of(Role.SERVICE), "VERIFY_LICENCE", "DRIVING_LICENCE", userId,
                licence.getStatus().name());
    }

    public LicenceResponse myLicence(String userId) {
        return licences.findByUserId(userId).map(LicenceService::toResponse)
                .orElseThrow(() -> ApiException.notFound("LICENCE_NOT_FOUND", "You have not submitted a licence yet"));
    }

    // ---------- M1.6 Licence expiry check ----------

    /** Runs every night (cron in application.yml, India time). */
    @Scheduled(cron = "${mmts.identity.licence-expiry-cron}", zone = "${mmts.identity.zone}")
    public void nightlyExpiryCheck() {
        runExpiryCheck(null);
    }

    /** Marks every VERIFIED licence whose expiry date has passed as EXPIRED and publishes LICENCE_EXPIRED. */
    public ExpiryCheckResponse runExpiryCheck(AuthenticatedUser actor) {
        LocalDate today = today();
        List<DrivingLicence> overdue = licences.findByStatusAndExpiresOnBefore(LicenceStatus.VERIFIED, today);
        Instant now = clock.instant();
        for (DrivingLicence licence : overdue) {
            licence.markExpired(now);
            licences.save(licence);
            events.publish(TOPIC, "LICENCE_EXPIRED", Map.of(
                    "userId", licence.getUserId(), "expiredOn", licence.getExpiresOn().toString()));
        }
        log.info("Licence expiry check for {}: {} licence(s) marked EXPIRED", today, overdue.size());
        String actorId = actor == null ? "scheduler" : actor.userId();
        Set<Role> roles = actor == null ? Set.of(Role.SERVICE) : actor.roles();
        audit.record(actorId, roles, "RUN_LICENCE_EXPIRY_CHECK", "DRIVING_LICENCE", "all",
                "EXPIRED_" + overdue.size());
        return new ExpiryCheckResponse(today, overdue.size());
    }

    // ---------- helpers ----------

    /** "dl0420190012345" and "DL-0420190012345" are the same licence: upper case, hyphen after the state code. */
    static String normalise(String raw) {
        String s = raw.trim().toUpperCase(Locale.ROOT).replace("-", "");
        return s.substring(0, 2) + "-" + s.substring(2);
    }

    private static LicenceResponse toResponse(DrivingLicence l) {
        return new LicenceResponse(Masking.licence(l.getLicenceNumber()), l.getStatus(), l.getVehicleClasses(),
                l.getExpiresOn(), l.getRejectionReason(), l.getSubmittedAt(), l.getCheckedAt());
    }
}
