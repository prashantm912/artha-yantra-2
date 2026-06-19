#!/usr/bin/env bash
# OpenAlgo OI cutover gate (plan §17.11): runs against the LIVE running stack.
#  CANARY  — does OpenAlgo /optionchain return real per-strike OI (sum > 0)?
#  PARITY  — for ATM±N strikes, does OpenAlgo OI agree with the Kite OI our
#            OptionsSnapshotService already captured into the live DB?
# READ-ONLY: no source flips, no writes. Kite side = freshest rows in
# marketdata.options_chain_snapshots (captured by the live Kite path), so the
# two sources are compared as-is without touching capture.
#
# Plumbing notes (both real traps hit while building this):
#  - market-data-service is a slim JRE image: NO python3. HTTP is done with its
#    busybox wget INSIDE the container; JSON is parsed on the HOST (python).
#  - The OpenAlgo API key is read INSIDE the container and spliced into the body
#    with sed — `$(docker exec ... cat /run/secrets/...)` returns EMPTY under
#    git-bash (mangled on the capture round-trip), so the key never leaves the box.
#
# Run from repo root against the live stack:  bash tools/openalgo/oi-parity-check.sh
STRIKES="${STRIKES:-10}"        # ATM +/- N strikes pulled from OpenAlgo
TOL_PCT="${TOL_PCT:-5}"         # per-leg OI %diff above this = parity WARN
FRESH_MIN="${FRESH_MIN:-15}"    # Kite DB rows older than this (min) = stale, skip
MD=ay-market-data-service
DB=ay-timescaledb
PY=$(command -v python || command -v python3)

set -uo pipefail

# DB_NAME | OPENALGO_UNDERLYING | OPENALGO_EXCHANGE
UNDERLYINGS=(
  "NIFTY 50|NIFTY|NFO"
  "SENSEX|SENSEX|BFO"
)

# wget in-container; key read locally and spliced after the leading '{'. $1=path $2=body(no apikey)
oa_call() {
  docker exec -e BODY="$2" -e APIPATH="$1" "$MD" sh -c '
    API=$(cat /run/secrets/openalgo_api_key)
    FULL=$(printf "%s" "$BODY" | sed "s/^{/{\"apikey\":\"$API\",/")
    wget -qO- --header="Content-Type: application/json" --post-data="$FULL" http://openalgo:5000/api/v1/$APIPATH 2>&1
  '
}

overall_canary_ok=1
overall_parity_ok=1

for triple in "${UNDERLYINGS[@]}"; do
  IFS='|' read -r DBNAME OAU OAEX <<<"$triple"
  echo "============================================================"
  echo "Underlying: $DBNAME  (OpenAlgo $OAU / $OAEX)"

  # 1. nearest expiry -> iso date + DDMMMYY token (host parse)
  expj=$(oa_call expiry "{\"symbol\":\"$OAU\",\"exchange\":\"$OAEX\",\"instrumenttype\":\"options\"}")
  read -r ISO TOKEN <<<"$(printf '%s' "$expj" | "$PY" -c '
import sys,json,datetime
try:
    d=json.load(sys.stdin)["data"][0].strip()
    dt=datetime.datetime.strptime(d,"%d-%b-%y")
    print(dt.strftime("%Y-%m-%d"), dt.strftime("%d%b%y").upper())
except Exception: print("", "")')"
  if [ -z "$TOKEN" ]; then echo "  ERROR: no expiry from OpenAlgo: $(printf '%s' "$expj" | head -c 160)"; overall_canary_ok=0; continue; fi
  echo "  nearest expiry: DB=$ISO token=$TOKEN"

  # 2. chain -> "META total" + "strike ce_oi pe_oi" rows (host parse)
  chainj=$(oa_call optionchain "{\"underlying\":\"$OAU\",\"exchange\":\"$OAEX\",\"expiry_date\":\"$TOKEN\",\"strike_count\":$STRIKES}")
  parsed=$(printf '%s' "$chainj" | "$PY" -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print("ERR parse",e); sys.exit()
if d.get("status")!="success": print("ERR status",d.get("message")); sys.exit()
tot=0; out=[]
for r in d.get("chain",[]):
    k=r.get("strike"); ce=(r.get("ce") or {}).get("oi") or 0; pe=(r.get("pe") or {}).get("oi") or 0
    tot+=ce+pe; out.append(f"{k} {ce} {pe}")
print("META",tot); print("\n".join(out))')
  if printf '%s' "$parsed" | head -1 | grep -q '^ERR'; then echo "  OpenAlgo chain ERROR: $parsed"; overall_canary_ok=0; continue; fi
  TOTAL=$(printf '%s' "$parsed" | awk '/^META/{print $2; exit}')
  echo "  CANARY: OpenAlgo total OI (ATM+/-$STRIKES) = $TOTAL"
  if [ "${TOTAL:-0}" -gt 0 ] 2>/dev/null; then echo "  CANARY: PASS (OI > 0)"; else echo "  CANARY: FAIL (OI not > 0)"; overall_canary_ok=0; fi

  # 3. parity vs freshest Kite OI in the live DB
  printf "  %-9s | %-3s | %12s | %12s | %8s\n" "strike" "opt" "openalgo_oi" "kite_db_oi" "diff%"
  echo "  ---------------------------------------------------------"
  matched=0; warned=0; stale=0
  while read -r strike ce_oi pe_oi; do
    [ -z "${strike:-}" ] && continue
    for leg in "CE:$ce_oi" "PE:$pe_oi"; do
      ot=${leg%%:*}; oaoi=${leg##*:}
      koi=$(docker exec "$DB" psql -U artha -d artha -t -A -c \
        "SELECT oi FROM marketdata.options_chain_snapshots WHERE underlying='$DBNAME' AND expiry='$ISO' AND strike=$strike AND option_type='$ot' AND ts > now() - interval '$FRESH_MIN minutes' ORDER BY ts DESC LIMIT 1;" 2>/dev/null | tr -d '[:space:]')
      if [ -z "$koi" ]; then stale=$((stale+1)); printf "  %-9s | %-3s | %12s | %12s | %8s\n" "$strike" "$ot" "$oaoi" "STALE/-" "-"; continue; fi
      matched=$((matched+1))
      diff=$(awk -v a="$oaoi" -v b="$koi" 'BEGIN{ if(b==0){print (a==0)?0:999} else printf "%.1f", (a-b)/b*100 }')
      flag=""; awk -v d="$diff" -v t="$TOL_PCT" 'BEGIN{exit !(d<0?-d>t:d>t)}' && { flag="  <-WARN"; warned=$((warned+1)); }
      printf "  %-9s | %-3s | %12s | %12s | %7s%%%s\n" "$strike" "$ot" "$oaoi" "$koi" "$diff" "$flag"
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
[ $overall_canary_ok -eq 1 ] && [ $overall_parity_ok -eq 1 ]
