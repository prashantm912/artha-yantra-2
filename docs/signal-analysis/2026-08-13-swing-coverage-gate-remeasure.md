# PR #1283 (swing data-coverage gate) — refusal rate re-measured against 2026-08-13 state

**Date:** 2026-08-13, measured 10:20–11:05 IST.
**Scope:** read-only. Nothing written, deployed, merged, or committed except this file.
**Branch measured:** `origin/feat/swing-coverage-gate` @ `266d37ae` (not checked out — read via `git show`).
**Supersedes as the current number:** `docs/signal-analysis/2026-08-11-1283-coverage-refusal-rate.md`
(that document remains correct for the state it measured).

---

## Verdict

**Arming the gate would change no outcome today — not one entry, not one exit, not one recorded
measurement — because the refusal rate on the real candidate population is exactly zero, not merely
because the books are capped** (computed; 0 of 102 manas candidates, 0 of 118 minervini candidates,
0 of 21 open-position exit evaluations).

That distinction is the finding. The brief's hypothesis was that the gate is *inert by cap* — that
it would only refuse entries the risk cap was going to refuse anyway. That is true for the
minervini book and **false for the manas book**, whose entry pass runs in full and scans all 102
candidates. Manas is inert for the stronger reason: the gate finds nothing to refuse.

Two corrections to the framing that matter more than the rate itself:

1. **Nothing can be armed today.** #1283 is unmerged and the gate is not in the deployed jar
   (computed: `unzip -l /app/*.jar | grep -c SwingCoverageProbe` → **0**, with `SwingBatchEngine`
   → **8** as the control proving the grep works). "Arming" is a three-step — merge, deploy, set
   `ARTHA_SIGNALS_SWING_COVERAGE_GATE_MODE=ARMED` — not a one-word env change from the current
   state.
2. **The mode flag does not gate the exit half.** `coverageGateMode` has exactly three consumption
   sites and none of them is in `exitPass` (sourced). So merging and deploying turns the exit
   observation on *immediately, in every mode including `DISABLED`* — which contradicts the mode
   table in the PR body. Detail in §6; it costs 0 alerts today, so this is a correctness note about
   what the flag promises, not a live hazard.

---

## 1. Premise check (STEP 0)

| Brief's premise | Status | Evidence |
|---|---|---|
| #1283 is open | **TRUE** | `gh pr view 1283` → `state: OPEN`, `mergeStateStatus: BEHIND` (sourced) |
| HOLD-tier, awaiting an owner arm/no-arm decision | **PARTLY** | The HOLD was *resolved* 2026-08-11 ("merge behind a default-OFF flag"); the PR body records this. But it never merged, so the live question is still merge-then-arm (sourced: PR body + `git ls-tree origin/main` → 0 `SwingCoverage*` files) |
| Refusal rate measured 2026-08-11 | **TRUE** | `docs/signal-analysis/2026-08-11-1283-coverage-refusal-rate.md`, method fully recorded and reproducible (sourced) |
| Both books at capacity with 18 open positions | **HALF WRONG** | minervini **12/12 = at cap**; manas-arora **6/7 — one free slot** (computed: `risk_settings` caps 12 and 7 vs `paper_positions` open counts 12 and 6) |
| The 08:35 catch-up is running | **TRUE, and it is now the only thing that ran** | The 2026-08-12 session's batch was executed by the catch-up at 08:35:35 IST on 08-13; the 08-12 evening chain did not run at all (computed, §7) |

The "both at capacity" error is load-bearing, not cosmetic: it is exactly the assumption that would
have made the whole measurement unnecessary. Manas is not capped, its entry pass runs, and a
non-zero refusal rate there **would** have changed a real outcome.

---

## 2. What the gate refuses on

Both halves key on the same predicate, `Coverage.notProvenSound()`
(`SwingCoverageProbe.java:210-212`, sourced):

```java
public boolean notProvenSound() {
  return !determinable || materiallyIncomplete();
}
```

and (`SwingCoverageProbe.java:179-181`, sourced):

```java
public boolean materiallyIncomplete() {
  return incomplete() && missing.size() * MATERIALITY_DENOMINATOR > materialityBasis;
}
```

with `MATERIALITY_DENOMINATOR = 22` (`SwingCoverageProbe.java:107`).

