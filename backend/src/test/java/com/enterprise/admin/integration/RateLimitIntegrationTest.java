package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.security.RateLimitProperties;
import com.enterprise.admin.security.RateLimitService;

/**
 * Rate limits with small, test-specific policies (own Spring context) against the shared MySQL store.
 * The client IP differs per test so windows never interfere.
 */
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void smallLimits(DynamicPropertyRegistry registry) {
        registry.add("app.security.rate-limit.policies.login-ip.limit", () -> "5");
        registry.add("app.security.rate-limit.policies.login-account-failure.limit", () -> "3");
        registry.add("app.security.rate-limit.policies.refresh-ip.limit", () -> "4");
        registry.add("app.security.rate-limit.policies.password-change.limit", () -> "2");
        registry.add("app.security.rate-limit.policies.admin-mutation.limit", () -> "3");
        registry.add("app.security.rate-limit.policies.account-mutation.limit", () -> "3");
        registry.add("app.security.rate-limit.policies.profile-update.limit", () -> "3");
        // Keep the per-instance lockout out of the way so the shared limiter is what is observed.
        registry.add("app.security.login-protection.max-failures-per-account", () -> "1000");
    }

    @Autowired private RateLimitProperties properties;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectProvider<DataSource> dataSourceProvider;

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login").header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", password)))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    /** Signs a new user in from its own IP, so the 5-per-IP login limit of this test class is never involved. */
    private String tokenFrom(RoleName role, String ip) throws Exception {
        User user = createUser(unique(role.name().toLowerCase()) + "@example.com", role);
        String body = login(user.getEmail(), DEFAULT_PASSWORD, ip).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }

    @Test
    void loginIsLimitedPerClientIpWithASafe429() throws Exception {
        String ip = "10.60.0.1";
        for (int i = 0; i < 5; i++) {
            login(unique("nobody") + "@example.com", "Wrong-Password-123", ip).andExpect(status().isUnauthorized());
        }
        login(ROOT_ADMIN_EMAIL, ROOT_ADMIN_PASSWORD, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please wait a moment and try again."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        // Another client is unaffected, and the window resets.
        login(ROOT_ADMIN_EMAIL, ROOT_ADMIN_PASSWORD, "10.60.0.2").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        login(ROOT_ADMIN_EMAIL, ROOT_ADMIN_PASSWORD, ip).andExpect(status().isOk());
    }

    @Test
    void failedLoginsAreLimitedPerAccountAcrossClientIps() throws Exception {
        User user = createUser(unique("guess") + "@example.com", RoleName.USER);
        for (int i = 0; i < 3; i++) {
            login(user.getEmail(), "Wrong-Password-123", "10.61.0." + i).andExpect(status().isUnauthorized());
        }
        // Even the right password from a fresh IP is refused until the window ends (no guessing across IPs).
        login(user.getEmail(), DEFAULT_PASSWORD, "10.61.0.99").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        clock.advance(Duration.ofMinutes(15));
        login(user.getEmail(), DEFAULT_PASSWORD, "10.61.0.99").andExpect(status().isOk());
    }

    @Test
    void refreshIsLimitedPerClientIp() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").with(r -> {
                r.setRemoteAddr("10.62.0.1");
                return r;
            })).andExpect(status().isNoContent());
        }
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "XMLHttpRequest").with(r -> {
            r.setRemoteAddr("10.62.0.1");
            return r;
        })).andExpect(status().isTooManyRequests());
    }

    @Test
    void mutationsAreLimitedPerUserWhileReadsAreNot() throws Exception {
        String admin = tokenFrom(RoleName.ADMIN, "10.63.0.1");
        String otherAdmin = tokenFrom(RoleName.ADMIN, "10.63.0.2");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of())).andExpect(status().isBadRequest());
        }
        mockMvc.perform(jsonBody(as(admin, post("/api/departments")), Map.of())).andExpect(status().isTooManyRequests());
        // Limits are per user: another administrator is unaffected.
        mockMvc.perform(jsonBody(as(otherAdmin, post("/api/departments")), Map.of())).andExpect(status().isBadRequest());
        // Ordinary browsing is never limited.
        for (int i = 0; i < 40; i++) {
            mockMvc.perform(as(admin, get("/api/departments"))).andExpect(status().isOk());
        }
    }

    @Test
    void passwordChangesAndPreferenceWritesHaveTheirOwnPolicies() throws Exception {
        String token = tokenFrom(RoleName.USER, "10.64.0.1");
        Map<String, String> wrong = Map.of("currentPassword", "Not-The-Password-1", "newPassword", "Brand-New-Password-1",
                "confirmPassword", "Brand-New-Password-1");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(jsonBody(as(token, put("/api/profile/password")), wrong)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(jsonBody(as(token, put("/api/profile/password")), wrong)).andExpect(status().isTooManyRequests());
        Map<String, String> prefs = Map.of("themeMode", "DARK", "themePreset", "AURORA", "density", "COMPACT");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs)).andExpect(status().isOk());
        }
        mockMvc.perform(jsonBody(as(token, put("/api/preferences")), prefs)).andExpect(status().isTooManyRequests());
    }

    @Test
    void theSharedStoreEnforcesOneLimitAcrossApplicationInstances() {
        // Two independent services = two application instances sharing the same database.
        RateLimitService instanceA = new RateLimitService(properties, dataSourceProvider, clock);
        RateLimitService instanceB = new RateLimitService(properties, dataSourceProvider, clock);
        String subject = "ip:" + unique("shared");
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            RateLimitService instance = i % 2 == 0 ? instanceA : instanceB;
            if (instance.consume(RateLimitProperties.LOGIN_IP, subject).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(5);
        // Only hashed keys are stored: never the raw subject.
        assertThat(jdbc.queryForObject("select count(*) from rate_limit_buckets where bucket_key like ?", Integer.class,
                "%" + subject + "%")).isZero();
        assertThat(dataSource).isNotNull();
    }
}
