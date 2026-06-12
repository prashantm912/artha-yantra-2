# Runbook notes (Phase 16)

## Kite minute-depth probe (feeds amendment A3)

Run ONCE in live mode after the first successful morning ritual; the outcome
decides how deep the Phase-11 cache may backfill 1m history.

```bash
# inside the compose network, with a valid session (token decrypts from the store):
curl -s -b cookies.txt '127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=RELIANCE&interval=1m&from=2015-03-02T09:15:00%2B05:30&to=2015-03-02T10:15:00%2B05:30'
```

- Rows returned → Kite still serves 2015 minute data; record the earliest
  working window in amendment A3 and let backfills page back to it.
- Empty/`DATA_STALE` → record the earliest window that DOES work (bisect by
  year). The cache never assumes depth it has not probed.

The call rides the normal 3/s historical limiter — one probe is budget noise.

## S2 expiry-day observation (B-15)

NSE index weeklies expire **Tuesday** (moved from Thursday, September 2025 —
SEBI single-expiry-day rule; `MarketCalendar.nextWeeklyIndexExpiry` encodes
this). On the first live Tuesday observe around 15:25–15:30 IST:

- option chain rows flip to `EXPIRED` reason after the 15:30 cutoff
  (IV/Greeks null by definition, raw quotes still captured);
- the 15:45 EOD backfill picks up the final session bars;
- the 16:15 roll scheduler only acts on FUT contracts at
  `roll_days_before_expiry` — index weeklies have no futures leg.

Record anything surprising here and in PHASE_GATES before the Friday gate.
