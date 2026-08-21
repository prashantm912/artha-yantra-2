# Session findings — 2026-08-20 (data date)

Analysis date: 2026-08-20 EOD (scheduled post-market agent, run ~15:55–16:20 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,176** (bounds
`2026-08-20T09:15:00+05:30`…`15:40`; rows 09:19–14:55), signals fired **6 ENTRY + 4 EXIT**,
paper trades **4 opened / 4 closed, net +₹3,582.29 — the funded book's BEST day on record**,
shadow closes **32** (champion 16 + composite-055 2 + vol-12k5 6 + vol-off 8).

Session character: **Thursday, BSE WEEKLY expiry (SENSEX 08-20)** · VIX 10.57–10.99 (low) ·
**CHOP** (continuous o 24,225.45 → freeze 24,211.60 = −0.06% on a 0.33% range, efficiency
**0.176**; official CAS bar reads eff 0.079 — **chop on BOTH reads, no straddle**. CAS delta
**+20.25**: 15:29 → 24,231.85) · signal contract `NFO:NIFTY26AUGFUT` (log-confirmed, 1,199
mentions) · **ONE Kite connectivity outage ~11:28–12:24:23 IST** (WS churn from 11:28, kite-rest
circuit open 11:44; watchdog restarts 12:14/12:24; reconnected 12:24:23 — self-healed, §6.1) ·
⚠️ **both services recreated 15:58:55 IST by a concurrent post-close deploy** — this run's log
greps all completed BEFORE the recreate except the boot-line grep (DB `engine_reloads` fallback
used); NEW-4 snapshots exist at the conventional path (15:19 IST stamps).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,176 — **34 of 38 scalpers** (silent: `scalp-golden-crossover-nifty-pe`, `scalp-golden-crossover-sensex-niftyoi-pe`, `scalp-hero-zero-nifty`, `scalp-hero-zero-sensex-niftyoi` — the PE golden-crossover pair silent on an all-CE day, the exact mirror of 08-18's silent CE pair on an all-PE day; hero-zero pair silent again, so `premium_skew` has 0 rows today) |
| eval outcomes | chart-gate-failed 1,978 · confluence-blocked 1,176 · composite-below-threshold 226 · **fired 10** · discipline-paused 0 |
| fired reconciliation (§3.36) | 10 fired evals = **6 ENTRY emitted + 4 risk-gate-suppressed** (+ 4 EXIT = 10 signal rows). ⚠️ **`daily_profit_target` TRIPPED 13:25:17 IST — 2nd trip ever (first 08-12), and the first on a realized-green book**: pos 89 banked +₹4,006.00 at 13:13, day realized +₹3,582.29 > the ₹2,250 target. Suppressed: 13:25:18 ×2 + 13:28:19 ×2 (`scalp-golden-crossover-nifty` + `scalp-connect-the-dots-nifty`, both NIFTY). Counterfactual of the suppressed legs: **all four WOULD-LOSE** (§5) — the pause protected the green day |
| coverage | 23 of 25 15-min buckets 09:15–14:55 (15:00/15:15 empty = window class; eval buckets write zeros to close). 12:15 thin (n=32) = the Kite-outage tail; 11:45/12:00 held 80/60 rows (engine kept evaluating on cached/backfilled bars until bars stopped closing briefly) |
| boot health | boot 08:18:32 IST install 0/38-unresolved transient → **38/0/0 at 08:21:15 (~163 s — above the normal 71–132 s band, 3rd consecutive above-band day: 139 s / 105 s / 163 s — watch)**. Mid-session reconciles 08:40:36 + 12:19:53 both clean 38/0/0 single rows (no §3.38 slow-reload cadence; the 12:19 one fell inside the outage window and stayed sub-second). `subscriber_health_events` 0 rows |
| paper (funded) | pos 86 `NIFTY26AUG24100CE` 65 @208.45 (10:13, sub-1) → **TIME_STOP 10:43 −₹341.76** · pos 87 same leg 65 @208.05 (10:52, sub-2) → **STRUCTURAL_STOP 10:58 −₹91.81** · pos 88 same leg **130** @213.15 (11:34, sub-3, 2-signal pyramid) → **TIME_STOP 12:04 +₹9.86** · pos 89 same leg **130** @222.25 (12:43, sub-4, 2-signal pyramid) → **TIME_STOP 13:13 +₹4,006.00**. Net **+₹3,582.29 — best funded day on record** (prior best +₹3,116.14 on 08-12, the other profit-target-trip day: both green days are the two trip days). Entry emit latency avg **~19.1 s** (6 entries, 17.5–20.7 s — G8/T26 class); exits 17.4–20.5 s |

## 2 Rail findings

- **§2.2 chain-proximity: BSE-weekly day-of SATURATED — `strike-pick` 511 fails, ALL
  sensex-rooted (16/16 slugs), 0 nifty-rooted.** The BSE Thursday day-of is now **2 saturations
  vs 1 zero** (08-13 336-SX / 08-06 0 / **08-20 511-SX**). Series: 08-13 336-SX / 08-14 14-SX /
  08-17 361-NF / 08-18 425-NF / 08-19 0 (Wed control) / **08-20 511-SX**. Consequence: **zero
  SENSEX entries all day** — every fired leg was NIFTY. Next cluster window: Fri 08-21
  (post-BSE-expiry Friday, 3-of-4 historically saturated).
- **volume-floor banded and honest; binding share 61.5%** (723/1,176): banded thresholds vs
  aligned 3m AUGFUT volume — floor ≈ p75 class, zero flat (§3.14: `relative-volume-floor` armed
  38/38, stamps unchanged 07-28 ×2 + 08-13 ×36).
- First-block tail: time-window 174 · time-of-day-preference 38 · rsi-band 32 ·
  divergence-vol-gate 30 · pct-price-move / volume-pump / two-candle 28 each · strike-pick 23 ·
  oi-cross-required 22 · confluence-composite 19 · directional-change-gate 14 · psar-durability
  6 · oi-slope-agree 4 · rsi-5m-cap / oi-divergence-magnitude / option-side-constraint 2 each ·
  max-oi-sr-gate 1 (18 distinct rails).

## 3 Composite + dots

- Distribution (scored n=960): pass mass **454/960 (47.3%) — second-highest recorded (08-17:
  43.5%), and 454/454 CE, 0 PE** — the exact inversion of 08-18's 104/104 PE. Max rejected
  0.5882. Fired composites 1.0000 (10:12!) / 0.7160 / 0.8333 / 0.8787.
- Dot support (complete session, n=960 unless noted): `iv_abs_band` **0% (n=125 — flipped from
  100% yesterday: the frozen daily stamp landed OUTSIDE the 10–12 band today; the per-day
  coin-flip class, 19th session)** · `iv_pair` **0% (23rd session — T3, owner)** · `iv_rank` 0%
  (withheld, standing) · oi_spurt 6.6% · volume 24.7% · iv_slope 25.6% (n=125) · vwap 26.9% ·
  trending_cross 43.3% · underlying_oi 48.1% · sentiment_slope 49.7% · futures_oi 54.1% ·
  sentiment 64.6% · psar 72.2% · rsi 76.6% · vwma 86.4% · vix / breadth / basis 98.5% ·
  drastic_oi 99.2% · **supertrend 100%** · premium_skew — (0 rows, hero-zero silent).
- **§3.28 breadth side-split — the dead-CE streak BROKE at 7 sessions: CE is the ALIVE side
  today**: CE advances 35–44 vs `>32` → 946 rows at 98.5%; PE n=14 only (advances 39, declines
  11 → the PE side is the dead one today). Side-aware step-function behaviour exactly as
  documented — on a CE day the dot is a free +1.0 for CE.
- OI bloc fully live on BOTH roots (BSE weekly ≠ S24 suppression, §3.19): quadrants NEUTRAL
  **0/960** contextful (SHORT_COVERING 400 / LONG_UNWINDING 345 / LONG_BUILDUP 133 /
  SHORT_BUILDUP 82; 216 NULLs = context-less pre-fetch class); `futures_oi_snapshots` 25,185
  snaps / **365 of ~375 minutes**, chain 364 minutes — the ~10 missing = the outage window.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,176/1,176 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,176/1,176 | by design (un-armed) |
| `fiiLongPct` | NULL 216 = exactly the context-less rows | daily EOD stamp, alive |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 19th) |
| vix | 26 distinct, 10.57–10.99 | alive |
| ceIvAvg6 / skew / basis | 43 / 83 / 80 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 3rd consecutive outage-backfill session with no phantom rows |
| §3.17 canary | **6 WARNs + 3 straddles** (§6.2): 09:19/09:25/09:31 = boot-fresh opening class (−6,435 / −1,560 / +910) · 11:52 (+4,810) + 11:55 (+8,580) = inside the Kite-outage degraded window · **12:28 bucket-12:24 = RECONNECT-INFLATION, 2nd observation, BOTH discriminators confirmed** (reconnect 12:24:23 in-bucket; DB 1m sum 10,465+2,795+3,445 = 16,705 = the canary's 3m value exactly — inflation in the in-memory mirror only, rails untouched) | all 6 explained-class; NEW-6 still needs a no-outage day |
| Kite session | validated 15:58 IST | ✓ |
| market-data canary | GREEN, 0 problems (15:59 read, post-recreate) | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 16 closes, 9W, +176.55 pts, +₹10,299.48 NET → 11 deduped (bar, leg) events —
the book's best net day on record; all-time −₹219,135.93 → −₹208,836.45** (points −2,731.65 →
−2,555.10). Every leg was `NIFTY26AUG24100CE`/`24150CE` (the strikes the composite converged on):

- 09:21 @197.50 → **TAKE_PROFIT +₹4,586.08** (2nd shadow TP in two days — the T21 +35% bracket
  paying again).
- **09:45 ×6 multi-exit cluster (§3.24 — G11's 7th observation, and the first HOLD-favouring
  cluster since 08-14's 12:21 winner)**: 5 slugs held to SQUARE_OFF **+20.95 pts (+₹1,287.58
  each)** while `scalp-market-movers-nifty`'s STRUCTURAL_STOP cut at 09:53 for **−9.35 pts
  (−₹679.13)** — the stop cut a would-be winner. Cluster series: **11 stop / 3 hold / 1 TP.**
- 09:57 / 10:12 SQUARE_OFF +₹1,277.83 / +₹1,219.28 · 10:15 / 10:24 / 11:00 / 12:06
  STRUCTURAL_STOP −₹261 to −₹439 · 12:30 STRUCTURAL_STOP +₹21.29 · 13:15 / 13:21 (24150CE)
  STRUCTURAL_STOP −₹368.36 / −₹731.66.

**Challengers — the only-class prior BROKE: composite-055 posted the FIRST winning loosening
observation.** 4 challenger-only rows today: `composite-055` 10:51 24100CE **+20.30 pts
+₹1,245.29 WIN** + 13:18 24150CE −₹10.58 (day +₹1,234.71 → all-time −₹4,591.02) — **11th
measured loosening, first win (n=1 row carried it — §3.24 caveat applies)**; `vol-12k5` 13:15
24150CE −₹1,686.00 (12th loosening, 11th loss → −₹44,983.14); `vol-off` same leg −₹1,686.00
(13th loosening, 12th loss → −₹60,621.33). **Prior updated: 13 measured loosenings, 12 losses,
1 win — still overwhelmingly negative; no tuning row.** `dot-null-withheld` 0 rows ever.
Shadow entry latency p50 1:20.2 / p95 1:22.6 (n=32, structural class).

**§4.2 counterfactuals** (§3.16 horizon caveat applies; 3-min LTP granularity):
- **The §3.36 risk-gate-suppressed class (4 legs, 13:25/13:28)**: picker-consistent leg =
  `NIFTY26AUG24150CE` (pinned by the 13:15/13:21 shadow rows @213.80/212.35). Entry ~215.65–217.00
  → 30-min model exits ~204.00–206.70 (13:55/13:58): **−10 to −12 pts each, ALL FOUR WOULD-LOSE
  (~−₹1,400–2,800 total at 65/leg)** — the `daily_profit_target` pause avoided losing re-entries
  and locked the book's best day. The governor did exactly what it is for.
- **G11 chop-day stop-vs-hold on the funded legs (first funded-book green sample)**: hold-all-to-
  15:12 model (24100CE @225.00 at 15:12) ≈ **+₹4,079 gross** vs the armed stops' **+₹3,582
  realized — a near-tie with huge per-leg variance in BOTH directions**: pos 86/87 stops cut
  would-be winners (−₹433 realized vs ~+₹2,180 hold) while pos 89's TIME_STOP banked the top
  (+₹4,006 vs ~+₹357 hold — the leg peaked ~258 at 13:26 and faded to 225). G11's KEEP decision
  (07-31) unchallenged; today neither confirms nor reverses.
- Sole-blocker rails (composite passed, fails=1): strike-pick 23 (the SENSEX expiry class —
  unpriceable, no leg resolved) · volume-floor 19/12 bars · two-candle 13 · volume-pump 13 ·
  pct-price-move 10 · rsi-band 1 · oi-divergence-magnitude 1. The volume-floor class was
  largely the same 24100CE bar-family the shadow book already traded (won small / lost small);
  the vetoed set on a chop day was again mostly noise — no tuning row filed.

## 6 New data points / anomalies

### 6.1 Fourth consecutive session with a Kite connectivity outage — same environmental class

WS ticker churn from 11:28:27 IST, kite-rest circuit open 11:44:22, watchdog restarts at
12:14:22 (tick 239 s) and 12:24:22 (tick 839 s), **reconnected 12:24:23 — ~56 min degraded**.
Effects bounded and healed: rejections 12:15 bucket thin (n=32); futures-OI/chain each lost ~10
minutes; signal-future candles repaired to **375/375** (KITE 347 + BACKFILL 14 + TICK_AGG 14),
0 misaligned rows; funded path untouched (nearest entry 12:43, post-recovery). Same host-network
class as 08-19's triple (register row appended to memory `stack-outage-register` covers the
series; H26 composite-fallback item is the build answer). Nothing to fix in-repo.

