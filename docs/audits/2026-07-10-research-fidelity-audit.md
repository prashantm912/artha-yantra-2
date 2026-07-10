# Research-fidelity audit & improvement plan — 2026-07-10

**Scope:** backtest/livetest accuracy, backtest↔live mismatch sources, data quality,
telemetry/event model, reproducibility/versioning, frontend usability, backend
workflow/APIs — plus the target architecture and roadmap needed so a follow-up
autonomous-optimization build (referred to throughout as **Prompt 2**) stands on sound
data. Produced by 6 parallel read-only audit agents + hand verification of the one
suspected live defect (§3.1, code-confirmed). Every claim carries a `file:line` or PR#.
Prior audits are incorporated, not re-derived: the 2026-07-05 full audit
(`docs/audits/2026-07-05-full-codebase-audit.md`) already fixed most of its HIGHs; its
open items (H6, H8, M-series parity rows) are folded in below where relevant.

**Headline verdict.** The platform's *parity spine* is genuinely strong — one shared
`libs/strategy-engine` JAR generates signals for both backtest and live, pinned by
byte-identical golden vectors, a replay-parity test, and a shared exit-equivalence
fixture. The backtest/optimizer lane has above-typical reproducibility hygiene (pinned
config checksums, pinned universes, per-series data hashes, unique run-per-job, seeded
resumable sweeps). The research-grade weak points are concentrated in five places:

1. **A code-confirmed P0 live defect**: coarse-primary (3m/5m/15m/1h) live series accrue
   *first-minute partial buckets that are never corrected* (§3.1) — live strategies have
   been evaluating truncated bars while backtests roll full buckets.
2. **The equity cross-sectional data layer**: survivorship-shaped universes, a
   live-screener-unadjusted vs backtest-adjusted split/bonus asymmetry (audit-H6
   superset), no dividends, no point-in-time storage (§4).
3. **Execution realism floors**: no order-event model, option slippage pinned at 1 tick
   because quoted spread is never captured, BTST exits not simulated at all, the
   `costs` knob dead (§2, §3).
4. **Run provenance holes**: engine code version absent from run rows; the swing
   backtest pipeline (the one the swing doctrine decisions were made on) has near-zero
   lineage (§6).
5. **Research workflow gaps**: no backtest-vs-paper comparison anywhere, no run
   tagging/notes/saved views, no export, optimizer sweeps non-durable and unlistable
   (§7, §8).

Path abbreviations: **SSS** = `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal`,
**BT** = `services/backtest-service/src/main/java/in/arthayantra/backtest`,
**MDS** = `services/market-data-service/src/main/java/in/arthayantra/marketdata`,
**LIB** = `libs/strategy-engine/src/main/java/in/arthayantra/strategyengine`,
**OPT** = `services/optimizer-service/app`, **FE** = `frontend-react/src`.

---

## 1. Architecture overview

**Stack (current, and it is the right stack — no wholesale swaps recommended):**

| Layer | Technology | Notes |
|---|---|---|
| Services | 4 × Java 21 / Spring Boot (Modulith): `edge-gateway`, `market-data-service`, `strategy-signal-service`, `backtest-service` | Multi-module Maven reactor; JaCoCo ≥60 %, Modulith `verify` in CI |
| Optimizer | Python 3.14 FastAPI + Optuna (`services/optimizer-service`) | Trials fan out onto the Java backtest worker pool via Redis Streams |
| Shared libs | `strategy-engine` (the parity core), `strategy-schema`, `black76-math`, `market-calendar`, `common-web` | One engine JAR linked by live + backtest |
| Data | TimescaleDB (pg17) — 4 Flyway lineages (admin / marketdata / strategy / backtest), Redis (streams, pub/sub, sessions) | Hypertables + continuous aggregates; mock/live DB + Redis isolation |
| Frontend | React 19 + Vite 6 + Tailwind v4 + shadcn, Zustand, TanStack Query v5, ECharts + lightweight-charts, STOMP WS | ~60 pages; single-owner auth |
| Deploy | Docker Compose, loopback-only gateway, `ay.ps1` CLI | CI: sharded per-service Maven matrix + separate path-filtered optimizer/margin workflows |

**Execution surfaces (who simulates/executes what):**

- **Live tick engine** — `SignalEngine` (SSS/signals/SignalEngine.java): per-strategy Redis
  subscription to `candles.1m.*`, single eval thread, 1m/coarse-primary evaluation,
  live-only `ScalperConfluenceGate` (~30 rails), BTST pre-close clock. Emits signals →
  paper books via `AutoPaperListener`/manual takes.
