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

## Live QA results — 2026-06-21 (Claude-in-Chrome vs live oipulse OI Spurt, SENSEX populated grids)

**Confirmed matching (on real data):** the 2×2 quadrant layout + names; the 4-state bucketing (PE strikes
fill Long Build-Up, CE fill Short Build-Up, etc.); `% Chng. LTP` / `% Chng. OI` / `OI Chng.` all
green(+)/red(−) toned; Indian lakh number grouping; **sort by |ΔOI| desc**; **7-rows pagination**
("1–7 of 101"); Search + Go.

**Fixed after QA:**
- **10-column** table to match the live page — `Strike · Type · LTP · Prev. Close · % Chng. LTP ·
  % Chng. OI · New OI · Old OI · OI Chng. · Volume`. My first build over-followed the study doc (added an
  absolute **LTP Chg** column + ordered `%OI` after Old OI). Dropped LTP Chg; reordered. (BE `ltpChange`
  field stays — harmless.)
- **Removed the strength-bold** — I had bolded rows where %ΔLTP>50 AND %ΔOI>50, but the live page renders
  all rows uniform weight. Deleted the visual marker + the helper (the strength filter is a documented
  oipulse *concept* but not a live visual; could return later as an opt-in "strong-only" filter).

## Remaining divergences / gaps
- **Underlying header**: oipulse shows `Underlying: SENSEX at 76802.9, Chg −607.08 (−0.78%) as on …`.
  `/spurt` carries no underlying quote spot/chg, so we show an **OI-bias badge + spot Δ** substitute —
  the underlying-quote gap (shared with the chain header; needs a quote endpoint).
- **`+` prefix** on positives: oipulse shows `93.03 %` (colour only); ours adds `+` (a11y — sign not
  colour-only). Intentional, consistent with the chain.
- **Zero-change strikes**: oipulse buckets a 0%/0-ΔOI strike into Long Unwinding (treats 0 as the
  fall/fall side); our `OiInterpretation.classify` treats 0 as the up side → Long Build-Up. Boundary-only
  edge case (illiquid no-trade strikes); the backend classify convention is shared + frozen, so not changed.
- **Interval selector**: oipulse OI Spurt has none; our shared `FilterBar` always renders it (minor).
- **Rows-per-page** selector: oipulse offers a configurable dropdown (7/10/15/30/50/All, default 7); ours
  is fixed 7.
- **Search debounce**: oipulse debounces 300ms (socket re-subscribe); ours filters the client array
  immediately (no socket) — functionally equivalent.
- **Expiry column**: study doc listed it; the live page does not show it (it's a filter).

## Value-verify pass — 2026-07-01 (live-vs-live, SENSEX) — DRIVEN
Driven live vs oipulse's OI Spurt. **Structure ✓** (2×2 quadrants, 10 cols, |ΔOI|-desc sort, 7/page,
green/red tones). **Absolute `New OI` matches** (77000 CE 2,876,560 — cross-checked against the live
chain-table at 13:24). **F6 — ✅ FIXED (#399):** oipulse OI Spurt classifies by **day-cumulative** ΔOI
(`Old OI` = day-open OI; **no interval selector**); ours used **per-interval** ΔOI, so a strike's quadrant
could flip (SENSEX 77000 CE: oipulse LONG_BUILDUP since open vs our LONG_UNWINDING in the last 5m). Added a
`window=cumulative|interval` param to `/spurt`; this page now sends `window=cumulative` and **hides the
interval selector** (`showInterval={false}`), Big OI keeps the per-interval default. Live-verified: SENSEX
77000 CE cumulative `oiChange` 229,700 (since open) vs interval 0 (flat last bucket). The zero-change strike
bucketing convention divergence above is unchanged. Full results: `phase-4-wave1-value-verify-runbook.md`.
