#!/usr/bin/env bash
# Wrapper invoked by the Windows "ArthaYantra-OI-Parity" scheduled task at Monday
# market-open. Runs the OI canary+parity check against the live stack and writes a
# timestamped report under tools/openalgo/reports/. Safe to run any time (off-market
# just shows STALE). No Claude required.
cd "$(dirname "$0")/../.." || exit 1
mkdir -p tools/openalgo/reports
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="tools/openalgo/reports/parity-$STAMP.txt"
{
  echo "OI canary+parity run @ $(date)"
  echo
  bash tools/openalgo/oi-parity-check.sh
  echo
  echo "exit=$?"
} > "$OUT" 2>&1
echo "wrote $OUT"
