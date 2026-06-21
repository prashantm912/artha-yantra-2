# Phase 4 / Wave 1 — Options OI Spurt (manual test + live-QA gate)

Page: React `OptionsSpurtPage` → `/options/oi-spurt`. Feed: `GET /api/v1/market/options/spurt`
(enriched with `prevLtp` + `ltpChange` + `volume`). Authority: §20.3 + the live oipulse page; spec
doc `docs/oipulse-study/options/oi-spurt.md`.

## How to run
Same as the chain (`phase-4-wave1-options-chain.md`): `./ay.ps1 up` (mock), seed ≥2 snapshot buckets
(`POST /options/snapshot` twice a few minutes apart), `cd frontend-react && npm run dev`, log in, open
OI Spurt.

## Automated coverage (green)
- `SpurtQuadrant.spec.tsx` — |ΔOI| sort, Old OI = New OI − ΔOI, pagination (7/page), strength gate.
- `OptionsAnalyticsControllerIntegrationTest.spurtReturnsRowsAndSummary` — asserts `prevLtp` + `ltpChange`.
- `npm run lint` / `test:ci` / `build` green.

## Live QA results — 2026-06-21 (Claude-in-Chrome vs live oipulse OI Spurt)
**Confirmed matching:** the 2×2 quadrant layout + names (Long Build Up / Short Build Up / Short
Unwinding / Long Unwinding); the 4-state bucketing; Search + Go controls; 7-rows pagination.

**Fixed after QA:** the per-quadrant table is **10 columns** in the live page —
`Strike · Type · LTP · Prev. Close · % Chng. LTP · % Chng. OI · New OI · Old OI · OI Chng. · Volume`.
My first build over-followed the study doc (added an absolute **LTP Chg** column + ordered `%OI` after
Old OI). Dropped LTP Chg; reordered to match. (The BE `ltpChange` field stays — harmless, unused by the
table.)

## Remaining divergences / gaps
- **Underlying header**: oipulse shows `Underlying: <name> | LTP | DH | DL`. `/spurt` carries no underlying
  quote OHLC, so we show an **OI-bias summary badge + spot Δ** instead (substitute) — same underlying-OHLC
  gap as the chain header (needs a quote endpoint).
- **Interval selector**: oipulse OI Spurt has none (uses a fixed interval); our shared `FilterBar` always
  renders it — minor (lets the user pick the spurt interval).
- **Rows-per-page** selector: oipulse offers a configurable dropdown (default 7); ours is fixed 7.
- **Search debounce**: oipulse debounces 300ms (for socket re-subscribe); ours filters the client array
  immediately (no socket) — functionally equivalent, no debounce needed.
- **Expiry column**: the study doc listed it; the live page does not show it as a column (it's a filter).
