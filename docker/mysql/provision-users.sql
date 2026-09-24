-- Least-privilege database accounts for Enterprise Admin (run once per environment by a DBA, as an administrative
-- account). Placeholders are substituted by provision-users.sh (or by the integration-test base class).
--
--   ${MIGRATION_USER}  runs Flyway: schema-scoped DDL + DML, WITH GRANT OPTION so its afterMigrate callback can
--                      grant the runtime account exactly what it needs on each table. No global privileges, no
--                      CREATE USER, no access to other schemas.
--   ${RUNTIME_USER}    used by the running application. Created here with NO privileges; table-level grants
--                      (e.g. audit_logs = SELECT, INSERT only) are applied by the application's Flyway callback.

CREATE USER IF NOT EXISTS '${MIGRATION_USER}'@'${HOST}' IDENTIFIED BY '${MIGRATION_PASSWORD}';
CREATE USER IF NOT EXISTS '${RUNTIME_USER}'@'${HOST}' IDENTIFIED BY '${RUNTIME_PASSWORD}';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON `${SCHEMA}`.* TO '${MIGRATION_USER}'@'${HOST}' WITH GRANT OPTION;

-- The runtime account must never hold schema-wide privileges (they would override the table-level restrictions).
REVOKE IF EXISTS ALL PRIVILEGES ON `${SCHEMA}`.* FROM '${RUNTIME_USER}'@'${HOST}';
