# Receipt: d4-export-fe-buttons

Date: 2026-07-13
Branch: `feat/d4-export-fe-buttons`
Tier: clean (FE-only, additive)
PR: **PENDING — populated immediately after `gh pr create`**

## Diff summary

- `frontend-react/src/api/backtestExport.ts` — new, 15 lines: typed CSV/JSON export functions for trades, folds, and equity.
- `frontend-react/src/api/backtestExport.spec.ts` — new, 31 lines: exact download-path coverage.
- `frontend-react/src/pages/backtests/BacktestResultsPage.tsx` — +47/−0: compact accessible CSV/JSON controls beside the equity chart and above the trades/folds tables.
- `frontend-react/src/pages/backtests/BacktestResultsPage.spec.tsx` — +36/−1: interaction coverage for all six results-page buttons.
- `docs/handoffs/2026-07-13-d4-export-fe-buttons-receipt.md` — this receipt (the brief-mandated audit artifact and sole non-frontend change).
- Compare-page code was not changed for the stop-condition reason recorded under **Open doubts**.

## Verification output

Dependencies were absent, so setup used `npm.cmd ci` from `frontend-react` after PowerShell policy blocked the `npm.ps1` shim:

```text
added 526 packages, and audited 527 packages in 17s
3 moderate severity vulnerabilities
```

TDD red probe, before implementation:

```text
Test Files  2 failed (2)
Tests       1 failed | 5 passed (6)
Error: Failed to resolve import "./backtestExport.ts"
Unable to find ... name "Download equity as CSV"
```

Focused green probe after implementation:

```text
Test Files  2 passed (2)
Tests       7 passed (7)
Duration    2.84s
```

Verify trio, run in the required order from `frontend-react`:

1. `npm.cmd run lint` — **PASS**, exit 0.

```text
> frontend-react@0.0.0 lint
> eslint src
```

2. `npm.cmd run test:ci` — **PASS**, exit 0.

```text
Test Files  107 passed (107)
Tests       348 passed (348)
Duration    49.58s
Statements : 53.41% (1048/1962)
Branches   : 52.64% (676/1284)
Functions  : 36.83% (263/714)
Lines      : 53.28% (909/1706)
```

3. `npm.cmd run build` — **PASS**, exit 0.

```text
> tsc -b && vite build
✓ 3174 modules transformed.
✓ built in 9.88s
```

Vite also emitted dynamic/static-import chunking warnings for pre-existing session, options, and multiple-window modules; none named the export module or the backtest results page, and the production build exited 0.

## Claims and evidence

- **computed** — Trades, folds, and equity functions delegate to the existing `downloadFile` helper with the exact live endpoint and requested `format`: `frontend-react/src/api/backtestExport.ts:5-14`; exact strings are asserted at `frontend-react/src/api/backtestExport.spec.ts:13-29`.
- **sourced** — The reused download choke point still invokes the truncation toast contract before creating the blob URL: `frontend-react/src/api/client.ts:144-160`.
- **computed** — One compact `ExportControls` pair uses the existing themed Button atom and produces real buttons with distinct screen-reader names: `frontend-react/src/pages/backtests/BacktestResultsPage.tsx:95-118`.
- **computed** — Equity controls sit inside the equity chart card, while trades and folds controls sit in their tab-local heading rows immediately above the data surfaces: `frontend-react/src/pages/backtests/BacktestResultsPage.tsx:316-321,338-342,472-476`.
- **computed** — The interaction spec clicks all six buttons and proves each selected format reaches the relevant export function with run id `run-1`: `frontend-react/src/pages/backtests/BacktestResultsPage.spec.tsx:139-157`.
- **sourced** — Compare was scoped out under the brief's explicit stop condition: the page selection contains run ids (`frontend-react/src/pages/backtests/BacktestComparePage.tsx:68-72`), its fetched `BacktestResults` shape has `strategyId` but no `strategyVersionId` (`frontend-react/src/api/backtests.ts:212-231`), while the live compare endpoint requires both path `runId` and query `strategyVersionIds` (`services/backtest-service/src/main/java/in/arthayantra/backtest/replay/ExportController.java:143-153`).
- **assumed** — No extra client-side loading/error state was added because the brief required delegation to the existing download helper and the established sibling export controls also fire it directly; this assumption does not alter URL construction, truncation handling, or accessibility.

## Open doubts

1. **Compare export — scoped out.** `BacktestComparePage` cleanly exposes only selected run ids. The result objects it already fetches do not expose strategy-version ids, but `/export/compare` needs `strategyVersionIds` and an anchor run id. Inventing a version-selection/fetch mechanism was explicitly forbidden. Consequently no compare button and no dead/mis-specified `exportCompare` helper were added; the module has three callable functions rather than the brief's planned four. The backend or existing results payload must expose the selected runs' `strategyVersionId` values before this can be wired correctly.
2. **Control placement and idiom.** Equity export is in the existing chart `BeatBlock`; trades and folds export sit in compact tab-local header rows immediately above their tables. All reuse `components/atoms/Button.tsx` with `outline`/`sm`, so styling stays on `--ay-*` tokens and matches current page controls.
3. **Accessibility.** No known concern in the implemented controls: each is a keyboard-reachable real `<button>`, each pair is a named group, and each button exposes the full unique accessible name `Download <artifact> as <format>` while retaining short visible CSV/JSON text. The interaction spec queries those role/name pairs directly.
4. **Dependency audit.** `npm ci` reported three moderate advisories in the locked dependency graph. No lockfile change or `npm audit fix` was attempted because dependency upgrades are outside this FE-button brief.
