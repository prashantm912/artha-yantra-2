# Session findings — 2026-08-19 (data date)

Analysis date: 2026-08-19 EOD (scheduled post-market agent, run ~16:00 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **984** (bounds
`2026-08-19T09:15:00+05:30`…`15:40`; rows 09:19–15:19), signals fired **8 ENTRY + 8 EXIT**,
paper trades **6 opened / 6 closed, net −₹2,863.79**, shadow closes **42** (champion 30 +
composite-055 2 + vol-12k5 5 + vol-off 5).

Session character: **Wednesday, no expiry on either root** (the §2.2 historically-clean control
day — and it came in clean: **strike-pick fails 0 on BOTH roots**) · VIX 11.31–11.57 ·
**TREND-DOWN** (continuous o 24,152.05 → freeze 24,048.55 = −0.43% on a 0.61% range, efficiency
**0.703**; official CAS bar reads eff 0.501 = mixed — **4th straddle of a cut boundary**;
doctrine §3.33a stamps continuous. CAS delta **+29.75**: 15:29 → 24,078.30) · signal contract
`NFO:NIFTY26AUGFUT` (log-confirmed, 1,004 mentions) · ⚠️ **THE HEADLINE: three Kite
connectivity outages inside the session** (WS ticker + REST circuit both down — ~11:01–11:03,
**11:50–12:14:51**, **13:02–13:27:51 IST**), feed watchdog self-healed each time, gap backfill
repaired the candles (§6.1) · no service recreate all day (both containers up since 08:33:36
IST — full `docker logs` available).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 984 — **34 of 38 scalpers** (silent: `scalp-golden-crossover-nifty`, `scalp-golden-crossover-sensex-niftyoi`, `scalp-open-high-low-nifty`, `scalp-open-high-low-sensex-niftyoi` — the IDENTICAL set to 08-18: CE golden-crossover pair 2nd consecutive, open-high-low pair 3rd consecutive; note the PE twins of all four DID emit) |
| eval outcomes | chart-gate-failed 1,674 · confluence-blocked 984 · composite-below-threshold 317 · **fired 8** · discipline-paused 0 |
| fired reconciliation (§3.36) | 8 fired evals = **8 ENTRY emitted + 8 EXIT = 16 signal rows**, risk-gate grep 0. Three bursts: **10:39** ×3 (comp 1.0000: `golden-crossover-sensex-niftyoi-pe` SENSEX77400PE · `golden-crossover-nifty-pe` + `connect-the-dots-nifty-pe` NIFTY24300PE) → EXIT 10:57 · **11:09** ×2 (comp 0.4107: `connect-the-dots-sensex-niftyoi-pe` SENSEX77300PE · `connect-the-dots-nifty-pe` NIFTY24300PE) → EXIT 11:12 · **14:24** ×3 (comp 0.7925: same golden-crossover/connect-the-dots trio) → EXIT 14:30/14:35 |
| coverage | 24 of 25 15-min buckets 09:15–15:19; **the 12:00 bucket is EMPTY and 11:45 (n=4) / 13:00 / 13:15 (n=16 each) are thin — these are the Kite-outage windows (§6.1), NOT the midday window class**; 12:15–12:45 ran full (70 rows each) |
| boot health | boot 08:33:36 IST, install 08:34:00 with the 0-loaded/38-unresolved transient → **38/0/0 at 08:36:19 (~139 s — just above the normal 71–132 s band**, nowhere near 08-17's 585 s outlier). Mid-session reconciles 12:10:19 and 13:25:19 both clean 38/0/0, single rows (no §3.38 slow-reload cadence) — both fell inside outage windows, noted §6.1 |
| paper (funded) | pos 80 SENSEX77400PE 20 @475.15 (10:40, sub-1) → **STRUCTURAL_STOP 10:58 −₹347.47** · pos 81 NIFTY24300PE 65 @244.20 (10:40, sub-2) → **STRUCTURAL_STOP 10:58 −₹654.40** · pos 82 SENSEX77300PE 20 @438.35 (11:10, sub-3) → **TRAILING_STOP 11:13 +₹59.34** · pos 83 NIFTY24300PE 65 @246.70 (11:10, sub-4) → **TRAILING_STOP 11:13 +₹69.02** · pos 84 SENSEX77300PE 20 @452.65 (14:25, sub-5) → **STRUCTURAL_STOP 14:31 −₹1,000.70** · pos 85 NIFTY24300PE 65 @251.60 (14:25, sub-3 re-entry) → **STRUCTURAL_STOP 14:31 −₹989.58**. Net **−₹2,863.79**. Entry emit latency avg **~21.0 s** (8 entries, 20.0–21.8 s — G8/T26 class); exit emits 12.0 s / 20.7 s / 1.9 s / 15 ms |

## 2 Rail findings

- **§2.2 chain-proximity: the Wednesday control held — `strike-pick` fails 0 on BOTH roots**
  (after 08-17 Mon-eve 361-NF and 08-18 Tue-day-of 425-NF). Series intact: Wednesdays clean
  4-for-4 (07-22 / 07-29 / 08-05 / 08-19).
- **volume-floor banded and honest; binding share 62.9%** (619/984, back up from 08-18's
  55.7%): 31 distinct thresholds 9,067–58,305 (avg 24,378) vs aligned 3m AUGFUT volume p50
  12,480 / p90 47,320 / max 133,250 — floor ≈ p75. §3.14: `relative-volume-floor` armed
  **38/38** published (stamps 07-28 ×2 + 08-13 ×36, unchanged).
- First-block tail: time-window 162 · rsi-band 85 · time-of-day-preference 40 ·
  option-side-constraint 14 · pct-price-move / volume-pump / two-candle 12 each ·
  divergence-vol-gate 10 · oi-cross-required 8 · max-oi-sr-gate 4 · psar-durability 2 ·
  confluence-composite 2 · hero-zero 2 (14 distinct rails).

## 3 Composite + dots

- Distribution (scored n=768): pass mass **122/768 (15.9%)** — **CE pass mass returned: 14 CE +
  108 PE** (08-18 was 0 CE); max rejected 0.5851.
- Dot support (complete session, n=768 unless noted): `iv_rank` 0% (withheld, standing) ·
  `iv_pair` **0% (22nd session — T3, owner)** · basis **1.8%** · vwap **4.4%** ·
  trending_cross 10.4% · oi_spurt 12.1% · volume 19.4% · iv_slope 23.1% (n=104) · rsi 43.4% ·
  futures_oi 49.2% · underlying_oi 51.3% · sentiment_slope 59.8% · sentiment 75.3% · psar
  77.6% · breadth 81.8% · supertrend 84.4% · drastic_oi 90.1% · vwma 95.3% · vix 97.1% ·
  `iv_abs_band` **100% (n=104; frozen daily stamp, 18th session)** · premium_skew 100%
  (n=30). basis/vwap at the bottom = same drift-down-re-approaching-VWAP regime as 08-18.
- **§3.28 breadth side-split — 7th consecutive dead-CE session**: CE advances 14–15 vs `>32`
  → **0/14**; PE declines 30–38 straddling the line → **628/754 (83.3%)**.
- OI bloc fully live (no expiry, no suppression): quadrants NEUTRAL **0/768** contextful
  (SHORT_BUILDUP 235 / LONG_BUILDUP 211 / SHORT_COVERING 193 / LONG_UNWINDING 129; 216 NULLs =
  context-less pre-fetch class); `futures_oi_snapshots` 23,460 snaps / **340 of ~375 minutes —
  the ~35 missing minutes are the Kite outages (§6.1)**, same for `options_chain_snapshots`
  (340 minutes).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 984/984 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 984/984 | by design (un-armed) |
| `fiiLongPct` | NULL 216 = exactly the context-less rows | daily EOD stamp, alive |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 18th) |
| vix | 22 distinct, 11.31–11.57 | alive |
| ceIvAvg6 / skew / basis | 39 / 77 / 73 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — the phantom-row class did NOT recur despite two backfilled outage gaps |
| §3.17 canary | **3 WARNs, 0 straddles — all UNPAIRED, all mid-session** (§6.2): 13:27 bucket shortfall **−63,505** (reconnect snapshot-tick inflation, explained) · 14:54 **−910** · 15:09 **−8,970** (both unexplained residual, post-reconnect) | 1 explained-class + 2 WATCH |
| Kite session | validated 15:59 IST | ✓ |
| market-data canary | GREEN, 0 problems, 91 ticked tokens (post-close read); went **RED twice intra-session** (snapshot age 653 s / 654 s) during the outages and recovered — the canary catching a real outage, working as designed | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 30 closes, +97.20 pts, −₹1,342.87 NET → 8 deduped (bar, leg) events; all-time
−₹217,793.06 → −₹219,135.93** (points −2,828.85 → −2,731.65 — a rare positive-points /
negative-net day: costs ate the gross):
- 09:24 `SENSEX2682077500PE` @450.55 → **TAKE_PROFIT +179.95 pts, +₹3,529.95**
  (`scalp-morning-trade-sensex-niftyoi-pe`) — a rare shadow TP (the T21 +35% bracket paying);
  09:24 `NIFTY26AUG24350PE` @258.75 → SQUARE_OFF +₹775.08.
- 10:21 ×16 fan-out on 2 legs: NIFTY24350PE @272.15 ×8 −₹96.32 each · SENSEX77400PE @456.05
  ×8 +₹53.66 each → net −₹341.28.
- 14:09 ×12 on 2 CE legs (the day's only CE entries): NIFTY23900CE @264.30 (SQUARE_OFF ×4
  −₹504.30 each; `market-movers` STRUCTURAL_STOP −₹647.10) · SENSEX76700CE @400.80
  (SQUARE_OFF ×4 −₹217.89 each; `market-movers` STRUCTURAL_STOP −₹619.32) → −₹4,155.18.
  **Two §3.24 multi-exit clusters, and BOTH ran counter to the usual stop-cuts-loses-less
  pattern: the stop exited WORSE than the square-off holds** (NIFTY −8.75 vs −6.55; SENSEX
  −27.90 vs −7.80) — the CE legs bottomed after the stop and recovered into the close.
- 14:36 hero-zero pair: SENSEX76900CE −₹952.02 · NIFTY24450CE −₹199.42.

**Challengers: ZERO challenger-only entries today.** composite-055's 2 closes (14:09 CE pair,
−₹722.19) were champion-shared rows, so today adds **no new loosening observation — the
only-class prior stands at 10 measured loosenings, 10 losses**. League: composite-055
−₹5,825.73 · vol-12k5 −₹40,994.98 · vol-off −₹56,392.85 · `dot-null-withheld` 0 rows ever.
Shadow entry latency p50 1:18.7 / p95 1:21.1 (n=42, structural class).

