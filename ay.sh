#!/usr/bin/env bash
# ay — ArthaYantra 2.0 operator CLI (A.1.1). Project-scoped compose only;
# never raw docker kill. Mirror of ay.ps1.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$REPO_ROOT/deploy/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

init_local_config() {
  if [[ ! -f "$ENV_FILE" ]]; then
    cp "$REPO_ROOT/.env.example" "$ENV_FILE"
    echo "[ay] created .env from .env.example (mock mode - no secrets needed)"
  fi
  local pw_file="$REPO_ROOT/deploy/secrets/postgres_password"
  if [[ ! -f "$pw_file" ]]; then
    LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 >"$pw_file"
    echo "[ay] generated deploy/secrets/postgres_password (gitignored)"
  fi
}

verb="${1:-help}"
shift || true

case "$verb" in
  up)
    init_local_config
    profile_args=()
    for p in "$@"; do
      case "$p" in
        obs|dev-tools) profile_args+=(--profile "$p") ;;
        *) echo "[ay] unknown profile '$p' (expected: obs, dev-tools)" >&2; exit 2 ;;
      esac
    done
    compose "${profile_args[@]+"${profile_args[@]}"}" up -d --wait
    ;;
  down)
    compose --profile obs --profile dev-tools down
    ;;
  logs)
    [[ $# -ge 1 ]] || { echo "usage: ay logs <service>" >&2; exit 2; }
    compose logs -f "$@"
    ;;
  status)
    compose ps -a --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}\t{{.Publishers}}'
    ;;
  backup)
    compose exec db-backup /usr/local/bin/backup.sh manual
    ;;
  restore)
    [[ $# -ge 1 ]] || { echo "usage: ay restore <dump-file>" >&2; exit 2; }
    dump="$1"
    [[ -f "$dump" ]] || { echo "[ay] no such file: $dump" >&2; exit 2; }
    echo "[ay] restoring $dump into database 'artha' (per-schema -Fc dump)"
    compose cp "$dump" timescaledb:/tmp/ay-restore.dump
    compose exec -T timescaledb pg_restore -U artha -d artha --clean --if-exists --no-owner /tmp/ay-restore.dump
    compose exec -T timescaledb rm -f /tmp/ay-restore.dump
    echo "[ay] restore complete"
    ;;
  reset-db)
    compose --profile obs --profile dev-tools down -v
    init_local_config
    compose up -d --wait
    ;;
  *)
    cat <<'EOF'
ay - ArthaYantra operator CLI (project-scoped docker compose)

  ay up [obs] [dev-tools]   start the stack (creates .env + db password if missing)
  ay down                   stop project containers (volumes kept)
  ay logs <svc>             follow logs for one service
  ay status                 healthcheck summary of all containers
  ay backup                 manual pg_dump into ./backups
  ay restore <file>         restore a -Fc dump file into the database
  ay reset-db               down, DROP VOLUMES, re-up (flyway rebuilds schemas)
EOF
    ;;
esac
