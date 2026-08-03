# Session findings — 2026-08-03 (data date)

Analysis date: 2026-08-03 (scheduled post-market agent, ran ~15:50–16:40 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,272** (bar times 09:19–15:04), signals **4 ENTRY + 3 EXIT**,
paper positions opened **1** (closed, **−₹743.33**), shadow positions opened **33**
(22 champion + 11 challenger; all closed).

**2026-08-03 is a Monday with NO expiry on either exchange** (NSE weekly Tue 08-04, BSE weekly Thu
08-06). The OI bloc is fully live (0 NEUTRAL quadrants, 0 null spurt pcts, basis LIVE on all 998
context-bearing rows).

**Signal contract: `NFO:NIFTY26AUGFUT@3m`**, confirmed directly from the engine's canary log lines
(`NFO:NIFTY26AUGFUT@3m`) and every `wouldBeLeg` — unchanged since the 07-27 roll; volume comparisons
vs 07-28…07-31 are like-for-like.

**Session regime (§3.25 / G15): `chop`, efficiency 0.007 — BUT ONLY AFTER CORRECTING A POISONED
DAILY BAR (§6.1).** The stored `NIFTY 50` 1d bar reads open 24,572.70 / high **24,774.30** / low
24,515.15 / close **24,774.30** → efficiency **0.778 = "trend-up"**, and that is FALSE: high and
close are both a single bogus 15:29 tick (+200.95 pts in one minute, refuted by futures, options and
SENSEX — full evidence §6.1). Corrected from the 1m series ex-the-bogus-bar: open 24,572.70, high
**24,609.45**, low 24,515.50, close **24,573.35** → net **+0.00%**, range **0.38%**, efficiency
**0.007** ⇒ **`chop`** (the most extreme chop reading in the folder; prior min 0.035).

---

## 0 Read this first — the session's headline

1. **A bogus NIFTY 50 closing tick (24,774.30, +200.95 pts above the last real level) poisoned the
   15:29 1m TICK_AGG bar, today's 1d daily bar (high AND close), and the chain snapshots'
   `spot_price` for 15:28–15:30 — and it flips the G15 regime stamp from "trend-up 0.778" to
   "chop 0.007".** The futures (24,650 at 15:29, +13 pts on the closing burst), the 24450CE premium
   (146.95 at 15:30 ≈ intrinsic at spot 24,573, vs 324+ intrinsic if spot were 24,774) and SENSEX
   (drifting DOWN 78,677 → 78,639 in the same minutes) all refute the print. The poisoned 1d row is
   `source=KITE, fetched_at 15:45` — Kite's own REST data carried it at fetch time. **It will NOT
   self-heal** (tomorrow's cache-first reads only re-fetch the 10-min tail; bhavcopy upserts are
   DO-NOTHING) and it sits on the swing books' RS benchmark and every regime read. Repair proposal +
   guard proposal in §6.1; new README **§3.32**. No engine impact TODAY (last rejection 15:04, signal
   series is the future, which is clean).
2. **The corrected chop day hands G11 its SECOND chop observation, same sign as 07-31:** the 12-leg
   §3.5 would-have-fired set resolves **stop −127.40 pts vs hold-to-15:12 −736.75 pts** — the
   30-minute time stop better by **+609.35 pts** (§5.0). On an all-CE wf set over a flat tape both
   models LOSE; the stop just bleeds 5.8× less. G11 remains owner-decidable; evidence still favours
   keeping the stop.
3. **4 ENTRY fires, 1 funded position: −₹743.33 (STRUCTURAL_STOP at 11:58, sub 1).** The two 12:18
   fires expired unfunded — `paper ENTRY zero-sized … premium=764.05 lot=20 budget=15000
   computedLots=0` (₹15,281 > ₹15,000). #1075 cumulative: **29 fires / 9 unfunded** (was 25/7).
   All-time scalper book: 10 closes, **−₹3,109.70**.
4. **`strike-pick` failed 235 times, ALL NIFTY-rooted (14 slugs, CE and PE), on a Monday — the first
   NIFTY-rooted non-expiry instance, and it does not fit §2.2's Friday/SENSEX pattern.** Reason
   string unchanged (`no strike met the delta/premium band`), spread 09:46–14:25. Today is the day
   BEFORE the NSE weekly expiry (Tue 08-04) — a decaying front-weekly premium band is the natural
   candidate mechanism, distinct from the post-BSE-expiry fresh-chain hypothesis (which keeps its
   two conforming Fridays and zero counters). Both tracked as one WATCH row (§7).
5. **The `breadth` dot flipped to 89.8% (896/998) — the G16 step function's 10th session, still
   never in between.** `advances` ran 38–44 all day, above the `>32` line from the open. Season to
   date: 0% on six sessions, ~100% on three, 0.2% on two. A per-session bias, not a per-bar
   discriminator — G16's diagnosis unchanged.
6. **The champion shadow book lost −₹14,716.04 (22 closes, 0 wins, → 5 deduped events)** — every
   cluster negative on a day the index went nowhere and CE premiums bled; verified against the
   snapshot premium paths (§5.1, no exit-model defect). All-time: **−₹82,144.33**.
7. **Coverage 34 of 38 scalpers emitting (17 SENSEX-rooted, 14 `-pe`)** on a genuinely two-sided
   tape (102 PE scored rows). No freeze: `discipline-paused` = **0**; only sub 1 ever entered
   (§5.4) — the rejection stream's 15:04 end is time-windows closing, not a freeze or stall
   (`chart-gate-failed` advances to 15:18, gauges equal and fresh).
8. **Every liveness oracle clean:** `confluence-blocked` counter **1,272** = rejection rows exactly;
   `fired` = **4** = ENTRY rows exactly; eval grid **375/375**, failures 0; gauges
   received = evaluated = 1,726.7 s at ~16:00 (consistent with the 15:30 close);
   `subscriber_health_events` empty; **0 ERROR lines in BOTH services** (no kite circuit events);
   0 misaligned 1m candles (8th clean session).

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-29 | 2026-07-30 | 2026-07-31 | **2026-08-03** |
|---|---|---|---|---|
| rejections | 1,293 | 1,118 | 1,055 | **1,272** |
| distinct strategies emitting | 34 | 38 | 20 | **34** (17 sensex, 14 -pe) |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| signals | 12 ENTRY + 8 EXIT | 2 ENTRY + 2 EXIT | 11 ENTRY + 6 EXIT | **4 ENTRY + 3 EXIT** |
| paper positions opened | 4 | 0 | 5 (+₹69.58) | **1 (−₹743.33)** |
| bar-time coverage | 09:18–15:12 | 09:18–15:18 | 09:19–13:34 (freeze) | **09:19–15:04 (time-windows, no freeze)** |
| scored rows | 983 | 814 | 835 (all CE) | **998 (896 CE / 102 PE)** |
| composite ≥ threshold rows | 311 (31.6%) | 118 (14.5%) | 323 (38.7%) | **273 (27.4%)** |
| max composite | 0.9118 | 0.8627 | 0.8511 (= cap) | **0.8511 (cap today is 0.9574 — §3.1)** |
| regime (§3.25) | mixed 0.501 | mixed 0.434 | chop 0.171 | **chop 0.007 (corrected; poisoned bar read 0.778 — §6.1)** |

**Eval counters (actuator :8082, read ~16:00 IST; container booted 08:19 IST pre-open, so
cumulative = session totals):** `chart-gate-failed` **2,030**, `confluence-blocked` **1,272**,
`composite-below-threshold` **255**, `fired` **4**, `discipline-paused` **0**, Σ **3,561**;
`ay_signal_eval_failures_total` **0**; `ay_signal_eval_duration_seconds_count` **375** (complete
grid). `ay_signal_bar_to_emit_seconds`: 7 emissions, sum 132.2 s ⇒ mean **18.9 s** (G8's ~17 s
entry-path shape holds; sample mixes 4 entries + 3 exits).

**Engine load state.** `engine_reloads`: boot pair 08:19:47 (`0 loaded / 38 unresolved`) →
08:20:31 (`38 / 0`) — the documented F10 cold-start shape, self-healed in **44 s** pre-open — then
the 08:40 periodic reconcile (`installed=f`, 38/0/0, the normal second row). `unresolved == 0` ✅.

**First-blocking-rail histogram** (1,272 rows, **18** distinct rails):

| rail | n | share | avg margin |
|---|---|---|---|
| volume-floor | **865** | **68.0%** | −22,828.0 |
| time-window | 214 | 16.8% | — |
| time-of-day-preference | 30 | | — |
| rsi-band | 30 | | — (returns after a one-session absence; cap side, avg RSI 67.98) |
| option-side-constraint | 28 | | — |
| confluence-composite | 21 | | −0.159 |
| two-candle | 14 | | — |
| volume-pump | 14 | | — |
| pct-price-move | 14 | | −0.727 |
| divergence-vol-gate | 12 | | — |
| oi-cross-required | 10 | | — |
| psar-durability | 6 | | −0.025 |
| directional-change-gate | 4 | | — |
| rsi-5m-cap / strike-pick / hero-zero / call-put-delta-filter / max-oi-sr-gate | 2 each | | — |

`chain-unavailable` **absent** (0 rows — no kite circuit events today; 07-31's first sighting stays
a one-off so far).

**All-failed-rails expansion (§3.3)** — top rows:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | **865** | 18,163.9 | 40,991.8 |
| confluence-composite | FAIL_CLOSED | **749** | 0.454 | 0.600 |
| rsi-band | FAIL_CLOSED | 293 | 67.98 | — |
| **strike-pick** | FAIL_CLOSED | **235** | — | — |
| time-window | FAIL_CLOSED | 214 | — | — |
| divergence-vol-gate | FAIL_CLOSED | 156 | 18,420.0 | — |
| trend-change | FAIL_CLOSED | 156 | — | — |
| directional-vix-gate | FAIL_OPEN | 134 | 11.93 | — |
| volume-pump | FAIL_OPEN | 126 | 20,441.0 | — |
| pct-price-move | FAIL_OPEN | 126 | 0.275 | 1.000 |
| two-candle | FAIL_CLOSED | 126 | — | — |
| oi-cross-required | FAIL_CLOSED | 98 | 82.5 | — |
| oi-divergence-magnitude | FAIL_CLOSED | 98 | **26.45** | 20.00 |
| open-high-low | FAIL_CLOSED | 90 | — | — |
| rising-volume | FAIL_CLOSED | 72 | 16,293.3 | — |
| rsi-5m-cap | FAIL_CLOSED | 60 | 77.83 | — |
| psar-durability | FAIL_OPEN | 58 | 0.034 | 0.050 |
| call-put-delta-filter | FAIL_OPEN | 20 | 28.86 | 50.00 |
| constituent-gate | FAIL_OPEN | 18 | 0.260 | — |
| rsi-cooloff | FAIL_CLOSED | 16 | 79.76 | — |

⚠ Observation, not an alarm: `oi-divergence-magnitude`'s failing rows average operand **26.45 vs
threshold 20.00** — above the magnitude line (07-31 read 13.78, below it). The rail is a compound
test (magnitude AND direction agreement), so a fail with the magnitude cleared is legitimate; noted
so a later sign-invariant read (G17) has the baseline.

### 1.1 Interior coverage (§3.11) — full session, no holes

24 populated 15-min buckets 09:15–15:00 (n = 8/10/80/80/90/90/90/90/90/60/67/60/73/22/30/60/26/32/
80/30/40/12/48/4). Thin buckets (12:30 n=22, 14:30 n=12, 15:00 n=4) are chart-gate/time-window
composition, not holes — `chart-gate-failed` advances to 15:18 in the eval buckets and both gauges
are fresh. `subscriber_health_events`: **0 rows**.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 G10 / T27 — opening-surge mechanism, FOURTH reproduction, sharpest peak yet

**Registry (§3.14):** 38/38 armed `relative-volume-floor`, published 2026-07-28 (unchanged), **0
flat floors** (`blocking_threshold = 125000`: 0 rows). Observed threshold range **9,701.25 –
93,697.50**.

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned only (§3.15):**

| bars | min | p50 | p90 | p99 | max | bars ≥125,000 |
|---|---|---|---|---|---|---|
| 125 | 2,535 | **13,455** | 54,145 | 126,880 | **272,415** | **2** |

Opening threshold peak **93,697.50 = p98.4** of the session's own distribution (2 of 125 bars
clear it) — the sharpest peak alongside 07-29's 133,185 = p98.4 (07-30: p92; 07-31: p91).
**Pre-11:00 share of `volume-floor` blocks: 356/865 = 41.2%** (07-29: 43%, 07-30: 28.9%, 07-31:
40.1%). Fourth consecutive reproduction. **Arming recommendation unchanged (NO)** — G10's own
counterfactual measured a loss, and §5.0 adds a 7th consecutive no-pay for loosening the floor.

