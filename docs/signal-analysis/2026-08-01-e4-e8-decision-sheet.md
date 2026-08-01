# 2026-08-01 — Owner decision sheet: E4 (`audit-doctrine-holds`) + E8 (`f3-dot-fixes-evidence-gated`)

Research + writing only. No production code touched. Checkout: `origin/main @ 0e0899e8` (worktree
`docs/e4-e8-decision-sheet`). Every material claim is tagged **[computed]** (derived here from a
file/config I read on this checkout), **[sourced]** (quoted or paraphrased from a cited doc/PR),
**[recalled]** (my own memory of the codebase, not re-verified in this pass), or **[assumed]** (an
inference I could not verify). A claim computed from an assumed input is left labelled **[assumed]**.

---

## Read this first — the bottom line

**E8 — CLOSE THE ROW. Nothing is owner-actionable today.** All four evidence-gated pieces the row
names (iv_rank, iv_pair, oi_spurt floor, the volume-floor "k verdict") already resolved — two of
them (iv_pair's redefinition, the k verdict) were settled in the last 24 hours by PRs already
merged to `main`, and the row's own note claiming they are "IN FLIGHT" was stale within hours of
being written. The one open thread (whether to arm `iv-rank-dot` once IV history clears its 60-day
floor) is a **September 2026** question with no evidence yet — correctly not decidable now, not a
gap in this sheet.

**E4 — NOT one decision; four independent doctrine calls plus one latent non-issue, bundled under
one ledger id.** Recommended per sub-item:
1. **H8** (cheat-3c mislabelled) — rename it; cheap, zero behaviour change, closes the audit
   finding without opening a new entry-gate question.
2. **Swing exit-parity HOLD batch** (task #128, 12 MEDIUM findings) — **not a single lever.** The
   real decision is *scoping*: authorize a first HOLD-tier slice now (it is actively biasing the
   forward-paper evidence E1 is accruing) or keep deferring. I recommend scoping a first slice
   (the fixture + the two findings that most directly bias E1's evidence), not the whole batch.
3. **M36** (Minervini 50-day-MA trail armed day-1) and **M37** (Power Play caps) — **not yet
   decidable**; each needs its own small backtest A/B before a call can be made, on the same
   method that settled M39.
4. **M38** (Primary Base mislabel) — bundle a rename into the same cheap PR as H8; the real fix
   (IPO-date data) is a separate, uncosted data-acquisition project, not this decision.
5. **M40** (Manas pyramiding/risk-cap/circuit-breaker vs. the pyramid-variant headline) — **largely
   moot.** Pyramiding + its risk-cap were built and then deliberately disarmed for a documented
   Sharpe reason; the quoted "realistic live" number already matches the disarmed (single-lot)
   state. What's left is a narrow, paper-only circuit-breaker gap — low urgency, cheap to note,
   not worth bundling into this ledger row's next action.
6. **VcpDetector base-week mismeasure** — no action needed; it is correctly latent and
   self-documenting in the code. Revisit only if someone proposes re-arming a week floor.

---

## Part 1 — E8 `f3-dot-fixes-evidence-gated`

### Verdict table

| sub-item | verdict | when/how settled |
|---|---|---|
| iv_rank live semantics (F3.2) | **DONE — gated safe** | #1179, merged 2026-08-01T10:40:40Z |
| iv_pair units + redefinition (F3.3) | **DONE — dead end confirmed, final** | units: #675 (2026-07-10); redefinition: #1170/#1174, merged 2026-08-01 |
| oi_spurt price/OI floor (F3.4) | **DONE — recalibrated + live-verified** | #675 (2026-07-10) → #991/T22 (2026-07-25 owner-approved, closed 2026-07-27) |
| volume-floor mechanism (F3.5) | **DONE — shipped, armed** (pre-existing) | #605, merged 2026-07-06 |
| the "k verdict" (`relativeVolumeMultiplier`) | **DONE — REJECTED, final** | T1, reconfirmed 6× through 2026-07-31, locked by the G11 exit decision |

### 1a. iv_rank live semantics — DONE, dormant until September

**[sourced]** The 2026-07-03 roadmap's F3.2 ask was: "compute rank over the captured window with
an explicit `min_history_days` guard, NEUTRAL below it"
(`docs/superpowers/plans/2026-07-03-10x-value-roadmap.md:96-97`). **[sourced]** That guard already
existed before today's PR — `IvAnalyticsService.HISTORY_FLOOR_DAYS = 60` trading days, and its own
javadoc says "60 keeps the iv_rank dot honest-null until ~September" (quoted in PR #1179's body).
So F3.2's substance was already shipped; what #1179 adds is a **safety catch for the day the floor
clears on its own**: without it, once ~60 days of `marketdata.iv_daily_summary` accrue (live
capture started 2026-06-15), the dot would self-arm on a calendar trigger and silently enter the
composite denominator fleet-wide (18.80 → 19.60) with no owner decision and no deploy.

**[computed]** `ConnectTheDotsScorer` now takes the dot only when `cfg.has("iv-rank-dot")`
(`ScalperConfluenceGate.java:1063`), default-OFF; `ScalperStrategyLoadTest` pins it unarmed across
every seeded scalper. Behaviour today is byte-identical to before the PR (the dot is null on every
live row regardless).

**Not yet decidable, correctly:** whether to arm `iv-rank-dot` is a real future decision, but no
evidence exists to make it — the history floor hasn't cleared. It becomes decidable once ~60
trading days of `iv_daily_summary` have accrued (roughly September 2026, per the PR). **This is not
a gap in E8** — it is a dated future trigger, not a present one.

### 1b. iv_pair units + redefinition — DONE, dead end confirmed

**[sourced]** F3.3's unit fix (0.10 → 0.02 IV-point gap) shipped back on 2026-07-10 via #675
(ledger row E7). That alone did not revive the dot: it kept reading ~0% support because put-call
parity pins the CE/PE 6-strike ATM IV averages to each other by construction (ledger row G13:
non-expiry ground truth over 983 rows, CE-vs-PE gap p50=0.00010, p90=0.00050, max=0.00070 — the
live 0.02 threshold is 28× the observed session maximum).

**[sourced]** The owner approved measuring a redefinition ("redefine default-OFF") in the weekend
pack. Two candidate successor operands were measured against pre-committed kill criteria in
`docs/signal-analysis/2026-08-01-iv-pair-skew-ground-truth.md` (#1170, merged
2026-08-01T08:45:59Z):
- **R1 (wing-skew level)** breaks the parity pin (median |R1| 15.0× the current operand) but fails
  on constancy — 95.7% single-signed, zero sign flips in 15 of 19 NIFTY sessions, and **no
  threshold at which both CE and PE sides are alive** (CE support ≤3.1% vs PE 55–94%). Widening
  makes it worse.
- **R2 (residual = R1 minus a trailing baseline)** passed all three pre-committed criteria — and
  so does a pure random walk under the same detrending, because subtracting a trailing median
  mechanically forces a zero-median, sign-symmetric series regardless of input. Re-gated on
  forward outcome (a circular-shift null, correcting an initial naive-shuffle run that would have
  wrongly said BUILD): p=0.056 (NIFTY) / p=0.082 (SENSEX) — not separable from chance.

**[sourced]** Both verdicts: **DO NOT BUILD.** Closed via #1174 (merged 2026-08-01T09:08:54Z),
which also updated the G13 ledger row directly. `iv_pair` stays dead-but-symmetric — it withholds
support from both sides equally, which leans the composite gate slightly *stricter*, the
historically safe direction, so leaving it dead costs nothing net.

### 1c. oi_spurt price/OI floor — DONE, already recalibrated and live-verified

**[sourced]** F3.4 asked for the price floor to move 50 → 5–10 (observed p95). This happened in
two steps, both already complete:
1. **2026-07-10, #675 (ledger E7/P2):** price floor 50 → 8, ground-truthed against 2,104 bars
   (abs p90=10.0).
2. **2026-07-25, T22, owner-approved (ledger row B10/D2+D3), shipped #991:** a further pass found
   the **OI-side** floor (still 50) sat at the *p95 of its own operand* — over 4,118 context rows
   the joint (OI+price) pass rate had decayed to 1.26%, and by 2026-07-23/24 the dot had gone
   **fully dead (0/1,120, 0/1,100 two sessions running)**. The joint recalibration landed at
   `(15, 3)` — 15.5% predicted joint pass rate.

**[computed]** Current live defaults, read directly: `ScalperOiProps.java:67-68` —
`DEFAULT_SPURT_OI_PCT = new BigDecimal("15")`, `DEFAULT_SPURT_PRICE_PCT = new BigDecimal("3")`. No
`application.yml` override exists, so these ARE the live values.

**[sourced]** T22 closed 2026-07-27 and was reconfirmed on subsequent non-expiry sessions: 9.9%
support on 07-27 (matching the 15.5% joint prediction adjusted for the quadrant gate), 8.5% on
07-29, 4.9% on 07-30 (`docs/signal-analysis/2026-07-27-session-findings.md` §3.2,
`2026-07-29-session-findings.md`, `2026-07-30-session-findings.md`). The dot is a live, selective,
non-dead filter today — this is the strongest of the four sub-items: not just "fixed" but
**fixed and verified on real forward data across four sessions**.

### 1d. the "k verdict" — DONE, REJECTED and now final

**[recalled/sourced]** F3.5's mechanism (a rolling-median relative-volume floor,
`multiplier × median(prior N bars)`, replacing the fixed 125,000-contract floor) shipped 2026-07-06
via #605 and is armed fleet-wide (38/38 scalpers per the 2026-07-31 session findings' registry
check, `docs/signal-analysis/2026-07-31-session-findings.md:184`, 0 flat floors). **[computed]**
Current live default: `ScalperOiProps.java:103`, `DEFAULT_RELATIVE_VOLUME_MULTIPLIER = 1.5`; window
20 bars, minBars 10 (`:104-105`).

**[sourced]** The remaining question — is k=1.5 the right value, specifically should it be *lowered*
to admit more entries — is exactly **T1** in the tune-candidate table, and it has now failed on its
**sixth consecutive forward measurement**:
- 2026-07-27 (first correctly-calibrated observation): the floor looked *expensive* (+235.1 pts if
  removed) — but this was one session and the tally was noted as provisional.
- 2026-07-29: reversed hard on a larger sample — the volume-floor-vetoed set resolves 2W/9L,
  −121.95 pts, and **every one of six rails' would-have-fired sets loses money** (union of 41
  distinct legs: 5W/36L, −538.50 pts). **REJECTED.**
- 2026-07-31 (the chop-day session): sole-blocker legs 1W/2L, −2.40 pts under the stop-exit model —
  "the T1 knob's sixth consecutive measurement that fails to pay"
  (`docs/signal-analysis/2026-07-31-session-findings.md:349-351`).

**[sourced]** All four of T1/T7/G13/G10's rejections were explicitly conditional on the 30-minute
`time_stop` staying in place (§ below, "the prior, applied"). **G11** — the ledger row asking
whether to keep that stop — was **DECIDED 2026-07-31: KEEP the 30-minute `time_stop`** (three
independent reads of the first expiry-free chop-day tape on file: the 22-leg counterfactual, stop
+196.10 pts vs. hold +35.05; matched champion-shadow events, +95.40 vs +32.00; live paper's first
green day, both winners closed by the time stop). Because the condition those four rejections were
suspended on has now resolved in the direction that *preserves* them, the ledger explicitly marks
T1/T7/G13/G10 **"FINAL rather than stop-conditional"**
(`docs/superpowers/plans/2026-07-02-remaining-items.md`, G11 row). **The k verdict is: keep
k=1.5. Do not lower it. This is settled, not pending.**

### The prior, applied

The task brief's standing prior — every measured loosening of the scalper entry gate has lost
money (T1, T7, G13, G10) — is **exactly what this sub-item's own history demonstrates**, and this
sheet does not need to add a new test to confirm it: T1 *is* one of the four data points the prior
is built from, and it is now the fourth data point to survive a fresh forward session (07-31)
without flipping. Converting to legs and P&L was already done by the runbook process itself before
this sheet was written (2W/9L / −121.95 pts on 07-29; 1W/2L / −2.40 pts on 07-31); costs were not
separately subtracted here because the counterfactual sets were already net-losing gross. No new
recommendation is warranted beyond: **do not revisit k without a new time-stop change**, per the
ledger's own standing rule that any exit-doctrine change requires re-running all four.

### Corrected ledger row

The current E8 row (`docs/superpowers/plans/2026-07-02-remaining-items.md:547`) reads, in its
final clause:

> **UPDATE 2026-08-01: the iv_rank + iv_pair halves are now IN FLIGHT via the G13 weekend decision
> (redefine/source-fix behind default-OFF flags, Fable plan running) — only oi_spurt floor + the k
> verdict remain evidence-gated here**

This was already stale by the time it would be read: #1170 and #1174 (both merged 2026-08-01,
closing G13's redefinition attempt as DO-NOT-BUILD) and #1179 (merged 2026-08-01, gating iv_rank
default-OFF) landed within hours, and — per §1c/§1d above — the oi_spurt floor and the k verdict
were **already closed days earlier** (2026-07-27 and 2026-07-31 respectively), not "remaining."
Replacement text (status column changes `OWNER` → `DONE`):

