# Session findings — 2026-07-22 (data date)

Analysis date: 2026-07-22 (scheduled post-market agent, ran 15:54–16:40 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,042** (bar_time 09:18–15:18 IST), signals fired **0**, paper
positions opened **0**, shadow positions **35** (27 champion + 8 challenger, all closed).
Session character: **clean trend-DOWN day.** `NIFTY26JULFUT` opened 24,126.30 and closed
23,982.00 (**−144.30 pts, −0.60%**), range 23,945–24,137 (192 pts).
`SENSEX26JULFUT` 77,533.05 → 76,794.05 (−739.0). 3m volume median 13,520 — as thin as 07-20/07-21.
The tape was PE-side again: **all 568 composite-passing rows were PE**.

This file folds in the three earlier read-only runs of the same date:
`2026-07-22-open-gate.md`, `2026-07-22-midday-gate.md`, `2026-07-22-live-watch-findings.md`.

---

## 0 Read this first — the session's headline

**Three things, in order of weight.**

1. **The `relative-volume-floor` regression (T16) is STILL LIVE and today it cost money.** All 18
   PE scalpers still run the fixed **125,000** floor (`min = max = 125,000` on every PE slug,
   §2.1); the registry is unchanged since the 2026-07-20 21:28 republish. On the first genuinely
   trending session since the regression, **816 of 1,042 rejections (78.3%)** died on that floor,
   and **566 of the 568 composite-passing rows** died on it. The rows it **alone** vetoed resolve
   **56 WOULD-WIN / 30 WOULD-LOSE, +2,547.6 premium points** (§5.1) — the exact inverse of
   yesterday's 6-of-6 losers. Yesterday the regression was accidentally protective; today it was
   accidentally expensive. Both readings are single-session and regime-driven, which is precisely
   why the fix should be argued as *restoring an armed knob*, not as a tuning bet.

2. **A ~14-minute live network outage, 12:51–13:05 IST, self-recovered.** Host-level connectivity
   to Kite dropped (`Failed to connect to 'ws.kite.trade:443'` + I/O errors on `api.kite.trade`
   *and* `liveindexsa.niftyindices.com`), the kite-rest circuit opened, options-chain snapshots
   served cached, the feed watchdog restarted the feed **twice**, and strategy-signal logged a
   **743 s receive-stall** with a Redis re-subscribe. Everything recovered by 13:06:20 IST without
   intervention (§6.1). The canaries did their job for the first time on this class.

3. **⚠ NEW STRUCTURAL DEFECT — the gap backfill writes 1m candles on an UNALIGNED bucket.** The
   post-outage backfill wrote **308 rows across 22 instruments** at `12:51:38`, `12:52:38`, …
   — the tick-gap's second offset, not the minute boundary. They are distinct PK rows sitting
   *between* the real bars, so any `time_bucket` rollup **double-counts** them. The live 3m volume
   operand is exactly such a rollup. Session-wide this inflated the 3m median 13,520 → 14,885
   (+10%). It is not new to today: 403 such rows on 07-20 and 887 on 07-15 (§6.2). Filed as **T19**.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-17 | 2026-07-20 | 2026-07-21 | **2026-07-22** |
|---|---|---|---|---|
| rejections | 523 | 1,013 | 1,372 | **1,042** |
| distinct strategies emitting | 17 ⚠ | 49 | 22 | **36** |
| published + enabled | 63 | 44 | 44 | **44** (38 scalper + 6 swing) |
| scalpers loaded by the engine | — | 63 | 38 | **38** |
| signals fired | 3 | 1 | 0 | **0** |
| paper positions opened | 0 | 0 | 0 | **0** |
| bar-time coverage | 09:24–15:18 + holes | 10:19–15:19 + holes | 09:21–14:57, no hole | **09:18–15:18, no hole** ✅ |
| composite ≥ threshold rows | 210 | 230 | 218 | **568** |

**Strategy coverage 36 of 38 is the best on record** (07-22 midday already read 34). The only two
silent scalpers all session were `scalp-golden-crossover-nifty` and
`scalp-golden-crossover-sensex-niftyoi` — both CE variants on a down day, i.e. the §6.5-class tape
explanation, not a load failure.

**Engine load (§3.10) — read the same day, logs intact (`RestartCount=0`, StartedAt 08:58:14 IST):**

```
03:28:23Z (08:58 IST)  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
03:29:43Z (08:59 IST)  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

The known F10 cold-start shape again; the #874 retry recovered 80 s later, well before the open.
Health signal `unresolved == 0` holds.

**Eval counters (actuator :8082, read 15:57 IST BEFORE any deploy):**

| outcome | 12:38 (midday gate) | 13:49 | **15:57 (final)** |
|---|---|---|---|
| `chart-gate-failed` | 1,188 | 1,574 | **2,028** |
| `composite-below-threshold` | 120 | 170 | **372** |
| `confluence-blocked` | 764 | 968 | **1,042** |
| `fired` | 0 | 0 | **0** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 | 0 | **0** |
| **Σ** | 2,072 | 2,712 | **3,442** |
| `ay_signal_eval_failures_total` | 0 | 0 | **0** |

`confluence-blocked` = 1,042 = the row count exactly; the two views agree.

**First-blocking-rail histogram** (1,042 rows):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **816 (78.3%)** | 24,482.7 | 122,422.0 | −97,939.3 |
| time-window | 160 (15.4%) | — | — | — |
| time-of-day-preference | 32 | — | — | — |
| option-side-constraint | 18 | — | — | — |
| rsi-band | 14 | 47.56 | — | — |
| chain-unavailable | 2 | — | — | — |

Six distinct first-blocking rails. **T14 check: zero rows with a non-negative `blocking_margin`**
(second consecutive clean session; the 07-20 defect stays intermittent).

**All-failed-rails expansion (§3.3)** — top 10:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | 816 | 24,482.7 | 122,422.0 |
| rsi-band | FAIL_CLOSED | 400 | 40.46 | — |
| confluence-composite | FAIL_CLOSED | 260 | 0.506 | 0.600 |
| time-window | FAIL_CLOSED | 160 | — | — |
| divergence-vol-gate / trend-change | FAIL_CLOSED | 124 each | 23,021.5 / — | — |
| pct-price-move | FAIL_OPEN | 96 | −0.551 | 1.000 |
| two-candle | FAIL_CLOSED | 96 | — | — |
| volume-pump | FAIL_OPEN | 96 | 26,503.8 | — |
| oi-cross-required | FAIL_CLOSED | 84 | 83.98 | — |
| oi-divergence-magnitude | FAIL_CLOSED | 84 | −17.65 | 20.0 |

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 ⚠⚠ T16 UNRESOLVED — the PE book is still on the fixed 125,000 floor (2nd session)

**Registry state is unchanged** (§3.14 standing query, run today):

| carries `relative-volume-floor` | published on | count | family |
|---|---|---|---|
| **yes** | 2026-07-06 | 10 | all nifty CE |
| no | 2026-06-29 / 06-30 | 10 | all sensex CE (never armed — the old T11) |
| **no** | **2026-07-20 21:28:5x** | **18** | **all `-pe`, nifty AND sensex** |

**Row proof, same session, both shapes side by side:**

| slug | n | min thr | max thr | avg operand |
|---|---|---|---|---|
| `scalp-connect-the-dots-nifty` (CE, armed) | 2 | **15,502.5** | **17,940.0** | 10,595 |
| `scalp-hero-zero-nifty` (CE, armed) | 5 | **7,068.8** | **7,653.8** | 5,564 |
| `scalp-connect-the-dots-nifty-pe` | 60 | 125,000.0 | 125,000.0 | 23,436 |
| `scalp-golden-crossover-nifty-pe` | 50 | 125,000.0 | 125,000.0 | 21,135 |
| `scalp-connect-the-dots-sensex-niftyoi` (CE, never armed) | 2 | 125,000.0 | 125,000.0 | 10,595 |
| … (all 18 `-pe` + all 10 sensex CE slugs at a flat 125,000) | | | | |

**The 125,000 floor is still past "near-never" on this session's own distribution.** 3m rollup of
`NIFTY26JULFUT` 09:15–15:30 IST, computed on **minute-aligned bars only** (see §6.2 for why that
qualifier now matters):

| bars | min | p50 | p90 | p95 | p99 | max | bars ≥ 125,000 |
|---|---|---|---|---|---|---|---|
| 125 | 1,690 | 13,520 | 48,490 | 78,260 | 147,940 | 198,445 | **2** |

Two passable bars in 125 (07-21: one). The armed CE slugs' floor ran 7,069–17,940 on the same
tape — a factor of ~7–17 lower than the un-armed one.

**Verdict: STRUCTURAL regression, unchanged from 07-21, and now with money attached** — see §5.1.
T16 stays the highest-priority row and is still owner-gated (re-publishing changes live behaviour).

### 2.2 What the floor blocked today

566 of the 568 composite-passing rows were first-blocked by `volume-floor`; the other 2 by
`rsi-band`. Of the composite-passing population, **86 rows had `volume-floor` as their ONLY failed
check** (§3.5) — up from 6 on 07-21. §5.1 resolves every one of them.

### 2.3 Rails with no evidence of miscalibration

`rsi-band` (avg 40.46 — correctly low on a down tape), `pct-price-move` (−0.551 vs 1.000),
`oi-divergence-magnitude` (−17.65 vs 20.0), `volume-pump` / `max-oi-sr-gate` (FAIL_OPEN) all read
plausibly. No order-of-magnitude gaps other than §2.1.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (828 scored rows):

| bucket | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|
| n | 4 | 40 | 122 | 270 | 322 | 58 | 12 |
| CE | 0 | 0 | 16 | 12 | 0 | 0 | 0 |
| PE | 4 | 40 | 106 | 258 | 322 | 58 | 12 |

Max composite **0.8511 — a PE row**, the highest ever recorded in this folder (07-21: 0.7447;
07-20: 0.452 on the PE side). Threshold 0.600; **568 rows cleared it, all 568 PE.**

**This closes the rollup's standing PE question.** The open item since 07-02 was "can a PE
composite pass, and how high can it go on a *clean* trend-down day". Answer: **0.8511, with 568
rows over the line (54.5% of all rejections)** — a PE composite is not merely capable of passing,
on the right tape it passes in bulk. What stopped every one of them was the §2.1 volume rail.

**Dot support rates** (828 scored rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_rank` | 0.8 | 0/828 | 0.0 | dead-data, **withheld from Σw** (#676) |
| `iv_abs_band` | 0.8 | 0/124 | 0.0 | dead (4th session) |
| `volume` | 1.0 | 0/828 | 0.0 | mirrors §2.1 — mechanically dead behind the 125k floor |
| `iv_pair` | 0.8 | 0/828 | 0.0 | dead — carried since 07-02 (5th confirmation) |
| `oi_spurt` | 1.0 | 2/828 | 0.2 | barely alive (07-21: 3.0%) |
| `trending_cross` | 1.0 | 112/828 | 13.5 | |
| `premium_skew` | 1.0 | 8/34 | 23.5 | (only on the 34 straddle-path rows) |
| `sentiment_slope` | 1.0 | 412/828 | 49.8 | |
| `underlying_oi` | 1.0 | 414/828 | 50.0 | ✅ alive |
| `futures_oi` | 1.5 | 454/828 | 54.8 | ✅ alive |
| `rsi` | 1.0 | 494/828 | 59.7 | |
| `iv_slope` | 0.8 | 76/124 | 61.3 | alive |
| `sentiment` | 1.0 | 640/828 | 77.3 | |
| `drastic_oi` | 1.0 | 648/828 | 78.3 | |
| `psar` | 1.0 | 678/828 | 81.9 | |
| `basis` | 1.0 | 694/828 | 83.8 | alive (T4 stays closed) |
| `vwma` | 1.0 | 704/828 | 85.0 | |
| `supertrend` | 1.0 | 794/828 | 95.9 | ⚠ 2nd near-free session |
| `breadth` | 1.0 | 800/828 | **96.6** | ✅ **T18 answered — see below** |
| `vix` | 1.0 | 800/828 | 96.6 | (07-21: 0.2% — pure regime) |
| `vwap` | 2.5 | 828/828 | **100.0** | ⚠ **4th consecutive session at 100%** |

**T18 is answered and should be CLOSED as regime, not a threshold problem.** On 07-21 `breadth`
supported 0.2% and the file argued the `> 32`-of-50 threshold sat one constituent outside the
operand's realised range (declines peaked at 31). Today, on a real down day, **declines ran 36–45
and advances 5–14**, and the dot supported **96.6%**. The threshold is reachable; 07-21 was a
mild-tape miss. Re-tuning it to 30 would have bought nothing today and would loosen the dot on
exactly the days it correctly abstains.

### 3.1 The dead-weight cap

| dots on row | rows | Σw | dead w (volume + iv_pair + iv_abs_band) | cap (iv_rank withheld) |
|---|---|---|---|---|
| 18 | 670 | 19.60 | 1.80 | **0.9043** |
| 19 | 34 | 20.60 | 1.80 | 0.9091 |
| 20 | 124 | 21.20 | 2.60 | 0.8725 |

Observed max **0.8511**, i.e. **94.1% of the cap** — the closest to the ceiling any session has run
(07-21: 82.4% of cap). The composite was genuinely discriminating: the 0.600 threshold required
66.4% of live weight and 568 rows made it.

## 4 Data health (§3.7)

| field | 2026-07-20 | 2026-07-21 | **2026-07-22** | class |
|---|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | NEUTRAL 748/748 | 0 NEUTRAL | **0 NEUTRAL**, 15 combos, 214 NULL | ✅ healthy (2nd session) |
| `spurtOiPct` / `spurtPricePct` | null 1,013/1,013 | null 302/1,372 | null **214**/1,042 | ✅ healthy |
| `advances` / `declines` | 0 zero-pairs, 265 null | 0 zero-pairs, 302 null | 0 zero-pairs, **214 null** | HEALTHY |
| `ivRank` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried since 07-02) |
| `fiiLongPct` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 100%** | by design (un-armed) |
| `vix` (macro mirror) | NULL 100% | NULL 100% | **NULL 100%** | known mirror gap (the `vix` **dot** is fine — 96.6%) |

**The 214 nulls are fully explained, same mechanism as 07-21's 302.** 214 = 160 `time-window` + 32
`time-of-day-preference` + 18 `option-side-constraint` + 2 `chain-unavailable` + 2 of the 14
`rsi-band` rows — the rows blocked at a rail that fires *before* macro/OI context is gathered.
Every context-bearing row carries live data.

**Capture (minute-aligned bars only — see §6.2):**

| series | bars / snaps | window |
|---|---|---|
| `NIFTY26JULFUT` 1m | **375** aligned (+14 misaligned BACKFILL) | 09:15–15:29 |
| `SENSEX26JULFUT` 1m (BFO) | **375** aligned | 09:15–15:29 |
| `NIFTY 50` 1m | **375** aligned | 09:15–15:29 |
| `futures_oi_snapshots` (`NIFTY26JULFUT`, `SENSEX26JULFUT`) | **187 rows / 187 distinct minutes** each | — |

⚠ **`futures_oi_snapshots` cadence degraded a third session running** — 187 of ~375 minutes (50%),
after 192 (07-21) and 208 (07-20), against 365 on 07-17. **And the OI quadrants were live anyway**,
for the second consecutive session. That re-confirms 07-21 §6.2: the cadence regression is real but
is **not** the mechanism behind the 07-20 NEUTRAL outage. T12 keeps the `/options/spurt` read as the
suspect and the cadence as a separate, lower-severity item — but three sessions of monotone decline
is now a trend, not noise.

**`dot-health` canary — T17 confirmed again, in a new guise.** The day's one strategy-signal
ERROR of this class fired at **08:56 IST, before the open**:

```
dot canary: required dot 'breadth' input DEAD — input dead across 40 rejections
```

At 08:56 the "newest 40 rejections" are **yesterday's** end-of-day `time-window` rows, which carry
no macro context at all — the same sampling defect T17 describes, now shown to bite pre-open as
well as post-close. The 16:00 read (`session=false`, `rowsInspected=40`) happened to catch
context-bearing rows and reported `breadth` alive, `iv_rank`/`dow`/`fii` dead — the correct answer.
**T17 should also require the probe to ignore rows from a prior session.**

## 5 Shadow-book outcomes

**Exit-fidelity caveat (standing):** indicator-driven exits (trend-flip / signal-exit) are NOT
replicated — premium brackets, structural stop and 15:12 square-off only. Rejections blocked before
leg resolution never shadow.

**Champion book — 27 closed, 18W/9L, +1,712.75 pts, +₹42,240.91 net.** All 27 first-blocked by
`volume-floor`. This is the largest single-session champion figure recorded, and it flips the
book's all-time net from **−₹41,260.30 to +₹980.61**.

⚠ **CORRELATION CAVEAT — 27 positions are 14 distinct entry events.**

| event (bar, leg, entry) | positions | outcome |
|---|---|---|
| 09:24 NIFTY26JUL24350PE @327.70 | 1 | **W** +21.1% (+₹4,395) |
| 09:24 SENSEX2672377600PE @601.90 | 1 | **W** +54.3% (+₹6,453) |
| 09:45 NIFTY26JUL24300PE @329.40 | 6 | **W** (5 × +8.1% square-off, 1 structural stop −1.1%) |
| 09:45 SENSEX2672377400PE @578.40 | 6 | **W** (4 × +27.9%, 1 TAKE_PROFIT +35.2%, 1 stop −1.6%) |
| 10:00 NIFTY26JUL24300PE @347.55 | 1 | W +2.4% |
| 10:00 SENSEX2672377400PE @616.20 | 1 | W +20.1% |
| 10:06 NIFTY26JUL24300PE @325.00 | 1 | **L** −1.2% |
| 10:06 SENSEX2672377500PE @633.25 | 1 | **L** −1.3% |
| 10:15 NIFTY26JUL24300PE @331.60 | 2 | W +7.4% |
| 10:15 SENSEX2672377400PE @578.25 | 2 | W (+28.0% / TP +35.2%) |
| 12:30 SENSEX2672377200PE @620.00 | 2 | **L** (−6.9% / stop −10.1%) |
| 13:06 SENSEX2672377200PE @593.75 | 1 | **L** −6.1% |
| 14:30 NIFTY26JUL24050PE @212.45 | 1 | **L** −12.9% |
| 14:30 SENSEX2672376100PE @76.15 | 1 | **L** −23.3% |

**Counted once per event: 8W / 6L, ≈ +₹18,080** (mean net per event). Still clearly positive — the
first genuinely profitable would-have-fired session since 07-17 — but the headline ₹42k is inflated
~2.3× by the correlation multiplier. Note the shape: **every morning event won, every event after
10:15 lost.** The floor was blocking a real morning trend and then, correctly, a chop.

**Variant league — this session:**

| variant | closed | wins | pts | net ₹ |
|---|---|---|---|---|
| champion | 27 | 18 | +1,712.75 | **+42,240.91** |
| vol-off | 4 | **4** | +339.75 | **+8,332.03** |
| vol-12k5 | 4 | **4** | +339.75 | **+8,332.03** |
| composite-055 | 0 | — | — | — (took nothing again) |

Both loosened books went **4-for-4** on identical entries (09:54 and 10:15 PE legs, the two events
champion dedup held it out of) — their first winning session since 07-17.

**Cumulative league (all sessions, judge on NET ₹):**

| variant | closed | net wins | pts | **net ₹** |
|---|---|---|---|---|
| champion | 130 | 56 | +1,132.15 | **+980.61** |
| vol-12k5 | 17 | 6 | +74.10 | **+3,046.36** |
| vol-off | 25 | 8 | −43.90 | **−2,894.87** |
| composite-055 | 8 | 2 | +2.50 | −478.98 |

**`vol-12k5` is now net-POSITIVE all-time** (+₹3,046) — the first challenger ever to be. `vol-off`
remains negative. That ordering (relax, don't remove) is the same shape the rollup has argued all
along, now with a positive sign on the relaxed book.

**Entry latency (F8):** p50 **72.8 s**, p95 **83.0 s** (35 positions) — inside the structural
73–87 s band. Standing caveat: every shadow fill is stamped ~73 s after `bar_time`.

### 5.1 Counterfactual — the 86 would-have-fired rows (§4.2)

The §3.5 set (composite ≥ threshold, `volume-floor` the **only** failed check) is **86 rows** from
4 slugs, all PE, spanning 09:54–13:06. They collapse to **55 distinct (bar, leg) events** over
**6 distinct legs** (`NIFTY26JUL24300PE` / `24250PE` / `24200PE`,
`SENSEX2672377400PE` / `77300PE` / `77200PE`). Four rows are directly resolved by a challenger
shadow position on the same rejection id; the rest are resolved by hand from
`options_chain_snapshots` (entry = first snapshot at/after `bar_time`, +35% premium take-profit,
otherwise 15:12 square-off).

**Result: 56 WOULD-WIN / 30 WOULD-LOSE, +2,547.61 premium points, mean +5.3%.**

| slug | rows | W | L | Σ pts |
|---|---|---|---|---|
| `scalp-connect-the-dots-sensex-niftyoi-pe` | 24 | 16 | 8 | +1,287.50 |
| `scalp-golden-crossover-sensex-niftyoi-pe` | 25 | 17 | 8 | +1,110.66 |
| `scalp-connect-the-dots-nifty-pe` | 12 | 11 | 1 | +201.70 |
| `scalp-golden-crossover-nifty-pe` | 25 | 12 | 13 | −52.25 |

**Split by time — this is the important cut:**

| segment | rows | W | L | Σ pts |
|---|---|---|---|---|
| morning (< 11:00) | 43 | **39** | 4 | **+2,616.46** |
| midday (≥ 12:00) | 43 | 17 | **26** | **−68.85** |

Three rows hit the +35% take-profit outright, all `SENSEX2672377400PE` entries between 10:03 and
10:18. The four shadow-resolved rows (09:54 and 10:15) all won (+5.0% to +28.0%), agreeing with the
manual model.

**Honest reading.** The 86 rows are 4 slugs trading 6 legs — effectively **two idea families**
(`golden-crossover` and `connect-the-dots`, mirrored NIFTY/SENSEX). They are not 86 independent
bets, and this is one down-trending session. What the number does support: on a trending morning
the fixed 125,000 floor vetoed a real move, and the relative floor (7,069–17,940 on the armed CE
slugs today) would have passed much of it. What it does **not** support: a blanket loosening — the
same rows after 12:00 are net-negative, and the manual model has **no structural stop**, which the
shadow book shows firing at −1.1% to −10.1% on several of these legs. Treat the +2,547.6 as an
upper bound.

## 6 New data points / anomalies

### 6.1 ⚠ A ~14-minute Kite connectivity outage, 12:51–13:05 IST — detected, self-recovered

Reconstructed from `docker logs` (all times IST):

| time | event |
|---|---|
| 12:51:49 | `kite ticker error: Failed to connect to 'ws.kite.trade:443': ws.kite.trade` (name-resolution shape) |
| 12:51:54 | `kite ticker disconnected` |
| 12:52:27 | first of 4 `Unexpected error occurred in scheduled task` (market-data) |
| 12:52:29→12:52:57 | `scheduled options snapshot failed for <28 index/expiry pairs>: kite-rest circuit open; serving cached data` |
| 12:52:57 | `gap fetch failed for NFO:NIFTY26JULFUT 3m — serving cached data stale: kite-rest circuit open` |
| 12:53:10 | `sector-index watch refresh failed …: I/O error on GET … liveindexsa.niftyindices.com` (a **different** host — so this was host/DNS-level, not Kite-specific) |
| 12:53:24 | `kite session probe errored: I/O error on GET https://api.kite.trade/user/profile` |
| 12:55:11 | `feed watchdog: market open but the last tick is 212s old — restarting the feed` |
| 13:05:14 | `feed watchdog: market open but the last tick is 815s old — restarting the feed` |
| 13:05:15 | `kite ticker connected; replayed 1 mode groups` + **23 × `tick gap on <instrument> — scheduling 1m backfill`** |
| 13:05:20 | strategy-signal: `subscriber watchdog: signal engine STARVED — no candle received for 743s while the feed is live` → `resubscribe` |
| 13:05:27 | `data canary RED: 25 instruments have ticks flowing but no 1m bars closing — feed-wide bar stall` |
| 13:06:20 | strategy-signal: `candle receipt recovered (20s)` |

**Everything that should have fired, fired**: the feed watchdog, the subscriber watchdog (#634/#679
class), the data canary, and the re-subscribe path — with a `strategy.subscriber_health_events` row
for each (3 rows: `receive-stall`, `resubscribe`, `recovery`). No human action; the stack was
healthy again inside 15 minutes. Chain capture visibly dipped in the 12:50 five-minute bucket
(7,192 snapshots vs a 16–20k norm) and recovered by 12:55.

**Rejection coverage tracked the outage exactly** — the 12:45 bucket holds 16 rows and 13:00 holds
38, against 80 in each of 12:00/12:15/12:30/13:15. §3.11's honesty limit applies (an empty-ish
bucket is not itself proof of a dead engine), but here the cause is directly logged.

This is the first time this folder can record the stall-detection stack working end-to-end on a
real event. **The rollup's standing question about #634/#679 is now answered affirmatively twice**
(07-20 logged rows; 07-22 logged rows *and* the mechanism that produced them).

### 6.2 ⚠⚠ NEW DEFECT — gap-backfill writes 1m candles on an unaligned bucket (T19)

The 13:05 backfill wrote its bars at the **tick-gap's second offset**, not floored to the minute:

```
 bucket (UTC)            ist                 source     volume
 2026-07-22 07:21:00+00  2026-07-22 12:51:00 TICK_AGG     1885
 2026-07-22 07:21:38+00  2026-07-22 12:51:38 BACKFILL     1560   <-- phantom
 2026-07-22 07:22:00+00  2026-07-22 12:52:00 KITE         2470
 2026-07-22 07:22:38+00  2026-07-22 12:52:38 BACKFILL     7150   <-- phantom
```

`marketdata.candles` is keyed `(exchange, tradingsymbol, interval, bucket)`, so an unaligned bucket
is a **distinct row**, not an upsert — the backfill's whole point (replacing the bars lost in the
gap) silently fails and it instead interleaves duplicates.

