# Session findings — 2026-08-14 (data date)

Analysis date: 2026-08-14 EOD (scheduled post-market agent). Analyst: Claude (scheduled
`session-analysis post`). Data: `signal_rejections` rows **1,189** (bounds
`2026-08-14T09:15:00+05:30`…`15:40`; rows 09:19–15:19), signals fired **4 ENTRY + 3 EXIT**,
paper trades **3 opened / 3 closed, net −₹1,469.12**, shadow closes **52** (champion 38 +
challengers 14).

Session character: **Friday, NO expiry on either exchange** (next: NSE Tue 08-18, BSE Thu
08-20) · VIX 11.23–11.52 · **CHOP** (o 24,361.90 → continuous close 24,354.85 = −0.03% on a
0.44% range, efficiency **0.065**; official CAS bar +0.02% / eff 0.038 — both sides of the
cut agree, no straddle) · morning PE drift fired the funded book, afternoon CE bounce filled
the composite tail (0.7+: 110 CE / 2 PE) · signal contract `NFO:NIFTY26AUGFUT` (log-confirmed,
1,199 rail lines). Logs of BOTH services snapshotted to scratchpad BEFORE any deploy could
destroy them (NEW-4 discipline, first compliant run).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,189 (**38 of 38 scalpers — first FULL-fleet emission since the stamp series began**; the hero-zero NIFTY-OI pair returned, carrying its `premium_skew`/`iv_slope` dots back into the tables) |
| eval outcomes | chart-gate-failed 1,898 · confluence-blocked 1,189 · composite-below-threshold 342 · **fired 4** · discipline-paused 0 |
| fired reconciliation (§3.36) | 4 fired evals = **4 ENTRY signals emitted** (196 @10:33 composite 0.9968; 198/199/200 @11:03 composite 0.8328) — risk-gate grep 0, no suppressed class, `daily_profit_target` never tripped (red day) |
| coverage | **25 of 25 buckets populated, but NOT clean — see §6.1**: a mid-session **receive-stall 13:02→13:16** (Redis `candles.1m` subscription dropped; self-healed) hollowed the 13:00 bucket to n=16 vs 70–80 neighbours |
| boot health | two PRE-OPEN boots (morning deploy): 08:38 boot with a ~90 s 0/38-unresolved transient → **38/0/0 at 08:39:38**; recreate → install **38/0/0 at 08:54:21**. Boot line read from live logs: `loaded 38 published strategies (0 dropped, 0 failed)` |
| first-block histogram | volume-floor 595 (50.0%) · time-window 206 · rsi-band 87 · time-of-day 44 · pct-price-move 38 · volume-pump 38 · two-candle 38 · confluence-composite 34 · divergence-vol-gate 32 · option-side 22 · rest ≤16 |
| paper (funded) | pos 69 `NIFTY2681824500PE` 65 @191.15 (10:34) → **TRAILING_STOP 10:55 −₹1,462.56** · pos 70 `SENSEX2682078500PE` 20 @767.05 (11:04) → **STRUCTURAL_STOP 11:07 +₹106.02** · pos 71 `NIFTY2681824500PE` **130** @182.75 (11:04, signals 199+200 pyramid-averaged, sub-3) → **STRUCTURAL_STOP 11:07 −₹112.58**. Emit latency 18.7–22.8 s (G8/T26 class). **Zero-sized entries: 0** (08-12 had 3) |

## 2 Rail findings

- **§2.2 chain-proximity: the post-BSE-expiry Friday came in CLEAN — `strike-pick` 14 fails
  (7 SENSEX slugs), vs 550/374/350 on the three prior post-expiry Fridays.** Series: 08-03
  eve 235-NF / 08-04 day-of 604-NF / 08-05 Wed 0 / 08-06 BSE-Thu 0 / 08-07 Fri 350-SX /
  08-11 NSE-Tue 322-NF / 08-12 Wed 0 / 08-13 BSE-Thu 336-SX / **08-14 Fri 14-SX**. Checked
  before crediting #1075: the picker's premium band is the STATIC S24 table
  (`ScalperConfig` — SENSEX 300–800 under both tag states) and `budget_inr` never reaches
  `StrikePicker`, so the budget change cannot explain it. The fresh 08-20 SENSEX weekly
  priced its delta-band strikes at ~700–775 — inside the band — where the three prior
  post-roll Fridays priced outside it. **Chain regime, not config; the "three consecutive
  saturated Fridays" pattern in README §3.27's 08-07 amendment is now 3-of-4** (amendment
  added to §3.27).
