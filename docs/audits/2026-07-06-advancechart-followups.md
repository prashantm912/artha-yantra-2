# AdvanceChart follow-ups — 2026-07-06 (live-session, owner-reported)

Two issues surfaced while charting `NIFTY26JULFUT` (the live dated front NIFTY future) on the
AdvanceChart page during the 2026-07-06 session. Neither is data loss — both are
search/windowing UX. Logged for a later fix pass; **not** worked live.

## AC-1 (MEDIUM) — dated monthly futures are un-findable via a broad symbol search
`GET /api/v1/instruments/search?q=NIFTY` returns `NIFTY-FUT-CONT` (the intentionally-stale
continuous synthetic) and then floods with **options** (`NIFTY26JUL21100CE`…). The dated monthly
futures (`NIFTY26JULFUT`, `NIFTY26AUGFUT`, …) exist in `marketdata.instruments` (segment `NFO-FUT`,
`active=true`) but fall **past the result limit**, so a user typing the underlying never sees them.
A specific query (`q=NIFTY26JUL`) does surface `NIFTY26JULFUT` first — so the data + matching are
fine; it is purely **result ranking**.

- **Impact:** the owner could not find the current-month NIFTY future from a plain "NIFTY" search.
- **Workaround (works today):** type the fuller `NIFTY26JUL` / `NIFTY26JULFUT`.
- **Fix:** in the instrument-search ranking, surface `NFO-FUT` dated contracts (and the CONT) **above**
  `NFO-OPT` options when the query matches an underlying, so the handful of monthly futures rank
  ahead of the thousands of option strikes. Keep the CONT labelled/deprioritised vs the live dated
  front. Where: the market-data instrument-search service (the `/api/v1/instruments/search` handler)
  + confirm the AdvanceChart symbol picker consumes it.

## AC-2 (LOW) — coarse-interval intraday reads drop the current (day-anchored) bucket
On AdvanceChart the `1h` (and `Daily`) interval showed "no today's data" early in the session while
`1m/3m/5m/15m` all showed today correctly. Root cause is a **from-boundary interaction**, not a gap:
- The `candles_1h` cagg is IST-re-anchored (#67), so today's first hourly bucket is stamped **09:00
  IST** and holds the 09:15+ ticks (verified: `2026-07-06 03:30Z` = 09:00 IST, close 24440).
- The chart requested `from=2026-07-06T09:15:00+05:30` (market open). Since the bucket's timestamp
  (09:00) is **before** the requested `from` (09:15), a `bucket >= from` filter excludes it → `1h`
  returned 0 bars while `5m/15m` (buckets at/after 09:15) returned data.
- Compounded by early session: only ~40 min in, so `1h` has at most one (partial) bar and `Daily`
  none (today's daily forms at EOD) — so coarse intervals legitimately look near-empty at open.

Endpoint evidence (NIFTY26JULFUT, 09:15→15:30 IST window, ~09:54 IST): `1m`=40 · `3m`=14 · `5m`=8 ·
`15m`=3 · `1h`=0 · `1d`=0.

- **Impact:** low — intraday charting works on 1m/3m/5m/15m; only coarse intervals look empty and
  only near the open.
- **Workaround (works today):** use 5m/15m for intraday; 1h/Daily fill as the session/EOD progresses.
- **Fix:** for a coarse interval, floor the intraday `from` to the **bucket start that contains** the
  requested `from` (or to the IST day-start) so the current day-anchored coarse bucket isn't filtered
  out. Where: the candles read path (`CandleReader` / the `/api/v1/market/candles` windowing) — clamp
  `from` down to `time_bucket(interval, from)` for the coarse caggs. Parity-neutral (read-only chart
  path, no engine/golden surface).

## AC-3 (LOW, QUEUED — do NOT start until the owner asks) — true tick-smooth candle streaming
The chart auto-refreshes during market hours via a ~10s polite poll (`useCandles` `refetchInterval`,
shipped #600) — this killed the "manually refresh every few minutes" pain, but the last candle jumps
every ~10s rather than sliding tick-by-tick. The owner asked to **queue** true tick-streaming (the
deferred datafeed) as a follow-up, explicitly **not to start it yet**.

- **What it is:** wire the existing live-tick WS (`useLiveTicks`, `/topic/ticks.{exch}.{sym}`, already
  used by paper/scalper MTM) into the forming candle so its close/high/low updates on every tick, with
  a new bar appended at each interval-bucket rollover — the sub-second smooth movement a pro chart has.
- **Scope (why it's not a quick nit):** touch the chart components (`AdvanceChart` /
  `CandleChart`) to accept a live-LTP prop and mutate the last series point without a full re-render
  (lightweight-charts `series.update()` on the last bar), plus bucket-rollover logic (when `now`
  crosses the next interval boundary, seal the bar and open a new one seeded at the LTP). Reconcile
  with the 10s poll so the poll's authoritative bar replaces the tick-synthesised one on arrival
  (dedupe by bucket — the merge already keys by bucket). Only the 1m/3m/5m/15m intraday intervals
  need it; 1h+/daily stay poll-only. Keep it read-only/parity-neutral (chart path only).
- **Effort:** moderate FE (LWC series-update + rollover + WS wiring + a test for the rollover/seal).
  The 10s poll (#600) is the 90% fix; this is the last 10% of smoothness.

---
*Filed 2026-07-06 during the live session. AC-1/AC-2 are UX/windowing; AC-3 is a queued polish lift.
Priority: AC-1 first (discoverability), then AC-3 (smoothness) if the owner wants it, AC-2 is a nit.
Status: the live-session future-window bug (separate from AC-2) was fixed in #599; the ~10s
auto-refresh shipped in #600.*
