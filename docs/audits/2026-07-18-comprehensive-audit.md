# Comprehensive platform audit — 2026-07-18

**Dual-signed:** Fable (Architect, orchestrator + validator) ✓ · Codex-Sol (`gpt-5.6-sol` @ xhigh,
read-only analyst) ✓. Main `7bd904c9`. Tiered sharded convergence run per
`.claude/skills/comprehensive-audit`.

**This is a PROPOSAL, not a gate.** Every accepted item still ships through the normal pipeline (its
own review + Architect audit + owner approval for money/arming/HOLD). It tells you *what* to consider,
never *what to trade*.

**Adversarial passes: COMPLETE.** 13 shards analysed by Sol; every finding validated by the Architect
against real code (and the live DB where a runtime claim was load-bearing); Deep + Standard shards
converged over 2 rounds; the money/parity Criticals ran an Opus adversarial-REFUTE pass; the whole doc
ran a fresh-Sol red-team. **Both passes materially changed this doc** — they killed one bad Critical
(AY-SL-05), narrowed two (AY-SL-04, AY-SL-06), corrected the AY-SL-01 wording, and re-labelled several
active/latent tags and prior-audit corrections. Those changes are folded in below. Full working
artifacts are in `.claude/skills/comprehensive-audit/state/` (gitignored — so the load-bearing file:line
evidence for the top findings is embedded directly in this doc).

**Honesty markers:** *active* = standing defect producing wrong output / standing exposure now ·
*latent* = code-confirmed, zero current live incidence (fix before the trigger) · *reachability* =
mechanism + a constructed trigger proven, but no persisted incidence found · *needs-verification* =
mechanism read, runtime behaviour not reproduced. **Validation scope:** every row's *mechanism* is
Architect-confirmed at the cited file:line; rows marked needs-verification have their runtime behaviour
unproven (listed in §Needs-verification). Not every row was exercised on the live DB — only the ones so
tagged.

---

## Executive summary (owner priority order = reliability → oipulse → perf → UX)

**The biggest finding is a THEME, not a row: the research → optimization → evolution → graduation
pipeline has multiple *independent* correctness defects, none caught by a green test suite.** No money
has moved (owner approval still gates every arming), but the evidence the owner would approve *on* is
untrustworthy. That is the #1 reliability issue. Note the honest scope: these are *silent-wrong-output*
and *reachability* defects — we did **not** find a persisted promotion that actually flipped; we found
that the machinery *can* produce a wrong verdict and nothing would catch it.

1. **[P0 · active silent-wrong-output] Backtest/fold risk metrics are computed on a downsampled equity
   curve (AY-SL-01).** Sharpe/Sortino are computed from the ≤500-point *display* curve
   (`ReplayEngine.java:337` stores `downsample(equityCurve,500)`; `BacktestRunner.java:288` +
   `FoldEvaluator.java:127` consume `result.equityCurve()`) while annualized at per-bar cadence
   (`MetricsCalculator.java:285`). Effect: risk/path metrics are **wrong-cadence** — Sharpe/Sortino are
   inflated ≈√stride on the common densely-in-position path (stride = curve points / 500; a 45-day 3m
   run has ~16,875 points → stride ~34), **maxDrawdown is understated-or-equal**, and **CAGR/totalReturn
   are unaffected** (endpoints + timestamps are exact — `MetricsCalculator.java:201`). Runs ≤500 points
   are untouched. The corrupted per-fold Sharpe feeds the deflated-Sharpe multiplicity gate
   (`FoldEvaluator:124` → `evolution.py:502` → `scoring.py:390`); a constructed example flips it
   FAIL→PASS (true 0.4 → −0.76 FAIL; ×3 → +3.07 PASS) — *reachability, not a demonstrated flip*. **This
   is a CORRECTION of a false prior closure**: the 2026-07-06 UI audit asserted the metric used the full
   curve (`docs/audits/2026-07-06-ui-data-correctness/backtests-dataops.md:36`). **EMPIRICALLY CONFIRMED
   on live persisted data (Phase C, read-only):** every intraday `backtest_runs.equity_curve` is capped
   at 501 points (1m: 352–501, 3m: 495–501 — short runs ≤500 correctly untouched), and **166 persisted
   intraday runs carry `|sharpe| > 3`** (up to 5.85 / −80.09 — implausible magnitudes that are the
   signature of broken per-bar annualization on a capped curve). So the defect is *active on real stored
   results*, not merely reachable.
