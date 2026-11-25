package com.mmts.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The drive-eligibility rule and licence state changes, tested without Spring or a database (driver D3). */
class DrivingLicenceRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private DrivingLicence verified(Set<VehicleClass> classes, LocalDate validUntil) {
        DrivingLicence licence = new DrivingLicence("u-1");
        licence.submit("DL-0420190012345", LocalDate.of(1994, 5, 20), NOW);
        licence.markFound(classes, validUntil, LocalDate.of(2020, 1, 1), NOW);
        return licence;
    }

    @Test
    void verifiedLicenceOfRightClassIsEligible() {
        DrivingLicence licence = verified(Set.of(VehicleClass.LMV, VehicleClass.MCWG), LocalDate.of(2031, 5, 20));

        assertThat(licence.eligibilityFor(VehicleClass.LMV, TODAY)).isEqualTo(EligibilityReason.VERIFIED);
        assertThat(licence.eligibilityFor(VehicleClass.MCWG, TODAY)).isEqualTo(EligibilityReason.VERIFIED);
    }

    @Test
    void wrongVehicleClassIsRefused() {
        DrivingLicence scooterOnly = verified(Set.of(VehicleClass.MCWG), LocalDate.of(2030, 1, 10));

        assertThat(scooterOnly.eligibilityFor(VehicleClass.LMV, TODAY)).isEqualTo(EligibilityReason.CLASS_NOT_COVERED);
    }

    @Test
    void licencePastItsDateIsRefusedEvenBeforeTheNightlyJob() {
        DrivingLicence licence = verified(Set.of(VehicleClass.LMV), LocalDate.of(2026, 11, 14));

        assertThat(licence.getStatus()).isEqualTo(LicenceStatus.VERIFIED);   // the job has not run yet
        assertThat(licence.eligibilityFor(VehicleClass.LMV, TODAY)).isEqualTo(EligibilityReason.EXPIRED);
    }

    @Test
    void licenceIsStillValidOnItsExpiryDay() {
        DrivingLicence licence = verified(Set.of(VehicleClass.LMV), TODAY);

        assertThat(licence.eligibilityFor(VehicleClass.LMV, TODAY)).isEqualTo(EligibilityReason.VERIFIED);
    }

    @Test
    void pendingRejectedAndExpiredLicencesAreRefused() {
        DrivingLicence pending = new DrivingLicence("u-2");
        pending.submit("DL-0420190012345", LocalDate.of(1994, 5, 20), NOW);
        assertThat(pending.eligibilityFor(VehicleClass.LMV, TODAY)).isEqualTo(EligibilityReason.PENDING_VERIFICATION);

        DrivingLicence rejected = new DrivingLicence("u-3");
        rejected.submit("TN-0919990011111", LocalDate.of(1990, 1, 1), NOW);
        rejected.markRejected("NOT_FOUND", NOW);
        assertThat(rejected.eligibilityFor(VehicleClass.LMV, TODAY)).isEqualTo(EligibilityReason.REJECTED);

        DrivingLicence expired = verified(Set.of(VehicleClass.LMV), LocalDate.of(2027, 1, 1));
        expired.markExpired(NOW);
        assertThat(expired.eligibilityFor(VehicleClass.LMV, TODAY)).isEqualTo(EligibilityReason.EXPIRED);
    }

    @Test
    void licenceFoundButAlreadyOutOfDateBecomesExpiredAndDateOfBirthIsCleared() {
        DrivingLicence licence = new DrivingLicence("u-4");
        licence.submit("MH-1220100098765", LocalDate.of(1985, 3, 31), NOW);

        licence.markFound(Set.of(VehicleClass.LMV), LocalDate.of(2024, 3, 31), TODAY, NOW);

        assertThat(licence.getStatus()).isEqualTo(LicenceStatus.EXPIRED);
        assertThat(licence.getDateOfBirth()).isNull();   // only needed for the lookup (data minimisation)
    }
}
