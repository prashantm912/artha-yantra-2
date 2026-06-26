# 2b-E2b — strike-reference spot (SENSEX strikes anchored on SENSEX-fut)

**What shipped:** the ATM-strike spot is decoupled from the signal series. A scalper can signal on the
NIFTY continuous front-future while executing SENSEX option legs whose strike is picked from the SENSEX
price (not the NIFTY-future price). Optional `universe.strike_reference` (default = the signal series →
existing configs byte-identical). See [ADR-0003](../adr/0003-scalper-signal-strike-option-decoupling.md).

## Preconditions
- 2b-E1 + 2b-E2 merged + deployed (the continuous-future backfill + signal decoupling).
- SENSEX expired FUT + options backfilled under exchange **BFO** (already present).

## Step 1 — build SENSEX-FUT-CONT (reuse the 2b-E1 backfill, no new code)
```bash
COOKIE=$(mktemp)
curl -sc "$COOKIE" -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"password":"<owner-password>"}' -o /dev/null
curl -sb "$COOKIE" -c "$COOKIE" http://localhost:8080/api/v1/auth/me -o /dev/null
XSRF=$(grep XSRF "$COOKIE" | awk '{print $NF}')
curl -sb "$COOKIE" -H "X-XSRF-TOKEN: $XSRF" -X POST \
  "http://localhost:8080/api/v1/market/admin/futures/continuous-backfill?root=SENSEX&underlyingExchange=BSE&underlying=SENSEX"
rm "$COOKIE"
# → {"root":"SENSEX","contSymbol":"SENSEX-FUT-CONT","contracts":<n>}
```
**Note the exchange:** the synthetic CONT row is stored under the front contract's OWN exchange = **BFO**
(SENSEX F&O). `underlyingExchange=BSE` stamps the synthetic's *underlying*, not its own exchange.

## Step 2 — verify the series
```bash
docker exec ay-timescaledb psql -U artha -d artha -c \
  "SELECT exchange, interval, count(*), min(bucket), max(bucket) FROM marketdata.candles WHERE tradingsymbol='SENSEX-FUT-CONT' GROUP BY exchange, interval;"
# expect exchange = BFO, interval 1m, tens of thousands of bars
```

## Step 3 — strike anchored on SENSEX, not NIFTY
A SENSEX scalper config carries:
```yaml
universe:
  mode: options_of_underlying
  underlying: { exchange: BSE, tradingsymbol: "SENSEX" }            # option-execution root
  signal_underlying: { exchange: NFO, tradingsymbol: "NIFTY-FUT-CONT" }  # signal on NIFTY-fut
  strike_reference: { exchange: BFO, tradingsymbol: "SENSEX-FUT-CONT" }  # strike anchored on SENSEX-fut
```
Run a backtest over a 2026 window. Confirm the trades' `tradingsymbol` are SENSEX strikes **near the
SENSEX spot** (~80000-range strikes), NOT ~24000 (which would mean the strike anchored on the NIFTY price).

## Step 4 — the loud-failure guard
A config whose `strike_reference` points at an uncovered/wrong series (e.g. a typo'd exchange) must fail
**422 DATA_GAP** ("strike_reference … has no candle coverage over the run window") at run start — NOT
silently anchor on the NIFTY signal price. Verify by pointing `strike_reference` at a nonexistent symbol.

## Notes
- Parity: absent `strike_reference`, the strike anchors on the signal bar's own close (byte-identical to
  pre-2b-E2b; the candle-close + premium goldens hold).
- The strike anchor only needs to be roughly right — `atm_window: ±3` then the StrikePicker refines by
  delta (the SENSEX-fut close is also the Black-76 forward), so the front-future basis is immaterial.
- Carry-forward: a missing strike-ref minute uses the last prior bar's price (front-future ≈ spot). The
  guard only catches a fully-EMPTY series; partial gaps degrade gracefully by design.
