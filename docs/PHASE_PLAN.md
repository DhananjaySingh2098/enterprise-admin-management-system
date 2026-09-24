# Phase Plan

Each phase ends with a verified, working build and requires explicit approval before the next phase starts.

| Phase | Name | Status |
|---|---|---|
| 1 | Foundation & Architecture | ✅ Complete |
| 2 | Authentication, JWT & Role-Based Access | ✅ Complete |
| 3 | User & Employee Management | ✅ Complete |
| 4 | Premium Dashboard & Analytics | ✅ Complete |
| 5 | Audit Logs, Notifications & Settings | ✅ Complete |
| 6 | Security Hardening & Testing | ✅ Complete |
| 7 | Docker, CI/CD & Release | ✅ Complete (v1.0.0 prepared; awaiting approval to commit, tag and release) |

## Phase 1 — Foundation & Architecture

- Spring Boot 4 / Java 21 backend with a layered package structure and Maven Wrapper
- Environment-driven configuration and a local MySQL 8.4 Docker service
- `GET /api/health` and `GET /api/health/db`
- A deny-by-default Spring Security foundation, JSON 401/403, and a global error handler
- Angular 22 standalone app (`core/`, `shared/`, `features/`, `layout/`), an API service foundation, and a dev proxy
- Design tokens (light and dark), plus architecture, security, design-system and phase documentation

## Phase 2 — Authentication, JWT & Role-Based Access

- Flyway migrations `V1__create_auth_schema.sql` (`users`, `roles`, `user_roles`, `refresh_tokens`) and
  `V2__seed_roles.sql`. Hibernate only validates the schema.
- A closed `RoleName` enum (`ADMIN`, `MANAGER`, `USER`) backed by a database CHECK constraint
- The initial ADMIN is created from `ADMIN_EMAIL` / `ADMIN_PASSWORD`, only while no admin exists, and never
  overwrites an existing account
- BCrypt (cost 12) and a password policy of 12 characters to 72 bytes
- Endpoints: `POST /api/auth/login`, `/refresh` and `/logout`, plus `GET /api/auth/me`
- JWT access tokens: HS256, 15 minutes, `typ=at+jwt`, minimal claims, strict validation, and startup fails on a
  missing or weak `JWT_SECRET`
- Refresh tokens: 256-bit random values stored as SHA-256 hashes, rotated on every use with family reuse detection,
  a 7-day TTL within a 30-day absolute session, and an `HttpOnly` / `SameSite=Strict` / `Path=/api/auth` cookie
  that is `Secure` by default
- Deny-by-default Spring Security, a JWT filter, `@PreAuthorize`, and JSON 401/403/429
- Login-abuse protection: bounded, temporary, exponentially growing lockouts per account and per IP
- Angular: login page, signal-based `AuthService` with the token held in memory, a refresh-coordinating
  interceptor, `authGuard` / `guestGuard` / `roleGuard`, session restore on startup, and a minimal welcome page
- Tests:
  - backend: unit tests, web-slice tests, and a Testcontainers MySQL 8.4 integration suite
  - Angular: unit tests for the service, interceptor, guards, login and home

## Phase 3 — User & Employee Management

- Flyway `V3__create_departments_and_employees.sql`:
  - unique department code and unique employee code, email and user link
  - `ON DELETE RESTRICT` from employees to departments, and `SET NULL` from employees to users
  - a CHECK constraint on status, and an `@Version` column
- Flyway `V4__auth_admin_support.sql`: user-list indexes, and the `PASSWORD_CHANGED` revocation reason (additive
  only)
- ADMIN-only user administration:
  - paginated, searched, filtered and sorted list, plus user details
  - create with BCrypt initial password and strict role allowlist, edit details, replace roles, enable or disable
    (disabling revokes sessions)
