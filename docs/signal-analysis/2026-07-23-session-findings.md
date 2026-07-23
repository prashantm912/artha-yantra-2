# Session findings — 2026-07-23 (data date)

Analysis date: 2026-07-23 (scheduled post-market agent, ran 16:20–17:20 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,430** (09:19–15:19 IST), signals fired **0**, paper positions
opened **0**, shadow positions **35** (26 champion + 9 challenger, all closed).
Session character: **flat/choppy day.** `NIFTY26JULFUT` opened 23,880.00 and closed **23,886.00**
(**+6.00 pts, +0.03%**), range 23,808.60–23,996.00 (187 pts). `SENSEX26JULFUT` 76,324.00 →
76,465.55 (+141.55). `NIFTY 50` 23,905.50 → 23,871.85. **The tape was 2.7× thicker than 07-22**
(3m volume median 36,595 vs 13,520) — the first genuinely liquid session in this folder for a week.

This file folds in the three earlier read-only runs of the same date: `2026-07-23-open-gate.md`,
`2026-07-23-midday-gate.md`, `2026-07-23-live-watch-findings.md`.

---

## 0 Read this first — the session's headline

**Three things, in order of weight.**

1. **NEW STRUCTURAL FINDING — 30 of the 38 live scalpers have NO premium exit rule at all.** Only
   **21 of 63** scalper YAMLs carry a `premium_pct` block (the `gap-theory`, `market-movers`,
   `hero-zero`, `btst-stbt` and `straddle` families). Every `golden-crossover`, `connect-the-dots`,
   `two-candle`, `trending-oi`, `trend-change` and `open-high-low` slug — i.e. the slugs that
   generate almost all of the would-have-fired population — has **no take-profit and no premium
   stop**. Their shadow positions carry `take_profit IS NULL` and `stop_loss IS NULL` and can only
   exit by structural stop (where set) or the 15:12 square-off. All **8** `TAKE_PROFIT` closes in
   the book's entire history belong to `gap-theory` / `market-movers` (§6.1). **This invalidates the
   "+35% take-profit" assumption in every prior §4.2 counterfactual in this folder**, including
   07-22's +2,547.6-point headline. Filed as **T21**.

2. **T16 (the `relative-volume-floor` regression) is UNRESOLVED for a third session — and today it
   SAVED money.** The registry is byte-unchanged since the 2026-07-20 21:28 republish: 18 PE slugs
   + 10 sensex CE slugs still run the fixed **125,000** floor. It first-blocked **1,065 of 1,430
   rows (74.5%)** and **595 of the 634 composite-passing rows**. The 22 rows it **alone** vetoed
   resolve **6 WOULD-WIN / 16 WOULD-LOSE, −451.2 premium points** (§5.1) — the exact inverse of
   yesterday's 56W/30L. **Third alternation in three sessions** (07-21 protective, 07-22 expensive,
   07-23 protective). The case for T16 remains "an armed knob was silently disarmed", never "loosening
   pays".

3. **Full-coverage session on every axis.** 38 of 38 loaded scalpers emitted — **a first**. The
   15-minute interior has **no empty bucket** (3rd consecutive clean session), `subscriber_health_events`
   is **empty**, `ay_signal_eval_failures_total = 0`, and **zero misaligned 1m candles** (T19 is
   quiet because there was no feed outage to backfill). The OI quadrants were live for a **third**
   session running.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-20 | 2026-07-21 | 2026-07-22 | **2026-07-23** |
|---|---|---|---|---|
| rejections | 1,013 | 1,372 | 1,042 | **1,430** |
| distinct strategies emitting | 49 | 22 | 36 | **38** ✅ |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| scalpers loaded by the engine | 63 | 38 | 38 | **38** |
| **coverage ratio (§3.10)** | — | 22/38 | 36/38 | **38/38 — full, a first** |
| signals fired | 1 | 0 | 0 | **0** |
| paper positions opened | 0 | 0 | 0 | **0** |
| bar-time coverage | 10:19–15:19 + holes | 09:21–14:57 | 09:18–15:18 | **09:18–15:18, no hole** ✅ |
| composite ≥ threshold rows | 230 | 218 | 568 | **634** (416 PE / **218 CE**) |
| scored rows | 748 | 1,070 | 828 | **1,120** |

**Engine load (§3.10) — read the same day, logs intact (no restart since the 08:37 boot):**