**§4.2 counterfactuals** (sole-blocker sets, composite passed; §3.16 horizon caveat applies):
sole-blocker rails today: two-candle / pct-price-move / volume-pump (8 rows each, the same 4
bar-events) · volume-floor 6 · rsi-band 3 · max-oi-sr-gate 3 · confluence-composite 2. Deduped
bar-legs and outcomes:
- 10:39 + 14:24 legs = the funded book's own entries: WOULD-LOSE (pos 80/81 −₹347/−₹654; pos
  84/85 −₹1,001/−₹990).
- 11:09 legs = funded pos 82/83: WOULD-WIN small (+₹59/+₹69, trailing stops).
- 10:21 / 10:36 / 10:45 legs = shadow-priced ±small (−96.32 / +53.66 class).
- 12:33 rsi-band legs (the only twin-less bar): NIFTY24300PE 244.30 → 238.05 @13:03 (−6.25
  pts) · SENSEX77400PE 490.75 → 482.05 @13:02 (−8.70 pts) — **both WOULD-LOSE** on the
  30-min funded model; neither bracket touched.
- 14:09 confluence-composite CE legs: already shadow-traded, lost (above).
Net: the vetoing rails were again refusing extra size on legs that mostly lost — **no tuning
row filed; the §3.26 prior stands (10/10)**.

