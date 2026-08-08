# Session findings — 2026-08-06 (data date)

Analysis date: 2026-08-06 (scheduled post-market agent, ran ~16:15–17:00 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,277** (bar times 09:18–15:18, generated 09:19–15:19), signals
**4 ENTRY + 3 EXIT**, paper positions opened **1** / closed **1** (−₹1,784.15), shadow positions
**38** opened (32 champion + 6 challenger; all closed).

**2026-08-06 is a Thursday — the BSE WEEKLY expiry day** (SENSEX weekly options expiring today;
NSE weekly was Tue 08-04). Weekly, not monthly — S24 correctly not engaged; OI bloc fully live.

**Signal contract: `NFO:NIFTY26AUGFUT@3m`** — named directly by the rail log lines (logs intact
all session). Unchanged since the 07-27 roll.

**Session regime (§3.25 / §3.33): `chop` on BOTH reads — the tightest day in the whole G15
sample.** 1d open 24,641.00 → continuous close 24,627.95 (frozen 15:15–15:27): net **−0.05%**,
efficiency **0.179**. Official CAS print 15:28 → 24,636.00 (**+8.05 — the smallest auction jump of
the four CAS sessions:** +200.95 / +151.45 / +54.45 / **+8.05**), official read net −0.02%, range
**0.30%**, efficiency **0.069**. Doctrine stamps the continuous read: **chop** — the third
post-07-27 chop observation (07-31, 08-03, today).

---

## 0 Read this first — the session's headline

1. **A VWAP-pin day nobody's threshold could see: the `vwap` dot (w 2.5 — the composite's largest
   weight) read 0/989 on BOTH sides, its first-ever 0% session.** Not a defect: the operand is
   live (104 distinct VWAP values, 0 nulls) and the session max |close−VWAP| was **13.2 bps
   against the dot's ≥15 bps rule** — the index future never left a 15-bps channel around VWAP all
   day (0.30% total range). §3.28's fourth state ("live, moving, never crosses") on a NEW dot, and
   invisible to `neverCrossing` because the canary's probe registry covers macro operands only,
   not chart ones. With `iv_pair` (0.8) also dead, 3.3 of 18.80 weight was structurally silent ⇒
   cap 0.8245; max composite reached only 0.6422 and the pass share collapsed to **62/989 = 6.3%**
   (prior four sessions: 38.7 / 27.4 / 48.5 / 41.7%).
2. **The engine fired INTO the chop and lost, and so did every model of the same tape:** 4 ENTRY
   signals on the 11:15 bar (CE, composite 0.7693 — golden-crossover + connect-the-dots, both
   roots). The SENSEX leg funded: both SENSEX slugs pyramided into ONE position
   (`SENSEX2680678200CE` ×40 @726.90, sub-account 1), closed 11:43 by STRUCTURAL_STOP =
   **−₹1,784.15**. Both NIFTY twins zero-sized (`292.7 × 65 = ₹19,025.50 > ₹15,000`) — **#1075
   cumulative 35 fires / 12 unfunded**. The shadow book's same-bar clusters lost on every exit
   flavour (§5.1).
3. **Shadow champion had its WORST DAY IN BOOK HISTORY: −₹40,671.30** (32 closes → 10 deduped
   events, 2 net-wins — both 09:24 square-offs), taking all-time past the ₹1L mark to
   **−₹103,489.14**. A chop day that held to 15:12 square-offs chewed every position; the 13:09 PE
   clusters alone lost −₹23.3k. The multi-exit clusters again favoured cutting early (§5.1) —
   the third stop-favouring chop observation for the (already decided) G11.
4. **`strike-pick`: ZERO fails on the BSE WEEKLY expiry day — the carried prediction FALSIFIED.**
   The watch row expected SENSEX-rooted fails today if "an expiry saturates the expiring root"
   generalized to weeklies on both exchanges. It did not: 0 fails on either root, and the shadow
   book traded expiring-today SENSEX legs all session. §3.27's claim now splits by exchange+cycle:
   monthly (both roots) and NSE-weekly eve/day-of (235→604) saturate; the first observed BSE
   weekly does NOT. README §3.27 amended. Next discriminators: Mon/Tue 08-10/11 (NIFTY weekly).
