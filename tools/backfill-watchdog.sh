#!/bin/sh
# Backfill auto-heal watchdog: 18 cycles x 10min = 3h.
# Re-arms backfill if BACKFILL row count goes flat across a cycle (idempotent; 409 if already running).
# Durable mitigation for the un-deployed per-expiry resilience fix (#140).
PSQL="docker exec ay-timescaledb psql -U artha -d artha -tAc"
prev=-1
for i in $(seq 1 18); do
  rows=$($PSQL "SELECT count(*) FROM marketdata.candles WHERE source='BACKFILL';" 2>/dev/null | tr -d '[:space:]')
  syms=$($PSQL "SELECT count(*) FROM marketdata.expired_contracts WHERE tradingsymbol LIKE 'SENSEX%';" 2>/dev/null | tr -d '[:space:]')
  last=$(docker logs ay-market-data-service --since 11m 2>&1 | grep -oE '(SENSEX|NIFTY) 20[0-9-]+' | tail -1)
  echo "[cycle $i] BACKFILL rows=${rows:-?} (prev=$prev) SENSEX_syms=${syms:-?} lastlog=${last}"
  if [ -n "$rows" ] && [ "$rows" = "$prev" ]; then
    echo "  -> STALLED (rows flat). Re-triggering (idempotent; 409 if already running)."
    docker exec ay-edge-gateway sh -c 'wget -qO- --timeout=30 --header="Content-Type: application/json" --post-data="{}" http://market-data-service:8081/api/v1/market/admin/expired-backfill 2>&1; echo'
  fi
  [ -n "$rows" ] && prev="$rows"
  [ "$i" -lt 18 ] && sleep 600
done
echo "=== watchdog finished 18 cycles ==="
