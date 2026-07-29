# Session learnings — 2026-07-29 evening → 07-30

Nine PRs merged, deployed, live-verified. The reusable output is below; per-item evidence lives in
the dated docs each row points at.

---

## The headline finding

**Every measured loosening of the scalper ENTRY gate has lost money — four tests, three knobs.**

| test | knob | result |
|---|---|---|
| T1 | relative-floor MULTIPLIER 1.5 → 1.2 | REJECTED — would-have-fired set 2W/9L, −121.95 pts |
| T7 | composite THRESHOLD 0.600 → 0.55 | REJECTED — `composite-055` worst book, −₹321/close |
| G13 | IV bloc dead weight | UNDECIDABLE — 6 legs; sign flips on one |
| G10 | volume-floor time-of-day profile | REJECTED — 265 legs, +324.87 gross, **−590.95 after 1% cost** |

**This is the prior for any future loosening proposal on this track.**

⚠️ **All four are conditional on the 30-minute `time_stop`**, which G11 says is itself the dominant
term in scalper P&L. If G11 changes the exit, every one of these rejections must be re-run. They are
not independent of it.

---

## Method rules earned this session

### 1. A pass-rate delta is not a result. Convert it to LEGS, then to P&L.

G13's headline was **+21.7% more rows clearing the composite**. It collapsed to **6 distinct legs**
once two things were applied:

- **Which rail actually BINDS.** Of 620 newly-passing rows only 8 were blocked *by* the composite;
  the rest were stopped by another rail, so raising their composite changes nothing.
  `volume-floor` binds **7,430 of 8,431** blocks (88%); `confluence-composite` binds **73** (0.9%).
- **Dedupe by `(bar_time, tradingsymbol)`** — slug fan-out inflates raw row counts.

The pipeline: newly-passing rows → rows where that rail was the SOLE blocker → dedupe → price.

### 2. Test the sign's robustness, and subtract costs, before quoting a number.

G10 was **+324.87** gross and **−305.88 excluding its top 5 legs of 265**. Median leg **−1.65**.
Break-even at ~**0.35%** round-trip. A result that flips on 1.9% of observations is not an edge.

### 3. Verify a claim on the population the CODE reads, not a convenient proxy.

I justified the G12 window fix with "18–34 bars on every session" — measured over 200 *context-bearing*
rows, while `FETCH_DEPTH` scans 200 **raw** rows. True range 7–25, and one session stayed under the
threshold. Caught only by post-deploy verification, after merge.

### 4. Red-proof the guard you actually claim — a GREEN red-proof is itself a finding.

Twice this session:

- The first G10 red-proof removed only the candidate *check* and left the scan fallback, so it
  passed. It proved nothing.
- Worse: the fixture gave the truncated session 20 bars, so the bad offset fell out of range and was
  merely **skipped** — the *safe* symptom. The dangerous case is the offset landing on a
  real-but-**wrong** bar. **The regression was written for the wrong failure mode** and would have
  passed against the defect indefinitely.

### 5. A sampling window is invisible to tests that build their own sample.

G12's frozen-operand probe shipped **18/18 green while inert on a third of sessions** — the tests
synthesised one row per bar, and live one 3m bar fans out across many scalpers. When a probe samples
a live table, measure the real window with SQL *before* choosing the constant.

### 6. Typing an opaque Map does not merely reveal a shape — it COMMITS to it.

`ConfigDiff.Op` emits `before=null` on an add and `after=null` on a remove. Invisible inside a
`Map<String,Object>`; the moment `DiffResponse` published it, the schema asserted two always-present
strings that are routinely null. **Check every operand's nullability at conversion time.**

### 7. Count a ratchet with the TEST's own regex.

`MapReturnRatchetTest` scans only `*Controller.java`. A shell grep over `src/main` returned 6 where
the true count was 4 — and because the assertion is `isLessThanOrEqualTo`, the over-stated floor
**passed green while leaving room for two new opaque handlers**.

---

## Two infrastructure defects found by chasing an unrelated red

Investigating why D3's PR went red on a shard it did not touch:

1. **A required check was masking a live architecture violation.** `ModularityTest` had been failing
   deterministically on main since #1094 with a real Modulith cycle
   (`bhavcopy → nse → screener → bhavcopy`), but surefire's `rerunFailingTestsCount=2` filed it as a
   **flake** and the shard reported SUCCESS. Whether any given PR saw it was luck. Fixed in #1115 —
   structural tests now run in their own execution pinned to zero retries. **A deterministic check
   must never be rerun-eligible.**
2. **The cycle itself** (#1116). Broken by moving `AdjustedEquityDailySql` to a zero-dependency
   `equitydaily` leaf — the newest edge (`nse → screener`, added by #1094) was the one to cut, and
   moving rather than duplicating preserved #1094's "one definition" goal exactly (`git diff -M` = 1
   insertion, 1 deletion).

⚠️ Not chosen: `corporateactions`, which is semantically the obvious home but already imports
`alerts`/`candles`/`instruments`/`kite` — parking a shared class there risks creating a *fresh* cycle
rather than closing one.

---

## Corrections I made to my own work

Recorded because the pattern matters more than any one of them: **my claims were wrong more often
than my code.**

- **Session date.** Dated everything 2026-07-30 while working the evening of 07-29 (#1112).
- **Freeze-window claim** measured on the wrong row population (#1113).
- **Ratchet floor** over-stated at 6; true 4 (#1114 review).
- **`Op` nullability** — published a contract lie (#1114 review).
- **"Register a shadow variant"** for G13 — the variant vocabulary is rails + a composite floor and
  cannot express a dot drop. Withdrawn.
- **"Seed the window from the prior session's median"** for G10 — measured, and it **REVERSES** the
  bias rather than fixing it (open becomes 2× *easier* than the rest of the day). Withdrawn; the
  time-of-day profile replaced it.
- **G10 regression written for the wrong failure mode** (see rule 4).

Every one was caught by a gate: cross-vendor review (4), post-deploy verification (2), or insisting
a red-proof actually go red (1).

---

## Owner decisions still open

- **G11** — exit doctrine. Blocked on a chop-day observation; now has a detector (G15) and a ~29%
  base rate, so it is a ~3–4 session wait rather than indefinite. **No usable chop day yet** — all
  five on file predate the 07-27 gate changes and four sit on an expiry.
- **G13** — IV bloc. Measured; the arithmetic favours dropping `iv_pair` + redefining `iv_abs_band`,
  but the payoff is ~6 legs and the loosening prior above argues against spending it.
- **#1075** `budget_inr` — held to 2026-08-12, and now quantified against **G14** (raising it lowers
  the convergence depth at which the sub-account ceiling refuses).
- **G10 arming** — recommendation is **NO**, on the P&L above.
- **T9 arming**, **INT I4** (~2026-08-09), **B8 host-clock resync** — unchanged.
