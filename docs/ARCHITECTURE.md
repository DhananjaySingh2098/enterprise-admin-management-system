# Architecture

Enterprise Admin Management System is a two-tier web application: an Angular single-page application talking to a
stateless Spring Boot REST API backed by MySQL.

```
┌──────────────────────────────┐
│  Angular 22 SPA (browser)    │  standalone components, signals, lazy routes
└──────────────┬───────────────┘
               │  HTTPS · JSON · relative /api/** paths
               ▼
┌──────────────────────────────┐
│  REST API (Spring Boot 4)    │  Spring Security filter chain in front of every request
├──────────────────────────────┤
│  Controller                  │  HTTP ↔ DTO mapping, input validation
│      ↓                       │
│  Service                     │  business rules, transactions, authorization decisions
│      ↓                       │
│  Repository                  │  Spring Data JPA, persistence only
└──────────────┬───────────────┘
               │  JDBC (HikariCP pool)
               ▼
┌──────────────────────────────┐
│  MySQL 8.4                   │  enterprise_admin schema
└──────────────────────────────┘
```

## Layers

### Angular (frontend)

The browser application. It owns presentation, routing, and client-side state. It never talks to the database and
never holds secrets.

| Folder | Responsibility | Rules |
|---|---|---|
| `core/` | App-wide singletons: `ApiService`, typed API clients (`core/api/`: users, employees, departments, profile), error parsing (`parseApiError`, `applyServerErrors`), `ThemeService`, and `core/auth/` (`AuthService`, `authInterceptor`, guards) | Services are `providedIn: 'root'`. Nothing in `core/` imports from `features/`. |
| `shared/` | Charts (`BarChart`, `DonutChart`, `LineChart`, pure `chart-utils`), `TiltDirective`, reusable UI (`Icon`, `Avatar`, `Badge`, `Pagination`, `SortHeader`, `Dialog`/drawer, `ConfirmDialog`, toasts, `PageHeader`, `EmptyState`), `ListQuery` + `pagedResource` (server-side list state), and label maps | No feature knowledge. |
| `features/` | One lazily loaded folder per area: `auth/login`, `dashboard` (analytics for ADMIN/MANAGER; it embeds the `home` workspace for USER), `users`, `employees`, `departments`, `profile`, `forbidden`, `not-found` | Features may use `core/` and `shared/`; the dashboard may reuse `home`. |
| `layout/` | `AppShell` (sidebar, top bar, toasts) and the role-aware navigation model | Hosts the protected child `<router-outlet>`. |

All HTTP calls go through Angular's `HttpClient` with `authInterceptor`. Paths are built by `ApiService` from
`API_BASE_URL` (default `/api`), so components never build URLs or hostnames. In development, the Angular dev server
proxies `/api` to the backend (`proxy.conf.json`), which keeps the SPA and the API on one origin. In deployed
environments, a reverse proxy or ingress serves both from one origin.

Global form and button primitives live in `src/styles/_forms.scss`, and design tokens in `src/styles/_tokens.scss`.

### REST API

The contract between frontend and backend: resource-oriented JSON over HTTP under `/api`.

