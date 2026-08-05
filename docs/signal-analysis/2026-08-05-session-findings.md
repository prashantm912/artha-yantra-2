# Session findings — 2026-08-05 (data date)

Analysis date: 2026-08-05 (scheduled post-market agent, ran ~16:00–16:45 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,318** (bar times 09:18–15:18, generated 09:19–15:19), signals
**2 ENTRY + 2 EXIT**, paper positions opened **1** / closed **1** (−₹2,583.73), shadow positions
**62** opened (37 champion + 25 challenger; all closed).

**2026-08-05 is a Wednesday — no expiry on either exchange** (NSE weekly was yesterday 08-04; BSE
weekly is tomorrow Thu 08-06). S24 correctly not engaged; OI bloc fully live.

**Signal contract: `NFO:NIFTY26AUGFUT@3m`** — named directly by the rail log lines (container up
since 08:24, logs intact all session). Unchanged since the 07-27 roll.

**Session regime (§3.25 / §3.33): `mixed`, efficiency 0.551 on the CONTINUOUS session** — 1d open
24,669.20 → continuous close 24,570.20 (net **−0.40%**, range 0.73%). The OFFICIAL CAS close is
24,624.65 (15:28 print, +54.45 over the frozen level — the smallest auction jump of the three CAS
sessions: +200.95 / +151.45 / +54.45), which reads efficiency **0.248 = chop**. Doctrine stamps the
continuous read: **mixed** — this is NOT the G11 chop day.

---

## 0 Read this first — the session's headline

1. **The engine FIRED and the trade LOST: 2 ENTRY signals at the 11:03 bar (both connect-the-dots
   variants, CE, scorer composite 0.9857, gate dot-aggregate 0.6176 — just over the 0.600 line),
   one funded paper position (sub-account 1, `SENSEX2680678400CE` ×20 @647.75), closed 11:31 by
   TRAILING_STOP at 521.95 = −125.80 pts = −₹2,583.73.** The NIFTY leg went UNFUNDED — the #1075
   zero-size class again: `premium=295.5 × lot 65 = ₹19,207.50 > ₹15,000 budget → computedLots=0`
   (**cumulative 31 fires / 10 unfunded**). First TRAILING_STOP close since 07-31 (now 12 all-time).
2. **⚠️ NEW DEFECT-CLASS FINDING — the paper book's heat-cap gate was INERT on the session's only
   entry.** At 11:04 `PaperMarginClient` (→ market-data `POST /api/v1/market/margin`) failed with
   `Error while extracting response ... content type [application/octet-stream]` and `RiskService`
   logged `book 'scalper' heat-cap enforcement ON but heat unassessable — gate inert this entry`
   (2 occurrences: entry path + notifier). The F9 risk layer's margin-heat check silently
   no-opped on a live money-path entry — fail-open by design, but the *reason* is a wire/content-type
   defect, not a margin-service outage. Promoted to README **§3.34** (check heat-gate evaluability
   on every funded fire) and proposed as a bug-class investigation (§7 NEW).
3. **`strike-pick`: ZERO fails today** — first zero since the 08-03/08-04 NIFTY run (235 → 604 →
   **0**). A non-expiry Wednesday with a healthy chain on both roots supports the
   expiry-eve/day-of mechanism over any persistent CAS-era chain degradation. Watch discriminators
   stand (BSE weekly TOMORROW 08-06 — expect SENSEX-rooted fails if the mechanism holds; Mon/Tue
   08-10/11 NIFTY).
4. **G16/T30 news: `breadth` produced its FIRST mid-range session in 12 — the "never in between"
   step-function claim is broken on the PE side.** Advances ran 15–28 (under the CE `>32` line all
   day ⇒ CE 0/206 as always), but PE support was **286/830 = 34.5%** — neither 0% nor ~100% for the
   first time. The PE side's rule crossed intraday, i.e. the operand CAN be a per-bar discriminator
   on one side while staying a step function on the other. Evidence sharpens G16; the design
   question (market-wide scalar in a per-bar composite) is unchanged, still OWNER.
5. **T1's 9th consecutive no-pay, now with REAL exits:** the §3.5 volume-floor-sole-blocker set is
   4 CE legs (10:54–11:00), and every one has a real shadow-book exit — all STOP_LOSS/losers
   (−₹3,731.48, −₹3,107.27, −₹5,077.48; the 4th leg is the SAME leg the engine actually fired 4
   minutes later and lost on). The floor's veto was right again, on the same side the engine's own
   fire lost on.
6. **Every liveness oracle clean, and T23 is measurable again** (no mid-session recreation today):
   boot 08:24 IST with the documented F10 cold-start self-heal (0/38 → 38/0 pre-open), reloads
   38/0/0, capture 375/375, OI 374/375 minutes, 0 misaligned 1m rows (10th clean session),
   `subscriber_health_events` empty, 0 ERROR lines in both services, canary GREEN, kite session
   validated today. Eval rollup reconciles exactly: `confluence-blocked` 1,318 = rows, `fired` 2 =
   ENTRY signals.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-31 | 2026-08-03 | 2026-08-04 | **2026-08-05** |
|---|---|---|---|---|
| rejections | 1,055 | 1,272 | 1,504 | **1,318** |
| distinct strategies emitting | 20 | 34 | 38 | **38 of 38** (2nd full-fleet session) |
| published + enabled | 44 | 44 | 44 | **44** |
| signals | 11 ENTRY + 6 EXIT | 4 + 3 | 0 + 0 | **2 + 2** |
| paper positions opened | 5 (+₹69.58) | 1 (−₹743.33) | 0 | **1 (−₹2,583.73)** |
| bar-time coverage | 09:19–13:34 (freeze) | 09:19–15:04 | 09:18–15:18 | **09:18–15:18 (full, 25 buckets, no holes)** |
| scored rows | 835 (all CE) | 998 (896 CE / 102 PE) | 1,242 (150 CE / 1,092 PE) | **1,036 (206 CE / 830 PE)** |
| composite ≥ threshold | 323 (38.7%) | 273 (27.4%) | 602 (48.5%) | **432 (41.7%)** |
| max composite | 0.8511 | 0.8511 | 0.9118 | **0.8627** (cap 0.9574) |
| regime (§3.25/§3.33 continuous) | chop 0.171 | chop 0.007 | trend-down 0.871 | **mixed 0.551 (official 0.248 chop)** |

**Eval counters (V045/V053 rollup, reconciles):** `chart-gate-failed` **1,992**,
`confluence-blocked` **1,318** (= rows), `composite-below-threshold` **240**, `fired` **2**
(= ENTRY signals), `discipline-paused` **0**, Σ **3,552**. Context-less rows reconcile:
216+8 `time-window`… precisely **224 time-window + 42 time-of-day-preference + 6+8
option-side-constraint…** = 224 + 42 + 14 + **2** = 282 = 1,318 − 1,036 — the 2 are
`scalp-morning-trade-*` 09:18 opening-tick rows blocked at `rsi-band` BEFORE the context fetch
(the opening-formation path is context-less by design; first time this sub-class appears in the
reconciliation).

**Engine load state.** Boot **08:24:01** with the F10 cold-start shape (`0 loaded / 38 unresolved`
→ self-healed **38/0** at 08:25:54, ~50 min pre-open); periodic reconciles 08:32/08:35/08:40 all
`38/0/0`. `unresolved == 0` ✅ on every row. **No mid-session recreation** (unlike 08-04) — logs
intact end-to-end.

**First-blocking-rail histogram** (1,318 rows, 17 distinct rails): `volume-floor` **744 (56.4%)**,
`time-window` 224, `rsi-band` 96, `time-of-day-preference` 42, `two-candle` 30, `pct-price-move`
30, `volume-pump` 28, `divergence-vol-gate` 26, `confluence-composite` 24 (avg margin −0.007 —
razor-thin), `oi-cross-required` 24, `supertrend-15m` 18, `option-side-constraint` 14,
`max-oi-sr-gate` 8, `psar-durability` 4, `call-put-delta-filter` / `directional-change-gate` /
`vwap-distance` 2 each.

**All-failed-rails expansion (§3.3)** — top rows: `confluence-composite` **994** fails at avg
**0.556 vs 0.600**; `volume-floor` 744 (avg 11,140 vs banded avg 24,360); `rsi-band` 536 (avg
46.39 — mid-band, mixed tape); `trend-change` 158; `divergence-vol-gate` 158; `two-candle` 144;
`pct-price-move` 144 (avg −0.215 vs 1.0); `volume-pump` 144; `oi-divergence-magnitude` **112 at
avg −9.84** (range −20.37…+0.53 — G17's sign-invariance baseline gains a 4th point: +13.78 /
+26.45 / −8.64 / **−9.84**); `oi-cross-required` 102; `supertrend-15m` 88; `strike-pick` **0** (§2.2).

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 G10 / T27 — volume floor: banded, no flat rows, mildest opening share yet

**Registry (§3.14):** 38/38 armed `relative-volume-floor` (published 2026-07-28, unchanged).
Blocking thresholds: **39 distinct, 10,530.00 – 60,108.75, no flat floors.**

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned (§3.15):**

| bars | min | p50 | p90 | p99 | max | ≥125,000 |
|---|---|---|---|---|---|---|
| 125 | 2,990 | 14,040 | 43,680 | 133,835 | 246,870 | 2 |

Peak threshold 60,108.75 = **p94.4** (7 of 125 bars clear — same percentile as 08-04). Pre-11:00
share of `volume-floor` first-blocks: **166/744 = 22.3%** — a new LOW (prior range 27.8–43%). The
floor bound 56.4% of first-blocks and its sole-blocker veto set was 4/4 losers (§5.0).

### 2.2 `strike-pick` — ZERO fails (watch row's non-expiry control)

| session | day | expiry | NIFTY-rooted | SENSEX-rooted |
|---|---|---|---|---|
| 2026-08-03 | Mon | none (NSE-weekly eve) | 235 | 0 |
| 2026-08-04 | Tue | NSE weekly | 604 | 0 |
| **2026-08-05** | **Wed** | **none** | **0** | **0** |

The eve→day-of→gone shape (235 → 604 → 0) is exactly what the decayed-front-weekly-premium-band
mechanism predicts and what a persistent CAS-era chain degradation would NOT show. Discriminators
ahead: **Thu 08-06 is the BSE weekly expiry** (expect SENSEX-rooted fails on the same mechanism —
note the carried watch row said "Fri 08-07 SENSEX"; the BSE weekly is actually Thursday, so read
Thu/Fri together), then Mon/Tue 08-10/11 for the NIFTY repeat.

### 2.3 Rails with no evidence of miscalibration

`pct-price-move` (avg −0.215 vs 1.0), `psar-durability` (0.025 vs 0.050), `call-put-delta-filter`
(32.69 vs 50), `vwap-distance` (margin 0.000, exact-line, n=2), `rsi-band` mid-band (avg 46.39) on
a mixed tape. `confluence-composite` first-block avg margin −0.007 — the passing population sat on
the line, as the §3.3 near-miss mass (0.556 avg) also says.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (1,036 scored rows — 206 CE / 830 PE):

| bucket | 0.1 | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|---|---|
| n | 2 | 26 | 120 | 128 | 160 | 250 | 240 | 68 | 42 |
| PE | 2 | 26 | 94 | 80 | 108 | 184 | 226 | 68 | 42 |

432 rows (41.7%) ≥ 0.600; max **0.8627**; everything ≥0.8 is PE, CE tops out in the 0.7 bucket.
**Cap:** denominator 18.80 (`iv_rank` withheld); dead-in-denominator = `iv_pair` 0.8 only ⇒ cap
**0.9574** — the market side set the ceiling, not the roster. (The FIRED rows' scorer composite
0.9857 is a different scale — the strategy scorer, not the dot aggregate; the fired bar's dot
aggregate was 0.6176.)

**Dot support rates** (side split where the dot is a side-mirror today):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_rank` | 0.8 | 0/1,036 | 0.0 | dead-data, withheld — 16th session |
| `iv_pair` | 0.8 | 0/1,036 | 0.0 | structurally impossible (G13) — 13th zero session; in Σw |
| `oi_spurt` | 1.0 | 100/1,036 | 9.7 | alive, both sides (CE 28, PE 72) |
| `basis` | 1.0 | 206/1,036 | 19.9 | **pure CE-mirror again: CE 206/206, PE 0/830** — basis +66…+99 all session |
| **`breadth`** | 1.0 | 286/1,036 | **27.6** | **FIRST mid-range session in 12 (G16): CE 0/206 (advances 15–28, under `>32` all day) but PE 286/830 = 34.5% — the PE rule crossed intraday. The step function broke on ONE side** |
| `volume` | 1.0 | 292/1,036 | 28.2 | alive (G6 path) |
| `trending_cross` | 1.0 | 428/1,036 | 41.3 | alive |
| `rsi` | 1.0 | 476/1,036 | 45.9 | |
| `futures_oi` | 1.5 | 552/1,036 | 53.3 | ✅ live |
| `underlying_oi` | 1.0 | 560/1,036 | 54.1 | ✅ live |
| `sentiment_slope` | 1.0 | 658/1,036 | 63.5 | |
| `vwap` | 2.5 | 682/1,036 | 65.8 | side-mirror: CE 0/206, PE 682/830 (82.2%) — price under VWAP nearly all day |
| `psar` | 1.0 | 760/1,036 | 73.4 | |
| `sentiment` | 1.0 | 810/1,036 | 78.2 | |
| `vix` | 1.0 | 826/1,036 | 79.7 | operand moving (50 distinct) |
| `drastic_oi` | 1.0 | 854/1,036 | 82.4 | |
| `iv_slope` | 0.8 | 126/142 | 88.7 | iv-roster rows only |
| `vwma` | 1.0 | 980/1,036 | 94.6 | |
| `supertrend` | 1.0 | 1,024/1,036 | 98.8 | near-saturated |
| `premium_skew` | — | 12/12 | 100.0 | 3rd appearance, n=12 — still no read |
| `iv_abs_band` | 0.8 | 142/142 | 100.0 | frozen input (G12) — **9th session**; stamp 0.107333, inside 0.10–0.12 |

## 4 Data health (§3.7)

| field | 2026-08-03 | 2026-08-04 | **2026-08-05** | class |
|---|---|---|---|---|
| quadrants NEUTRAL | 0/998 | 0/1,242 | **0/1,036** | ✅ live |
| `spurtOiPct`/`spurtPricePct` NULL | 0 | 0 | **0 (81 distinct spurtOi)** | ✅ live |
| `futuresBasis` | LIVE | LIVE | **1,036/1,036 (77 distinct, +66…+99)** | ✅ |
| `advances` | 38–44 | 4–16 | **15–28, 0 nulls** | under `>32` ⇒ CE side 0%; PE side mid-range (§3) |
| `fiiLongPct` | 11.12 | 13.02 | **12.53 (1 distinct, 0 nulls)** | ✅ daily stamp moved |
| `atmIv` | 0.107578 | 0.103481 | **0.107333 (1 distinct)** | frozen — G12, by design |
| `vixLevel` | moving | 43 distinct | **50 distinct** | ✅ |
| `ivRank` | NULL 100% | NULL 100% | **NULL 1,036/1,036** | dead-data (16th session) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 1,036/1,036** | by design (un-armed) |

**Capture:** `NIFTY26AUGFUT` 1m **375/375** (last 15:29), **0 misaligned 1m rows (T19 quiet a 10th
session)**; `futures_oi_snapshots` 25,806 rows / **374/375** minutes; market-data canary **GREEN**
(tickedTokens 69); kite session validated 15:55 IST; **0 ERROR lines in both services** (full-day
logs, no restart); `subscriber_health_events` **0 rows**.

**`dot-health` at 15:59 IST** (200/40): dead = standing pair `iv_rank` + `dow`; `fii` (12.53) +
`iv_abs_band` (0.107333) frozen BY DESIGN; everything else alive; `neverCrossing` false on all.
**Nothing newly dead.** Both EOD stamps moved day-over-day.

**T23 / G9 (PartialBucketCanary): measurable again — 1 unpaired WARN + 1 suppressed straddle.**
The WARN is the **opening bucket** (09:15–09:18): 3m bar 246,870 vs Σ(3×1m) 241,150, shortfall
**−5,720** (88 NIFTY lots, 2.3% of the bucket), explicitly logged UNPAIRED. Same session-open
locus as the pre-B2 opening defect but opposite sign (tick-agg UNDER-counting). Single event,
sub-3% — logged as a watch point, not escalated. The 10:27/10:30 straddle (±715, residue 0) is the
benign §3.17 fingerprint working as designed.

## 5 Paper outcome + shadow book

### 5.0 The fired trade, its unfunded twin, and the §4.2/§3.5 counterfactual

**The fire:** 11:03 bar, CE, both connect-the-dots variants (scorer 0.9857; gate aggregate
0.6176). `scalp-connect-the-dots-sensex-niftyoi` funded: `SENSEX2680678400CE` ×20 @647.75
(11:04:20, sub-account 1). `scalp-connect-the-dots-nifty` **zero-sized**: 295.5 × 65 =
₹19,207.50 > ₹15,000 (#1075 class; **cumulative 31 fires / 10 unfunded**). EXIT signals at the
11:30 bar (TRAILING_STOP); paper close 11:31:22 @521.95 = **−125.80 pts × 20 = −₹2,583.73**.
Emit latency 20.3–22.4 s (n=4) — the G8 shape, unchanged.

**Counterfactual for the unfunded NIFTY leg:** the shadow book holds the SAME leg from the 11:00
rejection (`NIFTY2681124450CE` @294.75, vol-off + vol-12k5): closed **STOP_LOSS −76.95 pts
(−₹5,077.48 net)** at 12:35. The funded twin lost −19.4% in 27 minutes; the unfunded leg hit the
−25% premium stop. **The budget rail's veto was accidentally right today; both halves of the fire
lost.**

**§3.5 volume-floor sole-blocker set (composite passed): 4 rows = 4 legs, ALL CE 10:54–11:00 —
every one a real, closed shadow LOSER (no model needed):**

| bar | leg | entry | real shadow exit | net ₹ |
|---|---|---|---|---|
| 10:54 | SENSEX2680678300CE | 658.30 | STOP_LOSS 12:32 (champion+vol-off) | −3,731.48 |
| 10:57 | SENSEX2680678400CE | 601.75 | STOP_LOSS 11:51 (vol-12k5) | −3,107.27 |
| 11:00 | NIFTY2681124450CE | 294.75 | STOP_LOSS 12:35 (vol-off/vol-12k5) | −5,077.48 |
| 11:00 | SENSEX2680678400CE | 653.25 | no shadow row (dedup) — the ENGINE fired this leg at 11:03 @647.75 and lost −125.80 pts | −₹2,583.73 (real) |

**T1's 9th consecutive no-pay** — and uniquely, the 4th row's outcome is not a model but the live
book's own trade. Costs and `signal_exit` caveats don't apply: these are the shadow book's real
bracket exits. Nothing for G11 (not a chop day on the continuous read).

### 5.1 Shadow book — champion +₹1,024.41 (10 events, second TP wave)

| variant | closed | net-wins | pts | net ₹ (day) | all-time net ₹ |
|---|---|---|---|---|---|
| **champion** | 37 | 21 | +593.70 | **+1,024.41** | **−62,817.84** |
| composite-055 | 7 | 2 | +196.30 | +899.91 | −3,844.67 |
| vol-off | 9 | 2 | −63.90 | −7,909.05 | −36,676.64 |
| vol-12k5 | 9 | 2 | −32.75 | −7,284.84 | −28,444.21 |

Champion dedupe (§3.24): 37 rows = **10 distinct `(bar, leg, entry)` events**, and NIFTY legs are
back (strike-pick healthy): the morning CE cluster lost big (10:54 `NIFTY24400CE` ×7 −₹16,563.61;
10:54 `SENSEX78300CE` ×6 −₹20,127.07), the midday PE flip paid — **11:36 `SENSEX79200PE` ×7 ALL
TAKE_PROFIT +₹31,733.94** (the second TP wave ever, again PE side, again SENSEX) + 11:36
`NIFTY24850PE` ×6 +₹4,222.68 + 12:42 `SENSEX79100PE` ×6 +₹8,214.96. Same fan-out caveat as
always: one 11:36 bar carries the day. Shadow entry latency p50 **1:16.9** / p95 **1:22.1** (n=62)
— structurally unchanged (G8).

**T7 nuance:** `composite-055` booked +₹899.91, but its **challenger-only rows (the extra trades
its lower floor alone takes) went 0/5, −₹5,002.67** — the green came from rows shared with
champion. Second green day, still adverse on the marginal-trade test. REJECTED stands.

### 5.2 §3.30 freeze telemetry

Sub-account 1: 1 entry (11:04), day PnL −₹2,583.73, first-loss frozen from 11:31; subs 2–5 never
received a funded fire. `discipline-paused` 0 (no post-freeze funded attempt reached the gate).
Not a freeze-truncation day — rejections flow to 15:19. All-time scalper book: **11 closes,
−₹5,693.43**. T10: stale OPEN swing positions now **18** (was 17).

## 6 New data points / anomalies

### 6.1 ⚠️ Heat-cap gate inert on a live entry (NEW — promoted to README §3.34)

At 11:04:22, on the session's only funded entry, `PaperMarginClient.margin()` failed:
`Error while extracting response for type [PaperMarginClient$Quote] and content type
[application/octet-stream]`, and `RiskService` proceeded with `heat-cap enforcement ON but heat
unassessable — gate inert this entry`. A second identical failure fired on the notifier thread at
11:04:23. The client calls market-data `POST /api/v1/market/margin` (fail-soft by design — an
`unpriced` quote degrades to gate-inert rather than blocking the entry), but `application/
octet-stream` on the response says the reply was not the typed JSON record at all — a wire/
content-negotiation defect (or an error body served without a JSON content type), NOT a
margin-unpriced case. Impact today: the F9 heat-cap never evaluated the one entry it exists to
check. Two occurrences, both 11:04; no other margin calls this session (only one fire). **Proposed
investigation (§7 NEW-1): reproduce the octet-stream reply against the live market-data container,
check the gateway/actuator error-path content type, and decide whether gate-inert should page.**
Read-only today; nothing restarted.

### 6.2 CAS third session — shape confirmed, smallest jump

Freeze at 15:15 (24,570.20 pinned 15:15–15:27), official print 15:28 → 24,624.65 (+54.45). Jump
series: +200.95 / +151.45 / **+54.45**. 1d bar carries the official close (source KITE). Both
closes stamped in the rollup regime row per §3.33; nothing repaired (G18 doctrine).

### 6.3 Mechanical pre-checks

`tools/ledger-consistency-check.py`: **13 REVIEW lines, all false positives, same classes as
08-04's 12** — 5×[A] snapshot/self-referential, 5×[B] keyword reference-not-claim, 3×[C] "T18
promotion" (the third is the 08-04 file's own §6.3 quoting the second — the predicted
accumulating self-quote FP; if a 4th accrues, teach the checker to skip quoted dispositions).
**No ledger edits required; ledger consistent in substance.**

`tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH), 0
DB-only, 0 YAML-only.** Same 2 as 08-03/08-04: `minervini-cheat-3c` / `minervini-primary-base`
(1.0.2 drafts, name+description only). Republish proposal carried — **not republished by this
run**.