```
03:08:03Z (08:38 IST)  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
03:09:06Z (08:39 IST)  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

The known F10 cold-start shape a third session running; the #874 retry recovered 63 s later, well
before the open. Health signal `unresolved == 0` holds.

**Eval counters (actuator :8082, read 16:44 IST — no post-close deploy has run):**

| outcome | 09:59 (live watch) | 12:42 (midday gate) | **16:44 (final)** |
|---|---|---|---|
| `chart-gate-failed` | 120 | 1,132 | **1,986** |
| `composite-below-threshold` | 12 | 44 | **154** |
| `confluence-blocked` | 84 | 928 | **1,430** |
| `fired` | 0 | 0 | **0** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 | 0 | **0** |
| **Σ** | 216 | 2,104 | **3,570** |
| `ay_signal_eval_failures_total` | 0 | 0 | **0** |

`confluence-blocked` = 1,430 = the row count exactly; the two views agree.
`ay_signal_eval_duration_seconds`: count **375**, sum **1,407.07 s** ⇒ mean **3.75 s**/eval. The
21.68 s outlier flagged in the live watch (§7.2 carry) did **not** recur — the `_max` gauge is a
sliding window and read 0.0 at close, so the max cannot be re-derived post-hoc; count 375 = one
eval per minute-bar cycle, no stall.

**First-blocking-rail histogram** (1,430 rows) — **15 distinct rails, the widest tail recorded**:

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **1,065 (74.5%)** | 40,343.1 | 112,968.2 | −72,625.1 |
| time-window | 268 (18.7%) | — | — | — |
| time-of-day-preference | 28 | — | — | — |
| option-side-constraint | 14 | — | — | — |
| vwap-distance | 10 | 0.0041 | 0.0040 | **+0.00028** ⚠ |
| rsi-band | 5 | 58.71 | — | — |
| pct-price-move | 5 | 0.34 | 1.00 | −0.655 |
| two-candle / supertrend-15m | 5 each | — | — | — |
| divergence-vol-gate / volume-pump | 5 each | 67,795.0 | — | — |
| directional-change-gate | 5 | 0.04 | — | — |
| confluence-composite | 5 | 0.57 | 0.60 | −0.031 |
| oi-cross-required | 3 | 111.44 | — | — |
| call-put-delta-filter | 2 | 23.24 | 50.00 | −26.76 |

**All-failed-rails expansion (§3.3)** — top 10:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | 1,075 | 40,538.2 | 113,080.1 |
| confluence-composite | FAIL_CLOSED | 704 | 0.555 | 0.600 |
| **strike-pick** | FAIL_CLOSED | **390** | — | — |
| rsi-band | FAIL_CLOSED | 350 | 35.76 | — |
| time-window | FAIL_CLOSED | 268 | — | — |
| divergence-vol-gate / trend-change | FAIL_CLOSED | 166 each | 41,416.0 / — | — |
| two-candle | FAIL_CLOSED | 140 | — | — |
| volume-pump | FAIL_OPEN | 140 | 41,811.7 | — |
| pct-price-move | FAIL_OPEN | 140 | 0.130 | 1.000 |
| oi-cross-required | FAIL_CLOSED | 128 | 130.11 | — |

`strike-pick` at 390 fails is new to this folder's top-10 (07-22 did not list it at all). It is a
FAIL_CLOSED leg-resolution rail, so those rows never reach a shadow position — consistent with the
`wouldBeLeg`-less population. Not investigated further; flagged for the next session to bucket by
slug and time.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 ⚠⚠ T16 UNRESOLVED — the PE book is still on the fixed 125,000 floor (3rd session)

**Registry state, standing §3.14 query, run today — unchanged from 07-21 and 07-22:**

| carries `relative-volume-floor` | published on | count | family |
|---|---|---|---|
| **yes** | 2026-07-06 | 10 | all nifty CE |
| no | 2026-06-29 / 06-30 | 10 | all sensex CE (the old T11) |
| **no** | **2026-07-20 21:28:5x** | **18** | **all `-pe`, nifty AND sensex** |

**Row proof, same session, both shapes side by side:**

| slug | n | min thr | max thr | avg operand |
|---|---|---|---|---|
| `scalp-connect-the-dots-nifty` (CE, armed) | 24 | **46,848.75** | **65,032.50** | 30,631 |
| `scalp-gap-theory-nifty` (CE, armed) | 21 | **50,115.00** | **65,032.50** | 30,795 |
| `scalp-hero-zero-nifty` (CE, armed) | 5 | **45,971.25** | **59,036.25** | 36,985 |
| `scalp-connect-the-dots-nifty-pe` | 54 | 125,000.00 | 125,000.00 | 43,767 |
| `scalp-golden-crossover-nifty-pe` | 40 | 125,000.00 | 125,000.00 | 45,024 |
| `scalp-connect-the-dots-sensex-niftyoi` (CE, never armed) | 29 | 125,000.00 | 125,000.00 | 37,039 |
| … (all 18 `-pe` + all 10 sensex CE slugs at a flat 125,000) | | | | |

**On a 2.7×-thicker tape the 125,000 floor was still unpassable — zero bars, not "near-never".**
3m rollup of `NIFTY26JULFUT` 09:15–15:30 IST, **minute-aligned bars only** (§3.15):

| bars | min | p50 | p90 | p95 | p99 | max | bars ≥ 125,000 |
|---|---|---|---|---|---|---|---|
| 125 | 14,430 | **36,595** | 72,345 | 88,010 | 105,755 | **112,645** | **0** |

The session **maximum** 3m bar was 112,645 — **11% below the fixed floor**. On 07-22 two bars
cleared it; today none did. The armed CE slugs' floor ran **45,971–65,032** on the same tape, i.e.
the relative floor tracked the thicker tape upward exactly as designed while the fixed one stayed
welded shut.

**Verdict: STRUCTURAL regression, third session, still owner-gated.** But see §5.1 — today it was
protective.

### 2.2 What the floor blocked today

**595 of the 634 composite-passing rows** were first-blocked by `volume-floor` (189 CE / 406 PE).
Of the composite-passing population, **22 rows had `volume-floor` as their ONLY failed check**
(§3.5) — down from 86 on 07-22. They are 2 slugs (`scalp-golden-crossover-nifty-pe`,
`scalp-connect-the-dots-nifty-pe`), all PE, 12:15–13:36, on 3 legs. §5.1 resolves every one.

### 2.3 ⚠ `vwap-distance` first-blocks with a POSITIVE margin — and it is CORRECT (T14 refinement)

All 10 `vwap-distance` rows log `operand 0.0041–0.0044 > threshold 0.0040`, margin **+0.000078 to
+0.000433**. `vwap-distance` is a **ceiling** rail (distance from VWAP must be ≤ the threshold), so
a positive `operand − threshold` is the semantically correct failure signature. **This refutes the
blanket `blocking_margin < 0` invariant proposed as T14 on 07-20** — the invariant must be
sign-aware per rail direction, not global. T14 is rewritten accordingly.

The **one** genuinely self-contradictory row is different: `id 7794`,
`scalp-connect-the-dots-nifty` 11:06, `blocking_rail = confluence-composite`, operand **0.6373**
vs threshold **0.6000**, margin **+0.0373**, `composite_score` 0.6373 — a composite that *passed*
its own threshold recorded as blocked by the composite rail. The documented optional-gate semantics
(an optional dot activates iff its score ≥ `optionalMinScore` **and** the required-only composite ≥
`threshold − optionalGateMargin`) would explain a block here, but then the row records the **full**
composite as the operand rather than the required-only one that actually failed. Diagnostic
mis-labelling, not a gate defect — folded into T14.

### 2.4 Rails with no evidence of miscalibration

`rsi-band` (avg 35.76 on failures), `pct-price-move` (0.130 vs 1.000), `oi-divergence-magnitude`
(−14.67 vs 20.0), `call-put-delta-filter` (23.24 vs 50.0), `volume-pump` / `max-oi-sr-gate`
(FAIL_OPEN) all read plausibly. No order-of-magnitude gaps other than §2.1.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (1,120 scored rows):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|---|
| n | 4 | 8 | 72 | 188 | 446 | 262 | 92 | 48 |
| CE | 0 | 0 | 12 | 90 | 278 | 58 | 0 | 0 |
| PE | 4 | 8 | 60 | 98 | 168 | 204 | 92 | 48 |

Max composite **0.8511** — identical to 07-22's record, and §3.1 below shows why that is not a
coincidence. Threshold 0.600; **634 rows cleared it: 416 PE and — for the first time in this
folder — 218 CE**. Every previous session was one-sided (07-17/07-20 all CE, 07-21/07-22 all PE).
A flat tape produced a genuinely two-sided composite population.

**Dot support rates** (1,120 scored rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_abs_band` | 0.8 | 0/166 | 0.0 | dead (5th session) |
| `volume` | 1.0 | 0/1,120 | 0.0 | mirrors §2.1 — mechanically dead behind the 125k floor |
| `iv_rank` | 0.8 | 0/1,120 | 0.0 | dead-data, **withheld from Σw** (#676) |
| `iv_pair` | 0.8 | 0/1,120 | 0.0 | dead — carried since 07-02 (6th confirmation) |
| `oi_spurt` | 1.0 | **0/1,120** | **0.0** | ⚠ **newly dead** — 3.0% (07-21) → 0.2% (07-22) → **0.0%** |
| `breadth` | 1.0 | 248/1,120 | 22.1 | regime (07-22: 96.6%) — T18 stays closed |
| `iv_slope` | 0.8 | 46/166 | 27.7 | alive |
| `trending_cross` | 1.0 | 504/1,120 | 45.0 | |
| `rsi` | 1.0 | 572/1,120 | 51.1 | |
| `sentiment_slope` | 1.0 | 670/1,120 | 59.8 | |
| `premium_skew` | 1.0 | 13/20 | 65.0 | (only the 20 straddle-path rows) |
| `futures_oi` | 1.5 | 736/1,120 | 65.7 | ✅ alive (3rd session) |
| `sentiment` | 1.0 | 740/1,120 | 66.1 | |
| `underlying_oi` | 1.0 | 764/1,120 | 68.2 | ✅ alive (3rd session) |
| `vix` | 1.0 | 826/1,120 | 73.8 | |
| `psar` | 1.0 | 856/1,120 | 76.4 | |
| `basis` | 1.0 | 1,008/1,120 | 90.0 | alive |
| `drastic_oi` | 1.0 | 1,010/1,120 | 90.2 | |
| `vwma` | 1.0 | 1,018/1,120 | 90.9 | |
| `supertrend` | 1.0 | 1,076/1,120 | 96.1 | ⚠ 3rd near-free session |
| `vwap` | 2.5 | **1,120/1,120** | **100.0** | ⚠ **5th consecutive session at 100%** |

**`vwap` has now supported 4,125 consecutive rows** (359 + 748 + 1,070 + 828 + 1,120) at the
heaviest weight in the model, across an up-trend, a chop, a mild-down, a clean-down and a flat
session. T6 is the best-evidenced row in the ledger by a wide margin.

**`oi_spurt` is newly dead and the trend is monotone** — 3.0% → 0.2% → 0.0% over three sessions.
The input data is present (`spurtOiPct` non-null on all 1,120 context-bearing rows), so this is a
threshold-vs-operand question, not a feed outage. New candidate **T22**; one more session before
proposing a number.

### 3.1 The dead-weight cap — the session's max composite *is* the ceiling

| dots on row | rows | Σw | withheld (iv_rank) | dead w (volume + iv_pair + oi_spurt [+ iv_abs_band]) | cap |
|---|---|---|---|---|---|
| 18 | 934 | 19.60 | 0.80 | 2.80 | **0.8511** |
| 19 | 20 | 20.60 | 0.80 | 2.80 | 0.8586 |
| 20 | 166 | 21.20 | 0.80 | 3.60 | 0.8235 |

18-dot cap = (18.80 − 2.80) / 18.80 = 16.00/18.80 = **0.851063…** — **exactly the observed
maximum**. So the top of today's distribution is not a market fact at all: **48 rows sat on the
arithmetic ceiling with every live dot supporting**. (It also explains why 07-22's max was the same
0.8511 to four places: same dot roster, same dead set except `oi_spurt`, which contributed
0.2% there and 0.0% here.) The 0.600 threshold requires **70.5%** of live weight.

## 4 Data health (§3.7)

| field | 2026-07-21 | 2026-07-22 | **2026-07-23** | class |
|---|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | 0 NEUTRAL | 0 NEUTRAL | **0 NEUTRAL**, 14 combos, 310 NULL | ✅ healthy (3rd session) |
| `spurtOiPct` / `spurtPricePct` | null 302/1,372 | null 214/1,042 | null **310**/1,430 | ✅ healthy |
| `advances` / `declines` | 0 zero-pairs | 0 zero-pairs | **0 zero-pairs**, 310 null | HEALTHY |
| `ivRank` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried since 07-02) |
| `fiiLongPct` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 100%** | by design (un-armed) |
| `vix` (macro mirror) | NULL 100% | NULL 100% | **NULL 100%** | known mirror gap (the `vix` **dot** is fine — 73.8%) |

