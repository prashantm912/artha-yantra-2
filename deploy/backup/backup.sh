#!/bin/sh
# db-backup sidecar (A.11 / plan §9.10, §12.7): per-schema pg_dump -Fc into
# the bind-mounted ./backups on the Windows filesystem (survives the WSL2 VM
# by design). Nightly at 00:30 IST via crond; `ay backup` runs it with
# mode=manual. Rotation: 14 nightly + 8 weekly. On failure the script POSTs
# the ops ntfy topic directly — shell-side, no shared code; a no-op when
# ARTHA_NTFY_TOPIC is unset.
set -u

MODE="${1:-nightly}"
BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
SCHEMAS="marketdata strategy backtest"
STAMP="$(date +%Y%m%d-%H%M%S)"
PGPASSWORD="$(cat /run/secrets/postgres_password)"
export PGPASSWORD

notify_failure() {
  # plan §12.7 first-party critical alert; no-op without a topic
  [ -z "${ARTHA_NTFY_TOPIC:-}" ] && return 0
  url="${ARTHA_NTFY_URL:-https://ntfy.sh}/${ARTHA_NTFY_TOPIC}"
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 10 -H "Title: ArthaYantra backup FAILED" -d "$1" "$url" >/dev/null 2>&1 || true
  else
    wget -q -T 10 --header "Title: ArthaYantra backup FAILED" --post-data "$1" -O /dev/null "$url" 2>/dev/null || true
  fi
}

dest="$BACKUP_ROOT/$MODE/$STAMP"
if ! mkdir -p "$dest"; then
  notify_failure "backup $STAMP: cannot create $dest"
  exit 1
fi

fail=""
dumped=0
for schema in $SCHEMAS; do
  exists="$(psql -X -t -A -c "SELECT 1 FROM information_schema.schemata WHERE schema_name='$schema'" 2>/dev/null || true)"
  if [ "$exists" != "1" ]; then
    echo "[backup] schema $schema absent - skipped (pre-flyway volume?)"
    continue
  fi
  if pg_dump -Fc -n "$schema" -f "$dest/${schema}.dump"; then
    dumped=$((dumped + 1))
  else
    fail="$fail $schema"
  fi
done

if [ -n "$fail" ] || [ "$dumped" -eq 0 ]; then
  notify_failure "backup $STAMP FAILED (mode=$MODE, failed:${fail:-' none dumped'})"
  echo "[backup] FAILED at $STAMP (failed:${fail:-' none dumped'})" >&2
  rm -rf "$dest"
  exit 1
fi

# weekly retention copy: Sunday nightly dumps also land in weekly/
if [ "$MODE" = "nightly" ] && [ "$(date +%u)" = "7" ]; then
  wdest="$BACKUP_ROOT/weekly/$STAMP"
  mkdir -p "$wdest" && cp "$dest"/*.dump "$wdest"/
fi

# rotation: keep newest N timestamped dirs (manual/ dumps are never pruned)
rotate() {
  dir="$1"
  keep="$2"
  [ -d "$dir" ] || return 0
  total="$(ls -1 "$dir" 2>/dev/null | wc -l)"
  excess=$((total - keep))
  [ "$excess" -le 0 ] && return 0
  ls -1 "$dir" | sort | head -n "$excess" | while read -r old; do
    rm -rf "${dir:?}/${old:?}"
    echo "[backup] rotated out $dir/$old"
  done
}
rotate "$BACKUP_ROOT/nightly" 14
rotate "$BACKUP_ROOT/weekly" 8

echo "[backup] OK mode=$MODE -> $dest ($dumped schema dumps)"
