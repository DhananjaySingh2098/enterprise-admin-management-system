#!/bin/bash
# Sourced by the official mysql image's entrypoint on FIRST start only (empty data volume), after MYSQL_DATABASE and
# the root password exist. Creates the least-privilege migration and runtime accounts from the repository template
# (docker/mysql/provision-users.sql). Existing volumes are never touched; for them run docker/mysql/provision-users.sh.
#
# Inputs: MYSQL_DATABASE, DB_MIGRATION_USER, DB_RUNTIME_USER (environment) and the Docker secrets
# /run/secrets/FLYWAY_PASSWORD (migration account) and /run/secrets/DB_PASSWORD (runtime account).
set -eo pipefail  # sourced by the entrypoint: no -u (it would leak into the entrypoint shell)

read_secret() { tr -d '\r\n' < "/run/secrets/$1"; }
migration_password="$(read_secret FLYWAY_PASSWORD)"
runtime_password="$(read_secret DB_PASSWORD)"
for value in "$MYSQL_DATABASE" "$DB_MIGRATION_USER" "$DB_RUNTIME_USER" "$migration_password" "$runtime_password"; do
  if [[ ! "$value" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "[provision] names and passwords must match [A-Za-z0-9._-]+ (use scripts/generate-secrets.sh)" >&2
    exit 1
  fi
done

sed -e "s/\${SCHEMA}/${MYSQL_DATABASE}/g" \
    -e "s/\${HOST}/%/g" \
    -e "s/\${MIGRATION_USER}/${DB_MIGRATION_USER}/g" \
    -e "s/\${RUNTIME_USER}/${DB_RUNTIME_USER}/g" \
    -e "s/\${MIGRATION_PASSWORD}/${migration_password}/g" \
    -e "s/\${RUNTIME_PASSWORD}/${runtime_password}/g" \
    /opt/enterprise-admin/provision-users.sql | docker_process_sql

echo "[provision] created ${DB_MIGRATION_USER} (migrations) and ${DB_RUNTIME_USER} (runtime, no privileges until migrated)"
