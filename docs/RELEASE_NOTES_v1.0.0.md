# Enterprise Admin Management System v1.0.0 — draft release notes

> **Draft.** Nothing has been committed, tagged or published. Publish only after the release is approved.

The first complete release. A secure, role-based administration platform — users, employees, departments, an
analytics dashboard, an append-only audit trail, in-app notifications and settings — delivered as a hardened
container stack with continuous integration.

## Highlights

- **Authentication built for real use.** Short-lived JWT access tokens kept in memory, rotating refresh tokens
  stored only as hashes in an `HttpOnly`, `SameSite=Strict` cookie, family reuse detection, and recovery from a
  refresh whose response was lost mid-reload. Repeated failures trigger bounded, temporary lockouts.
- **Roles that hold at every layer.** `ADMIN`, `MANAGER` and `USER` enforced deny-by-default in the filter chain and
  again with `@PreAuthorize`, covered by a full endpoint × role matrix test.
- **An audit trail you can trust.** Every sign-in, sign-out, failure and record change is written in the same
  transaction as the change, redacted twice, and stored in a table the application itself cannot update or delete —
  the database denies it.
- **A dashboard with no invented numbers.** Every KPI, chart and trend is a live database aggregate.
- **A premium interface.** System/Light/Dark across four presets, two densities, per-user preferences synced to the
  account, motion that respects reduced-motion settings, and no flash of the wrong theme on load.
- **Hardened by default.** A strict CSP with no inline scripts or styles, shared-store rate limiting, least-privilege
  database accounts, non-root containers, and a backend and database that are unreachable from the host.

## Running it

```bash
scripts/generate-secrets.sh
cp .env.production.example .env.production        # set ADMIN_EMAIL
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build --wait
```

Full instructions: [docs/DEPLOYMENT.md](DEPLOYMENT.md). Operations: [docs/RUNBOOK.md](RUNBOOK.md).

Terminate TLS in front of the stack and set `NGINX_TRUST_FORWARDED_PROTO=on` so HSTS and `Secure` cookies engage.

## Upgrading

This is the first release, so there is nothing to upgrade from. Future upgrades: back up, `build`, then
`up -d --wait` — the one-shot migration job applies pending migrations before the backend starts. Upgrading an
existing database has been tested from schema V4 and V5 with a copy of real data; all rows, accounts and history are
preserved, and an existing administrator account is never modified.

## Verification for this release

| Gate | Result |
|---|---|
| Backend tests (unit, web-slice, Testcontainers MySQL) | 200 passed |
| Angular unit tests | 182 passed |
| Playwright E2E — isolated stack | 26 passed |
| Playwright E2E — through the production stack | 26 passed |
| SpotBugs + FindSecBugs | 0 findings |
| OSV (139 Maven artifacts) | 0 vulnerabilities |
| `npm audit` (production and all dependencies) | 0 vulnerabilities |
| CSP consistency (source, dev server, nginx, build) | passed |
| Smoke test through nginx (8 groups) | passed |
| Clean install / upgrade / backup restore | passed |

Accessibility is covered by automated axe-core checks in light and dark plus keyboard tests; **manual screen-reader
testing has not been performed**.

## Known limitations

- No email delivery: notifications are in-app, and there is no self-service password reset (an ADMIN creates or
  resets accounts).
- Single MySQL container in the bundled stack; use managed or replicated MySQL for high availability.
- The bundled database connection uses `sslMode=PREFERRED` on an internal network; external databases should use
  `sslMode=VERIFY_IDENTITY`.
- Container images are built locally and not published to a registry.
- No cloud deployment, autoscaling or multi-tenancy.

## Credits

Built with Spring Boot 4.1 on Java 21, Angular 22, MySQL 8.4, Flyway, nginx and Docker.
