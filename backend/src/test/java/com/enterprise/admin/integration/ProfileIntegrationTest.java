package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.security.TokenHasher;

import jakarta.servlet.http.Cookie;

class ProfileIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anyRoleReadsOwnProfileWithoutSensitiveFields() throws Exception {
        User user = createUser(unique("profile") + "@example.com", RoleName.USER);
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);

        String body = mockMvc.perform(as(token, get("/api/profile")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).propertyNames())
                .containsExactlyInAnyOrder("id", "email", "firstName", "lastName", "roles", "createdAt");
        mockMvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void updatesOnlyTheCallerEvenIfAnotherIdIsSupplied() throws Exception {
        User me = createUser(unique("me") + "@example.com", RoleName.USER);
        User victim = createUser(unique("victim") + "@example.com", RoleName.USER);
        String token = accessToken(me.getEmail(), DEFAULT_PASSWORD);
        String newEmail = unique("renamed") + "@example.com";

        // Since Phase 6 an id or roles field is rejected outright (400 UNKNOWN_FIELD) rather than silently ignored.
        mockMvc.perform(jsonBody(as(token, put("/api/profile")),
                        Map.of("id", victim.getId(), "firstName", "Ada", "lastName", "Byron", "email", newEmail.toUpperCase(),
                                "roles", java.util.List.of("ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        mockMvc.perform(jsonBody(as(token, put("/api/profile")),
                        Map.of("firstName", "Ada", "lastName", "Byron", "email", newEmail.toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(me.getId()))
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.roles[0]").value("USER"));

        User victimAfter = userRepository.findById(victim.getId()).orElseThrow();
        assertThat(victimAfter.getEmail()).isEqualTo(victim.getEmail());
        assertThat(victimAfter.getFirstName()).isEqualTo("Test");
        mockMvc.perform(jsonBody(as(token, put("/api/profile/" + victim.getId())), Map.of("firstName", "X")))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(jsonBody(as(token, put("/api/profile")),
                        Map.of("firstName", "Ada", "lastName", "Byron", "email", victim.getEmail())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void changingPasswordRevokesOtherSessionsAndKeepsThisOne() throws Exception {
        User user = createUser(unique("pw") + "@example.com", RoleName.MANAGER);
        String otherDevice = refreshCookie(login(user.getEmail(), DEFAULT_PASSWORD));
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);

        MvcResult result = mockMvc.perform(jsonBody(as(token, put("/api/profile/password")), Map.of(
                        "currentPassword", DEFAULT_PASSWORD, "newPassword", "Brand-New-Password-42", "confirmPassword", "Brand-New-Password-42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Brand-New-Password-42");

        assertThat(jdbc.queryForObject("select revoked_reason from refresh_tokens where token_hash = ?", String.class,
                TokenHasher.sha256(otherDevice))).isEqualTo("PASSWORD_CHANGED");
        refresh(otherDevice).andExpect(status().isUnauthorized());
        refresh(refreshCookie(result)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, user.getId())).isEqualTo(1);

        login(user.getEmail(), DEFAULT_PASSWORD).getResponse().getStatus();
        assertThat(login(user.getEmail(), DEFAULT_PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(user.getEmail(), "Brand-New-Password-42").getResponse().getStatus()).isEqualTo(200);
        String stored = jdbc.queryForObject("select password_hash from users where id = ?", String.class, user.getId());
        assertThat(passwordEncoder.matches("Brand-New-Password-42", stored)).isTrue();
    }

    @Test
    void passwordChangeValidationUsesSafe400sNot401() throws Exception {
        User user = createUser(unique("pwv") + "@example.com", RoleName.USER);
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);

        change(token, "wrong-current-password", "Brand-New-Password-42", "Brand-New-Password-42")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("currentPassword"));
        change(token, DEFAULT_PASSWORD, "Brand-New-Password-42", "Different-Password-42")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        change(token, DEFAULT_PASSWORD, "short", "short")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        change(token, DEFAULT_PASSWORD, DEFAULT_PASSWORD, DEFAULT_PASSWORD)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_REUSED"));

        assertThat(passwordEncoder.matches(DEFAULT_PASSWORD,
                jdbc.queryForObject("select password_hash from users where id = ?", String.class, user.getId()))).isTrue();
    }

    @Test
    void repeatedWrongCurrentPasswordsAreRateLimited() throws Exception {
        User user = createUser(unique("pwlock") + "@example.com", RoleName.USER);
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);
        for (int i = 0; i < 5; i++) {
            change(token, "wrong-guess-" + i + "-xx", "Brand-New-Password-42", "Brand-New-Password-42").andExpect(status().isBadRequest());
        }
        change(token, DEFAULT_PASSWORD, "Brand-New-Password-42", "Brand-New-Password-42")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private org.springframework.test.web.servlet.ResultActions change(String token, String current, String next, String confirm) throws Exception {
        return mockMvc.perform(jsonBody(as(token, put("/api/profile/password")),
                Map.of("currentPassword", current, "newPassword", next, "confirmPassword", confirm)));
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String raw) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", raw)));
    }

    private static String refreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        return header.substring("ea_refresh_token=".length(), header.indexOf(';'));
    }
}
