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

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;

import tools.jackson.databind.JsonNode;

/** Notifications against real MySQL: generation from real changes, principal scoping and read state. */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    private JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long unread(String token) throws Exception {
        return read(mockMvc.perform(as(token, get("/api/notifications/unread-count"))).andExpect(status().isOk())).get("count").asLong();
    }

    /** Admin changes the user's roles, which notifies the user. Returns the user's token. */
    private String userWithRoleChangeNotification(User user) throws Exception {
        String admin = rootAdminToken();
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId() + "/roles")), Map.of("roles", List.of("MANAGER"))))
                .andExpect(status().isOk());
        return accessToken(user.getEmail(), DEFAULT_PASSWORD);
    }

    @Test
    void aNewUserHasAnEmptyInbox() throws Exception {
        String token = tokenForNewUser(RoleName.USER);
        assertThat(unread(token)).isZero();
        mockMvc.perform(as(token, get("/api/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(as(token, put("/api/notifications/read-all")))
                .andExpect(jsonPath("$.updated").value(0)).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void adminChangesNotifyTheAffectedUserInPlainText() throws Exception {
        User user = createUser(unique("notify") + "@example.com", RoleName.USER);
        String token = userWithRoleChangeNotification(user);
        String admin = rootAdminToken();
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId())),
                Map.of("firstName", "Renamed<script>", "lastName", user.getLastName(), "email", user.getEmail())))
                .andExpect(status().isOk());

        assertThat(unread(token)).isEqualTo(2);
        JsonNode list = read(mockMvc.perform(as(token, get("/api/notifications"))).andExpect(status().isOk()));
        assertThat(list.get("content")).extracting(n -> n.get("type").asString())
                .containsExactly("ACCOUNT_DETAILS_CHANGED", "ROLE_CHANGED");
        JsonNode role = list.get("content").get(1);
        assertThat(role.get("title").asString()).isEqualTo("Your access was updated");
        assertThat(role.get("message").asString()).isEqualTo("Your roles are now MANAGER (previously USER).");
        assertThat(role.get("read").asBoolean()).isFalse();
        assertThat(role.get("relatedEntityType").asString()).isEqualTo("USER");
        // Field names only; the new value (with markup) is not echoed into the message.
        assertThat(list.get("content").get(0).get("message").asString()).isEqualTo("An administrator updated your first name.")
                .doesNotContain("<");
    }

    @Test
    void changesYouMakeYourselfDoNotNotifyYouExceptSecurityNotices() throws Exception {
        User user = createUser(unique("self") + "@example.com", RoleName.USER);
        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(jsonBody(as(token, put("/api/profile")),
                Map.of("firstName", "Self", "lastName", "Edited", "email", user.getEmail()))).andExpect(status().isOk());
        assertThat(unread(token)).isZero();

        String next = "Another-Strong-Pass-2026";
        JsonNode session = read(mockMvc.perform(jsonBody(as(token, put("/api/profile/password")),
                Map.of("currentPassword", DEFAULT_PASSWORD, "newPassword", next, "confirmPassword", next))).andExpect(status().isOk()));
        String fresh = session.get("accessToken").asString();
        mockMvc.perform(as(fresh, get("/api/notifications")))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("PASSWORD_CHANGED"));
    }

    @Test
    void statusChangesAndNewAdministratorsAreAnnounced() throws Exception {
        String admin = rootAdminToken();
        User otherAdmin = createUser(unique("peer") + "@example.com", RoleName.ADMIN);
        String peerToken = accessToken(otherAdmin.getEmail(), DEFAULT_PASSWORD);
        long before = unread(peerToken);
        User target = createUser(unique("promoted") + "@example.com", RoleName.USER);
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + target.getId() + "/roles")), Map.of("roles", List.of("ADMIN"))))
                .andExpect(status().isOk());
        assertThat(unread(peerToken)).isEqualTo(before + 1);
        mockMvc.perform(as(peerToken, get("/api/notifications").param("status", "unread").param("size", "1")))
                .andExpect(jsonPath("$.content[0].type").value("ADMIN_GRANTED"))
                .andExpect(jsonPath("$.content[0].message").value(org.hamcrest.Matchers.containsString(target.getEmail())));

        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + target.getId() + "/status")), Map.of("enabled", false))).andExpect(status().isOk());
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + target.getId() + "/status")), Map.of("enabled", true))).andExpect(status().isOk());
        String targetToken = accessToken(target.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(as(targetToken, get("/api/notifications")))
                .andExpect(jsonPath("$.content[0].title").value("Your account was re-enabled"))
                .andExpect(jsonPath("$.content[1].title").value("Your account was disabled"));
    }

    @Test
    void theLinkedEmployeeIsToldAboutChangesToTheirRecord() throws Exception {
        String admin = rootAdminToken();
        User user = createUser(unique("linked") + "@example.com", RoleName.USER);
        String code = unique("NT").toUpperCase().substring(0, 11);
        long dept = read(mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of("name", "Notify Dept", "code", code)))
                .andExpect(status().isCreated())).get("id").asLong();
        Map<String, Object> body = new HashMap<>(Map.of("employeeCode", code + "-E", "firstName", "Dorothy", "lastName", "Vaughan",
                "email", code.toLowerCase() + "@corp.example", "jobTitle", "Analyst", "departmentId", dept, "hireDate", "2024-01-02",
                "userId", user.getId()));
        JsonNode employee = read(mockMvc.perform(jsonBody(as(admin, post("/api/employees")), body)).andExpect(status().isCreated()));
        mockMvc.perform(jsonBody(as(admin, put("/api/employees/" + employee.get("id").asLong() + "/status")),
                Map.of("status", "ON_LEAVE", "version", employee.get("version").asLong()))).andExpect(status().isOk());

        String token = accessToken(user.getEmail(), DEFAULT_PASSWORD);
        mockMvc.perform(as(token, get("/api/notifications")))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("EMPLOYEE_RECORD_UPDATED"))
                .andExpect(jsonPath("$.content[0].message").value("Your employment status changed from Active to On leave."))
                .andExpect(jsonPath("$.content[0].relatedEntityId").value(employee.get("id").asString()));
    }

    @Test
    void markReadAndMarkAllRead() throws Exception {
        User user = createUser(unique("reader") + "@example.com", RoleName.USER);
        String token = userWithRoleChangeNotification(user);
        String admin = rootAdminToken();
        mockMvc.perform(jsonBody(as(admin, put("/api/users/" + user.getId() + "/roles")), Map.of("roles", List.of("USER"))))
                .andExpect(status().isOk());
        assertThat(unread(token)).isEqualTo(2);

        long first = read(mockMvc.perform(as(token, get("/api/notifications")))).get("content").get(0).get("id").asLong();
        mockMvc.perform(as(token, put("/api/notifications/" + first + "/read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty());
        // Idempotent.
        mockMvc.perform(as(token, put("/api/notifications/" + first + "/read"))).andExpect(status().isOk());
        assertThat(unread(token)).isEqualTo(1);
        mockMvc.perform(as(token, get("/api/notifications").param("status", "unread")))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(as(token, put("/api/notifications/read-all")))
                .andExpect(jsonPath("$.updated").value(1)).andExpect(jsonPath("$.unread").value(0));
        assertThat(unread(token)).isZero();
        mockMvc.perform(as(token, get("/api/notifications")))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void usersCanNeverSeeOrChangeSomeoneElsesNotifications() throws Exception {
        User alice = createUser(unique("alice") + "@example.com", RoleName.USER);
        String aliceToken = userWithRoleChangeNotification(alice);
        long aliceNotification = read(mockMvc.perform(as(aliceToken, get("/api/notifications")))).get("content").get(0).get("id").asLong();

        for (String bob : List.of(tokenForNewUser(RoleName.USER), tokenForNewUser(RoleName.ADMIN))) {
            // Same 404 as a non-existent id: existence is not revealed, even to administrators.
            mockMvc.perform(as(bob, put("/api/notifications/" + aliceNotification + "/read"))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Notification not found"));
            mockMvc.perform(as(bob, put("/api/notifications/999999999/read"))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Notification not found"));
            String bobList = mockMvc.perform(as(bob, get("/api/notifications").param("size", "50"))).andReturn().getResponse().getContentAsString();
            assertThat(bobList).doesNotContain("\"id\":" + aliceNotification + ",");
            mockMvc.perform(as(bob, put("/api/notifications/read-all"))).andExpect(status().isOk());
            // No parameter can redirect the query to another user.
            mockMvc.perform(as(bob, get("/api/notifications").param("userId", String.valueOf(alice.getId()))))
                    .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) aliceNotification))));
        }
        assertThat(unread(aliceToken)).as("Bob's read-all never touches Alice's notifications").isEqualTo(1);
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void listParametersAreValidated() throws Exception {
        String token = tokenForNewUser(RoleName.USER);
        mockMvc.perform(as(token, get("/api/notifications").param("status", "archived"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        mockMvc.perform(as(token, get("/api/notifications").param("size", "51"))).andExpect(status().isBadRequest());
        mockMvc.perform(as(token, put("/api/notifications/abc/read"))).andExpect(status().isBadRequest());
    }
}
