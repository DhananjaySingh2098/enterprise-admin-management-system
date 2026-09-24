package com.enterprise.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.security.JwtProperties;
import com.enterprise.admin.security.JwtService;
import com.enterprise.admin.security.TokenHasher;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end authentication against a real MySQL 8.4 (Testcontainers): Flyway migrations, Hibernate schema
 * validation, persistence, login, refresh rotation and reuse detection, logout, /me, roles and login protection.
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = ROOT_ADMIN_EMAIL;
    private static final String ADMIN_PASSWORD = ROOT_ADMIN_PASSWORD;
    private static final String COOKIE = "ea_refresh_token";

    @Autowired private JwtProperties jwtProperties;

    // ---------------------------------------------------------------- schema & bootstrap

    @Test
    void flywayMigratedSchemaAndSeededExactlyTheThreeRoles() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, success from flyway_schema_history where version is not null order by installed_rank");
        assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isIn(true, 1, (byte) 1));

        assertThat(jdbc.queryForList("select name from roles order by name", String.class))
                .containsExactly("ADMIN", "MANAGER", "USER");
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = database()", String.class))
                .contains("users", "roles", "user_roles", "refresh_tokens", "departments", "employees",
                        "audit_logs", "notifications", "user_preferences", "organization_settings", "rate_limit_buckets");
    }

    @Test
    void databaseRejectsUnknownRolesAndDuplicateEmails() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("insert into roles(name) values ('ROOT')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "insert into users(email,password_hash,first_name,last_name,enabled,created_at,updated_at) "
                                + "values ('ROOT.ADMIN@example.com','x','a','b',1,now(6),now(6))"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void initialAdminWasCreatedFromConfigurationWithBcryptHash() {
        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(admin.roleNames()).containsExactly(RoleName.ADMIN);
        assertThat(admin.getPasswordHash()).startsWith("$2a$").doesNotContain(ADMIN_PASSWORD);
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, admin.getPasswordHash())).isTrue();
        // The bootstrap admin is the very first account ever created (other tests add admins later).
        assertThat(jdbc.queryForObject("select email from users order by id limit 1", String.class)).isEqualTo(ADMIN_EMAIL);
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginSucceedsAndPersistsOnlyTheRefreshTokenHash() throws Exception {
        MvcResult result = login(ADMIN_EMAIL, ADMIN_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        String raw = refreshCookie(result);
        assertThat(raw).hasSizeGreaterThanOrEqualTo(43);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, raw)).isZero();
        Map<String, Object> row = jdbc.queryForMap("select * from refresh_tokens where token_hash = ?", TokenHasher.sha256(raw));
        assertThat(row.get("revoked_at")).isNull();
        assertThat(((java.time.LocalDateTime) row.get("expires_at")).toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(START.plus(Duration.ofDays(7)));
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        login("  ROOT.Admin@Example.com ", ADMIN_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledAccountAreIndistinguishable() throws Exception {
        User disabled = createAccount("disabled.user@example.com", RoleName.USER);
        disabled.setEnabled(false);
        userRepository.save(disabled);

        JsonNode wrongPassword = errorBody(login(ADMIN_EMAIL, "definitely-not-the-password").andExpect(status().isUnauthorized()));
        JsonNode unknownEmail = errorBody(login("nobody.here@example.com", ADMIN_PASSWORD).andExpect(status().isUnauthorized()));
        JsonNode disabledAccount = errorBody(login("disabled.user@example.com", USER_PASSWORD).andExpect(status().isUnauthorized()));

        for (JsonNode body : List.of(wrongPassword, unknownEmail, disabledAccount)) {
            assertThat(body.get("message").asString()).isEqualTo("Invalid credentials");
            assertThat(body.get("status").asInt()).isEqualTo(401);
            // requestId (Phase 5) is a random correlation id, present in every error body alike.
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path", "requestId");
        }
    }

    @Test
    void repeatedFailuresLockTheAccountTemporarilyWithGeneric429() throws Exception {
        String email = "lockout.target@example.com";
        createAccount(email, RoleName.USER);
        String ip = "10.20.30.40";

        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password-" + i, ip).andExpect(status().isUnauthorized());
        }
        // Even the correct password is refused while locked, so guessing cannot continue.
        login(email, USER_PASSWORD, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.message").value("Too many login attempts. Please try again later."));
        // Unknown emails are counted the same way, so lockout does not reveal existence.
        for (int i = 0; i < 5; i++) {
            login("ghost.account@example.com", "x-password-" + i, "10.20.30.41").andExpect(status().isUnauthorized());
        }
        login("ghost.account@example.com", "x-password", "10.20.30.41").andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(61));
        login(email, USER_PASSWORD, ip).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- access tokens & /me

    @Test
    void meReturnsIntentionalDtoForValidToken() throws Exception {
        String token = accessToken(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());

        MvcResult me = mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.firstName").value("System"))
                .andExpect(jsonPath("$.lastName").value("Administrator"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andReturn();
        assertThat(json.readTree(me.getResponse().getContentAsString()).propertyNames())
                .containsExactlyInAnyOrder("id", "email", "firstName", "lastName", "roles");
    }

    @Test
    void missingMalformedTamperedAndExpiredAccessTokensAre401() throws Exception {
        String token = accessToken(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());

        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.substring(0, token.length() - 3) + "abc"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46YWRtaW4="))
                .andExpect(status().isUnauthorized());

        clock.advance(jwtProperties.accessTokenTtl().plus(jwtProperties.clockSkew()).plusSeconds(1));
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void validTokenOfDisabledUserNoLongerReachesMe() throws Exception {
        User user = createAccount("soon.disabled@example.com", RoleName.USER);
        String token = accessToken(login(user.getEmail(), USER_PASSWORD).andReturn());
        user.setEnabled(false);
        userRepository.save(user);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- refresh rotation

    @Test
    void refreshRotatesTokenAndRevokesThePreviousOne() throws Exception {
        String first = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        clock.advance(Duration.ofMinutes(20));

        MvcResult refreshed = refresh(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL))
                .andReturn();
        String second = refreshCookie(refreshed);
        assertThat(second).isNotEqualTo(first);

        Map<String, Object> old = tokenRow(first);
        Map<String, Object> current = tokenRow(second);
        assertThat(old.get("revoked_reason")).isEqualTo("ROTATED");
        assertThat(current.get("revoked_at")).isNull();
        assertThat(current.get("family_id")).isEqualTo(old.get("family_id"));
        assertThat(current.get("session_expires_at")).isEqualTo(old.get("session_expires_at"));

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(refreshed)))
                .andExpect(status().isOk());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeSession() throws Exception {
        String first = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        String second = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());

        clock.advance(Duration.ofMinutes(5)); // beyond the 30s benign-race grace period
        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Session expired. Please sign in again."))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        assertThat(tokenRow(second).get("revoked_reason")).isEqualTo("REUSE_DETECTED");
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRaceWithinGracePeriodDoesNotKillTheSession() throws Exception {
        String first = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        String second = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());

        // The losing request (same token, 5 s later) recovers instead of signing the user out...
        clock.advance(Duration.ofSeconds(5));
        String third = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());
        assertThat(tokenRow(second).get("revoked_reason")).isEqualTo("SUPERSEDED");
        // ...and whichever copy the browser keeps still works: the session never forks.
        String fourth = refreshCookie(refresh(second).andExpect(status().isOk()).andReturn());
        assertThat(tokenRow(third).get("revoked_reason")).isEqualTo("SUPERSEDED");
        assertThat(activeTokens(first)).isEqualTo(1);
        refresh(fourth).andExpect(status().isOk());
        assertThat(activeTokens(first)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- Phase 6: session robustness

    @Test
    void anAbortedRefreshIsRecoveredOnceWithinTheGracePeriod() throws Exception {
        String t1 = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        // The server rotates, but the browser never receives t2 (page reloaded mid-request).
        String lost = refreshCookie(refresh(t1).andExpect(status().isOk()).andReturn());
        clock.advance(Duration.ofSeconds(2));
        MvcResult recovered = refresh(t1).andExpect(status().isOk()).andReturn();
        String t3 = refreshCookie(recovered);

        assertThat(tokenRow(lost).get("revoked_reason")).isEqualTo("SUPERSEDED");
        assertThat(tokenRow(t1).get("replaced_by_id")).isEqualTo(tokenRow(t3).get("id"));
        assertThat(activeTokens(t1)).isEqualTo(1);
        assertThat(accessToken(recovered)).isNotBlank();
        // The recovered session continues normally, and no reuse alarm was raised.
        refresh(t3).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? and revoked_reason = 'REUSE_DETECTED'",
                Integer.class, tokenRow(t1).get("family_id"))).isZero();
    }

    @Test
    void aStaleDuplicateIsRejectedWithoutEndingTheSessionOnceTheReplacementWasUsed() throws Exception {
        String t1 = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        String t2 = refreshCookie(refresh(t1).andExpect(status().isOk()).andReturn());
        String t3 = refreshCookie(refresh(t2).andExpect(status().isOk()).andReturn());
        clock.advance(Duration.ofSeconds(3));

        refresh(t1).andExpect(status().isUnauthorized());
        assertThat(tokenRow(t3).get("revoked_at")).isNull();
        refresh(t3).andExpect(status().isOk());
    }

    @Test
    void replayingAnOldTokenAfterTheGracePeriodStillRevokesTheWholeSession() throws Exception {
        String t1 = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        String t2 = refreshCookie(refresh(t1).andExpect(status().isOk()).andReturn());
        clock.advance(Duration.ofMinutes(2));
        // Even though t2 was never used, recovery is only possible inside the grace period.
        refresh(t1).andExpect(status().isUnauthorized());
        assertThat(tokenRow(t2).get("revoked_reason")).isEqualTo("REUSE_DETECTED");
        assertThat(activeTokens(t1)).isZero();
    }

    @Test
    void concurrentRefreshesWithTheSameTokenLeaveExactlyOneActiveToken() throws Exception {
        String t1 = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        int parallel = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(parallel);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return refresh(t1).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (var result : results) {
            statuses.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).allSatisfy(code -> assertThat(code).isIn(200, 401)).contains(200);
        assertThat(activeTokens(t1)).as("rotation is serialized: never a forked session").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? and revoked_reason = 'REUSE_DETECTED'",
                Integer.class, tokenRow(t1).get("family_id"))).isZero();
    }

    @Test
    void logoutDuringOrAfterARefreshEndsTheWholeSession() throws Exception {
        String t1 = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        String t2 = refreshCookie(refresh(t1).andExpect(status().isOk()).andReturn());
        // The browser still holds t1 when it signs out (the refresh response was in flight).
        mockMvc.perform(xhr(post("/api/auth/logout")).cookie(new Cookie(COOKIE, t1))).andExpect(status().isNoContent());
        assertThat(activeTokens(t1)).isZero();
        refresh(t2).andExpect(status().isUnauthorized());
        // No recovery from a logged-out session, even within the grace period.
        refresh(t1).andExpect(status().isUnauthorized());
        assertThat(activeTokens(t1)).isZero();
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        String raw = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        clock.advance(Duration.ofDays(7).plusSeconds(1));
        refresh(raw).andExpect(status().isUnauthorized());
    }

    @Test
    void rotationNeverExtendsBeyondMaxSessionLifetime() throws Exception {
        String raw = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        for (int day = 0; day < 4; day++) {
            clock.advance(Duration.ofDays(6));
            raw = refreshCookie(refresh(raw).andExpect(status().isOk()).andReturn());
        }
        // 24 days in; the latest token must expire at the 30-day session limit, not 7 days from now.
        Map<String, Object> row = tokenRow(raw);
        assertThat(row.get("expires_at")).isEqualTo(row.get("session_expires_at"));
        clock.advance(Duration.ofDays(6).plusSeconds(1));
        refresh(raw).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        refresh(TokenHasher.newToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshForDisabledUserFailsAndRevokesSession() throws Exception {
        User user = createAccount("disabled.later@example.com", RoleName.USER);
        String raw = refreshCookie(login(user.getEmail(), USER_PASSWORD).andReturn());
        user.setEnabled(false);
        userRepository.save(user);

        refresh(raw).andExpect(status().isUnauthorized());
        assertThat(tokenRow(raw).get("revoked_reason")).isEqualTo("USER_DISABLED");
    }

    // ---------------------------------------------------------------- logout

    @Test
    void logoutRevokesSessionClearsCookieAndIsRepeatable() throws Exception {
        String raw = refreshCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(xhr(post("/api/auth/logout")).cookie(new Cookie(COOKIE, raw)))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        }
        assertThat(tokenRow(raw).get("revoked_reason")).isEqualTo("LOGOUT");
        refresh(raw).andExpect(status().isUnauthorized());
        mockMvc.perform(xhr(post("/api/auth/logout"))).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- roles

    @Test
    void adminManagerAndUserRolesAreEnforcedWith403() throws Exception {
        String admin = accessToken(login(ADMIN_EMAIL, ADMIN_PASSWORD).andReturn());
        String manager = accessToken(login(createAccount("role.manager@example.com", RoleName.MANAGER).getEmail(), USER_PASSWORD).andReturn());
        String user = accessToken(login(createAccount("role.user@example.com", RoleName.USER).getEmail(), USER_PASSWORD).andReturn());

        expectAccess(admin, "admin", 200);
        expectAccess(admin, "manager", 200);
        expectAccess(admin, "user", 200);

        expectAccess(manager, "admin", 403);
        expectAccess(manager, "manager", 200);
        expectAccess(manager, "user", 200);

        expectAccess(user, "admin", 403);
        expectAccess(user, "manager", 403);
        expectAccess(user, "user", 200);

        mockMvc.perform(get("/api/test/roles/user")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/test/roles/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(jsonPath("$.message").value("You do not have permission to access this resource"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void forgedRoleClaimCannotEscalatePrivileges() throws Exception {
        JwtService attacker = new JwtService(new JwtProperties("attacker-controlled-key-0123456789abcdefgh",
                jwtProperties.issuer(), jwtProperties.audience(), Duration.ofMinutes(15), Duration.ZERO), clock);
        String forged = attacker.issue(1L, java.util.Set.of(RoleName.ADMIN)).value();
        mockMvc.perform(get("/api/test/roles/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private static final String USER_PASSWORD = DEFAULT_PASSWORD;

    private User createAccount(String email, RoleName role) {
        User user = new User(email, passwordEncoder.encode(USER_PASSWORD), "Test", role.name());
        user.addRole(roleRepository.findByName(role).orElseThrow());
        return userRepository.save(user);
    }

    private void expectAccess(String token, String endpoint, int expectedStatus) throws Exception {
        mockMvc.perform(get("/api/test/roles/" + endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(expectedStatus));
    }

    private static MockHttpServletRequestBuilder xhr(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Requested-With", "XMLHttpRequest");
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return login(email, password, "127.0.0.1");
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password, String ip) throws Exception {
        String body = json.writeValueAsString(Map.of("email", email, "password", password));
        return mockMvc.perform(xhr(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String rawToken) throws Exception {
        return mockMvc.perform(xhr(post("/api/auth/refresh")).cookie(new Cookie(COOKIE, rawToken)));
    }

    private String refreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie").startsWith(COOKIE + "=");
        return header.substring(COOKIE.length() + 1, header.indexOf(';'));
    }

    private String accessToken(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private JsonNode errorBody(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private int activeTokens(String anyTokenOfFamily) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
                Integer.class, tokenRow(anyTokenOfFamily).get("family_id"));
    }

    private Map<String, Object> tokenRow(String raw) {
        return jdbc.queryForMap("select * from refresh_tokens where token_hash = ?", TokenHasher.sha256(raw));
    }

}