2. **[P0 · active exposure] Two live-secret exposures.** (a) SEC-01: `.env` + `deploy/secrets/` +
   backups carry `NT AUTHORITY\Authenticated Users:(M)` and the `CodexSandboxUsers` (AI-sandbox) group
   has Modify on live broker/DB/master-key material (`icacls` verified). (b) SEC-02: the read-only SQL
   console runs as the DB **superuser** `artha` (`rolsuper=t`, live-confirmed) with a verb-only blocklist
   (`AdminQueryService.java:49-54`) that doesn't stop `SELECT pg_read_file('/run/secrets/…')`. SEC-01 is
   a standing exposure; SEC-02 is Critical-capability-if-reached but Major present-likelihood (needs an
   authenticated console session on a loopback box).
3. **[P0 · active] Disaster-recovery restore validates only one table (OPS-R01).** `ay restore`
   tolerates arbitrary `pg_restore` errors (`ay.ps1:217`) and gates success solely on
   `marketdata.candles > 0` (`ay.ps1:226-232`); a dump that loses strategy/paper/backtest/roles/grants
   restores "successfully." CI round-trip seeds only market-data too.
4. **[P0 · active-swing] The swing walk-forward is methodologically invalid (AY-SL-03).** Folds replay
   from cold indicator state (`FoldEvaluator.java:74/145`; `TickwiseGoldenRunner.java:103`); a 125-bar
   OOS fold can never warm the 252-bar Minervini `WEEK52_HIGH` gate (`minervini-primary-base.yaml:21`) →
   the gate can't evaluate anywhere in-fold. (Scalper folds: a milder 27–87-min prefix loss.)
5. **[P0/P1 · latent] Optimizer research semantics (AY-OPT-02/03) + fail-open selection (PF-01).**
   AY-OPT-02: pruning is terminal + post-hoc, prunes on per-fold *Sharpe* not the sweep's expectancy
   objective (`BacktestRunner.java:562`) — and that Sharpe is the SL-01-inflated one — and can relabel a
   *completed* trial PRUNED. AY-OPT-03: NSGA-II collapses to a scalar in production (`service.py:563`),
   violating the frozen "never collapse" rule. **Both are latent/config-dependent** — the 63 shipped
   configs are TPE single-objective *expectancy*, so no NSGA run and no persisted pruned run was found.
   PF-01 (absorbs AY-SL-08): "no explicit FAIL" = "ready" at both `SCORED→SURVIVOR` (`scoring.py:141`)
   and `PAPER→TAKE_ELIGIBLE` (`proposals.py:448`).
6. **[P1 · latent · money/parity/doctrine]** AY-SL-04 (expiry settle's `avgEntryPrice` fallback fabricates
   intrinsic — reachable via **unpinned BSE:SENSEX spot** / symbol-key mismatch / feed outage, NOT the
   always-pinned NIFTY; zero incidence) and AY-SL-06 (premium-exit rounding: live 2dp vs backtest
   full-precision — a ≤1-paisa boundary divergence + a real fixture-coverage gap, AY-META-01).
