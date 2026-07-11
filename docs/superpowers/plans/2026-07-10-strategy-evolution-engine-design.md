# Autonomous strategy evolution & optimization engine — design (Prompt 2)

**Date:** 2026-07-10 · **Fixed input:** the research-fidelity audit
`docs/audits/2026-07-10-research-fidelity-audit.md` (merged `main@b246c8ce`) — its §13
"Inputs required by Prompt 2" is the contract this design consumes; its §10 gap ids
(P0-x/P1-x/P2-x) and §12 roadmap items are referenced throughout. Nothing from Prompt 1
is redesigned; one critical blocker is *re-flagged* (P0-1, §13 here) because it poisons
the live-evidence plane this engine depends on.

**Verification note.** A post-draft 2-pass verification (3 adversarial accuracy agents
over ~155 factual claims + 1 completeness agent vs the commissioning requirements)
found 5 refuted claims and ~25 corrections — all incorporated in this revision. The
verification also surfaced **three previously-unknown platform gaps** now carried as
§13 rows 19–21 (swing funnel-universe resolution in the job pipeline, the
regime-label vocabulary bug, the closed parameter-path grammar) and one latent
platform bug filed independently (`regimesCovered` always empty on real folds).

**Design stance.** This is not a new service and not a new ML platform. The platform
already contains ~70 % of an evolution engine: a seeded Optuna sweep lane with a
plateau (parameter-stability) leaderboard, walk-forward folds with per-regime OOS
attribution, a holdout-contamination guard, an immutable strategy-version registry with
draft/publish/rollback, a shadow book that trades *rejected* entries under challenger
knob-sets on live data, per-book paper accounting, and a graduation gate. The evolution
engine is the **orchestration brain + scoring model + promotion workflow** wired over
those parts, plus a small set of new tables and endpoints in `optimizer-service`.

Path abbreviations as in Prompt 1 (SSS/BT/MDS/LIB/OPT/FE).

**Status (2026-07-11).** All **7 E0 HARD prerequisites** (§12 E0 / §13 rows 1–5 + 19–20)
were **closed** by the 2026-07-10/11 overnight run — the platform gate this design waits on
is lifted: P0-1 partial buckets → **#683**, P0-2 engine SHA → **#703**, P2-1 optimizer
durability + sweep list → **#708**, T3 actor plumb → **#710**, §11.4 experiment views +
compare → **#714**, row 19 swing funnel-universe resolution → **#706**, row 20 regime-label
vocabulary reconciliation → **#705** (all merged + deployed + live-verified). §13 **row 21**
(param path-grammar, DEGRADED) shipped **2-of-3** as **#716** — gate-expression constants +
`risk.max_positions`; the screener/funnel props were refused (no config leaf; chip
`task_2560273c`). §13 **row 22** (worker-pool cap, DEGRADED) shipped as **#717**. **E1**
(experiment model + scoring on existing data) is therefore **buildable**; the build itself
awaits the owner's program-sequencing call (evolution vs. the intelligence layer) and the
E6-autonomy pre-decisions this design flags. Nothing here self-arms; the evo engine build is
NOT started. Design content below is unchanged.

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
     └─ Proposals   — PUBLISH_PAPER / PROMOTE / ROLLBACK / REVIEW_GATE (owner-gated)
                      + RETIRE (auto-applied, audit-rowed — §8.2)
```

A **campaign** is per base strategy, created by the owner (or suggested by the engine),
with an explicit budget: max generations, max trials/generation (probes, stress
re-runs and holdout runs are counted in **separate** budget buckets, not against the
trial cap), max holdout touches, max concurrent shadow variants, and a wall-clock
cadence. Note the holdout budget is **new evo enforcement**: today's StressGuard
counts window reuse but deliberately never refuses an exact-window repeat
(BT/jobs/StressGuard.java:24-29) — evo's orchestrator enforces the budget on top of
the counter.

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

**SIM_BLOCKED mechanics (BTST):** until P0-5, BTST campaigns run a reduced loop — no
sweeps; candidates come only from owner hypotheses + structure suggestions; evidence =
the paper A/B lane exclusively (the shadow book cannot host BTST — it materializes
*rejected scalper* entries only), gated by the §6.1 LIVE_FIRST column with the paper
floors (≥ 40 paper trades, weekly windows). When P0-5 lands, the campaign flips to
LIVE_FIRST-with-sim-smoke: backtests join as functional verification, ranking stays
live-evidence-led (the family remains gate-armed intraday options).

### 1.3 The control loop (per campaign)

```
1. PROPOSE     — generation N pre-registers sampler, seed, trial count, and any
                 structure mutations (§5) BEFORE evaluation (the snooping ledger).
2. EVALUATE    — SIM_FIRST: sweep trials over walk-forward folds — parameter vectors
                 live ONLY in optimization_trials at this stage, no version rows.
                 LIVE_FIRST: register challenger knob-sets as shadow variants +
                 counterfactual replay jobs; accrue ≥ the family's min evidence window.
3. SELECT      — plateau-rank; submit neighbor probes for the top-K; cost-stress
                 re-runs; pick survivors. Parameter-importance + brittleness update.
4. MATERIALIZE — ONLY the selected survivors (plateau top-K, typically ≤ 15/gen —
                 never all trials) become evo_candidates rows + registry DRAFT
                 versions (existing promote/clone mechanics, checksum-deduped),
                 created_by = 'evo:{campaignId}'. Losing vectors stay as trial rows —
                 no version-table bloat.