**Scope, measured:**

| session | misaligned 1m rows | instruments | window |
|---|---|---|---|
| 2026-07-22 | **308** | 22 | 12:51:38 – 13:04:39 |
| 2026-07-20 | 403 | — | — |
| 2026-07-15 | 887 | — | — |

(Only `source='BACKFILL'` rows are ever misaligned; `KITE` and `TICK_AGG` are always aligned.)

**Impact — it corrupts a live gate input.** 3m reads are a read-time rollup over the 1m base
(`CandleRepository.rangeRolledFromOneMinute`), so `time_bucket('3 minutes', …)` sums the phantom
bar *and* the real one for the same minute. Measured on today's signal series:

| 3m rollup over `NIFTY26JULFUT` | p50 | p90 | p95 |
|---|---|---|---|
| all rows (as the engine reads them) | 14,885 | 48,490 | 78,260 |
| minute-aligned rows only (truth) | **13,520** | 48,490 | 78,260 |

A 10% median inflation across the session, concentrated in the 12:51–13:05 window where it is far
larger. It inflates the `volume-floor` operand, `volume-pump`, `rising-volume`, and the `volume`
dot — i.e. it makes the gate *more* likely to pass on exactly the bars following an outage.

**Suspected origin:** `CandleQueryService.backfillRange` is handed the raw tick-gap instant
(`GapBackfillService.backfill(key, from, to)`, from = `2026-07-22T07:21:38.9Z`) and the fetched
bars are stored against that unfloored window. **Not verified in code beyond the call path** — the
fix is presumably a `truncatedTo(MINUTES)` on `from`/`to` (or on the bucket at write), plus a
one-off cleanup of the 1,598 historical misaligned rows. Filed as **T19**; every §3.8-class query in
this folder from now on should filter `EXTRACT(second FROM bucket) = 0`, and the numbers in this
file already do.