- Admin safeguards (self-disable, self-demotion, last active ADMIN), serialized with a row lock
- Self-service profile, and a password change that revokes every other session
- Departments: CRUD plus deactivation instead of delete, with a real headcount
- Employees: CRUD plus status changes, filters, allowlisted sorting and optimistic locking
- Standard `PageResponse`, and error `code`/`fieldErrors` extensions
- Angular:
  - premium app shell: role-aware sidebar, page header, theme control, user chip, mobile off-canvas navigation
  - Users, Employees, Departments and Profile pages, with create/edit drawers and confirm dialogs
  - mobile card tables, toasts, and motion that respects reduced-motion settings
- Tests:
  - backend: 127, including Testcontainers MySQL suites for users, profile, employees and departments
  - Angular: 94

## Phase 4 — Premium Dashboard & Analytics

- Read-only `/api/dashboard/*` aggregates: summary, headcount by department, status breakdown, a hiring trend
  (12–24 continuous months) and recent hires for ADMIN/MANAGER, plus system-account analytics for ADMIN only.
  USER gets 403.
- All metrics come from real rows through database aggregation (1–3 SQL statements per endpoint), using existing
  indexes only (EXPLAIN-verified).
- Dashboard page:
  - a compact hero with a real greeting and context
  - four KPI cards with shares derived from real counts
  - department bars, a status donut and a hiring-trend line/area chart with a data table
  - recent hires, and an ADMIN system-access panel
  - onboarding empty states; USER sees the personal workspace
- Theme system: System/Light/Dark × Aurora, Obsidian, Pearl and Midnight presets, with an accessible popover, local
  persistence and no-flash boot.
- Motion: staggered entrances, chart reveals, and 3D tilt with a pointer spotlight on KPI cards (fine pointer only,
  honours reduced motion).
- Tests:
  - backend: 139 (dashboard unit tests, plus MySQL integration with fixture deltas, an empty database,
    multi-role semantics, roles and query counts)
  - Angular: 119

## Phase 5 — Audit Logs, Notifications & Settings

Delivered:

- **Audit trail** (Flyway V5, `audit_logs`): append-only record of sign-ins, sign-in failures, sign-outs, token-reuse
  revocations and every user, employee, department and settings change. Written in the same transaction as the change
  (failed attempts in their own). Details are redacted twice. ADMIN-only API `GET /api/audit-logs` (filters, search,
  date range, allowlisted sort, paging) and `GET /api/audit-logs/{id}`. Audit Logs page with a detail drawer.
- **Request IDs**: `X-Request-Id` on every response, in every log line, in audit events and in error bodies.
- **Notifications** (`notifications`): generated by real account and record changes, principal-scoped API
  (list, unread count, mark read, mark all). Header bell with popover, and a `/notifications` timeline.
- **Preferences** (`user_preferences`): theme mode, preset and density per user, synced to the account with no-flash
  local application. New Comfortable/Compact density.
- **Organization settings** (`organization_settings`, ADMIN): organization name (shown in the header breadcrumb) and
  the dashboard's recent-hire window (previously a hard-coded 30 days).
- **Settings page**: appearance, account shortcuts, and an ADMIN-only organization section.
- Tests:
  - backend: 171 (audit, notification, settings and redaction suites against MySQL, plus unit tests)
  - Angular: 165
- Out of scope, as agreed: email delivery, report export, distributed rate limiting, final CSP, deployment.

## Phase 6 — Security Hardening & Testing

Delivered (details in [SECURITY.md](SECURITY.md)):

- **Headers & CSP:** API lock-down CSP, no framing, `no-referrer`, Permissions-Policy, COOP/CORP, HSTS on HTTPS only.
  SPA CSP with `script-src 'self'` + the boot script's SHA-256 and `style-src 'self'` (no `unsafe-inline`); component
  styles moved to the global stylesheet; `npm run check:csp` guards against drift.
- **Rate limiting:** per-IP / per-user / per-account policies in a MySQL-backed store shared by every instance (V6
  `rate_limit_buckets`); reads never limited; safe `429 RATE_LIMITED` with `Retry-After`.
- **Session robustness:** aborted-refresh recovery (V6 `replaced_by_id`, `SUPERSEDED`), one bounded restore retry,
  transient failures never sign out, late refresh results after logout discarded, safe `returnUrl` after sign-in.