```
| E8 | `f3-dot-fixes-evidence-gated` | F3.2–.4 dot fixes (iv_rank live semantics, iv_pair units, oi_spurt floor) — built on E7/E1 evidence. F3.5 volume-floor mechanism already shipped+armed #605. **CORRECTION 2026-08-01 (the same-day "IN FLIGHT via G13" note above was stale within hours): all four pieces are CLOSED, none in flight.** iv_rank gated default-OFF behind `iv-rank-dot` (#1179) — the F3.2 min-history guard already existed pre-PR; the only remaining decision (arm it) needs IV-history evidence that does not exist before the 60-day floor clears (~Sept 2026) and is correctly NOT decidable today. iv_pair: units fixed 2026-07-10 (#675); both G13 redefinition successors (R1 wing-skew, R2 residual) measured DO-NOT-BUILD (#1170/#1174, 2026-08-01) — dead-but-symmetric is final. oi_spurt floor: 50→8 via #675 (2026-07-10), then to (15,3) via T22 (#991, owner-approved 2026-07-25) — CLOSED 2026-07-27, reconfirmed live 07-29 (8.5%) + 07-30 (4.9%). The k verdict (`relativeVolumeMultiplier`=1.5): T1's loosening to 1.2 REJECTED on its 6th consecutive forward session and made FINAL by the 2026-07-31 G11 decision to keep the 30-minute `time_stop`. Full evidence: `docs/signal-analysis/2026-08-01-e4-e8-decision-sheet.md`. | 10x roadmap F3; decision sheet above | **DONE — nothing owner-actionable today; re-open only if Sept IV-history data prompts an iv-rank-dot arming decision** |
```