### 6.2 §3.17 — reconnect-inflation class CONFIRMED on its 2nd observation

The 12:28 WARN (bucket 12:24: in-memory 1m sum 96,070 vs broker 3m 16,705) carries both §3.17
discriminators: (a) reconnect 12:24:23 inside the bucket, (b) DB 1m bars sum EXACTLY to the
canary's 3m value. Inflation lives only in the in-memory mirror; rails read the broker-corrected
rollup. The 08-19 amendment's class is now 2-for-2. **NEW-6 (unpaired WARNs on a NO-outage day)
remains untested — today was again an outage day**; the 11:52/11:55 WARNs sit inside the
degraded window (outage-adjacent class), the opening trio is the boot-fresh cold-cache class.

### 6.3 §3.29 audit — no change to the never-fired set

`close_reason` deltas since the 08-19 run: TIME_STOP 14→17 (pos 86/88/89) · STRUCTURAL_STOP
14→15 (pos 87) · TRAILING_STOP 19→20 (a swing-book close in the 08-19 evening batch, post-run).
Never-fired unchanged: `take_profit premium_pct` (36 armed — the shadow book's two TPs in two
days sharpen the contrast: the +35% bracket pays in the shadow exit model but has still never
fired on a funded position) · `signal_exit` (38) · `square_off` (2) · tag `oi-confluence-exit`
(8). INDETERMINATE: `trailing_stop atr_multiple` (2) only (`stop_loss atr_multiple` resolved
08-18).

