# Session findings — 2026-08-18 (data date)

Analysis date: 2026-08-18 EOD (scheduled post-market agent, run ~15:55 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,138** (bounds
`2026-08-18T09:15:00+05:30`…`15:40`; rows 09:19–15:13), signals fired **3 ENTRY + 3 EXIT**,
paper trades **3 opened / 3 closed, net −₹1,599.67**, shadow closes **8** (champion 7 +
composite-055 1).

Session character: **Tuesday, NSE WEEKLY EXPIRY day** (NIFTY 08-18 expiring; BSE Thu 08-20) ·
VIX 11.29–11.78 · **MIXED** (continuous o 24,223.85 → freeze 24,166.35 = −0.24% on a 0.43%
range, efficiency **0.557**; official CAS bar reads eff 0.601 — ALSO mixed, first
non-straddling agreement in four sessions; CAS delta **−11.45 (2nd negative print)**: 15:29 →
24,154.90) · drift-down PE day — **every one of the 104 composite-passing rows was PE, CE
pass mass 0** · signal contract `NFO:NIFTY26AUGFUT` (log-confirmed, 2,458 mentions) · **both
services recreated 15:30:32/15:30:54 IST** (H24/H25 deploy #1408/#1410) — ✅ **predeploy
snapshots found at the conventional path** `C:\Trading\ArthaYantra\log-snapshots\2026-08-18\`
(the NEW-4 path convention added 08-17, first run where it paid off): strategy-signal covers
to 15:20 IST, market-data to 15:19 IST. Residual unknowable window: **15:19/15:20 → 15:30**
only (post-square-off; scalper windows shut ~15:21).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,138 — **34 of 38 scalpers** (silent: `scalp-golden-crossover-nifty`, `scalp-golden-crossover-sensex-niftyoi`, `scalp-open-high-low-nifty`, `scalp-open-high-low-sensex-niftyoi` — the CE-side golden-crossover pair on an all-PE day + the open-high-low pair, 2nd consecutive. hero-zero RETURNED: `premium_skew` n=8) |
| eval outcomes | chart-gate-failed 2,114 · confluence-blocked 1,138 · composite-below-threshold 300 · **fired 3** · discipline-paused 0 |
| fired reconciliation (§3.36) | 3 fired evals = **3 ENTRY emitted + 3 EXIT = 6 signal rows**, risk-gate grep 0 (no `daily_profit_target` trip — red day). **All 6 signals from ONE slug — `scalp-golden-crossover-sensex-niftyoi-pe` — and all 3 entries the SAME leg `SENSEX2682077900PE`** (213 @10:48 comp 0.9416 · 215 @11:30 comp 0.7875 · 217 @13:06 comp 0.8306) |
| coverage | 24 of 25 15-min buckets populated 09:15–15:13; rejections END 15:13 = **window taper, not a stall** (eval buckets keep writing — zeros — to 15:39, through the recreate; discipline-paused 0). Midday thin patch 12:00–12:45 (4-slug window class) |
| boot health | boot 08:29:38 IST with a 0-loaded/38-unresolved transient → **38/0/0 at 08:31:23 (~105 s — back inside the normal 71–132 s band**; 08-17's 585 s credential outlier did not repeat). Post-deploy reload 15:31:39 clean install 38/0/0 |
| paper (funded) | pos 76 `SENSEX2682077900PE` 20 @591.70 (10:49, sub-1) → **TIME_STOP 11:25 +₹95.56** · pos 77 same leg 20 @664.05 (11:31, sub-2) → **STRUCTURAL_STOP 11:37 −₹922.67** (6-min cut) · pos 78 same leg 20 @601.25 (13:07, sub-3) → **STRUCTURAL_STOP 13:10 −₹772.56** (3-min cut). Net **−₹1,599.67**. Emit latency avg **~22.1 s** (6 emits, 17.5–28.0 s — G8/T26 class). Zero-sized entries: 0 |

## 2 Rail findings

- **§2.2 chain-proximity: the NSE-Tuesday DAY-OF cluster arrived on schedule — `strike-pick`
  425 fails, ALL nifty-rooted (15 slugs), ZERO sensex.** Series: 08-07 350-SX / 08-11 322-NF /
  08-12 0 / 08-13 336-SX / 08-14 14-SX / 08-17 361-NF (Mon eve) / **08-18 425-NF (Tue
  day-of)**. The eve→day-of pair completes exactly as §3.27 predicts; Wednesday 08-19 is the
  historically-clean control day.
- **volume-floor banded and honest; binding share eased to 55.7%** (634/1,138; 60.6% on
  08-17): 29 distinct thresholds 6,922–46,702 (avg 22,976) vs aligned 3m AUGFUT volume p50
  9,685 / p90 35,945 / max 140,465 — floor ≈ p75. §3.14: `relative-volume-floor` armed
  **38/38** published (latest publish stamp 08-13, unchanged).
- **The 60m-bias veto was a NON-FACTOR today**: only 14 rows carry the reason, **0 of them
  composite-passing** — there was no CE pass tail for it to refuse (the chart gate had
  already silenced the CE side; contrast 08-14/08-17 where it refused 200+ passing rows).
- option-side-constraint 38 first-blocks — CE-only slugs on an all-PE day, mechanical.

## 3 Composite + dots

- Distribution (scored n=840): pass mass **104/840 (12.4%) — collapsed from 08-17's record
  43.5%, and 104/104 PE, 0 CE**; max rejected 0.7892. The chart gate owned the CE side; the
  PE pass tail died mostly on volume-floor and the §2 near-miss rails.
- Dot support (complete session, n=840 unless noted): `iv_rank` 0% (withheld, standing) ·
  `iv_pair` **0% (21st session — T3, owner)** · basis **1.7%** · vwap **5.4%** · oi_spurt
  16.4% · volume 24.5% · trending_cross 26.9% · breadth 32.7% · rsi 46.9% · futures_oi
  53.8% · underlying_oi 56.9% · sentiment_slope 60.5% · sentiment 63.1% · psar 69.3% ·
  iv_slope 70.4% (n=142) · premium_skew 75.0% (n=8) · drastic_oi 81.4% · vwma 82.6% · vix
  97.9% · supertrend 99.3% · `iv_abs_band` **100% (n=142; frozen daily stamp, 17th
  session)**. basis/vwap at the bottom is regime (a drift-down day re-approaching VWAP from
  below), not a data fault — both operands alive (67/many distinct values).
- **§3.28 breadth side-split — 6th consecutive dead-CE session**: CE advances 17–18 vs `>32`
  → **0/14**; PE declines 24–35 straddling the line → **275/826 (33.3%)**. Same shape as
  08-13/08-17.
- OI bloc fully live on the NIFTY root **despite the NSE weekly expiry** (S24 suppression is
  MONTHLY-only): quadrants NEUTRAL **0/840** contextful (SHORT_BUILDUP 404 / LONG_BUILDUP
  338 / SHORT_COVERING 60 / LONG_UNWINDING 38; 298 NULLs = context-less pre-fetch class);
  `futures_oi_snapshots` 25,461 snaps / **369 of ~375 minutes**.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,138/1,138 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,138/1,138 | by design (un-armed) |
| `fiiLongPct` | NULL 298 = exactly the context-less rows | daily EOD stamp, alive |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 17th) |
| vix | 31 distinct, 11.29–11.78 | alive |
| ceIvAvg6 / skew / basis | 69 / 77 / 67 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean |
| §3.17 canary | **1 WARN, 0 straddles**: 09:15 opening bucket, shortfall **−10,270**, unpaired — the boot-fresh opening-bucket class (boot 08:29, empty lot cache; same shape as 08-17's −1,560, larger magnitude). No mid-session WARN | explained-class |
| market-data 08:30 instrument sync | **FAILED (kite-rest circuit open)** → catch-up ran and **OK at 09:05 IST** (62,655 rows, 173 tombstoned) | self-healed pre-open; note only |
| Kite session | validated 15:55 IST | ✓ |
| market-data canary | GREEN, 0 problems, 81 ticked tokens (post-close read) | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 7 closes, 0W, −202.55 pts, −₹4,533.96 NET → 2 deduped (bar, leg) events;
all-time −₹213,259.10 → −₹217,793.06** (points −2,626.30 → −2,828.85):
- 09:45 `SENSEX2682078000PE` ×6 @610.90 → SQUARE_OFF **−₹2,852.04** (−121.80 pts across 6
  fan-out rows).
- 10:48 `SENSEX2682077900PE` ×1 @591.65 → SQUARE_OFF **−₹1,681.92** (−80.75 pts) — **the
  funded book's own 10:48 leg, and the funded TIME_STOP exit (+₹95.56 at 11:25) beat the
  shadow's hold-to-square-off by ~₹1,777 on the same entry** — the recurring
  time-stop-cuts-earlier-and-loses-less pattern.
- **No §3.24 multi-exit clusters today** (both events single-reason SQUARE_OFF).

**Challengers: composite-055 took ONE row (challenger-only) and lost it — −₹524.82. That is
the 10th measured entry-gate loosening and the 10th loss** (all-time: composite-055
−₹5,103.54 · vol-12k5 −₹40,133.81, 0 closes today · vol-off −₹55,531.68, 0 closes today ·
`dot-null-withheld` still 0 rows ever).

**§4.2 counterfactuals** (sole-blocker sets, composite passed; §3.16 horizon caveat applies —
funded twins model the engine time stop, shadow twins model brackets/square-off): the entire
would-have-fired class is **5 bar-events, ALL on the same leg the funded book actually
traded** (`SENSEX2682077900PE`), each sole-blocked in fan-out rows by one of
`max-oi-sr-gate` / `pct-price-move` / `two-candle` / `volume-pump` (5 rows each) or
`strike-pick` (6 rows, leg unresolved):
- 10:48 @591.65 → funded twin +₹95.56 (TIME_STOP) / shadow twin −₹1,681.92 (SQUARE_OFF) —
  **model-dependent sign**.
- 11:30 @664.00 and 11:33 @637.55 → funded twin pos 77 **−₹922.67** (structural stop cut in
  6 min) — WOULD-LOSE.
- 13:06 @601.20 → funded twin pos 78 **−₹772.56** — WOULD-LOSE.
- 10:54 @611.85 → no direct twin; premium rose to 664.05 by 11:31 (funded-model ~36-min hold
  ≈ +52 pts WOULD-WIN) but the shadow square-off model reads ≈ −101 pts WOULD-LOSE —
  **UNRESOLVED, model-dependent**.
Net: the knobs in question were vetoing extra size on a leg the book already had, on a day
that leg lost — **no tuning row filed; the §3.26 prior extends to 10/10.**

## 6 New data points / anomalies

### 6.1 At-the-bell deploy (15:30:32/15:30:54 IST) — clean on every §3.38 probe, and the NEW-4 path convention worked first time

Both services recreated at the closing bell for the H24/H25 deploy (#1408 market-data
15:30:32, #1410 strategy-signal 15:30:54). **Zero `subscriber_health_events` rows today, no
slow-reload fingerprint** (`engine_reloads`: clean install 38/0/0 at 15:31:39 + reconcile
15:32:34 — no ~70–80 s cadence), unlike 08-17's staggered deploy: both containers went down
together, so there was no window for the old engine to block on a dead market-data. The CAS
official print (15:29) was already captured. **The predeploy log snapshots were found at the
conventional path on the first try** — the 08-17 discoverability fix verified working; only
15:19/15:20→15:30 is genuinely unknowable (post-square-off, windows shut).

### 6.2 §3.29 audit — TWO armed paths exercised for the first time (position-level attribution)

`close_reason` deltas since 08-17 15:00: TIME_STOP 13→14 (pos 76) · STRUCTURAL_STOP 8→10
(pos 77/78) · **STOP_LOSS 6→8**, and both new STOP_LOSS closes are swing-book CUPID exits
whose strategies each carry exactly ONE armed stop_loss basis, so the fires are attributable
despite the close_reason collapse:
- pos 66 `manas-arora-vcp` (basis **atr_multiple**) 08-17 16:01, −₹1,627.67 → **`stop_loss
  atr_multiple` EXERCISED — half of the standing INDETERMINATE pair resolves** (first
  attributable fire ever).
- pos 68 `minervini-power-play` (basis **percent**) 08-18 08:35, −₹776.27 → **`stop_loss
  percent` EXERCISED — off the never-fired list** (armed on 4, zero fires since 07-01 until
  now).
Never-fired now: `take_profit premium_pct` (36) · `signal_exit` (38) · `square_off` (2) ·
tag `oi-confluence-exit` (8). INDETERMINATE: `trailing_stop atr_multiple` (2) only.

### 6.3 §3.34 heat-gate evaluability — PASS (5th consecutive funded-day)

Log grep `heat call failed|heat unassessable` = **0** across 3 funded entries; all three rows
carry `margin_snapshot 0.00 / margin_pct 0.00` populated. Evaluability only — the zero-SPAN
long-option coverage question (N23-A) stands.

### 6.4 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines — the identical set to 08-17**
  (6×[A] + 5×[B], all the standing keyword-class false positives; no new line, no
  substantive contradiction; no edits required).
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted =
  exactly the 24 #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged from
  08-17. Nothing republished by this run.

### 6.5 §3.30 freeze telemetry — flag NOT hit (2 of 5)

Entries: sub-1 ×1 (10:49), sub-2 ×1 (11:31), sub-3 ×1 (13:07). Day PnL: sub-1 **+₹95.56 (a
winning close — below the ~1% profit-lock, so sub-1 stayed AVAILABLE)** · sub-2 −₹922.67
first-loss frozen ~11:37 · sub-3 −₹772.56 first-loss frozen ~13:10. **2 of 5 before 14:30 —
under the ≥3 flag.** Trend: 08-12 3/5+global · 08-13 2/5 · 08-14 2/5 · 08-17 3/5 · **08-18
2/5**. Last fired eval 13:06 — the governors blocked zero entries (counterfactual cost ₹0).

### 6.6 One-slug session

All 3 fires came from `scalp-golden-crossover-sensex-niftyoi-pe` re-entering the SAME strike
three times (10:48 / 11:30 / 13:06), with the composite sliding 0.9416 → 0.7875 → 0.8306.
The structural stop cut re-entries 2 and 3 in 6 and 3 minutes (−₹922.67 / −₹772.56) while
the first entry's TIME_STOP banked +₹95.56 — the stops did their job on a fading move; the
day's loss is concentration on one fading leg, not an exit defect. First session with
STRUCTURAL_STOP as the funded book's modal exit.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| watch | `strike-pick` chain-proximity | **WATCH — day-of arrived on schedule: 425-NF (eve was 361-NF)** | §2; Wed 08-19 is the historically-clean control |
| NEW-5 (08-14) | Redis candles.1m subscription drop | **WATCH — no recurrence** (day 3 of week window to 08-21) | zero stall rows; gauges healthy |
| NEW-4 (08-13) | post-close deploy log snapshot | ✅ **VERIFIED WORKING** — first run to FIND predeploy snapshots at the conventional path (§6.1) | keep the convention; nothing further |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | today's deploy was at-the-bell (15:30:32) and clean on every §3.38 probe (§6.1); the 08-17 measured mechanism remains the evidence |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | no trip (red day) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | grep 0 on 3 funded — 5th consecutive evaluability PASS (§6.3); N23-A stands |
| T30 | `breadth` dot `>32` | **OPEN** | 6th session dead-CE (adv max 18); PE straddling 33.3% |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | floor ≈ p75 banded, zero flat; binding share 55.7% (down from 60.6%); **10th loosening, 10th loss** (§5) |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (17th) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (21st session) |
| T23 | partial-bucket tolerance | **OPEN** | 1 WARN (opening bucket −10,270, boot-fresh class, unpaired), 0 straddles |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5 0 closes today; only-class prior stands |
| T7 | composite threshold | **REJECTED — carried** | composite-055 only-class −₹524.82 (10th loss) |
| T10 | stale OPEN paper positions | **OWNER — chronic, but MOVEMENT**: 2 swing STOP_LOSS closes landed (CUPID ×2, §6.2) | population 17 OPEN, oldest 07-07 (as of ~16:00, pre-evening batch) |
| T8/T26 | latency | OPEN (data) | shadow p50 1:20.1 / p95 1:22.5 (n=8); emit avg ~22.1 s (max 28.0 s) — same structural class |
| T2 | `iv_rank` | carried, not open | NULL 1,138/1,138 |

## 8 Honesty caveats

- **This run executed ~15:55–16:00 IST** — before the 16:00 swing batch, the 18:4x
  screen/EOD jobs and the 16:00 `iv_daily_summary` stamp; T10 and ingest statements are
  as-of-run-time.
- Session log evidence comes from the **predeploy snapshots** at the conventional path
  (strategy-signal to 15:20 IST, market-data to 15:19 IST); 15:19/15:20→15:30 is unknowable
  for log-derived checks — post-square-off, so no funded-path exposure.
- Shadow P&L (brackets/structural/square-off, no time stop) and the funded book
  (per-strategy `max_bars` + structural stop) are different exit models; they disagreed in
  SIGN on the 10:48 leg today (+₹95.56 vs −₹1,681.92). The 10:54 counterfactual leg is
  UNRESOLVED for the same reason (§5).
- The would-have-fired class is 5 bar-events on ONE leg — §3.24 fan-out inflation applies;
  effective independent sample ~5 bars of one instrument.
- Regime stamped from the CONTINUOUS session (§3.33a): eff 0.557 = **mixed**; official CAS
  bar 0.601 also mixed — no straddle. CAS delta −11.45 (2nd negative print; symmetric-noise
  reading holds).
- Read-only run: SELECTs, in-container health GETs, log greps on the predeploy snapshots.
  No restarts, deploys, writes, config changes, or republishes. Docs-only PR: this file +
  rollup rows.
