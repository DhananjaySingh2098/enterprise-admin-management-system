#!/usr/bin/env bash
# Creates the least-privilege migration and runtime accounts in the local Docker MySQL.
# Reads everything from the environment (never from arguments, so nothing leaks into shell history or `ps`):
#   MYSQL_ROOT_PASSWORD, MYSQL_DATABASE,
#   DB_MIGRATION_USER, DB_MIGRATION_PASSWORD, DB_RUNTIME_USER, DB_RUNTIME_PASSWORD
# Optional: MYSQL_CONTAINER (default enterprise-admin-mysql), DB_USER_HOST (default %).
set -euo pipefail
: "${MYSQL_ROOT_PASSWORD:?}" "${MYSQL_DATABASE:?}" "${DB_MIGRATION_USER:?}" "${DB_MIGRATION_PASSWORD:?}" \
  "${DB_RUNTIME_USER:?}" "${DB_RUNTIME_PASSWORD:?}"
container="${MYSQL_CONTAINER:-enterprise-admin-mysql}"
# Generated secrets only (e.g. `openssl rand -hex 24`): keeps quoting in SQL and sed trivially safe.
for value in "$DB_MIGRATION_PASSWORD" "$DB_RUNTIME_PASSWORD" "$DB_MIGRATION_USER" "$DB_RUNTIME_USER" "$MYSQL_DATABASE"; do
  [[ "$value" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Names and passwords must match [A-Za-z0-9._-]+" >&2; exit 1; }
done
host="${DB_USER_HOST:-%}"
here="$(cd "$(dirname "$0")" && pwd)"

sed -e "s/\${SCHEMA}/${MYSQL_DATABASE}/g" \
    -e "s/\${HOST}/${host}/g" \
    -e "s/\${MIGRATION_USER}/${DB_MIGRATION_USER}/g" \
    -e "s/\${RUNTIME_USER}/${DB_RUNTIME_USER}/g" \
    -e "s/\${MIGRATION_PASSWORD}/${DB_MIGRATION_PASSWORD}/g" \
    -e "s/\${RUNTIME_PASSWORD}/${DB_RUNTIME_PASSWORD}/g" \
    "$here/provision-users.sql" \
  | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -i -e MYSQL_PWD "$container" mysql -u root

echo "Provisioned ${DB_MIGRATION_USER} (migrations) and ${DB_RUNTIME_USER} (runtime) on ${MYSQL_DATABASE}."
echo "Start the backend with FLYWAY_USER/FLYWAY_PASSWORD = migration account, DB_USERNAME/DB_PASSWORD = runtime"
echo "account, DB_RUNTIME_USER=${DB_RUNTIME_USER} and DB_VERIFY_LEAST_PRIVILEGE=true."