7. **[P1 · oipulse #2] Trending-OI gates on the wrong strike universe (FG-01)** — full chain, not
   oipulse's ATM-15 basket, feeding *hard scalper gates*. Plus the known-open §18.6 ΔOI-vs-level sentiment
   formula still feeds a live gate. Both are *measure-first* items, not gate changes.
8. **[P1 · reliability] The armed F9 heat governor can read zero SPAN (EXT-03)** if Upstox margin drift
   returns empty fields (`UpstoxMarginClient.java:98` null-only guard → `priced=true, spanMargin=0`) → the
   heat cap never trips. The Upstox token budget is also fragmented, not shared (EXT-02) — **corrects the
   2026-07-10 app-audit claim** of one shared limiter.

**Fastest high-value fixes:** SEC-01 (ACL tighten, S), OPS-R05 + OPS-R04 (`ay verify-deploy` unreachable
+ `ay.ps1` won't parse under PS5.1, XS–S), AY-SL-01 (compute analytics on the full local curve, downsample
only for display, M), AY-SL-04 (delete two `avgEntryPrice` fallbacks, S).

---

## Findings — by severity

Every row is `Fable ✓ + Sol ✓` (mechanism Architect-confirmed at the cited line). Priority P0–P3 is in
the roadmap. Detail beyond the embedded evidence is in the per-shard reviews under `state/` (gitignored).

### Critical (silent-wrong-output or standing-exposure, active unless noted)

| id | shard | state | root cause → fix (evidence) | prior-closure note |
|---|---|---|---|---|
| AY-SL-01 | strategy-logic | active | metrics on ≤500-pt downsampled curve + per-bar annualizer → Sharpe/Sortino wrong-cadence (√stride-inflated on the dense path), maxDD understated, CAGR/totalReturn OK; corrupted fold-Sharpe reaches the DSR gate (reachable flip) → compute analytics on full local curve, keep downsample for display, golden+parity byte-identical (M) | **prior-audit CORRECTION** (2026-07-06 UI audit falsely closed as "full curve") |
| SEC-01 | security | active | host ACLs (`Authenticated Users:(M)`, `CodexSandboxUsers:(M)`) on `.env`/`deploy/secrets`/backups → tighten to owner+SYSTEM, rotate, fail-startup on broad ACL. **Absorbs the SEC-05 backup-ACL portion.** (S–M) | — |
| SEC-02 | security/arch | Critical-if-reached / **Major present-likelihood** | console runs as superuser `artha` (`rolsuper=t`); verb-only blocklist ⇒ `pg_read_file` reads secrets. Needs an authenticated console session (loopback). Split the endpoint fix (dedicated LOGIN least-priv query role) from the broader per-service-principal / D10-enforcement architecture work. (M) | — |
| OPS-R01 | ops-resilience | active | `ay restore` gates on `marketdata.candles>0` only, tolerates all pg_restore errors (`ay.ps1:217/226-232`) → fatal-on-error + 4-lineage manifest + expand CI fixture (M) | — |
| AY-SL-03 | strategy-logic | active-swing | cold-start folds; 125-bar OOS can't warm the 252-bar Minervini gate → preload past-only warmup with **zero pre-boundary signals/trades/positions/MTM** (M) | — |

### Major

**Research/optimization/evolution integrity** — AY-SL-07 (deflated-Sharpe is a bespoke IID t-stat mixing
units, `scoring.py:393`; **Major, depends on AY-SL-01, does NOT inherit its Critical**) · AY-SL-02
(*latent*: request walk-forward geometry persisted but never consumed by Java; zero live mismatch) ·
PF-01 **(absorbs AY-SL-08)** (fail-open evidence at both SURVIVOR + TAKE_ELIGIBLE; fix = stage-aware
gate-readiness policy, NOT "every SKIPPED blocks") · PF-02 (*HOLD-tier*: bullish-only composite scores 9
mixed/neutral scalpers before side known; **fix is not implementation-ready — `resolved_side` isn't
available at `SignalEngine.java:1126` chart-eval time; needs a deterministic live/replay dataflow design
first**) · PF-03 (*mechanism-confirmed / incidence-UNVERIFIED*: risk-cap-censored entries return
`Outcome.FIRED` with no durable per-strategy record; `SignalEngine.java:1500/1228`) · AY-OPT-02
(*latent*: terminal/post-hoc pruning on wrong+inflated metric) · AY-OPT-03 (*latent*: NSGA-II Pareto
collapse) · AY-OPT-04 (untyped dict bodies → partial validation + incomplete D8 error envelope) ·
AY-OPT-05 (unbounded daemon threads; sweep cap excludes owner sweeps) · AY-OPT-01 (grid cardinality
rounds → out-of-range trial).

**Security** — SEC-07 (**split**: mutable action tags + unchecked gitleaks download `ci-java.yml:52` =
the stronger supply-chain finding; missing vuln/image scanners = a separate control gap; Stage-G-designed,
UNMET). SEC-05 encryption/signing → **Minor** (Stage G specs host-sync, not encryption — see Minor list).

**Data correctness** — DQ-01 (*active*: off-session TICK_AGG/BACKFILL poison bars admitted + consumed;
preflight count-masking false-pass reachable) · DQ-02 (*active*: instrument-master freshness not a feed
precondition — sync was late/missing on multiple recent trading days; 07-17 had none) · DQ-03 (*latent*:
bhavcopy single-exchange-fail / catch-up-older-day go green) · DQ-04 (*active archive*: 10,863 contracts
marked complete but short, ~25% of archive; **zero persisted-run exposure** — *may conditionally compound*
AY-SL-01 only if a short contract is selected) · EXT-01 (broker `last_trade_time` discarded → `now()` →
10-min LTP freshness guard defeated for a stale broker response) · BEJ-02 (options captures overlap,
misorder `previousOi`).

**Reliability / ops** — OPS-R04 (*confirmed on this box*: `ay.ps1` won't parse under PS5.1/CP1252 —
BOM-less em-dashes → curly-quote string terminator; `Parser::ParseFile`→2 errors, `ay.ps1 help`→exit 1
at :291) · OPS-R05 (`ay verify-deploy` absent from the ValidateSet `ay.ps1:14` → stale-jar guard
unreachable; `up` runs without `--build`; **prior closure incomplete** — 2026-07-05 declared it done) ·
OPS-R02 (*incomplete-closure of H10, not novel*: reconciler/ingest-canary forget missed runs) · OPS-R06
(external heartbeat is one service's unconditional ping — gateway OOM leaves it green) · BEJ-01
(*needs-verification + challenges a prior conclusion*: fixedDelay jobs may serialize on Spring's single
scheduler lane, delaying the F10 watchdog; 2026-07-05 audit refuted scheduler starvation) · EXT-02
(*corrects 2026-07-10 app-audit*: Upstox limiter fragmented 3× + quote/chain/margin unmetered) · EXT-03
(*latent*: margin drift → `spanMargin=0` → armed heat cap never trips) · EXT-04 (*latent-dormant*: source
flags remove Kite instead of preserving the always-on fallback) · ARCH-01 (MapReturnRatchet regex blind
to `ResponseEntity<Map>` — true count 86 vs 70 frozen) · ARCH-03 (30/69 `${ARTHA_*}` keys absent from
compose incl. the engine kill switch; the #653 class).

**Docs↔code integrity (meta)** — AY-META-02 (`failIfNoSpecifiedTests=false` half-ladder false-green) ·
AY-META-03 (liveness STOP block preserves the refuted §1 premise) · AY-META-04 (PHASE_GATES marker stale)
· AY-META-05 ("pages ride e2e/axe" unbacked — 65/93 page components have no explicit Playwright nav).

**Perf/storage** — AYDB-01 (5 candle caggs uncompressed) · AYDB-02 (IST-day options queries
`(ts AT TIME ZONE …)::date` defeat chunk exclusion) · AYDB-03 (50 GB review-trigger gauge measures only
`candles`=23 GB; DB total=46 GB=92% of trigger) · FE-01 (TrendingOiPage eager-imports ECharts → 661 KB gz
initial, violates the frozen ≤500 KB budget) · FE-03 (`useBacktestFolds` swallows errors → `[]`) · FE-04
(Dashboard/Orders bypass QueryState → a 500 renders a false-empty paper book).

**oipulse parity** — FG-01 (Trending-OI full-chain vs ATM-15, feeds hard gates; measure first) · FG-03
(Multiframe shares one symbol) · FG-04 (Strategy Builder = expiry-payoff only) · FG-05 (Multi Leg Price
absent).

### Minor
AY-SL-05 (*latent, futures-only — REFUTE-downgraded from Critical*: multi-add cost-basis reconstruction
error is structurally ZERO for options+equity; futures aren't a live paper instrument) · AY-SL-06 nit is
folded above as Major · SEC-03 (unbounded login body) · SEC-04 (WS revocation — *needs-verif*) · SEC-05
(backup encryption/signing — Stage G doesn't require it; hardening advice) · SEC-06 (ntfy topic in logs —
*needs-verif*) · SEC-08 (*Minor/latent-unmet-hardening, P2*: container read_only/cap_drop/digest-pins
absent — defense-in-depth, no established initial-entry path) · ARCH-04/05 · OPS-R03 (PartialBucketCanary
no alert path) · AY-META-06/07 · AYDB-04/05 (05 = candle index is structural, do NOT drop) · FE-02/05 ·
AY-OPT-06 · BEJ-03..06 · EXT-05/06 · FG-02/06.
**Withdrawn:** SEC-09 (CSV formula — no untrusted producer on a single-owner box).

---

## Prioritised roadmap

**P0 — highest leverage (reliability first):**
- *Research-pipeline integrity bundle* (headline): AY-SL-01 (full-path metrics) FIRST — several consumers
  depend on it — then PF-01/SL-08 (stage-aware gate policy), AY-SL-07 (rename or implement real DSR),
  AY-OPT-02 (honest pruning), PF-03 (assess the existing `signal_rejections` surface `V015:10` before
  adding a RISK_SUPPRESSED record). AY-OPT-03 rides along (cheap) but is latent.
- *Security*: SEC-01 (ACL + absorb SEC-05 ACL portion), SEC-02 endpoint fix (least-priv query role).
- *DR*: OPS-R01 (4-lineage restore validation).
- *Swing methodology*: AY-SL-03 (fold warmup, zero pre-boundary trades) before trusting any swing WF.

**P1:** *Latent money/parity* AY-SL-04 + AY-SL-06 (fix before the trigger); DQ-01/02/04, EXT-01/02/03,
ARCH-01/03, OPS-R02/R04/R05/R06, FE-03/04, the meta doc-drift set, FG-01 (measure ATM-15 vs full-chain),
BEJ-01 (after the runtime probe), SEC-02 architecture half.

**P2:** SEC-07 (supply-chain) + SEC-08 (container hardening) — the Stage-G unmet layer; AYDB-01/02/03;
FE-01; FG-03/04/05; AY-OPT-01/04/05; BEJ-02.

**P3:** the Minor list.

---

## oipulse gap-matrix + owner live-check shortlist

Full matrix in the features-gaps review. **20 built · 18 partial · 11 diverges · 4 missing** (intentional
divergences — Dashboard, Connecting Dots, Plans, Event Days — and D3-tracked items excluded). New gaps:
FG-01 (Trending-OI universe), FG-03/04/05, FG-02/06 (Minor).

**Owner-run live-check shortlist** (numbers static code can't settle): (1) Active-Strikes sentiment (ΔOI
vs level §18.6 — exact level formula is implemented but display-only while the gate consumes ΔOI);
(2) Trending-OI ATM-15 vs full-chain (quantifies FG-01); (3) Options Chain dense-interaction parity;
(4) Advance/Multiframe independent-symbol behaviour; (5) one fresh NSE Announcement end-to-end.

---

## Owner-decision list

1. **Trust of existing backtest/evolution numbers** — AY-SL-01 means intraday-run Sharpe/Sortino/maxDD on
   record are wrong-cadence and any DSR-gate verdict is suspect. Decide whether to re-run key campaigns
   after the fix. (No proven bad promotion — but the safeguard is defeatable.)
2. **The 24 fresh SENSEX strategies** (book 39→63 on 2026-07-17, no forward-paper record) + PF-03: risk-
   censored opportunities may be invisible, but **incidence is unverified** — re-evidence (grep the
   `ENTRY suppressed by scalper risk gate` logs) before deciding whether to pause them.
3. **PF-02 mixed/neutral scoring** (HOLD-tier) — approve the typed-scoring-bias direction? (Fix not yet
   implementation-ready; distinct from owner-parked task_71a017e6.)
4. **A2 retention vs A10 365d + the half-blind 50 GB gauge** (AYDB-03; DB at 92% of trigger).
5. **Stage-G unmet layer** (SEC-07 supply-chain + SEC-08 container hardening) — build or formally park
   (and correct PHASE_GATES's "Stage G merged as-built" claim).
6. **Doc/memory corrections owed:** memory `upstox-shared-ratelimiter-contention` is FALSE (EXT-02);
   CLAUDE.md "Kite always-on fallback" unimplemented for quote/ticker (EXT-04, dormant); liveness STOP
   block (AY-META-03); PHASE_GATES marker (AY-META-04).
7. **BEJ-01** (fixedDelay serialization touching the F10 watchdog) — run the runtime probe before acting.

---

## Needs-verification (mechanism read; runtime not reproduced — exact settling evidence)

- **AY-SL-01 historical incidence** — full curves discarded; identical-data rerun before/after the fix,
  diff metrics + leaderboard order + any DSR verdict change.
- **AY-OPT-02 / AY-OPT-03 active incidence** — a persisted pruned-run query / a persisted multi-objective
  NSGA run (none found in the shipped-config census).
- **PF-03 veto incidence** — retained `ENTRY suppressed by scalper risk gate` logs grouped by strategy/rail.
- **BEJ-01** — block one fixedDelay job in the running service; observe whether another fires.
- **SEC-04 / SEC-06** — fake-session / fake-topic reproductions.
- **DQ-02 token misattribution** — retained daily dump/token→key snapshots (not currently kept).
- **EXT-02 429 incidence** — 7 trading days of 429s per Upstox endpoint on one token timeline.

---

## Anti-re-flag provenance (rows that reverse/extend a prior closure — labelled, not "wholly new")

- **AY-SL-01** = correction of a false prior closure (2026-07-06 UI audit said "full curve").
- **BEJ-01** = challenges the 2026-07-05 audit's refutation of scheduler starvation (pending runtime proof).
- **OPS-R05** = the 2026-07-05 `verify-deploy` closure is incomplete (command unreachable).
- **EXT-02** = corrects the 2026-07-10 app-audit's "one shared limiter" assertion.
- **OPS-R02** = incomplete closure of the earlier H10 catch-up finding, not a novel regression.

Everything else respects the five closed audits + the counterintuitive-by-design list + the owner-gated
status rows (see §Already-covered).

---

## Fix-safety guards (every proposed fix must preserve these)

- **AY-SL-01:** compute from the full *local* curve; keep the ≤500-pt persisted/display curve; golden +
  BacktestParity byte-identical (CLAUDE.md parity firewall).
- **AY-SL-03:** warm indicators with **zero** pre-boundary signals/trades/positions/MTM.
- **AY-SL-04:** last real tick at any age; refuse only if no tick ever existed; **never `avgEntryPrice`**
  (#694 doctrine).
- **AY-SL-06 / PF-02 / PF-03:** parity side-channels stay non-serialized + deterministic; **no
  `signals`→`paper`/`notifier` import** (Modulith); PF-02's `resolved_side` fix needs a dataflow design
  first (not implementation-ready).
- **All schema fixes:** new suffix migrations only (next free: backtest V021 / strategy V042 /
  marketdata V049 / admin V002); never edit an applied migration (checksum-locked).

---

## Perf baselines

Persisted to `docs/audits/baselines.md` (new dated row per audit; regression vs a prior row is a finding
class). 2026-07-18: candle 1m range 7.74 ms; 5m cagg cold 68 ms; candles hypertable 23.3 GB (index 15.6 GB
= 3.4× heap, uncompressed — but *structural*, do not drop, AYDB-05); DB total 46 GB; backtest ~18×
throughput variance same workload; tick→signal emit p95 ~14.9 s (n=6, directional only); frontend all-JS
3.48 MB / critical path ~935 KB; timescale 792 MiB/4 GiB, edge-gateway 69% of cap. **Web-vitals/page-load
+ a mock-stack functional pass are deferred to the weekend off-market window (Phase C).**

---

## Already-covered — do NOT re-propose (raise only as a proven regression, labelled)

Five prior audits closed for startables: full-codebase (`2026-07-05-full-codebase-audit.md`), swing (#628),
app-platform (`2026-07-10-app-platform-audit.md`), research-fidelity (`2026-07-10-research-fidelity-audit.md`),
frontend live-audit (#476). Counterintuitive-by-design: muted derived-history OI, `NIFTY-FUT-CONT` stale,
`armed≈0`/`0 signals` on the AND gate, lowercase `published`, in-container UTC `now()`. Owner-gated status
rows: C8 INT-I4, C6 EVO-autonomy arming, E1 forward-paper month, E2 always-on host, E4 audit-doctrine
H8/#128, E5 calendar refresh, PE-composite task_71a017e6/79092520 (parked), paper_positions id=28
(remediated), F9/F7/notifications (armed). Regimes FROZEN → owner revisit 2026-08-14. Engine-liveness
detector design INVALIDATED both axes; F10 Part B shipped as #886.

---

## Fix log — 2026-07-18 build-out (owner: "start autonomously in recommended order")

Nine PRs merged the same day (delegated Opus builders → cross-vendor Codex review each round → Architect
audit → admin-merge on CI-green). Every row dual-verified; parity byte-identical on every engine-touching
change (Golden 9/9 + Parity 9/9, re-confirmed on the combined main tree after the three backtest-service
merges). Live deploy of the migration/secret/armed-engine batch is staged separately (owner go).

| Finding | PR | Merge SHA | Notes |
|---|---|---|---|
| AY-SL-01 (full-curve metrics) | [#913](https://github.com/prashantm912/artha-yantra-2/pull/913) | `59fb7d63` | metrics on full curve + **curve-cadence annualization** (2nd defect the review caught: 1m-curve vs primary-tf → 3m Sharpe was under-annualized). Residuals chipped/noted. |
| AY-SL-03 (fold warmup) | [#916](https://github.com/prashantm912/artha-yantra-2/pull/916) | `e0117439` | emissions-suppressed past-only warmup; boundary decision = continuous-run day-boundary equivalence (domain PASS). |
| SEC-01 (host ACLs) | main-loop | — | `.env`/`deploy/secrets`/`backups` tightened to owner+SYSTEM+Admins; `ay up` broad-ACL guard shipped in #914. Secret **rotation** = owner-gated residual. |
| SEC-02 (console least-priv) | [#917](https://github.com/prashantm912/artha-yantra-2/pull/917) | `91eed1da` | `ay_console` role (admin V002) in **market-data-service** (not edge-gateway — brief erratum); injection-safe init, advisory-lock block. Deploy-gated. |
| OPS-R01/R04/R05 + ACL guard | [#914](https://github.com/prashantm912/artha-yantra-2/pull/914) | `dd8eb376` | restore fatal-on-error via pg_restore summary discriminator; PS5.1 BOM (live-verified 0 parse errors); verify-deploy verb; CI 4-lineage roundtrip. |
| SEC-07 (CI supply-chain) | [#915](https://github.com/prashantm912/artha-yantra-2/pull/915) | `17806e37` | 32 action SHAs pinned (all Architect-re-resolved), gitleaks checksum, ref-guard workflow. SEC-08 parked (owner). |
| PF-03 (RISK_SUPPRESSED) | [#918](https://github.com/prashantm912/artha-yantra-2/pull/918) | `44fc56cd` | strategy V042 table + bounded async writer (eval-thread-safe) + drain-on-shutdown. Deploy-gated. Chips: prune / async-existing-rejection-writer / in-flight-accounting. |
| BEJ-01 (scheduler blind-spot) | [#919](https://github.com/prashantm912/artha-yantra-2/pull/919) | `4ad075b7` | **probe CONFIRMED** (pool=1, sibling 0 ticks during a block); resolves the 07-05 refutation (recovery off-pool, detection on-pool). Monitors-only isolation + FeedPipeline lifecycle lock. |
| AYDB-03 (storage gauge) | [#921](https://github.com/prashantm912/artha-yantra-2/pull/921) | `edc71c2b` | **live DB measured 45.7 GB = 91.5% of the 50 GB trigger** (old candles-only gauge showed 44%). Now DB-total + per-hypertable, profile-aware. |
| tradeFrequency unit (chip) | [#920](https://github.com/prashantm912/artha-yantra-2/pull/920) | `0fc9eedd` | surfaced by #913; sessions now in 1m cadence (trades/day). |
| PF-02 (typed-scoring design) | design | — | `docs/superpowers/plans/2026-07-18-pf02-typed-scoring-bias-design.md` — 2 adversarial reviews → rev 2 (Mode A retrofit of 9 PE mirrors). Build **owner-gated** (bearish numbers). |

**Owner-facing residuals:** (1) live deploy of the migration/secret/armed-engine batch (below) · (2) AY-SL-01
campaign re-run to correct the 166 historical |sharpe|>3 rows · (3) DB at 91.5% → compression (AYDB-01) or
scope · (4) PF-02 build approval · (5) SEC-01 secret rotation · (6) 4 follow-up chips.

---

## Method note

Sol's read-only sandbox lacked `rg`/docker for part of the run; the ops-resilience shard's package paths
were consequently garbled (`com.arthayantra`, a nonexistent `paper-trading-service`) and its OPS-R04
reproduction looked fabricated — until the Architect *ran* it on this box and it was real. The convergence
loop cut both ways: it caught Sol's hallucinated paths and severity overreach (SEC-03/04/06/09 downgraded,
the ops-resilience noise), and it corrected three Architect assumptions (the DQ-02 sync trigger, the FE-01
import form, and the OPS-R04 refutation). The Opus REFUTE pass then killed AY-SL-05 as a Critical and
narrowed AY-SL-04/06; the fresh-Sol red-team corrected the AY-SL-01 blast-radius wording, the active/latent
labels, the security severities for the loopback model, and forced the prior-audit-correction labels above.
Cost: ~24 Sol runs (13 first-pass + convergence + red-team) + 4 Opus subagents (2 recon, 1 perf, 1 REFUTE).
Shipping: this doc + `baselines.md` go out via a normal docs PR after owner sign-off; the run itself
committed nothing.