### 6.3 ✅ Second fully-covered session interior (§3.11)

15-minute buckets, 09:15 → 15:15, **no empty bucket**:

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 | 12:15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 8 | 10 | 80 | 80 | 90 | 90 | 70 | 40 | 32 | 32 | 24 | 80 | 80 |
| bucket | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 | 14:45 | 15:00 | 15:15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 80 | 16 | 38 | 80 | 34 | 12 | 8 | 8 | 18 | 18 | 10 | 4 |

The 12:45/13:00 dip is §6.1's outage, directly attributed. Unlike 07-21 the tail runs to **15:18**,
so the "empty tail after 14:57" flag from that file does not recur — it was a trade-window artefact,
as suspected. Combined with `failures_total = 0` and counters advancing at every check
(09:49 / 12:38 / 12:44 / 13:49 / 15:57), this is the second clean coverage record in a row.

### 6.4 ⚠ `vwap` at 100% for a fourth consecutive session; `supertrend` near-free again

`vwap` supported **828/828** today: 359/359 (07-17) + 748/748 (07-20) + 1,070/1,070 (07-21) +
828/828 = **3,005 consecutive rows at weight 2.5 with zero discrimination**, now spanning a
CE-heavy, a mixed, a mild-down and a clean-down session. **T6 is as well-evidenced as anything in
this ledger.** `supertrend` read 95.9% (07-21: 100%) — high but no longer free; keep it a watch.

