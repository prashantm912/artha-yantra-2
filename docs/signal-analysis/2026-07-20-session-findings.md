# Session findings — 2026-07-20 (data date)

Analysis date: 2026-07-20 (scheduled post-market agent, ran 17:31 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,013** (10:19–15:19 IST), signals fired **1**, paper positions
opened **0**, shadow positions **28** (17 champion + 11 challenger).
Session character: **flat / rangebound.** `NIFTY26JULFUT` opened 24,265.60 and closed 24,269.00
(**+3.4 pts**), range 24,121–24,290 (169 pts). Volume was thin — 3m median 13,260 vs 26,780 on
07-17 (half). Front weekly expiry **2026-07-21 (Tue, tomorrow)**. First session with a real PE
population in months (202 PE scored rows).

---

## 0 Read this first — the session's headline

Two things happened, and they point in opposite directions:

1. **GOOD:** the 07-17 coverage alarm has largely resolved. **49 distinct strategies emitted**
   (17 on 07-17), including **31 sensex slugs** (4 on 07-17). Rejection volume is the highest on
   record (1,013).
2. **BAD — the session's top finding:** **all three OI confluence dots were dead the entire
   session** (`futures_oi` w=1.5, `underlying_oi` w=1.0, `oi_spurt` w=1.0), because both OI reads
   returned the *data-absent* sentinel on **748 of 748** scored rows. This is **code-proven NOT a
   regime effect** (§3.1). It capped the composite at **0.7181**, and 30 rows sat exactly on that
   ceiling.

The gate itself behaved correctly on the money: the would-have-fired class **lost** today
(champion shadow book −₹5,881.86), the exact mirror of 07-17.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-15 | 2026-07-17 | **2026-07-20** |
|---|---|---|---|
| rejections | 396 | 523 | **1,013** |
| distinct strategies emitting | 33 | 17 ⚠ | **49** ✅ |
| published + enabled strategies | ~63 | 63 | **44** (post-session snapshot — see §6.4) |
| signals fired | 3 | 3 | **1** |
| paper positions opened | — | 0 | **0** |
| bar-time coverage | 09:50–15:21 | 09:24–15:18 | **10:19–15:19 + a midday hole (§6.1)** |
| composite ≥ threshold rows | 144 | 210 | **230** |

**Engine load (§3.10).** Today's cold boot was read by the previous run and is recorded in
`2026-07-17-session-findings.md` §6.5: `loaded 0 published strategies (63 dropped)` at 03:05:16Z,
self-healing to **63 loaded / 0 unresolved** at 03:06:51Z — i.e. F10 Part A (#874) worked, and the
engine held 63 strategies at 08:36 IST. The registry now reads **44** published+enabled, and 49
slugs emitted. See §6.4 — the registry changed after the session, not during it.

**First-blocking-rail histogram** (1,013 rows):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **706 (69.7%)** | 14,693.5 | 84,546.9 | −69,853.4 |
| time-window | 201 (19.8%) | — | — | — |
| time-of-day-preference | 40 | — | — | — |
| rsi-band | 24 | 50.76 | — | — |
| confluence-composite | 7 | 0.680 | 0.600 | **+0.077** ⚠ (§6.3) |
| chain-unavailable | 6 | — | — | — |
| pct-price-move | 6 | −0.14 | 1.000 | −1.145 |
| two-candle / volume-pump / divergence-vol-gate | 6 each | — | — | — |
| max-oi-sr-gate / oi-cross-required | 3 / 2 | — | — | — |

`volume-floor` + `time-window` = **89.5%** of first-blocks, the same shape as every prior session.

**All-failed-rails expansion** (§3.3, fail rows only) — top 8:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| confluence-composite | FAIL_CLOSED | 748 | 0.479 | 0.600 |
| volume-floor | FAIL_CLOSED | 706 | 14,693.5 | 84,546.9 |
| rsi-band | FAIL_CLOSED | 401 | 46.74 | — |
| strike-pick | FAIL_CLOSED | 333 | — | — |
| time-window | FAIL_CLOSED | 201 | — | — |
| trend-change | FAIL_CLOSED | 113 | — | — |
| divergence-vol-gate | FAIL_CLOSED | 113 | 17,422.3 | — |
| two-candle / volume-pump | FAIL_CLOSED / FAIL_OPEN | 96 / 96 | — / 17,322.5 | — |

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 `volume-floor` — the SENSEX family is still on the un-armed fixed 125k floor, and it is above p95

Splitting the 706 volume-floor blocks by family is the finding of this section:

| family | n | avg operand | avg threshold | min thr | max thr |
|---|---|---|---|---|---|
| nifty | 287 | 10,304 | **25,488** | 12,188 | 53,138 |
| sensex | 419 | 17,700 | **125,000** | 125,000 | 125,000 |

Ground truth (§3.8), 3m rollup of `NIFTY26JULFUT` 09:15–15:30 IST — **this is the right
distribution for both families**, because per ADR-0003 the sensex variants *signal* on the NIFTY
future and only *execute* on BFO:

| bars | min | p50 | p90 | p95 | max |
|---|---|---|---|---|---|
| 125 | 2,210 | **13,260** | 62,205 | 101,920 | 256,100 |

- The **nifty** family runs the RELATIVE floor (#605, `k=1.5` × trailing-20 median). Its threshold
  band 12,188–53,138 tracks a session median of 13,260 exactly as designed. **Behaving correctly.**
- The **sensex** family is still on the **fixed 125,000** floor on all 419 rows. That sits **above
  p95 (101,920)** of the session's own signal-series distribution — README §3.8's definition of a
  *near-never*. On a thin day like today it is effectively unpassable.

This is the first session where the fixed floor is *measurable at scale*, because it is the first
session where 31 sensex slugs actually emitted. The rollup has carried "SENSEX scalpers still
un-armed (fixed 125k)" as a note since 07-07; it is now an evidenced, quantified **STRUCTURAL**
candidate (T11).

**Counterfactual — the floor SAVED money today.** Per-rail shadow attribution (champion, closed):

| blocking rail | n | wins | net ₹ | avg % |
|---|---|---|---|---|
| volume-floor | 11 | **0** | **−6,723.42** | −4.7 |
| volume-pump / oi-cross-required / divergence-vol-gate / two-candle / pct-price-move / max-oi-sr-gate | 1 each | 1 each | +140.26 each | +1.9 |

Eleven volume-floor-blocked positions, **zero winners**. This is the 07-03 signature (grind day,
floor vetoed only losers) and the opposite of 07-06/07-17. **T1 now reads 2-for / 2-against.**

### 2.2 Rails with no evidence of miscalibration

`rsi-band` (avg 46.74 on a flat day — correctly mid-band), `oi-divergence-magnitude` (6.27 vs
20.0), `pct-price-move` (−0.264 vs 1.000) all read plausibly for a rangebound session. No
order-of-magnitude gaps.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (748 scored rows):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 |
|---|---|---|---|---|---|---|
| n | 8 | 105 | 213 | 178 | 146 | 98 |
| CE | 0 | 53 | 127 | 122 | 146 | 98 |
| PE | 8 | 52 | 86 | 56 | 0 | 0 |

Max composite **0.7181** (CE); max PE **0.452**. Threshold 0.600.

**Dot support rates** (748 rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `underlying_oi` | 1.0 | 0/748 | **0.0** | ⚠ **NEWLY DEAD** — was 51.8% on 07-17 |
| `futures_oi` | 1.5 | 0/748 | **0.0** | ⚠ **NEWLY DEAD** — was 57.1% on 07-17 |
| `oi_spurt` | 1.0 | 0/748 | **0.0** | dead (was 2.5% on 07-17) |
| `volume` | 1.0 | 0/748 | 0.0 | mirrors §2.1 (thin day) |
| `iv_rank` | 0.8 | 0/748 | 0.0 | dead-data, **withheld from Σw** since #676 |
| `iv_pair` | 0.8 | 0/748 | 0.0 | dead — carried since 07-02 |
| `iv_abs_band` | 0.8 | 0/113 | 0.0 | dead |
| `iv_slope` | 0.8 | 12/113 | 10.6 | alive (was 91.8% — regime) |
| `rsi` | 1.0 | 240/748 | 32.1 | |
| `trending_cross` | 1.0 | 274/748 | 36.6 | |
| `breadth` | 1.0 | 377/748 | 50.4 | alive |
| `vix` | 1.0 | 404/748 | **54.0** | ✅ alive (0% on 07-17 was regime, as suspected) |
| `drastic_oi` | 1.0 | 458/748 | 61.2 | |
| `sentiment_slope` | 1.0 | 478/748 | 63.9 | |
| `sentiment` | 1.0 | 487/748 | 65.1 | |
| `psar` | 1.0 | 496/748 | 66.3 | |
| `basis` | 1.0 | 505/748 | **67.5** | ✅ **ALIVE** — resolves T4 (07-17's 0/359 was regime) |
| `supertrend` | 1.0 | 546/748 | 73.0 | |
| `vwma` | 1.0 | 660/748 | 88.2 | |
| `premium_skew` | 1.0 | 32/34 | 94.1 | small n |
| `vwap` | 2.5 | 748/748 | **100.0** | ⚠ **2nd consecutive session at 100%** |

### 3.1 The dead-weight cap — reconciled exactly

Per-row dot sets vary, so the cap was computed empirically rather than assumed:

| dots on row | rows | Σw | dead w | cap |
|---|---|---|---|---|
| 18 | 601 | 19.60 | 6.10 | 0.6888 |
| 19 | 34 | 20.60 | 6.10 | 0.7039 |
| 20 | 113 | 21.20 | 6.90 | 0.6745 |

The observed max (**0.7181**) exceeds all three — because `iv_rank` is **withheld from the
denominator** (P3 / #676), not merely scored zero. Recomputing with that exclusion:

- live denominator = 19.6 − 0.8 (iv_rank withheld) = **18.8**
- dead weight = volume 1.0 + underlying_oi 1.0 + oi_spurt 1.0 + futures_oi 1.5 + iv_pair 0.8 = **5.3**
- cap = 13.5 / 18.8 = **0.71809…** = **exactly the observed maximum**

**30 rows sat precisely on 0.7181** — i.e. every live dot supported and the composite still could
not go higher. Threshold 0.600 ⇒ the live dots had to clear **0.600 / 0.7181 = 83.6%**.

Compare 07-17: cap 0.816, effective bar 73.5%. **Today the composite lost ~12 points of headroom**
purely from the OI outage. This is the mechanical link between §6.2 and the funnel: not a tuning
problem, a data problem.

⚠ **Asymmetry worth recording (code-confirmed, `ConnectTheDotsScorer.java:207-214`):** a NEUTRAL
quadrant is added with `absent=false`, so it **stays in the denominator and scores zero** — it
actively drags the composite down. Only explicitly-null dots (`iv_rank`, line 154-156) are
withheld. A dead OI endpoint is therefore strictly worse than a dead IV feed.

## 4 Data health (§3.7)

| field | 2026-07-17 | **2026-07-20** | class |
|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | 8 distinct values, rich | **NEUTRAL on 748/748** | ⚠ **NEW — data-absence (§6.2)** |
| `spurtOiPct` / `spurtPricePct` | null 164/523 (31%) | **null 1,013/1,013 (100%)** | ⚠ **NEW** |
| `ivRank` | NULL 100% | NULL 100% | dead-data (carried since 07-02) |
| `fiiLongPct` | NULL 100% | NULL 100% | dead-data (carried) |
| `vix` (macro mirror) | NULL 100% | NULL 100% | known mirror gap; the **dot** is alive (54%) |
| breadth `advances`/`declines` | 0 zero-pairs | **0 zero-pairs** | HEALTHY |
| `futuresBasis` | populated | populated (20.25) | HEALTHY |

**`dot-health` endpoint** (read post-restart, `rowsInspected` 40): `breadth` alive, `vix` alive;
`iv_rank`, `dow`, `fii`, `oi_spurt_price` dead. ⚠ **The probe registry does NOT cover
`futures_oi` / `underlying_oi`** — the two dots that died today went completely undetected by the
canary. That gap is T13.

**Capture was healthy.**

| series | bars / snaps | window |
|---|---|---|
| `NIFTY26JULFUT` 1m | 390 | 09:15–15:29 |
| `SENSEX26JULFUT` 1m (BFO) | 377 | →15:29 |
| NIFTY chain snapshots (07-21 expiry) | 34,224 over 93 strikes, **0 null OI** | →15:32 |
| SENSEX chain snapshots (07-23 expiry) | 64,032 over 174 strikes, **0 null OI** | →15:32 |
| futures OI snapshots | **14,352 (208 distinct minutes)** | 09:17–15:30 |

⚠ **The one capture regression:** `futures_oi_snapshots` recorded **208 of ~375 minutes** for
`NIFTY26JULFUT` today vs **365 on 07-17** — the 1-minute cron missed **43%** of minutes, with
visible holes (e.g. 13:07 → 13:15). See §6.2.

## 5 Shadow-book outcomes

**Exit-fidelity caveat (standing):** indicator-driven exits (trend-flip / signal-exit) are NOT
replicated — premium brackets, structural stop and 15:12 square-off only. Rejections blocked before
leg resolution never shadow.

**Champion book — 17 closed, 6W/11L, −258.35 pts, −₹5,881.86 net.**

| bar (IST) | strategy | leg | entry | exit | close | pts | % | net ₹ | blocked by |
|---|---|---|---|---|---|---|---|---|---|
| 13:24 | gap-theory | NIFTY 24100CE | 167.50 | 170.70 | SQUARE_OFF | +3.20 | +1.9 | +140.26 | volume-pump |
| 13:24 | trending-oi | NIFTY 24100CE | 167.50 | 170.70 | SQUARE_OFF | +3.20 | +1.9 | +140.26 | oi-cross-required |
| 13:24 | trend-change | NIFTY 24100CE | 167.50 | 170.70 | SQUARE_OFF | +3.20 | +1.9 | +140.26 | divergence-vol-gate |
| 13:24 | market-movers | NIFTY 24100CE | 167.50 | 170.70 | SQUARE_OFF | +3.20 | +1.9 | +140.26 | pct-price-move |
| 13:24 | connect-the-dots | NIFTY 24100CE | 167.50 | 170.70 | SQUARE_OFF | +3.20 | +1.9 | +140.26 | max-oi-sr-gate |
| 13:24 | two-candle | NIFTY 24100CE | 167.50 | 170.70 | SQUARE_OFF | +3.20 | +1.9 | +140.26 | two-candle |
| 13:33 | golden-crossover | NIFTY 24100CE | 172.45 | 170.70 | SQUARE_OFF | −1.75 | −1.0 | −181.64 | volume-floor |
| 14:00 | trend-change-sensex | SENSEX 77200CE | 797.75 | 763.10 | SQUARE_OFF | −34.65 | −4.3 | −768.90 | volume-floor |
| 14:00 | connect-the-dots-sensex | SENSEX 77200CE | 797.75 | 763.10 | SQUARE_OFF | −34.65 | −4.3 | −768.90 | volume-floor |
| 14:00 | gap-theory-sensex | SENSEX 77200CE | 797.75 | 763.10 | SQUARE_OFF | −34.65 | −4.3 | −768.90 | volume-floor |
| 14:00 | golden-crossover-sensex | SENSEX 77200CE | 797.75 | 767.00 | STRUCTURAL_STOP | −30.75 | −3.9 | −691.00 | volume-floor |
| 14:00 | market-movers-sensex | SENSEX 77200CE | 797.75 | 767.00 | STRUCTURAL_STOP | −30.75 | −3.9 | −691.00 | volume-floor |
| 14:00 | two-candle-sensex | SENSEX 77200CE | 797.75 | 763.10 | SQUARE_OFF | −34.65 | −4.3 | −768.90 | volume-floor |
| 14:18 | market-movers-sensex | SENSEX 77200CE | 794.95 | 761.20 | STRUCTURAL_STOP | −33.75 | −4.3 | −750.81 | volume-floor |
| 14:18 | golden-crossover-sensex | SENSEX 77200CE | 794.95 | 761.20 | STRUCTURAL_STOP | −33.75 | −4.3 | −750.81 | volume-floor |
| 14:30 | hero-zero | NIFTY 24250CE | 77.00 | 70.00 | SQUARE_OFF | −7.00 | −9.1 | −510.86 | volume-floor |
| 14:30 | hero-zero-sensex | SENSEX 79900CE | 14.00 | 12.80 | SQUARE_OFF | −1.20 | −8.6 | −71.70 | volume-floor |

⚠ **CORRELATION CAVEAT (same as 07-17, in the opposite direction).** These are **not 17 independent
edges** — deduplicated to distinct entry events:

| event | positions | outcome |
|---|---|---|
| 13:24 NIFTY 24100CE @167.50 | 6 | **W** (+1.9%) |
| 13:33 NIFTY 24100CE @172.45 | 1 | L |
| 14:00 SENSEX 77200CE @797.75 | 6 | L |
| 14:18 SENSEX 77200CE @794.95 | 2 | L |
| 14:30 NIFTY 24250CE @77.00 | 1 | L |
| 14:30 SENSEX 79900CE @14.00 | 1 | L |

**1 of 6 distinct entry events won.** The single winning idea was multiplied ×6 and so were the two
losing ones. Judge any tune off the 6 events, not the 17 rows.

**Variant league — this session:**

| variant | closed | wins | pts | net ₹ |
|---|---|---|---|---|
| champion | 17 | 6 | −258.35 | **−5,881.86** |
| vol-off | 5 | 0 | −104.80 | −2,713.80 |
| vol-12k5 | 4 | 0 | −71.05 | −1,962.99 |
| composite-055 | 2 | 0 | −15.35 | −1,133.87 |

**Every challenger lost too, 0 wins across 11 challenger positions.** On 07-17 the loosened books
were the ones that made money; today they are the ones that lost most per position. That is
textbook regime dependence and the clearest argument yet against acting on either T1 or T7 from
single-session evidence.

**Entry latency (F8):** p50 **79.2s**, p95 **79.8s** (28 positions) — consistent with the
structural 73–87 s band on every session logged. Standing caveat: every shadow fill is stamped
~79 s after `bar_time`.

### 5.1 Manual counterfactual — the 7 would-have-fired rows (§4.2)

The §3.5 would-have-fired set is **7 rows, all blocked by `confluence-composite`** (2 slugs), and
the shadow book skipped them, so they are resolved by hand. All are `NIFTY2672124100CE`
(expiry 2026-07-21). Premium path from `options_chain_snapshots`; exit = the earlier of the
structural stop breach on `NIFTY26JULFUT` or the 15:12 square-off (15:11 LTP = **169.05**):

| bar | strategy | entry | structural stop | stop breached | exit | pts | % | verdict |
|---|---|---|---|---|---|---|---|---|
| 13:48 | golden-crossover | 182.15 | 24,240.4 | 15:00 | 164.10 | −18.05 | −9.9 | **WOULD-LOSE** |
| 13:51 | golden-crossover | 174.60 | 24,250.0 | 14:36 | ~170.00 | −4.60 | −2.6 | **WOULD-LOSE** |
| 13:51 | connect-the-dots | 174.60 | *(none)* | — | 169.05 | −5.55 | −3.2 | **WOULD-LOSE** |
| 14:00 | golden-crossover | 183.55 | 24,256.3 | 14:14 | ~186.65 | +3.10 | +1.7 | WOULD-WIN (marginal) |
| 14:00 | connect-the-dots | 183.55 | *(none)* | — | 169.05 | −14.50 | −7.9 | **WOULD-LOSE** |
| 14:06 | connect-the-dots | 152.10 | *(none)* | — | 169.05 | +16.95 | +11.1 | WOULD-WIN |
| 14:06 | golden-crossover | 152.10 | 24,268.5 | 14:07 | ~190.90 | +38.80 | +25.5 | WOULD-WIN |

**4 lose / 3 win.** The +35% take-profit (245.90) was never reached — the strike's session high was
190.90. Caveats: 2-minute snapshot granularity, no slippage/fees, stop-exit premium read at the
breach minute (the true fill would be worse), and the two 14:06 "wins" hinge on a 152.10 entry LTP
that is 20% below the 13:48 reading of the same strike — that entry mark is worth a sanity check
before anyone leans on it.

## 6 New data points / anomalies

### 6.1 ⚠ Coverage holes in BOTH sessions — and 07-17's "no eval stall" claim was wrong

Bucketing rejections by 15 minutes shows today had **two holes**: nothing before **10:19** (64 min
after the open) and nothing from **11:45 to 12:45**. Running the same query for 07-17 shows *it had
holes too* — and they are in **different places**:

| 15-min bucket | 07-17 | 07-20 |
|---|---|---|
| 09:15–10:00 | 89 | **0** |
| 10:15–10:45 | 109 | 291 |
| 11:00–11:30 | **0** | 216 |
| 11:45–12:30 | 164 | **0** |
| 12:45–13:15 | 27 | 40 |
| 13:30–14:45 | 127 | 344 |
| 15:00–15:15 | 7 | 14 |

**This corrects `2026-07-17-session-findings.md` §1**, which claimed "FULL session, no eval stall —
the first clean full-session coverage since 07-06". That conclusion came from reading only
`min(bar_time)`/`max(bar_time)`; the interior was never checked. 07-17 had a ~45-minute hole at
11:00–11:30 and another at 13:00–13:45. Neither session was clean.

**Standing method fix:** never certify "full coverage" from min/max alone — always bucket the
interior. Promoted to README §3 as dimension **§3.11**.

**Capture was healthy through both of today's holes** (64 one-minute bars in the 09:15–10:19 window,
60 in the 11:45–12:45 window), which is the starvation signature.

⚠ **Honesty limit:** per the standing rule in [[engine-liveness-is-counters-not-rejections]], an
empty `signal_rejections` window is **not by itself proof of a dead engine** — `recordRejection`
sits downstream of the chart-gate early return, so bars that die earlier in the gate write no row.
I could not check the eval counters (`ay_signal_eval_outcome_total`) for these windows because the
container was recreated at 17:31 (§6.4), resetting them. **The holes are measured; "stall" is an
inference.** What *is* certain is §6.1a below.

### 6.1a ✅ FIRST-EVER canary telemetry for the stall class

`strategy.subscriber_health_events` recorded rows today — the first ever for this class. The rollup
has carried "**0 telemetry rows across all 3 confirmed occurrences**" and "priority is no longer
'tune #634's window' but 'prove #634 CAN log a row at all'". **That question is now answered: it
can.**

| IST | kind | detail |
|---|---|---|
| 09:58:28 | `eval-stall` | signal-eval STALLED — bars arriving but not evaluated for 213s (receipt 27s old) |
| 09:59:28 | `recovery` | signal-eval caught up (lag 0s) |
| 13:23:28 | `receive-stall` | no candle received for 778s while the feed is live (ticks 0s old) — Redis candles.1m subscription dropped; re-subscribing |
| 13:23:28 | `resubscribe` | (same detail) |
| 13:24:28 | `recovery` | candle receipt recovered (28s) |

Both the #634 subscriber watchdog **and** the #679 eval-vs-receipt heartbeat fired, and the
`resubscribe` self-heal worked (recovery 60 s later). **Caveat: the timestamps do not line up with
the rejection holes** — the 09:58 stall is inside the 09:15–10:19 hole, but the 13:23 receive-stall
sits in a period that *was* producing rows, and nothing alarmed during the 11:45–12:45 hole. So the
canaries are proven functional but **not proven sufficient**; the largest hole of the day passed
unalarmed.

### 6.2 ⚠⚠ THE TOP FINDING — all three OI dots dead session-wide, and it is NOT regime

**Measurement:** `futuresQuadrant` = `underlyingQuadrant` = `NEUTRAL` on **748/748** scored rows;
`spurtOiPct`/`spurtPricePct` NULL on **1,013/1,013**. On 07-17 the same fields carried 8 distinct
quadrant combinations.

**Why this is not a flat-day artifact — code ground truth.** The classifier is *total* and has no
dead zone (`OiInterpretation.java:16-23`):

```java
boolean priceUp = priceDelta.signum() >= 0;
boolean oiUp = oiDelta >= 0;
if (priceUp) { return oiUp ? LONG_BUILDUP : SHORT_COVERING; }
return oiUp ? SHORT_BUILDUP : LONG_UNWINDING;
```

Every (priceDelta, oiDelta) pair — **including exact zeros** — maps to one of four real states.
`NEUTRAL` is not produced by this function at all. It exists only in the strategy-side mirror
(`OiQuadrant.java:10-25`), documented there as representing **"data missing"**, and it is the
declared fallback on every read failure. **A flat market cannot produce NEUTRAL. Only absent data
can.**

**Which absence?** Ruled out and confirmed:

- ❌ **Monthly-expiry suppression** (`MarketOiClient.java:288-294`, which sets exactly this
  signature — both quadrants NEUTRAL, both spurt pcts null, `futuresBasis` retained): **ruled out.**
  `isMonthlyIndexExpiryDay` requires a weekly index expiry day (`MarketCalendar.java:259-263`);
  weekly index expiry is Tuesday and 2026-07-20 is a **Monday** (NIFTY weekly is tomorrow, BSE is
  Thursday).
- ❌ **Chain capture failure:** ruled out — 0 null OI across 34k NIFTY / 64k SENSEX snapshots (§4).
- ❌ **Futures OI values dead:** ruled out — `NIFTY26JULFUT` 1m bars carry 300 distinct OI values
  spanning 14.37M–14.73M.
- ⚠️ **Live probe, post-close (read-only):**
  - `GET /api/v1/market/options/spurt` returned **HTTP 400** on every parameter shape tried
    (`underlying=NIFTY`, `underlying=NIFTY 50`, `+interval=3m`, `+expiry=…`, `index=…`).
  - `GET /api/v1/market/futures/banks?name=NIFTY%2050` returned **200 with real interpretations**
    (`SHORT_COVERING`), but `name=NIFTY` returned **422**.
- ⚠️ **Futures OI snapshot cadence degraded 43%** — 208 of ~375 minutes captured today vs 365 on
  07-17, with multi-minute holes. `FuturesMoversService.java:142-151` returns
  `interpretation = null` (→ NEUTRAL) whenever a contract has **no prior bucket**, which is exactly
  what a gappy 1-minute capture produces against a 3-minute `latestPair` read.

**Verdict:** confirmed **data-absence, not regime**, on both legs. The most probable mechanism is
the spurt endpoint erroring (underlying leg) plus prior-bucket gaps from the degraded futures-OI
capture cadence (futures leg); a `name=NIFTY` vs `name=NIFTY 50` spelling mismatch is a concrete
additional suspect for the futures leg given the 422 above. **Root cause is not fully pinned** —
today's engine logs were destroyed before they could be read (§6.4), so the actual `scalper OI read
… unavailable` debug lines are gone. **This is the highest-priority follow-up of the session (T12).**

**Impact:** 3.5 of 18.8 live weight (18.6%) forced to zero *while remaining in the denominator*,
dropping the composite cap from 0.816 to 0.7181 (§3.1).

### 6.3 ⚠ Seven rejection rows are self-contradictory — a blocking rail with a POSITIVE margin

All 7 rows whose `blocking_rail` is `confluence-composite` have `composite_score ≥
composite_threshold` and **positive** `blocking_margin`:

| bar | strategy | composite | threshold | margin |
|---|---|---|---|---|
| 13:48 | golden-crossover | 0.665 | 0.600 | +0.065 |
| 13:51 | golden-crossover | 0.718 | 0.600 | +0.118 |
| 13:51 | connect-the-dots | 0.701 | 0.600 | +0.101 |
| 14:00 | golden-crossover | 0.665 | 0.600 | +0.065 |
| 14:00 | connect-the-dots | 0.613 | 0.600 | +0.013 |
| 14:06 | connect-the-dots | 0.662 | 0.600 | +0.062 |
| 14:06 | golden-crossover | 0.718 | 0.600 | +0.118 |

A row that says "I blocked you because 0.718 < 0.600" is wrong as logged. The likely benign
explanation is the optional-gate mechanic (CLAUDE.md: *an optional activates iff score ≥
optionalMinScore AND required-only composite ≥ threshold − optionalGateMargin*) — the **required-only**
sub-composite failed while the row records the **full** composite as the operand. If so the gate
decision is correct but the diagnostic is misleading, and every §3.5 "would-have-fired" query in
this folder silently mis-attributes these rows. **Not yet verified in code.** Cheap invariant worth
adding: assert `blocking_margin < 0` on every persisted rejection (T14).

### 6.4 The container was recreated at 17:31 IST — today's engine logs are gone (again)

`ay-strategy-signal-service` was recreated **mid-analysis at 17:31:14 IST** (`RestartCount=0`, fresh
container, `ay-flyway-init` and `ay-db-create` exiting seconds earlier) — i.e. a **deploy**, almost
certainly the post-close batch-deploy that was deferred from the overnight wave. Not an incident,
and not initiated by this read-only run.

Consequences for this analysis, stated plainly:
- Today's `signal engine loaded N published` boot line and all `scalper OI read … unavailable`
  debug lines are **destroyed**. §6.2's root cause is unpinnable as a result.
- Eval counters reset, so §6.1's holes cannot be checked against
  `ay_signal_eval_outcome_total`.
- `published_enabled` now reads **44**, but the engine loaded **63** at 08:36 IST and **49** slugs
  emitted. 49 > 44 means the registry shrank *after* strategies had already emitted. **The 44 is a
  post-deploy snapshot and must not be read as the session's denominator.**

**This is the second consecutive session whose logs were destroyed before the boot line could be
read** — 07-17's coverage collapse is permanently unexplained for the same reason, and README §3.10
exists specifically to prevent it. The window between market close (15:30) and the analysis run is
when deploys land, so the agent will keep losing this race. **T15 proposes shipping the boot line to
a table instead.**

### 6.5 PE finally has a real population — and still cannot pass

202 PE scored rows (vs 0 on 07-17), maxing at **0.452** against a 0.600 threshold. The rollup's
standing question ("awaiting a clean trend-DOWN day to see whether a PE composite can pass") is
still open — today was flat, not down — but PE is now demonstrably *evaluating* at scale rather than
being silent. Note the OI outage (§6.2) suppressed PE exactly as much as CE, so today is not a fair
test of the PE ceiling.

### 6.6 One signal fired, zero paper positions

| IST | slug | side | tradeable | composite |
|---|---|---|---|---|
| 10:18 | `scalp-straddle-nifty` | BUY | NIFTY2672124200CE | 0.500 |

Composite 0.500 is **below** the 0.600 gate threshold, consistent with the straddle path carrying
its own threshold (as on 07-07/07-10/07-17). `paper_positions` opened **0** today — the fourth
consecutive session where straddle fires are advisory-only. Still worth an owner confirmation that
this is intentional rather than a silent paper-book refusal (carried from 07-17 §6.2).

### 6.7 ⚠ CARRIED — 17 paper positions still OPEN since 2026-07-16

`strategy.paper_positions`: **17 OPEN** (was 18 on 07-17 — one closed), oldest `opened_at`
2026-07-05, newest **2026-07-16 20:00:05 IST**. Three sessions later these remain unmanaged. Still
an open-position hygiene problem; owner decision (T10).

## 7 Tuning candidates

Carried forward plus this session's new rows. **Nothing here is applied** — every row is a PROPOSAL.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| T1 | `relativeVolumeMultiplier` (volume-floor `k`) | 1.5 | 1.2 (or 1.0) | **07-20 flips the ledger to 2-for / 2-against**: floor blocked 11 positions, **0 winners, −₹6,723**, and all 11 challenger positions lost. 07-17 was +₹22,345 the other way | **REGIME** (2/2) | **PROPOSED — do NOT apply.** Evidence is now genuinely split; keep both books running |
| T2 | `iv_rank` dot | w 0.8, `ivRank` NULL 100% | source ivRank or drop from Σw | dead every session since 07-02. Note #676 already withholds it from Σw, so the headroom cost is fixed; only the lost *signal* remains | **STRUCTURAL** | **PROPOSED** (carried) |
| T3 | `iv_pair` gap threshold | 0.02 | ~0.005 | 0/748 today; 0% on every session logged including two post-recalibration | **STRUCTURAL** | **PROPOSED** (carried, now confirmed 3×) |
| T4 | `basis` dot | w 1.0 | — | **RESOLVED: 505/748 = 67.5% support today.** 07-17's 0/359 was REGIME, not dead-data | — | **CLOSED — no action** |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | 0/113 again today | **REGIME** (2 sessions) | **PROPOSED**, collect more |
| T6 | `vwap` dot weight | 2.5 | narrow the support condition or cut weight | **748/748 = 100% for the 2nd consecutive session** (359/359 on 07-17). The heaviest dot (12.8% of Σw) has now discriminated nothing across 1,107 rows | **STRUCTURAL** (2 sessions) | **PROPOSED — strengthened**; rollup asked for 2 sessions, this is the 2nd |
| T7 | composite threshold | 0.600 | no change | `composite-055` took 2 extra entries today, **both losers** (−₹1,134). Consistent with 07-17 where it bought the winner plus 2 losers | — | **REJECTED** (reaffirmed) |
| T8 | shadow entry latency | p50 ~79s | stamp entry at bar close | 6 sessions in the 73–87 s band; README flags p95 > 5 s | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T9 | strategy-coverage watchdog | none | alert on emitting/published ratio drop | 07-17's 17-of-63 recovered to **49** today, so the alarm eased — but §6.4 shows the denominator itself is unstable post-deploy | **STRUCTURAL** | **PROPOSED** (downgraded from "highest priority") |
| T10 | 17 stale OPEN paper positions | open since 07-16 | square off / age out / investigate | §6.7, third session carried | ops | **OWNER** |
| T11 | SENSEX volume-floor | **fixed 125,000** | arm the relative floor (#605) for the sensex family | §2.1: 419 of 706 blocks; 125k sits **above p95 (101,920)** of the signal series' own 3m distribution — a near-never by README §3.8 | **STRUCTURAL** | **PROPOSED — NEW 07-20**, first session with enough sensex rows to measure |
| T12 | OI quadrant / spurt reads | — | fix the failing `/options/spurt` read + the futures-OI capture cadence | §6.2: NEUTRAL on 748/748 is code-proven data-absence; cap fell 0.816 → 0.718; futures OI snapshots 208/375 minutes | **STRUCTURAL (defect)** | **PROPOSED — NEW 07-20, HIGHEST PRIORITY** |
| T13 | `dot-health` probe registry | 6 probes | add `futures_oi` / `underlying_oi` quadrant-NEUTRAL-share probes | §4: the canary reported healthy while the two heaviest OI dots were dead all session | **STRUCTURAL** | **PROPOSED — NEW 07-20** |
| T14 | rejection-row invariant | none | assert `blocking_margin < 0` on persist | §6.3: 7 rows logged a block with a **positive** margin | **STRUCTURAL (diagnostic)** | **PROPOSED — NEW 07-20** |
| T15 | engine boot line durability | log only | persist `loaded/unresolved/dropped` to a table at each reload | §6.4: second consecutive session with logs destroyed by a post-close deploy before the agent could read them | **STRUCTURAL (data-model)** | **PROPOSED — NEW 07-20** |

## 8 Honesty caveats

- **The −₹5,881.86 champion session is 6 distinct entry events, not 17.** One winning idea ×6 and
  two losing ideas ×6 and ×2. Do not read 6W/17 as a win rate.
- Shadow exits replicate brackets / structural stop / square-off only — **no indicator-driven exits**.
- Every shadow entry is stamped ~79 s after `bar_time`; bias direction unmeasured.
- **§6.1's holes are measured; calling them "stalls" is an inference** — empty `signal_rejections`
  is not proof of a dead engine (chart-gate early return writes no row), and the eval counters that
  would settle it were reset by the 17:31 deploy.
- **§6.2's mechanism is partly inferred.** That NEUTRAL means data-absence is code-proven and that
  monthly-expiry suppression is excluded is calendar-proven; *which* read failed is supported by a
  post-close probe and a capture-cadence regression, **not** by the session's own logs, which were
  destroyed.
- The post-close endpoint probes ran at ~17:36 IST against a **freshly deployed** market-data
  container. They may not reflect the binary or the state that was live during the session.
- §5.1's two 14:06 "WOULD-WIN" rows rest on a 152.10 entry LTP that is ~20% below the same strike's
  13:48 mark; treat that entry as unverified.
- `published_enabled = 44` is a **post-deploy** snapshot; the session ran with 63 loaded (§6.4).
- One flat/rangebound day. §2.1's "the floor saved money" is as single-session as 07-17's opposite
  claim.
- All costs are the 1-lot engine fill model (statutory + ₹20/lot). Not cost-adjusted twice.