### 6.4 §3.29 — unexercised-path audit: TRAILING_STOP +1; never-fired set unchanged

Fired vocabulary since 07-01: **TRAILING_STOP 12 (+1 today)**, STRUCTURAL_STOP 5, STOP_LOSS 5,
TIME_STOP 5, MANUAL 2. Day's delta: 1 TRAILING_STOP (the `indicator` basis fired; the
`atr_multiple` sibling stays INDETERMINATE — not attributable from `close_reason`). The
never-fired set stands: `take_profit premium_pct` (36), `signal_exit` (38), `square_off` (2), tag
`oi-confluence-exit` (8); INDETERMINATE pair (`trailing_stop atr_multiple`, `stop_loss
atr_multiple`) + `stop_loss percent` stand. Shadow evidence again shows `take_profit` REACHABLE
(the 11:36 ×7 TP wave, PE side) — the paper book's zero remains entry starvation, not bracket
distance: today's one entry was a CE cut by the trailing stop long before +35%.

### 6.5 Context-less reconciliation gained a third member

282 context-less rows = 224 `time-window` + 42 `time-of-day-preference` + 14
`option-side-constraint` + **2 `rsi-band`** — the last are `scalp-morning-trade-*` 09:18
opening-tick rows (6-check diagnostic, no context: the opening-formation path evaluates RSI before
any chain/context fetch). By-design, first time visible in this reconciliation; noted so future
runs don't read the rsi-band pair as a context outage.

