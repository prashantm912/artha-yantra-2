# G10 — volume-floor baseline bake-off: the recommendation I gave was wrong

**Written 2026-07-29.** Owner chose *test the time-of-day profile too, then build the winner
default-OFF*. This is the test. **No production code changed yet.**

---

## Verdict

**The time-of-day profile wins, and the prior-session seed I recommended earlier today is WRONG —
it does not under-fix the bias, it REVERSES it.** Under the seed the session open becomes ~2× EASIER
than the rest of the day, which would admit exactly the low-quality opening entries the floor exists
to filter. Building it would have replaced one defect with its mirror image.

Measured over 21 sessions on the front NIFTY future, 3m buckets, floor = `1.5 × baseline`,
pass = `bar volume ≥ floor`:

| baseline | open 90 min pass | rest-of-day pass | all day | open ÷ rest |
|---|---|---|---|---|
| **status quo** (20-bar in-session, truncating) | **11.9%** | 30.9% | 26.3% | **0.39** — open 2.6× HARDER |
| **prior-session seed** (flat all day) | **57.8%** | 29.5% | 36.3% | **1.96** — open 2× EASIER |
| **time-of-day profile** (same bucket, prior 5 sessions) | **28.8%** | 36.3% | 34.5% | **0.79** — closest to uniform |

The metric is chosen to match how the defect was *defined*: the floor should be about as restrictive
at 09:45 as at 13:45. Neither the status quo nor the seed is; the profile very nearly is.

No look-ahead in any estimator — the seed reads session *n−1*, the profile reads sessions *n−5…n−1*.

---

## 1. How the status quo fails, at 21× the original sample

The opening-10-bar median over the session median, per session:

- **mean 3.52×, worst 8.42×, best 1.14×**
- **over-estimates on 19 of 21 sessions**, and never once under-estimates

So it is a **systematic, one-directional** over-estimate, not a bad-day artifact. That confirms the
level-shift diagnosis at 21 sessions rather than the single session it was filed on.

## 2. Why the seed fails — and why the failure was invisible until now

Session-to-session volume is genuinely volatile. Prior-session median vs actual, 20 sessions:

- **mean error 1.44×, worst 2.71×**, spread **0.49 – 2.71**

That is a big improvement on 3.52× *as an estimate of the session's own median*, which is why it
looked right. But the floor is not judged on estimating a median — it is judged on **restrictiveness
across the day**, and a FLAT baseline cannot track a shape that rises at the open. Against the real
metric it overshoots to 57.8% open pass vs 29.5% rest.

⚠️ **Rolling a longer lookback does NOT rescue it.** A 5-session rolling median is no better on the
worst case (2.71× both) and *worse* on days over 1.5× (7 vs 5). Volume is not predictable from
recent history, so "use more history" is a dead end for a flat baseline — the shape is the thing,
not the level.

## 3. Why the profile wins

The opening surge is a *recurring daily shape*, so the physically-correct baseline is per-time-of-day.
A profile scales the floor with that shape, so a busy 09:18 is measured against other 09:18s rather
than against a 13:00. Its residual bias (0.79) is the smallest of the three by a wide margin, and it
also lifts all-day pass 26.3% → 34.5% without concentrating the loosening at the open.

## 4. Confound checked and cleared

The signal series is the **dated front future, which rolls**, so a history-based baseline could in
principle carry the old contract's volume into the new one. Measured: the roll is **gradual over ~3
sessions**, not a cliff (`NIFTY26AUGFUT` builds 2,145 → 7,215 → 26,520 → 23,790 as `NIFTY26JULFUT`
fades), and the front is selected per session by traded volume. No roll-day blow-up in any estimator.

---

## What this does NOT establish

**That a uniform floor is more PROFITABLE.** This measures restrictiveness bias, not P&L. The G13
counterfactual is the cautionary precedent: a +21.7% headline collapsed to 6 legs once "what actually
binds" was accounted for. Here the direction is better — `volume-floor` IS the binding rail on
**7,430 of 8,431** blocked rows (88%) — so the leverage is real, but the sign of the P&L is untested.

**Next step before arming: the §4.2 counterfactual P&L** on the legs the profile would newly admit,
the same method that settled T1 and G13.

⚠️ Build default-OFF behind a tag (`cfg.has(tag)`, the house pattern) so nothing changes until the
owner arms it, and so the counterfactual can be run against a real implementation.

---

## Claim labels

- Every table: **computed** this session from `marketdata.candles` on live `artha`,
  2026-07-01 → 07-29.
- The 88% binding-rail figure: **computed** this session from `strategy.signal_rejections`.
- `1.5 × median`, window 20, minBars 10, absolute fallback 125,000: **sourced**
  (`ScalperGates:188-202`, `ScalperOiProps:102-104`), read 2026-07-29.
- "The profile is the physically-correct model": **assumed** — an argument from the recurring shape,
  supported by the bias metric but not proven optimal against any other family of estimators.
