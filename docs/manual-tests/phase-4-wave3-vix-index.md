# Phase 4 · Wave 3 — Vix & Index (manual test)

The oipulse **Vix & Index** ("Vix & Price Chart", `docs/oipulse-study/features/vix-index.md`), React route
`/features/vix-index`, mega-menu **Features → Vix & Index**. Two stacked dual-axis line charts that read the
(usually inverse) India-VIX↔index-price relationship intraday.

## What was built
- **ZERO backend.** Three reuse GETs to the existing cache-first `/api/v1/market/candles` (NSE **INDIA VIX**,
  **NIFTY 50**, **NIFTY BANK**, `interval=1m`, the selected IST day's `from`/`to`), a FE union-by-minute fold
  (`core/vixIndexSeries.foldVixIndex`), and one reusable `VixIndexChart` rendered twice. No new endpoint, no
  contract change.
- **Chart**: VIX on the LEFT axis (blue/accent, tight `scale:true` range) + index price on the RIGHT axis
  (orange/warn) — independent auto-ranged axes (no co-scaling). Bottom legend, save-PNG + line/bar toolbox,
  "Oi Pulse" watermark, `connectNulls` across union-gap minutes.

## Preconditions
- Stack up; sign in. The three indices are pinned QUOTE subscriptions → dense 1m bars accrue from live
  capture; a day **before** capture started (or a day the stack was down) returns empty.

## Steps
1. Open **Features → Vix & Index**.
2. The date defaults to **today (IST)**. Press **Go** (or pick another captured trading day).
3. Verify **two** stacked charts: **India Vix Vs. Nifty** then **India Vix Vs. Banknifty**.
4. Each chart: blue **Vix** line on the left axis, orange **Price** line on the right axis; hover shows
   `HH:mm · Vix · <index>`. The two y-axes scale independently (VIX in the ~13–15 band, price in thousands).
5. Toolbox (top-right): line/bar toggle, zoom-select, restore, **download PNG**. Bottom legend toggles each
   series. "Data updated at" reflects the last fetch.

## Faithful divergences (documented)
- **No Live socket / auto-refresh** — a REST snapshot for the selected day (Go re-fetches), consistent with
  every other React page. oipulse's `EQ_VPD_{NAME}` websocket auto-extend + the Live/Historical radio collapse
  to the single date-snapshot model.
- **Historical only for forward-captured days** — index/VIX 1m bars accrue from live tick capture; there is no
  1m index historical backfill, so history is shallow until it accrues. Empty days show the explainer copy.
- **Per-minute value = the 1m bar `close`** (the faithful captured LTP equivalent), not a separate LTP tick
  stream. Indices carry `volume 0` / `oi null` — irrelevant (only close is plotted).
- Partly-captured day still renders — the empty-state copy shows only when **both** charts are empty.

## Verify (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
No backend / contract change — `vixIndexSeries.spec.ts` covers the fold (aligned / gap / misaligned-union /
empty / decimal→number).