**The 310 nulls reconcile exactly**: 268 `time-window` + 28 `time-of-day-preference` + 14
`option-side-constraint` = **310** — the rows blocked at a rail that fires *before* macro/OI context
is gathered. Every context-bearing row carries live data.

**Capture (minute-aligned bars only):**

| series | bars / snaps | window |
|---|---|---|
| `NIFTY26JULFUT` 1m | **375** aligned, **0 misaligned** | 09:15–15:29 |
| `SENSEX26JULFUT` 1m (BFO) | **375** aligned, 0 misaligned | 09:15–15:29 |
| `NIFTY 50` 1m | **375** aligned, 0 misaligned | 09:15–15:29 |
| `SENSEX` 1m | **375** aligned, 0 misaligned | 09:15–15:29 |
| `futures_oi_snapshots` (`NIFTY26JULFUT`, `SENSEX26JULFUT`) | **198 rows / 198 distinct minutes** each | — |

✅ **T19 quiet: zero misaligned 1m rows across the whole instrument set today** — there was no feed
outage, hence no gap backfill, hence no phantom bars. That is consistent with the 07-22 diagnosis
(only `source='BACKFILL'` rows are ever misaligned) and is the first session-level negative control
for it.

⚠ **`futures_oi_snapshots` cadence 198 of ~375 minutes (53%)** — a small improvement on 07-22's 187
but still roughly half, after 192 (07-21) and 208 (07-20). The four-session shape is a plateau
around 50–55%, not a continued decline. **And the OI quadrants were live anyway, for a third
consecutive session** — re-confirming that the cadence regression is real but is *not* the
mechanism behind the 07-20 NEUTRAL outage (T12).

