# Security

This document describes the security architecture. Sections are marked with the phase that implemented them;
Phase 6 (security hardening) is summarised in the sections from "Security headers" onward and in the production
checklist at the end.

## Summary

| Concern | Design | Status |
|---|---|---|
| API authentication | Stateless, short-lived JWT access token (HS256, 15 min) sent as `Authorization: Bearer` | Implemented |
| Session continuity | Opaque refresh token (256-bit) in an `HttpOnly` cookie; rotated on every use, with reuse detection | Implemented |
| Passwords | BCrypt, cost 12; policy of 12 characters to 72 bytes | Implemented |
| Authorization | Roles `ADMIN`, `MANAGER`, `USER`; deny-by-default URL rules plus `@PreAuthorize` | Implemented |
| Login abuse | Temporary, exponentially growing lockouts per account and per client IP (bounded memory) | Implemented |
| CSRF | Stateless API; the cookie endpoints use `SameSite=Strict` plus a required `X-Requested-With` header | Implemented |
| Errors | One safe JSON shape; no stack traces or internals | Implemented |
| Administration | ADMIN-only user management with self-protection and last-admin safeguards | Implemented (Phase 3) |
| Data access | Role matrix per endpoint, plus field-level rules (e.g. only ADMIN links accounts) | Implemented (Phase 3) |
| Concurrent edits | Optimistic locking (`@Version`), with a 409 `STALE_VERSION` response and no silent overwrite | Implemented (Phase 3) |
| Analytics | Read-only aggregates; workforce data for ADMIN/MANAGER, account aggregates for ADMIN only | Implemented (Phase 4) |
| Audit trail | Append-only; runtime DB user has no UPDATE/DELETE on it | Implemented (Phases 5–6) |
| Security headers | API: `default-src 'none'` CSP, no framing, `no-referrer`, Permissions-Policy, COOP/CORP, HSTS on HTTPS | Implemented (Phase 6) |
| SPA CSP | `script-src 'self'` + SHA-256 of the theme boot script; `style-src 'self'`; no `unsafe-inline`/`unsafe-eval` | Implemented (Phase 6) |
| Rate limiting | Per-IP/per-user/per-account fixed windows in a MySQL-backed store shared by all instances | Implemented (Phase 6) |
| Refresh races | Aborted refreshes recovered once within the grace period; replay protection unchanged | Implemented (Phase 6) |
| Database privileges | Separate migration and runtime accounts; per-table runtime grants; startup self-check | Implemented (Phase 6) |
| Mass assignment | Unknown JSON fields rejected (400 `UNKNOWN_FIELD`); explicit request DTOs only | Implemented (Phase 6) |

## Spring Security configuration (Implemented)

`security/SecurityConfig.java` defines a single stateless filter chain:

- `SessionCreationPolicy.STATELESS`. HTTP Basic, form login, logout filter, request cache and Boot's generated
  default user are all disabled, so no login page, browser prompt or `JSESSIONID` ever appears.
- **Public:** `GET /api/health`, `GET /api/health/db`, `POST /api/auth/login`, `POST /api/auth/refresh`,
  `POST /api/auth/logout` and CORS preflight `OPTIONS /api/**`.
  - `/logout` is public because it is authenticated by the refresh cookie rather than a possibly expired access token,
    and it is idempotent.
- **Everything else requires authentication**, including every endpoint added in future phases unless it is
  explicitly allowlisted.
- `@EnableMethodSecurity` is on. Role checks use `@PreAuthorize("hasRole('ADMIN')")` and similar.
- `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`. It is created inside the chain, not
  registered as a bean, so it can never also run as a stray servlet filter.
- Failures return JSON:
  - `401 Unauthorized` when there is no valid authentication
  - `403 Forbidden` when the caller is authenticated but not permitted
  - `429 Too Many Requests` during a login lockout

## Access tokens (JWT) (Implemented)

| Property | Value |
|---|---|
| Library | Nimbus JOSE + JWT 10.x (the library Spring Security itself uses) |
| Algorithm | HS256 only. Other algorithms, including `none`, are rejected before signature verification. |
| Key | `JWT_SECRET`, at least 32 bytes (256 bits). No default or fallback: startup fails if it is missing, too short, or still the `.env.example` placeholder. |
| Lifetime | 15 minutes (`JWT_ACCESS_TOKEN_TTL`, capped at 1 hour), with 30s clock-skew tolerance |
| Header `typ` | `at+jwt` (RFC 9068). Any other JWT type signed with the same key cannot be replayed as an access token. |
| Claims | `sub` (user id), `roles`, `iss`, `aud`, `iat`, `exp`, `jti`. There is no email, name or other personal data. |
| Validation | Signature, algorithm, type, expiry, issued-at, issuer (`JWT_ISSUER`), audience (`JWT_AUDIENCE`), numeric subject, and roles restricted to the known enum |

An invalid, expired, malformed or forged token leaves the request anonymous. Protected endpoints then return a
uniform 401. The reason is logged at debug level only and is never sent to the client.

Because access tokens are stateless, disabling a user takes effect when their access token expires (at most 15
minutes). `/api/auth/me` and every refresh re-check the account in the database immediately.

**Browser storage:** the access token is held only in the Angular `AuthService` memory. It is never written to
`localStorage`, `sessionStorage` or a cookie. A page reload restores it through the refresh flow.

## Refresh tokens (Implemented)

