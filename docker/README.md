# docker/

Container assets that are not part of a compose file itself.

## `mysql/`

Least-privilege database accounts. The application never uses `root` or a single all-powerful account: DDL belongs
to a migration account, and the running backend gets a runtime account that cannot modify audit rows.

| File | Purpose |
|---|---|
| `provision-users.sql` | The grant template (`${SCHEMA}`, `${MIGRATION_USER}`, `${RUNTIME_USER}`, …) — the single definition of both accounts |
| `provision-users.sh` | Applies that template to an **existing** MySQL container (local development, or an already-initialised volume). Reads everything from the environment so nothing lands in shell history |
| `initdb/10-provision-users.sh` | Runs automatically on a **fresh** production volume: reads the passwords from `/run/secrets` and applies the same template |

The production stack mounts `initdb/` and the template into the MySQL container, which installs the script into
`/docker-entrypoint-initdb.d/` at start (see the `mysql` service in
[`docker-compose.prod.yml`](../docker-compose.prod.yml)). After every migration a Flyway callback re-applies the
runtime grants, so a newly created table cannot silently widen the runtime account's access.

Details: [docs/SECURITY.md](../docs/SECURITY.md) · [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md#database-privilege-model)

## Where the rest lives

| Concern | File |
|---|---|
| Local MySQL for development | [`docker-compose.yml`](../docker-compose.yml) |
| Production stack (frontend, backend, migrate job, MySQL) | [`docker-compose.prod.yml`](../docker-compose.prod.yml) |
| Test-only overrides for running the E2E suite against the stack | [`docker-compose.e2e.yml`](../docker-compose.e2e.yml) |
| Backend image | [`backend/Dockerfile`](../backend/Dockerfile) |
| Frontend image and nginx configuration | [`frontend/Dockerfile`](../frontend/Dockerfile), [`frontend/nginx/`](../frontend/nginx/) |
| Secret generation | [`scripts/generate-secrets.sh`](../scripts/generate-secrets.sh) |
