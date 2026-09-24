package com.enterprise.admin.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import lombok.extern.slf4j.Slf4j;

/**
 * Flyway {@code afterMigrate} callback, executed on the migration user's connection: grants the runtime user exactly
 * the table privileges it needs. Idempotent (runs after every migrate) and fails fast when a table has no entry, so
 * a new migration cannot silently leave the runtime user without — or with too many — privileges.
 *
 * <p>{@code audit_logs} is SELECT + INSERT only: the running application can never UPDATE or DELETE audit rows,
 * whatever its code does. Any UPDATE/DELETE previously granted on it is revoked.
 */
@Slf4j
public class RuntimeGrants implements Callback {

    /** Table → privileges for the runtime user. Keep in sync with docs/SECURITY.md ("Database privilege model"). */
    public static final Map<String, String> TABLE_PRIVILEGES;

    static {
        Map<String, String> grants = new LinkedHashMap<>();
        grants.put("users", "SELECT, INSERT, UPDATE");
        // UPDATE only because MySQL requires it for SELECT … FOR UPDATE: the ADMIN role row is locked to serialize
        // admin demotions/disables. The application never writes to roles (names are CHECK-constrained anyway).
        grants.put("roles", "SELECT, UPDATE");
        grants.put("user_roles", "SELECT, INSERT, DELETE");
        grants.put("refresh_tokens", "SELECT, INSERT, UPDATE, DELETE");
        grants.put("departments", "SELECT, INSERT, UPDATE");
        grants.put("employees", "SELECT, INSERT, UPDATE");
        grants.put("audit_logs", "SELECT, INSERT");
        grants.put("notifications", "SELECT, INSERT, UPDATE, DELETE");
        grants.put("user_preferences", "SELECT, INSERT, UPDATE");
        grants.put("organization_settings", "SELECT, UPDATE");
        grants.put("rate_limit_buckets", "SELECT, INSERT, UPDATE, DELETE");
        TABLE_PRIVILEGES = Map.copyOf(grants);
    }

    /** Owned by the migration user only; the runtime user needs no access. */
    private static final Set<String> MIGRATION_ONLY = Set.of("flyway_schema_history");

    private final DatabasePrivilegeProperties properties;

    public RuntimeGrants(DatabasePrivilegeProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE && properties.managesGrants();
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            String schema = connection.getCatalog();
            if (schema == null || !schema.matches("[A-Za-z0-9_]+")) {
                throw new IllegalStateException("Unexpected schema name for runtime grants");
            }
            Set<String> tables = tables(statement);
            Set<String> unmapped = new TreeSet<>(tables);
            unmapped.removeAll(TABLE_PRIVILEGES.keySet());
            unmapped.removeAll(MIGRATION_ONLY);
            if (!unmapped.isEmpty()) {
                throw new IllegalStateException("No runtime grant defined for table(s) " + unmapped
                        + "; add them to RuntimeGrants.TABLE_PRIVILEGES");
            }
            String grantee = "'" + properties.runtimeUser() + "'@'" + properties.runtimeUserHost() + "'";
            for (var entry : TABLE_PRIVILEGES.entrySet()) {
                if (tables.contains(entry.getKey())) {
                    statement.execute("GRANT " + entry.getValue() + " ON `" + schema + "`.`" + entry.getKey() + "` TO " + grantee);
                }
            }
            statement.execute("REVOKE IF EXISTS UPDATE, DELETE ON `" + schema + "`.`audit_logs` FROM " + grantee);
            log.info("Applied least-privilege grants for runtime user {} on {} table(s)", properties.runtimeUser(),
                    TABLE_PRIVILEGES.size());
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not apply runtime database grants: " + ex.getMessage(), ex);
        }
    }

    private static Set<String> tables(Statement statement) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (ResultSet rs = statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                tables.add(rs.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return tables;
    }

    @Override
    public String getCallbackName() {
        return "runtime-grants";
    }
}
