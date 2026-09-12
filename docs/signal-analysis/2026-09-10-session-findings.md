# Session findings — 2026-09-10 (data date)

⚠️ **BACKFILL, written 2026-09-12.** The scheduled post-market routine did not run on 09-09, 09-10
or 09-11 — all three attempts died on a Claude weekly usage limit.
`2026-09-11-session-findings.md` carries the full account of the gap; this file completes the set
at the owner's request. **Log-derived numbers come from `docker logs --tail`; no `log-snapshots/`
directory exists for this day.** ⚠️ **strategy-signal logs exist for this day only from 14:40 IST
onward** — the log barrier's nine-day silence ended mid-session at 09:10:01Z (§6.2), so the morning
is DB-only and the afternoon is not.

Analyst: Claude (Architect session, manual backfill). Data: `signal_rejections` **1,216**
(rows 09:19:13–14:58:01), signals **3 fired evals = 3 ENTRY**, paper **2 filled / 2 closed, funded
net +₹631.06 (1W/1L)**, shadow champion **26 closes, 6 net wins, −₹22,963.49**.

Session character: **Thursday, no expiry** · **boot 08:02:55 IST — the earliest weekday boot in the
series, comfortably pre-open; the exact opposite of 09-09** · regime **DIVERGENT — mixed on the
continuous read (eff 0.543), CHOP on the official (0.272), with the largest CAS delta measured
(+88.55) doing the flipping (§8)** · signal contract `NIFTY26SEPFUT` · a genuinely indecisive tape:
**82 composite passes of 918 (8.9%)**, the lowest of the three days.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,216 — **35 of 37** enabled scalpers emitted rows |
| eval outcomes | chart-gate-failed 1,893 · confluence-blocked 1,216 · composite-below-threshold 334 · **fired 3** · discipline-paused 0 |
| fired reconciliation (§3.36) | **3 fired = 3 ENTRY emitted → 2 FILLED.** The third is the duplicate `NIFTY2691523650PE` fire at the same 14:09 bar as the filled one — idempotency, not a suppression |
| coverage | **23 of 23** populated 15-min buckets 09:15–14:45; thinnest 09:15 at 8, 09:30 at 10, **11:30 at 10** (a genuine midday chart-gate lull), 14:45 at 15 |
| boot health | boot **08:02:55 IST** (`Started` 08:03:09), `RestartCount=0` both; auto-login CONNECTED **08:03:30**, ticker 08:03:31 — ~36 s after boot, **7th consecutive fully-automatic login**, and **72 minutes before the open** |
| §3.30 freeze telemetry | 2 subs took one entry each (14:10); **1 of 5 frozen — NO flag**. `discipline-paused` 0 |

## 2 Rail findings

- **volume-floor first-block 712/1,216 (58.6%)** — banded, in the normal range.
- **`time-window` 224** — #2 first-blocker, the ordinary share for a full session (contrast 09-09,
  where the same rail's count was inflated by the late boot eating the early windows).
- **`confluence-composite` 24 rows, and the split is 14 veto / 10 score-shortfall**: 14 ×
  `60m bias opposes the side` (7 `scalp-connect-the-dots-nifty` + 7 `-sensex-niftyoi`) against
  **10 rows across six distinct `aggregate … below threshold 0.6` values** (0.4314, 0.5294, 0.5319,
  0.5539, 0.5585), spread over the `-pe` siblings and `scalp-golden-crossover-nifty-pe`.
  On a day this indecisive the shortfall class is nearly as large as the veto class — unusual, and
  the reason §3.39 insists the split is read by full reason string.
- First-block tail: time-of-day-preference 48 · rsi-band 42 · pct-price-move 26 · two-candle 26 ·
  divergence-vol-gate 26 · volume-pump 26 · option-side-constraint 26 · oi-cross-required 18 ·
  supertrend-15m 12 · directional-change-gate 6 (13 distinct).

## 3 Composite + dots

- **Composite passes 82 of 918 scored (8.9%) — CE 58 (max 0.6912) / PE 24 (max 0.7447).** The
  lowest pass rate of the three days and the only one where neither side dominates: **the tape gave
  the gate nothing to work with**, which is the honest reading of a chop/mixed session.
- **OI bloc fully LIVE, and the quadrant mix is the flattest measured**: SHORT_COVERING 242 /
  SHORT_BUILDUP 236 / LONG_BUILDUP 235 / LONG_UNWINDING 205 — four quadrants within 37 rows of each
  other, NEUTRAL **0**. Contrast 09-09 (SHORT_BUILDUP 350 dominant) and 09-11 (LONG_BUILDUP 360).
  **A flat quadrant distribution is what indecision looks like in the OI plane** — and it is
  *positive* evidence the bloc is live, since a dead bloc saturates rather than spreads.
  futures_oi capture **374 distinct minutes**.
