package com.mmts.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mmts.identity.IdentityTestBase;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint tests for /api/v1/auth/** (register, password login, OTP login) and /dev/otp. */
class AuthApiTest extends IdentityTestBase {

    private String loginBody(String method, String mobile, String password, String otp) {
        return """
                {"method":"%s","mobile":"%s","password":%s,"otp":%s}
                """.formatted(method, mobile, password == null ? "null" : "\"" + password + "\"",
                otp == null ? "null" : "\"" + otp + "\"");
    }

    @Test
    void registerCreatesTravellerWithoutAToken() throws Exception {
        String mobile = freshMobile();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Kavya Reddy","mobile":"%s","email":"Kavya.%s@Example.in","password":"Kavya@123","accountType":"TRAVELLER"}
                        """.formatted(mobile, mobile)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.fullName").value("Kavya Reddy"))
                .andExpect(jsonPath("$.email").value("kavya." + mobile + "@example.in"))
                .andExpect(jsonPath("$.roles[0]").value("TRAVELLER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registerAsDriverGivesDriverRole() throws Exception {
        String mobile = freshMobile();
        String id = register(mobile, "Driver@123", "DRIVER");

        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(id, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("DRIVER"));
    }

    @Test
    void sameMobileCannotRegisterTwice() throws Exception {
        String mobile = freshMobile();
        register(mobile, "Secret@123", "TRAVELLER");

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Someone Else","mobile":"%s","email":"other.%s@example.in","password":"Secret@123","accountType":"TRAVELLER"}
                        """.formatted(mobile, mobile)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOBILE_ALREADY_REGISTERED"));
    }

    @Test
    void badRegistrationListsEveryBadField() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"","mobile":"12345","email":"not-an-email","password":"short","accountType":"TRAVELLER"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.length()").value(4));
    }

    @Test
    void passwordLoginReturnsATokenWithTheUsersRoles() throws Exception {
        // Demo user Priya (u-101) from DemoDataLoader
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("PASSWORD", "9876543210", "Priya@123", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-101"))
                .andExpect(jsonPath("$.roles[0]").value("TRAVELLER"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // The token is a normal shared MMTS token: any app can check it with the shared key (stateless, D1)
        AuthenticatedUser user = jwtService.verify(read(body).get("token").asText());
        assertThat(user.userId()).isEqualTo("u-101");
        assertThat(user.roles()).containsExactly(Role.TRAVELLER);
    }

    @Test
    void loginCreatesNoServerSession() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("PASSWORD", "9876543210", "Priya@123", null)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Set-Cookie")).isNull());
    }

    @Test
    void wrongPasswordGives401InvalidCredentials() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("PASSWORD", "9876543210", "wrong", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void otpLoginWorksEndToEnd() throws Exception {
        String mobile = freshMobile();
        String id = register(mobile, "Secret@123", "TRAVELLER");

        mvc.perform(post("/api/v1/auth/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"" + mobile + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sentTo").value("******" + mobile.substring(6)))
                .andExpect(jsonPath("$.expiresInSeconds").value(300));

        JsonNode sms = read(mvc.perform(get("/dev/otp/" + mobile))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String otp = sms.get("otp").asText();
        assertThat(otp).hasSize(6);

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("OTP", mobile, null, otp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(id));

        // the same code cannot be used twice
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("OTP", mobile, null, otp)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_NOT_REQUESTED"));
    }

    @Test
    void wrongOtpGives401OtpInvalid() throws Exception {
        String mobile = freshMobile();
        register(mobile, "Secret@123", "TRAVELLER");
        mvc.perform(post("/api/v1/auth/otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\"}")).andExpect(status().isAccepted());
        String otp = read(mvc.perform(get("/dev/otp/" + mobile)).andReturn().getResponse().getContentAsString())
                .get("otp").asText();
        String wrong = otp.equals("000000") ? "111111" : "000000";

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("OTP", mobile, null, wrong)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void otpForUnknownMobileLooksTheSameButSendsNothing() throws Exception {
        String mobile = freshMobile();
        mvc.perform(post("/api/v1/auth/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"" + mobile + "\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(get("/dev/otp/" + mobile))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SMS_SENT"));
    }
}
