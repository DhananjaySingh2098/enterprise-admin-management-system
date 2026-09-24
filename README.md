# Enterprise Admin Management System

A secure, role-based administration platform for managing users, employees and organisational data: JWT
authentication with rotating refresh tokens, an append-only audit trail, a live analytics dashboard, in-app
notifications, and a premium light/dark interface — packaged as a hardened container stack with CI.

> **Status: v1.0.0.** All seven phases are complete: foundation; authentication and roles; user, employee and
> department management; the analytics dashboard and theme system; audit logs, notifications and settings; security
> hardening and full testing; Docker, CI/CD and release. See the [phase roadmap](#phase-roadmap) and the
> [changelog](CHANGELOG.md).

## Capabilities

Available now:

- Secure login with short-lived JWT access tokens, rotating refresh tokens and BCrypt password hashing
- Role-based access control: `ADMIN`, `MANAGER`, `USER`
- User administration (ADMIN), with self-protection and last-admin safeguards
- Employee and department management with server-side search, filters, sorting, pagination and conflict-safe edits
- A self-service profile, and a password change that signs out every other session
- A management analytics dashboard (KPIs, department headcount, status mix, hiring trend, recent hires,
  system-access overview), computed live from the database
- A premium responsive app shell: System/Light/Dark appearance, the Aurora, Obsidian, Pearl and Midnight presets,
  and subtle 3D depth

- An ADMIN-only append-only audit trail with filters, search and a detail drawer, plus in-app notifications
- Per-user appearance preferences and ADMIN organization settings, synced to the account
- Defence in depth: a strict CSP with no inline code, shared-store rate limiting, least-privilege database accounts,
  and a container stack whose backend and database are unreachable from the host

## Stack

| Layer | Technology |
|---|---|
| Frontend | Angular 22 (standalone components, signals, zoneless), TypeScript 6, Angular Router, SCSS design tokens, Vitest |
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Security, Data JPA, Validation), Nimbus JOSE+JWT, Lombok, Maven Wrapper |
| Database | MySQL 8.4 (Docker for local development), Flyway migrations |
| Testing | JUnit 6, Spring MockMvc, Testcontainers (real MySQL), Vitest, Playwright + axe-core |
| Infrastructure | Docker (multi-stage images, non-root), Docker Compose, nginx (SPA + `/api` proxy), GitHub Actions CI |

## Architecture

```
Angular SPA ──/api/**──► REST API ─► Controller ─► Service ─► Repository ─► MySQL
                          (Spring Security filter chain in front)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for layer responsibilities, the API and the authentication flow.

## Directory structure

```
enterprise-admin-management-system/
├── backend/                     Spring Boot API (Maven Wrapper: ./mvnw)
│   └── src/main/java/com/enterprise/admin/
│       ├── config/              CORS, Clock, scheduling
│       ├── controller/          Health, Auth, UserAdmin, Profile, Department, Employee
│       ├── dto/                 PageResponse, ApiErrorResponse, user/profile/department/employee records
│       ├── entity/              User, Role, RefreshToken, Department, Employee (+ enums)
│       ├── exception/           GlobalExceptionHandler, ApiException hierarchy, auth exceptions
│       ├── repository/          Spring Data repositories, Specifications, safe LIKE patterns
│       ├── security/            SecurityConfig, JWT service + filter, refresh cookie, login protection
│       └── service/             Auth, RefreshToken, UserAdmin, Profile, Department, Employee, paging helper
│   ├── src/main/resources/db/migration/   Flyway V1–V6
│   └── Dockerfile               Multi-stage production image (Temurin 21 → JRE, non-root)
├── frontend/                    Angular application
│   ├── proxy.conf.json          Dev proxy: /api → http://localhost:8080
│   └── src/
│       ├── styles/_tokens.scss  Design tokens (light + dark)
│       └── app/
│           ├── core/            ApiService, typed API clients, error parsing, theme, auth/
│           ├── shared/          UI kit, charts (bar/donut/line), tilt directive, ListQuery
│           ├── features/        login, dashboard, home (USER workspace), users, employees, departments, profile, …
│           └── layout/          App shell (sidebar, header) and role-aware navigation
│   ├── e2e/                     Playwright suite (isolated stack or a running deployment)
│   ├── nginx/                   Production server config: SPA, security headers, /api proxy
│   ├── security-headers.json    Single source of truth for the CSP and security headers
│   └── Dockerfile               Multi-stage production image (build → unprivileged nginx)
├── docs/                        Architecture, security, design system, deployment, runbook, phase plan
├── docker/mysql/                Least-privilege database account provisioning
├── scripts/                     Secret generation, smoke test, OSV dependency scan
├── .github/workflows/ci.yml     CI: tests, scans, E2E, container build and smoke test
├── docker-compose.yml           Local MySQL service (development)
├── docker-compose.prod.yml      Production stack: frontend, backend, migrate job, MySQL
├── .env.example                 Development configuration template (placeholders only)
├── .env.production.example      Production configuration template (no secrets)
├── CHANGELOG.md
└── README.md
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **Java** | **21** (e.g. Eclipse Temurin 21) | Required. The build targets Java 21; do not build with another JDK. |
| Maven | not required | Use the bundled wrapper `backend/mvnw` |
| Node.js | `^22.22.3`, `^24.15.0` or `>=26.0.0` | The range Angular 22 officially supports |
| npm | 10+ | |
| Angular CLI | not required globally | Use `npx ng …` inside `frontend/` |
| Docker | Docker Desktop with Compose v2 | Runs MySQL locally |