## 6 New data points / anomalies

### 6.1 Three Kite connectivity outages inside one session — watchdog self-healed all three, zero manual action

`ws.kite.trade:443` unreachable (WS ticker) **and** the kite-rest circuit open (34 scheduled
snapshot-task errors) in three episodes: **~11:01–11:03**, **11:50–12:14:51** (watchdog
restarts at tick-age 227 s / 827 s / 1,487 s; reconnected 12:14:51), **13:02–13:27:51**
(restarts at 184 s / 844 s / 1,504 s; reconnected 13:27:51). Effects, all bounded and healed:
- rejections: 12:00 bucket empty, 11:45/13:00/13:15 thin (§1) — bars stopped closing, the
  engine idled (correctly; no eval-stall rows, gauges recovered on reconnect).
- capture: futures-OI + chain snapshots each lost ~35 minutes (340/375); the data canary went
  RED twice on snapshot age and recovered.
- candles: signal-future session = KITE 333 + TICK_AGG 16 + BACKFILL 26 = 375/375 minutes,
  **0 misaligned rows** — the recency re-fetch + gap backfill fully repaired the 1m series
  with broker bars (the §3.15 phantom class did not recur).
- funded/paper path: untouched — no entry or exit fell inside an outage window (nearest: pos
  82/83 closed 11:13, 37 min before outage 2 bit... entered 11:10, outage began ~11:50).
