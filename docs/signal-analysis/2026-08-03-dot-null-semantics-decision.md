# Dot-null semantics unification — decision sheet (F5 backlog row 6)

**Date:** 2026-08-03 · **Type:** decision document, read-only investigation (no production code changed)
**Backlog row:** `docs/signal-analysis/README.md` §7 row 6
**Measurement snapshot:** 2026-08-03 ~14:30 IST. **The 2026-08-03 session was still OPEN while this was
written** — its row counts moved from 968 → 988 during the investigation. Every 08-03 number below is a
mid-session snapshot, not a session total.

---

## 0. Verdict

**Recommend option 2 — `null = withhold from BOTH numerator and denominator` — for all dots, but adopt it
as a *correctness* change with a measured near-zero live footprint, paired with a coverage floor, and do
NOT arm it on the current shadow-book evidence, which is structurally incapable of producing it.**
`[computed]`

Three claims carry that verdict, each measured below:

1. **Withhold is the only semantic that is *true* about a missing input**, and it is the one the scorer's
   own arithmetic already means. `[sourced]`
2. **On live data since P3 shipped (2026-07-10), unifying to withhold flips ZERO bar-side outcomes** — in
   either direction — across 13,192 recorded confluence evaluations over 18 sessions, once the three
   decisive legs are respected. The change is therefore near-free to make and near-worthless to *arm* on
   its own. `[computed]`
3. **But the direction is not uniform, and where it IS a loosening it fires exactly when the data is
   worst.** On the two catastrophic-OI sessions in the window the aggregate would have risen on 1,812 of
   1,816 rows. Nothing fired only because the decisive legs happened to block; that is the tape's luck,
   not a guarantee. **Withhold without a coverage floor converts a data outage into a reason to trade
   more.** `[computed]` The standing prior (§7) applies here and only here.

**A coverage floor is required for the recommendation to be honest, and it is not a fitted parameter** —
the measured coverage distribution has an empty band from 0.828 to 0.947 with **zero rows in it** (§5.3).

---

## 1. STEP 0 — the backlog summary is wrong on 2 of its 3 examples

README §7 row 6 verbatim:

> **Dot-null semantics unification** — decide null = NEUTRAL-supports vs null = withhold vs
> exclude-from-denominator, ONCE, for all dots (today: `dow` null→supports, `ivRank`/`fii` null→against).
> Analysis keeps mis-reading dead-data dots as bearish evidence until this is uniform.

Verified against `origin/main` @ `a4cb8c63`:

