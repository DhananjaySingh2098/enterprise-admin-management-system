# Runbook

Day-2 operations for a running stack: health, backup and restore, rotation, incident response and recovery.
Installation and configuration are in [DEPLOYMENT.md](DEPLOYMENT.md).

Every command assumes the repository root and:

```bash
alias eac='docker compose -f docker-compose.prod.yml --env-file .env.production'
```

Substitute `eac` for the full command if you prefer. Commands that read a password do so from the secret **file**
inside the container, so no credential ever reaches your shell history, `ps` output or the terminal.

## Contents

- [Health at a glance](#health-at-a-glance)
- [Logs](#logs)
- [Backup](#backup)
- [Restore](#restore)
- [Rotating secrets](#rotating-secrets)
- [User administration](#user-administration)
- [Incidents](#incidents)
- [Upgrade and rollback](#upgrade-and-rollback)
- [Decommissioning](#decommissioning)

## Health at a glance

```bash
eac ps                                          # every service should read "healthy"
curl -fsS localhost:8088/api/health              # backend liveness
curl -fsS localhost:8088/api/health/db           # database reachability (503 + "DOWN" when MySQL is unreachable)
scripts/smoke-test.sh                            # full functional check (see DEPLOYMENT.md)
```

| Service | Healthcheck | Unhealthy means |
|---|---|---|
| mysql | `mysqladmin ping` as root | The database is not accepting connections |
| backend | `GET /api/health` inside the container | The JVM is not serving HTTP (**not** a database check) |
| frontend | `GET /` contains `<app-root` | nginx is not serving the SPA |
| migrate | none (one-shot) | Exit code ≠ 0 means migrations failed and the backend never started |

## Logs

```bash
eac logs -f backend                    # application log: one line per request-ish event, each with a request ID
eac logs -f frontend                   # nginx access log, including rid=<request id>
eac logs --since 1h | less
```

- Every response carries `X-Request-Id`; error bodies include `requestId`. A user quoting that ID lets you find the
  exact request in both logs.
- Logs never contain passwords, tokens, cookies or secret values — audit details are redacted at any nesting depth,
  and the containers have been checked for leakage.
- For a user-visible history of *who changed what*, use the in-app **Audit Logs** page (ADMIN) rather than the logs.

## Backup

The database holds everything that matters: accounts, employees, departments, audit trail, notifications,
preferences and settings. Back up the **secrets directory** separately (encrypted); without `MYSQL_ROOT_PASSWORD`
a dump cannot be restored into a fresh stack without extra work.

```bash
mkdir -p backups && chmod 700 backups
eac exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" exec mysqldump -u root \
  --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces \
  --databases enterprise_admin' | gzip > "backups/enterprise-admin-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
```

- `--single-transaction` gives a consistent snapshot without locking the application out (InnoDB).
- The dump contains personal data and password hashes: store it encrypted, restrict access, and keep it out of the
  repository (`backups/` and `*.sql.gz` are git-ignored).
- Verify a backup by restoring it into a throwaway stack (below) — an untested backup is not a backup.
- Suggested schedule: nightly dump, 30 daily copies plus 12 monthly, on storage separate from the Docker host.

## Restore

**Into a fresh stack** (new host, or after losing the volume). Tested end-to-end: counts, grants and sign-in all
come back.

```bash
eac down                                       # stop the stack (omit -v to keep the existing volume)
docker volume rm enterprise-admin_mysql-data   # only when you mean to discard the current data
eac up -d --wait mysql                         # provisions ea_migrator / ea_app on the empty volume
gunzip -c backups/enterprise-admin-20260922T120000Z.sql.gz \
  | eac exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" exec mysql -u root'
eac up -d --wait                               # migrate re-applies grants (and any newer migrations), then backend
scripts/smoke-test.sh                          # verify
```

Notes:

- Restore the dump **before** the backend starts, so the bootstrap step sees the restored ADMIN and skips.
- The dump carries no database accounts — those come from the secret files via the init script, so restore with the
  **same secrets directory**. Restoring with different `DB_PASSWORD`/`FLYWAY_PASSWORD` files simply re-provisions
  the accounts with the new passwords.
- Restoring an **older** dump into a newer release is supported: the migrate job brings the schema forward.
- To check a backup without touching production, restore into another project name and port:
  `docker compose -p ea-restore-test -f docker-compose.prod.yml --env-file .env.restore-test up -d --wait`
  (copy `.env.production`, change `HTTP_PORT`), then `eac down -v` that project when finished.

## Rotating secrets

### JWT signing key (`JWT_SECRET`)

Rotate on suspicion of exposure, or on a schedule. **Every user is signed out** (access tokens stop validating);
refresh sessions in the database survive but their access tokens do not, so browsers re-authenticate.

```bash
openssl rand -base64 48 | tr -d '\n' > secrets/JWT_SECRET && chmod 444 secrets/JWT_SECRET
eac up -d --force-recreate backend
```

### Application database password (`DB_PASSWORD`)

```bash
new="$(openssl rand -hex 24)"
eac exec -T mysql sh -c "MYSQL_PWD=\"\$(cat /run/secrets/MYSQL_ROOT_PASSWORD)\" exec mysql -u root \
  -e \"ALTER USER 'ea_app'@'%' IDENTIFIED BY '$new'; FLUSH PRIVILEGES;\""
printf '%s' "$new" > secrets/DB_PASSWORD && chmod 444 secrets/DB_PASSWORD
unset new
eac up -d --force-recreate backend
```

Brief 500s are possible between the `ALTER USER` and the restart; do it in a maintenance window. Rotate
`FLYWAY_PASSWORD` the same way for `ea_migrator` (no restart needed — the job reads it on the next run).

### MySQL root password

Change it inside the database first (`ALTER USER 'root'@'localhost' …`), then update
`secrets/MYSQL_ROOT_PASSWORD`, then recreate the mysql container so the healthcheck uses the new value. Note the
init script only runs on an empty volume, so the file alone changes nothing on an existing database.

### Administrator password

Not a secret rotation: sign in and change it under **Profile**. `ADMIN_PASSWORD` is read only when bootstrapping an
installation that has no ADMIN, so editing that file later has no effect. If every ADMIN password is lost, see
below.

## User administration

| Task | How |
|---|---|
| Add a user, assign roles, disable an account | In the app: **Users** (ADMIN only) |
| Someone forgot their password | An ADMIN cannot read passwords; create a new account for them, or set a hash directly (below). There is no self-service reset yet. |
| Every ADMIN password is lost | Write a known BCrypt hash into the database, then sign in and change it. |

Last resort, when no administrator can sign in. Generate a BCrypt hash offline (cost 12), for example with
`htpasswd -nbBC 12 x 'NewPassword'` (the part after `x:`), then pipe the statement in — never pass it as an argument,
where it would land in your shell history and in `ps`:

```bash
eac exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" exec mysql -u root enterprise_admin' <<'SQL'
UPDATE users SET password_hash = '$2y$12$REPLACE_WITH_YOUR_HASH' WHERE email = 'admin@example.com';
SQL
```

Such a change is *not* recorded in the audit trail (it bypasses the application), so note it in your own change log
and sign in and rotate the password immediately afterwards.

## Incidents

### The site is down

1. `eac ps` — which service is unhealthy?
2. **frontend down** → the browser gets nothing. `eac logs frontend`; `eac up -d --force-recreate frontend`.
3. **backend down** → the SPA still loads and `/api/**` returns a JSON `503`
   (`The service is temporarily unavailable. Please try again shortly.`). No stack traces or internals are exposed.
   `eac logs backend` and restart.
4. **mysql down** → `/api/health` still reports `UP` (liveness only), `/api/health/db` reports `DOWN` with 503, and
   API calls fail with a generic 500. Rate limiting fails open and logs
   `Rate-limit store unavailable … allowing request`. Start MySQL; the backend reconnects on its own — no restart
   needed (verified).

### A migration fails

The `migrate` job exits non-zero and **the backend is never started**, so a half-migrated schema is never served.

1. `eac logs migrate` — the last line names the failing version.
2. Restore the pre-upgrade backup (above) and pin the old image tag (`APP_VERSION`) to get back into service.
3. Fix the migration, then re-run just the job: `eac run --rm migrate`.

Flyway runs with `cleanDisabled(true)`; there is no command in this system that drops the schema.

### Suspected compromise

1. Rotate `JWT_SECRET` (signs everyone out) and `DB_PASSWORD`.
2. Review **Audit Logs** for the period: sign-ins, role changes, account changes — filter by actor, outcome or date.
3. Check nginx logs for the source addresses (`X-Forwarded-For` is overwritten by nginx, so the logged address is the
   one observed at the proxy, not one a client claimed).
4. Disable affected accounts in **Users**, and take a backup before changing anything else.

### Rate limiting is blocking legitimate users

Limits are per IP, per account and per user, stored in MySQL (`rate_limit_buckets`). A shared corporate NAT can hit
the per-IP login limit. Raise the specific policy with `SPRING_APPLICATION_JSON` on the backend service, e.g.

```yaml
SPRING_APPLICATION_JSON: '{"app":{"security":{"rate-limit":{"policies":{"login-ip":{"limit":200,"window":"1m"}}}}}}'
```

Prefer raising one policy over `RATE_LIMIT_ENABLED=false`. Counters expire on their own; clearing the table is
harmless but rarely needed.

## Upgrade and rollback

Upgrade: [DEPLOYMENT.md](DEPLOYMENT.md#upgrades). Always back up first.

Rollback within the same schema version: set `APP_VERSION` to the previous tag and `eac up -d --wait`.

Rollback **across** a schema change: there are no down-migrations by design. Restore the backup taken before the
upgrade into a stack running the old images.

## Decommissioning

```bash
eac down -v                       # stops everything and deletes the database volume — irreversible
rm -rf secrets                    # after securely archiving, if you may ever need the backups
```

Take and verify a final backup first. Dumps contain personal data: delete them under your retention policy.
