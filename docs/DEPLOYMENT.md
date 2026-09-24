# Deployment

How to run Enterprise Admin as a container stack: the production images, the compose topology, secrets, TLS, the
first install, upgrades, and how to verify a deployment. Day-2 operations (backup, restore, rotation, incidents)
are in the [runbook](RUNBOOK.md); the threat model and control list are in [SECURITY.md](SECURITY.md).

> This stack is designed to run behind your own TLS termination on a single Docker host. It does not deploy to,
> or depend on, any cloud provider.

## Contents

- [Topology](#topology)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Secrets](#secrets)
- [First install](#first-install)
- [Verifying a deployment](#verifying-a-deployment)
- [TLS](#tls)
- [Upgrades](#upgrades)
- [Database privilege model](#database-privilege-model)
- [Operational limits](#operational-limits)
- [Troubleshooting](#troubleshooting)

## Topology

```
                    ┌──────────── edge network ────────────┐
  browser ─HTTPS─►  │  your TLS proxy  ─HTTP─►  frontend   │      (nginx: SPA + /api reverse proxy, uid 101)
                    └──────────────────────────┬───────────┘
                                               │  internal network (internal: true — no host or outbound access)
                                    ┌──────────┴──────────┐
                                    │      backend        │      (Spring Boot, uid 10001, runtime DB user only)
                                    │      migrate        │      (one-shot Flyway job, migration DB user)
                                    │      mysql          │      (named volume mysql-data)
                                    └─────────────────────┘
```

- **Only the frontend publishes a port** (`HTTP_BIND:HTTP_PORT` → 8080 in the container). The backend and MySQL have
  no host ports and sit on a network declared `internal: true`.
- **The browser talks to one origin.** nginx serves the SPA and reverse-proxies `/api` to the backend, so there is no
  CORS in production and the refresh cookie stays same-origin.
- **Migrations run in their own container** (`migrate`) with the migration database account, then exit. The
  long-running backend never holds DDL privileges (`FLYWAY_ENABLED=false`).
- **Secrets are files**, mounted at `/run/secrets` and read through Spring's `configtree` import. They are never
  environment variables, build arguments or image layers.

Files: [`docker-compose.prod.yml`](../docker-compose.prod.yml), [`backend/Dockerfile`](../backend/Dockerfile),
[`frontend/Dockerfile`](../frontend/Dockerfile), [`frontend/nginx/`](../frontend/nginx/),
[`docker/mysql/`](../docker/mysql/).

## Prerequisites

| Requirement | Notes |
|---|---|
| Docker Engine 25+ with Compose v2 | `docker compose version` |
| ~2 GB RAM free | MySQL ~512 MB, backend ~512 MB (75 % of the container limit), nginx small |
| A TLS terminator | Load balancer, or a reverse proxy such as Caddy/nginx/Traefik on the host — see [TLS](#tls) |
| Nothing else | No JDK, Node or Maven on the host: both images build from source in their own build stage |

Images are built from the repository; no registry is required. Building is the only step that needs network access.

## Configuration

Non-secret settings live in `.env.production` (git-ignored), copied from
[`.env.production.example`](../.env.production.example):

```bash
cp .env.production.example .env.production
```

| Variable | Default | Purpose |
|---|---|---|
| `APP_VERSION` | `1.0.0` | Image tag to build and run |
| `MYSQL_DATABASE` | `enterprise_admin` | Schema name |
| `DB_MIGRATION_USER` / `DB_RUNTIME_USER` | `ea_migrator` / `ea_app` | The two database accounts |
| `DB_URL` | `jdbc:mysql://mysql:3306/enterprise_admin?sslMode=PREFERRED&serverTimezone=UTC` | Override to require TLS to an external database (`sslMode=VERIFY_IDENTITY`) |
| `ADMIN_EMAIL` | — (**required**) | Initial ADMIN, created only while no ADMIN exists |
| `ADMIN_FIRST_NAME` / `ADMIN_LAST_NAME` | `System` / `Administrator` | Display name of that account |
| `REFRESH_COOKIE_SECURE` | `true` | Keep `true`; `false` only for a plain-HTTP trial |
| `HSTS_ENABLED` | `true` | Backend HSTS; sent on HTTPS requests only |
| `ALLOWED_ORIGINS` | empty | Empty = same origin only, which is what the nginx proxy gives you |
| `HTTP_BIND` / `HTTP_PORT` | `127.0.0.1` / `8088` | Where nginx listens on the host. Keep `127.0.0.1` behind a host proxy; use `0.0.0.0` only when nothing else terminates TLS |
| `NGINX_TRUST_FORWARDED_PROTO` | `off` | Set `on` **only** when a TLS proxy in front sets `X-Forwarded-Proto` |
| `JDK_JAVA_OPTIONS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM tuning |

The stack reads no other file. `.env` (local development) is never used in production.

`ADMIN_EMAIL` has no default on purpose: `docker compose` refuses to start without it rather than inventing an
administrator account.

## Secrets

Five secret files, one value per file, generated once:

```bash
scripts/generate-secrets.sh          # creates ./secrets (0700; files 0444), never overwrites, never prints values
```

| File | Used by | Rotatable |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | mysql (init, healthcheck, backups) | With care — see the runbook |
| `FLYWAY_PASSWORD` | migrate (the `ea_migrator` account) | Yes |
| `DB_PASSWORD` | backend (the `ea_app` account) | Yes |
| `JWT_SECRET` | backend (HS256 access tokens) | Yes — signs every user out |
| `ADMIN_PASSWORD` | backend (initial admin only, on the very first start) | Not after first start (change the password in the app) |

**Injecting secrets from elsewhere.** `docker-compose.prod.yml` reads each secret from `${SECRETS_DIR:-./secrets}/NAME`,
so any tool that can write a file can supply them. Point `SECRETS_DIR` at a directory your secret manager renders
into — for example `vault agent`/`consul-template`, `sops -d`, a systemd credential directory, or a mounted tmpfs
populated at boot. Alternatively replace the `secrets:` block with `external: true` entries when you run Swarm.
Rules that hold either way:

- Never put these values in `.env.production`, a shell profile, CI logs or an image.
- Keep the directory at mode 0700, owned by the deploying user; the container users read the bind-mounted copies.
- Back the directory up separately from the database, and encrypted. Losing `JWT_SECRET` only signs users out;
  losing `MYSQL_ROOT_PASSWORD` costs you administrative access to the database.

## First install

```bash
scripts/generate-secrets.sh
cp .env.production.example .env.production      # set ADMIN_EMAIL, review HTTP_BIND/HTTP_PORT
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build --wait
```

What happens, in order:

1. **mysql** starts. On an empty volume its init script provisions the two least-privilege accounts from the secret
   files; the root account keeps no remote access beyond the container.
2. **migrate** runs Flyway as `ea_migrator` (V1…V6), applies the runtime grants, and exits 0.
3. **backend** starts as `ea_app`, verifies at startup that it *cannot* modify audit rows, and creates the initial
   ADMIN only while no ADMIN exists.
4. **frontend** starts and reports healthy once the SPA is served.

A cold start on a laptop-class machine takes about 40 s from `up` to all services healthy. Sign in at
`http://HTTP_BIND:HTTP_PORT/` (through your TLS proxy in production) with `ADMIN_EMAIL` and the generated
`ADMIN_PASSWORD`, then change that password in **Profile**.

## Verifying a deployment

```bash
BASE_URL=https://admin.example.com \
ADMIN_EMAIL=admin@example.com \
ADMIN_PASSWORD_FILE=./secrets/ADMIN_PASSWORD \
  scripts/smoke-test.sh
```

Eight groups of checks, through the public URL only: security headers and CSP on all ten SPA routes, asset caching
and gzip, 404s for missing assets/source maps/dotfiles, `/api` routing, request-ID propagation, login and the refresh
cookie's attributes, token rotation, and that a spoofed `X-Forwarded-For` is not trusted. It never prints secrets or
tokens, and exits 1 on the first failure. Over plain HTTP, set `EXPECT_SECURE_COOKIE=false`.

Also useful:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production ps        # all services healthy
curl -fsS https://admin.example.com/api/health                                  # liveness
curl -fsS https://admin.example.com/api/health/db                               # database reachability (503 when down)
```

## TLS

The stack speaks HTTP; terminate TLS in front of it. Two supported shapes:

**1. A proxy on the host (recommended).** Keep `HTTP_BIND=127.0.0.1` so nginx is unreachable from the network, and
point your proxy at `127.0.0.1:8088`. The proxy must set `X-Forwarded-Proto: https`, and you then set:

```
NGINX_TRUST_FORWARDED_PROTO=on
```

That, and only that, makes the stack treat requests as HTTPS: nginx then emits HSTS
(`max-age=31536000; includeSubDomains`) and the backend marks the refresh cookie `Secure`.

**2. TLS inside this nginx.** Add a `443` server block and your certificate to
[`frontend/nginx/default.conf.template`](../frontend/nginx/default.conf.template), publish 443, and leave
`NGINX_TRUST_FORWARDED_PROTO=off`.

Header trust is deliberate and verified: with the default `off`, a client-supplied `X-Forwarded-Proto: https` is
ignored and no HSTS is sent, so an attacker cannot poison it. nginx always overwrites `X-Forwarded-For`,
`X-Forwarded-Host` and `Forwarded` with values it observed, and the backend only parses them because it is
configured for exactly one proxy hop (`FORWARD_HEADERS_STRATEGY=framework`, reachable solely on the internal
network).

Never expose the backend or MySQL directly. If you must reach the database for maintenance, use
`docker compose exec mysql …` or an SSH tunnel; do not publish 3306.

## Upgrades

```bash
git pull
docker compose -f docker-compose.prod.yml --env-file .env.production build --pull
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --wait
```

Compose recreates `migrate` first (it runs to completion before the backend starts), so pending migrations are
applied exactly once, by the migration account, with the runtime grants re-applied afterwards. Existing data,
accounts, audit history and preferences are preserved — **always take a backup first**
([runbook](RUNBOOK.md#backup)); a failed migration leaves the old backend image ready to restart, but only a backup
protects the data.

Verified upgrade paths (tested with a copy of a real development database):

| From | To | Result |
|---|---|---|
| Schema at V4 (users, roles, departments, employees) | V6 | V5 and V6 applied; all rows preserved; sign-in works |
| Schema at V5 (plus audit logs, notifications, preferences, settings) | V6 | V6 applied; all rows preserved; sign-in works |

Bootstrap never touches an existing installation: when an ADMIN already exists, the log says
`Initial admin bootstrap skipped: an ADMIN account already exists` and no account is created or modified.

To pin a migration target (for a staged upgrade), run the job on its own:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production run --rm -e FLYWAY_TARGET=5 migrate
```

## Database privilege model

Two accounts, created by the MySQL init script and never shared:

| Account | Privileges | Held by |
|---|---|---|
| `ea_migrator` | DDL + DML on the schema, and `flyway_schema_history` | the `migrate` job only, for the seconds it runs |
| `ea_app` | SELECT/INSERT/UPDATE/DELETE per table, but **SELECT and INSERT only on `audit_logs`**; no DDL, no `CREATE USER`, no access to `flyway_schema_history` | the backend, continuously |

Grants are re-applied after every migration by a Flyway callback, so a new table cannot silently widen the runtime
account. The backend proves the model at startup (`DB_VERIFY_LEAST_PRIVILEGE=true`) and **refuses to start** if it
can modify audit rows — verified: starting the backend with the migration account's credentials fails with
`The runtime database user can modify audit_logs (update=true, delete=true); refusing to run.`

**Using an external MySQL instead of the bundled container:** create both accounts with
[`docker/mysql/provision-users.sql`](../docker/mysql/provision-users.sql) (or `provision-users.sh` against an
existing container), set `DB_URL` to that server with `sslMode=VERIFY_IDENTITY`, write the two passwords into the
secret files, and remove the `mysql` service and its `depends_on` entries from the compose file.

## Operational limits

| Concern | Behaviour |
|---|---|
| Scaling | The backend is stateless apart from MySQL; rate limits and sessions live in the database, so several instances behind one proxy work. MySQL is a single container: use managed/replicated MySQL for HA. |
| Resource limits | None are set in the compose file; add `deploy.resources.limits` to fit your host. The JVM already sizes its heap to 75 % of whatever limit it sees. |
| Logging | Both containers log to stdout (JSON-ish app logs with request IDs, nginx access logs with `rid=`). Ship them with your Docker logging driver; no log files are written inside the images. |
| Time | Containers run UTC. Timestamps in the API and audit trail are UTC. |
| Email/SMS | Not implemented — notifications are in-app only. |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `required variable ADMIN_EMAIL is missing a value` | `.env.production` has no `ADMIN_EMAIL`. |
| `migrate` exits 1 with `Migration failed` | Wrong `FLYWAY_PASSWORD`, or a schema not managed by Flyway. Nothing else starts; see [failed migration](RUNBOOK.md#a-migration-fails). |
| Backend exits with `JWT_SECRET is too short` / `is not set` | Regenerate the secret file (`openssl rand -base64 48`); the stack refuses weak or missing secrets rather than starting insecurely. |
| Backend exits with `Access denied for user 'ea_app'` | `DB_PASSWORD` does not match the account. Re-provision or fix the secret file. |
| Backend exits with `The runtime database user can modify audit_logs` | The backend was given the migration account. Point `DB_PASSWORD`/`DB_USERNAME` at `ea_app`. |
| `/api/**` returns `503 The service is temporarily unavailable` as JSON | The backend is down or still starting; the SPA keeps serving. Check `docker compose logs backend`. |
| `/api/health` is `UP` but requests fail with 500 | MySQL is unreachable: `/api/health` is liveness only; check `/api/health/db` and the mysql container. |
| Login works, then every request 401s | Clock skew between host and browser, or `JWT_SECRET` changed (which signs everyone out by design). |
| No HSTS on HTTPS | `NGINX_TRUST_FORWARDED_PROTO` is `off` while a proxy terminates TLS, or the proxy does not set `X-Forwarded-Proto`. |
| nginx: `/etc/nginx/conf.d is not writable` | The tmpfs mounts lost their `uid=101` options; keep them as shipped. |
| MySQL exits 137 on stop | The shutdown grace period was shortened; `stop_grace_period: 60s` is required for InnoDB to flush. |
