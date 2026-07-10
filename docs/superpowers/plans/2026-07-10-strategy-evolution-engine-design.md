# Autonomous strategy evolution & optimization engine — design (Prompt 2)

**Date:** 2026-07-10 · **Fixed input:** the research-fidelity audit
`docs/audits/2026-07-10-research-fidelity-audit.md` (merged `main@b246c8ce`) — its §13
"Inputs required by Prompt 2" is the contract this design consumes; its §10 gap ids
(P0-x/P1-x/P2-x) and §12 roadmap items are referenced throughout. Nothing from Prompt 1
is redesigned; one critical blocker is *re-flagged* (P0-1, §13 here) because it poisons
the live-evidence plane this engine depends on.

**Design stance.** This is not a new service and not a new ML platform. The platform
already contains ~70 % of an evolution engine: a seeded Optuna sweep lane with a
plateau (parameter-stability) leaderboard, walk-forward folds with per-regime OOS
attribution, a holdout-contamination guard, an immutable strategy-version registry with
draft/publish/rollback, a shadow book that trades *rejected* entries under challenger
knob-sets on live data, per-book paper accounting, and a graduation gate. The evolution
engine is the **orchestration brain + scoring model + promotion workflow** wired over
those parts, plus a small set of new tables and endpoints in `optimizer-service`.

Path abbreviations as in Prompt 1 (SSS/BT/MDS/LIB/OPT/FE).

---

## 1. Optimization engine design

### 1.1 Concept hierarchy

```
Base strategy (registry UUID)
 └─ Campaign        — the long-lived evolution program for ONE base strategy
     └─ Generation  — one propose → evaluate → select iteration (bounded budget)
         └─ Candidate — one concrete version: params vector (+ optional ONE structure
            │          mutation), materialized as a registry DRAFT version
            ├─ Trials/Runs — existing jobs (sweeps, folds, holdout, stress re-runs)
            ├─ Live evidence — paper/shadow rows keyed by the candidate's version UUID
            └─ Scorecard — hard-gate results + RobustScore (§6)
     └─ Proposals   — PROMOTE / PAPER / RETIRE / ROLLBACK / REVIEW_GATE, owner-gated
```

A **campaign** is per base strategy, created by the owner (or suggested by the engine),
with an explicit budget: max generations, max trials/generation, max holdout touches
(StressGuard counter is the enforcement — audit §6 R8), and a wall-clock cadence.

### 1.2 Evidence policy — the routing decision (family-aware by construction)

