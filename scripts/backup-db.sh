#!/usr/bin/env bash
# Dumps the Tally database to backups/tally_<timestamp>.sql (run from /opt/apps/tally/repo).
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
COMPOSE=(docker compose --env-file .env.production -f docker-compose.production.yml)

mkdir -p "$BACKUP_DIR"
"${COMPOSE[@]}" exec -T postgres pg_dump -U tally -d tally > "$BACKUP_DIR/tally_$TIMESTAMP.sql"
echo "Backup created: $BACKUP_DIR/tally_$TIMESTAMP.sql"

# Keep the last 30 dumps.
ls -1t "$BACKUP_DIR"/tally_*.sql 2>/dev/null | tail -n +31 | xargs -r rm --
