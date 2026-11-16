package com.mmts.identity.service;

import com.mmts.identity.IdentityTestBase;
import com.mmts.identity.api.dto.LicenceDtos.SubmitLicenceRequest;
import com.mmts.identity.common.events.DomainEvent;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.DrivingLicence;
import com.mmts.identity.domain.LicenceStatus;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.domain.VehicleClass;
import com.mmts.identity.repository.DrivingLicenceRepository;
import com.mmts.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Licence verification, the expiry job and delete-my-data, run against the real application and H2 database.
 * The stub DigiLocker decides the outcome from the sample licence numbers (see StubDigiLockerAdapter).
 */
class LicenceServiceTest extends IdentityTestBase {

    @Autowired
    LicenceService licenceService;

    @Autowired
    AccountService accountService;

    @Autowired
    DrivingLicenceRepository licences;

    @Autowired
    UserAccountRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private AuthenticatedUser newTraveller() throws Exception {
        String id = register(freshMobile(), "Secret@123", "TRAVELLER");
        return new AuthenticatedUser(id, Set.of(Role.TRAVELLER));
    }

    private void submit(AuthenticatedUser user, String number, String dob) {
        licenceService.submit(user, new SubmitLicenceRequest(number, LocalDate.parse(dob), true));
    }

    @Test
    void validLicenceBecomesVerifiedAndPublishesLicenceVerified() throws Exception {
        AuthenticatedUser user = newTraveller();

        submit(user, "DL-0420190012345", "1994-05-20");

        DrivingLicence licence = licences.findByUserId(user.userId()).orElseThrow();
        assertThat(licence.getStatus()).isEqualTo(LicenceStatus.VERIFIED);
        assertThat(licence.getVehicleClasses()).containsExactlyInAnyOrder(VehicleClass.LMV, VehicleClass.MCWG);
        assertThat(licence.getExpiresOn()).isEqualTo(LocalDate.of(2031, 5, 20));
        assertThat(licence.getDateOfBirth()).isNull();

        DomainEvent event = eventsOfType("licence.events", "LICENCE_VERIFIED").get(0);
        assertThat(event.payload()).containsEntry("userId", user.userId())
                .containsEntry("expiresOn", "2031-05-20")
                .containsEntry("vehicleClasses", List.of("LMV", "MCWG"));
    }

    @Test
    void unknownLicenceIsRejectedAndPublishesLicenceRejected() throws Exception {
        AuthenticatedUser user = newTraveller();

        submit(user, "TN-0919990011111", "1990-01-01");

        assertThat(licences.findByUserId(user.userId()).orElseThrow().getStatus()).isEqualTo(LicenceStatus.REJECTED);
        DomainEvent event = eventsOfType("licence.events", "LICENCE_REJECTED").get(0);
        assertThat(event.payload()).containsEntry("userId", user.userId()).containsEntry("reason", "NOT_FOUND");
    }

    @Test
    void wrongDateOfBirthIsRejectedAsDetailsMismatch() throws Exception {
        AuthenticatedUser user = newTraveller();

        submit(user, "DL-0420190012345", "2000-01-01");

        DrivingLicence licence = licences.findByUserId(user.userId()).orElseThrow();
        assertThat(licence.getStatus()).isEqualTo(LicenceStatus.REJECTED);
        assertThat(licence.getRejectionReason()).isEqualTo("DETAILS_MISMATCH");
    }

    @Test
    void outOfDateLicenceBecomesExpiredAndPublishesLicenceExpired() throws Exception {
        AuthenticatedUser user = newTraveller();

        submit(user, "MH-1220100098765", "1985-03-31");

        assertThat(licences.findByUserId(user.userId()).orElseThrow().getStatus()).isEqualTo(LicenceStatus.EXPIRED);
        DomainEvent event = eventsOfType("licence.events", "LICENCE_EXPIRED").get(0);
        assertThat(event.payload()).containsEntry("userId", user.userId()).containsEntry("expiredOn", "2024-03-31");
    }

    @Test
    void licenceNumberIsStoredEncryptedInTheDatabase() throws Exception {
        AuthenticatedUser user = newTraveller();
        submit(user, "KA-0120200054321", "1998-01-15");

        String raw = jdbc.queryForObject("select licence_number from driving_licence where user_id = ?",
                String.class, user.userId());

        assertThat(raw).doesNotContain("0120200054321");                    // the column holds only cipher text
        assertThat(licences.findByUserId(user.userId()).orElseThrow().getLicenceNumber())
                .isEqualTo("KA-0120200054321");                             // the application reads it back
    }

    @Test
    void expiryJobMarksOverdueLicencesAndPublishesLicenceExpired() throws Exception {
        AuthenticatedUser user = newTraveller();
        submit(user, "KA-0520180067890", "1990-11-02");
        // pretend the licence ran out yesterday: write an earlier date straight into the row
        jdbc.update("update driving_licence set expires_on = ? where user_id = ?",
                licenceService.today().minusDays(1), user.userId());

        int expired = licenceService.runExpiryCheck(null).expiredCount();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(licences.findByUserId(user.userId()).orElseThrow().getStatus()).isEqualTo(LicenceStatus.EXPIRED);
        assertThat(eventsOfType("licence.events", "LICENCE_EXPIRED"))
                .anyMatch(e -> e.payload().get("userId").equals(user.userId()));
    }

    @Test
    void deleteMyDataRemovesPersonalFieldsAndTheLicence() throws Exception {
        AuthenticatedUser user = newTraveller();
        submit(user, "DL-0420190012345", "1994-05-20");

        accountService.deleteMyData(user);

        UserAccount stored = users.findById(user.userId()).orElseThrow();
        assertThat(stored.getFullName()).isNull();
        assertThat(stored.getMobile()).isNull();
        assertThat(stored.getEmail()).isNull();
        assertThat(stored.getPasswordHash()).isNull();
        assertThat(licences.findByUserId(user.userId())).isEmpty();
        assertThat(stored.getDeletedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void auditEventsCarryNoPersonalData() throws Exception {
        String mobile = freshMobile();
        String id = register(mobile, "Secret@123", "TRAVELLER");
        submit(new AuthenticatedUser(id, Set.of(Role.TRAVELLER)), "DL-0420190012345", "1994-05-20");

        List<DomainEvent> audits = eventsOfType("audit.events", "AUDIT").stream()
                .filter(e -> id.equals(e.payload().get("resourceId"))).toList();

        assertThat(audits).isNotEmpty();
        assertThat(audits).allSatisfy(e -> {
            assertThat(e.payload()).containsOnlyKeys("actorId", "actorRoles", "action", "resourceType",
                    "resourceId", "outcome");
            assertThat(e.payload().toString()).doesNotContain(mobile).doesNotContain("0420190012345");
        });
    }
}