5. **T1's 10th consecutive no-pay:** the volume-floor sole-blocker set is 2 CE legs (one 14:18
   bar, composite 0.6029), both with REAL shadow exits, both losers (−₹1,708.96 / −₹2,963.41).
   0W/2L. T7's marginal-trade test adverse a 3rd time: composite-055's 4 closes today were ALL
   challenger-only rows, 0/4, −₹3,340.20.
6. **Every liveness and data-health oracle clean, and the first fully-quiet PartialBucketCanary
   session on record: 0 WARNs + 0 straddles** (08-05 had 1+1). Boot 08:02 IST with the F10
   cold-start self-heal (0/38 → 38/0 by 08:05), no mid-session restart, capture 375/375, 0
   misaligned 1m rows (11th clean), `subscriber_health_events` empty, 0 ERROR lines in both
   services, canary GREEN, kite session validated 15:58. Eval rollup reconciles exactly:
   `confluence-blocked` 1,277 = rows, `fired` 4 = ENTRY signals. **§3.34 heat-gate check: grep
   count 0 on a funded-fire day — the margin call SUCCEEDED this time; the 08-05 octet-stream
   failure did not recur** (evaluability only; the N23-A coverage question stands).

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-08-03 | 2026-08-04 | 2026-08-05 | **2026-08-06** |
|---|---|---|---|---|
| rejections | 1,272 | 1,504 | 1,318 | **1,277** |
| distinct strategies emitting | 34 | 38 | 38 | **38 of 38** (3rd full-fleet session) |
| published + enabled | 44 | 44 | 44 | **44** |
| signals | 4 + 3 | 0 + 0 | 2 + 2 | **4 + 3** |
| paper positions opened | 1 (−₹743.33) | 0 | 1 (−₹2,583.73) | **1 (−₹1,784.15)** |
| bar-time coverage | 09:19–15:04 | 09:18–15:18 | 09:18–15:18 | **09:18–15:18 (full, 25 buckets, no holes)** |
| scored rows | 998 (896 CE / 102 PE) | 1,242 (150 CE / 1,092 PE) | 1,036 (206 CE / 830 PE) | **989 (613 CE / 376 PE)** |
| composite ≥ threshold | 273 (27.4%) | 602 (48.5%) | 432 (41.7%) | **62 (6.3%) — series low** |
| max composite | 0.8511 | 0.9118 | 0.8627 | **0.6422** (cap 0.8245 — `vwap`+`iv_pair` dead) |
| regime (continuous §3.33) | chop 0.007 | trend-down 0.871 | mixed 0.551 | **chop 0.179 (official 0.069)** |

**Eval counters (V045/V053 rollup, reconciles):** `chart-gate-failed` **1,925**,
`confluence-blocked` **1,277** (= rows), `composite-below-threshold` **184**, `fired` **4**
(= ENTRY signals), `discipline-paused` **0**, Σ **3,390**. Context-less rows reconcile:
**228 `time-window` + 44 `time-of-day-preference` + 14 `option-side-constraint` + 2 `rsi-band`**
(the `scalp-morning-trade-*` 09:18 opening-tick pair, §3.16 of the 08-05 file) = 288 = 1,277 − 989.

**Engine load state.** Boot **08:02:19 IST** with the F10 cold-start shape (`0 loaded / 38
unresolved` → self-healed **38/0** at 08:04:52, ~70 min pre-open). `unresolved == 0` ✅. No
mid-session recreation — logs intact end-to-end.

**First-blocking-rail histogram** (1,277 rows, 15 distinct rails): `volume-floor` **746 (58.4%)**,
`time-window` 228, `rsi-band` 135, `time-of-day-preference` 44, `divergence-vol-gate` /
`volume-pump` / `two-candle` / `pct-price-move` 18 each, `confluence-composite` 16 (avg margin
−0.135 — NOT razor-thin today; the passing population was far from the line), `option-side-
constraint` 14, `oi-cross-required` 10, `directional-change-gate` 6, `rsi-5m-cap` / `hero-zero` /
`supertrend-15m` 2 each.