If your machine's default Java is not 21 (check with `java -version`), select 21 for the shell before running
backend commands. On macOS:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # must report 21.x
```

## Environment configuration

```bash
cp .env.example .env
# edit .env: set real local passwords (e.g. `openssl rand -hex 24`) and keep DB_* consistent with MYSQL_*
```

| Variable | Used by | Required | Purpose |
|---|---|---|---|
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD` | docker-compose | yes | Initialise the MySQL container |
| `MYSQL_PORT` | docker-compose | no | Host port for the local container |
| `DB_URL` | backend | yes | JDBC URL, e.g. `jdbc:mysql://127.0.0.1:3307/enterprise_admin` |
| `DB_USERNAME`, `DB_PASSWORD` | backend | yes | Application database credentials |
| `JWT_SECRET` | backend | **yes** | HS256 signing key, at least 32 random bytes (`openssl rand -base64 48`). Startup fails if it is missing or weak. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | backend | first start | Initial ADMIN account, created only while no ADMIN exists (password: 12 characters to 72 bytes) |
| `REFRESH_COOKIE_SECURE` | backend | no | Default `true`. Set `false` **only** for local plain-HTTP development. |
| `ALLOWED_ORIGINS` | backend | no | Comma-separated exact CORS origins (no wildcards). Not needed with the dev proxy. |
| `JWT_ACCESS_TOKEN_TTL`, `REFRESH_TOKEN_TTL`, `REFRESH_SESSION_MAX_LIFETIME`, `BCRYPT_STRENGTH` | backend | no | Defaults: `15m`, `7d`, `30d`, `12` |

`.env` is git-ignored. The backend reads it **only** under the `local` Spring profile, which
`./mvnw spring-boot:run` activates automatically, whether started from `backend/` or the project root. Real
environment variables always take precedence. Tests and deployed environments never read `.env`.

## Local database (MySQL in Docker)

```bash
docker compose up -d          # starts enterprise-admin-mysql (MySQL 8.4) with a healthcheck
docker compose ps             # wait for STATUS "healthy"
```

> **Port note:** the local mapping currently uses host port **3307** (`127.0.0.1:3307 → 3306`) because port 3306 is
> already taken by another MySQL installation on the development machine. This is a local convenience only, not a
> production requirement. Set `MYSQL_PORT` (and the port in `DB_URL`) to any free port. Production supplies its own
> `DB_URL`.

Data lives in the `mysql-data` Docker volume. `docker compose down` keeps it, and `docker compose down -v` deletes it.

## Run the backend