Cause is environmental (network path to Kite — both protocols failed together; same class as
the 08-11 power-loss row in the outage register, smaller). Nothing to fix in-repo; the
watchdog + backfill + canary stack did exactly what it was built to do. Register row appended
to memory `stack-outage-register`.

### 6.2 §3.17 — first live observation of the RECONNECT-INFLATION class (README §3.17 amended)

The 13:27 WARN (shortfall −63,505: in-memory tick-agg 1m sum 70,590 vs broker 3m bar 7,085,
~10×) is the ticker reconnect at 13:27:51 attributing the outage-gap's cumulative volume to
the reconnect minute — the tick-agg baseline reset class, previously seen only at boot
(opening-bucket). DB values match the broker exactly (1,040+3,185+2,860 = 7,085), so **the
inflated side is the in-memory 1m mirror only; the rails read the broker-corrected 3m rollup
and were untouched**. README §3.17 gains a dated amendment. The 14:54 (−910) and 15:09
(−8,970, 33% of bucket) WARNs have **no adjacent reconnect** (last ticker event 13:27:51) and
stay UNEXPLAINED — 2-session WATCH (NEW-6): if unpaired mid-session WARNs recur on a
no-outage day, that is the §3.17 "new attribution defect" arm.

### 6.3 §3.29 audit — no change to the never-fired set

`close_reason` deltas since 08-18: STRUCTURAL_STOP 10→14 (pos 80/81/84/85) · TRAILING_STOP
17→19 (pos 82/83, both `indicator` basis — already-exercised path). Never-fired unchanged:
`take_profit premium_pct` (36) · `signal_exit` (38) · `square_off` (2) · tag
`oi-confluence-exit` (8). INDETERMINATE: `trailing_stop atr_multiple` (2) only. (The day's
shadow TAKE_PROFIT is the shadow book, not the paper vocabulary.)

### 6.4 §3.34 heat-gate evaluability — PASS (6th consecutive funded-day)

Log grep = **0** across 6 funded entries; all six rows carry `margin_snapshot 0.00 /
margin_pct 0.00` populated. Evaluability only — the zero-SPAN long-option coverage question
(N23-A) stands.

### 6.5 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines — the identical standing set to
  08-17/08-18** (keyword-class false positives; no new line, no edits required). Ledger
  consistent.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted =
  exactly the 24 #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged.
  Nothing republished by this run.

### 6.6 §3.30 freeze telemetry — flag NOT hit (2 of 5)

