# Minute-by-minute market research & strategy discovery system — gap analysis + roadmap (2026-07-12)

**STATUS: DORMANT — NOT IN THE BUILD QUEUE.**
This document is deliberately excluded from `docs/superpowers/plans/2026-07-02-remaining-items.md` §0.
No autonomous run, delegated builder, or queue sweep may pick items from this file. It exists for an
owner review scheduled AFTER the current queue (D3/D4 remainder, D2 residue, open chips) is built out.
When the owner activates it, its items enter the ledger through the normal §0 process with fresh IDs.

**Verdict first:** no rewrite, and mostly no new system — **10 of the 14 requested features already
exist at ≥80% depth** in this platform, several at 100%. The honest deliverable is three buildable
deltas (a unified per-minute research matrix, a generic hypothesis-testing lane, a monthly research
rollup) plus a short list of configuration seams for future asset classes. Everything rides the
existing services, schemas, and doctrine; nothing touches a parity surface.

Evidence basis: repo inspected 2026-07-12 at main `435e976e` (108 migrations: marketdata V046 /
strategy V040 / backtest V017 heads — matches the ledger's wave-2-abandoned note), plus the five
standing audits (2026-07-05 full, 2026-07-10 research-fidelity FID + app-platform APP) and the two
frozen program designs (EVO evolution engine, INT intelligence layer). Claims below are labeled
sourced (file:line / migration read now) or recalled (platform memory, verified where load-bearing).

---

## 1. Current system assessment

Multi-module Maven platform + Python optimizer, Dockerized, loopback-only gateway. Six services
(sourced: `services/`): `market-data-service` (capture, EOD ingest, chain snapshots, screeners),
`strategy-signal-service` (live engine, paper books, risk governors, insights), `backtest-service`
(deterministic replay, golden/parity, counterfactuals, EVO campaigns), `optimizer-service`
(FastAPI sweeps, walk-forward, E5 ablations), `edge-gateway`, `margin-service` (dormant SPAN
appliance). Five libs: `strategy-engine` (indicator bank + evaluators, shared by live and backtest),
`strategy-schema`, `black76-math`, `market-calendar`, `common-web`.

Four Flyway lineages (admin / marketdata / strategy / backtest), TimescaleDB hypertables +
continuous aggregates, single-writer-per-schema convention (D10). React 19 frontend, 77+ pages.
The platform is **already a research-first system with an optional live lane** — precisely the
target shape the request describes. It runs live paper trading on 45 published strategies, captures
the full NIFTY+SENSEX option chain intraday, persists every gate rejection per bar, and has a
built (default-OFF) strategy-evolution engine with ablations, OOS gates, and a graveyard.

### Feature-by-feature map (the core finding)

| # | Requested feature | Status | Existing implementation (evidence) | Delta needed |
|---|---|---|---|---|
| 1 | Minute-by-minute market data capture | **DONE** | 1m tick-agg candles via Kite WS + 1s close sweep (`CandlesConfig:75`, sourced); two-upsert write doctrine (#507); cache-first reads w/ 10-min tail; bhavcopy EOD; `ingest_runs` trust ledger (V040) + 08:45 coverage canary | None |
| 2 | Underlying + derivative state capture | **DONE** | `futures_oi_snapshots` every 1 min OHLC+OI (V011/V015; cron `0 * * * * *` sourced `FuturesOiSnapshotService:73`); dated front-contract resolution; `preopen_equity_snapshots` (V028); `market_context_days` EOD day-context (V043) | None |
| 3 | Option chain snapshots | **DONE** | `options_chain_snapshots` (V006), full NIFTY+SENSEX chain **every 2 min** default (sourced `OptionsSnapshotService:107`, cron `0 */2 * * * *`); quarantine (V045); 365d prune (V046, #749); virtual historical OI via `CandleDerivedChainReader` for pre-capture dates | Cadence decision only (§12 Q1); recommend keep 2-min |
| 4 | OI, volume, IV, VIX, greeks, price, indicators | **DONE** | Black-76 lib incl. third-order greeks (#511); `iv_history` (V016) + `iv_daily_summary` (V009); VIX factor in dots; `IndicatorBank` over 1m/3m/5m/15m/1h/1d/1w (sourced `IndicatorBank:151-157`) | None |
| 5 | Entry-condition state recording every minute | **PARTIAL (strong)** | `signal_rejections` (V015, #404): EVERY gate block persisted per bar with per-rail operands, for every published strategy; `fired_diagnostic` (V035, #763) mirrors the same shape on FIRED bars → fired-vs-rejected is complete for published strategies | R1+R2: conditions NOT attached to a published strategy aren't recorded live — solved offline, not by persisting more live state (§5) |
| 6 | Hypothetical trade labeling + forward returns | **PARTIAL** | Shadow book (V016–V018, #479): rejections traded virtually net-of-cost, live; counterfactual replay job kind (V013, #732, captured-data-only); session-analysis counterfactual W/L | R1b: minute-grid forward-return labels (computed-on-read, §6) |
| 7 | Exit-rule simulation | **DONE** | Frozen `PremiumExitEvaluator` reused by the COUNTERFACTUAL kind (V013); exit-equivalence fixture pins backtest↔live semantics (#505); `sessionOverrides{fillTiming}` (#740); stress `slippageMultiplier` (#727) | Thin wrapper: exit-rule GRID over a hypothesis set (§5 R2c) |
| 8 | Monthly performance analysis | **PARTIAL** | Insights engine + digests (V032/V033, V043; INT I1–I3 complete); EVO campaign reports (V017-bt); experiment views + compare endpoint (V010-bt) | R3: calendar-month rollup + monthly digest (small, clean) |
| 9 | Walk-forward testing | **DONE** | Optimizer `walkForward{train_days,test_days,step_days}` + `oos_fold_mean` objective (request-level; `service.py`, sourced files exist) | None — trap: WF comes from the REQUEST body, not the YAML (CLAUDE.md) |
| 10 | Out-of-sample validation | **DONE** | EVO E5 (#792): pre-registered ablations, paired IS/OOS evaluation, IS-only auto-reject, graveyard (V016-bt); deflated Sharpe + DOF penalties (#726); live-gap gate (#738) | None |
| 11 | Transaction cost + slippage modeling | **DONE** | Cost model in both engines; shadow book carries `cost`/`pnl_net` (F8); slippage stress 2×/4× orchestration (#729); real SPAN margin via Upstox (F9, #510) | Per-asset cost config seam when new classes arrive (§11) |
| 12 | Monthly refinement workflow | **PARTIAL** | EVO E1–E6 fully built (campaigns → probes → stress → proposals inbox → owner 2-click publish → TAKE/PROMOTE/ROLLBACK; autonomy scheduler default-OFF, V017-bt); reconciliation computer (V012-bt) | R3: a monthly research RITUAL (runbook + scheduled digest), mostly process not code |
| 13 | Modular architecture for later live trading | **DONE** | The live lane exists: engine → signals → paper books → risk governors → brackets → reconcilers → notifier. Research candidates promote through EVO's PUBLISH_PAPER path | None |
| 14 | Multi-asset via configuration | **PARTIAL** | Instrument model is exchange-agnostic (canonical `(exchange, tradingsymbol)`, Kite grammar); scalpers already cross NSE index → NFO/BFO options; universe/instrumentRefs fully config-driven | Real gaps sourced: `MarketCalendar` has `nse()`/`bse()` only (`MarketCalendar.java:77,88`); instrument sync covers NSE/NFO/BFO only (`InstrumentSyncScheduler:31`). Seam list in §11; build deferred |

---

## 2. Reusable components (no changes needed)

- **Capture plane** — Kite WS + tick-agg 1m candles, chain/futures/preopen snapshot jobs,
  bhavcopy + NSE EOD ingest, `ingest_runs` + canaries. This IS features 1–4.
- **Deterministic research engine** — golden-vector-pinned replay, tick-wise runner (1m→3m/5m/15m/1h
  rollup), premium replay for options, walk-forward optimizer, E5 ablation/OOS machinery.
- **Evidence stores** — `signal_rejections` + `fired_diagnostic` (per-bar rail operands),
  `shadow_positions` (net-of-cost virtual fills), `reconciliations` (sim↔live gap verdicts),
  `dataset_epochs` + content-hash comparability (V015-bt, #791), engine-SHA stamping (V008-bt).
- **Governance** — evidence-policy routing (SIM_FIRST/LIVE_FIRST/SIM_BLOCKED), proposals inbox with
  owner 2-click publish, family paper cap, graveyard. The request's "research first, algo later"
  policy is already codified here.
- **Anti-overfit doctrine** — deflated Sharpe, DOF penalties, IS-only auto-reject, paired
  evaluation, regime labels, live-gap hard gate. Reuse as-is for anything the new lane produces.

## 3. Missing capabilities (the genuinely new work)

**R1 — Unified per-minute research matrix (read layer, not a new store).** Today the per-minute
state is complete but SCATTERED: candles (marketdata), chain snapshots (marketdata),
rejections/fired diagnostics (strategy), context days (marketdata). There is no single queryable
"minute × feature vector" surface for ad-hoc research. Build: SQL VIEW(s) (+ a Python/notebook
recipe) that join, per 1m bucket per instrument: OHLCV, futures OI, ATM±n chain aggregates (OI,
IV, greeks from the nearest ≤2-min snapshot), VIX, day-context, and regime label. **View first,
materialize never** until a specific slice is proven slow (§6, §12 — the 1.12B-row snapshot pivot
already OOM'd once when materialized; that precedent is binding).

**R1b — Forward-return labels.** k-minute-ahead returns (k ∈ {5, 15, 30, 60, EOD}) for the
underlying future and for ATM straddle/CE/PE premium, joined at bucket END (never bucket start —
look-ahead rule §13 of the FID audit / B1 #683 completed-bucket contract). Computed on read via
window functions; a label is a JOIN, not a row.

**R2 — Hypothesis lane (generic entry-condition scan).** Evaluate an arbitrary condition
expression (the existing strategy-schema grammar, reused — no new DSL) over the historical 1m grid
WITHOUT publishing a strategy: scan → matched-minute set → label with R1b forward returns →
optionally hand the matched set to the COUNTERFACTUAL job kind (V013) for exit-rule-grid simulation
with real costs. This replaces "record every candidate condition live" (which would bloat the live
path) with "record raw operands once, evaluate any condition offline" — cheaper, replayable, and
immune to the publish-first bottleneck. Delivery shape: a new backtest-service job kind
(`HYPOTHESIS_SCAN`) + results tables in the backtest schema (D10: backtest-service is that schema's
single writer).

**R2c — Exit-rule grid.** Batch counterfactual replay of one entry set × N exit configs (reuses
the frozen evaluator; adds only orchestration + a comparison view).

**R3 — Monthly rollup + refinement ritual.** Calendar-month aggregation over runs, paper books,
shadow book, and reconciliations (a view over existing tables + one insights digest generator),
plus a documented monthly ritual (runbook skill) that walks: data-quality month review → per-book
performance → hypothesis-lane review → EVO campaign proposals → owner decisions. Mostly process.

**Asset-class seams (design now, build later)** — see §11.

## 4. Risks and compatibility issues

| Risk | Severity | Mitigation |
|---|---|---|
| Research matrix materialization OOMs live Timescale (precedent: 1.12B-row snapshot pivot; wide cagg refresh OOM #680) | HIGH | Views + on-read compute only; any materialization needs a chunked-refresh design and owner sign-off; never `refresh_continuous_aggregate` over wide windows on live |
| Cross-source timestamp-key trap (#214): joining candles (+05:30) to snapshot buckets (+00) by `OffsetDateTime` silently misses | HIGH | All R1 joins key by `time_bucket` in SQL or `.toInstant()` in Java — pin with a test |
| Look-ahead in labels/features | HIGH | Join features at bucket END; forward returns start at END+1; reuse the B1 completed-bucket contract and the IndicatorBank end-gating (#755) — property-test both |
| Live-path regression from research reads (heavy scans during market hours) | MED | Hypothesis scans run in backtest-service workers (same pool cap #717); heavy SQL off-hours by convention; no new live-path code at all |
| Schema-writer violation (D10) | MED | New tables live in the backtest lineage, written only by backtest-service; marketdata/strategy schemas read-only to the research lane |
| Parity surface contamination | MED | R2 reuses the engine read-only; goldens must stay byte-identical (any engine touch follows the side-channel doctrine); adversarial review mandatory if the evaluator is touched |
| Derived-history OI muting | MED | Pre-2026-06-15 chain state is VIRTUAL (Dow/IV → NEUTRAL); hypothesis results on derived history carry a mandatory provenance caveat — judge chain-led hypotheses on captured-OI windows only (evidence-policy already encodes this) |
| Snapshot retention vs research horizon | MED | A10 prune = 365d (owner-decided). Chain-state research beyond 365d falls back to derived (muted). Flag in monthly ritual; revisit horizon if it starts binding (§12 Q5) |
| Duplicate-sample inflation (overlapping forward windows on a 1m grid) | MED | §13 below — non-overlapping sampling / block bootstrap documented in the analysis method; E5 paired evaluation reused for any promotion decision |
| Queue contention with current program | LOW | This doc is DORMANT; nothing starts until the owner activates it |

## 5. Recommended architecture

**Principle: new READ layers and one new JOB KIND; zero new capture, zero live-path change.**

```
existing capture plane (unchanged)
  candles/1m ─ futures_oi_snapshots/1m ─ options_chain_snapshots/2m ─ context/EOD
        │                │                        │                      │
        └────────────────┴───────────┬────────────┴──────────────────────┘
                                     ▼
                    R1  research-matrix VIEWs (marketdata schema, read-only)
                    R1b forward-return label functions (window SQL)
                                     │
                                     ▼
                    R2  HYPOTHESIS_SCAN job kind (backtest-service)
                        grammar-expression → matched minutes → labels
                                     │                │
                                     ▼                ▼
                    R2c exit-rule grid            results tables
                        (COUNTERFACTUAL reuse)    (backtest schema)
                                     │
                                     ▼
                    existing promotion path: EVO proposals inbox → owner publish
                    → paper book → reconciliation → live-gap gate  (unchanged)
```

Multi-asset reuse falls out of keying everything by the canonical `(exchange, tradingsymbol)`
instrument and driving scans from config (`universe` refs), which is already the platform grammar.

## 6. Data model changes

Store raw facts (already stored); compute features and labels on read; persist only scan RESULTS
and monthly rollups. Concretely:

| Object | Lineage | Kind | Content |
|---|---|---|---|
| `research_matrix_1m` (+ an options variant) | marketdata | **VIEW** (no rows) | per-bucket join: OHLCV, fut OI/ΔOI, ATM±n chain aggregates, IV, VIX, day-context, regime |
| forward-return label SQL functions | marketdata | functions | `fwd_return(instrument, bucket, k)` variants; bucket-END anchored |
| `hypothesis_scans` | backtest | table | scan identity: expression (JSONB), universe, window, dataset epoch, engine SHA, created_by |
| `hypothesis_matches` | backtest | hypertable (narrow) | scan_id, bucket, instrument, operand snapshot (JSONB), k-labels — the ONLY new bulk rows, bounded by match count not grid size |
| `hypothesis_exit_results` | backtest | table | scan_id × exit-config → summary stats (reuses counterfactual outputs) |
| `monthly_rollups` | backtest | VIEW first | month × book/strategy/scan aggregates over existing tables |

Migrations: ~2 new backtest-lineage versions when activated (numbers assigned at build time —
V018/V019/V020 are currently reserved for the paused D4 wave; do NOT pre-claim). No strategy or
marketdata schema changes at all in MVP (views can ship in a marketdata migration or as
repeatable migrations — decide at build).

## 7. Implementation roadmap (for when the owner activates this)

| Phase | Content | Size | Tier |
|---|---|---|---|
| P0 | R1 views + label functions + no-lookahead property tests + a documented notebook/SQL recipe | 1–2 PRs | clean |
| P1 | R2 `HYPOTHESIS_SCAN` job kind + results tables + typed endpoints + FE list/detail page | 3–4 PRs | clean (engine read-only) |
| P2 | R2c exit-rule grid orchestration + comparison view | 1–2 PRs | clean |
| P3 | R3 monthly rollup view + insights digest generator + monthly-ritual runbook skill | 1–2 PRs | clean |
| P4 | Asset-class seams: calendar registry, per-segment session/cost/lot config, instrument-sync segment list | 2–3 PRs | clean, but data/feed entitlements = owner |
| P5 | New asset classes themselves (MCX/CDS/equity-options capture) | — | owner-gated (feed cost, disk, cadence) |

Sequencing rule: P0 → P1 → (P2 ∥ P3) → P4. Nothing here blocks or is blocked by the paused D3/D4
wave, but migration numbers must be claimed AFTER that wave lands to avoid the renumber-at-merge trap.

## 8. MVP scope (fastest usable research)

P0 only: the research-matrix views + forward-return labels + recipe doc. With just that, minute-level
questions ("what happens 30 min after OI spikes ≥2σ while IV percentile <30 between 09:30–11:00?")
are answerable in SQL/pandas against data ALREADY being captured — zero new rows, zero live risk,
one session of work. Everything else is leverage on top.

## 9. v1 scope

P0–P3: matrix + hypothesis lane + exit grid + monthly rollup/ritual — i.e. the full requested
feature set for NIFTY/SENSEX index futures + options, minus new asset classes (P4/P5 deferred).
Candidates that survive the hypothesis lane promote through the EXISTING EVO path (proposals inbox,
paper cap, reconciliation, live-gap gate) — no parallel promotion machinery.

## 10. Refactor recommendations

**None required before starting.** The extension points (job kinds, insights generators, experiment
views, schema grammar) were built for exactly this. Two conventions to hold, not refactors:
(a) keep the research lane out of `strategy-signal-service` entirely (no live-path imports — the
Modulith cycle rules apply); (b) new endpoints return typed records (MapReturnRatchetTest).

## 11. Modularity for future asset classes (seams to cut in P4)

1. **Calendar registry** — `MarketCalendar` gains `forSegment(exchange)` with per-segment sessions
   + holiday CSVs (MCX 09:00–23:30, CDS 09:00–17:00); horizon-canary per calendar. (Gap sourced:
   only `nse()`/`bse()` exist today.)
2. **Instrument sync segments** — config-list the synced exchanges (today NSE/NFO/BFO hardcoded);
   the canonical-key model already accommodates MCX/CDS symbols.
3. **Per-segment trading spec** — lot size (already per-instrument), tick size, cost model
   (brokerage/STT/CTT differ for commodities/currency), margin product mapping — one config record
   per segment consumed by cost + margin layers.
4. **Session-anchored bucketing** — the 09:15-anchored assumptions (3m alignment, session gates)
   become per-calendar constants.
5. **Benchmark/regime per asset** — regime pre-flight pins `NIFTY 50`; make the benchmark an
   attribute of the universe config.
None of these need building until a second asset class has a funded data source.

## 12. Open questions and assumptions (for the activation review)

1. **Chain cadence:** keep 2-min (recommended — 1-min doubles a table already at prune-horizon
   scale and tightens the Kite/Upstox rate budget for marginal research value) or fund 1-min?
2. **Matrix strike scope:** ATM±how-many for the options matrix view? (Recommend ±5 to start.)
3. **Equity option chains:** capture is NIFTY+SENSEX only today; stock-option research needs an
   owner call on universe + volume.
4. **MCX/CDS feed entitlement:** Kite covers the segments; the subscription/entitlement and disk
   budget are owner decisions (P5 gate).
5. **Retention:** 365d snapshot prune vs multi-year chain-state research — accept derived-history
   muting beyond 365d, or extend retention for a bounded strike band?
6. **Assumption:** research consumers are SQL/notebook-first initially; FE pages ride in P1, not P0.
7. **Assumption:** the strategy-schema grammar is expressive enough for hypothesis conditions
   (it powers 45 live strategies; anything it can't express becomes a grammar item, not a new DSL).

## 13. Method guardrails baked into the design (look-ahead / inflation / overfitting)

- **Look-ahead:** features join at bucket END; labels start END+1; completed-bucket contract (B1)
  and IndicatorBank end-gating already enforce this in the engines — the new SQL layer gets
  property tests asserting the same invariant. Timestamp joins by instant, never offset-bearing keys.
- **Duplicate-sample inflation:** overlapping k-forward windows on a 1m grid are NOT independent
  samples. The recipe doc mandates: non-overlapping event sampling (or block bootstrap) for
  significance claims; match-count ≠ sample-count is stated on every scan result.
- **Overfitting:** every promotion decision routes through the EXISTING doctrine — pre-registered
  ablations, paired IS/OOS, deflated Sharpe with campaign-cumulative trial counts, graveyard
  memory, live-gap reconciliation gate. The hypothesis lane feeds that machinery; it never bypasses it.
- **Testing strategy:** unit (label math, window edges, expression eval vs engine parity on a
  fixture); property tests (no-lookahead, instant-keyed joins); Testcontainers ITs (unique slugs,
  no per-method cleanup); goldens byte-identical (research lane must not drift them); determinism
  re-run on any scan (same epoch + SHA → identical results); walk-forward + OOS via the existing
  optimizer harness on anything promoted.

---
*Prepared 2026-07-12. DORMANT until owner activation. On activation: re-verify migration heads,
re-check the D3/D4 wave landed, then enter items into the ledger §0 with fresh row IDs.*
