# Session findings — 2026-07-10 (data date)

**Analysis date:** 2026-07-10 (post-market, scheduled agent run). **Analyst:** Claude (automated).
**Data:** `strategy.signal_rejections` — **701 rows on 2026-07-10**, **35 strategies**, spanning
**09:19–14:52 IST ONLY** (truncated — eval stall, see §6). `strategy.signals` fired: **9** (3 ENTRY
+ their EXITs + re-entries — see §5). Paper trades: **0** (advisory only). Shadow book: **24 opens**
across variants (champion 19 closed). Method: [README.md](README.md) §3 pass.

**Session character:** **up-trend day** — front future NIFTY26JULFUT **24,140 open → 24,250 close
(+110 pts)**, range 24,140–24,270 (~130 pts). CE-biased all session (every composite-passing row is CE;
PE tops out at the 0.5 bucket). 07-08 and 07-09 have **zero rejection rows** (the confirmed 07-08/09
outage — capture hole; no session analysis for those days). This is the first clean full-morning
session since 07-07.

**Headline verdicts (one big first, one alarm):**

1. **FIRST DIRECTIONAL-SCALPER FIRES EVER.** After the relative volume floor (#605) armed on 07-07, the
   first *directional* scalpers fired today on an up-trend afternoon: **`scalp-golden-crossover-nifty`
   (CE, composite 0.688)** and **`scalp-connect-the-dots-nifty` (CE, composite 0.688)**, plus
   `scalp-straddle-nifty` re-fired. This is the mechanism working as designed — on a day with genuine
   volume *expansion*, the relative floor passed the directional scalpers where the old fixed 125k wall
   never could. **All three fires were small LOSERS** (afternoon whipsaw: entries clustered 13:39 near a
   local high 24,229, the future then dipped to 24,208 before recovering to 24,250 close; the scalpers'
   own EXIT signals cut them on the dip). Advisory only — **no paper fill** (§5).

2. **ALARM (recurring) — 3rd EVAL STALL at 14:52 IST, and the `SubscriberHealthCanary` (#634) did NOT
   recover it.** The `signal-eval` loop went silent after **14:52:22 IST** (last log = last rejection row,
   exactly together) and never resumed before close, while **market-data capture stayed healthy** (candles
   to 15:29). This is the SAME signature as 07-07's silent Redis `candles.1m.*` subscriber drop (07-07 §8
   RCA). **The #634 canary IS in the running image** (class `SubscriberHealthCanary` present, built
   2026-07-10 08:46) — but it logged **nothing** all day and eval did not auto-recover. Either its stall
   window did not trip before the 15:29 close or it is not acting; **verify #634 actually pages + re-subscribes
   on this signature** (ledger #14). Read all rates below as **morning+midday only (09:19–14:52)**.

---

## 1 Funnel numbers

Rows 701 (09:19–**14:52** IST — truncated by the §6 stall). First-blocking-rail histogram:

| blocking_rail | rows | strategies | avg margin | note |
|---|---|---|---|---|
| volume-floor | 424 | 32 | −26,840 | RELATIVE now (armed ~5–6k / un-armed SENSEX 125k) — margin ⅔ of 07-07 |
| time-window | 178 | 14 | — | known-blocked bars re-logged = noise |
| rsi-band | 24 | 15 | — | regime tail |
| time-of-day-preference | 21 | 4 | — | same noise class |
| confluence-composite | 10 | 2 | −0.061 | near-miss |
| volume-pump / two-candle | 9 / 9 | 1 / 1 | — | tail |
| pct-price-move | 9 | 1 | −0.658 | FAIL_OPEN |
| divergence-vol-gate | 8 | 1 | — | tail |
| oi-cross-required / max-oi-sr / hero-zero / directional-change / psar-durability | 5/1/1/1/1 | 1 | — | tail |

All-failed-rails expansion (unnest `checks[]`, pass=false; top rows):
volume-floor 424 (**5,116 vs 31,956 avg** — armed ~5k / un-armed 125k on the same scale) ·
**confluence-composite 371 (0.552 vs 0.6 — the near-miss mass)** · time-window 178 · rsi-band 118 (**51.96**,
neutral) · oi-cross-required 76 · **oi-divergence-magnitude 76 (8.29 vs 20 — DROPPED from the rising
trend, regime reverted)** · trend-change 71 · divergence-vol-gate 71 · strike-pick 62 · volume-pump 58 ·
pct-price-move 58 (FAIL_OPEN, 0.351) · two-candle 58 · oi-slope-agree 52 · open-high-low 36 ·
rising-volume 28 · directional-change-gate 17 · max-oi-sr-gate 11 · directional-vix-gate 9 (FAIL_OPEN,
12.4) · psar-durability 8 (0.035 vs 0.05) · hero-zero 8 · call-put-delta-filter 6 (36.8 vs 50) ·
constituent-gate 6 · morning-opening-formation 4.

## 2 Rail findings

### 2.1 `volume-floor` — relative floor (#605) now PASSING directional scalpers on a volume-expansion day

- First-block avg margin **−26,840** (was −38,919 on 07-07, −118,670 on 07-06 under the fixed wall). On
  the same scale now: armed operand avg 5,116 vs threshold avg 31,956 (the un-armed SENSEX 125k pulls the
  aggregate threshold up).
- **Key event: on THIS up-trend afternoon the floor let directional scalpers through** — golden-crossover
  and connect-the-dots fired (§5). The relative floor demands ≥1.5× volume expansion over the trailing
  median; today's afternoon impulse bars cleared it. This is the *first* session where the relative floor
  produced real directional fires — exactly the behaviour the arming was for. Status: **SHIPPED/ARMED — in
  the 1-month tuning window (owner: judge k=1.5 on real fires; today it fired 2 directional scalpers that
  lost small to whipsaw — one session, not a verdict).**
- **16 would-have-fired rows remain** (2 strategies, composite passed, blocked ONLY by volume-floor) — the
  floor still vetoes some composite-passing bars where volume did not expand. Non-empty this session (vs 0
  on 07-07), so the shadow book had a would-have-fired population again (§5).

### 2.2 Working-as-designed / regime rails (no tune)

- `rsi-band`: operand avg **51.96** (neutral) — between the 79 trend day (07-06) and 47 cool day (07-07).
- `oi-divergence-magnitude`: operand **8.29 vs 20** — DROPPED sharply from the rising trend
  (5.98→16.5→23.9→24.65→**8.29**). Confirms it is REGIME, not a slow structural climb — do not tune. Rollup
  aggregates.
- `confluence-composite` 371 fails at avg 0.552 vs 0.6 — the near-miss mass sits just under threshold on a
  CE-biased day (see §3). Not a rail defect; the composite math is working.
- `strike-pick` / `time-window` / `time-of-day-preference`: same cosmetic/noise classes as prior sessions.

## 3 Composite + dots

Composite distribution (0.1 buckets): 0.2→6 (all PE) · 0.3→19 (PE) · 0.4→38 (21 CE / 17 PE) ·
0.5→120 (99 CE / 21 PE) · **0.6→153 (all CE, 81 passed) · 0.7→67 (all CE, all passed) · 0.8→71 (all CE,
all passed)**. **219 rows ≥ threshold, ALL CE** — the richest passing population in this analysis history
(vs 184 on 07-07, 254 on 07-06). Max bucket 0.8 populated (71 rows), consistent with the 0.816 cap. PE
side alive (63 rows in 0.2–0.5) but capped at 0.5 — PE never passes on an up day (regime, not structural).

Dot support rates (474 confluence-evaluated main-path rows; 61 straddle-path rows for iv_slope/iv_abs_band;
8 rows premium_skew):

| dot | w | support % | vs 07-07 | verdict |
|---|---|---|---|---|
| iv_pair | 0.8 | **0%** | 0% | dead-by-calibration (0.10 unit gap never occurs) — 5th session |
| volume | 1.0 | **0%** | 0% | dead (relative floor / no dot expansion) — 5th session |
| oi_spurt | 1.0 | **0%** | 0% | dead-by-calibration (price% floor 50 unreachable) — 5th session |
| iv_rank | 0.8 | **0%** | 0% | dead — `ivRank` NULL 701/701 (honest-null) — 5th session |
| iv_slope (straddle, n=61) | 0.8 | 26.2% | 40.8% | straddle-path; regime |
| trending_cross | 1.0 | 27.8% | 27.6% | regime |
| rsi | 1.0 | 37.8% | 44.6% | healthy |
| futures_oi | 1.5 | 47.5% | 56.2% | healthy |
| sentiment_slope | 1.0 | 50.8% | 44.4% | healthy |
| underlying_oi | 1.0 | 56.5% | 49.0% | healthy |
| sentiment | 1.0 | 59.3% | 57.7% | healthy |
| psar | 1.0 | 74.3% | 62.1% | healthy |
| vwma | 1.0 | 83.1% | 85.7% | healthy |
| drastic_oi | 1.0 | 85.0% | 98.0% | swings hard (hold for rollup) |
| **breadth** | 1.0 | **86.7%** | 0% (07-07) / 44.9% (07-06) | **REGIME (up day → breadth supports CE); data ALIVE** |
| vix | 1.0 | 86.7% | 60.9% | regime (swings hard) |
| basis | 1.0 | 86.7% | 62.5% | healthy |
| supertrend | 1.0 | 86.7% | 62.5% | healthy |
| premium_skew (n=8) | — | 87.5% | n/a | tiny sample |
| iv_abs_band (straddle, n=61) | 0.8 | 95.1% | 80.6% | straddle-path; regime |
| vwap | 2.5 | 100% | 100% | by construction (side chosen BY vwap) |

**Dead-weight cap — UNCHANGED at 0.816.** Main Σw = 19.6; the same **4** structural-dead dots
(volume 1.0 + iv_rank 0.8 + iv_pair 0.8 + oi_spurt 1.0 = 3.6). (19.6 − 3.6)/19.6 = **0.816**, confirmed by
the populated 0.8 bucket (71 rows) and the 219 passing rows. breadth's 86.7% support today (vs 0% on the
07-07 down day) is REGIME — data alive, direction-following — so it stays off the dead list.

