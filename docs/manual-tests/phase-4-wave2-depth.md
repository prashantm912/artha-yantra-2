# Phase 4 / Wave 2 — Depth pages (manual test + live-QA gate)

PR-W2 (master plan §20.3). Nine oipulse depth pages built faithfully on the shared React component
library (FilterBar / **new generic `DataTable`** / EChart / atoms). Owner decision 2026-06-21:
**"Faithful (small BE adds)"** — surface already-captured fields rather than ship degraded columns.

Authority per page = its `docs/oipulse-study/<area>/<page>.md` + the live oipulse page.

## Pages + feeds

| Page | Route | Feed | Archetype |
|---|---|---|---|
| OI Trending | `/options/trending-oi` | `/options/trending` (+`spot`) | DataTable (FE-fold) |
| Big OI Movement | `/options/big-oi-movement` | `/options/spurt` + `/chain-table` (spot) | dual DataTable (CE/PE) |
| Options Premium | `/options/options-premium` | `/options/premium` | EChart grouped bars |
| Futures OI Spurt | `/futures/oi-spurt` | `/futures/spurt` (+`prevClose`,`pricePct`) | 4× DataTable quadrant |
| Futures Market Movers | `/futures/market-movers` | `/futures/movers` (+`dayOpen/High/Low`) | dual DataTable |
| Futures EOD OI Analyzer | `/futures/eod-oi-analyzer` | `/futures/eod` (FE-fold) | DataTable |
| FII/DII Capital Market | `/fii-dii/capital-market` | `/fii-dii/cash` (FE-pivot) | 2× EChart bar + DataTable |
| Participant-wise OI | `/fii-dii/participant-wise-oi` | `/fii-dii/participant-oi` (FE 2-date diff) | grouped DataTable |
| FII Long-Short Ratio | `/fii-dii/long-short-ratio` | `/fii-dii/long-short` | EChart line |

## BE field-surfacing (data already captured; one springdoc recapture)
- `OiTrendingService.TrendPoint` += `spot` (the LTP column).
- `FuturesSpurtService.FutSpurt` += `prevClose`, `pricePct` (Prev. Close + LTP Chg %).
- `FuturesMoversService.MoverRow` += `dayOpen/dayHigh/dayLow` (the O=H/L flag).
- `BigOi`+spot was added then **reverted** — Big OI consumes `/spurt` (which already carries per-leg
  ΔLTP + interpretation) so the single-bucket `/big-oi` was not needed.

## Automated coverage (green)
- `DataTable.spec.tsx` — sort asc/desc, **decimal-string sort** (compareDecimal), pagination bounds +
  range text, aria-sort, non-sortable columns, custom render/cellClassName, mobile-card labels, empty state.
- Fold specs: `trendingOiFold` · `futuresEodFold` · `fiiDiiCashFold` · `participantOiFold` — Δ maths,
  Diff-in-OI sign, In-Market sum, day-over-day, participant ordering.
- Page smoke: `TrendingOiPage` · `FuturesOiSpurtPage` (mocked hook + QueryClient).
- `npm run lint` / `test:ci` (68 tests) / `build` green · springdoc recaptured + `contracts/gen` regen.

## Documented divergences (intentional / data-bounded)
- **`+` prefix** on positive %/Δ (a11y — sign not colour-only); ring badges not solid fills (WCAG). As Wave 1.
- **Reduced futures universe**: OI Spurt / Movers scan our captured futures (6 index + 17 bank-sector
  stocks), not oipulse's ~320 all-F&O universe — structurally faithful, fewer rows. The all-F&O capture
  is a deferred expansion.
- **Futures OI Buzz DEFERRED**: it is a per-constituent (member-stock) treemap; we don't capture the
  constituent stock-futures universe, so it can't be faithful → deferred (not in W2; "faithful or defer").
- **Futures Movers `Min. B.O. Days`** dropped — needs a multi-day prior-high/low history we don't capture.
- **Trending OI** Δ columns are cumulative vs the **session-open** baseline (oipulse uses the prior-day
  EOD "PEOD" row); "Chng. In Direction" / "Direction of chng. %" use a documented derivation (ΔDiff;
  Diff as % of total directional OI change) since oipulse's exact formula was not captured.
- **FII Long-Short** ships the LSR% line only; the **Nifty-futures candle overlay is deferred** (needs a
  futures-candle instrument feed — a Wave-4 chart concern).
- **Futures EOD** history is **forward-only / shallow** (accrues from capture boot) vs oipulse's ~400
  days; chart / cumulative-OI / range-data toggles deferred.
- **Participant-wise OI** change columns come from an FE latest-vs-prior-captured-date diff (no
  trading-calendar logic).
- **Mini-chart launcher column** (the oipulse "Chart" icon on Futures Spurt/Movers rows) is omitted —
  it opens an intraday chart page we have not built yet (Wave-4 charts); shipping a dead icon would be a
  false affordance.
- **Futures Movers "New High/Low Maker"** live panel is deferred — it needs a live new-high/low event
  feed (oipulse's `socketSeperateSubscribedEvents`) we do not capture; and **"Min. B.O. Days"** + the
  per-card **Filter** link are deferred (multi-day breakout history / column-filter dialog).

## Fidelity review (2026-06-21, 9 adversarial reviewers vs the study docs)
Verdicts: **5 faithful** (Options Premium, Futures OI Spurt, FII Capital Market, FII Long-Short, + after
fixes), 4 had gaps. Fixed: the Participant interpretation rule (now net-position AND matching-leg
direction must agree, per doc line 91 — not the `chngTotal` sign alone); column labels aligned to the
confirmed-live oipulse wording (`Chng. In Call/Put OI`, `Direction of chng.`, `Chng. In Direction`,
`Direction of chng. %`, `Strike Price`, `Close Price`, `% Chng. in LTP/OI`, `Chng. In Long/Short`).
Deferred items above were re-confirmed as out-of-scope (no chart page / no live H/L feed / no all-F&O
capture).

## Live-QA gate — PENDING (blocked on the data-foundation milestone)
Like Wave 1, these data pages are **built + structure-QA'd against the study docs + mock fixtures but
NOT value-verified** on a real session. Value-verification (Claude-in-Chrome side-by-side vs live
oipulse, §20.8) is **blocked on the data-foundation milestone** (Upstox-backed OpenAlgo + the
OI-backfill importer) — see `phase4-react-oipulse-plan` memory + `2026-06-21-data-foundation-milestone.md`.
Run the per-page live side-by-side once a real historical session is backfilled.