- Success bodies are explicit DTO records. JPA entities are never serialized.
- Every error uses the same body: `timestamp`, `status`, `error`, `message`, `path`.
- Stack traces, exception class names, SQL and configuration are never returned.

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/health`, `GET /api/health/db` | public | Liveness and database reachability |
| `POST /api/auth/login` | public + `X-Requested-With` | Email/password → access token (body) + refresh cookie |
| `POST /api/auth/refresh` | public + refresh cookie + `X-Requested-With` | Rotate the refresh token, issue a new access token. `204` when there is no session cookie. |
| `POST /api/auth/logout` | public + `X-Requested-With` | Revoke the session and clear the cookie (idempotent, always `204`) |
| `GET /api/auth/me` | bearer token | Current user DTO: `id, email, firstName, lastName, roles` |
| `GET/POST /api/users`, `GET/PUT /api/users/{id}`, `PUT /api/users/{id}/roles`, `PUT /api/users/{id}/status` | ADMIN | User administration |
| `GET/PUT /api/profile`, `PUT /api/profile/password` | any role (self) | Own profile and password |
| `GET /api/departments[/{id}]` · `POST`, `PUT /{id}`, `PUT /{id}/status` | read: any role · write: ADMIN | Departments (no delete) |
| `GET /api/employees[/{id}]` · `POST`, `PUT /{id}` · `PUT /{id}/status` | read: any · write: ADMIN/MANAGER · status: ADMIN | Employees (no delete) |
| `GET /api/dashboard/{summary,headcount-by-department,status-breakdown,hiring-trend,recent-employees}` | ADMIN, MANAGER | Workforce analytics (read-only) |
| `GET /api/dashboard/users` | ADMIN | System-account analytics (aggregates only) |

List endpoints accept `page` (zero-based), `size` (1–100, default 20), `search`, resource-specific filters, `sort`
(an allowlisted key) and `direction` (`asc`/`desc`). They return
`PageResponse {content, page, size, totalElements, totalPages, first, last}`.

### Controller (`controller/`)

Adapts HTTP to the application:

- binds and validates input (`@Valid` + Jakarta Validation on request DTOs)
- delegates to one service call
- chooses the HTTP status, headers and cookies

Controllers contain no business logic and no repository access.

### Service (`service/`)

Business logic lives here. Services own transaction boundaries (`@Transactional`), enforce business rules and
invariants, and map between entities and DTOs. Services are the only layer that coordinates several repositories.

This layer holds:

- `AuthService`: login, refresh, logout, current user, `startSession`
- `RefreshTokenService`: issue, rotate with reuse detection, revoke, purge
- `UserAdminService`: user administration and the admin safeguards
- `ProfileService`: self-service profile and password change
- `DepartmentService` and `EmployeeService`: CRUD, field-level permission rules, optimistic locking
- `PasswordPolicy` and `InitialAdminInitializer`
- `support/PageRequestFactory`: sort allowlists and page bounds

### Repository (`repository/`)

Spring Data JPA interfaces over `entity/` classes: users, roles, refresh tokens, departments and employees. They
handle persistence only:

- queries, and JPA `Specification`s for search and filters (`*Specifications`, `SearchPatterns` for escaped `LIKE`)
- entity graphs (to-one fetches that are safe with pagination) and grouped headcount queries
- row locks for refresh rotation and admin safeguards

Lazy collections, such as a page of users' roles, load in batches (`default_batch_fetch_size: 50`) rather than one
query per row. Repositories contain no business decisions.

### MySQL

The system of record. The application connects as a least-privilege user that only has rights on the
`enterprise_admin` schema.

- **Flyway owns the schema.** Migrations live in `backend/src/main/resources/db/migration`:
  - `V1__create_auth_schema.sql`: `users`, `roles`, `user_roles`, `refresh_tokens`, with PKs, FKs, unique
    constraints, indexes and CHECK constraints
  - `V2__seed_roles.sql`: `ADMIN`, `MANAGER`, `USER`
  - `V3__create_departments_and_employees.sql`: `departments`, `employees` (FKs, unique keys, indexes, CHECK,
    version)
  - `V4__auth_admin_support.sql`: user-list indexes, and a widened refresh-token revocation-reason CHECK
- Hibernate runs with `ddl-auto=validate`: it checks entities against the migrated schema and never modifies it.
  `flyway.clean-disabled=true`.
- New schema changes are always a new `V<n>__description.sql`. Applied migrations are never edited.

## Cross-cutting backend packages

| Package | Contents |
|---|---|
| `config/` | CORS (`CorsProperties`, `CorsConfig`), `Clock`, scheduling |
| `security/` | `SecurityConfig`, `JwtService`, `JwtAuthenticationFilter`, `RefreshTokenCookieService`, `LoginAttemptService`, `TokenHasher`, typed properties (`JwtProperties`, `RefreshTokenProperties`, `PasswordProperties`, `LoginProtectionProperties`), JSON 401/403 handlers |
| `exception/` | `GlobalExceptionHandler`, `ApiException` subtypes (`ResourceNotFoundException` 404, `ConflictException` 409, `BadRequestException` 400, `ForbiddenOperationException` 403) and auth exceptions |
| `dto/` | `PageResponse`, `ApiErrorResponse`, auth and health DTOs; `dto/user`, `dto/profile`, `dto/department`, `dto/employee` request/response records (no entity is ever serialized; requests list exactly the fields they may change) |
| `entity/` | `User`, `Role`, `RoleName`, `RefreshToken`, `RefreshTokenRevocationReason`, `Department`, `Employee`, `EmployeeStatus` |

## Configuration

All environment-specific values come from environment variables. Required: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
and `JWT_SECRET`. Optional: `ALLOWED_ORIGINS`, `ADMIN_EMAIL` / `ADMIN_PASSWORD`, `REFRESH_COOKIE_SECURE`, token
lifetimes and `BCRYPT_STRENGTH`; see `.env.example`.

- The `local` Spring profile, which `./mvnw spring-boot:run` activates, additionally imports the git-ignored
  project-root `.env`. Real environment variables still take precedence.
- Tests and deployed environments never read `.env`. Tests use `src/test/resources/config/application.yml` and
  Testcontainers.
- Invalid security configuration fails startup with a clear message: a missing or weak `JWT_SECRET`, wildcard CORS
  origins, `SameSite=None` without `Secure`, or a weak `ADMIN_PASSWORD`.

## Authentication flow (Phase 2)

```
Login form ──POST /api/auth/login {email, password} + X-Requested-With──► AuthController → AuthService
                                        │ LoginAttemptService: locked? → 429 + Retry-After
                                        │ BCrypt verify (dummy hash for unknown email; generic 401 on any failure)
                                        │ RefreshTokenService: new family, store SHA-256(token)
                                        ▼
  ◄── 200 {accessToken (JWT, 15 min), tokenType, expiresIn, user} + Set-Cookie: ea_refresh_token (HttpOnly, SameSite=Strict, Path=/api/auth)

