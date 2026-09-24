package com.enterprise.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.enterprise.admin.config.RuntimeGrants;

class Phase6HardeningTest {

    @Test
    void onlyHighRiskMutationsAreRateLimited() {
        assertThat(RateLimitFilter.policyFor("POST", "/api/auth/login")).isEqualTo(RateLimitProperties.LOGIN_IP);
        assertThat(RateLimitFilter.policyFor("POST", "/api/auth/refresh")).isEqualTo(RateLimitProperties.REFRESH_IP);
        assertThat(RateLimitFilter.policyFor("PUT", "/api/profile/password")).isEqualTo(RateLimitProperties.PASSWORD_CHANGE);
        assertThat(RateLimitFilter.policyFor("PUT", "/api/profile")).isEqualTo(RateLimitProperties.PROFILE_UPDATE);
        for (String path : Set.of("/api/users", "/api/users/5/roles", "/api/employees/9", "/api/departments", "/api/settings/organization")) {
            assertThat(RateLimitFilter.policyFor("PUT", path)).as(path).isEqualTo(RateLimitProperties.ADMIN_MUTATION);
        }
        assertThat(RateLimitFilter.policyFor("PUT", "/api/preferences")).isEqualTo(RateLimitProperties.ACCOUNT_MUTATION);
        assertThat(RateLimitFilter.policyFor("PUT", "/api/notifications/read-all")).isEqualTo(RateLimitProperties.ACCOUNT_MUTATION);
        // Reads are never limited, and prefixes must match whole path segments.
        for (String path : Set.of("/api/users", "/api/employees", "/api/audit-logs", "/api/dashboard/summary", "/api/notifications")) {
            assertThat(RateLimitFilter.policyFor("GET", path)).as(path).isNull();
        }
        assertThat(RateLimitFilter.policyFor("POST", "/api/usersX")).isNull();
        assertThat(RateLimitFilter.policyFor("POST", "/api/auth/logout")).isNull();
    }

    @Test
    void inMemoryStoreCountsPerWindowAndPurges() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        Instant w1 = Instant.parse("2026-03-01T09:00:00Z");
        assertThat(store.increment("k", w1, w1.plusSeconds(60))).isEqualTo(1);
        assertThat(store.increment("k", w1, w1.plusSeconds(60))).isEqualTo(2);
        assertThat(store.current("k", w1)).isEqualTo(2);
        assertThat(store.increment("k", w1.plusSeconds(60), w1.plusSeconds(120))).isEqualTo(1);
        assertThat(store.purgeExpired(w1.plusSeconds(90))).isEqualTo(1);
    }

    @Test
    void bucketKeysNeverContainRawSubjects() {
        String key = RateLimitService.key("login-ip", "ip:203.0.113.7");
        assertThat(key).startsWith("login-ip:").doesNotContain("203.0.113.7").hasSize("login-ip:".length() + 64);
    }

    @Test
    void sessionTimingSettingsAreBounded() {
        assertThatThrownBy(() -> new RefreshTokenProperties(Duration.ofDays(7), Duration.ofDays(30), Duration.ofMinutes(10),
                new RefreshTokenProperties.Cookie("n", true, "Strict", "/api/auth")))
                .hasMessageContaining("grace period");
        assertThatThrownBy(() -> new JwtProperties("x".repeat(40), "iss", "aud", Duration.ofMinutes(15), Duration.ofMinutes(10)))
                .hasMessageContaining("clock skew");
        assertThatThrownBy(() -> new RateLimitProperties.Policy(0, Duration.ofMinutes(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runtimeGrantsCoverEveryTableAndKeepAuditAppendOnly() {
        assertThat(RuntimeGrants.TABLE_PRIVILEGES).containsEntry("audit_logs", "SELECT, INSERT");
        assertThat(RuntimeGrants.TABLE_PRIVILEGES).containsKeys("users", "roles", "user_roles", "refresh_tokens", "departments",
                "employees", "notifications", "user_preferences", "organization_settings", "rate_limit_buckets");
        assertThat(RuntimeGrants.TABLE_PRIVILEGES.values()).noneMatch(p -> p.contains("ALL") || p.contains("GRANT")
                || p.contains("DROP") || p.contains("ALTER") || p.contains("CREATE"));
    }
}