```bash
cd backend
./mvnw clean test          # unit, web-slice and Testcontainers MySQL integration tests (Docker required)
./mvnw spring-boot:run     # http://localhost:8080, local profile (needs the MySQL container running)
```

On startup:

1. Flyway applies any pending migrations (`db/migration`).
2. Hibernate validates the schema (`ddl-auto=validate`); it never creates, alters or drops tables.
3. If no ADMIN exists, the initial admin is created from `ADMIN_EMAIL` / `ADMIN_PASSWORD`. The log shows only the
   new user id, never the password. On later starts this step is skipped and existing accounts are never modified.

The integration tests start their own throwaway MySQL 8.4 container via Testcontainers. They do not touch the
local database or `.env`.

## Run the frontend

```bash
cd frontend
npm ci
npm start                  # http://localhost:4200 (proxies /api to :8080), then sign in with ADMIN_EMAIL
                           # (create MANAGER/USER accounts from Users → Add user)
npm run test:ci            # unit tests (Vitest), single run
npm run build              # production build → dist/frontend
npm run check:csp          # verifies the SPA Content-Security-Policy against src/ and the build
npm run e2e                # Playwright end-to-end suite (see below)
```

## End-to-end tests (Phase 6)

`npm run e2e` (in `frontend/`) runs the Playwright suite in the installed Google Chrome against an **isolated** stack:

1. `e2e/start-backend.mjs` drops and recreates the `enterprise_admin_e2e` schema on the local Docker MySQL, provisions
   least-privilege migration/runtime users with `docker/mysql/provision-users.sql`, and starts the backend (Java 21)
   on :8082. It needs the MySQL root password: `E2E_MYSQL_ROOT_PASSWORD`, or `MYSQL_ROOT_PASSWORD` from `.env`.
2. The production build of the SPA is served on :4202 with its real security headers and CSP.
3. `e2e/seed.setup.ts` seeds deterministic data through the API; specs cover authentication and session robustness,
   roles, CRUD, audit, notifications, themes/density, CSP, accessibility (axe-core) and responsive layouts.

The development schema is never touched. Reports: `frontend/e2e-report/` (git-ignored). Locally running servers on
:8082/:4202 are reused (`CI=1` forces fresh ones).

## Security checks (Phase 6)

```bash
cd backend && ./mvnw -Psecurity-scan -DskipTests verify     # SpotBugs + FindSecBugs (reviewed exclusions)
python3 scripts/security/osv-maven-scan.py                  # Maven dependencies vs OSV.dev (from the repo root)
cd frontend && npm audit && npm run check:csp               # npm advisories, SPA CSP
```

