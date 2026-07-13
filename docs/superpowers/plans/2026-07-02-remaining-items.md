# Remaining items — the single forward ledger (2026-07-02)

**Status:** ACTIVE — the one place listing *everything still open* across the whole platform.
Supersedes [`archive/2026-06-30-remaining-build-inventory.md`](archive/2026-06-30-remaining-build-inventory.md)
(kept for provenance; its §4 false-flags, §5 descopes and §6 WON'T-DO lists are carried forward in §7 below
so nothing gets re-flagged). Cross-checked 2026-07-02 against `PHASE_GATES.md`, `docs/DEFERRED_BACKLOG.md`,
the two 2026-07-02 audits (both fix queues fully closed) and the open-PR/issue list (empty).

**Owner rule:** when an item here ships, mark it DONE in place (PR# + SHA) before starting the next one.

---

## 0. Work queue (consolidated 2026-07-10; 2-pass-audited same day)

The flat, ordered index of every actionable open item across the four 2026-07-10 docs
(fidelity audit `docs/audits/2026-07-10-research-fidelity-audit.md` = **FID**; app-platform audit
`docs/audits/2026-07-10-app-platform-audit.md` = **APP**; evolution design
[`2026-07-10-strategy-evolution-engine-design.md`](2026-07-10-strategy-evolution-engine-design.md) = **EVO**;
intelligence design [`2026-07-10-intelligence-layer-design.md`](2026-07-10-intelligence-layer-design.md) = **INT**)
plus the standing owner/data gates (group E) and the older-source startables (group F — 07-05
audit, 10x roadmap, ledger §8/§9, signal-analysis backlog). **Rules:** detail lives in the source
doc's § — a row here is 1 line + a pointer, never the spec. When an item ships: flip Status to
`DONE PR#` in THIS table (and keep the §1/§2 program rows as the prose history). Status values:
`TODO` · `IN-PROGRESS` · `BUILT-HOLD` (built, owner merge/arm pending) · `OWNER` (decision/
data-gated) · `DONE PR#`. **Dependency truth:** Group A (APP Phase 1) is the prerequisite for
INT-I1 (A4 hard; A1/A3 gate the later INT-I3 actions); **EVO-E0's seven gates are B1/B2 +
B6–B10** — A and B are independent tracks.

**STATE 2026-07-11 (overnight autonomous run, PRs #683–#718):** every startable clean/HOLD row in
groups A and B is **DONE** — B1 + A1–A9/A11/A12 + B2/B6–B10/B15–B17/B8, all merged, deployed live,
DB-probed, and live-verified the same night. **All seven EVO-E0 HARD gates are CLOSED** (EVO first
increment C1 is now buildable) and APP Phase 1 is complete (INT-I1's A4 hard-dependency satisfied;
C2 buildable). What remains: OWNER rows (A10, group E), the HOLD-tier design forks B3/B4/B5/B11–B14/
B18 (each wants an owner read before build), groups C/D (next program phases), and group F residue.
**UPDATE 2026-07-11 (day session): C1 EVO-E1 DONE (#720–#723, live-verified — see the C1 row).
EVO-E2 ALSO DONE same day (owner-directed continuation; first 4-way-parallel builder wave): #725 insights
(signed-Spearman tornado/brittleness/slices), #726 deflated-Sharpe gate + DOF penalties (campaign-cumulative N),
#727 backtest `stressOverrides{slippageMultiplier}` (goldens/parity byte-identical; zero-floored stressed SELLs),
#728 neighbor probes (boot reaper — deleted 1 REAL stranded row on first live boot), #729 stress orchestration
(2×/4× + degradation slope, durable STRESSING lifecycle, evidence-drift guard). Deployed + live-verified:
probe round on sweep 46706aa4 lifted trial-4 neighborCount 0→6 (plateau MEASURED), insights + deflated gate live
(N counts probes, DOF penalty exact), stress endpoint honestly skips un-rankable candidates (the full live 2×/4×
round awaits the first ≥3-regime campaign — unit-covered end-to-end). Follow-up chips: task_8c3964cc
(stress-vs-interactive worker reservation), task_547656bf (3m tradeFrequency). EVO next = E3 (design §12 items
7–10, incl. ONE HOLD: runtime shadow-variant registration API); still-startable clean rows = C2/C3 + F residue.**
**UPDATE 2026-07-12 (E3 wave 1, 3-way-parallel): item 7 reconciliation computer DONE #731 @ bbc9abdd (V012
`reconciliations`, gapZ √t-TIME-NORMALIZED per review — the raw design formula manufactured false DIVERGENTs;
funnel single-pick diagnosis gated; live-smoked: 202 → re-sim → honest INSUFFICIENT row, both caveats stamped).
Item 9 counterfactual-replay DONE #732 @ ab9ee0d7 (V013 COUNTERFACTUAL job kind, frozen PremiumExitEvaluator
reused, captured-data-only guard; live-smoked BOTH directions: DATA_GAP 422 on an uncaptured leg + a real
2-variant replay to completion). Item 8 shadow-variant runtime API = **OWNER-APPROVED → MERGED #733 @ 971dff7a + DEPLOYED LIVE 2026-07-12**
(V031 probed; live round-trip: register 201 → hot-reload active set 3→4 without restart → retire 204 → back
to 3, soft-disabled row with campaign ref; 3-lens review: live path SAFE, §3.3.3 per-bar clamp, per-strategy
league filter). ~~E3 = FULLY SHIPPED except wave 2~~ **E3 wave 2 SHIPPED 2026-07-12 → EVO E3 FULLY DONE**: fill-timing
override #740 @ 884f838a (`sessionOverrides{fillTiming}` strict-422, goldens/parity byte-identical, reconciliation
now PINS at_close for swing — honest residual exit-TRIGGER caveat kept) + item 10 live-gap gate #738 @ e4dde79b
(verdict-keyed hard gate + live_alignment component, §7.2 scalper whitelist, direction-tested; fetch-and-attach
hook deliberately deferred to E4 — candidates carry NULL versionId until then). Known data note: NFO per-contract
option 1m capture is sparse after 07-06 — verify Monday.**
**UPDATE 2026-07-12 (owner-authorized full-autonomy overnight run, 10-parallel builders; 12 PRs #736–#747 merged +
DEPLOYED LIVE @ 9dd93bd4, all V032/V033/V043 DB-probed, all services healthy on the true-main SHA):**
**Wave 1 (10/10):** C3 rs_rank #736 · M17 STOMP book field #737 · EVO item 10 #738 · M1+M16 paper meds #739 (heat-gate
blindness now audited — UNPRICED risk_audit rows; atomic governor seed) · fill-timing #740 · M18+M28 #741 (cockpit
honest book framing + 4 new e2e specs) · **INT-I1 SHIPPED = C2 DONE**: insights module #742 (V032/V033, engine +
TrustService + 3 shadow generators + 9 typed endpoints + golden test) + market-data digests #745 (V043
market_context_days + day-context/options digests, 19:45 IST EOD ledger job; review-caught HIGH fixed: stale-anchor
capture-outage days now DEGRADE + write NULL scalars, never fabricate) · F7 scalp payload extras #743 (suggested_qty
+ /orders deep link, dormant until ARTHA_NOTIFIER_APP_BASE_URL set) · F6 residue ×4 #744 (bhavcopy re-fetch endpoint,
AC-1 futures-search de-flood, kite.rateBudget real value — live-verified 1.0, Manas pyramiding IT) · F8 docs pass #746
· post-merge #742×#743 arity conflict fixed #747. **Owner decisions landed same night:** E6a regimes stay FROZEN
(see E6 row — revisit 2026-08-14); E6b optimize blocks AUTHORED + PUBLISHED (both Manas strategies v1.1.0 live,
engine reloaded; smoke sweep validated the block end-to-end — trials failed only on the weekend funnel-pin edge,
chip task_03b9f52d); **FLAG ARMS LIVE: ARTHA_PAPER_RISK_ENABLED=true (60% heat-cap ENFORCING on scalper book) +
ARTHA_GRADUATION_PROMOTION_ENABLED=true + notifications_enabled flipped for all 45 published strategies.**
~~Wave 2 in flight~~ **ALL WAVES LANDED by 2026-07-12 ~06:00 IST (run total: 32 PRs #736–#767, 3 deploy rounds, all live @ ddb4e8ff):**
waves 2–5 added A10/F2/F3 + INT-I1-FE #752 + the ENTIRE sim-honesty HOLD batch (B11–B14, B18) + ALL THREE FID P0
forks (B3 #764 live-verified with a real full-history lineage run; B4 #757; B5 #759) + C4 #763 + **INT-I2 COMPLETE**
(#765 generators + #766 digests — 7 new generators, 4 new digests, fired-vs-rejected Stage 1) + **EVO-E4 slices 1+2**
(#760 proposals inbox+ntfy+item-10 attach hook; #767 §8.1 selection + APPROVED→PUBLISH_PAPER execute — two owner
clicks before anything publishes; TAKE_ELIGIBLE/PROMOTE/ROLLBACK + RETIRE ack-rows = next slice, keyed on the PAPER
state) + analytics-CA #761. **The FID audit's startable queue is now EMPTY** (every P0/P1/P2 row shipped or chipped).
**WAVE 6 LANDED 2026-07-12 ~08:00 IST (#769–#774, deploy round 4 @ 7fb6c1f5): D1 = APP PHASE 2 COMPLETE +
EVO-E4 slice 3 (#774 — TAKE_ELIGIBLE/PROMOTE/ROLLBACK + RETIRE acks + demoted-champion counterfactual, full
fixture state-machine walk; counterfactuals cap-exempt, redundant clone archived on promote; E4 = items 11-13
ALL DONE → EVO next = E5).** Remaining startables: INT-I3 (NOW unblocked — D1 events/UI landed), EVO-E5,
D2/D3/D4 phases, F5 signal-analysis backlog (owner), owner rows. Monday watches unchanged + NEW: first scalper fire populates fired_diagnostic (C4),
20:05 batch writes the first F3 probe rows, funnel-pin weekend edge re-verify (task_03b9f52d).
**WAVE 7 LANDED 2026-07-12 ~13:00 IST (#776–#780 + #779, deploy round 5 @ 6f1556d8): INT-I3 BACKEND COMPLETE #778
(PROPOSE /act — server writes the audit trail, the owner's browser performs the real governed call; signal compare;
strategy dossier; STRATEGY_EVIDENCE + SELL_DECISION generators; WS insights channel flag-OFF) → the intelligence
program is I1+I2+I3-backend done; D2 Phase 3 substantially done (see the D2 row); chip task_fab9e823 fixed #779.
Next startables: I3-FE half (compare/dossier/act views), I4 (owner-gated arming), EVO-E5, D3/D4, D2 residue.**
**WAVE 8 LANDED 2026-07-12 evening (11 PRs #784–#794, one deploy round, live @ 173b93c1, both JVM services SHA-probed):
EVO-E5 COMPLETE #792 (ablations/paired-eval/IS-only-reject/graveyard/suggesters — fail-closed ACCEPT, V016) ·
EVO-E6 BUILT #793 backend (autonomy scheduler DEFAULT-OFF + per-campaign enroll consent + full §1.3
probes→stress orchestration + budgets/expiry/reports, V017) + #787 FE (/evolution suite) → EVO E1–E6 ALL BUILT;
autonomy arming = owner rows only · INT-I3-FE COMPLETE #789 → INT I1/I2/I3 FULLY DONE, I4 arming remains ·
D4 SLICES: dataset comparability #791 (content hash + dataset_epochs + provenance + evidencePolicy, V015;
epoch WRITERS not yet wired — market-data call sites are the follow-up) + paper forensics #788 (reject ledger +
fill quote provenance + flag snapshots + signals.book FROZEN at emission, V039/V040) · HOLD pair #794
(stale-take gate 60m/swing-exempt + live BTST pre-close exit sweep, 4-lens reviewed, parity boundary fenced) ·
chips closed: task_8c3964cc+task_f12c165f #784, task_132a71ba+task_547656bf+task_03b9f52d-core+task_c7132464 #785,
task_b57c7fbe+task_4f575c72(no-gap) #786, task_9062b5f1 #790, task_94f40cf6+task_3e95fade #794. Deploy: 6
migrations probed, signals.book 41/41 backfilled, scheduler dormant-verified, E5 endpoints honest-empty live.
Remaining startables: D3 (8 Phase-4 planes), D4 remainder (P1-3, P2-2..P2-8, #15/#26/#28/#29), D2 residue,
task_2560273c, task_1b85c64f; NEW OPEN chips: task_9e244d18 (counterfactual IT race), task_a86f2d17
(stranded-carry reconciler), task_ed6b9d81 (btst publish-time parity validation).**
**WAVE 2 (D3/D4-remainder) LAUNCHED 2026-07-12 then ABANDONED mid-build (owner paused, token budget) — NOTHING
MERGED, main clean @ 435e976e (heads still backtest V017 / strategy V040 / marketdata V046).** 10 builders were
briefed + stopped; their worktree branches hold PARTIAL LOCAL commits only (no push, no PR): feat/d4-exit-reason-attribution
(P1-3, was to be V018), feat/d4-decision-traces (P2-8, V019), feat/d4-run-tags-export (P2-2+P2-3, V020),
feat/d3-reference-data (V047, SOURCE-agnostic — the constituent SOURCE pick stays owner-gated), feat/d4-data-quality-report
(P2-4, V048), feat/d4-latency-instrumentation (P2-5, V041), feat/d3-alert-rules (V042), fix/stranded-carry-reconciler
(task_a86f2d17), feat/d3-datatable-adoption (FE, unowned pages only), fix/counterfactual-it-race (task_9e244d18).
**The provisionally-assigned migration numbers (backtest V018/V019/V020, strategy V041/V042, marketdata V047/V048)
remain FREE on main — reuse them when the build resumes.** Resume plan = re-brief these 10 (the partial worktrees can
be discarded or salvaged) after Mon 2026-07-13.
**UPDATE 2026-07-13 (owner-directed one-at-a-time resume, running on Opus 4.8 main loop): item 1 of the abandoned wave —
P1-3 exit-reason attribution — REBUILT on a fresh branch + SHIPPED #799 @ d1cf7b71 (see the D4 row). Turned out to need
NO migration (exit_reason column pre-existed), so provisional backtest V018 is STILL FREE — the salvaged worktree's design
was sound (side-channel exitType), re-derived + 4-reviewer-verified. 9 wave-2 items remain.** Held for a later wave (NOT yet attempted): D3 unified job envelope +
jobs console, Map-return burn-down, event registry, multi-window panes, user_prefs+cmdk; D4 dividends P2-6 /
margin-feasibility P2-7 / open-next-day #15 / confirm-rerun FE #26 / order-timeline #28 / headless A8-A10 #29.
Live behavioural notes a new session must know: B1 makes Monday 2026-07-13 a CALIBRATION session
(entries UP + wider stops expected — judge on `ay_signal_partial_bucket_mismatch_total`, not entry
count; E8 re-tune consumes the clean sessions); A3 changed exit semantics (settle = last REAL tick
at any age, never fabricated breakeven; fills reject ticks >15s); first canary sweeps + reconciler
run Monday 08:30/08:45/21:15 IST.

**A. Platform foundations (APP §10 Phase 1 + its §6/§8 orphans — prerequisite for INT-I1; A1/A3 also gate INT-I3)**

| # | id | what (1 line) | source | tier | status |
|---|---|---|---|---|---|
| A1 | `p1-v1-manual-order-governor` | Call `RiskService.entryAllowed` inside `PaperService.openOrder` — manual tickets currently bypass kill-switch/max-open/daily-loss/heat | APP §8 V1 | HOLD | **DONE — PR #687 @ a3210700, MERGED+DEPLOYED LIVE 2026-07-10 (owner pre-authorized autonomous run)**: openManualOrder gate via RiskService.entryVeto (emission path byte-identical, 2-lens review clean); residual = stale-signal manual take still ungated (chip task_94f40cf6) |
| A2 | `p1-v2-v4-order-validation` | `clientOrderId` idempotency on `POST /paper/orders` + lot-size-multiple validation | APP §8 V2/V4 | clean | **DONE — PR #690 @ b6f9f584, MERGED+DEPLOYED LIVE 2026-07-11** (V028 partial-unique index verified live; replay = 200+original DTO — D8-envelope override of the audit's 409 sketch; lot-check fail-soft on meta outage) |
| A3 | `p1-v3-tick-freshness` | Max-age guard on fills/MTM, bracket-starvation counter + ntfy, forbid silent breakeven settle | APP §8 V3 | HOLD | **DONE — PR #694 @ 2d8c2d2f, MERGED+DEPLOYED LIVE 2026-07-11 (owner pre-authorized)**: 15s fill max-age; breakeven fabrication killed — settles fall to last REAL tick any age (visible via ay_paper_stale_settle_total, daily post-close baseline expected; alert on the REFUSE counter); review round caught+fixed 3 stranding paths; knobs .env-tunable (compose passthroughs) |
| A4 | `p1-ingest-runs` | `marketdata.ingest_runs` ledger + writers (NseEod ×3, bhavcopy, screeners, capture summary, instrument sync) — the batch-source trust oracle | APP §7.2.3/§9.1 | clean | **DONE — PR #686 @ e112ca9e, MERGED+DEPLOYED LIVE 2026-07-10** (V040; 8/8 writers; timescale review PASS incl. empirical ON-CONFLICT check; live-verified — boot NseEod×3 SUCCESS rows + bhavcopy RUNNING within seconds); A5/A11 notes: aged RUNNING=crashed, NseEod 2 rows/day, screener SUCCESS/0=yellow |
| A5 | `p1-ingest-canaries` | T+1 expected-source×trading-day canary (V9) + notifier delivery-health check (V15) | APP §8 V9/V15 | clean | **DONE — PR #689 @ 59a65d68, MERGED+DEPLOYED LIVE 2026-07-11** (V9 IngestCoverageCanary 08:45 IST live-only + V15 NotifierHealthCheck 08:30 IST dual-channel; policy calls flagged in PR: FII-derivative false-REDs if analytics disabled, capture 0-rows=RED; first live sweeps = Mon 07-13 morning) |
| A6 | `p1-fii-diagnostic` | FII-derivative empty-state explains the analytics flag (dead-by-default today) | APP §2.5 | clean | **DONE — PR #696 @ b27e1cd5, MERGED+DEPLOYED LIVE 2026-07-11** (EmptyCard idiom, ui-a11y review clean) |
| A7 | `p1-backfill-identity` | Persist backfill jobId UUID into `backfill_jobs` + OI-backfill audit rows (V12) | APP §8 V12 | clean | **DONE — PR #692 @ 3dd78257, MERGED+DEPLOYED LIVE 2026-07-11** (V041 job_id column verified live; OI backfill now writes fail-soft kind=OI ledger rows; read endpoint unchanged) |
| A8 | `p1-jobs-error-display` | Jobs page renders `jobs.error` for failed runs + a failed-job detail fetch (BE already returns it) | APP §2.7 | clean | **DONE — PR #696 @ b27e1cd5, MERGED+DEPLOYED LIVE 2026-07-11** (failed badge → accessible error dialog, lazy detail fetch; 3 a11y review fixes landed: focus-return, focusable scroll region, mobile width cap) |
| A9 | `p1-admin-audit` | Append-only audit for query-console SQL + exports + paper-reset/capital-change rows; export truncation made explicit (V14) | APP §8 V14, §7.2.5 | clean | **DONE — PR #698 @ 9c2aa6fc, MERGED+DEPLOYED LIVE 2026-07-11** (V042 marketdata.admin_audit + V029 strategy.paper_admin_audit, both verified live; truncation headers on all 3 export paths; FE banner chipped task_f12c165f) |
| A10 | `p1-snapshot-retention` | Schedule `prune_options_snapshots` (horizon = owner pick) | APP §3.3 | owner | **DONE — #749 @ f1293f62, MERGED+DEPLOYED 2026-07-12 (owner-decided 365d)**: 03:30 IST drop_chunks job, ingest_runs-recorded, no-op until ~2027-06; found+chipped the never-worked V010 manual wrapper (task_b57c7fbe); OWNER ACK WANTED: 365d overrides the old V006/V010 "A2 ≥5-year floor" comment |
| A11 | `p1-ingest-health-board` | EOD ingest health board page over `ingest_runs` (per-source last-run/rows/missing-days — the screen A4/A5 feed; in no APP phase list) | APP §6.3/§9.1 | clean | **DONE — PR #699 @ 4bbf207a, MERGED+DEPLOYED LIVE 2026-07-11** (/data-ops/ingest-health + GET /api/v1/market/health/ingest; policy delegates to A5 canary — single source; a11y MAJOR fixed pre-merge) |
| A12 | `p1-v5-v16-reconcilers` | Nightly position↔order-leg (V5) + TAKEN-signal↔position (V16) reconciliation jobs → health board | APP §8 V5/V16 | clean | **DONE — PR #701 @ 49547407, MERGED+DEPLOYED LIVE 2026-07-11** (21:15 IST job; V030 paper_reconciliation_runs verified live — first-deploy stale-checkout caught by the DB probe and redone; results in-schema per D10, not the marketdata board; benign exclusions IT-proven) |
| A13 | `eval-stall-canary-fix` | ⚠ URGENT (with B1) — 3rd silent eval stall 2026-07-10 14:52→close (DB-proven: last of 701 `signal_rejections` rows 14:52:22 IST, zero eval output to the 15:57 recreate). **DIAGNOSED 2026-07-10 (Opus-investigated, Fable-audited):** canary WAS active (gate `artha.signals.engine-enabled` matchIfMissing=true; thresholds 180s/90s default-ON; sweep 09:20–15:30 → the "last-40-min window miss" hypothesis is REFUTED — 14:52 left ~34 in-session min). "Logged nothing all day" was an ARTIFACT: the 15:57 composite-055 container RE-create wiped the stall-window docker logs (Created≈StartedAt 10:27Z, RestartCount=0, old container gone). Root-cause FORK: **RC-1** heartbeat `lastBarReceivedAtMs` stamped on the RECEIVE thread (SignalEngine:452) — an eval-executor stall keeps it fresh → canary silent, and `forceResubscribe` routes through the same evalExecutor (:440) → recovery queues behind the block; **RC-2** detection fired but re-subscribe (same connectionFactory; Redis `client-output-buffer-limit pubsub 16mb 8mb 60` = likely drop trigger) failed every 3-min retry to close. **Discriminator = owner's ntfy history (~14:55 "signal engine STARVED" page ⇒ RC-2, none ⇒ RC-1).** Fix set (covers both): (1) second heartbeat `lastBarEvaluatedAtMs` at end of drain(), alarm on EITHER stale; (2) durable canary telemetry (DB row/counter — recreate-proof); (3) recovery off the eval thread WITHOUT re-holding the SignalEngine monitor across Redis I/O (#634 routed via eval deliberately); (4) unify `artha.signal.*`/`artha.signals.*` prefix split. Ops rule: snapshot `docker logs` BEFORE any post-incident recreate | rollup watchlist; 07-10 findings §6; §1 `signal-eval-redis-subscriber-watchdog`; live-mode-findings memory | HOLD | DIAGNOSED — **RC-1 CONFIRMED 2026-07-10 (owner ntfy history: NO "STARVED" page all day → eval-side stall, canary structurally blind; the 15:06 message was the FINNIFTY DataHealthCanary noise)**. **DONE #679 (2026-07-10, owner-approved "build both") — SHIPPED+DEPLOYED LIVE @ 1144b2b1:** eval-vs-RECEIPT heartbeat (`lastBarEvaluatedAtMs`, quiet-market-safe: compares received−evaluated, never wall-clock) + distinct eval-stall page + automatic `signal-eval` stack dump + recreate-proof `strategy.subscriber_health_events` telemetry (V027) + recovery re-threaded to a dedicated `subscriber-recovery` executor (lock analysis in `forceResubscribe` javadoc: both #634 invariants preserved) + prefix unified to `artha.signals.subscriber-watchdog.*`. Paper IT + ModularityTest green (the #634 CI-trap avoided); Opus-built, Fable-audited (audit: no fixes needed). Verify forward: next stall must page + leave telemetry rows |
| A14 | `ca-rebuild-hardening` | ⚠ URGENT (before Mon 07-13 16:30 IST sweep) — `CorporateActionJob` remediation is chronically broken AND crashes the live DB: 2026-07-10 sweep OOM-killed Postgres **3×** (17:07/17:20/17:34 IST, signal-9 + crash-recovery each) via the 12-yr single-CALL `refresh_continuous_aggregate('candles_5m', 2014→2026)` (`CandleRepository.refreshDerivedAggregates`, `rebackfill-days-1m:4400` — violates the known never-wide-refresh rule); INFY's `purgeSymbol` DELETE aborts daily on the compressed-hypertable decompression limit (1.06M > 100k). History: 247 DETECTED / 138 FAILED / 25 RESOLVED (all 25 on 06-23) / 10 stuck REBACKFILL_RUNNING — failing every attempt since 06-30. 07-10 damage (Opus-investigated, Fable-audited): DLINKINDIA/GOLDIAM/VSTIND base 1m+1d fully re-fetched ADJUSTED (label FAILED misleading; won't re-detect) but their 5m/15m/1h/1d/1w cagg HISTORY is empty (policies cover ≤60d — never self-heals; charts-only impact; swing batch reads native 1d so UNAFFECTED — verified `CandleQueryService:103` + both `refreshDerivedAggregates` call sites 1m-gated); INFY still UNADJUSTED, re-fails fast daily (no OOM). NO kill switch (no enabled-flag; `artha.corporate-actions.symbols` not compose-wired). Fix set: (1) chunked cagg refresh (~3-month windows); (2) compressed-safe windowed purge OR in-place ratio adjust; (3) split BASE_REBUILT/CAGGS_REFRESHED statuses + idempotent resume + applied-CA keying; (4) `artha.corporate-actions.enabled` kill switch + compose passthrough; cleanup: manual chunked cagg refresh for D/G/V + clear 10 stuck rows + INFY adjust. Systemic re-crash risk: any NEW large-history CA on any future sweep | 07-10 incident (this row is the record); live-mode-findings memory | clean | **DONE #680 (2026-07-10, owner-approved "build both") — SHIPPED+DEPLOYED LIVE @ 71b13bdd:** chunked cagg refresh (≤92d windows OVERLAPPING 8d — refresh excludes buckets not fully inside the window, so contiguous cuts would drop straddlers; > the 7d 1w bucket) + 6-month windowed purge (~47k rows/DML ≪ 100k limit) + `BASE_REBUILT` checkpoint/resume (V039 widens the status CHECK; refresh-only resume via sweep checkpoint scan) + `artha.corporate-actions.enabled` kill switch (compose-wired). Cleanup DONE on live: DLINKINDIA's event flipped → BASE_REBUILT (Monday's resume refreshes caggs TABLE-WIDE, healing GOLDIAM/VSTIND history in the same pass — 1 resume not 3), 10 stuck REBACKFILL_RUNNING → FAILED; INFY re-detects Monday through the windowed purge. **Verify Monday post-market: 3 CA ntfy "rebuilt" messages expected, 0 DB crashes; first live run of the chunked path** |

**B. Research-fidelity blockers. B1/B2 + B6–B10 = the seven EVO-E0 HARD gates; B3–B5 = FID P0s that are EVO-DEGRADED (BTST just runs SIM_BLOCKED, screener-CA defers funnel-knob tuning, lineage degrades portfolio scoring); B11–B14 + B18 = HOLD-tier sim-honesty (they change the numbers EVO ranks on); B15–B17 = optimizer-lane extras.**

| # | id | what (1 line) | source | tier | status |
|---|---|---|---|---|---|
| B1 | `fid-p0-1-partial-buckets` | ⚠ RUN FIRST — live 3m/5m/15m/1h series evaluate FROZEN 1-min partial buckets; completed-bucket read contract (chip task_8972447b; thresholds tuned on poisoned bars re-tune after) | FID §3.1/§10 P0-1 | HOLD | **DONE — PR #683 @ 37a25fd4, MERGED+DEPLOYED LIVE 2026-07-10 post-close (owner-approved)**: LiveSeriesStore completeness filter + `entryAnchorIndex` exit-anchor fix + PartialBucketCanary (metric live at 0.0, 39 strategies loaded); 4-lens review (1 HIGH fixed, 1h post-close drop accepted+test-pinned); Mon 07-13 16:05 verify scheduled; expect entries UP + wider stops Monday — E8 re-tune on the clean sessions |
| B2 | `fid-p0-2-engine-sha` | Stamp engine git SHA onto backtest run rows (comparability gate) | FID §10 P0-2 | clean | **DONE — PR #703 @ 2de5682f, MERGED+DEPLOYED LIVE 2026-07-11** (V008 engine_sha+engine_image on jobs+backtest_runs, column verified live; results API + FE panel surface it; plugins pre-existed #617; post-deploy check = next run row carries the SHA) |
| B3 | `fid-p0-3-swing-lineage` | Swing deep-sim lineage — job-pipeline route or full run-row lineage (report JSONB only today; FID's "biggest item") | FID §10 P0-3, §12 #16 | HOLD | **DONE — #764 @ c7a52842, MERGED+DEPLOYED+LIVE-VERIFIED 2026-07-12** (V014 DEEP_SWING kind; run b928d38f: engine_sha stamped, 13,181 lineage trades w/ 4 real exit reasons, report intact; per-share pnl basis caveat on rows; builder-run domain review 1 MED+6 LOW all fixed) |
| B4 | `fid-p0-4-screener-ca` | Live screener CA-adjusted prices (= prior-audit H6; backtest adjusted vs live raw; fix vehicle = §9 candidate 02 adjusted-price-plane) | FID §10 P0-4; 07-05 H6 | HOLD | **DONE — #757 @ 2dcc6f16, MERGED+DEPLOYED 2026-07-12** (gap proven REAL — #680 skips bhavcopy-only names; shared AdjustedEquityDailySql plane, equality IT decimal-exact; owner watch: first post-deploy screen membership deltas near recent-CA names; runtime watch on the nightly screen) |
| B5 | `fid-p0-5-btst-exits` | BTST exits never simulated (backtest branch never opens a position) — EVO runs BTST SIM_BLOCKED until fixed | FID §10 P0-5 | HOLD | **DONE — #759 @ 57fc36e8, MERGED 2026-07-12** (btst positions now open + exit at subsequent pre-closes; btst golden regenerated with mechanism; DOCTRINE RECORDED: close→close fills (gap-at-open needs per-leg fill timing — follow-up), sim-ahead-of-live (live exit sweep = chip task_3e95fade); EVO design §1.2 BTST → LIVE_FIRST-with-sim-smoke) |
| B6 | `fid-p2-1-optimizer-durability` | Durable sweep queue (in-process threads die on restart; orphans failed-with-NULL-error) | FID §10 P2-1 | clean | **DONE — PR #708 @ b0898fdf, MERGED+DEPLOYED LIVE 2026-07-11** (fail-loudly branch: reaper stamps a real interruption reason; GET /optimizations/jobs native listing SMOKE-TESTED live; true Optuna resume deliberately out of P2 scope; FE sweep-list page = separate item) |
| B7 | `fid-t3-actor-plumb` | `created_by` actor plumbed through job/run/version writes | FID §5 T3; EVO §13 row 4 | clean | **DONE — PR #710 @ 88547046, MERGED+DEPLOYED LIVE 2026-07-11** (backtest V009 column verified live; vocabulary owner / optimizer / optimizer:{sweepId}, scheduler:*+evo:* reserved; optimizer stops jamming provenance into notes; builder self-caught a tx self-invocation bug) |
| B8 | `fid-experiment-views` | Experiment browser views + server-side compare endpoint | FID §11.4/§13 #8/#11 | clean | **DONE — PR #714 @ fd016dda, MERGED+DEPLOYED LIVE 2026-07-11** (V010 experiment_runs view verified live; GET /backtests/experiments + /compare with LikeForLike incl. engineSha/dataHash/universe; costModelMatch honestly deferred to B18/#22a; strategy-schema views = item #19 boundary; FE compare-page switch = #23) |
| B9 | `evo-funnel-universe-resolver` | Resolve `universe.mode: *_funnel` in the job pipeline (Manas/Minervini trials 0-runnable today; `futures_screener` pinning precedent) | EVO §13 row 19 | clean | **DONE — PR #706 @ 347b4d69, MERGED+DEPLOYED LIVE 2026-07-11** (pin-at-submission, top-RS pick verified via ManasFunnelService ORDER BY rs_rank; static-universe divergence documented — per-name expectancy only, EVO item-8 DEGRADED; goldens+parity byte-identical) |
| B10 | `evo-regime-label-fix` | Optimizer/FE use BULL/RANGE/BEAR/CRASH vs canonical UP_QUIET/… → `regimesCovered` always empty (chip task_d6872aa3) | EVO §13 row 20 | clean | **DONE — PR #705 @ 5d72b2c1, MERGED+DEPLOYED LIVE 2026-07-11** (2 read-side constants; RETROACTIVE — labels never persisted wrong, historical sweeps populate immediately; ci-optimizer + trio green; cosmetic pretty-name map = optional follow-up) |
| B11 | `fid-p1-9-context-lookahead` | Gate context advancement on bucket END ≤ bar time — 1d-context strategies see today's close intraday; SIM_FIRST sims inherit the artifact | FID §10 P1-9, §12 #14 | HOLD | **DONE (already-gated, hardened) — #755 @ cb345547, MERGED 2026-07-12**: the audited lookahead was NOT reachable (IndicatorBank.mappedIndex end-gates every read — proven empirically); append-site hardened byte-identically as defense-in-depth; live-side glance chipped task_4f575c72 |
| B12 | `fid-p1-10-intrabar-touch` | Wire `IntrabarExitResolver` as opt-in `touch_basis: bar_hl_worstof` + per-run basis provenance | FID §10 P1-10 | HOLD | **DONE — #762 @ b34e9b45, MERGED+DEPLOYED 2026-07-12** (opt-in `session.touch_basis: bar_hl_worstof`, default byte-identical; conservative-fill demonstrated ≤stop; trailing stays close-basis per spec — follow-up noted) |
| B13 | `fid-p1-11-expiry-settlement` | Settle backtest legs held past expiry at intrinsic + exercise STT (`exit_reason='expiry_settlement'`) | FID §10 P1-11 | HOLD | **DONE — #753 @ 569022a9, MERGED+DEPLOYED 2026-07-12** (intrinsic + exercise STT ≡ paper path, EXPIRY_SETTLEMENT reason; guard keys on CONTRACT expiry never bars-running-out; domain review PASS) |
| B14 | `fid-d7-native-daily-context` | Backtest 1d context/benchmark reads → native daily (goldens/parity rerun) | FID §12 #8 (D7) | HOLD | **DONE — #754 @ a1b7acf4, MERGED+DEPLOYED 2026-07-12** (1d context + benchmark → native daily; divergent-data IT proves the swap load-bearing; 1w stays cagg — no native source; counterfactual nondeterminism chipped task_132a71ba) |
| B15 | `evo-param-path-grammar` | 3-point path-grammar extension (gate constants, `risk.max_positions`, funnel props) — widens the tunable space | EVO §13 row 21 | clean | **DONE (2 of 3 families) — PR #716 @ ee6aae85, MERGED+DEPLOYED LIVE 2026-07-11** (gate constants + position caps across all 5 sync points, goldens/parity byte-identical; funnel props REFUSED — no config leaf exists, service-global knobs; chipped task_2560273c per the designs own "funnel knobs wait") |
| B16 | `backtest-worker-pool-cap` | Queue cap/priority on the shared worker pool (campaign nights would starve interactive runs) | EVO §13 row 22; FID §8 A6 | clean | **DONE — PR #717 @ 08ddb539, MERGED+DEPLOYED LIVE 2026-07-11** (CAS-gated trial budget, ≥1 interactive slot guaranteed; 429 RATE_LIMIT_QUEUE at 500 queued BACKTESTs per-kind; knobs .env-tunable with compose passthroughs; defaults = current behavior) |
| B17 | `arch-03-trial-metrics-catalog` | Trial-metrics catalog (§9 candidate 03 — parity-neutral, cheap, "the clean next") | §9 candidate 03 | clean | **DONE — PR #712 @ 8e5793bc, MERGED+DEPLOYED LIVE 2026-07-11** (contracts/metrics/trial-metrics-catalog.json = one 20-metric contract; optimizer derives its allow-list from it, Java consistency test pins emit↔catalog; in-container smoke: 20 metrics load) |
| B18 | `fid-p1-2-costs-knob` | Wire the dead `costs` knob + instrument-class costing (candle path always EQUITY-class today — changes every net number) | FID §10 P1-2, §12 #11; EVO §13 row 10 | HOLD | **DONE — #756 @ 753ed166, MERGED+DEPLOYED 2026-07-12** (per-class costing + validated costs knob; review round fixed 3 MED incl. silently-zeroed slippage that would have neutered cost-stress; futures_of_underlying stays EQUITY by decision — prices what it fills) |

**C. Design-program builds (owner sequences which goes first; EVO needs its seven B-gates, INT needs A4 and is sequenced after APP Phase 1; later increments E2–E6 / I2–I4 live in each design's §12 — C1/C2 are the first increments only)**

| # | id | what (1 line) | source | tier | status |
|---|---|---|---|---|---|
| C1 | `evo-e1-experiment-model` | EVO first increment: evo_* tables + retro-scoring of existing sweeps (needs B1/B2 + B6–B10) | EVO §12 E1 | clean | **DONE — 4 PRs MERGED+DEPLOYED LIVE 2026-07-11: #720 @ 8c398ef3 (V011 evo_* tables + read APIs + gateway allowlist), #721 @ f81d744e (recoveryFactor/tradeFrequency/turnover metrics, goldens/parity byte-identical; 3m tradeFrequency absolute-value caveat chipped task_547656bf), #722 @ f4154722 (§6 scoring lib + GET /evolution/retro-score — 2-lens review caught 1 HIGH plateau division-vs-multiplication inversion + 2 MED, all fixed), #723 @ ece5dd84 (campaign/generation recorder, SIM_BLOCKED refusal, SWEEP_NOT_COMPLETED gate).** Live-verified end-to-end: V011 DB-probed; historical scalper sweep retro-scored (6 cards, sensible ranking, honest degradations — regime "1 of 4 covered" proves the B10 retroactive fix working); first real campaign+generation recorded (LIVE_FIRST caveat stamped in DB). E2 next increment = §12 items 4–6 |
| C2 | `int-i1-foundations` | INT first increment: insights module + day-context/options digests + `market_context_days` + feed/Focus in shadow mode + notification_events migration + the `/api/v1/insights/**` gateway-allowlist entry & spec recapture in the same PR (hard-needs A4; INT §12 sequences I1 after all of APP Phase 1) | INT §12 I1; §13 rows 18/20 | clean | **DONE — #742 @ 139c1386 (insights module, V032/V033, 9 typed endpoints, golden test, timescale review PASS) + #745 @ af5be41f (digests + V043 + EOD ledger job; review HIGH stale-anchor fabrication FIXED pre-merge), MERGED+DEPLOYED LIVE 2026-07-12**; FE feed/Focus half building (wave 2); forward notes for I2/I3 briefs: upsert-refresh needs RETURNING id before delivery lands, prune must exclude FK'd rows, MARKET_CONTEXT_DAY canary registration after first live run |
| C3 | `int-manas-rsrank-api` | Serialize `rs_rank` in the Manas screen API row (DB-only today; gates INT context component) | INT §13 row 12 | clean | **DONE — #736 @ 4da0719e, MERGED+DEPLOYED LIVE 2026-07-12** (screen + funnel rows, nullable-honest; known latent springdoc name collision flagged, pre-existing) |
| C4 | `int-fired-rail-sidechannel` | Fired-side per-rail operand side-channel (parity-safe additive; gates fired-vs-rejected Stage 2; when built, also add FID P1-8's full diagnostic context block) | INT §13 row 19; FID §10 P1-8 | HOLD | **DONE — #763 @ c06e6cd5, MERGED+DEPLOYED 2026-07-12** (V035 fired_diagnostic side-channel, shape-mirrors rejections field-for-field; goldens/parity byte-identical; emission blast-radius review PASS; live-verify = first scalper fire Monday populates the column; fired-vs-rejected Stage 2 now data-gated only) |
| C5 | `evo-e5` | EVO §12 E5 (next increment; read the design §12 for scope) | EVO §12 | clean | **DONE — PR #792 @ dc69c439, MERGED+DEPLOYED LIVE 2026-07-12** (ablation protocol paired-on-identical-folds + IS-only auto-reject + graveyard w/ NULLS NOT DISTINCT + 3 suggesters incl. regime-gate EMIT-ONLY per the frozen-regimes decision; 2-lens review → fail-closed ACCEPT via REJECTED_INCOMPLETE_EVIDENCE + bury-before-verdict crash-heal; V016; live-smoked: graveyard/ablations honest-empty on campaign a2155cfc) |
| C6 | `evo-e6-autonomy` | EVO §12 E6 (autonomy + FE; owner-gated arming) | EVO §12 | mixed | **BUILT — backend #793 @ 173b93c1 + FE #787 @ 5c5ab77b, MERGED+DEPLOYED LIVE 2026-07-12** (scheduler DEFAULT-OFF + per-campaign enroll consent — flipping the global flag alone advances NOTHING; tick-race fixed 3-layer incl. PENDING partial-unique; full §1.3 probes→stress orchestration; holdout stays owner-triggered and BLOCKS PUBLISH_PAPER mint until consumed; ARTHA_EVO_MAX_CONCURRENT_SWEEPS=1; /evolution FE board/workspace/inbox a11y-fixed; dormant-verified live. **ARMING = owner rows**: flip ARTHA_EVO_SCHEDULER_ENABLED, enroll campaigns w/ populated searchSpace{from,to}, off-hours window policy) |
| C7 | `int-i3-fe` | INT I3 FE half — compare/dossier/act views over #778's endpoints | INT §12 I3 | clean | **DONE — PR #789 @ 6c678c07, MERGED+DEPLOYED LIVE 2026-07-12** (compare 2–6 signals URL-addressable + dossier from GraduationPage/cards + PROPOSE act wiring trust-gated w/ aria-describedby reasons + journal draft-accept; INT I1+I2+I3 now FULLY complete — only I4 arming remains) |
| C8 | `int-i4-arming` | INT I4 — delivery arming + calibration | INT §12 I4 | owner | OWNER |

**D. Later-phase summary rows (full item lists live in the source §; expand into rows when a phase starts)**

| # | id | what (1 line) | source | tier | status |
|---|---|---|---|---|---|
| D1 | `p1-phase2-workflow-chains` | Signal-status push, `paper.events`, position-detail+PATCH brackets, ticket book selector + qty override, trade-chain view, journal links/edit, `sell_decisions` persistence, strategy enabled-toggle + audit-log reader + clone action, promotions display, dot-health panel | APP §10 Phase 2 + §6.5/§6.12 | mixed | **DONE — APP PHASE 2 COMPLETE, 5 PRs MERGED+DEPLOYED 2026-07-12 (round 4 @ 7fb6c1f5, V036/V037/V038 probed)**: slice A #773 (signals.status frames AFTER_COMMIT + V038 paper_events channel/table/endpoint — the INT-I3 unblockers), slice B #771 (position detail + PATCH brackets HOLD w/ discriminating IT + trade-chain drawer), slice C #772 (ticket book/qty selectors + V037 sell_decisions persistence — INT §13 row 8 satisfied; rows = still-open holdings post-batch), slice D #769 (enabled-toggle w/ #579 both-direction regression proof + audit timeline + clone + promotions, V036), slice E #770 (journal link pickers/edit + dot-health panel; a11y PASS). Chips: journal PUT rating validation task_fab9e823 |
| D2 | `p1-phase3-freshness-depth` | Freshness envelope + badge rollout, page auto-poll policy, chain time-travel, IV smile + surface (smile×expiries) + `/premium-series` decay, strike drill-down, screener date/diff/attrition + V10 engine-version stamps, per-underlying DataHealth, breadth series, Minervini backtest page, backtest-vs-paper parity view, CSV export standard, risk-calc margin tie-in, V6/V7/V8 data checks | APP §10 Phase 3 + §6.9/§6.16 + §8 V10/§9.1 | clean | **SUBSTANTIALLY DONE — 3 PRs MERGED+DEPLOYED 2026-07-12 (round 5 @ 6f1556d8)**: A #777 (DataFreshness envelope, 14 records + badge on 11 pages), B #776 (chain time-travel + IV smile + premium decay + strike drill-down), C #780 (screener dates/diff/attrition + V044 breadth materialization + CSV standard + SPAN tie-in + V6 quarantine V045 / V7 / V8). Residue: auto-poll policy (owner picks), remaining CSV FE buttons, V10 stamps, per-underlying DataHealth |
| D3 | `p1-phase4-platform-planes` | Unified job envelope + jobs console, reference-data tables + admin CRUD (constituent SOURCE = owner pick), user_prefs + saved views + cmdk palette, DataTable adoption wave, Map-return burn-down, event registry, alert rules, multi-window panes | APP §10 Phase 4 | clean | TODO |
| D4 | `fid-phase2-4-remainder` | FID roadmap remainder: dataset comparability — content hash + `dataset_epochs` + re-run policy (P1-1/#30), exit-reason attribution on the candle path (P1-3), paper order events + quote capture (P1-4/5), flag-snapshot ledger (P1-7), `book` persisted on signals (T1), run tags/notes (P2-2), per-run export (P2-3), data-quality report (P2-4), latency stamps (P2-5), dividends (P2-6), margin feasibility (P2-7), backtest decision traces (P2-8), provenance block on jobs/runs (#22a), evidencePolicy tag + StressGuard-aware windows (#22b), open-next-day variant (#15), confirm-before-run + rerun/clone FE (#26), order-timeline/mobile (#28), headless hardening A8/A9/A10 (#29). FID #27 promotion-workflow = superseded by EVO §8 | FID §10 P1/P2 + §12 Ph2–4 | mixed | **PARTIAL — 2 slices MERGED+DEPLOYED 2026-07-12 (wave 8)**: comparability slice #791 (P1-1/#30 content hash + dataset_epochs + re-run staleness + #22a provenance block + #22b evidencePolicy, V015 — NULL=not-comparable, premiumContentUnverified caveat on options runs; **epoch WRITERS not yet wired** — market-data CorporateActionJob/re-fetch call sites are the follow-up, head stays 0 until then) + paper-forensics slice #788 (P1-4 reject-gap + P1-5 quote provenance + P1-7 flag snapshots + T1 signals.book frozen-at-emission w/ trigger+backfill, V039/V040). **P1-3 exit-reason attribution DONE #799 @ d1cf7b71, MERGED+DEPLOYED+LIVE-VERIFIED 2026-07-13** (candle-path `SignalEvent.exitType` frozen-writer side-channel → runner stamps it at the 3 exit-emission points → `ReplayEngine.mapExitReason` persists the real {stop_loss,trailing_stop,take_profit,square_off,time_stop,signal_exit}; scaled_exit→take_profit, open-at-end keeps end_of_data; **NO migration** — `backtest_trades.exit_reason` already existed, so the reserved backtest **V018 stays FREE**; goldens byte-identical/BacktestParityTest 9/9/328 ITs green; 4-reviewer adversarial pass 0 defects; casing chip task_cf5d58c8; deploy live-verified — jar contains mapExitReason, container healthy). **P2-8 decision traces DONE [#807](https://github.com/prashantm912/artha-yantra-2/pull/807) @ fc7dec39, MERGED+DEPLOYED 2026-07-13 — FIRST CODEX BUILDER-LANE HANDOFF** (opt-in `traceDecisions` flag → nullable `DecisionListener` side-channel on TickwiseGoldenRunner → per-day `backtest_decision_days` V019 → typed `GET /decision-traces`; goldens/parity byte-identical, Architect-reran + 1 adversarial reviewer 0 defects; options-premium path chipped task_248c8c36). **P2-3 export BACKEND DONE [#810](https://github.com/prashantm912/artha-yantra-2/pull/810) @ 0de5e709, MERGED+DEPLOYED 2026-07-13 (first fully-autonomous Codex `codex exec` build — Architect-audited+salvaged)** (per-run CSV/JSON export for trades/folds/equity/compare; `ExportController` + `CsvEncoder` RFC-4180; no migration; cap 1000 + X-Result-Truncated. Side-effect accepted+flagged: shared `findByRun`/`RunRepository` reads now normalize entryTs/exitTs to IST +05:30 — a latent IST/UTC-trap fix on `/trades`//`/folds`//`/summary`, contract-safe. FE download buttons = follow-up chip task_16f16578). **P2-3 export FE DONE [#814](https://github.com/prashantm912/artha-yantra-2/pull/814) @ 93997ae2, MERGED+DEPLOYED 2026-07-13 (Codex UNSANDBOXED-worktree build — Architect-audited)** (CSV/JSON download buttons on `BacktestResultsPage` for trades/folds/equity via new `frontend-react/src/api/backtestExport.ts` → existing `downloadFile` helper w/ truncation toast; a11y accessible-name buttons; spec 7/7; compare-export SCOPED OUT — `strategyVersionIds` not cleanly in page state, re-chip if wanted; closed chip task_16f16578; e2e = known signals/ws flake pair only, backtest-results.spec green). REMAINDER TODO: P2-2 (tags/saved-views), P2-4..P2-7 (DQ/latency/dividends/margin-feasibility), #15/#26/#28/#29 |

**E. Standing owner/data gates (detail in the source column)**

| # | id | what (1 line) | source | status |
|---|---|---|---|---|
| E1 | `forward-paper-reliability-month` | ~1 month live-paper accrual → runbook analysis (E9 band + per-scalper keep/cut/tune) + swing §0.5 #12 sign-off | §2 `live-forward-paper-analysis`; runbook | OWNER |
| E2 | `always-on-host` | F5 hardware decision (brief: [`2026-07-03-always-on-host-brief.md`](2026-07-03-always-on-host-brief.md)) | 10x F5 | OWNER |
| E3 | `flag-arms` | Arm when ready: ~~F9 heat-cap~~ ~~F7 graduation marker~~ ~~per-strategy notification opt-ins~~ **ARMED LIVE 2026-07-12 (owner-approved): `ARTHA_PAPER_RISK_ENABLED=true` (60% scalper heat-cap ENFORCING; blind-gate visibility added #739 — watch `risk_audit` UNPRICED rows), `ARTHA_GRADUATION_PROMOTION_ENABLED=true` (measurement marker; dormant until 50 closed trades), notifications_enabled=true for all 45 published strategies (expect ntfy volume UP Monday — owner can re-mute per strategy)**. Still owner: heartbeat URL (needs an owner-created healthchecks.io check), Dow `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED`, `source.optionanalytics` PCR flip (freshness check first, §6), Minervini/Manas low-cap gate (needs a compose passthrough), `ARTHA_NOTIFIER_APP_BASE_URL` (scalp deep link, #743) | §1 rows + §8(3); §2/§6 | OWNER (3 armed) |
| E4 | `audit-doctrine-holds` | 07-05 audit H8 + swing exit-parity HOLD batch #128 + setup-doctrine owner-calls M36/M37/M38/M40 + VcpDetector base-week mismeasure (latent — bites if a week-floor re-arms; m39 doc option 2). #591 is CLOSED — superseded by MERGED #607 (H6 = B4) | 07-05 audit §12; §8a; m39 doc §4 | OWNER |
| E5 | `cd2-calendar-refresh` | 2027 NSE/BSE calendar CSV refresh before ~2026-11-16 (horizon canary reds ~45d prior) | §4 scheduled | OWNER |
| E6 | `evo-owner-predecisions` | ~~Stage-D regime-gate amendment + author the Manas optimize block~~ **BOTH DECIDED 2026-07-12 (owner, live Q&A):** (a) regimes STAY FROZEN "reported, not optimized" — **DO NOT start a regime-gate tuning stream before 2026-08-14** (owner-pinned revisit date = ~1 month of clean forward-paper sessions post-B1-calibration; re-decide then WITH per-regime live evidence — this date exists so it cannot start accidentally); (b) Manas `backtest.optimize` block AUTHORED + PUBLISHED (both strategies v1.1.0 live 2026-07-12, engine reloaded: 6 grammar-legal params — stop value/cap, trail value/arm, gate vol floor, max_positions — ranges bracket the #556-#563 proven config; owner reviews ranges in the morning note; smoke sweep validated end-to-end, weekend funnel-pin edge chipped task_03b9f52d) | EVO §5.1.2/§9; §1 row | DONE (E6a decided, E6b live) |
| E7 | `f2-proposals-pass` | ~~run F2 at ≥5 sessions~~ **PASS DONE 2026-07-10 (#673 + this session's verification):** rollup §Proposals UNLOCKED with **P1 iv_pair 0.10→0.02 / P2 oi_spurt price-floor 50→8 / P3 iv_rank null→excluded** — each with diff+evidence+risk. Verification added: P1/P2 knobs are YAML-ONLY today (`application.yml:73/76`, no `${ENV}` placeholder, no compose passthrough — landing needs placeholder+passthrough per the #653 trap); P2 ground-truthed against 2,104 observed bars (abs p90=10.0, ≥8 on 17% → 8 = selective-but-alive); P3's null→supports=false confirmed in `ConnectTheDotsScorer` (by-design comment). **Remaining = OWNER approval of P1–P3** (each its own PR, no auto-apply) + the carried owner-Qs (straddle-path threshold, 0DTE book routing, SENSEX fixed-125k). Cadence verified (07-08/09 = stack-down, task healthy). **LANDED 2026-07-10: P1+P2 #675 (env-wired + retuned iv_pair 0.10→0.02 / oi_spurt price-floor 50→8), P3 #676 (iv_rank null→excluded from the composite denominator).** | rollup §Proposals; #673; #675; #676 | DONE #675 (P1/P2) + #676 (P3) |
| E8 | `f3-dot-fixes-evidence-gated` | F3.2–.4 dot fixes (iv_rank live semantics, iv_pair units, oi_spurt floor) — build on E7/E1 evidence. F3.5 volume-floor mechanism already shipped+armed #605; only the k verdict remains (E1/E7) | 10x roadmap F3 | OWNER |
| E9 | `track3-long-term-investing` | Track-3 long-term investing placeholder (owner goal #3 — "parked, not silently dropped"): `long_term` screener preset + ADR-0004 fundamentals as first-class gate when started | master-plan §18.2 | OWNER |

**F. Older-source startables (pre-2026-07-10 sources; all verified still open)**

| # | id | what (1 line) | source | tier | status |
|---|---|---|---|---|---|
| F1 | `audit-startable-meds` | 07-05 audit startable Meds: M1 margin-heat one-basket + >20-leg truncation (matters when F9 arms), M16 book-default fail-open governors, M17 STOMP frames lack book field (pollutes filtered caches), M18 cockpit all-books framing, M28 e2e for new pages | 07-05 audit §12; §8a | clean | **DONE — ALL 5 MERGED+DEPLOYED LIVE 2026-07-12: M1+M16 #739 @ 4dc43731 (per-book ?book= + NFO/BFO filter + loud >20-leg refusal; startup governor seed; review adds: UNPRICED audit on blind heat-gate — load-bearing since F9 armed same night), M17 #737 @ f1aff015 (book on frames + FE merge guard), M18+M28 #741 @ 647b442b (honest cockpit book framing + 4 e2e specs + PaperPage book assertion)** |
| F2 | `manas-n1-reads` | Serial/N+1 candle reads in Manas + Minervini backtest services (~1,800 round-trips; ~40 min under pg_dump) — batch, prove equality re-run | §8d | HOLD | **DONE — #750 @ 4639a844, MERGED+DEPLOYED 2026-07-12** (chunk-batched IN-clause reads, ~1,800→~36 round-trips; equality proven byte-identical both services) |
| F3 | `fifo-slot-probe` | FIFO-vs-RS-priority slot admission probe: `portfolioFifoNet` accounting + live 7-slot-cap exceedance measure (owner "take later") | Manas Arora section (post-§7) | clean | **DONE — #751 @ 9c8df0b6, MERGED+DEPLOYED 2026-07-12** (V034 probe columns; measurement-only, fail-soft, admission byte-identical; first rows = Monday 20:05 batch; offline portfolioFifoNet re-run = follow-up consuming the persisted dropped-names+ranks) |
| F4 | `shadow-net-anomaly` | ~~Investigate pnlPoints vs pnlNet + composite-070 zero rows~~ **INVESTIGATED 2026-07-10 (live-DB forensics, no code change): NOT an aggregation bug.** (1) pnlNet excludes 20 unpriced closes — ALL losers, ALL closed 2026-07-03 pre-F8-cost-model (documented `VariantSummary` javadoc pitfall); net is survivorship-biased, not wrong (champion now 54 closed 19W/35L, −225.3 pts, +₹16,247 net over the 34 priced). (2) composite-070 is UNSATISFIABLE by construction: `accepts()` admits only pure composite-blocks (composite < 0.60) but its floor is 0.70 — a tightening variant on a rejected-only book can never fire. Follow-ups → F9 | this row; `ShadowVariants.accepts:117-149`; DB query 2026-07-10 | clean | DONE (investigation; follow-ups F9) |
| F9 | `shadow-evidence-hygiene` | ~~(a) backfill + (b) composite-070 pick~~ **DONE 2026-07-10 (owner: "do (a) and swap to composite-055")**: (a) `pnl_net` backfilled for all 20 pre-F8 closes — SQL fee-port verified EXACT against all 42 priced rows first, then UPDATE 20; league now 0 unpriced; **honest champion all-history net = −₹18,560** (was +₹16,247 with the losers hidden — month-end E1 can now use net directly); (b) `composite-055` swapped in (#671, `d1da58f8`), container recreated + live-verified ("shadow challenger variants active: vol-off, vol-12k5, composite-055", healthy, 39 strategies) — tests the falsifiable [0.55, 0.60) loosening band | F4 findings; #671 | done | DONE #671 |
| F5 | `signal-analysis-backlog` | README §7 rows 3–7: all-eval mode, data-health row flags, eval-denominator, **dot-null semantics unification** (dead dots mis-read as bearish), FE funnel view | `docs/signal-analysis/README.md` §7 | owner | OWNER |
| F6 | `small-residue` | Partial-bhavcopy targeted re-fetch endpoint (§8d), AC-1 dated-futures instrument-search miss (§8b), Manas pyramiding IT-gap (chip task_67cf8715), `kite.rateBudget` null-stub producer (SystemStatusController:95) | §8b/§8d; memory chip; PHASE_GATES parking | clean | **DONE — #744 @ e0ce8813, MERGED+DEPLOYED LIVE 2026-07-12** (all 4 built; rateBudget live-verified 1.0; pyramiding IT closes chip task_67cf8715) |
| F7 | `scalp-push-payload-extras` | Scalp alert push: add `suggested_qty` + an `/orders` pre-fill deep link (core shipped #152; "only the two payload extras remain") | master-plan §18.4 | clean | **DONE — #743 @ 1c029165, MERGED+DEPLOYED LIVE 2026-07-12** (qty rides automatically; deep link dormant until owner sets `ARTHA_NOTIFIER_APP_BASE_URL` to the reachable app origin — a .env flip, no rebuild) |
| F8 | `docs-currency-pass` | Strike stale rows: DEFERRED_BACKLOG (B6 export shipped #584, #140 deployed, Phase-5 live, value-verify PASSED, W-U4 WON'T, SPAN .spn superseded #510), PHASE_GATES §16.1 Phase-4/5 cells + parking hash-pinning, README W-U4 line, spec header "Implementing"→SHIPPED, ledger §8a #591→#607 | round-2 sweep | clean | **DONE — #746 @ 6c9aa518, MERGED 2026-07-12** (11 rows struck with per-row git evidence; §8a #591 verified already-struck) |

---

## 1. Net-new code

| id | item | authority | state |
|---|---|---|---|
| `phase5-minervini-trend-template` | **Phase-5 Minervini SEPA** — daily 8-gate Trend Template + cross-sectional RS-rank screener (master-plan §13) **plus** the full workflow (VCP/base geometry, the 6 §6 setups, swing paper/backtest/live, selling). Detailed trackable plan (audited): [`2026-07-04-minervini-sepa-implementation-plan.md`](archive/2026-07-04-minervini-sepa-implementation-plan.md) — Track A (screener) = shippable 80/20; Track B = setups/entries/paper/live. Carries OD-1..OD-5 pending owner input (OD-5 = screen-results lineage vs §17.1). | master-plan §13/§9/§5/§14/§17.1/§17.7; the new plan doc; `DEFERRED_BACKLOG.md` Phase-5 row | **TRACK A SHIPPED + LIVE 2026-07-04** — screener [#524](https://github.com/prashantm912/artha-yantra-2/pull/524) (1,590 low-caps scanned → 210 pass 8 gates) + Upstox fundamentals/low-cap gate [#525](https://github.com/prashantm912/artha-yantra-2/pull/525) + React `/equity/minervini` [#526](https://github.com/prashantm912/artha-yantra-2/pull/526). **Track B STARTED — Phase 5 (VCP/base geometry + analyzer endpoint) SHIPPED+LIVE [#528](https://github.com/prashantm912/artha-yantra-2/pull/528)**: `ZigZag`+`VcpDetector` (canonical `40W 31/3 4T`), `V033__minervini_setups`, `GET /candidate/{symbol}` analyzer; 12-agent adversarial review (6 findings fixed); live 210 geom rows / 102 is_vcp. **Phase 6 RECON + build-spec** (`2026-07-04-minervini-phase6-build-spec.md`). **Phase 6 SUBSTANTIALLY SHIPPED+LIVE:** PR-E `session.style=swing` engine keystone [#530](https://github.com/prashantm912/artha-yantra-2/pull/530) (parity-safe, all goldens byte-identical); PR-F `vcp` breakout setup + `VCP_PIVOT` indicator [#531](https://github.com/prashantm912/artha-yantra-2/pull/531) (`VcpSetupTest` fires on breakout-with-volume); PR-G SEPA funnel 3-list [#532](https://github.com/prashantm912/artha-yantra-2/pull/532) + funnel FE view [#533](https://github.com/prashantm912/artha-yantra-2/pull/533) (LIVE: 62 buyable / 35 on-deck / 113 watch on 2026-07-03; `/equity/minervini` Screen\|Funnel toggle). **Phase 6/7/8 ALL SHIPPED+LIVE:** all 4 setups (`vcp`#531/`primary_base`+`WEEK52`#535/`cheat_3c`+`power_play`#536), regime gate #537, swing backtest #538, flat-8%-stop #539. **SECOND BATCH #540-#542 (2026-07-04):** MV-8.1 **hit-rate harness** [#540](https://github.com/prashantm912/artha-yantra-2/pull/540) (candles@1d re-screen, forward returns vs NIFTY — LIVE: mean excess +0.27→+2.92% at +5→+63 sessions, an asymmetric momentum edge; shared `MinerviniGates`; 4-critic review, 2 fixes); MV-4.4 **analyzer page** [#541](https://github.com/prashantm912/artha-yantra-2/pull/541) (`/equity/minervini/:symbol`, 50/150/200-MA chart + VCP pivot + 3 tabs — Track A UI complete); MV-7.4 **50-day-MA trail** [#542](https://github.com/prashantm912/artha-yantra-2/pull/542) (parity-safe, existing trailing/indicator basis) + the **partial-close executor BUILD-SPEC** (MV-7.3/7.4 scaled/staggered — `2026-07-04-minervini-partial-close-build-spec.md`; deliberately a supervised pass, parity-firewall + reliability bar not yet met). **THIRD BATCH #543-#546 (2026-07-04, "build all one by one"):** `minervini_detail` V020 side-channel [#543](https://github.com/prashantm912/artha-yantra-2/pull/543); **scaled/partial-close executor** [#544](https://github.com/prashantm912/artha-yantra-2/pull/544) (MV-7.3/7.4 — the parity-critical one BUILT to spec; `scaled_exit` tiers + side-channel `qtyFraction` + `applyExit` + fraction-aware `ReplayEngine`; 10-agent adversarial review found a REAL parity edge `BacktestParityTest` misses — a colliding close-fill-bar closing the wrong position — + 4 more, ALL fixed; goldens byte-identical); defensive selling [#545](https://github.com/prashantm912/artha-yantra-2/pull/545) (MV-9.2 `signal_exit crossunder(px,sma20)`); report-card [#546](https://github.com/prashantm912/artha-yantra-2/pull/546) (MV-10.1/10.2 `SwingReportCard` grade vs the reliability bar). MV-9.4 alerts = **DONE-BY-REUSE** (existing `NotifierService` per-strategy opt-in — a minervini listener would be a redundant duplicate). **BUILDABLE TRACK-B SURFACE COMPLETE** — full exit doctrine (8% stop → 50d-MA trail → scaled tiers → 20d-MA defensive) built + parity-verified. **PHASE-9 LIVE-OPERATION PASS SHIPPED + DEPLOYED 2026-07-04** (owner reviewed the hit-rate — LIVE mean-excess +0.27→+2.92% at +5→+63 sessions — and said "all 4 setups, full Phase-9 in one pass"): **6 PRs #548–#553** — P9-B geometry cheat/thrust ([#548](https://github.com/prashantm912/artha-yantra-2/pull/548)), P9-A seed 4 setups + `minervini_funnel` universe + `seeded`-indicator publish gate ([#549](https://github.com/prashantm912/artha-yantra-2/pull/549)), **P9-C daily swing engine keystone** ([#550](https://github.com/prashantm912/artha-yantra-2/pull/550) — reuses the FROZEN EntryEvaluator/ExitEvaluator over the daily bar since funnel equities don't tick; 2-reviewer adversarial pass fixed 4 issues incl. the auto-paper `suggested_qty` stamp + daily-close settle; goldens 9/9, suite 532/532), P9-I report-card endpoint ([#551](https://github.com/prashantm912/artha-yantra-2/pull/551)), P9-H buyable-transition push ([#552](https://github.com/prashantm912/artha-yantra-2/pull/552)), P9-F sell-decision triad ([#553](https://github.com/prashantm912/artha-yantra-2/pull/553)). P9-D/P9-E = done-by-reuse (global auto-paper + swing excluded from the 15:45 square-off; `minervini_detail` stamped on entry). **P9-G Stage-3/4 exit = DEFERRED BY DOCTRINE** (owner pinned 8%-stop + 50d-trail; it's an A/B variant after the base proves out). Go-live (P9-Z): compose flag-passthroughs + live `.env` seed+swing ON + 4 strategies published + smoke-tested; **the 20:00-IST batch fires for real Monday 2026-07-06 post-close, accruing the forward paper trades the §0.5 #12 reliability bar needs** (the paper book with the pinned exits is the real test). The full daily find→geometry→funnel(+regime)→setups→backtest→hit-rate→analyze→**live-paper→sell-decisions** workflow is COMPLETE. **RAN LIVE 2026-07-04 (Fri close, owner: daily-bar signals analysed post-close any session) → 8 entries fired → 8 swing paper positions open; `.env` seed+swing flags persist true.** **DEEP-HISTORY BACKTEST + LIVE CALIBRATION DONE 2026-07-04/05 (#556–#563, owner: "calibrate the live knobs on evidence"):** ~11y event-driven sim over `candles`@1d (~1,789 EQ, ~40k signals) + a 7-version A/B chain (`MinerviniSwingBacktest`/`SwingPortfolio`/`SwingRotationPortfolio`, V035, outside the parity firewall) — full write-up [`docs/strategies/minervini-swing-backtest-results.md`](../../strategies/minervini-swing-backtest-results.md), per-version log in the build-log. **Findings:** v1 expectancy +5.68%/tr (asymmetric momentum edge); v3 the portfolio view FLIPS the per-trade A/B — 8 slots hold ~750 of 39k signals so *selection* is everything → **RS-rank is the edge** (nearly doubles 8-slot CAGR, best Sharpe); v4 RS-priority allocation = a free +6–16pt lift (live funnel already RS-ranks); v6 the ₹37.5 L turnover floor was a local minimum at every book size; v7 **12 slots is the sweet spot** (best net CAGR + lower DD + higher Sharpe) and **RS-rotation is catastrophic** (+39%→−41%, confirms hold-to-natural-exit). **Live config tuned + verified (owner-confirmed money picks):** turnover floor `liquidity-multiple` 100→**25** (₹37.5 L→₹9.375 L, #561); `max_open_paper_positions` uncapped→**12**; `max_deployment_pct` 20→**80%**; per-name `position_sizing` 5→**6.5%** (12×6.5%≈78%, #563), 4 strategies re-published **v1.0.1** (verified live). Realistic net-of-cost expectation for the tuned book: **~25% CAGR rs-turnover (live-funnel equivalent) → ~39% rs-only (optimistic upper bound)**, budget 40–50% DD. **REMAINING = ONLY the supervised forward-paper watch + the owner's §0.5 #12 reliability sign-off** — backtest proves the mechanics have edge; the live paper book (pinned 8%-stop + 50d-trail) is the real test. |
| `10x-roadmap-p2-p5` | **The 10x-value roadmap's remaining phases.** SHIPPED: P1+F8+F4v2 (#483–#491), F6 telegram bot (#493, LIVE), **F7 graduation-measurement dashboard (#515)**, **F9 SPAN capability + advisory heat read (#510/#514)**, **F9 app-layer (advised_lots + heat cap + governor) [#576](https://github.com/prashantm912/artha-yantra-2/pull/576) DEPLOYED (advisory-dormant, arm via `ARTHA_PAPER_RISK_ENABLED`)**, **F7 auto-promotion logic (GRADUATED strategy stage) [#577](https://github.com/prashantm912/artha-yantra-2/pull/577) DEPLOYED (measurement-only, arm via `ARTHA_GRADUATION_PROMOTION_ENABLED`; dormant until a strategy hits 50 closed paper trades)**. REMAINING: F2 proposals pass (self-triggers at ≥5 rollup sessions), F3.2–.5 dot fixes (variant-evidence-gated), F5 always-on host (owner pick). **F7/F9 code is BUILT — remaining is only owner ACTIVATION (flip the flag after the advisory week/data accrual), not a build.** | [`2026-07-03-10x-value-roadmap.md`](2026-07-03-10x-value-roadmap.md) | ACTIVE — every remaining sub-item is data-gated or owner-gated; nothing more buildable without a gate opening. |
| `f9-app-layer` | **F9 paper risk APPLICATION layer** — advised_lots sizing + portfolio heat cap + governor ntfy, on top of the SPAN capability. **SHIPPED + DEPLOYED LIVE 2026-07-05 ([#576](https://github.com/prashantm912/artha-yantra-2/pull/576), `b266d151`).** Owner numbers: per-trade risk 1% / daily-loss 3% / heat cap 60%. V023: advised_lots/margin_snapshot/margin_pct columns + per-book heat_cap_pct (scalper 60% enabled, swing books inert) + scalper daily-loss 10%→3%. advised_lots = risk-based sizing stamped at open (advisory); margin_snapshot = PaperMarginAnnotator prices each open position's SPAN AFTER_COMMIT+async (fail-soft); heat-cap gate blocks new entries at ≥60% book SPAN **only when `artha.paper.risk.enabled` (ARTHA_PAPER_RISK_ENABLED) is ON** — default OFF (advisory-dormant, zero hot-path cost). 2-reviewer pass (adversarial + timescale-domain, both PASS) → gated heat pricing behind the flag + added a PaperMarginClient timeout. **Remaining = owner ACTIVATION only:** after a clean advisory week (watch `GET /paper/margin-heat` + per-position margin_snapshot), set `ARTHA_PAPER_RISK_ENABLED=true` in `.env` to arm the heat-cap enforcement. | roadmap F9 |
| `upstox-margin-route` | **F9 SPAN source via Upstox margin API** — `UpstoxMarginClient` + `POST /api/v1/market/margin` (typed record, fail-soft, gated on the analytics token) compute broker-real SPAN server-side, NO `.spn` file. Live-verified 2026-07-04 (1-lot short → span 337004.85 / final 188604.45). `GET /v2/user/get-funds-and-margin` = live capital (down 00:00–05:30 IST → 423); `GET /v2/charges/brokerage` = pre-trade charges (cross-checks F8 `FeeConstants`). marginism appliance #126 = offline/backtest fallback. **Gotcha:** `quantity` must be a lot multiple (UDAPI1104 else) — scalper qty already lot-aligned; ≤20 legs/basket. | roadmap F9; ADR-0002 | **SPAN CAPABILITY SHIPPED [#510](https://github.com/prashantm912/artha-yantra-2/pull/510) + advisory heat read [#514](https://github.com/prashantm912/artha-yantra-2/pull/514) (`GET /api/v1/paper/margin-heat`), deployed 2026-07-04.** Remaining F9 app layer = paper `advised_lots` sizing + daily-loss governor + heat cap — needs owner risk numbers + an advisory week. Supersedes `span-real-spn-broker-parity` (§2). |
| `signal-eval-redis-subscriber-watchdog` | **HIGH reliability — silent recurring Redis subscriber drop on the live signal engine.** RCA'd 2026-07-07 (`docs/signal-analysis/2026-07-07-session-findings.md` §8, correcting the automated report's "consumer hang" misdiagnosis): `SignalEngine`'s `RedisMessageListenerContainer` on `candles.1m.*` **silently drops its subscription intermittently** mid-session (two gaps on 2026-07-07: ~12:18–13:20 IST recovered, 14:22→close did not). The `signal-eval` executor is healthy (parked on `take()`, thread-dump confirmed) — it's STARVED, not hung; market-data feed is GREEN and NIFTY bars build fine (the FINNIFTY canary REDs are the illiquid-far-month false-positive). **No error/reconnect is logged and no canary covers consumer-side receipt** (market-data's `DataHealthCanary` watches bar CLOSES on the producer side only). A silent mid-session receive gap can miss a stop-loss **EXIT** eval, not just entries. **Fix:** (a) log `RedisMessageListenerContainer` drops/recoveries; (b) a subscriber-side receive-gap watchdog — when market-data is GREEN but no `candles.1m` bar has been received for N min during market hours, re-subscribe + ntfy (mirror `DataHealthCanary`, consumer-side). | §8 findings; `SignalEngine.java:64-131`, `DataHealthCanary` | **BUILT — [#634](https://github.com/prashantm912/artha-yantra-2/pull/634), HOLD for owner deploy sign-off.** `SubscriberHealthCanary` (per-minute in-session receive-gap check, feed-fresh cross-check via `ticks:last-at`, overlap-safe re-subscribe routed through the eval thread, ntfy) + `SignalEngine` receive-heartbeat + notifier listener. 2-reviewer adversarial pass: fixed the one HIGH (monitor-held-across-resubscribe-I/O → routed through `evalExecutor`); rebutted global-vs-per-channel with the single-connection all-or-nothing model + incident evidence (NIFTY+SENSEX eval both stopped at 14:22:45); 7 unit tests + ModularityTest green. **MERGED + DEPLOYED LIVE 2026-07-07 (#634, `064c259c`, owner "deploy 634").** CI caught a real context-load break first (the canary's `SignalEngine` dep failed the engine-disabled paper `*IntegrationTest` contexts → gated it on the same `@ConditionalOnProperty(artha.signals.engine-enabled)`; lesson: run a paper IT locally when adding a `@Component` to strategy-signal). Live-verified: running sha == HEAD, health UP, bean loaded (clean boot under matched condition), engine still subscribes + loads 39 strategies. Armed (default ON); first live opportunity = tomorrow's session. |
| `external-batch-liveness-watchdog` | **NEW (2026-07-09) — a whole-stack/host outage silently skips the 20:05 swing batches, and the in-process P0-4 did-not-run canary CANNOT catch it.** Surfaced while verifying the first live H4 Chandelier batches: Docker Desktop was found DOWN on 2026-07-09 (the stack stopped sometime after the 07-08 20:05 run), so **07-09's Manas + Minervini swing batch MISSED** — a one-day gap in the forward-paper reliability record that the reliability sign-off depends on. The P0-4 `swing_batch_runs` did-not-run canary runs INSIDE strategy-signal, so a full-stack/host outage kills the watchman together with the batch → zero alert (an in-process canary is structurally blind to its own host being down). **Fix (owner call):** (a) the **always-on host** (thread 2 / F5) makes outages rare — the real fix; (b) an **EXTERNAL** liveness check — a cheap off-box uptime pinger, OR a post-batch heartbeat the stack emits (ntfy/healthchecks.io "dead-man's-switch") that an external service alerts on the ABSENCE of. Until one exists, the owner must keep the box + Docker up at 20:05 IST daily. | this session's verify; `swing_batch_runs`, P0-4 canary | **BUILT + DEPLOYED (dormant) 2026-07-09 — option (b) [`#640`](https://github.com/prashantm912/artha-yantra-2/pull/640), `753805b5`** (owner "can't go always-on host now, go for a[= the dead-man's-switch]"). `SwingBatchHeartbeat` (strategy-signal): @Scheduled 20:15 IST MON-FRI pings an EXTERNAL monitor after both swing batches; a whole-stack outage → no ping → the off-box monitor alerts on the missed schedule (survives exactly the outage the in-stack canary can't). Dependency-free bean (safe in engine-disabled paper ITs, unlike SubscriberHealthCanary); fail-soft; **DORMANT until armed**. **ARM (owner, ~15 min):** create a healthchecks.io / UptimeRobot *heartbeat* check, set its expected schedule to `15 20 * * 1-5` TZ Asia/Kolkata with a grace window, add `ARTHA_HEARTBEAT_URL=<ping-url>` to `.env`, redeploy strategy-signal. The always-on host (thread 2 / F5) is still the real fix (this only alerts, doesn't prevent). 07-09 batch missed (stack was down); 07-06/07/08 verified clean (exit_skipped=0; 07-08 first live exit = SBCL STOP_LOSS). |
| `evolution-engine` | **Autonomous strategy evolution & optimization engine** — campaign/generation/candidate model over the existing sweep+folds+StressGuard+shadow-book+F7 substrate; per-family evidence policies (SIM_FIRST swing / LIVE_FIRST scalpers / SIM_BLOCKED BTST until P0-5); stability-first search (plateau + neighbor probes, deflated-Sharpe multiplicity gate, DOF penalties); RobustScore hard-gates+weights; matched-window backtest↔live reconciliation with a DIVERGENT tripwire; owner-gated promotion state machine (PUBLISH_PAPER = **sibling-clone strategy**, nothing self-arms); roadmap E0–E6. Both docs verified by 2-pass adversarial+completeness agents (0 refuted in the audit; 5 refuted findings FIXED in the design). | **Design (2-pass-verified, MERGED):** [`2026-07-10-strategy-evolution-engine-design.md`](2026-07-10-strategy-evolution-engine-design.md) (#659, `f33fae62`); fixed input = the research-fidelity audit [`docs/audits/2026-07-10-research-fidelity-audit.md`](../../audits/2026-07-10-research-fidelity-audit.md) (#658, `b246c8ce`) | **BUILD SUBSTANTIALLY SHIPPED 2026-07-11/12 — E1–E4 / I1–I3-backend live; see §0 groups C + wave notes.** ~~**DESIGN MERGED 2026-07-10 — build NOT started; gated on E0**~~ (design §12/§13 HARD rows): (1) audit **P0-1 partial coarse-bucket poisoning** (chip filed — live 3m/5m/15m/1h series evaluate frozen 1-min partials; LIVE_FIRST campaigns must NOT launch pre-fix, pre-fix live evidence quarantined); (2) audit P0-2 engine git SHA on runs; (3) audit P2-1 optimizer durability (Optuna study state is in-memory; sweeps die on restart); (4) audit T3 `created_by` actor plumb; (5) audit §11.4 experiment views + compare endpoint; (6) design §13 **row 19 NEW: funnel-universe resolution in the job pipeline** (`universe.mode: manas_arora_funnel` unresolvable, `universeOverride` stored-never-read → no Manas/Minervini trial can run — fix via the `futures_screener` pinning precedent, JobsService.java:104-118); (7) design §13 **row 20 NEW: regime-label vocabulary bug** (optimizer/FE use BULL/RANGE/BEAR/CRASH vs canonical UP_QUIET/UP_TURBULENT/DOWN_QUIET/DOWN_TURBULENT → `regimesCovered` always empty on real folds; chip filed). Then E1 (experiment model + retro-scoring of existing sweeps) is the first build increment. Owner pre-decisions parked in the design: regime-gate stream needs a Stage-D design amendment (regimes are frozen "reported, not optimized"); Manas campaigns need an authored `backtest.optimize` block. |
| `intelligence-layer` | **Intelligence, automation & decision-support layer (program-2 Prompt 2)** — the FAST decision loop over the app surfaces (the evolution engine is the slow research loop; boundary pinned in design §0): `insights` Modulith module in strategy-signal (insight records w/ mandatory evidence + engine SHA + config hash, 12 deterministic generator types, dedupe/cooldown/severity-floor/mute suppression w/ suppressed-but-stored ledger) + 5 typed digest endpoints in market-data (options/futures/equity/FII/day-context) + one `market_context_days` table; signal priority = weighted components (edge .30/context .20/track-record .20/risk .15/freshness .15, clamp(0,1)) × trust cap (DEGRADED caps at B-band, BLOCKED never scores); AUTO/PROPOSE/MANUAL automation tiers (PROPOSE = one-click through existing endpoints, trust-gated; nothing self-arms); Focus panel + `/insights` feed (= the P1 notification center) + explain drawer + ContextStrip/TrustChip; staged shadow rollout (feed-only → WS → ntfy, flags default OFF); weekly quality report, owner tunes — nothing self-tunes. Deterministic rules, NO ML/LLM. 2-pass verified (5 accuracy agents ~135 checks + completeness): ZERO invented-data findings; 1 refuted (sweep timing) + 2 infeasible-as-specced (advised_lots pre-take; fired-vs-rejected per-rail) FIXED pre-merge. | **Design (2-pass-verified, MERGED):** [`2026-07-10-intelligence-layer-design.md`](2026-07-10-intelligence-layer-design.md) (#662, `a84ceb31`); fixed input = the app-platform audit [`docs/audits/2026-07-10-app-platform-audit.md`](../../audits/2026-07-10-app-platform-audit.md) (#661, `db2d8a88`) | **BUILD SUBSTANTIALLY SHIPPED 2026-07-11/12 — E1–E4 / I1–I3-backend live; see §0 groups C + wave notes.** ~~**DESIGN MERGED 2026-07-10 — build NOT started; gated on design §13 HARD rows:**~~ (1) `ingest_runs` ledger (P1-Phase-1 — the batch-source trust oracle; row 5); (2) P1 V1/V3 paper guards before any I3 one-click order action (row 6 — one-click take would amplify the ungoverned-manual-path defect); (3) `sell_decisions` persistence (row 8); (4) Manas `rs_rank` API serialization (row 12); (5) gateway allowlist entry + spec recapture in the module PR (row 18); (6) `notification_events` nullable-`strategy_id`+`insight_id` migration for market-scoped push audit (row 20); (7) fired-side rail-operand side-channel for the Stage-2 fired-vs-rejected contrast (row 19, parity-safe additive). Build order I1→I4 (design §12); I1 starts after P1-Phase-1 of the app-platform audit roadmap; I4 delivery arming is owner-gated. Shared prerequisite with `evolution-engine`: both wait on P1-Phase-1 foundations — owner sequences which program builds first. |

## 2. Owner-gated — needs owner input/time, not code

| id | what's built | what's needed |
|---|---|---|
| `live-forward-paper-analysis` | Auto-paper ON (#367); every gate block persisted to `signal_rejections` (#404); analysis procedure = [`2026-06-30-live-signal-analysis-runbook.md`](2026-06-30-live-signal-analysis-runbook.md) | **~1 month of live-paper scalper trades**, then run the runbook: E9 target/trail band number + per-scalper keep/cut/tune via counterfactual replay on real captured premium. The biggest open track. |
| `span-real-spn-broker-parity` | ~~`.spn` loader + parity harness (#144)~~ **SUPERSEDED 2026-07-04** — the `.spn` file is no longer needed. See `upstox-margin-route` in §1: Upstox computes SPAN server-side (`POST /v2/charges/margin`) on the analytics token we already hold — no NSCCL file, no broker-number hunt. The marginism appliance (#126) stays the offline/backtest fallback; live sizing routes through Upstox. **No owner action** — moved to buildable. |
| ~~`telegram-scalp-alert-optin`~~ **DONE (superseded)** | notifier path built (#152) | ~~owner sets the bot token~~ — satisfied: the F6 Telegram bot (#493) is LIVE and shares `ARTHA_NOTIFIER_TELEGRAM_BOT_TOKEN` (struck in the 2026-07-10 round-2 queue audit) |
| ~~`per-strategy-notifications`~~ **SATISFIED 2026-07-12** | ntfy verified end-to-end (direct + service path, 2026-07-02) | ~~only `scalp-connect-the-dots-nifty` has notifications ON — owner toggles the other 11 published strategies per taste~~ — notifications armed for ALL 45 published strategies (E3 flag-arms row); only per-strategy re-muting remains owner-taste. |
| ~~`sensex-pe-publish`~~ **DONE 2026-07-05** | 18 SENSEX-PE drafts seeded (#382) | ~~owner publish decision~~ — owner said "publish"; all 18 PUT-side drafts published live via the internal registry endpoint (`docker exec … wget POST /strategies/{id}/publish`, bypasses gateway auth); engine reconciled 21→39 scalpers. (The publish also surfaced + fixed the reconcile-loop bug #579.) No PR (a live data action). |
| ~~`value-verify-ratify`~~ **STALE (collapse)** | data-foundation value-verify **PASSED** live-vs-live 2026-07-01 (captured OI == oipulse exact share) | ~~owner ratifies the close; residual low nits in §5~~ — §5 CLOSED 2026-07-04 (pointer dead); nothing left beyond a one-sentence ratification (struck in the 2026-07-10 round-2 queue audit) |
| `soft-dot-arming` | FU2 dots + drasticFloor default built inert | arm only if live forward-paper data proves them (else stays a §7 WON'T) |

## 3. Verification only — next market session

Ran live 2026-07-03 (findings addendum A2 in `docs/signal-analysis/2026-07-03-session-findings.md`):

- [x] T2 aligned snapshot buckets row-for-row vs the oipulse barometer — **PASS** (labels identical;
  Call OI exact 3/3; put-row residuals = oipulse polling lag on BSE OI dissemination, our
  end-of-window values are the fresher ones).
- [x] Capture crons fire on the new boundaries: 09:16 futures / 09:18 options — **PASS**.
- [x] First pre-open equity scan lands at 09:09:30 (#470) + futures pre-open page renders live
  (#377) — **PASS**.
- [ ] NSE announcement field mapping on live data (#378) — not exercised 2026-07-03; check next
  session with fresh announcements.
- [ ] **sentimentPct formula reconcile (master-plan §18.6):** the level-based method is now BUILT
  (#512, 2026-07-04) — `ActiveStrikeService.sentimentLevelPct` (`100·(ΣputOI−ΣcallOI)/ΣputOI`) rides
  BESIDE the ΔOI-flow `sentimentPct` on the active-strikes response (`sentimentLevelPct` + per-bucket
  `levelPct`); the ΔOI number and the sentiment GATE are untouched. **Remaining (owner, live):** one
  live-session compare of both numbers vs the oipulse dashboard → decide which the gate should read.
- [ ] Stock-chain warm on a SECOND symbol during market hours (#472 verified on RELIANCE
  off-hours) — not exercised 2026-07-03.
- [ ] Owner hard-reload (Ctrl+Shift+R) after each FE redeploy — standing owner action.

**New verifies for 2026-07-06** (P1/F8 live acceptance, roadmap `2026-07-03-10x-value-roadmap.md`):
canary tile green through the session + zero false alerts; NIFTY26JULFUT TICK_AGG bars past 12:40
(#482 fix) + watch for "dropping future-stamped tick" WARNs; variant books (vol-off / vol-12k5 /
composite-070) populate with net-₹ labels; breadth dot scores intraday (advances/declines non-zero
in rejections' context). The 09:42 + 15:47 agents cover all of it.

### 3b. Audit-register fix queue — **CLOSED 2026-07-04**

All 22 surviving rows shipped in the weekend batch, PRs **#500–#507** (every one CI-green,
squash-merged; #506 replay-fallback re-verified against the goldens/parity suite before merge).
Per-row outcomes live in the addendum table at the bottom of
[`archive/2026-07-02-full-codebase-audit-findings-register.md`](archive/2026-07-02-full-codebase-audit-findings-register.md).
The whole 2026-07-02 audit is now fully closed: 90 findings → 54 fixed same-day (#407–#435) +
4 accepted-risk + 1 refuted + 22 fixed here + the rest resolved by intervening waves.

## 4. Scheduled maintenance

| when | item |
|---|---|
| **before ~2026-11-16** | **CD-2 yearly calendar CSV refresh** — add 2027 NSE/BSE holiday CSVs to `libs/market-calendar`. `CalendarHorizonCanaryTest` goes red ~45 days before year-end; unrefreshed, the monthly-expiry look-ahead cliff starts 2026-12-29 and OI capture silently halts 2027-01-01. |

## 4b. Chip register (out-of-scope findings parked as one-click background tasks)

**Rule (owner ask 2026-07-12): every chip ALSO gets a row here at filing time** — the popup cards are
ephemeral; this table is the durable record. Each row is self-contained enough to rebuild the task if
the chip is gone. Status: OPEN · DONE (how) · STALE.

| chip id | filed | what (self-contained) | status |
|---|---|---|---|
| task_03b9f52d | 2026-07-12 | Funnel-universe resolver (backtest JobsService, #706) pins TODAY's screen date — weekend/holiday submissions get zero candidates ("needs a pinned universe"); fall back to the latest screen date ≤ today, stamp it in provenance. Repro: sweep f803dee6 (Sat). | **DONE core-was-already-fixed #785 + provenance #790** (funnel resolves latest persisted date since #587; #790 stamps universeAsOf into job request + /results; f803dee6 was a no-rows data condition) |
| task_b57c7fbe | 2026-07-12 | V010's manual `marketdata.prune_options_snapshots()` SQL wrapper errors on EVERY call (regclass re-resolution of dropped chunks — empirically reproduced on 2.17.2); the documented manual usage never worked. Fix = plpgsql PERFORM variant in a NEW migration. A10's job calls drop_chunks directly, so nothing depends on it. | **DONE #786** (V046 plpgsql PERFORM; empirically reproduced then fixed; domain review PASS) |
| task_132a71ba | 2026-07-12 | `CounterfactualRunRepository.findRunIdByJobId` has no ORDER BY + findFirst() → arbitrary row under multi-row shared-DB state; flaked an IT once. Add ORDER BY created_at DESC (or key by job uniquely). | **DONE #785** (ORDER BY landed defense-in-depth; premise partly mis-attributed — V013 unique index already guaranteed ≤1 row; the REAL flake = cross-context worker race, chipped task_9e244d18) |
| task_4f575c72 | 2026-07-12 | Quick verify: does `LiveSeriesStore`'s context-series bookkeeping need the same bucket-END gating the backtest append site got in #755? Likely already covered by #683's completeness filter + IndicatorBank.mappedIndex — confirm and close, or mirror the 5-line hardening. | **DONE-NO-GAP #786** (verdict: already covered — #683 inProgress filter at both warm sites + IndicatorBank.mappedIndex last-completed read gate; 1d fail-open is a PINNED deliberate contract; zero code change) |
| task_3e95fade | 2026-07-12 | Live BTST exit sweep: the sim now simulates BTST exits (P0-5 fix) but the live SignalEngine emits NO btst exit (preCloseEvaluate is entry-only, onClosedBar skips btst). Port the identical ExitEvaluator sweep to the pre-close clock; paper IT with max_holding_days:1. | **DONE #794** (sweepBtstExit close→close mirroring #759; 4-lens HOLD review; parity boundary fenced by BtstExitRuleParityBoundaryTest; Friday-guard relocation test-pinned) |
| task_8c3964cc | 2026-07-11 | Stress rounds (#729) can occupy all backtest workers — reserve a slot for interactive runs (B16 shipped the per-kind queue cap; this is the stress-orchestration-side reservation). | **DONE #784** (bounded drain ARTHA_STRESS_MAX_CONCURRENT_JOBS=2; restart-flood residual documented) |
| task_547656bf | 2026-07-11 | 3m-primary `tradeFrequency` metric (#721) counts bars-per-session with a hardcoded session length — absolute value skewed for 3m; ranking within a same-interval sweep unaffected. Fix the bars-per-session divisor per interval. | **DONE #785** (3m→125 carve-out then collapsed into periodsPerYear 3m=31500 which also fixed Sharpe/Sortino annualization task_c7132464; 5m/15m/1h byte-identical, goldens/parity green) |
| task_2560273c | 2026-07-11 | Funnel-knob tunability (EVO §13 row 21 third family): funnel props (rs-min etc.) are service-global config, no per-strategy YAML leaf exists — needs a config leaf + resolver plumb before EVO can tune them (the design's "funnel knobs wait"). | OPEN |
| task_1b85c64f | 2026-07-10 | Backtest 1h bars anchor on the hour; live 1h candles anchor 09:15+n·60m → a 30-min phase gap between sim and live 1h series (FID finding). Decide + align one side. | OPEN |
| task_94f40cf6 | 2026-07-10 | Manual take of a STALE signal bypasses freshness gating (A1 residual): PaperService.openManualOrder now governor-gated, but a signal taken hours later still fills at current tick without a staleness check on the SIGNAL itself. | **DONE #794** (artha.paper.signal-take-max-age-minutes default 60, swing books exempt, 422 SIGNAL_STALE; reads frozen signals.book post-#788) |
| task_f12c165f | 2026-07-11 | FE banner for export truncation (A9 residual): the API sends X-Truncated headers on the 3 export paths; the FE doesn't surface them. | **DONE #784** (real header = X-Result-Truncated; warnIfExportTruncated toast at BOTH download choke points — superset of the named 3 paths) |
| task_c7132464 | 2026-07-12 | MetricsCalculator periodsPerYear had no 3m case — Sharpe/Sortino annualized 3m bars as daily (correct constant 125×252=31500). | **DONE #785** (owner-clicked; folded into the chips-trio PR) |
| task_9062b5f1 | 2026-07-12 | Funnel-chosen screen date invisible in run provenance: UniverseResolver returned asOf=null for both funnel modes; JobsService never stamped it. Propagate screenDate→asOf→job request universeAsOf→/results echo. | **DONE #790** (owner-clicked; also fixed the wrong "TODAY's screen" comment) |
| task_9e244d18 | 2026-07-12 | CounterfactualIntegrationTest cross-suite race: a cached context's WorkerPool (bt-worker-1) consumes the shared Redis stream and duplicate-executes the test's job; insert() DELETE+re-INSERT invalidates the runId mid-read. Local full-verify coin-flip; CI sharded leg green. Fix: test-data isolation / claim gating / upsert-in-place. Two independent RCAs (funnel-asOf + D4 builders). | OPEN |
| task_a86f2d17 | 2026-07-12 | Stranded-carry reconciler class: settle failure AFTER emit() commits EXIT+EXPIRED-anchor leaves position OPEN forever (no retry — activeEntry empty next day); 21:15 V5/V16 classes don't detect it; BTST carries also escape 15:45 MTM. Add reconciler class flagging OPEN positions whose newest linked signal is a persisted EXIT. Pre-existing P0-2 tradeoff, most exposed by overnight carries. | OPEN |
| task_ed6b9d81 | 2026-07-12 | Registry-side btst exit-rule parity validation: BtstExitRuleParityBoundaryTest fences only seeded YAMLs; an owner-published btst config with ATR/trailing/square_off/r_multiple/signal_exit bypasses it and drifts silently sim-vs-live (synthetic 15:20 vs dense 15:30 daily bars). 422 at PUBLISH only (never refuse reload of already-published). | OPEN |
| task_cf5d58c8 | 2026-07-13 | backtest exit_reason CASING divergence surfaced by P1-3 (#799): candle path persisted lowercase while options/deep-swing/counterfactual/live-paper use UPPERCASE. | **DONE [#801](https://github.com/prashantm912/artha-yantra-2/pull/801) @ 4d5599c1, MERGED+DEPLOYED+LIVE-VERIFIED 2026-07-13** — owner-confirmed **UPPERCASE canonical + backfill** (evidence flipped the chip's lowercase guess: live paper close_reason is UPPERCASE, so is the 34k-row majority; only the candle path changed). One `ExitReasons.canonical()` helper at the single `TradeRepository.insertAll` chokepoint (idempotent for the already-upper paths) + V018 backfill + case-insensitive FE grouping. 3-reviewer pass 0 defects. Deploy: flyway-init force → V018 applied (50ms), **DB-probed 0 lowercase rows remaining, SIGNAL_EXIT 6591→7289 (+698 backfilled), table uniform UPPERCASE**; backtest-service + frontend-react recreated + healthy (jar carries ExitReasons.class). Caveats (owner-acknowledged): reconciliation metric still DORMANT (forward-readiness, not a live fix); casing-only leaves a paper-vs-backtest vocabulary gap; V018 one-way door (harmless — casing non-load-bearing) |
| task_acf03155 | 2026-07-12 | Analytics folds raw-bhavcopy across splits | **DONE #761** (owner-accepted; only EquityReturnsService needed adjusting — 9-fold decision table in the PR) |
| task_fab9e823 | 2026-07-12 | Journal PUT skipped the 1-5 rating range check | **DONE #779** |
| task_67cf8715 | 2026-07-06 | Manas pyramiding IT gap | **DONE #744** (F6 item d) |
| task_d6872aa3 | 2026-07-10 | Regime-label vocabulary mismatch | **DONE #705** (B10) |
| task_8972447b | 2026-07-10 | Partial coarse-bucket poisoning | **DONE #683** (B1) |
| task_fc239b57 | 2026-07-05 | Registry reconcile-loop bug | **DONE #579** |

## 5. Small optional nits — **CLOSED 2026-07-04** (with the fix-queue batch)

- Value-verify residuals: F3 heatmap UTC labels were already fixed (#402, stale entry); F5
  strike-series ΔOI is now the bucket-lag delta (#503 — full-chain `series()`/`latest()` reads
  deliberately keep the captured semantics so the live gate's dots stay byte-identical); the
  single per-strike Black-76 IV (CE==PE) is documented on the chain's IV column header (#503).
- FE revamp leftovers: "Columns" rename + chain density toggle had already shipped (stale
  entries). Options-chain Radix-Select migration: **decided SKIP** — the shared Select atom is a
  native `<select>` by design (a11y-strong, zero-dep); the migration is cosmetic with
  e2e-selector risk on the platform's most-used control. Never re-flag as pending.

## 6. Deferred-by-design — build only when a consumer appears

Provenance rows live in [`docs/DEFERRED_BACKLOG.md`](../../DEFERRED_BACKLOG.md) (cross-cutting + parked tables):

- ~~`candles_1h` IST re-anchor~~ **DONE (#513, 2026-07-04):** V029 drops+recreates `candles_1h` with
  `time_bucket('1 hour', bucket, 'Asia/Kolkata')` (was UTC-hour, unlike its IST 1d/1w siblings), `WITH
  NO DATA` + the same refresh policy so the live DB never does a heavy one-shot materialization.
- ~~Upstox `/pcr` … dormant `source.pcr` flag~~ **FLAG ALREADY BUILT as `source.optionanalytics=upstox|native`**
  (routes `/pcr-series` to Upstox `/pcr` vs native; mapping tested). Only a LIVE market-hours freshness
  check remains before flipping it (§4-adjacent; not an offline build). Do not re-flag as buildable.
- ~~optimizer `requirements.txt` hash-pinning~~ **ALREADY DONE (2026-07-04 audit) — remove, do not re-flag:**
  `requirements.lock` (799 SHA-256 hashes) + `requirements-dev.lock` (922) via `uv pip compile
  --generate-hashes`; Dockerfile installs `--require-hashes`, `ci-optimizer.yml` installs
  `--require-hashes -r requirements-dev.lock`. Supply-chain guard is live.
- Recorded real Kite binary-frame capture + B-9 frame-guard production wiring (needs a first-party WS client).
- Options-fidelity SNAPSHOT/SYNTHETIC_B76 live walk; walk-forward fold + MedianPruner live walk (multi-month data).
- ~~Second-order greeks (speed/zomma/color)~~ **DONE — vanna/charm/vomma were already shipped (§17.6);
  the third-order gamma-sensitivity trio speed/zomma/color added + FD-verified in `black76-math` and
  surfaced on the chain `Leg` (#511, 2026-07-04).** ~~§6.3 BSM-on-spot seam (stock options)~~ **DONE
  ([#582](https://github.com/prashantm912/artha-yantra-2/pull/582), 2026-07-05):** `BScholesMerton`
  in `black76-math` — spot+dividend-yield q pricing (price/IV reuse Black-76 on F=S·e^((r-q)T);
  spot greeks are the distinct BSM closed forms), FD-verified. DORMANT — index options keep Black-76,
  and a live equity-option wire-in additionally needs a dividend-yield (q) data source we don't have.
  **~~20-level depth flattening~~ SOURCE-BLOCKED (2026-07-05 recon) — do NOT build dead code:** the
  Kite/Upstox retail feeds only disseminate **5** levels (their wire DTOs hold what the feed sends);
  true 20-level needs NSE's separate paid depth feed we don't subscribe to. Plus depth is dropped at
  the domain `Quote` boundary with ZERO consumer and is live-only (mock can't test it). Nothing to
  flatten to 20, no reader — DEFER until the 20-depth feed is subscribed.
- ~~Native 3-min option-snapshot capture~~ **MOOT (2026-07-04 empirical check) — do not re-flag:** the
  backlog premise (5-min capture, want 3-min for Table-2 fidelity) is stale. Live capture already runs
  at ~1-minute granularity (verified in `options_chain_snapshots`), FINER than 3-min; any coarser
  interval (incl. 3-min) is a read-time rollup off the 1-min base. Fidelity goal already exceeded.
- Data-Ops parked: ~~`backfill_jobs` audit table~~ **DONE (#517)** — V030 run-audit table + `GET
  /market/admin/backfill-jobs` + Status-page history; ~~contract-type selector~~ **DONE (#517)** —
  Both/Options/Futures on the Collection wizard (default Both). ~~per-expiry BULK export~~ **DONE
  ([#584](https://github.com/prashantm912/artha-yantra-2/pull/584), 2026-07-05):** `POST
  /market/admin/export/bulk` zips one per-contract CSV/JSON of a whole (underlying, expiry) chain
  (`BackfillExportService.exportBulk`, ≤500 contracts sync) + a "Download whole expiry (ZIP)" button.
  **STOMP status push = NOT-WORTH-BUILDING (2026-07-05):** market-data has NO WebSocket infra; ~200
  lines of new plumbing to replace a working 2s poll for a single operator (net-negative). Deferred
  until a multi-dashboard consumer exists. Do not re-flag.
- Per-check server audit on signal Take; full-auto execution flag (semi-auto "Take" is the v1 safety boundary).
- AdvanceChart TV-binary extras: ~~OI-bar, trade-history, audio alerts~~ **+ horizontal price lines DONE
  (#516)** on the lightweight-charts v5 chart (all off/empty by default). ~~study-template save/load~~
  **chart-state persistence DONE ([#583](https://github.com/prashantm912/artha-yantra-2/pull/583),
  2026-07-05):** symbol/interval + the extras toolbar persist to localStorage (`core/chartPrefs`),
  restored on reload — the ADR-A13 chart-state v1. **Freehand drawing tools = DEFERRED (ADR A13):** a
  ~5-day custom lightweight-charts-primitive/canvas lift with a KLineCharts library-re-eval trigger,
  no consumer — not built as speculative dead code.

## 7. Decided WON'T DO — never re-flag as pending

Owner NOs, consolidated (full rationale in the archived inventory §6):

E8 ATR-stop arming · FU2 soft-dots as hard gates · E3 Dow dot arming · **W-U4 Upstox cutover (stay Kite,
split-by-capability end-state)** · SPAN short/sell premium legs (long-only) · live-broker order arming
(keep paper/read-only) · E12 ideal-window + OH-freshness + economic-event lockout/event anchors ·
Event Days page (static Budget slideshow, no API) · OiPulse ≥90% AI badge (proprietary; faithful
Table-1/2 HIGH tier is the equivalent) · E1 equity-screener OOM path (replaced by on-demand Upstox +
captured bank-radar) · SENSEX point-scale constant (dead — signals ride NIFTY-FUT-CONT).

---

## Net (refreshed 2026-07-04, post buildable-items sweep)

Everything currently buildable is BUILT: roadmap P1+F8+F4v2 (#483–#491) · the entire audit-register
fix queue (#500–#508) · the **F9 SPAN capability + advisory heat read** (#510/#514) · and the
**2026-07-04 buildable-items batch (#511–#517)** — third-order greeks, level-based sentiment,
candles_1h IST re-anchor, **F7 graduation framework**, Data-Ops backfill-audit + contract-type, and
the AdvanceChart feasible extras. Items that turned out already-done/moot were reconciled in the
docs (optimizer hash-pinning, the `source.optionanalytics` PCR flag, sub-3-min capture). What
remains is **owner-gated or data-gated only**: **the data month** (rollup → proposals pass at ≥5
sessions → the exit-band tuning session) · the **F9 app layer** (`advised_lots` sizing + governor —
owner risk numbers + an advisory week) · **F7 promotion numbers** + **F5 host pick** + **SENSEX-PE
publish** + **per-strategy notif toggles** + **value-verify ratify** (owner decisions) ·
**Minervini** (BUILT + LIVE + backtest-calibrated #556–#563; live now = 8 paper positions, tuned to a
12-slot / 80%-deployed / 6.5%-per-name / ₹9.4 L-floor book — remaining is ONLY the forward-paper watch
+ the owner's reliability sign-off) · a **Nov-2026 calendar refresh** (§4) · §3 next-session verifies.
Genuinely-deferred-with-no-consumer leftovers: Data-Ops bulk-export + STOMP-push, AdvanceChart
freehand drawing tools + study-template save/load. Machines watch the machines: two in-code canaries
+ two scheduled agents + the weekly backup round-trip CI.

---

## Manas Arora + per-strategy paper books (2026-07-05 autonomous batch — BUILT + DEPLOYED LIVE)

Owner batch ("implement autonomously while I sleep"), all merged + deployed to the LIVE stack same day:
- **Task 1** — Manas Arora strategy doc committed + merged (#565).
- **Tasks 2/3 — separate paper/signals BOOKS** (#566 backend + #568 frontend): the single global paper
  book split into 5 per-family books (scalper/minervini/manas-arora/manual/other), each **₹1.5 L**
  (owner "all 1.5L each"), own capital + risk config + auto-paper toggle; `book` = the strategy's first
  family tag; V021 migration (per-book paper_account + `(book,key)` risk_settings + `book` on
  positions/orders; existing paper positions WIPED per owner). `?book=` on the paper/risk/signals
  endpoints; per-book FE pages `/paper/:book` + `/signals/:book`. Minervini now sizes off its own ₹1.5 L.
- **Task 4 — Manas Arora family** (#567 screener / #570 live engine / #569 10yr backtest / #571 FE):
  a separate, non-interfering swing family (India-adapted Minervini). Screener live = 2,224 scanned →
  **97 pass all 6 gates**, funnel 41 buyable + 70 on-deck. Live engine (parity-safe, goldens
  byte-identical; cross-family isolation via `universe.mode`) auto-papers into the manas ₹1.5 L book;
  cron 20:05 IST. 10-yr backtest ran over ~11 yr candles@1d — **results + best setup:
  `docs/strategies/manas-arora-swing-backtest-results.md`**.
- **Deploy:** backup + `:62fb38f3` rollback images taken first; go-live flags in `.env` + compose
  passthroughs. Each PR got adversarial + domain review before merge (all PASS).
- **Doc-fidelity follow-ups SHIPPED 2026-07-05 (owner "build full §3.5 live" + "per-setup pivots"):**
  §3.5 ATR exit doctrine LIVE ([#573](https://github.com/prashantm912/artha-yantra-2/pull/573) —
  `atr_multiple` stop cap_pct 10 + armed trail arm_pct 9 + new `square_off` type, additive, goldens
  9/9 byte-identical); per-setup live pivots ([#574](https://github.com/prashantm912/artha-yantra-2/pull/574)
  — breakout→`MANAS_BREAKOUT_PIVOT`, vcp→`MANAS_VCP_PIVOT`, so vcp + breakout no longer fire off the same
  funnel pivot; live-proof: 2 real entries SENORES+SBCL where the same data gave 0 before). The v1 "possible
  v2" is now DONE — the live engine is doc-faithful.
- **Backtest review follow-up DONE 2026-07-05:** the 2 adversarial Manas-backtest reviewers ran (task
  `adaf…` PASS + `a6dd6a…` 1-bug/1-risk/1-question). Only real finding = missing `ORDER BY` in
  `eqSymbols()` (portfolio-stat reproducibility) — fixed in both the Manas fork (#569) and the Minervini
  original ([#575](https://github.com/prashantm912/artha-yantra-2/pull/575)). The `a6dd6a` reviewer's
  L545 volumeRatio "off-by-one" = FALSE POSITIVE (50 bars, verified); its stale-pivot + RS-sparse flags =
  verified non-issues (weekly-fixed pivot is intended; Pass 1/Pass 2 gate RS_LOOKBACK identically).
- **Backtest-improvement follow-ups SHIPPED + LIVE 2026-07-06/07** (from the 2026-07-06 swing-backtest
  comparison — two doctrine-faithful edges Manas lacked but Minervini already had):
  - **F1 — RS-rank gate + RS-priority funnel admission** ([#611](https://github.com/prashantm912/artha-yantra-2/pull/611),
    `329e9d71`): the screener computes weighted trailing-return RS (0.4/0.2/0.2/0.2, same math as
    Minervini) over the full scanned universe, percentile-ranks 0..100, and the funnel now gates + orders
    by it (`artha.manas-arora.funnel-rs-min`, default **70**). §4.10 "the single biggest edge a filter
    adds." Live-verified: 2,229 scanned, 0 nulls, **96 passers ≥70**.
  - **F2 — §3.4 multi-lot pyramiding (add-to-winner)** ([#612](https://github.com/prashantm912/artha-yantra-2/pull/612),
    `32b717c6`; **armed live 2026-07-07**): a held winner takes an **averaged** add-lot (owner-picked over
    subaccount separate-lots — cash-equivalent under §3.5.D close-together, no shared-surface migration) on
    a fresh **+6%**-since-last-lot pivot, up to 3 lots, within a ≤6% book open-risk cap; the pyramid closes
    all lots together off the oldest lot's governing stop. Flag `artha.manas-arora.pyramid.enabled`
    (default OFF; arm +6% aligned to the backtest `PYRAMID_ARM_PCT`, un-arm = `.env` flip + redeploy).
    2 adversarial reviewers clean; backtest evidence MIXED → built for doctrine faithfulness + forward
    paper, not a proven edge. Applied-findings record in `docs/strategies/swing-backtest-latest-2026-07-06.md`
    ([#613](https://github.com/prashantm912/artha-yantra-2/pull/613)).
- **H4 canonical Chandelier + pyramid-disarm — MERGED + DEPLOYED LIVE 2026-07-07** ([#628](https://github.com/prashantm912/artha-yantra-2/pull/628),
  `c0d36310`; owner "deploy chandelier + disarm pyramid on both live and back"): the canonical Chandelier
  (highest-high − 2×rolling-ATR(20), +9% arm, breakeven floor, close exit) is now the operative live-paper
  Manas exit in BOTH engines — doctrine-equivalent, not byte-identical (double[] vs BigDecimal). Pyramiding
  **DISARMED** — live `ARTHA_MANAS_ARORA_PYRAMID_ENABLED=false` (it degrades Sharpe 0.96→0.61 under the tight
  Chandelier trail), backtest headline flipped `rs-turnover-pyramid → rs-turnover-nopyramid` (pyramid kept as
  a labeled A/B probe). Live-verified (sha==HEAD, pyramid off in-container, Manas re-published). Doc:
  `docs/strategies/manas-h4-chandelier-backtest-2026-07-07.md`.
- **OPEN (owner "add to pending, take it later") — FIFO vs RS-priority slot admission for the RS-gated funnel.**
  The H4 compare's headline "45 vs 24" was FIFO-**gross** vs RS-priority-**net** (confounded). Like-for-like on
  `rs-turnover` (our live config, H4 run 2026-07-07): FIFO-gross **45.0** > RS-priority-gross **32.1** at equal
  DD (~50%) and Sharpe (~0.96), turnover ~equal (1270 vs 1322 trades) → inferred **FIFO-net ~34–37% >
  RS-priority-net 23.8%**. RS-priority IS a real edge (it crushes FIFO in the non-gated `technical`/`turnover`
  variants, 53 vs 30 gross) but is **redundant with the funnel's RS-rank gate** (F1) — re-sorting freed slots
  by RS just concentrates into the top-RS names for no extra selection edge. **To decide (both cheap,
  reversible, no live change):** (a) add a `portfolioFifoNet` accounting to `ManasAroraBacktestService` +
  re-run → a direct net-vs-net read (clean, analytics-only, outside the parity firewall; the FIFO-net figure
  above is inferred, not measured); (b) measure how often the live 20:05 batch actually exceeds the 7-slot
  cap (if rare, the whole question is low-impact live). If confirmed, move live admission to FIFO within the
  gated pool (or raise `max_positions`) — a HOLD-tier live-admission change. Caveat: literal "FIFO" doesn't
  map to a same-day batch (entries fire together at EOD), so the real live lever is "don't pile into top-RS on
  an over-subscribed day / raise the cap."
- **No buildable code left otherwise;** the one open perf follow-up is the audit LOW "serial/N+1 backtest
  reads" (`ManasAroraBacktestService.readSeries`).

---

## 8. Cross-doc remaining-items sweep (2026-07-09)

Full sweep of every active planning + audit doc (4 parallel readers). ~150 raw open items deduped into **6 threads**:
(1) **the live-paper data month** — Minervini + Manas §0.5-#12 reliability sign-off (30–50 fwd trades, +expectancy,
~2:1, 45–55% hit) + E9 scalper exit-band tune + F1 acceptance + relative-vol-floor k=1.5 judge (all need ~1 month of
UNBROKEN forward paper — see the §1 batch-liveness row); (2) **always-on host** (appears ×4: F5 / always-on-host-brief /
master §18.3 / DEFERRED systematic-scalp prereq — ONE owner call ~₹35–50k); (3) **owner flag-flips** (F9
`ARTHA_PAPER_RISK_ENABLED`, F7 `ARTHA_GRADUATION_PROMOTION_ENABLED`, Dow `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED`,
per-strategy notif toggles, Minervini/Manas low-cap gate arm); (4) **swing exit-parity HOLD batch** (task #128 —
M2/M3/M4/M6/M7/M8/M9/M10/M11/M13/M14/M27, owner review); (5) **long-only / semi-auto boundary** (WON'T-DO until owner
reverses — live-order arming, SPAN sell-legs, OpenAlgo gateway arm, full-auto exec, Upstox cutover); (6) **the new
batch-liveness gap** (§1 row). Below = items NOT already enumerated elsewhere in this ledger.

### 8a. Open 2026-07-05 full-audit findings (`docs/audits/2026-07-05-full-codebase-audit.md` §12; fix log §13)
- **HIGH (2):** **H6** screener reads CA-UNADJUSTED bhavcopy (splits/bonuses poison SMA/52wk/RS/VCP ~1yr/event vs the
  adjusted engine plane; data-fidelity, owner call) · **H8** cheat-3c is a synthetic proxy mislabelled as the doc's true
  cheat setup (doctrine, owner call).
- **MEDIUM (27 open of 40):** ~~HELD-unmerged pending owner sign-off (#591)~~ **#591 CLOSED unmerged 2026-07-06, superseded by MERGED #607** — **M12** RS-tie
  determinism / **M35** liquidity depth 25×→50× / **M39** VCP depth+ceiling (with `min-base-weeks=0`) all LANDED via #607 (correction: 2026-07-10 round-2 queue audit) · setup-doctrine owner-call:
  **M36** 50d-trail armed day-1 / **M37** PowerPlay depth-duration caps / **M38** PrimaryBase 52wk-breakout mislabel /
  **M40** Manas open-risk cap · exit-parity HOLD batch (task #128): **M2 M3 M4 M6 M7 M8 M9 M10 M11 M13 M14 M27** · other:
  **M1** margin-heat basket-blind / **M16** book default 'manual' fail-open / **M17 M18** FE surfaces (~~M20 DONE #649~~,
  §8e) / **M28** zero e2e for new pages / ~~**M31** ~80% fork debt~~ **DONE [#655](https://github.com/prashantm912/artha-yantra-2/pull/655)
  §8g — SwingDoctrine port**.

### 8g. M31 swing fork consolidation — SHIPPED + DEPLOYED + LIVE-VERIFIED (2026-07-10)
The audit-M31 "~80% Minervini↔Manas fork" (7 duplicated file-pairs) collapsed into ONE `SwingBatchEngine` driven by a
`SwingDoctrine` port — [`#655`](https://github.com/prashantm912/artha-yantra-2/pull/655), `9bae2161`. Grilled via
`/improve-codebase-architecture` (Candidate 01, 5 decisions); design of record
[`2026-07-10-swing-doctrine-port-build-spec.md`](archive/2026-07-10-swing-doctrine-port-build-spec.md), domain glossary
[`CONTEXT.md`](../../../CONTEXT.md) (new). Multi-lot-native engine; Minervini = the degenerate single-lot case
(`PyramidPolicy.NONE`). Deleted 2 engines + 2 sell-decision services + 2 recorders. **Parity: byte-identical** — frozen
evaluators untouched (goldens safe), 24 unit tests + exact-string detail-JSON guards, adversarial timescale-domain review
PASS (no P&L/side-channel divergence). Contract recaptured (`SwingSellReport`/`SwingRun`). DEPLOYED + live-verified (both
`/sell-decisions` return the unified shape on real holdings, correct per-family trail). The entry/exit batch-firing verify
is inherently the next 20:00/20:05 IST batch (dead-man's-switch + P0-4 canary watch it). This was an owner-directed
mid-reliability-month deploy (parity-neutral + characterization-proven) — overriding the "hold — watch" posture for this one.
- **LOW (~28, none started):** cosmetic/test/long-tail; the only one with teeth = serial/N+1 backtest reads
  (`ManasAroraBacktestService.readSeries`, ~1,800 round-trips, ~40 min under a concurrent pg_dump).

### 8b. Open 2026-07-06 UI/data-correctness audit (`docs/audits/2026-07-06-ui-data-correctness/`, 21 items)
- **MED:** **D1** participant-OI keeps the synthetic `TOTAL` row in the group list AND the %-denominator → every
  Long%/Short% HALVED · **D2** `/strategies` sends no limit → server caps 50 of 73, hides a published+enabled name
  (list 44 vs graduation 45) · **AC-1** dated futures (`NIFTY26JULFUT`) un-findable in broad instrument search (CONT +
  options flood past the result limit).
- **Cross-cutting root:** `latestMapped` cross-date staleness — `ROW_NUMBER … ORDER BY trade_date DESC` spans dates, so
  357 EQ names resolve to a pre-07-03 "latest" under one "as of 07-03" badge (SectorStats/Heatmap/IndexContribution/
  EquityReturns) — one `WHERE trade_date = max()` fix.
- **Data action:** re-fetch the partial 2026-07-02 bhavcopy (181 EQ/BE vs ~2670; poisons Equity-Returns r1d + delivery).
- Rest = cosmetic (D3 sector staleness, D4 signals-strategy-UUID, D5 mojibake, D6 CAGR-0.00%, D7 drawdown-downsample) +
  LOW visual.

### 8c. Register Phase-1 recon leads (`archive/2026-07-02-...-findings-register.md` §9)
Main fix queue CLOSED (#500–507). Verified 2026-07-09: **24 still-open, 0 fixed-since, 3 moot** (most are accepted
single-owner tradeoffs). **Ops-hardening batch SHIPPED 2026-07-09/10** (owner "go with the ops-hardening batch"):
- **§9-14 fee-drift canary** — [`#642`](https://github.com/prashantm912/artha-yantra-2/pull/642): `FeeConstantsDriftTest`
  pins the 18 statutory-fee constants; a silent rate drift now fails loudly (test-only). MERGED.
- **§9-6/§9-7 optimizer error-swallowing + YAML-precedence** — already SHIPPED 2026-07-09 ([`#637`](https://github.com/prashantm912/artha-yantra-2/pull/637)).
- **§9-1 optimizer + margin OpenAPI contract diff-gate** — [`#643`](https://github.com/prashantm912/artha-yantra-2/pull/643):
  gates the version-STABLE API surface (method/path, params, response codes) via `contracts/<svc>.api-surface.json` — NOT
  the raw `app.openapi()` (its pydantic serialization churns across Python/fastapi versions → false-fails CI 3.12 vs dev
  3.14). MERGED.
- **§9-23 contract-canary no-token silent skip** — [`#644`](https://github.com/prashantm912/artha-yantra-2/pull/644),
  `c0943239`: `maybeRunDaily` now checks the token BEFORE reserving the daily marker, so a pre-login trigger defers
  (retries once the token exists) instead of burning the day's canary. MERGED + market-data redeployed.
- **§9-22 SystemStatus market-data-UP-forever (no freshness bound)** — SHIPPED + DEPLOYED
  [`#646`](https://github.com/prashantm912/artha-yantra-2/pull/646), `8a0e72d8`: during market hours (phase OPEN) the
  rollup now requires a fresh `ticks:last-at` heartbeat (≤5 min) or market-data reads DEGRADED (a lingering
  `kite:session:status` key survived a crash → dead feed read UP forever); quiet off-hours; UNKNOWN when the key is
  absent. Extracted a pure `marketDataStatus()` helper so it's unit-tested without the wall-clock (no Clock injection
  after all). `overall` stays kite-driven; still Redis-key-only (no REST fan-out). The register's OTHER half —
  enumerating backtest/strategy-signal/optimizer in `services[]` — is deferred (the rollup is Redis-key-only by design →
  needs a per-service heartbeat-key protocol). **Ops-hardening batch now FULLY shipped (§9-1/§9-14/§9-22/§9-23 + §9-6/§9-7).**

Remaining survivors = accepted single-owner tradeoffs (`ay reset-db -v` blast radius, plaintext broker creds in
`deploy/openalgo/.env`, host-published dev ports, PHC `$$`-escaping) + a few low robustness gaps (dual symbol-grammar
candle reads #214-class, calendar coverage-from-presence, Black76 degenerate-input guards). **VERIFY the cited lines
before actioning** (frozen 2026-07-02; the 3 moot ones no longer apply).

### 8d. Autonomously-startable batch — OUTCOMES (2026-07-09)
- **D2 strategies 50-cap — ALREADY FIXED** (verify-first win): `frontend-react/src/api/strategies.ts:74` already sets
  `limit=200` with the exact D2 citation (intervening commit; audit doc just wasn't struck). No change needed.
- **D1 participant-OI TOTAL — ALREADY FIXED**: `frontend-react/src/api/participantOiFold.ts:82` already excludes the
  synthetic `TOTAL` client type (group list + %-denominator) with the exact D1 citation. No change needed.
- **Register §9 Phase-1 leads — VERIFIED** (background agent): 24 still-open, **0 fixed-since**, 3 moot. Highest-value
  unaddressed: #14 fee-drift canary · #1 optimizer/margin contracts never diff-gated · #22/#23 single-pane health +
  contract-canary blind spots. Rest = accepted single-owner tradeoffs.
- **Optimizer §9-6 + §9-7 — SHIPPED + DEPLOYED LIVE** ([#637](https://github.com/prashantm912/artha-yantra-2/pull/637),
  `9402ce37`): log swallowed sweep failures (`_LOG.exception`) + warn on ignored YAML sweep config
  (`_yaml_precedence_warnings`, pure/unit-tested). Log/validation-only, no behaviour change; 70 tests + ruff green;
  optimizer-service rebuilt + redeployed healthy on `:8084`.
- **`latestMapped` staleness — SHIPPED + DEPLOYED LIVE 2026-07-09** (owner picked **"Drop"**): [`#639`](https://github.com/prashantm912/artha-yantra-2/pull/639),
  `a5539569`. `EquitySectorService.latestMapped()` + `EquityIndexContributionService.latestChange()` rank WITHIN
  `max(trade_date)`; `EquityReturnsService` keeps recency-ranked window bases but a `HAVING` drops any symbol whose LTP
  row isn't the max session. Drops stale/delisted names from SectorStats/Heatmap/IndexContribution/EquityReturns so
  nothing shows a stale close under the fresh "as of" badge. timescale-domain review PASS (non-correlated InitPlan — the
  max-subquery is index/chunk-metadata-served, actually *faster*; IST/DATE clean; IT deterministic). Shared-DB ITs
  reworked to today-seed + collision-free symbols (Testcontainers-verified). Live-verified: `/sector-stats` `asOf: 2026-07-09`.
- **N+1 backtest reads — HOLD/next**: parity-adjacent (touches owner-facing CAGR via `ManasAroraBacktestService`); needs
  a strict before/after equality re-run, done carefully.
- **07-02 bhavcopy re-fetch — DEFERRED (needs a trigger)**: still partial (167/~2380 EQ), but the reconcile SKIPS partial
  dates (anti-join on DISTINCT `trade_date` — 07-02 is present, just incomplete), so no clean single-date re-fetch path
  exists. Needs a targeted re-fetch endpoint (small build) or a delete-then-reconcile (mutating). Flagged, not run.

### 8e. "do 1 and 2" batch — OUTCOMES (2026-07-10)
Owner picked the two remaining clean-autonomous items off the "what next" fork (option 3 = the gated levers below).
- **§9-8/§9-9 optimizer `/optimizations/run` request-dict validation — SHIPPED + MERGED** ([`#648`](https://github.com/prashantm912/artha-yantra-2/pull/648)):
  the sweep-submit path trusted the raw dict, so bad input was an opaque 500 or a silently-empty leaderboard. Now: a
  non-numeric `maxTrials`/`seed`/`earlyStopping` → clean 400 (was int()-500 deep in the thread-kwargs build); `maxTrials`
  bounded 1..1000 (random/tpe/nsga2 run EXACTLY maxTrials → uncapped = runaway); a parameter missing `path` → 400 (was
  KeyError-500); a non-object `walkForward` → 400; and **§9-9** an objective naming a metric the backtest never emits →
  400 against the rankable-metric allowlist (source of truth: backtest `MetricsCalculator` + `BacktestRunner`, + `oos_fold_mean`)
  instead of NaN-ing every trial. No new route/param/response-code (400s are raised, not declared) → api-surface gate
  unchanged. +11 tests, 87 pass, ruff clean; ci-optimizer green; admin-merged past the two documented 2-core e2e flakes
  (Python-only → cannot touch the FE WS/signals e2e). **Follow-up [`#651`](https://github.com/prashantm912/artha-yantra-2/pull/651)
  (post-deploy live-verify caught it):** #648 ran the validation AFTER `resolve()`, whose `raise_for_status()` on a bad
  `strategyId` leaked a **500** before the numeric checks — moved the request-only knobs (objective/walkForward/maxTrials/
  seed/earlyStopping) ahead of the resolve round-trip so bad input is a fast 400 regardless of strategy validity (+1 test,
  88 pass, all CI incl. e2e green). **DEPLOYED + LIVE-VERIFIED** in-container: `maxTrials:"lots"` → `400 "maxTrials must be
  an integer"`; `objective.metric:"shrape"` → `400 "unknown objective metric 'shrape'; expected one of [...]"`.
- **M20 swing sell-decision FE surface — SHIPPED** ([`#649`](https://github.com/prashantm912/artha-yantra-2/pull/649), CI in flight):
  the daily Minervini + Manas Arora sell-decision triad was curl-only. New `/strategies/swing-sell-decisions` page —
  `api/swing.ts` over the two read-only `/sell-decisions` endpoints (recompute on read), a book toggle + per-holding table
  (setup, entry/current, signed unrealized %, base stop, current trail, "buyable now?" chip, HOLD/SELL verdict badge),
  route + MegaMenu entry. Pure consumption of existing typed endpoints; no backend change. Verify trio green (lint/build
  clean, 261 tests, coverage 51.09%). **DEPLOYED + LIVE-VERIFIED**: frontend-react rebuilt + recreated healthy; the new
  `/strategies/swing-sell-decisions` route is present in the served bundle.
- **Remaining = owner/data-gated only** (option 3): the live-paper reliability month, an always-on host, the flag-flips
  (F9 paper-risk arm / F7 promotion / Dow-factor), and the owner-call audit HIGH/MED doctrine items (H6/H8, the swing
  exit-parity HOLD batch, register accepted single-owner tradeoffs). Nothing further is clean-autonomous without opening
  one of those gates.

### 8f. F9/F7 arm-effect audit + config fix (2026-07-10)
Owner asked "what effect will F9/F7/audit-doctrine have on the live app" → verified against the live code (not memory):
- **F9 (`ARTHA_PAPER_RISK_ENABLED`) arm = a narrow SCALPER circuit-breaker, no number moves.** The flag gates ONE thing —
  the heat-cap BLOCK in `RiskService.entryAllowed` (`RiskService.java:146-156`): on the scalper book only (its `heat_cap_pct`
  is the sole seed with `enabled=true`, V023; swing rows inert), when open SPAN margin ≥60% of book equity, NEW paper entries
  stop being opened + a `risk_audit` TRIP row + 1 ntfy. Never resizes, never touches an open position, changes ZERO displayed
  figures (`advised_lots`/`margin_snapshot` are already stamped, advisory, not even surfaced in the FE). Adds a synchronous
  margin HTTP hop to the scalper entry path. The 10%→3% scalper daily-loss tighten is already live, flag-independent.
- **F7 (`ARTHA_GRADUATION_PROMOTION_ENABLED`) arm = effectively NO live effect.** Armed, the 21:00-IST evaluator only writes a
  `strategy_graduations` MARKER row + 1 push when a strategy clears ≥50 closed paper trades / expectancy>0 / Sharpe≥0.5 / DD≤25%.
  It does NOT republish, NOT flip `published_version_id`/`status`, NOT swap any live config, NOT change what trades — the V024
  migration header pins "NEVER arms … the owner decides any real live change", and no signal/paper code reads the marker table.
  No FE consumes `/graduation/promotions`. Worst case of arming = a spurious marker + a phone alert. (Task title "shadow→champion"
  is a misnomer — it promotes nothing.)
- **Audit HIGH/MED doctrine = code changes, not flags; the only lever that moves owner numbers** → correctly held for review:
  H6 (CA-unadjusted screener → candidate lists shift near corporate actions), H8 (cheat-3c mislabel → which setups fire), the
  swing exit-parity batch #128 / M36–M40 (exit timing+price → swing paper P&L). M1 matters only once F9 armed; M17/M18/M28/M31
  = no owner-facing behavior change.
- **Config bug fixed in passing** ([`#653`](https://github.com/prashantm912/artha-yantra-2/pull/653)): `application.yml:100` read
  env `ARTHA_PAPER_RISK_PER_TRADE_PCT` but compose/.env pass `ARTHA_PAPER_RISK_PER_TRADE_RISK_PCT` (with `RISK`) — so the
  per-trade sizing knob was non-wireable from `.env` (silently fell back to the 1.0 default). Aligned the placeholder to the
  code-bound property `artha.paper.risk.per-trade-risk-pct` (`PaperService.java:143`) + the compose/.env name. Advisory-only
  (advised_lots), so no behaviour change at the default; the knob is now actually settable. Config-only, packages clean.

## 9. Architecture-deepening candidates — `/improve-codebase-architecture` 2026-07-10 (OPEN, take later)

Today's architecture review (`/improve-codebase-architecture`, Ousterhout deep-module lens) surfaced 6 primary
deepening candidates + 4 secondary. **Candidate 01 (SwingDoctrine port) SHIPPED** (§8g, [`#655`](https://github.com/prashantm912/artha-yantra-2/pull/655)).
The rest are recorded here to take later. The report HTML was ephemeral (scratchpad `architecture-review-2026-07-10.html`);
its substance is captured below. **Owner said "add them, take later"** — none is started; each row notes its gate.

### 9a. Primary candidates (remaining 5)
| # | deepening | badge | files (verify before actioning) | gate / how to take it |
|---|---|---|---|---|
| ~~01~~ | ~~SwingDoctrine port — one `SwingBatchEngine`~~ | ~~STRONG · top pick~~ | — | **DONE §8g [#655](https://github.com/prashantm912/artha-yantra-2/pull/655)** |
| **02** | **Adjusted-equity price-plane reader** — screeners + geometry adapt ONE reader instead of each re-deriving the CA-(un)adjusted price plane | STRONG · **fixes H6** | market-data screener + geometry readers | **HOLD-tier, owner-facing** — this IS the audit H6 fix (CA-unadjusted screener poisons SMA/52wk/RS/VCP). Shifts candidate lists near corporate actions → build-and-**review**, not silent merge. Pairs with §8a H6. |
| **03** | **Trial-metrics catalog** — a shared rankable-metric contract across the Java backtest ↔ Python optimizer string seam (no seam today; each side hardcodes metric names) | STRONG | backtest `MetricsCalculator`/`BacktestRunner` ↔ optimizer objective handling | **Parity-neutral, cheap, auto-mergeable.** Partly nicked in §9-8 (`_ALLOWED_OBJECTIVE_METRICS` frozenset added optimizer-side); the deepening = make it ONE catalog both languages consume, not two drifting copies. The clean next-under-hold. |
| **04** | **Premium-exit doctrine** — the exit rule lives in ~4 copies pinned only by the 2-of-5 `exit-equivalence.json` fixture | WORTH EXPLORING | backtest PremiumExitEvaluator ↔ signals ↔ paper ↔ engine bracket chain | **HOLD-tier parity firewall.** Only after WIDENING the exit-equivalence fixture + owner go — this opens the pinned premium-exit semantics (CLAUDE.md #505). Do not touch under the hold. |
| **05** | **Risk governor — 2 interface leaks** | WORTH EXPLORING | `RiskService`, `PaperService.settle()` re-poll | Partly absorbed by 01 (the swing re-poll loop already folded). Remainder = paper/RiskService cleanup; parity-neutral if done right, but M1 (margin-heat basket-blind) + F9-arm interplay → check §8a M1 first. |
| **06** | **Session-rolled reader** — two 1m→N rollups on different grids collapsed into one | WORTH EXPLORING | market-data candle rollup readers | **Ride-along** whenever charts are next touched; not worth a standalone PR. |

### 9b. Secondary friction (fold into the above or defer)
- **`PaperService.settle()` leaky tx boundary** (worth exploring, `PaperService.java:311–345`) — `@Transactional`-only-because-one-caller-isn't + null-price-means-LTP + CAS-close-first leak onto callers. Let the close path own its tx + take an explicit settlement price.
- **`ExitEvaluator` index-convention leak** (worth exploring, `ExitEvaluator.java:288–306`) — every caller reconstructs `entryIndex` (primary-series) + picks the right of two `evaluate` overloads (parity-safety as tribal knowledge). Have `IndicatorBank` vend `positionAt(timestamp)`. **Parity-adjacent — HOLD-tier.**
- **Triplicated index-name canonicalization** (speculative) — same index identity in 3 drifting maps (`UnderlyingRef`/`OpenAlgoExchange`/`expiredUnderlying`), disagreeing coverage → one bidirectional `IndexRef`. (Leave the per-broker symbol/expiry mappers — ADR-0001/0002.)
- **`SweepService` repo-factory dance ×8** (speculative) — the per-thread `jobs=factory(); try/finally close()` lifecycle inlined in 8 methods → a `with self._unit_of_work()` context manager. Plus the OI-reader 6× fallback copy + 3× scheduler alert wrapper (both fold into 01/02).

### 9c. Genuinely deep — DO NOT re-flag (recorded so future reviews leave them alone)
Frozen `EntryEvaluator`/`IndicatorBank` (parity-by-construction, reused by replay + 3 live engines) · `HistoricalOiReader` + `CandleDerivedChainReader` (real 2-adapter seam, ~500L behaviour) · `SignalEngine` reconcile (converges without looping) · `EmissionGuard` SPI + `Books` (the exact port template 01 copied) · `CandleQueryService.read()` interval switch + 3 gateway adapters · `run_sweep`/`optuna_runner` ask/tell loop.

### 9d. Sequencing (report's own recommendation)
**01** ✅ → **03** (metrics catalog — cheap, parity-neutral, clean-under-hold) → **02** (price-plane = the H6 fix — owner-facing, build+review) → **04** (premium doctrine — HOLD-tier, only after widening the exit-equivalence fixture). **06** rides along whenever charts are next touched; **05** remainder is small paper/RiskService cleanup. Against the standing "hold — watch the live-paper month": only **03** is clean-autonomous; **02/04** need owner go, **05/06** are low-priority.
