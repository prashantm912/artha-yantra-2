#!/usr/bin/env bash
# Upstox-vs-Kite OI A/B-diff (Wave U4 cutover gate, plan §59 / runbook
# docs/manual-tests/wave-u4-upstox-cutover.md). Runs against the LIVE running stack.
#  CANARY  — does the direct-Upstox /v2/option/chain return real per-strike OI (sum > 0)?
#  PARITY  — for the chain's strikes, does the Upstox per-strike OI agree with the Kite OI
#            our OptionsSnapshotService already captured into the live DB? Both are
#            NSE/BSE-official, so they must reconcile before `source.optionchain` is flipped.
# READ-ONLY: no source flips, no writes. The Kite side is the freshest rows in
# marketdata.options_chain_snapshots (the live Kite capture path), compared as-is — capture
# is never touched, so this is safe to run alongside the running Kite capture.
#
# This is the OI half of the U4 gate; the ticker-latency half is the U3 runbook
# (docs/manual-tests/wave-u3-upstox-ws-ticker.md). Run BEFORE flipping source.optionchain.
#
# Plumbing notes (carried over from tools/openalgo/oi-parity-check.sh — same two traps):
#  - market-data-service is a slim JRE image: NO python3. HTTPS is done with its busybox wget
#    INSIDE the container; JSON is parsed on the HOST (python).
#  - The Upstox analytics token is read INSIDE the container and used in the Authorization
#    header there — `$(docker exec ... cat /run/secrets/...)` returns EMPTY/mangled under
#    git-bash, so the token never leaves the box.
#
# Run from repo root against the live stack:
#   EXPIRY=2026-06-30 bash tools/upstox/oi-ab-diff.sh
TOL_PCT="${TOL_PCT:-5}"         # per-leg OI %diff above this = parity WARN
FRESH_MIN="${FRESH_MIN:-15}"    # Kite DB rows older than this (min) = stale, skip
EXPIRY="${EXPIRY:-}"            # REQUIRED: the (ISO yyyy-MM-dd) expiry to diff
MD=ay-market-data-service
DB=ay-timescaledb
PY=$(command -v python || command -v python3)

set -uo pipefail

if [ -z "$EXPIRY" ]; then
  echo "ERROR: set EXPIRY=yyyy-MM-dd (the option expiry to diff). e.g. EXPIRY=2026-06-30 bash $0"
  exit 2
fi

# DB_UNDERLYING | UPSTOX_INSTRUMENT_KEY   (the verified U1 index-key map)
UNDERLYINGS=(
  "NIFTY 50|NSE_INDEX|Nifty 50"
  "SENSEX|BSE_INDEX|SENSEX"
)

# Direct-Upstox GET, in-container, token spliced into the bearer header there. $1 = full path+query.
ux_get() {
  docker exec -e UXPATH="$1" "$MD" sh -c '
    TOK=$(cat /run/secrets/upstox_analytics_token)
    wget -qO- --header="Authorization: Bearer $TOK" --header="Accept: application/json" \
      "https://api.upstox.com$UXPATH" 2>&1
  '
}

# URL-encode the few chars in an instrument_key that matter (| and space).
urlenc() { printf '%s' "$1" | sed 's/|/%7C/g; s/ /%20/g'; }

overall_canary_ok=1
overall_parity_ok=1

