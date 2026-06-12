#!/bin/sh
# flyway-init entrypoint (A.8 / D17): one Flyway invocation per lineage —
# admin FIRST (roles/schemas/grants), then the three per-schema lineages,
# giving each an independent flyway_schema_history. Exits 0 only when all
# four lineages migrate cleanly; app services gate on
# service_completed_successfully.
set -eu

DB_URL="jdbc:postgresql://timescaledb:5432/artha"
DB_USER="artha"
DB_PASSWORD="$(cat /run/secrets/postgres_password)"

run_lineage() {
  lineage="$1"
  echo "[flyway-init] migrating lineage: $lineage"
  flyway \
    -url="$DB_URL" \
    -user="$DB_USER" \
    -password="$DB_PASSWORD" \
    -schemas="$lineage" \
    -locations="filesystem:/flyway/sql/$lineage" \
    -connectRetries=30 \
    migrate
}

run_lineage admin
run_lineage marketdata
run_lineage strategy
run_lineage backtest

echo "[flyway-init] all four lineages migrated - exiting 0 (D17)"
