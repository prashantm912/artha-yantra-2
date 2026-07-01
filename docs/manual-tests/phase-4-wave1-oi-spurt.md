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

## Value-verify pass — 2026-07-01 (live-vs-live)
OI Spurt renders the same per-strike OI + LTP + 4-state interpretation that the **OI Analysis** pass
matched to oipulse **exactly** (see that page's doc + `phase-4-wave1-value-verify-runbook.md`), just in
the 2×2 quadrant layout — so it inherits the exact-OI data-fidelity proof. It was **not** driven
cell-for-cell this pass (structure + 4-state bucketing already confirmed 2026-06-21). The zero-change
strike bucketing convention divergence above is unchanged.
