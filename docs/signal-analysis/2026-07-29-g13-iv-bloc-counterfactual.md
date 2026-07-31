# G13 — the IV bloc: retrospective counterfactual (step 4 of the owner's "measure, then act")

**Written 2026-07-29.** Owner chose *measure first, then option 2*. This is the measurement.
**No production code changed. No dot dropped. Nothing armed.**

---

## Verdict

**Dropping `iv_pair` would have made 19.1% more rows clear the composite across 10 sessions; dropping
`iv_abs_band` as well takes it to 21.7% — and `iv_abs_band` is net-NEGATIVE on 9 of those 10
sessions, which is the finding that separates option 2 from option 1.** What the measurement does
**not** establish is whether firing more is *better*: the only forward evidence on loosening this
gate (T7) is negative.

⚠️ **Correction to my own recommendation.** I proposed registering a shadow variant. **The
shadow-variant vocabulary cannot express this** — `ShadowVariants` is per-rail overrides plus a
composite *floor*, re-scored against an already-computed composite
(`ShadowVariants.java:19-27`). Dropping a dot changes the composite *computation*, upstream in
`ConnectTheDotsScorer`. The plan as I stated it would not have worked.

The replacement is strictly better: the stored rejection diagnostics already carry **every dot with
its weight and `supports` flag**, so the counterfactual is a pure SQL re-aggregation over data
already on disk — instant, retrospective across 10 sessions, and zero risk, instead of waiting weeks
for forward data from a variant that would have needed new code first.

---

## 1. Model, and why it can be trusted

Composite = `Σ(w·supports) / Σw` over NON-ABSENT dots; an absent dot is withheld from **both**
numerator and denominator (`ConnectTheDotsScorer:236`).

The stored `diagnostic.confluence.dots[]` array **includes absent dots**, so they must be excluded
by hand. `iv_rank` is the absent one (NULL input, 11 sessions). The arithmetic confirms it: summing
every dot weight on a full row gives **19.6**, and 19.6 − 0.8 (`iv_rank`) = **18.80** — exactly the
denominator implied by the recorded 0.9574 cap.

**Validation: on 2026-07-29, the recomputed composite matches the stored `composite_score` on
983 of 983 rows** (±0.0002). The counterfactual rests on a model that reproduces live scoring exactly.

## 2. Results — 10 sessions, 8,431 context-bearing rows

| session | rows | pass now | drop `iv_pair` | drop both | `iv_abs_band` |
|---|---|---|---|---|---|
| 2026-07-15 | 246 | 114 | 116 | **118** | OUT |
| 2026-07-17 | 359 | 108 | 161 | **167** | OUT |
| 2026-07-20 | 748 | 230 | 232 | **244** | OUT |
| 2026-07-21 | 1070 | 218 | 284 | **290** | OUT |
| 2026-07-22 | 828 | 568 | 620 | **634** | OUT |
| 2026-07-23 | 1120 | 634 | 762 | **770** | OUT |
| 2026-07-24 | 1100 | 418 | 574 | **594** | OUT |
| 2026-07-27 | 909 | 253 | 280 | **292** | OUT |
| 2026-07-28 | 1068 | 0 | 0 | 0 | OUT |
| 2026-07-29 | 983 | 311 | **371** | 365 | IN |
| **total** | **8431** | **2854** | **3400 (+19.1%)** | **3474 (+21.7%)** | |

Two structural readings:

1. **`iv_pair` is pure drag — dropping it never lowers the pass count on any session.** Expected:
   it sits in the denominator and supports on 0% of rows, so removing it can only raise the ratio.
2. **`iv_abs_band` is net-NEGATIVE on 9 of 10 sessions.** On an OUT-of-band day it withholds 0.8
   from the numerator while still occupying the denominator — pure drag, exactly like `iv_pair`. On
   the single IN-band day (07-29) it hands out 0.8 free and dropping it costs 6 rows. **The dot is a
   per-day coin flip, and over this sample the coin landed against us 9 times out of 10.**

`2026-07-28` is 0 → 0 in every column, consistent with the recorded finding that the monthly-expiry
S24 suppression made the max ACHIEVABLE composite 0.5479 against a 0.600 threshold — no IV-bloc
change rescues an arithmetically impossible session.

