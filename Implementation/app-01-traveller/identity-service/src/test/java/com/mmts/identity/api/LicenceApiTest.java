package com.mmts.identity.api;

import com.mmts.identity.IdentityTestBase;
import com.mmts.identity.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint tests for /api/v1/licences/** and the drive-eligibility contract (INT-10 and INT-13, CONTRACTS.md).
 */
class LicenceApiTest extends IdentityTestBase {

    private static final String AUTH = "Authorization";

    private String eligibilityUrl(String userId, String vehicleClass, String purpose) {
        return "/api/v1/users/" + userId + "/drive-eligibility?vehicleClass=" + vehicleClass + "&purpose=" + purpose;
    }

    private void submitLicence(String token, String number, String dob) throws Exception {
        mvc.perform(post("/api/v1/licences").header(AUTH, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceNumber\":\"" + number + "\",\"dateOfBirth\":\"" + dob + "\",\"consent\":true}"))
                .andExpect(status().isAccepted());
    }

    // ---------- submit and view a licence ----------

    @Test
    void submitAnswersPendingAtOnceThenLicenceIsVerified() throws Exception {
        String id = register(freshMobile(), "Secret@123", "TRAVELLER");
        String token = bearer(id, Role.TRAVELLER);

        mvc.perform(post("/api/v1/licences").header(AUTH, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceNumber\":\"ka0120200054321\",\"dateOfBirth\":\"1998-01-15\",\"consent\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.maskedLicenceNumber").value("KA-01*********21"));

        mvc.perform(get("/api/v1/licences/me").header(AUTH, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.vehicleClasses[0]").value("MCWG"))
                .andExpect(jsonPath("$.expiresOn").value("2030-01-10"))
                .andExpect(jsonPath("$.licenceNumber").doesNotExist());
    }

    @Test
    void submitWithoutConsentIsRefused() throws Exception {
        String id = register(freshMobile(), "Secret@123", "TRAVELLER");

        mvc.perform(post("/api/v1/licences").header(AUTH, bearer(id, Role.TRAVELLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceNumber\":\"DL-0420190012345\",\"dateOfBirth\":\"1994-05-20\",\"consent\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void badLicenceNumberFailsValidation() throws Exception {
        mvc.perform(post("/api/v1/licences").header(AUTH, bearer("u-104", Role.TRAVELLER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceNumber\":\"12\",\"dateOfBirth\":\"1994-05-20\",\"consent\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void noLicenceYetGives404() throws Exception {
        String id = register(freshMobile(), "Secret@123", "TRAVELLER");

        mvc.perform(get("/api/v1/licences/me").header(AUTH, bearer(id, Role.TRAVELLER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LICENCE_NOT_FOUND"));
    }

    @Test
    void adminCannotSubmitALicence() throws Exception {
        mvc.perform(post("/api/v1/licences").header(AUTH, bearer("u-900", Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceNumber\":\"DL-0420190012345\",\"dateOfBirth\":\"1994-05-20\",\"consent\":true}"))
                .andExpect(status().isForbidden());
    }

    // ---------- INT-10 / INT-13 drive eligibility (contract) ----------

    @Test
    void eligibilityContractFieldsForAVerifiedUser() throws Exception {
        mvc.perform(get(eligibilityUrl("u-101", "LMV", "SELF_DRIVE")).header(AUTH, bearer("mmts-booking", Role.SERVICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.userId").value("u-101"))
                .andExpect(jsonPath("$.vehicleClass").value("LMV"))
                .andExpect(jsonPath("$.purpose").value("SELF_DRIVE"))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reason").value("VERIFIED"))
                .andExpect(jsonPath("$.licenceExpiresOn").value("2031-05-20"));
    }

    @Test
    void expiredLicenceIsNotEligible() throws Exception {
        mvc.perform(get(eligibilityUrl("u-103", "LMV", "SELF_DRIVE")).header(AUTH, bearer("mmts-booking", Role.SERVICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("EXPIRED"))
                .andExpect(jsonPath("$.licenceExpiresOn").value("2024-03-31"));
    }

    @Test
    void userWithoutLicenceGetsNoLicenceAndNullDate() throws Exception {
        String id = register(freshMobile(), "Secret@123", "TRAVELLER");

        mvc.perform(get(eligibilityUrl(id, "MCWG", "SELF_DRIVE")).header(AUTH, bearer("mmts-booking", Role.SERVICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("NO_LICENCE"))
                .andExpect(jsonPath("$.licenceExpiresOn").isEmpty());
    }

    @Test
    void wrongClassAndRejectedLicenceAreNotEligible() throws Exception {
        String scooterRider = register(freshMobile(), "Secret@123", "TRAVELLER");
        submitLicence(bearer(scooterRider, Role.TRAVELLER), "KA-0120200054321", "1998-01-15");
        String unknown = register(freshMobile(), "Secret@123", "TRAVELLER");
        submitLicence(bearer(unknown, Role.TRAVELLER), "TN-0919990011111", "1990-01-01");
        String service = bearer("mmts-booking", Role.SERVICE);

        mvc.perform(get(eligibilityUrl(scooterRider, "LMV", "SELF_DRIVE")).header(AUTH, service))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("CLASS_NOT_COVERED"));
        mvc.perform(get(eligibilityUrl(unknown, "LMV", "SELF_DRIVE")).header(AUTH, service))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("REJECTED"));
    }

    @Test
    void taxiDriverPurposeNeedsTheDriverRole() throws Exception {
        String service = bearer("mmts-provider-hub", Role.SERVICE);

        // Rahul (u-102) is a DRIVER with an LMV licence
        mvc.perform(get(eligibilityUrl("u-102", "LMV", "TAXI_DRIVER")).header(AUTH, service))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reason").value("VERIFIED"));
        // Priya (u-101) has a valid LMV licence but is only a TRAVELLER
        mvc.perform(get(eligibilityUrl("u-101", "LMV", "TAXI_DRIVER")).header(AUTH, service))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_A_DRIVER"));
    }

    @Test
    void unknownUserGives404UserNotFound() throws Exception {
        mvc.perform(get(eligibilityUrl("u-nobody", "LMV", "SELF_DRIVE")).header(AUTH, bearer("mmts-booking", Role.SERVICE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void eligibilityIsForServicesAndAdminsOnly() throws Exception {
        mvc.perform(get(eligibilityUrl("u-101", "LMV", "SELF_DRIVE")).header(AUTH, bearer("u-101", Role.TRAVELLER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(eligibilityUrl("u-101", "LMV", "SELF_DRIVE")).header(AUTH, bearer("u-900", Role.ADMIN)))
                .andExpect(status().isOk());
        mvc.perform(get(eligibilityUrl("u-101", "LMV", "SELF_DRIVE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownVehicleClassGives400() throws Exception {
        mvc.perform(get(eligibilityUrl("u-101", "TRUCK", "SELF_DRIVE")).header(AUTH, bearer("mmts-booking", Role.SERVICE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---------- M1.6 expiry check ----------

    @Test
    void adminRunsTheExpiryCheck() throws Exception {
        mvc.perform(post("/api/v1/licences/expiry-check").header(AUTH, bearer("u-900", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedOn").isNotEmpty())
                .andExpect(jsonPath("$.expiredCount").isNumber());
        // Vikram (u-105) had a VERIFIED licence that ran out on 2026-10-31: now it is EXPIRED
        mvc.perform(get(eligibilityUrl("u-105", "LMV", "TAXI_DRIVER")).header(AUTH, bearer("u-900", Role.ADMIN)))
                .andExpect(jsonPath("$.reason").value("EXPIRED"));
    }

    @Test
    void travellerCannotRunTheExpiryCheck() throws Exception {
        mvc.perform(post("/api/v1/licences/expiry-check").header(AUTH, bearer("u-101", Role.TRAVELLER)))
                .andExpect(status().isForbidden());
    }
}