Least-privilege database accounts: `docker/mysql/provision-users.sh` (reads its inputs from the environment; see
`.env.example`), then run the backend with `FLYWAY_USER`/`FLYWAY_PASSWORD` (migration account),
`DB_USERNAME`/`DB_PASSWORD` (runtime account), `DB_RUNTIME_USER` and `DB_VERIFY_LEAST_PRIVILEGE=true`.
Production checklist: [docs/SECURITY.md](docs/SECURITY.md#production-checklist-phase-6).

## Run the whole stack in Docker (production-style)

One command brings up nginx + the SPA, the backend, a one-shot migration job and MySQL. Only the frontend gets a
host port; the backend and database sit on an internal network. Secrets are files, never environment variables.

```bash
scripts/generate-secrets.sh                        # ./secrets (0700), values never printed
cp .env.production.example .env.production         # set ADMIN_EMAIL; review HTTP_BIND / HTTP_PORT
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build --wait
open http://127.0.0.1:8088                          # sign in as ADMIN_EMAIL with the generated ADMIN_PASSWORD
```

```bash
BASE_URL=http://127.0.0.1:8088 ADMIN_EMAIL=admin@example.com \
  ADMIN_PASSWORD_FILE=./secrets/ADMIN_PASSWORD scripts/smoke-test.sh     # 8 groups of end-to-end checks
docker compose -f docker-compose.prod.yml --env-file .env.production down       # keeps the data volume
```

- Migrations run in their own container with a migration-only database account; the backend runs with a runtime
  account that cannot modify audit rows (and refuses to start if it can).
- Terminate TLS in front of the stack: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#tls). Operations —
  backup, restore, rotation, incidents — are in the [runbook](docs/RUNBOOK.md).

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request and push to `main`:

| Job | What it does |
|---|---|
| Backend | Java 21, Maven cache, `clean verify` (unit + Testcontainers MySQL) with SpotBugs + FindSecBugs |
| Dependencies | OSV scan of every resolved Maven artifact, `npm audit` (high fails for production deps, critical for all) |
| Frontend | Node 22 and 24: `npm ci`, CSP consistency check, unit tests, production build, CSP check of the build |
| Secret scan | gitleaks with `--redact` and a documented fixture allowlist ([`.gitleaks.toml`](.gitleaks.toml)) |
| E2E | MySQL service, isolated schema, cached Playwright Chromium, the full Playwright suite |
| Containers | Builds both images, reviews them (non-root, no build tooling or baked secrets), starts the stack and smoke-tests it |

## Health checks

| Endpoint | Auth | Success | Failure |
|---|---|---|---|
| `GET /api/health` | public | `200 {"status":"UP","service":"enterprise-admin-backend","timestamp":…}` | – |
| `GET /api/health/db` | public | `200 {"status":"UP","component":"database","responseTimeMs":2,"timestamp":…}` | `503` with `"status":"DOWN"` |

```bash
curl -s localhost:8080/api/health
curl -s localhost:8080/api/health/db
curl -s localhost:4200/api/health     # via the Angular dev proxy
```

Neither response ever includes connection details, usernames or error internals.

## Authentication

| Endpoint | Purpose |
|---|---|
| `POST /api/auth/login` | `{email, password}` → `200 {accessToken, tokenType, expiresIn, user}` plus the refresh cookie. `401 Invalid credentials` on any failure; `429` + `Retry-After` when temporarily locked. |
| `POST /api/auth/refresh` | Rotates the refresh cookie and returns a new access token. `204` when there is no session; `401` when the session is invalid (the cookie is cleared). |
| `POST /api/auth/logout` | Revokes the session and clears the cookie. Always `204`, and safe to repeat. |
| `GET /api/auth/me` | `Authorization: Bearer …` → `{id, email, firstName, lastName, roles}` |

The three `POST` endpoints require the header `X-Requested-With: XMLHttpRequest` (CSRF defence); the Angular app
sends it automatically.

- **Access token:** a JWT valid for 15 minutes, kept only in browser memory.
- **Refresh token:** an `HttpOnly`, `SameSite=Strict` cookie scoped to `/api/auth`. It is rotated on every use,
  stored only as a hash, and valid for 7 days within a 30-day absolute session.
- **Roles:** `ADMIN`, `MANAGER`, `USER`.
- **Repeated failed logins** trigger temporary lockouts (5 per account or 20 per IP within 15 minutes; 1 minute
  doubling to at most 15).

Every other `/api/**` endpoint requires a valid access token. Full details: [docs/SECURITY.md](docs/SECURITY.md).

## Administration & records (Phase 3)

| Area | Endpoints | Access |
|---|---|---|
| Users | `GET/POST /api/users`, `GET/PUT /api/users/{id}`, `PUT /api/users/{id}/roles`, `PUT /api/users/{id}/status` | ADMIN |
| Profile | `GET/PUT /api/profile`, `PUT /api/profile/password` | Signed-in user (own account only) |
| Departments | `GET /api/departments[/{id}]`, plus `POST`, `PUT /{id}`, `PUT /{id}/status` | Read: all roles · write: ADMIN |
| Employees | `GET /api/employees[/{id}]`, plus `POST`, `PUT /{id}`, and `PUT /{id}/status` | Read: all · write: ADMIN/MANAGER · status: ADMIN |

- Lists support `page`, `size` (at most 100), `search`, filters, `sort` (allowlisted) and `direction`, and return a
  standard `PageResponse`.
- Employee updates must send the `version` they read; a stale version returns `409 STALE_VERSION`.
- Departments are deactivated, never deleted.
- Admins cannot disable or demote themselves, or remove the last active ADMIN.

See [docs/SECURITY.md](docs/SECURITY.md#permission-matrix-implemented-phase-3) for the full permission matrix.

## Dashboard & analytics (Phase 4)

| Endpoint | Access | Returns |
|---|---|---|
| `GET /api/dashboard/summary` | ADMIN, MANAGER | Employee totals by status, department totals, hires in the recent-hire window (organization setting, default 30 days) |
| `GET /api/dashboard/headcount-by-department` | ADMIN, MANAGER | Active + on-leave employees per department (empty and inactive departments included) |
| `GET /api/dashboard/status-breakdown` | ADMIN, MANAGER | Counts for ACTIVE, ON_LEAVE, TERMINATED |
| `GET /api/dashboard/hiring-trend?months=12` | ADMIN, MANAGER | Hires per month, continuous, with zero months (1–24 months; future dates excluded) |
| `GET /api/dashboard/recent-employees?limit=5` | ADMIN, MANAGER | Latest hire dates up to today |
| `GET /api/dashboard/users` | ADMIN | Account totals and holders per role (a multi-role account counts in each of its roles) |

- Every figure is a live aggregate; there are no estimates or invented deltas.
- USER sees a personal workspace; the analytics endpoints return 403 for USER.
- Definitions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#dashboard-analytics-phase-4).
- Theme and motion rules: [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md#theme-system-phase-4).

## Audit, notifications & settings (Phase 5)

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/audit-logs` | ADMIN | Paged audit trail. Filters: `action`, `entityType`, `outcome`, `actor`, `from`/`to` (UTC dates), `search`; `sort` ∈ createdAt, action, actor, entityType, outcome |
| `GET /api/audit-logs/{id}` | ADMIN | One event: who, what, target, outcome, time, IP, request ID, redacted details |
| `GET /api/notifications?status=all\|unread` | any signed-in user | Own notifications, newest first |
| `GET /api/notifications/unread-count` | any signed-in user | Own unread count |
| `PUT /api/notifications/{id}/read`, `PUT /api/notifications/read-all` | any signed-in user | Mark own notifications read (404 for anyone else's) |
| `GET/PUT /api/preferences` | any signed-in user | Own appearance: `themeMode`, `themePreset`, `density` |
| `GET/PUT /api/settings/organization` | ADMIN | Organization name and recent-hire window (1–365 days), optimistically locked |
| `GET /api/settings/workspace` | any signed-in user | Organization name only |

- Audit rows are never updated or deleted by the application and never contain passwords, tokens or credentials.
- Every response carries `X-Request-Id`; error bodies include `requestId` so users can quote it.
- The UI adds **Audit Logs** (ADMIN), **Settings** (everyone), a notification bell and `/notifications`.
- Details: [docs/SECURITY.md](docs/SECURITY.md#audit-trail-implemented-phase-5) ·
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#audit-notifications-and-settings-phase-5).

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — layers, API, data model, request flow
- [Security](docs/SECURITY.md) — threat model, controls, permission matrix, production checklist
- [Deployment](docs/DEPLOYMENT.md) — container topology, secrets, TLS, installs and upgrades
- [Runbook](docs/RUNBOOK.md) — health, backup and restore, rotation, incidents
- [Design system](docs/DESIGN_SYSTEM.md) — tokens, components, motion and theming
- [Phase plan](docs/PHASE_PLAN.md) · [Changelog](CHANGELOG.md)

## Phase roadmap

1. **Foundation & Architecture** ✅
2. **Authentication, JWT & Role-Based Access** ✅
3. **User & Employee Management** ✅
4. **Premium Dashboard & Analytics** ✅
5. **Audit Logs, Notifications & Settings** ✅
6. **Security Hardening & Testing** ✅
7. **Docker, CI/CD & Release** ✅

Details: [docs/PHASE_PLAN.md](docs/PHASE_PLAN.md)
