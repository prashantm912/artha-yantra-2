# Proposal — re-specify the swing reliability sign-off (§0.5 #12) censoring-aware

**Status: PROPOSAL, owner review required. Nothing applied.** The owner approved *re-specifying the
bar censoring-aware* on 2026-08-31; the exact form is a judgement call, so it is written out here
rather than edited into the ledger unilaterally.

---

## The bar as written

*(sourced, `archive/2026-07-04-minervini-build-log.md:496`, `:440`)* — **≥30–50 closed single-stop
forward paper trades**, **expectancy > 0**, **payoff ≥ 2**, **batting ≥ ~45%**.

## Why it is failing for a reason that is not book quality

E1's 2026-08-31 re-run refused sign-off: n=27 closed against ≥30, batting 18.5%, expectancy −4.73%,
payoff 0.89 — all four fail. On the censoring-corrected basis the **same book** shows n=45, batting
48.9%, expectancy +4.13%, payoff 2.25 — all four pass.

**The censoring is now measured rather than argued** *(computed 2026-08-31)*:

| | n | avg days held |
|---|---|---|
| **closed** — the set the bar samples | 27 | minervini 16.4 · manas-arora 12.4 |
| **open** — the set the bar excludes | 18 | minervini 36 · manas-arora 26 (max 55) |

Losers close in about two weeks. Winners have been held two-to-four times longer and are **still
open**, so they have never entered the sample. That is not a quirk of this window — it is what an
8%-stop / 50-day-MA-trail book is *designed* to do: cut losers fast, let winners run. **A
closed-only criterion applied to a trail-following book therefore samples its losses by
construction**, and does so more severely the better the book performs.

Corroborating the same point from the other side: win rate is **5 of 27 (18.5%) closed** against
**17 of 18 (94.4%) open**.

## What is wrong with simply switching to the MTM view

It is biased the other way, and E1 was right to decline sign-off on it. An open position marked to
last close counts as a win at a price it has not realized and may never realize. Granting sign-off
on unrealized marks would be the mirror of the current error, not a correction of it.

⚠️ **The obvious pessimistic bound is NOT computable today, and this is worth knowing before anyone
proposes it.** Marking each open position at its governing stop — the worst it can now realize —
would be the principled conservative view. It cannot be measured: `paper_positions.stop_loss` is the
**static entry stop**, never updated (nothing in the codebase `UPDATE`s that column), and the live
ratcheted stop exists only in `ManasGoverningStopCache`, an in-memory map that is **cold as the
normal operating regime** — its own javadoc records that the trail only arms at +9% and that zero of
six open positions qualified on 2026-08-02. Computing "mark at stop" from the DB today returns
−8.25% on every one of the 18 positions, which is simply every position's original entry stop read
back, not its protection.

## Proposal — three changes

**A. The sample-size criterion counts MATURE positions, not closed ones.**
A position is *mature* once held ≥ 20 trading days (comfortably above the 12.4/16.4-day closed
average, so a position that has had a normal loser's lifetime and not died counts as evidence).
Both closed and still-open mature positions count toward the ≥30. **Rationale:** the criterion's
purpose is "has the book had enough opportunity to show itself", not "have enough trades ended" —
and under a trail, the second grows slowest exactly when the book is working.

**B. Quality metrics are evaluated on the censoring-corrected basis, and must survive perturbation.**
Open positions marked to last stored close, and sign-off requires the corrected result to meet all
four criteria **and** survive **leave-3-out** **and** leave-out-the-largest-single-contributor.
*(E1 already computed these: +4.13% / +₹21,008 as measured; +1.66% / +₹9,824 drop-best-3; +3.06% /
+₹16,029 drop-`DIACABS`.)* **Rationale:** the robustness requirement is what stops one unrealized
peak carrying the verdict, which is the real risk in switching to marks.

**C. A pessimistic floor: closed-only expectancy must not be deteriorating.**
Judged as a **trend across re-runs**, not a level. *(Currently −6.72% → −4.73%: improving.)*
**Rationale:** this is the tripwire for the failure mode B cannot see — a book whose MTM looks
excellent because winners are being held through a top that never realizes would show corrected
metrics passing while closed-only expectancy rots. If B and C ever disagree in that direction, C
wins and sign-off is refused.

**Explicitly NOT acceptable:** sign-off on the MTM view alone, without B's robustness tests and C's
trend check.

## What this proposal would decide today

Under A+B+C the book **passes**: **n=39 mature** — 27 closed (all resolved, so all count) plus
**12 of the 18 open** at ≥20 days *(computed; the other 6 are 7–19 days old and do not yet count)*
— against the ≥30 minimum, with all four corrected criteria met, sign survives leave-3-out and drop-`DIACABS`, and closed-only
expectancy is improving rather than deteriorating.

⚠️ **Stated plainly because it matters: this proposal, if adopted, flips a REFUSED verdict to a
PASS.** That is exactly the shape a reader should distrust — a bar rewritten after it failed. The
defence is that the censoring was flagged in the 2026-08-03 doc, *before* this re-run, and the
holding-period asymmetry is a measured property of the exit doctrine rather than an artifact of this
window. If that defence does not satisfy the owner, option 2 below is the honest alternative.

## Options

1. **Adopt A+B+C** as re-specified §0.5 #12, then re-run E1 against it. *(Recommended — it fixes a
   criterion that a trail-following book cannot satisfy by construction, and B+C keep it strict.)*
2. **Keep the bar as written** and wait for n≥30 closed. Slower, immune to the "moved the goalposts"
   objection, and it will keep sampling losses preferentially for as long as the book works.
3. **Adopt A only** — fix the sample-size starvation, keep quality metrics closed-only. Half a fix:
   n reaches 30 sooner, but the metrics stay biased against.

## Optional follow-up, independent of which option is chosen

To make a true pessimistic bound computable in future, the governing stop would have to be
**persisted** rather than cached in memory. That is real work (a column plus a tighten-only write
path, mirroring the guarantee `ManasGoverningStopCache` currently enforces in Java) and it is not
required by this proposal — noted so the gap is visible rather than rediscovered.