- **Daily swing batch** — `SwingBatchEngine` + `SwingDoctrine` (SSS/swing/, post-#655):
  Minervini 20:00 / Manas 20:05 IST, entry+exit passes on daily bars, paper entries at
  daily close.
- **Paper execution** — `PaperService`/`PaperBracketEvaluator` (SSS/paper/): 3 family
  books (₹1.5 L each) + manual; fills via the same `LtpSlippageV1` the backtest uses;
  brackets polled every 15 s against Redis last-tick.
- **Shadow book** — `ShadowBookService` (SSS/signals/): every *rejected* scalper entry
  trades virtually (champion + knob-variant challengers) — a gate-tuning counterfactual.
- **Job-based backtest** — `BacktestRunner` → `ReplayEngine` (candle path) or
  `OptionsPremiumReplay` (premium-as-primary), Redis-Streams worker pool, walk-forward
  folds, Monte Carlo (BT/replay/, BT/jobs/).
- **Swing deep sims** — `MinerviniSwingBacktest` / `ManasAroraSwingBacktest` +
  `SwingPortfolio` (MDS/minervini/, MDS/manas/): ~11-year array-based daily sims,
  *outside* the job pipeline and outside the parity firewall.
- **Dormant real execution** — `LiveOrderService` → OpenAlgo `placeorder`
  (SSS/execution/), off by default (`artha.scalper.execution=paper`); acks log-only.

**Parity spine (what keeps backtest ≡ live honest today):** shared
`TickwiseGoldenRunner`/`EntryEvaluator`/`ExitEvaluator`/`FillSimulator`/`PositionSizer`
(LIB), golden vectors (`GoldenDeterminismTest`, byte-identical), `BacktestParityTest`
(replay == frozen live vectors), `contracts/fixtures/exit-equivalence.json` pinned by 3
suites across both services.

**Data spine:** Kite WS ticks → 1m candles (provenance-preserving dual upserts) → caggs
(5m/15m/1h/1d/1w) + read-time 3m rollup; bhavcopy EOD projection; Upstox expired
contracts (per-minute option OHLCV+OI); live options-chain snapshots every 2 min since
2026-06-15; virtual derived OI for history. Detail in §4.

---

## 2. Backtest fidelity audit

### What is sound

- **Signal parity is constructed, not asserted**: replay drives the identical engine JAR
  live uses (BT/replay/ReplayEngine.java:29-35, 90-92), pinned by goldens + parity test.
- **Determinism**: zero RNG/wall-clock in replay; Monte Carlo separately seeded and its
  seed persisted (BT/montecarlo/MonteCarlo.java:14-32); re-runs assert `data_hash`
  equality (BacktestReplayIntegrationTest.java:117).
- **Costs model exists and is statutory-grade** where wired: pinned Zerodha/NSE schedule
  (brokerage/STT/CTT/exchange txn/GST/stamp/SEBI, LIB/fills/FeeConstants.java) applied
  side-aware in `LtpSlippageV1.costs` (LIB/fills/LtpSlippageV1.java:74-152).
- **Options replay uses real premiums**: the traded contract's own backfilled 1m candles
  (BT/replay/options/CandlePremiumReader.java:38-47), provenance persisted per run;
  missing premium = loud 422 DATA_GAP, not silent skip.
- **Lookahead protections**: coarse bucket completes only when the next bucket's first
  bar arrives (LIB/golden/TickwiseGoldenRunner.java:156-190); regime labels strictly
  T−1 (BT/regime/RegimeLabeler.java:82-87); warmup enforced; `.toInstant()` map keys
  (#214 lesson).
- **Walk-forward mechanics** are correct: trading-day folds, independent re-replays,
  `min_trades` exclusion, per-fold regime attribution (BT/replay/folds/).

### Findings (ranked by research-fidelity impact)

| # | Finding | Evidence | Why it matters |
|---|---|---|---|
| B1 | **BTST exit is not simulated.** The btst branch evaluates pre-close entries but never opens a tracked position; exit rules never run; replay force-closes at end-of-data. | TickwiseGoldenRunner.java:210-227; ReplayEngine.java:330-344; golden fixture has entry events only | Any BTST backtest P&L is meaningless; overnight-gap capture — the whole point of BTST — is untested. Must be fixed before Prompt 2 can optimize BTST variants. |
| B2 | **Swing deep sims are frictionless and close-filled**: entry *and* exit at the signal bar's own close, zero slippage, zero trade-level costs (only a coarse portfolio-level % netting). | MDS/manas/ManasAoraSwingBacktest.java:266-272, 295-306, 361-370; MDS/minervini/SwingPortfolio.java:87-94 | The 11-year CAGR/DD/Sharpe headlines driving swing doctrine carry the loosest execution model on the platform. (Partially disclosed in report notes; still the top calibration risk.) |
| B3 | **No intrabar H/L touch anywhere wired.** All stops/targets/trails evaluate on bar closes (1m floor). `IntrabarExitResolver` (worst-of touch + gap-through-open) is dead code; `BAR_HL_WORSTOF` unreachable (`oneMinuteCovered` hardcoded `true`). | LIB/fills/IntrabarExitResolver.java:24-61 (no prod callers); BacktestRunner.java:203; LIB/eval/ExitEvaluator.java:196-231 | Spikes through a stop that mean-revert within the bar are invisible; systematically flatters tight-stop scalpers. |
| B4 | **`costs` knob is dead + wrong instrument class on the candle path.** Request/schema accept a `costs` block; nothing reads it. Every candle-path run costs as EQUITY DELIVERY even when the signal series is an index future. | BT/jobs/JobsService.java:96-97 vs BacktestRunner.java:202, 241; BT/replay/CostConfig.java:23-31 | Cost mis-specification skews net returns per strategy class; futures CTT/₹20-cap brokerage never exercised by the job pipeline. |
| B5 | **Option-leg liquidity/spread realism missing.** `quotedSpread` is hardwired `null` in both engine paths → option slippage always 1 tick (₹0.05); premiums carry forward stale last-trades for non-traded minutes; no participation cap. | ReplayEngine.java:357; OptionsPremiumReplay.java:802; LtpSlippageV1.java:52-68 | Deep-OTM/illiquid strikes report fills the market would not have given. Optimizers *seek out* exactly these unrealistic corners. |
| B6 | **No option expiry settlement.** A leg held past expiry carries the last traded premium until signal exit/end-of-data; no intrinsic settlement, no exercise STT in backtest. | OptionsPremiumReplay (no settlement branch); FeeConstants.java:40 (paper-only constant) | Multi-day option holds crossing expiry mis-report. |
| B7 | **Daily-context lookahead**: 1d context bars are visible with the *full day's* OHLC from that day's first 1m bar (cagg bar stamped at day start; `advanceContexts` appends any bar with `bucketStart ≤` current). | TickwiseGoldenRunner.java:259-274 | A coarse-primary strategy with a 1d context indicator can read up to one future close at decision time — classic leak, currently reachable. |
| B8 | **Swing-sim same-bar signal+fill and inclusive geometry window**: weekly geometry recompute includes the current bar; entry fires and fills on that same close. Live batch mirrors it (also fills at that close), so *paper matches the sim* — but both assume a fill a real order can't get. | ManasAoraSwingBacktest.java:210-214, 266-272; SSS/swing/SwingBatchEngine.java:326 | Consistent-but-optimistic; must be priced (open-next-day variant) before real-money graduation. |
| B9 | **No margin model in backtest.** Long-premium cash budget is the only constraint; SPAN exists only as a live analytics call. | grep clean in backtest-service; MDS margin path #510 | Capital feasibility/leverage of portfolios unvalidated; Prompt 2 needs a margin-feasibility term or it will "discover" un-fundable variants. |
| B10 | **Idealized order layer**: market-at-reference fills only; no limit/SL-M simulation, no partial fills, no rejections, no latency (NEXT_OPEN's one-bar delay is the only proxy). | LIB/fills/FillSimulator.java:9-12; FillTiming.java:8-11 | Acceptable *if disclosed per run*; must be a recorded run property (see §11 provenance block). |
| B11 | **Candle-path exit attribution lost**: `backtest_trades.exit_reason` is only ever `signal_exit`/`end_of_data`; the actual stop/trail/TP/square-off reason never reaches persistence (options path and swing sims *do* attribute). | ReplayEngine.java:303, 338; LIB/golden/GoldenSignalsJson.java:27-35 (frozen writer) | Blocks exit-doctrine forensics and Prompt 2's per-exit-type metrics on candle-path runs. Fix parity-safely via the non-serialized side-channel pattern. |
| B12 | **No decision-trace/rejected-entry persistence for backtests** — nothing like live `signal_rejections` exists for a run; only closed-trade `contributions` survive. | ReplayResult.signals unpersisted | "Why did the backtest not trade on date X" is unanswerable from stored data; optimizers need rejection counterfactuals. |
| B13 | **Swing results keep no trade rows** — one aggregate report JSONB per run. | deploy/flyway/marketdata/V037:1-17 | Per-trade re-analysis requires a re-run; no mechanical diffing of doctrine changes. (Also a §6 lineage hole.) |
| B14 | Survivorship-biased swing universe (disclosed in the report string). | ManasAoraBacktestService.java:386-389 | See §4.1 — data-layer root cause. |
| B15 | `seed` recorded but decorative for replay (replay has no RNG). Harmless; a reader may over-trust it. | deploy/flyway/backtest/V003:21 | Documentation nit; keep recording it (Monte Carlo uses `mcSeed`). |

### Flags/knobs that fork backtest vs live (must be run-recorded, see §11)

- `backtest.relax_session` — options premium path only; the candle path never passes it
  (ReplayEngine.java:90-92 → 3-arg run → false).
- `backtest.oi_confluence_gate` — backtest-only historical OI/IV filter, silent-degrades
  with `oiGateCoverage` tally (OptionsPremiumReplay.java:199-224, 232-332). NOT the live
  gate.
- `backtest.strike_premium_band` — backtest-only StrikePicker mirror, not in the schema.
- Live-only arming backtest never runs: `ScalperConfluenceGate` tags, relative-volume
  floor env knobs, `artha.manas-arora.pyramid.enabled`, `ARTHA_PAPER_RISK_*`.
- `session.fill_timing` defaults (btst→AT_CLOSE else NEXT_OPEN, StrategyCompiler.java:176-180).
- Backtest-side tunables mirroring live doctrine constants by hand
  (`artha.manas-arora.backtest.*`, ManasAoraBacktestService.java:177-197) — a
  manual-sync drift risk.

---

## 3. Live-test fidelity audit

### 3.1 P0 — partial coarse-bucket poisoning (code-confirmed this audit)

**Mechanism (each link verified in source):**

1. For a coarse primary (3m/5m/15m/1h), the 1m bar that *opens* bucket N+1 triggers
   `refreshFromRest(primaryKey)` then evaluation — SSS/signals/SignalEngine.java:632-651.
2. `refreshFromRest` fetches `[lastBarTime, now)` where `now` is ~60 s into the new
   bucket — SSS/signals/LiveSeriesStore.java:68-81.
3. The read returns the **in-progress bucket as a 1-minute partial**: the 3m read-time
   rollup has no completeness filter (MDS/candles/CandleRepository.java:221-244,
   `bucket >= ? AND bucket < ?` over 1m rows), and the 5m/15m/1h caggs are real-time
   (`materialized_only=false` — in-progress buckets compute live,
   deploy/flyway/marketdata/V004, V019).
4. `EngineSeries.append` is strictly increasing; the *completed* version of a
   previously-appended bucket throws and `appendQuietly` swallows it —
   LIB/series/EngineSeries.java:55-61, LiveSeriesStore.java:83-91. **The partial is
   frozen forever** (until a service restart re-warms history).

**Consequence:** after warm-up, a live coarse-primary series ≈ a chain of first-minute
slices — ~⅓ (3m) to ~1/15 (15m) of true volume, truncated highs/lows, closes sampled
one minute into each bucket — while the backtest's `TickwiseGoldenRunner` rolls full
buckets. Every live 3m scalper evaluation and every 5m/15m/1h gate series is affected;
higher-TF refreshes go through the same path (SignalEngine.java:645-649).

**Corroboration:** the 2026-07-02 session forensics found the 125k volume floor
*unpassable* live (`docs/signal-analysis/` first-pass findings) — exactly what a 3m
floor tuned on true 3m bars reads against ~1-minute volumes. The relative volume floor
(#605) partially masks the defect (partials compared against a partial-derived
average).

**Fix direction (Phase 0, §12):** clamp coarse reads to completed buckets (server-side
`to = bucketFloor(now)` for rolled/cagg intervals or a `complete=true` filter), or make
`EngineSeries` replace-on-same-bucket for the final bar; add a regression canary
(live 3m bar volume vs the same bucket's 1m sum) and an integration test. Until fixed,
**live↔backtest comparisons for coarse-primary strategies are invalid**, and live
rejection forensics on volume/H-L-derived rails are suspect.

### What is sound

- **Consumer-side liveness is now layered**: `SubscriberHealthCanary` (silent Redis
  subscription drops, #634), `DataHealthCanary` (tick/bar divergence, capture
  freshness), `FeedWatchdog`, `DotHealthCanary` (per-dot gate-input liveness),
  swing-batch marker + 08:30 canary + external 20:15 dead-man heartbeat (#640).
- **Decision-time capture on *rejections* is excellent**: blocking rail + operand +
  threshold + margin + full diagnostic JSONB (all rail checks, dots, chart/OI/macro
  context, would-be leg) — deploy/flyway/strategy/V015; SignalEngine.java:1049-1152.
- **Paper fills ride the same `LtpSlippageV1` + statutory costs as backtest** (one
  implementation — SSS/paper/PaperFillService.java:29-39).
- **Exit-equivalence fixture** pins backtest `PremiumExitEvaluator` ≡ live
  `PremiumBracketRules`/`PaperBracketEvaluator` semantics (5 scenarios, 3 suites).
- **Risk control plane**: per-book kill switch / max-open / daily-loss / deployment caps
  DB-backed and audited (`risk_audit`); scalper 5-sub-account discipline; §0B hard-stop
  refusal at load (SignalEngine.java:244-249).

### Findings (ranked; L1 = §3.1 above)

| # | Finding | Evidence | Why it matters |
|---|---|---|---|
| L2 | **No order-event model.** `paper_orders` inserts directly as `FILLED` (`placed_at = filled_at = now()`); NEW/ACK/PARTIAL/REJECT/CANCEL unrepresented. Real OpenAlgo acks (when armed) are **logged only, never persisted**; no cancel/modify path, no fill-confirmation loop. | SSS/paper/PaperOrderRepository.java:42-76; V005:18-19; SSS/execution/LiveOrderService.java:72-75 | Execution friction can't be modeled, audited, or reconciled. Prerequisite for any real-money path and for Prompt 2's fill-realism calibration. |
| L3 | **No decision-time wall clock, no latency instrumentation.** `signals.generated_at` = bar bucket instant by design; signal-emit wall time recorded nowhere; tick→signal→fill latency unrecoverable from persisted data. The master-plan §17.3 latency gate is plan-only. | SignalEngine.java:846-851; metrics are durations only | Live-arm decisions and slippage models need measured latency; currently unmeasurable. |
| L4 | **No fill-achievability evidence.** `quote_bid`/`quote_ask` columns exist but every insert passes null; quotedSpread never feeds the fill model (option slippage pinned 1 tick); no MAE/MFE; paper fills never compared to subsequent market. | SSS/paper/PaperService.java:198-200, 338-340; V005:23-27 | Paper P&L on illiquid strikes is systematically optimistic and *unverifiable*. Cheapest high-value fix: stamp bid/ask at open+close from the chain/quote at fill time. |
| L5 | **Stale-tick blindness in the paper exit path.** Brackets/MTM read Redis `ticks:last` with no age check; a dead option tick silently defers stops all session; `doSettle` fallback chain ends at `avgEntryPrice` (books a breakeven close for a tickless leg). | SSS/paper/PaperBracketEvaluator.java:35-87; PaperService.java:324-325 | A stop that never fires is the worst kind of silent risk drift; breakeven-fallback pollutes P&L records. |
| L6 | **Accepted-signal context asymmetry.** Rejections persist full chart/OI/macro context; accepted entries persist only the distilled leg + dots. | SignalEngine.java:891-914 vs 1049-1152 | Post-hoc forensics on *fired* trades can't reconstruct decision inputs; Prompt 2's decision-snapshot input is half-missing. |
| L7 | **Entry-price basis mismatch vs backtest.** Live scalper paper opens at the chain-captured `option_ltp` the gate saw (possibly memo-cached), fallback `event.fillPrice` (index-scale — wrong instrument scale). Backtest premium entry = entry bar's own 1m option close. Equity/futures job backtests default NEXT_OPEN vs live at-close/at-take-LTP — a systematic one-bar timing skew. | SSS/paper/PaperSignalListener.java:80-98 (fallback :96); OptionsPremiumReplay.java:700-708; LIB/fills/ReferencePriceSelector.java:17-35 | Small persistent bias between the two worlds; must be characterized in the backtest-vs-paper comparison view (§9). |
| L8 | **Exit observation cadence mismatch.** Backtest premium exits scan 1m closes and fill at the breaching close; live brackets poll every **15 s of tick LTP** and fill at that LTP. Equivalence is pinned at semantic level only. | PremiumExitEvaluator.java:12-16; SSS/paper/PaperScheduler.java:32-38 | Live exits earlier on intrabar spikes → live-vs-backtest exit P&L divergence that looks like edge decay but is cadence. |
| L9 | **Risk caps gate emission only.** Manual `POST /paper/orders` and manual takes bypass `entryAllowed` and the sub-account freeze. Heat cap advisory until `ARTHA_PAPER_RISK_ENABLED`. | SSS/paper/RiskService.entryAllowed:97-158; ScalperAccountModel.java:129-131 | An owner fat-finger can breach every configured cap; also makes paper books a polluted evidence base if used manually. |
| L10 | **1m pub/sub unrecoverable + in-memory latches.** A missed 1m bar (overlapping restarts) leaves a permanent live-series gap (no consumer-side gap detector); once-per-day latches (BTST `preCloseDone`, ntfy dedup) reset on restart. | SSS/signals (Redis pub/sub design); SignalEngine state fields | Rare but silent; a consumer gap detector + re-warm-on-gap closes it. |
| L11 | **Live-only exits have no backtest counterpart** (structural stop, confluence-flip, straddle VWAP stop, prior-day-VWAP stop), and the live confluence gate is live-only (armed scalpers ≈ 0 backtest trades — documented artifact). | SignalEngine.java:548-571, 1297-1320; CLAUDE.md | Already house policy ("judge armed scalpers on live") — but Prompt 2 must treat gate-armed strategies as *forward-only* optimizable; encode this as run metadata, not tribal knowledge. |

### Event/state persistence map (what each table captures / lacks)

| Table (strategy schema) | Captures | NOT captured |
|---|---|---|
| `signals` (V003/V006/V009/V020/V022) | version pin (FK), side, entry/SL/target, composite, frozen `score_breakdown` (gate tree + operand values + per-indicator raw/score/weight), `generated_at` (=bar time), `suggested_qty`, scalper leg + dots + manual checks, swing side-channels | wall-clock emit time; full market context on accepted entries; input data ages; chain snapshot ref |
| `signal_rejections` (V015) | blocking rail/operand/threshold/margin, composite, full diagnostic (rails + failPolicy + dots + chart/OI/macro + would-be leg) | input timestamps/ages |
| `paper_orders` (V005/V021) | book, signal link, side/qty, fill price, `fill_simulator`, `slippage_applied` | lifecycle states (always FILLED); `quote_bid/ask` (always null); reference-price source/age; latency |
| `paper_positions` (V005-V026) | book, brackets, realized PnL, `close_reason`, `subaccount_idx`, `advised_lots`, margin snapshot, `opening_signal_id` | per-lot fill history (averaging collapses), MAE/MFE, holding-period marks |
| `shadow_positions` (V016-18) | rejected-entry counterfactuals, variants, cost-adjusted net PnL | indicator-driven exits (by design), sizing beyond 1 lot |
| `risk_audit` / `swing_batch_runs` / `notification_events` / `strategy_graduations` / `journal_entries` | trips/flips; batch markers incl. `exit_skipped`; per-attempt notify audit; graduation snapshot; auto-stubs | trip clears; per-candidate batch decisions (log-only) |
| Redis | `ticks:last`, `ticks:last-at`, `signals` channel, `candles.1m.*` | everything is fire-and-forget; no persistence |

---

## 4. Data quality and completeness audit

### Lineage map

```
Kite WS ticks ─→ CandleBuilder (1m; pre-open/future/late-tick guards)
              ─→ BarWriter → candles hypertable  [B-6 merge: GREATEST/LEAST, source='TICK_AGG']
                            → Redis candles.1m.*  (live engine)
Kite/OpenAlgo REST ─→ upsertAuthoritativeAll  [full REPLACE, source=KITE/OPENALGO/BACKFILL]
NSE/BSE bhavcopy ─→ nse_eod_bhavcopy (write-once-ish) ─→ candles@1d [DO-NOTHING, source='BHAVCOPY']
                                                       └→ screeners + RegimeService read RAW here
caggs: candles_5m/15m/1h(IST re-anchor V029)/1d/1w  (real-time; NO source column)
3m = read-time 1m rollup (no cagg — V027 dropped it)
CA feeds ─→ eod_corporate_actions (splits+bonuses ONLY) ─→ read-time back-adjust,
            REST /candles path ONLY, 1d BHAVCOPY bars ONLY (EquitySplitBonusAdjuster)
CorporateActionJob (16:30) ─→ anchor-diff ⇒ purge + full re-fetch (Kite-history symbols)
Upstox expired-instruments ─→ expired_contracts (+V026 coverage) + per-minute candles
OptionsSnapshotService (2-min) ─→ options_chain_snapshots (live since 2026-06-15)
HistoricalOiReader ─→ CandleDerivedChainReader (virtual OI; iv/greeks null; PAST-only)
```

### What is sound

- **Intraday lineage integrity is strong**: provenance-preserving dual upserts
  (MDS/candles/CandleRepository.java:20-113), tick-poison guards (#482 fix,
  CandleBuilder.java:38, 81-93), 10-min recency tail (#490, GapDetector.java:32),
  layered canaries, `backfill_jobs` run ledger (V030), coverage endpoints.
- **The platform is unusually honest about its biases**: survivorship notes printed
  into every swing report; derived-OI fidelity contract documented; muted-dots-on-history
  documented.
- **Regime preflight** enforces ~272 benchmark sessions before any windowed run
  (BT/regime/RegimePreflight.java:35-44).

### Findings (ranked)

| # | Finding | Evidence | Failure scenario for research |
|---|---|---|---|
| D1 | **Live screener plane reads CA-unadjusted bhavcopy while backtests read broker-adjusted candles** (prior-audit H6, confirmed + broader). Screener SQL never joins `eod_corporate_actions` though ratios exist in-DB; same raw reads feed `RegimeService` breadth and Manas geometry. | MDS/screener/minervini/TrendTemplateService.java:100-111; ManasScreenService.java:109-114; RegimeService.java:23-31 | A split/bonus inside the 420-day window craters c63–c252 returns and strands the 52wk-high pre-split → momentum leaders (where bonuses cluster) silently fail RS/proximity gates for up to a year. The *live funnel excludes names the backtest happily trades* — a hidden live-vs-sim divergence no canary watches. |
| D2 | **Survivorship bias is structural.** Swing backtests/hit-rate scan today's `instruments` EQ ∩ deep-candle symbols; the master accrues only since ~2026-06; delisted names absent; RS percentile computed over survivors. | MDS/minervini/MinerviniBacktestService.java:717-724, :405; MinerviniHitRateService.java:250 | 43 %/23 % CAGR headlines exclude the delisted-midcap cohort; live forward CAGR structurally undershoots sim. Mitigated by disclosure + "judge on forward paper" — but Prompt 2 must carry universe metadata per run and never rank across universes. |
| D3 | **Point-in-time correctness is convention + tripwire, not storage.** Broker history is back-adjusted in place (price *levels* embed future split knowledge — ₹-level gates and turnover floors read post-hoc scales; volume never adjusted so `close×volume` is off by the ratio pre-split). Re-fetch REPLACES; CA job purges+rewrites whole symbols. `data_hash` detects drift, cannot restore. | CandleRepository.java:62-113; CorporateActionJob.java:233-263; BT/replay/DataHash.java:10-31 | Two runs weeks apart are not comparable after any CA/backfill event; the optimizer's memory decays invisibly except for a hash flag. |
| D4 | **Mixed-provenance 1d series in direct-SQL reads.** `candles`@1d interleaves broker-adjusted (KITE/BACKFILL) and raw (BHAVCOPY) bars; the source-aware adjuster exists only on the REST path; backtest/hit-rate read raw SQL. | CandleRepository (per-bar source); EquitySplitBonusAdjuster.java:18-44; MinerviniBacktestService.java:735-766 | Around any CA, adjacent bars sit at different scales → phantom gaps/spikes through ATR stops, SMA slopes, pivots. |
| D5 | **Dividend blindness.** CA parser explicitly discards dividends; no cash-flow table anywhere; returns are price-only. | MDS/bhavcopy/CorporateActionSubjectParser.java:10-19 | Multi-month swing holds understate total return; ex-dividend drops read as adverse moves to 2×ATR/Chandelier exits — a real (small) doctrine distortion. |
| D6 | **Point-in-time index membership missing.** `index_constituents` (V008) is an append-only PIT design but the live fetcher is a placeholder returning empty; Futures OI Buzz uses a static factsheet JSON. | MDS/constituents/PendingLiveIndexConstituentsFetcher.java:10-29; StaticIndexConstituents.java:14-20 | RS-rank/backtests have no membership history to bound the universe; static list silently ages. |
| D7 | **Backtest 1d read path serves the sparse cagg, not native 1d.** `CandleReader.read(interval='1d')` → `candles_1d` cagg (1m-derived, sparse on fresh boot) while warmup reads native. | BT/replay/CandleReader.java:36 vs :96-127 | A 1d-primary job backtest on a fresh stack sees a thinner series than the chart/screener shows — confusing, occasionally wrong. |
| D8 | **No automated daily data-quality artifact.** Pieces exist (canaries, 15:45 gap-audit pass, coverage summary, backfill ledger) but no scheduled per-symbol completeness/row-count report; nothing audits per-symbol bhavcopy presence day-over-day. | MDS canary/backfill packages | A symbol silently missing from one day's bhavcopy file is invisible; screener/regime inputs quietly thin. |
| D9 | **No per-bar completeness flag on `candles`** (`complete` exists only on `expired_contracts`); in-progress-bar staleness is a read-time rule invisible to direct-SQL consumers. | V026 vs candles DDL | Root enabler of §3.1; also lets backtests ingest a partial current-day bar if run intraday. |
| D10 | **Options capture outage healing is manual** (`OiBackfillService` flag-gated); a multi-hour capture outage leaves a permanent snapshot hole (2026-07-09 outage class). | MDS/backfill/OiBackfillService.java:33-53 | Forward-paper OI evidence (the designated discriminator) develops silent holes. |
| D11 | Depth/coverage bounds (accepted, must stay disclosed): bhavcopy ~1y broad; Upstox equity ~200-session; ~11y only for the subscribed subset; calendar bundle 2024–2026 (horizon canary, CD-2 refresh due before ~2026-11-16); BSE holiday list = NSE approximation; Muhurat unmodeled. | libs/market-calendar resources; MEMORY | Windows outside coverage 500 loudly (good); BSE expiry edge is a small standing risk. |

---

## 5. Telemetry and event-model audit

### What exists (per-feature, mostly good)

- **Firing signals**: full frozen `ScoreBreakdown` (composite, threshold, gate tree with
  operand values, per-indicator raw/score/weight/contribution) + family side-channels
  (`scalper_detail` V009, `minervini_detail` V020, `manas_arora_detail` V022).
- **Every scalper gate block**: `signal_rejections` with rail-level diagnostics (#404,
  V015) + shadow-book counterfactual fills (V016-18) + per-dot health endpoint.
- **Swing selection**: per-day per-symbol gate booleans *including failures* for every
  scanned candidate (V031/V033/V036/V038) — full explainability of screen decisions.
- **Ops events**: `risk_audit`, `swing_batch_runs`, `notification_events` (per-attempt
  SENT/FAILED/SUPPRESSED), `backfill_jobs`, `corporate_action_events`, `roll_events`,
  `strategy_graduations`, `bot_commands`.
- **Metrics**: Prometheus counters/durations for eval, emission, candle-builder lag,
  tick publish.

### Findings

| # | Finding | Evidence | Impact |
|---|---|---|---|
| T1 | **No unified event spine.** Lineage is per-feature tables joined by convention; "everything that happened to strategy X" = ~8 hand-written joins. The signal→book attribution is a *read-time join on mutable `strategies.tags`* — history silently rewrites if tags change. | SSS/signals/SignalRepository.java:105-110 (book join) | Prompt 2 needs one queryable event/experiment graph; today it would re-derive joins per question and inherit the tags-drift hazard. |
| T2 | **Un-audited mutations on lineage-bearing rows**: `strategies.enabled`, tag edits, notification toggles, `signals.status` transitions (ACTIVE→TAKEN/DISMISSED/EXPIRED) are in-place UPDATEs with no audit action. | SSS/registry/RegistryService.java:374-412; V003:20-21 | Evidence base mutates without trail; graduation/forward-paper conclusions can't be defended after the fact. `strategy_audit_log` already exists — cheap to extend. |
| T3 | **No UI-action or login audit.** Session cookie + Redis only; `loginTime` is a session attribute; no login-event row; no record of who triggered runs/resets from the UI (single-owner today, but autonomous-writes future needs an actor trail; `created_by`/`actor` are hardcoded `'owner'`). | edge-gateway AuthController.java:37,110-142; V002:36 | Required before any autonomous promotion writes (Prompt 2's approval workflow). |
| T4 | **Env-flag state not snapshotted at decision time.** Live knobs (`ARTHA_*`, relative-vol floor) leave no per-signal record; only rejections record the effective threshold tested. No flag-change ledger (the #653 name-mismatch class is invisible too). | V015:19-21 vs signals writes | Forward-paper evidence can't be stratified by flag regime after the fact — a direct blocker for tune-on-live workflows. |
| T5 | **Wall-clock emission time missing** on signals (see L3) and batch per-candidate decisions are log-only. | SignalEngine.java:846-851; SwingBatchRecorder | Latency + batch forensics gaps. |
| T6 | Marker-write failure on the swing batch path is WARN-only (batch ran, marker missing ⇒ next-morning canary false-alarms/blind spots). | SSS/swing/SwingBatchRecorder.java:59-61 | Minor; make marker write fail loud or retry. |

---

## 6. Reproducibility and versioning audit

### Verdicts per artifact

| Artifact | Verdict | Basis |
|---|---|---|
| Backtest run (job pipeline) | **PARTIAL** | Config fully pinned (version UUID + SHA-256 checksum into `jobs.request`, re-verified on read — BT/jobs/JobsService.java:84-86; SSS/registry/RegistryService.java:580-588); universe pinned by copy + checksum (JobsService.java:104-118); seed recorded; replay deterministic. Holes: engine **code** version absent (`engine_version` = strategy semver — BacktestRunner.java:578-580); data store mutable (detect-only `data_hash`); calendar unversioned. |
| Live signal | **PARTIAL** | Exact published config pinned (FK + checksum) + frozen breakdown. Not replayable-from-DB: input candles mutable, 3m is read-time, env knobs unsnapshotted, REST dot inputs not raw-persisted outside chain snapshots. |
| Paper trade | **PARTIAL** | Fill audit columns exist (`fill_simulator`, `slippage_applied`); close reasons; signal attribution (V026, H5 fix). Breaching tick not durable; bid/ask null. |
| Optimizer trial | **REPLAYABLE** (modulo data-store caveat) | Params + foldContext pinned per trial job; seeded sampler → reproducible trial sequence (OPT/optuna_runner.py:60-78); version resolved once at submit (OPT/service.py:196-227); trial→run linkage; resumable (V004 study replay). |
| Swing deep backtest (marketdata pipeline) | **NOT reproducible** | `minervini/manas_arora_backtest_runs` = `from_date` + report JSONB only — no params, no seed, no data hash, no code version, no trades (V035/V037). **This is the pipeline the swing doctrine decisions (#556/#557, pyramiding verdicts) were made on.** |

### Findings

| # | Finding | Impact |
|---|---|---|
| R1 | **Engine code identity absent from run rows.** Jars change weekly; goldens pin only 9 candle-path features — options/swing behavior changes pass silently between runs with identical metadata. Build-info/git.properties plumbing already exists (#617) but is never copied into `backtest_runs`/`jobs`. | The first thing a longitudinal optimizer trips over: strategy effect vs engine change is indistinguishable. |
| R2 | **Mutable market data with detection but no restoration.** `data_hash` = per-series (keys, interval, window, barCount, max `fetched_at`) — a freshness proxy, not a content hash; over-sensitive because `fetched_at = now()` is re-stamped on value-identical conflict writes (CandleRepository.java:42, 84). | Flagged runs can never be reproduced; hash cries wolf after tail re-fetches. Needs both a content-stable hash *and* a re-run policy (§11). |
| R3 | **Swing pipeline lineage ≈ zero** (see verdict table). | Port swing sims into the job pipeline or give them the same run-row treatment (params/seed/hash/engine SHA/trades). |
| R4 | Optimizer trial jobs leave `strategy_version_id` column NULL (version string only inside request JSONB; recovered by re-resolve at run time) — a soft-link inconsistency. Cross-schema `strategy_version_id` refs are soft by design (D10); hard-deleting a drafts-only strategy CASCADE-deletes versions and strands run rows (dangling UUIDs). | Join-graph landmines for Prompt 2's experiment store; document + guard (forbid hard-delete when runs exist, or tombstone). |
| R5 | Optimizer sweeps run on an **in-process daemon thread with an in-memory cancel set** — a restart abandons running sweeps silently (backtest jobs recover via Redis PEL; sweeps do not). Boot recovery marks orphans failed (OPT/repos.py:66-77) — but mid-sweep state is lost. | Durability gap for long autonomous sweeps — exactly Prompt 2's workload. |
| R6 | Calendar/timezone inputs unversioned (bundled CSV rides the jar; horizon canary guards the cliff only). | Low urgency; record a calendar version string per run. |
| R7 | Versioning that works well (keep): immutable `strategy_versions` with byte-preserved YAML + canonical JSONB + checksum; publish demotes previous; rollback = copy-forward; server-side diff; optimizer promotion → **draft only**, never auto-publish (OPT/service.py:381-414). | This is the exact strategy/parameter versioning substrate Prompt 2 needs — reuse as-is. |

---

## 7. Frontend usability audit

Router: FE/App.tsx; nav: FE/components/MegaMenu.tsx (9 sections, ~60 pages). Full route
map in the agent inventory; research-relevant capability matrix:

### Strong today

- **Jobs UX**: Postgres-backed table, **live WS progress** (`/topic/jobs/stream`),
  per-row cancel, status/strategy/tag/latest-only filters, server-side sort+pagination,
  compare picker 2–6 (FE/pages/backtests/JobsPage.tsx).
- **Results drill-down works end-to-end**: metric tiles + equity/drawdown/benchmark →
  trades (exit-reason breakdown, capped 1000) → per-trade detail with contributions →
  "View on chart" overlaying entry/exit marks (BacktestResultsPage.tsx; ChartsPage.tsx:81).
  Folds, Monte Carlo, OI-attribution tabs.
- **Compare page**: up-to-6 matrix, best-per-row highlight, dataHash/universe mismatch
  banners, rebased-100 equity overlay (BacktestComparePage.tsx).
- **Sweep detail**: trial scatter, plateau/raw guard-aware leaderboard, pruned/failed
  flagged not hidden, fold drill-down by regime, promote-to-draft (SweepDetailPage.tsx).
- **Decision traces**: signal reasoning breakdown (gauge + stacked contributions +
  recursive gate checklist); rejections page with rail-by-rail checklist + dots + raw
  context + shadow league strip.
- **Version management**: immutable timeline, structured diff + side-by-side YAML,
  publish dialog with advisory stress window, rollback (StrategyVersionsPage.tsx).

### Findings

| # | Finding | Evidence |
|---|---|---|
| F1 | **No backtest-vs-paper comparison view** — the single most important research↔live surface is absent. Graduation scores paper vs *thresholds*; shadow league compares live variants vs each other; neither overlays a strategy's backtest expectation against its live paper record. | GraduationPage.tsx:16; RejectionsPage.tsx:122 |
| F2 | **No clone, no rerun.** No strategy clone/duplicate anywhere; JobsPage has cancel only — no "rerun this job" / "duplicate with params". `useArchive` is defined but wired into no page (dead code). | grep-confirmed; FE/api/strategies.ts:187 |
| F3 | **No export anywhere in research surfaces.** CSV/JSON download exists only in Data-Ops (SQL console + contract exports). Backtest results/trades/folds/MC/compare/signals/rejections/graduation: none. | FE/api/dataops.ts:242 (only `createObjectURL` hits) |
| F4 | **Failed-job diagnosis is thin.** `JobDto.error` typed but never rendered; a failed backtest is a red badge with no "why"; no backtest engine log surface (Data-Ops has a LogFeed; backtests don't). | FE/api/backtests.ts:33; JobsPage.tsx |
| F5 | **No saved views / no run tags.** Filters exist but no persistence of filter sets; jobs have no tag/name/notes (backend gap too, §8). | localStorage grep: theme/chart prefs only |
| F6 | **No guided research→live promotion workflow.** Publish dialog, graduation board, risk arming, and paper books are disconnected pages; no checkpointed flow (graduate → publish → arm → watch). | StrategyVersionsPage.tsx:181; GraduationPage.tsx |
| F7 | **Two parallel backtest UX systems**: job-based `/backtests/*` vs bespoke `/equity/manas-arora/backtest` swing runner with its own Run/status semantics. | ManasAoraBacktestPage.tsx | 
| F8 | **No confirm-before-submit** on backtest/sweep launch (fires on click, navigates away). | BacktestRunnerPage.tsx |
| F9 | **Mobile (≈480 px) partially met**: shells/cockpits reflow, but dense research tables (trades 9-col, sweep leaderboard, versions diff, rejections 10-col) are raw tables with horizontal scroll; DataTable's card mode not applied to them. | e.g. BacktestResultsPage.tsx tables |
| F10 | Inconsistent live-data patterns: WS push for signals+jobs; polling for sweeps/paper/orders/scalper at varied intervals; cockpit hand-rolls `setInterval`. Sweep progress notably lags (poll) despite sharing the jobs pipeline. | FE/api/optimizations.ts:97 |
| F11 | Editor is a plain YAML textarea (Monaco deferred); no unsaved-changes route block (only `beforeunload`). | StrategyEditorPage.tsx:19-59 |
| F12 | No margin/exposure-heat *view* for paper books (`GET /paper/margin-heat` exists; no page renders it beyond cockpit chips). | agent route sweep |

---

## 8. Backend workflow and API audit

### Strong today

- **Job spine is production-grade**: Postgres-authoritative `jobs` table, Redis Streams
  dispatch (`jobs.backtest`, consumer groups, conditional claim = exactly-once, PEL
  crash recovery), bounded worker pool (cores−2), integer progress → WS
  `/topic/jobs/{id}`, cancel for queued *and* running (BT/jobs/JobsService.java,
  BT/dispatch/WorkerPool.java).
- **Endpoint hygiene**: typed records everywhere (MapReturnRatchetTest freezes the Map
  count), `{items:[…]}` envelope, server-capped limits, contract snapshots + TS gen +
  breaking-diff CI gate.
- **Auth**: Argon2id owner login, Redis sessions, strict CSP/XSRF, deny-by-default,
  route allowlist with test (`GatewayRouteAllowlistTest`), read-only SQL console
  properly guarded.
- **Registry lifecycle API** is complete (draft/publish/rollback/archive/diff/validate)
  and the optimizer promotes to **draft only**.

### Findings

| # | Finding | Evidence |
|---|---|---|
| A1 | **Optimizer durability + discoverability**: sweeps run on an in-process daemon thread, in-memory cancel set, **no `GET /optimizations/jobs` list** — running sweeps die silently on restart and are unlistable after submission. | OPT/service.py (threading), api.py |
| A2 | **No run tagging/naming/notes on jobs**; `purpose` is a fixed enum. Strategies have tags; runs don't. | BT/jobs schema |
| A3 | **No compare endpoint** — closest are `GET /backtests/summary?strategyVersionIds` (latest-per-version) and sweep `/best`; no compare of N arbitrary runs (FE compare page assembles client-side from per-run fetches). | BT/replay/ResultsController.java |
| A4 | **No artifact/file export API** for backtests/optimizer (JSON payloads only); CSV export exists solely in market-data admin. | ExportController (MDS only) |
| A5 | **No job-completion webhooks/SSE** (STOMP progress only; ntfy/Telegram cover signals/ops, not job terminal states). | notifier wiring |
| A6 | **No auto-retry** for failed jobs; no submission concurrency/queue-depth cap beyond pool size + `maxTrials≤1000` (a runaway sweep floods the shared pool that also serves interactive backtests). | WorkerPool.java |
| A7 | **No general mutation audit** (risk settings audited; strategy publishes audited; backfills/resets/paper mutations carry `correlationId` in logs only). | RiskService.audit vs rest |
| A8 | **No API tokens / non-interactive principal** — session-cookie auth only. Fine for a single owner, but Prompt 2's orchestrator (and any external scheduler) needs a first-class programmatic credential rather than replaying login+XSRF. | SecurityConfig.java |
| A9 | Single global rate-limit bucket (50 rps/100 burst) — an aggressive orchestrator can starve the UI. Per-principal buckets once A8 lands. | gateway application.yml |
| A10 | Inline preflight+auto-warm on `POST /backtests/run` can hold the request up to the 300 s gateway timeout — submission should enqueue the warm too (async), returning immediately. | JobsService.submit |

---

## 9. Missing screens, controls, and decision-support views

Concrete build list (each names its data dependency; ⚑ = depends on a §10 gap fix):

1. **Backtest-vs-Paper parity view** (per strategy/version): overlay backtest equity
   & trade markers against the paper book's record for the same period; per-trade
   matched pairs (signal → sim fill vs paper fill, entry/exit deltas, cadence-attributed
   exit differences); summary drift metrics (fill delta bps, exit-timing delta, PnL
   attribution sim-vs-paper). ⚑ needs L2/L4 order+quote capture to be fully honest;
   ship v1 on current data with disclosed caveats.
2. **Run detail "why" panel**: render `JobDto.error`, engine caveats[] (already in
   metrics JSONB), preflight/auto-warm log, `oiGateCoverage`, dataHash + engine SHA ⚑R1,
   premium provenance, relax flags — one screen answering "what exactly did this run do".
3. **Experiment browser**: runs-as-experiments — tag/name/notes ⚑A2, saved filter views
   ⚑F5, grouping by strategy/family/param, bulk-select → compare, archive noise.
4. **Sweep list page** ⚑A1 (+ WS progress parity with jobs).
5. **Promotion workflow screen**: graduation gate → publish (with stress-window result)
   → arm flags → watch (first-N-days paper vs backtest) as one checkpointed flow with
   explicit approvals; every step writes an audit row ⚑T2/T3.
6. **Data-quality dashboard** ⚑D8: per-symbol daily completeness (1m/1d/bhavcopy/chain
   capture), CA events feed, purge/re-fetch events, calendar horizon, capture-outage
   holes; drill to Data-Ops backfill actions.
7. **Order-event timeline** (per paper/live position) ⚑L2: NEW→FILLED→CLOSED with
   wall-clock stamps, quote context, latency ⚑L3.
8. **Export controls everywhere** ⚑A4/F3: trades/results/folds/MC/compare/signals/
   rejections → CSV/JSON.
9. **Rerun/clone controls** ⚑F2: "rerun job", "duplicate run with edited params",
   "clone strategy".
10. **Live parity/health strip on Signals + Cockpit**: coarse-bar integrity canary
    (post-§3.1 fix), consumer gap detector state, per-book heat (margin-heat endpoint
    already exists, F12).
11. **Confirm-before-run dialog** with an input summary (F8) — cheap, prevents
    fat-finger sweeps.

---

## 10. Gaps that must be fixed before serious optimization

Ordered; each has an unambiguous done-check. P0 = invalidates or poisons optimization
inputs; P1 = required for trustworthy comparisons; P2 = required for scale/ergonomics.

| Pri | Gap | Fix sketch | Done-check |
|---|---|---|---|
| **P0-1** | §3.1 partial-bucket live poisoning | Completed-bucket read contract (server clamp `to`→bucket floor for rolled/cagg intervals, or per-bucket `complete` filter) + `EngineSeries` last-bar replacement; regression IT + live canary (3m bar volume vs 1m-sum for the same bucket) | Canary green across a full session; live 3m volumes ≈ 3×1m; re-run of the 125k-floor forensics passes |
| **P0-2** | R1 engine identity on runs | Copy git SHA + image tag from build-info into `jobs` + `backtest_runs` (new columns, additive migration); surface in results API + run-detail panel | Every new run row carries the SHA; two runs across a deploy are distinguishable |
| **P0-3** | R3 swing-pipeline lineage | Either port swing sims into the job pipeline (preferred end-state) or add run rows: params, seed, engine SHA, data window hash, per-trade table | A swing doctrine number can be mechanically reproduced from its run row |
| **P0-4** | D1 live screener CA adjustment (prior-audit H6) | Apply `eod_corporate_actions` ratios in screener/regime/geometry SQL (join or materialized adjusted view); parity-check screener plane vs engine plane on known split names | Screener RS/52wk gates agree with adjusted candles on a split-name fixture set |
| **P0-5** | B1 BTST exit simulation | btst branch opens a tracked position; next-session exit evaluation (gap-at-open fill semantics defined and recorded); goldens extended parity-safely (side-channel) | A BTST golden with entry+exit; BTST backtest P&L ≠ end_of_data artifacts |
| **P1-1** | R2 dataset comparability | (a) stop re-stamping `fetched_at` on value-identical writes; (b) add a content-stable series hash; (c) codify the re-run policy: optimizer memory keyed on (strategy checksum, engine SHA, data hash) — stale hash ⇒ re-run before ranking | Tail re-fetch no longer flips `data_hash`; ranking code refuses cross-hash comparisons |
| **P1-2** | B4 costs knob + instrument class | Wire `request.costs`/YAML costs; derive instrument class from the resolved series (futures vs equity vs options) | Futures-signal run shows CTT/₹20-cap; costs block visibly echoed in run detail |
| **P1-3** | B11 exit-reason attribution (candle path) | Thread `ExitDecision.type` through `SignalEvent`/`Trade` via the frozen-writer side-channel; persist | `backtest_trades.exit_reason` ∈ {stop_loss, trailing_stop, take_profit, square_off, time_stop, signal_exit} on candle-path runs; goldens byte-identical |
| **P1-4** | L2 order-event model (paper first) | `paper_order_events` (or widen `paper_orders`): NEW/FILLED/REJECTED/CANCELLED + wall-clock + reference-price source/age; persist OpenAlgo acks when armed | Every paper fill has a lifecycle trail; LiveOrderService acks persisted |
| **P1-5** | L4 quote capture at fill | Stamp `quote_bid`/`quote_ask` (chain/quote read) on open+close; feed `quotedSpread` into `LtpSlippageV1` live and (from expired-chain snapshots where available) in backtest | Null-rate of bid/ask on new fills = 0; option slippage varies with spread |
| **P1-6** | L5 stale-tick guard on exits | Bracket evaluator: max tick age (e.g. 90 s) → escalate (ntfy + mark position `STALE_TICK`) instead of silent hold; kill the breakeven `avgEntryPrice` fallback (mark UNSETTLED + alert) | Synthetic stale-tick IT shows escalation, not silence |
| **P1-7** | T4 flag/config snapshot at decision time | Stamp effective env knobs (relative-vol params, pyramid flag, risk toggles) into `scalper_detail`/side-channels on FIRING signals + a tiny append-only `flag_state` ledger on change | Any signal row reconstructs its knob regime |
| **P1-8** | L6 accepted-signal context symmetry | Persist the same diagnostic context block on accepted entries as rejections (or a sampled/compressed variant) | Fired-trade forensics reconstruct decision inputs without logs |
| **P1-9** | B7 daily-context lookahead | Gate context advancement on bucket END ≤ current bar time for context intervals coarser than the primary | Golden added; a 1d-context strategy no longer sees today's close intraday |
| **P2-1** | A1 optimizer durability + listing | Persist sweep loop state (DB-backed ask/tell checkpoints or move orchestration onto the jobs table); add `GET /optimizations/jobs` | Kill the optimizer mid-sweep → restart resumes or fails loudly; sweeps listable |
| **P2-2** | A2/F5 run tags/notes/saved views | `jobs.tags[]`, `jobs.note`; saved-view table keyed to owner | FE filter sets persist; runs taggable at submit + after |
| **P2-3** | A4/F3 export | Per-run CSV/JSON export endpoints (trades, folds, equity, compare matrix) + FE buttons | Download works for the 4 core artifacts |
| **P2-4** | D8 data-quality artifact | Nightly per-symbol completeness report (1m gaps, 1d/bhavcopy presence, chain-capture holes) persisted + dashboard (§9.6) | A seeded gap appears in the next report |
| **P2-5** | L3 latency instrumentation | Wall-clock stamps: bar-publish→eval-start→emit→paper-fill; persist emit wall time on signals; Prometheus histograms | p50/p95 tick→signal→fill visible; §17.3 gate implementable |
| **P2-6** | D5/D6 dividends + PIT constituents | Ingest dividend cash flows (bhavcopy CA feed carries them; parser currently discards) as a separate table (do NOT mutate prices); implement the constituents fetcher (V008 design already PIT) | Total-return metric available as an overlay; membership history accrues |

Explicitly **not** blocking Prompt 2 (accepted, must stay disclosed as run metadata):
survivorship depth limits (D2 — disclose per run), derived-OI mutedness, no partial
fills/limit orders (B10), swing same-close fill convention (B8 — but add an
open-next-day sensitivity variant so the optimizer can price it).

---

## 11. A target architecture for accurate research and execution

Evolution, not rewrite. The stack stays (Spring Boot + Timescale + Redis + FastAPI +
React are all pulling their weight; CI, contracts, and the parity firewall are assets).
Seven additions:

### 11.1 Run provenance block (the spine of everything else)
Additive columns/JSONB on `jobs` + `backtest_runs` (and the swing run tables):
```
provenance {
  engineGitSha, imageTag,              // from build-info (exists at runtime, #617)
  engineLibVersion,                    // strategy-engine artifact version
  calendarVersion,                     // bundled CSV identifier
  fillModel {timing, slippageSource},  // NEXT_OPEN/AT_CLOSE, tick|spread
  costModel {class, source},           // equity|futures|options, defaults|request
  dataHash, contentHash,               // freshness + content-stable
  universe {id, checksum, survivorshipNote},
  forks [relax_session, oi_confluence_gate, strike_premium_band, ...],
  flagsSnapshot {…}                    // ARTHA_* effective values
}
```
Same block, smaller, stamped on firing signals (side-channel) and paper fills.

### 11.2 Completed-bucket read contract (data plane)
One rule, enforced server-side: **rolled/cagg interval reads never return the
in-progress bucket unless the caller passes `includePartial=true`** (charts want it;
engines never do). Plus a `complete` semantic for the current 1d bar. This fixes §3.1
at the source and protects every future consumer, including intraday-run backtests.

### 11.3 Dataset epochs (pragmatic PIT — not a bitemporal rewrite)
Do **not** bitemporalize the 1m hypertable (size). Instead:
- **Content-stable hashes** per consumed series (P1-1) + provenance block.
- **Epoch ledger**: a small `dataset_epochs` table — a row whenever a mutation class
  runs (CA purge/re-fetch, authoritative backfill over a window, bhavcopy long-tail
  fill), recording scope (symbols, window, reason, job link). Runs record the epoch
  head at execution. Comparability = same epoch scope untouched; the ledger makes
  "what changed between run A and run B" a query instead of an inference.
- **Snapshot exports for keystone studies** (optional, cheap): the swing deep sims can
  dump their exact input panel (parquet/CSV per run) to disk — bounded size at 1d
  granularity — making doctrine decisions literally re-runnable. (A DuckDB/parquet
  side-store is the one *optional* tech addition worth considering; it changes nothing
  in the serving path.)
- **Re-run policy** codified where ranking happens: never rank across differing
  (engineSha, contentHash, universe checksum) without re-running the stale side.

### 11.4 Unified experiment/event read layer
Not a new service; a read-side contract inside backtest-service (+ SQL views):
- `experiment` = strategy_version × run(s) × trials × (optionally) paper window.
- Views codifying the join graph: version→runs→trades; version→signals→paper;
  rejection→shadow; **materialize the signal→book attribution at write time** (persist
  `book` on the signal row; stop deriving from mutable tags — T1).
- `GET /api/v1/experiments?…` list/filter/tag; `GET /api/v1/experiments/compare?runIds=…`
  server-side matrix (replaces client-side assembly, gives Prompt 2 one call).

### 11.5 Order-event + execution-quality layer
`paper_order_events` (P1-4) with wall-clock stamps + quote context (P1-5) + latency
histograms (P2-5). The same table shape serves the dormant OpenAlgo live path — acks,
rejections, cancels persist to the identical schema, so paper vs live execution quality
becomes one query. A nightly **fill-quality reconciler** compares each paper fill to the
captured chain/candle at fill time and writes `fill_quality` rows (achievable? delta
bps) — the honest input for slippage-model calibration.

### 11.6 Research→live promotion workflow (state machine, not pages)
`strategy_stage` transitions (RESEARCH → PAPER → TAKE_ELIGIBLE → LIVE-ARMED) with:
required evidence per transition (graduation thresholds, backtest-vs-paper parity
within band, stress-window pass), explicit owner approval rows (T3 actor trail), and
flag-arming as an audited action instead of a raw `.env` edit where feasible. The F7
graduation machinery (V024, measurement-only) is the seed; this formalizes it without
auto-arming anything.

### 11.7 Async + automation hardening
Optimizer orchestration durability (P2-1); job-completion notifications (reuse ntfy
plumbing) or SSE; API token principal for headless orchestration (A8) with per-principal
rate buckets (A9); submission-time warm made async (A10).

**Separation of concerns stays as-is**: market-data owns data quality; strategy-signal
owns live+paper; backtest owns research runs; optimizer owns search. Prompt 2's
optimizer builds **on the experiment read layer + jobs API only** — it must not touch
live tables.

---

## 12. Phased implementation roadmap

PR-sized, house merge-policy tiers (clean / HOLD per `fable-method`), each with a
verify check. Phases are dependency-ordered; within a phase, items parallelize.

**Phase 0 — stop the bleeding (defect + provenance) [~1 week]**
1. §3.1 fix: completed-bucket read contract + EngineSeries guard + IT + live canary —
   **HOLD tier** (touches live signal inputs; adversarial review; expect live rejection
   patterns to *change* after deploy — pre-announce to owner). (P0-1)
2. Engine SHA + image tag into jobs/runs + run-detail surfacing — clean. (P0-2)
3. Stop `fetched_at` re-stamp on value-identical writes — clean, small. (P1-1a)
4. Render `JobDto.error` + caveats in Jobs/Results — clean. (F4)

**Phase 1 — data correctness [~2 weeks]**
5. Screener/regime/geometry CA adjustment (H6) — **HOLD** (changes screener output
   numbers). (P0-4)
6. Content-stable series hash + `dataset_epochs` ledger — clean. (P1-1b)
7. Nightly data-quality report + dashboard page — clean. (P2-4, §9.6)
8. Backtest 1d read → native daily (align with warmup path) — **HOLD** (changes
   backtest inputs; goldens/parity rerun). (D7)
9. Dividend cash-flow ingestion (separate table; no price mutation) + PIT constituents
   fetcher — clean, additive. (P2-6)

**Phase 2 — execution realism [~2-3 weeks]**
10. BTST position+exit simulation (goldens extended via side-channel) — **HOLD**. (P0-5)
11. Costs knob wiring + instrument-class costing — **HOLD** (changes net numbers). (P1-2)
12. Exit-reason attribution on candle path (side-channel) — clean (byte-identical
    goldens prove it). (P1-3)
13. Paper order-event model + quote capture at fill + stale-tick escalation + kill
    breakeven fallback — clean→HOLD mix (fallback removal changes P&L accounting;
    review). (P1-4/5/6)
14. Daily-context lookahead gate — **HOLD** (can change historical signals; goldens
    updated deliberately). (P1-9)
15. Swing open-next-day fill variant in the deep sims (sensitivity, not default) —
    clean. (B8)
16. Swing sims → job pipeline (or full run-row lineage) — **HOLD**, the biggest item;
    do after 10-12 so the pipeline it lands in is already honest. (P0-3)

**Phase 3 — telemetry & experiment layer [~2 weeks]**
17. Flag-state snapshot on firing signals + `flag_state` ledger — clean. (P1-7)
18. Accepted-signal context symmetry — clean (storage cost: consider sampling). (P1-8)
19. Persist `book` on signals (kill read-time tags join) + audit rows for
    enabled/tags/status mutations — clean. (T1/T2)
20. Experiment views + `GET /experiments` + server-side compare endpoint — clean. (§11.4)
21. Latency wall-clock stamps + histograms — clean. (P2-5)
22. Optimizer durability + `GET /optimizations/jobs` — clean. (P2-1)

**Phase 4 — research UX & workflow [~2-3 weeks]**
23. Run tags/notes + saved views (API + FE) — clean. (P2-2)
24. Backtest-vs-paper parity view — clean (v1 on existing data, caveats banner). (§9.1)
25. Export endpoints + FE buttons — clean. (P2-3)
26. Rerun/clone controls; sweep list page; confirm-before-run — clean. (F2/A1-FE/F8)
27. Promotion workflow screen + stage state machine + approval audit — **HOLD**
    (owner-facing process change). (§11.6)
28. Order-event timeline + data-quality dashboard polish; mobile card-mode for the 4
    dense research tables — clean. (§9.7, F9)

Sequencing rationale: Phases 0-1 make the *inputs* true; Phase 2 makes the *simulations*
honest; Phase 3 makes everything *queryable and attributable*; Phase 4 makes it
*operable at research speed*. Prompt 2 can start development against §13 contracts after
Phase 0 + items 5-6, and should not *rank or promote* anything until Phase 2 items
10-12 and 16 are merged.

---

## 13. Inputs required by Prompt 2

Exact artifacts the optimization build must consume. **EXISTS** = usable today (cite);
**ADD** = created by this plan (phase item ref). Prompt 2 must refuse to rank across
runs whose provenance blocks differ (engine SHA, content hash, universe, cost model)
unless it re-runs the stale side.

1. **Strategy definition format** — EXISTS. YAML, frozen schema v1
   (`GET /api/v1/strategies/schema/v1`; `libs/strategy-schema/…/strategy-schema-v1.json`).
   Identity: registry **UUID** (not slug) + semver + SHA-256 `checksum`; immutable
   `strategy_versions` rows (byte-preserved YAML + canonical JSONB) —
   deploy/flyway/strategy/V002. Lifecycle API: draft/publish/rollback/diff/validate
   (SSS/registry/RegistryController.java). Tunable surface: the `backtest.optimize`
   block (`method`, `max_trials`, `objective`, `parameters` — all required for a sweep).

2. **Parameter set format** — EXISTS. `optimize.parameters` (name→range/choices) in
   YAML; per-trial `params_override` JSONB on `backtest_runs` (V003:20) and trial
   `params` on `optimization_trials` (V004); request-side `walkForward`/`objective`/
   `maxTrials` override YAML (OPT/service.py). Promotion contract: trial →
   `POST /optimizations/{sweepId}/promote` → **draft** version.

3. **Run metadata** — EXISTS (partial): `jobs.request` (full pinned submission incl.
   strategyId/Version/checksum, window, pinned universe copy, seed), `backtest_runs`
   (seed, `data_hash`, `universe_checksum`, `premium_source`, fold columns, metrics
   JSONB with `caveats[]` + `oiGateCoverage`). **ADD**: provenance block §11.1
   (engine SHA, flags, cost/fill model, forks) — Phase 0-2; run tags/notes — Phase 4.

4. **Trade event schema** — EXISTS: `backtest_trades` (side, qty, entry/exit ts+price,
   pnl, pnl_pct, `exit_reason`, `bars_held`, `touch_basis`, `contributions` JSONB,
   bracket levels — V003/V005); paper `paper_orders`/`paper_positions` (fill audit,
   close_reason, `opening_signal_id`). **ADD**: candle-path exit-reason attribution
   (Phase 2 #12); `paper_order_events` lifecycle + quote context (Phase 2 #13); swing
   per-trade rows (Phase 2 #16).

5. **Market snapshot schema** — EXISTS: `signals.score_breakdown` (frozen DTO:
   composite = Σ(w·s)/Σw, gate tree with operand values, per-indicator raw/score/
   weight/contribution); `scalper_detail`/`minervini_detail`/`manas_arora_detail`
   side-channels; `signal_rejections.diagnostic` (rails + dots + chart/OI/macro
   context + would-be leg); `options_chain_snapshots` (2-min live chain since
   2026-06-15). **ADD**: accepted-signal context symmetry + flag snapshot
   (Phase 3 #17-18) — until then, decision-input reconstruction is rejection-side only.

6. **Evaluation metric schema** — EXISTS: `backtest_runs.metrics` JSONB + headline
   columns (total_return, CAGR, Sharpe/Sortino @6.5 % rf, maxDD+duration, win_rate,
   profit_factor, expectancy, exposure, trade_count), benchmark block (alpha/beta/IR/
   excess_cagr), fold aggregates (`oos_fold_mean`, `oos_fold_std`,
   `sharpe_degradation`, `fold_metrics`), `montecarlo_summary` (+`mcSeed`),
   `SwingReportCard` grade (`GET /{id}/report-card`). Optimizer guard metrics on
   `/best` (plateau/raw, regime/OOS/fold guards). Prompt 2's objective must consume
   the **guarded** shapes, never raw single-window returns.

7. **Regime tagging schema** — EXISTS: `RegimeLabeler` strictly-T−1 labels
   (BT/regime/), per-fold regime attribution inside `fold_metrics`, fold drill-down API
   (`/trials/{id}/folds`); regime preflight (272-session warmup) as a data-sufficiency
   gate. No separate regime table — labels are computed + embedded per fold; Prompt 2
   should read them from fold_metrics, not recompute.

8. **UI state and experiment metadata** — ADD (Phase 3-4): experiment list/compare
   endpoints (§11.4), run tags/notes, saved views. Today's substitutes: jobs filters
   (status/strategy/latest-only), `purpose` enum, compare page assembling client-side.
   Prompt 2 should target the experiment API, not scrape jobs.

9. **Dataset lineage information** — EXISTS (partial): per-run `data_hash` (per-series
   freshness tuples), `universe_checksum` + pinned universe copy, `premium_source`,
   candle `source`/`fetched_at` provenance, `backfill_jobs` + `corporate_action_events`
   + `roll_events` ledgers. **ADD**: content-stable hash + `dataset_epochs` (Phase 1
   #6); survivorship/universe disclosure carried per run (P0-3/D2). Comparability rule
   as in the preamble of this section.

10. **Result artifact structure** — EXISTS: `GET /backtests/{id}/results` (metrics +
    ~500-pt equity/drawdown/benchmark curves + reproducibility triple),
    `/trades` (paged, capped 1000), `/folds`, `/montecarlo`, `/oi-attribution`,
    `/report-card`, `/summary?strategyVersionIds`; optimizer `/trials`, `/best`,
    `/trials/{id}/folds`. All `{items:[…]}` enveloped, offset-paginated. **ADD**:
    file export (Phase 4 #25) if Prompt 2 wants bulk artifacts rather than API pulls;
    full-resolution equity only via re-run (downsampled persisted — disclosed).

11. **Comparison and ranking output format** — ADD (Phase 3 #20): server-side
    `GET /experiments/compare?runIds=…` returning the matrix the FE compare page
    currently assembles (metrics per run + like-for-like flags: dataHash match,
    universe match, engine SHA match, cost-model match). Prompt 2's ranking output
    should extend this shape (adding score, guard verdicts, refusal reasons) rather
    than invent a new one. Until it exists: `GET /backtests/summary` + per-run fetches.

12. **Live/forward evidence feed** (Prompt 2 will need it even though it optimizes
    offline): signals + rejections + shadow variants + paper trades per strategy
    version (`/api/v1/signals`, `/signal-rejections` + `shadow-summary`, `/paper/*`,
    graduation endpoints) — with the standing caveat that **gate-armed scalpers are
    forward-only optimizable** (live gate absent from backtests) and OI edges are muted
    on derived history; both facts must be machine-readable run/strategy metadata
    (provenance `forks[]` + a strategy-level `evidencePolicy` tag — ADD, Phase 3).

---

*Method note: 6 parallel read-only audit agents (backtest fidelity, live/paper
fidelity, data quality, telemetry/reproducibility, frontend, backend APIs) over the
2026-07-10 working tree at `main`@d477c3f7, cross-checked against the 2026-07-05 full
audit and the forward ledger; the §3.1 defect chain was re-verified by hand in source
before inclusion. No code was changed by this audit.*