**All-failed-rails expansion (§3.3)** — top rows: `confluence-composite` **953** fails at avg
**0.437 vs 0.600** (the VWAP-starved composite; compare 08-05's 0.556); `volume-floor` 746 (avg
11,831 vs banded avg 25,280); `rsi-band` 475 (avg 50.57 — mid-band chop); `trend-change` /
`divergence-vol-gate` 168 each; `two-candle` / `volume-pump` / `pct-price-move` 130 each;
`directional-vix-gate` 108; `oi-cross-required` 86; `oi-divergence-magnitude` **86 at avg −3.84**
(range within ±20 — G17's sign-invariance series gains a 5th point: +13.78 / +26.45 / −8.64 /
−9.84 / **−3.84**); `strike-pick` **0** (§2.2).

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 G10 / T27 — volume floor: banded, no flat rows, peak at p98.4

**Registry (§3.14):** 38/38 armed `relative-volume-floor` (published 2026-07-28, unchanged — no
publish stamp newer than the last findings file).

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned (§3.15):**

| bars | min | p50 | p90 | p99 | max | ≥125,000 |
|---|---|---|---|---|---|---|
| 125 | 3,315 | 12,740 | 32,630 | 79,365 | 108,485 | 0 |

Blocking thresholds: **41 distinct, 7,653.75 – 58,353.75, no flat floors.** Peak threshold
58,353.75 = **p98.4** (2 of 125 bars clear — the thin chop tape pushed the same rupee-level floor
two percentile points past 08-05's p94.4). Pre-11:00 share of `volume-floor` first-blocks:
**248/746 = 33.2%** (back inside the prior 27.8–43% range after 08-05's 22.3% low). The floor
bound 58.4% of first-blocks; its sole-blocker veto set was 2/2 losers (§5.0).

### 2.2 `strike-pick` — ZERO fails on the BSE weekly expiry day (prediction falsified)

| session | day | expiry | NIFTY-rooted | SENSEX-rooted |
|---|---|---|---|---|
| 2026-08-03 | Mon | none (NSE-weekly eve) | 235 | 0 |
| 2026-08-04 | Tue | NSE weekly | 604 | 0 |
| 2026-08-05 | Wed | none | 0 | 0 |
| **2026-08-06** | **Thu** | **BSE weekly** | **0** | **0** |

The carried watch row predicted SENSEX-rooted fails today. **Zero, on either root** — and the
shadow book opened and closed expiring-today SENSEX legs (78200/78300/79300 strikes) all session,
so the SENSEX chain was pickable end-to-end on its own expiry day. The §3.27 claim therefore does
NOT generalize to "any expiry saturates the expiring root": the observed saturations are the two
MONTHLIES (07-28 NSE → NIFTY 534; 07-30 BSE → SENSEX 405) and the NSE-weekly eve/day-of pair
(08-03/08-04 → NIFTY 235/604); the first observed BSE weekly produced nothing. One observation —
the next BSE weeklies decide whether that is a rule or this week's chain shape. README §3.27
carries a dated amendment. Next discriminators: Mon/Tue 08-10/11 (NIFTY weekly eve/day-of).

### 2.3 Rails with no evidence of miscalibration

`pct-price-move` (avg 0.164 vs 1.0 on a 0.30%-range day), `psar-durability` (0.023 vs 0.050),
`call-put-delta-filter` (29.99 vs 50), `rsi-5m-cap` (79.12 — CAP side, n=10), `rsi-cooloff`
(81.11, n=8). `confluence-composite` all-fails avg 0.437 vs 0.600 — the failure mass sat far
under the line (VWAP starvation), unlike 08-04/08-05's near-miss shape.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (989 scored rows — 613 CE / 376 PE):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 |
|---|---|---|---|---|---|
| n | 50 | 137 | 240 | 370 | 192 |
| PE | 0 | 46 | 84 | 142 | 104 |

62 rows (6.3%) ≥ 0.600; max **0.6422** — both series lows. **Cap:** denominator 18.80 (`iv_rank`
withheld); dead-in-denominator = `iv_pair` 0.8 **+ `vwap` 2.5** (never-crosses day) ⇒ cap
**0.8245**, and the tape then stayed 0.18 under even that. The fired bar's scorer composite was
0.7693 (strategy-scorer scale, not the dot aggregate).

**Dot support rates:**

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_rank` | 0.8 | 0/989 | 0.0 | dead-data, withheld — 17th session |
| **`vwap`** | **2.5** | **0/989** | **0.0** | **FIRST-EVER 0% session, BOTH sides: max \|close−VWAP\| = 13.2 bps vs the ≥15 bps rule — §3.28 never-crosses, regime (0.30% range), not defect. Canary blind by design (chart operand, not in probe registry)** |
| `iv_pair` | 0.8 | 0/989 | 0.0 | structurally impossible (G13) — 14th zero session; in Σw |
| `oi_spurt` | 1.0 | 88/989 | 8.9 | alive |
| `trending_cross` | 1.0 | 223/989 | 22.5 | alive |
| `volume` | 1.0 | 243/989 | 24.6 | alive (G6 path) |
| `rsi` | 1.0 | 300/989 | 30.3 | mid-band chop |
| **`breadth`** | 1.0 | 304/989 | **30.7** | **SECOND mid-range session (G16), PE side again: CE 0/613 (advances 13–23, under `>32` all day), PE 304/376 = 80.9% (declines 27–37 CROSSED the 32 line intraday)** |
| `vix` | 1.0 | 380/989 | 38.4 | operand moving (28 distinct) |
| `premium_skew` | — | 14/34 | 41.2 | 4th appearance, n=34 — first non-100% read |
| `iv_slope` | 0.8 | 72/149 | 48.3 | iv-roster rows only |
| `futures_oi` | 1.5 | 488/989 | 49.3 | ✅ live |
| `underlying_oi` | 1.0 | 548/989 | 55.4 | ✅ live |
| `basis` | 1.0 | 613/989 | 62.0 | pure CE-mirror again: CE 613/613, PE 0/376 — basis +71.5…+121.05 all session |
| `sentiment_slope` | 1.0 | 648/989 | 65.5 | |
| `drastic_oi` | 1.0 | 693/989 | 70.1 | |
| `sentiment` | 1.0 | 750/989 | 75.8 | |
| `vwma` | 1.0 | 850/989 | 85.9 | |
| `psar` | 1.0 | 850/989 | 85.9 | |
| `supertrend` | 1.0 | 943/989 | 95.3 | near-saturated |
| `iv_abs_band` | 0.8 | 149/149 | 100.0 | frozen input (G12) — **10th session**; stamp 0.106095, inside 0.10–0.12 |

## 4 Data health (§3.7)

| field | 2026-08-04 | 2026-08-05 | **2026-08-06** | class |
|---|---|---|---|---|
| quadrants NEUTRAL | 0/1,242 | 0/1,036 | **0/989** | ✅ live |
| `spurtOiPct`/`spurtPricePct` NULL | 0 | 0 | **0 (99 distinct spurtOi)** | ✅ live |
| `futuresBasis` | LIVE | LIVE | **989/989 (90 distinct, +71.5…+121.05)** | ✅ |
| `advances` | 4–16 | 15–28 | **13–23, 0 nulls (declines 27–37)** | under `>32` ⇒ CE 0%; PE mid-range (§3) |
| `fiiLongPct` | 13.02 | 12.53 | **12.13 (1 distinct, 0 nulls)** | ✅ daily stamp moved |
| `atmIv` | 0.103481 | 0.107333 | **0.106095 (1 distinct)** | frozen — G12, by design |
| `vixLevel` | 43 distinct | 50 distinct | **28 distinct** | ✅ |
| `ivRank` | NULL 100% | NULL 100% | **NULL 989/989** | dead-data (17th session) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 989/989** | by design (un-armed) |

**Capture:** `NIFTY26AUGFUT` 1m **375/375** (last 15:29), **0 misaligned 1m rows (T19 quiet an
11th session)**; `futures_oi_snapshots` 25,047 rows / **363/375** minutes (down from 374 — mild
cadence dip, quadrants still 0 NEUTRAL, watch only); `options_chain_snapshots` 1,256,892 rows /
366 minutes; market-data canary **GREEN** (tickedTokens 73); kite session validated 15:58 IST;
**0 ERROR lines in both services**; `subscriber_health_events` **0 rows**.

**`dot-health` at 15:58 IST** (200/40): dead = standing pair `iv_rank` + `dow`; `fii` (12.13) +
`iv_abs_band` (0.106095) frozen BY DESIGN; everything else alive; `neverCrossing` false on all
(and structurally silent on the day's actual never-crosser, `vwap` — chart operands are outside
the probe registry). **Nothing newly dead.** Both EOD stamps moved day-over-day.

**T23 / G9 (PartialBucketCanary): FIRST fully-quiet session on record — 0 WARNs + 0 suppressed
straddles** (logs intact all day; 08-05 read 1+1, 08-03 2+1). The 08-05 opening-bucket watch point
did not recur.

**Liveness gauges (15:58 IST):** received 1,720.2 s ≈ evaluated 1,720.2 s — both stamped by the
last closed bar of the session (15:29 + latency), equal and consistent with a closed market.

## 5 Paper outcome + shadow book

### 5.0 The fired trade, its unfunded twins, and the §3.5 counterfactual

**The fire:** 11:15 bar, CE, scorer composite 0.7693, four slugs across both roots
(`scalp-golden-crossover-{nifty,sensex-niftyoi}`, `scalp-connect-the-dots-{nifty,sensex-niftyoi}`).
SENSEX funded: both SENSEX slugs pyramided into ONE position — 2 OPENED events at 11:16,
`SENSEX2680678200CE` **×40** (2 lots) @726.90, sub-account 1. EXIT signals 11:42 (STRUCTURAL_STOP
×2, golden-crossover) + 11:45 (TRAILING_STOP, connect-the-dots-nifty); the position closed
**11:43 STRUCTURAL_STOP @682.30-equivalent = −44.60 pts × 40 = −₹1,784.15** (earliest exit wins
the pyramided row — the G11-documented fan-out shape). Both NIFTY twins zero-sized:
`premium=292.7 × lot 65 = ₹19,025.50 > ₹15,000 budget → computedLots=0` (#1075 class; **cumulative
35 fires / 12 unfunded**). Emit latency 19.2–21.3 s (n=7) — the G8 shape, unchanged.

**Counterfactual for the unfunded NIFTY twins:** the champion book holds the same leg from the
11:15 rejections (`NIFTY2681124450CE` @292.70): market-movers' structural stop closed it 11:42 at
**−13.60 pts (−₹965.31)**; the five square-off holds bled to **−16.90 pts (−₹1,179.51 each)** by
15:12. Every exit flavour of the 11:15 bar lost, on both roots. The budget rail's veto was
"right" again — but so was firing nothing at all: this was a chop-day CE fire into a VWAP pin.

**§3.5 volume-floor sole-blocker set (composite passed): 2 rows = 2 legs, one 14:18 CE bar —
both real, closed shadow LOSERS (no model needed): 0W/2L.**

| bar | leg | entry | real shadow exit | net ₹ |
|---|---|---|---|---|
| 14:18 | NIFTY2681124450CE | 288.60 | SQUARE_OFF/STRUCTURAL_STOP (2 rows) | −1,708.96 |
| 14:18 | SENSEX2680678500CE | 511.05 | STOP_LOSS/STRUCTURAL_STOP (2 rows) | −2,963.41 |

**T1's 10th consecutive no-pay.** This is also the day's only would-have-fired set — every other
composite-passing row had a second failed rail.

### 5.1 Shadow book — champion's worst day ever: −₹40,671.30 (10 events, 2 wins)

| variant | closed | net-wins | pts | net ₹ (day) | all-time net ₹ |
|---|---|---|---|---|---|
| **champion** | 32 | 2 | −1,287.85 | **−40,671.30** | **−103,489.14** |
| composite-055 | 4 | 0 | −108.40 | −3,340.20 | −7,184.87 |
| vol-off | 2 | 0 | −145.40 | −3,665.56 | −40,342.20 |
| vol-12k5 | 0 | — | — | — | −28,444.21 (untraded) |

Champion dedupe (§3.24): 32 rows = **10 distinct `(bar, leg, entry)` events**; the only 2 winners
are the 09:24 pair (+₹550.25 / +₹434.09, both SQUARE_OFF). The chop killed everything after:
11:15 CE clusters −₹11,996.53, **13:09 PE clusters −₹23,321.52** (the book flipped PE right as
the tape stopped moving), 13:21 PE stragglers −₹1,665.22, 14:18 CE pair −₹4,672.37. All-time
crosses the ₹1L mark: **−₹103,489.14**. Shadow entry latency p50 **1:18.9** / p95 **1:24.7**
(n=38) — structurally unchanged (G8).

**Multi-exit clusters (§3.24's controlled exit experiment) — the third stop-favouring chop
observation:** 11:15 SENSEX: market-movers STRUCTURAL_STOP **−5.25** vs five SQUARE_OFF holds
**−45.90 each**; 11:15 NIFTY: stop **−13.60** vs holds **−16.90**; 13:09 NIFTY: stop −28.20 vs
holds −25.40 (tie-ish, hold marginally better); 13:09 SENSEX: stop −110.50 vs holds −101.35
(ditto). Morning chop strongly favoured cutting; afternoon a wash. Consistent with the two prior
chop days (07-31, 08-03) and with the owner's 2026-07-31 G11 decision (KEEP the time stop) — no
reopen trigger here.

**T7 nuance, 3rd adverse reading:** composite-055's 4 closes today were ALL challenger-only rows
(the trades its lower floor alone takes): **0/4, −₹3,340.20**. REJECTED stands.

### 5.2 §3.30 freeze telemetry

Sub-account 1: 2 entries (both 11:16, one pyramided position), day PnL −₹1,784.15, first-loss
frozen from 11:43; subs 2–5 never received a funded fire. `discipline-paused` 0. **1 of 5 frozen
— not a freeze-truncation day** (rejections flow to 15:19). All-time scalper book: **12 closes,
−₹7,477.58**. T10: stale OPEN swing positions **18** (unchanged).

## 6 New data points / anomalies

### 6.1 §3.34 heat-gate evaluability — clean on a funded-fire day (no recurrence)

`grep -cE "heat call failed|heat unassessable"` = **0** with one funded entry at 11:16 — the
`PaperMarginClient` call SUCCEEDED today; the 08-05 octet-stream/timeout failure did not recur
(consistent with the root cause in `2026-08-05-f9-heat-cap-inert.md`: the 2000 ms read-timeout
race on the day's first `keyFor()` — by 11:16 the FnO master was long warm). Per the §3.34
correction, zero means "the call ran", not "the cap covered anything": every scalper entry is a
long option BUY carrying no SPAN, so the heat operand stays structurally 0.00% (ledger N23-A,
owner question, unchanged). NEW-1 (the timeout defect) remains PROPOSED — today adds the
first post-incident clean point.

### 6.2 CAS fourth session — smallest jump yet (+8.05)

Freeze at 15:15 (24,627.95 pinned 15:15–15:27), official print 15:28 → 24,636.00. Jump series:
+200.95 / +151.45 / +54.45 / **+8.05** — monotonically shrinking so far; on a 0.30%-range day the
auction had nothing to reprice. Both closes stamped in the rollup regime row per §3.33; nothing
repaired (G18 doctrine).

### 6.3 Mechanical pre-checks

`tools/ledger-consistency-check.py`: **10 REVIEW lines, all standing false-positive classes —
5×[A] snapshot/self-referential (task_37ee83e0, task_53ce441b, task_79092520, task_a2ae20ed,
task_fb8914fc: each "open" line is a quoted STATE block, each has a CLOSED/SHIPPED row), 5×[B]
keyword reference-not-claim (map-return, cross-context, G8, G14, G12).** The 08-05 [C]
accumulating-self-quote class did not recur (the findings window rolled past it — the predicted
4th accrual did not happen). **No ledger edits required; ledger consistent in substance.**

`tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH), 0
DB-only, 0 YAML-only.** Same 2 as 08-03/08-04/08-05: `minervini-cheat-3c` /
`minervini-primary-base` (1.0.2 drafts, name+description only). Republish proposal carried —
**not republished by this run**.

### 6.4 §3.29 — unexercised-path audit: STRUCTURAL_STOP +1; never-fired set unchanged

Fired vocabulary since 07-01: TRAILING_STOP 12, **STRUCTURAL_STOP 6 (+1 today)**, STOP_LOSS 5,
TIME_STOP 5, MANUAL 2. Day's delta: 1 STRUCTURAL_STOP (`stop_loss` non-premium basis fired; the
`atr_multiple` siblings stay INDETERMINATE — not attributable from `close_reason`). The
never-fired set stands: `take_profit premium_pct` (36), `signal_exit` (38), `square_off` (2), tag
`oi-confluence-exit` (8); INDETERMINATE pair (`trailing_stop atr_multiple`, `stop_loss
atr_multiple`) + `stop_loss percent` stand. No shadow TP wave today to re-demonstrate
reachability — the standing evidence (08-04/08-05 waves) is unchanged: the paper book's TP zero
is entry starvation, not bracket distance.

