package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Dashboard aggregates against real MySQL. The database is shared with other suites, so each test measures the
 * exact change caused by its own known fixture rows (or runs against a genuinely empty database in a rolled-back
 * transaction).
 */
class DashboardIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManagerFactory entityManagerFactory;

    private JsonNode getJson(String token, String path) throws Exception {
        return json.readTree(mockMvc.perform(as(token, get(path))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private long createDepartment(String admin, String code) throws Exception {
        return json.readTree(mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of("name", "Analytics " + code, "code", code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private void createEmployee(String admin, String code, long departmentId, String hireDate, String status) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("employeeCode", code, "firstName", "Fixture", "lastName", code,
                "email", code.toLowerCase() + "@dash.example", "jobTitle", "Analyst", "departmentId", departmentId,
                "hireDate", hireDate, "status", status));
        mockMvc.perform(jsonBody(as(admin, post("/api/employees")), body)).andExpect(status().isCreated());
    }

    private static Map<String, Long> months(JsonNode trend) {
        Map<String, Long> result = new HashMap<>();
        trend.get("months").forEach(m -> result.put(m.get("month").asString(), m.get("hires").asLong()));
        return result;
    }

    private static long statusCount(JsonNode breakdown, String status) {
        for (JsonNode row : breakdown) {
            if (row.get("status").asString().equals(status)) {
                return row.get("count").asLong();
            }
        }
        throw new AssertionError("missing status " + status);
    }

    @Test
    void aggregatesReflectExactlyThePersistedFixtureRows() throws Exception {
        String admin = rootAdminToken(); // clock "today" = 2026-03-01 (UTC)
        JsonNode summaryBefore = getJson(admin, "/api/dashboard/summary");
        JsonNode statusBefore = getJson(admin, "/api/dashboard/status-breakdown");
        JsonNode trendBefore = getJson(admin, "/api/dashboard/hiring-trend");

        String tag = unique("K").toUpperCase().replace("-", "").substring(0, 8);
        long dept = createDepartment(admin, tag);
        long emptyDept = createDepartment(admin, tag + "Z");
        createEmployee(admin, tag + "-1", dept, "2026-03-01", "ACTIVE");      // this month, within 30 days
        createEmployee(admin, tag + "-2", dept, "2026-02-15", "ON_LEAVE");    // last month, within 30 days
        createEmployee(admin, tag + "-3", dept, "2025-11-10", "TERMINATED");  // counted as a hire, not in headcount
        createEmployee(admin, tag + "-4", dept, "2025-04-01", "ACTIVE");      // first month of the 12-month range
        createEmployee(admin, tag + "-5", dept, "2025-03-31", "ACTIVE");      // just outside the range
        createEmployee(admin, tag + "-6", dept, "2026-04-15", "ACTIVE");      // future start: not a hire yet

        JsonNode summary = getJson(admin, "/api/dashboard/summary");
        assertThat(summary.at("/employees/total").asLong() - summaryBefore.at("/employees/total").asLong()).isEqualTo(6);
        assertThat(summary.at("/employees/active").asLong() - summaryBefore.at("/employees/active").asLong()).isEqualTo(4);
        assertThat(summary.at("/employees/onLeave").asLong() - summaryBefore.at("/employees/onLeave").asLong()).isEqualTo(1);
        assertThat(summary.at("/employees/terminated").asLong() - summaryBefore.at("/employees/terminated").asLong()).isEqualTo(1);
        assertThat(summary.at("/departments/total").asLong() - summaryBefore.at("/departments/total").asLong()).isEqualTo(2);
        assertThat(summary.at("/recentHires/count").asLong() - summaryBefore.at("/recentHires/count").asLong()).isEqualTo(2);
        assertThat(summary.at("/recentHires/from").asString()).isEqualTo("2026-01-31");
        assertThat(summary.at("/recentHires/to").asString()).isEqualTo("2026-03-01");

        // Totals agree with raw SQL over the same tables.
        assertThat(summary.at("/employees/total").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from employees", Long.class));
        assertThat(summary.at("/employees/onLeave").asLong())
                .isEqualTo(jdbc.queryForObject("select count(*) from employees where status = 'ON_LEAVE'", Long.class));

        JsonNode statuses = getJson(admin, "/api/dashboard/status-breakdown");
        assertThat(statuses).hasSize(3);
        assertThat(statusCount(statuses, "ACTIVE") - statusCount(statusBefore, "ACTIVE")).isEqualTo(4);
        assertThat(statusCount(statuses, "TERMINATED") - statusCount(statusBefore, "TERMINATED")).isEqualTo(1);

        JsonNode trend = getJson(admin, "/api/dashboard/hiring-trend");
        assertThat(trend.get("months")).hasSize(12);
        assertThat(trend.get("months").get(0).get("month").asString()).isEqualTo("2025-04");
        assertThat(trend.get("months").get(11).get("month").asString()).isEqualTo("2026-03");
        Map<String, Long> after = months(trend);
        Map<String, Long> before = months(trendBefore);
        Map<String, Long> delta = new HashMap<>();
        after.forEach((m, v) -> delta.put(m, v - before.getOrDefault(m, 0L)));
        assertThat(delta).containsEntry("2026-03", 1L).containsEntry("2026-02", 1L).containsEntry("2025-11", 1L)
                .containsEntry("2025-04", 1L).containsEntry("2025-12", 0L).containsEntry("2026-01", 0L);
        assertThat(delta.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(4);
        assertThat(trend.get("total").asLong() - trendBefore.get("total").asLong()).isEqualTo(4);

        JsonNode headcount = getJson(admin, "/api/dashboard/headcount-by-department");
        Map<Long, Long> byDept = new HashMap<>();
        headcount.forEach(row -> byDept.put(row.get("departmentId").asLong(), row.get("headcount").asLong()));
        assertThat(byDept).containsEntry(dept, 5L).containsEntry(emptyDept, 0L);
        assertThat(headcount.size()).isEqualTo(jdbc.queryForObject("select count(*) from departments", Integer.class));
        long previous = Long.MAX_VALUE;
        for (JsonNode row : headcount) { // largest first
            assertThat(row.get("headcount").asLong()).isLessThanOrEqualTo(previous);
            previous = row.get("headcount").asLong();
        }

        JsonNode recent = getJson(admin, "/api/dashboard/recent-employees");
        assertThat(recent).hasSize(5);
        assertThat(recent.get(0).get("employeeCode").asString()).isEqualTo(tag + "-1");
        assertThat(recent.get(1).get("employeeCode").asString()).isEqualTo(tag + "-2");
        assertThat(recent.toString()).doesNotContain(tag + "-6");
        assertThat(recent.get(0).get("departmentName").asString()).isEqualTo("Analytics " + tag);
        assertThat(recent.get(0).propertyNames()).containsExactlyInAnyOrder("id", "employeeCode", "firstName", "lastName",
                "departmentName", "jobTitle", "status", "hireDate");
    }

    @Test
    void inactiveDepartmentsKeepReportingTheirHeadcount() throws Exception {
        String admin = rootAdminToken();
        String tag = unique("I").toUpperCase().replace("-", "").substring(0, 8);
        long dept = createDepartment(admin, tag);
        createEmployee(admin, tag + "-1", dept, "2025-06-01", "ACTIVE");
        mockMvc.perform(jsonBody(as(admin, put("/api/departments/" + dept + "/status")), Map.of("active", false))).andExpect(status().isOk());

        JsonNode headcount = getJson(admin, "/api/dashboard/headcount-by-department");
        JsonNode row = null;
        for (JsonNode r : headcount) {
            if (r.get("departmentId").asLong() == dept) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("active").asBoolean()).isFalse();
        assertThat(row.get("headcount").asLong()).isEqualTo(1);
    }

    @Test
    void userAnalyticsCountDistinctHoldersPerRoleAndExposeNoIdentities() throws Exception {
        String admin = rootAdminToken();
        JsonNode before = getJson(admin, "/api/dashboard/users");

        User multi = createUser(unique("multi") + "@example.com", RoleName.MANAGER, RoleName.USER);
        User disabledAdmin = createUser(unique("dis-admin") + "@example.com", RoleName.ADMIN);
        disabledAdmin.setEnabled(false);
        userRepository.save(disabledAdmin);

        JsonNode after = getJson(admin, "/api/dashboard/users");
        assertThat(after.get("total").asLong() - before.get("total").asLong()).isEqualTo(2);
        assertThat(after.get("enabled").asLong() - before.get("enabled").asLong()).isEqualTo(1);
        assertThat(after.get("disabled").asLong() - before.get("disabled").asLong()).isEqualTo(1);
        assertThat(after.get("multiRoleUsers").asLong() - before.get("multiRoleUsers").asLong()).isEqualTo(1);
        assertThat(roleDelta(before, after, "MANAGER", "users")).isEqualTo(1);
        assertThat(roleDelta(before, after, "USER", "users")).isEqualTo(1);
        assertThat(roleDelta(before, after, "ADMIN", "users")).isEqualTo(1);
        assertThat(roleDelta(before, after, "ADMIN", "enabledUsers")).isZero();

        assertThat(after.get("total").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from users", Long.class));
        assertThat(after.toString()).doesNotContain("@", "password", "hash", multi.getEmail());
    }

    private static long roleDelta(JsonNode before, JsonNode after, String role, String field) {
        return find(after, role).get(field).asLong() - find(before, role).get(field).asLong();
    }

    private static JsonNode find(JsonNode analytics, String role) {
        for (JsonNode r : analytics.get("roles")) {
            if (r.get("role").asString().equals(role)) {
                return r;
            }
        }
        throw new AssertionError(role);
    }

    @Test
    void emptyOrganisationReturnsZeroesAndEmptyLists() throws Exception {
        // Committed through the fixture connection: the application's least-privilege user cannot delete these rows
        // (by design), and every other test creates its own uniquely named data, so an empty organisation is safe.
        jdbc.update("delete from employees");
        jdbc.update("delete from departments");
        String admin = rootAdminToken();

        mockMvc.perform(as(admin, get("/api/dashboard/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.employees.total").value(0))
                .andExpect(jsonPath("$.employees.active").value(0))
                .andExpect(jsonPath("$.departments.total").value(0))
                .andExpect(jsonPath("$.recentHires.count").value(0));
        mockMvc.perform(as(admin, get("/api/dashboard/status-breakdown")))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].count").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
        mockMvc.perform(as(admin, get("/api/dashboard/hiring-trend").param("months", "6")))
                .andExpect(jsonPath("$.months.length()").value(6))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(as(admin, get("/api/dashboard/headcount-by-department"))).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(admin, get("/api/dashboard/recent-employees"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void roleVisibilityIsEnforcedByTheApi() throws Exception {
        String admin = rootAdminToken();
        String manager = tokenForNewUser(RoleName.MANAGER);
        String user = tokenForNewUser(RoleName.USER);
        List<String> workforce = List.of("/api/dashboard/summary", "/api/dashboard/headcount-by-department",
                "/api/dashboard/status-breakdown", "/api/dashboard/hiring-trend", "/api/dashboard/recent-employees");

        for (String path : workforce) {
            mockMvc.perform(as(admin, get(path))).andExpect(status().isOk());
            mockMvc.perform(as(manager, get(path))).andExpect(status().isOk());
            mockMvc.perform(as(user, get(path))).andExpect(status().isForbidden());
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(as(admin, get("/api/dashboard/users"))).andExpect(status().isOk());
        mockMvc.perform(as(manager, get("/api/dashboard/users"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have permission to access this resource"));
        mockMvc.perform(as(user, get("/api/dashboard/users"))).andExpect(status().isForbidden());
    }

    @Test
    void parametersAreValidated() throws Exception {
        String admin = rootAdminToken();
        mockMvc.perform(as(admin, get("/api/dashboard/hiring-trend").param("months", "0"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(as(admin, get("/api/dashboard/recent-employees").param("limit", "500"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void eachEndpointIssuesASmallFixedNumberOfQueries() throws Exception {
        String admin = rootAdminToken();
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        Map<String, Long> expectedMax = Map.of(
                // +1 since Phase 5: the recent-hire window comes from organization settings.
                "/api/dashboard/summary", 4L,
                "/api/dashboard/headcount-by-department", 1L,
                "/api/dashboard/status-breakdown", 1L,
                "/api/dashboard/hiring-trend", 1L,
                "/api/dashboard/recent-employees", 1L,
                "/api/dashboard/users", 3L);
        for (var entry : expectedMax.entrySet()) {
            stats.clear();
            mockMvc.perform(as(admin, get(entry.getKey()))).andExpect(status().isOk());
            assertThat(stats.getPrepareStatementCount()).as(entry.getKey()).isLessThanOrEqualTo(entry.getValue());
        }
    }
}
