# Session findings — 2026-09-09 (data date)

⚠️ **BACKFILL, written 2026-09-12.** The scheduled post-market routine did not run on 09-09, 09-10
or 09-11 — all three attempts died on a Claude weekly usage limit. `2026-09-11-session-findings.md`
carries the full account of the gap and of what the reconstruction could and could not recover;
this file and `2026-09-10-session-findings.md` complete the set at the owner's request.
**Log-derived numbers come from `docker logs --tail` (market-data reaches back to 09-03); no
`log-snapshots/` directory exists for this day, because the snapshot is written BY the routine that
never ran.** ⚠️ **strategy-signal has NO logs at all for this day** — it sits inside the log
barrier's nine-day silence (§6.2 of the 09-11 file), so every strategy-signal claim below is from
the DB only.

Analyst: Claude (Architect session, manual backfill). Data: `signal_rejections` **1,275**
(rows **09:46:01**–14:58:01), signals **9 fired evals = 6 ENTRY + 3 EXIT**, paper **3 filled /
3 closed, funded net +₹1,068.60 (2W/1L)**, shadow champion **33 closes, 11 net wins,
−₹47,454.99**.

Session character: **Wednesday, no expiry** · **the host booted at 09:42:48 IST — 27 minutes AFTER
the market open, the first post-open boot in this series, and it cost ~28 minutes of live OI
capture that cannot be recovered (§6.1 — the day's headline)** · regime **TREND-DOWN (continuous
eff 0.647, official 0.647 — aligned)** · signal contract `NIFTY26SEPFUT` · PE-favouring day:
286 composite passes, 156 PE (max 0.9043) / 130 CE.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,275 — **37 of 37** enabled scalpers emitted rows (full coverage once the engine was up) |
| eval outcomes | chart-gate-failed 1,931 · confluence-blocked 1,275 · composite-below-threshold 184 · **fired 9** · discipline-paused 0 |
| fired reconciliation (§3.36) | **9 fired evals = 6 ENTRY + 3 EXIT signals → 3 FILLED.** ⚠️ Reads as 3 missing opens and is not: four of the six entries are repeat fires on `NIFTY2691523750PE` (11:54, 12:00, 12:03, 12:15) against position 119, open since 11:55 — the idempotency path correctly declining to pyramid |
| coverage | **21 populated 15-min buckets, 09:45–14:45. The 09:15 and 09:30 buckets are EMPTY — not thin, absent** (§6.1). Interior coverage after 09:45 is unbroken; thinnest 13:15 / 14:15 at 30 |
| boot health | boot **09:42:48 IST**, `Started` 09:43:03, `RestartCount=0` both services. Auto-login boot catch-up CONNECTED **09:43:24**, ticker 09:43:24 — ~36 s after boot, **6th consecutive fully-automatic login** — but 28 minutes after the open |
| §3.30 freeze telemetry | 3 subs took one entry each (09:52, 11:55, 11:55); **2 of 5 frozen — NO flag** (needs ≥3). `discipline-paused` 0 |

## 2 Rail findings

- **volume-floor first-block 730/1,275 (57.3%)** — banded, in the normal range.
- **`time-window` 232** is the #2 first-blocker, and on this day it is partly an ARTEFACT of the
  late boot: the strategies whose entry windows open early had already lost 27 minutes of them
  before the engine existed.
- **`confluence-composite` 23 rows, TWO reason classes**: 20 × `60m bias opposes the side`
  (10 `scalp-connect-the-dots-nifty` + 10 `scalp-connect-the-dots-sensex-niftyoi`) and 3 ×
  `aggregate 0.5851 below threshold 0.6` (`scalp-golden-crossover-nifty-pe`). Split by the full
  reason string — the two classes are not distinguishable by position.
- First-block tail: two-candle 44 · pct-price-move 44 · volume-pump 44 · divergence-vol-gate 42 ·
  rsi-band 31 · oi-cross-required 30 · time-of-day-preference 28 · supertrend-15m 10 ·
  option-side-constraint 8 · call-put-delta-filter 4 · directional-change-gate 2 ·
  psar-durability 2 · **max-oi-sr-gate 1** (16 distinct).