**In one sentence:** a refusal fires when the trailing window the strategy declares spans more NSE
trading sessions than it holds bars for — i.e. the row-based window silently reached *further back
in time* than declared — by more than 1-in-22 of the declared depth's own span; or when the probe
could not measure at all (`determinable = false`: empty series, zero depth, a null bar, a year
outside the bundled calendar, or a caught exception), which fails **closed**.

### The two halves are different severities

| | **ENTRY** | **EXIT** |
|---|---|---|
| Site | `SwingBatchEngine.java:623-649` | `SwingBatchEngine.java:1072-1098` |
| Probe | `probeEntry()` — reads `declaredDepth + DEPTH_SLACK(2)`, materiality denominator pinned to the declared depth (`SwingCoverageProbe.java:457-463`) | `probe()` — no slack, denominator = the probed span (`:440-443`) |
| Depth scope | gate-reachable ∪ scoring indicators only (`entryLookbackBars`, `:304`) | max over **all** indicators + all exit rules + bank `unstableBars()` (`exitLookbackBars`, `:375`) |
| Action when `ARMED` | `continue` — the candidate is **refused**, never evaluated | **never refuses.** Logs ERROR, writes a `swing_batch_refusals` row, publishes an ops alert; `ExitEvaluator` still runs unconditionally |
| Action when `OBSERVE_ONLY` | records a `WOULD_REFUSE_`-prefixed row, logs INFO, **entry proceeds** | *unchanged — the exit half is not mode-gated at all* (§6) |
| Doctrine | preventive — "you can always not enter" | detective — "you cannot refuse to leave forever" (#694 shape) |

**Live entry depths** (computed from each published version's `entry_rules.gate` ∪ scoring
indicators, matching the constant's own javadoc):

| strategy | entry depth | exit depth |
|---|---|---|
| `minervini-vcp`, `minervini-cheat-3c`, `minervini-power-play` | 20 | 50 |
| `manas-arora-breakout`, `manas-arora-vcp` | 20 | 50 |
| `minervini-primary-base` | **252** (`w52h`, `period: 252`, read in the gate) | 252 |

### Method validation before measuring anything

I re-implemented `measure()` in Python and red-proofed it against the source's **own documented
boundary cases** before trusting a single live number (computed):

| case | expected (per javadoc) | got |
|---|---|---|
| D=20, series longer than depth, 1 interior hole | basis 21, `1*22 > 21` → REFUSE | basis 21, REFUSE ✓ |
| D=20, no holes | basis 20, allow | basis 20, allow ✓ |
| D=50 exit window, m=2 / m=3 | allow / REFUSE (`m >= 3`) | allow / REFUSE ✓ |
| D=252, m=12 / m=13 | allow / REFUSE (`m >= 13`, was 14) | allow / REFUSE ✓ |
| slack 0 → 2 on the same 1-hole case | basis stays 21, window 21 → 23 | basis 21, window 23 ✓ |
| empty series | `notProvenSound = true` (fail closed) | true ✓ |

The slack case is the one worth calling out: it confirms the `d2b5bea4` footprint/denominator
separation actually holds, so `DEPTH_SLACK = 2` tightens and cannot loosen. That was the Critical
found in review round 3.

---

## 3. Re-measured refusal rate

### Window chosen, and why

**Headline: the single session `screen_date = 2026-08-12`** — the most recent completed swing
batch, executed by the 08:35 catch-up on 2026-08-13. Chosen because it is the only session for
which I can reconstruct the **exact** candidate population the engine actually scanned: the live
funnel endpoint serves the latest screen date, and its `immediatelyBuyable + onDeck` counts come
back **102 (manas) and 118 (minervini)**, byte-matching the `candidates` column
`swing_batch_runs` recorded for that run (computed). This is strictly better than the 2026-08-11
document, which used `passes_all` as a proxy population — that proxy is ~2× too wide (283 vs 118).

**Robustness: 29 sessions, 2026-07-03 → 2026-08-12**, on the `passes_all` population. Start date
chosen to match the 08-11 document exactly so the rolling figures are directly comparable.

### Entry half — the real funnel (primary result)

| family | population | depth | evaluated | gapped | undeterminable | **REFUSED** | **%** |
|---|---|---|---|---|---|---|---|
| manas-arora | funnel 08-12 | 20 | 102 | 0 | 0 | **0** | **0.00%** |
| minervini | funnel 08-12 | 20 | 118 | 0 | 0 | **0** | **0.00%** |
| minervini | funnel 08-12 | 252 | 118 | 0 | 0 | **0** | **0.00%** |
| **combined** | | | **220 candidate-scans** | **0** | **0** | **0** | **0.00%** |

Not one candidate in either funnel has a single missing session anywhere in its probed window —
`gapped = 0`, which is a stronger statement than `refused = 0`.

### Entry half — `passes_all` population (reproduces the 08-11 method)

| family | population | depth | evaluated | gapped | **REFUSED** | **%** |
|---|---|---|---|---|---|---|
| minervini | `passes_all` 08-12 | 20 | 283 | 1 | **1** | **0.35%** |
| minervini | `passes_all` 08-12 | 252 | 283 | 2 | **0** | **0.00%** |
| manas-arora | `passes_all` 08-12 | 20 | 144 | 0 | **0** | **0.00%** |

The single refusal is **WELINV** — 22 bars over a 25-session span, 3 missing (2026-07-10, 07-20,
07-31), basis 22, so `3*22 = 66 > 22` refuses decisively. Those are genuine structural gaps, not a
query artifact: WELINV simply has no bhavcopy row on those dates (computed, per-bar listing).
**WELINV is not in either engine funnel** (computed), which is why the real-population number is 0
and the proxy number is 1.

### Rolling, 29 sessions

| family | nights | symbol-nights | refusals | rate | worst night | recurring |
|---|---|---|---|---|---|---|
| minervini | 29 | 7 299 | 20 | **0.274%** | 1 of 240 = **0.42%** (2026-07-30) | WELINV × 20 |
| manas-arora | 29 | 3 290 | **0** | **0.000%** | 0 | — |

Every one of the 20 minervini refusals across 29 nights is the same symbol.

---

## 4. Comparison to the 2026-08-11 measurement

The method reproduced cleanly — the 08-11 document records its arithmetic step by step, so no
deviation was necessary on the shared population. **The rate did not move.**

| quantity | 2026-08-11 | 2026-08-13 | delta |
|---|---|---|---|
| latest funnel, depth 20, slack 2 (`passes_all`) | 0 / 285 = 0.0% | 0 / 283 = 0.0% ¹ | unchanged |
| latest funnel, depth 252, slack 2 (`passes_all`) | 0 / 285 = 0.0% | 0 / 283 = 0.0% | unchanged |
| rolling worst night (depth 20) | 1 of 241 = 0.41% | 1 of 240 = 0.42% | +0.01pp (same single symbol) |
| rolling nights covered | 27 (07-03..08-10) | 29 (07-03..08-12) | +2 |
| recurring refusal | WELINV | WELINV | unchanged |
| manas-arora | **not measured** (stated limitation 4) | 0 / 102 funnel, 0 / 3 290 symbol-nights | **gap now closed** |

¹ On the 08-12 `passes_all` set the depth-20 cell is 1/283 = 0.35%, because WELINV re-entered the
`passes_all` set (it had dropped out of the 08-10 set the prior measurement used). On the **real
funnel** the same cell is 0/118. Both are stated above; they differ by population, not by rate
drift.

**Most likely reason the rate did not move:** it was already at the floor. The 08-11 document
attributed the low rate to the entry-scoping + materiality-fraction rework, and nothing in the data
plane has degraded since. The two changes the brief flagged — books at capacity, catch-up running —
affect *whether the gate is consulted*, not *what it finds*, and the underlying candle plane for
these symbols is fully contiguous.

**Two things this re-measurement can claim that 08-11 could not:**

- **Manas is measured.** Limitation 4 of the prior document ("one funnel, one strategy family") is
  closed: 0 refusals on 102 real candidates and across 3 290 symbol-nights.
- **The retro-mutability caveat is bounded to zero here, not merely acknowledged.** Across the
  entire union population's history back to 2025-07-01, exactly **one** bar was written after the
  batch started — `EBGNG` 2025-07-30, source `KITE`, `fetched_at` 08:35:47 IST (computed). It falls
  **one session outside** the D=252 slack-2 probed window, which begins 2025-07-31, and removing it
  as a batch-time counterfactual leaves every verdict identical (computed). So today's-data and
  batch-time answers coincide. `fetched_at` is an upsert timestamp and bounds rather than pins, so
  this is an upper bound on divergence — but the upper bound is one irrelevant bar.

---

## 5. The decision-relevant question: would arming change any outcome today?

**No — and the reason differs per book, which matters more than the shared answer.**

### minervini — inert by cap (the brief's hypothesis, confirmed)

`entryPass` early-outs before the candidate loop when the book's governor blocks entry
(`SwingBatchEngine.java:528-534`, sourced). Measured live (computed, log line 2026-08-13T03:05:37Z):

> `minervini swing: entry pass skipped — the minervini book gate blocks entry at run start;`
> `118 funnel candidate(s) not scanned`

The coverage probe sits *inside* that loop, so on a capped minervini book **it never executes at
all**. Arming is unobservable there regardless of the rate. `MAX_OPEN` is the blocking rail:
`openCount` 12 vs cap 12 (`RiskService.entryVeto`, sourced; counts computed).

### manas-arora — NOT inert by cap; inert only because the rate is zero

Manas is 6 of 7 — one slot free — so `entryBlocked` is false and the entry pass **runs the full
102-candidate scan** (computed: no skip line in the log; `swing_batch_runs.candidates = 102`). The
coverage gate at `:623` sits **before** `EntryEvaluator.evaluate` at `:650` and before the M40
open-risk cap further down, so an `ARMED` refusal would pre-empt both.

Four manas candidates did fire entries and were stopped by the open-risk cap, not by slots
(computed, log): **BIRLACABLE, HAPPYFORGE, BLUSPRING, AUTOIND** — each
`fresh entry for X would breach the open-risk cap — skipped`. All four are coverage-clean, as are
the other 98. So arming changes nothing — but had any of those four been gapped, arming *would*
have converted a risk-cap refusal into a data refusal and, more importantly, a gapped candidate
that cleared the risk cap would have been blocked outright. **The cap is not what makes the manas
gate inert; the zero rate is.**

### The measurement side-channel — also unchanged, and this one is not cap-protected

The ledger-F3 admission probe runs after both passes and is **not** gated by `entryBlocked`
(`SwingBatchEngine.java:414-418`), and its coverage check *is* mode-gated to `ARMED`
(`:868-871`, sourced). So arming would shrink `wouldEnter` / `cap_exceedance` / `dropped_by_cap`
for **both** books, including capped minervini, if any would-be entrant were coverage-unsound.
Today none is, so the recorded F3 numbers (minervini `would_enter` 16, manas 4) are identical under
either mode (computed).

That is the one path by which arming could have changed something observable on a capped book, and
it was worth checking rather than assuming. It comes out zero too.

---

## 6. The exit half, separately

### Population is 21 evaluations over 18 symbols, not 18 positions

`exitPass` iterates `openLotsBySymbol()` — distinct symbols with an **ACTIVE/TAKEN ENTRY signal
anchor** resolvable to that family (`SwingBatchEngine.java:989`, sourced) — not `paper_positions`.
That resolves the brief's "18":

- 21 anchors: manas-arora-vcp 6, minervini-vcp 7, minervini-primary-base 4, minervini-cheat-3c 3,
  minervini-power-play 1 (computed) — matching `open_at_start` 6 and 15.
- over **18 distinct symbols**, because AVALON, KANORICHEM and PRECOT are each held by *both* books
  (computed).
- and 18 open `paper_positions` (12 minervini + 6 manas), but **not the same 18**: three minervini
  anchors — **INDUSINDBK, SENORES, TMB**, all on older versions (v1.0.0) — have an active anchor
  with no open paper position (computed). Flagged, not diagnosed; see open doubts.

### Result: 0 of 21

| family | anchors | exit depth | held bars | missing | **ALERT** |
|---|---|---|---|---|---|
| manas-arora | 6 | 50 | 50 each | 0 each | **0** |
| minervini (vcp / cheat-3c / power-play) | 11 | 50 | 50 each | 0 each | **0** |
| minervini (primary-base) | 4 | 252 | 252 each | 0 each | **0** |
| **total** | **21** | | | | **0 (0.00%)** |

Every held anchor has a fully contiguous window. Sensitivity: re-run at depth **+2** (in case
`unstableBars()` reaches deeper than the declared params) also yields **0** alerts (computed).

### ⚠️ The exit half is not gated by the mode flag

`coverageGateMode` is consumed at exactly three sites — `:623` (entry emission), `:626` (armed
test), `:868` (F3 probe). `exitPass` (lines 981–~1200) contains **no reference to it** (sourced,
exhaustive grep). Consequences:

- Merging and deploying #1283 turns the exit probe, its `swing_batch_refusals` rows, its ERROR log
  and its ntfy alert **on immediately, in every mode** — including `DISABLED`.
- The PR body's mode table states `DISABLED` → *"probe never runs — no rows, no logs, no cost."*
  That is **true of the entry half only**.
- Whether this is a defect or an intended asymmetry is a design question for the owner, not a fact
  I can settle: the exit half never refuses, so shipping it always-on is defensible on doctrine.
  But it is not what the flag advertises, and the merge decision was taken on the strength of
  "ships inert".

### And the exit alert is still un-deduped

`alertExitCoverageDegraded` (`:1240-1258`) publishes unconditionally on every degraded evaluation,
with no `(batch, session, symbol, reason)` suppression — the cross-vendor reviewer's **Minor 5**
from 2026-08-10 is still open on the branch head (sourced). The `swing_batch_refusals` row is
deduped by its primary key, but the alert is not. With the current schedule this matters more than
when it was raised, not less: a session's exits can be evaluated by both the settle path and the
08:35 catch-up. Cost today is zero because zero positions flag.

---

## 7. Context — the 2026-08-12 evening chain did not run

Worth recording because it is why the catch-up was load-bearing for the measured session
(computed, `marketdata.ingest_runs`): there is **no `BHAVCOPY` run on 2026-08-12 evening**. The
chain resumed the next morning — `BHAVCOPY` 08:03:33, `MANAS_SCREEN` 08:04:06, `MINERVINI_SCREEN`
08:04:39, swing batches 08:35 IST on 2026-08-13. RELIANCE's 2026-08-12 bar carries
`fetched_at = 2026-08-13 08:03:41 IST`, consistent.

So the 08-12 session's swing decisions were made entirely by the catch-up path, and the coverage
gate — had it been deployed — would have measured a plane that had been repaired ~30 minutes
earlier. It found it clean. Not diagnosed here; the stack outage register is the right home.

---

## Claims ledger

| Claim | Label | Evidence |
|---|---|---|
| #1283 OPEN, BEHIND, head `feat/swing-coverage-gate` | sourced | `gh pr view 1283 --json state,mergeStateStatus,headRefName` |
| HOLD resolved 2026-08-11 to "merge behind a default-OFF flag"; still unmerged | sourced | PR body final section; `git ls-tree -r origin/main \| grep -ci swingcoverage` → 0 |
| Gate absent from the deployed jar | computed | `unzip -l /app/*.jar \| grep -c SwingCoverageProbe` → **0**; control `SwingBatchEngine` → **8** |
| No `SWING_COVERAGE_GATE` var in `.env` or container env | computed | `grep -c` → 0; `docker inspect … Config.Env` shows only the *unrelated* `ARTHA_SIGNALS_STRATEGY_COVERAGE_WATCHDOG_MODE=ARMED` (T9 watchdog, different gate) |
| Refusal predicate `!determinable \|\| missing*22 > materialityBasis` | sourced | `SwingCoverageProbe.java:107,179-181,210-212` |
| `DEPTH_SLACK = 2`, applied in `probeEntry` only | sourced | `SwingCoverageProbe.java:288,457-463` |
| Entry depths 20 (×5) and 252 (primary-base); exit depths 50 (×5) and 252 | computed | published `strategy_versions.config` gate ∪ scoring per `entryLookbackBars`; exit per `exitLookbackBars` incl. `exit_rules` params |
| Re-implementation reproduces all 6 documented boundary cases | computed | §2 validation table |
| Caps: minervini 12, manas-arora 7 | sourced | `strategy.risk_settings` `max_open_paper_positions` |
| Open: minervini 12, manas 6 (18 total) | computed | `strategy.paper_positions WHERE status='OPEN'` |
| minervini entry pass SKIPPED at run start | computed | live log 2026-08-13T03:05:37Z, *"entry pass skipped — the minervini book gate blocks entry at run start; 118 funnel candidate(s) not scanned"* |
| manas entry pass RAN, 102 candidates scanned | computed | no skip line; `swing_batch_runs.candidates = 102`; 4 open-risk-cap skip lines |
| Funnel populations 102 / 118 match what the batch saw | computed | `/api/v1/market/screener/{manas-arora,minervini}/funnel` `immediatelyBuyable+onDeck` vs `swing_batch_runs.candidates` |
| **0 / 220 entry refusals on the real funnels** | computed | §3 table |
| 1 / 283 (WELINV) on the `passes_all` proxy; WELINV outside both funnels | computed | §3; per-bar listing shows genuine absence 07-10 / 07-20 / 07-31 |
| Rolling 20 / 7 299 minervini (0.274%), 0 / 3 290 manas, 29 nights | computed | per-date `passes_all` re-run, D=20 slack 2 |
| **0 / 21 exit-half alerts**, and 0 at depth+2 | computed | §6 table |
| Exit population is 21 anchors over 18 symbols; 3 symbols held by both books | computed | `strategy.signals` ENTRY ACTIVE/TAKEN joined to versions |
| INDUSINDBK / SENORES / TMB: active anchor, no open paper position | computed | anchors LEFT JOIN `paper_positions` |
| Exit half has NO mode gate | sourced | exhaustive `grep -n coverageGateMode` → 204, 233, 245, 296, 299, 623, 626, 868; none in `exitPass` (981–1200) |
| Exit alert un-deduped (reviewer Minor 5 still open) | sourced | `SwingBatchEngine.java:1240-1258`, unconditional `publishEvent` |
| Only 1 bar repaired after the batch started, and it is outside the probed window | computed | `fetched_at > 2026-08-13 08:35 IST` → EBGNG 2025-07-30; D=252 slack-2 window starts 2025-07-31; counterfactual removal changes nothing |
| No 2026-08-12 evening BHAVCOPY; chain resumed 08:03 IST 08-13 | computed | `marketdata.ingest_runs` |
| Catch-up ran 08:35:35 IST 2026-08-13 | computed | `swing_batch_runs.ran_at`; live log thread `swing-catchup-sched-1`. (The code *default* is `0 35 8 * * MON-FRI`, `SwingBatchCatchUp.java:175` — quoted only as corroboration; per the house rule a YAML/annotation default is not evidence of the deployed schedule, so the measured time is the claim.) |
| The ~88% rate that originally made this HOLD-tier | recalled | quoted from PR history; not re-measured here or on 08-11 |

## Open doubts

- **`materialityBasis` is re-derived, not executed.** As on 08-11, I reproduced `measure()` in
  Python rather than running the shipped Java. The six boundary cases in §2 give real confidence
  (they include the two that previously encoded bugs), but this is a re-implementation. A run of
  `SwingCoverageProbeTest` against live series would be strictly better and was not done.
- **Exit depth assumes the declared params dominate `IndicatorBank.unstableBars()`.** I could not
  execute the bank, so `exitLookbackBars` was computed from config params only. The depth+2
  sensitivity check also returns 0 alerts, and every held anchor's window is fully contiguous with
  zero holes, so the conclusion is robust to a considerably larger error than +2 — but the exact
  depth is `assumed`, not computed.
- **One session is the headline; 29 nights is the proxy-population robustness check.** The exact
  funnel is only reconstructable for the latest screen date, so the rolling figures necessarily use
  `passes_all`, which over-reports (1 vs 0 on the one night where both are available). The
  direction of that bias is conservative.
- **Zero gapped symbols is a suspiciously clean result** and deserves one adversarial reading: it
  could indicate the probe population is not what the engine reads. Two things argue against a
  filter artifact — the funnel counts byte-match `swing_batch_runs`, and the same query *does* find
  WELINV's real 3-session gap in the wider population, so the probe demonstrably can see holes when
  they exist. But per the `series='EQ'` precedent, a clean result is exactly when to say this out
  loud.
- **The three anchors with no paper position (INDUSINDBK, SENORES, TMB) are unexplained here.**
  They inflate the exit-evaluation population above the position count and may relate to the
  zombie-position class in `docs/signal-analysis/2026-08-04-zombie-position-audit.md`. Out of scope
  for this measurement; not investigated.
- **The 252-tightening remains untested against a live refusal** (unchanged from 08-11): no symbol
  is anywhere near `m >= 13`. Worst observed hole count at depth 252 is small and non-refusing.
- **Whether the un-gated exit half is a defect or intent is not established.** I can show the flag
  does not reach `exitPass` and that the PR body's table says otherwise; I cannot show which of the
  two the author meant.