for pair in "${UNDERLYINGS[@]}"; do
  # The Upstox key itself contains a '|', so split on the FIRST '|' only.
  DBNAME="${pair%%|*}"; UXKEY="${pair#*|}"
  echo "============================================================"
  echo "Underlying: $DBNAME  (Upstox $UXKEY)  expiry $EXPIRY"

  # 1. whole chain in one call -> "META total" + "strike ce_oi pe_oi" rows (host parse)
  ENCKEY=$(urlenc "$UXKEY")
  chainj=$(ux_get "/v2/option/chain?instrument_key=$ENCKEY&expiry_date=$EXPIRY")
  parsed=$(printf '%s' "$chainj" | "$PY" -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print("ERR parse",e); sys.exit()
if d.get("status")!="success": print("ERR status",d.get("errors") or d.get("message")); sys.exit()
rows=d.get("data") or []
tot=0; out=[]
for r in rows:
    k=r.get("strike_price")
    ce=((r.get("call_options") or {}).get("market_data") or {}).get("oi") or 0
    pe=((r.get("put_options") or {}).get("market_data") or {}).get("oi") or 0
    if k is None: continue
    tot+=ce+pe; out.append(f"{k} {ce} {pe}")
print("META",tot); print("\n".join(out))')
  if printf '%s' "$parsed" | head -1 | grep -q '^ERR'; then
    echo "  Upstox chain ERROR: $(printf '%s' "$parsed" | head -c 200)"; overall_canary_ok=0; continue
  fi
  TOTAL=$(printf '%s' "$parsed" | awk '/^META/{print $2; exit}')
  echo "  CANARY: Upstox total OI (all returned strikes) = $TOTAL"
  if awk -v t="${TOTAL:-0}" 'BEGIN{exit !(t>0)}'; then
    echo "  CANARY: PASS (OI > 0)"
  else
    echo "  CANARY: FAIL (OI not > 0 — chain empty or stale expiry)"; overall_canary_ok=0; continue
  fi

  # 2. parity vs freshest Kite OI in the live DB (strike-by-strike)
  printf "  %-9s | %-3s | %12s | %12s | %8s\n" "strike" "opt" "upstox_oi" "kite_db_oi" "diff%"
  echo "  ---------------------------------------------------------"
  matched=0; warned=0; stale=0
  while read -r strike ce_oi pe_oi; do
    [ -z "${strike:-}" ] && continue
    # strike is e.g. 25000.0 — Postgres numeric '=' treats 25000.0 = 25000, so pass it as-is.
    for leg in "CE:$ce_oi" "PE:$pe_oi"; do
      ot=${leg%%:*}; uxoi=${leg##*:}
      koi=$(docker exec "$DB" psql -U artha -d artha -t -A -c \
        "SELECT oi FROM marketdata.options_chain_snapshots WHERE underlying='$DBNAME' AND expiry='$EXPIRY' AND strike=$strike AND option_type='$ot' AND ts > now() - interval '$FRESH_MIN minutes' ORDER BY ts DESC LIMIT 1;" 2>/dev/null | tr -d '[:space:]')
      if [ -z "$koi" ]; then stale=$((stale+1)); printf "  %-9s | %-3s | %12s | %12s | %8s\n" "$strike" "$ot" "$uxoi" "STALE/-" "-"; continue; fi
      matched=$((matched+1))
      diff=$(awk -v a="$uxoi" -v b="$koi" 'BEGIN{ if(b==0){print (a==0)?0:999} else printf "%.1f", (a-b)/b*100 }')
      flag=""; awk -v d="$diff" -v t="$TOL_PCT" 'BEGIN{exit !(d<0?-d>t:d>t)}' && { flag="  <-WARN"; warned=$((warned+1)); }
      printf "  %-9s | %-3s | %12s | %12s | %7s%%%s\n" "$strike" "$ot" "$uxoi" "$koi" "$diff" "$flag"
    done
  done < <(printf '%s' "$parsed" | grep -vE '^META|^ERR')

  if [ "$matched" -eq 0 ]; then
    echo "  PARITY: NO FRESH KITE DATA (all $stale legs stale > ${FRESH_MIN}m) — run during market hours"
  else
    echo "  PARITY: $matched legs compared, $warned over ${TOL_PCT}% tolerance, $stale stale"
    [ "$warned" -gt 0 ] && overall_parity_ok=0
  fi
done

echo "============================================================"
echo "CANARY overall: $([ $overall_canary_ok -eq 1 ] && echo PASS || echo FAIL)"
echo "PARITY overall: $([ $overall_parity_ok -eq 1 ] && echo PASS || echo 'WARN (see above)')"
echo "Flip source.optionchain=upstox ONLY when both are PASS over a live session (runbook §C)."
[ $overall_canary_ok -eq 1 ] && [ $overall_parity_ok -eq 1 ]
