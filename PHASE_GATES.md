# PHASE_GATES (A.15 / S5)

One page only: the **current-phase marker**, a **checkbox copy of the current
phase's acceptance criteria**, and a **"deferred" parking list**. The stage
files under `docs/design/` stay the single source of truth — this file is the
S5 **Friday gate ritual** input: walk the checklist **against the running mock
stack** at each phase boundary; an unchecked box extends the phase. No hard
calendar freeze dates; no on-push gate enforcement (the machine-checkable
subset is CI-enforced).

---

## Current phase

> **⚠️ CURRENCY (2026-07-18): this file's top "Current frontier (2026-07-02)" marker + the dated currency
> blocks below are a HISTORICAL LOG, not the live frontier (audit AY-META-04). The authoritative current
> open-work state is the ledger `docs/superpowers/plans/2026-07-02-remaining-items.md §0`. Latest: the
> 2026-07 comprehensive audit merged (#911, `docs/audits/2026-07-18-comprehensive-audit.md`) — its P0 set
> (SL-01 metrics, SEC-01/02, OPS-R01, SL-03) is the next build frontier. Also note: SEC-07/08 show the
> Stage-G hardening layer below was SPEC'd but NOT fully built — "merged as-built" overstates Stage G.**
>
> **CURRENCY UPDATE 2026-07-25:** the 2026-07-18 audit's P0 set is CLOSED (SL-01/SEC-01/SEC-02/OPS-R01/SL-03
> all shipped + deployed). The frontier since is the **weekly session-forensics loop**: the 2026-07-20→24
> trading week's routine docs produced the B0–B11 bug queue
> (`docs/signal-analysis/2026-07-25-weekly-bug-queue.md`), now FULLY CLOSED, and its four owner decisions
> shipped + deployed as #990 (T21 premium bands) / #991 (T22+T6 dot calibration) / #992 (T10) / #993
> (PE-side stop direction) / #1016 (S1–S3 scheduler isolation). Open work stays in the ledger §0; the only
> owner action outstanding is the B8 host-clock resync.
>
> **CURRENCY UPDATE 2026-07-27:** the residual G-group is now CLOSED and deployed — **G3** F-OPT
> `OptionAtmPinner` (#1039, live: 44 option pins, 2.3% of the subscription cap, 0 evictions),
> **G4** F-SYNC (#1023, the `kite-dump` permit was spent by a breaker-rejected call — resilience4j
> decoration order), **G5**/T12 futures-OI cadence (#1031, the cause was scheduler starvation, not
> the limiter), **G2**/T9 strategy-coverage watchdog (#1035, deployed **DISABLED** — arming is an
> owner decision), plus task_1b85c64f (#1033, HOLD) and task_bd871971 (#1034).
>
> The **swing catch-up branch was SPLIT** rather than shipped or dropped (owner-approved): the
> *detect* half is live and armed (#1044 + orphan cleanup #1046), the *auto-replay* half stays a
> parked draft (#1036) because all seven Criticals live in it. **Its behaviour is not yet verified** —
> the intent ledger was empty at deploy, so the first meaningful sweep is the morning after the
> first weekday evening; a recurring 08:40 IST weekday check is scheduled.
>
> Two OWNER items outstanding: the B8 host-clock resync (carried), and **arming the T9 watchdog**
> (needs an alert channel, a `NOT_LIVE_RESOLVABLE` re-page cadence, and an OBSERVE_ONLY duration).
> **INT I4 is PARKED to ~2026-08-09** — 7 Majors, and its headline delivery-rate number was wrong
> (see ledger C8).
>
> **CURRENCY UPDATE 2026-07-28 (supersedes the 07-27 swing paragraph above):** the swing story is
> now THREE armed layers, all live-verified. The **detect** half's intent machinery proved itself on
> first contact (intent rows at exactly 19:59:59/20:04:59 IST, both batches marked). The
> **auto-replay** half was FINISHED the same day the owner funded it and — on the owner's
> "merge and arm both" — **#1036 is MERGED + DEPLOYED + ARMED** (`2e4ea6f0`; V049–V051 probed,
> `swing-catchup-sched-1` thread live; a 7th Critical, an exit-vs-open TOCTOU, was found in round 4
> and closed with per-anchor advisory-lock serialization). The **#640 dead-man's-switch turned out
> ALREADY armed** (URL set, ping verified) — the "dormant" note was stale. Also shipped same night:
> **V048 `signals.exit_reason`** (#1057, deployed + DB-probed — scalper exits are now durably
> explainable) and the **guarded swing Run-batch-now buttons** (#1061, deployed, bundle-probed) as
> the escalation surface for ABANDONED/refused sessions. First armed 08:35 catch-up sweep verifies
> via a one-time 08:50 task on 07-28 (expected: clean no-op). Owner items unchanged: B8 clock, T9
> arming, I4 ~2026-08-09.

> **CURRENCY UPDATE 2026-07-29 (supersedes the 07-28 block above for the SCALPER story):** the
> scalper→paper path went from dead to bounded in two days, in four shipped pieces. **#1067** revived
> it (sizing was computed against the INDEX future, so `premium_budget` floored to 0 lots on every
> entry for three weeks, silently); **#1071** resolved the option's exchange from the instrument
> master rather than its name (the same field feeds the UNARMED real-money path, so a SENSEX option
> stamped NSE was a latent landmine); **#1084** added `max_lots` + `min_premium_inr` and made the LIVE
> path honour both, closing a three-layer disagreement where *replay honoured a param, live ignored
> it, and the schema forbade it*; **#1086** bound the capital governors at the writer — the deployment
> cap now refuses the order that CROSSES it, a per-book advisory lock serializes check-plus-write,
> straddle legs open atomically, and sub-account routing reads deployed capital under that same lock.
> All four are DEPLOYED and fingerprint-verified.
>
> **CURRENCY UPDATE 2026-07-30 (the entry-gate track has a verdict; the frontier moves to the EXIT):**
> the 07-29/30 run closed the pickup-sheet G-rows (#1111–#1119, live at `f6ba4dab`) and produced one
> finding that reorders the board: **every measured loosening of the scalper ENTRY gate has lost
> money — T1 (multiplier, 2W/9L), T7 (threshold, worst book), G13 (IV bloc, undecidable at 6 legs),
> G10 (time-of-day volume profile, 265 legs, +324.87 gross but −590.95 after 1% cost).** Four tests,
> three knobs, one direction. G10's profile IS built and reviewed but stays **default-OFF** —
> recommendation is not to arm. ⚠️ **All four are conditional on the 30-minute `time_stop`, which G11
> says is itself the dominant term in scalper P&L — so G11 (exit doctrine) is now the highest-leverage
> open row, and if its exit changes every one of the four must be re-run.** G11 is data-gated on a
> chop-day observation; **G15 shipped the detector that will announce one** (regime label on each
> `rollup.md` session row, base rate ~29% ⇒ ~3–4 sessions), which converts an indefinite wait into a
> short clock. Also closed: **G12** (frozen-operand probe; the `atmIv` freeze turned out to be
> CORRECT-by-construction — a daily EOD scalar — so the defect belongs to the dot, not the feed) and
> **G14** (convergence measured: 5–7 strategies on one option key on 6 of 7 sessions, not binding at
> today's fire rate but directly in the path of any rise). Infrastructure: a required check had been
> **masking a live Modulith cycle on main** since #1094 by filing a deterministic failure as a flake
> (#1115 pins structural tests to zero retries; #1116 broke the cycle). D3 burn-down 47 → **37**.
>
> **OWNER DECISION 2026-07-29: `budget_inr` HOLDS at ₹15,000 for two weeks; the ₹20,000 raise
> ([#1075](https://github.com/prashantm912/artha-yantra-2/pull/1075)) stays open and is revisited on
> LIVE data on 2026-08-12** (scheduled task `revisit-scalper-budget-inr-2026-08-12`). The decision
> turns on one arithmetic fact: the sub-account allocation is ₹30,000 and the ceiling refuses when
> projected > allocation, so at ₹15,000 each account holds exactly 2 and the book's 80% cap binds
> first at 8 — while ANY budget above ₹15,000 drops each account to 1 and concurrency to 5. The cliff
> is exactly at ₹15,000, and the two numbers that should decide it (real `ZERO_SIZE` rate, real peak
> concurrency) did not exist yet because the path had been dead.
>
> Also live from the same wave: **#1064** (no more phantom zero-volume 1m bars on non-trading days),
> **#1073** (S24 expiry-day exemption covers the spurt dot), **#1076** (a close this pass did not
> perform is no longer reported as one), **#1077** (edge-gateway Map-returning handlers typed — D3
> slice 1), **#1082** (T24: the volume dot tests the floor the RAIL tested; verify task 07-29 16:20),
> **#1065** (exit-equivalence fixture widened — all copies AGREE). Owner items unchanged: B8 clock,
> T9 arming, I4 ~2026-08-09, and now budget_inr ~2026-08-12.
>
> **LATE 2026-07-29 — architecture-deepening sweep + D3 slice 1 (main `195bfd1b`).** The four §9
> candidates are resolved: **§9-02 CLOSED** ([#1094](https://github.com/prashantm912/artha-yantra-2/pull/1094),
> one definition of the CA-adjustment factor), **§9-05** done earlier, **§9-06 DECLINED** (the two
> 1m→N rollup anchors are mutually exclusive — IST-midnight for `time_bucket` parity vs 09:15
> SESSION_OPEN for the grid join; 555 minutes makes them identical for 1/3/5/15m and divergent for
> 10/30/60m, so one definition provably cannot serve both), and **§9-04 BUILT but HELD OPEN**
> ([#1095](https://github.com/prashantm912/artha-yantra-2/pull/1095) — `PremiumLevels` gives the
> `premium_pct` formula one home; the ledger gate reads "widen the fixture **+ owner go**", the
> widening is met via #1065 and the go is not, so it awaits the owner and has had no review round).
>
> **D3 slice 1 (Map-return burn-down) moved 68 → 47 handlers**:
> [#1097](https://github.com/prashantm912/artha-yantra-2/pull/1097) typed the whole signals family
> (25→18) and [#1098](https://github.com/prashantm912/artha-yantra-2/pull/1098) the paper read
> envelopes + journal list (18→14). Remaining: market-data 26, strategy-signal 14, backtest 7.
> These are contract-visibility changes only — no engine, money, parity or migration surface — and
> **need no deploy**: they alter the published OpenAPI shape and the generated TS client, not
> runtime behaviour. New chip task_7f57c0d5 (`OpeningSignal`'s three nullable `JsonNode` fields).

**Re-platformed 2026-06-19 to the OpenAlgo + React master plan.**
`docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md` §16.1 is now the
forward-work authority (Phases 0–6). The legacy **Stage A–G** system in the sections below is the
**historical as-built record** — all merged to `main` (A 2026-06-12; B PR #2; C PR #4; D PR #5; E
PR #6 `a96c99b`; F/G via the market-data + oipulse-parity PRs through #41). Its exit-gate
checklists stay as the as-built reference; new phase boundaries are tracked by the map below.

**Current frontier (2026-07-02) = the Phase-5 Minervini screener is the lone remaining net-new build; everything
else pending is owner-gated / verify-only / scheduled maintenance, not code.** Forward inventory:
**`docs/superpowers/plans/2026-07-02-remaining-items.md`** (supersedes the archived 2026-06-30 build inventory).
**2026-07-02 was a double-audit day, both fix queues FULLY CLOSED same day:** the **full codebase audit**
(41 agents; P0 #407–#412 paper-pipeline/brackets/timeouts/backups/notifier, P1 #413–#419 calendar+job-spine+
fail-closed-compose+UI-trust+FeedWatchdog, P2 #420–#434 ratchets/tripwires/V027-drops-candles_3m/FailPolicy-
registry/G1 — redeployed live ~11:00 IST) and the **frontend UI live audit vs oipulse** (2 passes; Waves 1–5 +
charts part B + FE-e2e repair = PRs #440–#475 — capture bucket alignment T2, trending-OI cap T1, 09:15 pre-open
gap-bar data fix #459, stock-chain OI as **on-demand Upstox warm** #472, FE-e2e 44/44 + CI shard BLOCKING #474).
Earlier 2026-06-30 closes stand: scalper signal-side §1a (#371–#374), frontend/oipulse §1b (#375–#379, #390),
E11 PE-mirror (#381/#382) + straddle auto-exit (#383/#384), E9 tunable band (#386), 200-day equity backfill
(#389 — the Minervini MA history is seeded). Gate observability shipped #404 (`signal_rejections` + page).
What remains beyond the screener: the **~1-month live forward-paper gather → E9/keep-cut-tune analysis**
(runbook `2026-06-30-live-signal-analysis-runbook.md`), SPAN real-`.spn` parity sign-off, per-strategy
notification toggles + Telegram token, next-session live verifies (T2 buckets / 09:09:30 pre-open scan /
09:16+09:18 crons), and the **CD-2 2027 calendar-CSV refresh before ~2026-11-16**. W-U4 Upstox cutover is a
settled owner NO (stay Kite, split-by-capability). The expired/OI backfill is **COMPLETE/idle**; the Data Ops
Console is DEPLOYED + live.

**2026-07-07 currency update:** both swing families are now BUILT + LIVE (Minervini Phase-5→Phase-9 + deep-history
calibration → 12-slot/80%/6.5% book #563; Manas Arora family + doc-faithful exits, plus the two
backtest-improvement follow-ups **F1 RS-rank funnel #611** and **F2 §3.4 pyramiding #612** armed live 2026-07-07).
The lone net-new build frontier (Phase-5 Minervini screener) is CLOSED — everything remaining across both
families is the supervised forward-paper watch + the owner's §0.5 #12 reliability sign-off (owner-gated, not
code). Live forward-state detail: `docs/superpowers/plans/2026-07-02-remaining-items.md`.

**2026-07-10 currency update:** the external **batch-liveness dead-man's-switch** is ARMED (healthchecks.io ping @ 20:15
IST — catches a whole-stack outage the in-process P0-4 did-not-run canary can't). The **swing sell-decision FE** (audit M20)
shipped LIVE at `/strategies/swing-sell-decisions` (Minervini + Manas HOLD/SELL triad, was curl-only). **Optimizer
`/optimizations/run` request-dict validation** hardened (#648/#651 — bad `maxTrials`/objective → 400, not a 500 / silent
empty sweep; validated pre-resolve). **F9/F7 arm-effects verified against live code** (both deployed-dormant, flag-off):
arming **F9** (`ARTHA_PAPER_RISK_ENABLED`) = a scalper-only heat circuit-breaker (open SPAN ≥60% of book equity pauses NEW
scalper entries + a TRIP audit + ntfy; ZERO displayed-number change; swing books inert); arming **F7**
(`ARTHA_GRADUATION_PROMOTION_ENABLED`) = an inert `strategy_graduations` marker + one push — it promotes NOTHING live
(never republishes / swaps config). Fixed the per-trade-risk `.env` knob name mismatch (#653). Everything still owner/data-gated:
the forward-paper reliability month, an always-on host, the flag-arms, and the audit HIGH/MED doctrine items (H6/H8 + the
swing exit-parity HOLD batch #128).

**2026-07-10 (later) — two design programs closed; the NEXT net-new build frontier is defined.** Two 2-prompt
programs each produced an audit + a 2-pass-adversarially-verified design, all four MERGED same day:
**Program 1 (backtest/live research fidelity):** the 4th audit `docs/audits/2026-07-10-research-fidelity-audit.md`
(#658 — headline P0: live 3m/5m/15m/1h series evaluate FROZEN 1-min partial buckets, fix chip filed) → the
**strategy evolution engine design** `docs/superpowers/plans/2026-07-10-strategy-evolution-engine-design.md` (#659,
ledger row `evolution-engine` #660). **Program 2 (everything else — all 77 app pages):** the 5th audit
`docs/audits/2026-07-10-app-platform-audit.md` (#661 — headlines: manual paper orders bypass the risk governors,
signal status transitions never re-published, FII/DII silent ingest holes, no ingest ledger, 71/203 Map-returning
handlers at ratchet capacity) → the **intelligence/decision-support layer design**
`docs/superpowers/plans/2026-07-10-intelligence-layer-design.md` (#662, ledger row `intelligence-layer` #663).
Housekeeping: 6 shipped plan docs archived + README refreshed (#664), older archive links repaired (#665); the live
stack was redeployed to HEAD pre-open (all services SHA-verified). **The net-new build frontier is now: the
app-platform audit's Phase-1 foundations (`ingest_runs` ledger + paper-order guards V1–V3 + silent-failure kills) —
the shared prerequisite BOTH merged designs gate on (evolution E0 / intelligence I1); owner sequences which program
builds first.** Neither design self-arms anything; both builds are NOT started.

**2026-07-11 — the shared build frontier is CLEARED; both engines are now buildable.** The
2026-07-10/11 overnight run shipped **21 queue items (#683–#717, all merged + deployed +
live-verified)** — the app-platform Phase-1 foundations PLUS every prerequisite BOTH merged
designs gate on. **Evolution E0 (all 7 HARD gates) closed:** B1 partial-buckets #683, B2
engine-SHA #703, B6 optimizer durability #708, B7 `created_by` actor #710, B8 experiment
views+compare #714, B9 swing funnel-universe resolution #706, B10 regime-label reconciliation
#705 (row 21 param-grammar shipped 2-of-3 #716, row 22 pool cap #717). **Intelligence I1
hard-dep satisfied:** the `ingest_runs` ledger #686 + canaries #689, plus the manual-order
governor #687 / idempotency #690 / tick-freshness #694 safety block (audit V1–V16 largely
closed; V5/V16 reconcilers #701, admin audit #698, ingest health board #699). Migrations
`strategy V028/V029/V030`, `marketdata V040/V041/V042`, `backtest V008/V009/V010` applied +
probed live. **The B1 partial-bucket fix (#683) is a live behavior change** — coarse-primary
scalper entries go UP with wider stops from Mon 2026-07-13 (an E8 re-tune prerequisite,
pre-announced). Fix-logs appended to both 2026-07-10 audits. **Net-new frontier now: the
owner sequences which engine builds first — evolution E1 and intelligence I1 are both
buildable; neither is started, neither self-arms.** Detail: `docs/superpowers/plans/2026-07-02-remaining-items.md`.

**2026-07-12 — both design programs are substantially BUILT + LIVE; the frontier moves to their later increments.** The
2026-07-12 run shipped **#736–#782 over 5 deploy rounds** (final live @ `6f1556d8`). **Evolution: E1→E4 LIVE** (experiment
model + scoring #720–#723, search upgrades #725–#729, live-evidence integration #731–#740, proposals/promotion pipeline
#760/#767/#774). **Intelligence: I1→I3-backend LIVE** (insights module + digests + feed #742/#745/#752, context/rejection
intelligence #765/#766, actions/compare/evidence backend #778). **App-platform Phase 2 COMPLETE** (#769–#773) **+ Phase 3
substantial** (#776/#777/#780); A10 snapshot-prune scheduled (#749). The **research-fidelity audit is closed for every
startable P0/P1** (#750–#764). **3 flags ARMED live:** paper heat-cap enforcement, graduation marker, notifications on ALL 45
published strategies. **New frontier (all owner-sequenced):** EVO E5/E6, INT I3-FE/I4 (delivery arming owner-gated), APP
Phase 4 (ledger D3) + the FID Phase-2–4 remainder (D4) + the D2 Phase-3 residue. Nothing self-arms. Detail:
`docs/superpowers/plans/2026-07-02-remaining-items.md` §0 groups C/D.

**2026-07-12 (evening) — wave 8 shipped the EVO/INT later increments; frontier narrows to D3/D4-remainder + owner arming.** 11 PRs (#784–#795) merged in one deploy round (live @ `173b93c1`, both JVM services SHA-probed, 6 migrations DB-probed: backtest V015/V016/V017, strategy V039/V040, marketdata V046). **EVO E5 (#792) + E6 (backend #793 + FE #787) → E1–E6 ALL BUILT** (autonomy scheduler is DEFAULT-OFF + per-campaign enroll consent — arming is owner-gated). **INT I3-FE (#789) → I1+I2+I3 COMPLETE** (only I4 delivery-arming owner-gated remains). **D4 two slices LIVE:** dataset comparability (#791, content hash + `dataset_epochs` + provenance block + evidencePolicy, V015 — epoch WRITERS not yet wired) + paper forensics (#788, reject ledger + fill quote provenance + flag snapshots + `signals.book` frozen at emission, V039/V040). **HOLD pair (#794):** stale-signal manual-take gate (`artha.paper.signal-take-max-age-minutes=60`, swing-exempt) + live BTST pre-close exit sweep (close→close, first live btst exit) — both 4-lens reviewed. 10 chips closed. **Frontier now = D3 (APP Phase-4 planes: unified job envelope + jobs console, reference-data tables + admin CRUD, user_prefs + saved views + cmdk, DataTable adoption, Map-return burn-down, event registry, alert-rules, multi-window panes), D4 remainder (P1-3 exit-reason, P2-2..P2-8, #15/#26/#28/#29), D2 residue** + owner rows (EVO autonomy arming, INT-I4). **A wave-2 build of D3/D4-remainder was LAUNCHED then ABANDONED mid-build (owner paused; token budget) — NOTHING merged; the 10 worktree branches (feat/d3-*, feat/d4-{exit-reason,decision-traces,run-tags-export,data-quality-report,latency-instrumentation}, fix/{stranded-carry-reconciler,counterfactual-it-race}) hold partial LOCAL commits only, and the provisionally-assigned migration numbers (backtest V018/V019/V020, strategy V041/V042, marketdata V047/V048) remain FREE on main.** Build resumes after Mon 2026-07-13. **Monday is a B1 calibration session** (partial-bucket fix: scalper entries UP + wider stops; judge on `ay_signal_partial_bucket_mismatch_total`); first live fires exercise `fired_diagnostic` (C4), `flag_snapshots` + quote provenance (#788), frozen `book`, and — if the 1 live btst strategy carries — the new pre-close exit sweep.

**2026-07-19→20 — the D3 DataTable-adoption plane is COMPLETE; D4's last deferred UI row closes with it.** 8 PRs (#941–#948) merged, main `e12d1ce2`. Two clean reliability items — **#941 session-liveness heartbeat** (external dead-man's-switch every ~10 min inside [09:30,15:20) IST, gated ONLY on the engine's `lastBarReceivedAtMs` receive-gap; closes the F10 gap where the 20:15 `SwingBatchHeartbeat` proves only the batch ran; **default-OFF/dormant** — arming = create the external check + set `ARTHA_HEARTBEAT_SESSION_URL`) and **#942 AYDB-03 storage re-baseline** (50 GB soft trigger → the real 100 GB ceiling, warn 75 / alert 90; live 27.7 GB = 27.7%). Then the **DataTable plane**: shared-component capability built (`onRowClick` + `maxHeight` #945, `renderExpanded` #946 — all optional, omitted-render byte-identical) and every non-bespoke table converted (#943 ×4, #944 ×5, #945 ×2, #946 RejectionsPage, #948 per-trade). **This also closes D4 `#28-A`**, deferred since 07-14 precisely because "rejections has expandable detail rows with no DataTable equivalent" — #946 built that equivalent; mobile card-mode came free via `mobileLabel`. ⚠️ **New a11y law now in CLAUDE.md + the DataTable JSDoc: NEVER `role="button"` on a `<tr>`** (audit M23/#596 — it strips the cells of their `row` parent, AT sees zero data rows); clickable/expandable rows keep `role="row"` and the keyboard control is a real in-cell `<button>`. It was re-broken and re-fixed inside this session — caught by a dedicated `ui-a11y-reviewer` **after** both cross-vendor Codex and the Architect audit had approved the code (**code-correct ≠ a11y-correct**); its follow-up chip shipped as #947 (WCAG 1.4.1 non-colour cue on the confluence dots). **Frontier now = D3 remainder** (unified job envelope + jobs console, reference-data admin CRUD [source owner-gated], user_prefs + saved views + cmdk, **Map-return burn-down** ← the highest-value one, event registry, alert rules, multi-window panes) **+ D2 residue + owner rows** (EVO autonomy arming, INT-I4, SEC-01 rotation, PF-02). **D4's queue stays exhausted/owner-gated** (async-warm #29-A10, API-token #29-A8/A9, D6 PIT constituents, P2-7 SPAN).

**Scalper engine → 100% (2026-06-28):** the S24 incorporation is closed (debloated operative doc + W3 drift
tags + W4 gates + 2b infra, all BUILT/inert). Finishing the engine to "nothing-deferred" is now tracked in
the consolidated roadmap [`docs/superpowers/plans/archive/2026-06-28-scalper-to-100-roadmap.md`](docs/superpowers/plans/archive/2026-06-28-scalper-to-100-roadmap.md)
(ARCHIVED 2026-07-02 — build complete)
(12 epics E1–E12 / ~95 packages, dependency waves, descopes, owner-decisions). The bloated `docs/strategy-audit/`
chain is CLOSED. Arming any built/new tag on a real strategy is the owner's forward-paper (2c) step.

**2026-06-26 — 2b scalper tunable-infra COMPLETE (#220–#230, all MERGED + DEPLOYED + live-verified):** the
12 Siva scalpers became 36 instrument-agnostic, tunable, paper-ready variants on the real expired-premium
archive. Arc: **#220** fold-fix (walk-forward folds route through `OptionsPremiumReplay` → `oos_fold_mean`
populates, live-verified 942.52); **2b-E1 #222/#223** historical **NIFTY-FUT-CONT** continuous front-future
1m signal series (reconstructed for backtests; backfill skips the wide cagg refresh); **2b-E2 #224** decouple
`signal_underlying` from the option-execution root; **2b-E2b #225** `strike_reference` spot + **SENSEX-FUT-CONT**
(BFO) — the **three-way decoupling** signal / strike-anchor / option-root, all defaulting to the signal series
so goldens stay byte-identical (ADR-0003); **2b-1 #226 + #227** the 12 scalpers forked into 36 variants
(×{NIFTY, SENSEX·NIFTY-OI, SENSEX·SENSEX-OI}) seeded LIVE as drafts (`ARTHA_SCALPER_SEED_STRATEGIES`); **2b-E3
#228** the tick-wise golden runner gained 3m-primary support (it had 5m/15m/1h only → every 3m scalper backtest
failed at submission; parity-safe additive `case "3m"`); **2b-2 #229** 36/36 full-window functional backtests
green (zero engine errors), sweep pipeline validated; **#230** backtest jobs/results show strategy name +
returns + a strategy filter + pagination. The 36 are **functional-screened only** — returns are overfit/NOT
tradeable, and the OI-confluence gate is MUTED on history (Dow+IV → NEUTRAL) so the niftyoi-vs-sensexoi A/B is a
FORWARD-paper discriminator (identical on backtests). **Tune on live forward paper (2c), not the backtest.**

**2026-06-25 (cont'd) — backtest-realism + analytics + historical-OI session (#198–#211, all MERGED + DEPLOYED):**
A coherent arc that made the backtester trade real option premium *with* OI awareness, surfaced the results,
and lit the whole oipulse OI suite on history:
- **Backtest analytics surfaces (#199–#202):** live P&L strip on `/orders`, exit-reason breakdown +
  multi-run leaderboard on the backtest pages, and the keystone **OI-confluence trade attribution**
  (`/api/v1/backtests/{id}/oi-attribution` — post-hoc, parity-safe; buckets each trade by the historical
  Connecting-Dots trend at entry → win-rate per confluence). Engine fix #198 (min-premium floor + max-lots
  cap) killed a tiny-premium lot-explosion; #202 fixed a Postgres-timestamptz parse caught only by live-verify;
  #205 corrected inverted trend labels (composite trend is 1=Ext.Bullish…4=Ext.Bearish, no Neutral).
- **Historical OI without a snapshot backfill (#203/#204/#210/#211/#213/#214):** `CandleDerivedChainReader`
  reconstructs the per-strike chain (oi/oi_change/ltp/volume + a future-close **spot proxy**) from the
  per-contract `candles` already on disk — **no** ~1.12B-row `options_chain_snapshots` write (which would
  re-OOM the compressed hypertable). A `HistoricalOiReader` facade (snapshots-first, candle-derived fallback
  for fully-past empties, LIVE untouched) swapped into `OptionsAnalyticsController`'s one `reader` field lights
  up the **entire** OI-page suite (spurt/big-oi/trending/active-strikes/premium/oi-stats/strike-session-stats/…)
  on past dates; `ConnectingDotsService` got the same fallback (+ the historical futures spine). **#213** then
  recomputes ATM±3 Black-76 IV from candle premium (the future-close spot proxy IS the forward) → the 11th
  Connecting-Dots factor + OI-page IV columns populate near the money. **#214 was the keystone fix:** the
  activeStrikeOi/IV/VIX factors were keyed by `OffsetDateTime` but the futures-spine bars carry `+05:30` while
  JDBC `time_bucket` returns `+00`, so every `map.get(bar.bucket)` MISSED → those 3 factors read NEUTRAL on
  EVERY history session (captured AND derived) since forever; keying by `bucket.toInstant()` fixed it and
  flipped the April OI-attribution from a buggy "all Bullish 54%" to the REAL signal — **Ext.Bullish
  5tr/80%win/+8436 vs Bearish 1tr/0%win/−1314**, validating the OI-confluence thesis (strong-bullish-OI entries
  outperform). Design + adversarial review:
  `docs/superpowers/plans/archive/2026-06-25-historical-oi-virtual-readtime.md`.
  **Caveat for strategy eval:** derived history still forces Dow+IV NEUTRAL so the composite rarely hits the
  strong (Ext.) states — the OI edge reads MUTED on backtests as a data-fidelity artifact; judge OI-led
  strategies on FORWARD paper with real captured OI, not a weak historical backtest alone.
- **Replay realism (#206):** the replay (`TickwiseGoldenRunner`) now enforces the strategy's session window /
  square-off / expiry-day window (it had ignored them — a backtest could enter at 15:29 on expiry day);
  opt-in, parity-inert on the goldens.
- **OI-confluence entry gate (#208/#209):** opt-in `backtest.oi_confluence_gate` drops option legs entering
  against the historical Connecting-Dots trend (long/CE into Bearish, short/PE into Bullish) — turns the
  attribution finding into a tradeable filter (live A/B: 8tr/−6058/25% → 6tr/−2499/33%). New (additive) schema
  property.
- **Sweepable (#207):** the value-verify scalper gained a `backtest.optimize` block → the Sweep tab works
  (path-based overrides; live TPE sweep verified). All parity-safe (goldens byte-identical), each live-verified.

**2026-06-24 session (#136–#156):** the **Upstox login-free live migration** (U1 OI capture / U2 quotes /
U3 v3-WS ticker / F&O key map / cutover-prep), the **scalper registry completed to 12/12** (#3 Market
Movers + #8 BTST/STBT + **#11 long-straddle via a new two-leg/neutral engine primitive**), the dormant
**`OpenAlgoOrderGateway`**, **higher-order greeks** (vanna/charm/vomma), the **SPAN `.spn` ingest+golden
harness**, three more oipulse pages (OI heatmap / OI expiry / Open & High), and the **Scalping Cockpit
paper-trade panel + scalp-signal alerts** all merged — all flag-gated / paper / default-off, **nothing
live changed**. The full pending list is `docs/DEFERRED_BACKLOG.md`.

**Phase 3 — Track-2 Siva options scalper (MERGED #42/#43/#44).** The index-option core (#1/#5/#6/#10)
is paper-complete + risk-railed + execution-boundaried, with the manual-verification-checklist
backend done. **Phase 3.5 is COMPLETE**: the Tier-2
OI-analytics fidelity gaps T2.1–T2.8 are closed (per-side ΔOI cross/widening/drastic, sentiment
slope, spurt OI%/price%, 6-strike IV pair → 18 confluence dots + the #5 ≥50% ΔOI hard pre-gate),
and the four feasible index-option intraday strategies are implemented + seeded — **#4 Gap Theory,
#12 Trend Change, #2 Open=High/Low (per-strike Table-1/Table-2 faithful grading), #9 Morning Trade
(opening-tick)**. Golden + Parity stay byte-identical (V009 side-channel). The S24 monthly-expiry OI
suppression (`isMonthlyIndexExpiryDay` → skip the chain-OI reads) and the #2 per-strike OH/OL faithful
grading (`/options/strike-session-stats`, branch `feat/open-high-per-strike`) are also done. **Registry
now 12/12 (2026-06-24):** #3 Market Movers + #8 BTST/STBT (#148) + #11 long-straddle on a new two-leg/
neutral primitive (#155) seeded as paper drafts; #7 Hero-Zero done (#130). The **ONLY remaining strategy
gap = the SHORT-premium SELL legs of #8/#11** (gated on SPAN sizing live + live orders) and the full
stock-universe #3 (→ Track-1/Phase-5). Still deferred elsewhere: the §2 OiPulse ≥90% AI badge, Tier-3 OI
history.

### Master-plan phase map (§16.1)

| Phase | Branch | State |
|---|---|---|
| 0 — OpenAlgo spine | `feat/openalgo-spine` | **MERGED** (PR #39) |
| 1 — Data inflow (routing + ExpiryTrack OI + daily) | merged #40/#41/#112–#116, #137–#149 | **MOSTLY** — §4 routing + EOD bhavcopy daily (#40/#41) + §5 expired OHLCV+OI backfill (#112–#116) **COMPLETE/idle** (self-resume skips all 32,543 legs) + **Upstox login-free live capture (OI/quotes/v3-WS, #137/#139/#141/#149) BUILT flag-gated default-Kite** — the broker END-STATE (#217) keeps BOTH brokers split by capability; cutover = W-U4 (deploy + live A/B + owner flip), the only remaining wave. **§15 200-day daily history DONE (#389)** — `UpstoxEquityMasterClient` (NSE_EQ key) + `EquityDailyBackfillService` (`POST /market/admin/equity-daily-backfill`) → `candles`@1d `source=BACKFILL`; live-verified 222 candles/symbol (RELIANCE/TCS/INFY) |
| 2 — Quant libs (greeks + indicators) | merged #40, #156 | **DONE** — §7 scalp indicators (#40); §6 **higher-order greeks vanna/charm/vomma DONE (#156)** on `black76-math` + the chain (FD-cross-checked) |
| 3 — Scalper engine (§12 + §8 SPAN) | merged #42/#43/#44, #126, #144/#148/#154/#155 | **MERGED — registry 12/12** — core #1/#5/#6/#10 + Tier-2 OI fidelity + #2(faithful)/#4/#9/#12 + #7 Hero-Zero (#130) + **#3/#8 (#148) + #11 long-straddle on a two-leg/neutral primitive (#155)**, all paper drafts. **SPAN appliance dormant (#126) + `.spn` golden harness (#144)**; **`OpenAlgoOrderGateway` dormant (#154)**; checklist UI (#125). Only **DEFERRED**: SHORT-premium SELL legs of #8/#11 (SPAN live + live orders), full stock-universe #3 (→Track-1), §2 OiPulse badge |
| 4 — React migration (§10 + §11) | merged #82–#110, #121, #146–#177 | ~~**IN PROGRESS**~~ **SUBSTANTIALLY DONE + LIVE** (2026-07-12 currency pass — Phase-4 React migration + full oipulse suite shipped + deployed live; see the 2026-07-07 currency note above) — cockpit + React cutover + oipulse W1/W2/W3 + Data Ops Console (#121) + new pages OI-heatmap/OI-expiry/Open&High (#146/#150/#153) + cockpit paper-trade panel & scalp alerts (#151/#152) merged; **2026-06-25 frontend pass MERGED+DEPLOYED** — look/UX **revamp** (tokens+fonts+shadcn+DataTable+signature header/QueryState/motion, #158–#163) rolled out to 64/65 pages (#166–#173) + **World Indices** (#174/#176) + **Pre-Open Market** (#175) Upstox pages + **"All Menu"→per-section nav bar** (#177); **2026-06-25:** the analytics surfaces shipped (live P&L strip + exit-reason breakdown + compare leaderboard + OI-attribution tab, #199–#201), the **whole OI-page suite is now HISTORY-capable** (candle-derived fallback `HistoricalOiReader`, #210/#211, + ATM-band IV recompute #213 + the #214 `Instant`-key fix that un-NEUTRAL'd the OI/IV/VIX factors and validated the OI-confluence thesis, live-verified), and the `/orders` read-path is verified (stub → broker-gated); **Data Ops Console (#121) DEPLOYED + live** (#219 route fix); backtest jobs/results show strategy name + returns + filter + pagination (#230). **2026-06-30 — frontend/oipulse §1b COMPLETE:** Risk Calculator (#375), Multiple Window (#376), Futures Pre-Open (#377), Announcement (#378), Advance Chart + Multiframe (#379), **Calendar Spread (#390)** all built (Event Days SKIP — static Budget slideshow, no API). Remaining: data-foundation value-verify (owner oipulse sign-in), OiPulse ≥90% badge, deploy-verify of #377 (pre-open render)/#378 (NSE field mapping)/#379 (chart render) |
| 5 — Minervini Track-1 screener (§13) | `feat/minervini-track1` | ~~**NOT STARTED — UNBLOCKED**~~ **SHIPPED + LIVE (#524–#553, 2026-07-04/05)** — Track A screener (#524/#525/#526 + #527) + the full Phase-5→Phase-9 swing workflow (geometry/setups/regime/backtest/exit doctrine/live daily engine, #528–#553); frontier CLOSED per the 2026-07-07 currency note above. ~~the Phase-1 §15 200-day history is now seeded (#389); the daily 8-gate Trend Template + RS-rank screener is the lone remaining net-new build~~ |
| 6 — Backtest + forward wiring (§14) | merged #114–#119, #195–#211, **#220–#230** | **SUBSTANTIAL** — Part 2 premium-as-primary replay landed (options trade their own 1m premium, golden-pinned). **2026-06-25:** real-data value-verify DONE; sizing guards (#198), session/square-off/expiry enforcement (#206), the **OI-confluence entry gate** (#208/#209), a sweepable optimize block (#207), the **OI-attribution surface** (#201). **2026-06-26 (2b):** the **fold engine routes walk-forward folds through `OptionsPremiumReplay`** (#220 → `oos_fold_mean` populates); backtests read a historical **NIFTY-FUT-CONT / SENSEX-FUT-CONT** continuous front-future 1m signal series (#222/#223/#225); `signal_underlying` / `strike_reference` / option-execution-root are **three independent refs** (ADR-0003, #224/#225); the **tick-wise golden runner supports a 3m primary** (#228 — it had 5m/15m/1h only, so every 3m scalper backtest failed at submission; parity-safe additive `case "3m"`); **36 scalper variants ran 36/36 functional backtests, zero engine errors** (#229). **Backtest API:** `strategyId` is the registry UUID (not the slug), omit `strategyVersion` → latest draft, terminal job status = `completed`, results keyed by `resultRef`; the optimizer reads `optimize.parameters` from the YAML but `walkForward`/`objective`/`maxTrials` from the REQUEST. Remaining: **live forward-paper tuning (2c — backtest sweeps overfit, functional screening only)**, forward-test wiring; needs Phases 3 + 5 |

---

## Stage-F exit gate (plan §15.2 Phase-5 row — mirrored from the Stage-F design)

*(Legend: **impl** = implemented + unit/IT/build/lint green per phase; **walk** =
exercised on the running mock stack / Playwright e2e — runs via ci-e2e on the PR.)*

**Phase 5 acceptance criteria (demo-able):**

- [x] **Chain refreshes live via WS** — `/options` updates from the `options.chain` topic
      through the gateway STOMP bridge `[Phase 42]` (impl)
- [x] **Historical IV query over own snapshots** — history mode returns stored
      `options_chain_snapshots` rows via `GET /options/chain/history` `[Phase 42]` (impl)
- [x] **Accepting a signal opens a paper position whose P&L tracks ticks** —
      `signals/{id}/taken` (with qty) → paper position → mark-to-market from the last-tick
      map `[Phases 43/43A]` (impl; IT-proven taken→position)

**Key-deliverable checklist:**

- [x] Options chain UI (Black-76 IV/Greeks, PCR, wired filters) + snapshot-history `[42]` (impl)
- [x] Futures workbench (`/futures` term-structure, basis history, oi-buildup) `[42A]` (impl)
- [x] IV rank/percentile rollup (`iv_daily_summary`, `GET /options/iv-history`, badge/tab,
      honest insufficient-history floor) `[42B]` (impl; IT recompute-idempotent)
- [x] Paper-trading ledger on the **shared `ltp_slippage/v1` FillSimulator** with fill-audit
      columns + P&L UI — paisa-parity to the backtest vector proven `[43]` (impl)
- [x] Paper account + capital model + global risk limits + kill switch + `suggested_qty`
      at emission `[43A]` (impl; daily-loss trip pauses ENTRY only, IT-proven)
- [x] Derivative expiry settlement (intrinsic vs spot LTP, expiry STT leg, `close_reason`)
      + T-1 roll-or-close push + paper chart marks `[43B]` (impl; spot-LTP approximation
      documented in the manual guide / settlement caveat)
- [x] Universe pinning — submission-time resolve (REST-only) copied into `jobs.request`
      + checksum; publish guard lifted; editor "Published Universe (as of …)" label `[44]`
      (impl; `universe_checksum` now persisted onto `backtest_runs` rows + echoed in `/results`
      + the `/backtests/compare` universe-checksum mismatch banner beside `dataHash` — follow-on)
- [x] Trade journal — `journal_entries` + CRUD + drawer (signals surface) + `/journal`
      review route `[44A]` (impl; paper-position/closed-trade + backtest-trade drawer entry points
      now wired — follow-on)

**Cross-cutting invariants (impl):**

- [x] Resolution REST-only, submission-time, by-copy; strategy-signal holds no `marketdata`
      grant (Phase 44 FAIL guarded)
- [x] Paper fill == backtest fill **to the paisa** (shared JAR); no paper-local fill path
- [x] Unrealized P&L / equity computed on demand, never stored; risk limits on DB rows, never YAML
- [x] No cross-schema FK (journal/paper soft references only); same-schema FKs validated
- [x] Prices as decimal strings end-to-end (new exact `subtractDecimal`/`multiplyByInt`)
- [x] Chain/futures pages use per-symbol topics, never the tick firehose

---

## Stage-E exit gate (plan §15.2 Phase-4 row — mirrored from the Stage-E design Part 3)

*(Legend: **impl** = implemented + unit/build/lint green per phase; **walk** = exercised
on the running mock stack / Playwright E2E.)*

### Phase-4 key deliverables

- [x] Angular 21 SPA (zoneless, SignalStore per domain): **dashboard** `[Phase 35]` (impl)
- [x] **Monaco + monaco-yaml strategy editor with schema validation** `[Phase 36]` (impl)
- [x] **Version diff/publish UI** `[Phase 37]` (impl)
- [x] **Backtest runner + jobs monitor** `[Phases 35, 38]` (impl)
- [x] **ECharts 5.6 heatmap/parallel-coordinates trial explorer** `[Phase 39]` (impl)
- [x] **lightweight-charts ≥5.2 equity curves** `[Phases 36 drawer, 38 results]` (impl)
- [x] **lightweight-charts main chart page + toolbar/overlays** `[Phases 40, 40C]` (impl)
- [x] **Indicator-series endpoint (ta4j overlays)** — registry + series in backtest-service,
      golden-vector equality proven (IndicatorSeriesServiceTest) `[Phase 40B]` (impl)
- [x] **Leaderboard per-regime/degradation/fold panel** `[Phases 38/39]` (impl; per-regime/
      dataHash/folds-excluded surface in the fold drill-down + Pareto, not dedicated /best
      columns — backend gap, parking)
- [x] **Advisory stress-test panel in the publish dialog** `[Phase 37]` (impl)
- [x] **Chart-module lint boundary** (no-restricted-imports, CI-enforced; datafeed core has
      zero chart-library imports) `[Phase 40]` (impl; deliberate-violation-fails-lint verified)
- [x] **Signal notifier module** (ntfy/Telegram plain POST, opt-in outside the YAML, cooldown
      + hourly cap, editor controls + test-send) `[Phase 41]` (impl; FloodControl + Modularity
      green; NotifierIntegrationTest at the IT walk)
- [x] **INDIA VIX dashboard card + reserved Global-risk settings slot** `[Phase 35]` (impl)
- [x] **Monte Carlo tab + benchmark overlay + alpha/beta/IR/excess-CAGR columns** `[Phase 38]` (impl)
- [x] **1w resolution** (datafeed core + interval picker, `candles_1w`) `[Phases 40, 40C]` (impl)
- [x] **Trade/signal marks via createSeriesMarkers + deep links**, no new endpoints,
      containment preserved `[Phase 40A]` (impl)

### Phase-4 acceptance criteria (demo-able)

- [x] **Full Section-7 workflow clickable end-to-end on the mock stack** — walked
      2026-06-14; the 23-test Playwright suite is green on the rebuilt mock stack.
- [x] **Playwright E2E suite green** — 23/23 on the local mock stack (login, dashboard,
      charts + toolbar + marks, editor, versions, runner, sweep shell, signals MVP,
      notifier push, ws-reconnect, axe every route); ci-e2e mirrors it on the PR.
- [x] **Strategy edit → quick backtest → publish loop < 2 min** — create-from-template
      → validate → save → quick-backtest drawer walked; a fully-covered windowed run
      uses the guide's derived 1m window (the boot-rolling mock has thin 1m history).
- [x] **Opted-in strategy's signal arrives as a phone push** — the notifier pushes to
      the WireMock-stubbed ntfy on opt-in + test-send (walked); the in-process listener
      fires on emission.

### Cross-cutting invariants

- [x] Zero polling where a topic exists (only the 10 s system-status fallback) (impl)
- [x] No `markForCheck`; OnPush default; signal-driven re-renders (impl)
- [x] Large tables scroll within a fixed-height viewport (impl — plain `p-table` + `scrollHeight`;
      `virtualScroll` removed: the PrimeNG 21 scroller renders 0 rows / collapses to 0-height under
      zoneless CD — bounded per-view row counts make plain render fine; a zoneless-compatible
      virtualization is parking)
- [x] Bundle budgets: initial ~113 KB gz (no chart/editor lib in initial); Monaco editor route
      ~562 KB gz is the documented exception (parking) (impl)
- [x] No chart-library types outside the designated wrappers (lint-proven) (impl)
- [x] Prices as decimal strings — never `parseFloat` arithmetic (impl)
- [x] Equity/drawdown curves use the persisted downsampled curve (impl)
- [x] Notification settings on DB rows, never YAML; toggling perturbs no checksum
      (NotifierIntegrationTest checksum-invariance) (impl)
- [x] No ngx-monaco-editor wrapper; no ChartingService; no second main-chart renderer; no
      client-side indicator engine; no third-party bot SDK (impl)
- [x] WCAG 2.1 AA: `@axe-core/playwright` clean on every route (walked — light-theme
      palette/primary/toggle contrast + empty-table-header fixed in the walk)

---

## Stage-D exit gate (plan §15.2 Phase-3 row — mirrored at Phase 34; walked 2026-06-13 against the running mock stack)

*(Walk legend: **live** = exercised end-to-end on the running mock stack;
**IT** = gated by green Testcontainers/golden tests in CI, not separately
hand-walked on the mock stack — some paths can't be shown on a ~3-day rolling
mock window or without an options archive.)*

- [x] `POST /api/v1/backtests/run` → **`202 {jobId}`** → progress via
      `jobs.progress` WS (`/topic/jobs/{jobId}`). **(live: 202→completed→resultRef)**
      `[Phase 28]`
- [x] **Engine-parity test passes** — same YAML + candles ⇒ **identical trades
      live vs. backtest** (byte-identical signal lists incl. per-indicator
      breakdowns; the D15 headline gate). **(IT: TickwiseGoldenRunner replay half)**
      `[Phase 30]`
- [x] **A sweep completes and ranks configs** (grid/random/TPE/NSGA-II over
      `optimize.parameters`; leaderboard with **plateau-adjusted sort**; winner
      **promotable to a draft**). **(live: grid/TPE/NSGA-II runs, NSGA-II Pareto
      cagr+maxDrawdown, plateau `/best`, 30-trial ranking, promote→201 draft
      1.1.0; the 200-trial scale is the design target, mechanism proven at 30)**
      `[Phases 33–34]`
- [x] **S3 spike gate** (Phase 34 acceptance): pruner defaults
      (`n_startup_trials=5` / `n_warmup_folds=3`, `n_min_trials=2`) **run, recorded
      as a dated ADR amendment** (`docs/design/DECISIONS_LOG.md`, 2026-06-13) **and
      configured** — fold-fed `MedianPruner` is **enabled** (the "or pruning
      disabled" branch not taken). `[§D.13 / Phase 34]`
- [x] **A9 execution semantics green** [FP-5/6/7]: fill vectors pass for **futures
      cost legs**, **`at_close` fills**, and the **intra-bar exit-touch rule** (1m
      drill, worst-of/gap-through fallback; every closed trade records
      `touch_basis`); the **BTST pre-close bar view** assembles byte-identically
      live vs replay. **(IT: FillSimulator + replay fill-vector tests)**
      `[Phases 29–30]`
- [x] **Extended pre-flight demonstrated** [FP-1/3/19]: context-instrument
      coverage (422 `DATA_GAP` naming the context series), corporate-action window
      warning, lot/tick **as-of trade date** with the pre-accrual honesty flag.
      **(live: 422 `DATA_GAP` on missing NIFTY 50 benchmark; corp-action/lot-tick IT)**
      `[Phase 30]`
- [ ] **Options fidelity contract live** [FP-4]: an options run on mock snapshots
      records `premium_source=SNAPSHOT`; archive gap → 422 `DATA_GAP`; synthetic
      mode completes flagged `SYNTHETIC_B76` (never masquerading as snapshot-grade);
      market-data Greeks **byte-identical** after the `libs/black76-math` hoist.
      **(IT only: byte-identical Greeks + SNAPSHOT/SYNTHETIC tests green; live walk
      DEFERRED — needs an options archive + multi-month window, see parking list)**
      `[Phase 30A]`
      — **UPDATE 2026-06-24:** Part 2 (#114–#119) landed the **premium-as-primary** replay: an options
      backtest now trades the option's OWN 1m premium series (`premium_source=CANDLE_1M`), golden-pinned
      (`OptionsPremiumGoldenTest`), and the expired-contract archive that feeds it is now loading
      (#112–#116). The SNAPSHOT/SYNTHETIC live walk + the real-data value-verify remain (parking list).
- [x] **Run analytics live** [FP-31/32]: results carry
      alpha/beta/information-ratio/excess-CAGR + the benchmark buy-and-hold curve
      beside `equityCurve`; `GET /api/v1/backtests/{id}/montecarlo` returns seeded,
      reproducible bands and persists `montecarlo_summary`. **(live: analytics +
      seeded reproducible Monte Carlo verified)** `[Phase 32A]`

**Stage-end notes:** Stage E's leaderboard UI consumes the per-regime OOS columns,
the degradation badge, the "n folds excluded" flag and the fold-breakdown panel
produced here; the advisory stress-test panel consumes the Phase 32 backend.
Universe-pinning (`backtest_runs.universe_checksum`) and paper fill-audit columns
land in Stage F (the `FillSimulator` JAR itself is complete at Phase 29). The
optimizer (Phases 33–34) can overlap Stage E (different stack).

*(How Stage C was walked: Phases 18–27 implemented phase-per-commit with unit +
Testcontainers ITs + Vitest, then a full-stack Playwright E2E that drove the live
MVP through a real browser for the first time — it exposed and fixed 9
integration gaps unit/IT coverage could not reach (SPA auth-gating made login
unreachable; the STOMP `Sec-WebSocket-Protocol` echo missing failed every
browser WS handshake; a strict CSP blocked PrimeNG inline styles; the auth probe
trusted any 200 and admitted anonymous users; a `+05:30` candle warm-up query
encoding 500'd, leaving the engine cold). Then an 18-agent adversarial
spec-vs-impl audit (8 reviewers, independent verification of every finding)
confirmed 1 CRITICAL + 8 MAJOR gaps — headline: `candles.1m.*` conflated with
latest-value-wins (a dropped bar = permanent series gap + a possibly-skipped
exit), `.nan`/`.inf` 500s, duplicate YAML keys defeating the checksum,
score-breakdown decimals as rounding JSON numbers, registry filter-after-
pagination, phantom ARCHIVE audit rows, and a wall-clock `generated_at` breaking
live↔replay determinism — all fixed and regression-tested in the audit commit.
strategy-schema + strategy-engine + both services green; Playwright E2E 7/7.)*

---

## Stage-C exit gate (plan §15.2 Phase-2 row — the MVP gate — walked 2026-06-13 against the running mock stack)

- [x] **Golden-vector tests pin determinism** — same YAML + same candles ⇒
      identical signals/scores/breakdowns. `GoldenDeterminismTest` 5/5 byte-
      matches the frozen fixtures across two runs; the `ScoreBreakdown` writer is
      byte-stable (now exact-decimal strings); the replay half lands Stage D. `[Phase 23]`
- [x] **Publishing a YAML strategy → a live signal pushed over gateway STOMP,
      visible in the browser.** The Playwright MVP test publishes a strategy via
      the API and sees a live `RELIANCE`/`ENTRY` row stream onto `/signals` with
      its reasoning breakdown — the MVP statement, driven end-to-end through a
      real browser. `[Phases 23+26]`
- [x] `strategy-schema/v1` **complete + frozen** — 31-fixture corpus green;
      `slippage_bps`, `fees{}`, `objective.fold_aggregation`, `walk_forward`,
      `scoring.{optional_min_score, optional_gate_margin}` and the A7 additions
      (`1w`, `risk.session.{pre_close_at, fill_timing, exit_intrabar}`, indicator
      `instrument` override, `universe.mode: futures_of_underlying` + `futures{}`)
      all present + validated; indicator-name enum stays advisory (Q2). The
      loader now rejects non-finite scalars and duplicate keys (audit). `[Phase 18]`
- [x] strategy-engine JAR — `IndicatorVectorTest` 19/19 (ta4j matches the
      committed reference vectors exactly), `CompositeScorerTest` 9/9 (the
      normative A1 composite + optional-activation truth table),
      `BreakdownContractTest` 5/5 (byte-stable `ScoreBreakdown`); JaCoCo BRANCH
      ≥ 70 %. `[Phases 19–20]`
- [x] Registry — immutable JSONB versions + SHA-256; full
      draft→published→archived with publish/rollback/diff/validate; every
      mutating call writes an audit row (append-only BY GRANT);
      `index_constituents`-universe publish guard (422
      `STRATEGY_UNIVERSE_UNSUPPORTED`). `RegistryLifecycleIntegrationTest` 12/12,
      incl. the audit's filter-then-paginate + archive-idempotency fixes. `[Phase 21]`
- [x] `marketdata.index_constituents` — append-only with point-in-time REST
      resolution (latest-on-or-before, audit-confirmed); mock fixture path green;
      live NSE fetcher gated on source verification; no cross-schema FK;
      survivorship-bias caveat documented. `[Phase 22]`
- [x] OpenAPI 3.1 specs for the three running services committed and **diff-
      gated** in CI; each `ContractCaptureTest` green; generated TS client
      compiles under `tsc --strict` (ci-contracts). `[Phase 24]`
- [x] Angular 21 SPA (zoneless, signals-first) served **through the gateway,
      same origin, zero CORS**; login round-trip works; initial bundle **457 KB
      raw / 109 KB transfer** (≤ 500 KB budget enforced); no Zone.js, no
      hardcoded `localhost`. SPA-shell auth + CSP relaxation fixed (E2E). `[Phases 25–26]`
- [x] `WsClientService` reconnects with backoff + jitter and re-syncs the REST
      snapshot; `/signals` renders the reasoning breakdown obeying
      `composite = Σ contributions / weightDenominator`. STOMP subprotocol echo
      fixed so the browser socket opens (E2E). `[Phase 26]`
- [x] **Playwright E2E 7/7 green** on the full mock stack: login (deep-link,
      cookie flags, wrong/right password, axe), the live-signals MVP + breakdown,
      signals-page axe, and the WS-reconnect chaos test; axe reports no
      violations on login/signals. `ci-e2e` runs the same suite on every PR
      (green-on-main lands at merge). `PHASE_GATES.md` mirrors this row. `[Phase 27]`

**Stage-end notes:** `strategy-schema/v1` (Phase 18) and the `ScoreBreakdown`
contract (Phase 20) **freeze here** — Stage D's FillSimulator/replay consume both
unchanged, and the replay half of the golden parity pair asserts byte-identity
against the live half frozen in Phase 23. **Open items carried forward:** NSE
index-constituents CSV source verification (before the Phase 22 live fetcher);
statutory fee-schedule values (pinned at Stage-D Phase 29). Neither blocks the
MVP demo on the mock stack. Owner action: mint a brand-new 2.0 Kite API
key/secret for live-mode (the Stage-C manual-testing guide's live appendix).

---

## Stage-B exit gate (plan §15.2 Phase-1 row — walked 2026-06-13 against the running mock stack; merged to main via PR #2)

*(Stage B walk: 13 phases phase-per-commit + a 39-agent audit — 3 CRITICAL + ~20
MAJOR fixed (post-close ticks re-opening the flushed close bar, continuous=1 on
per-contract FUT fetches, CONT via POST /candles/refresh); 164 market-data + 20
gateway tests green.)*

- [x] **Live tick reaches Redis < 50 ms after Kite delivery.** Measured live:
      tick generation → published-on-Redis = **3 ms** (mock feed, B-6
      pipeline, same-tick comparison of the embedded producer timestamp vs the
      `ticks:last-at` publish marker).
- [x] **Historical fetch fills gaps idempotently at ≤ 3 req/s.** Cold fetch =
      exactly one gateway call; warm read = zero gateway-port invocations
      (asserted); partial coverage fetches only the missing sub-range; 50-burst
      limiter test ≥ 15 s end-to-end and never > 3/s in any window.
- [x] **The same flows pass on the mock profile** — every Stage-B phase is
      mock-green with zero Kite credentials (the whole IT battery runs without
      any Kite material; live impls are WireMock-pinned).
- [x] **Snapshots accruing every 5 min in market hours** — the Phase-15
      scheduler is calendar-gated; the IT drives a market-hours clock and the
      off-hours degradation (stale:true, zeroed book, EOD OI) separately.
- [x] **Raw quote rows persist from the first market day with IV gated on S1**
      — every row carries LTP/bid/ask/spot/OI/oi_change + `forward_price` +
      `risk_free_rate`; the 490-vector golden suite (S1, above) gates the
      computed columns via `artha.options.iv-enabled`; null-IV rows persist
      with reason codes, never skipped.
- [x] **Contract canary runs on the first LIVE transition and surfaces on
      `/auth/kite/status`** — WireMock-verified both drift directions + the
      Redis daily-once marker; mock runs no canary by design. *(Result key is
      `kite:contract:check` — documented deviation, parking list.)*
- [x] **Contract-spec history accrues from the first sync** — FIRST_SEEN rows
      on sync, as-of resolution at change boundaries, `spec_asof_estimated`
      honesty flag (Phase 9A ITs).
- [x] **Front/next/far FUT + INDIA VIX pinned; term structure from ONE batched
      quote** — single-invocation assertion; basis fixture (120.0000 /
      0.0608 @ 30d); CONTANGO/BACKWARDATION by near→next slope; per-bar FUT OI
      through to the 1d cagg's `last(oi)`.
- [x] **Continuous futures stitch deterministically** — exact 150.0000 fixture
      gap, idempotent re-runs, `adjust=back|none` verified at the API,
      roll-day divergence caveat documented in the roller javadoc + stage doc.
- [x] **Corporate-action job detects the planted split and rebuilds** —
      uniform-ratio guard rejects single-anchor and non-uniform noise;
      re-backfill rides the rate-limited gateway; `fetched_at` bump asserted;
      byte-stable post-rebuild series.

**Stage-end deliverables roll-up:**

- [x] Daily Kite contract canary (S2B) — fixture-derived manifests, recursive
      field-set diff, first-party ntfy.
- [x] Greeks golden-vector suite + S1 gating of IV persistence; raw quotes
      captured from day one (S4).
- [x] `docs/retention.md` (A2 ≥5y floor, 50 GB review trigger) +
      `docs/runbook-notes.md` (A3 minute-depth probe, S2 Tuesday-expiry note).
- [x] ~~Leaked-credential tripwire~~ — dropped per A6; the live fail-fast
      (key + secret + master key files) remains and is tested.
- [x] 2026-06-12 feature-selection additions all landed: 9A spec history, 15A
      futures slice + INDIA VIX, 15B CONT + roll_events, 16A corporate
      actions, `candles_1w`, `oi_buildup`/`rs_rank`.

**Owner actions carried forward:** minute-depth probe in live mode (A3);
NSE constituents CSV source verification before Phase 22 (S8); branch
protection clicks. **Forward dependencies (by design):** `jobs:summary`
zeros until Phase 28; `rs_rank` universe = active equities until Phase 22;
1w/futures_of_underlying validate at the Phase 18 freeze.

*(How Stage A was walked: Part 1 sections A.1–A.17 implemented
section-per-commit; Part 2 Phases 1–8 then audited one-by-one against their
Deliverables/Tests/Acceptance — Phases 1, 2, 3, 5, 6, 7 + the COMMON
conventions sweep came back clean; Phase 4 was missing the lint pre-commit
hook entry (fixed) and Phase 8 had a real `GATEWAY_WS_FLUSH_HZ` binding bug
plus two missing IT cases (fixed, tested); Part 3 exit gate walked against
the running mock stack, below. CI red→green iterations: mvnw exec bit,
gitleaks-action→pinned CLI, drift-check pending-vs-checksum semantics.)*

## Acceptance checklist (Part 1 sections)

- [x] A.9 — secrets hygiene: `.gitignore`, `.env.example`, secrets layout, gitleaks hook blocks a planted secret
- [x] A.12 — PR self-review checklist template
- [x] A.13 — golden-vector fixture-format freeze (`docs/golden-vectors.md`)
- [x] A.14 — `docs/dev-setup.md` tier table + port map (S6 corrections)
- [x] A.15 — this file: marker + checklist + parking list
- [x] A.16 — `docs/LEGAL.md` attribution record (A13) + A6 credentials record
- [x] A.1 — compose topology: pinned/healthchecked/capped timescaledb + redis, dev-tools profile, `ay` CLI, remote-access doc (Q3)
- [x] A.11 — db-backup sidecar: 00:30 IST `pg_dump -Fc` per schema, 14d+8w rotation, ntfy on failure
- [x] A.8 — Flyway one-shot init: admin + 3 per-service lineages from empty volume, idempotent
- [x] A.3 — Maven reactor + `common-web` (core / servlet adapter split)
- [x] A.4 — error-code taxonomy constants (COMMON §8.3 spellings)
- [x] A.5 — `market-calendar` (IST session, NSE 2026 holidays, Tuesday expiries)
- [x] A.6 — ECS JSON logging + `MaskingMessageConverter` (masking unit-tested)
- [x] A.2 — edge-gateway: Argon2id login, Redis sessions, route table, headers, rate limits, hash-password tool
- [x] A.7 — tick pipeline (mock feed → normalizer → Redis) + gateway STOMP WS bridge with 20 Hz conflation
- [x] A.10 — CI: ci-java + ci-migrations, gitleaks step in every workflow
- [x] A.17 — Stage-A exit-gate checklist recorded below and walked against the running mock stack

---

## Stage-A exit gate (plan §15.2 Phase-0 row — walked 2026-06-12)

**Deliverables present:**

- [x] Monorepo layout (COMMON §10.1); process docs committed (`README.md`, `PHASE_GATES.md`, `docs/golden-vectors.md`, `docs/remote-access.md`, `docs/dev-setup.md`, `docs/LEGAL.md`, PR template, 8-file design set under `docs/design/`).
- [x] Compose: timescaledb, redis, flyway-init, db-backup, edge-gateway, market-data-service + dev-tools profile — all with `mem_limit` + healthchecks + pinned tags + loopback binds. *(Remaining D7 app containers land in later stages.)*
- [x] Flyway 11 init job: 3 schemas + 3 roles + the single backtest→marketdata read-only grant from an empty volume (admin first), idempotent — `ay reset-db` twice green.
- [x] GitHub Actions `ci-java.yml` + `ci-migrations.yml` committed; gitleaks in every workflow; both Dockerfiles build locally; JaCoCo ≥60 % gates pass locally. *(First remote run + branch protection pend the first push — owner clicks protection in GitHub settings.)*
- [x] edge-gateway: Argon2id (m=19456/t=2/p=1) login + Spring Session Redis + route table + headers + 5/min login limit + 50 req/s valve.
- [x] Mock Kite feed (D13) publishing deterministic ticks; gateway WS bridge relays over STOMP-on-native-WS with 20 Hz conflation.
- [x] Review additions: PHASE_GATES (S5+P4), dev-setup tier table with S6 ports, LEGAL attribution record [A13], Tailscale-first remote-access doc (Q3). *(Day-zero rotation superseded by A6 — fresh keys, no tripwire.)*

**Acceptance (walked against the running stack, 2026-06-12):**

- [x] `ay up` green from a clean restart with **no Kite credentials** (9 healthy containers + flyway-init exit 0).
- [x] Login at `127.0.0.1:8080` works — 204 + HttpOnly SameSite=Strict cookie; authenticated session probe.
- [x] Service images build (edge-gateway, market-data-service) — CI image-build matrix mirrors the same Dockerfiles. *(CI runs on first push.)*
- [x] Mock ticks visible on Redis `ticks.*` (string decimals, `+05:30`, monotonic seq, deterministic seed) and **end-to-end via `e2e/tools/stomp-probe.mjs`** (10 frames).
- [x] Tier 2 verbatim: host-run market-data-service (`dev,mock`) connected to compose Redis published on loopback by `ay up dev-tools` — actuator health UP.

**Closed by the Part 2 verification pass (2026-06-12):** branch pushed; PR
[#1](https://github.com/prashantm912/artha-yantra-2/pull/1) opened; CI runs on
the PR; drift-check red path proven locally (edited applied migration →
checksum mismatch, exit 1); restore drill executed once via `ay restore`.

**Still owner-clickable:** branch protection on `main` (GitHub → Settings →
Branches); optional OneDrive sync of `./backups`; quarterly restore-drill
recurrence.

**Stage B parking list (seeds for the next branch):** instruments table +
candles hypertable + Kite OAuth/AES-GCM token store + live ticker + options
snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE index-constituents
CSV source verification (before Phase 22); `tools/hash-password` may gain a
compose-escaped output mode (quality-of-life).

## S1 gate — Black-76 golden-vector acceptance (Phase 14, walked 2026-06-13)

The formal S1 record (B-10 / B-15): the Phase 15 snapshot job may enable its
computed IV/Greeks columns **only while this suite stays green**; raw-quote
capture is never blocked by it.

- [x] Grid covered: F/K 0.85–1.15, T ∈ {0.5, 2, 7, 30, 90} d, σ 8–60 %, CE+PE —
      **490 committed py_vollib vectors** (offline generator, A4 exception;
      never generated at test runtime).
- [x] Greeks vs reference: relative error ≤ 1e-6 across all vectors; absolute
      ≤ 1e-9 where |reference| < 1e-3 (far-OTM gamma/vega corners included).
- [x] IV solver round-trip: reprice |Black76(IV) − market price| ≤ ₹0.01 for
      every vector carrying ≥ 1 tick of time value (324/490; the 0.5 d/2 d
      far-OTM remainder has no recoverable vol by construction).
- [x] Expiry-day: T from 5 minutes to 0 returns finite greeks via the
      documented `T_MIN` clamp (5 calendar minutes, ACT/365).
- [x] Edge corpus: at/below-discounted-intrinsic and zero-quote inputs → null
      IV + reason code (`BELOW_INTRINSIC` / `ZERO_QUOTE` / `NO_CONVERGENCE`),
      never NaN/Infinity.
- [x] Model is Black-76 **on the forward** (PCP → monthly-futures-LTP →
      `S·e^{rT}` precedence implemented and tested); no Black-Scholes-on-spot
      shortcut anywhere.
- [x] Deterministic across runs (same inputs ⇒ identical `BigDecimal` outputs).
- [ ] Market sanity (informational, non-gating): solved IV within ±2 vol points
      of the NSE chain page for liquid ATM±2 strikes on one live capture —
      pends the first live-mode session with real Kite credentials.

## Parking list (deferred)

**From the Stage-B audit (2026-06-13)** — accepted deviations + deferred work,
each with its target:

- **B-9 binary-frame guard production wiring** — `KiteBinaryFrameParser` is
  fixture-pinned and registry-driven, but javakiteconnect's `KiteTicker`
  exposes no raw-frame hook, so the guard cannot intercept live frames through
  the SDK. Production coverage today = the daily contract canary + the
  fixture-pinned envelope tests + no-tick alerting. Full wiring requires
  replacing the SDK socket with a first-party WS client (revisit when Kite
  changes its wire format or at the Stage-C hardening pass).
- ~~**`instruments.exchange_token` population**~~ — **RESOLVED 2026-06-30 (#387):**
  `exchange_token` now threaded wire→domain→DB (`InstrumentRecord` field + both dump
  parsers + `setLong(4, …)`); nullable BIGINT column, no migration.
- **Canary result Redis key** — result lands in `kite:contract:check` (JSON) +
  `GET /auth/kite/status`, not embedded in the plain-string
  `kite:session:status` the spec names (would break that key's existing
  readers). Documented deviation.
- **Recorded Kite binary-frame capture** — the mixed-frame fixture is
  synthesized from the documented envelope; commit one real capture during the
  first live session (closes the shared-misreading risk).
- ~~**`candles_1h` IST alignment**~~ — **DONE (#513, V029, 2026-07-04):** dropped +
  recreated `candles_1h` with `time_bucket('1 hour', bucket, 'Asia/Kolkata')` (was
  UTC-hour = :30 IST boundaries, unlike the 1d/1w IST siblings), `WITH NO DATA` +
  refresh policy so the live DB does no heavy one-shot materialization.
- **`kite.rateBudget` on system status** — field present, null until
  market-data-service publishes a budget key (limiter metrics exist; producer
  pends Stage C status work).
- **~5k-row mock dump fixture** — CD-14 names ~5k rows; the frozen fixture is
  ~1.1k. The ≤5 s sync budget is asserted at the committed size; regenerate at
  5k only as a deliberate fixture-freeze event.

**From Stage D (2026-06-13, merged via PR #5)** — deferred work:

- **Options fidelity live walk** — SNAPSHOT/SYNTHETIC_B76 premium-source path is
  IT-green but not hand-walked on the mock stack (needs an options snapshot
  archive + a multi-month window the rolling mock feed can't supply). Walk it in
  the first live-mode options session.
- **Walk-forward folds + fold-fed `MedianPruner` live walk** — can't be shown on
  the ~3-day rolling mock window (needs train+test trading days ≥ 3 warmup folds ×
  5 startup trials); verified by optimizer unit tests instead. Walk on a real
  multi-month dataset.
- ~~**`requirements.txt` hash-pinning** — optimizer-service deps are version-pinned
  but not hash-locked; add `pip-compile --generate-hashes` in CI.~~ **DONE (#133, struck
  2026-07-12):** `requirements.lock`/`requirements-dev.lock` generated with `--generate-hashes`;
  Dockerfile + `ci-optimizer.yml` + `ci-margin.yml` all `--require-hashes` (`DEFERRED_BACKLOG.md`:
  "do not re-flag").

**From Stage E (2026-06-14)** — accepted deviations + deferred work:

- **Monaco editor-route chunk ~562 KB gz** exceeds the E-6 "≤ 400 KB gz per lazy
  chunk" budget — Monaco's irreducible floor (`editor.api` is the lean import; the
  yaml/editor workers are separate on-open chunks; the initial bundle stays ~113 KB
  gz). The budget's intent (no heavy lib in initial) holds; the editor route is the
  one justified large lazy chunk. Not CI-budget-enforced for that chunk.
- **Phase-40B indicator cache uses Caffeine (in-memory), not Redis** — the
  `StrategyVersionClient` precedent; single-instance backtest-service; unit-testable;
  functionally equivalent. Swap to Redis if the service ever scales out.
- **Optimizer `/best` omits guard COLUMNS** (per-regime OOS Sharpe/expectancy,
  regimes-covered badge, `dataHash` parity, "n folds excluded") — those guard outputs
  surface in the Phase-39 fold drill-down (regime chips + guard-7 degradation), the
  all-trials states (pruned/failed flagged), and the Pareto front, NOT as dedicated
  leaderboard columns. Adding the columns needs an optimizer `/best` enrichment.
- **Strategy list last-backtest summary** → RESOLVED (2026-06-14): the strategy list
  shows a "Last backtest" column (Sharpe + equity sparkline). Strategy versions carry
  `currentVersionId`/`publishedVersionId`; the frontend enriches via backtest-service
  `GET /api/v1/backtests/summary?strategyVersionIds=…` (latest run per version) — the
  cross-schema join stays client-side, no new cross-service backend dependency.
  `/strategies/compare` still shows configs only (no per-version compare metrics).
- **Backtest `TradeRow` symbol + SL/target** → RESOLVED (2026-06-14): each trade row
  denormalizes the run instrument (`exchange`/`tradingsymbol`) and persists entry-time
  `stop_loss`/`take_profit` levels (migration `V005`; levels computed parity-safe as a
  `SignalEvent` side-channel — golden vectors byte-identical). Results expose the run
  symbol so the "View on chart" deep-link carries the right instrument; the `/trades`
  endpoint accepts `symbol`/`from`/`to` filters. SL/target chart price-lines remain a
  display follow-on (data is now available; trades table shows Stop/Target columns).
- **Per-trade reasoning drill-down** shows the trade's `contributions` map (all the
  `TradeRow` persists), not the full `ReasoningBreakdownPanel`; compare-page
  trade-distribution histograms deferred (need per-run trade fetches).
- **Notifier payload** sends composite score (threshold included); paper-trade chart
  marks arrive in Stage F Phase 43B (slot reserved in the mark-source vocabulary).

*(other items deferred out of a section land here with their target)*

- Stage B seeds (recorded in the stage file, not deferred work): instruments
  table, candles hypertable, Kite OAuth/AES-GCM token store, live ticker,
  options snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE
  index-constituents CSV source verification (before Phase 22).
