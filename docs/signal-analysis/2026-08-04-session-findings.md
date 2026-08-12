# Session findings — 2026-08-04 (data date)

Analysis date: 2026-08-04 (scheduled post-market agent, ran ~15:55–16:45 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,504** (bar times 09:18–15:18), signals **0 ENTRY + 0 EXIT**,
paper positions opened **0** / closed **0**, shadow positions opened **33**
(23 champion + 10 challenger; all closed).

**2026-08-04 is a Tuesday and the NSE WEEKLY expiry day** (the 08-04 NIFTY series expired at the
close; BSE weekly is Thu 08-06; neither exchange has a monthly expiry — S24 suppression correctly
did NOT engage: 0 NEUTRAL quadrants, 0 null spurt pcts, basis LIVE on all 1,242 context-bearing
rows).

**Signal contract: `NFO:NIFTY26AUGFUT@3m`** — derived per §3.18 from context closes
(24,542.00–24,640.10, inside the future's day range) and every `wouldBeLeg`; the canary log line
was unavailable because the container was recreated twice (see §6.2). Unchanged since the 07-27
roll.

**Session regime (§3.25 / G15): `trend-down`, efficiency 0.871 on the CONTINUOUS session** —
open 24,703.90, continuous-session close 24,463.45 (net **−0.97%**, range 1.12%). The OFFICIAL
close is 24,614.90 (CAS auction — see §6.1), which reads efficiency 0.323 = mixed; the regime
stamp uses the continuous session because that is what an intraday stop can trade (§6.1 sets the
doctrine, new README §3.33).

---

## 0 Read this first — the session's headline

1. **The 08-03 "bogus closing tick" verdict was WRONG, and the mechanism is now identified: NSE/BSE
   launched the Closing Auction Session (CAS) on 2026-08-03 — yesterday was its FIRST day, today its
   second.** Continuous trading in F&O (Category I) stocks ends **15:15 IST**; a call auction
   15:15–15:30 sets the official closing price (finalised 15:30–15:35). What the 08-03 file read as
   a "bogus tick refuted by three markets" is the CAS equilibrium close arriving as a late print.
   **Proof from today's data:** the day's EXPIRING NIFTY options converged to settlement
   **24,613–24,615** across five strikes at 15:30 (24400CE=213.10, 24450CE=162.65, 24500CE=112.60,
   24550CE=63.65, 24600CE=14.55; every PE ≤1.90) — exactly the official close 24,614.90 the daily
   bar carries, and ~+151 above the last continuously-traded level 24,463.45. The option market
   KNEW the auction close; it is real, not poison. Yesterday's official close 24,774.30 is likewise
   confirmed by public market reports (a widely-noted NIFTY/SENSEX close divergence on CAS launch
   day). **T31's repair proposal is RETRACTED** (a "repair" would corrupt the official close);
   **T31's capture-time basis-divergence guard is RETRACTED as designed** (it would quarantine every
   legitimate CAS close daily). Ledger **G18 rescoped**; README **§3.32 amended**, new **§3.33**;
   dated addendum appended to the 08-03 file. What SURVIVES: the G15 regime stamp must use the
   CONTINUOUS session (an intraday stop cannot trade the auction), so 08-03's `chop 0.007` stamp
   stands — with its rationale corrected.
2. **Both market-data (14:44 IST) and strategy-signal (14:45 AND 15:24 IST) were RECREATED
   MID-SESSION** — deploy activity during market hours, against standing doctrine. Measured impact:
   none on data (1m capture 375/375 KITE-sourced through both windows; eval buckets advance
   normally to the close; rejections flow to 15:18), but the morning container logs are DESTROYED —
   the boot line, the PartialBucketCanary WARN count (T23: **unmeasurable this session**) and the
   emit-latency summary are gone, and the actuator counters were zeroed twice (the DB rollup
   V045/V053 is the only session record — it reconciles: `confluence-blocked` 1,504 = rows exactly).
3. **Zero fires, and the shadow book had its BEST DAY EVER: champion +₹18,302.08 (23 closes,
   → 8 deduped events, 5 wins), driven by the first TAKE_PROFIT wave in the book's history outside
   gap-theory/market-movers** — 8 TP closes, ALL on SENSEX PE legs on the trend-down day (the T21
   +35% bracket paying for the first time since it was armed fleet-wide on 63/63 by #990). The
   composite passed 602 of 1,242 scored rows (48.5%, the highest share in the folder) on a
   PE-dominant tape (1,092 PE / 150 CE — the first PE-majority session), and every passing PE entry
   the gate then blocked was blocked by `volume-floor`/`strike-pick`-class rails. All-time champion:
   **−₹63,842.25** (from −₹82,144.33).
4. **`strike-pick` 604 fails, ALL NIFTY-rooted (17 slugs, CE and PE), on the NSE weekly expiry day —
   the eve-then-day-of pattern: 08-03 eve 235 → 08-04 expiry day 604.** All prior weekly Tuesdays
   in the table (07-08/15/22/29) showed ZERO, so this is new behaviour of the chain/band since
   08-03, coincident with CAS. The gate declining an unusable chain is still correct behaviour; no
   tune. Watch discriminators unchanged (§2.2).
5. **Every liveness oracle that survives a restart is clean:** DB eval rollup `confluence-blocked`
   1,504 = rejection rows; `fired` 0 = signals exactly; `chart-gate-failed` advances every bucket
   through 14:57 and the 15:00–15:18 tail matches the time-windows; capture 375/375; OI snapshots
   374/375 minutes; 0 misaligned 1m rows (9th clean session); `subscriber_health_events` empty;
   0 ERROR lines in both services' surviving logs; kite session validated today; market-data canary
   GREEN.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-30 | 2026-07-31 | 2026-08-03 | **2026-08-04** |
|---|---|---|---|---|
| rejections | 1,118 | 1,055 | 1,272 | **1,504** |
| distinct strategies emitting | 38 | 20 | 34 | **38 of 38 (19 sensex, 18 -pe) — first full-fleet session** |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| signals | 2 ENTRY + 2 EXIT | 11 ENTRY + 6 EXIT | 4 ENTRY + 3 EXIT | **0 + 0** |
| paper positions opened | 0 | 5 (+₹69.58) | 1 (−₹743.33) | **0** |
| bar-time coverage | 09:18–15:18 | 09:19–13:34 (freeze) | 09:19–15:04 | **09:18–15:18 (full)** |
| scored rows | 814 | 835 (all CE) | 998 (896 CE / 102 PE) | **1,242 (150 CE / 1,092 PE) — first PE-majority session** |
| composite ≥ threshold rows | 118 (14.5%) | 323 (38.7%) | 273 (27.4%) | **602 (48.5%) — record share** |
| max composite | 0.8627 | 0.8511 | 0.8511 | **0.9118 (ties 07-29's record; cap 0.9574)** |
| regime (§3.25) | mixed 0.434 | chop 0.171 | chop 0.007 (continuous) | **trend-down 0.871 continuous (official-CAS close reads 0.323 mixed — §6.1)** |

**Eval counters (from the V045/V053 DB rollup — the actuator was zeroed by the §6.2 restarts):**
`chart-gate-failed` **1,966**, `confluence-blocked` **1,504**, `composite-below-threshold` **100**,
`fired` **0**, `discipline-paused` **0**, Σ **3,570**; eval failures 0 on the surviving boots. The
context-less rows reconcile exactly: 216 `time-window` + 40 `time-of-day-preference` + 6
`option-side-constraint` = **262** = 1,504 − 1,242.

**Engine load state.** `engine_reloads`: overnight deploy pair 00:11/00:12; **session boot 08:12**
with the documented F10 cold-start shape (`0/38 unresolved` → self-healed `38/0` at 08:14:30, 2m23s
pre-open); 08:40 periodic reconcile; then the two mid-session recreations — installs at
**14:45:19** and **15:25:39**, each `38 loaded / 0 unresolved / 0 errors`. `unresolved == 0` ✅ on
every row.

**First-blocking-rail histogram** (1,504 rows, 17 distinct rails):

| rail | n | share | avg margin |
|---|---|---|---|
| volume-floor | **986** | **65.6%** | −19,100.0 |
| time-window | 216 | 14.4% | — |
| rsi-band | 84 | | floor side (avg operand 41.68 on the PE tape) |
| time-of-day-preference | 40 | | — |
| confluence-composite | 26 | | −0.144 |
| pct-price-move / divergence-vol-gate / volume-pump / two-candle | 24 each | | — |
| oi-cross-required | 18 | | — |
| supertrend-15m | 12 | | first appearance as a first-block in weeks |
| directional-change-gate / option-side-constraint | 6 each | | — |
| call-put-delta-filter / morning-opening-formation / max-oi-sr-gate | 4 each | | — |
| vwap-distance | 2 | | — |

**All-failed-rails expansion (§3.3)** — top rows: `confluence-composite` **1,218** fails at avg
operand **0.591 vs 0.600** (the PE tape pushed the whole population to the line — near-miss mass,
not miscalibration); `volume-floor` 988 (avg 25,104 vs banded avg 44,195); `strike-pick` **604**
(§2.2); `rsi-band` 488 (avg 41.68, floor side); `constituent-gate` 158 (up from 18 — FAIL_OPEN);
`oi-divergence-magnitude` 130 at avg operand **−8.64** vs threshold 20 (note the NEGATIVE mean —
the G17 sign-invariance baseline now has both signs on record: 07-31 +13.78, 08-03 +26.45, 08-04
−8.64); `oi-slope-agree` 74, `max-oi-sr-gate` 63, `supertrend-15m` 40, `hero-zero` 34,
`oi-interval-and-60m-trend` 12, `iv-buyer-cap` 2 — the PE families' rails all exercised on the
first PE-majority tape.

### 1.1 Interior coverage (§3.11) — full session, no holes

25 populated 15-min buckets 09:15–15:15 (n = 8/6/80/64/68/80/80/90/60/90/90/80/80/80/80/80/80/80/
70/60/24/30/30/10/4). The 10:15 bucket spans 34 distinct slugs (widest fan-out bar of the session).
Both mid-session recreations are INVISIBLE in this table — no hole at 14:45 or 15:24.
`subscriber_health_events`: **0 rows**.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 G10 / T27 — volume floor: banded, no flat rows; mild opening peak

**Registry (§3.14):** 38/38 armed `relative-volume-floor` (published 2026-07-28, unchanged),
**0 flat floors**. Observed threshold band **15,258.75 – 62,302.50**.

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned (§3.15):**

| bars | min | p50 | p90 | p99 | max | ≥125,000 |
|---|---|---|---|---|---|---|
| 125 | 3,380 | 28,990 | 57,590 | 100,035 | 124,735 | **0** |

Peak threshold 62,302.50 = **p94.4** (7 of 125 bars clear) — milder than 08-03/07-29's p98.4.
Pre-11:00 share of `volume-floor` blocks: **274/986 = 27.8%** (low end of the 28–43% range). The
floor bound 65.6% of first-blocks on a session whose passing PE set the shadow book then monetised
— see §5.0 for what the floor's sole-blocker set actually contained (2 CE legs, both losers: the
floor again vetoed the RIGHT rows).

### 2.2 `strike-pick` 604 fails, ALL NIFTY-rooted, on the NSE WEEKLY expiry day

| session | day | expiry | NIFTY-rooted | SENSEX-rooted |
|---|---|---|---|---|
| 2026-07-24 | Fri | none | 0 | 550 |
| 2026-07-28 | Tue | NSE monthly | 534 | 0 |
| 2026-07-29 | Wed | none | 0 | 0 |
| 2026-07-30 | Thu | BSE monthly | 0 | 405 |
| 2026-07-31 | Fri | none | 0 | 374 |
| 2026-08-03 | Mon | none (NSE-weekly eve) | 235 | 0 |
| **2026-08-04** | **Tue** | **NSE weekly** | **604 (17 slugs, CE+PE, 09:21–14:18)** | **0** |

The eve→day-of escalation (235 → 604) fits the decayed-front-weekly-premium-band mechanism, and
§3.27's "an expiry saturates the expiring root's strike-pick" now has its first WEEKLY instance.
**But note what is genuinely new: every prior weekly Tuesday in the folder showed ZERO fails**, so
weekly expiries did not do this before 08-03 — the chain/band behaviour changed coincident with
CAS week. Side effect with teeth: the picker refused every NIFTY leg, so **the entire shadow book
traded SENSEX legs only today** (§5.1). Still classified correct-behaviour/no-tune; WATCH row
discriminators unchanged (Mon/Tue 08-10/11 NIFTY, Fri 08-07 SENSEX).

### 2.3 Rails with no evidence of miscalibration

`pct-price-move` (avg −0.335 vs 1.000), `psar-durability` (0.031 vs 0.050),
`call-put-delta-filter` (18.07 vs 50.00), `iv-buyer-cap` (0.414 vs 0.400 — 2 rows, working as
designed), `vwap-distance` (margin 0.000 — an exact-line block, n=2), `rsi-cooloff` absent. The
`rsi-band` floor-side surge (84 first-blocks, avg 41.68) is the PE families meeting a genuinely
weak-RSI tape — regime, not a tune candidate.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (1,242 scored rows — 150 CE / 1,092 PE):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|---|
| n | 2 | 38 | 102 | 362 | 350 | 220 | 92 | 76 |
| PE | 2 | 38 | 48 | 292 | 324 | 220 | 92 | 76 |

602 rows (48.5%) ≥ 0.600 — record share; max **0.9118** (ties 07-29). CE rows top out in the 0.6
bucket; everything ≥0.7 is PE. The composite read the trend-down day correctly — and the engine
still fired zero: what the composite passed, `volume-floor` (65.6% of first-blocks), the
time-windows and `strike-pick` removed.

**Dot support rates** (side-split where the dot is a pure side-mirror today):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_rank` | 0.8 | 0/1,242 | 0.0 | dead-data, withheld from Σw — 15th session |
| `iv_pair` | 0.8 | 0/1,242 | 0.0 | structurally impossible (G13) — 12th zero session; in Σw |
| `oi_spurt` | 1.0 | 150/1,242 | 12.1 | alive, both sides (CE 28/150, PE 122/1,092) |
| `basis` | 1.0 | 150/1,242 | 12.1 | **pure side-mirror today: CE 150/150, PE 0/1,092** — basis stayed +84…+97 all session, supporting CE / opposing every PE row |
| `volume` | 1.0 | 254/1,242 | 20.5 | alive (G6 path) |
| `trending_cross` | 1.0 | 414/1,242 | 33.3 | alive |
| `sentiment_slope` | 1.0 | 606/1,242 | 48.8 | |
| `vwap` | 2.5 | 782/1,242 | 63.0 | |
| `rsi` | 1.0 | 792/1,242 | 63.8 | |
| `futures_oi` | 1.5 | 792/1,242 | 63.8 | ✅ live |
| `underlying_oi` | 1.0 | 800/1,242 | 64.4 | ✅ live |
| `sentiment` | 1.0 | 838/1,242 | 67.5 | |
| `psar` | 1.0 | 952/1,242 | 76.7 | |
| `drastic_oi` | 1.0 | 982/1,242 | 79.1 | |
| `iv_slope` | 0.8 | 146/180 | 81.1 | alive (present on the 180 iv-roster rows) |
| **`breadth`** | 1.0 | 1,092/1,242 | **87.9** | **pure side-mirror: PE 1,092/1,092, CE 0/150** — advances 4–16 all day, deep under the `>32` line ⇒ CE side 0%, PE side 100%. G16's step function, now shown to be SIDE-mirrored: the 11th session and still never a mid-range value on either side |
| `premium_skew` | — | 30/34 | 88.2 | second appearance (hero-zero family), n=34 — still no read |
| `vwma` | 1.0 | 1,156/1,242 | 93.1 | |
| `vix` | 1.0 | 1,172/1,242 | 94.4 | near-saturated on the one-sided tape (PE 1,064/1,092, CE 108/150); operand moving (43 distinct) |
| `supertrend` | 1.0 | 1,208/1,242 | 97.3 | |
| `iv_abs_band` | 0.8 | 180/180 | 100.0 | frozen input (G12) — 8th session; stamp **0.103481**, inside 0.10–0.12 |

**Cap (§3.1-style):** denominator 18.80 (`iv_rank` withheld); dead-in-denominator = `iv_pair` 0.8
only ⇒ cap **0.9574**. Observed max 0.9118 — the market side set the ceiling, not the roster.

## 4 Data health (§3.7)

| field | 2026-07-31 | 2026-08-03 | **2026-08-04** | class |
|---|---|---|---|---|
| quadrants NEUTRAL | 0/835 | 0/998 | **0/1,242** | ✅ live (weekly expiry ≠ S24 — correct) |
| `spurtOiPct`/`spurtPricePct` NULL | 0 | 0 | **0/1,242** (102 distinct spurtOi) | ✅ live |
| `futuresBasis` | LIVE | LIVE | **1,242/1,242 (102 distinct)** | ✅ |
| `advances` | 21–35 | 38–44 | **4–16, 0 nulls** | ⚠ deep under `>32` ⇒ breadth = pure PE-side dot today (G16) |
| `fiiLongPct` | 0 nulls (10.38) | 0 nulls (11.12) | **0 nulls (13.02, 1 distinct)** | ✅ daily stamp moved |
| `atmIv` | 1 distinct | 1 distinct (0.107578) | **1 distinct (0.103481)** | frozen — G12, by design |
| `vixLevel` | moving | moving | **43 distinct** | ✅ |
| `ivRank` | NULL 100% | NULL 100% | **NULL 1,242/1,242** | dead-data (since 07-02) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 1,242/1,242** | by design (un-armed) |

**Capture:** `NIFTY26AUGFUT` 1m **375/375** (last bar 15:29), all KITE-sourced through both restart
windows; **0 misaligned 1m rows (T19 quiet a 9th session)**; `futures_oi_snapshots` 25,806 rows /
**374/375** minutes; market-data canary **GREEN** (tickedTokens 87); kite session validated today;
0 ERROR lines in both services' surviving logs.

**`dot-health` canary at 16:03 IST** (200/40): dead = standing pair `iv_rank` + `dow`; `fii`
(13.02) + `iv_abs_band` (0.103481) frozen BY DESIGN (EOD daily operands); everything else alive;
`neverCrossing` false on all. **Nothing newly dead.** Both EOD stamps moved day-over-day.

**T23 / G9 (PartialBucketCanary): UNMEASURABLE.** Both recreations destroyed the in-session logs
(the WARN grep window covers a container that no longer exists). No WARN/straddle count can be
reported for 09:15–15:24; the 15:24–close log has none. First unmeasurable session since the metric
existed — a direct cost of the §6.2 mid-session deploys.

**SENSEX 1d bar for 08-04 was ABSENT at analysis time** (~16:15 IST) while `NIFTY 50`'s was
present (fetched 15:45). Likely ingest-schedule timing; the ingest-health canary owns it — noted,
not escalated.

## 5 Shadow-book outcomes + the counterfactual

### 5.0 README-§4.2 counterfactual — the §3.5 set is 2 CE legs; both lose; T1's 8th no-pay

The volume-floor-sole-blocker set (composite passed) is **2 rows = 2 deduped legs**, both
`scalp-connect-the-dots-sensex-niftyoi` CE on `SENSEX2680678300CE` (09:51 @694.25, 09:57 @668.50) —
on the day the PE side was the one paying. Model (harness choice per §3.16): +35%/−25%/30-min
stop/15:12 square-off, priced off `options_chain_snapshots` (~2-min granularity):

| bar | leg | entry | stop-model exit | stop pts | hold-to-15:12 pts |
|---|---|---|---|---|---|
| 09:51 | SENSEX2680678300CE | 694.25 | time ~10:21 (10:20 snap 626.70 / 10:22 597.80) | **−67.6 to −96.5** | −327.45 (15:12 = 366.80) |
| 09:57 | SENSEX2680678300CE | 668.50 | time ~10:27 (10:28 snap 592.50) | **−76.0** | −301.70 |

No TP/SL touch inside either window. Both models lose on both legs; the floor's veto was again
correct. **T1's 8th consecutive no-pay.** (The 09:51 leg's REAL shadow exits agree: all 6 fan-out
rows negative, −₹22,537.76 — §5.1.) Nothing for G11 here — trend-down day, and the stop-vs-hold gap
(+231 to +260 pts/leg in the stop's favour) is the trend-day shape already on record.

### 5.1 Shadow book — champion +₹18,302.08 (8 events, 5 wins): best day in the book's history

| variant | closed | net-wins | pts | net ₹ |
|---|---|---|---|---|
| **champion** | **23** | 15 | **+999.40** | **+18,302.08** |
| composite-055 | 2 | 2 | +291.20 | +5,670.41 |
| vol-off | 4 | 2 | +34.45 | +397.83 |
| vol-12k5 | 4 | 2 | +34.45 | +397.83 |

Champion dedupe (§3.24): 23 rows = **8 distinct `(bar, leg, entry)` events**, ALL SENSEX legs
(strike-pick refused every NIFTY leg — §2.2):

- 09:51 `78300CE` @694.25 ×6 — **−₹22,537.76** (STOP_LOSS ×4 / SQUARE_OFF / STRUCTURAL_STOP — the
  CE mistake, killed by the brackets)
- 10:33 `79200PE` @620.45 ×7 — **+₹29,880.13, all TAKE_PROFIT**
- 11:00 `79200PE` @641.95 — −₹555.38 (STRUCTURAL_STOP)
- 11:36 `79200PE` @675.70 — +₹4,766.83 (TAKE_PROFIT)
- 12:00 `79000PE` @678.95 / @705.00 ×5 — +₹6,989.04 (SQUARE_OFF)
- 13:09 / 13:27 `78900PE` — −₹335.99 / +₹95.21 (STRUCTURAL_STOP)

**The 8 TAKE_PROFIT closes are the first in the book's history outside the gap-theory /
market-movers families** — connect-the-dots, golden-crossover, open-high-low, trend-change,
trending-oi and two-candle `-pe` variants all TP'd. That is the T21/#990 fleet-wide +35% bracket
(armed 63/63 since 07-25) paying for the FIRST time, and it required a PE trend day to be
reachable. §3.16's "all 8 historical TP closes belong to gap-theory/market-movers" is now
historical-only. Fan-out caveat as always: +₹18,302.08 is 8 independent events, and the 10:33
TP cluster carries +₹29.9k of it across 7 slug-rows (one bar, one leg).

**All-time league:** champion **331 closed / 134 net-wins / +612.00 pts / −₹63,842.25**; vol-off
−₹28,767.59; vol-12k5 −₹21,159.37 (`vol-12k5 > vol-off` ordering survives a 10th session);
composite-055 **−₹4,744.58** (its 2 rows today both won — the extra trades IT alone takes remain
its risk, but today they paid). Shadow entry latency p50 **1:17.9** / p95 **1:20.3** (n=33) —
structurally unchanged (G8).

### 5.2 Paper book + §3.30 freeze telemetry — nothing to report, by upstream construction

0 fires ⇒ 0 paper entries ⇒ 0 closes; all 5 sub-accounts untouched; `discipline-paused` **0**.
Trend row: 07-31 5/5 frozen by 13:34 → 08-03 1/5 entered → **08-04 0/5 entered (no funded fire
existed)**. All-time scalper book unchanged: 10 closes, −₹3,109.70. The 17 stale OPEN swing
positions (T10) stand.

## 6 New data points / anomalies

### 6.1 ⚠️⚠️ CAS (Closing Auction Session) — the 08-03 "bogus tick" was the new official close

Since **2026-08-03**, NSE/BSE run a Closing Auction Session: continuous trading in F&O-enabled
(Category I) stocks ends **15:15 IST**, a call auction 15:15–15:30 determines the official closing
price (finalised 15:30–15:35), and the index's official close is built from CAS constituent closes.
Both sessions since launch show the same data shape, which the 08-03 file misread as a bad tick:

1. **The index 1m stream freezes just after 15:14–15:15** (today: NIFTY pinned at 24,463.45 from
   15:15, SENSEX at 78,324.56 from ~15:20) — that is continuous trading ENDING, not a feed outage.
2. **A late print carries the auction close** (today: 15:28 bar jumps +151.45 to 24,614.90; SENSEX
   15:29 +104.39 to 78,428.95; yesterday: 15:29 +200.95 to 24,774.30).
3. **The futures keep trading to 15:30 at continuous-market levels**, so the future-vs-official-close
   "basis" looks broken at the close (today −50 vs the +84…+97 measured all afternoon; yesterday
   −124). This is auction-vs-continuous mechanics, not an arbitrage and not corruption.
4. **Decisive evidence — the expiring chain:** today's 08-04 NIFTY options settled on the CAS
   close, and at 15:30 five strikes' LTPs converge to settlement **24,613–24,615** with sub-point
   precision (24400CE 213.10 / 24450CE 162.65 / 24500CE 112.60 / 24550CE 63.65 / 24600CE 14.55;
   PEs 0.15–1.90). The option market priced the official close exactly. Yesterday's official close
   24,774.30 is likewise publicly confirmed (the CAS-launch-day NIFTY/SENSEX close divergence was
   widely reported).

**Corrections this forces (all applied in this PR):**
- **T31 repair: RETRACTED.** The 08-03 1d bar (close 24,774.30) is the OFFICIAL close — correct as
  stored; Kite "not correcting it within hours" (checked today: unchanged) is because there is
  nothing to correct. The swing RS benchmark SHOULD carry official closes; no hand UPDATE.
- **T31 guard: RETRACTED as designed.** A capture-time quarantine of index ticks diverging from
  futures basis would quarantine every legitimate CAS close, every day, ~15:28–15:30.
- **G15 regime doctrine (new README §3.33):** the regime stamp uses the **CONTINUOUS session**
  (open → last continuous tick) because a 30-minute intraday stop cannot trade the auction. The
  official close stays authoritative for daily bars, RS-rank and backtests. Both values are stamped
  in the rollup from now on. 08-03's `chop 0.007` stamp STANDS (as the continuous-session read);
  today stamps `trend-down 0.871` continuous vs 0.323-mixed official.
- **Residual real wrinkle (G18's rescope):** the 1m `TICK_AGG` bar at 15:28/15:29 carries the
  auction print as if traded in that minute, and `options_chain_snapshots.spot_price` flips to the
  auction close at 15:28+ — any session-tail analytics (VWAP, 3m rollups, last-30-min reads) now
  mix two price regimes. Also NIFTY-future daily settlement now derives from CAS-close-anchored
  values while intraday marks are continuous — nothing in our engine reads that today, but backtest
  parity on post-CAS data should be revisited when option-replay covers these dates.
- The 08-03 findings file carries a dated addendum; README §3.32 is amended in place (its SQL
  discriminators remain useful — they now DETECT the CAS shape rather than "prove poison").

### 6.2 ⚠️ Mid-session container recreations (14:44 and 15:24 IST)

`docker inspect`: market-data StartedAt **14:44:19 IST**, strategy-signal **15:24:15 IST** — and
`engine_reloads` shows strategy-signal install rows at **14:45:19** AND **15:25:39**, i.e. it was
recreated TWICE inside market hours (the 14:4x wave took market-data + strategy-signal together;
the 15:24 one strategy-signal alone). This is deploy activity during a live session — against the
standing "no restarts/deploys mid-session" doctrine (owner/architect action item, not this run's).
Measured impact: **data intact** (capture 375/375 with KITE-sourced bars through both windows; eval
buckets advance 22/bucket through 14:57; rejections to 15:18; both reloads came up 38/0/0 within
~60 s) — but **evidence destroyed**: morning boot line, T23 canary WARN count (first unmeasurable
session), emit-latency summary, and cumulative actuator counters (zeroed twice; the V045/V053 DB
rollup is the only full-session record — it reconciles exactly). Timing correlates with today's
merges (#1288/#1291 landed on main today); the 15:24 recreate came 6 minutes before the close.

### 6.3 Mechanical pre-checks

Run from the shared checkout on `docs/2026-08-04-session-closeout` (origin/main's docs superset at
run time; a concurrent session held the checkout — branch not switched per the shared-checkout
trap).

`tools/ledger-consistency-check.py`: **12 REVIEW lines, all false positives, same classes as
08-03's 11** — 5×[A] snapshot-line/self-referential FPs (task_37ee83e0, task_53ce441b,
task_79092520, task_a2ae20ed, task_fb8914fc — dispositions unchanged from the 08-03 file §6.3),
5×[B] keyword reference-not-claim FPs, 2×[C] "T18 promotion" FPs (the second is the 08-03 file's
own §6.3 text quoting the first — a self-referential FP the checker will now flag every session;
if it accumulates, teach the checker to skip quoted dispositions). **No ledger edits required;
ledger consistent in substance.**

`tools/published-config-drift.py`: **69 published — 69 matched (67 clean, 2 STALE-PUBLISH), 0
DB-only, 0 YAML-only.** The same 2 as 08-03: `minervini-cheat-3c` / `minervini-primary-base`
(1.0.2 drafts, name+description only). Republish proposal carried — **not republished by this
run**.

### 6.4 §3.29 — unexercised-path audit: no movement (no closes today)

Fired vocabulary since 07-01 unchanged: TRAILING_STOP 11, STRUCTURAL_STOP 5, STOP_LOSS 5,
TIME_STOP 5, MANUAL 2. Armed paths (10 rows + tag) unchanged; day's delta **zero fires**. The
never-fired set stands: `take_profit premium_pct` (36), `signal_exit` (38), `square_off` (2), tag
`oi-confluence-exit` (8); INDETERMINATE pair (`atr_multiple` ×2) + `stop_loss percent` stand.
**New nearest-miss evidence for `take_profit`:** the shadow book hit +35% TP on 8 PE legs today
(§5.1) — the bracket is REACHABLE this regime on the PE side; the paper book just never held a
position on a day like this (0 fires). Classification stays "unreachable-this-regime" for the
paper book, now with a sharper cause: entry starvation, not bracket distance.

## 7 Tuning candidates

Carried from 08-03 with this session's movement. **Nothing here is applied.** Ledger §0 group G is
the authoritative status.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T31** | 08-03/08-04 index closes | official CAS closes stored | ~~repair + guard~~ **RETRACTED** — closes are correct; G18 RESCOPED to CAS-aware analytics (regime = continuous session; session-tail reads mix two price regimes; parity revisit when replay covers CAS dates) | §6.1 — expiring-chain settlement convergence 24,613–24,615 = the stored close; official 08-03 close publicly confirmed | DOCTRINE (was DATA-INTEGRITY) | **RETRACTED / RESCOPED (ledger G18 updated)** |
| **T29** | scalper `time_stop` | armed | owner decision (G11) | no new chop-day evidence (trend-down); chop ledger stands 2 observations, both stop-favouring | EXIT-BAND (owner) — G11 | **OWNER-DECIDABLE (carried)** |
| **T30** | `breadth` dot `>32` | fixed | relative / percentile, or reweight | **11th session never-in-between, now shown SIDE-MIRRORED**: advances 4–16 ⇒ PE 100% / CE 0% — a per-session side bias worth ±5.3pp of composite | STRUCTURAL — G16 | **OPEN** |
| **T27** | relative-floor window | 1.5×median session-start | time-of-day profile (built, default-OFF) | peak 62,302.50 = **p94.4**, pre-11:00 share 27.8% — mildest reproduction yet | STRUCTURAL — G10 | **OPEN; arming rec unchanged (NO)** |
| **T28** | `atmIv` frozen daily stamp | frozen | intraday operand | 8th one-distinct-value session (0.103481) | STRUCTURAL — G12 | **OPEN** |
| **T3** | `iv_pair` | 0.02 gap | drop or redefine | 0/1,242 — 12th zero session | STRUCTURAL — G13 | **OPEN (owner)** |
| **T23** | partial-bucket tolerance 650 | — | — | **UNMEASURABLE this session** (logs destroyed by §6.2 recreations) | STRUCTURAL — G9 | **OPEN — no reading** |
| **T1** | `relativeVolumeMultiplier` 1.5 | — | — | §5.0: **8th consecutive no-pay** (2-leg sole-blocker set, both CE losers on a PE day) | REJECTED | **REJECTED — reconfirmed** |
| **T7** | composite threshold 0.600 | — | — | `composite-055` won both its extra rows today (+₹5,670.41; all-time −₹4,744.58) — first supportive session after 4 adverse ones; single-session, no re-open | REJECTED | **REJECTED — carried (note the first positive day)** |
| **watch** | `strike-pick` chain-quality | — | none (correct behaviour) | §2.2: eve→day-of escalation 235→604, ALL NIFTY, first weekly-expiry instance; prior weekly Tuesdays all ZERO — behaviour new since CAS week | REGIME | **WATCH — discriminators: Mon/Tue 08-10/11 (NIFTY), Fri 08-07 (SENSEX)** |
| **NEW** | mid-session deploys | 2 recreations in-session (§6.2) | deploys wait for post-market | evidence destroyed (T23 unmeasurable, boot line, counters ×2) even though data survived | ops (owner/architect) | **PROPOSED — reaffirm the doctrine** |
| **NEW** | minervini republish | published 1.0.1 ×2 | republish 1.0.2 drafts | §6.3 (carried from 08-03): name+description only | ops (owner) | **PROPOSED — carried** |
| T10 | stale OPEN paper positions | 17 OPEN | square off / age out | unchanged | ops | **OWNER — chronic** |
| T8/T26 | latency | shadow p50 1:17.9 / p95 1:20.3 (n=33); emit n/a (counters zeroed) | — | structurally unchanged | STRUCTURAL — G8 | OPEN (data) |
| T2 | `iv_rank` | NULL 100% (15th session) | — | E8 pointer stands | STRUCTURAL | carried, not open |
| T16/T12/T19/T22/T24/T21/T6/T15/T17+T13/T20/T25 | — | — | — | §2.1 (38/38 armed, 0 flat); §4 (374/375 OI; 0 misaligned ×9); reloads 38/0/0 | — | ✅ remain CLOSED |

**Group G movement: G18 RESCOPED (CAS — repair/guard retracted); G16 11th session + side-mirror
finding; G10 mildest reproduction (p94.4); G9 no reading (logs destroyed); G12 8th frozen session;
T1 8th rejection; T21's bracket PAID for the first time (shadow, PE side).**

## 8 Honesty caveats

- **§6.1's CAS mechanism is established from our own data + public reporting of the 08-03 launch;
  the exact NSE settlement formula under CAS (auction close vs any residual weighted-average rule)
  was not verified from primary NSE circulars.** The expiring-chain convergence is measured fact;
  the "settlement = CAS close" reading follows from it. Sources: NSE CAS product page, Zerodha
  Z-Connect CAS explainer, press coverage of the 08-03 launch divergence.
- Today's regime stamp (trend-down 0.871) is a CONTINUOUS-session read per the new §3.33 doctrine;
  the official-close read is 0.323 mixed. Prior sessions' stamps are unaffected (pre-CAS the two
  coincide).
- §5.0's model omits costs and `signal_exit`; snapshot granularity ~2 min; the 09:51 stop exit is
  bracketed (626.70/597.80) rather than pointed.
- Champion's +₹18,302.08 is **8 independent events, not 23**; the 10:33 TP cluster alone is
  +₹29.9k across 7 fan-out rows of one bar/leg. The all-time league inherits fan-out as always.
- T23/G9 has **no reading** this session — that is a measurement outage caused by the mid-session
  recreations, not a clean session.
- `composite-055`'s first green day is n=2 — noted, not evidence.
- This run was **read-only against the live stack**: SELECTs, `docker logs`, `docker inspect`,
  in-container actuator/health GETs, plus two external web lookups (CAS mechanism + official
  close confirmation). No restart, deploy, write, or config change; nothing republished. Docs
  edits in this PR: findings + 08-03 addendum + README §3.32-amend/§3.33 + rollup + ledger G18.
