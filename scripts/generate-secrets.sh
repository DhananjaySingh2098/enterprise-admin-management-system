#!/usr/bin/env bash
# Generates the secret files used by docker-compose.prod.yml (Docker secrets) into ./secrets (or $SECRETS_DIR).
# Existing files are never overwritten. Values are random and never printed.
#
#   scripts/generate-secrets.sh            # then: docker compose -f docker-compose.prod.yml --env-file .env.production up -d
#
# The directory is private to the deploying user (0700); files are 0444 so the unprivileged container users can read
# the bind-mounted copies. Keep the directory out of version control (it is git-ignored) and back it up securely.
set -euo pipefail
dir="${SECRETS_DIR:-./secrets}"
mkdir -p "$dir"
chmod 700 "$dir"

create() {
  local name="$1" value="$2"
  if [[ -e "$dir/$name" ]]; then
    echo "kept      $dir/$name"
  else
    printf '%s' "$value" > "$dir/$name"
    chmod 444 "$dir/$name"
    echo "generated $dir/$name"
  fi
}

create MYSQL_ROOT_PASSWORD "$(openssl rand -hex 24)"
create FLYWAY_PASSWORD     "$(openssl rand -hex 24)"
create DB_PASSWORD         "$(openssl rand -hex 24)"
create JWT_SECRET          "$(openssl rand -base64 48 | tr -d '\n')"
# The bootstrap administrator's first password: at least 12 characters. Sign in and change it immediately.
create ADMIN_PASSWORD      "Admin-$(openssl rand -hex 12)"