---

## Part 2 — E4 `audit-doctrine-holds`

### Verdict table

| sub-item | decidable today? | recommendation |
|---|---|---|
| H8 — cheat-3c mislabelled | yes | rename only; cheap, no behaviour change |
| Swing exit-parity HOLD batch (#128, 12 findings) | scoping decision only | authorize a first slice now (fixture + the two items biasing E1); defer the rest |
| M36 — 50d-MA trail armed day-1 | **no — needs a backtest A/B first** | commission the A/B, decide after |
| M37 — Power Play caps + thrust adjacency | **no — needs the M39-style measurement first** | commission it before touching any threshold |
| M38 — Primary Base mislabel | yes (rename); no (real fix) | rename now; real fix uncosted, separate project |
| M40 — Manas pyramiding/risk-cap/circuit-breaker | mostly moot | note and mostly close; leave one narrow paper-only gap |
| VcpDetector base-week mismeasure (latent) | yes | no action; correctly dormant |

**Housekeeping note, [sourced]:** the E4 row's own text says "#591 is CLOSED — superseded by
MERGED #607 (H6 = B4)". This is correct and needs no correction: #607
(merged 2026-07-06T13:49:19Z) kept M35 (liquidity 50×) and M12 (RS tie-break) from #591 unchanged
and replaced only #591's M39 duration-floor default (3 → 0, the guillotine fix); H6 (screener
CA-adjustment) shipped separately as ledger row B4 via #757 (merged 2026-07-12). All three —
M12, M35, M39's floor-disable, H6 — are done and are correctly absent from the remaining scope
below.