- **Least privilege:** migration vs runtime DB accounts, per-table runtime grants applied after every migration,
  `audit_logs` SELECT/INSERT only, startup self-check; the whole integration suite runs under this model.
- **Authorization:** URL role rules in the filter chain mirroring `@PreAuthorize` (403 before request parsing); full
  endpoint × role matrix test; unknown JSON fields rejected (mass assignment).
- **Quality gates:** SpotBugs + FindSecBugs profile, OSV dependency scan (Tomcat patched to 11.0.26), `npm audit`,
  strict TypeScript + strict templates.
- **E2E:** Playwright suite (26 tests) on an isolated schema with the production build and real CSP: auth/session
  (incl. aborted refresh), roles, CRUD, audit, notifications, themes/density, CSP, accessibility (axe), responsive.
- Bugs found and fixed: role checkboxes hidden in the user dialog (Phase 5 CSS collision), badge contrast below AA,
  appearance change lost when closing within the save debounce, recent-hires table widening the page on phones,
  refresh-abort sign-out, validation-before-authorization leak.
- Tests: backend 200 (all integration tests as the least-privilege runtime user), Angular 182, E2E 26.

## Phase 7 — Docker, CI/CD & Release

- Production Dockerfiles for the backend and frontend, and a full-stack compose setup
- GitHub Actions workflows: build, test, scan and image publish
- Release configuration, environment documentation, and a deployment runbook

## Phase 7 — Docker, CI/CD & Release

Delivered (details in [DEPLOYMENT.md](DEPLOYMENT.md) and [RUNBOOK.md](RUNBOOK.md)):

- **Images:** multi-stage backend build (Temurin 21, cached dependency layer, layered jar extraction, non-root
  uid 10001, healthcheck, `MaxRAMPercentage`) and frontend build served by unprivileged nginx (uid 101) with a
  read-only root filesystem and tmpfs mounts. No sources, build tooling, shells, source maps or secrets in either
  runtime image.
- **Production compose:** frontend, backend, a one-shot `migrate` job and MySQL; named volume; `internal: true`
  network for backend and database; only the frontend publishes a port; healthchecks everywhere; MySQL given a 60 s
  shutdown grace period.
- **Secrets:** five Docker secret files read through Spring's `configtree`, generated by `scripts/generate-secrets.sh`,
  never in images, environment files or build arguments; injection from a secret manager documented.
- **nginx:** SPA fallback, gzip, immutable caching for hashed assets and `no-cache` for `index.html`, the approved
  security headers and CSP, `/api` reverse proxy with self-set forwarded headers, a JSON 503 when the backend is
  down, and HSTS only for genuine HTTPS (`NGINX_TRUST_FORWARDED_PROTO`).
- **One CSP source of truth:** `frontend/security-headers.json` generates the dev-server headers and the nginx
  snippet; `npm run check:csp` fails on any drift between source, dev server, nginx and the built output.
- **CI** (`.github/workflows/ci.yml`, actionlint-clean): backend tests + SpotBugs/FindSecBugs, OSV and `npm audit`
  scans, frontend tests/build/CSP on Node 22 and 24, redacted gitleaks scan, Playwright E2E with browser caching,
  and a container job that builds, reviews, starts and smoke-tests the stack.
- **Verification:** clean install and upgrade (V4→V6 and V5→V6 with a copy of real data), backup and restore into a
  fresh stack, 8-group smoke test, the Playwright suite through production nginx, failure modes (MySQL down, backend
  down, weak/missing/wrong secrets, over-privileged database account, invalid configuration), proxy-trust and HSTS
  behaviour, and a log check for secret leakage.
- **Release:** version 1.0.0 across the pom, `package.json` and the README; `CHANGELOG.md` and draft release notes;
  deployment guide and operations runbook.
- Tests: backend 200, Angular 182, E2E 26 (run both locally and through the production stack).
- Bug found and fixed: MySQL was SIGKILLed on shutdown (exit 137) under the default 10 s grace period.
- Out of scope, as agreed: cloud deployment, image publishing to a registry, and any product feature work.
