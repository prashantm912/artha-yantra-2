# Session findings — 2026-08-27 (data date)

Analysis date: 2026-08-27 EOD (scheduled post-market agent, run ~15:45–16:25 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,420** (bounds
`2026-08-27T09:15:00+05:30`…`15:40`; rows 09:19–15:19), signals fired **4 evals = 3 emitted + 1
risk-suppressed**, paper trades **2 entries / 2 closes (−₹4,155.14)**, shadow closes **41** across
4 variants (champion 29).

Session character: **Thursday, BSE MONTHLY expiry (SENSEX26AUG expired today)** · trend-down day
(official o 24,277.60 → c 24,090.85 = −0.77% on a 0.85% range, eff **0.904 = trend**; continuous
freeze 24,133.05 = −0.60% on a 0.73% continuous range [24,120.80–24,296.90], continuous eff
**0.821 = trend** — **both stamps agree, NO straddle** — first aligned session since the straddle
series began; CAS delta **−42.20**, 2nd consecutive negative, and **the CAS print IS the daily low
again** — 3rd time, 2nd consecutive) · signal contract **`NFO:NIFTY26SEPFUT`** (1,444 log
mentions) · **overnight HOST downtime again (benign, stack-outage-register class):** all
containers started **08:40:03 IST** simultaneously, `RestartCount=0`; morning batch ran as the
boot catch-up 08:40:22 — all SUCCESS; zero in-session outage lines, **3rd consecutive clean
in-session day**.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,420 — **36 of 38** scalpers (silent: the **open-high-low CE pair** — `scalp-open-high-low-nifty`, `scalp-open-high-low-sensex-niftyoi` — chart-gate class on a down tape, the same mirror-of-up-day mechanic as 08-26's golden-crossover CE pair); `premium_skew` n=34 |
| eval outcomes | chart-gate-failed 2,000 · confluence-blocked 1,420 · composite-below-threshold 128 · **fired 4** · discipline-paused 0 |
| fired reconciliation (§3.36) | **4 fired = 3 emitted + 1 suppressed** — `daily_loss_limit` tripped 14:07:16 IST (first live LOSS-limit trip ever; 08-12 was the profit-target). Suppressed: `scalp-golden-crossover-nifty-pe` NFO:NIFTY26SEPFUT 14:07 |
| coverage | **25 of 25** 15-min buckets 09:15–15:15 populated — no interior holes; `subscriber_health_events` 0 rows |
| boot health | boot 08:40:29 IST (post host-downtime), 0/38-unresolved transient → **38/0/0 at 08:42:10 (~100 s — in-band)**; boot line `loaded 38 published strategies (0 dropped, 0 failed)` |
| paper (funded) | **2 entries, 2 closes, −₹4,155.14** — first funded fires since 08-24. Both STRUCTURAL_STOP (§5) |

## 2 Rail findings

- **volume-floor first-block 890/1,420 (62.7%)** — banded and honest; §3.14 tag check:
  `relative-volume-floor` armed on 38/38 enabled.
- **`strike-pick` BSE-MONTHLY cluster landed exactly as predicted (§3.27): 377 all-fails on
  15 of 15 SENSEX-rooted slugs, ZERO NIFTY-rooted.** Series: NSE Mon/Tue 235/604/322/452/531 →
  Wed 0 → **Thu (BSE monthly) 377 SENSEX**. Next watch: tomorrow Friday — post-BSE-expiry Friday
  is 3-of-4 saturated (550/374/350 vs 14 clean on 08-14); the fresh SENSEX front weekly's pricing
  vs the static 300–800 band decides it, not the calendar.
- First-block tail: time-window 214 · rsi-band 75 · time-of-day-preference 38 ·
  **chain-unavailable 30** (expiry-day SENSEX chain, first material count in weeks) ·
  pct-price-move 26 · volume-pump 26 · two-candle 26 · divergence-vol-gate 24 ·
  oi-cross-required 20 · confluence-composite 18 · option-side-constraint 12 · max-oi-sr-gate 10 ·
  strike-pick 5 · hero-zero 4 · psar-durability 2 (16 distinct rails).
- **confluence-composite first-blocks are BOTH classes today (§3.39):** 586 all-fails split into
  **`60m bias opposes the side` (composite 0.2451–0.7181)** and 38 distinct
  score-shortfall aggregates. Sole-blocker veto set: §6.1.

## 3 Composite + dots

- **OI bloc fully LIVE** — the expiring root is BSE but every enabled slug reads NIFTY OI (§3.19
  root rule): quadrants NEUTRAL **0/1,126**, spurt NULL 0/1,126, basis LIVE 1,126/1,126. Chain +
  futures capture healthy underneath (options snaps: NIFTY 50 226,780 / SENSEX 373,726;
  futures_oi 24,564 snaps but **356 of ~375 minutes — ~20 missing minutes, ALL ≤11:38, post-boot
  warm-up + scattered early-session gaps** — did not surface in quadrants; watch once).
- **Composite passes 322 of 1,126 scored (28.6%) — 20 CE / 302 PE** (down-day PE dominance);
  max 0.7979. `composite-below-threshold` evals 128.
- Live-dot support (complete session, n=1,126 unless noted): `iv_abs_band` 0% (n=163, **6th
  day** — atmIv stamp **0.093143**, below the 10–12 band) · `iv_rank` 0% (withheld, standing) ·
  `iv_pair` 0% (**28th** — T3, owner) · **`breadth` 0%** (see T30 below) · oi_spurt 13.1% ·
  volume 21.0% · basis 21.0% · trending_cross 30.6% · vwap 39.3% · sentiment_slope 47.0% ·
  futures_oi 51.4% · iv_slope 58.3% (n=163) · underlying_oi 62.7% · psar 67.3% · rsi 71.2% ·
  sentiment 74.1% · vix 79.0% · vwma 90.1% · drastic_oi 90.4% · premium_skew 94.1% (n=34) ·
  supertrend 97.2%.
- **§3.28 breadth (T30) — back to the step-function shape after 4 mid-range days:** CE dead
  (advances 20–24, 0/236) AND PE dead (**declines 19–32, max EXACTLY 32 vs the strictly-greater
  `>32` rule, 0/890**) — the third session where the operand's extremum lands exactly on the
  threshold boundary (07-28 and 07-30 were adv=32).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,420/1,420 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,420/1,420 | by design (un-armed) |
| `fiiLongPct` | live on all 1,126 contextful rows (null only on the 294 context-less) | healthy |
| `atmIv` | 1 distinct (0.093143) | frozen daily stamp — correct (G12/T28, 24th) |
| vix / ceIvAvg6 / skew / basis | 19 / 55 / 105 / 99 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 8th consecutive |
| §3.17 canary | **2 WARNs, 0 straddles** — ±11,310 (174 NIFTY lots) on consecutive buckets, WARNs 10:16:48 / 10:19:48 IST, BOTH logged UNPAIRED: the day's-first-non-benign-event lot-cache-miss class (08-25 §6.1 mechanism; boot 08:40, first non-benign event 10:16 — lazy cache). Benign-by-shape (± lot-multiple pair, consecutive buckets, no earlier WARN/straddle). No reconnect lines all session | benign — explained |
| signal-future capture | **375/375 min** aligned 1m on `NIFTY26SEPFUT` | ✓ |
| futures_oi capture | 24,564 snaps / 356 of ~375 min — ~20 missing minutes all ≤11:38 (post-boot warm-up class) | minor — watch |
| morning ingest | boot catch-up 08:40:22: BHAVCOPY / NSE_FII_DII / NSE_PARTICIPANT_OI / NSE_FII_DERIVATIVE all SUCCESS; INSTRUMENT_SYNC 09:05; OPTIONS_SNAPSHOT_CAPTURE 09:20 | ✓ (host-downtime boot-catch-up class, 2nd consecutive day) |

## 5 Funded book + shadow outcomes + counterfactuals

### 5.1 Funded fires (first since 08-24)

| pos | slug (signal) | leg | entry | exit | reason | net |
|---|---|---|---|---|---|---|
| 96 | scalp-golden-crossover-nifty-pe (#261, 11:21 bar) | NIFTY2690124400PE ×65 | 237.90 (11:22:21) | 11:37:18 | STRUCTURAL_STOP | −₹536.81 |
| 97 | scalp-golden-crossover-sensex-niftyoi-pe (#262, 11:24 bar) | SENSEX26AUG77500PE ×80 | 311.50 (11:25:20) | 11:34:19 | STRUCTURAL_STOP | −₹3,618.33 |

- **Signal #263 (`scalp-connect-the-dots-sensex-niftyoi-pe`, same 11:24 bar, same leg) was
  REFUSED by the `sub_account_allocation` governor** — "projected 24920.00 would cross
  sub-account 2 allocation 30000" — the first live observation of the #1086 governor blocking a
  funded convergence entry (the G14 convergence-bound mechanism working; without it #263 would
  have averaged into pos 97 and doubled the −₹3,618 loss).
- Entries were PE longs bought INTO the 10:54–11:24 midday bounce; both structural stops fired
  on the bounce's continuation before the afternoon leg down.
- **§3.34 heat-gate:** grep 0 (`heat call failed|heat unassessable`) — the margin call succeeded
  on both entries; `margin_snapshot` 0.00/0.00 = the long-option zero-SPAN class, so the 60% cap
  bound nothing (N23-A standing owner question, coverage ≠ evaluability).
- **§3.36 risk-gate:** `daily_loss_limit` (3% **of current equity** — limit ₹4,013.98 = 3% ×
  ₹133,799 book equity, NOT 3% × the ₹150k allocation) tripped at 14:07:16 on dayPnl −₹4,155.14;
  one suppression (the 14:07 golden-crossover-nifty-pe fire). Counterfactual for the suppressed
  leg: composite-055's real fill of the adjacent 14:09 NIFTY2690124400PE @232.80 closed
  STRUCTURAL_STOP **−₹588.10** — the suppression likely avoided a ~₹500 loss.
- **Emit latency measured on real emissions (T8/T26 first data since G8):** entry signals
  19.9–21.3 s, exit signals 17.7–19.9 s bar-close→emit.
- **Exit-vocabulary note:** pos 97 carried BOTH sensex slugs' intent but one row; signal #266
  (connect-the-dots TIME_STOP exit, 11:54) landed on an already-closed position — the documented
  pyramiding/averaging row-sharing mechanic, first seen live on a funded convergence pair.

### 5.2 Shadow book

**Champion: 29 closes, 14 net wins, +219.25 pts, +₹1,418.27** — but concentration rules it
(§3.24): 7 deduped `(bar, leg, entry)` clusters on 6 bar times; the 10:06
`SENSEX26AUG77700PE` **TAKE_PROFIT cluster (+₹15,202.46, 7 slugs)** carries everything —
**ex-cluster the day is −₹13,784.19**. Worst: 11:15 SENSEX26AUG77600PE @338.85 (7 slugs,
SQUARE_OFF/STOP_LOSS/STRUCTURAL_STOP multi-exit) **−₹10,509.28**; 12:42 NIFTY2690124050CE
(5 slugs, counter-trend CE) −₹5,907.01. All-time champion **−₹217,414.94** (705 closes, 259 net
wins). Shadow entry latency p50 79 s / p95 82 s (n=41, structural class, unchanged).

**Challenger-only class: 4 observations, 0 wins / 4 losses, −₹4,745.39 — ALL composite-055**
(10:45 NIFTY24400PE −₹1,267.88 STRUCTURAL_STOP; 10:45 SENSEX77600PE −₹1,733.97 STOP_LOSS;
12:42 NIFTY24050CE −₹1,155.44 SQUARE_OFF; 14:09 NIFTY24400PE −₹588.10 STRUCTURAL_STOP).
**Loosening ledger moves 26/21/5 → 30 measured / 25 losses / 5 wins.** All-time: composite-055
**−₹8,151.40** (the "≈break-even" read of 08-25 is now decisively negative) · vol-12k5
−₹39,036.12 · vol-off −₹57,981.88.

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor
398 / **−₹134,556.64** (today **+₹8,541.60 on 16 closes** — the TP cluster sits in this bucket;
tail-driven, do not read as the floor blocking winners) · rsi-band 92 / −₹48,980.82 (0 today) ·
`confluence-composite` 16 / **+₹8,085.07** (today −₹755.38; still the only materially positive
rail — concentration: its top-3 contributors are TP-cluster trades; and see §6.1 for why the
bucket must not judge the veto, §3.13) · pct-price-move 40 / +₹3,744.65 · supertrend-15m 7 /
+₹3,643.32 · oi-cross-required 22 / +₹2,145.06. **Root split FLIPPED AGAIN — SENSEX all-time
now POSITIVE: +₹3.98/trade (312) vs NIFTY −₹556.38 (393)** — today's SENSEX closes (TP cluster)
added ≈+₹3,940. Third flip in four measured days: the root split is not stable enough to act on.

### 5.3 §4.2 manual counterfactuals — the 60m-bias-vetoed set (day 2)

Sole-blocker veto set: 6 rows → **4 deduped legs** (10:45 + 10:54, all PE). Model: +35%/−25%
premium brackets, 30-min time stop (HARNESS modelling choice, §3.16), 15:12 square-off.

| bar | leg | entry | model exit | pts | model | real-fill corroboration |
|---|---|---|---|---|---|---|
| 10:45 | NIFTY2690124400PE | 218.30 | ~224 time-stop 11:15 | +5.7 | WIN | composite-055 real fill: STRUCTURAL_STOP **−₹1,267.88 LOSS** |
| 10:45 | SENSEX26AUG77600PE | 300.00 | ~333.65 time-stop 11:15 | +33.65 | WIN | champion STRUCTURAL_STOP −₹755.38; composite-055 STOP_LOSS −₹1,733.97 — **LOSS** |
| 10:54 | NIFTY2690124400PE | 225.65 | 244.60 time-stop 11:24 | +18.95 | WIN | none |
| 10:54 | SENSEX26AUG77600PE | 322.85 | 395.95 time-stop 11:24 | +73.10 | WIN | none |

**The two instruments DISAGREE IN SIGN on the same legs:** the 30-min-hold harness model says the
veto refused 4 winners (+131 pts gross); the engine-replicating books (structural stop + premium
brackets live) LOST on all 3 corroborated fills — the structural stop fired on the bounce before
the premium recovered. This is the §3.16 model-divergence class, now inside a single day's veto
set. **Veto ledger after 2 days: 08-26 refused 8/8 losers (both models agree); 08-27 refused 4
legs that lose under engine exits and win under a pure 30-min hold.** n=2; keep accumulating,
no conclusion — but note the veto's value estimate is now MODEL-DEPENDENT, so quote both.

## 6 New data points / anomalies

### 6.1 First live `daily_loss_limit` trip — and the limit base is CURRENT EQUITY

`risk_audit` id 88: `day P&L -4155.1400 breached limit 4013.9835` at 14:07:16 IST. The knob is
`{"mode":"pct","value":3}` and `limitInr` resolves the pct against the book's CURRENT equity
(₹133,799 after cumulative losses), not the ₹150k allocation — so the effective loss limit
RATCHETS DOWN as the book loses (3% of a shrinking base). Observed, not judged: on a ₹150k-naive
read today's −₹4,155 would NOT have tripped (limit 4,500). §3.36's mechanics generalize: the
suppression left `discipline-paused` at 0 (risk gate is upstream), entry-only, one log line per
suppressed fire. README §3.36 amended.

### 6.2 H31 follow-up — ZERO day-context failures (first clean full session)

`insight trust read day-context FAILED` grep count: **0** over the full session (boot 08:40 →
run time), vs 5/~28 (~18%) on 08-26 and 25/28 (~89%) pre-fix. Trajectory 89% → 18% → 0%.
Denominator caveat: successes do not log on this path; ~28 = the 15-min cron's expected fires.
One clean day after an 18% day — the alternation mechanism (write-based TTL expiry) predicts
variance, so this is a good day, not proof of fix. Chip `task_de01f6bb` ruling stays with the
Architect; this run supplies the second post-fix measurement.

### 6.3 §3.29 audit — funded vocabulary delta: STRUCTURAL_STOP 18 → 20

First funded closes since 08-24. Fired vocabulary since 07-01: TRAILING_STOP 22 ·
**STRUCTURAL_STOP 20 (+2 today)** · TIME_STOP 17 · STOP_LOSS 8 · MANUAL 2. Armed-path table
re-verified — identical 10 rows + tag `oi-confluence-exit` 8. Never-fired unchanged:
`take_profit premium_pct` (36, zero funded TP closes since 07-01 — while the shadow book's TP
count grew again today) · `signal_exit` (38) · `square_off` (2) · tag `oi-confluence-exit` (8).
INDETERMINATE: `trailing_stop atr_multiple` (2), `stop_loss atr_multiple` (2). Note: signal #266
carried exit_reason TIME_STOP but its position was already closed by #262's STRUCTURAL_STOP —
`close_reason` on the POSITION is the §3.29 source of truth (row-sharing, §5.1).

### 6.4 §3.30 freeze telemetry — 2 of 5 frozen by 11:37 (first-loss each)

subaccount 1: 1 entry 11:22, first-loss frozen ~11:37 (−₹536.81). subaccount 2: 1 entry 11:25,
first-loss frozen ~11:34 (−₹3,618.33). Subs 3–5 never entered (no further fires reached
funding; 14:07 fire died at the risk gate first). Trend: 08-20 3/5 · 08-21 0/5-trivial ·
08-24 3/5 by 13:40 · 08-25/08-26 0/5-trivial · **08-27 2/5 by 11:37**.

### 6.5 NEW-8 trail-should-have-fired watch — 4th clean measurement

Boot catch-up pass wrote sell_decisions; morning `pyramid_risk_cap` trips for IOLCP/AEROFLEX
(manas-arora 6% open-risk cap, normal governor). No trail/stop breach anomalies.

### 6.6 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines — the identical standing set of 08-26**
  (7×[A]: task_37ee83e0, 53ce441b, 79092520, a2ae20ed, de01f6bb/H31, f624fca7, fb8914fc; 5×[B]:
  map-return, cross-context, G8, G14, G12 — all previously classified keyword-class false
  positives or deliberate open-notes). No edits made; ledger consistent modulo the standing set.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-9 (08-26) | 60m-bias veto (inside confluence-composite) | **OPEN — day-2 evidence is MODEL-DEPENDENT**: 4 sole-blocker legs lose under engine exits (3/3 real fills lost) but win under a pure 30-min hold (+131 pts). Cumulative: day 1 refused 8/8 losers; day 2 split by model. Keep accumulating; quote both models | §5.3 |
| NEW-10 (08-27) | `daily_loss_limit` base = current equity (ratchets down) | **NEW OBSERVATION (owner)** — 3% of ₹133.8k equity = ₹4,014, not 3% of ₹150k = ₹4,500; today's trip only exists because of the shrunken base. Whether the ratchet is intended is an owner question, not a tune | §6.1 |
| watch | `strike-pick` chain-proximity | **WATCH** — BSE monthly day-of landed as predicted (377 SENSEX/0 NIFTY); tomorrow = post-BSE-expiry Friday, 3-of-4 saturated historically | §2 |
| NEW-7 (08-24) | fii intra-day retry #1450 | **SHIPPED, still unexercised** — boot catch-up SUCCESS again | §4 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no deploy today |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried**; its LOSS twin fired today (§6.1) | not reached |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | call succeeded today (grep 0); coverage still N23-A |
| T30 | `breadth` dot `>32` | **OPEN — back to step-function after 4 mid-range days**: CE 0% (adv 20–24), PE 0% with declines max EXACTLY 32 — 3rd boundary-pin session | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 62.7%, banded, zero flat; loosening ledger now **30/25/5** — today added 4 losses / 0 wins |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct = 0.093143 (24th); `iv_abs_band` 0% 6th day |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (28th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs 0 straddles — ±11,310 boot-fresh first-event pair, benign-by-shape |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5: no challenger-only rows today |
| T7 | composite threshold | **REJECTED — carried** | composite-055 challenger-only today: **0W/4L −₹4,745.39**; all-time −₹8,151.40 |
| NEW-8 (08-24) | trail-should-have-fired watch | **STANDING — 4th clean measurement** | §6.5 |
| T8/T26 | latency | OPEN (data) — **emit latency finally re-measured on real emissions: 17.7–21.3 s** (consistent with G8's 16.7–17.6 s); shadow entry p50 79 s / p95 82 s | §5.1 |
| T2 | `iv_rank` | carried, not open | NULL 1,420/1,420 |

## 8 Honesty caveats

- **This run executed ~15:45–16:25 IST** — before the 18:4x evening batch, the 18:52/18:53 swing
  settles (EXITS-only; 0/0 is the normal correct outcome, H27) and 18:59 buyable-alerts;
  tonight's ingest outcomes are tomorrow's verifications.
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.821 = trend; official 0.904 =
  trend — **no straddle**; CAS delta −42.20 (2nd consecutive negative; the print IS the daily
  low). Not a G11 chop day (count unchanged at 7).
- §5.3's model column uses a uniform 30-min time stop (harness parameter, §3.16), 2-min-sampled
  chain LTPs, no slippage/fees; the real-fill column carries engine fills + costs. They disagree
  in sign today — both are stated, neither is "the" answer.
- §6.2's denominator is the cron schedule (~28), not a per-fire log count; 0 failures is one
  day's draw from a mechanism that predicts variance.
- The champion book's +₹1,418.27 is a fan-out figure over 7 deduped events on 6 bar times; ONE
  TP cluster carries +₹15,202 of it — the effective independent sample is ~6 and the day
  ex-cluster is −₹13,784.
- futures_oi's ~20 missing capture minutes (all ≤11:38) did not surface in any quadrant/dot
  read; flagged as watch, not defect.
- Read-only run: SELECTs, log greps, in-container health reads. No restarts, deploys, writes,
  config changes, or republishes. Docs-only PR: this file + rollup rows + a README §3.36
  amendment.
