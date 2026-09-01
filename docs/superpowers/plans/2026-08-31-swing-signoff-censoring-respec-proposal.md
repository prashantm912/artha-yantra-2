# ADOPTED — the swing reliability sign-off (§0.5 #12), re-specified censoring-aware

**Status: ADOPTED 2026-09-01 (owner). This document is now the authority for §0.5 #12**, superseding
the prose bar in `archive/2026-07-04-minervini-build-log.md:440`,`:496` (archived, left untouched).

⚠️ **The re-specified bar REFUSES sign-off today.** It was approved on a worked example that said the
book would pass; tightening two clauses that the proposal left ambiguous flips that to a refusal on
**payoff under perturbation**. See §"What this decides today". The correction is recorded rather than
smoothed over, because a bar rewritten after a failure that then conveniently passes is exactly the
shape a reader should distrust — and this one does not pass.

---

## The bar as written before

*(sourced, `archive/2026-07-04-minervini-build-log.md:496`, `:440`)* — **≥30–50 closed single-stop
forward paper trades**, **expectancy > 0**, **payoff ≥ 2**, **batting ≥ ~45%**.

## Why it was failing for a reason that is not book quality

E1's 2026-08-31 re-run refused sign-off: n=27 closed against ≥30, batting 18.5%, expectancy −4.73%,
payoff 0.89 — all four fail. *(Reproduced independently 2026-09-01, `computed`: n=27, 18.5%, −4.73%,
0.89 — exact to the decimal on all four. E1's swing figures are sound.)*

**The censoring is measured, not argued** *(computed 2026-09-01)*:

| | n | avg days held |
|---|---|---|
| **closed** — the set the old bar sampled | 27 | minervini 15.7 · manas-arora 11.9 |
| **open** — the set the old bar excluded | 18 | minervini 35.0 · manas-arora 25.5 |

Losers close in about two weeks. Winners have been held two-to-three times longer and are **still
open**, so they never entered the sample. That is not a quirk of this window — it is what an
8%-stop / 50-day-MA-trail book is *designed* to do: cut losers fast, let winners run. **A
closed-only criterion applied to a trail-following book therefore samples its losses by
construction**, and does so more severely the better the book performs. From the other side: win
rate is **5 of 27 (18.5%) closed** against **17 of 18 (94.4%) open**.

## What is wrong with simply switching to the MTM view

It is biased the other way, and E1 was right to decline sign-off on it. An open position marked to
last close counts as a win at a price it has not realized and may never realize. Granting sign-off
on unrealized marks would be the mirror of the old error, not a correction of it. **This is why
criterion B carries perturbation tests, and why they are binding rather than advisory** — see the
verdict below, where they are the whole story.

⚠️ **The obvious pessimistic bound is NOT computable today.** Marking each open position at its
governing stop would be the principled conservative view. It cannot be measured:
`paper_positions.stop_loss` is the **static entry stop**, never updated (nothing `UPDATE`s that
column), and the live ratcheted stop exists only in `ManasGoverningStopCache`, an in-memory map that
is **cold as the normal operating regime** — its own javadoc records that the trail only arms at +9%
and that zero of six open positions qualified on 2026-08-02. Computing "mark at stop" from the DB
returns −8.25% on every open position, which is every position's original entry stop read back, not
its protection.

## The re-specified bar — three criteria

**A. The sample-size criterion counts RESOLVED-or-MATURE positions.**
A **closed** position counts, always — it has shown its outcome, whatever its holding period. An
**open** position counts once it has been held **≥ 20 trading sessions**, comfortably above the
11.9/15.7-day closed average, so a position that has survived a normal loser's lifetime counts as
evidence. The threshold is ≥30.

> ⚠️ **This clause is the tightened form.** The proposal read "a position is mature once held ≥20
> trading days; both closed and still-open mature positions count", which applies the maturity test
> to closed positions too and yields **n=16** — a fail — while the same document's worked example
> yielded n=39. Two readings, opposite verdicts, and the ambiguity was invisible until the query ran.
> The reading above (closed always count) is the one the worked example used and the one that matches
> the criterion's stated purpose.

**B. Quality metrics on the censoring-corrected basis, and they must SURVIVE perturbation.**
Open positions marked to last stored close. Sign-off requires **all four criteria to hold on the
corrected basis, AND to still hold after leave-3-out (drop the three largest contributors), AND
after dropping the single largest.**

> ⚠️ **"Survive" means all four criteria still met — not merely that the sign stays positive.** The
> proposal did not define it. The strict reading is the one adopted, because the loose reading
> defeats the criterion's own stated purpose: B exists to stop a small number of unrealized peaks
> carrying the verdict, and a sign-only test cannot detect that.

**C. A pessimistic floor: closed-only expectancy must not be deteriorating.**
Judged as a **trend across re-runs**, not a level. **If B and C ever disagree — corrected metrics
passing while closed-only expectancy rots — C wins and sign-off is refused.** This is the tripwire
for the failure mode B cannot see: a book whose MTM looks excellent because winners are being held
through a top that never realizes.

**Explicitly NOT acceptable:** sign-off on the MTM view alone, without B's perturbation tests and
C's trend check.

## What this decides today — REFUSED

*(all `computed` 2026-09-01 from `strategy.paper_positions` + `marketdata.nse_eod_bhavcopy`,
series-agnostic; open positions marked to each symbol's latest stored close)*

| criterion | result | verdict |
|---|---|---|
| **A** — n ≥ 30 | **39** = 27 closed + 12 of 18 open at ≥20 sessions *(9 minervini, 3 manas-arora)* | **PASS** |
| **B** — corrected, as measured | n=45, batting 48.9%, expectancy **+4.53%**, payoff **2.36**, +₹23,038.65 | PASS |
| **B** — drop largest 1 | expectancy +4.01%, payoff **2.29**, batting 47.7%, +₹18,076.42 | PASS |
| **B** — drop largest 3 | expectancy +1.98%, payoff **1.83**, batting 45.2%, +₹9,073.92 | **FAIL — payoff < 2** |
| **C** — closed-only expectancy trend | −6.72% → −4.73%, improving | PASS |

**Sign-off is REFUSED, on criterion B under leave-3-out.** Everything else clears, including the
sample size that blocked it before. Three positions are carrying payoff above 2.0; remove them and
the book still makes money (+₹9,074) with acceptable batting (45.2%), but its win/loss ratio drops
below the doctrine's bar. That is precisely the concentration B was written to detect, and it is
detecting it.

⚠️ **This is a materially different answer from the one the re-spec was approved on.** The proposal's
worked example claimed a PASS. It was wrong twice: criterion A's wording gave n=16 rather than 39,
and criterion B's undefined "survive" hid a payoff failure that only appears once the perturbation
test is actually computed rather than cited. Both errors were mine, both were found by running the
queries the proposal described instead of trusting the numbers it quoted, and **both are recorded
here rather than in the direction that would have made the re-spec look better.**

**What clears the refusal:** payoff ≥ 2 under leave-3-out. That needs either more winners (reducing
concentration) or realization of the current ones (moving them from marks to closes, where they
count with certainty). No code change, no doctrine change — time and more positions.

## Optional follow-up, independent of this adoption

To make a true pessimistic bound computable, the governing stop must be **persisted** rather than
cached in memory: a column plus a tighten-only write path, mirroring the guarantee
`ManasGoverningStopCache` enforces in Java. Not required by this bar — noted so the gap stays visible
rather than being rediscovered.

## Not changed, deliberately

`SwingReportCard` grades a **backtest run's** trades against the old four-criterion bar. It is
run-scoped analytics, not the forward-paper sign-off this document governs, so it is left alone.
Changing it would be scope creep and would silently retune backtest letter grades.