| Property | Design |
|---|---|
| Value | 32 bytes from `SecureRandom`, base64url-encoded |
| Storage | Only `SHA-256(token)` is kept in `refresh_tokens.token_hash` (unique index). The raw value exists only in the cookie. A fast hash is appropriate because the token has 256 bits of entropy, unlike a password. |
| Lifetime | 7 days per token (`REFRESH_TOKEN_TTL`), within an **absolute session limit** of 30 days from login (`REFRESH_SESSION_MAX_LIFETIME`). Rotation can never extend a session past that limit. |
| Rotation | Every successful refresh revokes the presented token (`ROTATED`) and issues a successor in the same **family** (one family per login) |
| Concurrency | The token row is locked with `SELECT … FOR UPDATE` during rotation, so parallel refreshes with the same token are serialized |
| Reuse detection | Presenting an already rotated token more than 30 seconds after its rotation is treated as theft. The whole family is revoked (`REUSE_DETECTED`) and the event is logged with the user id. |
| Benign races | Within the 30s grace period, a stale token never kills the session. The Angular client also serializes refreshes across tabs with the Web Locks API. |
| Aborted refreshes (Phase 6) | Each rotated token records its replacement (`replaced_by_id`). If a rotated token comes back within the grace period and its replacement was **never used**, the client cannot have received it (the response was lost, e.g. a reload mid-request): the replacement is revoked as `SUPERSEDED` and a new token is issued. If the replacement *was* used, the request is a stale duplicate: 401 within the grace period (session kept), reuse detection after it. The replacement row is locked too, so recovery and a normal rotation can never both succeed (no forked sessions). See "Refresh races" below. |
| Revocation | Logout revokes the family (`LOGOUT`). A disabled user's family is revoked on their next refresh (`USER_DISABLED`). |
| Cleanup | A nightly job (03:17) deletes tokens that expired more than a day ago |

## Refresh cookie (Implemented)

| Attribute | Value | Why |
|---|---|---|
| Name | `ea_refresh_token` (`REFRESH_COOKIE_NAME`) | |
| `HttpOnly` | always | JavaScript, including any injected script, cannot read it |
| `Secure` | **true by default** (`REFRESH_COOKIE_SECURE`) | Only sent over HTTPS |
| `SameSite` | `Strict` (`REFRESH_COOKIE_SAME_SITE`) | Never sent on cross-site requests. `None` is rejected unless `Secure=true`. |
| `Path` | `/api/auth` | Sent only to the refresh and logout endpoints, never with ordinary API calls |
| `Max-Age` | Remaining lifetime of the token | Cleared with `Max-Age=0` on logout or any refresh failure |

**Development vs production:**

- **Production** (HTTPS) must keep the default `Secure=true`. Nothing needs to be set.
- **Local development** runs over plain `http://localhost`, where browsers may refuse to store `Secure` cookies.
  The local `.env` therefore sets `REFRESH_COOKIE_SECURE=false`. This is an explicit, local-only opt-out. The code
  defaults to secure, and a missing variable in a deployed environment can only make the cookie *more* secure.

## CSRF (Implemented)

API calls authenticate with a bearer header, which browsers never attach automatically, so they are not
CSRF-able. The endpoints that rely on the refresh cookie (`/login`, `/refresh`, `/logout`) are protected in layers:

1. `SameSite=Strict`: the cookie is not sent on cross-site requests.
2. A required `X-Requested-With: XMLHttpRequest` header (otherwise `403 Request rejected`). A cross-origin page
   cannot add a custom header without a CORS preflight, and the preflight fails for origins not listed in
   `ALLOWED_ORIGINS`.
3. The refresh response (the new access token) cannot be read cross-origin because of CORS.

## CORS (Implemented)

- Driven only by `ALLOWED_ORIGINS`, a comma-separated list of exact origins. Wildcards are rejected at startup.
- `Access-Control-Allow-Credentials: true` is sent only for listed origins (needed when the SPA is hosted on another
  origin and must send the refresh cookie). `Access-Control-Allow-Origin: *` is never used.
- Local development uses the Angular dev proxy (same origin), so CORS is not exercised there.

## Passwords (Implemented)

- `BCryptPasswordEncoder` with cost **12** (`BCRYPT_STRENGTH`; tests use 4 for speed).
- Policy for new passwords: at least **12 characters** and at most **72 bytes**. BCrypt silently ignores bytes beyond
  72, which would weaken long passphrases.
- Login input is bounded (email ≤ 254, password ≤ 128) to cap hashing work. Over-long passwords are rejected after
  equivalent BCrypt work, so they are indistinguishable from a wrong password.
- Unknown emails are verified against a dummy hash, so every failure costs one BCrypt comparison. This removes the
  timing oracle for account existence.
- Passwords and hashes are never logged, returned or placed in exception messages. Request and response records
  redact secrets in `toString()`.

## Login-abuse protection (Implemented)

`security/LoginAttemptService.java` implements bounded, temporary lockouts:

| Setting | Default | Meaning |
|---|---|---|
| `max-failures-per-account` | 5 | Failures for one email within the window before that email is locked |
| `max-failures-per-client` | 20 | Failures from one client IP within the window before that IP is locked (higher because of shared NAT) |
| `window` | 15 min | Counters reset after this period |
| `base-lockout` | 1 min | First lockout. It doubles with each further failure while counting (1 → 2 → 4 → 8 min …). |
| `max-lockout` | 15 min | Hard cap. **No one is ever locked out permanently.** |
| `max-tracked-keys` | 10 000 | LRU-bounded memory |

Behaviour:

- While locked, the endpoint returns `429` with `Retry-After` and the generic message "Too many login attempts.
  Please try again later." The password is **not** checked, so guessing cannot continue during a lockout.
- Failures are counted for any submitted email, whether it exists or not. Neither the 401 nor the 429 reveals
  account existence.
- Failure responses are always `401 {"message":"Invalid credentials"}`, identical for unknown email, wrong password
  and disabled account.
- A successful login clears that account's counter.

Trade-offs:

- This lockout state is per application instance. Since Phase 6 it is complemented by the **shared** rate limiter
  (see "API rate limiting"): failed logins per account (10 per 15 min) and login attempts per IP (30 per min) are
  counted in MySQL across every instance.
