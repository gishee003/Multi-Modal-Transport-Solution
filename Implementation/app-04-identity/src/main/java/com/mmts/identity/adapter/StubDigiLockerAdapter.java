package com.mmts.identity.adapter;

import com.mmts.identity.domain.VehicleClass;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Stand-in for DigiLocker (the "stub" from lecture 19). It answers from a small fixed list of sample licences,
 * so every outcome can be shown in the demo and in tests:
 *
 * | Licence number   | Date of birth | Classes   | Valid until | Result in MMTS |
 * | DL-0420190012345 | 1994-05-20    | LMV, MCWG | 2031-05-20  | VERIFIED       |
 * | KA-0520180067890 | 1990-11-02    | LMV       | 2029-08-31  | VERIFIED       |
 * | KA-0120200054321 | 1998-01-15    | MCWG      | 2030-01-10  | VERIFIED (scooter only, so LMV gives CLASS_NOT_COVERED) |
 * | MH-1220100098765 | 1985-03-31    | LMV       | 2024-03-31  | EXPIRED        |
 * | any other number |               |           |             | REJECTED (NOT_FOUND)        |
 * | known number, wrong date of birth |  |      |             | REJECTED (DETAILS_MISMATCH) |
 *
 * The real adapter would: send the user to DigiLocker to give consent (OAuth 2.0), receive an access token,
 * call the Pull Document API for doctype DRVLC, and read the classes and validity from the returned XML.
 */
@Component
public class StubDigiLockerAdapter implements IdentityDocumentProvider {

    private record SampleLicence(LocalDate dateOfBirth, Set<VehicleClass> classes, LocalDate validUntil) {
    }

    private static final Map<String, SampleLicence> SAMPLES = Map.of(
            "DL-0420190012345", new SampleLicence(LocalDate.of(1994, 5, 20),
                    Set.of(VehicleClass.LMV, VehicleClass.MCWG), LocalDate.of(2031, 5, 20)),
            "KA-0520180067890", new SampleLicence(LocalDate.of(1990, 11, 2),
                    Set.of(VehicleClass.LMV), LocalDate.of(2029, 8, 31)),
            "KA-0120200054321", new SampleLicence(LocalDate.of(1998, 1, 15),
                    Set.of(VehicleClass.MCWG), LocalDate.of(2030, 1, 10)),
            "MH-1220100098765", new SampleLicence(LocalDate.of(1985, 3, 31),
                    Set.of(VehicleClass.LMV), LocalDate.of(2024, 3, 31)));

    @Override
    public LicenceLookup fetchDrivingLicence(String licenceNumber, LocalDate dateOfBirth) {
        SampleLicence sample = SAMPLES.get(licenceNumber.trim().toUpperCase());
        if (sample == null) {
            return LicenceLookup.notFound();
        }
        if (!sample.dateOfBirth().equals(dateOfBirth)) {
            return LicenceLookup.detailsMismatch();
        }
        return LicenceLookup.found(sample.classes(), sample.validUntil());
    }
}
