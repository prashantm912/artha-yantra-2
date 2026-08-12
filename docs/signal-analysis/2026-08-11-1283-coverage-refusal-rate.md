# PR #1283 (swing data-coverage gate) — measured refusal rate

**Date:** 2026-08-11, measured 16:45–17:05 IST (post-close; NSE closed 15:30).
**Scope:** read-only measurement on the LIVE `artha` DB. Nothing written, deployed, or merged.
**Branch measured:** `origin/feat/swing-coverage-gate` (`SwingCoverageProbe.java`, `SwingBatchEngine.java`).
**Purpose:** produce the one number that decides whether #1283 can merge — nobody had measured the
refusal rate for the CURRENT revision.

---

## 0. Premise correction — the gate is NOT always-on, and it already has a flag

The task framing said "#1283 is HOLD-tier because the gate is ALWAYS-ON with no feature flag: the
moment it deploys it starts refusing live swing entries." **That is false on the current revision.**

`SwingBatchEngine` carries a three-state mode mirroring the T9 coverage watchdog:

| Evidence | Value | Label |
|---|---|---|
| `SwingBatchEngine.java:233` | `@Value("${artha.signals.swing-coverage-gate.mode:OBSERVE_ONLY}")` | sourced |
| `application.yml:117` | `mode: ${ARTHA_SIGNALS_SWING_COVERAGE_GATE_MODE:OBSERVE_ONLY}` | sourced |
| `deploy/docker-compose.yml:527` | `ARTHA_SIGNALS_SWING_COVERAGE_GATE_MODE: ${…:-OBSERVE_ONLY}` | sourced |
| `.env` (live) | variable ABSENT → default applies | sourced |

States are `DISABLED` / `OBSERVE_ONLY` / `ARMED` (`SwingBatchEngine.java:85–97`). In `OBSERVE_ONLY`
the gate logs `WOULD_REFUSE_…` and emits an aggregated count (`:724`), refusing nothing. So the
merge decision and the arming decision are already separate; merging #1283 does not refuse a single
live entry until someone sets `ARTHA_SIGNALS_SWING_COVERAGE_GATE_MODE=ARMED` in `.env`.

The measurement below is therefore the number that decides **arming**, which is what actually
matters. It was worth running either way.

---

## 1. Result

Population: `marketdata.minervini_screen_results`, latest `screen_date` = **2026-08-10**,
`passes_all = true` → **285 symbols** (computed). Exchange `NSE` (sourced: `SwingBatchEngine.java:72`,
`EX = "NSE"` — the swing universe is NSE-only).

| declaredDepth | DEPTH_SLACK | symbols | gapped | would refuse | % refused |
|---|---|---|---|---|---|
| 20 | 0 | 285 | 0 | 0 | **0.0%** |
| 20 | 2 | 285 | 0 | 0 | **0.0%** |
| 252 | 0 | 285 | 1 | 0 | **0.0%** |
| 252 | 2 | 285 | 1 | 0 | **0.0%** |

All four cells computed. Both 252 rows completed (25 s and 35 s wall-clock) — no abandonment needed.

Supporting distribution (computed, slack 2):

| depth | symbols | short history (`held < depth`) | max `missing` on any symbol | worst symbol's margin to the refusal threshold |
|---|---|---|---|---|
| 20 | 285 | 0 | 0 | −20 (i.e. `0*22 − 20`) |
| 50 (exit windows) | 285 | 0 | 0 | −50 |
| 252 | 285 | 0 | 4 | −168 (`4*22 = 88` vs basis 256) |

The single gapped symbol at depth 252 is **PANSARI** — 254 bars spanning 2025-07-22..2026-08-10,
4 missing sessions, `materialityBasis` 256. It refuses at `missing ≥ 12`; it is at 4. Nothing else in
the funnel has a hole at all.

Zero symbols were `undeterminable` (all 285 have bars; the 252 span touches only 2025–2026, both
inside the bundled calendar, so the CD-2 cliff is not in play).

### Marginal cost of DEPTH_SLACK 2 vs 0 — the thing the last commit changed

**Zero on this funnel.** Slack 2 and slack 0 produce byte-identical counts at both depths (0/0 and
1/0). Mechanically that is expected here rather than a coincidence: slack only widens the numerator
(`missing`) while `materialityBasis` stays pinned to the declared depth's own span, and on
2026-08-10 there were no holes anywhere — including in the 2-bar slack region — for any symbol at
either depth. The 2026-08-08 review's concern (that slack 2 previously LOOSENED the gate by widening
the denominator) is structurally fixed on this revision and costs nothing measurable on live data.

