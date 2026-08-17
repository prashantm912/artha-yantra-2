# Session findings — 2026-08-17 (data date)

Analysis date: 2026-08-17 EOD (scheduled post-market agent, run 15:55 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,303** (bounds
`2026-08-17T09:15:00+05:30`…`15:40`; rows 09:19–14:43), signals fired **4 ENTRY + 3 EXIT**,
paper trades **3 opened / 3 closed, net −₹891.88**, shadow closes **60** (champion 38 +
challengers 22).

Session character: **Monday, NO expiry today** (NSE weekly Tue 08-18 tomorrow, BSE Thu 08-20)
· VIX 11.30–11.71 · **CHOP** (o 24,343.45 → continuous close 24,339.80 = −0.01% on a 0.55%
range, efficiency **0.027**; official CAS bar reads eff 0.419 = mixed — third straddle of a
cut boundary, and the **first NEGATIVE CAS print in the series: −52.15**, 15:28 → 24,287.65)
· morning PE drift fired the funded book (09:45–10:51), afternoon SENSEX CE bounce refused by
the 60m-bias veto · signal contract `NFO:NIFTY26AUGFUT` (log-confirmed). Logs of BOTH services
snapshotted to scratchpad at run start (NEW-4) — ⚠️ **market-data was recreated 15:36 IST
(PR #1394 deploy) BEFORE this run started, so its session logs are DESTROYED**; §3.37
fallbacks used, and the log-loss caveat applies to every market-data log-derived check.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,303 — **34 of 38 scalpers** (silent: `scalp-hero-zero-nifty`, `scalp-hero-zero-sensex-niftyoi`, `scalp-open-high-low-nifty`, `scalp-open-high-low-sensex-niftyoi`; hero-zero's `premium_skew` dot absent from today's tables accordingly) |
| eval outcomes | chart-gate-failed 2,014 · confluence-blocked 1,303 · composite-below-threshold 206 · **fired 4** · discipline-paused 0 |
| fired reconciliation (§3.36) | 4 fired evals = **4 ENTRY emitted** (203 @09:45 ctd-nifty-pe 0.9877; 205+206 @10:45 golden-crossover pair 1.0000; 207 @10:51 ctd-sensex-niftyoi-pe 0.9929) + 3 EXIT = 7 emits, matching `ay_signal_bar_to_emit_seconds_count` 7. Risk-gate grep 0 (no `daily_profit_target` trip — red day) |
| coverage | 22 of 25 buckets populated 09:15–14:43; **rejections END 14:43 — NOT a stall**: eval buckets keep writing to 15:18+ (chart-gate 16–22/bucket to 14:57, window-close taper after 15:00), discipline-paused 0. The 09:15/09:30 buckets are thin (8/10 rows, 2 slugs — morning-trade class) |
| boot health | ONE pre-open boot 08:17:30 IST with a **10-minute 0-loaded/38-unresolved transient** (`engine_reloads`: install 0/38/0 at 08:17:30 → install **38/0/0 at 08:27:15**; boot line `loaded 38 published (0 dropped, 0 failed)` at 08:27:15 in live logs). Same class as 08-14's transient but ~90 s there vs **~10 min** today — watch the growth. Resolved 48 min before open; no session impact |
| first-block histogram | volume-floor 789 (**60.6%**) · time-window 188 · rsi-band 150 · time-of-day 34 · divergence-vol-gate 22 · confluence-composite 22 · pct-price-move 20 · two-candle 20 · volume-pump 20 · oi-cross-required 14 · option-side 12 · rest ≤4 |
| paper (funded) | pos 72 `NIFTY2681824450PE` 65 @157.50 (09:46, sub-1) → **TIME_STOP 10:16 +₹625.03** · pos 73 `SENSEX2682078200PE` 40 @725.65 (10:46 + 10:52 pyramid, sub-2) → **TIME_STOP 11:22 −₹188.31** · pos 74 `NIFTY2681824400PE` **130 @165.05 in ONE fill (10:46, sub-3)** → **TIME_STOP 11:22 −₹1,328.60**. All three exits TIME_STOP (per-strategy `max_bars`). Emit latency avg ~20.1 s (141.048/7 — G8/T26 class). Zero-sized entries: 0 |

## 2 Rail findings

- **§2.2 chain-proximity: the NSE-Tuesday-eve cluster arrived on schedule — `strike-pick`
  361 fails, ALL nifty-rooted (15 slugs), ZERO sensex.** Series: 08-05 0 / 08-06 0 / 08-07
  350-SX / 08-11 322-NF / 08-12 0 / 08-13 336-SX / 08-14 14-SX / **08-17 361-NF (Mon eve of
  NSE Tue 08-18)**. Matches §3.27's Mon–Tue-around-NSE-expiry cluster; day-of window is
  tomorrow.
- **volume-floor banded and honest, but the binding share jumped to 60.6%** (50.0% on
  08-14): thresholds 8,970–32,955 (31 distinct, avg 21,471), vs aligned 3m AUGFUT volume
  p50 10,855 / p90 37,765 / max 190,710 — floor ≈ p70–p75. §3.14: `relative-volume-floor`
  armed **38/38** published.
- **The 60m-bias veto again refused the afternoon CE bounce**: 530 `confluence-composite`
  fails carry reason `60m bias opposes the side`, **246 of them with composite ≥ threshold**
  (the whole CE pass tail). Genuine near-miss class is separate (aggregate-below rows, avg
  margin −0.055). See §5 for what the veto was worth — same shape as 08-14.

## 3 Composite + dots

- Distribution (scored n=1,067): pass mass **464/1,067 (43.5%) — the highest pass share
  recorded** (08-14: 25.1%), CE 246 / PE 218 balanced; max rejected 0.8627. The gate's
  binding constraint today was volume, not confluence.
- Dot support (complete session, n=1,067 unless noted): `iv_rank` 0% (withheld, standing) ·
  `iv_pair` **0% (20th session — T3, owner)** · oi_spurt 11.7% · volume 26.1% · breadth
  36.9% · vwap 41.0% · trending_cross 42.7% · iv_slope 48.4% (n=153) · basis 49.7% · vix
  52.6% · sentiment_slope 54.5% · futures_oi 59.3% · underlying_oi 62.0% · psar 74.3% ·
  sentiment 82.1% · rsi 82.9% · vwma 89.8% · drastic_oi 98.5% · `iv_abs_band` **100%
  (n=153; frozen daily stamp, 16th session)** · supertrend 100%.
- **§3.28 breadth side-split — 5th consecutive dead-CE session**: CE advances 18–27 vs
  `>32` → **0/530**; PE declines 23–40 straddling the line → **394/537 (73.4%)**. The
  aggregate 36.9% is a dead side plus a straddling side (the 08-13 shape, not 08-14's
  re-saturated PE). T30 evidence row.
- OI bloc fully live: quadrants NEUTRAL **0/1,067** contextful (SHORT_COVERING 296 /
  SHORT_BUILDUP 273 / LONG_BUILDUP 260 / LONG_UNWINDING 238; 236 NULLs = context-less
  pre-fetch class); `futures_oi_snapshots` 25,668 snaps / **372 of ~375 minutes**.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,303/1,303 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,303/1,303 | by design (un-armed) |
| `fiiLongPct` | NULL 236 = exactly the context-less rows; 1 distinct on the rest | daily EOD stamp, alive |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 16th) |
| vix | 32 distinct, 11.30–11.71 | alive |
| ceIvAvg6 / skew / basis | 68 / 89 / 84 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean |
| §3.17 canary | **3 WARNs, 0 straddles — all explained**: 09:15 opening bucket −1,560 (boot-fresh empty-lot-cache class) + the 13:06/13:09 ±6,890 pair (lot-multiple, equal-and-opposite, consecutive buckets) reported as two UNPAIRED because it was the **first pair since the 08:17 boot — the documented fail-closed cold-cache behaviour**, not a regression | no unexplained WARN |
| Kite session | validated 15:56 IST | ✓ |
| market-data canary | GREEN, 0 problems, 77 ticked tokens (post-close read) | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 38 closes, 11W, −172.25 pts, −₹23,396.06 NET → 13 deduped (bar, leg) events;
all-time −₹189,863.04 → −₹213,259.10** (points −2,454.05 → −2,626.30). Same three-act
shape as 08-14:
- Morning PE clusters killed by the midday stabilisation: 09:24 pair −₹6,106.35 · 09:45
  `NIFTY…24450PE` ×5 + `SENSEX…78400PE` ×6 **−₹33,035.52** (the funded legs' bar-family) ·
  10:00–10:42 SENSEX/NIFTY PE −₹10,834.01.
- The 60m-bias-vetoed CE class: champion traded it — 12:00 `SENSEX2682077200CE` ×6
  **+₹22,938.14** (5 legs closed **TAKE_PROFIT +237.55 pts avg** — the +35% T21 bracket
  paying) · 12:18 +₹4,489.23 · 12:39 +₹3,483.05 · 13:03 clusters −₹4,330.60.
- **§3.24 multi-exit clusters on the CHOP day (G11's 6th observation): 3 of 4
  stop-favouring.** 09:45 NIFTY: structural −13.60 beat SL −41.70 / hold −60.30 · 09:45
  SENSEX: structural −36.40 beat SL −181.55 / hold −205.90 · 10:42: structural −12.85 beat
  SL −38.90 · 12:00 (the winner): **TAKE_PROFIT +237.55 crushed structural −17.60** —
  bracket-favouring, not hold-favouring. Cluster series now **11 stop / 2 hold / 1 TP**
  (decision already made 07-31: KEEP).

**Challengers: league day positive for two books (composite-055 +₹3,659.05 → −₹4,578.72 ·
vol-12k5 +₹3,756.39 → −₹40,133.81 · vol-off −₹848.76 → −₹55,531.68) — but the
challenger-ONLY class lost again: 9th measured loosening, 9th loss** (only-class:
composite-055 −₹1,013.27 · vol-12k5 −₹915.93 · vol-off −₹4,621.07). The league positives
are the SHARED 12:00 CE winners; the mix artifact of 08-14 repeats.

**§4.2 counterfactuals** (sole-blocker sets, deduped ~18 (bar, leg) events; resolved via
shadow twins — modelled exits are the shadow's brackets/structural/square-off, NOT the
engine time stop; 3-min LTP granularity, no slippage/fees):
- Morning PE sole-blocker rows (volume-floor 7 legs · volume-pump/two-candle/
  pct-price-move/max-oi-sr 4 each · rsi-band 2; 09:45–11:24, all PE): same bar-families as
  the funded fires and the losing shadow clusters → **WOULD-LOSE across the board** (every
  morning PE cluster negative).
- The 60m-bias sole-blocked CE rows (8 rows → 5 legs, 12:00–13:48): 12:00/12:18
  `SENSEX…77200CE` **WOULD-WIN** by twins (+₹22,938 / +₹4,489); the 13:18/13:48 legs
  **WOULD-LOSE** by the 13:03 clusters (−₹4,330.60 net). Veto refused the day's one big
  winner for the 2nd consecutive session — but the win is again carried ~95% by a single
  bar-family (§3.24), and the §3.26 standing prior (all NINE measured loosenings lost)
  stands. **No tuning row filed.**

## 6 New data points / anomalies

### 6.1 Post-close eval-stall: the engine-reload path blocks the signal-eval thread on market-data HTTP during a deploy (promoted to README §3.38)

`strategy.subscriber_health_events` **15:28:20 IST: `eval-stall` — "bars arriving but not
evaluated for 180s (receipt 19s old)"**, with the watchdog's thread-stack capture showing
`signal-eval` **WAITING inside `FuturesUniverseResolver.resolve` → RestClient HTTP to
market-data**, reached from `SignalEngine.reload` via `drainReloadOnly` — the reload path
runs ON the eval thread by design (config swaps at bar boundaries). Timeline:
`engine_reloads` shows three consecutive slow reloads completing 15:26:58 / 15:28:16 /
15:29:23 (~70–80 s each vs normally sub-second "unchanged"), exactly the window in which
market-data was being redeployed for PR #1394 (new container `StartedAt` 15:36:22 IST);
a clean reload at 15:37:15 after it came up healthy. Gauges now read received-age ==
evaluated-age (last bar ~15:34) — **self-recovered, zero trading impact** (every scalper
window closed by 15:21; rejections had ended 14:43; square-offs done 15:12).

**Why it matters:** the same sequence during market hours would starve evaluation for the
duration of the deploy — this is the first live measurement of the mechanism, and it is
direct evidence for the standing "no mid-session deploys" proposal (NEW 08-04). The
market-data half of the evidence is unknowable (its logs were destroyed by the same deploy
— §3.37). Detection worked end-to-end: watchdog ERROR + DB row + stack capture.

### 6.2 First genuine 2-lot NIFTY fill under NEW-2 (₹25k budget)

pos 74 filled **130 qty (2 × 65) in a single OPENED event** (10:46, sub-3) — unlike 08-14's
pos 71 (130 via two-signal pyramid). 08-14's trace predicted the 2-lot regime needs the E8
imbalance operand ≥ 50; today's fill is consistent [computed — operand inferred from the
sizing arithmetic, not read from a row]. pos 73 also shows pyramiding intact (10:46 open +
10:52 average-in on signals 205/207 sharing the SENSEX leg).

### 6.3 §3.34 heat-gate evaluability — PASS (4th consecutive funded-day)

Log grep `heat call failed|heat unassessable` = **0** across 3 funded entries; all three
rows carry `margin_snapshot 0.00 / margin_pct 0.00` populated. Evaluability only — the
zero-SPAN long-option coverage question (N23-A) stands.

### 6.4 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines (was 10)** — 6×[A] + 5×[B]. The
  new [A] is `task_53ce441b`: the checker reads the chip id quoted inside the H24 ledger
  row (itself an OPEN item) as a "closed" mention — a false positive of the standing
  keyword class; the chip is genuinely open (H24 PR-1 of 13 done). No substantive
  contradiction; no edits required.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted:
  exactly the 24 #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** ✅ **The 2
  minervini 1.0.2 drafts CLEARED** (drifted 26 → 24) — the standing minervini republish
  proposal is RESOLVED; the disabled-scalper drafts note carries (diff before any
  re-enable). Nothing republished by this run.

### 6.5 §3.29 unexercised-path audit (day delta)

Fired vocabulary since 07-01 (all books): **TIME_STOP 10 → 13** (+3: pos 72/73/74) ·
TRAILING_STOP 17 · STRUCTURAL_STOP 8 · STOP_LOSS 6 · MANUAL 2. Armed set unchanged (10
(type,basis) rows + `oi-confluence-exit` tag on 8). Never-fired stands: `take_profit
premium_pct` (36 — class (c) SHADOWED in the funded book; note the SHADOW book's +35% TP
paid ₹4,672.32 × 5 today, §5) · `signal_exit` (38) · `square_off` (2) · `stop_loss percent`
(4) · tag `oi-confluence-exit` (8). INDETERMINATE pair (`trailing_stop atr_multiple` 2,
`stop_loss atr_multiple` 2) stands.

### 6.6 §3.30 freeze telemetry — ≥3-of-5 flag HIT (3rd time)

Entries: sub-1 ×1 (09:46), sub-2 ×2 (10:46 + 10:52 average-in), sub-3 ×1 (10:46). Day PnL:
sub-1 **+₹625.03 → profit-lock frozen ~10:16** (+2.1% of the ₹30k allocation, over the ~1%
lock) · sub-2 −₹188.31 first-loss frozen ~11:22 · sub-3 −₹1,328.60 first-loss frozen
~11:22. **3 of 5 stopped before 14:30 — flag threshold hit**, trend: 08-11 1/5 · 08-12
3/5+global · 08-13 2/5 · 08-14 2/5 · **08-17 3/5 (all by 11:22, earliest yet)**. Context
that keeps it protection-not-starvation today: the last fired eval was 10:51 — the gate
produced nothing after the freezes anyway, so the governors blocked zero entries
(counterfactual cost ₹0; subs 4/5 stayed available and untouched).

### 6.7 Coverage dip 38 → 34

Silent slugs: the hero-zero pair (returned 08-14, silent again) and the open-high-low pair.
All four are early/window-constrained families; chart-gate-failed 2,014 covers their bars
and `engine_reloads` shows 38/0/0 all day, so this is chart-gate silence, not a load
failure. Noted for the §3.10 trend, not alarmed.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| **NEW-6 (08-17)** | engine-reload blocks eval thread on market-data HTTP | **PROPOSED (evidence for NEW 08-04 mid-session-deploy rule; promoted as §3.38)** | first live measurement: 3 × ~75 s reloads during the PR #1394 market-data recreate starved eval 180 s+, watchdog fired, self-healed post-close (§6.1). Zero impact today; mid-session it would be an outage |
| watch | `strike-pick` chain-proximity | **WATCH — Mon-eve NSE cluster on schedule: 361-NF** | §2; day-of window tomorrow (Tue 08-18) |
| NEW-5 (08-14) | Redis candles.1m subscription drop | **WATCH — no recurrence** (today's stall is a different class: §6.1) | week window open to 08-21 |
| NEW-2 (08-12) | scalper `budget_inr` ₹25k | **SHIPPED-VERIFIED** | first genuine 2-lot NIFTY fill (§6.2); 24 disabled-scalper drafts = standing residue |
| NEW-4 (08-13) | post-close deploy log snapshot | **PROPOSED (process, owner) — carried; VIOLATED today by the 15:36 market-data recreate** | this run started 15:55, after the deploy — market-data session logs gone (§3.37 caveat in header). The strategy-signal snapshot survived (boot 08:17) |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip (red day) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | grep 0 on 3 funded entries — 4th consecutive evaluability PASS (§6.3); N23-A stands |
| T29/G11 | scalper `time_stop` | **CLOSED (owner KEEP) — 6th chop-day observation, again stop-favouring** | 3 of 4 clusters stop-favouring; the 4th favoured the TP bracket, not the hold (§5). Series 11 stop / 2 hold / 1 TP |
| T30 | `breadth` dot `>32` | **OPEN** | 5th session dead-CE (adv max 27); PE straddling 73.4% |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | floor ~p70–p75 banded, zero flat; binding share up to 60.6%; challenger-only class lost again — 9th loosening loss |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (16th) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (20th session) |
| T23 | partial-bucket tolerance | **OPEN** | 3 WARNs all explained (boot-fresh open + first-pair-after-boot cold cache); 0 straddles |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5 only-class −₹915.93 (9th loss) |
| T7 | composite threshold | **REJECTED — carried** | composite-055 only-class −₹1,013.27 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried, evidence STRENGTHENED** | §6.1 measured the mechanism a mid-session deploy would trigger |
| NEW (08-03) | minervini republish | **RESOLVED — drift cleared** (§6.4) |
| T10 | stale OPEN paper positions | **OWNER — chronic** | no swing closes today as of 15:55 (evening batch pending); population ~15 |
| T8/T26 | latency | OPEN (data) | shadow p50 1:20.0 / p95 1:23.0 (n=60); emit avg ~20.1 s — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 1,303/1,303 |

## 8 Honesty caveats

- **This run executed at 15:55 IST** — before the 16:00 swing batch, the 18:4x screen/EOD
  jobs and the 16:00 `iv_daily_summary` stamp; T10 and ingest-coverage statements are
  as-of-run-time.
- market-data's session logs were destroyed by the 15:36 recreate (deploy of #1394); every
  market-data log-derived check is unknowable for this session (§3.37) — reported as such,
  not as clean. The strategy-signal log snapshot covers the full session (boot 08:17 IST).
- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book
  (per-strategy `max_bars`) are different exit models; today the funded TIME_STOP exits
  (−₹891.88 total) again cut earlier and lost less than the shadow twins on the same
  bar-families.
- The §5 challenger league positives and the 60m-bias-veto "cost" are both carried ~95% by
  the single 12:00 SENSEX CE bar-family — §3.24 fan-out inflation applies; effective
  independent afternoon sample ~4 bar-families.
- The §6.2 sizing trace is code-derived arithmetic; the E8 imbalance operand (≥50) is
  inferred, not read from a persisted row.
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.027 = **chop**; the official
  CAS bar reads 0.419 (mixed) — third straddle, doctrine keeps the continuous stamp. First
  negative CAS print (−52.15).
- Read-only run: SELECTs, in-container health GETs, log greps on a pre-snapshotted copy.
  No restarts, deploys, writes, config changes, or republishes. Docs-only PR: this file +
  rollup rows + README §3.38.