Every API call:  authInterceptor adds Authorization: Bearer <access token from memory>
                 JwtAuthenticationFilter → verify alg/typ/signature/exp/iss/aud → SecurityContext(user id, roles)
                 → URL rules (deny by default) → @PreAuthorize role checks → 403 if not permitted

401 on an API call → authInterceptor → ONE shared POST /api/auth/refresh (Web-Locks serialized across tabs)
                   → server: lock row, revoke old token (ROTATED), issue successor in same family
                   → retry the original request once; if refresh fails → clear state, navigate to /login
                   (a rotated token presented again after 30 s → whole family revoked: REUSE_DETECTED)

Page reload → provideAppInitializer → AuthService.initialize() → POST /api/auth/refresh
            → 200: session restored before the first route renders · 204/401: anonymous → guards send to /login

Logout → POST /api/auth/logout → family revoked (LOGOUT), cookie cleared (Max-Age=0), local state cleared
```

Roles are `ADMIN`, `MANAGER` and `USER`. See [SECURITY.md](SECURITY.md) for the full model, including cookie
behaviour in development and production, and the login-abuse design.

### Frontend auth state

`AuthService` is the only owner of authentication state:

- Signals: `status` (`initializing` / `authenticated` / `anonymous`), `user`, `isAuthenticated`, `roles` and
  `displayName`. The access token is a private in-memory field.
- Operations: `login`, `refresh` (coalesced, one in-flight request), `logout` (clears locally even if the server is
  unreachable), `loadCurrentUser` (`GET /me`) and `initialize` (startup restore, never rejects).
- Guards wait for `initializing` to finish, so protected content never flashes. `index.html` shows a neutral splash
  until the app boots.

## Dashboard analytics (Phase 4)

These endpoints are read-only and backed by `DashboardRepository`: JPQL `GROUP BY` / `COUNT` queries and DTO
projections, with no entity loading for counts. The integration suite asserts the statement count per request with
Hibernate statistics. The existing indexes (`ix_employees_status`, `ix_employees_hire_date`,
`ix_employees_department_id`) serve these queries, as confirmed with MySQL `EXPLAIN`, so no new indexes were added.

"Today" is the current UTC date from the application `Clock`. Hire dates are calendar dates.

| Endpoint | Contract | Definition | SQL statements |
|---|---|---|---|
| `summary` | `{employees:{total,active,onLeave,terminated}, departments:{total,active,inactive}, recentHires:{count,windowDays,from,to}, generatedAt}` | `total = active + onLeave + terminated` (every record). `recentHires` = hire date in the last 30 days, up to and including today. | 3 |
| `headcount-by-department` | `[{departmentId,name,code,active,headcount}]`, largest first | Headcount = ACTIVE + ON_LEAVE. LEFT JOIN keeps empty departments at 0. Inactive departments are included and flagged. | 1 |
| `status-breakdown` | `[{status,count}]`, always ACTIVE, ON_LEAVE, TERMINATED | Counts over every record, with zeros present | 1 |
| `hiring-trend?months=12` (1–24) | `{from,to,total,months:[{month:"YYYY-MM",hires}]}` | Continuous calendar months ending with the current month; empty months are an explicit 0. Hires dated after today are excluded. Terminated employees still count as hires in their hire month. | 1 |
| `recent-employees?limit=5` (1–10) | `[{id,employeeCode,firstName,lastName,departmentName,jobTitle,status,hireDate}]` | Latest hire dates ≤ today, newest first (ties broken by id). Department fetched in the same query. | 1 |
| `users` (ADMIN) | `{total,enabled,disabled,multiRoleUsers,roles:[{role,users,enabledUsers}]}` | `roles[].users` = distinct accounts **holding** the role. A multi-role account counts once per role, so role figures can exceed `total`; `multiRoleUsers` makes that explicit. No identities are included. | 3 |

The backend never returns percentages. The frontend derives shares from these counts (e.g. "81% of all employees"),
and a zero total shows 0%. There are no trend deltas or comparisons, because no historical snapshot exists to
compare against.

## Audit, notifications and settings (Phase 5)

**Schema (Flyway V5, additive):**
- `audit_logs`: append-only, no FK to users; actor and target are snapshots.
- `notifications`: FK to users, `ON DELETE CASCADE`.
- `user_preferences`: primary key = user ID, with CHECK constraints on every enum.
- `organization_settings`: singleton `id = 1`, a `version` column, and a 1–365 CHECK on the window; seeded with the
  previous 30-day window.

**Backend flow.** Services record audit events and notifications next to the change they describe:

```
Controller ─▶ Service (@Transactional)
               ├─ business change (saveAndFlush)
               ├─ AuditService.record(...)            MANDATORY  → same transaction
               └─ NotificationService.<event>(...)    MANDATORY  → same transaction