**`dot-health` canary — T17 fired again, same defect, smaller sample.** The day's one
strategy-signal ERROR of this class:

```
dot canary: required dot 'breadth' input DEAD — input dead across 10 rejections
```

`breadth` supported 248/1,120 rows over the session — it was demonstrably alive. The probe sampled
10 context-less rows. Third consecutive session this canary has produced a false alarm off the
sampling window. The remaining 4 strategy-signal ERRORs are all the **08:37 boot-time JDBC race**
(`Failed to obtain JDBC Connection` in the shadow-variant registry read and the
`signal_eval_outcomes` rollup) — the same benign boot-ordering class the live watch logged for
market-data at 03:07:34Z, self-resolved on restart, well before the open.

## 5 Shadow-book outcomes

**Exit-fidelity caveat (standing, and materially sharpened this session — see §6.1):** indicator-driven
exits (trend-flip / signal-exit) are NOT replicated, **and 30 of the 38 live slugs have no premium
bracket configured at all**, so for those slugs the shadow book models *only* the structural stop
(where set) and the 15:12 square-off. Rejections blocked before leg resolution never shadow.

**Champion book — 26 closed, 8W/18L, −1,693.75 pts, −₹30,946.11.** This nearly cancels 07-22's
+₹42,240.91 and takes the all-time champion book from **+₹980.61 back to −₹29,965.50** over 156
closes.

⚠ **CORRELATION CAVEAT — 26 positions are 8 distinct entry events.**