## 7 Tuning candidates

Carried from 08-04 with this session's movement. **Nothing here is applied.** Ledger §0 group G is
the authoritative status.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **NEW-1** | paper heat-cap margin call | gate-inert on octet-stream reply | investigate the content-type defect on market-data `POST /api/v1/market/margin` via the paper path; decide whether repeated gate-inert should page | §6.1 — 2 failures at 11:04 on the session's only entry; F9 heat check never ran | BUG-CLASS (owner/architect) | **PROPOSED** |
| **T30** | `breadth` dot `>32` | fixed | relative / percentile, or reweight | **FIRST mid-range session in 12: PE 34.5% (CE side still 0%, advances 15–28)** — the step function broke on one side; per-session-bias framing needs the side split | STRUCTURAL — G16 | **OPEN (evidence sharpened)** |
| **T29** | scalper `time_stop` | armed | owner decision (G11) | not a chop day (continuous mixed 0.551); chop ledger stands at 2 observations, both stop-favouring | EXIT-BAND (owner) — G11 | **OWNER-DECIDABLE (carried)** |
| **T27** | relative-floor window | 1.5×median session-start | time-of-day profile (built, default-OFF) | peak 60,108.75 = p94.4; pre-11:00 share **22.3% — new low** | STRUCTURAL — G10 | **OPEN; arming rec unchanged (NO)** |
| **T28** | `atmIv` frozen daily stamp | frozen | intraday operand | 9th one-distinct-value session (0.107333) | STRUCTURAL — G12 | **OPEN** |
| **T3** | `iv_pair` | 0.02 gap | drop or redefine | 0/1,036 — 13th zero session | STRUCTURAL — G13 | **OPEN (owner)** |
| **T23** | partial-bucket tolerance 650 | — | — | **measurable again: 1 unpaired opening-bucket WARN (−5,720 = 88 lots, 2.3%) + 1 benign straddle (±715)** — new sign (bar > Σ1m) at the old locus; watch | STRUCTURAL — G9 | **OPEN — watch the opening bucket** |
| **T1** | `relativeVolumeMultiplier` 1.5 | — | — | §5.0: **9th consecutive no-pay** — 4-leg sole-blocker set, all real exits, all losers; 4th leg = the engine's own losing fire | REJECTED | **REJECTED — reconfirmed** |
| **T7** | composite threshold 0.600 | — | — | composite-055 +₹899.91 day but its 5 challenger-ONLY rows all lost (−₹5,002.67) — the marginal trades stay adverse | REJECTED | **REJECTED — carried** |
| **watch** | `strike-pick` chain-quality | — | none (correct behaviour) | **ZERO fails on the non-expiry Wed** (235→604→0) — supports the expiry-eve/day mechanism; next: Thu 08-06 BSE weekly (SENSEX), Mon/Tue 08-10/11 (NIFTY). NB the carried "Fri 08-07 SENSEX" should read Thu 08-06 — BSE weekly is Thursday | REGIME | **WATCH** |
| **NEW (08-04)** | mid-session deploys | none today | deploys wait for post-market | clean day — logs intact, T23 measurable again; doctrine reaffirmed by contrast | ops (owner/architect) | **PROPOSED — carried** |
| **NEW (08-03)** | minervini republish | published 1.0.1 ×2 | republish 1.0.2 drafts | §6.3 (3rd session): name+description only | ops (owner) | **PROPOSED — carried** |
| T10 | stale OPEN paper positions | **18 OPEN (+1)** | square off / age out | unchanged class | ops | **OWNER — chronic** |
| T8/T26 | latency | emit 20.3–22.4 s (n=4); shadow p50 1:16.9 / p95 1:22.1 (n=62) | — | structurally unchanged | STRUCTURAL — G8 | OPEN (data) |
| T2 | `iv_rank` | NULL 100% (16th session) | — | E8 pointer stands | STRUCTURAL | carried, not open |
| T16/T12/T19/T22/T24/T21/T6/T15/T17+T13/T20/T25 | — | — | — | §2.1 (38/38 armed, 0 flat); §4 (374/375 OI; 0 misaligned ×10); reloads 38/0/0 | — | ✅ remain CLOSED |

