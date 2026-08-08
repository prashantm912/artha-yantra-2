# Session findings — 2026-08-07 (data date)

Analysis date: 2026-08-07 (scheduled post-market agent, ran ~16:00–16:45 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,066** (bar times 09:18–15:18, generated 09:19–15:19), signals
**0 ENTRY + 0 EXIT**, paper positions opened **0** / closed **0** (scalper book untouched; one
SWING close post-market 08-06 — §5.2), shadow positions **22** opened (12 champion + 10
challenger; all closed).

**2026-08-07 is a Friday — no expiry on either exchange** (NSE weekly was Tue 08-04, BSE weekly
Thu 08-06). OI bloc fully live, S24 not engaged.

**Signal contract: `NFO:NIFTY26AUGFUT@3m`** — named directly by the rail log lines (logs intact
all session). Unchanged since the 07-27 roll.

**Session regime (§3.25 / §3.33): `chop` 0.168 on the continuous read** — 1d open 24,538.90 →
continuous close **24,557.00** (frozen 15:15–15:28): net **+0.07%**, range 0.44%, efficiency
0.168. Official CAS print 15:29 → **24,570.65** (**+13.65** — the jump series is no longer
monotonic: +200.95 / +151.45 / +54.45 / +8.05 / **+13.65**); the official read is net +0.13%,
efficiency 0.295 (borderline `mixed`). Doctrine stamps the continuous read: **chop — the fourth
post-07-27 chop day** (07-31, 08-03, 08-06, today).

---

## 0 Read this first — the session's headline

1. **Zero fires, and the gate was RIGHT every time it was tested: the day's only sole-blocker
   set (5 composite-passing PE rows, all `scalp-golden-crossover-nifty-pe`, one leg
   `NIFTY2681124800PE`) went 0W/5L** — 3 real shadow STRUCTURAL_STOP losers
   (−₹39.50/−₹530.67/−₹936.33) + 2 modelled 30-min-time-stop losers (−19.95/−12.10 pts). The
   blocking mechanic is notable: all 5 carry aggregate composite **≥ 0.600 with a NULL margin**
   — the required-dot floor inside the `confluence-composite` rail vetoed them (the aggregate
   cleared the line only via optional dots), the same fingerprint as the 07-20
   "positive-blocking-margin" watchlist class. The standing prior (every measured loosening
   loses) gains a required-floor data point.
2. **First live `neverCrossing` firing: the G16 probe caught `breadth` hugging its line.**
   `dot-health` reports `breadth` `neverCrossing: true` — "live and moving, yet supported 1/56
   distinct (bar,side) verdicts; session max 33 hugs the line advances/declines > 32 (distance
   1 ≤ 3)". Raw: CE 0/16 (advances 17–25), PE 16/692 = 2.3% (declines 25–33, crossed 32
   briefly). T30's per-session-bias case gains its 3rd mid-range-ish session, and the probe
   built for it worked on its first opportunity.
3. **A Kite REST outage blipped twice and the resilience stack absorbed both** (§6.1): a single
   quote-parse ERROR at 09:35 IST (Kite returned HTML), then a **circuit-open episode
   14:13–14:16 IST** — quote parse fail → `kite-rest circuit open; serving cached data` ×4
   minutes, with the kite session probe AND an Upstox quote batch timing out in the same window
   (both vendors ⇒ network egress blip, not a Kite defect). Cost: 5 missing
   `futures_oi_snapshots` minutes (370/375) and **24 `chain-unavailable` rejections** (12 PE
   slugs × 2 bars, 14:12/14:15) — the honest gate outcome. Self-healed by 14:17; zero
   strategy-signal ERRORs; first `chain-unavailable` sighting since the 07-31 one-off.
4. **`strike-pick`: 350 fails, ALL SENSEX-rooted, on a NON-expiry Friday — and the full history
   reframes the watch row.** This is the **third consecutive Friday-after-BSE-Thursday-expiry
   SENSEX saturation (07-24: 550 → 07-31: 374 → 08-07: 350)**, and back-history shows 07-23
   (BSE weekly day-of) saturated too (390) — so 08-06's zero, which "falsified" the prediction,
   is the OUTLIER among day-of observations, not the rule. The mechanism candidate shifts from
   "expiry day saturates" to **chain proximity-to-expiry/roll**: the freshly-rolled far-dated
   front weekly fails the delta/premium band, mid-week chains fit it (Wednesdays 07-22 / 07-29 /
   08-05: all zero). §2.2 has the full table; README §3.27 amended again.
