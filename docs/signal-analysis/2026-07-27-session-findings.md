# Session findings — 2026-07-27 (data date)

Analysis date: 2026-07-27 (scheduled post-market agent, ran 16:30–17:40 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,253** (bar times 09:18–15:18 IST), signals fired **3 ENTRY + 3 EXIT**,
paper positions opened **0**, shadow positions **63** (41 champion + 22 challenger, all closed).
Session character: **gap-up trending day.** `NIFTY 50` 23,928.40 → **24,002.10** in-session; against the
07-24 close of 23,786.70 that is **+215.40 pts, +0.91%**. `SENSEX` 76,613.01 → 76,844.94.

⚠ **The signal series ROLLED to `NIFTY26AUGFUT` today** (July monthly expiry is 2026-07-28, so
`FuturesUniverseResolver` moved the front contract at the ~08:40 IST re-resolve). Every rejection row,
every threshold and every volume operand below is on **AUGFUT**, not the JULFUT series all prior files in
this folder used. AUGFUT ran 23,999.00 → **24,118.00** (+119.00), range 23,965.20–24,120.00 (154.8 pts);
3m volume median **22,620**. Cross-session volume comparisons against 07-24 and earlier are **not
like-for-like** — see §6.4, promoted to README §3.18.

This file folds in the three earlier read-only runs of the same date: `2026-07-27-open-gate.md` (PASS),
`2026-07-27-live-health.md` (GREEN, 09:43–09:47 IST) and `2026-07-27-midday-gate.md` (PASS, 13:00–13:08).

---

## 0 Read this first — the session's headline

**This is the first live session measuring the whole 2026-07-25 D-wave. Six carried tune rows moved, and
five of them closed.**

1. **T16 is RESOLVED.** All **38** enabled scalpers carry the `relative-volume-floor` tag, published
   `2026-07-25 21:44:5x IST` — **zero slugs left on the flat 125,000 floor**, for the first time since the
   07-20 regression. Session thresholds ran **25,935–49,140** and tracked the tape (§2.1). Four sessions of
   "an armed knob was silently disarmed" are over.
2. **T21 is RESOLVED, and the brackets bit immediately.** **63 of 63** YAMLs now carry a `premium_pct`
   block (was 21 of 63); 16 of the 18 slugs that shadowed today had `take_profit` **and** `stop_loss` set
   (TP ×1.35 / SL ×0.75), the two exceptions being the `hero-zero` pair by design. The book recorded its
   **first-ever `STOP_LOSS` closes — 3 of them, −₹8,016.99** (§5).
3. **T6 and T22 both shipped (#991) and both moved hard.** `vwap` fell from **100% support on six
   consecutive sessions (5,225 rows) to 17.5%** under the new ≥15 bps distance condition, and `oi_spurt`
   went **0.0% → 9.9%** under floors (15, 3). Both read as designed against ground truth (§3). The
   best-evidenced row in the ledger is now closed.
4. **T17 + T13 are RESOLVED** — the `dot-health` canary read **correctly** at 16:56 IST for the first time
   in five sessions (`rowsScanned 200 / rowsInspected 40`, `breadth`/`futures_oi`/`underlying_oi` all alive
   and all `required`). #983 is live (§4).
5. **T12's cadence half is confirmed fixed** — `futures_oi_snapshots` cadence jumped **211 → 372 of 375
   minutes (99.2%)** after #1031. Five sessions of ~50% coverage ended in one deploy.
6. **T23 is 92% quiet but not gone** — `PartialBucketCanary` WARNed **3 times** (was 37 on 07-24, 48 on
   07-23). Two are a benign ±845 pair. **The third is the 09:15 opening bucket again, +3,185 = 49 lots**,
   the same unpaired-opening signature #981 was meant to close, at roughly half the previous magnitude
   (§6.1).
7. **Three signals FIRED — the first entries since 2026-07-20 — and produced ZERO paper positions.** All
   three went `EXPIRED` an hour later. There is no scalper paper book at all (`paper_positions` holds only
   `minervini` and `manas-arora` rows), so this is the standing arming state rather than a failure — but
   it is the first session where fires existed to test it, and it deserves an explicit owner answer (§6.2).
8. **Coverage is 38 of 38** — every enabled scalper emitted. Best on record, and it answers the 07-23/07-24
   open question about which slugs go silent: today, none did.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-22 | 2026-07-23 | 2026-07-24 | **2026-07-27** |
|---|---|---|---|---|
| rejections | 1,042 | 1,430 | 1,366 | **1,253** |
| distinct strategies emitting | 36 | 38 | 36 | **38** |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| scalpers loaded by the engine | 38 | 38 | 38 | **38** |
| **coverage ratio (§3.10)** | 36/38 | 38/38 | 36/38 | **38/38** ✅ |
| signals fired | 0 | 0 | 0 | **3 ENTRY + 3 EXIT** |
| paper positions opened | 0 | 0 | 0 | **0** |
| bar-time coverage | 09:18–15:18 | 09:18–15:18 | 09:18–14:57 | **09:18–15:18** |
| composite ≥ threshold rows | 568 | 634 | 418 | **253** (253 CE / **0 PE**) |
| scored rows | 828 | 1,120 | 1,100 | **909** |
| max composite | 0.8511 | 0.8511 | 0.7447 | **0.8511** |

**Engine load (§3.10) — the container has not restarted since the 2026-07-26 23:19 IST boot
(`RestartCount=0`), so the boot line is still readable:**

```
2026-07-26T17:50:17.820Z (23:20 IST)  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

A **single** line, no F10 cold-start retry pair — the boot happened outside market hours with the universe
already resolvable. `unresolved == 0` on the first and only attempt. Health signal satisfied.

**Set-difference against the registry returns the EMPTY SET** — all 38 enabled scalpers emitted at least
one row. This is the first session since 07-23 at full coverage, and better than 07-23 in that 07-23's
count was also 38 but the `hero-zero` pair went silent the very next session.

**Eval counters (actuator :8082, read 16:47 IST — no post-close deploy has run).** The container booted at
23:20 IST **yesterday**, so the cumulative counters ARE today's session totals — no delta arithmetic needed:

| outcome | 2026-07-27 (cumulative = today) |
|---|---|
| `chart-gate-failed` | **1,994** |
| `composite-below-threshold` | **258** |
| `confluence-blocked` | **1,253** |
| `fired` | **3** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 |
| **Σ** | **3,508** |
| `ay_signal_eval_failures_total` | **0** |

`confluence-blocked` = **1,253** = today's rejection row count **exactly**, and Σ = **3,508** byte-matches
`sum(eval_count)` over the 133 persisted `signal_eval_outcomes` buckets. Three independent views agree.

`ay_signal_eval_duration_seconds_count` = **374** (09:15→15:29 is 375 minute-cycles). One cycle short of
the grid; sum 1,809.80 s ⇒ mean **4.84 s**/eval (07-24: 3.92 s). Flagged as a one-cycle discrepancy, not
diagnosed — it is well inside the "no stall" reading and could be a boundary effect on the first or last
bar.

**First-blocking-rail histogram** (1,253 rows, **16** distinct rails — the widest tail recorded):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **768 (61.3%)** | 20,338.2 | 31,249.6 | −10,911.4 |
| time-window | 282 (22.5%) | — | — | — |
| time-of-day-preference | 42 | — | — | — |
| rsi-band | 36 | 66.98 | — | — |
| option-side-constraint | 20 | — | — | — |
| divergence-vol-gate | 18 | 44,965.6 | — | — |
| volume-pump | 18 | 44,965.6 | — | — |
| pct-price-move | 18 | 0.267 | 1.000 | −0.733 |
| two-candle | 18 | — | — | — |
| confluence-composite | 10 | 0.495 | 0.600 | −0.1052 |
| oi-cross-required | 6 | 127.38 | — | — |
| strike-pick | 6 | — | — | — |
| hero-zero | 6 | — | — | — |
| rsi-5m-cap | 2 | 84.39 | — | — |
| directional-change-gate | 2 | 0.010 | — | — |
| max-oi-sr-gate | 1 | 24,000.0 | — | — |

**`volume-floor`'s first-block share fell 73.1% → 61.3%** — the direct consequence of §2.1. The
`avg threshold` collapsed from **103,454 to 31,250**, which is the T16 fix showing up as a number.

**All-failed-rails expansion (§3.3)** — top 15:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | 768 | 20,338.2 | 31,249.6 |
| confluence-composite | FAIL_CLOSED | 666 | 0.454 | 0.600 |
| time-window | FAIL_CLOSED | 282 | — | — |
| strike-pick | FAIL_CLOSED | **264** | — | — |
| rsi-band | FAIL_CLOSED | 244 | 58.18 | — |
| divergence-vol-gate | FAIL_CLOSED | 144 | 23,734.0 | — |
| trend-change | FAIL_CLOSED | 144 | — | — |
| constituent-gate | FAIL_OPEN | 118 | −0.410 | — |
| pct-price-move | FAIL_OPEN | 118 | 0.187 | 1.000 |
| two-candle | FAIL_CLOSED | 118 | — | — |
| volume-pump | FAIL_OPEN | 118 | 24,615.2 | — |
| oi-divergence-magnitude | FAIL_CLOSED | 84 | −7.83 | 20.00 |
| oi-cross-required | FAIL_CLOSED | 84 | 104.69 | — |
| open-high-low | FAIL_CLOSED | 70 | — | — |
| directional-change-gate | FAIL_CLOSED | 70 | 0.249 | — |

✅ **`strike-pick` fails FELL 550 → 264**, reversing two sessions of growth. Still the 4th-largest failing
rail and still un-bucketed by slug/time — carried as an open question for a third session, but no longer
escalating.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 ✅ T16 RESOLVED — every enabled scalper is back on the relative floor

**Registry state, standing §3.14 query, run today:**

| carries `relative-volume-floor` | published on | count | family |
|---|---|---|---|
| **yes** | **2026-07-25 21:44:5x IST** | **38** | **ALL of them — nifty + sensex, CE + PE** |
| no | — | **0** | — |

The 07-25 wave (#980, 63/63 YAMLs tagged + a guard test, then a 38-strategy republish) closed the
regression that ran from 07-20 to 07-24. The §3.14 fingerprint — `min(blocking_threshold) =
max(blocking_threshold)` on a family — is gone from every slug:

| slug | n | min thr | max thr | avg operand |
|---|---|---|---|---|
| `scalp-connect-the-dots-nifty` (CE) | 50 | 25,935.00 | 36,952.50 | 19,972 |
| `scalp-connect-the-dots-nifty-pe` (PE, was welded to 125,000) | 11 | **26,227.50** | **34,320.00** | 20,818 |
| `scalp-trend-change-sensex-niftyoi` (sensex CE, the old T11, never armed until now) | 50 | **25,935.00** | **36,952.50** | 19,972 |
| `scalp-golden-crossover-nifty-pe` | 4 | 29,932.50 | 32,565.00 | 18,785 |
| `scalp-hero-zero-nifty` | 12 | 32,321.25 | 49,140.00 | 27,538 |
| … all 34 volume-floor-blocking slugs banded; **none flat** | | | | |

**Ground truth on the ROLLED signal series.** 3m rollup of `NIFTY26AUGFUT` 09:15–15:30 IST,
**minute-aligned bars only** (§3.15):

| bars | min | p50 | p90 | max |
|---|---|---|---|---|
| 125 | 6,565 | **22,620** | 47,320 | 117,000 |

The floor ran 25,935–49,140 against a p50 of 22,620 and a p90 of 47,320 — i.e. it now sits between the
**p55 and the p92** of the operand's own distribution, which is exactly what a k=1.5 relative floor is
supposed to do. Compare 07-24, when the fixed 125,000 cleared **1 bar in 125**. **T16 CLOSED.**

### 2.2 What the floor blocked today — and it cost money

**768 of 1,253 rows** first-blocked by `volume-floor`. Of the composite-passing population, **18 rows had
`volume-floor` as their ONLY failed check** (§3.5) — up from 15 on 07-24. They are 4 slugs, **all CE**,
11:27–14:00, on 3 legs. §5.1 resolves every one: **8 WOULD-WIN / 10 WOULD-LOSE, +235.10 premium points.**

**This is the first session in which the CORRECTLY-CALIBRATED floor was measured, and it was expensive.**
Every prior "protective" reading (07-21, 07-23, 07-24) was taken against a floor welded 4–6× above the
tape. That distinction matters for T1 and is spelled out in §8.

### 2.3 ⚠ `confluence-composite` self-contradiction did NOT recur

Zero rows today record `blocking_rail = confluence-composite` with a passing `composite_score` — the
10 composite-rail first-blocks all carry a genuinely-failing operand (avg 0.495 vs 0.600, avg margin
−0.1052). 07-23 had 1 such row, 07-24 had 3. **T14's composite half is quiet for the first time**, but a
one-session absence on a CE-dominated tape is not a fix — the mechanism (an optional-gate block recording
the FULL composite as the operand) is untouched in code. Carried.

`vwap-distance` did not first-block a single row today, so the sign-aware-margin half of T14 gained no new
evidence either way.

### 2.4 Rails with no evidence of miscalibration

`rsi-band` (avg 58.18 on failures), `pct-price-move` (0.187 vs 1.000), `oi-divergence-magnitude` (−7.83 vs
20.0), `oi-cross-required`, `volume-pump` / `constituent-gate` (both FAIL_OPEN) read plausibly. The single
`max-oi-sr-gate` row (operand 24,000) is a first sighting, one row, nothing to read from it.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (909 scored rows):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|---|
| n | 6 | 116 | 118 | 321 | 193 | 76 | 62 | 17 |
| CE | 4 | 32 | 82 | 257 | 193 | 76 | 62 | 17 |
| PE | 2 | 84 | 36 | 64 | 0 | 0 | 0 | 0 |

Max composite **0.8511**. Threshold 0.600; **253 rows cleared it — all CE, zero PE.** The two-sided
passing population of 07-23/07-24 is gone: the PE book topped out at **0.5xx** on a +0.91% day, which is
the composite behaving correctly, not a ceiling (07-22 proved PE can reach 0.8511 on a down tape).

**Dot support rates** (909 scored rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_abs_band` | 0.8 | 0/135 | 0.0 | dead (7th session) |
| `iv_pair` | 0.8 | 0/909 | 0.0 | dead — carried since 07-02 (8th confirmation) |
| `iv_rank` | 0.8 | 0/909 | 0.0 | dead-data, **withheld from Σw** (#676) |
| `volume` | 1.0 | 0/909 | 0.0 | ⚠ **still 0% even with the floor fixed** — see below |
| `iv_slope` | 0.8 | 9/135 | 6.7 | alive, thin |
| `oi_spurt` | 1.0 | **90/909** | **9.9** | ✅ **REVIVED by #991** (was 0.0% ×2 sessions) |
| `vwap` | 2.5 | **159/909** | **17.5** | ✅ **T6 SHIPPED** — the 100% streak is over |
| `trending_cross` | 1.0 | 181/909 | 19.9 | 9.5% on 07-24 → 19.9% — regime, watch continues |
| `rsi` | 1.0 | 417/909 | 45.9 | |
| `sentiment_slope` | 1.0 | 539/909 | 59.3 | |
| `futures_oi` | 1.5 | 575/909 | 63.3 | ✅ alive (5th session) |
| `underlying_oi` | 1.0 | 581/909 | 63.9 | ✅ alive (5th session) |
| `sentiment` | 1.0 | 625/909 | 68.8 | |
| `premium_skew` | 1.0 | 24/34 | 70.6 | **NEW in this folder** — soft warning dot, armed on the `hero-zero` pair only |
| `drastic_oi` | 1.0 | 695/909 | 76.5 | |
| `basis` | 1.0 | 723/909 | 79.5 | alive |
| `breadth` | 1.0 | 723/909 | 79.5 | regime (38.7% → 79.5%) — T18 stays closed |
| `vix` | 1.0 | 723/909 | 79.5 | |
| `psar` | 1.0 | 769/909 | 84.6 | |
| `vwma` | 1.0 | 831/909 | 91.4 | |
| `supertrend` | 1.0 | 859/909 | 94.5 | off 100% for the first time in 2 sessions |

### 3.1 ✅ T6 CLOSED — the vwap dot now discriminates, and the numbers check out

`ConnectTheDotsScorer` now emits the reason string `price vs VWAP (side + >=15 bps)` (#991, `vwapMinDistanceBps`).
The dot fell from **100% on six consecutive sessions / 5,225 rows** to **17.5%**, split **CE 22.0% (159 of
723) / PE 0.0% (0 of 186)**.

Ground truth on the operand confirms the rail, not a defect:

| supports | side | rows | avg close | avg vwap | avg gap |
|---|---|---|---|---|---|
| false | CE | 564 | 24,030.1 | 24,009.8 | **+20.3 pts (8.5 bps)** |
| **true** | CE | 159 | 24,067.4 | 24,019.1 | **+48.2 pts (20.1 bps)** |
| false | PE | 186 | 23,992.7 | 24,007.2 | **−14.5 pts (−6.0 bps)** |

Row-level spot check: `id 11083` (CE, 14:06) has close 24,055.00 vs vwap 24,019.37 = **+35.63 pts =
14.83 bps** — correctly just under the 15 bps bar. The PE 0% is a +0.91% tape, not a dead dot: PE needs
price ≥15 bps **below** VWAP and the session average was −6.0 bps.

**T6 CLOSED — shipped, live, measured, behaving as specified.** One caveat for the next rollup: the dot
carries the heaviest weight in the model (2.5) and just went from free to scarce, so composites are now
structurally lower on marginal bars. That is the intent, but it changes what a 0.600 threshold means, and
T7 must be re-judged against post-#991 sessions only.

### 3.2 ✅ T22 CLOSED — oi_spurt revived, and the operand distribution backs the floor

`oi_spurt` went **0.0% (07-23) → 0.0% (07-24) → 9.9% (today)** after #991 moved the floors from (50, 8) to
**(15, 3)**. The §3.8 ground-truth query T22 had been asking for, computed today:

| rows | min | p50 | p90 | p99 | max |
|---|---|---|---|---|---|
| 909 | −55.00 | **−5.96** | **14.44** | 284.13 | 284.13 |

An OI floor of **|15|** sits just above p90 of its own operand, so ~10% support is the *arithmetically
expected* outcome — and 9.9% is what was observed. The old floor of 50 sat at roughly p97+, which is why
the dot read dead for months. **T22 CLOSED**; the dot is now a real, selective spurt filter rather than an
unreachable one.

### 3.3 ⚠ The `volume` dot is STILL 0% — and the floor fix did not touch it

`volume` supported **0 of 909** rows, unchanged from every prior session, even though the `volume-floor`
**rail** is now correctly calibrated. Prior files described this dot as "mechanically dead behind the 125k
floor". **That explanation is now falsified**: the 125k floor is gone and the dot is still 0%. The
`volume` dot and the `volume-floor` rail evidently do not share a threshold. This is a **new open
question**, filed as **T24** — it costs 1.0 of weight on every row and the cause is now unexplained.

### 3.4 The dead-weight cap — the ceiling ROSE, and the session max is 94% of it

| dots on row | rows | Σw | withheld (iv_rank) | dead w | cap | observed max |
|---|---|---|---|---|---|---|
| 18 | 740 | 19.60 | 0.80 | **1.80** (volume + iv_pair) | **0.9043** | **0.8511** |
| 19 (+`premium_skew`) | 34 | 20.60 | 0.80 | 1.80 | 0.9091 | — |
| 20 (+`iv_abs_band`) | 135 | 21.20 | 0.80 | 2.60 | 0.8725 | — |

18-dot cap = (18.80 − 1.80)/18.80 = **0.904255…**, **up from 0.8511 on 07-23/07-24** because `oi_spurt`
rejoined the live roster. Today's max **0.8511 = 94.1% of the cap** — a market reading, not a ceiling.
The 0.600 threshold now requires **66.4% of live weight** (was 70.5%).

## 4 Data health (§3.7)

| field | 2026-07-23 | 2026-07-24 | **2026-07-27** | class |
|---|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | 0 NEUTRAL | 0 NEUTRAL | **0 NEUTRAL**, 16 combos, 344 NULL | ✅ healthy (5th session) |
| `spurtOiPct` / `spurtPricePct` | null 310/1,430 | null 266/1,366 | null **344**/1,253 | ✅ healthy |
| `advances` / `declines` | 0 zero-pairs | 0 zero-pairs | **0 zero-pairs** | HEALTHY |
| `ivRank` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried since 07-02) |
| `fiiLongPct` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 100%** | by design (un-armed) |
| `vix` (macro mirror) | NULL 100% | NULL 100% | **NULL 100%** | known mirror gap (the `vix` **dot** is fine — 79.5%) |

**The 344 nulls reconcile exactly**: 282 `time-window` + 42 `time-of-day-preference` + 20
`option-side-constraint` = **344** — the rows blocked at a rail that fires *before* macro/OI context is
gathered. Every context-bearing row carries live data. The dead trio (`ivRank`/`fiiLongPct`/`dowUp`) is
the standing ledger set, per the correction promoted to README §4.1 this morning — **not news**.

**Capture (minute-aligned bars only):**

| series | bars | misaligned | last bar |
|---|---|---|---|
| **`NIFTY26AUGFUT` 1m (today's signal series)** | **375** | **0** | 15:29 |
| `NIFTY26JULFUT` 1m | 375 | 0 | 15:29 |
| `SENSEX26JULFUT` 1m (BFO) | 375 | 0 | 15:29 |
| `NIFTY 50` / `SENSEX` 1m | 375 / 375 | 0 | 15:29 |
| `FINNIFTY26AUGFUT` 1m | 375 | 0 | 15:29 |
| `FINNIFTY26SEPFUT` 1m | **35** | 0 | 15:25 |
| `futures_oi_snapshots` (`NIFTY26JULFUT`) | **372 rows / 372 distinct minutes** | — | — |
| `options_chain_snapshots` (11:05–11:20 window probe) | 28,480 rows / 10 distinct minutes | — | — |

✅ **T19 quiet for a THIRD consecutive session — zero misaligned 1m rows session-wide** (§3.15 query
returns the empty set). Three clean negative controls now, and #982's live cleanup of 1,682 July phantoms
holds.

✅ **T12 cadence CONFIRMED FIXED — `futures_oi_snapshots` 372 of ~375 minutes (99.2%)**, after 211, 198,
187, 192, 208 over the preceding five sessions. #1031's dedicated `oiCaptureTaskScheduler` did exactly what
its ledger row claimed. Combined with a 5th clean quadrant session, T12 is closed on both halves.

✅ **`dot-health` canary is CORRECT for the first time in five sessions — T17 + T13 both CLOSED.** Read at
16:56 IST:

```
{"session":false,"rowsScanned":200,"rowsInspected":40,
 "dots":[{"breadth",alive:true,required:true}, {"iv_rank",alive:false,required:false},
         {"dow",alive:false,required:false}, {"fii",alive:false,required:false},
         {"oi_spurt_price",alive:true}, {"vix",alive:true},
         {"futures_oi",alive:true,required:true}, {"underlying_oi",alive:true,required:true}]}
```

The `rowsScanned 200 / rowsInspected 40` split is #983's context-bearing sampling — it scanned past the
context-less EOD `time-window` tail that produced four straight false all-dead readings. The
`futures_oi` / `underlying_oi` probes are #983's T13 half, present and both `required: true`. The three
dead dots are exactly the ledger's standing set.

**Error channels.** `ay-strategy-signal-service`: **0 ERROR lines all session**. `ay-market-data-service`:
**3 ERRORs**, all `"Unexpected error occurred in scheduled task"` — the OI-capture scheduler during the
11:11 IST Kite blip (§6.3). ✅ **No `FINNIFTY` bar-close canary REDs** — #986's tick-density guard has
silenced five sessions of thin-tape noise. **T20 CLOSED.**

## 5 Shadow-book outcomes

**Exit-fidelity caveat (standing, per §3.16):** indicator-driven exits (trend-flip / signal-exit) are NOT
replicated. **This caveat is now much narrower than in prior files** — §3.16's standing check shows
`take_profit` and `stop_loss` set on 16 of the 18 slugs that traded (the `hero-zero` pair excepted, by
design), so the book models premium brackets, structural stops and the 15:12 square-off for almost every
position. Rejections blocked before leg resolution still never shadow.

**Champion book — 41 closed, 19W/22L, +506.35 pts, +₹9,007.08.** The best session on record, and it takes
the all-time champion book from **−₹44,160.71 to −₹35,153.63** over 219 closes.

**Close-reason mix (champion):**

| reason | n | pts | net ₹ |
|---|---|---|---|
| SQUARE_OFF | 22 | +823.00 | **+24,555.41** |
| STRUCTURAL_STOP | 16 | −196.20 | −7,531.34 |
| **STOP_LOSS** | **3** | −120.45 | **−8,016.99** |

**The 3 `STOP_LOSS` closes are the first ever recorded in this folder** — T21's premium bands (SL ×0.75)
are live and biting. There were **0 `TAKE_PROFIT` closes**: the +35% band was never reached on a day whose
best leg ran +18%.

⚠ **CORRELATION CAVEAT — 41 positions are 21 distinct entry events**, and two of them dominate:

| event (bar, leg, entry) | positions | pts | net ₹ |
|---|---|---|---|
| 09:48 `NIFTY26JUL23850CE` @157.85 | 5 | −109.30 | **−7,425.81** |
| 09:48 `SENSEX26JUL76200CE` @799.00 | 5 | +289.40 | **+5,395.20** |
| 11:24 `SENSEX26JUL76200CE` @783.00 | 2 | +76.00 | +1,365.20 |
| 11:27 `NIFTY26JUL23850CE` @156.70 | 6 | +124.85 | **+7,706.92** |
| 11:30 `SENSEX26JUL76300CE` @751.90 | 1 | +53.50 | +993.32 |
| 11:33 `NIFTY26JUL23850CE` @157.75 | 1 | −2.50 | −228.53 |
| 11:39 `SENSEX26JUL76200CE` @793.60 | 1 | −19.05 | −457.18 |
| 11:57 `NIFTY26JUL23850CE` @155.00 | 1 | −12.20 | −857.81 |
| 11:57 `SENSEX26JUL76200CE` @798.15 | 1 | −40.40 | −883.75 |
| 13:18 `NIFTY26JUL23850CE` @160.85 | 2 | −7.70 | −633.04 |
| 13:18 `SENSEX26JUL76300CE` @735.95 | 2 | −3.50 | −219.00 |
| 13:21 `NIFTY26JUL23850CE` @156.25 | 2 | −1.00 | −197.06 |
| 13:21 `SENSEX26JUL76300CE` @730.60 | 2 | −12.50 | −398.34 |
| 14:00 `NIFTY26JUL23850CE` @160.00 | 2 | +49.70 | **+3,092.86** |
| 14:00 `SENSEX26JUL76300CE` @743.20 | 2 | +124.40 | **+2,334.80** |
| 14:30 / 15:12 / 15:18 `hero-zero` legs (both indices) | 6 | −4.35 | −580.70 |

**Counted once per event: 9W / 12L.** The 11:27 NIFTY cluster (+₹7,707) and the 09:48 SENSEX cluster
(+₹5,395) carry the session; the 09:48 NIFTY cluster (−₹7,426) offsets most of it.

⚠ **Points are NOT commensurable across the two underlyings.** The 09:48 pair is the cleanest illustration
in the folder so far: on the same bar the NIFTY leg lost −109.30 pts (−₹7,426 at lot 75) while the SENSEX
leg gained +289.40 pts (+₹5,395 at lot 20). A points-only reading would call that event strongly positive;
in rupees it is −₹2,031. **Judge every cross-index comparison on `pnl_net`.**

**The T21 same-leg contrast reproduces a third time, now via the new SL band.** On the 09:48
`NIFTY26JUL23850CE` leg (entry 157.85), the bracket-less-until-07-25 slugs now carry SL ×0.75 = 118.31 and
**three of the five hit `STOP_LOSS` at −25.4%**, while the two `market-movers` positions with a structural
stop exited at −10.0%. Prior to #990 those three would have ridden to the 15:12 square-off at +17.1%
(shadow `id 263`, `gap-theory-nifty`, same leg, same bar, closed SQUARE_OFF at 184.85). **On this specific
leg the new premium stop COST money.** One leg is not a verdict, but it is the first measured downside of
the T21 bands and belongs in the rollup as such.

**Per-rail attribution (champion, context only — never a knob verdict, §3.13):**

| blocking_rail | n | W | pts | net ₹ |
|---|---|---|---|---|
| volume-floor | 29 | 13 | +328.10 | **+11,271.97** |
| rsi-band | 8 | 4 | +137.25 | **−939.01** |
| rsi-5m-cap | 2 | 1 | +42.85 | −1,091.60 |
| hero-zero | 2 | 1 | −1.85 | −234.28 |

Note `rsi-band` again: **+137.25 points but −₹939 net** — the sign flip is the NIFTY/SENSEX lot-size
asymmetry, not a costing error.

**Variant league — this session:**

| variant | closed | wins | pts | net ₹ |
|---|---|---|---|---|
| champion | 41 | 19 | +506.35 | **+9,007.08** |
| vol-12k5 | 10 | 4 | +134.80 | **+4,058.74** |
| vol-off | 12 | 4 | +82.20 | **+2,317.18** |
| composite-055 | **0** | — | — | — |

`composite-055` took nothing, and **that is correct, not a book fault** — the eligibility query (composite
in [0.55, 0.600) AND no failed check other than `confluence-composite`) returns **0 rows**. All 95 rows in
that composite band also failed another rail, so the composite floor was never the binding constraint for
any of them.

**Cumulative league (all sessions, judge on NET ₹):**

| variant | closed | net wins | pts | **net ₹** |
|---|---|---|---|---|
| champion | 219 | 88 | −246.50 | **−35,153.63** |
| composite-055 | 11 | 3 | −10.20 | **−1,542.15** |
| vol-12k5 | 38 | 12 | −218.30 | **−9,331.31** |
| vol-off | 48 | 14 | −388.90 | **−17,014.10** |

Every book remains net-negative all-time. Both orderings survive another session: **`vol-12k5` > `vol-off`**
(relax, don't remove) and **`composite-055` remains the least-bad challenger** — though it has now sat out
a session, so its 11 closes are getting staler rather than more decisive.

**Entry latency (F8):** p50 **79.8 s**, p95 **85.5 s** (63 positions) — inside the structural 73–87 s band,
11th consecutive session. Standing caveat: every shadow fill is stamped ~80 s after `bar_time`.

### 5.1 Counterfactual — the 18 would-have-fired rows (§4.2)

The §3.5 set (composite ≥ threshold, `volume-floor` the **only** failed check) is **18 rows** from 4 slugs,
**all CE**, 11:27–14:00, on 3 legs (`NIFTY26JUL23850CE`, `SENSEX26JUL76200CE`, `SENSEX26JUL76300CE`, expiry
2026-07-28). Per **§3.16**, all four slugs now carry a `premium_pct` block (TP ×1.35, SL ×0.75);
`golden-crossover` additionally carries an entry-candle structural stop on the signal future,
`connect-the-dots` does not.

**12 of the 18 are resolved directly by their own champion shadow row.** The other 6 were suppressed by the
book's one-OPEN-per-slug-side dedup and are modelled by hand from `options_chain_snapshots` + the sibling
shadow's close stamp.

**Result: 8 WOULD-WIN / 10 WOULD-LOSE, +235.10 premium points.**

Shadow-confirmed (12 rows, **4W / 8L, +82.20 pts**):

| rejection | bar | slug | leg | entry | exit | reason | pts | shadow |
|---|---|---|---|---|---|---|---|---|
| 10534 | 11:27 | connect-the-dots-nifty | 23850CE | 156.70 | 184.85 | SQUARE_OFF | **+28.15** | `id 270` |
| 10549 | 11:30 | connect-the-dots-sensex | 76300CE | 751.90 | 805.40 | SQUARE_OFF | **+53.50** | `id 274` |
| 10566 | 11:33 | golden-crossover-nifty | 23850CE | 157.75 | 155.25 | STRUCTURAL_STOP | −2.50 | `id 277` |
| 10601 | 11:39 | golden-crossover-sensex | 76200CE | 793.60 | 774.55 | STRUCTURAL_STOP | −19.05 | `id 280` |
| 10709 | 11:57 | golden-crossover-sensex | 76200CE | 798.15 | 757.75 | STRUCTURAL_STOP | −40.40 | `id 283` |
| 10710 | 11:57 | golden-crossover-nifty | 23850CE | 155.00 | 142.80 | STRUCTURAL_STOP | −12.20 | `id 285` |
| 10911 | 13:18 | golden-crossover-sensex | 76300CE | 735.95 | 734.20 | STRUCTURAL_STOP | −1.75 | `id 287` |
| 10912 | 13:18 | golden-crossover-nifty | 23850CE | 160.85 | 157.00 | STRUCTURAL_STOP | −3.85 | `id 290` |
| 10927 | 13:21 | golden-crossover-sensex | 76300CE | 730.60 | 724.35 | STRUCTURAL_STOP | −6.25 | `id 295` |
| 10928 | 13:21 | golden-crossover-nifty | 23850CE | 156.25 | 155.75 | STRUCTURAL_STOP | −0.50 | `id 298` |
| 11049 | 14:00 | golden-crossover-sensex | 76300CE | 743.20 | 805.40 | SQUARE_OFF | **+62.20** | `id 303` |
| 11050 | 14:00 | golden-crossover-nifty | 23850CE | 160.00 | 184.85 | SQUARE_OFF | **+24.85** | `id 306` |

Modelled — dedup-suppressed (6 rows, **4W / 2L, +152.90 pts**):

| rejection | bar | slug | leg | entry (`wouldBeLeg`) | TP / SL | exit | pts | basis |
|---|---|---|---|---|---|---|---|---|
| 10550 | 11:30 | connect-the-dots-nifty | 23850CE | 163.20 | 220.32 / 122.40 | 184.85 SQUARE_OFF | **+21.65** | neither band touched (path max 191.75, min 124.40) |
| 10568 | 11:33 | connect-the-dots-nifty | 23850CE | 157.75 | 212.96 / 118.31 | 184.85 SQUARE_OFF | **+27.10** | same path |
| 10602 | 11:39 | golden-crossover-nifty | 23850CE | 154.10 | 208.04 / 115.58 | ~148.00 @11:50:37 | −6.10 | sibling `id 280` (same bar, same structural stop 24,032.10) closed 11:50:37 |
| 10620 | 11:42 | golden-crossover-nifty | 23850CE | 158.70 | 214.25 / 119.03 | ~155.25 @≈11:45 | −3.45 | AUGFUT breached 24,034.00 on the 11:45 low; priced off `id 277`'s same-stamp exit |
| 10711 | 11:57 | connect-the-dots-sensex | 76200CE | 798.15 | 1,077.50 / 598.61 | 882.00 SQUARE_OFF | **+83.85** | neither band touched (path max 923.20, min 705.90); square-off price from `id 254`/`256` on the identical leg |
| 10712 | 11:57 | connect-the-dots-nifty | 23850CE | 155.00 | 209.25 / 116.25 | 184.85 SQUARE_OFF | **+29.85** | same path |

**Honest reading.** On a +0.91% trending day the correctly-calibrated relative floor vetoed a net **+235.10
points** across 18 CE rows — **expensive**. But read the shape, not the headline: the 4 `connect-the-dots`
rows (which have no structural stop and rode to the square-off) supply **+162.45** of it, while 10 of the
12 `golden-crossover` rows died on their own structural stop for small losses. **The floor's cost here is
mostly the cost of not being in a trend, and it is concentrated in the one slug family with no stop.**

Set against the shadow league, where all three books that traded made money, the single-knob evidence for
**T1 now stands at 4-for / 5-against** — but see §8: this is the first observation taken on a floor that
was actually calibrated, so the earlier four sessions are weak comparanda.

## 6 New data points / anomalies

### 6.1 ⚠ T23 — 3 canary WARNs, and the unpaired opening-bucket signature RECURS post-fix

`PartialBucketCanary` WARNed **3 times** today (37 on 07-24, 48 on 07-23) — a 92% reduction, consistent with
#981 shipping both the tolerance (650 absolute AND ≤10% relative) and the CandleBuilder day-rollover
baseline fix:

| IST | bucket | engine 3m | Σ(3×1m) | shortfall | lots |
|---|---|---|---|---|---|
| 09:19:07 | **09:15** | 117,000 | 120,185 | **+3,185** | **49** |
| 09:28:06 | 09:24 | 71,565 | 70,720 | −845 | 13 |
| 09:31:06 | 09:27 | 42,185 | 43,030 | +845 | 13 |

The ±845 pair on consecutive buckets is the documented benign boundary residue (13 lots), now above the
650 tolerance only because it exceeds it in absolute terms.

**The 09:15 row is the finding.** It is the **same unpaired opening-bucket shortfall** #981 targeted — the
warm-process day-rollover baseline — at **49 lots instead of 07-24's 94 lots**. The container booted at
23:20 IST the previous evening, so it *was* a warm process across the day rollover, exactly the condition
the fix addressed. Halved but not eliminated. Two readings are possible and this run distinguishes
neither: the fix is partial, or a second contributor exists at the open. **T23 stays PROPOSED**, narrowed
to "the opening bucket only", with a code read of the post-#981 rollover baseline as the next step.

Unlike 07-24, the impact is now low: the 09:15 bar's 3m volume (117,000) is far above every slug's floor
(25,935–49,140), so a 2.7% error on it changed no threshold decision.

### 6.2 ⚠⚠ Three signals fired, zero paper positions, all EXPIRED

The first entries since 2026-07-20:

| id | slug | bar (IST) | composite | tradeable leg | emit latency | status |
|---|---|---|---|---|---|---|
| 89 | `scalp-golden-crossover-sensex-niftyoi` | 14:03 | 0.8040 | `SENSEX26JUL76300CE` | **17,544 ms** | EXPIRED |
| 90 | `scalp-connect-the-dots-sensex-niftyoi` | 14:03 | 0.8040 | `SENSEX26JUL76300CE` | **17,595 ms** | EXPIRED |
| 91 | `scalp-golden-crossover-nifty` | 14:27 | **1.0000** | `NIFTY26JUL23900CE` | **16,683 ms** | EXPIRED |
| 92 | `scalp-connect-the-dots-sensex-niftyoi` (EXIT) | 14:33 | — | — | 205 ms | EXPIRED |
| 93 | `scalp-golden-crossover-sensex-niftyoi` (EXIT) | 14:39 | — | — | 381 ms | EXPIRED |
| 94 | `scalp-golden-crossover-nifty` (EXIT) | 14:39 | — | — | 388 ms | EXPIRED |

**Two separate observations.**

**(a) No paper position was opened by any of the three.** `strategy.paper_positions` contains **only
`minervini` (12 OPEN / 6 CLOSED) and `manas-arora` (6 OPEN / 6 CLOSED)** rows — there is no scalper paper
book at all, and zero rows opened today. The signals carry `book='scalper'`, sat for their full 1-hour
`expires_at` window, and lapsed. `docker logs ay-strategy-signal-service` contains **zero** paper-related
lines for the session. The honest reading is that **scalper→paper routing is simply not armed**, which is
consistent with every prior session showing 0 paper opens — but until today there were no fires to test it
against, so this is the first actual evidence either way. **Filed as T25, an owner question, not a defect
claim** (the arming state may be deliberate).

**(b) The ENTRY path costs ~17 seconds; the EXIT path costs ~0.3 s.** `ay_signal_bar_to_emit_seconds`
recorded 6 observations summing 52.796 s — the three entries at 16.7–17.6 s and the three exits at
0.2–0.4 s. The entry path does strike/leg resolution and a chain read that the exit path does not. On a 3m
scalper that is ~10% of a bar spent before the signal is emitted, and it is measured for the first time
here because fires are rare. **Filed as T26** (measure-then-decide; no tune proposed).

The three entries all resolved to CE legs on the 14:03/14:27 bars near the session high and were exited
6–12 minutes later — consistent with the engine's own exit logic, which the shadow book does not replicate.

### 6.3 ✅ The 11:08–11:16 IST Kite blip left a bounded, 3-minute OI hole

The midday gate flagged an ~8-minute upstream-connectivity episode (ws ticker disconnect + REST session
`ERROR` at 11:11, `CONNECTED` again at 11:16) and asked the EOD run to check for a data hole. Probing
11:05–11:20 IST:

| series | expected | observed |
|---|---|---|
| `NIFTY26AUGFUT` 1m candles | 15 | **15** ✅ |
| `futures_oi_snapshots` distinct minutes | 15 | **12** (3-minute hole) |
| `options_chain_snapshots` distinct minutes | ~15 | 10 (28,480 rows) |

**The 1m candle stream is complete** — the circuit-breaker's cached-data fallback held. The 3-minute OI
gap matches the single `FuturesOiSnapshotService.scheduledSnapshot` ERROR, and the session still finished
at 372/375 minutes. Bounded and self-recovered; nothing to act on.

### 6.4 The front-contract roll silently changed the signal series → README §3.18

Every rejection today evaluated `NFO:NIFTY26AUGFUT@3m`; every prior file in this folder measured
`NIFTY26JULFUT`. Nothing in `signal_rejections` names the contract — `diagnostic.context.chart` has no
`signalSymbol` field, and the roll was only identifiable by matching `context.chart.close` (24,103.90)
against the two contracts' ranges (AUGFUT 23,965.2–24,120.0 vs JULFUT 23,902.4–24,045.0). The two series'
3m volume distributions differ materially (AUGFUT p90 47,320 vs JULFUT p90 57,785, max 117,000 vs
222,560), so a §3.8 ground-truth query run against the wrong contract silently mis-places every threshold.

**Promote to README §3.18: name the signal contract in every session file, and derive it from the data
(match `context.chart.close` against candidate contracts) rather than assuming last session's.** The roll
happens monthly and the next one is ~2026-08-25.

### 6.5 ✅ Fifth consecutive clean interior, and the quietest error channel yet

15-minute buckets 09:15 → 15:15, **no empty bucket**:

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 | 12:15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 8 | 10 | 64 | 52 | 82 | 86 | 24 | 52 | 68 | 88 | 90 | 50 | 30 |
| bucket | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 | 14:45 | 15:00 | 15:15 | |
| n | 48 | 12 | 32 | 78 | 34 | 40 | 72 | 69 | 70 | 80 | 10 | 4 | |

`strategy.subscriber_health_events` **empty**, `ay_signal_eval_failures_total = 0`, **0** strategy-signal
ERROR lines, **0** misaligned candles, **0** `FINNIFTY` canary REDs. The 15:00/15:15 tail is thin (10 and
4 rows, 2 slugs) but **non-empty**, unlike 07-24 — the session ran to 15:18.

### 6.6 Method addenda → README §3.18

Promote to the standard pass: **identify and name the signal contract from the data before running any
§3.8 ground-truth query** (§6.4). The front-future roll is invisible in the rejection row and silently
invalidates cross-session volume comparisons.

## 7 Tuning candidates

Carried forward from 07-24 plus this session's new rows. **Nothing here is applied** — every open row is a
PROPOSAL.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T24** | the `volume` **dot** (w 1.0), as distinct from the `volume-floor` **rail** | 0/909 support, 8th consecutive session | find the dot's own threshold and place it on the operand distribution | §3.3: the dot is STILL 0% with the 125,000 floor gone and the relative floor correctly banded at 25,935–49,140. **The standing "mechanically dead behind the 125k floor" explanation is falsified** — the dot and the rail do not share a threshold, and 1.0 of weight is dead on every row for an unexplained reason | **STRUCTURAL (unexplained)** | **PROPOSED — NEW 07-27, HIGHEST NEW PRIORITY** |
| **T25** | scalper→paper routing | no scalper paper book exists; the 3 fires lapsed `EXPIRED` after 1 h | owner: confirm scalper paper execution is intentionally un-armed, or arm it | §6.2: `paper_positions` holds only `minervini`/`manas-arora`; zero paper log lines all session. First session with fires to test it against | **OWNER (arming question)** | **PROPOSED — NEW 07-27** |
| **T26** | ENTRY-path emit latency | ~17 s bar-close→emit on entries vs ~0.3 s on exits | measure across more fires before proposing anything; likely strike-pick + chain read | §6.2: `ay_signal_bar_to_emit_seconds` 3 entries at 16.7–17.6 s. ~10% of a 3m bar | **STRUCTURAL (measurement)** | **PROPOSED — NEW 07-27, collect more fires** |
| **T23** | 3m-vs-1m volume attribution on the opening bucket (+ `artha.signals.partial-bucket-canary.volume-tolerance`) | post-#981: 3 WARNs/session; the 09:15 unpaired shortfall persists at 49 lots (was 94) | code-read the post-#981 day-rollover baseline; the tolerance half is DONE | §6.1: 37 → **3** WARNs, but the same unpaired opening-bucket signature recurs at half magnitude on a warm process that crossed the day rollover. Impact now low (the 09:15 bar clears every floor by 2.4×) | **STRUCTURAL (defect, narrowed)** | **PROPOSED — narrowed to the opening bucket; NOT closed** |
| T16 | `relative-volume-floor` tag on all scalpers | **armed on 38/38**, published 2026-07-25 21:44 | — | §2.1: zero slugs on the flat floor; thresholds 25,935–49,140 track the tape between p55 and p92 of the operand | **STRUCTURAL (regression)** | ✅ **CLOSED 2026-07-27 — resolved by [#980](https://github.com/prashantm912/artha-yantra-2/pull/980), verified live this session** |
| T21 | premium exit rules on the bracket-less scalpers | **63/63 YAMLs carry `premium_pct`**; TP ×1.35 / SL ×0.75 | — | §5: 16 of 18 trading slugs had both bands set; the book's **first 3 `STOP_LOSS` closes ever**. ⚠ On the 09:48 NIFTY leg the new stop COST money (−25.4% vs +17.1% at square-off) — carry that as a watch, not a reversal | **STRUCTURAL (config)** | ✅ **CLOSED 2026-07-27 — resolved by [#990](https://github.com/prashantm912/artha-yantra-2/pull/990); downside case logged for the rollup** |
| T6 | `vwap` dot support condition | **≥15 bps distance** (#991) | — | §3.1: 100% ×6 sessions / 5,225 rows → **17.5%**; CE 22.0% / PE 0.0%; row-level check confirms the bps arithmetic. ⚠ The heaviest dot went free→scarce, so **T7 must be re-judged on post-#991 sessions only** | **STRUCTURAL** | ✅ **CLOSED 2026-07-27 — resolved by [#991](https://github.com/prashantm912/artha-yantra-2/pull/991)** |
| T22 | `oi_spurt` floors | **(15, 3)** (#991) | — | §3.2: 0.0% ×2 → **9.9%**; ground truth `\|spurtOiPct\|` p90 = 14.44, so a floor of 15 predicts ~10% support and delivered 9.9% | **STRUCTURAL** | ✅ **CLOSED 2026-07-27 — resolved by [#991](https://github.com/prashantm912/artha-yantra-2/pull/991)** |
| T17 + T13 | `dot-health` sampling window + OI probes | context-bearing sampling, `rowsScanned` field, `futures_oi`/`underlying_oi` REQUIRED | — | §4: correct read at 16:56 IST (`rowsScanned 200 / rowsInspected 40`), `breadth` alive as it should be, both OI probes present. Four consecutive false all-dead readings ended | **STRUCTURAL (diagnostic)** | ✅ **CLOSED 2026-07-27 — resolved by [#983](https://github.com/prashantm912/artha-yantra-2/pull/983)** |
| T12 | futures-OI capture cadence | dedicated `oiCaptureTaskScheduler` (#1031) | — | §4: **372 of 375 minutes (99.2%)** after 211 / 198 / 187 / 192 / 208; quadrants live a 5th session | **STRUCTURAL (defect)** | ✅ **CLOSED — cadence half now verified live 2026-07-27** |
| T19 | gap-backfill bucket alignment | floored at the `fetchAndStore` choke point (#982) | — | §4: **0 misaligned rows for a 3rd consecutive session**; the 1,682-row July cleanup holds | **STRUCTURAL (defect)** | ✅ **CLOSED — 3 clean sessions** |
| T20 | far-month contracts in the bar-divergence canary | tick-density guard, min-divergence-ticks 30 (#986) | — | §4: **0 canary REDs**; `FINNIFTY26SEPFUT` still thin (35 bars) but silent | ops (noise) | ✅ **CLOSED 2026-07-27** |
| T14 | rejection-row margin invariant | proposed as global `blocking_margin < 0` | make it sign-aware per rail direction; record the operand that actually failed on `confluence-composite` optional-gate blocks | §2.3: **0 self-contradicting rows today** (was 3 on 07-24, 1 on 07-23) — but on a CE-dominated tape with no `vwap-distance` first-blocks at all, and the code is untouched | **STRUCTURAL (diagnostic)** | **PROPOSED — carried; one quiet session is not a fix** |
| T7 | composite threshold | 0.600 | no change **for now** | `composite-055` sat out (0 eligible rows, §5). The cap ROSE to 0.9043 (oi_spurt revived) while the heaviest dot got scarce (#991) — **the threshold's meaning changed this session**; all pre-07-27 challenger evidence is stale | — | **REJECTED — but re-baseline on post-#991 sessions before quoting any challenger evidence** |
| T1 | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | §5.1: the floor vetoed **+235.10 pts** on a trending day (4-for/5-against). ⚠ **This is the FIRST observation on a correctly-calibrated floor** — the four preceding sessions measured a welded 125,000, so the ledger's earlier rows are weak comparanda | **REGIME** | **PROPOSED — still do NOT apply; the evidence base effectively restarts today** |
| T10 | stale OPEN paper positions | **18** OPEN (was 17), 12 CLOSED | square off / age out, or subscribe the swing holdings | §4: the 07-24 20:00/20:05 batch closed TIRUPATIFL (−₹686.15) but opened **2** new — the drain reversed. #992's `eod-managed-books` stopped the alert paging, not the accumulation | ops | **OWNER — chronic, re-accumulating** |
| T15 | engine boot-line durability | V046 table (#987) | — | §1: readable today only because nothing restarted; the persisted table exists but was not queried this run | **STRUCTURAL (data-model)** | **SHIPPED #987 — verify the table on the next restart** |
| T8 | shadow entry latency | p50 ~80 s | stamp entry at bar close | 11 sessions in the 73–87 s band | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source ivRank or drop from Σw | dead every session since 07-02 | **STRUCTURAL** | **PROPOSED** (carried) |
| T3 | `iv_pair` gap threshold | 0.02 | ~0.005 | 0/909 today; 0% on every session logged (8×) | **STRUCTURAL** | **PROPOSED** (carried) |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | 0/135 again (7th session) | **REGIME** (7 sessions) | **PROPOSED**, collect more |
| T18 | `breadth` dot threshold | `advances/declines > 32` | **no change** | 79.5% today after 38.7% / 22.1% / 96.6% / 0.2% — regime, as concluded | **REGIME (resolved)** | **CLOSED — no action** |
| T11 | SENSEX volume-floor | — | — | absorbed by T16; all 10 sensex CE slugs armed 07-25 | — | ✅ **CLOSED with T16** |
| T4 | `basis` dot | w 1.0 | — | alive (79.5%) | — | **CLOSED — no action** |

**Ledger note (README §5 rule).** T24, T25 and T26 are new; T23 is narrowed but still open. Group **G1** in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
says the blocked tunes (T1/T2/T3/T5/T7) unblock "after 2 clean forward sessions post-D-wave (earliest Tue
2026-07-28)". **Today is the first of those two.** G1 stays BLOCKED-DATA; re-check after the 07-28 session.

## 8 Honesty caveats

- **§5.1's +235.10 points is 18 rows from 4 slugs on 3 legs across 2.5 hours of one directional up-move —
  not 18 independent bets.** The 4 `connect-the-dots` rows supply +162.45 of it and are the same leg at
  four adjacent bars. Six of the 18 exits are **modelled**, not observed: the two `golden-crossover` rows
  are priced off a sibling shadow's close stamp on the same structural-stop level, and the four
  `connect-the-dots` rows assume neither premium band was touched between 2-minute snapshots. `10550`'s
  SL (122.40) sits **2.0 points** above the observed path minimum (124.40) — a 1-minute wick could have
  triggered it and the snapshot cadence would not show it. Snapshot granularity ~2 min; no slippage or
  fees; strike-pick approximated.
- **This is the first session measuring a correctly-calibrated volume floor.** Every "protective" or
  "expensive" verdict in the 07-21…07-24 files was taken against a floor welded 4–6× above the tape. The
  T1 ledger tally (4-for/5-against) mixes those readings with today's and should be treated as
  **effectively restarting on 2026-07-27**.
- **Points are not commensurable across NIFTY and SENSEX** (lot 75 vs 20). §5's 09:48 pair differs in sign
  between the two measures. Every league and attribution number here is quoted in **net ₹** for that
  reason; the points columns are scale-free comparison only.
- **The champion book's +₹9,007.08 is 21 distinct entry events, not 41 positions** (9W/12L), and three
  clusters carry essentially the whole spread. The headline should not be read at face value.
- **§6.1 identifies a recurrence, not a root cause.** The 09:15 +3,185 shortfall matching #981's target
  signature at half magnitude is measured; "the fix is partial" is one of two inferences that fit, the
  other being a second contributor at the open. The post-#981 rollover baseline was **not read in code**
  this run.
- **§6.2(a) states an observation, not a diagnosis.** "Scalper paper routing is not armed" is inferred
  from the absence of a scalper book, the absence of paper log lines, and 0 opens — it was **not** confirmed
  against the paper module's configuration or code. It may be deliberate.
- **T21's downside case is one leg.** The 09:48 `NIFTY26JUL23850CE` stop-out at −25.4% against a
  same-leg square-off at +17.1% is real and measured, but it is a single entry event on a day the leg
  recovered. It is logged so the rollup does not record T21 as costless.
- **T6's closure changes what every prior composite number means.** The heaviest dot (w 2.5) went from
  always-supporting to 17.5%, so composites, the near-miss mass and the effective threshold are all on a
  new footing from today. Cross-session composite comparisons spanning 2026-07-25 are invalid.
- **`composite-055` took zero rows**, so its all-time −₹1,542 over 11 closes is now a stale number being
  quoted a third session running.
- **The signal series rolled to `NIFTY26AUGFUT`** (§6.4). All volume percentiles, thresholds and operand
  distributions here are on that contract. The `NIFTY26JULFUT` numbers in every earlier file are a
  different series.
- Shadow exits replicate premium brackets + structural stop + square-off — no indicator-driven exits.
  Every entry is stamped ~80 s after `bar_time`.
- Costs are the 1-lot engine fill model (statutory + ₹20/lot). Not cost-adjusted twice.
- This run was **read-only**: SELECTs, `docker logs`, in-container actuator/health GETs, and source/git
  reads. No restart, deploy, write or config change. No strategy knob was altered.