- Dot support (n=918 unless noted): `iv_rank` 0% (withheld, standing) · `iv_pair` 0% ·
  **vwap 2.6% — the lowest vwap reading in the recent record** · oi_spurt 7.8% · breadth 8.2% ·
  iv_slope 12.5% (n=144) · trending_cross 17.4% · volume 22.4% · rsi 30.4% · basis 34.2% ·
  vix 34.6% · futures_oi 54.8% · underlying_oi 59.4% · sentiment_slope 68.4% · psar 75.4% ·
  sentiment 78.2% · drastic_oi 87.5% · vwma 91.1% · **`iv_abs_band` 100% (144/144)** — fresh
  `atmIv` **0.109546**, well inside the 10–12 band · **`supertrend` 100% (918/918)**.
- **§3.28 breadth (T30)**: PE 75/604 (12.4%), **CE 0/314 (0%)** — the CE side gets nothing even
  though the official close was UP. On a chop day the advance/decline count sits below the `>32`
  line most of the session, so the dot withholds from both sides. Third distinct T30 shape in
  three days (total split 09-09, near-total withholding here, up-day mirror 09-11).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,216/1,216 | dead-data, standing (since 07-02) |
| `dowUp` | NULL | by design (un-armed) |
| `fiiLongPct` | NULL on 298 of 1,216 | normal standing ratio, not a regression (09-11 file §6.4) |
| `atmIv` | 1 distinct, **0.109546** | frozen daily stamp, correct mechanism (G12/T28) |
| vix | 11 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean |
| §3.17 PartialBucketCanary | **0 WARNs + 0 straddles** | clean |
| signal-future capture | 375/375 1m minutes | healthy — **and here the number means what it says** (contrast 09-09 §6.1) |
| futures_oi capture | **374 distinct minutes**, hour 09 at 44 of ~45 | healthy — this day is the CONTROL that made 09-09's loss measurable |
| per-contract tick/bar divergence | **ZERO `data canary RED` events** | **H49 clean — 2nd of 3 consecutive** |
| market-data ERRORs | **0** | clean |

## 4b BOOT WINDOW (§3.41) — clean, stated as an explicit zero

All IST. **Circuit-breaker transitions: ZERO. Capture minutes lost overlapping the window: ZERO** —
the login completed **72 minutes before the open**, the widest margin in the series.

- **08:02:55** — containers start (the earliest weekday boot measured). `Started` 08:03:09,
  `RestartCount=0` both services; the DB-not-ready bean-race crash did not recur.
