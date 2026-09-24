package com.enterprise.admin.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wires the least-privilege model: Flyway (running as the migration user) grants runtime privileges after every
 * migration, and — when {@code app.db.verify-least-privilege} is on — the runtime connection proves at startup that
 * it cannot modify the audit trail.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseSecurityConfig {

    private final DatabasePrivilegeProperties properties;
    private final DataSource dataSource;

    @Bean
    FlywayConfigurationCustomizer runtimeGrantsCustomizer() {
        return configuration -> configuration.callbacks(new RuntimeGrants(properties));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyLeastPrivilege() {
        if (!properties.verifyLeastPrivilege()) {
            return;
        }
        boolean canUpdate = allowed("UPDATE audit_logs SET action = action WHERE 1 = 0");
        boolean canDelete = allowed("DELETE FROM audit_logs WHERE 1 = 0");
        if (canUpdate || canDelete) {
            throw new IllegalStateException("The runtime database user can modify audit_logs (update=" + canUpdate
                    + ", delete=" + canDelete + "); refusing to run. See docs/SECURITY.md, 'Database privilege model'.");
        }
        log.info("Least-privilege check passed: the runtime database user cannot update or delete audit rows");
    }

    /** Runs a statement that matches no rows, in a rolled-back transaction: only the privilege check matters. */
    private boolean allowed(String sql) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
                return true;
            } catch (SQLException ex) {
                if (ex.getErrorCode() == 1142) { // ER_TABLEACCESS_DENIED_ERROR
                    return false;
                }
                throw new IllegalStateException("Least-privilege probe failed: " + ex.getMessage(), ex);
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Least-privilege probe could not connect", ex);
        }
    }
}