### 6.5 §3.28 gains its first CHART-side case, outside the canary's sight

The `vwap` dot's 0/989 is the fourth-state shape (live, moving, never crosses) on a **chart**
operand — `DotHealthCanary`'s probe registry mirrors §3.7's macro fields, so `neverCrossing`
structurally cannot see it. Not proposing a probe: the VWAP distance is per-bar/per-root (not a
session-wide scalar like breadth), a 15-bps pin day is self-evidently regime, and the dot recovers
the moment the tape moves. Noted so a future 0% `vwap` reading is checked against range first
(the §3.28 min/max-vs-threshold SQL applies verbatim with the chart fields).

## 7 Tuning candidates

Carried from 08-05 with this session's movement. **Nothing here is applied.** Ledger §0 group G is
the authoritative status.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| NEW-1 (08-05) | paper heat-cap margin call | 2000 ms read-timeout race on first `keyFor()` | fix timeout/lazy-load interaction; decide whether repeated gate-inert should page | §6.1: clean today (0 failures on 1 funded entry, master warm) — first post-incident clean point; N23-A coverage question separate | BUG-CLASS (owner/architect) | **PROPOSED — carried** |
| **T30** | `breadth` dot `>32` | fixed | relative / percentile, or reweight | **2nd mid-range session (G16), PE side again: PE 80.9% (declines 27–37 crossed 32 intraday), CE 0/613** — the per-bar-discriminator case now has two PE observations | STRUCTURAL — G16 | **OPEN (evidence sharpened)** |
| T29 | scalper `time_stop` | armed (5 horizons) | — | G11 **DONE (owner 2026-07-31: KEEP)**; today = 3rd chop observation, again stop-favouring (11:15 stops −5.25/−13.60 vs holds −45.90/−16.90) — the decision's direction re-confirmed, no reopen trigger | EXIT-BAND (owner) — G11 | **CLOSED (decision stands; observation logged)** |
| **T27** | relative-floor window | 1.5×median session-start | time-of-day profile (built, default-OFF) | peak 58,353.75 = **p98.4** (thin tape); pre-11:00 share 33.2% (back in range) | STRUCTURAL — G10 | **OPEN; arming rec unchanged (NO)** |
| T28 | `atmIv` frozen daily stamp | frozen | intraday operand | 10th one-distinct-value session (0.106095) | STRUCTURAL — G12 | **OPEN** |
| T3 | `iv_pair` | 0.02 gap | drop or redefine | 0/989 — 14th zero session | STRUCTURAL — G13 | **OPEN (owner)** |
| T23 | partial-bucket tolerance 650 | — | — | **first fully-quiet session: 0 WARNs + 0 straddles** — 08-05's opening-bucket watch point did not recur | STRUCTURAL — G9 | **OPEN — watch (quiet)** |
| T1 | `relativeVolumeMultiplier` 1.5 | — | — | §5.0: **10th consecutive no-pay** — 2-leg sole-blocker set, both real shadow losers | REJECTED | **REJECTED — reconfirmed** |
| T7 | composite threshold 0.600 | — | — | composite-055 challenger-only 0/4 −₹3,340.20 — 3rd adverse marginal-trade reading | REJECTED | **REJECTED — carried** |
| **watch** | `strike-pick` chain-quality | — | none (correct behaviour) | **BSE-weekly prediction FALSIFIED: 0 fails on the SENSEX expiry day** (§2.2) — saturation observed only on monthlies + NSE-weekly eve/day; next: Mon/Tue 08-10/11 NIFTY | REGIME | **WATCH (mechanism narrowed)** |
| NEW (08-04) | mid-session deploys | none today | deploys wait for post-market | 2nd consecutive clean day; T23/G9 fully measurable | ops (owner/architect) | **PROPOSED — carried** |
| NEW (08-03) | minervini republish | published 1.0.1 ×2 | republish 1.0.2 drafts | §6.3 (4th session): name+description only | ops (owner) | **PROPOSED — carried** |
| T10 | stale OPEN paper positions | **18 OPEN (unchanged)** | square off / age out | unchanged class | ops | **OWNER — chronic** |
| T8/T26 | latency | emit 19.2–21.3 s (n=7); shadow p50 1:18.9 / p95 1:24.7 (n=38) | — | structurally unchanged | STRUCTURAL — G8 | OPEN (data) |
| T2 | `iv_rank` | NULL 100% (17th session) | — | E8 pointer stands | STRUCTURAL | carried, not open |
| T16/T12/T19/T22/T24/T21/T6/T15/T17+T13/T20/T25 | — | — | — | §2.1 (38/38 armed, 0 flat); §4 (0 misaligned ×11); reloads 38/0 | — | ✅ remain CLOSED |