- An attacker can deliberately trigger a victim's temporary lockout, which is bounded to 15 minutes at a time.
- The client IP is `request.getRemoteAddr()`. Behind a reverse proxy, set `FORWARD_HEADERS_STRATEGY=framework` (or
  `native`) **only** when the proxy is trusted and overwrites client-supplied `X-Forwarded-For` headers.

## Initial administrator (Implemented)

At startup, `InitialAdminInitializer` creates an `ADMIN` account from `ADMIN_EMAIL` / `ADMIN_PASSWORD`:

- It runs only while **no ADMIN account exists**. Once any admin exists, it logs "skipped" and does nothing.
- It never modifies an existing account. If the email belongs to an existing user, it logs a warning and leaves
  that account unchanged.
- The password is BCrypt-hashed and must satisfy the policy. Otherwise **startup fails** with a message that names
  the variable but never its value.
- The password is never logged. The log states only "Initial ADMIN account created (user id N)".
- If the variables are not set and no admin exists, a warning is logged and the application still starts.
- Operational advice: after first sign-in, rotate the bootstrap password and remove `ADMIN_PASSWORD` from the
  environment. A password-change endpoint arrives in Phase 3.

## Roles (Implemented)

| Role | Intended capability (enforced as features arrive) |
|---|---|
| `ADMIN` | Full administration: users and roles, system settings, audit logs |
| `MANAGER` | Manage employees and view analytics within their scope |
| `USER` | Self-service: own profile, permitted data |

- Role checks are `@PreAuthorize` annotations on the controller endpoints. They are backed by deny-by-default URL
  rules and by field-level checks in services (see "Permission matrix" below).
- Roles are a closed Java enum (`RoleName`), stored by name and guarded by a database `CHECK` constraint. They are
  seeded only by migration `V2__seed_roles.sql`, so arbitrary role strings cannot enter the system.
- Authorities are `ROLE_ADMIN`, `ROLE_MANAGER` and `ROLE_USER`. Hierarchy is expressed explicitly in each
  expression, e.g. `hasAnyRole('ADMIN','MANAGER')`.
- Angular route guards (`authGuard`, `roleGuard`) are a UX convenience only. The backend is always authoritative.

## Permission matrix (Implemented, Phases 3–6)

Enforced by the API: since Phase 6 role rules are applied **twice** — URL rules in the security filter chain (so an
unauthorized caller gets 403 before any request parsing, never a validation error that reveals the request shape)
and `@PreAuthorize` on every controller. The Angular guards and hidden buttons only mirror it for usability.
`AuthorizationMatrixIntegrationTest` checks every endpoint below for anonymous, USER, MANAGER and ADMIN callers.

**Anonymous** callers may only use `GET /api/health`, `GET /api/health/db`, `POST /api/auth/login`,
`POST /api/auth/refresh` and `POST /api/auth/logout`; everything else is `401` (unknown paths included, so their
existence is not revealed).

| Endpoint | ADMIN | MANAGER | USER |
|---|---|---|---|
| `GET /api/auth/me` | own | own | own |
| `GET /api/users`, `GET /api/users/{id}` | ✅ | ❌ 403 | ❌ 403 |
| `POST /api/users`, `PUT /api/users/{id}`, `PUT …/roles`, `PUT …/status` | ✅ | ❌ 403 | ❌ 403 |
| `GET /api/departments`, `GET /api/departments/{id}` | ✅ | ✅ | ✅ |
| `POST /api/departments`, `PUT /api/departments/{id}`, `PUT …/status` | ✅ | ❌ 403 | ❌ 403 |
| `GET /api/employees`, `GET /api/employees/{id}` | ✅ | ✅ | ✅ |
| `POST /api/employees`, `PUT /api/employees/{id}` | ✅ | ✅ | ❌ 403 |
| `PUT /api/employees/{id}/status` | ✅ | ❌ 403 | ❌ 403 |
| `GET /api/profile`, `PUT /api/profile`, `PUT /api/profile/password` | own account | own account | own account |
| `GET /api/dashboard/summary`, `…/headcount-by-department`, `…/status-breakdown`, `…/hiring-trend`, `…/recent-employees` | ✅ | ✅ | ❌ 403 |
| `GET /api/dashboard/users` (system-account aggregates) | ✅ | ❌ 403 | ❌ 403 |
| `GET /api/audit-logs`, `GET /api/audit-logs/{id}` (no write endpoints exist) | ✅ | ❌ 403 | ❌ 403 |
| `GET /api/settings/organization`, `PUT /api/settings/organization` | ✅ | ❌ 403 | ❌ 403 |
| `GET /api/settings/workspace` (organization name only) | ✅ | ✅ | ✅ |
| `GET/PUT /api/preferences` | own account | own account | own account |
| `GET /api/notifications…`, `PUT /api/notifications/{id}/read`, `PUT /api/notifications/read-all` | own account | own account | own account |

**Dashboard visibility (Phase 4):**

- Organisation-wide workforce analytics are management information: ADMIN and MANAGER only.
- USER keeps the Phase 3 employee directory but sees a personal workspace instead of analytics. The Angular app never
  calls the analytics endpoints for USER, and the API returns 403 if they are called directly.
- System-account analytics are ADMIN-only. They are aggregates without emails, names or security fields.

Field-level rules, returned as `403 FORBIDDEN_FIELD`:

- Only ADMIN may link or unlink an employee's user account (`userId`).
- Only ADMIN may create an employee with a status other than `ACTIVE`.

A MANAGER may edit a record that has a linked account, provided the link itself is unchanged.

MANAGER has no user or role administration of any kind.

## Administrator safeguards (Implemented, Phase 3)

