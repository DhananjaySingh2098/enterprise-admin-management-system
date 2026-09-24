package com.enterprise.admin.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enterprise.admin.config.DatabasePrivilegeProperties;
import com.enterprise.admin.config.RuntimeGrants;

import ch.qos.logback.classic.Level;

/**
 * One-shot database migration for production deployments (the {@code migrate} service in docker-compose.prod.yml):
 * runs Flyway with the <em>migration</em> account, applies the least-privilege runtime grants ({@link RuntimeGrants})
 * and exits. The long-running backend then uses only the runtime account and never holds DDL credentials.
 *
 * <p>Configuration (environment variables, or files of the same name in {@code SECRETS_DIR}, default
 * {@code /run/secrets} — Docker secrets): {@code DB_URL}, {@code FLYWAY_USER}, {@code FLYWAY_PASSWORD},
 * {@code DB_RUNTIME_USER}, optional {@code DB_RUNTIME_USER_HOST} (default {@code %}) and {@code FLYWAY_TARGET}.
 * Exit code 0 on success, 1 on any failure (with a message that never contains credentials).
 */
public final class MigrationMain {

    private static final Logger log = LoggerFactory.getLogger(MigrationMain.class);

    private MigrationMain() {
    }

    public static void main(String[] args) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        try {
            String runtimeUser = value("DB_RUNTIME_USER", "");
            if (runtimeUser.isBlank()) {
                throw new IllegalStateException("DB_RUNTIME_USER is required: the migration grants its privileges");
            }
            FluentConfiguration configuration = Flyway.configure()
                    .dataSource(required("DB_URL"), required("FLYWAY_USER"), required("FLYWAY_PASSWORD"))
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .validateOnMigrate(true)
                    .callbacks(new RuntimeGrants(new DatabasePrivilegeProperties(runtimeUser,
                            value("DB_RUNTIME_USER_HOST", "%"), false)));
            String target = value("FLYWAY_TARGET", "");
            if (!target.isBlank()) {
                configuration.target(target);
            }
            MigrateResult result = configuration.load().migrate();
            log.info("Migration finished: {} migration(s) applied, schema version {}", result.migrationsExecuted,
                    result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion);
            System.exit(0);
        } catch (RuntimeException ex) {
            // Flyway/JDBC messages name objects and versions, never passwords; still keep it to one line.
            log.error("Migration failed: {}", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().lines().findFirst().orElse(""));
            System.exit(1);
        }
    }

    private static String required(String name) {
        String value = value(name, "");
        if (value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    /** Environment variable, else a Docker secret file of the same name, else the fallback. */
    static String value(String name, String fallback) {
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String dir = System.getenv().getOrDefault("SECRETS_DIR", "/run/secrets");
        Path file = Path.of(dir, name);
        if (Files.isReadable(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8).strip();
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot read secret file for " + name);
            }
        }
        return fallback;
    }
}