5. **Thinnest tape of the series and the mildest floor peak:** signal-future 3m volumes p50
   9,620 / max 80,925 (both series lows); the banded volume-floor produced 16 distinct
   thresholds 8,823.75–20,426.25, peak at **p88** (15 of 125 bars clear) — the same rupee
   band sat at p98.4 on 08-06. `volume-floor` bound 46.9% of first-blocks (500/1,066).
6. **Ops: clean except the Kite blip.** Boot 07:49 IST pre-open with the F10 cold-start
   self-heal (0/38 → 38/0 by 07:51:41), no mid-session recreation (3rd consecutive clean day),
   capture 375/375, 0 misaligned 1m rows (12th clean), `subscriber_health_events` empty,
   0 strategy-signal ERRORs, canary GREEN (tickedTokens 91), kite session validated 15:55 IST.
   Eval rollup reconciles exactly: `confluence-blocked` 1,066 = rows, `fired` 0 = signals.
   T23/G9: **1 unpaired opening-bucket WARN** (−1,625 = 25 lots, 2.0%, bar > Σ1m — the 08-05
   sign/shape again) + 0 straddles.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-08-04 | 2026-08-05 | 2026-08-06 | **2026-08-07** |
|---|---|---|---|---|
| rejections | 1,504 | 1,318 | 1,277 | **1,066** |
| distinct strategies emitting | 38 | 38 | 38 | **34 of 38** (4 CE-family twins silent — chart-gate, not load; §1.1) |
| published + enabled | 44 | 44 | 44 | **44** |
| signals | 0 + 0 | 2 + 2 | 4 + 3 | **0 + 0** |
| paper positions opened | 0 | 1 (−₹2,583.73) | 1 (−₹1,784.15) | **0** |
| bar-time coverage | 09:18–15:18 | 09:18–15:18 | 09:18–15:18 | **09:18–15:18 (full, 25 buckets, no holes)** |
| scored rows | 1,242 (150 CE / 1,092 PE) | 1,036 (206 CE / 830 PE) | 989 (613 CE / 376 PE) | **708 (16 CE / 692 PE) — the most one-sided scored population yet** |
| composite ≥ threshold | 602 (48.5%) | 432 (41.7%) | 62 (6.3%) | **134 (18.9%)** |
| max composite | 0.9118 | 0.8627 | 0.6422 | **0.8511** (cap 0.9574 — only `iv_pair` dead-in-denominator; `vwap` recovered) |
| regime (continuous §3.33) | trend-down 0.871 | mixed 0.551 | chop 0.179 | **chop 0.168 (official 0.295)** |

**Eval counters (V045/V053 rollup, reconciles):** `chart-gate-failed` **2,114**,
`confluence-blocked` **1,066** (= rows), `composite-below-threshold` **390**, `fired` **0**
(= ENTRY signals), `discipline-paused` **0**, Σ **3,570**. Context-less rows reconcile:
**252 `time-window` + 48 `option-side-constraint` + 34 `time-of-day-preference` +
24 `chain-unavailable`** = 358 = 1,066 − 708.

**Engine load state.** Boot **07:49 IST** (02:19:33Z) with the F10 cold-start shape (`0 loaded /
38 unresolved` at 07:49:51 → self-healed **38/0, 0 failed** at 07:51:41, ~85 min pre-open).
`unresolved == 0` ✅. No mid-session recreation — logs intact end-to-end (3rd consecutive clean
ops day on that axis).

### 1.1 Coverage: 34 of 38 — the four silent slugs are the CE-family twins