AuthService.login (failure) ─▶ AuditService.recordFailure  REQUIRES_NEW → survives the rollback
```

- The actor comes from the security context (`CurrentActor`). The IP comes from the current request, and the request
  ID from the MDC (`RequestIdFilter`).
- `AuditLogRepository`, `NotificationRepository` and `UserPreferencesRepository` extend Spring Data's plain
  `Repository` and declare only the operations they need: no deletes for audit, and principal-scoped queries for
  notifications.
- The dashboard reads the recent-hire window from `OrganizationSettingsService`: one extra fixed query per summary.

**Frontend.**
- `NotificationCenter` holds the unread count and recent items, shared by the header bell and `/notifications`.
  It polls the count every 60 s only while the tab is visible, plus on becoming visible, and marks items read
  optimistically.
- `PreferencesSync` keeps appearance in sync with the account:
  1. The boot script and `ThemeService` apply the local copy before first paint.
  2. After the session is known, it fetches the account preferences once per user. Saved values are applied
     instantly (a `theme-syncing` class suppresses transitions for two frames). If the account has never saved any,
     this browser's choice is adopted. If the user changes something while the fetch is in flight, the newer local
     choice wins.
  3. User changes are applied locally at once and saved with a 400 ms debounce. Values applied from the server are
     never echoed back.
- `Workspace` holds the organization name shown in the header breadcrumb.
- **Density**: `data-density="compact"` on `<html>`. `_density.scss` tightens table rows, card/panel padding and
  navigation rows only; font sizes and control heights stay the same.

**Role permissions:** see the permission matrix in [SECURITY.md](SECURITY.md#permission-matrix-implemented-phase-3).
Audit Logs and organization settings are ADMIN-only in the API. The Angular route guard and hidden navigation only
mirror that.

## Security hardening (Phase 6)

**Request pipeline** (backend):

```
RequestIdFilter (X-Request-Id, MDC)
  → Spring Security chain: CORS → JwtAuthenticationFilter (bearer ≤ 4 KB) → RateLimitFilter (RATE_LIMITED 429)
    → URL role rules (403 before parsing) → headers (CSP, HSTS on HTTPS, …)
  → MVC: DTO binding (unknown fields rejected) + Bean Validation → @PreAuthorize → service