### 2a. H8 — cheat-3c is a synthetic proxy, not the doc's Low Cheat

**[sourced]** The doctrine (`strategy-documents/mark-minervini-operative/...Consolidated_Strategy.md`)
is explicit that the Trend-Template's criterion #5 (price above the 50-day MA) is **waived** for a
"Low Cheat" entry — by definition a Low Cheat happens in the lower third of the base, which sits
below the 50-day MA (lines 1002, 1386, 648-652 in that doc; the "5-Month Wide" screener exists
specifically to hunt this).

**[computed]** The current implementation cannot express this. `MinerviniGates.java:42` computes
`g[4] = close > sma50` unconditionally as one of the 8 Trend-Template gates, and
`MinerviniFunnelService` only ever joins geometry (VCP setups) onto rows that already passed the
full 8-gate screen (`MinerviniFunnelService.java:16-17`: "joining the persisted screen
(`minervini_screen_results`: the 8-gate pass + RS-rank) with the Phase-5 base geometry"). So a true
Low Cheat entry — by construction priced below the 50-day MA — can never reach the funnel at all.
What is actually labelled `cheat_3c` today is `VcpDetector`'s `cheatPivot`
(`VcpDetector.java:130-135`): a deterministic point a configurable fraction up the *final*
contraction (trough → pivot), i.e. an earlier trigger *within* the standard VCP breakout, which by
construction sits inside the already-passing 8-gate/above-50-day population. It is a real,
well-defined, useful proxy — it is just not the doctrine's Low Cheat, and calling it that overstates
what it captures.

**Options:**
- **(a) Rename only** — e.g. "vcp-early-pivot" or similar — zero behaviour change, purely a label
  fix, closes the audit's actual complaint (mislabelling, not underperformance).
- **(b) Build the real waiver** — add an explicit criterion-5 exception for a genuine
  lower-third-of-base condition. This changes which setups fire (a new, previously-unreachable
  population enters the funnel) — HOLD-tier by the same standard as M39, and per that precedent
  must not be applied on doctrine-literal thresholds without its own deploy-free geometry query +
  backtest A/B first.

**Recommendation: (a) now.** There is no evidence the current proxy is a losing setup — the
swing-backtest doc shows cheat-3c at +4.90%/trade per-setup expectancy alongside vcp (+5.10%) and
power-play (+7.94%), all positive (`docs/strategies/swing-backtest-latest-2026-07-06.md:20`)
**[sourced]** — so there is no urgency to chase the "real" edge, and (b) is a fresh feature
decision, not a bug fix; I recommend not bundling it into this ledger row.

**Note on the prior:** this is the Minervini swing screener, not the scalper Connect-the-Dots gate
the task's stated prior (T1/T7/G13/G10) is about — that prior has no direct evidence here. The
*structural* lesson from M39 (a doc-literal threshold, applied without measurement, deleted 99% of
a profitable trade population) is the locally relevant precedent, and it argues for treating
option (b) with the same caution, not for treating this as pre-decided by the scalper prior.

### 2b. Swing exit-parity HOLD batch (task #128) — a scoping decision, not a lever

**[sourced]** This bundles 12 MEDIUM findings from the 2026-07-05 audit: M2 (reconcile never
retries transient load failures; a mid-session market-data restart unloads ALL strategies), M3
(exit-commit→publish window can strand an OPEN position with an EXPIRED anchor), M4 (swing book is
MTM-blind — non-ticking equities mark at cost), M6 (live engines evaluate exits on the entry bar;
both backtests never do), M7 (**no swing exit-equivalence fixture** — the premium-exit pattern from
`exit-equivalence.json`/#505 was never replicated for swing, and the sides already diverge), M8
(Manas live volume gate is 20-day vs. the backtest's 50-day — different trade populations), M9
(RS-rank universe/convention differs live vs. backtest — filtered-bhavcopy ordinal vs.
unfiltered-survivor midpoint), M10 (deep-sim cost model never compounds order size), M11
(survivorship + universe mismatch, disclosed but present in every quoted number), M13 (holiday/
stale-bar runs have no MarketCalendar guard), M14 (no daily-bar freshness assertion), M27 (deep
sims have no frozen-output golden — published CAGR numbers are silently mutable under refactor).

**[computed]** Nothing in this batch has been touched since the audit — a repo-wide grep for `M2`
through `M14`/`M27` in this context finds only the audit doc and the ledger's own §8a summary of
it, zero PRs or later docs. **[computed]** The audit's file:line citations for this batch
(`MinerviniSwingEngine.java`, `ManasAroraSwingEngine.java`, and various `ExitEvaluator.java` lines)
are now **stale paths**: the 2026-07-10 SwingDoctrine consolidation (#655) deleted both named
engine classes in favour of one `SwingBatchEngine` + `SwingDoctrine` port (confirmed —
`MinerviniSwingEngine.java`/`ManasAroraSwingEngine.java` do not exist on this checkout;
`services/strategy-signal-service/.../swing/SwingBatchEngine.java` and `SwingDoctrine.java` do).
#655's own PR claims "frozen evaluators untouched" and "byte-identical" parity, so the *behaviours*
these 12 findings describe almost certainly still exist unchanged — but **any builder must
re-locate every citation against current HEAD before scoping work**, not trust the 2026-07-05 line
numbers.

**Why this is not decidable as a single yes/no:** it is 12 unrelated findings (reliability bugs,
a missing test fixture, two live-vs-backtest data-convention mismatches, a cost-model gap, and a
missing golden) wearing one task id. There is no single number or flag the owner can approve; the
real decision is **how much of this to schedule, and in what order.**

**Why it matters now, not just eventually:** the audit's own framing is that these findings
"corrupt the forward-paper evidence the owner's go-live decisions depend on"
(`docs/audits/2026-07-05-full-codebase-audit.md:19-20`). **[sourced]** E1
(`forward-paper-reliability-month`) is *actively accruing right now* and explicitly includes "swing
§0.5 #12 sign-off" as part of what it needs
(`docs/superpowers/plans/2026-07-02-remaining-items.md:540`). M6 (exits evaluated differently on
the entry bar) and M9 (RS-rank convention mismatch) directly bias the comparability of the swing
paper data E1 is collecting *today*; M7 (no fixture) means nobody would catch a live/backtest
divergence introduced by any exit-doctrine change in the meantime.

**Recommendation:** I recommend the owner decide on **scoping**, not on the whole batch at once.
A defensible first slice: build the swing exit-equivalence fixture (M7) and use it to characterize
and align M6 + M9 — the two items that bias the evidence E1 depends on *while it is being
collected*. Leave M2/M3/M4/M8/M10/M11/M13/M14/M27 (mostly operational-robustness, cost-model, and
universe-disclosure items, none of which corrupt the accruing evidence the same way) for a later
pass. **I am not costing this slice** — that needs its own scoping/planning pass (e.g. a Fable
plan) before it is buildable; this sheet's job is to say the batch needs a scoping decision, not to
pre-scope it. The alternative — keep deferring the whole batch — is a legitimate owner choice too,
given competing priorities (E1 itself, E2 always-on host); I am flagging the cost of that choice
(E1's swing evidence keeps accruing under an unbounded divergence), not overriding it.

### 2c. M36 — Minervini 50-day-MA trail armed from day 1 — not yet decidable

**[sourced]** The audit's finding: the live 50-day-MA trailing exit arms from the position's first
day; the doctrine's intent is that it should only begin governing once the MA has caught up to
(reached) the entry level — arming immediately biases which trades get stopped, and biases
reliability statistics toward more, earlier exits than the doctrine would produce.

**[recalled]** I did not re-locate the exact current arm-condition inside `SwingDoctrine` /
`MinerviniSellDecisionService` on this checkout — the file:line the audit cites
(`ExitEvaluator.java:458-487` for the *comparable* Manas ATR trail, not this exact Minervini path)
predates the #655 consolidation, and I did not trace the post-consolidation equivalent in this
pass. This is a real gap in this sheet (see Open Doubts).

**Why not decidable today:** whether "arm day-1" helps or hurts P&L is not obvious in either
direction — it could cut losers early (good) or cut winners before the MA doctrine intends (bad,
the audit's stated concern) — and no measurement isolating this one knob exists yet.

**Recommendation:** commission a two-arm backtest (arm-day-1 vs. arm-only-once-MA-reaches-entry)
using the existing swing backtest harness before deciding anything — the same evidence-first
discipline that settled M39 (a deploy-free geometry check + a deep A/B), scaled down since this is
a single boolean condition, not a threshold sweep. Until that A/B exists, there is nothing to
decide; manufacturing a recommendation here would be exactly the "pick a number without the
ground-truth discipline" mistake the ledger repeatedly warns against (cf. G15's note on T22/G13).

### 2d. M37 — Power Play missing depth/duration caps, thrust adjacency-free — not yet decidable

**[sourced]** The audit's finding: unlike VCP (which now has a depth cap and a duration ceiling,
per M39/#607), Power Play has no analogous caps, and its "thrust" precondition (a prior ≈+100%
move) can be found anywhere before the base with no requirement that it be *recent* relative to the
base — i.e., stale, adjacency-free evidence of a blast-off can still qualify a setup today.

**[computed]** Current code confirms Power Play's thrust check has no adjacency window on the
*base* side: `VcpDetector.thrust()` scans the whole `[0, baseStartIdx)` range for a qualifying move
within `thrustWindow` (default 40) sessions *of that move itself*, but nothing bounds how long ago
`baseStartIdx` may be relative to *today* — a thrust from years before the current base still
counts (`VcpDetector.java:151-166`).

**Why this is the same class of risk as M39, and why it is not yet decidable:** M39 is the direct,
measured precedent in this exact codebase for what happens when a doctrine-literal cap is applied
to this detector without evidence — a 3-week duration floor, textually correct per the doc, deleted
**99% of the VCP trade population**, and the deleted trades were disproportionately winners
(`docs/strategies/m39-vcp-caps-backtest-2026-07-06.md`). There is no reason to assume Power Play's
population is more forgiving; there is also no reason to assume it is equally fragile. No
measurement exists either way.

**Recommendation:** before touching any Power Play threshold, run the same two-step method that
settled M39: (1) a deploy-free geometry query — how many of today's live Power Play setups would
survive a candidate cap, and (2) if the answer isn't obviously "~all of them," a deep backtest A/B
comparing caps-off vs. caps-on. This is cheap (the harness and method already exist) relative to
the risk of blind-applying a doc-literal cap to a setup nobody has measured.

### 2e. M38 — Primary Base mislabel — rename now, real fix is a separate, uncosted project

**[sourced]** The audit's finding: "Primary Base" is documented as targeting young post-IPO leaders
forming their first base, but the platform holds no IPO-date data, so the implementation is a
generic 52-week-high breakout wearing the doctrine's name.

**[sourced]** Per the same backtest doc, primary-base is not a losing setup as implemented:
+6.27%/trade per-setup expectancy on the 2026-07-06 run, ahead of vcp (+5.10%) and behind cheat-3c
(+4.90%) and power-play (+7.94%) (`docs/strategies/swing-backtest-latest-2026-07-06.md:20`) — so,
as with H8, there is no performance urgency, only a labelling-accuracy one.

**Options:** (a) rename to something honest about what it actually is (a generic 52-week breakout,
not an IPO/first-base setup) — cheap, zero behaviour change; (b) acquire real IPO-date data and
rebuild the setup to genuinely gate on young-leader status — a data-acquisition project with an
unknown vendor/cost, not something this sheet can price.

**Recommendation:** (a) now, bundled with H8's rename into one small, safe, docs/labelling-only PR
(both are "the name overpromises, the mechanism underneath is fine and profitable" findings, and
fixing both together is one small, low-risk change rather than two). Leave (b) as a distinct,
owner-initiated project if real IPO/first-base fidelity is ever wanted — it is not decidable today
because no data source or cost estimate exists.

### 2f. M40 — Manas pyramiding/risk-cap/circuit-breaker — largely moot since the audit

**[recalled/sourced]** The audit's finding (2026-07-05): Manas live had none of pyramiding, an
open-risk cap, or a circuit breaker, while a quoted "pyramid-variant" backtest headline number
implicitly assumed all three. **Two days later this changed:** F2 (multi-lot pyramiding, plus a
tied `pyramid.max-portfolio-risk-pct` open-risk cap, default 6%) shipped via #612
(2026-07-07T-ish) and was armed the same day — then **disarmed that same day** under #628, because
the H4 canonical-Chandelier exit fix (a separate, already-shipped audit item) dragged the pyramid
variant's Sharpe from 0.96 to 0.61 (DD 49.9%→60.6%). Single-lot has been the documented "operative
config" ever since; re-arming is a `.env` flag flip + redeploy, no migration.

**[sourced]** Critically, the backtest doc's own "realistic live numbers" section already quotes
the **non-pyramid** `rs-turnover` figure as what to trust for Manas — "~26% CAGR / ~50% DD"
(`docs/strategies/swing-backtest-latest-2026-07-06.md:43`) — meaning the number actually presented
as the live-comparable headline already matches the disarmed, single-lot reality. **[computed]**
The frontend's Manas backtest page (`frontend-react/src/pages/equity/ManasAroraBacktestPage.tsx`)
is an interactive results view over whichever variant was run, not a single hardcoded marketing
number, which further reduces the risk of someone quoting the pyramid figure as if it were live.
So the specific mismatch the audit worried about — a headline number the live engine cannot
reproduce — does not appear to exist today.

**[computed]** What genuinely remains: Manas swing has **no live circuit breaker**, and closing
that gap is not a simple flag flip. `deploy/flyway/strategy/V023__paper_risk_governor.sql:24-31`
seeds a `heat_cap_pct` governor row for every book, but `manas-arora`'s (and `minervini`'s) rows are
seeded `enabled: false` **by design** — the comment states this governor is a SPAN-margin check,
and "cash-equity swing books carry no SPAN margin," so the same mechanism the scalper book uses
(F9) does not even apply as-is to equities. A real Manas circuit breaker would need a different
metric entirely — e.g. % of book capital deployed, or a daily-loss-limit analog to the scalper's 3%
— which is a small design-plus-build task, not a config change.

**Recommendation:** say so and mostly close this row. The headline-mismatch risk that motivated the
finding is gone. The residual circuit-breaker gap is real but narrow, paper-money-only, and low
urgency — I recommend leaving it as a noted future item rather than folding it into this ledger row
as if it were still the audit's original, larger concern.

### 2g. VcpDetector base-week mismeasure — latent, no action needed

**[computed]** Confirmed still live and dormant. `VcpDetector.java:73`,
`@Value("${artha.minervini.vcp.min-base-weeks:0}")`, defaults to 0 (the floor is disabled); no
`application.yml` override exists on this checkout, so 0 is the live value. The constructor's own
comment block (`VcpDetector.java:62-71`) documents the exact trap in place: `baseWeeks =
round(durationDays/5)` (`VcpDetector.java:116`) measures only the trailing tight contraction
(observed avg ~0.7 weeks), not the classical multi-week base the `[3,65]`-week window assumes —
re-enabling the floor without first fixing this measurement would reproduce M39's ~99% trade
annihilation. This is the m39 doc's §4 "option 2," explicitly left undone in favour of option 1
(disable the floor), which is what shipped.

**Recommendation:** no action. This is correctly latent, self-documenting in the code comment, and
carries zero live effect. Revisit only if and when someone proposes re-arming
`artha.minervini.vcp.min-base-weeks` above 0 — at that point, fixing the measurement (aligning
`baseWeeks` to the full base, not just the final contraction) is a precondition, not an
afterthought.

### Process recommendation

E4 bundles a screener doctrine call (H8), a 12-finding engineering batch (#128), four independent
setup-specific doctrine calls (M36–M40, only two of which are H8/M38-adjacent), and one dormant
non-issue, under a single ledger id with a single `OWNER` status. That shape makes it very hard to
ever mark "done" — flipping one sub-item does not change the row's status, so the row silently
becomes stale evidence-holder rather than a decision queue (exactly the effect this sheet has to
untangle for E8 in miniature). I'd suggest splitting E4 into E4a (H8+M38 rename), E4b (#128
scoping), E4c (M36/M37 backtest-A/B track), E4d (M40 — close, note the residual gap) the next time
this ledger is touched, so each can carry its own status instead of one shared `OWNER` label
covering unrelated questions at very different levels of readiness.

---

## Open doubts

1. **M36's current file:line was not re-verified against HEAD.** The audit's citation predates the
   #655 SwingDoctrine consolidation (2026-07-10), which deleted the classes it names
   (`MinerviniSwingEngine.java`/comparable). I did not trace the equivalent logic inside
   `SwingBatchEngine`/`SwingDoctrine` in this pass — I'm relying on #655's own "frozen evaluators
   untouched" claim that behaviour is unchanged, which I did not independently re-derive line by
   line. Before any M36 backtest A/B is commissioned, the current arm-condition should be
   re-located and confirmed.
2. **The #128 batch's "first slice" scope (M7+M6+M9) is my own suggestion, not a costed or
   owner-reviewed plan.** A real cost/timeline needs its own scoping pass (e.g. a Fable plan)
   before the owner should treat it as buildable-as-stated.
3. **M40's frontend spot-check was narrow.** I confirmed the backtest results page is
   variant-interactive rather than a fixed marketing number, and that the strategy doc's own
   "realistic live" figure already matches single-lot. I did not walk every page that might quote a
   Manas CAGR (e.g. the graduation/leaderboard surfaces) to confirm none of them still displays a
   stale pyramid-variant number as if it were current.
4. **No live database or runtime verification was performed for this sheet.** Every "computed"
   claim comes from static reads of code/config/docs on the `origin/main @ 0e0899e8` checkout, not
   from querying the live `artha` database or the running containers. Session-findings figures
   (T22's 8.5%/4.9%, T1's leg counts, G11's chop-day numbers) are **[sourced]** from those docs, not
   independently recomputed here.
5. **iv_pair's "unfixable as a per-bar dot" conclusion is relied on, not re-derived.** I read and
   cite `2026-08-01-iv-pair-skew-ground-truth.md`'s stated methodology and results; I did not
   re-run its SQL or its permutation test against live data myself.
6. **E4/E8 are the only rows this sheet closes or corrects.** Adjacent owner-gated rows visible in
   the same ledger sweep (E3's remaining arm-flags, E10's fii-bias threshold, E9, E1/E2 themselves)
   are out of scope by the task's own framing and are not touched here — this sheet is not a
   substitute for the ledger's six-location "what's open" enumeration recipe.

---

## Receipt

- **Doc:** `docs/signal-analysis/2026-08-01-e4-e8-decision-sheet.md` (this file).
- **One-line verdict per sub-item:**
  - E8 / iv_rank — DONE, gated default-OFF (#1179); next decision needs Sept IV-history data, not decidable now.
  - E8 / iv_pair — DONE, dead end confirmed (#675, #1170, #1174); do not re-propose.
  - E8 / oi_spurt floor — DONE, recalibrated + live-verified across 3+ sessions (#675, #991/T22).
  - E8 / k verdict — DONE, REJECTED-and-final (T1 ×6, locked by G11 2026-07-31).
  - E4 / H8 — decidable: rename now; real waiver is a separate HOLD-tier feature decision.
  - E4 / #128 batch — scoping decision only; recommend a first slice (M7+M6+M9), not the whole batch.
  - E4 / M36 — not yet decidable; needs a backtest A/B.
  - E4 / M37 — not yet decidable; needs the M39-style measurement.
  - E4 / M38 — decidable: rename now (bundle with H8); real fix uncosted/separate.
  - E4 / M40 — largely moot; note and mostly close, one narrow gap remains.
  - E4 / VcpDetector latent bug — decidable: no action, correctly dormant.
- **Corrected E8 ledger row:** see "Corrected ledger row" in Part 1 above; applied in the same PR
  to `docs/superpowers/plans/2026-07-02-remaining-items.md:547`.
- **Claim labels:** [computed] / [sourced] / [recalled] / [assumed], used inline throughout.
- **Open doubts:** see section above (6 items).