### 6.4 §3.34 heat-gate evaluability — PASS (7th consecutive funded-day)

Log grep = **0** across 4 funded entries (ran pre-recreate); all four rows carry
`margin_snapshot 0.00 / margin_pct 0.00`. Evaluability only — N23-A (zero-SPAN long-option
coverage) stands.

### 6.5 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines — the identical standing set to
  08-17/08-18/08-19** (keyword-class false positives; no new line). Ledger consistent.
  ⚠️ Run from the working tree parked on `docs/ledger-2026-08-20-close` (2 unpushed ledger
  commits, no PR) — i.e. checked against a ledger NEWER than origin/main, not older.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

### 6.6 §3.30 freeze telemetry — flag technically 3-of-5, but two mechanisms superseded it

Entries: sub-1 ×1 (10:13) · sub-2 ×1 (10:52) · sub-3 ×2 (11:34 pyramid) · sub-4 ×2 (12:43
pyramid) · sub-5 ×0. Day PnL: sub-1 −₹341.76 (first-loss frozen ~10:43) · sub-2 −₹91.81
(first-loss frozen ~10:58) · sub-3 +₹9.86 (win below profit-lock — stayed AVAILABLE) · sub-4
**+₹4,006.00 (profit-lock frozen ~13:13 — the design banking a win)** · sub-5 never entered.
**3 of 5 stopped before 14:30 (2 first-loss + 1 profit-lock)** — but the binding constraint from
13:25 was the BOOK-level `daily_profit_target` pause (§1), which suppressed all 4 later fires,
and their counterfactuals all LOSE (§5). Trend: 08-14 2/5 · 08-17 3/5 · 08-18 2/5 · 08-19 2/5 ·
**08-20 3/5 — governors + risk gate blocked 4 entries at counterfactual PROFIT (≈+₹1,400–2,800
saved), the first session the freeze layer measurably added money.**

