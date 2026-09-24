#!/usr/bin/env bash
# Smoke test for a running production-style stack, through the public frontend only (nginx → /api → backend → MySQL).
#   BASE_URL=http://127.0.0.1:8088 ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD_FILE=./secrets/ADMIN_PASSWORD \
#     scripts/smoke-test.sh
# Never prints secrets or tokens. Exit code 1 on the first failed check.
set -euo pipefail
BASE_URL="${BASE_URL:-http://127.0.0.1:8088}"
: "${ADMIN_EMAIL:?}" "${ADMIN_PASSWORD_FILE:?}"
ADMIN_PASSWORD="$(tr -d '\r\n' < "$ADMIN_PASSWORD_FILE")"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
pass=0
ok()   { pass=$((pass + 1)); echo "PASS  $*"; }
fail() { echo "FAIL  $*" >&2; exit 1; }
header() { grep -i "^$2:" "$1" | head -1 | cut -d' ' -f2- | tr -d '\r'; }

# ---------------------------------------------------------------- SPA + headers
for path in / /login /dashboard /users /employees /departments /profile /audit-logs /notifications /settings; do
  code=$(curl -s -o "$tmp/body" -D "$tmp/h" -w '%{http_code}' "$BASE_URL$path")
  [[ "$code" == 200 ]] && grep -q '<app-root' "$tmp/body" || fail "SPA route $path → $code"
  csp=$(header "$tmp/h" Content-Security-Policy)
  [[ "$csp" == *"script-src 'self' 'sha256-"* && "$csp" != *unsafe-inline* && "$csp" == *"frame-ancestors 'none'"* ]] || fail "CSP on $path"
  [[ "$(header "$tmp/h" Referrer-Policy)" == no-referrer ]] || fail "Referrer-Policy on $path"
  [[ "$(header "$tmp/h" X-Content-Type-Options)" == nosniff ]] || fail "nosniff on $path"
  [[ "$(header "$tmp/h" Permissions-Policy)" == *"camera=()"* ]] || fail "Permissions-Policy on $path"
  [[ "$(header "$tmp/h" Cache-Control)" == no-cache ]] || fail "index.html must not be cached ($path)"
  [[ -z "$(header "$tmp/h" Strict-Transport-Security)" || "$BASE_URL" == https* ]] || fail "HSTS must not be sent over plain HTTP"
done
ok "10 SPA routes serve index.html with CSP, Referrer-Policy, nosniff, Permissions-Policy, no-cache (no HSTS on HTTP)"

asset=$(grep -o 'main-[A-Z0-9]*\.js' "$tmp/body" | head -1)
curl -s -o /dev/null -D "$tmp/h" -H 'Accept-Encoding: gzip' "$BASE_URL/$asset"
[[ "$(header "$tmp/h" Cache-Control)" == *immutable* && "$(header "$tmp/h" Content-Encoding)" == gzip ]] || fail "hashed asset caching/compression"
[[ -n "$(header "$tmp/h" Content-Security-Policy)" ]] || fail "headers on static assets"
ok "hashed assets: immutable caching, gzip, security headers"

for path in /missing-ABCDEFGH.js /main.js.map /.env /.git/config; do
  [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL$path")" == 404 ]] || fail "$path must be 404"
done
ok "missing assets, source maps and dotfiles are 404 (no SPA fallback)"

# ---------------------------------------------------------------- API routing
code=$(curl -s -o "$tmp/body" -D "$tmp/h" -w '%{http_code}' "$BASE_URL/api/health")
[[ "$code" == 200 ]] && grep -q '"status":"UP"' "$tmp/body" || fail "/api/health → $code"
[[ "$(header "$tmp/h" Content-Security-Policy)" == "default-src 'none'"* ]] || fail "API responses carry the API CSP"
code=$(curl -s -o "$tmp/body" -w '%{http_code}' "$BASE_URL/api/users")
[[ "$code" == 401 ]] && grep -q '"status":401' "$tmp/body" || fail "/api/users unauthenticated must be a JSON 401 from the backend ($code)"
code=$(curl -s -o "$tmp/body" -w '%{http_code}' "$BASE_URL/api/does-not-exist.js")
[[ "$code" == 401 ]] || fail "/api/** must never fall back to the SPA or static files ($code)"
ok "/api/** is proxied to the backend only (health, JSON 401s, no SPA shadowing)"

rid=$(curl -s -o /dev/null -D - -H 'X-Request-Id: smoke-test-0001' "$BASE_URL/api/health" | grep -i '^x-request-id:' | tr -d '\r' | cut -d' ' -f2)
[[ "$rid" == smoke-test-0001 ]] || fail "request id pass-through ($rid)"
ok "X-Request-Id is propagated through nginx to the backend"

# ---------------------------------------------------------------- authentication through the proxy
body=$(python3 -c 'import json,sys; print(json.dumps({"email": sys.argv[1], "password": sys.argv[2]}))' "$ADMIN_EMAIL" "$ADMIN_PASSWORD")
code=$(curl -s -o "$tmp/login" -D "$tmp/h" -w '%{http_code}' -c "$tmp/jar" -H 'Content-Type: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' -H 'X-Forwarded-For: 203.0.113.99' --data "$body" "$BASE_URL/api/auth/login")
[[ "$code" == 200 ]] || fail "admin login through nginx → $code"
cookie=$(grep -i '^set-cookie: ea_refresh_token=' "$tmp/h" | tr -d '\r')
for attr in HttpOnly 'SameSite=Strict' 'Path=/api/auth' 'Max-Age='; do
  [[ "$cookie" == *"$attr"* ]] || fail "refresh cookie lacks $attr"
done
secure_expected="${EXPECT_SECURE_COOKIE:-true}"
if [[ "$secure_expected" == true ]]; then [[ "$cookie" == *Secure* ]] || fail "refresh cookie must be Secure"; fi
ok "login via nginx; refresh cookie HttpOnly, SameSite=Strict, Path=/api/auth, Max-Age$([[ $secure_expected == true ]] && echo ', Secure')"

token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["accessToken"])' "$tmp/login")
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$BASE_URL/api/auth/me")
[[ "$code" == 200 ]] || fail "/api/auth/me with bearer → $code"
# Secure cookies are only replayed by curl over https; send it explicitly for the refresh check.
raw=$(printf '%s' "$cookie" | sed -E 's/^[Ss]et-[Cc]ookie: ea_refresh_token=([^;]*).*/\1/')
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'X-Requested-With: XMLHttpRequest' -H "Cookie: ea_refresh_token=$raw" \
  -X POST "$BASE_URL/api/auth/refresh")
[[ "$code" == 200 ]] || fail "refresh through nginx → $code"
ok "bearer access and refresh-token rotation work through the proxy"

# The spoofed X-Forwarded-For above must not become the audited client IP (nginx overwrites it).
ip=$(curl -s -H "Authorization: Bearer $token" "$BASE_URL/api/audit-logs?action=LOGIN_SUCCESS&size=1" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["content"][0].get("ipAddress",""))')
[[ -n "$ip" && "$ip" != 203.0.113.99 ]] || fail "spoofed X-Forwarded-For reached the backend ($ip)"
ok "client-supplied X-Forwarded-For is not trusted (audited IP: proxy-observed address)"

echo "Smoke test passed: $pass checks."