| Rule | Response |
|---|---|
| An admin cannot disable their own account | `409 SELF_DISABLE` |
| An admin cannot remove their own ADMIN role | `409 SELF_DEMOTION` |
| The last **enabled** ADMIN cannot be disabled | `409 LAST_ADMIN` |
| The last **enabled** ADMIN cannot lose the ADMIN role | `409 LAST_ADMIN` |

Operations that could reduce the number of active admins first take a row lock on the `ADMIN` role
(`SELECT … FOR UPDATE`). This serializes concurrent changes, so two admins disabling or demoting each other at the
same moment cannot leave zero admins.

**Disabling a user:**

- Every one of their refresh tokens is revoked (`USER_DISABLED`), so no session can be renewed.
- New logins fail with the generic 401.
- An access token that is already issued remains valid until it expires (at most 15 minutes). This is the documented
  Phase 2 trade-off for stateless tokens.
- `/api/auth/me` and `/api/profile` re-check the account on every call.

**Role changes** take effect at the user's next token refresh (at most 15 minutes).

**Generic updates** (`PUT /api/users/{id}`) accept first name, last name and email only. Roles, status and password
each have their own guarded operation. Unknown JSON properties such as `passwordHash`, `roles` or `enabled` are
ignored, never applied.

## Self-service profile and password change (Implemented, Phase 3)

- `/api/profile` has no id parameter. The account is always the authenticated principal, so extra `id` fields in
  the body are ignored.
- `PUT /api/profile/password` requires `currentPassword`, `newPassword` and `confirmPassword`:
  - A wrong current password returns `400 INVALID_CURRENT_PASSWORD`. It is deliberately not a 401, so clients don't
    treat it as an expired session.
  - Wrong attempts count towards the same temporary lockout as failed logins, returning `429` once locked.
  - The new password must meet the policy (`WEAK_PASSWORD`), match the confirmation (`PASSWORD_MISMATCH`) and differ
    from the current one (`PASSWORD_REUSED`).
- On success:
  - The new password is stored as a BCrypt hash.
  - **All** refresh-token families are revoked (`PASSWORD_CHANGED`).
  - A fresh session is issued to the caller (new access token plus refresh cookie), so the current tab stays signed
    in and every other device is signed out.
- Password values never appear in responses, logs or `toString()` output.

## Query safety (Implemented, Phase 3)

- Sorting uses public keys (e.g. `name`, `hireDate`) mapped through a per-resource allowlist to entity properties.
  Any other value returns `400 INVALID_SORT`, so query parameters never reach JPQL as property names.
- Page size is 1–100 (default 20). Out-of-range values return `400`.
- Search terms are bound parameters in escaped `LIKE` patterns, so `%` and `_` are matched literally.
- Enum filters (`role`, `status`) bind to Java enums. Unknown values return `400`.

## Concurrent edits (Implemented, Phase 3)

- `employees.version` is a JPA `@Version` column. Every update and status change must send the `version` the
  client last read.