## 4 Data health (2026-07-10 rows)

| field | state | classification | vs 07-07 |
|---|---|---|---|
| macro.advances/declines | **NON-ZERO on all rows** (breadth_zero = 0/701) | **ALIVE** (#486 holding) | same (alive) |
| macro.ivRank | NULL 701/701 | honest-null (insufficient IV history); scores against | same |
| macro.dowUp | NULL 701/701 | by-design (Dow un-armed); null = NEUTRAL | same |
| macro.fiiLongPct | NULL 701/701 | fii-bias dot un-armed | same |
| macro.vix | NULL 701/701 in `context.macro` | vix DOT reads a separate path (86.7% support) — macro mirror still blind | **same watch (ledger #10)** |
| everything else (chart/oi) | populated, plausible **through 14:52** | ✓ capture healthy until the eval stall | truncated (§6) |

**Nothing that was alive went dead** (breadth alive; its high support is regime). **NO new dead-data
alarms vs 07-07.** The only anomaly is operational: the §6 eval stall (feed healthy, consumer silent) — a
recurrence of the 07-07 signature, not a feed failure.

## 5 Shadow-book outcomes + fire counterfactuals

**Shadow book (champion): 19 closed, 9 wins, −24.3 pts, −₹3,027.30 net.** Losses driven by two strategies
hitting STRUCTURAL_STOP on the afternoon dip: `scalp-market-movers-nifty` (7 stops, −48.7 pts) and
`scalp-golden-crossover-nifty` (3 stops, −23.2 pts). Most other slugs closed at SQUARE_OFF ~flat (+0.7% to
+6.4%); `scalp-trending-oi-nifty-pe` +11.9% was the best. Variants: vol-off 4 closed / −23.3 pts / −₹1,820;
vol-12k5 1 / −₹87. (Exit-fidelity caveat: shadow exits are brackets/structural/square-off only — indicator
trend-flip/signal exits are NOT replicated.)

**Fire counterfactuals — the 3 real fires (ADVISORY; no paper fill; 3-min chain LTP, near-intrinsic clean
value used where the snapshot carried multi-row noise; no slippage/fees; exit at the strategy's own EXIT
signal minute):**

| slug | side | entry IST | leg | entry prem | exit IST | exit prem | result |
|---|---|---|---|---|---|---|---|
| golden-crossover-nifty | CE | 13:39 | 24000CE (2026-07-14) | 237.25 | 14:03 | 221.80 | **−6.5%** (−15.45) |
| golden-crossover-nifty | CE | 14:24 | 24000CE | ~240 | 14:36 | ~240 | ~flat (future 24231→24230) |
| connect-the-dots-nifty | CE | 13:39 | 24000CE | 237.25 | 14:09 | 215.65 | **−9.1%** (−21.60) |
| straddle-nifty | CE+PE | 13:09 | 24200CE 97.65 + 24200PE 113.20 = 210.85 | | 14:39 | 105.25 + 101.15 = 206.40 | **−2.1%** (−4.45) |
| straddle-nifty | CE+PE | 14:42 | 24200 re-entry | — | (square-off) | — | UNRESOLVED (post-14:52 stall) |

- **All directional fires lost small to afternoon whipsaw.** The future dipped 24,229 → 24,208 in the
  20–30 min after the 13:39 entries (entries landed at a local top), then recovered to 24,250 close. The
  scalpers' EXIT signals fired on the dip (14:03/14:09), cutting −6.5% / −9.1% — the exit rule cannot use
  the post-exit recovery (hindsight). The straddle re-entry (14:42) is UNRESOLVED because the eval loop
  stalled at 14:52 (§6), so no engine exit was recorded.
- **Net advisory: 3 fires, all small losers (−2% to −9%).** A coherent up-day-with-afternoon-chop outcome:
  the relative floor correctly let directional scalpers fire on real volume, but entry timing (13:39, into a
  local top) was poor and exits cut the whipsaw. First directional-fire evidence — one session, not a verdict.
- **16 would-have-fired rows** (blocked only by volume-floor) exist this session; these fed the champion
  shadow book alongside the composite-passing multi-rail-blocked rows.

## 6 New data points / anomalies

- **STALL (operational, HIGH, RECURRING) — `signal-eval` silent from 14:52:22 IST → close.** Last per-bar
  log = 09:22:22 UTC (14:52:22 IST) = the last rejection row; shadow closes (15:12 square-off) and the 15:45
  sweep ran on separate scheduled threads. **Market-data candles healthy to 15:29.** Same signature as 07-07
  §8 (silent Redis `candles.1m.*` subscriber drop → executor starves → nothing logs). The redis channel
  `candles.1m.NFO.NIFTY26JULFUT` **has a live subscriber now** (re-subscribed, healthy at analysis time).
  **`SubscriberHealthCanary` (#634) is in the running image** (built 07-10 08:46) but produced **zero log
  lines** and eval did not recover before close. **Owner action: verify #634 fires + re-subscribes on a
  mid-session GREEN-feed-but-no-bar-received gap; if not, its stall window/market-hours guard needs
  tightening.** (Read-only run — no restart.)
- **FIRST DIRECTIONAL-SCALPER FIRES** — golden-crossover + connect-the-dots CE at composite 0.688 (above the
  0.6 directional threshold), enabled by the relative floor passing volume-expansion bars. Both lost small
  (§5). Milestone: the scalper family can now fire directionally on the live gate, not just the straddle path.
- **`oi-divergence-magnitude` operand crashed to 8.29** (from the 5.98→16.5→23.9→24.65 rising run) — confirms
  REGIME, kills any "slowly climbing structural" read. Rollup aggregates.
- **PE side alive (63 rows) but capped at 0.5** on an up day — consistent with "PE = regime": it evaluated,
  scored low, never passed. Watch a clean trend-DOWN day for whether PE composites can pass threshold.
- **`context.macro.vix` NULL while the vix dot works (86.7%)** — watch-item continues (ledger #10).
- **07-08 / 07-09 = zero rejection rows** (confirmed outage) — no session analysis exists for those days;
  the rollup session log skips them.

## 7 Tuning candidates (status ledger — carried forward from 2026-07-07)

| # | knob | current | proposed | evidence | status |
|---|---|---|---|---|---|
| 1 | `volume_floor` / relative floor | **relative k×median (k=1.5,N=20) ARMED #605** | tune k over 1 month | §2.1: 1st directional fires today (up-day volume expansion); floor working as designed | **SHIPPED/ARMED — in tuning window (owner: is k=1.5 right?)** |
| 2 | `artha.scalper.oi.ivPairMinGap` | 0.10 | 0.01–0.02 | §3: 0% dot support **all 5 sessions** | **PROPOSED (rollup §Proposals P1)** |
| 3 | `artha.scalper.oi.spurtPricePct` | 50 | 5–10 | §3: 0% dot support **all 5 sessions** | **PROPOSED (rollup §Proposals P2)** |
| 4 | breadth live producer | live A/D | — | §4: ALIVE, holding (#486) | **SHIPPED #486 (holding 07-10)** |
| 5 | iv_rank null semantics | null scores against | null = neutral / excluded | §3/§4: honest-null punished **all 5 sessions** | **PROPOSED (code) — rollup §Proposals P3** |
| 6 | composite threshold 0.6 | keep | keep (fix inputs) | §3 cap 0.816; 219 rows passed | DECIDED-KEEP |
| 7 | `DataHealthCanary` staleness watcher | BUILT #484/#491 | superseded by #634 for the consumer-side gap | see #14 | SUPERSEDED by #634 |
| 8 | candle upsert provenance preserve | BUILT | — | 07-03 A1 | SHIPPED |
| 9 | covered-range 1m re-fetch treadmill | FIXED | — | 07-03 A1 | SHIPPED |
| 10 | `context.macro.vix` NULL while vix dot works | macro mirror blind | populate macro.vix or add a data-health flag | §4/§6: NULL 701/701, dot path OK (86.7%) | PROPOSED (data-health, low urgency) |
| 11 | shadow entry latency p95 | structural 3m cadence | investigate | shadows reopened today; measure next rollup | PROPOSED (measure across sessions) |
| 12 | composite-070 variant never opens | configured, 0 rows all-time | owner: loosen or drop | still 0 all-time | WATCH (owner) |
| 13 | `scalp-straddle-nifty` fires 0DTE ATM straddles, no paper fill | re-fired today (13:09, 14:42) | owner: confirm threshold + whether to auto-paper | §5: straddle re-fired, small loss | WATCH (owner) |
| 14 | strategy-signal eval STALL (silent Redis subscriber drop) | #634 `SubscriberHealthCanary` DEPLOYED | **VERIFY #634 pages + re-subscribes on this signature** | §6: 3rd stall (14:52), #634 in image but 0 logs, eval did not recover | **RE-OPENED — HIGH (verify #634 coverage/action)** |

**Five-session note:** items 2/3/5 are STRUCTURAL (dead every session; threshold vs operand physical
range) and are now promoted to the rollup **§Proposals** (this is the 5th logged session). Item 1 (relative
floor) is ARMED and in its live tuning window — judge k=1.5 on real fires (today's first directional fires
lost small to whipsaw; watch entry-timing quality over the month). Item 14 is the priority operational item.
**This session is TRUNCATED (09:19–14:52)** — treat the afternoon as missing, not empty.
