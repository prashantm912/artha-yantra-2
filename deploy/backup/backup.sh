#!/bin/sh
# db-backup sidecar (A.11 / plan §9.10, §12.7): WHOLE-DATABASE pg_dump -Fc (+ a
# cluster-globals dump) into the bind-mounted ./backups on the Windows
# filesystem (survives the WSL2 VM by design). Nightly at 00:30 IST via crond;
# `ay backup` runs it with mode=manual. Retention: keep only the 3 NEWEST backups
# across manual/ nightly/ weekly/ COMBINED (each whole-db dump is ~3 GB) — a 4th
# evicts the oldest. On failure the script POSTs the ops ntfy topic directly —
# shell-side, no shared code; a no-op when ARTHA_NTFY_TOPIC is unset.
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

# Per-DATABASE retention (audit P0-5): keep only the KEEP_PER_DB newest dumps OF THIS
# DATABASE across manual/ nightly/ weekly/ combined (each whole-db dump is ~3 GB, so a
# handful of slots is plenty). Only stamp dirs containing "${DB}-full.dump" count and are
# pruned — a dump of ANOTHER database (or a pre-#395 per-schema dir, which has no
# *-full.dump) can neither occupy this DB's slots nor be rotated out by its backups; the
# old GLOBAL cap let three mock-mode nightlies silently evict the only live dump. The
# stamp dir name is YYYYMMDD-HHMMSS, so a lexical sort is chronological; the just-written
# $dest is the newest and always survives. weekly/ is no longer written to but is still
# scanned so pre-existing weekly copies of this DB age out too. manual/ dumps are NOT
# exempt — they count toward the cap.
KEEP_PER_DB=3
prune_db() {
  keep="$1"
  pairs="$(
    for d in "$BACKUP_ROOT"/manual/*/ "$BACKUP_ROOT"/nightly/*/ "$BACKUP_ROOT"/weekly/*/; do
      [ -d "$d" ] || continue                 # literal glob (no match) -> skip
      [ -f "${d%/}/${DB}-full.dump" ] || continue   # only THIS database's dumps
      printf '%s\t%s\n' "$(basename "$d")" "${d%/}"
    done | sort                               # ascending by stamp -> oldest first
  )"
  [ -z "$pairs" ] && return 0
  total="$(printf '%s\n' "$pairs" | wc -l)"
  excess=$((total - keep))
  [ "$excess" -le 0 ] && return 0
  printf '%s\n' "$pairs" | head -n "$excess" | cut -f2- | while IFS= read -r old; do
    rm -rf "$old"
    echo "[backup] rotated out $old"
  done
}
prune_db "$KEEP_PER_DB"

# Dead-man signal (audit P0-5): a SUCCESS ping too, so "no message by morning" means the
# nightly did not run (stack down at 00:30, crond dead) — a silent miss was previously
# indistinguishable from a quiet success. No-op when ARTHA_NTFY_TOPIC is unset.
notify_success() {
  [ -z "${ARTHA_NTFY_TOPIC:-}" ] && return 0
  url="${ARTHA_NTFY_URL:-https://ntfy.sh}/${ARTHA_NTFY_TOPIC}"
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 10 -H "Title: ArthaYantra backup OK" -d "$1" "$url" >/dev/null 2>&1 || true
  else
    wget -q -T 10 --header "Title: ArthaYantra backup OK" --post-data "$1" -O /dev/null "$url" 2>/dev/null || true
  fi
}

sz="$(ls -l "$dest/${DB}-full.dump" 2>/dev/null | awk '{print $5}')"
notify_success "backup $STAMP OK (mode=$MODE, db=$DB, ${sz:-?} bytes)"
echo "[backup] OK mode=$MODE db=$DB -> $dest (whole-db dump ${sz:-?} bytes + globals)"