### 6.5 The two silent scalpers are a tape artefact

36 of 38 loaded scalpers emitted. The two silent slugs are `scalp-golden-crossover-nifty` and
`scalp-golden-crossover-sensex-niftyoi` — CE variants on a −0.60% day, dying at the chart gate,
which writes no rejection row (`chart-gate-failed` = 2,028, the largest counter of the day). Not the
07-17 shrinking-load class; the boot line shows 38/38 resolved.

### 6.6 ⚠ CARRIED, WORSENING — 19 paper positions OPEN, brackets starved all session

`strategy.paper_positions`: **19 OPEN** (07-21: 15, 07-20: 17), oldest 2026-07-07, and **4 NEW ones
opened by the 07-21 20:00/20:05 swing batch** (`minervini`: KANORICHEM, MENONBE; `manas-arora`:
KANORICHEM, TIRUPATIFL). `PaperStaleTickAlerter` WARNed **31,730 times** today, worst case:

```
paper bracket starved: position 13 SL/TP un-evaluated for ~25184s (tick absent) — stop may not fire
```

~25,184 s ≈ **7 hours** un-evaluated. These are NSE cash equities on the swing books, not on the
live tick subscription (`tickedTokens = 25`). The population is now **growing** rather than draining
— the swing batch keeps opening positions whose intraday stops cannot fire. T10 escalates from
"chronic" to "accumulating".

