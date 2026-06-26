# 2b-E1 — NIFTY continuous front-future 1m backfill (manual test)

**What shipped:** a historical backfill that reconstructs the `NIFTY-FUT-CONT` continuous
front-future 1m candle series so a backtest signal can read it over a fresh window. The live
16:15 `ContinuousFuturesRoller` only sees currently-listed contracts; this reuses its
roll/stitch loop but sources the FULL ladder — `expired_contracts` FUT ∪ live `instruments`
futures — and materialises the front-month stitch into `marketdata.candles`.

Why materialise (not read-time-virtual): the backtest reads its primary 1m via a DIRECT JDBC
read from `marketdata.candles`, so a read-time reader in market-data would never be seen. The
continuous-future infra already materialises (`stitchInto` + `roll_events` + `ContBackAdjuster`),
so this just feeds it the historical roster. Row cost is trivial (one series).

## Preconditions
- Live stack up (`.\ay.ps1 up`), market-data healthy.
- `expired_contracts` has the NIFTY FUT roster (the Upstox expired-backfill ran). Check:
  ```bash
  docker exec ay-timescaledb psql -U artha -d artha -tA -c \
    "SELECT count(*), min(expiry), max(expiry) FROM marketdata.expired_contracts WHERE instrument_type='FUT' AND underlying_symbol='NIFTY';"
  ```
  Expect ≥ 12 contracts spanning the past year, each with dense 1m bars in `candles`.

## Trigger (owner-driven, idempotent — safe to re-run)
Authenticate to the gateway, seed the XSRF cookie, then POST the admin endpoint:
```bash
COOKIE=$(mktemp)
curl -sc "$COOKIE" -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"password":"<owner-password>"}' -o /dev/null
curl -sb "$COOKIE" -c "$COOKIE" http://localhost:8080/api/v1/auth/me -o /dev/null
XSRF=$(grep XSRF "$COOKIE" | awk '{print $NF}')
curl -sb "$COOKIE" -H "X-XSRF-TOKEN: $XSRF" -X POST \
  "http://localhost:8080/api/v1/market/admin/futures/continuous-backfill"
rm "$COOKIE"
# defaults: root=NIFTY, underlyingExchange=NSE, underlying=NIFTY 50
# → {"root":"NIFTY","contSymbol":"NIFTY-FUT-CONT","contracts":<n>}
```
The call runs synchronously and is fast (it stitches 1m/1d bars only — it deliberately does NOT
refresh the mid-interval continuous aggregates; see Notes).

## Verify the series
1. **Bars materialised + span the roster:**
   ```bash
   docker exec ay-timescaledb psql -U artha -d artha -tA -c \
     "SELECT interval, count(*), min(bucket), max(bucket) FROM marketdata.candles WHERE tradingsymbol='NIFTY-FUT-CONT' GROUP BY interval ORDER BY interval;"
   ```
   Expect the 1m count to jump from ~hundreds to tens of thousands, `min(bucket)` back at the
   earliest expired contract's first bar.
2. **Roll events appended:**
   ```bash
   docker exec ay-timescaledb psql -U artha -d artha -tA -c \
     "SELECT roll_date, from_tradingsymbol, to_tradingsymbol, price_gap FROM marketdata.roll_events WHERE underlying='NIFTY 50' ORDER BY roll_date;"
   ```
   Expect one row per monthly roll (e.g. JAN→FEB→MAR…), gap = next.close − this.close.
3. **Continuity across a roll (raw):** read a few minutes either side of a roll date and confirm
   the symbol switches with no time gap (one-bar basis discontinuity at the switch is expected
   and documented — B-19):
   ```bash
   docker exec ay-timescaledb psql -U artha -d artha -c \
     "SELECT bucket, close FROM marketdata.candles WHERE tradingsymbol='NIFTY-FUT-CONT' AND interval='1m' AND bucket BETWEEN '2026-02-23 09:45+05:30' AND '2026-02-24 10:15+05:30' ORDER BY bucket;"
   ```
4. **Back-adjusted read (HTTP):** `GET /api/v1/market/candles?exchange=NFO&tradingsymbol=NIFTY-FUT-CONT&interval=1m&adjust=back&from=...&to=...` shifts pre-roll bars by the cumulative gap.

## Backtest reads it (the point of 2b-E1)
A strategy whose signal underlying is `NFO:NIFTY-FUT-CONT` (1m) now gets a continuous series.
Full wiring (`universe.signal_underlying`) lands in 2b-E2; until then, a single-instrument
universe pointing at `NIFTY-FUT-CONT` confirms the read path:
```bash
# pre-flight: the backtest's CandleReader reads marketdata.candles directly, so the bars above are
# what it sees — count1mBuckets over the window should be > 0 for NFO / NIFTY-FUT-CONT.
```

## Notes
- Backtests read the UNADJUSTED CONT 1m (the direct-JDBC path); the one-bar roll-day basis gap is
  the known B-19 artifact. Intraday scalpers reset daily, so a monthly roll-day discontinuity is
  immaterial to signal quality.
- Re-running the admin call is a no-op (`stitchInto` is `ON CONFLICT DO NOTHING`, `roll_events`
  dedupes on `(underlying, roll_date)`); it also extends the stitch if new expired contracts have
  since registered.
- SENSEX-FUT-CONT is intentionally NOT built: per the locked design, the SENSEX scalper variant
  signals on the NIFTY future (correlation play), so only `NIFTY-FUT-CONT` is needed.
- **CONT mid-interval caggs (5m/15m/1h/1d/1w) are NOT materialised over history by the backfill.**
  Stitching months of bars at once invalidates a wide cagg range, and since the expired-OI backfill
  never materialised those caggs over history, a refresh would re-aggregate ~106k expired contracts'
  buckets in one lock-holding, OOM-risky call (this stalled the first live run for >4 min and blocked
  the live refresh policy — terminated, harmless: the 1m bars had already committed). Backtests read
  CONT **1m from the base table**, so the 1m stitch is all they need; reading CONT at 5m/15m over
  history returns the real-time-aggregated view (raw 1m unioned in) or empty, not a stalled refresh.
