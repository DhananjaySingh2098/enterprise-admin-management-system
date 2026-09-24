package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.enterprise.admin.entity.RoleName;

/**
 * Every endpoint from Phases 1–5 × {anonymous, USER, MANAGER, ADMIN}, enforced by the API itself.
 *
 * <p>Requests use invalid bodies or non-existent ids, so allowed callers get 200/204/400/404 without changing data.
 * "Allowed" means "not 401/403"; denied means exactly 401 (anonymous) or 403 (authenticated, wrong role). Because
 * role rules are enforced in the filter chain, a denied caller gets 403 even with an invalid body.
 */
class AuthorizationMatrixIntegrationTest extends AbstractIntegrationTest {

    private static final String MISSING = "/999999999";
    private static final Set<RoleName> ALL = EnumSet.allOf(RoleName.class);
    private static final Set<RoleName> MANAGEMENT = EnumSet.of(RoleName.ADMIN, RoleName.MANAGER);
    private static final Set<RoleName> ADMIN = EnumSet.of(RoleName.ADMIN);
    private static final Set<RoleName> PUBLIC = null;

    private record Endpoint(HttpMethod method, String path, String body, Set<RoleName> allowed) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static Endpoint e(HttpMethod method, String path, Set<RoleName> allowed) {
        return new Endpoint(method, path, method == HttpMethod.GET ? null : "{}", allowed);
    }

    static final List<Endpoint> ENDPOINTS = List.of(
            // public
            e(HttpMethod.GET, "/api/health", PUBLIC),
            e(HttpMethod.GET, "/api/health/db", PUBLIC),
            e(HttpMethod.POST, "/api/auth/login", PUBLIC),
            e(HttpMethod.POST, "/api/auth/refresh", PUBLIC),
            e(HttpMethod.POST, "/api/auth/logout", PUBLIC),
            // any signed-in user (own data only)
            e(HttpMethod.GET, "/api/auth/me", ALL),
            e(HttpMethod.GET, "/api/profile", ALL),
            e(HttpMethod.PUT, "/api/profile", ALL),
            e(HttpMethod.PUT, "/api/profile/password", ALL),
            e(HttpMethod.GET, "/api/notifications", ALL),
            e(HttpMethod.GET, "/api/notifications/unread-count", ALL),
            e(HttpMethod.PUT, "/api/notifications" + MISSING + "/read", ALL),
            e(HttpMethod.PUT, "/api/notifications/read-all", ALL),
            e(HttpMethod.GET, "/api/preferences", ALL),
            e(HttpMethod.PUT, "/api/preferences", ALL),
            e(HttpMethod.GET, "/api/settings/workspace", ALL),
            e(HttpMethod.GET, "/api/employees", ALL),
            e(HttpMethod.GET, "/api/employees" + MISSING, ALL),
            e(HttpMethod.GET, "/api/departments", ALL),
            e(HttpMethod.GET, "/api/departments" + MISSING, ALL),
            // ADMIN + MANAGER
            e(HttpMethod.GET, "/api/dashboard/summary", MANAGEMENT),
            e(HttpMethod.GET, "/api/dashboard/headcount-by-department", MANAGEMENT),
            e(HttpMethod.GET, "/api/dashboard/status-breakdown", MANAGEMENT),
            e(HttpMethod.GET, "/api/dashboard/hiring-trend", MANAGEMENT),
            e(HttpMethod.GET, "/api/dashboard/recent-employees", MANAGEMENT),
            e(HttpMethod.POST, "/api/employees", MANAGEMENT),
            e(HttpMethod.PUT, "/api/employees" + MISSING, MANAGEMENT),
            // ADMIN only
            e(HttpMethod.GET, "/api/dashboard/users", ADMIN),
            e(HttpMethod.PUT, "/api/employees" + MISSING + "/status", ADMIN),
            e(HttpMethod.POST, "/api/departments", ADMIN),
            e(HttpMethod.PUT, "/api/departments" + MISSING, ADMIN),
            e(HttpMethod.PUT, "/api/departments" + MISSING + "/status", ADMIN),
            e(HttpMethod.GET, "/api/users", ADMIN),
            e(HttpMethod.GET, "/api/users" + MISSING, ADMIN),
            e(HttpMethod.POST, "/api/users", ADMIN),
            e(HttpMethod.PUT, "/api/users" + MISSING, ADMIN),
            e(HttpMethod.PUT, "/api/users" + MISSING + "/roles", ADMIN),
            e(HttpMethod.PUT, "/api/users" + MISSING + "/status", ADMIN),
            e(HttpMethod.GET, "/api/audit-logs", ADMIN),
            e(HttpMethod.GET, "/api/audit-logs" + MISSING, ADMIN),
            e(HttpMethod.GET, "/api/settings/organization", ADMIN),
            e(HttpMethod.PUT, "/api/settings/organization", ADMIN));

    @Test
    void everyEndpointEnforcesItsRolesInTheApi() throws Exception {
        String admin = tokenForNewUser(RoleName.ADMIN);
        String manager = tokenForNewUser(RoleName.MANAGER);
        String user = tokenForNewUser(RoleName.USER);
        List<String> violations = new ArrayList<>();

        for (Endpoint endpoint : ENDPOINTS) {
            check(endpoint, null, null, violations);
            check(endpoint, RoleName.USER, user, violations);
            check(endpoint, RoleName.MANAGER, manager, violations);
            check(endpoint, RoleName.ADMIN, admin, violations);
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void unknownEndpointsDoNotRevealThemselvesToAnonymousCallers() throws Exception {
        assertThat(status(HttpMethod.GET, "/api/does-not-exist", null, null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/does-not-exist", null, tokenForNewUser(RoleName.USER))).isEqualTo(404);
    }

    @Test
    void aTokenForAnotherRoleCannotBeUpgradedByRequestData() throws Exception {
        String user = tokenForNewUser(RoleName.USER);
        // Role hints in headers or parameters are ignored: authority only comes from the verified token.
        int status = mockMvc.perform(as(user, request(HttpMethod.GET, "/api/users").param("role", "ADMIN")
                .header("X-User-Role", "ADMIN"))).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    private void check(Endpoint endpoint, RoleName role, String token, List<String> violations) throws Exception {
        int status = status(endpoint.method(), endpoint.path(), endpoint.body(), token);
        String who = role == null ? "anonymous" : role.name();
        if (endpoint.allowed() == PUBLIC) {
            if (status == 401 || status == 403) {
                violations.add(endpoint + " is public but returned " + status + " for " + who);
            }
        } else if (role == null) {
            if (status != 401) {
                violations.add(endpoint + " returned " + status + " for anonymous (expected 401)");
            }
        } else if (endpoint.allowed().contains(role)) {
            if (status == 401 || status == 403) {
                violations.add(endpoint + " denied " + who + " with " + status);
            }
        } else if (status != 403) {
            violations.add(endpoint + " returned " + status + " for " + who + " (expected 403)");
        }
    }

    private int status(HttpMethod method, String path, String body, String token) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path).header("X-Requested-With", "XMLHttpRequest");
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (token != null) {
            builder = as(token, builder);
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }
}