| event (bar, leg, entry) | positions | outcome |
|---|---|---|
| 10:00 NIFTY26JUL23700CE @295.50 | 7 | **L** (5 × −20.5% square-off, 2 × structural stop −3.5%) |
| 10:03 SENSEX2672376300CE @330.85 | 7 | **L** (5 × **−88.4%**, 2 × structural stop −10.4%) |
| 10:06 NIFTY26JUL23750CE @272.50 | 1 | **L** −26.4% |
| 10:06 SENSEX2672376300CE @309.00 | 1 | **L** −87.6% |
| 11:27 NIFTY26JUL24200PE @293.40 | 6 | **W** (2 × TAKE_PROFIT +35.5%, 4 × +20.7% square-off) |
| 11:51 NIFTY26JUL24150PE @263.60 | 1 | **W** +18.5% |
| 12:15 NIFTY26JUL24150PE @265.10 | 1 | **W** +17.9% |
| 13:06 NIFTY26JUL24050PE @269.55 | 2 | **L** (−13.0%, structural stop −3.1%) |

**Counted once per event: 3W / 5L.** The whole loss is the two morning CE events: the SENSEX
76300CE decayed 330.85 → **38.45** by the 15:12 square-off (−88.4%) on a day the index closed
*up* 141 pts — a flat/whipsaw tape is exactly where a long-premium book with **no take-profit and no
premium stop** bleeds to zero. Five of those seven positions had `take_profit IS NULL` (§6.1); the
two that carried a structural stop lost 10.4% instead of 88.4%. **The single most valuable number in
this session is that contrast.**

**Per-rail attribution (champion, context only — never a knob verdict, §3.13):**

| blocking_rail | n | W | pts | net ₹ |
|---|---|---|---|---|
| volume-floor | 19 | 8 | −1,369.60 | −9,325.64 |
| directional-change-gate / divergence-vol-gate / call-put-delta-filter / two-candle / volume-pump | 1 each | 0 | −60.65 each | −4,019.61 each |
| supertrend-15m / pct-price-move | 1 each | 0 | −10.45 each | −761.21 each |

**Variant league — this session:**

| variant | closed | wins | pts | net ₹ |
|---|---|---|---|---|
| champion | 26 | 8 | −1,693.75 | **−30,946.11** |
| vol-off | 4 | 1 | −324.85 | **−9,229.56** |
| vol-12k5 | 4 | 1 | −324.85 | **−9,229.56** |
| composite-055 | 1 | 0 | −60.65 | −4,019.61 |

Both loosened books took the identical 4 entries again (10:06 NIFTY 23750CE + SENSEX 76300CE, 12:15
NIFTY 24150PE, 12:57 NIFTY 24100PE) and both lost — reversing their 4-for-4 on 07-22.

**Cumulative league (all sessions, judge on NET ₹):**

| variant | closed | net wins | pts | **net ₹** |
|---|---|---|---|---|
| champion | 156 | 64 | −561.60 | **−29,965.50** |
| vol-12k5 | 21 | 7 | −250.75 | **−6,183.20** |
| vol-off | 29 | 9 | −368.75 | **−12,124.43** |
| composite-055 | 9 | 2 | −58.15 | −4,498.59 |

**`vol-12k5`'s all-time positive sign lasted exactly one session** (+₹3,046 → −₹6,183). Every book
in the league is now net-negative all-time. The ordering `vol-12k5 > vol-off` survives, which is the
only stable reading this ledger has ever produced: **relax, don't remove.**

**Entry latency (F8):** p50 **81.6 s**, p95 **84.5 s** (35 positions) — inside the structural
73–87 s band. Standing caveat: every shadow fill is stamped ~80 s after `bar_time`.

### 5.1 Counterfactual — the 22 would-have-fired rows (§4.2)

The §3.5 set (composite ≥ threshold, `volume-floor` the **only** failed check) is **22 rows** from
2 slugs, all PE, 12:15–13:36, on 3 legs (`NIFTY26JUL24150PE` / `24100PE` / `24050PE`). They collapse
to **14 distinct (bar, leg) events**. Neither slug carries a `premium_pct` rule (§6.1), so — unlike
every prior file in this folder — the model applies **square-off only**, with no +35% take-profit.

**Result: 6 WOULD-WIN / 16 WOULD-LOSE, −451.20 premium points.**
**Counted once per event: 6W / 8L, −132.30 points.**

| leg | rows | entry range | 15:12 square-off | W | L | Σ pts |
|---|---|---|---|---|---|---|
| `NIFTY26JUL24150PE` (12:15–12:30) | 6 | 266.00–289.05 | 306.40 | **6** | 0 | **+186.60** |
| `NIFTY26JUL24100PE` (12:57–13:03) | 6 | 298.25–303.15 | 268.05 | 0 | **6** | −195.30 |
| `NIFTY26JUL24050PE` (13:06–13:36) | 10 | 271.30–284.85 | 230.30 | 0 | **10** | −442.50 |

**Had the "+35% take-profit" assumption been applied as in prior files**, three of the 24150PE rows
would have scored as TP wins (the leg printed 359.75 at 13:16, 364.00 at 13:18 and 367.80 at 14:52),
turning the total into −284.39 pts. **That is exactly the error T21 describes**, and it is directly
falsified by the shadow book: position `id 209` is the *same slug on the same leg at 12:15* with
`take_profit IS NULL`, and it closed `SQUARE_OFF` at 312.45 — the engine never had a take-profit to
hit. The square-off-only number is the correct one.