### 6.7 Zero fires, fifth consecutive session with no straddle fire

`strategy.signals`: **0 rows**. `fired` counter = 0. Explained by §2.1 (the PE book's volume rail
welded shut) plus the CE book dying at the chart gate.

### 6.8 `FINNIFTY26SEPFUT` canary noise — 4th consecutive session

18 of the day's 25 market-data ERROR lines are the same far-month contract (`ticks flowing but no
1m bar closed for 606–909 s`). Not a scalper signal series, absent from the health endpoint's
`problems`, `status=GREEN` throughout. The suggestion stands: exclude non-front contracts from the
divergence probe or scale the threshold by liquidity. The remaining 7 ERROR lines are all §6.1.

### 6.9 Method addendum → README §3.15

Promote to the standard pass: **any query that rolls up or counts 1m candles must filter to
minute-aligned buckets** (`EXTRACT(second FROM bucket) = 0`), and every session should count the
misaligned rows as a data-integrity probe. §6.2 is the evidence; without the filter, every volume
percentile, bar count and 3m rollup in this folder is silently inflated after any feed outage.

## 7 Tuning candidates

Carried forward from 07-21 plus this session's new rows. **Nothing here is applied** — every row is
a PROPOSAL.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T16** | `relative-volume-floor` tag on the 18 PE scalpers (+ the 10 sensex CE) | **absent** since the 07-20 21:28 republish | restore the tag and re-publish; add a guard so a seeder-draft publish cannot drop an armed tag | §2.1 registry unchanged; 816 volume-floor blocks at a flat 125,000 vs 7,069–17,940 on the armed CE slugs the same day; 125,000 clears **2 of 125** 3m bars. §5.1: the rows it alone vetoed were **56W/30L, +2,547.6 pts** | **STRUCTURAL (regression)** | **PROPOSED — 2nd session, HIGHEST PRIORITY** |
| **T19** | gap-backfill bucket alignment | `from`/`to` passed unfloored → bars stored at `HH:MM:38` | floor the backfill window (or the bucket at write) to the minute; clean up the 1,598 historical misaligned rows | §6.2: 308 rows / 22 instruments today, 403 on 07-20, 887 on 07-15; PK makes them phantom rows, not upserts; 3m rollup median inflates 13,520 → 14,885 — a **live gate input** | **STRUCTURAL (defect)** | **PROPOSED — NEW 07-22** |
| T12 | OI quadrant / spurt reads | — | fix the failing `/options/spurt` read; separately fix the futures-OI 1-minute capture cadence | quadrants healthy a 2nd session while cadence fell again (**187**/375 after 192, 208) — cadence mechanism refuted, endpoint still suspect; the decline is now a 3-session trend | **STRUCTURAL (defect)** | **PROPOSED — carried** |
| T6 | `vwap` dot weight | 2.5 | narrow the support condition or cut the weight | **828/828 = 100% for the 4th consecutive session**; **3,005 rows** across four tape characters with zero discrimination | **STRUCTURAL** (4 sessions) | **PROPOSED — strengthened** |
| T13 | `dot-health` probe registry | 6 probes | add `futures_oi` / `underlying_oi` NEUTRAL-share probes | 07-20: canary healthy while the two heaviest OI dots were dead all session | **STRUCTURAL** | **PROPOSED** (carried) |
| **T17** | `dot-health` sampling window | newest 40 rejections | sample the newest 40 **context-bearing** rows **from the current session only** | §4: today's false ERROR fired at **08:56, pre-open**, off yesterday's `time-window` tail — the defect bites at both ends of the day, not just post-close | **STRUCTURAL (diagnostic)** | **PROPOSED — scope widened** |
| T18 | `breadth` dot threshold | `advances/declines > 32` (of 50) | **no change** | §3: on a real down day declines ran 36–45 and the dot supported **96.6%**; 07-21's 0.2% was a mild-tape regime miss, not an unreachable threshold | **REGIME (resolved)** | **CLOSED — no action** |
| T1 | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | 07-22 is the first clean **for**: both loosened books 4-for-4 (+₹8,332 each) and `vol-12k5` turned net-positive all-time (+₹3,046). But the wins are **all pre-11:00**; the same rows after 12:00 are net-negative. Ledger now **3-for / 3-against** | **REGIME** | **PROPOSED — still do NOT apply; decide with T16, not before** |
| T11 | SENSEX volume-floor | fixed 125,000 | arm the relative floor for the sensex family | the 10 sensex CE slugs still carry no tag (published 06-29/06-30) | **STRUCTURAL** | **MERGED INTO T16** |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source ivRank or drop from Σw | dead every session since 07-02 | **STRUCTURAL** | **PROPOSED** (carried) |
| T3 | `iv_pair` gap threshold | 0.02 | ~0.005 | 0/828 today; 0% on every session logged, including three post-recalibration | **STRUCTURAL** | **PROPOSED** (carried, confirmed 5×) |
| T4 | `basis` dot | w 1.0 | — | alive (83.8%) | — | **CLOSED — no action** |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | 0/124 again today (4th session) | **REGIME** (4 sessions) | **PROPOSED**, collect more |
| T7 | composite threshold | 0.600 | no change | `composite-055` took **nothing** again; cumulative net −₹478.98. Today 568 rows cleared 0.600 unaided | — | **REJECTED** (reaffirmed) |
| T8 | shadow entry latency | p50 ~73 s | stamp entry at bar close | 8 sessions in the 73–87 s band | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T9 | strategy-coverage watchdog | none | alert on emitting/published ratio drop, split by counter | 36/38 today (best on record) — the shortfall is tape-driven; the watchdog needs the counter split | **STRUCTURAL** | **PROPOSED** (needs redesign) |
| T10 | **19** stale OPEN paper positions + starved brackets | open since 07-07, **growing** | square off / age out, or subscribe the swing holdings | §6.6: 4 NEW positions from the 07-21 batch, 31,730 starvation WARNs, worst ~25,184 s (7 h) | ops | **OWNER — escalating** |
| T14 | rejection-row invariant | none | assert `blocking_margin < 0` on persist | 2nd consecutive clean session (0 non-negative margins); the 07-20 defect is intermittent, so the invariant is still worth adding | **STRUCTURAL (diagnostic)** | **PROPOSED** (carried) |
| T15 | engine boot-line durability | log only | persist `loaded/unresolved/dropped` to a table | readable today (`RestartCount=0`) — twice in a row by luck, not design | **STRUCTURAL (data-model)** | **PROPOSED** (carried) |
| T20 | far-month contracts in the bar-divergence canary | all subscribed instruments | exclude non-front contracts, or scale the no-bar threshold by liquidity | §6.8: `FINNIFTY26SEPFUT` produced 18 of 25 market-data ERRORs, 4th consecutive session, `status=GREEN` throughout | ops (noise) | **PROPOSED — NEW 07-22** |

## 8 Honesty caveats

- **The champion book's +₹42,240.91 is 14 distinct entry events, not 27 positions.** Counted once
  each they sum to roughly **+₹18,080** (8W/6L). The session is genuinely positive for the
  would-have-fired class either way — the first since 07-17 — but the headline is inflated ~2.3×.
- **§5.1's +2,547.6 points is an upper bound.** The manual model has 2-minute snapshot granularity,
  no slippage/fees, approximated strike-picking, and — most importantly — **no structural stop**,
  which the shadow book shows firing on several of the same legs (−1.1% to −10.1%). The 86 rows are
  4 slugs on 6 legs, effectively two idea families, on one down-trending day.
- **Today's evidence for loosening the volume floor is the mirror image of yesterday's evidence
  against it, on the same rail, one session apart.** 07-21: 6 of 6 losers. 07-22: 56 of 86 winners.
  Both are regime readings. The case for T16 is that an **armed knob was silently disarmed**, not
  that loosening pays.
- **§6.2's root cause is inferred from the call path** (`GapBackfillService` → `backfillRange` →
  `fetchAndStore`, `from` = the raw tick-gap instant) plus the observed 38-second offset matching
  the logged gap timestamp exactly. The precise write site was not read; treat the proposed fix as a
  direction, not a patch.
- **The §3.8 percentiles, bar counts and capture table in this file filter to minute-aligned buckets.**
  Earlier files in this folder do not, so their post-outage sessions (07-15, 07-20) carry an
  unquantified upward bias in any 3m volume figure.
- Shadow exits replicate brackets / structural stop / square-off only — **no indicator-driven
  exits**. Every entry is stamped ~73 s after `bar_time`; bias direction unmeasured.
- **The PE-ceiling result (0.8511, 568 rows over threshold) is from one clean down day.** It answers
  "how high can PE score", not "is a PE fire profitable" — no PE signal has ever fired.
- Costs are the 1-lot engine fill model (statutory + ₹20/lot). Not cost-adjusted twice.
- This run was **read-only**: SELECTs, `docker logs`, in-container health GETs. No restart, deploy,
  write or config change. No strategy knob was altered.