**Group G movement: G16 FIRST mid-range breadth session (step function broken on the PE side);
G10 new-low opening share (22.3%) at the same p94.4 peak; G9 reading resumed (1 unpaired opening
WARN, new sign); G12 9th frozen session; G13 13th zero; T1 9th rejection; G8 unchanged; G17
4th sign point (−9.84). NEW: heat-cap-inert defect class (§6.1).**

## 8 Honesty caveats

- §5.0's outcomes are REAL shadow-book bracket exits (and one real paper trade), not modelled
  paths — the usual §4.2 granularity/cost caveats do not apply to them. The shadow book still
  does not replicate `time_stop`/`signal_exit` (§3.16), so its exits differ from what the ENGINE
  would have done with the same entries.
- Champion's +₹1,024.41 is 10 independent events, not 37; the 11:36 TP cluster is +₹31.7k across
  7 fan-out rows of one bar/leg, offset by the two big morning CE clusters.
- The regime stamp (mixed 0.551) is the continuous-session read per §3.33; the official-close read
  is 0.248 chop. G11's chop-gate keys on the continuous read by doctrine.
- `premium_skew` n=12 — still no read. `composite-055` day figures are n=7 (5 challenger-only).
- The 08-04 watch row's "Fri 08-07 SENSEX" discriminator mislabeled the BSE weekly (Thursday);
  corrected in this file's watch row rather than editing the immutable 08-04 file.
- This run was **read-only against the live stack**: SELECTs, `docker logs`, `docker inspect`,
  in-container health/actuator GETs. No restart, deploy, write, or config change; nothing
  republished. Docs edits in this PR: this file + README §3.34 + rollup rows.
