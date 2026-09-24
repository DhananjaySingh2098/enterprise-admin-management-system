package com.enterprise.admin.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.mysql.MySQLContainer;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.repository.RoleRepository;
import com.enterprise.admin.repository.UserRepository;
import com.enterprise.testsupport.MutableClock;
import com.enterprise.testsupport.RoleProbeController;

import tools.jackson.databind.json.JsonMapper;

/**
 * Shared base for integration tests: one real MySQL 8.4 container and one Spring context for the whole suite.
 * Tests share the database, so every test creates its own uniquely named data.
 *
 * <p><b>Least privilege (Phase 6).</b> The container is provisioned with the repository's own
 * {@code docker/mysql/provision-users.sql}: Flyway migrates as {@value #MIGRATION_USER} and the application runs as
 * {@value #RUNTIME_USER}, which only holds the table grants applied by {@code RuntimeGrants} (e.g. no UPDATE/DELETE on
 * {@code audit_logs}). Every integration test therefore proves the application works with the production privilege
 * model. {@link #jdbc} is a fixture/assertion connection with migration-user rights; {@link #runtimeJdbc} is the
 * application's own connection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({AbstractIntegrationTest.ClockConfig.class, RoleProbeController.class})
public abstract class AbstractIntegrationTest {

    protected static final String ROOT_ADMIN_EMAIL = "root.admin@example.com";
    protected static final String ROOT_ADMIN_PASSWORD = "Integration-Admin-Pass-2026";
    protected static final String DEFAULT_PASSWORD = "Regular-User-Pass-2026";
    protected static final Instant START = Instant.parse("2026-03-01T09:00:00Z");

    static final String MIGRATION_USER = "ea_migrator";
    static final String RUNTIME_USER = "ea_app";
    static final String MIGRATION_PASSWORD = "migrator-test-password";
    static final String RUNTIME_PASSWORD = "runtime-test-password";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
        provisionLeastPrivilegeUsers();
    }

    /** Runs the repository's provisioning script as root, exactly as a DBA would. */
    private static void provisionLeastPrivilegeUsers() {
        try {
            String script = Files.readString(Path.of("..", "docker", "mysql", "provision-users.sql"))
                    .replace("${SCHEMA}", MYSQL.getDatabaseName())
                    .replace("${HOST}", "%")
                    .replace("${MIGRATION_USER}", MIGRATION_USER)
                    .replace("${MIGRATION_PASSWORD}", MIGRATION_PASSWORD)
                    .replace("${RUNTIME_USER}", RUNTIME_USER)
                    .replace("${RUNTIME_PASSWORD}", RUNTIME_PASSWORD);
            try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                 Statement statement = root.createStatement()) {
                for (String sql : script.replaceAll("(?m)^--.*$", "").split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Could not provision least-privilege test users", ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.user", () -> MIGRATION_USER);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("app.db.runtime-user", () -> RUNTIME_USER);
        registry.add("app.db.verify-least-privilege", () -> "true");
        // The shared (MySQL) rate-limit store is exercised by every test; the generous limits live in
        // config/application.yml so RateLimitIntegrationTest can override them.
        registry.add("app.security.rate-limit.store", () -> "jdbc");
        registry.add("app.bootstrap.admin.email", () -> ROOT_ADMIN_EMAIL);
        registry.add("app.bootstrap.admin.password", () -> ROOT_ADMIN_PASSWORD);
        // Every test logs in from the same MockMvc address; per-IP limiting is covered by unit tests.
        registry.add("app.security.login-protection.max-failures-per-client", () -> "1000");
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected MutableClock clock;
    /** Fixture/assertion connection with migration-user rights (tests may set up data the app itself cannot). */
    protected static final JdbcTemplate jdbc = new JdbcTemplate(
            new DriverManagerDataSource(MYSQL.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD));
    /** The application's own (least-privilege) connection. */
    @Autowired protected JdbcTemplate runtimeJdbc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RoleRepository roleRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JsonMapper json;

    @BeforeEach
    void resetSharedClock() {
        clock.set(START);
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected User createUser(String email, RoleName... roles) {
        User user = new User(email, passwordEncoder.encode(DEFAULT_PASSWORD), "Test", roles[0].name());
        for (RoleName role : roles) {
            user.addRole(roleRepository.findByName(role).orElseThrow());
        }
        return userRepository.save(user);
    }

    /** Creates a user with the given roles and returns a fresh access token for them. */
    protected String tokenForNewUser(RoleName... roles) throws Exception {
        User user = createUser(unique(roles[0].name().toLowerCase()) + "@example.com", roles);
        return accessToken(user.getEmail(), DEFAULT_PASSWORD);
    }

    protected String rootAdminToken() throws Exception {
        return accessToken(ROOT_ADMIN_EMAIL, ROOT_ADMIN_PASSWORD);
    }

    protected String accessToken(String email, String password) throws Exception {
        String body = json.writeValueAsString(Map.of("email", email, "password", password));
        String response = mockMvc.perform(post("/api/auth/login").header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asString();
    }

    protected static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
}
