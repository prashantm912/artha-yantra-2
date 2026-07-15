# Session findings — 2026-07-15 (data date)

**Analysis date:** 2026-07-15 (post-market, on ask — ran ~18:30 IST, after close). **Analyst:** Claude
(automated). **Data:** `strategy.signal_rejections` — **396 rows**, **33 strategies**, spanning
**09:50–15:21 IST** (full session, but with an internal silent gap — see §6). `strategy.signals` fired:
**3** (`scalp-straddle-nifty` ENTRY 10:18, EXIT 11:48, re-ENTRY 14:42). Paper trades: 0 (advisory only).
Shadow book (champion): 12 closed, all losers. Method: [README.md](README.md) §3 pass + §4.3 open-gate
(retroactive — market had already closed when the run started).

**Session character: rangy/choppy, net-flat.** Front future NIFTY26JULFUT **09:15 open 24,073.5 → 15:29
close 24,072.0** (−1.5 pts), intraday range **24,012.5–24,225.0 (~212 pts)** — no sustained trend, unlike
07-10's up-day. Composite distribution tops out at **0.7** today (no 0.8 bucket, vs 07-10's 71-row 0.8
bucket) — a weaker passing population than the last clean session.

**Headline verdicts:**

1. **CONFIRMED — a 2h21m silent `signal-eval` thread stall, 11:49–14:10 IST, SELF-RECOVERED, NEITHER
   existing canary logged it.** This is the same starvation signature as 07-07/07-10/07-14 (ledger item
   #14, "RE-OPENED — HIGH" as of 07-10) — **the 4th confirmed occurrence.** Evidence in §6.
2. **A SEPARATE, unrelated live incident**: 16 of 17 open paper (swing-book) positions had **zero live
   ticks all session** (`PaperStaleTickAlerter`, up to 5.7h "tick absent"), and zero 1m equity candles
   were captured for the affected symbols today. Bracket SL/TP could not evaluate live for these
   positions all day. See §4/§6.
3. The #866 fix (bounded candle fetch + dedup, deployed 2026-07-14 post-market) is **holding for its own
   failure mode** — no repeat of the "unbounded fetch parks the loop" signature specifically. Today's
   stall has a different fingerprint (see §6) — likely the older subscriber-side class (#634/#679), not
   a #866 regression.

---

## 1 Funnel numbers

Rows 396 (09:50–15:21 IST, gap 11:49–14:10). First-blocking-rail histogram:

| blocking_rail | rows | note |
|---|---|---|
| volume-floor | 241 | avg operand 9,775 vs avg threshold 49,203 — armed relative floor holding down the funnel, as designed |
| time-window | 116 | known-blocked-bar noise (per README, re-logged every bar) |
| time-of-day-preference | 13 | same noise class |
| oi-cross-required | 3 | tail |
| pct-price-move | 3 | FAIL_OPEN, tail |
| confluence-composite | 3 | near-miss |
| divergence-vol-gate / max-oi-sr-gate / two-candle / volume-pump | 3 each | tail |
| rsi-band | 2 | tail |
| open-high-low / directional-change-gate / rsi-5m-cap | 1 each | tail |

All-fails expansion (top): `confluence-composite` 246 (0.550 vs 0.6, the near-miss mass) ·
`volume-floor` 241 (9,775 vs 49,203) · `time-window` 116 · `rsi-band` 89 (55.16, neutral) ·
`divergence-vol-gate` 46 · `trend-change` 46 · `oi-divergence-magnitude` 27 (**5.71 vs 20** — same
regime-crash pattern as 07-10's 8.29, confirms REGIME not structural) · `oi-cross-required` 27 ·
`pct-price-move` 25 (FAIL_OPEN, 0.479) · `two-candle` 25 · `volume-pump` 25 · `constituent-gate` 25
(FAIL_OPEN) · `open-high-low` 25 · `directional-vix-gate` 21 (FAIL_OPEN) · `oi-slope-agree` 19 ·
`max-oi-sr-gate` 14 (FAIL_OPEN) · `directional-change-gate` 13 · `call-put-delta-filter` 13 (FAIL_OPEN,
30.6 vs 50) · `time-of-day-preference` 13 · `rising-volume` 12 · `hero-zero` 10 ·
`oi-interval-and-60m-trend` 6 (FAIL_OPEN) · `rsi-5m-cap` 6 · `psar-durability` 2 (FAIL_OPEN).

## 2 Rail findings

### 2.1 `volume-floor` — relative floor (#605) holding, no directional fires today

Armed operand avg 9,775 vs threshold avg 49,203 (SENSEX un-armed 125k still pulls the aggregate up).
Unlike 07-10 (up-day, volume expansion → 2 directional fires), today's rangy session produced **zero
would-have-fired-blocked-only-by-volume-floor rows** — the floor wasn't the sole gate on any
composite-passing bar today. Consistent with a chop day: no sustained volume expansion for the relative
floor to catch. No tune signal either way from this session alone.

### 2.2 Working-as-designed / regime rails (no tune)

- `oi-divergence-magnitude` 5.71 vs 20 — third session in the 07-10 crash pattern (5.98→16.5→23.9→
  24.65→8.29→**5.71**), confirms REGIME.
- `confluence-composite` near-miss mass at 0.550 vs 0.6 threshold — same shape as prior sessions, math
  working as intended.
- `time-window`/`time-of-day-preference`: same noise class.

## 3 Composite + dots

Composite distribution: 0.2→12 (PE) · 0.3→26 (PE) · 0.4→14 (6 CE/8 PE) · 0.5→50 (34 CE/16 PE) ·
**0.6→64 (all CE, passed) · 0.7→80 (all CE, passed)**. **144 rows ≥ threshold, ALL CE** — weaker than
07-10 (219 rows, reached 0.8) but same CE-only-passes pattern; PE capped at 0.5 again (regime).

Dot support rates (246 confluence-evaluated rows; 46 straddle-path rows for iv_slope/iv_abs_band):

| dot | support % | vs 07-10 | verdict |
|---|---|---|---|
| iv_pair | **0%** | 0% | dead-by-calibration, **still 0% even after the #675/#676 recalibration to 0.02** — 6th+ session |
| trending_cross | **0%** | 27.8% | regime swing — down from 07-10, watch |
| volume | **0%** | 0% | dead (relative floor removes expansion signal) |
| iv_slope (straddle, n=46) | **0%** | 26.2% | regime swing (straddle-path) |
| iv_abs_band (straddle, n=46) | **0%** | 95.1% | regime swing (straddle-path) — **large swing, watch next session** |
| iv_rank | 0% | 0% | dead — `ivRank` NULL 396/396 (honest-null), 6th+ session |
| oi_spurt | **1.6%** (4/246) | 0% | **first sign of life since the #675/#676 recalibration (floor 8)** — still tiny |
| premium_skew (n=10) | 20.0% | 87.5% | tiny sample, regime swing |
| futures_oi | 46.7% | 47.5% | healthy |
| underlying_oi | 47.2% | 56.5% | healthy |
| sentiment_slope | 47.6% | 50.8% | healthy |
| rsi | 59.3% | 37.8% | healthy |
| psar | 64.2% | 74.3% | healthy |
| sentiment | 65.0% | 59.3% | healthy |
| drastic_oi | 68.7% | 85.0% | healthy |
| breadth | 72.0% | 86.7% | healthy (regime-following, still alive) |
| vwma | 73.2% | 83.1% | healthy |
| vix | 74.8% | 86.7% | healthy |
| supertrend | 75.2% | 86.7% | healthy |
| basis | 79.3% | 86.7% | healthy |
| vwap | 100% | 100% | by construction |

**Dead-weight cap — unchanged at 0.816** (same 4 structural-dead dots: volume 1.0 + iv_rank 0.8 + iv_pair
0.8 + oi_spurt 1.0 = 3.6 of Σw 19.6). Consistent with today's max passing bucket (0.7, populated) sitting
below the cap.

**iv_slope/iv_abs_band regime swing (07-10 → today) worth a note:** both went from healthy-support
(26.2%/95.1%) to 0% today on the straddle path — comparable sample size (46 vs 61 rows), so this is a
real regime shift (choppy day → the straddle-path IV structure read differently), not a data-health drop.
Not evidence of a broken dot; watch across sessions.

## 4 Data health

| field | state | classification |
|---|---|---|
| macro.ivRank / fiiLongPct / dowUp / vix (context.macro mirror) | NULL 396/396 | same as every prior session — honest-null / by-design / vix-mirror-blind (ledger #10, unchanged) |
| macro.advances/declines | non-zero on all rows | ALIVE (#486 holding) |
| candle capture (NFO front future, all 3 contracts) | 409 bars each, 09:15→15:29, **zero gaps** | ✓ healthy all session |
| candle capture (NSE equities, 16 swing-book underlyings) | **0 rows today** for the tick-starved symbols (SENORES/AUTOIND/NGIL/ATHERENERG/DIACABS checked) | **anomaly** — see §6 item 2. Swing books evaluate on daily bars by design, so this may be a non-issue for signal generation, but it correlates exactly with the live-tick-absent alarm |
| `strategy.subscriber_health_events` | **0 rows today** | did not detect the §6 item 1 stall — same non-detection as 07-10 |

## 5 Shadow-book outcomes

**Shadow book (champion): 12 closed, 0 wins, −54.69 avg pts, −₹656.30-scale net (points, not cost-net).**
All 8 distinct strategies that closed lost: `scalp-connect-the-dots-nifty`/`scalp-gap-theory-nifty`/
`scalp-open-high-low-nifty`/`scalp-trend-change-nifty`/`scalp-two-candle-nifty` each 1×SQUARE_OFF at
−32.3%; `scalp-trending-oi-nifty` 1×SQUARE_OFF −27.6%; `scalp-golden-crossover-nifty` 2×STRUCTURAL_STOP
avg −5.0%; `scalp-market-movers-nifty` 4×STRUCTURAL_STOP avg −1.9%. Consistent with the chop/whipsaw
session character (flat close, 212-pt range) — a losing day for the shadow book, no rail-tuning signal
(rail didn't cause these losses; the market character did). **Zero would-have-fired-blocked-only-by-
volume-floor rows this session** (unlike 07-10's 16) — nothing to counterfactual for that rail today.

`scalp-straddle-nifty` (the only real fire, advisory, no paper fill): ENTRY 10:18 (composite 1.0 —
straddle path scores outside the confluence composite), EXIT 11:48 TIME_STOP at 24,180, re-ENTRY 14:42
(composite 0.6778). The 11:48 EXIT is the **last `signal-eval` log line before the §6 stall** — coincidence
of timing (this strategy's own scheduled exit-check fired right as the broader eval silence began), not a
causal link established.

## 6 New data points / anomalies

### 6.1 CONFIRMED silent `signal-eval` stall, 11:49–14:10 IST (2h21m), self-recovered — 4th occurrence

**Evidence chain (read-only, no live action taken — session was already closed when found):**
- `signal_rejections` total silence 11:43:20→14:10:10 IST across **every** blocking-rail type (§1 by-hour
  breakdown showed zero rows of ANY rail 12:00–13:59 IST) — ruling out "legit quiet regime" (a regime
  lull would still log `time-window`/noise rails every bar; total silence across all 14 rail types did
  not).
- `strategy.signals` shows exactly one event in the window: the `scalp-straddle-nifty` EXIT at 11:48 IST.
- **Direct thread-level proof:** `docker logs` filtered to logger `SignalEngine` thread `"signal-eval"`
  shows exactly **one** log line between 06:14–08:38 UTC (11:44–14:08 IST) — the 11:49 EXIT — then
  silence until 08:40:10 UTC (14:10:10 IST), matching the DB row resumption to the second.
- **Capture stayed healthy throughout** — 1m candles for the front future had zero gaps >2min across the
  whole window (checked minute-by-minute).
- **A DIFFERENT scheduled thread (`PartialBucketCanary`, unrelated to the eval-dispatch path) kept firing
  every ~3min through the entire window** — proving the JVM/scheduler pool itself was alive, not a full
  process hang. This is what makes the signature "silent," not "crashed": a health check that only looks
  at "is the process up" or "are candles arriving" would read GREEN throughout.
- **`strategy.subscriber_health_events` has ZERO rows for today** — neither `#634` (`SubscriberHealthCanary`)
  nor `#679`'s eval-vs-receipt heartbeat detected or logged this stall.

**Why the existing canaries likely missed it (structural, not a new bug in #866/#868):** #679's
eval-vs-receipt heartbeat compares `lastBarEvaluatedAtMs` against `lastBarReceivedAtMs` (receipt-relative,
deliberately quiet-market-safe). If the Redis `candles.1m.*` **subscriber itself** silently dropped
(the 07-07 RC-2 signature), both `receivedAtMs` and `evaluatedAtMs` freeze **together** — the delta
between them stays small, so #679 never sees a growing lag and never alarms. `#634` is supposed to catch
the subscriber-drop case directly but has now logged nothing across 4 known stall sessions (07-07, 07-10,
today, and by inference 07-14's different-cause stall). **The dormant `SignalStarvationCanary` (#868)
also would NOT have caught this specific case** — its predicate explicitly excludes `receiveGap>=barGapMs
|| evalLag>=barGapMs` (deferring to the subscriber watchdog on purpose, to avoid double-alarming) — and
if receive+eval froze together, `receiveGap` would indeed exceed `barGapMs` (180s) quickly, correctly
routing this to `#634`'s job, which isn't doing it.

**This reopens ledger item #14 with a 4th data point** — the recurring pattern is no longer "does #634
work" but **"why does #634 never fire regardless of the underlying cause."** Recommend: before arming
`SignalStarvationCanary`, first get `#634`/`subscriber_health_events` instrumented enough to prove it CAN
log a row (e.g., a forced fault-drill via `artha.canary.drill-suppress-key`, already built per README
§7.8) — today's evidence suggests the detector itself may be silently broken, not just tuned wrong.

### 6.2 Separate live incident — paper bracket tick-starvation, 16/17 open positions, entire session

`PaperStaleTickAlerter` warned continuously from market open (03:45 UTC / 09:15 IST, ~900s) through close
(09:59:45 UTC / 15:29:45 IST, position 10 at ~20,564s ≈ 5.7h) — **16 of 17 open paper positions** had
"tick absent" all session. Zero NSE 1m candles captured today for the sampled affected symbols
(SENORES/AUTOIND/NGIL/ATHERENERG/DIACABS). These are all NSE equity **swing-book** positions
(Minervini/Manas family). Two readings, not disambiguated in this read-only pass:
- **(a) Expected-by-design:** swing strategies evaluate on daily bars, may never have needed live 1m/tick
  coverage for their SIGNAL path — the alerter's threshold may simply be miscalibrated for a book that was
  never meant to have live ticks.
- **(b) Real gap:** if these positions are SUPPOSED to have live WS tick coverage for bracket SL/TP
  (the tick-freshness doctrine explicitly says "exits need the best available truth"), a full-session
  zero-tick gap means stops literally could not evaluate live all day — a genuine live-risk exposure,
  independent of the signal-generation path in §6.1.
Needs an owner/code check of which equity underlyings are supposed to carry live WS subscriptions for
open paper positions — out of scope for this read-only pass to resolve further.

### 6.3 iv_slope/iv_abs_band regime swing (straddle path) — see §3, watch next session

### 6.4 oi_spurt first sign of life since #675/#676 recalibration (1.6%, was 0% across 5 prior sessions)

Still tiny (4/246 rows), but the first non-zero reading since the floor was lowered to 8. `iv_pair`
remains fully dead (0%) even after its 0.10→0.02 recalibration — worth a ground-truth check (§3.8-class)
of the real IV-pair-gap distribution against 0.02 next session if it stays at 0%.

## 7 Tuning candidates (carried forward from 2026-07-10 + today's additions)

| # | knob | current | proposed | evidence | status |
|---|---|---|---|---|---|
| 1 | `volume_floor` relative floor | k=1.5,N=20 ARMED #605 | tune k over 1 month | §2.1: no directional fires today (chop day, no would-have-fired rows) — no new evidence either way | **SHIPPED/ARMED — in tuning window, unchanged** |
| 2 | `artha.scalper.oi.ivPairMinGap` | **0.02 (recalibrated #675/#676)** | ground-truth the real gap distribution — still 0% support post-recalibration | §3: 0% support 6th+ session, even after the fix | **RECALIBRATED BUT STILL DEAD — escalate to ground-truth check** |
| 3 | `artha.scalper.oi.spurtPricePct` | **8 (recalibrated #675/#676)** | keep watching | §3/§6.4: first life today (1.6%, was 0%) | **RECALIBRATION SHOWING EARLY SIGNAL — keep watching, do not re-tune yet** |
| 4 | breadth live producer | live A/D | — | ALIVE, holding | SHIPPED #486 (holding) |
| 5 | iv_rank null semantics | null scores against | null = neutral / excluded | 6th+ session honest-null | PROPOSED (unchanged, rollup §Proposals P3) |
| 6 | composite threshold 0.6 | keep | keep | cap 0.816, 144 rows passed today | DECIDED-KEEP |
| 10 | `context.macro.vix` NULL while vix dot works | macro mirror blind | populate or flag | unchanged 6th+ session | PROPOSED (unchanged, low urgency) |
| 14 | strategy-signal eval STALL (silent subscriber drop) | #634/#679 deployed, `SignalStarvationCanary` #868 dormant | **instrument #634 to PROVE it can log (fault-drill), not just re-tune thresholds** | §6.1: **4th occurrence**, 0 telemetry rows, direct thread-level proof of a 2h21m eval-thread silence | **RE-OPENED — HIGHEST PRIORITY (detector itself may be broken, not just untuned)** |
| 15 | NEW — paper bracket tick-starvation, swing-book positions | unknown WS subscription scope for equity underlyings | owner/code check: are swing positions supposed to carry live ticks? | §6.2: 16/17 positions, full-session zero-tick, zero equity 1m candles | **PROPOSED — needs scoping, possibly WON'T-DO if by-design** |

**Note on item 14:** the SignalStarvationCanary shipped this cycle (#868, dormant, arming chip
`task_a6c12601`) explicitly does NOT cover this stall class by design (it defers to the subscriber
watchdog on a receive/eval-gap signature). Today's evidence means the priority fix is making #634 provably
work, not tuning #868's arming window — these are two different problems and #868's arming timeline should
not be read as "this incident is now covered."
