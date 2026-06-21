# Phase 4 / Wave 1 — Straddle / Strangle Chart (manual test + live-QA gate)

Page: React `OptionsStraddlePage` → `/options/straddle-chart` (`frontend-react/`). Backend feed:
`GET /api/v1/market/options/straddle-chart` (combined CE+PE premium candles). Plan authority:
§20.7.7 (build notes) + §20.8 (the standing live-oipulse QA gate). Study oracle:
`docs/oipulse-study/strategies/straddle-chart.md`.

## What it is

The combined ATM-straddle (CE + PE) **premium candlestick** over a session, with VWAP, 20 EMA, and the
individual Call/Put price lines — for trading premium decay/expansion. A **Strangle** toggle splits the
single strike into a Call-strike + Put-strike pair.

- **No new capture pipeline:** each leg's intraday OHLC is read cache-first at `1m` via the generic
  `CandleQueryService` and aggregated to the requested interval; the two legs are summed leg-wise
  (`O=CE.open+PE.open … C=CE.close+PE.close`). The faithful invariant is `close == ceClose + peClose`.
- **Interval = raw minutes** 1/3/5/10/15/30/60 (the page offers the shared `OiInterval` subset incl 1m;
  **10-min is the one documented gap** — `OiInterval` lacks it).

## How to run (dev, against the mock stack)

`frontend-react/` is NOT gateway-wired until the cutover, so run the Vite dev server against a running
mock stack:

1. Bring up the mock stack: `./ay.ps1 up` (mock profile) — gateway on 8080.
2. `cd frontend-react && npm run dev` → http://127.0.0.1:4300 (proxies `/api` → 8080). Log in with the
   owner password; navigate to **Options → Straddle Chart**.
3. The mock historical gateway fabricates deterministic 1m option bars on a trading day, so the chart
   renders combined-premium candles even in mock (use a covered/trading session date in History mode).

## Automated coverage (already green)

- `straddleSeries.spec.ts` — candle `[open,close,low,high]` mapping, HH:MM labels, cumulative VWAP,
  20-EMA seed, zero-volume flat-carry, day-H/L extrema + indices, empty-series nulls.
- Backend: `OptionsStraddleChartIntegrationTest` (5) — faithful sum invariant `close==ceClose+peClose`,
  strangle dual-strike resolution, REST decimal-string envelope, 422 unlisted strike, 400 off-set
  interval.
- `npm run lint` / `test:ci` / `build` green; ECharts lazy-chunked (`OptionsStraddlePage-*.js`, ~1 MB
  gzip ~346 KB — main bundle stays ~362 KB / gzip 112 KB).

## Live oipulse side-by-side QA gate (§20.8 — MANDATORY before "done")

Open the owner's logged-in oipulse Straddle Chart in Chrome (Claude-in-Chrome). **Load real data first**
(select Name + Expiry + Strike + Go, wait ~6 s) before any cell-level analysis. Compare vs
`docs/oipulse-study/strategies/straddle-chart.md`:

- [ ] **Filter bar:** Mode · Name · Date · Expiry · **Time Interval** (incl 1-min) · **Strike Price** · Go.
- [ ] **Underlying header:** index name · datetime · LTP · DO.
- [ ] **Chart:** straddle (CE+PE) premium **candlesticks**; **VWAP** (blue) line; **20 EMA** (yellow) line;
      **Call Price** + **Put Price** lines; **day H/L markers** (green ▲ / red ▼); **dataZoom** slider;
      legend (Straddle · VWAP · 20 EMA · Call · Put); toolbox (save/zoom/restore); watermark.
- [ ] **Last Updated At** near the title.

## Live QA results — PENDING

Not yet run. Build is faithful to the study doc; the live side-by-side is the remaining acceptance gate.

## Documented divergences (surface to owner)

- **Interval 10-min** omitted — the shared `OiInterval` lacks it (same gap as OI Analysis). The other
  oipulse intervals (1/3/5/15/30/60) are present. Needs an `OiInterval` extension (deferred, batched
  with the chain's interval-set extension in PR-W3).
- **Strategies sub-tab** not built (oipulse's `Straddle Chart | Strategies` tabs) — the payoff/strategy
  builder is a separate later page.
- **Call/Put lines** drawn **dashed** in `bull`/`bear` tones (theme-aware) to stay distinct from the
  candle bodies — a deliberate visual choice; oipulse uses solid coloured lines.
- **VWAP** uses the straddle **close** as the per-interval price (oipulse doc: "straddle_price × volume";
  `straddle_price` is unspecified — close is the faithful representative).
- **Combined volume** = CE volume + PE volume per interval (drives VWAP). Mock volumes are synthetic.
- **Greeks/IV not shown here** (this page is premium-only, matching oipulse).
