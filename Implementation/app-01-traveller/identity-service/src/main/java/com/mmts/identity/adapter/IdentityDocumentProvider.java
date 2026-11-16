package com.mmts.identity.adapter;

import com.mmts.identity.domain.VehicleClass;

import java.time.LocalDate;
import java.util.Set;

/**
 * Port to the government document service that confirms a driving licence (Adapter pattern).
 * Business code depends only on this interface. The demo uses {@link StubDigiLockerAdapter};
 * production would plug in a DigiLocker adapter (consent flow + Pull Document API, doctype DRVLC).
 */
public interface IdentityDocumentProvider {

    /** Looks up a licence. Must only be called after the user has given consent. */
    LicenceLookup fetchDrivingLicence(String licenceNumber, LocalDate dateOfBirth);

    /** What the provider said about the licence. */
    record LicenceLookup(Outcome outcome, Set<VehicleClass> vehicleClasses, LocalDate validUntil) {

        public static LicenceLookup found(Set<VehicleClass> classes, LocalDate validUntil) {
            return new LicenceLookup(Outcome.FOUND, classes, validUntil);
        }

        public static LicenceLookup notFound() {
            return new LicenceLookup(Outcome.NOT_FOUND, Set.of(), null);
        }

        public static LicenceLookup detailsMismatch() {
            return new LicenceLookup(Outcome.DETAILS_MISMATCH, Set.of(), null);
        }
    }

    enum Outcome {
        FOUND,             // licence exists and the date of birth matches
        NOT_FOUND,         // no such licence number in the issuing records
        DETAILS_MISMATCH   // licence exists but the date of birth does not match
    }
}