### 2.2 `strike-pick` 235 fails, ALL NIFTY-rooted, on a Monday — a NEW instance class

| session | day | expiry | NIFTY-rooted fails | SENSEX-rooted fails |
|---|---|---|---|---|
| 2026-07-24 | Fri | none | 0 | 550 |
| 2026-07-28 | Tue | NSE monthly | 534 | 0 |
| 2026-07-29 | Wed | none | 0 | 0 |
| 2026-07-30 | Thu | BSE monthly | 0 | 405 |
| 2026-07-31 | Fri | none | 0 | 374 |
| **2026-08-03** | **Mon** | **none** | **235 (14 slugs, CE+PE)** | **0** |

First NIFTY-rooted fails outside an NSE expiry day. Reason unchanged (`no strike met the
delta/premium band`), spread across the whole session (09:46–14:25), heaviest on the
plain `-nifty` slugs. Today is the eve of the NSE WEEKLY expiry (Tue 08-04): the front-weekly
NIFTY chain (the 08-04 series the picker resolves into — every funded/would-be leg today is
`NIFTY26804*`) carries decayed premiums, the natural way a delta/premium band empties. The 07-24/
07-31 Friday-SENSEX instances (fresh post-Thu-expiry chain) remain a separate candidate mechanism
with two conforming instances. **Both stay one WATCH row (§7): the gate declining an unusable chain
is correct behaviour; no tune.** Discriminating observation to collect: does NIFTY repeat on the
NEXT Monday / expiry eve (08-10 or 08-11), and does SENSEX repeat on Friday 08-07?

