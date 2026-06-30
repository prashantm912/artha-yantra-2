#!/bin/sh
# db-backup sidecar (A.11 / plan §9.10, §12.7): WHOLE-DATABASE pg_dump -Fc (+ a
# cluster-globals dump) into the bind-mounted ./backups on the Windows
# filesystem (survives the WSL2 VM by design). Nightly at 00:30 IST via crond;
# `ay backup` runs it with mode=manual. Rotation: 7 nightly + 4 weekly. On
# failure the script POSTs the ops ntfy topic directly — shell-side, no shared
# code; a no-op when ARTHA_NTFY_TOPIC is unset.
#
# WHY WHOLE-DATABASE (not per-schema): a `pg_dump -n <schema>` does NOT capture
# TimescaleDB hypertable DATA — chunks live in the _timescaledb_internal schema,
# OUTSIDE the dumped namespace — so candles / options_chain_snapshots (200M+
# rows, the expensive backfilled history) were silently omitted and only the
# small relational tables were saved. A whole-database dump captures every schema
# PLUS the chunk data, and is the only dump that round-trips a `reset-db` rebuild.
# Roles/grants are CLUSTER-global (not inside any one database) so they need a
# separate `pg_dumpall --globals-only`. Restore is the Timescale pre/post_restore
# dance — see `ay restore`.
set -u

MODE="${1:-nightly}"
BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
DB="${PGDATABASE:-artha}"
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

# 1) Cluster globals (roles + grants) — tiny, restored before the data dump so the
#    per-schema ay_* read-only roles exist for the dump's GRANT statements.
if ! pg_dumpall --globals-only -f "$dest/globals.sql"; then
  notify_failure "backup $STAMP FAILED (mode=$MODE): globals dump"
  echo "[backup] FAILED at $STAMP (globals)" >&2
  rm -rf "$dest"
  exit 1
fi

# 2) Whole-database custom-format dump (includes _timescaledb_internal chunk data).
if ! pg_dump -Fc -d "$DB" -f "$dest/${DB}-full.dump"; then
  notify_failure "backup $STAMP FAILED (mode=$MODE, db=$DB): data dump"
  echo "[backup] FAILED at $STAMP (db=$DB)" >&2
  rm -rf "$dest"
  exit 1
fi

# weekly retention copy: Sunday nightly dumps also land in weekly/
if [ "$MODE" = "nightly" ] && [ "$(date +%u)" = "7" ]; then
  wdest="$BACKUP_ROOT/weekly/$STAMP"
  mkdir -p "$wdest" && cp "$dest"/globals.sql "$dest"/*.dump "$wdest"/
fi

# rotation: keep newest N timestamped dirs (manual/ dumps are never pruned). Full
# dumps are GB-scale, so retention is shallower than the old per-schema dumps.
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
rotate "$BACKUP_ROOT/nightly" 7
rotate "$BACKUP_ROOT/weekly" 4

sz="$(ls -l "$dest/${DB}-full.dump" 2>/dev/null | awk '{print $5}')"
echo "[backup] OK mode=$MODE db=$DB -> $dest (whole-db dump ${sz:-?} bytes + globals)"
