#!/usr/bin/env bash
# Deploys the Tally server on the mini-PC. Run by .github/workflows/deploy.yml (self-hosted runner).
set -euo pipefail

APP_DIR="/opt/apps/tally/repo"
COMPOSE_FILE="$APP_DIR/docker-compose.production.yml"
ENV_FILE="$APP_DIR/.env.production"
HEALTH_URL="http://127.0.0.1:18310/healthz"
BRANCH="main"

dc() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

echo "[deploy] Starting Tally deployment..."
cd "$APP_DIR"

echo "[deploy] Fetching latest $BRANCH..."
git fetch origin "$BRANCH"
git reset --hard "origin/$BRANCH"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "[deploy] ERROR: .env.production missing at $ENV_FILE (copy .env.example)"
  exit 1
fi

echo "[deploy] Building server image..."
dc build server

echo "[deploy] Starting Postgres..."
dc up -d postgres
for i in {1..30}; do
  if dc exec -T postgres pg_isready -U tally -d tally >/dev/null 2>&1; then
    echo "[deploy] Postgres is healthy."
    break
  fi
  if [[ "$i" == "30" ]]; then
    echo "[deploy] ERROR: Postgres did not become healthy."
    dc logs --tail=100 postgres || true
    exit 1
  fi
  sleep 2
done

# Migrations run inside the server on startup (Flyway), before it starts listening.
echo "[deploy] Starting server..."
dc up -d --remove-orphans server

echo "[deploy] Waiting for health check..."
for i in {1..45}; do
  if curl -fsS "$HEALTH_URL" >/dev/null; then
    echo "[deploy] Tally is healthy."
    break
  fi
  if [[ "$i" == "45" ]]; then
    echo "[deploy] ERROR: health check failed"
    dc ps || true
    dc logs --tail=200 server || true
    dc logs --tail=50 postgres || true
    exit 1
  fi
  echo "[deploy] Not healthy yet... attempt $i/45"
  sleep 2
done

echo "[deploy] Deployment complete."
dc ps

echo "[deploy] Cleaning stale Docker images/build cache..."
docker image prune -f --filter "until=24h" || true
docker builder prune -f --filter "until=168h" || true