- **volume-floor banded and honest**: thresholds 7,605–19,939 (avg 13,226), zero flat rows,
  vs aligned 3m AUGFUT volume p50 9,165 / p90 25,675 / max 91,780 — floor ~p70. §3.14:
  `relative-volume-floor` armed **38/38** published.
- **The `confluence-composite` first-block class today is the 60m-bias veto, not the
  number**: all 34 first-blocks and the whole §3.5 CE tail carried composite ≥ threshold
  with reason `60m bias opposes the side` (a decisive leg of `bullish()`/`bearish()` since
  #404 — the hourly bias refused the afternoon CE bounce on a chop tape). See §5 for what
  that veto was worth.

## 3 Composite + dots

- Distribution (scored n=915): pass mass **230/915 (25.1%)**; max rejected 0.8627 (13:51 CE,
  60m-bias veto); fired bars 0.9968 / 0.8328. High tail CE-heavy (0.7+: 110 CE / 2 PE) —
  afternoon bounce; the morning PE legs fired instead of queueing.
- Dot support (complete session, n=915 unless noted): `iv_rank` 0% (withheld, standing) ·
  `iv_pair` **0% (19th session — T3, owner)** · oi_spurt 2.8% · iv_slope 16.3% (n=135) ·
  vwap 21.4% · trending_cross 22.2% · volume 35.0% · `premium_skew` 35.3% (n=34, hero-zero
  carriers re-emitting — dot present since #317, not new) · rsi 39.0% · breadth 46.2% ·
  sentiment_slope 50.4% · futures_oi 51.4% · underlying_oi 52.9% · basis 53.8% · vix 62.5% ·
  sentiment 71.7% · psar 74.4% · vwma 87.1% · drastic_oi 87.9% · supertrend 96.9% ·
  `iv_abs_band` **100% (n=135; frozen daily stamp, 15th session)**.
- **§3.28 breadth side-split, 4th consecutive dead-CE session and the PE side re-saturated**:
  CE advances 0–20 vs `>32` → **0/492**; PE declines 33–39, every row over the line →
  **423/423**. The aggregate 46.2% is two saturated sides superimposed (the 08-11 shape,
  not 08-13's straddling PE). T30 evidence row.
- OI bloc fully live: quadrants NEUTRAL **0/915** contextful (SHORT_BUILDUP 306 /
  SHORT_COVERING 221 / LONG_BUILDUP 216 / LONG_UNWINDING 172; the 274 NULLs are the
  context-less pre-fetch class); `futures_oi_snapshots` 366/375 minutes (the missing minutes
  sit in the §6.1 stall window).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,189/1,189 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,189/1,189 | by design (un-armed) |
| `fiiLongPct` | NULL 274 = exactly the context-less rows; 1 distinct on the rest | daily EOD stamp, alive |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 15th) |
| vix | 26 distinct, 11.23–11.52 | alive |
| ceIvAvg6 / skew / basis | 45 / 91 / 89 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — notable because the §6.1 stall recovery backfilled ALIGNED, same as 08-11 |
| §3.17 canary | **2 WARNs, 0 straddles — both explained**: 09:15 opening bucket −2,470 (boot-fresh empty lot cache, the documented post-restart class) + 13:15 recovery bucket +15,860 (the §6.1 stall's recovery bucket, same class as 08-11's outage WARN) | no unexplained WARN |
| Kite session | validated 16:03 IST | ✓ |
| market-data canary | GREEN, 0 problems, 93 ticked tokens (post-close read) | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 38 closes, 14W, −823.85 pts, −₹29,476.89 NET → 13 deduped (bar, leg) events;
all-time −₹160,386.15 → −₹189,863.04** (3rd-worst session). The day in three acts:
- Morning PE entries near the low, killed by the bounce: 10:33 `NIFTY2681824500PE` ×6
  **−₹22,159.37** (funded pos 69/71's leg) · 11:03 `SENSEX2682078500PE` ×7 **−₹24,095.61**
  (pos 70's leg).
- The afternoon CE class the 60m-bias veto refused (§2): champion shadow traded it anyway —
  12:21 `NIFTY…24250CE`+`SENSEX…77400CE` **+₹24,914.42** (the day's only big winner),
  13:00 −₹1,035.64 · 13:45 −₹629.48 mixed · 14:30 +₹594.30.
- **§3.24 multi-exit clusters on the CHOP day (G11's second chop observation — the one its
  DONE row said would harden it): 2 of 3 stop-favouring.** 10:33: structural −23.20 beat
  SL@13:45 −60.95 and hold −67.90 · 11:03: structural −5.15 crushed SL −194.10 / hold
  −204.30 · 12:21 (the winner): hold +37.40/+144.40 beat structural −6.20/−19.20. Funded
  exits (TRAILING 10:55 at −22.5 pts, STRUCTURAL 11:07) again matched or beat every shadow
  model on the loser legs. **G11 cluster series now 8 stop-favouring / 2 pro-hold; the KEEP
  decision's second chop-day datapoint landed pro-stop.**

**Challengers: league NET positive (composite-055 +₹3,057.17 · vol-12k5 +₹3,057.17 ·
vol-off +₹3,332.68) — but the challenger-ONLY class lost again: 8th measured loosening, 8th
loss.** The positive league is a mix artifact: every challenger's winners are the SHARED
12:21 CE rows the champion also took (+₹2,808/+₹2,360 each), while their morning acceptance
skipped the champion's big PE losers. The rows ONLY a loosened config accepted — the actual
loosening delta — were uniformly negative: composite-055 −₹2,111.46 · vol-12k5 −₹2,111.46 ·
vol-off −₹1,835.95 (14:00 CE pair −₹2,111.46; vol-off's extra 14:21 pair +₹275.51).

**§4.2 counterfactuals** (modelled horizon: 30-min, a HARNESS choice per §3.16; 3-min LTP
granularity, no slippage/fees):
- 09:27 morning-trade PE pair (sole blocker `morning-eod-precondition`):
  `NIFTY2681824550PE` @218.30 → ~208–213 at 09:57 = **WOULD-LOSE ~−8 pts**;
  `SENSEX2682078500PE` @766.85 → ~720–750 = **WOULD-LOSE −20 to −45 pts**. No bracket touch
  on either.
- 10:33 / 11:03 single-rail rows (volume-pump / two-candle / pct-price-move / max-oi-sr):
  same legs as the funded fires and shadow clusters — **WOULD-LOSE** by their twins.
- The 60m-bias CE class (12:21–14:06, 20 rows → ~7 deduped bars): shadow twins price it at
  **net +₹23,843 across the four traded clusters, 95% carried by the single 12:21 bar** —
  the veto cost the day's winners, but per §3.24 that is ONE bar's evidence, and per §3.26
  the standing prior (all measured entry loosenings lost) stands. No tuning row filed.

## 6 New data points / anomalies

### 6.1 Mid-session receive-stall, self-healed in 43 s — the resubscribe watchdog's first live save

`strategy.subscriber_health_events` 13:15:43 IST: **`receive-stall` — "no candle received
for 818s while the feed is live (ticks 0s old) — Redis candles.1m subscription dropped;
re-subscribing"** → `resubscribe` same second → `recovery (43s)` at 13:16:43. Rejections
hole ≈ 13:02–13:16 (the 13:00 bucket holds 16 rows vs 70–80 in its neighbours); eval
gauges/buckets never went stale enough to page. Unlike 08-11 (power loss), this was an
in-process Redis subscription drop on a healthy stack — the exact class the
`SubscriberHealthCanary`+resubscribe chain was built for, and it worked: detection at the
818 s threshold, recovery in 43 s, backfill ALIGNED (§3.15 = 0). The 13:15 canary WARN
(§4) is this event's recovery bucket. **Watch item: a second same-class drop within a week
would suggest a Redis-client or broker-side regression rather than a one-off.**

### 6.2 NEW-2 (₹25k budget) VERIFIED live — sizing traced end-to-end on its first day

- **SENSEX**: pos 70's per-lot cost ₹15,341 (767.05 × 20) **exceeds the old ₹15,000
  budget** — this entry sizes to ZERO under the old config and funded 1 lot today. Direct
  proof the 15:45-yesterday republish is live.
- **NIFTY**: all three fills were 1 lot (65) where bare budget arithmetic gives 2
  (⌊25000/(191.1×65)⌋ = ⌊25000/(182.7×65)⌋ = 2). Traced [computed]: `PaperEmissionGuard
  .suggestedQty` multiplies the `PositionSizer` base by the **E8 graded multiplier**
  (`ScalperSizing`), and 2 lots × 0.85 (OI-gap thin factor; aggregate ≥0.75 → 1.0, VIX
  11.5 → 1.0) floors to **1 lot**. The multiplier operand (call-put imbalance < 50) is
  inferred from the arithmetic, not read from a row — consistent across all three fills.
  **First observed interaction of E8 grading with the raised budget: on thin-imbalance
  days the fleet still sizes 1 NIFTY lot; the 2-lot regime needs imbalance ≥ 50.**
- pos 71 is qty 130 via PYRAMIDING (signals 199+200, two strategies, same leg/bar,
  averaged into one sub-3 row), not 2-lot sizing.

### 6.3 §3.34 heat-gate evaluability — PASS (3rd consecutive funded-day)

Log grep `heat call failed|heat unassessable` = **0** across 3 funded entries; all three
rows carry `margin_snapshot 0.00 / margin_pct 0.00` populated. Evaluability only — the
zero-SPAN long-option coverage question (N23-A) stands.

### 6.4 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **10 REVIEW lines (was 11)** — 5×[A] open/closed
  snapshot chips + 5×[B] keyword refs, all standing false-positive classes; the 08-13 wave's
  extra [A] chip cleared. No substantive contradiction; no edits required.
- `tools/published-config-drift.py`: **69 published — 69 matched (43 clean, 26 drifted:
  the 24 #1075 disabled-scalper drafts + the same 2 minervini 1.0.2 drafts, 10th session),
  0 DB-only, 0 YAML-only.** Nothing republished by this run. Standing notes carry: minervini
  republish proposal; disabled-scalper drafts must be diffed + republished before any
  re-enable.

### 6.5 §3.29 unexercised-path audit (day delta)

Fired vocabulary since 07-01 (all books): **TRAILING_STOP 13 → 17** (+1 scalper pos 69 —
the trailing stop's first scalper fire since 07-01's pair — and +3 swing closes, see §6.7) ·
**STRUCTURAL_STOP 6 → 8** (+2: pos 70/71) · TIME_STOP 10 · STOP_LOSS 6 · MANUAL 2. Armed
set unchanged (10 (type,basis) rows + `oi-confluence-exit` tag on 8). Never-fired stands:
`take_profit premium_pct` (36 — class (c) SHADOWED) · `signal_exit` (38) · `square_off` (2)
· `stop_loss percent` (4) · tag `oi-confluence-exit` (8). INDETERMINATE pair
(`trailing_stop atr_multiple` 2, `stop_loss atr_multiple` 2) stands.

### 6.6 §3.30 freeze telemetry

Entries: sub-1 ×1 (10:34), sub-2 ×1 (11:04), sub-3 ×2 (11:04, the pyramid pair). Day PnL:
sub-1 −₹1,462.56 (first-loss frozen ~10:55) · sub-2 +₹106.02 (win — not frozen, below the
1% profit-lock) · sub-3 −₹112.58 (first-loss frozen ~11:07). **2 of 5 stopped before 14:30 —
below the ≥3 flag.** Trend: 08-11 1/5 · 08-12 3/5+global · 08-13 2/5 · 08-14 2/5.

### 6.7 T10 movement: 18 → 15 stale OPEN swing positions

3 swing positions closed today via TRAILING_STOP (manas-arora 6→4, minervini 12→11) — the
first change in the chronic population in weeks. 15 remain; the owner row stands.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| watch | `strike-pick` chain-proximity | **WATCH — post-expiry Friday CLEAN (14-SX vs 550/374/350 on the 3 prior)** | §2; the Friday half of the cluster claim is now 3-of-4; next windows Mon/Tue 08-17/18 (NSE) |
| **NEW-5 (08-14)** | Redis candles.1m subscription drop | **WATCH (no action)** | first in-process receive-stall, self-healed 43 s (§6.1); second same-class event within a week ⇒ investigate client/broker regression |
| NEW-2 (08-12) | scalper `budget_inr` ₹25k | **SHIPPED-VERIFIED (#1075)** | SENSEX leg fundable only under ₹25k funded; NIFTY sizes 1 lot via E8 ×0.85 grading (§6.2); 24 disabled-scalper drafts = standing residue |
| NEW-4 (08-13) | post-close deploy log snapshot | **PROPOSED (process, owner) — carried; complied with this run** | logs snapshotted pre-analysis; all log checks ran on live evidence |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip (red day); no new evidence |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | grep 0 on 3 funded entries — 3rd consecutive funded-day evaluability PASS (§6.3); N23-A stands |
| T29/G11 | scalper `time_stop` | **CLOSED (owner KEEP) — 2nd chop-day observation landed PRO-STOP** | chop day (eff 0.065): 2 of 3 multi-exit clusters stop-favouring, funded exits matched/beat shadow on losers (§5). Cluster series 8 stop / 2 hold |
| T30 | `breadth` dot `>32` | **OPEN** | 4th session dead-CE-side (adv max 20); PE re-saturated 423/423 — two-sided step function again |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | floor ~p70 banded, zero flat; challenger-only class lost again — 8th loosening loss |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (15th) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (19th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs, both explained (boot-fresh open + stall-recovery bucket); 0 straddles |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5 only-class −₹2,111.46 (8th loss) |
| T7 | composite threshold | **REJECTED — carried** | composite-055 only-class −₹2,111.46 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | clean: today's deploys were both PRE-open |
| NEW (08-03) | minervini republish | **PROPOSED — carried** | 10th session (§6.4) |
| T10 | stale OPEN paper positions | **OWNER — chronic, IMPROVED** | **18 → 15 OPEN** (3 TRAILING_STOP closes today, §6.7) |
| T8/T26 | latency | OPEN (data) | shadow p50 1:17.7 / p95 1:38.7 (n=52); emit 18.7–22.8 s — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 1,189/1,189 |

## 8 Honesty caveats

- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book
  (per-strategy `max_bars` + trailing) are different exit models; today they agreed in
  direction on the morning PE legs and the funded exits cut earlier and lost less.
- The §5 challenger league positives and the §4.2 60m-bias-veto "cost" are both carried
  ~95% by the single 12:21 bar — §3.24 fan-out inflation applies; effective independent
  sample for the afternoon CE story is ~4 bars.
- The §6.2 NIFTY sizing trace is code-derived arithmetic; the OI-gap imbalance operand
  (<50) is inferred, not read from a persisted row — labeled [computed/inferred].
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.065 = **chop**; the official
  bar agrees (0.038). CAS delta today was the smallest yet (+11.15, 15:28 print).
- Read-only run: SELECTs, in-container health GETs, log greps on a pre-snapshotted copy.
  No restarts, deploys, writes, config changes, or republishes. Docs-only PR: this file +
  rollup rows + README §3.27 amendment.