### 2.3 Rails with no evidence of miscalibration

`pct-price-move` (0.275 vs 1.000), `volume-pump`, `psar-durability` (0.034 vs 0.050),
`call-put-delta-filter` (28.86 vs 50.00), `constituent-gate`, `rsi-cooloff` (79.8 — a genuine hot
tape), `rsi-band`/`rsi-5m-cap` (cap-side fails on an up-drifting morning) all read plausibly.
`confluence-composite` first-blocks are near-misses (avg margin −0.159, 21 rows).

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (998 scored rows — 896 CE / 102 PE, the first PE mass since 07-30):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|---|
| n | 18 | 105 | 164 | 310 | 225 | 130 | 32 | 14 |
| PE | 0 | 10 | 22 | 46 | 24 | 0 | 0 | 0 |

273 rows (27.4%) ≥ 0.600; max **0.8511**. PE rows top out in the 0.6 bucket — the composite is
CE-leaning on this tape but PE rows did pass.

**Dot support rates:**

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_pair` | 0.8 | 0/998 | 0.0 | structurally impossible (G13) — 11th zero session; in Σw |
| `iv_rank` | 0.8 | 0/998 | 0.0 | dead-data, withheld from Σw — 14th session |
| `oi_spurt` | 1.0 | 48/998 | **4.8** | ⚠ recovered from 07-31's 0% — the conjunct-starve was REGIME as filed; watch closes (§7) |
| `vix` | 1.0 | 126/998 | 12.6 | direction dot un-saturated on a two-sided tape (100% on 07-31's all-CE) |
| `volume` | 1.0 | 133/998 | 13.3 | ⚠ down from 31.4%/29.5% — but 123 of 125 bars sat under p90; low support on a thin tape is the floor working, not G6 regressing |
| `trending_cross` | 1.0 | 168/998 | 16.8 | alive (50.3% on 07-31 — day-dependent) |
| `vwap` | 2.5 | 189/998 | 18.9 | alive |
| `iv_slope` | 0.8 | 34/149 | 22.8 | alive |
| `sentiment_slope` | 1.0 | 492/998 | 49.3 | |
| `underlying_oi` | 1.0 | 537/998 | 53.8 | ✅ live |
| `futures_oi` | 1.5 | 613/998 | 61.4 | ✅ live |
| `sentiment` | 1.0 | 666/998 | 66.7 | |
| `rsi` | 1.0 | 719/998 | 72.0 | |
| `psar` | 1.0 | 781/998 | 78.3 | |
| `basis` | 1.0 | 896/998 | 89.8 | |
| **`breadth`** | 1.0 | 896/998 | **89.8** | **G16 10th session, never in between**: advances 38–44, above `>32` from the open (0.2% on 07-31, 0% on 07-30) |
| `vwma` | 1.0 | 899/998 | 90.1 | |
| `supertrend` | 1.0 | 988/998 | 99.0 | |
| `iv_abs_band` | 0.8 | 149/149 | 100.0 | frozen input (G12) — 7th session; today's stamp **0.107578**, inside 0.10–0.12 |
| **`premium_skew`** | — | 10/10 | 100.0 | **first appearance in the folder** — hero-zero family only, 5 rows/root, all ≥14:52 (the family's late window); n=10, no read yet |

### 3.1 Dead-weight cap — NOT the binding term today

18-dot majority roster: Σw 19.60, denominator 18.80 (`iv_rank` withheld); dead-in-denominator =
`iv_pair` 0.8 only (both `oi_spurt` and `breadth` are alive today) ⇒ cap **18.00/18.80 = 0.9574**.
Observed max **0.8511** — numerically equal to 07-31's cap by coincidence (a best row missing
`oi_spurt` + one other 1.0 dot = 16.0/18.80), but today the market side, not the roster, set the
ceiling.

## 4 Data health (§3.7)

| field | 2026-07-30 | 2026-07-31 | **2026-08-03** | class |
|---|---|---|---|---|
| `futuresQuadrant`/`underlyingQuadrant` NEUTRAL | 0/814 | 0/835 | **0/998** | ✅ live |
| `spurtOiPct`/`spurtPricePct` NULL | 0 | 0 | **0/998** (82 distinct spurtOi) | ✅ live |
| `futuresBasis` | LIVE | LIVE | **998/998 LIVE** | ✅ |
| `advances`/`declines` | 23–32 | 21–35 | **38–44, 0 nulls, 0 zero-pairs** | ⚠ dot 89.8% — G16 step |
| `fiiLongPct` | 0 nulls (9.62) | 0 nulls (10.38) | **0 nulls (11.12)** | ✅ daily stamp moved |
| `atmIv` | 1 distinct | 1 distinct (0.114471) | **1 distinct (0.107578)** | frozen — G12, labelled |
| `ivRank` | NULL 100% | NULL 100% | **NULL 998/998** | dead-data (since 07-02) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 998/998** | by design (un-armed) |

**The 274 context-less rows reconcile exactly**: 214 `time-window` + 30 `time-of-day-preference` +
28 `option-side-constraint` + 2 `strike-pick` first-blocks = **274** = 1,272 − 998.

**Capture (minute-aligned only):** `NIFTY26AUGFUT` 1m **375/375**, last bar 15:29; **0 misaligned
1m rows session-wide (T19 quiet an 8th session)**; `futures_oi_snapshots` 25,806 rows / **374 of
375** minutes; `tickedTokens` **69** (the contract-set plateau); market-data canary **GREEN, 0
problems**; kite session validated today. **0 ERROR lines in both services.**
⚠️ **EXCEPT the §6.1 poisoned close** — which sits in `NIFTY 50` 1m/1d and chain `spot_price`,
outside every existing canary's probe set.

**`dot-health` canary at 16:04 IST** (200 scanned / 40 context-bearing): dead set = the standing
pair `iv_rank` + `dow`; `fii` (11.12) and `iv_abs_band` (0.107578) both `frozen BY DESIGN — EOD
daily operand` on 18 bars; `breadth`/`oi_spurt_price`/`vix`/`futures_oi`/`underlying_oi` alive;
`neverCrossing` false on all. **Nothing newly dead.** Both EOD stamps moved day-over-day
(10.38→11.12, 0.114471→0.107578) — per-day step, as designed.

**T23 / G9 (PartialBucketCanary):** **2 WARNs**, both `NFO:NIFTY26AUGFUT@3m`, both at the open —
09:15 bucket shortfall **−2,405 (37 lots)** and 09:21 bucket **+780 (12 lots)**, both exact ×65,
both UNPAIRED — plus **1 suppressed straddle pair** (09:27/09:30, ±3,055, `straddle_total` = 1).
Identical magnitudes to 07-31's two WARNs. Count at floor a third session; G9 stays open.

## 5 Shadow-book outcomes + the counterfactual

### 5.0 README-§4.2 counterfactual — 12 legs; the stop model bleeds 5.8× less than holding

The §3.5 query (volume-floor sole blocker, composite passed) returns **23 rows collapsing to 12
distinct `(bar, leg)` events** (§3.24 dedupe), all CE, 10:48–12:27. Model (a HARNESS choice per
§3.16's 2026-08-02 correction, not "the armed fleet stop"): uniform **+35% TP / −25% SL / 30-min
time stop / 15:12 square-off**, priced off `options_chain_snapshots` (~2-min granularity today);
hold-to-15:12 prices the identical leg with no stop.

| bar | leg | entry | stop-model exit | stop pts | hold-to-15:12 pts |
|---|---|---|---|---|---|
| 10:48 | NIFTY2680424450CE | 155.65 | time 11:18 | +8.15 | −12.15 |
| 10:48 | SENSEX2680678200CE | 725.00 | time 11:18 | +26.25 | −56.85 |
| 11:03 | NIFTY2680424450CE | 166.10 | time 11:34 | −9.35 | −22.60 |
| 11:03 | SENSEX2680678200CE | 753.25 | time 11:34 | −28.25 | −85.10 |
| 11:06 | NIFTY2680424450CE | 166.55 | time 11:36 | −8.30 | −23.05 |
| 11:06 | SENSEX2680678200CE | 757.70 | time 11:36 | −27.50 | −89.55 |
| 11:09 | NIFTY2680424450CE | 162.30 | time 11:40 | −1.50 | −18.80 |
| 11:09 | SENSEX2680678200CE | 746.70 | time 11:40 | +2.30 | −78.55 |
| 11:42 | SENSEX2680678200CE | 746.30 | time 12:12 | +2.90 | −78.15 |
| 11:51 | SENSEX2680678200CE | 753.10 | time 12:22 | +7.80 | −84.95 |
| 12:15 | SENSEX2680678200CE | 761.25 | time 12:46 | −23.85 | −93.10 |
| 12:27 | SENSEX2680678200CE | 762.05 | time 12:58 | −76.05 | −93.90 |
| **total (12 legs)** | | | **5W/7L** | **−127.40** | **−736.75 (0W/12L)** |

**Zero TP and zero SL touches — every leg resolved at the time stop.** Both models LOSE: the
would-have-fired set was junk today (CE longs on a going-nowhere tape), and the floor vetoing it
was correct. **T1's 7th consecutive no-pay.** For G11: the stop model beats holding by **+609.35
pts** on the day's dominant set — the second chop-day observation, same sign as 07-31 (§7 T29 row).
A ~1% round-trip cost (≈2 pts NIFTY / ≈7.5 pts SENSEX legs ≈ 70 pts total) deepens both totals and
changes neither sign.

### 5.1 Shadow book — champion −₹14,716.04 (5 events, 0 wins), VERIFIED against snapshots

| variant | closed | W | pts | net ₹ |
|---|---|---|---|---|
| **champion** | **22** | **0** | **−429.75** | **−14,716.04** |
| vol-off | 5 | 2 | −85.60 | −2,639.49 |
| vol-12k5 | 5 | 2 | −85.60 | −2,639.49 |
| composite-055 | 1 | 0 | −17.10 | −415.92 |

Champion dedupe (§3.24): 22 rows = **5 distinct `(bar, leg, entry)` events** — 09:54
SENSEX78100CE ×6 (−₹2,987.16), 10:06 SENSEX78100CE (−₹941.06), 10:15 NIFTY24450CE ×8 (−₹7,108.40,
mixed SQUARE_OFF/STRUCTURAL_STOP), 10:15 SENSEX78200CE (−₹1,183.90), 12:48 **SENSEX79300PE** ×6
(−₹2,495.52 — the first PE cluster since 07-30). **Exit-fidelity spot-check against the snapshot
paths confirms the book, not a defect:** square-off pts at 15:12 = −12.55 (78100CE from 759.95),
−10.75 (24450CE from 154.25), −65.30 (78200CE from 733.45), −16.10 (79300PE from 731.55) — every
entry was near its leg's session high-water and premiums bled all day; the index chop (§6.1
corrected read) never paid the longs. `vol-off` = `vol-12k5` today (identical 5 rows — the floor
they remove/lower was not those rows' blocker).

**All-time league:** champion **308 closed / 119 net-wins / −387.40 pts / −₹82,144.33**; vol-off
−₹29,165.42; vol-12k5 −₹21,557.20 (the `vol-12k5 > vol-off` per-close ordering survives a 9th
session); composite-055 −₹10,414.99 (1 trade today, lost). Shadow entry latency p50 **1:20.6** /
p95 **1:23.4** (n=33) — structurally unchanged (G8).

### 5.3 Paper book — one funded fire, one stop

| id | leg | entry | qty | opened | closed | reason | net ₹ | sub |
|---|---|---|---|---|---|---|---|---|
| 55 | SENSEX2680678200CE | 749.80 | 40 | 11:55 | 11:58 | STRUCTURAL_STOP | **−743.33** | 1 |

qty 40 = the two 11:54 fires (golden-crossover + connect-the-dots sensex-niftyoi) averaging in —
`paper_events` records 2 OPENED. The golden-crossover EXIT signal fired 11:57 (STRUCTURAL_STOP,
future 24,670 vs entry 24,678); connect-the-dots' trailing exit followed at 12:36 (by then the
position was already closed). The two 12:18 fires (premium 764.05) were **unfunded** (§0.3).
All-time scalper book: **10 closes, −₹3,109.70**.

### 5.4 §3.30 — sub-account freeze telemetry: NOT a freeze day

| sub | entries (OPENED events) | last entry | day PnL | frozen by | at |
|---|---|---|---|---|---|
| 1 | 2 | 11:55 | −743.33 | first-loss | 11:58 |
| 2–5 | 0 | — | — | — (never entered — no funded fire reached them) | — |

`discipline-paused` counter **0** all session (sub rotation never exhausted — subs 2–5 stayed
available; the engine simply produced no further funded fires). §3.30's flag condition (≥3 of 5
stopped before 14:30) **does not apply** — 4 of 5 never started. Trend row: 07-29 3/5 by 13:40 →
07-31 5/5 by 13:34 → **08-03 1/5 entered, 1 first-loss freeze**.

## 6 New data points / anomalies

### 6.1 ⚠️⚠️ NEW — a bogus NIFTY 50 closing tick poisoned three surfaces, incl. the daily bar

**The evidence chain (each step measured, §6-style SQL in README §3.32):**

1. `NIFTY 50` 1m closes are FROZEN at 24,573.35 from 15:20 through 15:28 (an index should tick
   ~continuously), then the **15:29 bar (source `TICK_AGG`) prints open 24,573.35 / high 24,774.30
   / close 24,774.30** — +200.95 pts (+0.82%) inside one minute.
2. Session high excluding that bar: **24,609.45** — the "high" 24,774.30 exists ONLY in that bar.
3. **Three independent markets refute the print:** `NIFTY26AUGFUT` ticked freely through the same
   minutes and closed 24,650 (+13 on its closing burst — a real +200 index move puts the future
   ~24,790, not 124 pts UNDER spot); the 08-04 24450CE closed at 146.95 ≈ intrinsic-plus-time at
   spot ~24,573 (at 24,774 its intrinsic alone is 324); SENSEX (live, unfrozen) drifted DOWN
   78,677 → 78,639 across the same minutes. NIFTY +0.82% with SENSEX flat-down is not a market.
4. **Poisoned surfaces:** (a) the 15:29 1m `TICK_AGG` bar; (b) **today's 1d daily bar** — `source
   KITE, fetched_at 15:45:00` carries high = close = 24,774.30, i.e. Kite's own REST data had the
   print at fetch time; (c) `options_chain_snapshots.spot_price` = 24,774.30 on the 15:28–15:30
   captures. SENSEX surfaces are clean.
5. **No engine impact today**: last rejection 15:04, last eval 15:18, and the scalper signal series
   is the future (clean). The paper book was closed by 11:58.

**Why it matters beyond today:** the 1d bar feeds the G15 regime stamp (it flipped today's label
trend↔chop outright), the swing books' RS-rank benchmark (`NIFTY 50` 1d is the RS universe
benchmark), and any backtest window covering 2026-08-03. **It will not self-heal:** tomorrow's
cache-first reads re-fetch only the 10-min trailing tail (the bucket is present and past, so
`GapDetector` skips it) and bhavcopy upserts are DO-NOTHING. A value-replacing repair needs an
authoritative re-fetch or a hand UPDATE — proposed below, owner/architect action (this run is
read-only).

**Proposals (T31, §7):** (a) REPAIR — after Kite's data settles (they normally correct index
history within hours), re-fetch `NIFTY 50` 1m for 15:25–15:30 and the 1d bar for 2026-08-03 via an
authoritative fetch path (or a hand UPDATE from verified values) and re-verify
`high=24,609.45-ish / close≈24,573`; then re-stamp today's regime row if the corrected bar differs
from §0's ex-bogus computation. (b) GUARD — an index tick that moves >N% against a LIVE futures
basis (basis jumped −124 pts here; normal intraday range ±~80) is rejectable/quarantinable at
capture time; the chain snapshots already carry a `quarantined` flag. Build-shaped → needs a
ledger group-G row (added as **G18** in this PR).

### 6.2 §3.29 — unexercised-path audit

Fired vocabulary since 07-01 (paper): TRAILING_STOP 11, TIME_STOP 5, STRUCTURAL_STOP 5 (+1 today),
STOP_LOSS 5 (+2 — both the 07-31-evening SATIN swing closes in minervini + manas-arora, landed
after that day's report ran), MANUAL 2. Armed paths vs fires:

| armed path | strategies | fired? | classification |
|---|---|---|---|
| trailing_stop (indicator) | 42 | ✅ 11 | exercised |
| time_stop | 38 | ✅ 5 | exercised |
| stop_loss premium_pct | 30 | ✅ | exercised (basis-collapse caveat stands) |
| stop_loss index_points | 8 | ✅ (5 STRUCTURAL_STOP) | exercised |
| stop_loss percent | 4 | — | INDETERMINATE (indistinguishable in `close_reason` from premium_pct) |
| stop_loss atr_multiple | 2 | — | INDETERMINATE (standing pair) |
| trailing_stop atr_multiple | 2 | — | INDETERMINATE (standing pair) |
| **take_profit premium_pct** | **36** | **✗ 0 since 07-01** | **unreachable-this-regime** — today's funded position peaked ≈ +2% vs the +35% trigger; zero §5.0 legs touched TP either |
| **signal_exit** | **38** | **✗ 0** | **shadowed** — today's one real exit path fired the structural stop at bar 1 |
| **square_off** | **2** | **✗ 0** | unreachable (btst family has never fired) |
| **tag `oi-confluence-exit`** | **8** | **✗ 0 CONFLUENCE_FLIP** | **unexercised** (T24 carry) |

Day's delta: STRUCTURAL_STOP +1, STOP_LOSS +2 (swing); **the never-fired set is unchanged.**

### 6.3 Mechanical pre-checks

`tools/ledger-consistency-check.py`: **11 REVIEW lines, all false positives, dispositioned as
follows.** 5×[A] "OPEN and CLOSED in different places" — task_37ee83e0 + task_79092520 sit in the
ledger's 2026-07-17 "Open chips:" snapshot line, which ALREADY carries the next-line annotation
"THAT LIST IS A 2026-07-17 SNAPSHOT AND IS NOW MOSTLY DISCHARGED" (the checker cannot see it);
task_53ce441b + task_fb8914fc are the 08-02 closeout's "Two chips remain OPEN" line, which is
CURRENT AND TRUE (their other hits are rows describing, not closing, them); task_a2ae20ed's second
hit is the drift-rule's own self-referential example (stands, as on 07-31). 5×[B] keyword matches
(map-return / cross-context / G8 / G14 / G12) — reference-not-claim, false by construction. 1×[C]
"T18 promotion" — the G16 row exists; same FP as 07-31. **No ledger edits required; ledger
consistent in substance.**

`tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH), 0
DB-only, 0 YAML-only.** The 2 STALE-PUBLISH: `minervini-cheat-3c` and `minervini-primary-base`,
published 1.0.1 vs 1.0.2 drafts of 2026-08-02 (the minervini-live-plane-split docs wave). Diffed
directly in the DB: **both drafts change ONLY `name` + `description`** (honest-labelling renames —
"Minervini VCP Early-Pivot (3-C)" / "Minervini 52-Week Breakout (Primary Base proxy)"; zero
tags/exit_rules/params/entry_rules change). Republish proposal in §7 — GAINS honest naming, LOSES
nothing. **Not republished by this run** (doctrine: never publish).