**Group G movement: G16 2nd mid-range breadth session (PE side again); G10 peak at p98.4 (thin
tape), share back in range; G9 first fully-quiet session; G12 10th frozen session; G13 14th zero;
T1 10th rejection; T7 3rd adverse; G8 unchanged; G17 5th sign point (−3.84); G11 3rd chop
observation, decision unchallenged. §3.27 mechanism narrowed (BSE weekly null result). §3.28
first chart-side case (`vwap`, canary-blind by design).**

## 8 Honesty caveats

- §5.0's outcomes are REAL shadow-book exits and one real paper trade — no modelled premium
  paths this session (the §4.2 granularity/cost caveats do not apply). The shadow book still
  does not replicate `time_stop`/`signal_exit` (§3.16), so its exits differ from what the ENGINE
  would have done with the same entries; the 11:15 comparison uses the engine's own real exit.
- Champion's −₹40,671.30 is 10 independent events, not 32; the 13:09 PE clusters are −₹23.3k
  across 12 fan-out rows of two bar/legs.
- The regime stamp (chop 0.179) is the continuous-session read per §3.33; the official-close read
  is 0.069 — chop on both, so no doctrine tension today. Continuous efficiency uses the daily
  bar's H/L (both printed pre-freeze; the official close sits inside the range).
- `premium_skew` n=34 — still no read. `composite-055`'s day is n=4, all challenger-only.
- The BSE-weekly null result (§2.2) is ONE observation; the mechanism split (monthly + NSE-weekly
  vs BSE-weekly) is a hypothesis until the next BSE weeklies repeat it.
- This run was **read-only against the live stack**: SELECTs, `docker logs`, `docker inspect`,
  in-container health/actuator GETs. No restart, deploy, write, or config change; nothing
  republished. Docs edits in this PR: this file + README §3.27 amendment + rollup rows.
