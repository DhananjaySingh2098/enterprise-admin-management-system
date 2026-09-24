package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.enterprise.admin.entity.AuditAction;
import com.enterprise.admin.entity.AuditEntityType;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.service.AuditDetails;
import com.enterprise.admin.service.AuditService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/** Audit trail against real MySQL: what is recorded, by whom, transactional integrity, access and redaction. */
class AuditIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private PlatformTransactionManager transactionManager;

    private JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private List<Map<String, Object>> rows(AuditAction action, Object entityId) {
        return jdbc.queryForList("select * from audit_logs where action = ? and entity_id = ? order by id",
                action.name(), String.valueOf(entityId));
    }

    private Map<String, Object> single(AuditAction action, Object entityId) {
        List<Map<String, Object>> rows = rows(action, entityId);
        assertThat(rows).as(action + " for " + entityId).hasSize(1);
        return rows.getFirst();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    // ---------------------------------------------------------------- authentication events

    @Test
    void loginSuccessFailureAndLogoutAreRecordedWithoutSecrets() throws Exception {
        User user = createUser(unique("auditee") + "@example.com", RoleName.USER);
        String wrongPassword = "Wrong-Password-" + unique("x");
        String unknownEmail = unique("ghost") + "@example.com";

        login(user.getEmail(), wrongPassword).andExpect(status().isUnauthorized());
        login(unknownEmail, wrongPassword).andExpect(status().isUnauthorized());
        MvcResult ok = login(user.getEmail(), DEFAULT_PASSWORD).andExpect(status().isOk()).andReturn();
        String cookie = ok.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String raw = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", raw)))
                .andExpect(status().isNoContent());

        Map<String, Object> failure = single(AuditAction.LOGIN_FAILURE, user.getId());
        assertThat(failure.get("outcome")).isEqualTo("FAILURE");
        assertThat(failure.get("actor_user_id")).as("the presenter is not authenticated").isNull();
        assertThat(failure.get("details")).isEqualTo("{\"reason\":\"INVALID_CREDENTIALS\"}");
        assertThat(failure.get("ip_address")).isEqualTo("127.0.0.1");
        assertThat(failure.get("request_id")).isNotNull();

        Map<String, Object> success = single(AuditAction.LOGIN_SUCCESS, user.getId());
        assertThat(success.get("actor_user_id")).isEqualTo(user.getId());
        assertThat(success.get("actor_email")).isEqualTo(user.getEmail());
        assertThat(success.get("request_id")).isEqualTo(ok.getResponse().getHeader("X-Request-Id"));
        assertThat(single(AuditAction.LOGOUT, user.getId()).get("actor_user_id")).isEqualTo(user.getId());

        // An unknown account is recorded without the submitted identifier (it may be a mistyped password).
        String everything = jdbc.queryForList("select concat_ws('|', actor_email, target_label, entity_id, details) from audit_logs",
                String.class).toString();
        assertThat(everything).doesNotContain(unknownEmail).doesNotContain(wrongPassword).doesNotContain(DEFAULT_PASSWORD);
    }

    @Test
    void refreshTokenReuseIsRecordedAndTheOwnerIsWarned() throws Exception {
        User user = createUser(unique("reuse") + "@example.com", RoleName.USER);
        MvcResult ok = login(user.getEmail(), DEFAULT_PASSWORD).andReturn();
        String cookie = ok.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String first = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", first)))
                .andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(5));
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", first)))
                .andExpect(status().isUnauthorized());

        Map<String, Object> row = single(AuditAction.REFRESH_TOKEN_REVOKED, user.getId());
        assertThat(row.get("details").toString()).contains("REUSE_DETECTED").doesNotContain(first);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'SECURITY_ALERT'",
                Integer.class, user.getId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- business events

    @Test
    void userAdministrationIsRecordedWithTheAdminAsActorAndOnlyFieldNames() throws Exception {
        String admin = rootAdminToken();
        String email = unique("subject") + "@example.com";
        MvcResult created = mockMvc.perform(jsonBody(as(admin, post("/api/users")), Map.of("firstName", "Ada", "lastName", "L",
                "email", email, "initialPassword", "Initial-Password-2026", "roles", List.of("USER"), "enabled", true)))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + id)), Map.of("firstName", "Augusta", "lastName", "L", "email", email)))
                .andExpect(status().isOk());
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + id + "/roles")), Map.of("roles", List.of("MANAGER", "USER"))))
                .andExpect(status().isOk());
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + id + "/status")), Map.of("enabled", false))).andExpect(status().isOk());
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + id + "/status")), Map.of("enabled", true))).andExpect(status().isOk());
        // No-op updates are not recorded.
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + id + "/status")), Map.of("enabled", true))).andExpect(status().isOk());

        Map<String, Object> create = single(AuditAction.USER_CREATED, id);
        assertThat(create.get("actor_email")).isEqualTo(ROOT_ADMIN_EMAIL);
        assertThat(create.get("target_label")).isEqualTo(email);
        assertThat(create.get("request_id")).isEqualTo(created.getResponse().getHeader("X-Request-Id"));
        assertThat(create.get("details").toString()).contains("\"roles\":[\"USER\"]").doesNotContain("Initial-Password-2026");
        assertThat(single(AuditAction.USER_UPDATED, id).get("details")).isEqualTo("{\"changedFields\":[\"firstName\"]}");
        assertThat(single(AuditAction.USER_ROLES_CHANGED, id).get("details"))
                .isEqualTo("{\"from\":[\"USER\"],\"to\":[\"MANAGER\",\"USER\"]}");
        assertThat(single(AuditAction.USER_DISABLED, id).get("details").toString()).contains("revokedCount");
        assertThat(rows(AuditAction.USER_ENABLED, id)).hasSize(1);
    }

    @Test
    void employeeAndDepartmentChangesAreRecorded() throws Exception {
        String admin = rootAdminToken();
        String code = unique("AUD").toUpperCase().substring(0, 12);
        long dept = read(mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of("name", "Audit Dept", "code", code)))
                .andExpect(status().isCreated())).get("id").asLong();
        mockMvc.perform(jsonBody(as(admin, put("/api/departments/" + dept)), Map.of("name", "Audit Department", "code", code)))
                .andExpect(status().isOk());

        Map<String, Object> body = new HashMap<>(Map.of("employeeCode", code + "-E", "firstName", "Mary", "lastName", "Jackson",
                "email", code.toLowerCase() + "@corp.example", "jobTitle", "Engineer", "departmentId", dept, "hireDate", "2024-01-02"));
        body.put("phone", "+1 555 0199");
        JsonNode employee = read(mockMvc.perform(jsonBody(as(admin, post("/api/employees")), body)).andExpect(status().isCreated()));
        long id = employee.get("id").asLong();
        body.put("jobTitle", "Senior Engineer");
        body.put("phone", "+1 555 0100");
        body.put("version", employee.get("version").asLong());
        JsonNode updated = read(mockMvc.perform(jsonBody(as(admin, put("/api/employees/" + id)), body)).andExpect(status().isOk()));
        mockMvc.perform(jsonBody(as(admin, put("/api/employees/" + id + "/status")),
                Map.of("status", "ON_LEAVE", "version", updated.get("version").asLong()))).andExpect(status().isOk());
        mockMvc.perform(jsonBody(as(admin, put("/api/departments/" + dept + "/status")), Map.of("active", false))).andExpect(status().isOk());

        assertThat(single(AuditAction.DEPARTMENT_CREATED, dept).get("target_label")).isEqualTo(code + " · Audit Dept");
        assertThat(single(AuditAction.DEPARTMENT_UPDATED, dept).get("details")).isEqualTo("{\"changedFields\":[\"name\"]}");
        assertThat(rows(AuditAction.DEPARTMENT_DEACTIVATED, dept)).hasSize(1);
        assertThat(single(AuditAction.EMPLOYEE_CREATED, id).get("target_label")).isEqualTo(code + "-E · Mary Jackson");
        Map<String, Object> update = single(AuditAction.EMPLOYEE_UPDATED, id);
        assertThat(update.get("details")).isEqualTo("{\"changedFields\":[\"phone\",\"jobTitle\"]}");
        assertThat(update.get("details").toString()).as("values of personal fields are never stored").doesNotContain("555");
        assertThat(single(AuditAction.EMPLOYEE_STATUS_CHANGED, id).get("details")).isEqualTo("{\"from\":\"ACTIVE\",\"to\":\"ON_LEAVE\"}");
    }

    @Test
    void passwordChangesAreRecordedForSuccessAndWrongCurrentPassword() throws Exception {
        User user = createUser(unique("pw") + "@example.com", RoleName.USER);
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);
        String newPassword = "Brand-New-Password-2026";
        mockMvc.perform(jsonBody(as(token, put("/api/profile/password")), Map.of("currentPassword", "Not-The-Current-Pass-1",
                "newPassword", newPassword, "confirmPassword", newPassword))).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody(as(token, put("/api/profile/password")), Map.of("currentPassword", DEFAULT_PASSWORD,
                "newPassword", newPassword, "confirmPassword", newPassword))).andExpect(status().isOk());

        List<Map<String, Object>> events = rows(AuditAction.PASSWORD_CHANGED, user.getId());
        assertThat(events).extracting(row -> row.get("outcome")).containsExactly("FAILURE", "SUCCESS");
        assertThat(events.toString()).doesNotContain(newPassword).doesNotContain("Not-The-Current-Pass-1").doesNotContain("$2a$");
    }

    // ---------------------------------------------------------------- transactional integrity

    @Test
    void successRecordsJoinTheBusinessTransaction() {
        String label = unique("rolled-back");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            auditService.record(AuditAction.DEPARTMENT_CREATED, new AuditService.Target(AuditEntityType.DEPARTMENT, 0, label),
                    AuditDetails.none());
            throw new IllegalStateException("business failure after the audit write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_label = ?", Integer.class, label)).isZero();

        // A success record can never be written on its own, outside the change it describes.
        assertThatThrownBy(() -> auditService.record(AuditAction.DEPARTMENT_CREATED,
                new AuditService.Target(AuditEntityType.DEPARTMENT, 0, label), AuditDetails.none()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rejectedChangesLeaveNoSuccessRecord() throws Exception {
        String admin = rootAdminToken();
        long rootId = userRepository.findByEmail(ROOT_ADMIN_EMAIL).orElseThrow().getId();
        long before = jdbc.queryForObject("select count(*) from audit_logs where action = 'USER_ROLES_CHANGED' and entity_id = ?",
                Long.class, String.valueOf(rootId));
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + rootId + "/roles")), Map.of("roles", List.of("USER"))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'USER_ROLES_CHANGED' and entity_id = ?",
                Long.class, String.valueOf(rootId))).isEqualTo(before);
    }

    @Test
    void readOnlyRequestsAreNotAudited() throws Exception {
        String admin = rootAdminToken();
        long before = jdbc.queryForObject("select count(*) from audit_logs", Long.class);
        for (String path : List.of("/api/users", "/api/employees", "/api/departments", "/api/dashboard/summary",
                "/api/audit-logs", "/api/notifications", "/api/preferences", "/api/settings/organization")) {
            mockMvc.perform(as(admin, get(path))).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_logs", Long.class)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- API

    @Test
    void onlyAdministratorsCanReadTheAuditTrail() throws Exception {
        String manager = tokenForNewUser(RoleName.MANAGER);
        String user = tokenForNewUser(RoleName.USER);
        for (String token : List.of(manager, user)) {
            mockMvc.perform(as(token, get("/api/audit-logs"))).andExpect(status().isForbidden());
            mockMvc.perform(as(token, get("/api/audit-logs/1"))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
        // There are no write endpoints at all.
        mockMvc.perform(as(rootAdminToken(), post("/api/audit-logs"))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void adminListFiltersPaginatesSortsAndShowsDetail() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("filter");
        for (int i = 0; i < 3; i++) {
            createUser(tag + "-" + i + "@example.com", RoleName.USER);
            login(tag + "-" + i + "@example.com", DEFAULT_PASSWORD).andExpect(status().isOk());
        }
        login(tag + "-0@example.com", "Wrong-Password-12345").andExpect(status().isUnauthorized());

        JsonNode page = read(mockMvc.perform(as(admin, get("/api/audit-logs").param("search", tag).param("action", "LOGIN_SUCCESS")
                        .param("size", "2").param("sort", "actor").param("direction", "asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].actor.email").value(tag + "-0@example.com"))
                .andExpect(jsonPath("$.content[0].target.type").value("USER"))
                .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS")));
        assertThat(page.get("content").get(0).propertyNames()).containsExactlyInAnyOrder("id", "createdAt", "actor", "action",
                "target", "outcome", "ipAddress", "requestId", "details");

        mockMvc.perform(as(admin, get("/api/audit-logs").param("search", tag).param("outcome", "FAILURE")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_FAILURE"))
                .andExpect(jsonPath("$.content[0].details.reason").value("INVALID_CREDENTIALS"));
        mockMvc.perform(as(admin, get("/api/audit-logs").param("actor", tag + "-1").param("entityType", "USER")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(as(admin, get("/api/audit-logs").param("search", tag).param("from", "2026-03-01").param("to", "2026-03-01")))
                .andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(as(admin, get("/api/audit-logs").param("search", tag).param("from", "2026-03-02")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(as(admin, get("/api/audit-logs").param("search", "%")))
                .andExpect(jsonPath("$.totalElements").value(0));

        long id = page.get("content").get(0).get("id").asLong();
        mockMvc.perform(as(admin, get("/api/audit-logs/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mockMvc.perform(as(admin, get("/api/audit-logs/999999999"))).andExpect(status().isNotFound());
    }

    @Test
    void invalidQueriesAreRejected() throws Exception {
        String admin = rootAdminToken();
        mockMvc.perform(as(admin, get("/api/audit-logs").param("sort", "details"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mockMvc.perform(as(admin, get("/api/audit-logs").param("action", "DROP_TABLE"))).andExpect(status().isBadRequest());
        mockMvc.perform(as(admin, get("/api/audit-logs").param("from", "2026-03-05").param("to", "2026-03-01")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(as(admin, get("/api/audit-logs").param("size", "101"))).andExpect(status().isBadRequest());
    }

    @Test
    void requestIdsAreEchoedAndOnlySafeInboundIdsAreReused() throws Exception {
        String admin = rootAdminToken();
        MvcResult reused = mockMvc.perform(as(admin, get("/api/audit-logs")).header("X-Request-Id", "support-case-12345")).andReturn();
        assertThat(reused.getResponse().getHeader("X-Request-Id")).isEqualTo("support-case-12345");
        MvcResult replaced = mockMvc.perform(as(admin, get("/api/audit-logs")).header("X-Request-Id", "bad\nid injected")).andReturn();
        assertThat(replaced.getResponse().getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
        mockMvc.perform(as(admin, get("/api/audit-logs/999999999")).header("X-Request-Id", "trace-abcdef12"))
                .andExpect(jsonPath("$.requestId").value("trace-abcdef12"));
    }

    @Test
    void theStoredTrailContainsNoSecrets() throws Exception {
        // Exercise a spread of events first, then scan every stored value.
        userAdministrationIsRecordedWithTheAdminAsActorAndOnlyFieldNames();
        passwordChangesAreRecordedForSuccessAndWrongCurrentPassword();
        String admin = rootAdminToken();
        String dump = jdbc.queryForList("select concat_ws('|', actor_email, action, entity_type, entity_id, target_label, details, "
                + "ip_address, request_id) from audit_logs", String.class).toString();
        assertThat(dump).doesNotContain(ROOT_ADMIN_PASSWORD).doesNotContain(DEFAULT_PASSWORD)
                .doesNotContain("Initial-Password-2026").doesNotContain("$2a$").doesNotContain("eyJ")
                .doesNotContainIgnoringCase("bearer ").doesNotContain(admin);
        String api = mockMvc.perform(as(admin, get("/api/audit-logs").param("size", "100"))).andReturn().getResponse().getContentAsString();
        assertThat(api).doesNotContain("password_hash").doesNotContain("$2a$").doesNotContain("eyJ");
    }
}