- **08:03:04** — `kite session restore: persisted token from 2026-09-09T04:13:24Z expired at
  2026-09-10T06:00+05:30 — NOT resumed` (#1520's 7th refusal).
- **08:03:10** — auto-login boot catch-up armed, inside window. **08:03:30 authorize hop 1 → 302
  same-origin, request_token at hop 2, CONNECTED 08:03:30, ticker connected 08:03:31** — 7th
  consecutive fully-automatic login, ~36 s after boot.
- **08:04:59** — `kite auto-login: session already CONNECTED — standing down`. This is the **08:05
  cron** arriving to find the boot catch-up had already done the work, and declining to re-login.
  The stand-down is the `attemptIfStillNeeded` guard behaving exactly as specified, and it is worth
  a line precisely because a silent no-op and a missed job look identical otherwise.

## 5 Shadow outcomes + counterfactuals

### 5.1 Funded book — 2 fills, 1W/1L, +₹631.06

| pos | sub | leg | entry (IST) | exit | net |
|---|---|---|---|---|---|
| 121 | 1 | NIFTY2691523650PE ×130 @229.65 | 14:10 | STRUCTURAL_STOP 14:25 | −₹500.42 |
| 122 | 2 | SENSEX2691075000PE ×40 @327.40 | 14:10 | TIME_STOP 14:40 | **+₹1,131.48** |

**A second consecutive green funded day** — and again the TIME_STOP is the winner while the
structural stop takes the loss. Both entries are the SAME bar (14:09) on the two roots, so this is
one decision expressed twice, not two independent trades: **n=1 in decision terms.**

§3.34 heat: grep **0** on 2 funded entries; `margin_pct` 0.00 both (N23-A stands). §3.40 settle:
**0 refused**. Emit latency 16.9–17.2 s on the entries, plus a **680 ms** outlier on the 14:39
exit — far below the 16–19.6 s band and worth watching rather than explaining from one instance.
§3.29 delta: STRUCTURAL_STOP +1, TIME_STOP +1; **never-fired set unchanged**.

### 5.2 Shadow book — 26 closes, 6 net wins, −₹22,963.49

Deduped to **6 `(bar, leg)` clusters**, and the day is one bad decision plus one good one:
12:06 `NIFTY2691523300CE` ×7 **−₹18,263.64** and `SENSEX2691074500CE` ×7 **−₹11,786.08** — the
midday CE chase, **−₹30,050 from one bar** · 12:15 follow-ons −₹1,894.12 / −₹1,829.80 · then the
afternoon PE turn that the funded book also took: 14:09 `NIFTY2691523650PE` ×4 **+₹3,666.10** and
`SENSEX2691075000PE` ×4 **+₹7,144.05** (including a TAKE_PROFIT).

**Challenger-only: composite-055 3 of 5 wins, +₹239.58 — its only positive day in the window**,
and vol-12k5 0 of 2, −₹6,411.03.

### 5.3 §4.2 counterfactuals — the veto again refused losers

Sole-blocker set: **13 rows** — confluence-composite 6, two-candle 3, volume-pump 3,
pct-price-move 1. Joined **directly on `rejection_id`**:

| bar | rail | leg | variant | close | net |
|---|---|---|---|---|---|
| **12:15** | **confluence-composite (the veto)** | NIFTY2691523300CE | composite-055 | **STOP_LOSS** | **−₹4,614.37** |
| **12:15** | **confluence-composite (the veto)** | SENSEX2691074600CE | composite-055 | **STOP_LOSS** | **−₹1,796.66** |
| 12:15 | confluence-composite | both CE legs | vol-12k5 | STOP_LOSS | −₹4,614.37 / −₹1,796.66 (fan-out) |
| 14:09 | two-candle / volume-pump | NIFTY2691523650PE | champion | SQUARE_OFF | **+₹1,554.24** |
| 14:09 | two-candle / volume-pump | SENSEX2691075000PE | champion | TAKE_PROFIT | **+₹2,542.94** |

**NEW-9: the veto refused 2 more corroborated CE losers at 12:15** — the follow-on bar to the
12:06 cluster that cost the shadow book ₹30,050. **Second consecutive day the veto is right.**

⚠️ **But the volume rails were WRONG on the same day**: `two-candle` and `volume-pump` sole-blocked
the 14:09 PE legs that the funded book DID take and that won (+₹1,554.24 / +₹2,542.94 in the
shadow). The funded book got those trades only because the blocking applied to *different slugs* on
the same bar. **On one session the veto saved ₹6,411 and the volume rails cost ₹4,097** — which is
the whole argument for judging rails per-row, and against treating "a rail blocked something that
won" as a verdict on the rail.

## 6 New data points / anomalies

### 6.1 The flattest OI quadrant distribution measured, and it is evidence of HEALTH

Four quadrants within 37 rows (242/236/235/205). Every prior findings file reports a dominant
quadrant. **The instinct is to read a flat distribution as a degraded signal; the opposite is
true** — the known failure mode for this bloc is SATURATION (NEUTRAL 0/N under S24 monthly-expiry
suppression, or a frozen feed pinning one quadrant). A near-uniform spread cannot be produced by
either. Recorded so a future flat day is not misfiled as a defect.

### 6.2 The log barrier cleared DURING this session

strategy-signal's stdout resumed at **09-10 09:10:01Z (14:40 IST)** after nine days of silence, on
an ordinary `scalper confluence blocked entry` line, in the same process that went quiet on 09-01
(the surrounding `Starting` lines are 09-01T02:20 and 09-11T03:19 — no restart between). Full
write-up in the 09-11 file §6.2; noted here because **this session is the boundary**, and it is why
this file has strategy-signal logs for its afternoon and none for its morning.

### 6.3 A 680 ms emit latency against a 16–19.6 s band

The 14:39 EXIT emitted in **680 ms** while every other signal in the window took 16.1–19.6 s. One
instance, one direction, no explanation attempted — the band has been stable for weeks and a single
outlier two orders of magnitude below it is either a different code path or a measurement artifact.
Recorded for the next occurrence, not acted on. (T8/T26.)

### 6.4 Minor, named so "benign" and "absent" do not read the same

- **market-data ERRORs: zero.**
- `bhavcopy-close canary YELLOW: 1 of 214 symbols diverge > 1.00%` — LTM (bhav 4,374.00 vs kite
  4,318.00, 1.30%). The canary working.
- `minervini screen already running — scheduled trigger skipped (H13: two doors overlapped)` at
  18:46:58 — the H13 guard firing, second consecutive day.
- **Two `Cannot reconnect to [redis/<unresolved>:6379]: CancellationException` WARNs at 01:33 IST**
  — during host shutdown, outside the session, no impact.
- Swing: both SETTLEs ran 18:52:07 / 18:52:59, **`exit_skipped` 0**, 0 exits. The 09-10 ENTRIES
  pass ran next morning: manas 140 candidates → **0 entries** (5 would-enter, all cap-exceedance),
  minervini 155 → 0 of 8. **NEW-8 14th measurement.**

## 7 Tuning candidates

Ledger §0 group G/H is authoritative; nothing applied by this run.

| # | knob | status | evidence |
|---|---|---|---|
| NEW-9 (08-26) | 60m-bias veto | **OPEN — 2 more corroborated losers refused at 12:15 (−₹4,614.37 / −₹1,796.66), second consecutive correct day** | §5.3 |
| **volume rails** | two-candle / volume-pump | **WATCH — sole-blocked the 14:09 PE winners (+₹1,554.24 / +₹2,542.94) on the same day the veto saved ₹6,411. Per-ROW attribution, not per-rail verdicts** | §5.3 |
| H49 (ledger) | per-contract tick-agg bar-closing sparse | **ZERO REDs — 2nd of three consecutive clean sessions** | §4 |
| T30 | `breadth` dot `>32` | **OPEN — third distinct shape in three days: near-total withholding (PE 12.4%, CE 0%) on a chop tape** | §3 |
| T28 | `atmIv` frozen daily stamp | **OPEN** — stamp 0.109546, `iv_abs_band` 100% (144/144) | §3 |
| T29 | exit model dominates entry gate | **OPEN (exit-band track)** — TIME_STOP wins again, structural stop loses; but n=1 in decision terms (one bar, two roots) | §5.1 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 58.6%; composite-055's only green day (3/5, +₹239.58) does not move its −₹28,564.77 book |
| T8/T26 | latency | **OPEN (data)** — a **680 ms** emit against a 16–19.6 s band, n=1 | §6.3 |
| T3 | `iv_pair` | **OPEN (owner)** — 0% | §3 |
| NEW-8 (08-24) | swing governor watch | **STANDING — 14th measurement**, fully capacity-bound | §6.4 |
| log barrier (09-01) | strategy-signal docker log | **OPEN (#1578)** — cleared mid-session this day, 14:40 IST | §6.2 |
| NEW-6 / NEW-13 / NEW-16 / NEW-10 / NEW-3 / NEW-1 / T1 / T7 / T2 | — | **carried unchanged** | — |

## 8 Honesty caveats

- **This is a backfill written two days late**, from the DB and market-data logs.
  **strategy-signal logs exist only from 14:40 IST onward** — the morning half of this session has
  no engine logs at all, and never will.
- **Regime is DIVERGENT and both reads are given: continuous eff 0.543 (MIXED) vs official 0.272
  (CHOP).** Continuous: 1m open 23,448.45 → freeze 23,389.25 pinned 15:15–15:28, range
  23,380.65–23,489.70. The official 15:29 CAS print is **23,477.80** — a **CAS delta of +88.55, by
  far the largest in the measured series** (the prior extremes are +200.95 early on, then nothing
  above +75 since). **That single print is what flips the label.** Under the continuous read — the
  primary, §3.33 — **G11's chop count stays 10**; a reader taking the official stamp alone would
  increment it to 11. This is the doctrine tension §3.33 exists to surface, and it should not be
  resolved silently in either direction.
- The **+88.55 CAS delta itself is `computed` and unexplained.** It is large enough to be worth a
  look, and one print is not a pattern.
- Counterfactuals are joined on `rejection_id`, so leg attribution is exact — but the P&L is the
  SHADOW fill model and clusters fan out across variants (champion reference where available;
  the 12:15 veto rows have no champion row, so composite-055 is quoted and labelled).
- The funded book is **n=2, and both fills are the same 14:09 decision on two roots.** "Second
  consecutive green day" is an observation about two days of ≤3 trades each.
- Read-only run: SELECTs, `docker logs --tail`, `docker inspect`. No restarts, deploys, writes,
  config changes or republishes.
