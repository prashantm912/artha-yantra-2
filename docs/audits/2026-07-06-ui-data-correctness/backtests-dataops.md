# UI data-correctness audit — Backtests / Optimizer / Data-Ops cluster

Date: 2026-07-06 · Scope: read-only verification of displayed numbers vs. DB truth.
Method: `.tsx` → `api/*.ts` → in-container endpoint (`docker exec ay-<svc> wget`/`urllib`) → DB (`ay-timescaledb psql -U artha -d artha`).

Ports discovered: backtest-service **8083**, market-data-service **8081**, optimizer-service **8084** (FastAPI/uvicorn, no wget/curl → used `python3 urllib`).

Overall verdict: **PASS — every headline metric, curve, trade count, coverage number, query and export reconciles to the DB.** Three non-blocking items noted (all backend-side, not UI defects): negative-equity CAGR renders `0`, a downsampled drawdown-curve max sits slightly under the exact metric, and the collection-status log feed shows UTF-8 mojibake.

---

## BacktestResultsPage (`/backtests/:id`)

Endpoint slice (`api/backtests.ts`): `/backtests/{runId}/results|trades|folds|montecarlo|oi-attribution`. `id` = the **run id** (`backtest_runs.id`, the resultRef), NOT the jobId — confirmed correct.

Probe run: `22fa8d47-5d54-45f0-8590-b65d2c963ff8` (947 trades, NIFTY-FUT-CONT/3m, CANDLE_1M).

Metric-panel reconciliation (endpoint `/results` vs `backtest_runs` vs `backtest_trades` aggregate):

| Metric | UI/endpoint | DB truth | ✓ |
|---|---|---|---|
| totalReturn | -219.606175% | final−initial = −239212.35−200000 = −439212.35; /200000 = −219.606% | ✓ |
| trade_count | 947 | `count(*) backtest_trades` = 947 | ✓ |
| win_rate | 35.480465% | 336 wins / 947 = 35.4805% | ✓ |
| profit_factor | 0.607863 | gross_win 680834.96 / |gross_loss| 1120047.31 = 0.607863 | ✓ |
| expectancy / avgTrade | -463.79 | sum_pnl −439212.35 / 947 = −463.79 | ✓ |
| sum(pnl) | (trades table) −439212.35 | trades sum = −439212.35 = final−initial | ✓ |

- **Trades tab**: `/trades?limit=1000` returns all 947, sum pnl −439212.35, 336 wins — matches DB. `side=BUY` for all (option legs) — confirms the FE's LONG||BUY bull-tone fix (line 378 comment). Exit reasons: 596 SIGNAL_EXIT / 351 TIME_STOP.
- **Equity/drawdown/benchmark curves**: 495 points each. Equity **last value = -239212.35 = `final_equity`** — curve is consistent with the terminal metric. Benchmark present (191786.25), matches `benchmarkCoverage: present`. Negative equity is a legitimate unbounded-short CANDLE_1M artifact (per CLAUDE.md), not a UI bug.
- **Folds**: `[]` (no walk-forward) → tab hidden. Correct.
- **Monte Carlo**: `trades: 947`, `insufficientSample: false`, equityBands present. Trade count matches.
- **OI Attribution** (`?interval=5m`): `underlying=SENSEX`, tradeCount 947, tradesAttributed 927 + tradesNoData 20 = 947, bucket count sum = 947, `oiDerived: true` (derived history, Dow NEUTRAL — matches muted-OI doctrine). Bucket wins/count ≈ winRate internally consistent. ✓
- Note: run's `tradingsymbol=NIFTY-FUT-CONT` but OI `underlying=SENSEX` — the scalper 3-way decoupling (signal on NIFTY future, options on SENSEX), by design.

### ⚠ Non-blocking (backend metric edge cases)
1. **Negative-equity CAGR = `"0"`**: window is 112 days (≥90 so CAGR is shown, not suppressed), but the engine emits `cagr: "0"` because a portfolio that ends at negative equity can't be annualized (`(final/initial)^(1/yr)` undefined for final<0). FE prints `0.00%` CAGR next to a −219% total return — misleading, but the FE faithfully renders what the engine computes. Backend concern.
2. **Drawdown-curve max (235.62) < metric maxDrawdown (238.196645)**: the drawdown curve is downsampled to 495 points, so its visible peak sits just below the exact metric (computed on the full path). Metric is correct; the chart peak is a downsample artifact. Cosmetic.

The `<90d` CAGR suppression (`n/a (<90d)`) is intended (audit 2026-07-02 §5) and confirmed present in `metric()`.

---

## JobsPage (`/backtests/jobs`)

Endpoint: `/backtests/jobs?limit=25&offset=…&sortBy&sortDir` returns `{items:[JobDto]}`. Results link resolves `resultRef` lazily via `/backtests/jobs/{jobId}` (list omits it — confirmed by design, line 182 of api).

- List top row `ccd5eac3` = newest `jobs.created_at` row in DB. Order correct.
- `totalReturn` on the list row (−12.900730) = the run's `backtest_runs.total_return` for that job. ✓
- Job-detail `resultRef` = `4af249c8…` = that run's `id` (the value the FE navigates to). ✓
- DB job census (kind×status): BACKTEST completed 97 / cancelled 2 / failed 3; OPTIMIZATION completed 7 / failed 1; TRIAL completed 162. List statuses/kinds render straight from these.
- Minor: the job **detail** endpoint returns `totalReturn:null`/`testFrom:null` while the **list** populates them; harmless because the FE reads detail only for `resultRef`.

---

## BacktestComparePage (`/backtests/compare?ids=…`)