```

- **Rate limiting:** `RateLimitFilter` maps routes to policies; `RateLimitService` hashes the subject and counts in a
  `RateLimitStore` — `JdbcRateLimitStore` (MySQL, atomic upsert, own transaction, shared by all instances) or
  `InMemoryRateLimitStore`. `AuthService` additionally counts failed logins per account (checked with `peek` before
  the password is verified).
- **Refresh tokens:** `refresh_tokens.replaced_by_id` links each rotated token to its replacement; recovery of an
  unused replacement within the grace period is described in SECURITY.md ("Refresh races").
- **Database accounts:** `spring.flyway.user/password` (migration account) are separate from the datasource
  (runtime account). `DatabaseSecurityConfig` registers the `RuntimeGrants` Flyway callback and the startup
  least-privilege probe. Provisioning SQL: `docker/mysql/provision-users.sql`.
- **Frontend:** strict TypeScript and strict templates; no component-level styles or inline style attributes (CSP);
  `AuthService` session epoch + single restore retry; `PreferencesSync` reconciles appearance by timestamp and
  flushes pending saves on `pagehide` (keepalive).
- **Tests:** see SECURITY.md ("Security test strategy"). `frontend/e2e/` holds the Playwright suite; its launcher
  builds an isolated schema per run.


## Deployment topology (Phase 7)

The same code runs in three shapes: local development (`ng serve` + `spring-boot:run`), the isolated E2E stack, and
the production container stack. Only the packaging differs — there is no production-only code path in the
application.

```
browser ──HTTPS──► your TLS proxy ──HTTP──► frontend (nginx, uid 101, read-only rootfs)
                                              │  serves dist/frontend + security headers/CSP
                                              └─ /api/** ──► backend (Spring Boot, uid 10001)
                                                               │  runtime DB account only
                                                               ▼
                                                             mysql (named volume)
                                                               ▲
                                              migrate (one-shot Flyway, migration DB account, exits 0)
```

- **Networks:** `internal` (`internal: true`) holds backend, migrate and mysql — no host or outbound access.
  `edge` carries only the frontend, which is the sole service with a published port.
- **Startup order:** mysql (healthy) → migrate (`service_completed_successfully`) → backend → frontend. A failed
  migration therefore stops the release before any traffic is served.
- **Schema ownership:** Flyway lives in `MigrationMain`, run by the migrate container. `FLYWAY_ENABLED=false` in the
  backend, which starts only if it lacks audit-mutating privileges.
- **Configuration:** non-secret values come from `.env.production`; secrets are files under `/run/secrets`, imported
  by Spring's `configtree`. Nothing is baked into an image.
- **The SPA's CSP** is generated from `frontend/security-headers.json` into both the dev-server configuration and the
  nginx snippet, so the policy the tests assert is the policy production serves.
- **Request IDs** flow end to end: nginx keeps or generates `X-Request-Id`, logs it as `rid=`, and the backend puts
  it in the MDC, the response header, audit rows and error bodies.

Operations: [DEPLOYMENT.md](DEPLOYMENT.md) and [RUNBOOK.md](RUNBOOK.md).
