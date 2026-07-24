# Session findings — 2026-07-24 (data date)

Analysis date: 2026-07-24 (scheduled post-market agent, ran 16:00–17:10 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,366** (bar times 09:18–14:57 IST), signals fired **0**, paper
positions opened **0**, shadow positions **38** (22 champion + 16 challenger, all closed).
Session character: **quiet up-day.** `NIFTY26JULFUT` opened 23,700.00 and closed **23,830.00**
(**+130.00 pts, +0.55%**), range 23,640.00–23,854.00 (214 pts). `NIFTY 50` 23,666.35 → 23,786.70.
**The tape was thin again** — 3m volume median **25,935** vs 36,595 on 07-23 (−29%).

This file folds in the two earlier read-only runs of the same date: `2026-07-24-open-gate.md`
(PASS) and `2026-07-24-live-watch-findings.md` (GREEN).

---

## 0 Read this first — the session's headline

**Three things, in order of weight.**

1. **NEW DEFECT — the live 3m signal series and its own 1m series disagree on volume, 37 times
   today, and every discrepancy is an exact multiple of the NIFTY lot size (65).** `PartialBucketCanary`
   WARNed 37× on `NFO:NIFTY26JULFUT@3m` — and only on that series, the one every scalper signals off.
   The magnitudes are 65, 130, 195, 260, 325, 390, 455, 520, 845, 1,560, 1,820 and **6,110** — i.e.
   1, 2, 3, 4, 5, 6, 7, 8, 13, 24, 28 and **94 lots** — and they come in near-perfect ± pairs on
   consecutive buckets. Both sides of the comparison are **in-memory** (`LiveSeriesStore`), so this is
   an engine-internal boundary-tick attribution race between the 1m and 3m aggregation, **not** the
   frozen-first-minute partial the canary was built to catch (that signature is a persistent ~⅔
   shortfall, not an alternating ± pair). It matters because the `volume-floor` operand is read off
   that 3m series: at the 09:15 bar the operand was off by **6,110 (4.7% of the bar)**. Filed as
   **T23**. Promoted to README **§3.17**.

2. **T16 (the `relative-volume-floor` regression) is UNRESOLVED for a fourth session — and today it
   was strongly PROTECTIVE.** The registry is byte-unchanged since the 2026-07-20 21:28 republish: 18
   PE slugs + 10 sensex CE slugs still run the fixed **125,000** floor. It first-blocked **998 of
   1,366 rows (73.1%)**. The 15 rows it **alone** vetoed resolve **2 WOULD-WIN / 13 WOULD-LOSE,
   −1,020.10 premium points** (§5.1) — and both "wins" are +0.30 and +0.65 points, i.e. same-bar
   structural stops, not trades. That is **three protective sessions out of four** (07-21 protective,
   07-22 expensive, 07-23 protective, 07-24 protective). The case for T16 remains "an armed knob was
   silently disarmed" — a correctness argument, never a profitability one.

3. **Coverage was clean but the tail is short.** 36 of 38 loaded scalpers emitted (the 2 silent ones
   are the `hero-zero` pair). No empty 15-minute bucket 09:15→14:45, `subscriber_health_events`
   **empty**, `ay_signal_eval_failures_total = 0`, **zero misaligned 1m candles** (T19 quiet, 2nd
   session), OI quadrants live a **4th** session. But the rejection stream stops at bar **14:57** and
   the 15:00/15:15 buckets are empty — the eval loop ran all 375 minute-cycles, so this is a
   gate-stage effect (chart gate), not a stall. Flagged, not diagnosed.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-21 | 2026-07-22 | 2026-07-23 | **2026-07-24** |
|---|---|---|---|---|
| rejections | 1,372 | 1,042 | 1,430 | **1,366** |
| distinct strategies emitting | 22 | 36 | 38 | **36** |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| scalpers loaded by the engine | 38 | 38 | 38 | **38** |
| **coverage ratio (§3.10)** | 22/38 | 36/38 | 38/38 | **36/38** |
| signals fired | 0 | 0 | 0 | **0** (7th consecutive) |
| paper positions opened | 0 | 0 | 0 | **0** |
| bar-time coverage | 09:21–14:57 | 09:18–15:18 | 09:18–15:18 | **09:18–14:57** |
| composite ≥ threshold rows | 218 | 568 | 634 | **418** (218 CE / 200 PE) |
| scored rows | 1,070 | 828 | 1,120 | **1,100** |
| max composite | 0.7447 | 0.8511 | 0.8511 | **0.7447** |

The two silent slugs are `scalp-hero-zero-nifty` and `scalp-hero-zero-sensex-niftyoi` (set-difference
against the registry). Both are 0DTE-shaped families on a Friday with a Tuesday weekly expiry —
consistent with the tape, not a load failure; the engine's own load line reports 38/0-unresolved.

**Engine load (§3.10) — the container has not restarted since the 2026-07-23 08:37 IST boot, so the
boot line is still readable (3rd session running):**

```
2026-07-23T03:08:03Z (08:38 IST)  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
2026-07-23T03:09:06Z (08:39 IST)  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

The known F10 cold-start shape; the #874 retry recovered 63 s later. Health signal `unresolved == 0`
holds. **Caveat carried from 07-23:** this line is yesterday's boot, not today's — today had no boot
at all, so §3.10's "read the boot line the same day" is satisfied only because nothing restarted.

**Eval counters (actuator :8082, read 16:32 IST — no post-close deploy has run).** Counters are
cumulative since the 07-23 08:37 boot, so today's figures are the **delta** against the 07-23 close:

| outcome | 07-23 close (cumulative) | 07-24 close (cumulative) | **Δ = today** |
|---|---|---|---|
| `chart-gate-failed` | 1,986 | 4,012 | **2,026** |
| `composite-below-threshold` | 154 | 332 | **178** |
| `confluence-blocked` | 1,430 | 2,796 | **1,366** |
| `fired` | 0 | 0 | **0** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 | 0 | **0** |
| **Σ** | 3,570 | 7,140 | **3,570** |
| `ay_signal_eval_failures_total` | 0 | 0 | **0** |

`confluence-blocked` Δ = **1,366** = today's row count exactly; the two views agree.
`ay_signal_eval_duration_seconds`: count 750 cumulative ⇒ **375 today** = one eval cycle per
minute-bar, 09:15→15:29, **no stall**; sum Δ 1,469.88 s ⇒ mean **3.92 s**/eval (07-23: 3.75 s).
**The 375 cycles are what rules the empty 15:00–15:29 rejection tail out as a stall** (§6.2).

**First-blocking-rail histogram** (1,366 rows, 14 distinct rails):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **998 (73.1%)** | 31,059.5 | 103,454.0 | −72,394.4 |
| time-window | 210 (15.4%) | — | — | — |
| time-of-day-preference | 44 | — | — | — |
| vwap-distance | 40 | 0.0050 | 0.0040 | **+0.00050** (ceiling rail — correct, §2.3) |
| rsi-band | 12 | 77.20 | — | — |
| option-side-constraint | 12 | — | — | — |
| confluence-composite | 10 | 0.544 | 0.600 | −0.0555 |
| divergence-vol-gate | 8 | 49,050.6 | — | — |
| pct-price-move | 7 | 0.225 | 1.000 | −0.775 |
| two-candle | 7 | — | — | — |
| volume-pump | 6 | 43,831.7 | — | — |
| oi-cross-required | 6 | 164.40 | — | — |
| supertrend-15m | 4 | — | — | — |
| directional-change-gate | 2 | 1.020 | — | — |

**All-failed-rails expansion (§3.3)** — top 15:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | 1,035 | 31,002.8 | 102,894.4 |
| confluence-composite | FAIL_CLOSED | 900 | 0.550 | 0.600 |
| **strike-pick** | FAIL_CLOSED | **550** | — | — |
| time-window | FAIL_CLOSED | 210 | — | — |
| rsi-band | FAIL_CLOSED | 172 | 42.68 | — |
| divergence-vol-gate | FAIL_CLOSED | 166 | 32,488.3 | — |
| trend-change | FAIL_CLOSED | 166 | — | — |
| pct-price-move | FAIL_OPEN | 146 | 0.160 | 1.000 |
| volume-pump | FAIL_OPEN | 146 | 32,715.5 | — |
| two-candle | FAIL_CLOSED | 146 | — | — |
| oi-divergence-magnitude | FAIL_CLOSED | 118 | −17.40 | 20.00 |
| oi-cross-required | FAIL_CLOSED | 118 | 131.82 | — |
| constituent-gate | FAIL_OPEN | 102 | −0.520 | — |
| directional-vix-gate | FAIL_OPEN | 102 | 14.13 | — |
| directional-change-gate | FAIL_CLOSED | 84 | −0.094 | — |

`strike-pick` fails rose **390 → 550** (07-23 → 07-24), now the 3rd-largest failing rail two sessions
running. It is a FAIL_CLOSED leg-resolution rail, so those rows never reach a shadow position. Still
not bucketed by slug/time — carried as an open question for a third session.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 ⚠⚠ T16 UNRESOLVED — the PE book is still on the fixed 125,000 floor (4th session)

**Registry state, standing §3.14 query, run today — byte-unchanged from 07-21, 07-22 and 07-23:**

| carries `relative-volume-floor` | published on | count | family |
|---|---|---|---|
| **yes** | 2026-07-06 | 10 | all nifty CE |
| no | 2026-06-29 (7) / 06-30 (3) | 10 | all sensex CE (the old T11) |
| **no** | **2026-07-20 21:28:5x** | **18** | **all `-pe`, nifty AND sensex** |

**Row proof, same session, both shapes side by side:**

| slug | n | min thr | max thr | avg operand |
|---|---|---|---|---|
| `scalp-connect-the-dots-nifty` (CE, armed) | 41 | **29,396.25** | **53,283.75** | 24,158 |
| `scalp-golden-crossover-nifty` (CE, armed) | 34 | **29,737.50** | **53,283.75** | 24,425 |
| `scalp-trending-oi-nifty` (CE, armed) | 31 | **29,396.25** | **53,283.75** | 25,916 |
| `scalp-connect-the-dots-nifty-pe` | 32 | 125,000.00 | 125,000.00 | 36,150 |
| `scalp-golden-crossover-nifty-pe` | 21 | 125,000.00 | 125,000.00 | 27,653 |
| `scalp-trend-change-sensex-niftyoi` (CE, never armed) | 51 | 125,000.00 | 125,000.00 | 30,191 |
| … (all 18 `-pe` + all 10 sensex CE slugs at a flat 125,000; 26 of the 34 emitting slugs) | | | | |

**On a thinner tape the fixed floor cleared exactly ONE bar in 125.** 3m rollup of `NIFTY26JULFUT`
09:15–15:30 IST, **minute-aligned bars only** (§3.15):

| bars | min | p50 | p90 | p95 | max | bars ≥ 125,000 |
|---|---|---|---|---|---|---|
| 125 | 11,700 | **25,935** | 67,015 | 78,520 | **131,300** | **1** (the 09:15 opening bar) |

The single qualifying bar is the 09:15 opener — before most slugs' trade windows open. The armed CE
slugs' floor ran **29,396–53,284** on the same tape, i.e. the relative floor tracked the thinner tape
*downward* exactly as designed while the fixed one stayed welded shut.

**Verdict: STRUCTURAL regression, fourth session, still owner-gated.** See §5.1 — today it was
protective, for the third time in four sessions.

### 2.2 What the floor blocked today

**998 of 1,366 rows** first-blocked by `volume-floor`. Of the composite-passing population, **15 rows
had `volume-floor` as their ONLY failed check** (§3.5) — down from 22 on 07-23 and 86 on 07-22. They
are 2 slugs (`scalp-connect-the-dots-nifty-pe`, `scalp-golden-crossover-nifty-pe`), all PE, 09:57–11:12,
on 2 legs. §5.1 resolves every one.

### 2.3 `vwap-distance` positive margins — 40 rows, all correct (T14 refinement holds)

All 40 `vwap-distance` first-blocks log operand > threshold with a positive margin (avg +0.00050).
`vwap-distance` is a **ceiling** rail, so a positive `operand − threshold` is the semantically correct
failure signature. This re-confirms the 07-23 refutation of the blanket `blocking_margin < 0`
invariant: **the invariant must be sign-aware per rail direction.** Row count quadrupled (10 → 40) on
a day whose price spent more time away from VWAP; that is regime, not a defect.

### 2.4 ⚠ `confluence-composite` self-contradiction recurs — 3 rows today (was 1)

Three rows record `blocking_rail = confluence-composite` with a `composite_score` that **passes** its
own threshold (e.g. `scalp-connect-the-dots-nifty`, operand 0.6373 vs threshold 0.6000, margin
+0.0373). Same class as 07-23's `id 7794`: the documented optional-gate semantics would explain a
block, but the row records the **full** composite as the operand rather than the required-only
sub-composite that actually failed. Diagnostic mis-labelling, not a gate defect — 2nd confirmation,
folded into **T14**. It also means the §3.5 query mis-attributes these 3 rows.

### 2.5 Rails with no evidence of miscalibration

`rsi-band` (avg 42.68 on failures), `pct-price-move` (0.160 vs 1.000), `oi-divergence-magnitude`
(−17.40 vs 20.0), `oi-cross-required`, `volume-pump` / `constituent-gate` / `directional-vix-gate`
(all FAIL_OPEN) read plausibly. No order-of-magnitude gaps other than §2.1.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (1,100 scored rows):

| bucket | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 |
|---|---|---|---|---|---|
| n | 38 | 52 | 260 | 592 | 158 |
| CE | 14 | 46 | 196 | 344 | 74 |
| PE | 24 | 6 | 64 | 248 | 84 |

Max composite **0.7447**. Threshold 0.600; **418 rows cleared it: 218 CE and 200 PE** — two-sided for
a second consecutive session, but with **no 0.8 bucket at all** (07-22 and 07-23 both reached 0.8511).

**Dot support rates** (1,100 scored rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `oi_spurt` | 1.0 | 0/1,100 | 0.0 | ⚠ dead — **2nd consecutive session at 0.0%** (T22) |
| `volume` | 1.0 | 0/1,100 | 0.0 | mirrors §2.1 — mechanically dead behind the 125k floor |
| `iv_abs_band` | 0.8 | 0/166 | 0.0 | dead (6th session) |
| `iv_rank` | 0.8 | 0/1,100 | 0.0 | dead-data, **withheld from Σw** (#676) |
| `iv_pair` | 0.8 | 0/1,100 | 0.0 | dead — carried since 07-02 (7th confirmation) |
| `trending_cross` | 1.0 | 104/1,100 | **9.5** | ⚠ new low (07-23: 45.0%) — regime, watch |
| `iv_slope` | 0.8 | 52/166 | 31.3 | alive |
| `sentiment_slope` | 1.0 | 378/1,100 | 34.4 | |
| `vix` | 1.0 | 426/1,100 | 38.7 | |
| `breadth` | 1.0 | 426/1,100 | 38.7 | regime (22.1% → 38.7%) — T18 stays closed |
| `futures_oi` | 1.5 | 610/1,100 | 55.5 | ✅ alive (4th session) |
| `underlying_oi` | 1.0 | 658/1,100 | 59.8 | ✅ alive (4th session) |
| `basis` | 1.0 | 674/1,100 | 61.3 | alive |
| `rsi` | 1.0 | 784/1,100 | 71.3 | |
| `sentiment` | 1.0 | 830/1,100 | 75.5 | |
| `psar` | 1.0 | 850/1,100 | 77.3 | |
| `drastic_oi` | 1.0 | 958/1,100 | 87.1 | |
| `vwma` | 1.0 | 1,026/1,100 | 93.3 | |
| `supertrend` | 1.0 | **1,100/1,100** | **100.0** | ⚠ 2nd session at exactly 100% (07-21 was the first) |
| `vwap` | 2.5 | **1,100/1,100** | **100.0** | ⚠ **6th consecutive session at 100%** |

**`vwap` has now supported 5,225 consecutive rows** (359 + 748 + 1,070 + 828 + 1,120 + 1,100) at the
heaviest weight in the model, across an up-trend, a chop, a mild-down, a clean-down, a flat and now a
quiet-up session. **T6 remains the best-evidenced row in the ledger.**

**`oi_spurt` is dead for a second consecutive session** (3.0% → 0.2% → 0.0% → 0.0%) with the input
data present (`spurtOiPct` non-null on all 1,100 context-bearing rows). **T22's "collect one more
session" condition is now met** — the read is threshold-vs-operand, not a feed outage. A number still
requires the operand's real distribution (a §3.8-class ground-truth query on `spurtOiPct`), which this
run did not compute; T22 stays PROPOSED with that as its next step.

### 3.1 The dead-weight cap — today the ceiling was NOT reached

| dots on row | rows | Σw | withheld (iv_rank) | dead w | cap | observed max |
|---|---|---|---|---|---|---|
| 18 | 934 | 19.60 | 0.80 | 2.80 (volume + iv_pair + oi_spurt) | **0.8511** | **0.7447** |
| 20 | 166 | 21.20 | 0.80 | 3.60 (+ iv_abs_band) | 0.8235 | 0.6863 |

18-dot cap = (18.80 − 2.80)/18.80 = **0.851063…**, identical to 07-22 and 07-23 (same dead roster).
Today's max **0.7447 = 87.5% of the cap** — unlike 07-23, when 48 rows sat exactly on the ceiling.
The gap is `trending_cross` collapsing to 9.5% and `vix`/`breadth` at 38.7%: a genuine market
reading, not an arithmetic one. The 0.600 threshold still requires **70.5% of live weight**.

## 4 Data health (§3.7)

| field | 2026-07-22 | 2026-07-23 | **2026-07-24** | class |
|---|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | 0 NEUTRAL | 0 NEUTRAL | **0 NEUTRAL**, 15 combos, 266 NULL | ✅ healthy (4th session) |
| `spurtOiPct` / `spurtPricePct` | null 214/1,042 | null 310/1,430 | null **266**/1,366 | ✅ healthy |
| `advances` / `declines` | 0 zero-pairs | 0 zero-pairs | **0 zero-pairs**, 266 null | HEALTHY |
| `ivRank` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried since 07-02) |
| `fiiLongPct` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 100%** | by design (un-armed) |
| `vix` (macro mirror) | NULL 100% | NULL 100% | **NULL 100%** | known mirror gap (the `vix` **dot** is fine — 38.7%) |

**The 266 nulls reconcile exactly**: 210 `time-window` + 44 `time-of-day-preference` + 12
`option-side-constraint` = **266** — the rows blocked at a rail that fires *before* macro/OI context
is gathered. Every context-bearing row carries live data.

**Capture (minute-aligned bars only):**

| series | bars | misaligned | last bar |
|---|---|---|---|
| `NIFTY26JULFUT` 1m | **375** | **0** | 15:29 |
| `SENSEX26JULFUT` 1m (BFO) | **375** | 0 | 15:29 |
| `NIFTY 50` 1m | **375** | 0 | 15:29 |
| `SENSEX` 1m | **375** | 0 | 15:29 |
| `NIFTY26AUGFUT` / `FINNIFTY26AUGFUT` 1m | **375 / 375** | 0 | 15:29 |
| `futures_oi_snapshots` (`NIFTY26JULFUT`) | **211 rows / 211 distinct minutes** | — | — |
| `options_chain_snapshots` | 1,286,672 rows / 6,844 symbols | — | — |

✅ **T19 quiet for a second consecutive session — zero misaligned 1m rows session-wide.** No feed
outage today (the kite-rest breaker opened **0** times, against 93 warnings on 07-23), hence no gap
backfill, hence no phantom bars. Two clean negative controls now support the 07-22 diagnosis.

✅ **`futures_oi_snapshots` cadence 211 of ~375 minutes (56%)** — the best of the last five sessions
(198, 187, 192, 208). Still ~half, but the decline has clearly reversed. And the OI quadrants were
live for a **fourth** consecutive session, re-confirming that the cadence regression is not the
mechanism behind the 07-20 NEUTRAL outage (T12).

**`dot-health` canary — T17 fired again, 4th consecutive session, and worse.** Read at 15:58 IST it
reports `session=false, rowsInspected=40` with **all six probed dots dead, including the required
`breadth`** — which supported **426 of 1,100** rows over the session. The newest 40 rejections at
that hour are the 14:30–14:57 `time-window` rows, which carry no macro/OI context at all. This is the
documented sampling defect, now confirmed on four straight sessions.

**Market-data ERROR channel: 14 lines, all `FINNIFTY26SEPFUT`** ("ticks flowing but no 1m bar closed
for 374–892 s"). That contract logged **35** aligned bars all day — the established thin-tape
artifact, 5th consecutive session. ✅ **`FINNIFTY26AUGFUT`'s 34-minute hole from 07-23 did NOT recur**
(375/375 bars today), so T20's respecification holds: exclude on *measured thin tape*, never
blanket-exclude far-month contracts.

## 5 Shadow-book outcomes

**Exit-fidelity caveat (standing, per §3.16):** indicator-driven exits (trend-flip / signal-exit) are
NOT replicated, **and 30 of the 38 live slugs have no premium bracket configured at all** — for those
slugs the book models only the structural stop (where set) and the 15:12 square-off. Rejections
blocked before leg resolution never shadow.

**Champion book — 22 closed, 8W/14L, −191.25 pts, −₹14,195.21.** This takes the all-time champion
book from −₹29,965.50 to **−₹44,160.71** over 178 closes.

⚠ **CORRELATION CAVEAT — 22 positions are 11 distinct entry events.**

| event (bar, leg, entry) | positions | outcome |
|---|---|---|
| 09:27 NIFTY26JUL23950PE @281.15 | 1 | **L** −25.5% (square-off) |
| 09:48 NIFTY26JUL23900PE @275.00 | 7 | **L** (5 × −36.0% square-off, 1 × −7.3% structural stop) |
| 10:00 NIFTY26JUL23900PE @276.15 | 1 | **L** −36.2% |
| 10:21 NIFTY26JUL23900PE @296.90 | 1 | **L** −8.0% (structural stop) |
| 11:00 NIFTY26JUL23900PE @289.30 | 1 | **L** −2.9% (structural stop) |
| 11:24 NIFTY26JUL23450CE @290.20 | 7 | **W** (1 × TAKE_PROFIT +37.5%, 5 × +25.1% square-off, 1 × −2.7% structural stop) |
| 11:42 NIFTY26JUL23500CE @252.90 | 1 | **W** +35.7% (TAKE_PROFIT) |
| 12:06 NIFTY26JUL23500CE @282.25 | 1 | **W** +13.6% (square-off) |
| 12:30 NIFTY26JUL23600CE @265.20 | 1 | **L** −11.2% (structural stop) |
| 12:33 NIFTY26JUL23600CE @254.15 | 1 | **L** −6.5% (square-off) |
| 14:00 NIFTY26JUL23600CE @269.10 | 1 | **L** −7.6% (structural stop) |

**Counted once per event: 3W / 8L.** The 09:48 PE cluster (7 positions on one leg) carries −514.50 of
the day's −191.25 net points; the 11:24 CE cluster (7 positions) carries +465.05. **Everything else
is noise around those two clusters** — the headline −₹14,195 is two entry decisions, not 22.

**The §6.1/T21 contrast repeats, cleanly.** On the 09:48 PE leg, the five bracket-less positions rode
to the 15:12 square-off at **−36.0%**; the one `scalp-market-movers-nifty-pe` position on the
identical leg at the identical price carried a structural stop and lost **−7.3%**. Second session
running that the same-leg contrast is visible. Symmetrically, on the 11:24 CE leg the one position
with a take-profit (`scalp-gap-theory-nifty`) booked **+37.5%** while the five bracket-less ones took
+25.1% at square-off — the bracket helped in **both** directions today.

**Per-rail attribution (champion, context only — never a knob verdict, §3.13):**

| blocking_rail | n | W | pts | net ₹ |
|---|---|---|---|---|
| volume-floor | 20 | 7 | −145.10 | −11,041.86 |
| rsi-band | 1 | 0 | −29.70 | −2,007.30 |
| vwap-distance | 1 | 0 | −16.45 | −1,146.05 |

**Variant league — this session:**

| variant | closed | wins | pts | net ₹ |
|---|---|---|---|---|
| champion | 22 | 8 | −191.25 | **−14,195.21** |
| vol-off | 7 | 1 | −102.35 | **−7,206.85** |
| vol-12k5 | 7 | 1 | −102.35 | **−7,206.85** |
| composite-055 | 2 | 1 | +47.95 | **+2,956.44** |

Both loosened books took the identical 7 entries again — 5 of them structural-stop losses on
`golden-crossover` legs — and both lost the same −₹7,207, a second consecutive losing session for
them. **`composite-055` had its first profitable session** (+₹2,956 on 2 closes: a
`connect-the-dots-nifty` 23500CE that rode +21.7% to square-off, and a `golden-crossover-nifty`
23650CE stopped out at −3.8% two minutes after entry).

**Cumulative league (all sessions, judge on NET ₹):**

| variant | closed | net wins | pts | **net ₹** |
|---|---|---|---|---|
| champion | 178 | 72 | −752.85 | **−44,160.71** |
| composite-055 | 11 | 3 | −10.20 | **−1,542.15** |
| vol-12k5 | 28 | 8 | −353.10 | **−13,390.05** |
| vol-off | 36 | 10 | −471.10 | **−19,331.28** |

Every book remains net-negative all-time. Two orderings have now survived every session in which both
books traded: **`vol-12k5` > `vol-off`** (relax, don't remove) and — newly, and against the earlier
reading — **`composite-055` is the least-bad challenger by a wide margin** (−₹1,542 over 11 closes).
That is 11 closes, not a verdict; but T7 ("composite threshold: no change") should stop being
described as *reaffirmed by the challenger book*, because the challenger book no longer says that.

**Entry latency (F8):** p50 **76.3 s**, p95 **82.6 s** (38 positions) — inside the structural 73–87 s
band, 10th consecutive session. Standing caveat: every shadow fill is stamped ~76 s after `bar_time`.

### 5.1 Counterfactual — the 15 would-have-fired rows (§4.2)

The §3.5 set (composite ≥ threshold, `volume-floor` the **only** failed check) is **15 rows** from 2
slugs, all PE, 09:57–11:12, on 2 legs (`NIFTY26JUL23900PE` / `23850PE`, expiry 2026-07-28). Per
**§3.16**, neither slug carries a `premium_pct` block, so **no take-profit and no premium stop** is
modelled. `connect-the-dots-nifty-pe` carries no structural stop either (square-off only);
`golden-crossover-nifty-pe` carries an entry-candle structural stop on `NIFTY26JULFUT`, which the
shadow book shows firing as an **upper** level for a PE position.

**Result: 2 WOULD-WIN / 13 WOULD-LOSE, −1,020.10 premium points.**

`scalp-connect-the-dots-nifty-pe` — square-off only (23900PE → 176.10, 23850PE → 146.70 at 15:12):

| bar | leg | entry | exit | pts | outcome |
|---|---|---|---|---|---|
| 09:57 | 23900PE | 259.90 | 176.10 | −83.80 | L *(shadow-confirmed, `id 224`)* |
| 10:00 | 23900PE | 276.15 | 176.10 | −100.05 | L *(shadow-confirmed, `id 225`, same leg/entry)* |
| 10:03 | 23900PE | 286.95 | 176.10 | −110.85 | L |
| 10:06 | 23850PE | 256.60 | 146.70 | −109.90 | L |
| 10:12 | 23850PE | 262.10 | 146.70 | −115.40 | L |
| 10:21 | 23900PE | 296.90 | 176.10 | −120.80 | L |
| 10:24 | 23900PE | 289.70 | 176.10 | −113.60 | L |
| 10:33 | 23900PE | 284.00 | 176.10 | −107.90 | L |
| 11:00 | 23900PE | 289.30 | 176.10 | −113.20 | L |
| | | | | **−975.50** | **0W / 9L** |

`scalp-golden-crossover-nifty-pe` — structural stop on the signal future:

| bar | leg | entry | stop level | exit | pts | outcome |
|---|---|---|---|---|---|---|
| 10:21 | 23900PE | 296.90 | 23,667.70 | 273.15 @10:40 | −23.75 | L *(shadow-confirmed, `id 226`)* |
| 10:24 | 23900PE | 289.70 | 23,658.70 | ~290.00 @10:28 | **+0.30** | W *(modelled)* |
| 10:33 | 23900PE | 284.00 | 23,664.00 | ~284.65 @10:38 | **+0.65** | W *(modelled)* |
| 11:00 | 23900PE | 289.30 | 23,669.90 | 280.95 @11:16 | −8.35 | L *(shadow-confirmed, `id 229`)* |
| 11:03 | 23850PE | 251.30 | 23,660.00 | ~248.45 @11:08 | −2.85 | L *(modelled)* |
| 11:12 | 23900PE | 284.00 | 23,669.70 | ~273.40 @11:18 | −10.60 | L *(modelled)* |
| | | | | | **−44.60** | **2W / 4L** |

**Read the two "wins" honestly: +0.30 and +0.65 points are same-bar structural stops, not trades.**
Rounded to the tick they are scratches; the label is an artifact of a stop that triggers within one
snapshot of entry. On any economic reading this set is **0 winners in 15**.

**Honest reading.** Today the fixed 125,000 floor was strongly **protective**: it vetoed 13 losers and
2 scratches for −1,020 points on a day the index rose 0.55% and every one of these rows was a long
PUT. That is the **third protective session in four** (07-21 protective, 07-22 expensive, 07-23
protective, 07-24 protective). Combined with the shadow league — both loosened books lost again — the
single-knob evidence for T1 now stands at **3-for / 5-against**. It still does not decide T16: the
argument for restoring the tag is that an armed knob was silently disarmed by a seeder republish.

## 6 New data points / anomalies

### 6.1 ⚠⚠ NEW DEFECT — the engine's 3m signal series disagrees with its own 1m series, in exact lot multiples (T23)

`PartialBucketCanary` (`services/strategy-signal-service/.../signals/PartialBucketCanary.java`) WARNed
**37 times** during today's session, **every one on `NFO:NIFTY26JULFUT@3m`** — the series every live
scalper signals off — and on no other series. (It also fired **48** times on 07-23; that session's
findings file did not report it, because it only enumerated ERROR-level lines. This is therefore *not*
new behaviour, only newly measured.)

**The magnitudes are the finding.** Every shortfall is an exact multiple of **65**, the NIFTY lot size:

| |shortfall| | 65 | 130 | 195 | 260 | 325 | 390 | 455 | 520 | 845 | 1,560 | 1,820 | **6,110** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| lots | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 13 | 24 | 28 | **94** |
| occurrences (±) | 2 | 7 | 8 | 2 | 2 | 2 | 4 | 3 | 2 | 2 | 2 | 1 |

and they arrive in near-perfect **± pairs on consecutive buckets** (`04:15Z −325` then `04:18Z +325`;
`04:30Z −195` then `04:33Z +195`; …). Only the opening bucket's **+6,110** is unpaired.

**Why this is not the bug the canary was written for.** The canary detects a *frozen first-minute
partial*, whose signature is a persistent ~⅔ shortfall in one direction. What is actually happening is
symmetric and tiny. Both sides of the comparison are **in-memory** (`LiveSeriesStore` 3m vs
`LiveSeriesStore` 1m — the canary does no DB read and no REST call), and the 3m side agrees with the
database: the 09:15 bucket's engine 3m volume **131,300** equals `Σ` of the store's own aligned 1m
bars 09:15+09:16+09:17 (72,995 + 28,340 + 29,965) exactly, while the canary's in-memory 1m sum read
**137,410**. So the divergence is a **boundary-tick attribution race between the two in-memory
aggregations** — a whole trade (1–94 lots) landing in different minutes on the two paths.

**Why it matters.** The live `volume-floor` operand, `volume-pump`, `rising-volume` and the `volume`
dot all read the 3m series. On 35 of the 37 events the error is ≤ 8 lots (≤ 0.1% of a median 3m bar,
immaterial). On the **09:15 opening bar it was 94 lots = 6,110 = 4.7% of that bar** — and the 09:15
bar is the only bar all session that cleared the fixed 125,000 floor (§2.1), so a 4.7% aggregation
error sat directly on the one threshold decision that mattered.

**Two separable actions, both PROPOSED as T23** — (a) find and fix the boundary attribution (the real
fix), and (b) meanwhile set `artha.signals.partial-bucket-canary.volume-tolerance` above 0, which the
canary's own javadoc anticipates ("can loosen it live if a benign tick-agg-vs-persisted skew proves
noisy"). **Do (b) only after (a) is scoped** — muting the canary before the skew is understood would
also mute the frozen-partial regression it exists to catch. Promoted to README **§3.17**.

### 6.2 The empty 15:00–15:29 rejection tail is a gate-stage effect, not a stall

Rejections stop at bar **14:57** and the 15:00/15:15 buckets are empty, where 07-22 and 07-23 both ran
to 15:18. The population thins from 14:21 onward:

| bar | 14:00–14:18 | 14:21 | 14:24–14:27 | 14:30–14:57 | 15:00+ |
|---|---|---|---|---|---|
| rows/bar | 16 | 6 | 4 | 4 (`time-window` only) | **0** |

**Ruled out as a stall:** `ay_signal_eval_duration_seconds_count` advanced by exactly **375** today —
one eval cycle per minute-bar from 09:15 to 15:29 — `ay_signal_eval_failures_total = 0`, and
`subscriber_health_events` is empty. Per §3.11's honesty limit, `recordRejection` sits downstream of
the chart-gate early return, so bars dying at the chart gate write no row; `chart-gate-failed`
advanced **2,026** today, its largest share yet. The honest reading is that after ~14:21 essentially
every strategy died at the chart gate, and the last four slugs still reaching the confluence stage
were `time-window`-blocked until their windows closed. **Flagged, not diagnosed** — the same residual
07-21 carried.

### 6.3 ✅ Fourth consecutive clean interior, and the quietest error channel on record

15-minute buckets 09:15 → 14:45, **no empty bucket**:

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 8 | 10 | 80 | 80 | 88 | 80 | 40 | 90 | 74 | 44 | 82 | 80 |
| bucket | 12:15 | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 | 14:45 | |
| n | 80 | 80 | 80 | 70 | 80 | 34 | 28 | 80 | 46 | 16 | 16 | |

`strategy.subscriber_health_events` **empty**, `ay_signal_eval_failures_total = 0`, **0** kite-rest
circuit-open lines (07-23: 93), **0** misaligned candles, and the only market-data ERRORs are the 14
`FINNIFTY26SEPFUT` thin-tape lines. The 4 strategy-signal boot-time JDBC-race ERRORs seen on 07-23 did
not recur (no boot today).

### 6.4 ✅ T10 improved for the first time — 19 → 17 OPEN paper positions

`strategy.paper_positions`: **17 OPEN** (was 19 on 07-22 and 07-23), **11 CLOSED** (was 9). The 07-23
20:00/20:05 swing batch closed two on `TRAILING_STOP`:

| id | book | symbol | opened | closed | realized |
|---|---|---|---|---|---|
| 31 | minervini | CARYSIL | 07-15 20:00 | 07-23 20:00 | **−₹744** |
| 21 | manas-arora | ATHERENERG | 07-08 20:04 | 07-23 20:05 | **+₹429** |

`PaperStaleTickAlerter` still WARNs continuously (the equities are not on the live tick
subscription), so the **mechanism is unchanged** — these exited on the EOD batch's trailing stop, not
on a live intraday bracket. But the population has now drained two sessions running with zero new
opens. T10 de-escalates further: **chronic, draining, no longer accumulating.**

### 6.5 `trending_cross` collapsed to 9.5% — regime, filed as a watch only

The dot ran 45.0% on 07-23 and **9.5%** today. Per the standing lesson from `basis`, `vix` and
`breadth`, a single low session on a directional tape is regime until a session of the opposite
character disagrees. No candidate filed; re-read next session.

### 6.6 Method addendum → README §3.17

Promote to the standard pass: **count `PartialBucketCanary` WARNs per session and bucket their
magnitudes.** It is a free, already-deployed, in-memory probe of the exact series the volume rails
read, and its magnitude distribution (lot multiples, ± pairing) distinguishes a benign boundary race
from the frozen-partial regression it was built for. §6.1 is the evidence.

## 7 Tuning candidates

Carried forward from 07-23 plus this session's new row. **Nothing here is applied** — every row is a
PROPOSAL.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T23** | 3m-vs-1m volume attribution on the live signal series (+ `artha.signals.partial-bucket-canary.volume-tolerance`) | in-memory 3m bar ≠ Σ(3×1m) 37×/session on `NIFTY26JULFUT`; tolerance 0 | (a) fix the boundary-tick attribution race; (b) only then set a non-zero tolerance so the canary stops crying on the benign residue | §6.1: every shortfall an exact NIFTY-lot multiple (65…6,110), near-perfect ± pairs on consecutive buckets, both sides in-memory, 3m side matches the DB rollup exactly; **the 09:15 error was 4.7% of the bar and 09:15 was the only bar all day clearing the 125,000 floor** | **STRUCTURAL (defect)** | **PROPOSED — NEW 07-24, HIGHEST NEW PRIORITY** |
| **T21** | premium exit rules on the 30 bracket-less scalpers | no `premium_pct` block → shadow `take_profit`/`stop_loss` NULL; exit only via structural stop (where set) or 15:12 square-off | owner decision: confirm intentional (indicator-exit-only design) or add a premium band | §5: on the 09:48 PE leg the 5 bracket-less positions rode to −36.0% while the one with a structural stop lost −7.3%; on the 11:24 CE leg the one with a take-profit booked +37.5% vs +25.1% at square-off. **The same-leg contrast now reproduces in both directions, on a second session** | **STRUCTURAL (config + method)** | **PROPOSED — 2nd session, HIGHEST PRIORITY** |
| **T16** | `relative-volume-floor` tag on the 18 PE scalpers (+ the 10 sensex CE) | **absent** since the 07-20 21:28 republish | restore the tag and re-publish; add a guard so a seeder-draft publish cannot drop an armed tag | §2.1 registry unchanged 4th session; 998 blocks at a flat 125,000 while armed CE slugs ran 29,396–53,284 the same day; **125,000 clears 1 of 125 3m bars, and that one is the 09:15 opener**. §5.1: the rows it alone vetoed were **0 economic winners in 15, −1,020.1 pts** — **3rd protective session in 4** | **STRUCTURAL (regression)** | **PROPOSED — 4th session. Argue as correctness, never as PnL** |
| **T14** | rejection-row margin invariant | proposed as global `blocking_margin < 0` | make it **sign-aware per rail direction**; separately, record the **operand that actually failed** on `confluence-composite` optional-gate blocks | §2.3: all 40 `vwap-distance` positive margins are semantically CORRECT (ceiling rail). §2.4: **3 rows** today (1 on 07-23) record a composite that passes its own threshold as blocked by the composite rail | **STRUCTURAL (diagnostic)** | **PROPOSED — 2nd confirmation of the composite half** |
| **T22** | `oi_spurt` dot threshold | current spurt floor (`artha.scalper.oi.*`, recalibrated #675/#676) | lower the floor — **next step is the §3.8 ground-truth distribution of `spurtOiPct`**, not a guessed number | 0/1,100 today after 0/1,120 (07-23), 0.2% (07-22), 3.0% (07-21) — **2nd fully-dead session**, input data present on every context-bearing row. The "collect one more session" condition is met | **STRUCTURAL (confirmed)** | **PROPOSED — escalated 07-24; needs the operand distribution** |
| T19 | gap-backfill bucket alignment | `from`/`to` passed unfloored → bars stored at `HH:MM:38` | floor the backfill window (or the bucket at write) to the minute; clean up the historical misaligned rows | **0 misaligned rows for a 2nd consecutive session** (no outage ⇒ no backfill) — two clean negative controls now | **STRUCTURAL (defect)** | **PROPOSED — carried** |
| T12 | OI quadrant / spurt reads | — | fix the failing `/options/spurt` read; separately fix the futures-OI 1-minute capture cadence | quadrants healthy a **4th** session while cadence **improved to 211**/375 (after 198, 187, 192, 208) — cadence mechanism stays refuted, endpoint still suspect | **STRUCTURAL (defect)** | **PROPOSED — carried** |
| T6 | `vwap` dot weight | 2.5 | narrow the support condition or cut the weight | **1,100/1,100 = 100% for the 6th consecutive session; 5,225 rows** across six distinct tape characters with zero discrimination | **STRUCTURAL** (6 sessions) | **PROPOSED — strongest row in the ledger** |
| T13 | `dot-health` probe registry | 6 probes | add `futures_oi` / `underlying_oi` NEUTRAL-share probes | 07-20: canary healthy while the two heaviest OI dots were dead all session | **STRUCTURAL** | **PROPOSED** (carried) |
| T17 | `dot-health` sampling window | newest 40 rejections | sample the newest N **context-bearing** rows **from the current session only** | §4: 4th consecutive false reading — at 15:58 **all six** dots called dead, `breadth` included, while `breadth` supported 426/1,100 | **STRUCTURAL (diagnostic)** | **PROPOSED — 4th confirmation** |
| T1 | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | both loosened books lost again on identical entries (−₹7,207 each, 1-for-7); ledger now **3-for / 5-against** | **REGIME** | **PROPOSED — still do NOT apply; decide with T16, not before** |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source ivRank or drop from Σw | dead every session since 07-02 | **STRUCTURAL** | **PROPOSED** (carried) |
| T3 | `iv_pair` gap threshold | 0.02 | ~0.005 | 0/1,100 today; 0% on every session logged, including post-recalibration (7×) | **STRUCTURAL** | **PROPOSED** (carried) |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | 0/166 again today (6th session) | **REGIME** (6 sessions) | **PROPOSED**, collect more |
| T7 | composite threshold | 0.600 | no change | **the challenger evidence has turned:** `composite-055` had its first profitable session (+₹2,956) and is now the **least-bad** book all-time (−₹1,542 over 11 closes) vs champion −₹44,161. Still only 11 closes | — | **REJECTED — but the "reaffirmed by the challenger book" justification no longer holds; re-read next session** |
| T8 | shadow entry latency | p50 ~76 s | stamp entry at bar close | 10 sessions in the 73–87 s band | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T9 | strategy-coverage watchdog | none | alert on emitting/published ratio drop, split by counter | 36/38 today; the 2 silent slugs are the `hero-zero` pair on a non-expiry Friday — a ratio alarm would have to tolerate this | **STRUCTURAL** | **PROPOSED** (needs redesign) |
| T10 | **17** stale OPEN paper positions + starved brackets | open since 07-07, **draining** | square off / age out, or subscribe the swing holdings | §6.4: 19 → 17, two closed on the 07-23 batch's trailing stop, 0 new opens for a 2nd session | ops | **OWNER — chronic, draining** |
| T15 | engine boot-line durability | log only | persist `loaded/unresolved/dropped` to a table | readable today only because nothing restarted — and today's line is *yesterday's* boot | **STRUCTURAL (data-model)** | **PROPOSED** (carried) |
| T20 | far-month contracts in the bar-divergence canary | all subscribed instruments | exclude non-front contracts **only where thin-tape is the cause** | `FINNIFTY26SEPFUT` thin-tape 5th session (35 bars, 14 of 14 ERRORs) while **`FINNIFTY26AUGFUT` recovered to 375/375** — the 07-23 hole did not recur, so the respecified form holds | ops (noise) | **PROPOSED — carried** |
| T18 | `breadth` dot threshold | `advances/declines > 32` (of 50) | **no change** | 38.7% today after 22.1% / 96.6% / 0.2% — regime, as concluded | **REGIME (resolved)** | **CLOSED — no action** |
| T11 | SENSEX volume-floor | fixed 125,000 | arm the relative floor for the sensex family | the 10 sensex CE slugs still carry no tag (published 06-29/06-30) | **STRUCTURAL** | **MERGED INTO T16** |
| T4 | `basis` dot | w 1.0 | — | alive (61.3%) | — | **CLOSED — no action** |

## 8 Honesty caveats

- **§5.1's −1,020.10 points is square-off-and-structural-stop only.** The 15 rows are 2 slugs on 2
  legs across 75 minutes of one directional up-move — **not 15 independent bets**. Four of the six
  `golden-crossover` exits are **modelled**, not observed: the rule used is "first 1m close of
  `NIFTY26JULFUT` at or above the row's structural-stop level, ≥2 min after `bar_time`", calibrated
  against the two rows the shadow book *did* take. That rule reproduces `id 229` (11:16) but would
  have fired `id 226` at 10:27 where the book closed it at 10:40 — **so the modelled exits carry an
  unmeasured timing bias**. Snapshot granularity is ~2 min; no slippage or fees; strike-pick
  approximated.
- **The two "WOULD-WIN" rows are +0.30 and +0.65 points.** Calling them wins is arithmetically true
  and economically meaningless. The set is 0 economic winners in 15.
- **The champion book's −₹14,195.21 is 11 distinct entry events, not 22 positions** (3W/8L), and two
  clusters (09:48 PE, 11:24 CE) carry essentially the whole spread. Headline should not be read at
  face value.
- **§6.1 identifies a mechanism class, not a root cause.** The lot-multiple and ±-pairing evidence is
  measured; "boundary-tick attribution race" is the inference that fits it. The write path was **not**
  read — `LiveSeriesStore`'s 1m and 3m ingest were not inspected, and the canary's own summation was
  verified only against the DB, not stepped through. T23 needs a code read before a fix is designed.
- **The 07-23 file did not report `PartialBucketCanary` at all**, so §6.1's "37 today, 48 yesterday"
  is a newly-measured quantity on pre-existing behaviour — not a new regression. Sessions before
  07-23 are unmeasured (their logs are gone).
- **The max composite 0.7447 is a market reading today, not the arithmetic ceiling** (0.8511) — the
  opposite of 07-23, where 48 rows sat on the cap. Cross-session "max composite" comparisons are only
  meaningful with the dead-dot roster stated.
- **Today's protective reading of the fixed floor is the third in four sessions, on a day when every
  candidate row was a long PUT into a +0.55% tape.** That is a regime reading. T16 is a correctness
  argument.
- **`composite-055` becoming the least-bad book is 11 closes.** It is reported because it contradicts
  a standing justification, not because it is decisive.
- Shadow exits replicate structural stop + square-off (+ premium brackets **only for the 8 slugs that
  configure them**) — no indicator-driven exits. Every entry is stamped ~76 s after `bar_time`.
- Costs are the 1-lot engine fill model (statutory + ₹20/lot). Not cost-adjusted twice.
- This run was **read-only**: SELECTs, `docker logs`, in-container actuator/health GETs, and a source
  read. No restart, deploy, write or config change. No strategy knob was altered.