## 7 Tuning candidates

Carried forward from 07-31 plus this session's movement. **Nothing here is applied.** Ledger rows
in [`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
§0 group G are the authoritative status; this table is the evidence.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T31 (NEW)** | `NIFTY 50` poisoned 2026-08-03 close (1m 15:29 + 1d bar + chain spot 15:28–15:30) | high/close = bogus 24,774.30 | (a) repair by authoritative re-fetch or hand UPDATE once Kite corrects; (b) basis-sanity guard on index ticks at capture | §6.1 — three-market refutation; sits on the RS benchmark + regime stamps; will NOT self-heal | **DATA-INTEGRITY (build) — NEW ledger row G18** | **PROPOSED — owner/architect repair** |
| **T29** | scalper `time_stop` (`max_bars`, fleet 5 horizons) | armed | owner decision (G11) | **Second chop-day observation, same sign:** §5.0 stop −127.40 vs hold −736.75 (+609.35 for the stop) on the 12-leg wf set. Chop ledger: 07-31 stop-wins-large, 08-03 stop-bleeds-5.8×-less | EXIT-BAND (owner) — ledger G11 | **OWNER-DECIDABLE (carried); evidence still favours keeping the stop** |
| **T30** | `breadth` dot threshold (`>32`) | fixed | relative/percentile, or reweight | 89.8% today (advances 38–44, above the line from the open). **10 sessions: 0% on six, ~100% on three, 0.2% on two — never in between** | STRUCTURAL — ledger G16 | **OPEN** |
| **T27** | relative-floor window | 1.5×median, session-start | time-of-day profile (built, default-OFF) | §2.1: FOURTH reproduction — peak 93,697.50 = **p98.4**, pre-11:00 share **41.2%** | STRUCTURAL — ledger G10 | **OPEN; arming rec unchanged (NO)** |
| **T28** | `atmIv` freshness / `iv_abs_band` | frozen daily stamp | intraday operand | 7th one-distinct-value session (0.107578); dot 149/149 free 0.8 | STRUCTURAL — ledger G12 | **OPEN, redefinition half** |
| **T3** | `iv_pair` | 0.02 gap | drop or redefine | 0/998 — 11th zero session | STRUCTURAL — ledger G13 | **OPEN (owner)** |
| **T23** | partial-bucket tolerance 650 | 2 WARNs (37/12 lots) + 1 straddle | scale with bar size (dormant arm stays 0) | §4: identical magnitudes to 07-31, floor a third session | STRUCTURAL — ledger G9 | **OPEN** |
| **T1** | `relativeVolumeMultiplier` 1.5 | — | — | §5.0: **7th consecutive no-pay** — the sole-blocker wf set is 5W/7L −127.40 even on the better model | REJECTED | **REJECTED — reconfirmed** |
| **T7** | composite threshold 0.600 | — | — | `composite-055` took 1 row today and lost (−₹415.92); all-time −₹10,414.99 | REJECTED | **REJECTED — carried** |
| **watch** | `strike-pick` chain-quality instances | — | none (correct behaviour) | §2.2: NEW first NIFTY-rooted non-expiry instance (Mon, NSE-weekly eve, 235 fails); Friday-SENSEX fresh-chain hypothesis keeps 2 conforming / 0 counter | REGIME (two candidate mechanisms) | **WATCH — next discriminators: Mon/Tue 08-10/11 (NIFTY), Fri 08-07 (SENSEX)** |
| **watch → closed** | `oi_spurt` quadrant conjunct | floors (15, 3) | none | recovered to 4.8% today after 07-31's 0% — regime as filed, not a defect | REGIME | **WATCH CLOSED (1-session anomaly)** |
| **NEW** | minervini republish (config-drift) | published 1.0.1 ×2 | republish 1.0.2 drafts | §6.3: name+description-only diffs (honest labelling), LOSES nothing | ops (owner) | **PROPOSED — owner OK to republish** |
| T10 | stale OPEN paper positions | **17** OPEN (11 minervini, 6 manas) | square off / age out | unchanged | ops | **OWNER — chronic** |
| T8/T26 | latency | shadow p50 1:20.6 (n=33); emit mean 18.9 s (n=7) | stamp at bar close / measure | structurally unchanged | STRUCTURAL — ledger G8 | OPEN (data) |
| T2 | `iv_rank` dot | NULL 100% (14th session) | — | ledger row E8 DONE; not available work (07-31 pointer stands) | STRUCTURAL | carried, not open |
| T16/T12/T19/T22/T24/T21/T6/T15/T17+T13/T20/T25 | — | — | — | §2.1 (38/38 armed, 0 flat); §4 (374/375 OI; 0 misaligned ×8); §5.3 (book live); reloads verified again | — | ✅ remain CLOSED |

**Group G movement this session: NEW G18 (bogus-tick repair + guard); G11 second chop corroboration
(stop-favouring, owner-decidable unchanged); G16 10th never-in-between session; G10 fourth
reproduction (rec NO); G12/G13/G9/G8 unchanged; T1 seventh rejection.**

## 8 Honesty caveats

- **The regime stamp is computed from a CORRECTED bar** (§0/§6.1) — the stored 1d row says
  trend-up 0.778 and is wrong. If Kite's later-corrected official close differs materially from
  24,573.35 (the last real 1m close), today's regime row needs a dated amendment; the chop label is
  robust to any close inside the real session range (max possible efficiency with close ≤ 24,609.45
  is ~0.39, still sub-trend).
- **§5.0's model omits costs, `signal_exit` and the trailing SuperTrend**; snapshot granularity
  today is ~2 min, so 1-minute bracket touches can be missed. The stop-vs-hold GAP (+609.35) is
  robust to costs (both totals deepen equally per leg).
- The §5.0 set is volume-floor-sole-blocker only; the 2 `strike-pick` first-block rows carry no
  `wouldBeLeg` and are structurally unresolvable.
- Champion's −₹14,716.04 is **5 independent events, not 22**; the all-time league inherits fan-out
  inflation as always.
- `premium_skew`'s 100% is n=10 from one family's late window — no conclusion drawn.
- §5.4's "not a freeze day" is about the discipline path; the REASON subs 2–5 never entered is
  upstream (only 4 fires, 2 unfunded) — capacity telemetry continues.
- This run was **read-only against the live stack**: SELECTs, `docker logs`, `docker inspect`,
  in-container actuator/health GETs. No restart, deploy, write, or config change; no strategy knob
  altered; nothing republished. Docs edits in this PR: findings + rollup + README §3.32 + ledger
  G18 row.
