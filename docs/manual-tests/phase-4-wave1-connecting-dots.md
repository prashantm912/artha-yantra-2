# Phase 4 / Wave 1 — Connecting Dots (manual test + live-QA gate)

Page: React `ConnectingDotsPage` → `/features/connecting-dots` (`frontend-react/`). Backend feed:
`GET /api/v1/market/connecting-dots` (per-interval 11-factor sentiment matrix). Plan authority:
§20.7.8 + §20.8 (the standing live-oipulse QA gate). Study oracle:
`docs/oipulse-study/features/connecting-dots.md`.

## What it is

A multi-factor sentiment matrix for an index: each interval bucket is a row of **11 factors** each rated
3-state (0 Neutral ↔ / 1 Bullish ↑ / 2 Bearish ↓) plus a 5-state composite **Trend** (1 Ext.Bullish,
2 Bullish, 3 Bearish, 4 Ext.Bearish, 0 Neutral). 13 columns: `Date Time · Trend · Dow Jones · Vix ·
Volume · Active Strike IV · Active Strike OI · OI Inter. · VWAP · Supertrend · RSI · Price · Daily Trend`.

- **Factor sources** (all via exposed module APIs): the front-month FUTURES candle series (cache-first
  `CandleQueryService`, resampled to the interval, midnight-IST bucket parity with pg `time_bucket`)
  drives Price / VWAP / Supertrend / RSI / Volume / OI-Interpretation (the candle `oi`); the INDEX daily
  candle drives Daily Trend; INDIA VIX candles drive Vix (inverse); option snapshots
  (`OptionsSnapshotReader` + `ActiveStrikeService`) drive Active-Strike OI + ATM-IV.
- **Indicators computed locally** (`ConnectingDotsIndicators` — Wilder RSI/ATR, session VWAP, Supertrend
  direction); market-data has no ta4j.
- **Intervals** 3/5/10/15/30/60 (no 1m); 10m rides the new `OiInterval.M10`.

## How to run (dev, against the mock stack)

1. `./ay.ps1 up` (mock) — gateway 8080. (Mock fabricates the futures candle spine on a trading day.)
2. `cd frontend-react && npm run dev` → http://127.0.0.1:4300; log in; **Features → Connecting Dots**.
3. Pick an index + interval, set **History** mode + a weekday date (e.g. 2026-06-15), **Go**.

## Automated coverage (already green)

- `ConnectingDotsIndicatorsTest` (5) — RSI warmup/up/down, VWAP=typical, Supertrend uptrend flip, the
  composite net→trend mapping.
- `ConnectingDotsIntegrationTest` (2) — the matrix builds valid 3-state codes off the mock futures
  spine, newest-first, Dow Neutral, and the 10-min interval is accepted.
- `ConnectingDotsTable.spec.tsx` (5) — 13-col header order, factor/trend cells, pagination, legend.
- `npm run lint` / `test:ci` (45) / `build` green; springdoc recaptured + `contracts/gen` regenned.

## Live oipulse side-by-side QA gate (§20.8)

Open the owner's logged-in oipulse Connecting Dots (`/app/connecting-dots`) and compare vs
`docs/oipulse-study/features/connecting-dots.md`: 13 columns + order, the 3-state ↑/↓/↔ colour
semantics, the 5-state Trend badge, the extreme-row maroon tint, pagination (25/page), and the 5-pill
legend.

## QA results — 2026-06-21 (vs the study doc; mock render verified)

Rendered on the mock stack (market-data recreated mock-only; gateway/session stayed live), NIFTY 50 ·
3-min · History 2026-06-15 → **125 rows** (the doc's "~125 rows/session at 3-min"). Confirmed matching
the study doc: the **13 columns in exact order**; **newest interval first**; **"HH:MM-HH:MM"** labels;
the **3-state factor glyphs** (↑ green / ↓ red / ↔ blue); the **5-state Trend** badges; the
**extreme-trend maroon row tint** (Ext.Bullish/Ext.Bearish rows); the **5-pill legend**; the
**index-only** Name select (NIFTY 50 / NIFTY BANK); the **3/5/10/15/30/60** interval set (no 1m, incl
10m). A fresh live side-by-side re-capture is pending the owner's browser (the study doc is itself the
authoritative live capture, 2026-06-18).

## Documented divergences (surface to owner)

- **Dow Jones = Neutral always** — the Upstox global feed is not integrated; the study confirms
  `inDow=0` during Indian hours, so Neutral is faithful intraday. Wiring real Dow (via Upstox global)
  is a separate task (kite-vs-upstox routing, funded not built).
- **Active Strike IV / OI = Neutral when no option snapshots** (e.g. fresh mock / off-hours) — they
  populate from live capture; graceful Neutral otherwise.
- **Composite cutoffs are an approximation** — oipulse's exact per-factor raw→enum cutoffs + the
  composite WEIGHTS are server-side. Ours uses the study's empirically-fitted net→trend mapping
  (net ≥ 8 → Ext.Bullish, 2..7 → Bullish, −4..1 → Bearish, ≤ −5 → Ext.Bearish) and reasonable
  per-factor bands (RSI 45/55, VIX/IV direction, volume-confirms-price). Same intended-divergence class
  as black76 greeks vs oipulse's server greeks. Per-factor cutoffs can be tuned later against live data.
- **FINNIFTY / MIDCPNIFTY** indices pending instrument coverage (oipulse offers 4; we ship NIFTY 50 +
  NIFTY BANK today).
- **Sub-tabs** (`Connecting Dots | Tool`) — the "Tool" builder sub-tab is a separate later page.
- The `+` prefix / ring-not-fill a11y conventions and our `--ay-*` theming are the standing intended
  divergences (see the chain QA doc).