Entries: sub-1 ×1 (10:40) · sub-2 ×1 (10:40) · sub-3 ×2 (11:10 win, 14:25 loss) · sub-4 ×1
(11:10) · sub-5 ×1 (14:25). Day PnL: sub-1 −₹347.47 (frozen ~10:58) · sub-2 −₹654.40 (frozen
~10:58) · sub-3 −₹930.24 (won 11:13, stayed available, re-entered, frozen ~14:31) · sub-4
+₹69.02 (winner, stayed AVAILABLE) · sub-5 −₹1,000.70 (frozen ~14:31). **2 of 5 before 14:30
— under the ≥3 flag** (subs 3/5 froze at 14:31, past the cutoff). Trend: 08-13 2/5 · 08-14
2/5 · 08-17 3/5 · 08-18 2/5 · **08-19 2/5**. Last fired eval 14:24 — governors blocked zero
entries (counterfactual cost ₹0).

### 6.7 The funded day in one line

Three synchronized multi-slug bursts on PE legs of a trend-down day; the 11:09 burst's
trailing stops banked small wins, the 10:39 and 14:24 bursts' structural stops cut fading
re-entries in 6–18 min. Exits again lost less than the shadow holds on the same legs
(SENSEX77400PE: funded −₹347.47 stop @10:58 vs shadow square-off... the shadow's 10:21 twin
closed +₹53.66 — mixed today, not the clean pattern). Loss is again concentration: all six
positions were the same two strikes the composite converged on.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-6 (08-19) | unpaired mid-session §3.17 WARNs w/o reconnect | **WATCH (2 sessions)** | 14:54 −910 · 15:09 −8,970 (§6.2); recurrence on a no-outage day = attribution-defect arm |
| watch | `strike-pick` chain-proximity | **WATCH — Wednesday control CLEAN (0 fails both roots, 4-for-4)** | §2; next cluster window Thu–Mon (BSE expiry 08-20) |
| NEW-5 (08-14) | Redis candles.1m subscription drop | **WATCH — no recurrence** (day 4 of week window to 08-21) | zero stall rows; engine idled only during genuine feed outages |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no deploy today; §3.38 mechanism unchallenged |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip (red day) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | grep 0 on 6 funded — 6th consecutive evaluability PASS (§6.4); N23-A stands |
| T30 | `breadth` dot `>32` | **OPEN** | 7th session dead-CE (adv max 15); PE 83.3% |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | floor ≈ p75 banded, zero flat; binding 62.9%; prior 10/10 stands (§5) |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (18th) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (22nd session) |
| T23 | partial-bucket tolerance | **OPEN** | 3 WARNs 0 straddles — see NEW-6; reconnect class documented (§6.2) |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5 5 closes, all champion-shared (no only-class row) |
| T7 | composite threshold | **REJECTED — carried** | composite-055 2 closes, champion-shared — no new observation |
| T10 | stale OPEN paper positions | **OWNER — chronic** | population 18 OPEN (+1), oldest 07-07 (as of ~16:00, pre-evening batch) |
| T8/T26 | latency | OPEN (data) | shadow p50 1:18.7 / p95 1:21.1 (n=42); entry emit avg ~21.0 s — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 984/984 |

## 8 Honesty caveats

- **This run executed ~15:55–16:05 IST** — before the 16:00 swing batch and the 18:4x
  screen/EOD jobs; T10 and ingest statements are as-of-run-time.
- Full-session `docker logs` were available (no recreate today) — log-derived checks are
  complete, EXCEPT that during the three outage windows market-data's own logging is the
  record of the outage, not of the market.
- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book (per-strategy
  `max_bars` + structural/trailing stops) are different exit models; today they disagreed in
  magnitude and direction on shared legs (§5, §6.7).
- The would-have-fired class deduped to ~9 bar-events on 4 distinct strikes — §3.24 fan-out
  inflation applies; effective independent sample is small.
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.703 = **trend-down**; official
  CAS bar reads 0.501 = mixed (4th straddle). CAS delta +29.75 (series remains
  symmetric-noise).
- Read-only run: SELECTs, in-container health GETs, log greps. No restarts, deploys, writes,
  config changes, or republishes. Docs-only PR: this file + rollup rows + README §3.17
  amendment.