### 6.7 The funded day in one line

Four entries, one strike family (24100CE), pyramiding doubling size into the winning
afternoon leg; the 12:43 double-lot entry caught the 13:00–13:13 pop and the TIME_STOP banked
it at the top (+₹4,006 — exit ~253, leg peaked 258.40 at 13:26, faded to 225 by 15:12); the
profit target then correctly refused four losing re-entries. Best funded day on record, and
the first where every protection layer (structural stop, time stop, profit-lock freeze, daily
target) either cut a small loss or locked a gain.

### 6.8 Post-close deploy during the analysis run

Both services recreated 15:58:55–15:58:56 IST (image built 15:33) by a concurrent session
while this run was mid-pass. All log-derived checks (§3.17 grep, §3.36 suppression lines,
contract confirm, heat grep, outage timeline) completed minutes BEFORE the recreate; the boot
line grep ran after and came back empty — `strategy.engine_reloads` (§3.37 DB fallback) used
instead. NEW-4 snapshots exist at `/c/Trading/ArthaYantra/log-snapshots/2026-08-20/` (15:19 IST,
both services) — the conventional-path discipline held.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-6 (08-19) | unpaired mid-session §3.17 WARNs w/o reconnect | **WATCH — untestable today (outage day again)** | all 6 WARNs explained-class (§6.2); needs a clean no-outage session |
| watch | `strike-pick` chain-proximity | **WATCH — BSE day-of SATURATED 511-SX (2-of-3 now)** | §2; next window Fri 08-21 (post-expiry Friday, 3-of-4 historical) |
| NEW-5 (08-14) | Redis candles.1m subscription drop | **WATCH — no recurrence (day 5 of 5, window closes 08-21)** | 0 stall rows; engine idled only during the genuine feed outage |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no mid-session deploy (the 15:58 recreate was post-close); §3.38 unchallenged |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried; 2nd trip, STRONGLY favourable** | tripped 13:25 on the best day ever; all 4 suppressed fires WOULD-LOSE (§5) — two green days = the two trip days |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | grep 0 on 4 funded — 7th consecutive PASS (§6.4); N23-A stands |
| T30 | `breadth` dot `>32` | **OPEN** | dead-CE streak broke at 7 — CE alive 98.5% (adv 35–44), PE side dead (n=14); side-saturation shape intact |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | banded ≈ p75, zero flat; binding 61.5%; loosening prior now 13 obs / 12 losses / 1 win (§5) |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (19th); `iv_abs_band` flipped 100%→0% on the day's stamp — the coin-flip made visible |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (23rd session) |
| T23 | partial-bucket tolerance | **OPEN** | 6 WARNs 3 straddles, all explained; reconnect-inflation 2-for-2 on discriminators (§6.2) |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5 only-class −₹1,686 (12th loosening, 11th loss) |
| T7 | composite threshold | **REJECTED — carried; ⚠️ first counter-observation** | composite-055 only-class **+₹1,234.71 — first winning loosening in 11 observations** (one row carried it); REJECTED stands, watch for a second |
| T10 | stale OPEN paper positions | **OWNER — chronic** | population 17 OPEN (−1: a swing trailing-stop close), oldest 07-08 (as of ~16:00, pre-evening batch) |
| T8/T26 | latency | OPEN (data) | shadow p50 1:20.2 / p95 1:22.6 (n=32); entry emit avg ~19.1 s — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 1,176/1,176 |

## 8 Honesty caveats

- **This run executed ~15:55–16:20 IST** — before the 16:00 swing batch completed and the 18:4x
  screen/EOD jobs; T10 and ingest statements are as-of-run-time.
- **Both containers recreated 15:58:55 IST mid-run** — every log-derived claim above was grepped
  before the recreate except the boot line (DB fallback used, §6.8). The 15:19-IST NEW-4
  snapshots cover the session's first 6 hours as an independent record.
- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book (per-strategy
  `max_bars` + stops) are different exit models; today they AGREED in sign (both green) but the
  hold-vs-stop split was leg-dependent (§5 G11 note).
- The hold-to-15:12 counterfactual uses 3-min chain LTPs, no slippage/fees on the hold side;
  the suppressed-leg counterfactual approximates the picker (±1 strike risk).
- The composite-055 "first win" is one row (+₹1,245.29) — §3.24 fan-out/small-n caveats apply
  in full; it changes the tally, not the verdict.
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.176 = **chop**; official reads
  0.079 = chop — both agree, no straddle. CAS delta +20.25 (symmetric-noise series holds).
- Read-only run: SELECTs, in-container health GETs, log greps. No restarts, deploys, writes,
  config changes, or republishes. Docs-only PR: this file + rollup rows.
