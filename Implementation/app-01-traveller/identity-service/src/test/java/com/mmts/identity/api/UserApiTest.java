package com.mmts.identity.api;

import com.mmts.identity.IdentityTestBase;
import com.mmts.identity.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint tests for the profile (/api/v1/users/me/**) and the admin user and role endpoints. */
class UserApiTest extends IdentityTestBase {

    private static final String AUTH = "Authorization";

    @Test
    void getMyProfile() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(AUTH, bearer("u-101", Role.TRAVELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-101"))
                .andExpect(jsonPath("$.fullName").value("Priya Sharma"))
                .andExpect(jsonPath("$.savedPlaces[0].label").value("Home"))
                .andExpect(jsonPath("$.licenceCheckConsent").value(true));
    }

    @Test
    void profileOfUnknownUserGives404() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(AUTH, bearer("u-does-not-exist", Role.TRAVELLER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void serviceTokenCannotUseTheProfileEndpoints() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(AUTH, bearer("mmts-booking", Role.SERVICE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void updateProfileSavedPlacesAndConsent() throws Exception {
        String id = register(freshMobile(), "Secret@123", "TRAVELLER");
        String token = bearer(id, Role.TRAVELLER);

        mvc.perform(put("/api/v1/users/me").header(AUTH, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Rohan Gupta\",\"email\":\"rohan." + id + "@example.in\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Rohan Gupta"));

        mvc.perform(put("/api/v1/users/me/saved-places").header(AUTH, token).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"places":[{"label":"Home","name":"Hauz Khas Metro","lat":28.5433,"lon":77.2066,"stopId":"DMRC-HKS"},
                                           {"label":"Office","name":"India Gate","lat":28.6129,"lon":77.2295,"stopId":null}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedPlaces.length()").value(2))
                .andExpect(jsonPath("$.savedPlaces[1].name").value("India Gate"));

        mvc.perform(put("/api/v1/users/me/consent").header(AUTH, token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenceCheck\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licenceCheckConsent").value(true))
                .andExpect(jsonPath("$.consentRecordedAt").isNotEmpty());
    }

    @Test
    void deleteMyAccountRemovesDataAndLoginStopsWorking() throws Exception {
        String mobile = freshMobile();
        String id = register(mobile, "Secret@123", "TRAVELLER");

        mvc.perform(delete("/api/v1/users/me").header(AUTH, bearer(id, Role.TRAVELLER)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"PASSWORD\",\"mobile\":\"" + mobile + "\",\"password\":\"Secret@123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/v1/users/me").header(AUTH, bearer(id, Role.TRAVELLER)))
                .andExpect(status().isNotFound());
        // the mobile number is free again: the same person may register afresh
        register(mobile, "Secret@123", "TRAVELLER");
    }

    @Test
    void adminListsUsersWithMaskedMobiles() throws Exception {
        mvc.perform(get("/api/v1/users").header(AUTH, bearer("u-900", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == 'u-101')].maskedMobile").value(hasItem("******3210")))
                .andExpect(jsonPath("$[*].maskedMobile").value(not(hasItem("9876543210"))));
    }

    @Test
    void travellerCannotListUsers() throws Exception {
        mvc.perform(get("/api/v1/users").header(AUTH, bearer("u-101", Role.TRAVELLER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminAssignsAndRemovesARole() throws Exception {
        String id = register(freshMobile(), "Secret@123", "TRAVELLER");
        String admin = bearer("u-900", Role.ADMIN);

        mvc.perform(post("/api/v1/users/" + id + "/roles").header(AUTH, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"DRIVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("DRIVER")));

        mvc.perform(delete("/api/v1/users/" + id + "/roles/DRIVER").header(AUTH, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", not(hasItem("DRIVER"))));
    }

    @Test
    void roleRulesAreEnforced() throws Exception {
        String admin = bearer("u-900", Role.ADMIN);

        mvc.perform(post("/api/v1/users/u-104/roles").header(AUTH, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SERVICE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_ASSIGNABLE"));

        mvc.perform(delete("/api/v1/users/u-900/roles/ADMIN").header(AUTH, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_OWN_ADMIN"));

        mvc.perform(post("/api/v1/users/u-nobody/roles").header(AUTH, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"DRIVER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mvc.perform(post("/api/v1/users/u-104/roles").header(AUTH, bearer("u-101", Role.TRAVELLER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }
}