- A mismatched version returns `409 STALE_VERSION` ("This record was updated by someone else. Reload the latest
  version before saving again."). A race between two in-flight transactions is caught by Hibernate's version check
  and mapped to the same response.
- The frontend shows the conflict with an explicit "Reload latest" action. It never retries or overwrites
  automatically.

## Error safety (Implemented)

- One error body everywhere: `{timestamp, status, error, message, path}`, optionally extended with a stable `code`
  (e.g. `DUPLICATE_EMAIL`, `STALE_VERSION`, `LAST_ADMIN`) and `fieldErrors: [{field, message}]` for forms.
  Rejected values are never echoed back.
- Status codes: `400` for validation, `401` when not authenticated, `403` when not permitted, `404` for a missing
  resource, `409` for duplicates, conflicts and safeguards, and `429` during a lockout.
- Messages are fixed, client-safe strings. `500` always says "An unexpected error occurred".
- `server.error.include-stacktrace=never`, `include-message=never`, `include-exception=false`.
- JWT parsing errors, SQL errors and refresh-token reasons stay in server logs.

## Audit trail (Implemented, Phase 5)

**Event model.** One row per event in `audit_logs`: time, actor (id + email snapshot; absent for anonymous events),
action, target (type, id and a label snapshot), outcome (`SUCCESS`/`FAILURE`), redacted details, client IP and
request ID. Snapshots keep the trail meaningful even if an account is later renamed.

| Area | Actions |
|---|---|
| Authentication | `LOGIN_SUCCESS`, `LOGIN_FAILURE` (reason: unknown account, invalid credentials, disabled, locked out), `LOGOUT`, `REFRESH_TOKEN_REVOKED` (reuse detected) |
| Users | `USER_CREATED`, `USER_UPDATED`, `USER_ENABLED`, `USER_DISABLED`, `USER_ROLES_CHANGED`, `PASSWORD_CHANGED` (success and wrong-current-password failure) |
| Employees | `EMPLOYEE_CREATED`, `EMPLOYEE_UPDATED`, `EMPLOYEE_STATUS_CHANGED` |
| Departments | `DEPARTMENT_CREATED`, `DEPARTMENT_UPDATED`, `DEPARTMENT_DEACTIVATED`, `DEPARTMENT_REACTIVATED` |
| Settings | `ORGANIZATION_SETTINGS_UPDATED`, `USER_PREFERENCES_UPDATED` |

Read-only requests are never audited. No-op updates (nothing actually changed) are not recorded either.

**Transaction strategy.**
- Successful changes are recorded with `Propagation.MANDATORY`: in the same transaction as the change, so a rollback
  removes the audit row too, and an audit write outside a transaction fails fast.
- Failed attempts whose own transaction rolls back (wrong password, lockout) use `REQUIRES_NEW`, so the attempt is
  kept.
- Refresh-token reuse is recorded in the rotation transaction, which already commits despite the exception.
- Covered by MySQL tests: a rolled-back transaction leaves no row, and a rejected change (e.g. `LAST_ADMIN`) leaves
  no success row.

**Redaction.**
1. Callers pass only non-secret facts: changed field *names* (never values such as phone numbers), from/to statuses
   and roles, reasons and counts.
2. `AuditDetails` then drops any key that looks secret (`password`, `token`, `secret`, `hash`, `cookie`,
   `authorization`, `credential`, `jwt`, `bearer`, `apikey`), masks JWT- or bearer-looking values, strips control
   characters and caps sizes (200 characters per value, 20 items, 2000 characters overall).
3. The API redacts again on the way out.
4. Unknown-account login failures never store the submitted identifier, since people sometimes type a password into
   the email field.

**Append-only (application).** The entity is `@Immutable` with no setters, and the repository exposes only save,
find-by-id and a filtered search (no update or delete).

**Append-only (database, Phase 6).** The runtime account holds only `SELECT, INSERT` on `audit_logs` (see "Database
privilege model"): even a compromised or buggy application cannot rewrite or erase the trail, and the application
refuses to start if its account could (`DB_VERIFY_LEAST_PRIVILEGE=true`). Verified by
`SecurityHardeningIntegrationTest` (UPDATE/DELETE/TRUNCATE denied, row count unchanged) and against the local MySQL.
DB triggers are not used (with binary logging on they need `SUPER`/`log_bin_trust_function_creators`); a DBA or the
migration account can still modify rows, which is the accepted operational boundary.

**Retention assumption.** The application keeps audit rows indefinitely; nothing in the app deletes them. Archival
and deletion are an operational policy (e.g. export and purge after N years) to be decided with deployment. Read
notifications are purged after 90 days (`app.notifications.read-retention`); unread ones are kept.

## Request correlation (Implemented, Phase 5)

- `RequestIdFilter` runs before security.
- It reuses an inbound `X-Request-Id` only if it matches `[A-Za-z0-9-]{8,64}`, so the value can never inject into
  logs; otherwise it generates a UUID.
- The ID is echoed in the response header, added to every log line (`logging.pattern.correlation`), stored on audit
  events, and included as `requestId` in error bodies. It reveals nothing about internals.

## Notifications (Implemented, Phase 5)

- **Scoping.** Every repository query is scoped by the owner's user ID, taken from the verified access token, and
  there is no unscoped find-by-ID. Another user's notification ID returns the same `404 Notification not found` as a
  non-existent one, so existence cannot be inferred. Mark-all-read only ever touches the caller's rows.
- **Content.** Title and message are plain text built from fixed server templates. Interpolated values have control
  characters and angle brackets removed, Angular renders them as text, and no HTML is ever stored.
- **Anti-spam.** Nobody is notified about their own change, except security notices (password changed, token reuse).
  No-op changes generate nothing.
- **No email delivery** (out of scope).

## Preferences & organization settings (Implemented, Phase 5)

- **Preferences** (theme mode, preset, density) are keyed by the principal's user ID. Unknown enum values are
  rejected with `400 INVALID_VALUE`, and a `userId` field in the body is ignored. They contain nothing sensitive.
- **Organization settings** are a single row with a DB `CHECK` on the 1–365-day window, optimistically locked
  (`409 STALE_VERSION`), and ADMIN-only. Only the organization name is exposed to other roles, via
  `/api/settings/workspace`.

## Secrets management

- No secrets in source control. `.env` is git-ignored and `.env.example` holds placeholders only (and the backend
  rejects the `JWT_SECRET` placeholder).
- The backend reads `.env` **only** under the `local` Spring profile, which `./mvnw spring-boot:run` activates. Tests
  and deployed environments never read it; production injects real environment variables from a secret manager.
- Rotate `JWT_SECRET` to invalidate all access tokens (refresh tokens stay valid and issue new access tokens).
  Revoke `refresh_tokens` rows to force re-login.
- Test fixtures (e.g. `test-only-hs256-signing-key-…`) are public test values and are never used outside tests.
- **Containers (Phase 7):** every secret is a *file* mounted at `/run/secrets` (Docker secrets) and imported by
  Spring's `configtree`. Secrets are never environment variables in a compose file, build arguments or image layers;
  `scripts/generate-secrets.sh` creates them (directory 0700, files 0444) and never prints a value. Point
  `SECRETS_DIR` at a directory rendered by your secret manager to inject them from elsewhere. Verified: the images
  carry no secret in their layers or environment, and no container log contains a secret value.

## Security headers (Implemented, Phase 6)

**API responses** (Spring Security, every response including errors):

| Header | Value | Why |
|---|---|---|
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` | The API only returns JSON: nothing may load or frame it |
| `Strict-Transport-Security` | `max-age=31536000 ; includeSubDomains` — **HTTPS requests only** | Never sent on local HTTP; `HSTS_ENABLED=false` only for environments without TLS. `preload` is deliberately not set (an irreversible, domain-wide decision for deployment). |
| `X-Content-Type-Options` | `nosniff` | No MIME sniffing |
| `Referrer-Policy` | `no-referrer` | URLs (ids, search terms) never leak to other origins |
| `Permissions-Policy` | camera, microphone, geolocation, payment, usb, sensors all `()` | Features the app never uses stay off |
| `Cross-Origin-Opener-Policy` / `-Resource-Policy` | `same-origin` | Process isolation; no cross-origin embedding of API responses |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` | Personal data is never cached |
| `X-Request-Id` | correlation id | Support/audit correlation |

Deliberately **not** sent: `X-Frame-Options` (superseded by CSP `frame-ancestors`) and `X-XSS-Protection` (deprecated;
modern guidance is to omit it).

**SPA responses** (the Angular dev server sends them from `angular.json` → `serve.options.headers`; the production web
server — Phase 7 — must send the same set): `Content-Security-Policy` (below), `Referrer-Policy: no-referrer`,
`X-Content-Type-Options: nosniff`, `Permissions-Policy` (as above), `Cross-Origin-Opener-Policy: same-origin`, and
HSTS from the TLS terminator.

## Content-Security-Policy for the SPA (Implemented, Phase 6)

```
default-src 'self'; script-src 'self' 'sha256-+mOEaVXkx47ZX17adDtM9Up360KvsmPmBJOlzm8ukK4='; style-src 'self';
img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self';
frame-ancestors 'none'; worker-src 'self'; manifest-src 'self'
```

- **No `'unsafe-inline'`, no `'unsafe-eval'`, no wildcards.** The only inline script — the pre-paint theme boot script
  in `index.html` (prevents a light flash for dark-mode users) — is allowed by its SHA-256 hash. Any edit to that
  script changes the hash: `npm run check:csp` recomputes it and fails until the policy is updated.
- **Styles.** Angular injects component `styles`/`styleUrl` as runtime `<style>` tags and static `style="…"`
  attributes are inline styles; both would need `'unsafe-inline'`. All component styles therefore live in the
  global stylesheet (scoped by the host element), templates use attribute-driven variants instead of `style=""`,
  and production builds disable critical-CSS inlining (it emits an inline `<style>` and an `onload` handler).
  Dynamic `[style.x]` bindings are fine: Angular applies them through the CSSOM, which CSP does not restrict.
- **`img-src data:`** only for the inline SVG chevron of native selects.
- **`connect-src 'self'`**: the SPA calls the API through the same origin (dev proxy / production reverse proxy). If
  the API is ever served from another origin, add exactly that origin — never a wildcard.
- **`npm run check:csp`** (after `ng build`) verifies the policy, the boot-script hash, and that neither the source nor
  the built `index.html` contains inline styles, event handlers or unhashed scripts. The E2E suite visits every page
  and interactive surface and asserts **zero** `securitypolicyviolation` events and no console errors, and that the
  boot script still applies the stored theme before Angular starts.
- **One source of truth (Phase 7):** `frontend/security-headers.json` defines the policy and the other headers;
  `npm run security:headers` regenerates the `angular.json` dev-server headers and `frontend/nginx/security-headers.conf`
  from it, and `npm run check:csp` fails if the source, the dev server, the nginx snippet or the built output drift
  apart — so development, production and the tests cannot diverge.
- **Development vs production:** identical policy. The dev server's Vite client is a same-origin script
  (`'self'`); its HMR websocket is same-origin as well (CSP Level 3 `'self'` covers `ws:` to the same host).

## API rate limiting (Implemented, Phase 6)

Fixed-window limits, enforced by `RateLimitFilter` inside the security chain (after bearer authentication, before
any controller) and returning `429 Too Many Requests` with `Retry-After` and
`{"code":"RATE_LIMITED","message":"Too many requests. Please wait a moment and try again.","requestId":…}`.
**Read-only requests (GET/HEAD/OPTIONS) are never limited.**

| Policy | Applies to | Subject | Default |
|---|---|---|---|
| `login-ip` | `POST /api/auth/login` | client IP | 30 / min |
| `login-account-failure` | failed logins and wrong current passwords (checked before the password is verified) | account email | 10 / 15 min |
| `refresh-ip` | `POST /api/auth/refresh` | client IP | 120 / min |
| `password-change` | `PUT /api/profile/password` | user | 5 / 15 min |
| `profile-update` | `PUT /api/profile` | user | 20 / min |
| `admin-mutation` | POST/PUT on users, employees, departments, organization settings | user | 120 / min |
| `account-mutation` | PUT on own preferences and notifications | user | 120 / min |

**Multi-instance design.** The default store (`RATE_LIMIT_STORE=jdbc`) keeps counters in the `rate_limit_buckets`
table of the application's MySQL: one atomic `INSERT … ON DUPLICATE KEY UPDATE hits = LAST_INSERT_ID(hits + 1)` per
limited request, in its own short transaction (never rolled back with a failed login). Every instance counts into the
same rows, so limits hold for any number of instances **without new infrastructure**; `RateLimitIntegrationTest`
proves two independent limiter instances share one budget. Keys are `policy:sha256(subject)` — raw IPs, ids or
emails are never stored. Expired windows are purged every 10 minutes. `memory` exists for single-instance setups and
tests only.

Why not Redis (yet): one indexed write per *risky* request is cheap for this application's traffic profile, and it
avoids a new stateful dependency. At very high write volume, or once a gateway exists, the same `RateLimitStore`
interface can be backed by Redis (INCR + EXPIRE) or the policies moved to the gateway; `RateLimitService` and the
filter do not change. **Fail-open:** if the counter table is unreachable the request is allowed and a warning is
logged — limiting is defence in depth, and the per-instance lockout still applies.

## Refresh races (Implemented, Phase 6)

Problem (from Phase 2): a refresh can succeed on the server while the browser never receives the response (the page
is reloaded or navigated away mid-request). The browser keeps the rotated token; presenting it again was rejected
(within 30 s) or, later, treated as theft — signing the user out.

Fix, backend: aborted-refresh recovery (see the refresh-token table above). Security properties are unchanged —
replay after the grace period still revokes the whole family, a *used* replacement still marks the old token as a
stale duplicate, and recovery requires the replacement to be unused, unexpired and the user enabled.

Fix, frontend:
- Session restore retries **once** (600 ms) after a transient failure (network error, 5xx, 429); a 401/403 is final.
- Only a definitive rejection (401/403) ends the session; transient refresh failures keep it (no redirect, no loop).
- A session epoch discards refresh results that complete after logout (or a newer login): no silent re-login.
- Concurrent 401s share one refresh (per tab) and one retry each; refreshes are serialized across tabs (Web Locks).
- After sign-in the login page honours a safe same-app `returnUrl` (open redirects rejected).

Tests: `AuthIntegrationTest` (aborted refresh, stale duplicate, replay after grace, 6 parallel refreshes → exactly one
active token, logout during refresh), Angular unit tests (logout during refresh, transient failures, 5 simultaneous
401s → one refresh), and the E2E "aborted refresh" test that drops a real refresh response in the browser.

## Database privilege model (Implemented, Phase 6)

| Account | Privileges | Used by |
|---|---|---|
| DBA / root | everything | one-time provisioning only (`docker/mysql/provision-users.sh`) |
| Migration (`ea_migrator`) | `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES` on the application schema only, `WITH GRANT OPTION`; **no** global privileges, **no** `CREATE USER` | Flyway (`FLYWAY_USER`) |
| Runtime (`ea_app`) | table-level grants only (below); **no** schema-wide privileges, DDL, `GRANT`, `CREATE USER`, or access to `flyway_schema_history` | the running application (`DB_USERNAME`) |

Runtime grants, applied by Flyway's `afterMigrate` callback (`RuntimeGrants`) on every start:

| Table | Runtime privileges |
|---|---|
| `users` | SELECT, INSERT, UPDATE (never DELETE: accounts are disabled, not deleted) |
| `roles` | SELECT, UPDATE (UPDATE only because MySQL requires it for the `SELECT … FOR UPDATE` row lock that serializes admin demotions; the app never writes roles) |
| `user_roles` | SELECT, INSERT, DELETE |
| `refresh_tokens`, `notifications`, `rate_limit_buckets` | SELECT, INSERT, UPDATE, DELETE (purge jobs) |
| `departments`, `employees`, `user_preferences` | SELECT, INSERT, UPDATE |
| `organization_settings` | SELECT, UPDATE |
| **`audit_logs`** | **SELECT, INSERT** (UPDATE/DELETE explicitly revoked) |

- The callback fails startup if any table lacks a grant entry, so a future migration cannot silently ship without one.
- `DB_VERIFY_LEAST_PRIVILEGE=true` makes the running application prove at startup (zero-row statements in a
  rolled-back transaction) that it cannot UPDATE or DELETE audit rows; otherwise it refuses to start.
- **Every integration test runs under this model** (the test base provisions the users with the same script), so the
  whole suite proves the application works with exactly these grants.
- **Local development** may still run with the single Docker `MYSQL_USER` (schema-wide rights) by leaving
  `DB_RUNTIME_USER`/`FLYWAY_USER` unset; `.env.example` documents switching to the two accounts. Shared environments
  must use them.

## Input validation, mass assignment and IDOR (Reviewed, Phase 6)

- Every mutating endpoint takes an explicit request DTO with Bean Validation (lengths, email format, enums,
  `@NotNull`, bounds such as the 1–365 day window); emails are normalised (trimmed, lower-case) before uniqueness
  checks; enums reject unknown values (`400 INVALID_VALUE`); paging is bounded (size 1–100, notifications 1–50) and
  sort keys go through per-endpoint allowlists (`400 INVALID_SORT`); dates are ISO dates with `from ≤ to`.
- **Unknown JSON fields are rejected** (`400 UNKNOWN_FIELD`, field name sanitised): attempts to set `roles`,
  `enabled`, `passwordHash`, `id`, `version`, `createdAt`, `updatedAt`, owner ids or audit fields through an endpoint
  that does not own them fail explicitly (`SecurityHardeningIntegrationTest`). Roles and account status change only
  through their dedicated, safeguarded endpoints.
- **IDOR:** profile, preferences and notifications take the owner from the verified token only; there are no
  user-id parameters. Another user's notification id returns the same 404 as a non-existent one. Tested in
  `NotificationIntegrationTest`, `SettingsIntegrationTest` and the E2E notification test.

## JWT and cookie review (Phase 6)

- HS256 with a ≥ 32-byte secret; startup fails if it is missing, short or the placeholder — no fallback secret.
- Header `typ` must be `at+jwt`; algorithm pinned (no `none`, no algorithm confusion); `iss`, `aud`, `exp`, `iat`
  (not in the future) validated; `sub` must be a positive id; roles must be known enum values. Clock skew is capped
  at 2 minutes and access-token TTL at 1 hour (default 15 min); bearer values over 4 KB are rejected unparsed.
- Refresh cookie: `HttpOnly`, `Secure` (default), `SameSite=Strict`, `Path=/api/auth`, `Max-Age` = remaining lifetime.
  Local HTTP development alone sets `REFRESH_COOKIE_SECURE=false`. The E2E suite asserts the attributes in a real
  browser and that no token-like value ever reaches `localStorage`/`sessionStorage`.

## Password review (Phase 6)

BCrypt cost 12 by default (a startup warning below 10); policy 12 characters to 72 bytes; the current password is
verified before a change, wrong attempts count towards lockout and the shared per-account limit, and a change
revokes every other session. Passwords never appear in logs, audit rows, notifications, error responses or browser
storage — verified by the audit/secret-scan tests, a scan of every server log from the verification runs, and E2E
storage checks. Request DTOs carrying passwords override `toString()`.

## Security test strategy (Phase 6)

| Layer | What it proves |
|---|---|
| Unit (JUnit) | JWT validation edge cases, redaction, rate-limit routing/stores, config bounds, grants map |
| MySQL integration (Testcontainers, **least-privilege users**) | authorization matrix (42 endpoints × 4 callers), headers, mass assignment, error hygiene, DB privileges & audit immutability, rate limits incl. multi-instance, refresh races and concurrency, IDOR |
| Angular unit (Vitest) | interceptor/session races, restore retry, guards, safe return URLs, 429 handling |
| E2E (Playwright, real Chrome, isolated schema) | login/session/reload/abort, roles, CRUD, audit, notifications, themes/density, CSP (zero violations), accessibility (axe), responsive |
| Static/dependency | SpotBugs + FindSecBugs (`-Psecurity-scan`), OSV scan of all Maven artifacts, `npm audit`, `npm run check:csp` |

## Dependency scanning (Phase 6)

- **Backend:** `python3 scripts/security/osv-maven-scan.py` resolves the full Maven dependency tree (runtime and test)
  and queries OSV.dev. Phase 6 found three critical advisories in the Spring Boot–managed embedded Tomcat 11.0.24
  (CVE-2026-65905 DIGEST auth replay, CVE-2026-65182 security-constraint ordering, CVE-2026-68525 FORM auth). None is
  reachable here (no container authentication or `web.xml` constraints — Spring Security handles all auth), but the
  fix is a drop-in patch: `tomcat.version` is pinned to 11.0.26. Remove the override once the Boot BOM manages
  ≥ 11.0.25. Result after the fix: 0 findings across 139 artifacts.
- **Frontend:** `npm audit` — 0 vulnerabilities (production and development dependencies).
- OWASP dependency-check was not added: it needs a multi-GB NVD mirror and an API key; OSV covers the same Maven
  advisories (GHSA/NVD) with a single HTTPS call. Run both scanners in CI (Phase 7).

## Deployment hardening (Implemented, Phase 7)

**Network.** Only the frontend container publishes a port (bound to `127.0.0.1` by default). The backend and MySQL
sit on a compose network declared `internal: true`: no host access, no outbound access, no published ports. The
browser therefore talks to one origin, and production needs no CORS at all.

**Images.** Multi-stage builds; the runtime layers contain no sources, Maven, Node, shells beyond `sh`, `curl` or
source maps. The backend runs as uid 10001 and the frontend as nginx uid 101 with a read-only root filesystem
(tmpfs for its rendered config, cache and pid). Both images declare healthchecks.

**Reverse proxy.** nginx overwrites `X-Forwarded-For`, `X-Forwarded-Host` and `Forwarded` with values it observed,
so a client cannot spoof its address into the audit trail (verified). `X-Forwarded-Proto` is honoured **only** when
`NGINX_TRUST_FORWARDED_PROTO=on`, which is how HSTS and `Secure` cookies are enabled behind a TLS terminator; with
the default `off` a spoofed `X-Forwarded-Proto: https` changes nothing. The backend parses forwarded headers only
because it is reachable exclusively through that one proxy hop. When the backend is unavailable, nginx answers
`/api` with a generic JSON 503 carrying a request ID — no upstream details.

**Privilege separation at runtime.** Flyway runs in a separate one-shot container as the migration account; the
long-running backend has `FLYWAY_ENABLED=false` and only the runtime account, and verifies at startup that it
cannot modify `audit_logs`.

**Fail-closed configuration** (each verified by starting a container that way): a missing or too-short `JWT_SECRET`,
a wildcard in `ALLOWED_ORIGINS`, an unknown rate-limit store, wrong database credentials, or an over-privileged
database account all stop the backend with one clear message, exit code 1, and no secret values in the logs.

**Supply chain.** CI runs OSV against every resolved Maven artifact, `npm audit` gates, SpotBugs with FindSecBugs,
and a gitleaks secret scan (`--redact`) whose only allowlist entry is a named unit-test fixture.

## Production checklist (Phase 7)

- [ ] TLS everywhere; `REFRESH_COOKIE_SECURE` unset (true); HSTS left on.
- [ ] Web server for the SPA sends the CSP and headers listed above (the shipped nginx image does; `npm run check:csp`).
- [ ] Behind a TLS terminator: `NGINX_TRUST_FORWARDED_PROTO=on` and the proxy sets `X-Forwarded-Proto`.
- [ ] Migration and runtime DB accounts provisioned; `FLYWAY_USER`/`DB_USERNAME` separated;
      `DB_RUNTIME_USER` set; `DB_VERIFY_LEAST_PRIVILEGE=true`; TLS to MySQL (`useSSL=true`/`sslMode=VERIFY_IDENTITY`).
- [ ] `JWT_SECRET`, DB and admin passwords from secret files or a secret manager (`SECRETS_DIR`); bootstrap admin
      password changed after first sign-in.
- [ ] Backups scheduled **and test-restored**; the secrets directory backed up separately and encrypted
      (see [RUNBOOK.md](RUNBOOK.md)).
- [ ] `ALLOWED_ORIGINS` exact origins only (empty when same-origin); no localhost values.
- [ ] `FORWARD_HEADERS_STRATEGY=framework` only behind a trusted proxy that overwrites `X-Forwarded-*`.
- [ ] `RATE_LIMIT_STORE=jdbc` (default) for multiple instances; review limits against real traffic.
- [ ] `JPA_DDL_AUTO=validate` (default); Flyway `clean` stays disabled (default).
- [ ] Error bodies expose no internals (default config); logs shipped without request bodies.
- [ ] Dependency scans (OSV, npm audit), SpotBugs and the secret scan green in CI; the Tomcat override removed once
      Boot catches up.
- [ ] `scripts/smoke-test.sh` passes against the deployed URL.

## Planned

- **Later:** email-based password reset (single-use, hashed, short-expiry tokens; needs mail infrastructure).
- **Later:** TLS *to* MySQL with a verified CA in the bundled stack (`sslMode=VERIFY_IDENTITY` is documented and
  supported for external databases; the bundled container uses `sslMode=PREFERRED` on an internal network).
- **Later:** signed and published container images, and an automated dependency-update pipeline.