Reuses `/backtests/{id}/results` per run (`useCompareResults`, capped 6). Metric matrix, best-per-row highlight, dataHash/universe mismatch banners, and equity curves rebased to 100 all read from the same verified `results` payload — no independent computation to drift. `metricValue` is a straight `Number(metrics[key])`. No separate DB surface; inherits the ResultsPage reconciliation. PASS by construction.

---

## SweepDetailPage / Optimizer (`/optimizations/:sweepId`)

Optimizer is FastAPI (8084), routes `/api/v1/optimizations/{id}/best|trials|trials/{n}/folds|promote`.

Probe sweep: `c1713943-a453-485e-9ed5-1808b1458eba` (60 trials).

- `/trials`: 60 items, all COMPLETE = DB `optimization_trials` (60 COMPLETE / 0 pruned / 0 failed, all with backtest_run_id). ✓
- `/best?sort=raw`: metric=sharpe, 50 rows (top=50 cap). Best trial #42 objective **35.426203** = DB `objective_values->>'sharpe'` AND the linked `backtest_runs.id=e49d3712…` `sharpe=35.426203`. ✓ Trial #0 objective 10.817084 = its run's sharpe. ✓
- Top-5 objectives match DB ordering; plateau vs raw both offered (plateau default). `guardMetrics:null` on these full-window trials → "no fold guards" badge (correct).
- DB per-sweep trial counts also sane: sweep `0a414f47` = 40 trials all FAILED (0 runs) — the "flagged not hidden" path.

---

## Data-Ops — CoveragePage (`/data-ops/coverage`)

Endpoint `/market/admin/coverage-summary` (candleRows = Σ bar_count).

| Underlying/Exch | contracts | complete | partial | minExp | maxExp | candleRows | DB match |
|---|---|---|---|---|---|---|---|
| NIFTY / NFO | 13709 | 13709 | 0 | 2025-04-03 | 2026-06-30 | 91,578,584 | ✓ exact |
| SENSEX / BFO | 27339 | 27339 | 0 | 2025-04-01 | 2026-07-02 | 52,064,054 | ✓ exact |
| **Totals** | 41,048 | 41,048 | 0 | — | — | 143,642,638 | ✓ |

Every column byte-matches `SELECT … FROM marketdata.expired_contracts GROUP BY underlying_symbol,exchange`. Verified `bar_count` is trustworthy: two top contracts' stored bar_count (29242, 29239) equal actual `candles` row counts (29242, 29239), all interval=1m. Grand-total pills sum correctly. **PASS.**

---

## Data-Ops — StatusPage (`/data-ops/status`)

- **expired-backfill/status**: `state=OK`, last run 2026-07-05 22:31, legsWritten 0 / legsSkipped 32294 (idempotent resume — all present), legsFailed 0. `contracts:32294`/`expiries:104` are the **last run's date-band** counts (job walks a recent window), not full-table coverage (41048/132) — internally consistent, not a coverage figure.
- **oi-backfill/status**: `NEVER_RUN` (OI is live-ticker capture, not this batch) — correct.
- **upstox-quota-status**: `configured=true`, windows 1s/1m/30m with live used/max/remaining — correct shape.
- **backfill-jobs?limit=50**: 24 rows all COMPLETED = DB `marketdata.backfill_jobs` (24/24/0/0). Latest id=24 timings match (`2026-07-05 22:31:19.82Z` ↔ endpoint `04:01:19.82+05:30`). ✓

### ⚠ Non-blocking (cosmetic)
3. **Log-feed mojibake**: `recentLogs` render `SENSEX 2025-07-22 â†' 371 contracts` — the `→` arrow is UTF-8 double-decoded (`â†'`). Garbled in the LogFeed component. Encoding/display bug, no data impact.

---

## Data-Ops — QueryConsolePage (`/data-ops/query`)

`POST /market/admin/query` (read-only allowlist). Ran the "Contracts by expiry" preset → result grid byte-matches the same SQL run directly in psql (SENSEX 2026-07-02 CE=154/PE=154, NIFTY 2026-06-30 PE=137/FUT=1/CE=136). `columns`/`rows`/`rowCount` shape correct, nulls preserved. **PASS.**

---

## Data-Ops — ExportWizardPage (`/data-ops/export`) + CollectionWizard

- `/market/admin/export/expiries?underlying=NIFTY` → 66 = DB `count(distinct expiry)` for NIFTY. ✓
- `/market/admin/export/contracts?underlying=NIFTY&expiry=2026-06-30` → 274 = DB (137 PE + 136 CE + 1 FUT). ✓
- `POST /market/admin/export` (one contract, CSV) → 7552 lines = 1 header + **7551 rows = DB candle count** for NIFTY30JUN2619000CE in [2026-06-01,2026-07-01). Columns openalgo_symbol/date/time/timestamp/o/h/l/c/volume/oi with real values. ✓
- ExportWizard/CollectionWizard consume exactly these verified endpoints (+ bulk ZIP, download-only). **PASS.**

---

## Summary of flagged items (all non-blocking, all backend/cosmetic)

| # | Page | Item | Severity |
|---|---|---|---|
| 1 | BacktestResults | Negative-equity run reports `cagr:"0"` (annualization undefined for final<0); FE shows 0.00% beside −219% return | Low — backend metric |
| 2 | BacktestResults | Downsampled drawdown-curve visible max (235.62) < exact metric (238.20) | Cosmetic — chart downsample |
| 3 | Status | `recentLogs` UTF-8 mojibake (`â†'` for `→`) in LogFeed | Cosmetic — encoding |

No wrong metrics, no mismatched trade counts, no wrong coverage counts, no curve inconsistent with its trades were found.