`scalp-golden-crossover-{nifty,sensex-niftyoi}` and `scalp-open-high-low-{nifty,sensex-niftyoi}`
wrote no rejection all day, while **their `-pe` twins emitted normally** (golden-crossover-nifty-pe
alone carries the day's would-have-fired set). On a tape where the scored population was 16 CE /
692 PE, this is the CE chart gate never passing for those families — the §3.10 load-failure class
is excluded by the boot line (38/0/0), the reload row, and the twins' liveness. Distinguishing
check run, not assumed.

**First-blocking-rail histogram** (1,066 rows, 17 distinct rails): `volume-floor` **500 (46.9%)**,
`time-window` 252, `option-side-constraint` 48, `rsi-band` 42, `time-of-day-preference` 34,
`confluence-composite` 25 (avg margin −0.087; **5 of them margin-NULL with aggregate ≥ 0.600 —
§5.0**), `two-candle` / `chain-unavailable` / `divergence-vol-gate` / `volume-pump` /
`pct-price-move` 24 each, `max-oi-sr-gate` 15, `oi-cross-required` 14, `call-put-delta-filter` 6,
`hero-zero` 6, `oi-divergence-magnitude` 2, `directional-change-gate` 2.

**All-failed-rails expansion (§3.3)** — top rows: `confluence-composite` **708** fails at avg
**0.475 vs 0.600**; `volume-floor` 500 (avg 8,840 vs banded avg 15,598); `strike-pick` **350 —
all SENSEX-rooted (§2.2)**; `rsi-band` 334 (avg 42.79 — FLOOR side, the PE tape); `trend-change` /
`divergence-vol-gate` 104 each; `constituent-gate` 102; `oi-divergence-magnitude` **78 at avg
−9.81** (G17's sign-invariance series gains a 6th point: +13.78 / +26.45 / −8.64 / −9.84 / −3.84 /
**−9.81**; range −16.32…+1.08, still within ±20).

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 G10 / T27 — volume floor: banded, no flat rows, mildest peak yet (p88)

**Registry (§3.14):** 38/38 armed `relative-volume-floor` (published 2026-07-28, unchanged — no
publish stamp newer than the last findings file).

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned (§3.15):**

| bars | min | p50 | p90 | p99 | max | ≥125,000 |
|---|---|---|---|---|---|---|
| 125 | 2,275 | 9,620 | 23,530 | 55,770 | 80,925 | 0 |

Both p50 and max are series lows — the thinnest session yet measured. Blocking thresholds: **16
distinct, 8,823.75 – 20,426.25, no flat floors.** Peak threshold 20,426.25 = **p88** (15 of 125
bars clear) — the mildest peak on record (prior: p94.4–p98.4), i.e. on a uniformly thin tape the
relative floor tracked DOWN as designed instead of pinning at an unpassable level. Pre-11:00
share of `volume-floor` first-blocks: **188/500 = 37.6%** (inside the 27.8–43% range). T1's
sole-blocker veto set is EMPTY today (§5.0).

### 2.2 `strike-pick` — 350 SENSEX-rooted fails on a non-expiry Friday; the mechanism reframed

Full history since 07-20 (fails, by OI-agnostic slug family; reason uniformly
`no strike met the delta/premium band`):

| date | day | expiry | NIFTY-rooted | SENSEX-rooted |
|---|---|---|---|---|
| 07-20 | Mon | none | 0 | 333 |
| 07-21 | Tue | NSE weekly | 535 | 0 |
| 07-22 | Wed | none | 0 | 0 |
| 07-23 | Thu | BSE weekly | 0 | 390 |
| 07-24 | Fri | none | 0 | 550 |
| 07-27 | Mon | none | 175 | 89 |
| 07-28 | Tue | NSE monthly | 534 | 0 |
| 07-29 | Wed | none | 0 | 0 |
| 07-30 | Thu | BSE monthly | 0 | 405 |
| 07-31 | Fri | none | 0 | 374 |
| 08-03 | Mon | none (NSE eve) | 235 | 0 |
| 08-04 | Tue | NSE weekly | 604 | 0 |
| 08-05 | Wed | none | 0 | 0 |
| 08-06 | Thu | BSE weekly | 0 | **0** |
| **08-07** | **Fri** | **none** | **0** | **350** (13 slugs) |

Three readings follow. (a) **Wednesdays are always clean** (07-22/07-29/08-05: 0 on both roots).
(b) **SENSEX saturation clusters Thu–Mon around the BSE Thursday expiry** — including **three
consecutive post-expiry Fridays (550 / 374 / 350)** — and NIFTY saturation clusters Mon–Tue
around the NSE Tuesday expiry. (c) **08-06's zero is the odd one out**, not the falsifier the
08-06 file took it for: 07-23 (BSE weekly day-of) saturated with 390. The candidate mechanism is
therefore **chain proximity to expiry/roll** (a freshly-rolled or about-to-expire front weekly
sits outside the delta/premium band; mid-week chains fit it), not "the expiry day" as an event.
README §3.27 carries a second dated amendment. Next discriminators: Mon/Tue 08-10/11 (NIFTY
cluster expected), Wed 08-12 (expected clean).

### 2.3 Rails with no evidence of miscalibration

`pct-price-move` (avg −0.083 vs 1.0 on a 0.44%-range day), `psar-durability` (0.040 vs 0.050,
n=2), `call-put-delta-filter` (28.02 vs 50), `oi-slope-agree` (0.553, n=30). `rsi-band` at avg
42.79 is the floor side reading mid-band PE chop, not a calibration defect.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (708 scored rows — 16 CE / 692 PE):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|---|
| n | 16 | 112 | 182 | 170 | 168 | 46 | 2 | 12 |
| PE | 16 | 112 | 182 | 158 | 164 | 46 | 2 | 12 |

134 rows (18.9%) ≥ 0.600; max **0.8511**. **Cap:** denominator 18.80 (`iv_rank` withheld);
dead-in-denominator = `iv_pair` 0.8 only (`vwap` recovered from 08-06's pin day) ⇒ cap **0.9574**.

**Dot support rates:**

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_rank` | 0.8 | 0/708 | 0.0 | dead-data, withheld — 18th session |
| `iv_pair` | 0.8 | 0/708 | 0.0 | structurally impossible (G13) — 15th zero session; in Σw |
| **`breadth`** | 1.0 | 16/708 | **2.3** | **G16/T30: CE 0/16 (advances 17–25 under `>32`), PE 16/692 = 2.3% (declines 25–33 — max 33 crossed the line briefly). FIRST live `neverCrossing: true` from the G16 probe: "supported 1/56 distinct (bar,side) verdicts; session max 33 hugs the line (distance 1 ≤ 3)"** |
| `basis` | 1.0 | 16/708 | 2.3 | pure CE-mirror again: CE 16/16, PE 0/692 — basis +65.5…+100.9 all session |
| `oi_spurt` | 1.0 | 50/708 | 7.1 | alive |
| `vwap` | 2.5 | 56/708 | 7.9 | **recovered from 08-06's first-ever 0%** (CE 0/16, PE 56/692) — regime reading confirmed |
| `iv_slope` | 0.8 | 14/104 | 13.5 | iv-roster rows only |
| `premium_skew` | — | 2/8 | 25.0 | 5th appearance, n=8 — still no read |
| `volume` | 1.0 | 208/708 | 29.4 | alive (G6 path) |
| `trending_cross` | 1.0 | 248/708 | 35.0 | alive |
| `futures_oi` | 1.5 | 374/708 | 52.8 | ✅ live |
| `rsi` | 1.0 | 410/708 | 57.9 | |
| `underlying_oi` | 1.0 | 416/708 | 58.8 | ✅ live |
| `sentiment_slope` | 1.0 | 478/708 | 67.5 | |
| `sentiment` | 1.0 | 522/708 | 73.7 | |
| `drastic_oi` | 1.0 | 568/708 | 80.2 | |
| `psar` | 1.0 | 646/708 | 91.2 | |
| `vwma` | 1.0 | 660/708 | 93.2 | |
| `supertrend` | 1.0 | 670/708 | 94.6 | near-saturated |
| `vix` | 1.0 | 696/708 | **98.3** | near-saturated, PE-sided: PE 692/692 = 100%, CE 4/16 (vixLevel 12.14–12.53, 22 distinct — operand moving) |
| `iv_abs_band` | 0.8 | 104/104 | 100.0 | frozen input (G12) — **11th session**; stamp 0.108627, inside 0.10–0.12 |

## 4 Data health (§3.7)

| field | 2026-08-05 | 2026-08-06 | **2026-08-07** | class |
|---|---|---|---|---|
| quadrants NEUTRAL | 0/1,036 | 0/989 | **0/708** | ✅ live |
| `spurtOiPct`/`spurtPricePct` NULL | 0 | 0 | **0** | ✅ live |
| `futuresBasis` | LIVE | LIVE | **708/708 (52 distinct, +65.5…+100.9)** | ✅ |
| `advances` | 15–28 | 13–23 | **17–25 (9 distinct; declines 25–33)** | under `>32` on CE; PE crossed briefly (§3) |
| `fiiLongPct` | 12.53 | 12.13 | **13.80 (1 distinct, 0 nulls)** | ✅ daily stamp moved |
| `atmIv` | 0.107333 | 0.106095 | **0.108627 (1 distinct)** | frozen — G12, by design |
| `vixLevel` | 50 distinct | 28 distinct | **22 distinct** | ✅ |
| `ivRank` | NULL 100% | NULL 100% | **NULL 708/708** | dead-data (18th session) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 708/708** | by design (un-armed) |

**Capture:** `NIFTY26AUGFUT` 1m **375/375** (last 15:29), **0 misaligned 1m rows (T19 quiet a
12th session)**; `futures_oi_snapshots` 25,530 rows / **370/375** minutes (the 5-minute hole is
the 14:13–14:16 Kite circuit episode + the 09:35 blip — attributed, not unexplained; 08-06's
363 was unattributed, watch continues); `options_chain_snapshots` 1,234,698 rows / 372 minutes;
market-data canary **GREEN** (tickedTokens 91); kite session validated **15:55 IST**;
strategy-signal **0 ERROR lines**; market-data **7 ERROR lines, all the two Kite-blip episodes
(§6.1)**; `subscriber_health_events` **0 rows**.

**`dot-health` at 15:59 IST** (200/40): dead = standing pair `iv_rank` + `dow`; `fii` (13.80) +
`iv_abs_band` (0.108627) frozen BY DESIGN; `breadth` **`neverCrossing: true`** (first live
firing — §3); everything else alive. **Nothing newly dead.** Both EOD stamps moved day-over-day.

**T23 / G9 (PartialBucketCanary): 1 unpaired WARN + 0 straddles.** 09:19 IST, opening bucket
(09:15): 3m bar 80,925 vs Σ(3×1m) 79,300, shortfall **−1,625** (25 NIFTY lots, 2.0% of bucket,
bar > Σ1m). Same shape+sign as 08-05's opening-bucket event (−5,720, 88 lots) — the
opening-bucket class now has 2 instances in 3 sessions after 08-06's clean day; still absorbed
by nothing (unpaired, above the 650 tolerance). Watch, magnitude small.

**Liveness gauges (15:59 IST):** received 1,773.1 s = evaluated 1,773.1 s — both stamped by the
last closed bar of the session, equal and consistent with a closed market.

## 5 Paper outcome + shadow book

### 5.0 Zero fires; the §3.5 sole-blocker set is the composite's required-dot floor, 0W/5L

`fired` = 0 — no ENTRY signal, no funded or unfunded trade, all 5 sub-accounts untouched
(`discipline-paused` 0; #1075 cumulative stays 35 fires / 12 unfunded).

**The day's only would-have-fired set** (§3.5: composite ≥ threshold, no other failed rail):
**5 rows, all `scalp-golden-crossover-nifty-pe`, all one leg `NIFTY2681124800PE`, sole blocker
`confluence-composite`.** The mechanic deserves the record: every row carries **aggregate
composite ≥ 0.600 (0.6117–0.8511) with operand = the passing aggregate and margin NULL** — the
rail's REQUIRED-dot floor blocked (the aggregate cleared the line only via optional dots while
the required-only composite fell short of `threshold − optionalGateMargin`). Same fingerprint as
the 07-20 "positive blocking margin" watchlist class. Outcomes — 3 real shadow exits + 2 modelled
(the shadow dedup suppressed re-opens; model = uniform 30-min time stop on the option's 1m/2-min
premium path, a HARNESS choice per §3.16, brackets ±35/25% never touched):

| bar | entry LTP | exit | outcome |
|---|---|---|---|
| 10:30 | 258.50 | real shadow STRUCTURAL_STOP | **−₹39.50** |
| 11:18 | 267.75 | real shadow STRUCTURAL_STOP | **−₹530.67** |
| 11:54 | 267.60 | real shadow STRUCTURAL_STOP | **−₹936.33** |
| 12:18 | 275.80 | modelled 30-min time stop @≈255.85 | **−19.95 pts (WOULD-LOSE)** |
| 12:24 | 277.15 | modelled 30-min time stop @≈265.05 | **−12.10 pts (WOULD-LOSE)** |

**0W/5L — the required-dot floor's veto saved money.** The standing prior (all measured entry
loosenings lose: T1, T7, G13, G10) gains a fifth knob-family data point, this one on the
composite's required/optional structure rather than its level. **T1's volume-floor sole-blocker
set is EMPTY today (n=0)** — REJECTED carried with no new evidence either way.

### 5.1 Shadow book — champion −₹11,713.46 (5 events, 0 wins), one leg all day

| variant | closed | net-wins | pts | net ₹ (day) | all-time net ₹ |
|---|---|---|---|---|---|
| **champion** | 12 | 0 | −165.95 | **−11,713.46** | **−115,202.60** |
| composite-055 | 3 | 0 | −25.05 | −1,863.23 | −9,048.10 |
| vol-12k5 | 3 | 0 | −19.55 | −1,506.50 | −29,950.71 |
| vol-off | 4 | 0 | −24.70 | −1,919.04 | −42,261.24 |

Every position in every book was the SAME leg (`NIFTY2681124800PE` — §3.24 fan-out at its
purest). Champion dedupe: 12 rows = **5 distinct `(bar, leg, entry)` events, 0 wins**. The 10:24
event (7 rows) is the day's multi-exit cluster: **market-movers' STRUCTURAL_STOP −₹600.59 vs six
SQUARE_OFF holds −₹1,376.25 each** — the **4th consecutive stop-favouring chop observation**
(07-31, 08-03, 08-06, today), consistent with the owner's G11 KEEP decision; no reopen trigger.
Cumulative points −248.10. Shadow entry latency p50 **1:19.9** / p95 **1:22.1** (n=22) —
structurally unchanged (G8).

**T7 nuance, 4th adverse reading:** composite-055's one challenger-only row (11:06, the trade its
lower floor alone takes) lost **−₹887.40 (0/1)**. REJECTED stands. `vol-12k5` traded for the
first time since 08-05 (3 closes, all shared-leg losers — no challenger-only rows, so no
marginal-trade read for it).

### 5.2 §3.30 freeze telemetry + swing movement

**No scalper entries** — all 5 sub-accounts untouched, 0/5 frozen, `discipline-paused` 0 (not a
freeze-truncation day; rejections flow to 15:19). All-time scalper book unchanged: 12 closes,
−₹7,477.58. **Swing:** `manas-arora` closed GRWRHITECH by **TRAILING_STOP +₹225.13** at 08-06
20:05 IST (post-market batch, after the 08-06 file was written) — this is the fired-vocabulary
+1 in §6.3 and takes **T10 stale OPEN swing positions 18 → 17**.

## 6 New data points / anomalies

### 6.1 Two Kite REST blips; circuit breaker + fail-soft chain gate both did their jobs

- **09:35:12 IST** — single ERROR: `quote response parse failed: Unexpected character ('<')` (Kite
  answered HTML, one `FuturesOiSnapshotService` tick lost).
- **14:13–14:16 IST** — the same parse failure opened the breaker: `kite-rest circuit open;
  serving cached data` on 4 consecutive minute ticks; the kite session probe
  (`HTTP connect timed out`) AND an Upstox quote batch (`Connect timed out`) failed in the same
  window — **both vendors timing out ⇒ a local network-egress blip, not a Kite-side defect**.
  Downstream: strategy-signal logged one candle-STALE visibility WARN (14:13) and the options
  chain fetch failed for 2 bars → **24 `chain-unavailable` rejections (12 PE slugs × 14:12 +
  14:15)**, the designed honest outcome (block the entry, never trade on a missing chain).
  Recovered 14:17; `futures_oi_snapshots` cadence 370/375 fully attributed.

No action proposed — every layer (breaker, cached-data serving, chain-unavailable gate, STALE
visibility WARN) behaved per design; noting it so the 370/375 cadence dip and the
`chain-unavailable` reappearance are attributed rather than mysterious. First circuit-open
episode in the logged window.

### 6.2 CAS fifth session — the jump series stops shrinking (+13.65)

Freeze at 15:15 (24,557.00 pinned 15:15–15:28), official print 15:29 → 24,570.65. Jump series:
+200.95 / +151.45 / +54.45 / +8.05 / **+13.65** — no longer monotonic; small on a quiet day, as
expected. Both closes stamped in the rollup regime row per §3.33; nothing repaired (G18
doctrine).

### 6.3 §3.29 — unexercised-path audit: TRAILING_STOP +1 (swing); never-fired set unchanged

Fired vocabulary since 07-01: **TRAILING_STOP 13 (+1 — the 08-06 20:05 manas-arora GRWRHITECH
close, `trailing_stop indicator` basis, §5.2)**, STRUCTURAL_STOP 6, STOP_LOSS 5, TIME_STOP 5,
MANUAL 2. The never-fired set stands: `take_profit premium_pct` (36), `signal_exit` (38),
`square_off` (2), tag `oi-confluence-exit` (8); INDETERMINATE pair (`trailing_stop atr_multiple`,
`stop_loss atr_multiple`) + `stop_loss percent` stand. Zero-fire day — no new reachability
evidence; the standing read (paper TP zero = entry starvation, not bracket distance) unchanged.

### 6.4 Mechanical pre-checks

`tools/ledger-consistency-check.py`: **10 REVIEW lines — the identical standing false-positive
set as 08-06** (5×[A] snapshot/self-referential: task_37ee83e0, task_53ce441b, task_79092520,
task_a2ae20ed, task_fb8914fc — each "open" line is a quoted STATE block with a CLOSED/SHIPPED
row elsewhere; 5×[B] keyword reference-not-claim: map-return, cross-context, G8, G14, G12).
**No ledger edits required; ledger consistent in substance.**

`tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH), 0
DB-only, 0 YAML-only.** Same 2 as 08-03…08-06: `minervini-cheat-3c` / `minervini-primary-base`
(1.0.2 drafts, name+description only). Republish proposal carried — **not republished by this
run**.

### 6.5 §3.34 heat-gate check — zero-fire day, grep proves nothing

`grep -cE "heat call failed|heat unassessable"` = 0, but with **zero funded entries the gate
never ran** — per §3.34 this is a no-information day, not a clean point. N23-A (coverage:
`spanMargin` structurally 0.00 on long-option entries) stands as an owner question.

## 7 Tuning candidates

Carried from 08-06 with this session's movement. **Nothing here is applied.** Ledger §0 group G
is the authoritative status.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| NEW-1 (08-05) | paper heat-cap margin call | 2000 ms read-timeout race on first `keyFor()` | fix timeout/lazy-load interaction | zero-fire day — no new evidence (§6.5); N23-A separate | BUG-CLASS (owner/architect) | **PROPOSED — carried** |
| **T30** | `breadth` dot `>32` | fixed | relative / percentile, or reweight | **First live `neverCrossing: true` firing (G16 probe): session max 33, distance 1 from the line; supported 1/56 deduped verdicts.** 3rd session with the operand hugging the line | STRUCTURAL — G16 | **OPEN (probe now corroborates)** |
| T27 | relative-floor window | 1.5×median session-start | time-of-day profile (built, default-OFF) | peak 20,426.25 = **p88 — mildest yet**: on a uniformly thin tape the floor tracked DOWN as designed; pre-11:00 share 37.6% (in range) | STRUCTURAL — G10 | **OPEN; arming rec unchanged (NO)** |
| T28 | `atmIv` frozen daily stamp | frozen | intraday operand | 11th one-distinct-value session (0.108627) | STRUCTURAL — G12 | **OPEN** |
| T3 | `iv_pair` | 0.02 gap | drop or redefine | 0/708 — 15th zero session | STRUCTURAL — G13 | **OPEN (owner)** |
| T23 | partial-bucket tolerance 650 | — | — | 1 unpaired opening-bucket WARN −1,625 (25 lots, 2.0%, bar > Σ1m) + 0 straddles — 2nd opening-bucket instance in 3 sessions (08-05 −5,720) | STRUCTURAL — G9 | **OPEN — watch (opening-bucket class forming)** |
| T1 | `relativeVolumeMultiplier` 1.5 | — | — | sole-blocker set EMPTY (n=0) — no new evidence | REJECTED | **REJECTED — carried** |
| T7 | composite threshold 0.600 | — | — | composite-055 challenger-only 0/1 −₹887.40 — 4th adverse marginal read | REJECTED | **REJECTED — carried** |
| **watch** | `strike-pick` chain-quality | — | none (correct behaviour) | **350 SENSEX fails on a non-expiry Friday — 3rd consecutive post-BSE-expiry Friday (550/374/350); full history reframes mechanism to chain proximity-to-expiry/roll; 08-06's day-of zero is the outlier (07-23 day-of = 390); Wednesdays always clean** (§2.2) | REGIME | **WATCH (mechanism reframed)** |
| NEW (08-04) | mid-session deploys | none today | deploys wait for post-market | 3rd consecutive clean day | ops (owner/architect) | **PROPOSED — carried** |
| NEW (08-03) | minervini republish | published 1.0.1 ×2 | republish 1.0.2 drafts | §6.4 (5th session): name+description only | ops (owner) | **PROPOSED — carried** |
| T10 | stale OPEN paper positions | **17 OPEN (−1: GRWRHITECH closed TRAILING_STOP +₹225.13)** | square off / age out | first organic drain since 07-31 | ops | **OWNER — chronic** |
| T8/T26 | latency | no emits today (n=0); shadow p50 1:19.9 / p95 1:22.1 (n=22) | — | structurally unchanged | STRUCTURAL — G8 | OPEN (data) |
| T2 | `iv_rank` | NULL 100% (18th session) | — | E8 pointer stands | STRUCTURAL | carried, not open |
| T29 | scalper `time_stop` | armed (5 horizons) | — | G11 DONE (owner: KEEP); **4th consecutive stop-favouring chop observation** (10:24 cluster: stop −600.59 vs holds −1,376.25 ×6) | EXIT-BAND (owner) — G11 | **CLOSED (observation logged)** |
| T16/T12/T19/T22/T24/T21/T6/T15/T17+T13/T20/T25 | — | — | — | §2.1 (38/38 armed, 0 flat); §4 (0 misaligned ×12); reloads 38/0 | — | ✅ remain CLOSED |

**Group G movement: G16 first live `neverCrossing` firing (probe works); G10 mildest peak (p88 —
floor tracks a thin tape correctly); G9 opening-bucket class 2nd instance; G12 11th frozen
session; G13 15th zero; T7 4th adverse; T1 no-data day; G8 unchanged; G17 6th sign point (−9.81);
G11 4th stop-favouring chop observation, decision unchallenged. §3.27 mechanism REFRAMED
(chain proximity, not expiry-day; Friday triple 550/374/350). §3.28's `vwap` case closed as
regime (recovered 7.9% the next session).**

## 8 Honesty caveats

- §5.0 mixes 3 REAL shadow exits with 2 MODELLED rows; the model is the uniform 30-min time stop
  on the option's own premium path — a harness parameter, never "the armed fleet-wide stop"
  (§3.16). Chain granularity is ~2-min captures; a 1m bracket touch can be missed (none of the
  legs came near ±35/25%).
- The champion's −₹11,713.46 is 5 independent events on ONE leg — an effective sample of ~5
  entries of the same PE into a chop tape, not 12 observations.
- The §2.2 mechanism reframe is a HYPOTHESIS fitted post-hoc to 15 sessions; the 08-10/11/12
  discriminators are named in advance precisely so it can fail.
- The regime stamp (chop 0.168) is the continuous-session read per §3.33; the official-close
  read is 0.295 (borderline mixed) — doctrine stamps continuous, both recorded.
- `premium_skew` n=8 — still no read. composite-055's marginal-trade reading today is n=1.
- 34/38 coverage is attributed to the CE chart gate (twins' liveness + boot line 38/0/0), not
  proven per-slug; a per-strategy eval denominator read (V053) could pin it exactly but was not
  run for the 4 slugs.
- This run was **read-only against the live stack**: SELECTs, `docker logs`, `docker inspect`,
  in-container health/actuator GETs. No restart, deploy, write, or config change; nothing
  republished. Docs edits in this PR: this file + README §3.27 amendment + rollup rows.
