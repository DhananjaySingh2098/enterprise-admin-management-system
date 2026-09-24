package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;

import tools.jackson.databind.JsonNode;

/** Phase 6: response headers, mass assignment, database least privilege, audit immutability and error hygiene. */
class SecurityHardeningIntegrationTest extends AbstractIntegrationTest {

    // ---------------------------------------------------------------- headers

    @Test
    void apiResponsesCarryModernSecurityHeaders() throws Exception {
        MvcResult result = mockMvc.perform(as(rootAdminToken(), get("/api/auth/me"))).andReturn();
        var response = result.getResponse();
        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy")).contains("camera=()", "geolocation=()", "microphone=()");
        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        // Superseded by CSP frame-ancestors / deprecated: not sent.
        assertThat(response.getHeader("X-Frame-Options")).isNull();
        assertThat(response.getHeader("X-XSS-Protection")).isNull();
        // Plain HTTP (local development): no HSTS.
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
        // Error responses get the same headers.
        var error = mockMvc.perform(get("/api/users")).andReturn().getResponse();
        assertThat(error.getHeader("Content-Security-Policy")).startsWith("default-src 'none'");
    }

    @Test
    void hstsIsSentOnHttpsOnly() throws Exception {
        var secure = mockMvc.perform(get("/api/health").secure(true)).andReturn().getResponse();
        assertThat(secure.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000 ; includeSubDomains");
    }

    // ---------------------------------------------------------------- mass assignment

    @Test
    void profileUpdatesCannotSetRolesStatusIdsOrSecrets() throws Exception {
        User user = createUser(unique("mass") + "@example.com", RoleName.USER);
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);
        for (String field : List.of("roles", "enabled", "id", "passwordHash", "createdAt", "updatedAt")) {
            Map<String, Object> body = new HashMap<>(Map.of("firstName", "Mal", "lastName", "Lory", "email", user.getEmail()));
            body.put(field, field.equals("roles") ? List.of("ADMIN") : field.equals("enabled") ? true : "x");
            mockMvc.perform(jsonBody(as(token, put("/api/profile")), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
        }
        User reloaded = userRepository.findWithRolesById(user.getId()).orElseThrow();
        assertThat(reloaded.roleNames()).containsExactly(RoleName.USER);
        assertThat(reloaded.getFirstName()).isEqualTo("Test");
    }

    @Test
    void adminAndRecordEndpointsRejectFieldsTheyDoNotOwn() throws Exception {
        String admin = rootAdminToken();
        User target = createUser(unique("target") + "@example.com", RoleName.USER);
        // Roles and status change only through their dedicated, safeguarded endpoints.
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + target.getId())),
                Map.of("firstName", "A", "lastName", "B", "email", target.getEmail(), "roles", List.of("ADMIN"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        mockMvc.perform(jsonBody(as(admin, post("/api/users")), Map.of("firstName", "A", "lastName", "B",
                        "email", unique("n") + "@example.com", "initialPassword", "Initial-Password-2026", "roles", List.of("USER"),
                        "passwordHash", "$2a$04$forged")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("passwordHash"));
        mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of("name", "X", "code", unique("M").substring(0, 8),
                        "active", false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("active"));
        mockMvc.perform(jsonBody(as(admin, put("/api/settings/organization")),
                        Map.of("organizationName", "X", "recentHireWindowDays", 30, "version", 0, "updatedByUserId", 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("updatedByUserId"));
        mockMvc.perform(jsonBody(as(admin, put("/api/preferences")),
                        Map.of("themeMode", "DARK", "themePreset", "AURORA", "density", "COMPACT", "userId", target.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("userId"));
        assertThat(userRepository.findWithRolesById(target.getId()).orElseThrow().roleNames()).containsExactly(RoleName.USER);
    }

    @Test
    void echoedUnknownFieldNamesAreSanitized() throws Exception {
        mockMvc.perform(jsonBody(as(rootAdminToken(), put("/api/preferences")),
                        Map.of("themeMode", "DARK", "themePreset", "AURORA", "density", "COMPACT", "<script>\nx", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("?script??x"));
    }

    // ---------------------------------------------------------------- database least privilege & audit immutability

    @Test
    void theRuntimeUserCannotModifyOrDeleteAuditRows() {
        long before = jdbc.queryForObject("select count(*) from audit_logs", Long.class);
        assertThatThrownBy(() -> runtimeJdbc.update("update audit_logs set action = 'LOGOUT'"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("UPDATE command denied");
        assertThatThrownBy(() -> runtimeJdbc.update("delete from audit_logs"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("DELETE command denied");
        assertThatThrownBy(() -> runtimeJdbc.update("truncate table audit_logs"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs", Long.class)).isEqualTo(before);
        // Appending is exactly what the application needs.
        assertThat(runtimeJdbc.queryForObject("select count(*) from audit_logs", Long.class)).isEqualTo(before);
    }

    @Test
    void theRuntimeUserHasNoAdministrativeOrSchemaPrivileges() {
        for (String sql : List.of(
                "create user 'intruder'@'%' identified by 'x-password-1'",
                "grant select on audit_logs to 'ea_migrator'@'%'",
                "drop table notifications",
                "create table evil (id int)",
                "alter table users add column evil int",
                "delete from users",
                "delete from employees",
                "insert into roles (name) values ('ROOT')",
                "select count(*) from flyway_schema_history",
                "select count(*) from mysql.user")) {
            assertThatThrownBy(() -> runtimeJdbc.execute(sql)).as(sql).isInstanceOf(DataAccessException.class);
        }
        List<String> grants = runtimeJdbc.queryForList("show grants", String.class);
        assertThat(String.join("\n", grants))
                .doesNotContain("ALL PRIVILEGES")
                .doesNotContain("GRANT OPTION")
                .doesNotContain("CREATE USER")
                .contains("GRANT SELECT, INSERT ON `" + MYSQL.getDatabaseName() + "`.`audit_logs`");
    }

    @Test
    void theMigrationUserCannotCreateAccounts() {
        assertThatThrownBy(() -> jdbc.execute("create user 'intruder2'@'%' identified by 'x-password-1'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theDatabaseEnforcesKeyAndCheckConstraints() {
        assertThatThrownBy(() -> jdbc.update("insert into users (email, password_hash, first_name, last_name, enabled, created_at, updated_at) "
                + "values (?, 'x', 'a', 'b', 1, now(), now())", ROOT_ADMIN_EMAIL)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into employees (employee_code, first_name, last_name, email, job_title, department_id, "
                + "hire_date, status, created_at, updated_at) values ('FK-1', 'a', 'b', 'fk1@x.example', 't', 999999999, '2024-01-01', 'ACTIVE', now(), now())"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into notifications (user_id, type, title, message, created_at) values (999999999, 'X', 't', 'm', now())"))
                .isInstanceOf(DataAccessException.class);
    }

    // ---------------------------------------------------------------- error hygiene

    @Test
    void errorResponsesNeverExposeInternals() throws Exception {
        String admin = rootAdminToken();
        List<MvcResult> results = List.of(
                mockMvc.perform(as(admin, put("/api/profile")).contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn(),
                mockMvc.perform(as(admin, get("/api/users").param("page", "abc"))).andReturn(),
                mockMvc.perform(as(admin, get("/api/users").param("sort", "passwordHash"))).andReturn(),
                mockMvc.perform(as(admin, get("/api/audit-logs/not-a-number"))).andReturn(),
                mockMvc.perform(get("/api/users").header("Authorization", "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.")).andReturn(),
                mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + "a".repeat(10_000))).andReturn(),
                mockMvc.perform(as(admin, get("/api/nope"))).andReturn());
        for (MvcResult result : results) {
            String body = result.getResponse().getContentAsString();
            JsonNode json = this.json.readTree(body);
            assertThat(json.has("requestId")).as(body).isTrue();
            assertThat(json.has("status")).isTrue();
            assertThat(body).as(body).doesNotContainIgnoringCase("exception").doesNotContain("at com.").doesNotContain("java.")
                    .doesNotContainIgnoringCase("select ").doesNotContainIgnoringCase("hibernate").doesNotContain("/Users/")
                    .doesNotContain("SQLState").doesNotContain("stackTrace").doesNotContain("nimbus");
        }
    }
}
