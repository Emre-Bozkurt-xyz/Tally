#!/usr/bin/env bash
# Restores a dump made by backup-db.sh into the (empty) Tally database.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <backup.sql>"
  exit 1
fi

docker compose --env-file .env.production -f docker-compose.production.yml \
  exec -T postgres psql -U tally -d tally < "$1"