## 3 Composite + dots

- **Composite passes 286 of 1,007 scored (28.4%) — PE 156 (max 0.9043) / CE 130 (max 0.7647).**
- **OI bloc fully LIVE once capture resumed**: SHORT_BUILDUP 350 / LONG_BUILDUP 317 /
  SHORT_COVERING 214 / LONG_UNWINDING 126 — quadrants NEUTRAL **0**. ⚠️ But see §6.1: the OI
  context on this day is built on a series missing its first ~28 minutes.
- Dot support (n=1,007 unless noted): premium_skew 0% (n=2) · `iv_pair` 0% · `iv_rank` 0%
  (withheld, standing) · oi_spurt 10.6% · iv_slope 13.5% (n=148) · trending_cross 16.0% ·
  vwap 21.4% · breadth 26.3% · vix 26.3% · volume 27.5% · rsi 38.6% · underlying_oi 53.9% ·
  futures_oi 57.8% · psar 59.4% · sentiment_slope 70.5% · sentiment 70.8% · basis 73.7% ·
  drastic_oi 76.5% · vwma 82.9% · **`iv_abs_band` 100% (148/148)** — fresh `atmIv` **0.101805**
  inside the band (T28's coin-flip landing IN for the second time in three sessions) ·
  **`supertrend` 100% (1,007/1,007)**.
- **§3.28 breadth (T30) — PERFECTLY saturated both ways, the cleanest instance yet: PE 265/265
  (100%) and CE 0/742 (0%).** Not a partial straddle of the `>32` line as on 09-08 — a total split.
  Worth having, because it shows the dot is a pure side-switch on a decisively directional day
  rather than a gradient.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,275/1,275 | dead-data, standing (since 07-02) |
| `dowUp` | NULL | by design (un-armed) |
| `fiiLongPct` | NULL on 268 of 1,275 | normal — the standing ratio, not a regression (09-11 file §6.4) |
| `atmIv` | 1 distinct, **0.101805** | frozen daily stamp, correct mechanism (G12/T28) |
| vix | 13 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean |
| §3.17 PartialBucketCanary | **0 WARNs + 0 straddles** | clean |
| signal-future 1m rows | 375/375 minutes present | ⚠️ **present, but see §6.1 — this number is exactly the reassuring artifact the outage register warns about** |
| **futures_oi capture** | **348 distinct minutes; hour 09 has 17 of a possible ~45** | ⚠️ **~28 MINUTES PERMANENTLY LOST — §6.1** |
| per-contract tick/bar divergence | **ZERO `data canary RED` events** | **H49 clean — 1st of 3 consecutive** |
| market-data ERRORs | **0** | clean |

## 4b BOOT WINDOW (§3.41) — the boot itself is the day's defect

All IST. **Circuit-breaker transitions: ZERO. Capture minutes lost overlapping the window: ~28,
and they are INSIDE the session, not before it.**

- **09:42:48** — containers start. `Started MarketDataServiceApplication in 16.838 seconds` at
  09:43:03. `RestartCount=0` both services — the DB-not-ready bean-race crash did not recur.
- **09:42:57** — `kite session restore: persisted token from 2026-09-08T03:20:21Z expired at
  2026-09-09T06:00+05:30 — NOT resumed` (#1520's 6th refusal).
- **09:43:04** — `kite auto-login boot catch-up: started at 09:43:04 IST, inside the window` →
  **09:43:24 authorize hop 1 → 302 same-origin, request_token at hop 2, CONNECTED 09:43:24, ticker
  connected 09:43:24.** ~36 s from boot to a live ticker. **The login lattice worked perfectly. It
  is the only reason the loss was 28 minutes and not the whole session.**
- Missed-cron catch-ups, all clean and all late by the same 27 minutes: BHAVCOPY / NSE_FII_DII /
  NSE_PARTICIPANT_OI / NSE_FII_DERIVATIVE all SUCCESS at 09:43:03–04, OPTIONS_SNAPSHOT_CAPTURE
  started 09:44:19, **INSTRUMENT_SYNC 09:50:00 (60,754 rows)** against its usual 08:30 slot.
- **09:42:48 → first eval bucket 09:42, first non-zero eval 09:45, first rejection row 09:46:01.**

⚠️ **The catch-up lattice is bounded by the clock, not by the boot.** Every job here recovered
because its window had not closed. **The market open has no catch-up** — there is nothing to
re-run, because the ticks that were never received cannot be re-received.

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — 3 fills, 2W/1L, +₹1,068.60

| pos | sub | leg | entry (IST) | exit | net |
|---|---|---|---|---|---|
| 117 | 1 | SENSEX2691075600PE ×20 @561.10 | 09:52 | TRAILING_STOP 10:22 | −₹525.41 |
| 118 | 2 | SENSEX2691075500PE ×20 @486.90 | 11:55 | TIME_STOP 12:25 | **+₹709.61** |
| 119 | 3 | NIFTY2691523750PE ×65 @240.15 | 11:55 | TIME_STOP 12:25 | **+₹884.40** |

**A green funded day, and T29's FAVOURABLE face: both TIME_STOPs won.** The trailing stop was the
only loser. Note the 09:52 entry came **10 minutes after the engine first evaluated** — the book
was trading on a session it had only just joined.

§3.34 heat: grep **0** on 3 funded entries; `margin_pct` 0.00 all three (long-option no-SPAN shape,
N23-A stands). §3.40 settle: **0 refused**. Emit latency 16.1–19.6 s — **19.6 s is a new
high-water** (prior 19.5 s, 09-07). §3.29 delta: TIME_STOP +2, TRAILING_STOP +1; **never-fired set
unchanged**.

### 5.2 Shadow book — 33 closes, 11 net wins, −₹47,454.99

Deduped to **14 `(bar, leg)` clusters**, and the day splits cleanly in two. The morning PE side
was right: 09:51 `SENSEX2691075600PE` ×5 **+₹8,645.94** and `NIFTY2691523750PE` ×6 +₹848.51.
**Then the 12:36 CE reversal-chase destroyed it**: `NIFTY2691523400CE` ×6 **−₹23,854.89** and
`SENSEX2691074800CE` ×6 **−₹18,118.24** — **−₹41,973 from one bar**, the single worst cluster pair
in the recent record. Tail: 10:00 stops −₹4,648.89 / −₹2,797.48 · 11:54 −₹1,127.73 / −₹717.37 ·
12:45 +₹219.86 / +₹76.01 · 12:48 −₹1,608.73 / −₹1,310.05 · 12:57 −₹1,607.92 / −₹1,454.01.

Challenger-only: composite-055 **0 of 5, −₹9,833.92**.

### 5.3 §4.2 counterfactuals — the veto refused the 12:36 disaster

Sole-blocker set: **61 rows** — two-candle 18, volume-pump 18, confluence-composite 12,
pct-price-move 12, max-oi-sr-gate 1. Joined **directly on `rejection_id`** to shadow rows (exact
attribution, not a hand match on `(bar, leg)`):

| bar | rail | leg | variant | close | net |
|---|---|---|---|---|---|
| 09:51 | volume-pump / two-candle / max-oi-sr-gate | NIFTY2691523750PE | champion | SQUARE_OFF | **+₹467.58** |
| 09:51 | volume-pump / two-candle | SENSEX2691075600PE | champion | SQUARE_OFF | **+₹2,282.60** |
| 09:51 | pct-price-move | both PE legs | champion | STRUCTURAL_STOP | −₹484.46 / −₹1,489.39 |
| 11:54 | pct-price-move | both PE legs | champion | STRUCTURAL_STOP | −₹717.37 / −₹1,127.73 |
| **12:36** | **confluence-composite (the veto)** | NIFTY2691523400CE | champion | **STOP_LOSS** | **−₹4,446.94** |
| **12:36** | **confluence-composite (the veto)** | SENSEX2691074800CE | champion | **STOP_LOSS** | **−₹2,816.33** |

**NEW-9: the veto refused the 12:36 bar — the exact bar whose champion clusters lost ₹41,973 in
fan-out — for two corroborated losers at champion reference, −₹7,263.27 saved.** This is the
veto's best single day on record and the mirror image of 09-11.

⚠️ Note the rails disagree with each other on the SAME 09:51 bar: `volume-pump` and `two-candle`
sole-block legs that went on to win, while `pct-price-move` sole-blocks the same two roots into
losses. They are blocking different `(slug, leg)` rows at the same minute, so both readings are
true — a reminder that per-rail attribution is per-row, never per-bar.

## 6 New data points / anomalies

### 6.1 THE HOST BOOTED 27 MINUTES AFTER THE OPEN, and ~28 minutes of OI capture is permanently gone

`Starting MarketDataServiceApplication` at **09:42:48 IST**. The market opened at 09:15. This is
**the first post-open boot in the measured series** and it is a different class of event from the
eleven "overnight downtime, boots 08:0x–08:5x" days around it.

⚠️ **The obvious health checks all say the day was fine, and they are all wrong here.** The
signal-future 1m series shows **375/375 minutes, 09:15 → 15:29** — complete — because the
cache-first 10-minute-tail REST re-fetch backfilled the missing bars from Kite. `min/max(bucket)`
certifies full coverage. This is precisely the reassuring-direction artifact the outage register
records, and it would have concealed the whole event.

⚠️ **The `source` column does NOT discriminate it either, and I nearly claimed it did.** The
pre-boot window is 100% `source='KITE'` — but so is most of the rest of the day (hour 10: 59 KITE /
1 TICK_AGG; hours 11–12: 60/0), because the authoritative re-fetch REPLACES tick-agg bars on a
value-changing write. **A KITE-dominated window is the normal steady state, not evidence of an
outage.**

**What actually measures it is the OI snapshot cadence, because OI capture has no backfill path at
all** (historical OI is read-time-derived; live capture is the only source of real snapshots):

| hour | 09-09 futures_oi distinct minutes | 09-10 (control) |
|---|---|---|
| 09 (≈45 min of session) | **17** | **44** |
| 10–14 | 60 each | 59–60 each |

**~28 minutes of live futures OI and ~29 minutes of options-chain capture (hour 09: 16 distinct
minutes) are permanently absent from 2026-09-09.** Day totals: futures_oi **348** minutes vs 374 on
09-10 and 09-11.

**Consequences, stated at their real size and no larger:** price history is intact (re-fetched
broker-official), so no funded fill was mispriced and no rail read a wrong candle. What is gone is
the OI plane for the first half-hour — every OI-derived dot, the quadrants, the confluence context
— on a day the OI bloc otherwise read fully live. The engine also simply did not exist for those
27 minutes, so no entry could have been taken however good the setup.

**The catch-up lattice is not at fault and worked exactly as designed** — auto-login CONNECTED 36 s
after boot, every missed morning cron re-ran, `unresolved` healed. **The lattice recovers JOBS,
whose windows had not closed. It cannot recover a live tick stream, because there is no re-run of
a minute that has passed.** Escalated to the ledger as a new row.

### 6.2 The rails contradicted each other on one bar — per-row attribution, never per-bar

See §5.3: at 09:51, `volume-pump`/`two-candle` sole-block rows that would have won while
`pct-price-move` sole-blocks rows that would have lost, on the same two roots at the same minute.
Both are correct; they are different `(slug, leg)` rows. Recorded because a per-bar reading of the
counterfactual table produces a contradiction that does not exist.

### 6.3 `max-oi-sr-gate` appears as a first-blocker for the first time in this series

One row. No prior findings file names this rail in a first-block histogram. It is also a
sole-blocker on the 09:51 winning leg. n=1 — noted so its next appearance is not read as new.

### 6.4 Minor, named so "benign" and "absent" do not read the same

- **market-data ERRORs: zero.**
- `bhavcopy-close canary YELLOW: 1 of 214 symbols diverge > 1.00%` — AUROPHARMA (bhav 1,698.90 vs
  kite 1,677.60, 1.27%). The canary working.
- `minervini screen already running — scheduled trigger skipped (H13: two doors overlapped)` at
  18:46:58 — the H13 guard firing.
- Swing: minervini + manas SETTLE both ran 18:52:06 / 18:53:00, **`exit_skipped` 0**, 0 exits;
  the 09-09 ENTRIES pass ran next morning (manas 141 candidates → **1 entry**, 6 would-enter,
  5 cap-exceedance; minervini 142 → 0 of 14 would-enter). **NEW-8 13th measurement.**

## 7 Tuning candidates

Ledger §0 group G/H is authoritative; nothing applied by this run.

| # | knob | status | evidence |
|---|---|---|---|
| **NEW (09-09)** | **post-open host boot** | **NEW — escalated to the ledger. Boot 09:42:48, 27 min after the open; ~28 min of futures-OI and ~29 min of options-chain capture permanently lost; every routine health check reads CLEAN** | §6.1 |
| NEW-9 (08-26) | 60m-bias veto | **OPEN — the veto's BEST day: refused the 12:36 CE bar at champion reference for −₹7,263.27 of avoided loss, the exact bar whose fan-out cost ₹41,973** | §5.3 |
| H49 (ledger) | per-contract tick-agg bar-closing sparse | **ZERO REDs — 1st of three consecutive clean sessions** | §4 |
| T30 | `breadth` dot `>32` | **OPEN — the cleanest split yet: PE 265/265 (100%) and CE 0/742 (0%), a total side-switch rather than a gradient** | §3 |
| T28 | `atmIv` frozen daily stamp | **OPEN** — stamp 0.101805, `iv_abs_band` 100% (148/148) | §3 |
| T29 | exit model dominates entry gate | **OPEN (exit-band track)** — favourable face: both TIME_STOPs won | §5.1 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 57.3%; composite-055 0 of 5 |
| T8/T26 | latency | **OPEN (data)** — funded emit **19.6 s, new high-water** | §5.1 |
| T3 | `iv_pair` | **OPEN (owner)** — 0% | §3 |
| NEW-8 (08-24) | swing governor watch | **STANDING — 13th measurement** | §6.4 |
| NEW-6 / NEW-13 / NEW-16 / NEW-10 / NEW-3 / NEW-1 / T1 / T7 / T2 | — | **carried unchanged** | — |

## 8 Honesty caveats

- **This is a backfill written three days late.** It is reconstructed from the DB and market-data
  logs only. **strategy-signal has NO logs for this day at all** — 09-09 sits inside the log
  barrier's nine-day silence — so nothing about the engine's internal behaviour during the 27
  minutes before it produced rows can be established. That is a real, permanent evidence gap, and
  it is exactly the cost the barrier imposes.
- **The ~28-minute capture loss is `computed` from OI snapshot minutes with 09-10 as the control**
  (17 vs 44 in hour 09). It is not inferred from the boot time alone, and it is explicitly NOT
  inferred from the candle `source` column, which does not discriminate (§6.1).
- Regime: **TREND-DOWN, and the two reads AGREE (continuous eff 0.647 / official 0.647)** — the 1d
  bar's close equals the continuous close because the 15:28 bar already carried it. ⚠️ Its
  continuous freeze therefore broke a minute early (the pin held 23,462.45 through 15:27), so **no
  clean CAS delta is separable for this day and none is quoted.** **G11 chop count stays 10.**
- Counterfactuals are joined on `rejection_id`, so leg attribution is exact — but the P&L is the
  SHADOW fill model, not a funded fill, and clusters fan out across variants (champion reference
  used; summing variants would multiply-count).
- The funded book is **n=3**. "A green day" is an observation.
- Read-only run: SELECTs, `docker logs --tail`, `docker inspect`. No restarts, deploys, writes,
  config changes or republishes.