5. SCORE       — hard gates, then RobustScore (§6); finalists take their one-shot
                 holdout; scorecards persisted.
6. PROPOSE ACTIONS — proposals to the owner inbox (§8): PUBLISH_PAPER, PROMOTE,
                 ROLLBACK, REVIEW_GATE. RETIREs auto-apply with an audit row (§8.2).
                 NOTHING self-arms or self-publishes.
7. SLEEP       — until the next scheduled cadence (swing: weekly; scalper: after
                 each evidence window) or an owner trigger.
```

Determinism & traceability: every generation records `engine_sha` (audit P0-2),
`data_hash`/epoch refs (P1-1), the search-space hash, the sampler seed, and the
StressGuard touch count. Rankings **refuse to compare across differing engine SHA /
data epoch** — the stale side is re-queued instead (audit §13 comparability rule,
roadmap #30).

### 1.4 Safety invariants (non-negotiable, encoded not documented)

1. The engine writes **drafts and proposals only**. Publish, arm, promote, rollback
   all require owner approval (house owner-gated tier; F7 precedent: measurement-only).
2. Holdout windows are touched at most once per finalist, budgeted per campaign.
   Enforcement is layered: StressGuard 422 blocks *overlap with different windows*
   and counts reuse, but exact-window repeats are counted-not-refused today
   (StressGuard.java:24-29) — the evo orchestrator adds the hard budget check.
3. Live-plane experiments (shadow variants, paper lanes) are capped per family
   (default: ≤ 6 concurrent challenger variants per strategy, ≤ 2 evo paper
   strategies per family book) and ride default-OFF flags the owner arms once.
4. Mock-profile evidence is never scored (audit §13 preamble).
5. Every autonomous write lands an audit row (`strategy_audit_log` action set
   extended — its CHECK constraint means a new suffix-versioned migration, per the
   checksum-lock rule; `evo_proposals` is itself append-only).
6. Global compute fairness: evo trials run under a **global concurrency cap**
   (default ≤ 50 % of the shared cores−2 worker pool, off-hours preferred) with
   interactive backtests taking priority — the shared pool serves the owner's
   interactive runs too (audit A6's runaway-sweep warning applies to evo doubly).

---

## 2. Experiment and versioning model

### 2.1 Reuse (no redesign)

- **Candidate = `strategy_versions` DRAFT row** — immutable, checksum-deduped,
  byte-preserved YAML, structured diff, publish/rollback lifecycle already exist
  (audit §6 R7). The optimizer's promote-to-draft (§D.9) is the materialization
  mechanic; evo generalizes it from "one winning trial" to "every candidate".
- **Runs/trials/folds/Monte-Carlo/holdout** — the existing jobs spine untouched.
- **Live evidence** — `signals` (version FK) and `shadow_positions` (+`variant`)
  are version-keyed directly; `paper_positions/orders` reach the version transitively
  via `signal_id`; `strategy_graduations` is keyed by strategy only (the version link
  is a §11 addition). (Audit §6 lineage.)

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
  id uuid PK, campaign_id FK,
  candidate_id FK NULL,          -- NULL for campaign-level REVIEW_GATE suggestions
  kind text
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
- Only materialized candidates occupy version rows (§1.3 step 4); losing trial vectors
  never touch the registry. Materialized losers stay as ARCHIVED drafts (queryable
  history). Checksum-dedupe caveat: the registry compares only against the *latest*
  version and answers a **409** (not a silent no-op) — the evo materializer therefore
  dedupes against its own `evo_candidates.params` history before calling the registry.
- **Knob planes** (decides what can be a candidate dimension at all):
  **VERSION-plane** knobs live in the strategy YAML and are addressable by the
  parameter-path grammar → legal campaign dimensions. **ENV-plane** knobs
  (`ARTHA_*`/`artha.*` props — e.g. the relative-vol floor k/N, which are *global
  across all scalpers*) are NOT candidate dimensions: a version row cannot carry
  them, and flipping them A/Bs every strategy at once. ENV-plane knobs are evaluated
  only through shadow variants (where the variant vocabulary can express them) or
  counterfactual replay, and productizing a winner means either promoting the env
  default (owner .env action) or building a per-strategy override into the YAML
  (a schema addition — new work, §13 row 21 adjacent). Every campaign search space
  declares each dimension's plane explicitly.
- Clone-and-rerun (user-driven): `POST /evolution/candidates/{id}/rerun
  {paramsPatch}` → new candidate in an ad-hoc "manual" generation — same lineage
  rows, so hand experiments and autonomous ones live in one history.

---

## 3. Parameter search methodology

### 3.1 Search space discipline (anti-overfit before any search runs)

- Space = the strategy's YAML `backtest.optimize.parameters` (1–20 entries, typed
  range/step/choices — per-item schema `$defs/optimizeParameter`), **capped by evo**:
  ≤ 6 active dimensions per generation (excess dims frozen at incumbent values,
  rotated across generations). Rationale: DOF control is a first-class scoring
  penalty (§6), and 20-dim TPE on ~10 y of daily data is a curve-fitting machine.
- **Grammar bound (hard, verified):** the parameter-path grammar is a closed
  4-production whitelist — `indicators[…].params.*`, `exit_rules[…].params.*`,
  `entry_rules.scoring.*`, `risk.position_sizing.*` — enforced at the schema pattern,
  the optimizer submit (`path_grammar.py` → 400), and the backtest apply
  (`ParameterPaths`/`TrialOverrides`). Gate-expression constants (they are string
  literals), `risk.max_positions`, costs/fills fields, and screener/funnel props are
  **not tunable today**; widening the grammar means changing all three synchronized
  enforcement points (§13 row 21). Gen-1 campaigns therefore tune exit / sizing /
  scoring / indicator params only.
- Bounds must be *doctrine-plausible* (owner-reviewed once at campaign creation):
  e.g. Manas ATR trail multiplier 2.0–4.0, not 0.1–20. The campaign freezes the
  space; changing it = a new campaign (comparability).
- Every generation pre-registers its sampler, seed, and candidate count **before**
  evaluation (`evo_generations.proposal`) — the data-snooping ledger.

### 3.2 Staged search (per generation, SIM_FIRST)

1. **Screen** — `grid` (small spaces) or seeded `random` (larger; LHS/QMC would be a
   new sampler — the method enum is closed at grid|random|tpe|nsga2): ~30–50 % of the
   trial budget, full walk-forward folds per trial (existing pipeline; objective
   auto-overridden to `oos_fold_mean` — already enforced, OPT/service.py:87-103).
2. **Exploit** — seeded `tpe` warm-started from screen results for the remainder.
3. **Stabilize** — for the plateau-ranked top-K (existing leaderboard: each trial
   re-scored as the **median objective of itself + its neighbors**, where a neighbor
   must be within one step/ε in *every* tuned param — OPT/leaderboard.py:23-38, 86-95):
   where `neighborCount < 4`, actively submit the missing neighbor trials ("neighbor
   probes") so plateau scores are estimated, not accidental. Survivors need
   `plateauObjective ≥ 0.8 × rawObjective` — single-point peaks die here.
4. **Pareto pass (optional)** — `nsga2` (exists) on 2–4 objectives
   (`oos_fold_mean` ↑, `max_drawdown` ↓, `oos_fold_std` ↓) when the owner wants the
   frontier view; final selection still goes through §6 scoring (no raw-profit picks).
5. **Cost-stress** — top-K re-run with slippage doubled and quadrupled.
   **Correction (verified):** `slippage_bps`/`slippage_ticks` are YAML *fields* but
   are NOT addressable by `params_override` — the path grammar has no costs/fills
   production. The mechanism is therefore a small backtest-service addition: a
   request-level `stressOverrides {slippageMultiplier}` field applied at
   `CostConfig`/fill construction and recorded in run provenance (golden-safe —
   goldens pin signals + trades of *unstressed* runs; stressed runs are new runs).
   Preferred over widening the grammar (three synchronized enforcement points) or
   minting stress drafts (churns candidate identity). Degradation slope feeds §6.
   (Statutory-class cost correctness for futures-signal strategies additionally
   needs Prompt-1 P1-2; §13 row 10.)
6. **Holdout** — ≤ 2 finalists per generation touch the campaign's holdout window
   once, via `purpose: stress_test` (StressGuard-enforced).

### 3.3 LIVE_FIRST search (scalpers)

Parameters here are mostly **gate knobs** (rail thresholds, composite floor,
relative-vol k/N, band widths). The search mechanism is the one that already earns
real-PnL labels on identical market data:

1. Candidate knob-sets → **shadow challenger variants** (existing `ShadowVariants`
   mechanics: rail threshold override / rail disable / composite floor; each accepting
   variant opens its own tagged 1-lot with cost-adjusted `pnl_net`). Evo needs these
   registrable per campaign via API instead of the current boot-time static env JSON
   (`artha.scalper.shadow-book.variants-json`) — §11. Knob-sets outside today's
   variant vocabulary (e.g. the ENV-plane relative-vol k/N, §2.3) need either a
   vocabulary extension or the counterfactual-replay path (item 4).
2. Evidence window: default 4 trading weeks or ≥ 60 champion-comparable events,
   whichever later.
3. **Paired evaluation**: variants are re-scored on the same event stream, so
   comparison is paired-by-event (Wilcoxon signed-rank on per-event net PnL + win-rate
   delta with a binomial interval), not independent-sample — small samples become
   decision-grade sooner. **Coverage bound (verified):** today's shadow writer fires
   only on the *rejection* path — champion-**accepted** entries never reach variant
   scoring, so a **tightening** knob-set (e.g. a higher composite floor) has no
   paired plane. Two options, both spec'd in §11: (a) extend the shadow writer to
   also score variants on accepted entries (champion-accepted / variant-rejected
   rows — HOLD tier, touches the live emit path), or (b) until then, restrict
   LIVE_FIRST shadow candidates to *relaxing-or-neutral* variants and route
   tightening candidates through counterfactual replay + the paper A/B lane. The
   engine enforces whichever mode is active.
4. **Counterfactual replay** (offline complement): the live-signal-analysis runbook's
   E9 method formalized as a job type — replay captured premium/chain over the same
   window under the knob diff, for knobs the shadow book can't express (e.g. exit
   band changes). Runs on captured (real) data only — never derived history.
5. Exit-knob candidates that pass counterfactual replay graduate to a **paper
   A/B lane** (evo paper strategies in the family book, capped, flag-armed §1.4).

### 3.4 Parameter effect & brittleness analytics (per campaign, updated per generation)

- **Importance**: Optuna importance over all campaign trials → ranked "strongest
  positive / negative effect" per parameter (the user-facing tornado). Dependency
  note: Optuna's fANOVA evaluator requires scikit-learn (not currently shipped);
  either add it or use an sklearn-free evaluator — pinned at build time.
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
  `regimeOosMin/Mean/Max`). **Label correction (verified):** the canonical regime
  vocabulary is **UP_QUIET / UP_TURBULENT / DOWN_QUIET / DOWN_TURBULENT**
  (BT/regime/RegimeLabel.java — trend×vol, frozen Stage-D guard 6), *not*
  BULL/RANGE/BEAR/CRASH; the optimizer/FE constants using the latter are a latent
  platform bug that leaves `regimesCovered` always empty on real folds (fix chip
  filed — §13 row 20; the ≥-3-regimes gate depends on it). Gates: `regimeOosMin`
  must not be catastrophic (default: ≥ −0.5 × `regimeOosMean`), and ≥ 3 of the 4
  labels covered for swing. A candidate that only wins in UP_QUIET is labeled
  REGIME_DEPENDENT — rankable but penalized, and its card says so.
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
2. **Regime gates**: `RegimeLabeler` labels as entry filters (e.g. suppress entries
   in DOWN_TURBULENT) — directly targets regime dependence. **Not cheap, flagged:**
   the frozen Stage-D design explicitly keeps regimes *reported, not optimized*
   (RegimeLabel.java:6-7; Stage-D S1A rejected regime-filter expressions) — a regime
   gate is a new LIB gate type + a live-side regime feed + a design amendment the
   owner ratifies before this stream runs.
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
  - lift ≥ 0 in ≥ 2 of the 3 majority regime labels (not UP_QUIET-only);
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
`manas-arora-breakout--evo-g7-c01`, the §8.2 sibling-clone convention) with the full
evidence chain (which mutation contributed
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
| Evidence floor | ≥ 60 OOS trades across folds (swing — the only SIM_FIRST family today; default 120 for any future higher-frequency SIM_FIRST family) | ≥ 60 paired shadow events or ≥ 40 paper trades (BTST/SIM_BLOCKED uses the paper floor only) |
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
                                            # runs: metrics.caveats[] entries;
                                            # oiGateCoverage below 80% (note: persisted
                                            # as a "42/45" label string — parse the
                                            # fraction); LIVE_FIRST liquidity caveat —
                                            # paper fills are 1-tick/no-spread until
                                            # audit P1-5, so paper P&L on illiquid
                                            # strikes is flagged, not trusted
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
| Slippage/cost sensitivity | stress re-runs (§3.2.5) | ADD (needs the `stressOverrides` request field — the path grammar cannot reach fills/costs today) |
| Explainability score | 0.4·(% trades with full entry breakdown + reason-coded exit) + 0.3·(% gate operands persisted) + 0.3·(1 − activeDOF/12). Exit-reason completeness on candle-path runs needs audit P1-3. | ADD |
| Review-readiness | boolean checklist: all hard gates green + holdout consumed + scorecard complete + caveats acknowledged + proposal card rendered | ADD |

### 6.3 Ranking output

Scorecard JSONB (persisted on `evo_candidates`, served to FE §10):

```json
{ "robustScore": 1.42, "rank": 2, "weights": {...},
  "gates": [{"id":"oos_sign","pass":true,"value":0.031}, ...],
  "components": [{"id":"stability","z":0.8,"raw":{"plateauRatio":0.91,...}}, ...],
  "penalties": {"dof":0.06,"caveats":0.0},
  "flags": ["REGIME_DEPENDENT:DOWN_TURBULENT_UNTESTED"],
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
   the daily close (SwingBatchEngine), and the reconcile re-run **pins
   `session.fill_timing: at_close`** so the sim fills there too (the job pipeline's
   swing default is NEXT_OPEN — without the pin, entry deltas would be one bar of
   noise, not evidence); scalpers pair by signal id → `scalper_detail` leg vs sim leg.
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
SURVIVOR → RETIRED (dominated 2 generations / staleness — §8.2)
PAPER → TAKE_ELIGIBLE (F7 thresholds + §6 gates + §7 not-DIVERGENT)
PAPER → RETIRED (live gates fail / DIVERGENT unexplained)
TAKE_ELIGIBLE → [owner approves PROMOTE] → PROMOTED (published; becomes champion)
PROMOTED → [rollback trigger + owner approves ROLLBACK] → ROLLED_BACK
```

### 8.2 Rules (concrete defaults; owner-tunable per campaign)

- **RETIRE (autonomous — applies immediately, no owner gate)**: any hard-gate fail
  after full evaluation; or RobustScore < champion − 0.5 for 2 consecutive
  generations; or staleness (data epoch moved and the re-run fails gates). Each
  RETIRE writes an auto-applied `evo_proposals` row (status APPROVED, actor
  `evo:{campaignId}`) — the inbox shows them as review/acknowledge items, never as
  pending gates. Retired ≠ deleted — archived draft + scorecard stay; reversible by
  re-proposing.
- **PUBLISH_PAPER proposal**: SURVIVOR + RobustScore ≥ champion − ε (ε = 0.1 — near-
  champion challengers earn live evidence) + holdout consumed. Owner one-click.
  **Materialization (important, verified constraint):** the registry holds ONE
  `published_version_id` per strategy — a candidate cannot paper *alongside* the
  champion as a version of the same strategy UUID. The evo paper lane is therefore a
  **sibling clone strategy**: slug `{base-slug}--evo-g{gen}-{shortId}`, first tag =
  the family tag (so `Books.fromTags` routes it to the same family book), `evo` tag
  second, config = the candidate version's YAML verbatim, linkage via
  `evo_candidates.version_id` + a `cloned_from` note. Approval publishes the clone;
  caps per §1.4.3. Publishing is owner-gated because it changes live-paper behavior
  (HOLD-tier precedent). Closing/retiring archives the clone (engine unloads it —
  existing archive semantics).
- **TAKE_ELIGIBLE**: existing F7 thresholds — ≥ 20 closed paper trades + PF ≥ 1.3 +
  expectancy > 0 + maxDD ≤ 25 % (the stricter ≥ 50-trades + Sharpe ≥ 0.5 bar is F7's
  GRADUATED auto-promotion marker, a different stage) — plus §6 hard gates on the
  live window + live-gap gate + RobustScore(live-weighted) ≥ champion.
- **PROMOTE proposal**: TAKE_ELIGIBLE + the composed-variant explanation card (§5.3
  when applicable). Owner approval **always** — the engine never arms live behavior
  (house rule; F7 promotion stayed measurement-only for exactly this reason).
  On approval: registry publish (existing), champion pointer moves, and **the demoted
  champion keeps running as the rollback counterfactual for 6 weeks** — as a shadow
  challenger variant for scalpers (the shadow book hosts scalper rejection-path
  entries only), and as a retained paper-lane clone for swing (the shadow book cannot
  host a daily-batch swing strategy). Either way the old champion's counterfactual
  P&L accrues live from day one.
- **ROLLBACK trigger (autonomous proposal, owner-approved action)**: promoted
  version's rolling 4-week live RobustScore < its own pre-promotion OOS band (mean −
  1σ) **and** < the demoted champion's concurrent counterfactual (shadow or paper
  lane per family, above), 2 consecutive
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

**Preconditions (verified — two one-time build/authoring steps).** (a) The Manas
registry strategies (`manas-arora-breakout`, `manas-arora-vcp` — there is no
`manas-arora-swing` slug) carry **no `backtest.optimize` block today**: the owner
authors one (a normal draft edit). (b) Their `universe.mode: manas_arora_funnel` is
**not resolvable by backtest-service** (and the request `universeOverride` is stored
but never read) — the funnel-universe resolver/pinning (§13 row 19, the
`futures_screener` submission-pinning precedent) must land before any Manas trial can
run through the job pipeline. Grammar bound (§3.1) also applies: of the doctrine
knobs, `atr_mult` and `trail_arm_pct` (exit-rule params) plus indicator/scoring
params are tunable now; the gate volume floor (an expression string literal),
`risk.max_positions`, and the MDS funnel props `rs-min`/`liquidity-multiple` need
the §13 row-21 grammar/funnel extension — they enter later generations.

**Setup (owner, ~10 min).** `POST /evolution/campaigns` for base strategy
`manas-arora-breakout` (registry UUID): evidence_policy SIM_FIRST; search space
(4 authored dims ≤ the 6-dim cap): `atr_mult [2.0..4.0 step 0.25]`,
`trail_arm_pct [6..12 step 1]`, `rsi_len {10,14,20}` (indicator param),
`scoring.threshold [0.55..0.75 step 0.05]`; budget {maxGenerations 8,
maxTrialsPerGen 300 (probes/stress/holdout in separate buckets), holdoutTouches 6,
cadence weekly}; holdout = 2025-07-01→2026-06-30 (evo-enforced budget; StressGuard
counts reuse); objective_spec = swing defaults (§6). Incumbent champion = the live
v1.0.1 config.

**Generation 1 (autonomous, ~1 night on the worker pool).**
1. Screen: 120 seeded-`random` trials × walk-forward (train 750/test 125/step 125
   over 2015→2025-06). Each trial = one jobs row + fold columns (existing).
2. Exploit: 180 TPE trials seeded from screen (300/300 trial budget).
3. Stabilize: plateau top-12; 9 need neighbor probes → 41 probe trials submitted
   (probe bucket, not the trial cap). 4 single-point peaks die (`plateauObjective`
   0.55–0.7 × raw). The 12 plateau survivors MATERIALIZE as evo_candidates + drafts.
4. Cost-stress: top-8 re-run at 2×/4× slippage (the `stressOverrides` request field,
   §3.2.5; stress bucket).
5. Score: hard gates kill 3 (one regime-dependent — `regimeOosMin` −9 % in
   DOWN_TURBULENT vs mean +4 %; two fold-consistency fails). 5 more score below the
   champion band. 2 SURVIVORS beat champion: e.g. candidate g1-c07
   `{atr_mult 3.25, trail_arm 9, rsi_len 14, threshold 0.65}` RobustScore +0.31 vs
   champion (stability z +1.1, DD z +0.6, oos z +0.2 — the narrative: *same return,
   materially steadier*).
6. Holdout: both finalists one-shot pass (g1-c07 holdout +11.2 % vs OOS-mean 13.8 % —
   ratio 0.81 ≥ 0.5 ✓). Holdout touches 2/6.
7. Importance/brittleness update: `atr_mult` dominant (moderately brittle below 2.5);
   `rsi_len` near-zero importance (candidate for DOF freeze next generation).
8. Proposals filed: PUBLISH_PAPER(g1-c07); RETIRE ×10 auto-applied (audit-rowed);
   REVIEW_GATE suggestion: "add a RegimeLabeler DOWN_TURBULENT entry-suppression —
   3 of 5 scored candidates lose only in DOWN_TURBULENT folds" (pre-registered for
   gen-2 ablation; carries the §5.1.2 caveat that a regime gate needs a Stage-D
   design amendment the owner ratifies first).

**Owner (5 min in the inbox).** Reviews g1-c07's card (params diff vs champion, fold
chart, regime strip, stress slope, holdout tile) → approves PUBLISH_PAPER. The
sibling clone `manas-arora-breakout--evo-g1-c07` publishes (first tag `manas-arora` →
same family book, §8.2); the 20:05 batch now papers it alongside the champion.

**Generation 2 (next week).** Param stream continues (`rsi_len` frozen, rotation
admits `pyramid.enabled` re-test as a variant — doctrine-flagged); structure stream
runs the pre-registered DOWN_TURBULENT-gate ablation paired against g1-c07 (owner
ratified the design amendment): OOS lift +1.9 %/yr, positive in 3 of 4 regime labels,
trade retention 88 %, paired p 0.06 → ACCEPTED → composed candidate g2-c01
(params + gate).

**Weeks 3–10.** g1-c07/g2-c01 accrue paper trades; weekly reconciliations run
(`fill_timing` pinned at_close for the re-sim — trade-set overlap 0.94, entry deltas
≈ 0, returnGap z −0.3 — aligned). At ≥ 20 closed trades + PF ≥ 1.3 + expectancy > 0 +
maxDD ≤ 25 % + gates green → TAKE_ELIGIBLE; PROMOTE proposal with the composed
explanation ("+0.42 RobustScore vs incumbent: 60 % from stability/DD, 25 % regime
gate, 15 % threshold 0.60→0.65; live 8 weeks aligned"). Owner approves; champion
pointer moves; the old champion's clone keeps papering 6 weeks as the rollback
counterfactual. Campaign continues or is CLOSED by the owner.

**Scalper contrast (LIVE_FIRST, compressed).** Campaign on
`scalp-connect-the-dots-nifty`: knob-set candidates {composite floor 0.68/0.70/0.72 ×
two rail-threshold overrides} → 6 shadow challenger variants (cap) — all
variant-vocabulary-expressible; the relative-vol k/N knobs are **ENV-plane** (global
across scalpers, §2.3) so they ride counterfactual replay instead, and a k/N winner
becomes an owner `.env` proposal, not a version. 4 weeks paired evidence →
floor 0.68 + rail-A relax shows paired lift +₹412/event, p 0.04, adjacent knob-sets
also positive → counterfactual replay agrees → PUBLISH_PAPER proposal for the
knob-set version (loosening variants only until the accepted-entry shadow extension
lands, §3.3.3); backtests never enter the ranking (functional smoke only).

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
     viz) + regime heat strip (UP_QUIET/UP_TURBULENT/DOWN_QUIET/DOWN_TURBULENT); cost-stress slope sparkline;
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
   countdown; RETIREs appear as auto-applied review/acknowledge items (never pending
   gates, §8.2). This is the Prompt-1 §11.6 checkpoint
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
  the known allowlist trap: a missing prefix silently serves the SPA shell, caught
  only by live-verify; CLAUDE.md/ops memory, verified no existing route collides):
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
- `purpose: reconcile` accepted (purpose is a free-form string today; StressGuard
  fires only on `stress_test`, so no guard change needed — just stamp it).
- The `stressOverrides {slippageMultiplier}` request field (§3.2.5) — small,
  golden-safe, recorded in provenance.
- Exit-reason attribution on the candle path (audit P1-3) — explainability and §7.1's
  `exitReasonDivergence` depend on it.
- Nothing else: folds, MC, holdout, plateau inputs all exist.

**strategy-signal-service (Java — additions only):**
- Shadow challenger variants registrable at runtime per campaign:
  `POST/DELETE /api/v1/shadow-variants` (campaign-scoped knob-set defs → the existing
  `ShadowVariants` mechanism), cap-enforced; today's boot-time static env JSON
  (`artha.scalper.shadow-book.variants-json`) remains the fallback. Second (HOLD)
  step when tightening variants are needed: extend the shadow writer to score
  variants on champion-**accepted** entries too (§3.3.3 — today it fires on the
  rejection path only).
- Evo paper lane: the **sibling-clone** mechanics (§8.2) — clone-create with ordered
  tags (family tag first), publish/archive lifecycle, per-family evo-strategy cap
  check at publish; book resolution and per-book risk caps unchanged.
- `created_by` actor plumb through registry create/promote (audit **T3**; V002:36's
  stated contract) so `evo:{campaignId}` provenance is a column, not a note. (Audit
  R4 — the NULL `strategy_version_id` on optimizer jobs rows — is separate adjacent
  hygiene, not the actor fix.)
- TAKE_ELIGIBLE↔candidate linkage on the **graduation read model** (GraduationService
  summary), not on `strategy_graduations` rows — that table holds one row per
  already-GRADUATED strategy only (V024), so a column there cannot link pre-graduation
  stages. Any V024 change is a new suffix-versioned migration (checksum-lock rule).

**Storage/infra:** evo tables in the backtest schema (optimizer already writes there);
the optimizer's **trial ledger** stays Postgres (V004) while Optuna *study* state is
in-memory today (`create_study` with default storage — the very gap P2-1/durable
orchestration closes with DB-backed checkpoints); Redis unchanged. **No stack swaps
required.** Three optional, additive dependency notes: (a) `scipy` in
optimizer-service for the paired Wilcoxon (absent today; or implement the exact test
by hand — n is small); (b) scikit-learn if Optuna's fANOVA importance evaluator is
chosen (§3.4); (c) the Prompt-1 optional parquet snapshot for keystone campaign
panels. Everything else — Optuna, Timescale, Redis Streams, React — is already the
right tool.

---

## 12. Phased implementation roadmap

PR-sized, house merge tiers (clean / HOLD), each with a verify check. E0 is the gate;
E1+ can interleave with Prompt-1 phases.

**E0 — prerequisites (platform work, not evo code):** the §13 HARD rows — P0-1
(partial buckets — gates all LIVE_FIRST evidence), P0-2 (engine SHA), P2-1 (optimizer
durability), T3 actor plumb, §11.4 experiment views, **row 19** (swing
funnel-universe resolution in the job pipeline — gates all SIM_FIRST swing
campaigns), **row 20** (regime-label vocabulary fix — gates the regime-coverage
hard gate). Everything else degrades gracefully (§13 table; P1-3 is deliberately
DEGRADED — explainability starts entry-side-only).

**E1 — experiment model + scoring on existing data [~1.5 wk, clean]**
1. `evo_*` migrations + typed read APIs (campaigns/candidates read-only first).
2. Scoring library (§6 gates + RobustScore + metric adds: recovery/turnover/frequency)
   applied **retroactively to existing sweeps** — verify: the historical Manas/scalper
   sweeps re-rank sensibly, plateau winners surface, scorecards render.
3. Campaign/generation recorder wrapping a manually-triggered sweep (no autonomy yet).

**E2 — search upgrades [~1.5 wk, clean]**
4. Neighbor-probe submission for top-K (`plateauObjective` becomes estimated).
5. Cost-stress re-runs: the backtest-service `stressOverrides` request field (the
   path grammar cannot reach fills/costs — §3.2.5) + 2×/4× orchestration + degradation
   slope in scorecards.
6. Deflated-Sharpe multiplicity gate; DOF penalties; importance/brittleness insights
   endpoints. Verify: a deliberately overfit toy sweep is rejected by the gates.

**E3 — live-evidence integration [~2 wk, clean + one HOLD]**
7. Reconciliation computer + `purpose: reconcile` + reconciliations API (clean).
8. Runtime shadow-variant registration API (HOLD — touches the live engine's shadow
   path; adversarial review) + campaign wiring for LIVE_FIRST. Optional second HOLD
   step: accepted-entry variant scoring for tightening knob-sets (§3.3.3) — until it
   lands, LIVE_FIRST shadow candidates are relaxing-or-neutral only.
9. Counterfactual-replay job type (runbook E9 formalized) on captured data (clean).
10. Live-gap gate + DIVERGENT checklist in scoring. Verify: champion-vs-itself
    reconciliation reads aligned (gapZ ≈ 0) over a known-clean window.

**E4 — proposals, approvals, promotion pipeline [~1.5 wk, HOLD-heavy]**
11. `evo_proposals` + inbox API + ntfy (clean).
12. PUBLISH_PAPER flow: approve → publish to evo lane with caps (HOLD — changes
    live-paper behavior).
13. TAKE_ELIGIBLE/PROMOTE/ROLLBACK rules + demoted-champion counterfactual
    auto-registration (HOLD). Verify: the full state machine is walked end-to-end on
    a **seeded fixture campaign** — recorded/fixture data with stubbed evidence
    (mock candles cannot drive folds/holdout/reconciliation, and §1.4.4 forbids
    scoring mock evidence; the fixture harness is the test carve-out, mirroring the
    golden-vector pattern).

**E5 — structure experimentation [~1.5 wk, clean]**
14. Pre-registered ablation protocol + paired evaluation + IS-only auto-reject +
    graveyard. 15. Gate-candidate suggesters (rejection forensics, regime gates —
    owner-ratified design amendment first (§5.1.2), unused indicators). Verify: a
    known-noise indicator is rejected; the DOWN_TURBULENT-gate example reproduces on
    historical folds.

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
| 4 | **T3 actor plumb (`created_by`; V002:36's stated contract)** | Machine-readable `evo:{campaignId}` provenance on versions; approval audit trail | Provenance lives in free-text notes; approval workflow un-auditable. (Audit R4 — NULL `strategy_version_id` on optimizer jobs rows — is adjacent hygiene worth fixing alongside, but not the actor fix.) | HARD (small) |
| 5 | **§11.4 experiment views + compare endpoint (#20)** | The scorecard/card UI reads the codified join graph; server-side compare feeds the leaderboard | Evo re-implements 8 hand joins (the exact debt Prompt 1 flagged) | HARD (or built as part of E1) |
| 6 | **P1-3 exit-reason attribution (candle path)** | Explainability score; §7 exit-reason divergence | Explainability degrades to entry-side only; exit forensics blind on candle-path runs | DEGRADED |
| 7 | **P0-5 BTST exit simulation** | Any BTST sim evidence | BTST campaigns run SIM_BLOCKED (live/paper only) — acceptable but slow | DEGRADED |
| 8 | **P0-3 via its #16 "port" path — swing portfolio-mode in the job pipeline** (P0-3's run-row alternative closes lineage but not this) | Slot-level (book) metrics for swing campaigns — DD/CAGR at the portfolio, not per-name | Campaigns score per-name expectancy + regime/stability through the 1d-primary job pipeline (once row 19 lands); portfolio effects (slots, deployment) validated only at the paper stage | DEGRADED |
| 9 | **P1-1 dataset epochs + content-stable hash** | Comparability + the re-run policy (#30) | data_hash over-sensitivity forces excess re-runs; epoch drift detected but noisy | DEGRADED |
| 10 | **P1-2 costs knob + instrument class** | Correct statutory class in cost-stress for futures-signal strategies | Slippage stress rides the new `stressOverrides` field (§3.2.5 — the path grammar cannot reach fills, so "params_override on slippage" is NOT a fallback); cost-CLASS stress stays skewed for futures until P1-2 | DEGRADED |
| 11 | **P1-4/P1-5 order events + quote capture** | Fill-quality/`entryPriceDelta` precision in §7 | Proxy = `scalper_detail.option_ltp` deltas; slippage-realized metric absent | DEGRADED |
| 12 | **P1-7 flag snapshot + ledger** | Stratify live evidence by knob regime; DIVERGENT checklist item 3 | Live evidence windows crossing a flag flip are discarded manually | DEGRADED |
| 13 | **P0-4 screener CA adjustment** (+ D12/D13 follow-ons) | Swing funnel-coupled parameters (rs_min etc.) are tuned against screener outputs; unadjusted inputs distort them | Campaign tuning of funnel-coupled knobs deferred; engine-plane knobs (exits, sizing) unaffected | DEGRADED |
| 14 | **P2-2 run tags/notes + saved views** | Experiment browser ergonomics; candidate/job tagging | Evo keeps its own linkage (evo tables) — UI filtering poorer until then | DEGRADED |
| 15 | **#22a provenance block (forks/fillModel/costModel/flags/profile)** | Scorecard caveat penalties read it; comparability checks | Caveats computed from metrics JSONB + convention — weaker guarantees | DEGRADED |
| 16 | **#22b `evidencePolicy` strategy tag** | The §1.2 routing stamp | Policy lives only in evo_campaigns (acceptable; tag makes it registry-visible) | DEGRADED |
| 17 | **T1 book-on-signal materialization** | Stable book attribution for live evidence joins | Read-time tags join — history rewrites if tags change mid-campaign (freeze tags during campaigns as the workaround) | DEGRADED |
| 18 | **Prompt-1 §9.1 backtest-vs-paper view** | The reconciliation UI home | §7 data renders in the campaign workspace only | DEGRADED |
| 19 | **NEW (found by this design's verification): swing funnel-universe resolution in the job pipeline** — `universe.mode: manas_arora_funnel` (and the Minervini equivalent) is unresolvable by backtest-service, and the request `universeOverride` is stored but never read | Every SIM_FIRST swing campaign runs its trials through the job pipeline | **No Manas/Minervini trial can run at all** — SIM_FIRST campaigns are dead in the water. Fix = a funnel-universe resolver/pinning step at submission (the `futures_screener` pinning precedent, JobsService.java:104-118) | HARD |
| 20 | **NEW: regime-label vocabulary reconciliation** — canonical labels are UP_QUIET/UP_TURBULENT/DOWN_QUIET/DOWN_TURBULENT but the optimizer/FE constants say BULL/RANGE/BEAR/CRASH → `regimesCovered` is always empty on real folds (latent platform bug, fix chip filed) | The §6.1 regime-coverage hard gate and regime-consistency scoring | The regime gate silently passes/fails wrong; regime-wise scoring unusable | HARD (small) |
| 21 | **NEW: parameter-path grammar extension** (schema pattern + OPT path_grammar/config_patch + LIB ParameterPaths/TrialOverrides — three synchronized enforcement points) for gate-expression constants, `risk.max_positions`, and screener/funnel props | Widens the tunable surface beyond exit/sizing/scoring/indicator params (§3.1 grammar bound) | Campaigns tune the 4-production surface only — real but narrower search; funnel knobs (`rs-min`, `liquidity-multiple`) and position caps wait | DEGRADED |
| 22 | **Audit A6 — no worker-pool concurrency/queue cap** | Evo multiplies job volume (trials × probes × stress × reconciliations, multiple campaigns) on the shared cores−2 pool serving interactive runs | Owner's interactive backtests starve during campaign nights; mitigated by evo's own global cap + off-hours preference (§1.4.6) until a pool-level priority lands | DEGRADED |

No other blockers found. Prompt-1's architecture, data contracts, and workflow model
are consumed as-is; the one re-flagged critical item is #1 (P0-1), because an
optimizer pointed at poisoned live evidence would *systematically institutionalize*
the artifact it was built to eliminate. Rows 19–21 are new platform gaps surfaced by
this design's own verification pass (not in Prompt 1's §10 list); rows 19–20 join the
HARD set alongside rows 1–5.
