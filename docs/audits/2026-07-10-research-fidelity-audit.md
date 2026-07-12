# Research-fidelity audit & improvement plan — 2026-07-10

**Scope:** backtest/livetest accuracy, backtest↔live mismatch sources, data quality,
telemetry/event model, reproducibility/versioning, frontend usability, backend
workflow/APIs — plus the target architecture and roadmap needed so a follow-up
autonomous-optimization build (referred to throughout as **Prompt 2**) stands on sound
data. Produced by 6 parallel read-only audit agents + hand verification of the one
suspected live defect (§3.1, code-confirmed). Every claim carries a `file:line` or PR#.
A post-draft **2-pass verification** (5 adversarial accuracy agents re-checking every
claim against source + 1 completeness agent vs the commissioning requirements) found
**0 refuted claims**; its ~35 precision corrections and completeness additions are
incorporated in this revision.
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
| Services | 4 × Java 21 / Spring Boot: `edge-gateway`, `market-data-service`, `strategy-signal-service`, `backtest-service` (Modulith on market-data + strategy-signal only; backtest-service deliberately plain Boot) | Multi-module Maven reactor; JaCoCo ≥60 %, Modulith `verify` in CI |
| Optimizer | Python 3.12 FastAPI + Optuna (`services/optimizer-service`; 3.12 in the image + CI, 3.14 only on the dev host) | Trials fan out onto the Java backtest worker pool via Redis Streams |
| Margin appliance | Python FastAPI `services/margin-service` (`ay-margin-service`, internal :8086; deploy/docker-compose.yml:552-568) | Dormant `.spn`-file SPAN calculator (#126) — the designated offline/backtest margin fallback; live SPAN routes through Upstox (#510) |
| Shared libs | `strategy-engine` (the parity core), `strategy-schema`, `black76-math`, `market-calendar`, `common-web` | One engine JAR linked by live + backtest |
| Data | TimescaleDB (pg17) — 4 Flyway lineages (admin / marketdata / strategy / backtest), Redis (streams, pub/sub, sessions) | Hypertables + continuous aggregates; mock/live DB + Redis isolation |
| Frontend | React 19 + Vite 6 + Tailwind v4 + shadcn, Zustand, TanStack Query v5, ECharts + lightweight-charts, STOMP WS | ~80 pages (88 routes); single-owner auth |
| Deploy | Docker Compose, loopback-only gateway, `ay.ps1` CLI | CI: sharded per-service Maven matrix + separate path-filtered optimizer/margin workflows |

**Execution surfaces (who simulates/executes what):**

- **Live tick engine** — `SignalEngine` (SSS/signals/SignalEngine.java): per-strategy Redis
  subscription to `candles.1m.*`, single eval thread, 1m/coarse-primary evaluation,
  live-only `ScalperConfluenceGate` (~30 rails), BTST pre-close clock. Emits signals →
  paper books via `AutoPaperListener`/manual takes.
- **Daily swing batch** — `SwingBatchEngine` + `SwingDoctrine` (SSS/swing/, post-#655):
  Minervini 20:00 / Manas 20:05 IST, entry+exit passes on daily bars, paper entries at
  daily close.
- **Paper execution** — `PaperService`/`PaperBracketEvaluator` (SSS/paper/): 5 seeded
  books — scalper / minervini / manas-arora / manual / other, ₹1.5 L each (V021:30-35);
  fills via the same `LtpSlippageV1` the backtest uses;
  brackets polled every 15 s against Redis last-tick.
- **Shadow book** — `ShadowBookService` (SSS/signals/): every *rejected* scalper entry
  trades virtually (champion + knob-variant challengers) — a gate-tuning counterfactual.
- **Job-based backtest** — `BacktestRunner` → `ReplayEngine` (candle path) or
  `OptionsPremiumReplay` (premium-as-primary), Redis-Streams worker pool, walk-forward
  folds, Monte Carlo (BT/replay/, BT/jobs/).
- **Swing deep sims** — `MinerviniSwingBacktest` / `ManasAroraSwingBacktest` +
  `SwingPortfolio` (MDS/screener/minervini/, MDS/screener/manas/): ~11-year array-based daily sims,
  *outside* the job pipeline and outside the parity firewall.
- **Dormant real execution** — `LiveOrderService` → OpenAlgo `placeorder`
  (SSS/execution/), off by default (`artha.scalper.execution=paper`); acks log-only.

**Parity spine (what keeps backtest ≡ live honest today):** shared
`TickwiseGoldenRunner`/`EntryEvaluator`/`ExitEvaluator`/`FillSimulator`/`PositionSizer`
(LIB), golden vectors (`GoldenDeterminismTest`, byte-identical), `BacktestParityTest`
(replay == frozen live vectors), `contracts/fixtures/exit-equivalence.json` pinned by 3
suites across both services. The shared runner also rolls a **1d primary**
(TickwiseGoldenRunner.java:411, MV-6.2) — the live daily swing engine runs *inside* the
parity engine; only the MDS deep sims sit outside the firewall.

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
  missing premium = loud 422 DATA_GAP, not silent skip. Theta decay, IV moves, and
  greeks are thereby *embedded* rather than modeled — the honest choice given real
  premium data (the unwired `SyntheticPremium` flat-IV Black-76 reconstructor exists
  for gaps but is deliberately not a default).
- **Lookahead protections**: coarse bucket completes only when the next bucket's first
  bar arrives (LIB/golden/TickwiseGoldenRunner.java:156-190); regime labels strictly
  T−1 (BT/regime/RegimeLabeler.java:82-87); warmup enforced; `.toInstant()` map keys
  (#214 lesson).
- **Walk-forward mechanics** are correct: trading-day folds, independent re-replays,
  `min_trades` exclusion, per-fold regime attribution (BT/replay/folds/).

### Findings (ranked by research-fidelity impact)

| # | Finding | Evidence | Why it matters |
|---|---|---|---|
| B1 | **BTST exit is not simulated.** The btst branch evaluates pre-close entries but never opens a tracked position; exit rules never run; replay force-closes at end-of-data. Worse: `legs()` ignores new entries while one is open, so a multi-day BTST run degenerates to exactly **one** trade (first entry → end_of_data). | TickwiseGoldenRunner.java:210-227; ReplayEngine.java:318, :330-344; the btst golden fixture holds 3 consecutive-day entries → 1 leg | Any BTST backtest P&L is meaningless; overnight-gap capture — the whole point of BTST — is untested. Must be fixed before Prompt 2 can optimize BTST variants. |
| B2 | **Swing deep sims are frictionless and close-filled**: entry *and* exit at the signal bar's own close, zero slippage, zero trade-level costs (only a coarse portfolio-level % netting). | MDS/screener/manas/ManasAroraSwingBacktest.java:266-272, 295-306, 361-370; MDS/screener/minervini/SwingPortfolio.java:87-94 | The 11-year CAGR/DD/Sharpe headlines driving swing doctrine carry the loosest execution model on the platform. (Partially disclosed in report notes; still the top calibration risk.) |
| B3 | **No intrabar H/L touch anywhere wired.** All stops/targets/trails evaluate on bar closes (1m floor). `IntrabarExitResolver` (worst-of touch + gap-through-open) is dead code; `BAR_HL_WORSTOF` unreachable (`oneMinuteCovered` hardcoded `true`). | LIB/fills/IntrabarExitResolver.java:24-61 (no prod callers); BacktestRunner.java:203; LIB/eval/ExitEvaluator.java:196-231 | Spikes through a stop that mean-revert within the bar are invisible; systematically flatters tight-stop scalpers. |
| B4 | **`costs` knob is dead + wrong instrument class on the candle path.** Request/schema accept a `costs` block; nothing reads it. Every candle-path run costs as EQUITY DELIVERY even when the signal series is an index future. | BT/jobs/JobsService.java:96-97 vs BacktestRunner.java:202, 241; BT/replay/CostConfig.java:23-31 | Cost mis-specification skews net returns per strategy class; futures CTT/₹20-cap brokerage never exercised by the job pipeline. |
| B5 | **Option-leg liquidity/spread realism missing.** `quotedSpread` is hardwired `null` in both engine paths → option slippage always 1 tick (₹0.05); premiums carry forward stale last-trades for non-traded minutes; no volume-participation cap (the ₹1 `min_premium_inr` floor and `max_lots` are budget guards, not liquidity bounds). | ReplayEngine.java:357; OptionsPremiumReplay.java:804, :724-742; LtpSlippageV1.java:52-68 | Deep-OTM/illiquid strikes report fills the market would not have given. Optimizers *seek out* exactly these unrealistic corners. |
| B6 | **No option expiry settlement.** A leg held past expiry carries the last traded premium until signal exit/end-of-data; no intrinsic settlement, no exercise STT in backtest. | OptionsPremiumReplay (no settlement branch); FeeConstants.java:40 (paper-only constant) | Multi-day option holds crossing expiry mis-report. |
| B7 | **Daily-context lookahead**: 1d context bars are visible with the *full day's* OHLC from that day's first 1m bar (cagg bar stamped at day start; `advanceContexts` appends any bar with `bucketStart ≤` current). | TickwiseGoldenRunner.java:259-274 | A coarse-primary strategy with a 1d context indicator can read up to one future close at decision time — classic leak, currently reachable. |
| B8 | **Swing-sim same-bar signal+fill and inclusive geometry window**: weekly geometry recompute includes the current bar; entry fires and fills on that same close. Live batch mirrors it (also fills at that close), so *paper matches the sim* — but both assume a fill a real order can't get. | ManasAroraSwingBacktest.java:210-214, 266-272; SSS/swing/SwingBatchEngine.java:326 | Consistent-but-optimistic; must be priced (open-next-day variant) before real-money graduation. |
| B9 | **No margin model in backtest.** Long-premium cash budget is the only constraint; the platform's two SPAN capabilities — the live Upstox call (#510) and the dormant `services/margin-service` `.spn` appliance (#126, the designated offline/backtest fallback) — are consumed by neither backtest path. | grep clean in backtest-service; deploy/docker-compose.yml:552-568 | Capital feasibility/leverage of portfolios unvalidated; Prompt 2 needs a margin-feasibility term or it will "discover" un-fundable variants — the appliance is the ready substrate. |
| B10 | **Idealized order layer**: market-at-reference fills only; no limit/SL-M simulation, no partial fills, no rejections, no latency (NEXT_OPEN's one-bar delay is the only proxy). | LIB/fills/FillSimulator.java:9-12; FillTiming.java:8-11 | Acceptable *if disclosed per run*; must be a recorded run property (see §11 provenance block). |
| B11 | **Candle-path exit attribution lost**: `backtest_trades.exit_reason` is only ever `signal_exit`/`end_of_data`; the actual stop/trail/TP/square-off reason never reaches persistence (options path and swing sims *do* attribute). | ReplayEngine.java:303, 338; LIB/golden/GoldenSignalsJson.java:27-35 (frozen writer) | Blocks exit-doctrine forensics and Prompt 2's per-exit-type metrics on candle-path runs. Fix parity-safely via the non-serialized side-channel pattern. |
| B12 | **No decision-trace/rejected-entry persistence for backtests** — nothing like live `signal_rejections` exists for a run; only closed-trade `contributions` survive. | ReplayResult.signals unpersisted | "Why did the backtest not trade on date X" is unanswerable from stored data; optimizers need rejection counterfactuals. |
| B13 | **Swing results keep no trade rows** — one aggregate report JSONB per run. | deploy/flyway/marketdata/V037:1-17 | Per-trade re-analysis requires a re-run; no mechanical diffing of doctrine changes. (Also a §6 lineage hole.) |
| B14 | Survivorship-biased swing universe (disclosed in the report string). | ManasAroraBacktestService.java:386-389 | See §4.1 — data-layer root cause. |
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
  (`artha.manas-arora.backtest.*`, ManasAroraBacktestService.java:177-197) — a
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
   deploy/flyway/marketdata/V004; the V029 1h recreate keeps `materialized_only=false`).
4. `EngineSeries.append` is strictly increasing; the *completed* version of a
   previously-appended bucket throws and `appendQuietly` swallows it —
   LIB/series/EngineSeries.java:55-61, LiveSeriesStore.java:83-91. **The partial is
   frozen forever** (until a service restart re-warms history).

**Consequence:** after warm-up, a live coarse-primary series ≈ a chain of first-minute
slices — ~⅓ (3m) to ~1/15 (15m) of true volume, truncated highs/lows, closes sampled
one minute into each bucket — while the backtest's `TickwiseGoldenRunner` rolls full
buckets. Every live 3m scalper evaluation and every 5m/15m/1h gate series is affected;
higher-TF refreshes go through the same path (SignalEngine.java:645-649).

**Corroboration:** the 2026-07-02 session forensics measured live-evaluated bar volumes
(rejection operand avg ≈ 5.1k) far below the same session's full 1m→3m rolled
distribution (p50 ≈ 12,350) — consistent with ~1-minute slices
(`docs/signal-analysis/2026-07-02-session-findings.md` §2.1). Note the 125k floor
itself was unpassable even on *full* 3m bars (session max ≈ 116.9k) — a separate
calibration defect. The relative volume floor (#605) partially masks this one
(partials compared against a partial-derived average). Evaluation happens on the
series' **last** bar (`evaluateAtBarClose` reads `primary.size()-1`,
SignalEngine.java:541-542) — i.e. the just-opened bucket's partial itself, not even
the previous frozen partial.

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
  refusal at load (SignalEngine.java:244-249). A **Telegram bot** is a second mutating
  control surface — `/pause` `/resume` `/flatten` with two-phase `/confirm`-within-60s
  and per-command audit rows (SSS/telegram/TelegramCommandBot.java:131-177; V019) — a
  non-UI actor can flip the kill switch or flatten books, with better confirm
  ergonomics than the UI (prior-audit H7 was the reverse).

### Findings (ranked; L1 = §3.1 above)

| # | Finding | Evidence | Why it matters |
|---|---|---|---|
| L2 | **No order-event model.** `paper_orders` inserts directly as `FILLED` (`placed_at = filled_at = now()`); NEW/ACK/PARTIAL/REJECT unrepresented (CANCELLED is in the schema enum but unreachable — no cancel path exists). Real OpenAlgo acks (when armed) are **logged only, never persisted**; no cancel/modify path, no fill-confirmation loop. | SSS/paper/PaperOrderRepository.java:42-76; V005:18-19; SSS/execution/LiveOrderService.java:72-75 | Execution friction can't be modeled, audited, or reconciled. Prerequisite for any real-money path and for Prompt 2's fill-realism calibration. |
| L3 | **No decision-time wall clock, no latency instrumentation.** `signals.generated_at` = bar bucket instant by design; signal-emit wall time recorded nowhere on the signal record (`notification_events.created_at` incidentally proxies it for alerted signals only); tick→signal→fill latency unrecoverable from persisted data. The master-plan §17.3 latency gate is plan-only. | SignalEngine.java:846-851; metrics are durations only | Live-arm decisions and slippage models need measured latency; currently unmeasurable. |
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
NSE/BSE bhavcopy ─→ nse_eod_bhavcopy / bse_eod_bhavcopy (raw; ON CONFLICT DO UPDATE = last-writer replace)
                 ─→ candles@1d projection [DO-NOTHING = effectively write-once, source='BHAVCOPY']
                 └→ screeners + RegimeService read the RAW tables directly
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
| D1 | **Live screener plane reads CA-unadjusted bhavcopy while backtests read broker-adjusted candles** (prior-audit H6, confirmed + broader). Screener SQL never joins `eod_corporate_actions` though ratios exist in-DB; the same raw reads feed Manas geometry (`DailyBarReader`). `RegimeService` breadth also reads raw but is largely insulated (same-row `close_price > prev_close` against the exchange-published ex-adjusted prev close). | MDS/screener/minervini/TrendTemplateService.java:100-111; ManasScreenService.java:109-114; RegimeService.java:23-31 | A split/bonus inside the 420-day window craters c63–c252 returns and strands the 52wk-high pre-split → momentum leaders (where bonuses cluster) silently fail RS/proximity gates for up to a year. The *live funnel excludes names the backtest happily trades* — a hidden live-vs-sim divergence no canary watches. |
| D2 | **Survivorship bias is structural.** Swing backtests/hit-rate scan today's `instruments` EQ ∩ deep-candle symbols; the master accrues only since ~2026-06; delisted names absent; RS percentile computed over survivors. | MDS/screener/minervini/MinerviniBacktestService.java:717-724, :405; MinerviniHitRateService.java:250 | 43 %/23 % CAGR headlines exclude the delisted-midcap cohort; live forward CAGR structurally undershoots sim. Mitigated by disclosure + "judge on forward paper" — but Prompt 2 must carry universe metadata per run and never rank across universes. Also undefined: an open swing hold through a suspension/delisting — no delisting event source exists, and the live exit pass silently skips a symbol with no fresh bar (prior-audit M5 class). |
| D3 | **Point-in-time correctness is convention + tripwire, not storage.** Broker history is back-adjusted in place (price *levels* embed future split knowledge — ₹-level gates and turnover floors read post-hoc scales; volume never adjusted so `close×volume` is off by the ratio pre-split). Re-fetch REPLACES; CA job purges+rewrites whole symbols. `data_hash` detects drift, cannot restore. | CandleRepository.java:62-113; CorporateActionJob.java:233-263; BT/replay/DataHash.java:10-31 | Two runs weeks apart are not comparable after any CA/backfill event; the optimizer's memory decays invisibly except for a hash flag. |
| D4 | **Mixed-provenance 1d series in direct-SQL reads.** `candles`@1d interleaves broker-adjusted (KITE/BACKFILL) and raw (BHAVCOPY) bars; the source-aware adjuster exists only on the REST path; backtest/hit-rate read raw SQL. | CandleRepository (per-bar source); EquitySplitBonusAdjuster.java:18-44; MinerviniBacktestService.java:735-766 | Around any CA, adjacent bars sit at different scales → phantom gaps/spikes through ATR stops, SMA slopes, pivots. |
| D5 | **Dividend blindness.** CA parser explicitly discards dividends; no cash-flow table anywhere; returns are price-only. | MDS/bhavcopy/CorporateActionSubjectParser.java:10-19 | Multi-month swing holds understate total return; ex-dividend drops read as adverse moves to 2×ATR/Chandelier exits — a real (small) doctrine distortion. |
| D6 | **Point-in-time index membership missing.** `index_constituents` (V008) is an append-only PIT design but the live fetcher is a placeholder returning empty; Futures OI Buzz uses a static factsheet JSON. | MDS/constituents/PendingLiveIndexConstituentsFetcher.java:10-29; StaticIndexConstituents.java:14-20 | RS-rank/backtests have no membership history to bound the universe; static list silently ages. |
| D7 | **Backtest 1d context/benchmark reads serve the sparse cagg, not native 1d.** `CandleReader.read('1d')` → `candles_1d` cagg (1m-derived, sparse on a fresh boot) while warmup/regime read native. A 1d *primary* is unaffected by this split (it reads 1m and rolls up — TickwiseGoldenRunner.java:411) but is equally thin on a fresh stack via the 1m base. | BT/replay/CandleReader.java:36 vs :96-127; BacktestRunner.java:491-500 (1d contexts); BenchmarkAnalyzer.java:69 | 1d context indicators and benchmark analytics can read a thinner series than the chart/screener shows — confusing, occasionally wrong. |
| D8 | **No automated daily data-quality artifact.** Pieces exist (canaries, 15:45 gap-audit pass, coverage summary, backfill ledger) but no scheduled per-symbol completeness/row-count report; nothing audits per-symbol bhavcopy presence day-over-day. | MDS canary/backfill packages | A symbol silently missing from one day's bhavcopy file is invisible; screener/regime inputs quietly thin. |
| D9 | **No per-bar completeness flag on `candles`** (`complete` exists only on `expired_contracts`); in-progress-bar staleness is a read-time rule invisible to direct-SQL consumers. | V026 vs candles DDL | Root enabler of §3.1; also lets backtests ingest a partial current-day bar if run intraday. |
| D10 | **Options capture outage healing is manual** (`OiBackfillService` flag-gated); a multi-hour capture outage leaves a permanent snapshot hole (2026-07-09 outage class). | MDS/backfill/OiBackfillService.java:33-53 | Forward-paper OI evidence (the designated discriminator) develops silent holes. |
| D11 | Depth/coverage bounds (accepted, must stay disclosed): bhavcopy ~1y broad; Upstox equity ~200-session; ~11y only for the subscribed subset; calendar bundle 2024–2026 (horizon canary, CD-2 refresh due before ~2026-11-16); BSE holiday list = NSE approximation; Muhurat unmodeled. | libs/market-calendar resources; MEMORY | Windows outside coverage 500 loudly (good); BSE expiry edge is a small standing risk. |
| D12 | **The hit-rate validation panel runs on a different price basis than the screen it validates**: `MinerviniHitRateService` reads `candles`@1d (mostly Kite-adjusted) while the live TrendTemplate screen reads raw bhavcopy. | MinerviniHitRateService.java:24, :109-113 | The forward-return evidence backing the screener gates was measured on prices the live screen never sees — the screener's own validation loop inherits the D1 asymmetry. |
| D13 | **Time fuse on D2/D4**: the deep-sim membership floor is `MIN_SERIES=260` bars while bhavcopy-only names now carry ~250 sessions and accruing. | MinerviniBacktestService.java:55 | Within weeks ~2k RAW-priced BHAVCOPY-only names cross the floor and enter the RS cross-section alongside Kite-adjusted names — D4 escalates from a per-symbol CA edge case to a universe-wide ranking distortion. Fix D4/D1 before the fuse burns. |
| D14 | **Bhavcopy restatements silently diverge from their candle projection** (raw table DO-UPDATEs; projection DO-NOTHINGs; the CA job skips BHAVCOPY-only symbols), and **BSE-only listings never get CA ratios** (ratio sync is NSE-keyed, ISIN cross-applied). | NseEodBhavcopyRepository.java:45-53 vs CandleRepository.java:115-121; CorporateActionJob.java:162; BhavcopyBackfillService.java:376-445 | Exchange corrections never reach research reads; a BSE-only name's "adjusted" reads are silently raw. |

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
| T2 | **Un-audited mutations on lineage-bearing rows**: tag edits, notification toggles, and `signals.status` transitions (ACTIVE→TAKEN/DISMISSED/EXPIRED) are in-place UPDATEs with no audit action — the 15:45 `expireAllActive()` sweep is the highest-volume un-audited mutator (one bulk subquery UPDATE, only a row count returned). `strategies.enabled` is stranger: **no application mutation path exists at all** (raw SQL only — no repo UPDATE, no endpoint, no FE toggle; Telegram `/pause` flips the risk kill_switch, not enabled). | SSS/registry/RegistryService.java:374-412; SignalRepository.java:179-188, :219-225; V003:20-21 | Evidence base mutates without trail; graduation/forward-paper conclusions can't be defended after the fact. TAKEN is partially reconstructable via `paper_orders.signal_id`; DISMISSED/EXPIRED leave zero trail — prioritize those. `strategy_audit_log` already exists — cheap to extend. |
| T3 | **No UI-action or login audit.** Session cookie + Redis only; `loginTime` is a session attribute; no login-event row; no record of who triggered runs/resets from the UI (single-owner today, but autonomous-writes future needs an actor trail; `created_by`/`actor` are hardcoded `'owner'`). V002:36's own contract (`created_by = optimizer:{jobId}`) is already violated — optimizer promotion provenance lands in free-text `notes` while `created_by` stays `'owner'` (OPT/service.py:407-409; RegistryService.java:66, :98). | edge-gateway AuthController.java:37,110-142; V002:36 | Required before any autonomous promotion writes (Prompt 2's approval workflow). |
| T4 | **Env-flag state not snapshotted at decision time.** Live knobs (`ARTHA_*`, relative-vol floor) leave no per-signal record; only rejections record the effective threshold tested. No flag-change ledger (the #653 name-mismatch class is invisible too). | V015:19-21 vs signals writes | Forward-paper evidence can't be stratified by flag regime after the fact — a direct blocker for tune-on-live workflows. |
| T5 | **Wall-clock emission time missing** on signals (see L3) and batch per-candidate decisions are log-only. | SignalEngine.java:846-851; SwingBatchRecorder | Latency + batch forensics gaps. |
| T6 | Marker-write failure on the swing batch path is WARN-only (batch ran, marker missing ⇒ next-morning canary false-alarms/blind spots). | SSS/swing/SwingBatchRecorder.java:59-61 | Minor; make marker write fail loud or retry. |
| T7 | **Engine lifecycle events uninventoried**: publish→hot-reload, feed re-arm, boot/warm-up, canary latch resets are log-only (no engine-events row). The daily 09:42 live-health + 15:47 post-market analyses are Claude-side scheduled agents whose outputs land as dated docs, not DB rows. | SignalEngine.java:390-398, :1195-1234; `.claude/skills/daily-ops` | An optimizer stratifying live evidence by "engine restarted / reloaded mid-session?" has nothing to join on. |

---

## 6. Reproducibility and versioning audit

### Verdicts per artifact

| Artifact | Verdict | Basis |
|---|---|---|
| Backtest run (job pipeline) | **PARTIAL** | Config fully pinned (version UUID + SHA-256 checksum into `jobs.request`, re-verified on read — BT/jobs/JobsService.java:84-86; SSS/registry/RegistryService.java:580-588); universe pinned by copy + checksum (JobsService.java:104-118); seed recorded; replay deterministic. Holes: engine **code** version absent (`engine_version` = strategy semver — BacktestRunner.java:578-580); data store mutable (detect-only `data_hash`); calendar unversioned. |
| Live signal | **PARTIAL** | Exact published config pinned (FK + checksum) + frozen breakdown. Not replayable-from-DB: input candles mutable, 3m is read-time, env knobs unsnapshotted, REST dot inputs not raw-persisted outside chain snapshots. |
| Paper trade | **PARTIAL** | Fill audit columns exist (`fill_simulator`, `slippage_applied`); close reasons; signal attribution (V026, H5 fix). Breaching tick not durable; bid/ask null. |
| Optimizer trial | **REPLAYABLE** (modulo data-store caveat) | Params + foldContext pinned per trial job; seeded sampler → reproducible trial sequence (OPT/optuna_runner.py:60-78); version resolved once at submit (OPT/service.py:196-227); trial→run linkage. Caveat: the V004 trial ledger is resume-*designed* but study replay is **not implemented** — a restart marks orphaned sweeps failed (OPT/repos.py:66-77); REPLAYABLE rests on deterministic seeded re-submission, not resume. |
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
| R8 | Also working (keep + integrate): **StressGuard holdout protection** — `purpose: stress_test` submissions are validated against the strategy's full run lineage → hard 422 `WINDOW_CONTAMINATED`, plus a Redis holdout-reuse counter and a clean-window suggester `GET /api/v1/backtests/stress-window` (BT/jobs/StressGuard.java:17-30; StressWindowController.java:14-24). | A first-class research-honesty mechanism. Prompt 2's automated sweeps MUST integrate with it (pick windows via the suggester or expect 422s) — and should route final-validation runs through it deliberately. |

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
| F2 | **No clone, no rerun.** No strategy clone/duplicate anywhere; JobsPage has cancel only — no "rerun this job" / "duplicate with params". The archive lifecycle has **no UI entry point at all** (backend + audit exist; `useArchive` defined but wired into no page). The jobs compare picker also excludes sweep TRIAL runs (`kind === 'BACKTEST'` only — JobsPage.tsx:169), so trials can't be cross-compared with plain runs. | grep-confirmed; FE/api/strategies.ts:187 |
| F3 | **No export anywhere in research surfaces.** CSV/JSON download exists only in Data-Ops (SQL console + contract exports). Backtest results/trades/folds/MC/compare/signals/rejections/graduation: none. | FE/api/dataops.ts:242 (only `createObjectURL` hits) |
| F4 | **Failed-job diagnosis is thin.** `JobDto.error` typed but never rendered; a failed backtest is a red badge with no "why"; no backtest engine log surface (Data-Ops has a LogFeed; backtests don't). Restart-killed sweeps compound it: orphans are marked `failed` with `error` left NULL — indistinguishable from real failures even after rendering. | FE/api/backtests.ts:32; JobsPage.tsx; OPT/repos.py:66-77 |
| F5 | **No saved views / no run tags.** Filters exist but no persistence of filter sets; jobs have no tag/name/notes (backend gap too, §8). | localStorage persists theme/chart prefs/density/symbol-context/window layout — no research filter sets |
| F6 | **No guided research→live promotion workflow.** Publish dialog, graduation board, risk arming, and paper books are disconnected pages; no checkpointed flow (graduate → publish → arm → watch). | StrategyVersionsPage.tsx:181; GraduationPage.tsx |
| F7 | **Two parallel backtest UX systems**: job-based `/backtests/*` vs the bespoke `/equity/manas-arora/backtest` swing runner (drives `POST /market/screener/manas-arora/swing-backtest` + `/compare` with its own background-thread status — not the jobs pipeline). | FE/pages/equity/ManasAroraBacktestPage.tsx; FE/api/manasArora.ts:269-279 | 
| F8 | **No confirm-before-submit** on backtest/sweep launch (fires on click, navigates away). | BacktestRunnerPage.tsx |
| F9 | **Mobile (≈480 px) partially met**: shells/cockpits reflow, but dense research tables (trades 9-col, sweep leaderboard, versions diff, rejections 10-col) are raw tables with horizontal scroll; DataTable's card mode not applied to them. | e.g. BacktestResultsPage.tsx tables |
| F10 | Inconsistent live-data patterns: WS push for signals+jobs; polling for sweeps/paper/orders/scalper/dashboard-jobs at varied intervals; cockpit hand-rolls `setInterval`. Sweep progress notably lags (poll) despite sharing the jobs pipeline. | FE/api/optimizations.ts:97; FE/api/dashboard.ts:44 |
| F11 | Editor is a plain YAML textarea (Monaco deferred); no unsaved-changes route block (only `beforeunload`). | StrategyEditorPage.tsx:19-59 |
| F12 | No FE surface renders margin-heat **at all** — zero `margin-heat` consumers in FE src (the cockpit "heat" panel is the OI heatmap, a different thing); the endpoint has no consumer. | grep frontend-react/src; SSS/paper/PaperMarginController.java:48 |
| F13 | **No strategy-level pause/disable UI and no in-app notification center.** The kill switch is per-book on /paper; global pause is a Telegram command; `strategies.enabled` is unreachable (T2); alerts land on ntfy/Telegram with no in-app inbox/ack surface. | FE/pages/paper/PaperPage.tsx:166-169; §5 T2 |

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
| A1 | **Optimizer durability + discoverability**: sweeps run on an in-process daemon thread with an in-memory cancel set — a restart kills them (orphans marked `failed` with `error` left NULL, indistinguishable from real failures). No optimizer-**native** list endpoint (`GET /optimizations/jobs` 404s); sweeps ARE visible via the shared `jobs` table (`GET /backtests/jobs` + the JobsPage "Sweep" link), but there is no sweep-scoped listing/progress surface. | OPT/service.py:157, :232-245; OPT/repos.py:20-42, :66-77; OPT/api.py |
| A2 | **No run tagging/naming/notes on jobs**; `purpose` is a free-form string defaulting `"backtest"` with only `"stress_test"` special-cased (no validation; the FE runner never sends it). Strategies have tags; runs don't. | BT/jobs/JobsService.java:81, :123; V002:16 |
| A3 | **No compare endpoint** — closest are `GET /backtests/summary?strategyVersionIds` (latest-per-version) and sweep `/best`; no compare of N arbitrary runs (FE compare page assembles client-side from per-run fetches). | BT/replay/ResultsController.java |
| A4 | **No artifact/file export API** for backtests/optimizer (JSON payloads only); CSV export exists solely in market-data admin. | ExportController (MDS only) |
| A5 | **No job-completion webhooks/SSE** (STOMP progress only; ntfy/Telegram cover signals/ops, not job terminal states). | notifier wiring |
| A6 | **No auto-retry** for failed jobs; no submission concurrency/queue-depth cap beyond pool size + `maxTrials≤1000` (a runaway sweep floods the shared pool that also serves interactive backtests). | WorkerPool.java |
| A7 | **No general mutation audit** — risk settings + strategy lifecycle + Telegram commands audited; backfills have the V030 `backfill_jobs` ledger; but paper reset and ad-hoc candle re-fetches leave only `correlationId` log trails. | RiskService.audit; V030; SSS/paper/PaperService.reset:396 |
| A8 | **No API tokens / non-interactive principal** — session-cookie auth only. Fine for a single owner, but Prompt 2's orchestrator (and any external scheduler) needs a first-class programmatic credential rather than replaying login+XSRF. | SecurityConfig.java |
| A9 | Single global rate-limit bucket (50 rps/100 burst) — an aggressive orchestrator can starve the UI. Per-principal buckets once A8 lands. | gateway application.yml |
| A10 | Inline preflight+auto-warm on `POST /backtests/run` can hold the request up to the 300 s gateway timeout — submission should enqueue the warm too (async), returning immediately. | JobsService.submit |

---

## 9. Missing screens, controls, and decision-support views

Concrete build list (each names its data dependency; ⚑ = depends on the referenced
finding's fix — a §10 gap row or §12 roadmap item):

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
| **P1-10** | B3 intrabar touch realism | Wire the already-built `IntrabarExitResolver` as an opt-in `touch_basis: bar_hl_worstof` for stops/targets; record the basis per run (provenance) | A tight-stop backtest shows earlier/worse stop fills under the opt-in; `oneMinuteCovered` no longer a hardcoded literal |
| **P1-11** | B6 option expiry settlement | Settle a leg held past its expiry at intrinsic (settlement spot − strike, floored 0) + exercise STT; emit `exit_reason='expiry_settlement'` | A golden holding through expiry settles at intrinsic, not stale carried premium |
| **P2-1** | A1 optimizer durability + listing | Persist sweep loop state (DB-backed ask/tell checkpoints or move orchestration onto the jobs table); add `GET /optimizations/jobs` | Kill the optimizer mid-sweep → restart resumes or fails loudly **with `jobs.error` populated**; sweeps listable natively |
| **P2-2** | A2/F5 run tags/notes/saved views | `jobs.tags[]`, `jobs.note`; saved-view table keyed to owner | FE filter sets persist; runs taggable at submit + after |
| **P2-3** | A4/F3 export | Per-run CSV/JSON export endpoints (trades, folds, equity, compare matrix) + FE buttons | Download works for the 4 core artifacts |
| **P2-4** | D8 data-quality artifact | Nightly per-symbol completeness report (1m gaps, 1d/bhavcopy presence, chain-capture holes) persisted + dashboard (§9.6) | A seeded gap appears in the next report |
| **P2-5** | L3 latency instrumentation | Wall-clock stamps: bar-publish→eval-start→emit→paper-fill; persist emit wall time on signals; Prometheus histograms | p50/p95 tick→signal→fill visible; §17.3 gate implementable |
| **P2-6** | D5/D6 dividends + PIT constituents | Ingest dividend cash flows (bhavcopy CA feed carries them; parser currently discards) as a separate table (do NOT mutate prices); implement the constituents fetcher (V008 design already PIT) | Total-return metric available as an overlay; membership history accrues |
| **P2-7** | B9 margin feasibility in backtest | Feed portfolio SPAN from the dormant margin-service appliance (#126) or Upstox (#510) as a per-run feasibility metric (advisory first) | Runs report peak margin vs capital; un-fundable variants flagged, not ranked |
| **P2-8** | B12 backtest decision traces | Persist sampled per-bar gate/composite breakdowns (or all rejected-entry evaluations) per run — the backtest twin of `signal_rejections` | "Why no trade on date X" answerable from stored rows |

Explicitly **not** blocking Prompt 2 (accepted, must stay disclosed as run metadata):
survivorship depth limits (D2 — disclose per run), derived-OI mutedness, no partial
fills/limit orders (B10), swing same-close fill convention (B8 — but add an
open-next-day sensitivity variant so the optimizer can price it). B2's frictionless
swing sims are *resolved* by Phase 2 #15 (sensitivity variant) + #16 (port into the
job pipeline) rather than patched in place. Market impact stays portfolio-layer-only
(equity swing clips ≤ ~6.5 % of a ₹1.5 L book are far below ADV-impact scales;
revisit at real capital).

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
  profile,                             // live | mock — mock runs are never ranked
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
auto-arming anything. The Telegram bot's two-phase `/confirm`-within-60s (V019) is the
in-house approval pattern to reuse for transition confirmations.

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
8. Backtest 1d context/benchmark reads → native daily (align with the warmup path) —
   **HOLD** (changes backtest inputs; goldens/parity rerun). (D7)
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
22a. Run-row provenance completion — `forks[]`, `fillModel`, `costModel`,
    `flagsSnapshot`, `profile` onto jobs/runs (completes §11.1; P1-7's flag ledger
    feeds it) — clean.
22b. `evidencePolicy` strategy tag (forward-only vs backtestable) + StressGuard-aware
    window selection in the optimizer client (§6 R8) — clean.

**Phase 4 — research UX & workflow [~2-3 weeks]**
23. Run tags/notes + saved views (API + FE) — clean. (P2-2)
24. Backtest-vs-paper parity view — clean (v1 on existing data, caveats banner). (§9.1)
25. Export endpoints + FE buttons — clean. (P2-3)
26. Rerun/clone controls; sweep list page; confirm-before-run — clean. (F2/A1/F8)
27. Promotion workflow screen + stage state machine + approval audit — **HOLD**
    (owner-facing process change). (§11.6)
28. Order-event timeline + data-quality dashboard polish; mobile card-mode for the 4
    dense research tables — clean. (§9.7, F9)
29. Headless-automation hardening: API-token principal + per-principal rate buckets +
    async submission warm + job-completion notifications — clean. (§11.7; A8/A9/A10/A5)
30. Re-run/ranking policy enforcement inside the experiment layer (refuse cross-hash /
    cross-SHA rankings; auto-queue re-runs) — clean. (P1-1c; §13 preamble rule)

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
unless it re-runs the stale side — and must only consume **live-profile** runs (mock
candles are synthetic boot-accrual; the stacks are DB-isolated).

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
   `maxTrials` override YAML (OPT/service.py). Methods supported: `grid | random |
   tpe | nsga2` (nsga2 = multi-objective with its own validation); objectives gated by
   the frozen `_ALLOWED_OBJECTIVE_METRICS` set (unknown metric → 400 at submit);
   `maxTrials` capped at 1000; a walk-forward sweep **auto-overrides an in-sample
   objective to `oos_fold_mean`** (anti-curve-fit guard) — OPT/service.py:16-31,
   :87-103; OPT/optuna_runner.py:52-78. Promotion contract: trial →
   `POST /optimizations/{sweepId}/promote` → **draft** version.

3. **Run metadata** — EXISTS (partial): `jobs.request` (full pinned submission incl.
   strategyId/Version/checksum, window, pinned universe copy, seed), `backtest_runs`
   (seed, `data_hash`, `universe_checksum`, `premium_source`, fold columns, metrics
   JSONB with `caveats[]` + `oiGateCoverage`). **ADD**: provenance block §11.1 —
   engine SHA Phase 0 #2, the rest (forks, fill/cost model, flags snapshot, profile)
   Phase 3 #22a; run tags/notes — Phase 4 #23. Note: `purpose: stress_test` runs are
   holdout-guarded (§6 R8) — automated submitters must respect the contract.

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
   the **guarded** shapes, never raw single-window returns — and must route
   final-validation runs through the StressGuard holdout contract (§6 R8: pick
   windows via `GET /backtests/stress-window` or expect 422 `WINDOW_CONTAMINATED`).

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
    (provenance `forks[]` + a strategy-level `evidencePolicy` tag — ADD, Phase 3
    #22a/#22b).

---

## 14. Fix log (2026-07-11 overnight implementation pass)

The 2026-07-10/11 overnight run (Opus builders, Fable audit/merge per the delegation
standing rule) closed the P0 live defect plus the run-provenance and durability holes
this audit ranked highest. PRs #683–#717, all merged + deployed + live-verified;
migrations `backtest V008/V009/V010` (+ `strategy V028/V029/V030`, `marketdata V040/V041/V042`
on the sibling app-platform track) applied + probed live. Append-only log — §1–§13 above
are unchanged.

### Addressed this pass

| Finding | Item · PR | Outcome |
|---|---|---|
| **P0-1 / §3.1** partial coarse-bucket poisoning | B1 · #683 | `LiveSeriesStore` completed-bucket read filter + `SignalEngine.entryAnchorIndex` exit-anchor fix + `PartialBucketCanary`. The 4-lens adversarial review **found + fixed a HIGH exit-anchor off-by-one**; the 1h post-close truncated-bucket drop was accepted + test-pinned. **Live behavior shifts from Mon 2026-07-13: entries UP + wider stops — an E8 re-tune prerequisite** (pre-announced to owner). |
| **P0-2 / R1** engine identity on run rows | B2 · #703 | `engine_sha` + `engine_image` stamped on `jobs` + `backtest_runs` (V008); surfaced in the results API + FE run-detail panel. Two runs across a deploy are now distinguishable. |
| **P2-1 / A1 / F4** optimizer durability + listing | B6 · #708 | orphan reaper populates `jobs.error` (real failures now distinguishable from restart-orphans); native `GET /optimizations/jobs` listing. |
| **T3** actor plumb (`created_by`) | B7 · #710 | actor threaded through job/run/version writes (V009); vocabulary `owner \| optimizer \| optimizer:{sweepId}`, `scheduler:*`/`evo:*` reserved. Honors the V002:36 contract. |
| **§11.4 / §13 #8 + #11** experiment read layer + server compare | B8 · #714 | `experiment_runs` view (V010); `GET /backtests/experiments` list + `/compare` returning the LikeForLike matrix (dataHash/universe/engine-SHA match). `costModelMatch` **deferred to the P1-2 costs work** (no cost model on runs yet). |
| **F4** failed-job diagnosis | A8 · #696 (+ B6 · #708) | `JobDto.error` now rendered in a failed-job dialog; B6 ensures the field is populated for orphaned sweeps. |
| **A6** no submission concurrency / queue cap | B16 · #717 | interactive-reserved CAS budget on the shared pool + `429 RATE_LIMIT_QUEUE`; a runaway sweep no longer starves interactive backtests. |
| **L5 / P1-6 (partial)** stale-tick blindness in the paper exit path | A3 · #694 | 15 s fill max-age → DATA_STALE; bracket-starvation alerter; the breakeven `avgEntryPrice` settle fabrication was **KILLED** — review redesigned settle to last-real-tick-any-age after finding 3 state-stranding paths. Closes the L5 silent-hold + breakeven-pollution risks; the standalone `STALE_TICK`/`UNSETTLED` position marking of P1-6 is the residual. |

Shipped extras (not on the §10 gap list; widen the tunable/contract surface Prompt 2 consumes):
- **B15 · #716** — parameter path-grammar extension (gate-expression constants + `risk.max_positions`) across the 5 sync points (schema / OPT path_grammar+config_patch / LIB ParameterPaths+TrialOverrides). Screener/funnel props were **refused — no config leaf exists** (chip `task_2560273c`).
- **B17 · #712** — `contracts/metrics/trial-metrics-catalog.json`: one 20-metric contract both the Java and Python sides consume (kills the hand-synced metric drift between the backtest and optimizer seams).
- (B16 above also = §8 A6.)

### NEW finding (surfaced during B1's review)

- **Backtest 1h rollup is UTC-anchored while the live cagg re-anchors to IST `:00`** — a
  30-minute phase gap between the backtest 1h coarse-primary buckets and the live 1h series
  (the same class as the §3.1 anchoring family, but a *between-worlds* offset rather than a
  partial-bucket poison). A backtest-vs-live comparison for a 1h-primary strategy is
  phase-shifted by 30 min. Fix chip `task_1b85c64f` filed; not yet addressed.

## 15. Fix log (2026-07-12 implementation pass)

The 2026-07-12 run (Opus builders, Fable audit/merge; the #736–#782 wave) closed the
remaining P0 lineage/CA/BTST holes plus the P1 execution-realism residue this audit ranked
next. All merged + deployed (5 deploy rounds; final live @ `6f1556d8`). Append-only log —
§1–§13 above are unchanged.

### Addressed this pass

| Finding | PR | Outcome |
|---|---|---|
| **P0-3 / §5** swing-pipeline lineage | #764 | `DEEP_SWING` job kind — the Manas/Minervini deep-sims now run through the job pipeline (real run-row lineage), not a side script. |
| **P0-4 / H6** screener + geometry CA adjustment | #757 (+ Equity-Returns plane #761) | CA-adjusted price plane feeds the live screeners + geometry; the analytics returns plane is CA-adjusted too. Live screen rows no longer read raw-split prices. |
| **P0-5** BTST position + exit simulation | #759 | pre-close position opened + `exit_rules` evaluated at subsequent pre-closes (close→close convention recorded). Live BTST intraday-exit realism = chip `task_3e95fade`. |
| **P1-2** costs knob + instrument-class costing | #756 | validated `costs` knob on the candle path + instrument-class costing (B18). |
| **P1-8** accepted-signal context symmetry | #763 | fired-side per-rail diagnostic side-channel (shape-mirrors `signal_rejections.diagnostic`; parity-safe additive). |
| **P1-9** daily-context lookahead | #755 | end-gate context advancement pinned to COMPLETED buckets (was already gated; hardened + test-pinned). |
| **P1-10** intrabar touch realism | #762 | opt-in `touch_basis: bar_hl_worstof` intrabar exit realism + per-run basis provenance. |
| **P1-11** option expiry settlement | #753 | legs held past expiry settle at intrinsic + exercise STT. |
| **D7** backtest 1d context/benchmark → native daily | #754 | 1d context + benchmark reads routed to the dense native-daily path (aligns with the warmup path). |
| **F2** no-rerun / N+1 deep-sim reads | #750 | batched the N+1 candle reads in the Manas + Minervini deep-sim backtests. |

### Still OPEN (after the 2026-07-12 pass)

- **P1:** P1-1 dataset comparability (content hash + `dataset_epochs`) · P1-3 candle-path exit-reason attribution · P1-4 order-event model · P1-5 quote capture at fill · **P1-6** (only the L5 exit-path half landed via A3 — the standalone stale-tick position mark is open) · P1-7 flag/config snapshot at decision time.
- **P2:** P2-2 run tags/notes/saved views · P2-3 export · P2-4 data-quality artifact · P2-5 latency instrumentation · P2-6 dividends + PIT constituents · P2-7 margin feasibility · P2-8 backtest decision traces. *(P2-1 closed 2026-07-11.)*
- The backtest-vs-live 1h rollup phase-gap chip `task_1b85c64f` (§14 NEW finding) — still not addressed.

---

*Method note: 6 parallel read-only audit agents (backtest fidelity, live/paper
fidelity, data quality, telemetry/reproducibility, frontend, backend APIs) over the
2026-07-10 working tree at `main`@d477c3f7, cross-checked against the 2026-07-05 full
audit and the forward ledger; the §3.1 defect chain was re-verified by hand in source
before inclusion. No code was changed by this audit. Second stage: a 2-pass
verification — 5 adversarial accuracy agents re-checking every §1–§9 claim against
source (~150 claim-units; **0 refuted**, ~20 imprecise corrected) + 1 completeness
agent against the commissioning requirements — produced this revision's corrections
and additions (margin-service appliance, StressGuard contract, optimizer
method/objective contract, D12–D14, T7, R8, F13, P1-10/11, P2-7/8, §12 #22a/b/29/30).*