**Honest reading.** Today the fixed 125,000 floor was **protective**: it vetoed 16 losers and 6
winners for a net −451 points. That is the third alternation in three sessions on the same rail
(07-21 protective, 07-22 expensive, 07-23 protective), which is the strongest available evidence
that **single-session PnL cannot decide T16**. The argument for restoring the tag is that an armed
knob was silently disarmed by a seeder republish — a correctness argument, not a profitability one.

## 6 New data points / anomalies

### 6.1 ⚠⚠ NEW STRUCTURAL FINDING — 30 of 38 live scalpers have no premium exit rule (T21)

Source-verified: only **21 of the 63** YAMLs under
`services/strategy-signal-service/src/main/resources/scalper-strategies/` contain a `premium_pct`
block — the `gap-theory`, `market-movers`, `hero-zero`, `btst-stbt` and `straddle` families. The
`golden-crossover`, `connect-the-dots`, `two-candle`, `trending-oi`, `trend-change` and
`open-high-low` families have none; e.g. `scalp-golden-crossover-nifty-pe.yaml` carries
`entry-candle-stop` (a structural stop on the crossover candle) and an indicator signal-exit, and
nothing else.

The shadow book mirrors this exactly. Per-slug, all-time:

| bracket | slugs | rows | TAKE_PROFIT closes |
|---|---|---|---|
| `take_profit` set | **8** (gap-theory ×4, market-movers ×4) | 62 | **8 (all of them)** |
| `stop_loss` set | 2 (hero-zero) | 7 | 0 |
| **neither** | **28** | **118** | **0** |

**Consequences, in order of weight:**

1. **Every §4.2 counterfactual in this folder that applied a +35% take-profit to a non-`gap-theory`
   / non-`market-movers` slug overstated the win side.** That includes 07-22 §5.1's headline
   (**+2,547.6 pts, 56W/30L**) — all four slugs there (`connect-the-dots-*-pe`,
   `golden-crossover-*-pe`) are in the no-bracket set, and its "three rows hit the +35% take-profit
   outright" cannot have happened live. Those files are immutable; this note is the correction of
   record. **README §4.2 step 4 must stop naming "+35% (E9 default)" as universal.**
2. **The live exposure is real, not just analytical.** A long-premium intraday position with no
   take-profit and no premium stop can only exit on an indicator flip or at 15:12. §5's SENSEX
   76300CE (−88.4% held to square-off) is what that looks like on a whipsaw day; the two positions
   on the identical leg that *did* carry a structural stop lost 10.4%.
3. Whether the missing brackets are intentional (these families are designed to ride the
   indicator exit) or an unfinished config is **not determinable from the data** — it is an owner
   question. Filed as **T21**, not proposed as a number.

### 6.2 ✅ Third fully-covered session interior (§3.11), and the first with 38/38 coverage

15-minute buckets, 09:15 → 15:15, **no empty bucket**:

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 | 12:15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 2 | 8 | 74 | 90 | 90 | 90 | 90 | 90 | 36 | 80 | 68 | 66 | 80 |
| bucket | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 | 14:45 | 15:00 | 15:15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 80 | 80 | 80 | 80 | 60 | 30 | 24 | 16 | 34 | 70 | 8 | 4 |

`strategy.subscriber_health_events` is **empty** for the day (no eval-stall, no receive-stall, no
resubscribe) and `ay_signal_eval_failures_total = 0`. Combined with **38 of 38** published+enabled
scalpers emitting at least one row — verified by set-difference against the registry, the empty set
— this is the cleanest coverage record the folder has.

### 6.3 The 09:31–09:34 kite-rest breaker burst DID dent chain capture (live-watch carry §7.1)

The live watch flagged 93 `kite-rest circuit open; serving cached data` warnings confined to
09:31–09:34 IST and asked whether any 3-minute chain bucket was dropped. Per-bucket answer, NIFTY 50
snapshots:

| 5-min bucket | 09:20 | 09:25 | **09:30** | 09:35 | 09:40 | 09:45 |
|---|---|---|---|---|---|---|
| snapshots | 3,870 | 2,580 | **1,290** | 2,580 | 3,870 | 2,580 |

The 09:30 bucket carries **one capture cycle instead of two** — a real, single-cycle loss, recovered
by 09:35. Session totals hid it (the live watch read totals, correctly caveated). No downstream
effect is visible: OI quadrants were live all session and the 09:30–09:45 rejection buckets are
full. The trigger itself was not root-caused — the 8 in-session `ApiException`/circuit lines carry
no upstream status code, and the breaker's own logs record only the open state.

### 6.4 ⚠ `FINNIFTY26AUGFUT` is a genuine bar-close hole, not the SEP thin-tape artifact

Confirming the midday gate's carry #4: `FINNIFTY26AUGFUT` has **341 of 375** aligned 1m bars
(max bucket 15:29) — a near-full series with **34 missing minutes**, so its 412 s no-close canary is
a real capture hole, unlike `FINNIFTY26SEPFUT` (the established thin-tape case, 20 of the day's 22
market-data canary ERRORs, 4th consecutive session). Neither is a scalper signal series and neither
appeared in the health endpoint's `problems` (GREEN throughout). T20 stands, and now needs to
distinguish the two cases rather than blanket-excluding far-month contracts.

### 6.5 ✅ The host-vs-container clock lag did not persist

