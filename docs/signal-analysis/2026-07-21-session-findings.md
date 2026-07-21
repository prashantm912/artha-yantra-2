# Session findings — 2026-07-21 (data date)

Analysis date: 2026-07-21 (scheduled post-market agent, ran 16:54–17:10 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,372** (bar_time 09:21–14:57 IST), signals fired **0**, paper
positions opened **0**, shadow positions **16** (9 champion + 7 challenger).
Session character: **mild down / rangebound, NIFTY weekly expiry day (Tue).** `NIFTY26JULFUT`
opened 24,220.00 and closed 24,185.00 (**−35.0 pts, −0.14%**), range 24,135–24,280 (145 pts).
`SENSEX26JULFUT` 77,793.90 → 77,508.40 (−285.5). 3m volume median 13,390 — as thin as 07-20.
**The tape was PE-side: 1,068 of 1,070 scored rows were PE** (07-20: 202 of 748).

This file folds in the three earlier read-only runs of the same date:
`2026-07-21-open-gate.md`, `2026-07-21-midday-gate.md`, `2026-07-21-live-watch-findings.md`.

---

## 0 Read this first — the session's headline

**A config regression, not a market story.** At **21:28:56 IST on 2026-07-20** all **18 PE
scalpers** were re-published from seeder drafts whose tag list is missing
**`relative-volume-floor`**. The engine arms that floor from the *published* config tag
(`ScalperConfluenceGate.java:422`, `cfg.has("relative-volume-floor")`), so from this morning every
PE strategy fell back to the **fixed 125,000** volume floor. On a PE-dominated tape that floor
became the session: **1,069 of 1,372 rejections (77.9%) died on it, every one at threshold
125,000**, against an operand averaging 20,840 — and only **1 of the day's 125 three-minute bars**
cleared 125,000 at all (p99 = 117,455).

Two things went **right** and should be recorded as such:

1. **07-20's top finding is RESOLVED.** The OI dots are alive again — `futures_oi` 57.6%,
   `underlying_oi` 63.9%, `drastic_oi` 80.4%, **zero NEUTRAL quadrants** on the whole session. The
   composite cap recovered **0.7181 → 0.9043**.
2. **First fully-covered interior since §3.11 was written** — 15-minute buckets are continuous
   09:15 → 14:57 with no hole, and `subscriber_health_events` recorded nothing. The container was
   *not* recreated post-close, so today is also the **first session whose boot line and eval
   counters survived to be read** (§6.4).

And the money question has a clean answer: the six entries the volume floor **alone** vetoed were
**6 losers out of 6** (§5.1). Loosening it would have cost money today.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-15 | 2026-07-17 | 2026-07-20 | **2026-07-21** |
|---|---|---|---|---|
| rejections | 396 | 523 | 1,013 | **1,372** |
| distinct strategies emitting | 33 | 17 ⚠ | 49 | **22** |
| published + enabled | ~63 | 63 | 44 | **44** (38 scalper + 6 swing) |
| signals fired | 3 | 3 | 1 | **0** |
| paper positions opened | — | 0 | 0 | **0** |
| bar-time coverage | 09:50–15:19 | 09:24–15:18 | 10:19–15:19 + hole | **09:21–14:57, no hole** ✅ |
| composite ≥ threshold rows | 144 | 210 | 230 | **218** |

**Engine load (§3.10) — read the same day, logs intact:**

```
03:19:23Z (08:49 IST)  signal engine loaded 0 published strategies (38 dropped on an unresolved universe, 0 failed to load)
03:21:05Z (08:51 IST)  signal engine loaded 38 published strategies (0 dropped on an unresolved universe, 0 failed to load)
```

The cold boot hit the known F10 shape (38 × `futures universe resolution failed for NIFTY 50: 503
Service Unavailable`) and the #874 retry recovered it 102 s later, before the open. Health signal
`unresolved == 0` holds. Denominator: 38 scalpers loaded, **22 emitted** — the 16 silent slugs are
the CE variants, which is a *tape* explanation this time (see §6.5), not the 07-17 shrinking-load
class.

**Eval counters (actuator :8082, read 16:54 IST BEFORE any deploy):**

| outcome | value |
|---|---|
| `chart-gate-failed` | 2,020 |
| `composite-below-threshold` | 178 |
| `confluence-blocked` | **1,372** |
| `fired` | **0** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 |
| **Σ** | **3,570** |
| `ay_signal_eval_failures_total` | **0** |

`confluence-blocked` = 1,372 = the row count exactly; the two views agree. Midday reading was Σ
2,456 at 13:17 IST, so the engine added 1,114 evaluations over the afternoon.

**First-blocking-rail histogram** (1,372 rows):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **1,069 (77.9%)** | 20,840.4 | **125,000.0** | −104,159.6 |
| time-window | 256 (18.7%) | — | — | — |
| time-of-day-preference | 44 | — | — | — |
| option-side-constraint | 2 | — | — | — |
| morning-opening-formation | 1 | — | — | — |

Only **five** distinct first-blocking rails all session (07-20 had thirteen) — the funnel died
early and uniformly.

**All-failed-rails expansion (§3.3)** — top 8:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | 1,069 | 20,840.4 | 125,000.0 |
| confluence-composite | FAIL_CLOSED | 854 | 0.454 | 0.600 |
| rsi-band | FAIL_CLOSED | 672 | 44.04 | — |
| strike-pick | FAIL_CLOSED | 535 | — | — |
| time-window | FAIL_CLOSED | 256 | — | — |
| divergence-vol-gate / trend-change | FAIL_CLOSED | 170 each | 18,741.4 / — | — |
| directional-vix-gate | FAIL_OPEN | 170 | 12.74 | — |
| volume-pump / pct-price-move / two-candle | FAIL_OPEN / FAIL_OPEN / FAIL_CLOSED | 128 each | 22,296.0 / −0.111 / — | — / 1.000 / — |

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 ⚠⚠ THE FINDING — `relative-volume-floor` was silently stripped from all 18 PE scalpers

**Measurement.** Every one of the 1,069 volume-floor blocks carries `blocking_threshold =
125000.000000` exactly — `min = max = 125,000` for both families:

| family | n | avg operand | min thr | max thr |
|---|---|---|---|---|
| nifty | 534 | 20,750 | 125,000 | 125,000 |
| sensex | 535 | 20,931 | 125,000 | 125,000 |

Contrast **yesterday**, same query, nifty family only — the relative floor was demonstrably live,
*including on the `-pe` variants*:

| slug (07-20) | n | min thr | max thr |
|---|---|---|---|
| `scalp-connect-the-dots-nifty` | 29 | 15,112.5 | 53,137.5 |
| `scalp-connect-the-dots-nifty-pe` | 12 | **12,187.5** | **32,370.0** |
| `scalp-trending-oi-nifty-pe` | 9 | 12,187.5 | 21,645.0 |
| … (all 16 nifty slugs banded, none at 125,000) | | | |

**Root cause — config, proven in the registry.** Version history of
`scalp-connect-the-dots-nifty-pe`:

| version | status | created (IST) | carries `relative-volume-floor` |
|---|---|---|---|
| 1.0.0 | archived | 2026-06-30 21:22:32 | **no** |
| 1.0.1 | archived | 2026-07-06 17:10:07 | **yes** ← what ran on 07-20 |
| 1.0.2 | draft | 2026-07-07 00:21:33 | **no** ← seeder `resyncConfig` draft |
| 1.0.3 | **published** | **2026-07-20 21:28:56** | **no** ← what ran today |

Grouped across the whole published scalper set:

| carries tag | published on | count | family |
|---|---|---|---|
| **yes** | 2026-07-06 | 10 | all nifty CE |
| no | 2026-06-29 / 06-30 | 10 | all sensex CE (never armed — this is T11) |
| **no** | **2026-07-20 21:28:5x** | **18** | **all `-pe`, nifty AND sensex** |

All 18 PE publishes are stamped 21:28:55–21:28:56 IST — a single batch action, ~6 h after the
07-20 close, ~11 h before this session. This is the exact failure mode CLAUDE.md warns about
("`ScalperStrategySeeder` mints a fresh DRAFT on boot … the live engine runs the *published*
version"), run in reverse: publishing the stale seeder draft **reverted** a knob that had been
armed since #605.

**Code ground truth** — the floor is tag-gated, with the fixed value as the else-branch:

```java
cfg.has("relative-volume-floor")
    ? ScalperGates.relativeVolumeFloor(priorVolumes(...), oiProps.relativeVolumeMultiplier(), ...)
    : /* fixed floor */                       // ScalperConfluenceGate.java:422-426
```

**Impact — the 125,000 floor is above p99 of the signal series' own distribution.** 3m rollup of
`NIFTY26JULFUT`, 09:15–15:30 IST (the right series for both families per ADR-0003):

| bars | min | p50 | p90 | p95 | p99 | max | bars ≥ 125,000 |
|---|---|---|---|---|---|---|---|
| 125 | 2,210 | 13,390 | 51,480 | 56,160 | **117,455** | 364,260 | **1** |

One passable bar in 125. By README §3.8's own definition that is past "near-never" — for practical
purposes the PE book ran all session with its volume rail **welded shut**.

**Verdict: STRUCTURAL, and a regression rather than a tuning question.** Restoring the tag on the
18 PE strategies is a config action, not a knob change — but it is still owner-gated because
re-publishing changes live behaviour. Filed as **T16**.

### 2.2 The floor nonetheless blocked only losers today (§5.1)

Do not read §2.1 as "unblock it and we'd have made money". The six rows the floor **alone** vetoed
resolve **6 WOULD-LOSE / 0 WOULD-WIN**, and both loosened shadow variants lost heavily (§5). The
regression is real *and* today it was accidentally protective. Those are separate claims.

### 2.3 Rails with no evidence of miscalibration

`rsi-band` (avg 44.04 — correctly low-mid on a soft tape), `pct-price-move` (−0.111 vs 1.000),
`oi-divergence-magnitude` (−13.55 vs 20.0), `directional-vix-gate` (12.74, FAIL_OPEN) all read
plausibly. No order-of-magnitude gaps other than §2.1.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (1,070 scored rows):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 |
|---|---|---|---|---|---|---|
| n | 12 | 140 | 222 | 338 | 264 | 94 |
| CE | 0 | 0 | 0 | 0 | 0 | 2 |
| PE | 12 | 140 | 222 | 338 | 264 | 92 |

Max composite **0.7447 — and it is a PE row.** Max CE 0.6915 (n=2). Threshold 0.600; **218 rows
cleared it**, of which 216 are PE.

**This answers the rollup's standing PE question in part.** Since 07-02 the open item has been
"can a PE composite ever pass?" — 07-20 got PE evaluating at scale but capped at 0.452. Today PE
reached **0.7447 with 216 rows over the line**. A PE composite passes fine; what stopped every one
of them was the §2.1 volume rail, not the confluence score. (Caveat: today was mildly *down*, not
a clean trend-down day, so the ceiling is still not measured on a strong move.)

**Dot support rates** (1,070 rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_abs_band` | 0.8 | 0/170 | 0.0 | dead (3rd session) |
| `iv_pair` | 0.8 | 0/1070 | 0.0 | dead — carried since 07-02 |
| `iv_rank` | 0.8 | 0/1070 | 0.0 | dead-data, **withheld from Σw** (#676) |
| `volume` | 1.0 | 0/1070 | 0.0 | mirrors §2.1 — mechanically dead while the floor is 125k |
| `breadth` | 1.0 | 2/1070 | **0.2** | ⚠ near-miss, not dead — see below |
| `vix` | 1.0 | 2/1070 | **0.2** | ⚠ was 54.0% on 07-20 |
| `oi_spurt` | 1.0 | 32/1070 | 3.0 | ✅ **alive again** (0.0% on 07-20) |
| `trending_cross` | 1.0 | 154/1070 | 14.4 | |
| `basis` | 1.0 | 248/1070 | 23.2 | alive (T4 stays closed) |
| `rsi` | 1.0 | 444/1070 | 41.5 | |
| `iv_slope` | 0.8 | 86/170 | 50.6 | alive |
| `sentiment_slope` | 1.0 | 556/1070 | 52.0 | |
| `futures_oi` | 1.5 | 616/1070 | **57.6** | ✅ **RECOVERED** (0.0% on 07-20) |
| `sentiment` | 1.0 | 662/1070 | 61.9 | |
| `underlying_oi` | 1.0 | 684/1070 | **63.9** | ✅ **RECOVERED** (0.0% on 07-20) |
| `psar` | 1.0 | 790/1070 | 73.8 | |
| `drastic_oi` | 1.0 | 860/1070 | 80.4 | |
| `vwma` | 1.0 | 932/1070 | 87.1 | |
| `supertrend` | 1.0 | 1070/1070 | **100.0** | ⚠ **NEW — free dot this session** |
| `vwap` | 2.5 | 1070/1070 | **100.0** | ⚠ **3rd consecutive session at 100%** |

**`breadth` is a near-miss, not dead data.** The context carries real constituent counts all
session (0 zero-pairs); the dot's own reason string is `advances/declines > 32`, and today's
extremes were **advances 19–36, declines 14–31**. On the PE side the operand needed **declines >
32** and the day's maximum was **31** — one constituent short, all session. That is a genuine
regime miss sitting one tick from a threshold, which is a different (and more interesting) class
than `iv_rank`'s permanent null. Filed as **T18**.

### 3.1 The dead-weight cap — recovered to 0.9043

| dots on row | rows | Σw | dead w (volume + iv_pair + iv_abs_band) | cap (iv_rank withheld) |
|---|---|---|---|---|
| 18 | 900 | 19.60 | 2.60 | **0.9043** |
| 20 | 170 | 21.20 | 3.40 | 0.8725 |

Observed max **0.7447**, well clear of the cap. **Compare 07-20's cap of 0.7181, where 30 rows sat
exactly on the ceiling.** With the OI dots restored the composite regained ~19 points of headroom
and today was genuinely *discriminating* rather than structurally starved — the 0.600 threshold
required 66.4% of live weight (07-20: 83.6%).

## 4 Data health (§3.7)

| field | 2026-07-20 | **2026-07-21** | class |
|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | **NEUTRAL 748/748** | **0 NEUTRAL**, 13 distinct combos, 302 NULL | ✅ **RESOLVED** |
| `spurtOiPct` / `spurtPricePct` | null 1,013/1,013 | null **302**/1,372 | ✅ **RESOLVED** |
| `advances` / `declines` | 0 zero-pairs, 265 null | 0 zero-pairs, **302 null** | HEALTHY (see below) |
| `ivRank` | NULL 100% | NULL 100% | dead-data (carried since 07-02) |
| `fiiLongPct` | NULL 100% | NULL 100% | dead-data (carried) |
| `vix` (macro mirror) | NULL 100% | NULL 100% | known mirror gap |

**The 302 nulls are fully explained and are NOT a feed problem.** 302 = 256 `time-window` + 44
`time-of-day-preference` + 2 `option-side-constraint` — exactly the rows blocked at a rail that
fires *before* macro/OI context is gathered. Every context-bearing row carries live data. This
resolves an ambiguity the 07-20 file left open (265 null advances, unexplained there).

**Capture was healthy.**

| series | bars / snaps | window |
|---|---|---|
| `NIFTY26JULFUT` 1m | **375** | 09:15–15:29 |
| `SENSEX26JULFUT` 1m (BFO) | **375** | 09:15–15:29 |
| SENSEX 07-23 chain | 65,424 over 174 strikes, **0 null OI** | →15:32 |
| BANKEX / NIFTY BANK / SENSEX monthly chains | 60k–74k each, **0 null OI** | →15:33 |
| `futures_oi_snapshots` (NIFTY26JULFUT) | **192 rows / 192 distinct minutes** | — |

⚠ **`futures_oi_snapshots` cadence is still degraded** — 192 of ~375 minutes (51%), continuing
07-20's 208 and against 365 on 07-17. **But the OI quadrants were live anyway today.** That is a
direct refutation of 07-20 §6.2's leading hypothesis ("gappy 1-minute capture leaves a 3-minute
`latestPair` read with no prior bucket"): the same gappy cadence produced 0 NEUTRAL today. The
cadence regression is real and worth fixing, but it was **not** the cause of the 07-20 outage.
07-20's other suspect — the failing `/options/spurt` read — is the more likely one, and `oi_spurt`
recovering to 3.0% today is consistent with that read having been transiently broken.

**`dot-health` canary — a sampling artifact, and it produced the day's only ERROR line.**

```
dot canary: required dot 'breadth' input DEAD — input dead across 40 rejections
```

The probe inspects the **newest 40 rejections**, which at end of day are the 14:5x `time-window`
rows — precisely the class that carries **no macro/OI context at all** (see above). At 16:58 the
endpoint reported *all six* probed dots dead, including `oi_spurt_price` which it had reported
**alive** at 09:44. Nothing died; the sample went context-less. **The canary's EOD reading is not
trustworthy as written** — filed as **T17**.

## 5 Shadow-book outcomes

**Exit-fidelity caveat (standing):** indicator-driven exits (trend-flip / signal-exit) are NOT
replicated — premium brackets, structural stop and 15:12 square-off only. Rejections blocked
before leg resolution never shadow.

**Champion book — 9 closed, 7W/2L, +177.45 pts, +₹2,872.77 net.** All nine were first-blocked by
`volume-floor`.

| bar | strategy | leg | entry | exit | close | pts | % | net ₹ |
|---|---|---|---|---|---|---|---|---|
| 09:21 | morning-trade-sensex | SENSEX 77100**CE** | 785.50 | 625.55 | STRUCTURAL_STOP | −159.95 | −20.4 | −3,270.87 |
| 09:48 | gap-theory-sensex-pe | SENSEX 78200PE | 743.95 | 811.60 | SQUARE_OFF | +67.65 | +9.1 | +1,276.22 |
| 09:48 | market-movers-sensex-pe | SENSEX 78200PE | 743.95 | 811.60 | SQUARE_OFF | +67.65 | +9.1 | +1,276.22 |
| 09:48 | open-high-low-sensex-pe | SENSEX 78200PE | 743.95 | 811.60 | SQUARE_OFF | +67.65 | +9.1 | +1,276.22 |
| 09:48 | trend-change-sensex-pe | SENSEX 78200PE | 743.95 | 811.60 | SQUARE_OFF | +67.65 | +9.1 | +1,276.22 |
| 09:48 | two-candle-sensex-pe | SENSEX 78200PE | 743.95 | 811.60 | SQUARE_OFF | +67.65 | +9.1 | +1,276.22 |
| 10:15 | trending-oi-sensex-pe | SENSEX 78100PE | 693.70 | 732.10 | SQUARE_OFF | +38.40 | +5.5 | +693.92 |
| 10:24 | golden-crossover-sensex-pe | SENSEX 78100PE | 731.55 | 732.10 | SQUARE_OFF | +0.55 | +0.1 | −63.42 |
| 10:39 | connect-the-dots-sensex-pe | SENSEX 78000PE | 696.40 | 656.60 | SQUARE_OFF | −39.80 | −5.7 | −867.96 |

⚠ **CORRELATION CAVEAT — this is 5 distinct entry events, not 9.**

| event | positions | outcome |
|---|---|---|
| 09:21 SENSEX 77100CE @785.50 | 1 | **L** (−20.4%) |
| 09:48 SENSEX 78200PE @743.95 | **6** | **W** (+9.1%) |
| 10:15 SENSEX 78100PE @693.70 | 1 | W (+5.5%) |
| 10:24 SENSEX 78100PE @731.55 | 1 | flat (+0.1% gross, −₹63 net) |
| 10:39 SENSEX 78000PE @696.40 | 1 | L (−5.7%) |

**The +₹2,872.77 is one idea multiplied ×5.** Counting each idea once, the same five events sum to
roughly **−₹2,232** — the single 20.4% CE loser outweighs the winners. Do not read this as a
profitable session; read it as "the correlation multiplier happened to land on the winner today,
the opposite of 07-20".

**Variant league — this session:**

| variant | closed | wins | pts | net ₹ |
|---|---|---|---|---|
| champion | 9 | 7 | +177.45 | **+2,872.77** |
| vol-off | 4 | **0** | −241.05 | **−5,112.20** |
| vol-12k5 | 3 | **0** | −222.75 | **−4,671.44** |
| composite-055 | 0 | — | — | — (took nothing) |

**Both loosened volume books went 0-for-7 and lost ~₹9,784 between them.** Their four distinct
entries (11:30, 11:42, 13:00, 13:03 — all `SENSEX…PE`) are exactly the rows the champion book
could not take because dedup held it in an open position on the same strategy+side.

**Cumulative league (all sessions, judge on NET ₹):**

| variant | closed | net wins | pts | **net ₹** |
|---|---|---|---|---|
| champion | 103 | 38 | −580.60 | **−41,260.30** |
| vol-off | 21 | 4 | −383.65 | −11,226.90 |
| vol-12k5 | 13 | 2 | −265.65 | −5,285.67 |
| composite-055 | 8 | 2 | +2.50 | −478.98 |

**Entry latency (F8):** p50 **77.1 s**, p95 **82.5 s** (16 positions) — inside the structural
73–87 s band seen every session. Standing caveat: every shadow fill is stamped ~77 s after
`bar_time`.

### 5.1 Counterfactual — the 6 would-have-fired rows (§4.2)

The §3.5 would-have-fired set (composite ≥ threshold, `volume-floor` the **only** failed check) is
**6 rows, all `SENSEX…PE`, from 2 slugs**. Four are directly resolved by a challenger shadow
position on the same rejection id; two (11:45, 13:03 golden-crossover) had no shadow row (dedup)
and are resolved by hand from `options_chain_snapshots`.

| bar | strategy | leg | composite | entry | exit | basis | pts | % | verdict |
|---|---|---|---|---|---|---|---|---|---|
| 11:30 | golden-crossover-sensex-pe | SENSEX 78100PE | 0.6915 | 755.30 | 737.00 | shadow `vol-off`, STRUCTURAL_STOP | −18.30 | −2.4 | **WOULD-LOSE** |
| 11:42 | golden-crossover-sensex-pe | SENSEX 78000PE | 0.6915 | 700.95 | 669.25 | shadow `vol-off`/`vol-12k5`, STRUCTURAL_STOP | −31.70 | −4.5 | **WOULD-LOSE** |
| 11:45 | golden-crossover-sensex-pe | SENSEX 78000PE | 0.6915 | 699.10 | 669.25 | manual — sibling's stop | −29.85 | −4.3 | **WOULD-LOSE** |
| 13:00 | golden-crossover-sensex-pe | SENSEX 77900PE | 0.6383 | 715.00 | 633.75 | shadow, STRUCTURAL_STOP | −81.25 | −11.4 | **WOULD-LOSE** |
| 13:03 | connect-the-dots-sensex-pe | SENSEX 78000PE | 0.6765 | 766.40 | 656.60 | shadow, SQUARE_OFF | −109.80 | −14.3 | **WOULD-LOSE** |
| 13:03 | golden-crossover-sensex-pe | SENSEX 78000PE | 0.6915 | 768.20 | 656.60 | manual — 15:12 square-off | −111.60 | −14.5 | **WOULD-LOSE** |

**6 of 6 WOULD-LOSE.** The +35% take-profit was never in reach: from a 699 entry it sits at ~944,
and the strike's high after 11:45 was **768.20** (low 631.20). The 11:45 row loses on either exit
convention (structural stop −4.3%, or 15:12 square-off at 657.00 = −6.0%), so the verdict is
robust to the approximation.

**Method note worth carrying forward.** The champion book's per-rail attribution credits
`volume-floor` with **+₹2,873** while the would-have-fired analysis of the *same rail on the same
day* returns **6 losers out of 6**. Both are correct and they measure different things: the
champion book opens on any composite-passing rejection **whatever else also failed**, so its
"blocked by volume-floor" bucket is mostly rows that other rails vetoed too — unblocking the
volume floor alone would never have produced those entries. **For tuning a single knob, use the
§3.5 would-have-fired set, not the per-rail shadow bucket.** Filed as a method addendum, README
§3.13 (below).

## 6 New data points / anomalies

### 6.1 ✅ First fully-covered session interior (§3.11)

15-minute buckets, 09:15 → 14:57, **no empty bucket**:

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 6 | 8 | 80 | 80 | 88 | 90 | 90 | 70 | 70 | 70 | 90 |
| bucket | 12:00 | 12:15 | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 | 14:45 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 40 | 60 | 40 | 80 | 80 | 60 | 20 | 20 | 60 | 70 | 70 | 30 |

`strategy.subscriber_health_events`: **0 rows today** (07-20 had five). Combined with
`ay_signal_eval_failures_total = 0` and Σ counters advancing at every check (09:47, 13:17, 16:54),
this is the cleanest coverage record in the folder.

⚠ **One tail note, flagged not alarmed:** the last row is bar **14:57**, whereas 07-17 and 07-20
both ran to ~15:18. The 14:45–14:57 bars carry only 4 slugs, all blocked by `time-window` — the
strategies' trade windows were closing normally. Whether anything evaluated after 14:57 cannot be
settled from rejections alone (the counters are cumulative, not time-bucketed). Watch it next
session; do not treat one truncated tail as a stall.

### 6.2 ✅ 07-20's OI outage did not repeat — and its stated mechanism is partly refuted

Zero NEUTRAL quadrants across 1,070 context-bearing rows, 13 distinct quadrant combinations,
`spurtOiPct` populated on every context-bearing row. Meanwhile `futures_oi_snapshots` cadence is
**192/375 minutes — worse than 07-20's 208**. Since the cadence got worse and the quadrants got
better, the "gappy 1-minute capture ⇒ NEUTRAL fallback" hypothesis in 07-20 §6.2 does **not**
hold on its own. The `/options/spurt` HTTP 400 remains the better-supported suspect. **T12 stays
open** (both the spurt read and the cadence deserve fixing) but its diagnosis should be rewritten
around the endpoint, not the cadence.

### 6.3 ⚠ `supertrend` joins `vwap` as a free dot

`supertrend` supported **1,070/1,070** today — first time it has been recorded at 100%. `vwap` is
now at 100% for the **third consecutive session** (359/359 on 07-17, 748/748 on 07-20, 1,070/1,070
today), across 2,177 rows without discriminating once. Note both are one-directional populations
today (98% PE), so a single-side tape inflates any directional dot — but `vwap`'s streak now spans
a CE-heavy day, a mixed day and a PE-heavy day, which is the harder standard. **T6 strengthened;
`supertrend` added as a watch, not yet a candidate.**

### 6.4 ✅ The log race was finally won

`ay-strategy-signal-service` has `RestartCount=0` and `StartedAt = 2026-07-21T03:19:15Z`
(08:49 IST) — **no post-close deploy recreated it before this run**. Consequently the boot line
(§1), the eval counters (§1) and the full day's logs were all readable, unlike 07-17 and 07-20.
This is what T15 asks to make permanent rather than lucky.

### 6.5 The CE book was silent for a tape reason, not a load reason

22 of 38 loaded scalpers emitted; 18 of the 22 are `-pe` slugs and the 4 CE-ish ones contributed
only 42 rows (11/11/10/10). With the index down and PE-favourable, the CE variants died at the
chart gate — which writes **no rejection row** (`recordRejection` is downstream of the chart-gate
early return). `chart-gate-failed = 2,020` is the largest counter of the day and is exactly where
that population went. **This is not the 07-17 shrinking-numerator class** — the boot line shows
38/38 resolved and the counter shows the CE evaluations happening.

### 6.6 ⚠ CARRIED — 15 paper positions still OPEN, brackets starved all session

`strategy.paper_positions`: **15 OPEN** (was 17 on 07-20), oldest 2026-07-07, newest 2026-07-20
20:05 IST. `PaperStaleTickAlerter` WARNed once per position per cycle all day, worst case:

```
paper bracket starved: position 33 SL/TP un-evaluated for ~9988s (tick absent) — stop may not fire
```

~9,988 s ≈ 2 h 46 m un-evaluated. These are NSE cash equities on the `minervini` / `manas-arora`
books, which are not on the live tick subscription (`tickedTokens = 25`, indices/futures/option
legs). Chronic, not a today-regression, but a live intraday stop on the swing books **would not
fire**. Owner call (T10 extended).

### 6.7 Zero fires, zero paper entries — fourth consecutive session with no straddle fire

`strategy.signals`: **0 rows today** (07-20 had 1, the `scalp-straddle-nifty` advisory). `fired`
counter = 0. Nothing anomalous given §2.1 welded the PE book's volume rail shut and the CE book
never cleared the chart gate.

### 6.8 Market-data canary: `FINNIFTY26SEPFUT` again, 21 ERROR lines

All 21 market-data ERRORs are the same far-month contract (`ticks flowing but no 1m bar closed for
886–910 s`). Third session in a row (07-19 wave, 07-21 open gate, midday gate). Not a scalper
signal series, absent from the health endpoint's `problems` list, `status=GREEN` throughout. The
open-gate file's suggestion stands: either exclude far-month FINNIFTY from the divergence probe or
scale its threshold by liquidity. Low severity, but it is now the only recurring noise source in
the ERROR channel.

### 6.9 Method addendum → README §3.13

Promote to the standard pass: **when attributing P&L to a single rail, use the §3.5
would-have-fired set, never the shadow book's `blocking_rail` bucket.** The shadow book opens on
any composite-passing rejection regardless of how many rails failed, so its per-rail bucket
answers "what happened to trades where X was the *first* blocker", not "what would unblocking X
alone have done". On 2026-07-21 the two disagreed in sign on the same rail, same day
(+₹2,872.77 vs 6-of-6 losers). Added to README §3 as dimension **§3.13**.

## 7 Tuning candidates

Carried forward from 07-20 plus this session's new rows. **Nothing here is applied** — every row
is a PROPOSAL.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T16** | `relative-volume-floor` tag on the 18 PE scalpers | **absent** since the 07-20 21:28 republish | restore the tag and re-publish (and add a guard so a seeder-draft publish cannot drop an armed tag) | §2.1: all 1,069 volume-floor blocks at a flat 125,000; the same slugs ran banded 12,188–32,370 on 07-20; version 1.0.1 carried the tag, 1.0.3 does not; 125,000 is above **p99** of the session's 3m distribution | **STRUCTURAL (regression)** | **PROPOSED — NEW 07-21, HIGHEST PRIORITY** |
| T12 | OI quadrant / spurt reads | — | fix the failing `/options/spurt` read; separately fix the futures-OI 1-minute capture cadence | §6.2: outage did **not** repeat (0 NEUTRAL) although cadence worsened to 192/375 — refutes the cadence mechanism, leaves the spurt endpoint as the suspect | **STRUCTURAL (defect)** | **PROPOSED — carried, diagnosis revised** |
| T6 | `vwap` dot weight | 2.5 | narrow the support condition or cut the weight | **1,070/1,070 = 100% for the 3rd consecutive session**; 2,177 rows across a CE-heavy, a mixed and a PE-heavy day with zero discrimination | **STRUCTURAL** (3 sessions) | **PROPOSED — strengthened** |
| T13 | `dot-health` probe registry | 6 probes | add `futures_oi` / `underlying_oi` NEUTRAL-share probes | 07-20: canary healthy while the two heaviest OI dots were dead all session | **STRUCTURAL** | **PROPOSED** (carried) |
| **T17** | `dot-health` sampling window | newest 40 rejections | exclude rows blocked before context is gathered (`time-window` / `time-of-day-preference` / `option-side-constraint`), or sample the newest 40 **context-bearing** rows | §4: the 16:58 read called all six dots dead, including one it called alive at 09:44; the 302 context-less rows are exactly the early-rail blocks. Produced the day's only strategy-signal ERROR line | **STRUCTURAL (diagnostic)** | **PROPOSED — NEW 07-21** |
| **T18** | `breadth` dot threshold | `advances/declines > 32` (of 50) | consider 30, or scale to the constituent count | §3: declines peaked at **31** — one constituent short of supporting PE, all session; advances did reach 36. Not dead data; a threshold sitting on the edge of the operand's realised range | **REGIME** (1 session) | **PROPOSED — NEW 07-21**, collect more |
| T1 | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | 07-21 adds a **third** against: both loosened books 0-for-7, −₹9,784, and the pure would-have-fired set is 6/6 losers. Ledger now **2-for / 3-against** | **REGIME** | **PROPOSED — do NOT apply** |
| T11 | SENSEX volume-floor | fixed 125,000 | arm the relative floor for the sensex family | §2.1 confirms the 10 sensex CE slugs have never carried the tag (published 06-29/06-30). **Now entangled with T16** — do them as one change | **STRUCTURAL** | **PROPOSED — merge into T16** |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source ivRank or drop from Σw | dead every session since 07-02 | **STRUCTURAL** | **PROPOSED** (carried) |
| T3 | `iv_pair` gap threshold | 0.02 | ~0.005 | 0/1,070 today; 0% on every session logged, including two post-recalibration | **STRUCTURAL** | **PROPOSED** (carried, confirmed 4×) |
| T4 | `basis` dot | w 1.0 | — | alive again (23.2%) | — | **CLOSED — no action** |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | 0/170 again today (3rd session) | **REGIME** (3 sessions) | **PROPOSED**, collect more |
| T7 | composite threshold | 0.600 | no change | `composite-055` took **nothing** today; cumulative net −₹478.98 | — | **REJECTED** (reaffirmed) |
| T8 | shadow entry latency | p50 ~77 s | stamp entry at bar close | 7 sessions in the 73–87 s band | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T9 | strategy-coverage watchdog | none | alert on emitting/published ratio drop | 22/38 today, but §6.5 shows the shortfall is tape-driven — the watchdog needs the counter split, not the row count | **STRUCTURAL** | **PROPOSED** (needs redesign) |
| T10 | 15 stale OPEN paper positions + starved brackets | open since 07-07 | square off / age out, or subscribe the swing holdings | §6.6: bracket starvation WARNed all session, worst ~9,988 s; live intraday stops would not fire | ops | **OWNER** |
| T14 | rejection-row invariant | none | assert `blocking_margin < 0` on persist | 07-20 §6.3: 7 rows logged a block with a positive margin. **No such rows today** (all 1,372 margins negative) — the defect is intermittent, so the invariant is still worth adding | **STRUCTURAL (diagnostic)** | **PROPOSED** (carried) |
| T15 | engine boot-line durability | log only | persist `loaded/unresolved/dropped` to a table | §6.4: readable today only because no deploy landed first. Luck, not design | **STRUCTURAL (data-model)** | **PROPOSED** (carried) |

## 8 Honesty caveats

- **The champion book's +₹2,872.77 is 5 distinct entry events, not 9.** Counted once each they sum
  to roughly **−₹2,232**. The ×6 multiplier landed on the winner today; on 07-20 it landed on the
  losers. Neither number is a win rate.
- **§2.1 is proven from the registry and the rejection rows; the *intent* behind the 21:28 publish
  is not known.** It may have been a deliberate owner action whose tag loss was unintended, or an
  automated republish. This run did not attempt to find out, and changed nothing.
- §5.1's two manual rows use 2-minute snapshot granularity and borrow the sibling shadow position's
  exit; the 13:03 golden-crossover entry (768.20) is the 13:04 mark, 15.7 above the 13:02 mark.
  Both rows lose on either exit convention, so the verdict survives the approximation, but the
  point totals are approximate.
- Shadow exits replicate brackets / structural stop / square-off only — **no indicator-driven
  exits**. Every entry is stamped ~77 s after `bar_time`; bias direction unmeasured.
- **§6.1's "no hole" claim covers 09:15–14:57 only.** The absence of rows after 14:57 is consistent
  with trade windows closing but is not *proved* to be that — cumulative counters cannot be
  time-sliced.
- **§3's PE ceiling result is from a mildly-down day** (−0.14%), not a trend-down day. PE reaching
  0.7447 answers "can it pass", not "how high can it go".
- §6.3's 100% dots sit on a 98%-PE population; a one-sided tape flatters any directional dot.
- Costs are the 1-lot engine fill model (statutory + ₹20/lot). Not cost-adjusted twice.
- This run was **read-only**: SELECTs, `docker logs`, in-container health GETs. No restart, deploy,
  write or config change. No strategy knob was altered.