The single most important design fact from Prompt 1: **the three families have
different valid evidence planes.** The engine routes every campaign through one of
three policies, stamped on the campaign and on every scorecard (`evidencePolicy`,
audit §12 #22b):

| Policy | Families | Primary evidence | Secondary | Rationale (Prompt-1 findings) |
|---|---|---|---|---|
| **SIM_FIRST** | Manas, Minervini swing/momentum | Walk-forward backtests on `candles`@1d (job pipeline, 1d primary is parity-supported) + holdout | Forward paper (slow accrual; months-long holds can't be forward-tuned) | Deep history exists (~11 y); live gates ≈ sim gates for swing; house doctrine "swing = backtest-driven" |
| **LIVE_FIRST** | All intraday Siva options scalpers | Shadow-book challenger variants + paper book + counterfactual replay on captured premium/chain | Backtest as *functional smoke only* (never for ranking) | The ~30-rail confluence gate is live-only; OI/Dow/IV muted on derived history; house rule "armed ≈ 0 backtest trades is an artifact — judge on live" (audit §2 flags, §3 L11) |
| **SIM_BLOCKED** | BTST (Siva) | Paper/live evidence only; **no sim ranking at all** until audit P0-5 lands | — | BTST exits are not simulated — a multi-day run degenerates to one trade (audit B1). Ranking BTST variants on backtests today would be ranking noise. |

The engine refuses to score a candidate on an evidence plane its policy forbids — this
is a hard rule, not a warning.

### 1.3 The control loop (per campaign)

```
1. PROPOSE   — generation N: sample candidates from the search space (§3);
               at most one structure mutation stream per generation (§5).
2. MATERIALIZE — each candidate = a registry DRAFT version (existing promote/
               clone mechanics, checksum-deduped), created_by = 'evo:{campaignId}'.
3. EVALUATE  — SIM_FIRST: sweep trials → walk-forward folds → top-K neighbor probes
               → cost-stress re-runs → (finalists) one-shot holdout via StressGuard.
               LIVE_FIRST: register challenger knob-sets as shadow variants +
               counterfactual replay jobs; accrue ≥ the family's min evidence window.
4. SCORE     — hard gates, then RobustScore (§6); scorecards persisted.
5. SELECT    — plateau-ranked survivors; parameter-importance + brittleness update.
6. PROPOSE ACTIONS — proposals to the owner inbox (§8): publish-to-paper,
               promote, retire, rollback, review-gate. NOTHING self-arms.
7. SLEEP     — until the next scheduled cadence (swing: weekly; scalper: after each
               evidence window) or an owner trigger.
```

Determinism & traceability: every generation records `engine_sha` (audit P0-2),
`data_hash`/epoch refs (P1-1), the search-space hash, the sampler seed, and the
StressGuard touch count. Rankings **refuse to compare across differing engine SHA /
data epoch** — the stale side is re-queued instead (audit §13 comparability rule,
roadmap #30).

### 1.4 Safety invariants (non-negotiable, encoded not documented)

1. The engine writes **drafts and proposals only**. Publish, arm, promote, rollback
   all require owner approval (house owner-gated tier; F7 precedent: measurement-only).
2. Holdout windows are touched at most once per finalist, budgeted per campaign
   (StressGuard 422 + Redis reuse counter enforce it mechanically).
3. Live-plane experiments (shadow variants, paper lanes) are capped per family
   (default: ≤ 6 concurrent challenger variants per strategy, ≤ 2 evo paper
   strategies per family book) and ride default-OFF flags the owner arms once.
4. Mock-profile evidence is never scored (audit §13 preamble).
5. Every autonomous write lands an audit row (`strategy_audit_log` action set
   extended; `evo_proposals` is itself append-only).

---

## 2. Experiment and versioning model

### 2.1 Reuse (no redesign)

- **Candidate = `strategy_versions` DRAFT row** — immutable, checksum-deduped,
  byte-preserved YAML, structured diff, publish/rollback lifecycle already exist
  (audit §6 R7). The optimizer's promote-to-draft (§D.9) is the materialization
  mechanic; evo generalizes it from "one winning trial" to "every candidate".
- **Runs/trials/folds/Monte-Carlo/holdout** — the existing jobs spine untouched.
- **Live evidence** — `signals`, `paper_positions/orders`, `shadow_positions`
  (+`variant`), `strategy_graduations`, all keyed by version UUID (audit §6 lineage).

### 2.2 New tables (backtest schema; written by optimizer-service, its existing role)

```sql
evo_campaigns(
  id uuid PK, strategy_id uuid, family text, evidence_policy text
    CHECK (evidence_policy IN ('SIM_FIRST','LIVE_FIRST','SIM_BLOCKED')),
  objective_spec jsonb,          -- §6 weights/gates overrides (defaults per family)
  search_space jsonb,            -- frozen copy of the YAML optimize block + evo caps
  budget jsonb,                  -- {maxGenerations, maxTrialsPerGen, holdoutTouches,
                                 --  maxShadowVariants, cadence}
  status text,                   -- ACTIVE | PAUSED | CLOSED
  champion_version_id uuid,      -- current incumbent
  created_at, updated_at)

evo_generations(
  id uuid PK, campaign_id FK, n int,
  proposal jsonb,                -- pre-registered: sampler, seed, structure mutations
  search_space_hash text, engine_sha text, data_epoch jsonb,
  stress_touches int DEFAULT 0, status text, started_at, finished_at)

evo_candidates(
  id uuid PK, generation_id FK,
  version_id uuid,               -- strategy_versions row (soft ref, D10 convention)
  parent_candidate_id uuid,      -- lineage for "which mutation produced this"
  mutation_kind text CHECK (mutation_kind IN ('PARAMS','STRUCTURE','SEED')),
  params jsonb, structure_diff jsonb,
  sweep_job_id text, holdout_run_id text,     -- links into jobs/backtest_runs
  scorecard jsonb,               -- §6 output: gates, components, RobustScore, caveats
  state text,                    -- PROPOSED|EVALUATING|SCORED|SURVIVOR|RETIRED|
                                 -- PAPER|TAKE_ELIGIBLE|PROMOTED|ROLLED_BACK
  updated_at)

evo_proposals(
  id uuid PK, candidate_id FK, kind text
    CHECK (kind IN ('PUBLISH_PAPER','PROMOTE','RETIRE','ROLLBACK','REVIEW_GATE')),
  evidence jsonb,                -- the proposal card payload (§10)
  status text CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
  actor text, decided_at, expires_at,          -- default expiry 7 days
  created_at)
```

Joins: `evo_candidates.version_id → strategy_versions → jobs/backtest_runs/
optimization_trials → backtest_trades` (sim plane) and `→ signals → paper_* /
shadow_positions` (live plane) — exactly the Prompt-1 experiment join graph (§11.4
views/#20); the evolution UI reads through those views, not bespoke joins.

### 2.3 Versioning rules

- Candidate YAML = base version + `params_override` materialized + (optionally) one
  structure diff — never hand-edited. `notes` carries the human-readable lineage;
  `created_by='evo:{campaignId}'` carries the machine-readable one (needs the actor
  plumb — Prompt-1 T3; see §13).
- Candidates that lose stay as ARCHIVED drafts (queryable history, zero cost); the
  registry's checksum dedupe means re-proposing an identical config is a no-op.
- Clone-and-rerun (user-driven): `POST /evolution/candidates/{id}/rerun
  {paramsPatch}` → new candidate in an ad-hoc "manual" generation — same lineage
  rows, so hand experiments and autonomous ones live in one history.

---

## 3. Parameter search methodology

### 3.1 Search space discipline (anti-overfit before any search runs)

- Space = the strategy's YAML `optimize.parameters` (1–20 entries, typed
  range/step/choices — schema `$defs/optimize`), **capped by evo**: ≤ 6 active
  dimensions per generation (excess dims frozen at incumbent values, rotated across
  generations). Rationale: DOF control is a first-class scoring penalty (§6), and
  20-dim TPE on ~10 y of daily data is a curve-fitting machine.
- Bounds must be *doctrine-plausible* (owner-reviewed once at campaign creation):
  e.g. Manas ATR trail multiplier 2.0–4.0, not 0.1–20. The campaign freezes the
  space; changing it = a new campaign (comparability).
- Every generation pre-registers its sampler, seed, and candidate count **before**
  evaluation (`evo_generations.proposal`) — the data-snooping ledger.

### 3.2 Staged search (per generation, SIM_FIRST)

1. **Screen** — `grid` (small spaces) or seeded `random`/LHS (larger): ~30–50 % of the
   trial budget, full walk-forward folds per trial (existing pipeline; objective
   auto-overridden to `oos_fold_mean` — already enforced, OPT/service.py:87-103).
2. **Exploit** — seeded `tpe` warm-started from screen results for the remainder.
3. **Stabilize** — for the plateau-ranked top-K (existing leaderboard: each trial
   re-scored as the **median objective of its ±1-step neighbors**, OPT/leaderboard.py):
   where `neighborCount < 4`, actively submit the missing neighbor trials ("neighbor
   probes") so plateau scores are estimated, not accidental. Survivors need
   `plateauObjective ≥ 0.8 × rawObjective` — single-point peaks die here.
4. **Pareto pass (optional)** — `nsga2` (exists) on 2–4 objectives
   (`oos_fold_mean` ↑, `max_drawdown` ↓, `oos_fold_std` ↓) when the owner wants the
   frontier view; final selection still goes through §6 scoring (no raw-profit picks).
5. **Cost-stress** — top-K re-run with slippage doubled and quadrupled via
   `params_override` on the fills paths (`slippage_bps`/`slippage_ticks` are ordinary
   overridable YAML paths today). Degradation slope feeds §6. (Statutory-class cost
   correctness for futures-signal strategies additionally needs Prompt-1 P1-2.)
6. **Holdout** — ≤ 2 finalists per generation touch the campaign's holdout window
   once, via `purpose: stress_test` (StressGuard-enforced).

### 3.3 LIVE_FIRST search (scalpers)

Parameters here are mostly **gate knobs** (rail thresholds, composite floor,
relative-vol k/N, band widths). The search mechanism is the one that already earns
real-PnL labels on identical market data:

1. Candidate knob-sets → **shadow challenger variants** (existing `ShadowVariants`
   mechanics: rail threshold override / rail disable / composite floor; each accepting
   variant opens its own tagged 1-lot with cost-adjusted `pnl_net`). Evo needs these
   registrable per campaign via API instead of static config (§11).
2. Evidence window: default 4 trading weeks or ≥ 60 champion-comparable events,
   whichever later.
3. **Paired evaluation**: variants see the *same* rejected/accepted event stream, so
   comparison is paired-by-event (Wilcoxon signed-rank on per-event net PnL + win-rate
   delta with a binomial interval), not independent-sample — small samples become
   decision-grade sooner.
4. **Counterfactual replay** (offline complement): the live-signal-analysis runbook's
   E9 method formalized as a job type — replay captured premium/chain over the same
   window under the knob diff, for knobs the shadow book can't express (e.g. exit
   band changes). Runs on captured (real) data only — never derived history.
5. Exit-knob candidates that pass counterfactual replay graduate to a **paper
   A/B lane** (evo paper strategies in the family book, capped, flag-armed §1.4).

### 3.4 Parameter effect & brittleness analytics (per campaign, updated per generation)

- **Importance**: Optuna fANOVA/importance over all campaign trials → ranked
  "strongest positive / negative effect" per parameter (the user-facing tornado).
- **Brittleness map**: per parameter, the objective's local variance across its
  neighbor pairs (from plateau data) × its importance → a param×stability heat
  surface. High-importance + high-local-variance = brittle; flagged in the campaign
  report and on candidate cards ("this version's edge leans on a brittle knob").
- **Interaction sniff**: 2-D plateau slices for the top-3 importance pairs (grid of
  existing trials; no extra runs unless a hole > 2 cells).

---

## 4. Walk-forward and out-of-sample validation design

Reuses the existing `WalkForwardRunner` (rolling/anchored, trading-day arithmetic,
`min_trades` fold exclusion, per-fold regime attribution) — this section standardizes
*how it's applied*, per family:

| | SIM_FIRST swing | LIVE_FIRST scalper (sim smoke only) | BTST (post-P0-5) |
|---|---|---|---|
| Fold shape | train 750 td / test 125 td / step 125 td (≈3 y → 6 m), rolling; one anchored variant for trend-following sanity | train 30 td / test 10 td / step 10 td | as scalper |
| Min trades/fold | 8 | 30 (existing default) | 15 |
| Holdout | most recent 12 months, campaign-frozen, StressGuard-registered | 4 most recent weeks of *captured* data | most recent 8 weeks |
| Live rolling eval | forward-paper monthly windows | shadow/paper weekly windows — **live IS the walk-forward** (next week is OOS by construction) | paper weekly |

**Decision gates are OOS-only** (the constraint, enforced):
- Primary: `oos_fold_mean` (existing auto-override) with `oos_fold_std` alongside.
- `sharpe_degradation` (IS→OOS) ≤ 0.5 — worse means the fit is in-sample.
- Fold consistency: ≥ 60 % of included folds positive.
- **Regime consistency** (existing `guardMetrics`: `regimesCovered`,
  `regimeOosMin/Mean/Max` over BULL/RANGE/BEAR/CRASH): `regimeOosMin` must not be
  catastrophic (default: ≥ −0.5 × `regimeOosMean`), and ≥ 3 regimes covered for swing.
  A candidate that only wins in BULL is labeled REGIME_DEPENDENT — rankable but
  penalized, and its card says so.
- **Multiplicity correction**: the campaign records total trials-to-date N; finalists'
  OOS Sharpe is deflated (DSR-style: expected-max-of-N adjustment using the standard
  E[max of N normal draws] ≈ √(2·ln N) term) before gating. Concretely:
  `deflatedSharpe = (S_oos − S₀(N)) / se(S_oos)`, gate `> 0`. This is the formal
  answer to "detect data snooping" — the engine literally charges itself for every
  trial it ran.
- **Monte Carlo** (exists): bootstrap DD distribution → `p95(maxDD)` must fit the
  family risk cap; risk-of-ruin < 1 %.
- Curve-fit tripwires: plateau ratio (§3.2.3), DOF penalty (§6), IS-only-lift
  auto-reject for structure mutations (§5).

**Live gate (both policies):** once a candidate has live evidence, the live rolling
windows enter the same gate shape — a candidate whose live windows fail the gates its
sim windows passed is DIVERGENT (§7), never promotable while unexplained.

---

## 5. Indicator and gate experimentation design

Structure changes are rarer, costlier, and more overfit-prone than parameter changes —
they get their own stream with stricter rules.

### 5.1 Candidate sources (ranked by prior)

1. **Rejection forensics** (LIVE_FIRST): rails whose *disable* variant shows paired
   positive lift in the shadow book ("rail X keeps blocking winners" — the shadow
   book's stated purpose) → propose relaxation; rails never blocking anything →
   propose removal (dead DOF).
2. **Regime gates**: existing `RegimeLabeler` labels as entry filters (e.g. suppress
   entries in CRASH) — cheap, well-understood, directly targets regime dependence.
3. **Existing indicator library** (LIB IndicatorBank + dot inputs) not used by this
   strategy — e.g. an MA-slope filter for Manas, an IV-rank band for premium buys.
4. **Owner hypotheses** — entered as pre-registered experiment rows like any other.
   The engine *suggests* (1)–(3) automatically in the campaign report; it never
   invents novel indicator math on its own.

### 5.2 Ablation protocol (the only accepted evidence)

- One structure mutation per candidate (base + gate, or base − gate). No stacking.
- **Paired on identical data**: same folds, same `data_hash`, same seed, evaluated in
  the same generation as its parent. Lift = candidate − parent per fold (paired), not
  leaderboard position.
- Acceptance requires ALL of:
  - OOS lift > 0 with paired p < 0.10 across folds;
  - lift ≥ 0 in ≥ 2 of the 3 majority regimes (not BULL-only);
  - trade count retained ≥ 70 % of parent (a gate that deletes the sample "wins" by
    starvation — reject);
  - survives the DOF penalty (§6) — i.e. the robustness gain pays for the added knob;
  - **IS-only-lift auto-reject**: if IS lift > 0 but OOS lift ≤ 0 → rejected and
    *recorded* (`REVIEW_GATE` proposal with verdict REJECTED_IS_ONLY), so the same
    gate isn't re-proposed next quarter (institutional memory against snooping).
- LIVE_FIRST gates: the same protocol but the paired unit is the shadow event stream
  (§3.3.3) and the window is calendar time; a gate accepted on shadow evidence still
  passes through a paper A/B before any promotion proposal.
- Explainability requirement: an accepted gate must persist its operand into
  `score_breakdown`/rejection diagnostics (the platform already does this for every
  rail) — a gate whose decisions can't be traced is rejected on principle
  (explainability is a scored dimension, §6).

### 5.3 "Propose a new strategy variant" (the composed case)

When a campaign accumulates ≥ 2 accepted mutations + a stable parameter region that
together beat the incumbent champion on RobustScore by ≥ 10 % over ≥ 2 generations,
the engine composes them into a **named variant proposal** (e.g.
`manas-arora-swing/evo-g7`) with the full evidence chain (which mutation contributed
what, per the paired ablations) in the proposal card — "clearly explain why the chosen
version is superior" is generated from stored lineage, not prose.

---

## 6. Scoring and ranking model

Two stages: **hard gates** (constraints — fail any ⇒ not rankable, card shows which),
then **RobustScore** (weighted normalized components). No single metric decides;
raw backtest profit is deliberately *not* a component (it enters only via OOS
fold returns).

### 6.1 Hard gates

| Gate | SIM_FIRST default | LIVE_FIRST default |
|---|---|---|
| Evidence floor | ≥ 120 OOS trades across folds (swing ≥ 60) | ≥ 60 paired shadow events or ≥ 40 paper trades |
| OOS sign | `oos_fold_mean > 0` and deflated Sharpe > 0 (§4) | paired lift > 0, p < 0.10 |
| Fold consistency | ≥ 60 % folds positive | ≥ 60 % weekly windows positive |
| Drawdown cap | `p95(maxDD)` ≤ family cap (swing 40 %, per deep-sim budget) | daily-loss discipline unbreached (3 % book rule) |
| Regime floor | `regimeOosMin ≥ −0.5 × regimeOosMean`, ≥ 3 regimes covered | n/a (intraday; session-type slices instead: expiry-day vs normal) |
| Stability floor | `plateauObjective ≥ 0.8 × raw` with `neighborCount ≥ 4` | knob-set is a plateau member (≥ 2 adjacent knob-sets also positive) |
| Holdout | one-shot holdout return > 0 and ≥ 0.5 × OOS mean | most-recent-window replay agrees in sign |
| Comparability | same engine SHA + data epoch as comparator; live-profile only | same + no data-health incidents in window (audit T7/feed canaries) |
| Live-gap (when live evidence exists) | not DIVERGENT (§7) | not DIVERGENT |

### 6.2 RobustScore

All components are computed on **OOS/live evidence only**, normalized to z-scores
within the campaign (comparisons are within-campaign; cross-strategy views are
descriptive league tables, never ranked promotion inputs):

```
RobustScore = 0.22·z(oos_return)            # oos_fold_mean (ann.)
            + 0.16·z(stability)             # −oos_fold_std, plateau ratio, neighbor var
            + 0.14·z(risk_adjusted)         # OOS Sortino (Sharpe shown alongside)
            + 0.12·z(drawdown_quality)      # −p95(maxDD), −DD duration, recovery factor
            + 0.10·z(regime_consistency)    # regimeOosMin/Mean + covered count
            + 0.08·z(cost_resilience)       # −degradation slope at 2×/4× slippage (§3.2.5)
            + 0.08·z(live_alignment)        # −live-gap magnitude (§7); 0 if no live yet
            + 0.05·z(explainability)        # trace completeness (below)
            + 0.05·z(efficiency)            # expectancy/trade + turnover sanity
            − DOF_penalty                   # 0.03 per active param over 4;
                                            # 0.06 per structure gate
            − caveat_penalty                # 0.05 per unresolved data caveat on the
                                            # runs (metrics.caveats[], oiGateCoverage<80%…)
```

Weights are per-family defaults in `objective_spec` (LIVE_FIRST shifts weight from
`oos_return` to `live_alignment` + `cost_resilience`); the owner can tune them per
campaign, and every scorecard stores the weights used (reproducible ranking).

**Component sources — required-metric coverage** (every metric the commissioning
prompt demands, mapped):

| Metric | Source | Status |
|---|---|---|
| Net profit / CAGR | `backtest_runs.total_return`, CAGR in metrics JSONB (OOS variants per fold) | EXISTS |
| Profit factor / win rate / expectancy | metrics JSONB | EXISTS |
| Max drawdown / DD duration | metrics JSONB (`maxDD` + duration) | EXISTS |
| Sharpe / Sortino | metrics JSONB (rf 6.5 %) | EXISTS |
| Recovery factor | totalReturn ÷ maxDD | ADD (trivial, MetricsCalculator) |
| Trade frequency / turnover | trade_count ÷ window; Σ|fill value| ÷ equity | ADD (MetricsCalculator) |
| Parameter stability | plateauObjective/neighbor stats (leaderboard) + probes | EXISTS + §3.2.3 |
| OOS performance | `oos_fold_mean/std`, fold metrics | EXISTS |
| Live-to-backtest gap | §7 outputs | ADD (evo) |
| Regime-wise performance | `guardMetrics.regimeOos*` | EXISTS |
| Slippage/cost sensitivity | stress re-runs (§3.2.5) | ADD (evo orchestration; mechanics exist) |
| Explainability score | 0.4·(% trades with full entry breakdown + reason-coded exit) + 0.3·(% gate operands persisted) + 0.3·(1 − activeDOF/12). Exit-reason completeness on candle-path runs needs audit P1-3. | ADD |
| Review-readiness | boolean checklist: all hard gates green + holdout consumed + scorecard complete + caveats acknowledged + proposal card rendered | ADD |

### 6.3 Ranking output

Scorecard JSONB (persisted on `evo_candidates`, served to FE §10):

```json
{ "robustScore": 1.42, "rank": 2, "weights": {...},
  "gates": [{"id":"oos_sign","pass":true,"value":0.031}, ...],
  "components": [{"id":"stability","z":0.8,"raw":{"plateauRatio":0.91,...}}, ...],
  "penalties": {"dof":0.06,"caveats":0.0},
  "flags": ["REGIME_DEPENDENT:CRASH_UNTESTED"],
  "evidence": {"simRuns":[...], "holdoutRun":"...", "liveWindow":{...}},
  "comparator": {"championVersionId":"...", "delta":+0.19} }
```

This extends the existing `/best` guard-aware leaderboard shape rather than replacing
it (audit §13.11: extend, don't invent).

---

## 7. Backtest-vs-live reconciliation logic

Purpose: (a) fair comparison of sim and live evidence for the same version; (b) a
material-divergence tripwire that blocks promotion and triggers diagnosis.

### 7.1 Matched-window comparison (per version with live evidence)

1. Re-run the **exact version** over the **exact live window** through the job
   pipeline (same interval, same costs config, `purpose: reconcile` — a new purpose
   string; not holdout-guarded since the window is by definition already lived).
2. Pair trades: swing pairs naturally by (symbol, entry date) — live paper enters at
   the same daily close the sim fills at (audit §2 parity note), so overlap should be
   near-total; scalpers pair by signal id → `scalper_detail` leg vs sim leg.
3. Compute the **gap vector**:
   - `returnGap` = live window return − sim window return (annualized);
   - `tradeSetOverlap` = Jaccard on paired trade keys (swing target ≥ 0.9; a low
     overlap means the *inputs* diverged — screener/CA/feed issues — before fills did);
   - `entryPriceDelta` (bps) = live fill vs sim fill per paired trade — until audit
     P1-4/5 land, the proxy is `scalper_detail.option_ltp` vs the sim entry premium;
   - `exitReasonDivergence` = L1 distance between exit-reason distributions (needs
     audit P1-3 on the candle path);
   - `slippageRealized` vs modeled (post-P1-5 quote capture).
4. Persist as `reconciliations` rows (evo tables) keyed (version, window) — the FE
   backtest-vs-paper view (Prompt-1 §9.1) renders exactly this.

### 7.2 The divergence gate

`gapZ = returnGap / σ(fold returns)` for the matched duration.
- `gapZ ≥ −0.5`: aligned — `live_alignment` component rewards it.
- `−1.5 < gapZ < −0.5`: penalized proportionally in RobustScore.
- `gapZ ≤ −1.5` **with evidence floor met** (≥ 20 paired trades or ≥ 4 weeks):
  **DIVERGENT** — hard-gate fail; promotion blocked; an automatic diagnosis checklist
  attaches to the candidate card:
  1. data-health incidents in the window (feed canaries, subscriber gaps, batch
     misses — audit §3 canary set);
  2. partial-bucket canary state (audit P0-1 — until fixed, coarse-primary scalper
     live evidence is quarantined wholesale, see §13);
  3. flag-regime changes mid-window (needs audit P1-7 flag snapshot);
  4. cost/slippage realized vs modeled;
  5. trade-set overlap — if low, the divergence is upstream (screener/data planes,
     audit D1/D12), not execution.
- Structural, *known* divergences are whitelisted per family and stamped on the card
  rather than counted (LIVE_FIRST: sim trade set ≠ live trade set **by design** —
  reconciliation for scalpers therefore runs **shadow-vs-paper** (same plane, same
  gate) plus counterfactual-replay agreement, never raw backtest-vs-live).

### 7.3 Continuous live-quality watch (post-promotion)

Every promoted version gets a rolling 4-week reconciliation; two consecutive DIVERGENT
windows → automatic ROLLBACK proposal (§8). The pinned parity surfaces (goldens,
exit-equivalence fixture) are the null hypothesis: if *those* drift, it's an engine
bug, and the engine files a defect proposal instead of a strategy verdict.

---

## 8. Promotion, rejection, approval, and rollback criteria

### 8.1 Candidate state machine (extends F7's PAPER → TAKE_ELIGIBLE)

```
PROPOSED → EVALUATING → SCORED ─┬→ RETIRED (fail gates / dominated 2 generations)
                                └→ SURVIVOR
SURVIVOR → [owner approves PUBLISH_PAPER] → PAPER (live evidence accrual)
PAPER → TAKE_ELIGIBLE (F7 thresholds + §6 gates + §7 not-DIVERGENT)
TAKE_ELIGIBLE → [owner approves PROMOTE] → PROMOTED (published; becomes champion)
PROMOTED → [rollback trigger + owner approves ROLLBACK] → ROLLED_BACK
```

### 8.2 Rules (concrete defaults; owner-tunable per campaign)

- **RETIRE (autonomous, reversible)**: any hard-gate fail after full evaluation; or
  RobustScore < champion − 0.5 for 2 consecutive generations; or staleness (data
  epoch moved and the re-run fails gates). Retired ≠ deleted — archived draft +
  scorecard stay.
- **PUBLISH_PAPER proposal**: SURVIVOR + RobustScore ≥ champion − ε (ε = 0.1 — near-
  champion challengers earn live evidence) + holdout consumed. Owner one-click; the
  version publishes into the **evo paper lane** (its family book, evo tag, capped
  §1.4). Publishing is owner-gated because it changes live-paper behavior (HOLD-tier
  precedent).
- **TAKE_ELIGIBLE**: existing F7 thresholds (e.g. ≥ 50 closed paper trades) + §6 hard
  gates on the live window + live-gap gate + RobustScore(live-weighted) ≥ champion.
- **PROMOTE proposal**: TAKE_ELIGIBLE + the composed-variant explanation card (§5.3
  when applicable). Owner approval **always** — the engine never arms live behavior
  (house rule; F7 promotion stayed measurement-only for exactly this reason).
  On approval: registry publish (existing), champion pointer moves,
  **the demoted champion is auto-registered as a shadow/paper variant for 6 weeks**
  — the rollback counterfactual runs live from day one.
- **ROLLBACK trigger (autonomous proposal, owner-approved action)**: promoted
  version's rolling 4-week live RobustScore < its own pre-promotion OOS band (mean −
  1σ) **and** < the demoted champion's concurrent shadow performance, 2 consecutive
  windows; or any DIVERGENT verdict ×2; or a risk event (daily-loss trip attributable
  to the version). Action = registry rollback (existing copy-forward + publish) +
  incident note auto-attached to the campaign.
- **Approval mechanics**: `evo_proposals` inbox (§10) with 7-day expiry; approve/
  reject writes actor + audit rows; optionally mirrored to the Telegram two-phase
  `/confirm` pattern (V019) for away-from-desk approvals. Every proposal card carries:
  what changes, evidence summary, gates table, "why superior" lineage, blast radius
  (which book/engine), and the rollback plan.

### 8.3 Rejection ≠ silence

Every rejection persists its reason (gate ids, IS-only-lift verdicts, dominated-by).
The campaign report's "graveyard" section is a first-class output — it is how the
system proves it is not cherry-picking and how it avoids re-testing dead ideas.

---

## 9. Example workflow for one strategy — Manas Arora swing (SIM_FIRST)

**Setup (owner, ~10 min).** `POST /evolution/campaigns` for base strategy
`manas-arora-swing` (registry UUID): evidence_policy SIM_FIRST; search space from its
YAML optimize block — `atr_mult [2.0..4.0 step 0.25]`, `trail_arm_pct [6..12 step 1]`,
`volume_floor_multiple [15..40 step 5]`, `rs_min {60,65,70,75,80}`,
`liquidity_multiple {15,25,50}`, `max_open {8,10,12,14}` (6 dims = the cap); budget
{maxGenerations 8, maxTrialsPerGen 300, holdoutTouches 6, cadence weekly}; holdout =
2025-07-01→2026-06-30, StressGuard-registered; objective_spec = swing defaults (§6).
Incumbent champion = the live v1.0.1 config.

**Generation 1 (autonomous, ~1 night on the worker pool).**
1. Screen: 120 LHS trials × walk-forward (train 750/test 125/step 125 over
   2015→2025-06). Each trial = one jobs row + fold columns (existing).
2. Exploit: 180 TPE trials seeded from screen.
3. Stabilize: plateau top-12; 9 need neighbor probes → 41 probe trials submitted.
   4 single-point peaks die (`plateauObjective` 0.55–0.7 × raw).
4. Cost-stress: top-8 re-run at 2×/4× slippage_bps.
5. Score: hard gates kill 3 (one regime-dependent — `regimeOosMin` −9 % in BEAR vs
   mean +4 %; two fold-consistency fails). 5 SCORED; 2 SURVIVORS beat champion:
   e.g. candidate g1-c07 `{atr_mult 3.25, trail_arm 9, vol_floor 25, rs_min 75,
   liq 25, max_open 12}` RobustScore +0.31 vs champion (stability z +1.1, DD z +0.6,
   oos z +0.2 — the narrative: *same return, materially steadier*).
6. Holdout: both finalists one-shot pass (g1-c07 holdout +11.2 % vs OOS-mean 13.8 % —
   ratio 0.81 ≥ 0.5 ✓). StressGuard touches 2/6.
7. Importance/brittleness update: `rs_min` dominant positive; `vol_floor` near-zero
   importance (candidate for DOF freeze next generation); `atr_mult` moderately
   brittle below 2.5.
8. Proposals filed: PUBLISH_PAPER(g1-c07), RETIRE ×7 (auto), REVIEW_GATE suggestion:
   "add RegimeLabeler CRASH entry-suppression — 3 of 5 scored candidates lose only in
   CRASH folds" (pre-registered for gen-2 ablation).

**Owner (5 min in the inbox).** Reviews g1-c07's card (params diff vs champion, fold
chart, regime strip, stress slope, holdout tile) → approves PUBLISH_PAPER. g1-c07
publishes into the manas-arora book's evo lane; the 20:05 batch now papers it
alongside the champion.

**Generation 2 (next week).** Param stream continues (vol_floor frozen, rotation
admits `pyramid.enabled` re-test as a variant — doctrine-flagged); structure stream
runs the pre-registered CRASH-gate ablation paired against g1-c07: OOS lift +1.9 %/yr,
positive in BEAR+RANGE+BULL, trade retention 88 %, paired p 0.06 → ACCEPTED →
composed candidate g2-c01 (params + gate).

**Weeks 3–10.** g1-c07/g2-c01 accrue paper trades; weekly reconciliations run
(trade-set overlap 0.94, entry deltas ≈ 0 by construction, returnGap z −0.3 —
aligned). At ≥ 50 closed trades + gates green → TAKE_ELIGIBLE; PROMOTE proposal with
the composed explanation ("+0.42 RobustScore vs incumbent: 60 % from stability/DD,
25 % regime gate, 15 % rs_min 70→75; live 8 weeks aligned"). Owner approves; champion
pointer moves; old champion runs 6 weeks as the rollback shadow. Campaign continues
or is CLOSED by the owner.

**Scalper contrast (LIVE_FIRST, compressed).** Campaign on
`scalp-connect-the-dots-nifty`: knob-set candidates {relative-vol k 1.25/1.5/2.0 ×
N 10/20, composite floor 0.68/0.70/0.72} → 6 shadow challenger variants (cap);
4 weeks paired evidence → k=1.25/N=20 shows paired lift +₹412/event, p 0.04, plateau
neighbors positive → counterfactual replay agrees → PUBLISH_PAPER proposal for the
knob-set version; backtests never enter the ranking (functional smoke only).

---

## 10. Frontend presentation requirements

Builds directly on Prompt-1 §7/§9 surfaces (jobs WS progress, SweepDetailPage,
ComparePage, FoldDrilldownModal, GraduationPage, rejections/shadow league) — evolution
adds one nav section and reuses drill-downs rather than duplicating them.

**New routes** (Evolution section):
1. `/evolution` — campaign board: per campaign a card (strategy, family badge,
   policy badge, generation N, champion vs best-challenger RobustScore spark, pending
   proposals count, budget burn — trials + holdout touches).
2. `/evolution/:campaignId` — the campaign workspace, 4 tabs:
   - **Leaderboard**: candidates table (RobustScore, rank, gates-passed chips, state,
     flags) with filter/sort/search/tag (Prompt-1 P2-2 saved views); row → candidate
     card.
   - **Candidate card** (the core artifact): params diff vs parent AND vs champion
     (registry diff component reused); hard-gate checklist with values; RobustScore
     stacked-bar breakdown (component z × weight); OOS fold chart (existing folds
     viz) + regime heat strip (BULL/RANGE/BEAR/CRASH); cost-stress slope sparkline;
     live-gap tile (gapZ dial + paired-trade table when live); holdout tile;
     caveats/flags; buttons: open sweep detail / open run results / clone-and-rerun
     (paramsPatch dialog) / propose. Drill-down chain ends at the existing
     trades-table → chart-overlay pages — summary to trade-level evidence in ≤ 3
     clicks.
   - **Insights**: parameter-importance tornado; brittleness heatmap (param ×
     local-variance, importance-sized cells); 2-D plateau slices for top pairs;
     structure-experiment ledger (accepted/rejected ablations with paired-lift CIs —
     including the IS-only-rejected graveyard).
   - **Generations**: timeline (proposal pre-registration, trials, probes, holdout
     touches, engine SHA / data epoch per generation — the audit trail rendered).
3. `/evolution/proposals` — the approval inbox: PENDING cards grouped by kind, each
   with the §8.2 card payload; Approve/Reject with note (XSRF, audit row); expiry
   countdown; batch approve for RETIREs. This is the Prompt-1 §11.6 checkpoint
   workflow's first concrete instance.
4. Promotion pipeline strip (on `/evolution` and the strategy page): kanban
   PROPOSED→…→PROMOTED per family, so "what is close to live" is one glance.

**Integration touches**: strategy list rows gain an "evolution" chip (active campaign
→ campaign link); GraduationPage links TAKE_ELIGIBLE rows to their candidate cards;
backtest-vs-paper view (Prompt-1 §9.1) renders §7 reconciliations; signals/paper pages
tag evo-lane rows.

**House rules honored**: typed `{items:[…]}` envelopes; mobile — cards reflow, dense
tables get the DataTable card mode (Prompt-1 F9 fix applies here first); every chart
downloadable once Prompt-1 P2-3 export lands; no new "Coming soon" stubs.

---

## 11. Backend service requirements

**optimizer-service (Python — the evolution home; no new service):**
- Campaign orchestrator module: generation loop, scheduler (APScheduler/asyncio cron
  in-service), budget/StressGuard accounting, scoring engine (§6), reconciliation
  computer (§7). **Hard requirement: durable orchestration** — campaign/generation
  state machine persisted per step so a restart resumes (Prompt-1 P2-1 pattern; the
  in-memory sweep thread model is insufficient for week-long campaigns).
- New endpoints (gateway allowlist: add `Path=/api/v1/evolution` → optimizer-service —
  remember the Prompt-1 route-allowlist trap):
  `POST/GET /api/v1/evolution/campaigns`, `GET/POST /campaigns/{id}` (+`/pause`
  `/resume` `/close`, `/generations` trigger), `GET /campaigns/{id}/candidates`,
  `GET /candidates/{id}` (scorecard), `POST /candidates/{id}/rerun`,
  `GET /campaigns/{id}/insights` (importance/brittleness/slices),
  `GET/POST /evolution/proposals` (+`/{id}/approve|reject`),
  `GET /campaigns/{id}/report`, `GET /evolution/reconciliations?versionId=`.
  All typed, enveloped, offset-paginated (house conventions).
- Sweep submission reuses `POST /optimizations/run` internals (pinning, fold guards,
  promote); neighbor probes and stress re-runs are ordinary trial jobs.
- Notifications: proposal-created / DIVERGENT / rollback-trigger → ntfy (existing
  plumbing), and these double as the job-completion notify gap (Prompt-1 A5) for evo.

**backtest-service (Java — additions only):**
- MetricsCalculator: recovery factor, turnover, trade frequency (additive, golden-safe
  — metrics JSONB only).
- `purpose: reconcile` accepted (skip StressGuard, stamp purpose).
- Exit-reason attribution on the candle path (audit P1-3) — explainability + §7.4
  depend on it.
- Nothing else: folds, MC, holdout, plateau inputs all exist.

**strategy-signal-service (Java — additions only):**
- Shadow challenger variants registrable at runtime per campaign:
  `POST/DELETE /api/v1/shadow-variants` (campaign-scoped knob-set defs → the existing
  `ShadowVariants` mechanism), cap-enforced; today's static config remains the
  fallback.
- Evo paper lane: candidates publish with an `evo` tag; book resolution unchanged
  (first-tag), per-book caps already enforced by risk settings; add the per-family
  evo-strategy cap check at publish.
- `created_by` actor plumb through registry create/promote (audit T3/R4) so
  `evo:{campaignId}` provenance is a column, not a note.
- F7 `strategy_graduations` gains the TAKE_ELIGIBLE↔candidate link (one column).

**Storage/infra:** evo tables in the backtest schema (optimizer already writes there);
Optuna storage stays Postgres; Redis unchanged. **No stack swaps required.** Two
optional, additive suggestions only: (a) `scipy` in optimizer-service for the paired
Wilcoxon (or implement the exact test by hand — n is small); (b) the Prompt-1 optional
parquet snapshot for keystone campaign panels. Everything else — Optuna, Timescale,
Redis Streams, React — is already the right tool.

---

## 12. Phased implementation roadmap

PR-sized, house merge tiers (clean / HOLD), each with a verify check. E0 is the gate;
E1+ can interleave with Prompt-1 phases.

**E0 — prerequisites (from Prompt 1; not evo code):** P0-1 (partial buckets — gates
all LIVE_FIRST evidence), P0-2 (engine SHA), P2-1 (optimizer durability), P1-3
(exit-reason attribution), T3 actor plumb, §11.4 experiment views. Others degrade
gracefully (§13 table).

**E1 — experiment model + scoring on existing data [~1.5 wk, clean]**
1. `evo_*` migrations + typed read APIs (campaigns/candidates read-only first).
2. Scoring library (§6 gates + RobustScore + metric adds: recovery/turnover/frequency)
   applied **retroactively to existing sweeps** — verify: the historical Manas/scalper
   sweeps re-rank sensibly, plateau winners surface, scorecards render.
3. Campaign/generation recorder wrapping a manually-triggered sweep (no autonomy yet).

**E2 — search upgrades [~1.5 wk, clean]**
4. Neighbor-probe submission for top-K (`plateauObjective` becomes estimated).
5. Cost-stress re-run orchestration (2×/4× slippage) + degradation slope in scorecards.
6. Deflated-Sharpe multiplicity gate; DOF penalties; importance/brittleness insights
   endpoints. Verify: a deliberately overfit toy sweep is rejected by the gates.

**E3 — live-evidence integration [~2 wk, clean + one HOLD]**
7. Reconciliation computer + `purpose: reconcile` + reconciliations API (clean).
8. Runtime shadow-variant registration API (HOLD — touches the live engine's shadow
   path; adversarial review) + campaign wiring for LIVE_FIRST.
9. Counterfactual-replay job type (runbook E9 formalized) on captured data (clean).
10. Live-gap gate + DIVERGENT checklist in scoring. Verify: champion-vs-itself
    reconciliation reads aligned (gapZ ≈ 0) over a known-clean window.

**E4 — proposals, approvals, promotion pipeline [~1.5 wk, HOLD-heavy]**
11. `evo_proposals` + inbox API + ntfy (clean).
12. PUBLISH_PAPER flow: approve → publish to evo lane with caps (HOLD — changes
    live-paper behavior).
13. TAKE_ELIGIBLE/PROMOTE/ROLLBACK rules + demoted-champion shadow auto-registration
    (HOLD). Verify: full state machine walked on a mock-stack candidate end-to-end.

**E5 — structure experimentation [~1.5 wk, clean]**
14. Pre-registered ablation protocol + paired evaluation + IS-only auto-reject +
    graveyard. 15. Gate-candidate suggesters (rejection forensics, regime gates,
    unused indicators). Verify: a known-noise indicator is rejected; the CRASH-gate
    example reproduces on historical folds.

**E6 — autonomy + FE suite [~2-3 wk, clean]**
16. Scheduler/cadence + budgets + expiry + campaign reports.
17. FE: `/evolution` board, campaign workspace (leaderboard/candidate card/insights/
    generations), proposal inbox, pipeline strip, integration chips. Verify: the §9
    Manas walkthrough executes end-to-end with every artifact visible in the UI.

Autonomy is switched on last (E6), after every scoring and safety rung has run
supervised through E1–E5 on real campaigns.

---

## 13. Dependencies from Prompt 1

Missing inputs that block or degrade implementation, with the Prompt-1 gap ids. HARD =
do first; DEGRADED = evo ships with the listed workaround and upgrades in place.

| # | Prompt-1 item | Why evo needs it | Without it | Class |
|---|---|---|---|---|
| 1 | **P0-1 partial coarse-bucket fix** (+ its canary) | LIVE_FIRST evidence (shadow/paper for 3m/5m scalpers) is computed off poisoned bars; knob tuning on it is tuning to an artifact | **HARD for LIVE_FIRST campaigns** — until fixed + a quarantine date stamped, scalper campaigns must not launch; pre-fix live evidence is excluded wholesale | HARD |
| 2 | **P0-2 engine SHA on runs/jobs** | Cross-deploy ranking validity; generation comparability | Rankings silently mix engine behaviors — the first thing a longitudinal optimizer trips over (audit R1) | HARD |
| 3 | **P2-1 optimizer durability** (+ sweep list) | Campaigns are week-scale; the in-process thread model loses them on any restart | Campaign orchestration cannot be trusted to run unattended | HARD |
| 4 | **T3/R4 actor plumb (`created_by`)** | Machine-readable `evo:{campaignId}` provenance on versions; approval audit trail | Provenance lives in free-text notes; approval workflow un-auditable | HARD (small) |
| 5 | **§11.4 experiment views + compare endpoint (#20)** | The scorecard/card UI reads the codified join graph; server-side compare feeds the leaderboard | Evo re-implements 8 hand joins (the exact debt Prompt 1 flagged) | HARD (or built as part of E1) |
| 6 | **P1-3 exit-reason attribution (candle path)** | Explainability score; §7 exit-reason divergence | Explainability degrades to entry-side only; exit forensics blind on candle-path runs | DEGRADED |
| 7 | **P0-5 BTST exit simulation** | Any BTST sim evidence | BTST campaigns run SIM_BLOCKED (live/paper only) — acceptable but slow | DEGRADED |
| 8 | **P0-3 swing portfolio-mode in the job pipeline** | Slot-level (book) metrics for swing campaigns — DD/CAGR at the portfolio, not per-name | Campaigns score per-name expectancy + regime/stability (valid today via the 1d-primary job pipeline); portfolio effects (slots, deployment) validated only at the paper stage | DEGRADED |
| 9 | **P1-1 dataset epochs + content-stable hash** | Comparability + the re-run policy (#30) | data_hash over-sensitivity forces excess re-runs; epoch drift detected but noisy | DEGRADED |
| 10 | **P1-2 costs knob + instrument class** | Correct statutory class in cost-stress for futures-signal strategies | Slippage stress works today (params_override on fills paths); cost-class stress skewed for futures until fixed | DEGRADED |
| 11 | **P1-4/P1-5 order events + quote capture** | Fill-quality/`entryPriceDelta` precision in §7 | Proxy = `scalper_detail.option_ltp` deltas; slippage-realized metric absent | DEGRADED |
| 12 | **P1-7 flag snapshot + ledger** | Stratify live evidence by knob regime; DIVERGENT checklist item 3 | Live evidence windows crossing a flag flip are discarded manually | DEGRADED |
| 13 | **P0-4 screener CA adjustment** (+ D12/D13 follow-ons) | Swing funnel-coupled parameters (rs_min etc.) are tuned against screener outputs; unadjusted inputs distort them | Campaign tuning of funnel-coupled knobs deferred; engine-plane knobs (exits, sizing) unaffected | DEGRADED |
| 14 | **P2-2 run tags/notes + saved views** | Experiment browser ergonomics; candidate/job tagging | Evo keeps its own linkage (evo tables) — UI filtering poorer until then | DEGRADED |
| 15 | **#22a provenance block (forks/fillModel/costModel/flags/profile)** | Scorecard caveat penalties read it; comparability checks | Caveats computed from metrics JSONB + convention — weaker guarantees | DEGRADED |
| 16 | **#22b `evidencePolicy` strategy tag** | The §1.2 routing stamp | Policy lives only in evo_campaigns (acceptable; tag makes it registry-visible) | DEGRADED |
| 17 | **T1 book-on-signal materialization** | Stable book attribution for live evidence joins | Read-time tags join — history rewrites if tags change mid-campaign (freeze tags during campaigns as the workaround) | DEGRADED |
| 18 | **Prompt-1 §9.1 backtest-vs-paper view** | The reconciliation UI home | §7 data renders in the campaign workspace only | DEGRADED |

No other blockers found. Prompt-1's architecture, data contracts, and workflow model
are consumed as-is; the one re-flagged critical item is #1 (P0-1), because an
optimizer pointed at poisoned live evidence would *systematically institutionalize*
the artifact it was built to eliminate.
