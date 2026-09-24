package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;

import tools.jackson.databind.JsonNode;

/** Personal preferences and organization settings against real MySQL. */
class SettingsIntegrationTest extends AbstractIntegrationTest {

    private JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> prefs(String mode, String preset, String density) {
        Map<String, Object> body = new HashMap<>();
        body.put("themeMode", mode);
        body.put("themePreset", preset);
        body.put("density", density);
        return body;
    }

    // ---------------------------------------------------------------- preferences

    @Test
    void preferencesDefaultUntilSavedThenPersistPerUser() throws Exception {
        User alice = createUser(unique("prefs-a") + "@example.com", RoleName.USER);
        String aliceToken = accessToken(alice.getEmail(), DEFAULT_PASSWORD);
        String bobToken = tokenForNewUser(RoleName.MANAGER);

        mockMvc.perform(as(aliceToken, get("/api/preferences")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeMode").value("SYSTEM"))
                .andExpect(jsonPath("$.themePreset").value("AURORA"))
                .andExpect(jsonPath("$.density").value("COMFORTABLE"))
                .andExpect(jsonPath("$.saved").value(false));
        assertThat(jdbc.queryForObject("select count(*) from user_preferences where user_id = ?", Integer.class, alice.getId()))
                .as("reading never creates a row").isZero();

        mockMvc.perform(jsonBody(as(aliceToken, put("/api/preferences")), prefs("DARK", "MIDNIGHT", "COMPACT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.themeMode").value("DARK"));
        mockMvc.perform(as(aliceToken, get("/api/preferences")))
                .andExpect(jsonPath("$.themePreset").value("MIDNIGHT"))
                .andExpect(jsonPath("$.density").value("COMPACT"));
        assertThat(jdbc.queryForMap("select theme_mode, theme_preset, density from user_preferences where user_id = ?", alice.getId()))
                .containsEntry("theme_mode", "DARK").containsEntry("theme_preset", "MIDNIGHT").containsEntry("density", "COMPACT");

        // Bob is unaffected, and nothing he sends can target Alice's row: an owner field is rejected outright.
        mockMvc.perform(as(bobToken, get("/api/preferences"))).andExpect(jsonPath("$.saved").value(false));
        Map<String, Object> hijack = prefs("LIGHT", "PEARL", "COMFORTABLE");
        hijack.put("userId", alice.getId());
        mockMvc.perform(jsonBody(as(bobToken, put("/api/preferences")), hijack)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        mockMvc.perform(as(aliceToken, get("/api/preferences"))).andExpect(jsonPath("$.themePreset").value("MIDNIGHT"));
    }

    @Test
    void preferenceValuesAreValidated() throws Exception {
        String token = tokenForNewUser(RoleName.USER);
        mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs("DARK", "NEON", "COMPACT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("themePreset"));
        mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs("DARK", "AURORA", "TINY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("density"));
        mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs("dark", "AURORA", "COMPACT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("themeMode"));
        mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs(null, "AURORA", "COMPACT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyRealPreferenceChangesAreAudited() throws Exception {
        User user = createUser(unique("prefs-audit") + "@example.com", RoleName.USER);
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs("LIGHT", "EMERALD", "COMFORTABLE"))).andExpect(status().isOk());
        }
        mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs("LIGHT", "EMERALD", "COMPACT"))).andExpect(status().isOk());
        List<String> details = jdbc.queryForList("select details from audit_logs where action = 'USER_PREFERENCES_UPDATED' "
                + "and entity_id = ? order by id", String.class, String.valueOf(user.getId()));
        assertThat(details).hasSize(2);
        assertThat(details.get(1)).startsWith("{\"changedFields\":[\"density\"]");
    }

    // ---------------------------------------------------------------- organization settings

    @Test
    void administratorsReadAndUpdateOrganizationSettingsWithOptimisticLocking() throws Exception {
        String admin = rootAdminToken();
        JsonNode current = read(mockMvc.perform(as(admin, get("/api/settings/organization"))).andExpect(status().isOk()));
        assertThat(current.propertyNames()).containsExactlyInAnyOrder("organizationName", "recentHireWindowDays", "version", "updatedAt");
        long version = current.get("version").asLong();
        String originalName = current.get("organizationName").asString();
        int originalWindow = current.get("recentHireWindowDays").asInt();
        try {
            JsonNode updated = read(mockMvc.perform(jsonBody(as(admin, put("/api/settings/organization")),
                            Map.of("organizationName", "  Acme Corp  ", "recentHireWindowDays", 7, "version", version)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.organizationName").value("Acme Corp"))
                    .andExpect(jsonPath("$.recentHireWindowDays").value(7)));
            assertThat(updated.get("version").asLong()).isGreaterThan(version);

            // The dashboard uses the configured window.
            mockMvc.perform(as(admin, get("/api/dashboard/summary"))).andExpect(jsonPath("$.recentHires.windowDays").value(7));
            // Everyone signed in sees the organization name, nothing else.
            String user = tokenForNewUser(RoleName.USER);
            JsonNode workspace = read(mockMvc.perform(as(user, get("/api/settings/workspace"))).andExpect(status().isOk()));
            assertThat(workspace.propertyNames()).containsExactly("organizationName");
            assertThat(workspace.get("organizationName").asString()).isEqualTo("Acme Corp");

            // A stale version is rejected.
            mockMvc.perform(jsonBody(as(admin, put("/api/settings/organization")),
                            Map.of("organizationName", "Other", "recentHireWindowDays", 30, "version", version)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STALE_VERSION"));

            String details = jdbc.queryForObject("select details from audit_logs where action = 'ORGANIZATION_SETTINGS_UPDATED' "
                    + "order by id desc limit 1", String.class);
            assertThat(details).contains("\"recentHireWindowDays\":{\"from\":" + originalWindow + ",\"to\":7}")
                    .contains("\"to\":\"Acme Corp\"");
        } finally {
            // Shared database: restore the seeded values for other tests (re-reading the version, in case an
            // assertion above failed before it was updated).
            version = read(mockMvc.perform(as(admin, get("/api/settings/organization")))).get("version").asLong();
            mockMvc.perform(jsonBody(as(admin, put("/api/settings/organization")),
                    Map.of("organizationName", originalName, "recentHireWindowDays", originalWindow, "version", version)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void organizationSettingsAreValidated() throws Exception {
        String admin = rootAdminToken();
        long version = read(mockMvc.perform(as(admin, get("/api/settings/organization")))).get("version").asLong();
        for (Object days : List.of(0, 366, -5)) {
            mockMvc.perform(jsonBody(as(admin, put("/api/settings/organization")),
                            Map.of("organizationName", "Acme", "recentHireWindowDays", days, "version", version)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("recentHireWindowDays"));
        }
        for (String name : List.of("", "   ", "x".repeat(121))) {
            mockMvc.perform(jsonBody(as(admin, put("/api/settings/organization")),
                            Map.of("organizationName", name, "recentHireWindowDays", 30, "version", version)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("organizationName"));
        }
        mockMvc.perform(jsonBody(as(admin, put("/api/settings/organization")), Map.of("organizationName", "Acme", "recentHireWindowDays", 30)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
    }

    @Test
    void nonAdministratorsCannotReadOrChangeOrganizationSettings() throws Exception {
        for (RoleName role : List.of(RoleName.MANAGER, RoleName.USER)) {
            String token = tokenForNewUser(role);
            mockMvc.perform(as(token, get("/api/settings/organization"))).andExpect(status().isForbidden());
            mockMvc.perform(jsonBody(as(token, put("/api/settings/organization")),
                    Map.of("organizationName", "Hacked", "recentHireWindowDays", 30, "version", 0))).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select organization_name from organization_settings where id = 1", String.class))
                .isNotEqualTo("Hacked");
    }

    @Test
    void migrationSeededASingleSettingsRowAndTheDatabaseEnforcesIt() {
        assertThat(jdbc.queryForObject("select count(*) from organization_settings", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into organization_settings (id, organization_name, recent_hire_window_days, updated_at) values (2, 'x', 30, now())"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update organization_settings set recent_hire_window_days = 0 where id = 1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into user_preferences (user_id, theme_mode, theme_preset, density, updated_at) "
                        + "select id, 'NEON', 'AURORA', 'COMPACT', now() from users limit 1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