⚠️ `iv_abs_band` is scored on only a SUBSET of rows (133 of 983 on 07-29) — it is per-strategy-tag,
not fleet-wide. An earlier note of mine said "every row"; that was wrong.

## 3. §4.2 counterfactual P&L — the number the row-count could not give

**The +21.7% headline collapses on contact with what actually BINDS.** Of the 620 rows that would
newly clear the composite, only **8** were blocked BY the composite at all — the other 612 were
stopped by a different rail, so raising their composite changes nothing. Deduped by
`(bar_time, tradingsymbol)`: **6 distinct legs across 10 sessions.**

The reason is stark. Blocking-rail distribution over the same 8,431 rows:

| rail | rows |
|---|---|
| `volume-floor` | **7,430** |
| `rsi-band` | 214 |
| `two-candle` / `pct-price-move` | 112 / 112 |
| … | |
| **`confluence-composite`** | **73** |

**The composite is the binding constraint on 0.9% of blocks. `volume-floor` is on 88%.**

Priced against `options_chain_snapshots` (15–16 ticks each over the 30-minute window), exit model
matching T1/G11 — TP +35%, SL −25%, else the 30-minute time stop:

| bar (IST) | leg | entry | exit | pts |
|---|---|---|---|---|
| 07-17 12:48 | `NIFTY2672124000CE` | 298.00 | 279.35 | **−18.65** |
| 07-23 10:00 | `NIFTY26JUL23700CE` | 295.50 | 316.35 | +20.85 |
| 07-23 10:12 | `NIFTY26JUL23750CE` | 279.70 | 280.25 | +0.55 |
| 07-23 11:00 | `NIFTY26JUL23750CE` | 287.25 | 251.20 | **−36.05** |
| 07-24 11:51 | `NIFTY26JUL23500CE` | 268.65 | 277.50 | +8.85 |
| 07-24 12:24 | `NIFTY26JUL23550CE` | 257.70 | 316.25 | +58.55 |

**4W / 2L, +34.10 points. ZERO take-profit touches, ZERO stop-loss touches** — all six resolved at
the time stop, the identical signature G11 found on its 41 legs.

⚠️⚠️ **The sign flips on ONE observation.** Remove the single best leg (07-24 12:24, +58.55) and it
is **3W/2L, −24.45 points**. Median leg +8.85. **Six legs is not a result, it is an anecdote** — it
does not support acting, and it does not support refusing either.

## 4. What this does NOT establish

**That firing more is better.** These are composite-pass counts, not fills and not P&L. Two limits:

- **Composite-passing ≠ firing.** These rows are rejections; many were blocked by a rail as well, so
  the newly-passing rows do not all become entries.
- ⚠️ **The only forward evidence on loosening this gate is NEGATIVE.** T7 tested the lower threshold
  and `composite-055` came back the WORST book of four at **−₹321/close** (−₹2,952.21 on 3 closes).
  Removing dead weight is arithmetically close to lowering the threshold — both make more rows pass.
  A +19% loosening should be read against that, not in isolation.

~~The honest next step is the §4.2 counterfactual P&L~~ — **done, §3 above.** It returned 4W/2L
+34.10 pts on **6 legs**, with the sign flipping on one of them. The measurement is complete; the
sample is too small to decide on.

⚠️ **Cross-link to G14.** More fires push the fleet toward shadow-book density, where convergence
runs 5–7 deep on one option key and the #1086 sub-account ceiling begins refusing. **G13 raises the
fire rate; G14 says the capital layer starts biting when it rises.** They are one system and a
decision on either should name the other.

---

## Claim labels

- §1 model + the 983/983 validation, §2 table: **computed** this session from
  `strategy.signal_rejections` on live `artha`, 2026-07-15 → 07-29.
- `ConnectTheDotsScorer:236` absent-dot rule, `ShadowVariants.java:19-27` variant vocabulary,
  `W_IV = 0.8`: **sourced**, read 2026-07-29.
- T7's −₹321/close and T1's 2W/9L: **sourced** from the 2026-07-29 findings / G1 ledger row, not
  re-derived here.
- The 0.9574 cap and the 18.80 denominator: **sourced** from the recorded findings, and independently
  **computed** to match in §1 — the two agree, which is why the model is trusted.