The live watch recorded the Windows host at 09:42 IST while the DB read 09:59 IST (~17 min lag).
Re-measured at close: host **16:57:28**, container **16:57:29** — agreement to one second. The lag
was transient (a host sleep/resume drift that resynced), not a standing hazard for host-scheduled
jobs. No action.

### 6.6 ⚠ CARRIED, STABLE — 19 paper positions OPEN, brackets starved all session

`strategy.paper_positions`: **19 OPEN** (unchanged from 07-22), 9 CLOSED. `PaperStaleTickAlerter`
WARNed **31,768** times today (07-22: 31,730). No new positions were added this session — the
population stopped growing but did not drain. T10 stays OWNER, de-escalated from "accumulating" back
to "chronic".

### 6.7 Zero fires, sixth consecutive session with no fire of any kind

`strategy.signals`: **0 rows**. `fired` counter = 0. Explained by §2.1 for the composite-passing
population and the chart gate for the rest (`chart-gate-failed` = 1,986, the largest counter of the
day).

### 6.8 Method addendum → README §3.16

Promote to the standard pass: **before resolving any counterfactual, check whether the slug actually
carries a `premium_pct` exit rule.** §6.1 is the evidence. The default assumption of a universal
+35% take-profit is wrong for 30 of the 38 live scalpers and has been silently inflating the win
side of this folder's counterfactuals.

## 7 Tuning candidates

