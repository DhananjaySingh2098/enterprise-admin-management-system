package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.security.TokenHasher;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

class UserAdminIntegrationTest extends AbstractIntegrationTest {

    private Map<String, Object> newUser(String email, List<String> roles) {
        return Map.of("firstName", "Grace", "lastName", "Hopper", "email", email,
                "initialPassword", "Initial-Password-2026", "roles", roles, "enabled", true);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    @Test
    void adminCreatesUserWithHashedPasswordAndNoSecretsInResponse() throws Exception {
        String admin = rootAdminToken();
        String email = unique("grace") + "@Example.com";

        JsonNode created = body(mockMvc.perform(jsonBody(as(admin, post("/api/users")), newUser(email, List.of("MANAGER", "USER"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/users/")))
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.roles[0]").value("MANAGER"))
                .andExpect(jsonPath("$.roles[1]").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true)));

        assertThat(created.propertyNames()).containsExactlyInAnyOrder("id", "email", "firstName", "lastName", "roles",
                "enabled", "createdAt", "updatedAt");
        assertThat(created.toString()).doesNotContain("Initial-Password-2026");
        String hash = jdbc.queryForObject("select password_hash from users where id = ?", String.class, created.get("id").asLong());
        assertThat(hash).startsWith("$2a$");
        assertThat(passwordEncoder.matches("Initial-Password-2026", hash)).isTrue();

        // The new account can sign in with its initial password.
        assertThat(accessToken(email, "Initial-Password-2026")).isNotBlank();
    }

    @Test
    void createValidatesDuplicatesRolesAndPasswordPolicy() throws Exception {
        String admin = rootAdminToken();
        mockMvc.perform(jsonBody(as(admin, post("/api/users")), newUser(ROOT_ADMIN_EMAIL.toUpperCase(), List.of("USER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));

        mockMvc.perform(jsonBody(as(admin, post("/api/users")), newUser(unique("r") + "@example.com", List.of("SUPERUSER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("roles")));

        mockMvc.perform(jsonBody(as(admin, post("/api/users")), newUser(unique("r") + "@example.com", List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("roles"));

        Map<String, Object> weak = new java.util.HashMap<>(newUser(unique("w") + "@example.com", List.of("USER")));
        weak.put("initialPassword", "short");
        mockMvc.perform(jsonBody(as(admin, post("/api/users")), weak))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("initialPassword"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("short"))));
    }

    @Test
    void listSupportsSearchFiltersSortingAndPagination() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("zeta");
        for (int i = 0; i < 3; i++) {
            createUser(tag + "-" + i + "@example.com", RoleName.USER);
        }
        User manager = createUser(tag + "-m@example.com", RoleName.MANAGER);
        User disabled = createUser(tag + "-d@example.com", RoleName.USER);
        disabled.setEnabled(false);
        userRepository.save(disabled);

        mockMvc.perform(as(admin, get("/api/users").param("search", tag).param("size", "2").param("sort", "email").param("direction", "asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content[0].email").value(tag + "-0@example.com"))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());

        mockMvc.perform(as(admin, get("/api/users").param("search", tag).param("size", "2").param("page", "2").param("sort", "email")))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(as(admin, get("/api/users").param("search", tag).param("role", "MANAGER")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(manager.getId()));
        mockMvc.perform(as(admin, get("/api/users").param("search", tag).param("enabled", "false")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].enabled").value(false));
        // Wildcards in the search term are literal.
        mockMvc.perform(as(admin, get("/api/users").param("search", "%")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listRejectsUnsafeSortAndOversizedPages() throws Exception {
        String admin = rootAdminToken();
        mockMvc.perform(as(admin, get("/api/users").param("sort", "passwordHash")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mockMvc.perform(as(admin, get("/api/users").param("size", "1000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(as(admin, get("/api/users").param("role", "ROOT")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminUpdatesDetailsAndRoles() throws Exception {
        String admin = rootAdminToken();
        User user = createUser(unique("edit") + "@example.com", RoleName.USER);

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId())),
                        Map.of("firstName", "Edited", "lastName", "Name", "email", "  " + user.getEmail().toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Edited"))
                .andExpect(jsonPath("$.email").value(user.getEmail()));

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId() + "/roles")), Map.of("roles", List.of("MANAGER", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));
        assertThat(jdbc.queryForList("select r.name from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = ? order by r.name",
                String.class, user.getId())).containsExactly("MANAGER", "USER");

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId())),
                        Map.of("firstName", "A", "lastName", "B", "email", ROOT_ADMIN_EMAIL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        mockMvc.perform(as(admin, get("/api/users/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void genericUpdateCannotTouchPasswordRolesOrStatus() throws Exception {
        String admin = rootAdminToken();
        User user = createUser(unique("mass") + "@example.com", RoleName.USER);
        String hashBefore = user.getPasswordHash();

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId())), Map.of("firstName", "X", "lastName", "Y",
                        "email", user.getEmail(), "passwordHash", "$2a$04$attacker", "roles", List.of("ADMIN"), "enabled", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));

        User after = userRepository.findWithRolesById(user.getId()).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(after.roleNames()).containsExactly(RoleName.USER);
        assertThat(after.isEnabled()).isTrue();
    }

    @Test
    void disablingRevokesSessionsAndBlocksLoginUntilReEnabled() throws Exception {
        String admin = rootAdminToken();
        User user = createUser(unique("disable") + "@example.com", RoleName.USER);
        String refresh = refreshCookieOf(user.getEmail());
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, user.getId())).isEqualTo(1);

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId() + "/status")), Map.of("enabled", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(jdbc.queryForObject("select revoked_reason from refresh_tokens where token_hash = ?", String.class,
                TokenHasher.sha256(refresh))).isEqualTo("USER_DISABLED");
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", refresh)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", DEFAULT_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId() + "/status")), Map.of("enabled", true)))
                .andExpect(status().isOk());
        assertThat(accessToken(user.getEmail(), DEFAULT_PASSWORD)).isNotBlank();
    }

    @Test
    void adminCannotDisableOrDemoteThemselves() throws Exception {
        User admin = createUser(unique("self") + "@example.com", RoleName.ADMIN);
        String token = accessToken(admin.getEmail(), DEFAULT_PASSWORD);

        mockMvc.perform(jsonBody(as(token, put("/api/users/" + admin.getId() + "/status")), Map.of("enabled", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_DISABLE"));
        mockMvc.perform(jsonBody(as(token, put("/api/users/" + admin.getId() + "/roles")), Map.of("roles", List.of("USER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_DEMOTION"));

        User reloaded = userRepository.findWithRolesById(admin.getId()).orElseThrow();
        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.roleNames()).contains(RoleName.ADMIN);
    }

    @Test
    void lastActiveAdminIsProtected() throws Exception {
        // Make the root admin the only enabled ADMIN, then act as a second admin whose account was just disabled
        // but whose short-lived access token is still valid (the documented Phase 2 window).
        jdbc.update("""
                update users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id
                set u.enabled = 0 where r.name = 'ADMIN' and u.email <> ?""", ROOT_ADMIN_EMAIL);
        User other = createUser(unique("other-admin") + "@example.com", RoleName.ADMIN);
        String otherToken = accessToken(other.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(jsonBody(as(rootAdminToken(), put("/api/users/" + other.getId() + "/status")), Map.of("enabled", false)))
                .andExpect(status().isOk());
        long rootId = userRepository.findByEmail(ROOT_ADMIN_EMAIL).orElseThrow().getId();

        mockMvc.perform(jsonBody(as(otherToken, put("/api/users/" + rootId + "/status")), Map.of("enabled", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"))
                .andExpect(jsonPath("$.message").value("At least one active administrator must remain."));
        mockMvc.perform(jsonBody(as(otherToken, put("/api/users/" + rootId + "/roles")), Map.of("roles", List.of("USER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        assertThat(userRepository.countEnabledWithRole(RoleName.ADMIN)).isEqualTo(1);
    }

    @Test
    void managersAndUsersCannotUseUserAdministration() throws Exception {
        User target = createUser(unique("target") + "@example.com", RoleName.USER);
        for (String token : List.of(tokenForNewUser(RoleName.MANAGER), tokenForNewUser(RoleName.USER))) {
            mockMvc.perform(as(token, get("/api/users"))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
            mockMvc.perform(as(token, get("/api/users/" + target.getId()))).andExpect(status().isForbidden());
            mockMvc.perform(jsonBody(as(token, post("/api/users")), newUser(unique("x") + "@example.com", List.of("ADMIN"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(jsonBody(as(token, put("/api/users/" + target.getId() + "/roles")), Map.of("roles", List.of("ADMIN"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(jsonBody(as(token, put("/api/users/" + target.getId() + "/status")), Map.of("enabled", false)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        assertThat(userRepository.findWithRolesById(target.getId()).orElseThrow().roleNames()).containsExactly(RoleName.USER);
    }

    private String refreshCookieOf(String email) throws Exception {
        String header = mockMvc.perform(post("/api/auth/login").header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", DEFAULT_PASSWORD))))
                .andReturn().getResponse().getHeader("Set-Cookie");
        return header.substring("ea_refresh_token=".length(), header.indexOf(';'));
    }
}