| summary claim | verdict | evidence |
|---|---|---|
| `dow` null → supports | **TRUE in code, but the dot has never been live** | `ConnectTheDotsScorer.java:390-393` — `dowUp == null \|\| (ce == m.dowUp())`. The dot is behind the default-OFF `dow-confluence` tag (`ScalperConfluenceGate.java:1056`). **Zero** published enabled strategies carry the tag; **zero** repo YAMLs carry it; it appears in **0 of 13,192** live rejection rows since 2026-07-02. `[computed]` |
| `ivRank` null → against | **FALSE — stale since 2026-07-10** | `ConnectTheDotsScorer.java:342-350` marks a null `ivRank` **`absent`** — withheld from both numerator and denominator. Landed in `992abdc4` (#676, rollup P3) on 2026-07-10. **`iv_rank` is the one dot already on exactly the semantic row 6 proposes.** `[sourced]` |
| `fii` null → against | **FALSE twice — there is no `fii` dot** | `grep` for a `fii` dot in `ConnectTheDotsScorer.java` returns **nothing**. `fii` is (a) a **RAIL**, `ScalperGates.fiiBias:805-808`, whose null explicitly **DEGRADES TO PASS** — the opposite of "against"; and (b) a **canary probe name**, `DotHealthCanary.java:177`, which is the likely source of the confusion. `[sourced]` |

**The summary is also incomplete in the way that matters.** It names three dots. There are **18 default
dots plus 4 tag-gated ones**, and the summary never names the actual majority rule: **15 of the 18 default
dots score a missing input as `supports=false` while keeping full weight in the denominator** — i.e. as
evidence *against* the side. That class, not `dow`/`ivRank`/`fii`, is the subject of the decision.

`NullPolicy.java` (the enum's own javadoc, shipped 2026-08-01 in #1189) already carries a correct and
complete three-class map. **It, not the README row, is the accurate prior art** — and its counts
("fifteen of the default eighteen … seventeen once the `iv-per-strike` tag adds `iv_slope` +
`iv_abs_band`") reproduce exactly against the code. `[computed]` **README §7 row 6 should be corrected to
point at it.**

---

## 2. What every dot's null actually does today

Source: `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/scalper/ConnectTheDotsScorer.java`
@ `a4cb8c63`. `add(...)` at `:560-564` records `inputMissing` but under `NullPolicy.LEGACY` (the default,
`ScalperConfluenceGate.java:1069`) never converts it to `absent` — so the per-dot rule below is whatever
each dot's own `supports` expression produces. The scoring loop is `:411-424`. `[sourced]`

| # | dot | w | added | missing-input condition | rule producing the null result | **today's null semantic** |
|---|---|---|---|---|---|---|
| 1 | `vwap` | 2.5 | `:294-297` | `close` or `vwap` null | `gt()` `:587-589` → false | **OPPOSES** |
| 2 | `supertrend` | 1.0 | `:298-299` | `supertrendDir == 0` | `> 0` / `< 0` both false | **OPPOSES** |
| 3 | `vwma` | 1.0 | `:300-301` | `close` or `vwma20` null | `gt()` → false | **OPPOSES** |
| 4 | `psar` | 1.0 | `:302-303` | `close` or `psar` null | `gt()` → false | **OPPOSES** |
| 5 | `rsi` | 1.0 | `:304-305` | `rsi14` null | `ScalperGates.rsiBand:228-230` → `fail` | **OPPOSES** |
| 6 | `volume` | 1.0 | `:307-309` | `volume` null | `ScalperGates.volume:163` `ok = volume != null && …` | **OPPOSES** |
| 7 | `futures_oi` | 1.5 | `:310-311` | `futures() == NEUTRAL` | `oiQuadrant:582-586` → `OiQuadrant:27-34` both false | **OPPOSES** |
| 8 | `underlying_oi` | 1.0 | `:312-313` | `underlying() == NEUTRAL` | `.bullish()`/`.bearish()` both false | **OPPOSES** |
| 9 | `trending_cross` | 1.0 | `:316-317` | `ceOiDelta`/`peOiDelta` null | `trendingCross:457-466` → false | **OPPOSES** |
| 10 | `sentiment` | 1.0 | `:318-319` | `sentimentPct` null | `sideSigned:580-585` → false | **OPPOSES** |
| 11 | `drastic_oi` | 1.0 | `:321-322` | deltas null | `drasticOi:473-485` → false | **OPPOSES** |
| 12 | `sentiment_slope` | 1.0 | `:324-325` | `sentimentSlope` null | `sideSigned` → false | **OPPOSES** |
| 13 | `oi_spurt` | 1.0 | `:328-329` | spurt pcts null **or** quadrant NEUTRAL | `oiSpurt:491-499` → false | **OPPOSES** |
| 14 | `breadth` | 1.0 | `:330-331` | `advances == 0 && declines == 0` | `breadth:589-598` `count > 32` false | **OPPOSES** |
| 15 | `vix` | 1.0 | `:332-333` | `vixRising` null | `ScalperGates.vix:601-604` → **`GateOutcome.pass`** | **SUPPORTS** |
| 16 | `basis` | 1.0 | `:334-335` | `futuresBasis` null | `futuresBasis:852-856` → **`GateOutcome.pass`** | **SUPPORTS** |
| 17 | `iv_rank` | 0.8 | `:342-350` | `ivRank` null | explicit `absent = true` | **WITHHELD** |
| 18 | `iv_pair` | 0.8 | `:356-357` | `ceIvAvg6`/`peIvAvg6` null | `ivPair:505-512` → false | **OPPOSES** |
| T1 | `iv_slope` | 0.8 | `:361-365` *(tag `iv-per-strike`)* | side's `ivSlope` null | `!= null && signum()>0` | **OPPOSES** |
| T2 | `iv_abs_band` | 0.8 | `:368-372` *(tag `iv-per-strike`)* | `atmIv` null | `!= null && …` | **OPPOSES** |
| T3 | `premium_skew` | 1.0 | `:384-385` *(tag `premium-skew`)* | `premiumSkewPct` null | **`== null \|\|`** ⇒ true | **SUPPORTS** |
| T4 | `dow` | 1.0 | `:390-393` *(tag `dow-confluence`)* | `dowUp` null | **`== null \|\|`** ⇒ true | **SUPPORTS** |

**Tally (default 18):** 15 OPPOSES · 2 SUPPORTS · 1 WITHHELD. Full denominator 19.60; with `iv_rank`
withheld (its live state on 100% of rows) **18.80**. `[computed]`

**Live arming state** (`strategy.strategies` ⋈ `published_version_id`, 2026-08-03): `iv-per-strike` on 4
enabled strategies; `premium-skew` on 2; **`dow-confluence` on 0; `iv-rank-dot` on 0; `dot-null-withheld`
on 0.** `[computed]`

---

## 3. Method, and how it was validated

`inputMissing` is deliberately **not serialized** (`ConnectTheDotsScorer.java:63-65`), so it was
reconstructed from the raw `diagnostic.context.{chart,oi,macro}` operands by transcribing each condition
in the table above into SQL, then recomputing the aggregate as
`Σ(w·supports over !absent) / Σ(w over !absent)`.

**Validation gate: the recomputed aggregate matches the engine's own recorded
`diagnostic.confluence.aggregate` on 13,192 of 13,192 rows — mismatch = 0.** `[computed]` Two era
corrections were needed to get there, and each one initially produced a *silently wrong* answer:

- `absent` is serialized **only from 2026-08-03** (F5 U4a, first session after the 2026-08-02 deploy).
  `COALESCE(absent, false)` on older rows nulled out every filter and reported "no missing inputs" on the
  two sessions that had the most.
- P3 (`iv_rank` → withheld) shipped 2026-07-10, so sessions 07-02…07-10 legitimately counted `iv_rank` in
  the denominator and later ones do not. The era rule is `COALESCE(absent, dot='iv_rank' AND ses >= '2026-07-15')`.

**Population caveat.** The dots are only scored on bars that reach the confluence gate, and only two
writers record them: `signal_rejections` (confluence-blocked) and `signals.fired_diagnostic`. On
2026-08-03 that is 988 confluence-bearing rejections + 7 fired against 3,247 total evaluations
(`strategy.strategy_eval_denominator`) — **~31% of evaluations**. A chart-stage block never consults a
dot, so its null state is both unobservable and irrelevant. Every rate below is per *scored* evaluation.
`[computed]`

**Excluded sessions** (per the brief's traps, both confirmed):
- **2026-07-28 — NSE MONTHLY EXPIRY** (last Tuesday of July 2026, computed). `MarketOiClient.oi()` skips
  the entire OI block by design, so all 7 OI dots read missing on 100% of rows. Not a data fault.
- **2026-07-20 — the TimescaleDB 2.18.2 sorted-merge planner outage** (`docs/signal-analysis/2026-07-20-session-findings.md` §6.2).
  Same 100% OI-null signature, from a bug since fixed and regression-guarded.

Both are retained in the tables, labelled, and reported separately — they are the *only* sessions where
the opposes-in-denominator class is exercised at scale, so discarding them silently would have hidden the
one real risk.

---

## 4. How many evaluations have a null dot, per dot

Post-P3 window (2026-07-15 → 2026-08-03), n = **11,068** scored evaluations; "clean" excludes the two
sessions above, n = **9,252**. `[computed]`

| dot | missing, all sessions | missing, clean | **clean rate** | driver |
|---|---:|---:|---:|---|
| `iv_rank` | 11,068 | 9,252 | **100.000%** | honest-null (60-day IV history floor) — **already withheld** |
| `futures_oi` | 1,816 | 0 | 0.000% | 07-20 outage + 07-28 expiry only |
| `underlying_oi` | 1,816 | 0 | 0.000% | ″ |
| `oi_spurt` | 1,816 | 0 | 0.000% | ″ |
| `sentiment` | 1,068 | 0 | 0.000% | 07-28 expiry only |
| `sentiment_slope` | 1,068 | 0 | 0.000% | ″ |
| `drastic_oi` | 1,068 | 0 | 0.000% | ″ |
| `trending_cross` | 1,068 | 0 | 0.000% | ″ |
| **`vix`** | 142 | **38** | **0.411%** | sporadic; **currently null→SUPPORTS** |
| `iv_pair` | 24 | 0 | 0.000% | 07-28 only |
| **`iv_abs_band`** | 16 | **7** | **0.518%** (of 1,645 tagged rows) | sporadic; null→OPPOSES |
| `vwap` `supertrend` `vwma` `psar` `rsi` `volume` `breadth` `basis` `iv_slope` `premium_skew` | 0 | 0 | **0.000%** | never missing in the window |

**The headline the README does not say:** apart from `iv_rank` (already withheld), **45 of 9,252 clean
rows — 0.49% — have any missing dot input at all**, and the *dominant* one (`vix`, 38 rows) currently
reads as **SUPPORT**, not as the bearish evidence row 6 describes. The "dead-data dots read as bearish"
mis-read is real, but at scale it exists **only** on the two incident sessions. `[computed]`

---

## 5. What each option changes — legs first

### 5.1 Bar-side outcome flips under option 2 (withhold)

A rejection becomes a signal only if the recomputed aggregate clears the threshold **and** the three
policy-independent decisive legs hold (`decisiveLegsHeld`, `ConnectTheDotsScorer.java:440`) **and** every
other rail passed (`blocking_rail = 'confluence-composite'`).

| session | n | rows w/ missing | agg ↑ | agg ↓ | scalar flip ↑ | flip ↓ | **+ legs held** | **would have FIRED** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 07-02 … 07-10 *(pre-P3 era)* | 2,104 | 2,104 | 2,090 | 14 | 225 | 0 | 207 | **4** |
| 07-15 | 246 | 7 | 7 | 0 | 0 | 0 | 0 | **0** |
| 07-17 | 359 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| *07-20 (Timescale outage)* | 748 | 748 | 748 | 0 | **54** | 0 | **0** | **0** |
| 07-21 | 1,070 | 2 | 0 | 2 | 0 | 0 | 0 | **0** |
| 07-22 | 828 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| 07-23 | 1,120 | 12 | 0 | 12 | 0 | 0 | 0 | **0** |
| 07-24 | 1,100 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| 07-27 | 909 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| *07-28 (monthly expiry)* | 1,068 | 1,068 | 1,064 | 4 | **14** | 0 | **0** | **0** |
| 07-29 | 983 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| 07-30 | 814 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| 07-31 | 835 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| 08-03 *(partial)* | 988 | 24 | 0 | **24** | 0 | 0 | 0 | **0** |
| **post-P3 total** | **11,068** | **1,861** | **1,819** | **42** | **68** | **0** | **0** | **0** |

**Post-P3, unifying to withhold changes ZERO bar-side outcomes.** All 4 "would have fired" rows are in the
pre-P3 era, i.e. they are the P3 change itself, which shipped 2026-07-10 and is already realised in
production. `[computed]`

The 68 scalar flip-ups are all in the two incident sessions, and **all 68 were blocked by a decisive leg**
— which is exactly why the scalar must never be read as the verdict (`ShadowVariants.armedPolicyCouldHaveFired`).

*Conservatism note:* `vwapHardGate` is not serialized, so `decisiveLegsHeld` was approximated as
`vwapAligned && biasAligned && !standAside`. The #9 Morning-Trade path before 10:30 IST drops the hard
VWAP leg, so the true `legs held` count is **≥** the figure above. Since the figure is 0 post-P3, the
approximation can only have *under*-counted flips — and it did not, because `flip_up` is itself 0. `[computed]`

### 5.2 Option-by-option

| | **1 — null = NEUTRAL-supports** | **2 — null = withhold** *(recommended)* | **3 — exclude from denominator only** |
|---|---|---|---|
| What it means | "we could not ask, so assume yes" | "we could not ask, so do not count it" | numerator keeps the `false`, denominator drops the weight |
| Truthful about a gap? | **No** — asserts agreement never observed | **Yes** | **No** — strictly *worse* than either: mathematically identical to withhold, since a withheld dot contributes 0 to the numerator anyway *only when* `supports=false`; where `supports=true` it silently deletes a real support |
| Post-P3 outcome flips | **not computed** — see below | **0** of 11,068 | equal to option 2 on today's data |
| Direction vs the standing prior | **pure loosening on 15 of 18 dots** — a dead feed becomes 100% agreement. On 07-28 this drives the aggregate toward 1.0 on 1,068 rows | **mixed**: tightening on `vix`/`basis`/`premium_skew`/`dow`, loosening on the other 15+2 | ″ |
| Verdict | **REJECT** | **ADOPT (with §5.3)** | **REJECT** — same arithmetic as 2 in the live case, strictly less safe in the general case, and no simpler |

Option 1 was not measured further, deliberately: it is the maximal loosening of the entry gate on 15 of 18
dots, and per the standing prior every measured loosening (T1/T7/G13/G10) has lost money. Measuring it to
three decimal places would not change that it is the wrong direction to move on a data gap.

**Option 3 is not actually a third option.** Written out, "exclude from denominator" for a dot whose
`supports` is already `false` *is* withholding. It differs from option 2 only for dots where a missing
input yields `supports=true` — `vix`, `basis`, `premium_skew`, `dow` — where it would keep the free
support in the numerator while removing its cost from the denominator, i.e. **the most loosening variant
of all three**. Row 6 should stop listing it as distinct. `[computed]`

### 5.3 The coverage floor — required, and empirically located

Withhold has one perverse property: **the less data arrives, the higher the composite can go**, because
the denominator shrinks faster than the numerator whenever the surviving dots agree. The degenerate case
is already fail-closed (`ratio()` `:448-450` returns ZERO on an empty denominator), but the *intermediate*
case is not: on 07-28 the whole OI plane vanished and 1,064 rows' aggregates rose.

Distribution of `surviving weight ÷ legacy weight` over the 11,068 post-P3 rows: `[computed]`

| coverage band | rows | sessions | reading |
|---|---:|---:|---|
| exactly **1.000** | 9,207 | 11 | nothing missing |
| **0.947 – 0.961** | 45 | 4 | exactly one dot missing (`vix` → 0.947, `iv_abs_band` → 0.961) |
| **0.828 – 0.947** | **0** | **0** | **empty band, 11.9 points wide — the natural break** |
| **0.761 – 0.828** | 748 | 1 | 2026-07-20 Timescale outage (OI cluster gone) |
| **0.548 – 0.632** | 1,068 | 1 | 2026-07-28 monthly expiry (OI cluster + deltas gone) |

Total 9,207 + 45 + 748 + 1,068 = 11,068. The middle band is not sparse — it is **empty**.

**A coverage floor anywhere in [0.85, 0.94] separates "one dot happened to be missing" from "a whole data
plane is gone" with zero rows in the gap.** That is a natural break in the data, not a fitted parameter —
`0.90` sits dead centre. Below the floor the confluence should be **invalid** (the existing `standAside`
shape is the natural home), not merely lower. Without it, unifying to withhold means a Timescale planner
regression or an unexpected OI suppression *raises* every composite on the tape.

---

## 6. P&L — cannot be reached at this n, and why

**No P&L figure can be attached to this decision from the evidence available.** `[computed]` Three
independent reasons, each sufficient:

1. **There are no counterfactual trades to price.** Post-P3, zero bar-side outcomes flip (§5.1). A change
   that opens no new position and closes no existing one has no P&L by construction.
2. **The `dot-null-withheld` shadow variant has produced ZERO rows — and structurally could not have
   produced any on the data it has seen.** It is registered and live (`strategy.shadow_variant_registry`
   id `425d790c…`, `enabled=t`, created 2026-08-02 01:49 IST; boot log `shadow-variant active set now:
   [vol-off, vol-12k5, composite-055, dot-null-withheld]`). But **2026-08-02 was a Sunday**: the variant's
   first and only trading session is **2026-08-03, still open at snapshot time**. On that session the only
   non-`iv_rank` missing input is `vix` (24 rows), and withholding `vix` *lowers* the aggregate — so
   `withheldAggregate ≤ aggregate` on **every** row. `ShadowVariants.compositeFor` clamps the variant to
   `max(champion, withheld)` (`:297-303`), which by design makes a lowering **invisible**. **The evidence
   lane, as built, cannot produce evidence in the direction today's data actually moves.** That is a
   design consequence the code documents honestly (`:288-296`: "this plane measures the PROMOTION half
   only and CANNOT authorize arming"), not a defect — but it means the shadow book will stay empty until a
   session arrives with a *missing opposes-class* dot, and the only two such sessions in five weeks were an
   outage and an expiry.
3. **The fired side has n = 4.** `withheldAggregate` is serialized on `signals.fired_diagnostic` only from
   2026-08-03; 4 fired rows carry it, all with `withheldAggregate == aggregate`, so **0 fired signals would
   have been lost**. Four rows is not evidence.

**The only adjacent outcome data, and what it is not.** The shadow book's closed positions rank
monotonically by looseness: `[computed]`

| variant | closed | avg PnL % | net |
|---|---:|---:|---:|
| `champion` | 289 | **−1.56%** | −70,243 |
| `vol-off` (loosest volume) | 62 | −2.44% | −26,499 |
| `vol-12k5` | 47 | −2.86% | −18,891 |
| `composite-055` (lower composite floor) | 18 | **−3.55%** | −9,999 |

This is directionally consistent with the standing prior — the closest analogue to "lower the effective
bar", `composite-055`, is the worst — but **it is about different knobs and it is not evidence about this
change.** n = 18 on the closest comparator. Cited for context only. **⚠️ It is also a selection statistic,
not an outcome test for dot-null semantics**: it measures books that *were* selected differently, not this
rule's effect.

---

## 7. The standing prior, applied precisely

> *Every measured loosening of the scalper ENTRY gate has LOST money — T1/T7/G13/G10.*

Where it applies, stated explicitly:

- **Option 1 (null → supports) is a loosening on 15 of the 18 default dots.** It is the single most
  loosening choice available and should be rejected on the prior alone.
- **Option 3 is a loosening on the 4 supports-on-null dots** and identical to option 2 elsewhere. Reject.
- **Option 2 is a loosening for the 15+2 opposes-class dots and a TIGHTENING for `vix`/`basis`/
  `premium_skew`/`dow`.** On the clean live population the tightening side dominates by rows (38 `vix` vs
  7 `iv_abs_band`), so on *today's* tape option 2 is net-tightening. On the two incident sessions it is
  overwhelmingly loosening. **The coverage floor (§5.3) is what confines it to the tightening regime**;
  without the floor, the prior applies at full force to precisely the sessions where the data is worst.
- **Nothing here is recommended on pass-rate evidence.** The recommendation rests on (a) a semantic
  correctness argument and (b) a measured **zero** outcome delta. It does not claim the change makes
  money, and it must not be sold that way.

**⚠️ Selection vs outcome.** §5.1 is a *selection* measurement — it counts which bars would change side.
It is a legitimate basis for the claim "this change is inert on live data" (a null result about selection
is a null result about outcome, since an unchanged selection cannot change P&L). It is **not** a basis for
any claim that the unified rule is *better*. No forward-outcome test of this rule exists yet.

---

## 8. The fourth semantic — "present but dead"

Measured live 2026-08-03 (#1242): the OI sentiment operand is **exactly 0.00 in ~34% of SENSEX 3-minute
buckets**. `sideSigned` (`ConnectTheDotsScorer.java:580-585`) returns `ce ? signum() > 0 : signum() < 0`,
so **an exactly-zero operand fails BOTH sides at once** while charging full weight to the denominator.

Independently confirmed on the rejection population: `sentimentPct = 0` occurs on 0–6.4% of NSE-root rows
per session (72/1,120 on 07-23) and 20–25% of the small BSE-root sample (11/54 on 07-20, 5/20 on 07-15).
`sentiment_slope = 0` on 0–2.7% (22/828 on 07-22). **⚠️ The BSE-root rejection sample is tiny (≤54 rows/session) and
vanishes after 07-20, so this population cannot corroborate the 34% figure — the 3m-bucket measurement in
#1242 is the better instrument for that claim.** `[computed]`

**Recommendation: NO — keep it out of row 6, and make it a separate, parallel decision.** `[computed]`

- **The diagnosis is different.** Row 6 asks *did the input arrive?* This asks *did the operand
  discriminate?* A genuinely balanced tape producing 0.00 is real information ("neither side"), not a gap.
  Treating 0 as missing would silently withhold real neutrality and would be a **loosening justified by a
  falsehood**.
- **The scoring consequence should nonetheless be the same** once each is diagnosed: a dot that cannot
  support either side must not charge the denominator on both. The right shape for this class is a
  **deadband/magnitude floor** that makes the dot explicitly *inconclusive* → withheld, not a redefinition
  of null.
- **It cannot be decided before #1242 lands.** If that PR's evidence leads to swapping `sentimentPct` for
  the level-based `sentimentLevelPct` (0–2 sign flips per session vs 19–21), the exactly-zero class
  largely disappears for `sentiment` and the remaining surface shrinks to `sentiment_slope` and `basis`.
  **Deciding a deadband now would be tuning against an operand that may be replaced.**

**#1242 corroborates row 6's principle one level down**, and this is worth recording: its stated design
rule is *"Missing level ⇒ `null` verdict, not `false` … 'could not evaluate' and 'the level says no' are
different facts, and collapsing them would bias the measurement toward the incumbent precisely where data
is thin."* That is exactly option 2's argument, arrived at independently. `[sourced]`

---

## 9. Dependencies on #1242 (open, mid-review)

`#1242 feat(strategy-signal): shadow-measure the level-based OI sentiment operand`, branch
`feat/scalper-sentiment-level-shadow`, **OPEN**. It touches `ConnectTheDotsScorer.java`,
`FiredDiagnosticJson.java`, `MarketOiClient.java`, `ScalperConfluenceGate.java`, `ScalperGateContext.java`
and adds `V056__exit_oracle_shadow.sql`. **Nothing in this document edits any file.**

Where the recommendation depends on it, and where it does not:

| depends? | item |
|---|---|
| **No** | §0 verdict, §2 per-dot table, §4 null rates, §5.1 zero-flip result, §5.3 coverage floor. #1242 is measurement-only and adds no dot, so it cannot move the null rates or the flip count. |
| **Yes — blocking** | §8. The deadband decision must wait for #1242's verdict on whether the flow operand is replaced. |
| **Yes — sequencing only** | Any implementation of §5.3 touches `ConnectTheDotsScorer.score`, which #1242 also edits. **Land #1242 first**; a coverage floor rebased onto it is a clean additive change, the reverse is a conflict. |

---

## 10. Recommendation, as a sequence

1. **Correct README §7 row 6** to point at `NullPolicy.java`'s three-class map, drop `fii` (not a dot),
   drop `ivRank` (already on the target semantic since #676), note that `dow` is armed nowhere, and
   collapse "exclude-from-denominator" — it is not a distinct third option (§5.2). *Docs-only.*
2. **Adopt `NullPolicy.WITHHELD` as the intended single semantic** — the decision this document asks the
   owner to make. Do **not** arm it yet.
3. **Land #1242**, then add a **coverage floor** (§5.3) to the scorer, default-OFF, floor `0.90`. Without
   it the unified rule is unsafe in exactly the sessions that matter.
4. **Only then arm `dot-null-withheld`**, and arm the *floor together with the policy* — never the policy
   alone. Expected live effect on today's data: **zero signals gained or lost** (§5.1). If arming produces
   a visible change in signal counts, that is a signal something else moved, and it should be investigated
   rather than accepted.
5. **Do not wait on the shadow book for a verdict.** §6 shows it cannot supply one for the direction the
   data moves. If outcome evidence is genuinely wanted before arming, the instrument is a paired
   counterfactual replay or paper A/B covering the *tightening* half, which the rejection-only writer
   cannot observe — a separate build, and arguably not worth it for a change measured at zero flips.

---

## Open doubts

1. **n is small where it matters, and the two informative sessions are both pathological.** The
   opposes-class null was exercised on exactly 2 of 18 sessions, and both were incidents (a fixed
   Timescale bug; a by-design expiry suppression). The "zero flips" result is therefore strong for
   *normal* tapes and weak for *degraded* ones — which is the case §5.3's floor exists to cover. A third,
   *different* degradation mode could behave unlike either.
2. **`decisiveLegsHeld` was approximated.** `vwapHardGate` is not serialized. The approximation can only
   under-count flips, and the count is 0, so the conclusion is safe — but a future re-run on a tape with
   non-zero flips must not reuse this shortcut. Serializing `vwapHardGate` (or `decisiveLegsHeld` itself)
   on the rejection diagnostic would remove the caveat cheaply.
3. **The 2026-08-03 session was open during measurement** (988 rows at snapshot, rising). Its numbers are
   partial. Nothing in the verdict turns on the last hour of one session, but the 08-03 row in every table
   is a snapshot, not a total.
4. **The pre-P3 era rule is reconstructed, not read.** `absent` is unserialized before 2026-08-03, so the
   07-02…07-10 rows use an inferred `iv_rank` rule. The inference is validated by mismatch = 0 against the
   engine's own recorded aggregate on all 13,192 rows, which is strong — but it is inference.
5. **Twelve live strategies carry `oi-confluence-exit`**, which re-evaluates this same scorer on the
   **exit** path. Arming the policy would change exits too. Neither the shadow book nor anything in this
   document models that, and `ShadowVariants:294-296` flags it explicitly. **The zero-flip result is about
   ENTRIES only.**
6. **The 0.90 coverage floor is located, not validated.** The empty band [0.828, 0.947] is measured, but
   "invalid below the floor" has never been run live. It is fail-closed (it can only *block*), so the
   downside is bounded — but it would silence the OI dots' contribution entirely on an expiry day, which
   is arguably already the case and arguably a behaviour change worth its own note.
7. **`iv_pair` supports on 0 of 968 rows today** with non-null inputs — a present-but-failing dot, not a
   null one, so out of scope here. Flagged because a 0.8-weight dot that never supports is its own
   finding, and it is not what row 6 is about.