### Robustness — one night is not enough, so 27 nights

Depth 20, slack 2, over every screen date from 2026-07-03 to 2026-08-10 (computed, today's data):

| nights | symbols/night | refusals/night | worst night |
|---|---|---|---|
| 27 | 207–285 | **0 or 1** | 1 of 241 = **0.41%** |

The recurring single refusal 2026-07-14..2026-08-07 is **WELINV** — 22 bars spanning a 25-session
window, 3 missing, basis 23, so `3*22 = 66 > 23` refuses decisively. It is a genuinely gapped
symbol, which is the gate working. It drops out of the 2026-08-10 funnel.

So the answer is not "0% because the funnel happened to be clean on one night" — it is **≤0.41%
across every night measured**.

---

## 2. Arithmetic implemented (matches the branch, verified against source)

Per symbol, for `(declaredDepth, slack)`:

1. take the last `P = declaredDepth + slack` daily bars (`NSE`, `interval='1d'`);
2. `span` = NSE trading sessions in `[oldest, newest]` of those bars, inclusive;
3. `missing = span − held`;
4. `declaredFirst` = date of the `min(declaredDepth, held)`-th bar from the newest;
5. `materialityBasis` = trading sessions in `[declaredFirst, newest]` — algebraically identical to
   the branch's `declaredHeld + holesInDeclared` (`SwingCoverageProbe.measure`), and it collapses to
   the whole span when the series is shorter than the declared depth;
6. **refuse iff `missing * 22 > materialityBasis`.**

Cross-checked against the source comment's own worked case: depth 20 with one interior hole gives
`basis = 21`, `1*22 > 21` → refuses. Reproduced by this query (`min_basis` 20 with no holes, 21 with
one). Confirms the implementation is faithful, not merely plausible.

### Deliberate deviation from the brief's method

The brief suggested deriving trading sessions as "dates where ≥100 distinct symbols have a 1d bar".
This report uses the **actual bundled NSE calendar instead** — weekday minus the 30 holiday dates in
`libs/market-calendar/src/main/resources/nse-trading-holidays.csv` for 2025–2026 — because that is
the exact calendar `SwingCoverageProbe` measures against, and because the ≥100-symbol proxy would
have silently mis-classified any outage day as a non-session (2026-08-10 is recorded in the stack
outage register as a no-batch day, and the proxy would have hidden precisely the holes this
measurement is looking for). Verified that `MarketCalendar.isTradingDay` returns false
for every Saturday and Sunday with no special-session modelling (`MarketCalendar.java:137`, and the
CSV header states the Budget-day session "is NOT modeled here"), so the weekday filter matches it
exactly — including the one weekend session in range, Sunday **2026-02-01** (196 symbols hold a bar;
the probe does not count it as a session, and neither does this query).

---

## 3. Limitations — read these before quoting the 0%

1. **This is "what the gate would refuse against TODAY's data", not a historical backtest.**
   `marketdata.candles` is retro-mutable; the nightly BHAVCOPY ingest backfills holes after the fact.
   The rolling 27-night table above re-runs the gate as-of each past screen date but reads today's
   (already-backfilled) bars, so it measures *structural* gaps that survived backfill, not what was
   on disk that night.

2. **The contrast with the earlier review is the whole caveat, quantified.** The 2026-08-08
   calibration table in `SwingCoverageProbe` records the 2026-08-03 funnel (277 `passes_all`) as
   having **46 symbols with 3–6 missing sessions**. Re-measured today, that same date and depth
   yields **1**. The holes are gone from the data, not from history. Anyone comparing the two numbers
   is comparing two different worlds.

3. **Mitigating, and it is load-bearing:** a batch-time reconstruction via `fetched_at` was run for
   the depth-20 window and shows the gap between the two worlds is smaller than (2) implies *for the
   armed decision*. Of all 285×22 bars in the 2026-08-10 depth-20 window, **zero interior bars were
   fetched after the 19:31 batch**; the only late writes are 147 copies of the session's own
   2026-08-10 bar (fetched ~20:05). Absence of the newest bar is the probe's **declared blind spot**
   — the span starts at the oldest bar held, so a trailing-edge absence shifts the window back rather
   than punching a hole — and produces zero `missing`. The batch-time answer is therefore also 0%.
   (`fetched_at` is an upsert timestamp, not first-seen, so it bounds rather than pins; the bias is
   toward over-reporting absence, i.e. conservative.)

4. **One funnel, one strategy family.** Measured against the Minervini screen only. The five
   depth-20 strategies and `minervini-primary-base` share this candidate pool, but a Manas book with
   a different universe was not measured.

5. **`OBSERVE_ONLY` already produces this number nightly for free.** `SwingBatchEngine:724` logs
   `coverage gate OBSERVE_ONLY would have refused N candidate(s)` with the symbol list on every run.
   Arming can be gated on a week of that log rather than on this single measurement.

---

## 4. Verdict for the owner

**(a) Safe to merge as-is** — the current revision would refuse **0 of 285** candidates on the
latest funnel and at most **1 per night (0.41%)** across 27 nights, three orders of magnitude below
the ~88% that made this HOLD-tier; and it ships `OBSERVE_ONLY` by default anyway, so merging refuses
nothing until the flag is set to `ARMED`, which makes (b) the shipped state rather than a change to
request.

**DEPTH_SLACK 2 vs 0 costs exactly zero refusals** at both depth 20 and depth 252 on this funnel —
the last commit is free in refusal terms, and by construction slack can only ever tighten, never
loosen, now that `windowSessions` and `materialityBasis` are separate.

Merge is the owner's call; #1283 was not merged and nothing here was committed.

---

## Claims ledger

| Claim | Label | Evidence |
|---|---|---|
| 285 `passes_all` symbols, `screen_date` 2026-08-10 | computed | `SELECT max(screen_date), count(*) FILTER (WHERE passes_all)` → `2026-08-10 \| 285` |
| 0 / 0 / 0 / 0 refusals in the 2×2 table | computed | window-function query, 2.4 s (depth 20) + 24.8 s (depth 252) |
| WELINV: 22 held, 25-session span, 3 missing, basis 23 → refuses | computed | per-symbol query at `screen_date=2026-08-07` |
| PANSARI: 254 held, 4 missing, basis 256 → does NOT refuse (needs 12) | computed | per-symbol query at `screen_date=2026-08-10` |
| ≤1 refusal/night over 27 nights | computed | rolling as-of query, 2026-07-03..2026-08-10 |
| Gate defaults to `OBSERVE_ONLY`, flag absent from `.env` | sourced | `application.yml:117`, `docker-compose.yml:527`, `grep .env` |
| Swing universe is NSE-only | sourced | `SwingBatchEngine.java:72` |
| Refusal rule `missing * 22 > materialityBasis` | sourced | `SwingCoverageProbe.materiallyIncomplete()`, `MATERIALITY_DENOMINATOR = 22` |
| `DEPTH_SLACK = 2` on branch | sourced | `SwingCoverageProbe.DEPTH_SLACK` |
| 46 symbols gapped on 2026-08-03 at review time | sourced | calibration table in the `MATERIALITY_DENOMINATOR` javadoc (38+7+1) |
| No interior bar fetched after the 19:31 batch on 2026-08-10 | computed | `count(*) FILTER (WHERE fetched_at > '2026-08-10T19:31+05:30')` per bar date → 0 except the session's own bar |
| Earlier revision would have refused ~88% | recalled | asserted by the task brief; NOT independently re-measured here |

## Open doubts

- **The ~88% baseline is unverified by this run.** It is quoted from the task brief and the review
  history, against an earlier revision and an earlier data snapshot. The improvement is real
  regardless (0% is 0%), but the *ratio* between the two should not be quoted as measured.
- **Retro-mutability caps the confidence of any historical read** (limitation 2). The strongest
  evidence for arming is not this document — it is a week of `OBSERVE_ONLY` counts from the live
  batch, which are measured at batch time against the data the batch actually saw.
- **The 252 tightening noted in the source (m ≥ 13, was 14) is untested against a live refusal**
  because no symbol is anywhere near it (worst is 4). If a genuine multi-week outage ever put a
  symbol at 12–13 missing, this measurement says nothing about whether refusing there is correct.
- **Manas books were not measured** (limitation 4).
- **`materialityBasis` was recomputed as "sessions in `[declaredFirst, last]`"** rather than by
  replaying the Java line-for-line. The equivalence is algebraic and was cross-checked against the
  source's own worked boundary case, but it is a re-derivation, not an execution of the shipped code.