Carried forward from 07-22 plus this session's new rows. **Nothing here is applied** — every row is
a PROPOSAL.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T21** | premium exit rules on the 30 bracket-less scalpers | no `premium_pct` block → shadow `take_profit`/`stop_loss` NULL; exit only via structural stop (where set) or 15:12 square-off | owner decision: confirm intentional (indicator-exit-only design) or add a premium band; **and** fix README §4.2 to stop assuming a universal +35% TP | §6.1: 21 of 63 YAMLs carry `premium_pct`; all 8 all-time `TAKE_PROFIT` closes are gap-theory/market-movers; §5 the SENSEX 76300CE rode −88.4% to square-off while the 2 stopped positions on the same leg lost 10.4% | **STRUCTURAL (config + method)** | **PROPOSED — NEW 07-23, HIGHEST NEW PRIORITY** |
| **T16** | `relative-volume-floor` tag on the 18 PE scalpers (+ the 10 sensex CE) | **absent** since the 07-20 21:28 republish | restore the tag and re-publish; add a guard so a seeder-draft publish cannot drop an armed tag | §2.1 registry unchanged 3rd session; 1,065 blocks at a flat 125,000 while armed CE slugs ran 45,971–65,032 the same day; **125,000 clears 0 of 125 3m bars — above the session max (112,645)**. §5.1: the rows it alone vetoed were **6W/16L, −451.2 pts** (07-22: 56W/30L, +2,547.6) — **3rd sign alternation in 3 sessions** | **STRUCTURAL (regression)** | **PROPOSED — 3rd session, HIGHEST PRIORITY. Argue as correctness, never as PnL** |
| **T14** | rejection-row margin invariant *(rewritten)* | proposed as global `blocking_margin < 0` | make it **sign-aware per rail direction** (floor rails negative, ceiling rails positive); separately, record the **operand that actually failed** on `confluence-composite` optional-gate blocks | §2.3: all 10 `vwap-distance` positive margins are semantically CORRECT (ceiling rail) — the 07-20 blanket invariant would have flagged healthy rows. Row `id 7794` is the one true self-contradiction (composite 0.6373 ≥ thr 0.600, blocked by the composite rail) | **STRUCTURAL (diagnostic)** | **PROPOSED — respecified 07-23** |
| T19 | gap-backfill bucket alignment | `from`/`to` passed unfloored → bars stored at `HH:MM:38` | floor the backfill window (or the bucket at write) to the minute; clean up the historical misaligned rows | 07-22 §6.2 unchanged; **today 0 misaligned rows session-wide** (no outage ⇒ no backfill) — a clean negative control confirming the trigger | **STRUCTURAL (defect)** | **PROPOSED — carried** |
| T12 | OI quadrant / spurt reads | — | fix the failing `/options/spurt` read; separately fix the futures-OI 1-minute capture cadence | quadrants healthy a **3rd** session while cadence sat at **198**/375 (after 187, 192, 208) — the decline has plateaued at ~50–55%, not worsened; cadence mechanism stays refuted, endpoint still suspect | **STRUCTURAL (defect)** | **PROPOSED — carried** |
| T6 | `vwap` dot weight | 2.5 | narrow the support condition or cut the weight | **1,120/1,120 = 100% for the 5th consecutive session; 4,125 rows** across five distinct tape characters with zero discrimination | **STRUCTURAL** (5 sessions) | **PROPOSED — strongest row in the ledger** |
| **T22** | `oi_spurt` dot threshold | current spurt floor (`artha.scalper.oi.*`, recalibrated #675/#676) | lower the floor — number to be set after one more session | **0/1,120 today** after 3.0% (07-21) and 0.2% (07-22): a monotone decline to fully dead, with input data present (`spurtOiPct` non-null on all context-bearing rows) | **STRUCTURAL (suspected)** | **PROPOSED — NEW 07-23, collect 1 more session** |
| T13 | `dot-health` probe registry | 6 probes | add `futures_oi` / `underlying_oi` NEUTRAL-share probes | 07-20: canary healthy while the two heaviest OI dots were dead all session | **STRUCTURAL** | **PROPOSED** (carried) |
| T17 | `dot-health` sampling window | newest 40 rejections | sample the newest N **context-bearing** rows **from the current session only** | §4: 3rd consecutive false `breadth DEAD` ERROR, today "across **10** rejections" while breadth supported 248/1,120 | **STRUCTURAL (diagnostic)** | **PROPOSED — 3rd confirmation** |
| T1 | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | both loosened books lost on identical entries today (−₹9,229 each, 1-for-4) after 4-for-4 on 07-22; `vol-12k5`'s all-time positive sign lasted one session. Ledger now **3-for / 4-against** | **REGIME** | **PROPOSED — still do NOT apply; decide with T16, not before** |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source ivRank or drop from Σw | dead every session since 07-02 | **STRUCTURAL** | **PROPOSED** (carried) |
| T3 | `iv_pair` gap threshold | 0.02 | ~0.005 | 0/1,120 today; 0% on every session logged, including post-recalibration | **STRUCTURAL** | **PROPOSED** (carried, confirmed 6×) |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | 0/166 again today (5th session) | **REGIME** (5 sessions) | **PROPOSED**, collect more |
| T7 | composite threshold | 0.600 | no change | `composite-055` took 1 row and lost; cumulative −₹4,498.59. 634 rows cleared 0.600 unaided | — | **REJECTED** (reaffirmed) |
| T8 | shadow entry latency | p50 ~80 s | stamp entry at bar close | 9 sessions in the 73–87 s band | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T9 | strategy-coverage watchdog | none | alert on emitting/published ratio drop, split by counter | **38/38 today** — full coverage, so the watchdog has a clean baseline to alert against | **STRUCTURAL** | **PROPOSED** (needs redesign) |
| T10 | **19** stale OPEN paper positions + starved brackets | open since 07-07, **stable** | square off / age out, or subscribe the swing holdings | §6.6: unchanged count, 0 new, 31,768 starvation WARNs | ops | **OWNER — chronic (de-escalated)** |
| T15 | engine boot-line durability | log only | persist `loaded/unresolved/dropped` to a table | readable today (no restart) — three sessions in a row by luck, not design | **STRUCTURAL (data-model)** | **PROPOSED** (carried) |
| T20 | far-month contracts in the bar-divergence canary | all subscribed instruments | exclude non-front contracts **only where thin-tape is the cause**; keep alerting on genuine holes | §6.4: `FINNIFTY26SEPFUT` is thin-tape (4th session, 20 of 22 ERRORs) but `FINNIFTY26AUGFUT` has 341/375 bars — a real 34-minute hole. A blanket exclusion would suppress the true positive | ops (noise) | **PROPOSED — respecified 07-23** |
| T18 | `breadth` dot threshold | `advances/declines > 32` (of 50) | **no change** | 22.1% today on a flat tape, 96.6% on 07-22's down tape — regime, as concluded | **REGIME (resolved)** | **CLOSED — no action** |
| T11 | SENSEX volume-floor | fixed 125,000 | arm the relative floor for the sensex family | the 10 sensex CE slugs still carry no tag (published 06-29/06-30) | **STRUCTURAL** | **MERGED INTO T16** |
| T4 | `basis` dot | w 1.0 | — | alive (90.0%) | — | **CLOSED — no action** |

## 8 Honesty caveats

- **§5.1's −451.20 points is square-off-only and therefore also approximate in the other direction:**
  it models **no structural stop**, which the shadow book shows firing at −3.1% on one of the same
  legs. The 22 rows are 2 slugs on 3 legs across 82 minutes of one flat session — not 22 independent
  bets. Snapshot granularity is ~2 min; no slippage or fees; strike-pick approximated.
- **The champion book's −₹30,946.11 is 8 distinct entry events, not 26 positions** (3W/5L), and two
  morning CE events carry essentially the whole loss. Symmetrically, 07-22's +₹42,240.91 was 14
  events. Neither headline should be read at face value.
- **§6.1 corrects prior files rather than re-deriving them.** The per-slug bracket table is
  DB-measured and the YAML count is source-grepped, but the *individual* prior counterfactuals were
  not recomputed — only 07-22's is named because its slugs are directly checkable. Earlier sessions
  may carry the same upward bias.
- **The max composite 0.8511 is an arithmetic ceiling, not a market reading** (§3.1): 48 rows sat on
  it with every live dot supporting. Comparisons of "max composite" across sessions are comparisons
  of the dead-dot set, unless the roster is stated.
- **Today's evidence that the fixed volume floor is protective is the mirror image of yesterday's
  evidence that it is expensive, on the same rail, one session apart** — the third such flip. Both
  are regime readings; T16 is a correctness argument.
- Shadow exits replicate structural stop + square-off (+ premium brackets **only for the 8 slugs
  that configure them**) — no indicator-driven exits. Every entry is stamped ~80 s after `bar_time`.
- **The 09:31–09:34 breaker burst was not root-caused** (§6.3); only its effect on capture was
  measured.
- Costs are the 1-lot engine fill model (statutory + ₹20/lot). Not cost-adjusted twice.
- This run was **read-only**: SELECTs, `docker logs`, in-container actuator/health GETs, and a
  source grep. No restart, deploy, write or config change. No strategy knob was altered.
