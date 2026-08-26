# Session findings — 2026-08-26 (data date)

Analysis date: 2026-08-26 EOD (scheduled post-market agent, run ~15:50–16:20 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,494** (bounds
`2026-08-26T09:15:00+05:30`…`15:40`; rows 09:19–15:19), signals fired **0**, paper trades **0**,
shadow closes **50** across 4 variants (champion 39).

Session character: **Wednesday, no expiry on either root** · down day (official o 24,341.95 →
c 24,207.75 = −0.55% on a 0.70% range, eff **0.785 = trend**; continuous freeze 24,276.90 =
−0.27% on a 0.51% continuous range, continuous eff **0.522 = mixed** — **7th straddle**, stamp =
MIXED per §3.33a; **CAS delta −69.15 — the largest NEGATIVE print in the series, and the CAS
print IS the daily low**) · signal contract **`NFO:NIFTY26SEPFUT`** (1,496 log mentions) ·
**overnight HOST downtime** (stack-outage-register class, benign): boot **08:41:39 IST**, morning
batch chain ran as the boot catch-up at 08:41 — all SUCCESS; **zero in-session outage lines,
2nd consecutive clean in-session day**.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,494 — **36 of 38** scalpers (silent: the **golden-crossover CE pair** — `scalp-golden-crossover-nifty`, `scalp-golden-crossover-sensex-niftyoi` — chart-gate class on a down tape, both evaluated 105× with `chart-gate-failed`, the exact mirror of the PE-pair silences on up days); `premium_skew` n=34 |
| eval outcomes | chart-gate-failed 1,988 · confluence-blocked 1,494 · composite-below-threshold 88 · **fired 0** · discipline-paused 0 |
| fired reconciliation (§3.36) | 0 fired = 0 emitted + 0 risk-gate suppressions (grep 0) — trivially reconciled |
| coverage | **25 of 25** 15-min buckets 09:15–15:15 populated — no interior holes; `subscriber_health_events` 0 rows |
| boot health | boot 08:41:39 IST (post host-downtime), 0/38-unresolved transient → **38/0/0 at 08:42:48 (~69 s — in-band)**; boot line `loaded 38 published strategies (0 dropped, 0 failed)` |
| paper (funded) | 0 entries, 0 closes — 270 composite passes but every one died at an earlier rail or the 60m-bias veto (§2/§6.1); nothing reached emission |

## 2 Rail findings

- **volume-floor first-block 906/1,494 (60.6%)** — banded and honest (56 distinct thresholds,
  8,434–125,726; zero flat). §3.14 tag check: `relative-volume-floor` armed on 38/38 enabled.
- **`strike-pick` 0 fails on BOTH roots — Wednesday-clean again** (every observed Wednesday clean;
  Mon/Tue NSE series closed at 235/604/322/452/531 → 0). Next window: Thu–Fri around the BSE
  monthly expiry (SENSEX26AUG expires Thu 08-27 — expect the SENSEX-root cluster).
- First-block tail: rsi-band 248 (down-tape RSI, 2nd-largest — unusual) · time-window 214 ·
  time-of-day-preference 42 · pct-price-move 12 · volume-pump 12 · two-candle 12 ·
  divergence-vol-gate 12 · **confluence-composite 10** (see §6.1 — the 60m-bias veto, NOT a score
  shortfall) · supertrend-15m 8 · oi-cross-required 8 · max-oi-sr-gate 6 ·
  directional-change-gate 2 · option-side-constraint 2 (14 distinct rails).

## 3 Composite + dots

- **OI bloc fully LIVE again** (post-expiry Wednesday): quadrants NEUTRAL **0/1,236**, spurt NULL
  0/1,236, basis LIVE 1,236/1,236; capture healthy (25,875 snaps / 375 of ~375 minutes).
- **Composite passes 270 of 1,236 scored (21.8%) — 258 PE / 12 CE** (down-day PE dominance);
  max 0.8511. `composite-below-threshold` evals 88.
- Live-dot support (complete session, n=1,236 unless noted): `iv_abs_band` 0% (n=182, **5th day** —
  atmIv stamp **0.098547**, below the 10–12 band) · `iv_rank` 0% (withheld, standing) · `iv_pair`
  0% (**27th** — T3, owner) · oi_spurt 8.6% · vix 8.7% · basis 8.7% · rsi 16.3% · volume 26.7% ·
  breadth 27.2% · sentiment_slope 44.3% · trending_cross 47.6% · futures_oi 52.1% · vwap 60.8% ·
  underlying_oi 61.0% · iv_slope 61.5% (n=182) · sentiment 67.3% · psar 70.2% · vwma 90.1% ·
  drastic_oi 92.7% · supertrend 97.2% · premium_skew 100% (n=34).
- **§3.28 breadth (T30) — 4th consecutive mid-range day, side-aware:** CE dead (advances 20–27,
  never crossed `>32`, 0/108); PE **336/1,128 = 29.8%** — declines spanned 29–35, straddling the
  line intra-session: genuine per-bar discrimination on the PE side.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,494/1,494 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,494/1,494 | by design (un-armed) |
| `fiiLongPct` | live on all 1,236 contextful rows (null only on the 258 context-less) | healthy — participant OI current |
| `atmIv` | 1 distinct (0.098547) | frozen daily stamp — correct (G12/T28, 23rd) |
| vix / ceIvAvg6 / skew / basis | 15 / 59 / 110 / 95 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 7th consecutive |
| §3.17 canary | **1 WARN + 1 straddle.** WARN 09:19:59 IST on the **opening bucket 09:15** — shortfall −2,925 (45 lots, 1.7% of the 174,720 bucket), UNPAIRED: the boot-fresh first-non-benign-event class (08-25 §6.1 mechanism — boot 08:41, lot cache empty at first read, fail-closed). Straddle ±975 at 10:45/10:48 IST suppressed correctly (cache warm by then) — the suppressor's first observed same-day miss-then-work sequence. No reconnect lines all session | benign — both explained |
| signal-future capture | **375/375 min** aligned 1m on `NIFTY26SEPFUT` (also OCT/NOV) | ✓ |
| Kite session | validated 15:56 IST (canary GREEN at run time) | ✓ |
| morning ingest | boot catch-up 08:41: BHAVCOPY / NSE_FII_DII / NSE_PARTICIPANT_OI / NSE_FII_DERIVATIVE all SUCCESS; INSTRUMENT_SYNC SUCCESS 09:05; OPTIONS_SNAPSHOT_CAPTURE SUCCESS | ✓ (the host-downtime 6-min shift class — jobs ran at boot instead of 08:3x) |

## 5 Shadow-book outcomes + counterfactuals

**Champion: 39 closes, 12 net wins, −978.95 pts, −₹40,906.58 — the book's 3rd-worst net day on
record** (worse only: 07-30 −₹58,233.05, 07-15 −₹43,637.67). Deduped (§3.24): **11
`(bar, leg, entry)` clusters on 6 bar times** — the 09:24/09:45 CE entries (NIFTY 24250CE +
SENSEX 77700CE ahead of the down move) carried −₹36,007 of it; the one green cluster was the
10:21 `SENSEX26AUG78200PE` **TAKE_PROFIT +₹15,234.05** (7-slug multi-exit cluster,
STRUCTURAL_STOP vs TAKE_PROFIT — exit-experiment class). All-time champion **−₹218,833.21**
(676 closes, 245 net wins); cumulative points −1,985.25.

**Challenger-only class: 11 observations, 2 wins / 9 losses** — composite-055 took 3 (12:03
SENSEX78100PE SQUARE_OFF −₹1,782.15; the two 13:48 60m-bias-vetoed legs, both STRUCTURAL_STOP,
−₹463.85 / −₹384.85), vol-12k5 took 4 (10:24 SENSEX78200PE **+₹305.83 WIN**, 10:24 NIFTY24550PE
−₹293.94, the 13:48 pair), vol-off took 4 (the 10:24 pair incl. the same WIN, 13:21 pair
−₹1,005.42 / −₹1,203.73). **Loosening ledger moves 15/12/3 → 26 measured / 21 losses / 5 wins.**
All-time: composite-055 −₹2,650.63 · vol-12k5 −₹39,869.86 · vol-off −₹56,868.50.

**Per-rail counterfactual P&L (owner directive 08-20), all-time champion NET:** volume-floor
376 / **−₹143,098.24** (today −₹42,196.91 on 16 closes — the CE-morning clusters) · rsi-band 92 /
−₹48,980.82 (**today +₹10,706.28 on 18** — the TP cluster sits here) · `confluence-composite`
15 / **+₹8,840.45** (today −₹1,883.19; still the only materially positive rail — but see §6.1:
today's would-have-fired set for this rail is 0W/8L, the §3.13 bucket-vs-set divergence again) ·
pct-price-move 38 / +₹5,432.42 · supertrend-15m 7 / +₹3,643.32 · oi-cross-required 21 /
+₹3,329.71. **Root split FLIPPED BACK: SENSEX −₹9.08/trade (297) vs NIFTY −₹570.28 (379)** —
08-24's SENSEX-positive read lasted one measured day.

**§4.2 manual counterfactuals — the 60m-bias-vetoed set (§6.1): 8 deduped legs, 0W/8L,
≈ −303.35 pts gross.** Model: +35%/−25% premium brackets, 30-min time stop (a HARNESS modelling
choice, §3.16 — not "the armed fleet-wide stop"), 15:12 square-off; 6-min snapshot sampling.

| bar | leg | entry | exit (model) | pts | result |
|---|---|---|---|---|---|
| 12:03 | SENSEX26AUG78100PE | 440.35 | 389.50 time-stop | −50.85 | LOSE |
| 12:09 | SENSEX26AUG78100PE | 432.50 | 401.55 time-stop | −30.95 | LOSE |
| 12:12 | SENSEX26AUG78100PE | 445.40 | 401.55 time-stop | −43.85 | LOSE |
| 12:33 | SENSEX26AUG78100PE | 405.60 | 395.00 time-stop | −10.60 | LOSE |
| 13:48 | NIFTY2690124500PE | 215.90 | 195.15 time-stop | −20.75 | LOSE |
| 13:48 | SENSEX26AUG78000PE | 382.30 | 302.60 time-stop | −79.70 | LOSE |
| 13:57 | NIFTY2690124550PE | 252.20 | 231.20 time-stop | −21.00 | LOSE |
| 13:57 | SENSEX26AUG78000PE | 377.30 | 331.65 time-stop | −45.65 | LOSE |

No leg touched either bracket inside its window; every one resolved at the modelled time stop.
Corroboration: the challenger books actually traded the 12:03 and both 13:48 legs and lost on
all of them. Counterfactual W/L: **0W/8L**.

## 6 New data points / anomalies

### 6.1 T14 anomaly SETTLED — "composite-blocked with a PASSING score" is the 60m-bias VETO

The rollup watchlist has carried since 2026-07-20: *"`confluence-composite` rows logged with a
POSITIVE blocking margin … probably the optional-gate mechanic — **unverified in code**"* (T14).
Today's rows settle it, from the data: all 10 first-block `confluence-composite` rows carry
composite **above** threshold (0.6383–0.8235 vs 0.6) with `margin: null` and reason
**`"60m bias opposes the side"`** — the hourly-bias veto that lives inside the
confluence-composite rail. The stored operand is the full composite; the failing test is the
bias check, so the diagnostic is honest but the margin is null, not negative (T14's proposed
`blocking_margin < 0` assert would be wrong for this class). Consequence for §3.5: a
would-have-fired query keyed on `composite >= threshold` DOES legitimately return these rows —
they are exactly the "blocked ONLY by the veto" class, and today they were the ENTIRE sole-blocker
set (8 deduped legs, §5). **The veto's first measured day as sole blocker: it refused 8 losers
and zero winners (≈ +303 pts saved).** Promoted to README §3.39.

### 6.2 H31 measurement (chip `task_de01f6bb` — "open until tomorrow's measurement rules")

The day-context read-timeout rate with the TTL raise deployed, measured over today's full
session: **5 `insight trust read day-context FAILED` lines** at 09:45:02 / 11:30:02 / 13:15:02 /
14:30:02 / 15:30:02 IST against the 15-min sweep's ~28 fires = **~18% failure rate, spread not
clustered** — down from 25-of-28 (~89%) on the pre-fix session, but decisively NOT zero.
Consistent with the ledger's falsified-prediction analysis (write-based expiry → alternation at
best; failures cache nothing). **H31 = REDUCED, NOT FIXED, now with a post-fix rate.** Ruling on
the chip stays with the Architect; this run only supplies the measurement.

### 6.3 §3.29 audit — zero delta; never-fired set unchanged

No funded closes today. Fired vocabulary since 07-01 unchanged: TRAILING_STOP 22 ·
STRUCTURAL_STOP 18 · TIME_STOP 17 · STOP_LOSS 8 · MANUAL 2. Armed-path table re-verified —
identical 10 rows + tag `oi-confluence-exit` 8. Never-fired unchanged: `take_profit premium_pct`
(36, zero funded TP closes since 07-01 — while the shadow book banked its 4th TP today) ·
`signal_exit` (38) · `square_off` (2) · tag `oi-confluence-exit` (8). INDETERMINATE:
`trailing_stop atr_multiple` (2), `stop_loss atr_multiple` (2).

### 6.4 §3.34 heat-gate — not evaluable (zero funded fires); grep 0

### 6.5 §3.30 freeze telemetry — trivially 0 of 5 (zero entries)

Trend: 08-19 2/5 · 08-20 3/5 · 08-21 0/5-trivial · 08-24 3/5 by 13:40 · 08-25 0/5-trivial ·
**08-26 0/5-trivial**.

### 6.6 NEW-8 trail-should-have-fired watch — 3rd clean measurement

run_date 2026-08-26 (boot catch-up pass): 23 sell_decisions rows, 23 `stop_level`, 20
`trail_level` — identical operand shape to 08-25 (static swing book); none breached. Clean.

### 6.7 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **12 REVIEW lines (was 11)** — 7×[A] + 5×[B]. The new
  [A] is **`task_de01f6bb` (H31)**: the ledger deliberately holds it OPEN ("stays open until
  tomorrow's measurement rules") while the H31 row text pattern-matches as closed — the standing
  keyword-class false positive, and the underlying tension is real and now measured (§6.2). No
  edit made; the Architect rules on the chip with §6.2's rate.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-9 (08-26) | 60m-bias veto (inside confluence-composite) | **NEW OBSERVATION — first sole-blocker day: refused 8/8 losers (≈ +303 pts saved). NOT a tuning candidate — evidence the veto earns its keep; logged so the per-rail bucket's +₹8.8k never argues for removing it (§3.13)** | §5/§6.1 |
| watch | `strike-pick` chain-proximity | **WATCH** — Wednesday-clean (0 both roots); BSE MONTHLY expiry tomorrow (Thu 08-27) — expect the SENSEX-root cluster | §2 |
| NEW-7 (08-24) | fii intra-day retry #1450 | **SHIPPED, still unexercised** — boot catch-up SUCCESS again | §4 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no deploy today |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | not reached (0 fires) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | not evaluable |
| T30 | `breadth` dot `>32` | **OPEN — 4th consecutive mid-range day**: CE dead (adv 20–27), PE 29.8% with declines straddling the line intra-session (genuine discrimination) | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding 60.6%, banded (56 thresholds), zero flat; loosening ledger now **26/21/5** — today added 9 losses / 2 wins |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct = 0.098547 (23rd); `iv_abs_band` 0% 5th day |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (27th session) |
| T23 | partial-bucket tolerance | **OPEN** | 1 WARN + 1 straddle, both explained (opening-bucket first-event class; suppressor worked once warm) |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5 challenger-only today: 1W/3L, −₹836.81 |
| T7 | composite threshold | **REJECTED — carried** | composite-055 challenger-only today: 0W/3L, −₹2,630.85 (all-time −₹2,650.63 — the ≈break-even read lasted one day) |
| NEW-8 (08-24) | trail-should-have-fired watch | **STANDING — 3rd clean measurement** | §6.6 |
| T8/T26 | latency | OPEN (data) | shadow entry latency p50 79 s / p95 81 s (structural class); emit latency not measurable (0 emissions) |
| T2 | `iv_rank` | carried, not open | NULL 1,494/1,494 |

## 8 Honesty caveats

- **This run executed ~15:50–16:20 IST** — before the 18:4x evening batch, the 18:52/18:53 swing
  settles (EXITS-only — 0 candidates AND 0 exits is the normal correct outcome, per H27) and the
  18:59 buyable-alerts; tonight's ingest outcomes are tomorrow's verifications.
- Regime stamped from the CONTINUOUS session (§3.33a): eff **0.522 = mixed**; official 0.785 =
  trend — **7th straddle**; CAS delta **−69.15** (largest negative; the CAS print IS the daily
  low). Not a G11 chop day (count unchanged at 7).
- §5's counterfactual table models a uniform 30-min time stop (harness parameter, §3.16), prices
  off 6-min-sampled 3-min snapshots (a 1m bracket touch can be missed), no slippage/fees on the
  manual legs; the challenger-book corroboration carries real engine fills + costs.
- §6.2's ~28-fire denominator is the cron schedule, not a per-fire log count (successes do not
  log a line on this path); the 5 failures are directly observed, the rate is derived.
- The champion book's −₹40,906.58 is a fan-out figure over 11 deduped events on 6 bar times
  (§3.24) — the effective independent sample is ~6, and two morning CE clusters carry −₹36k of it.
- Read-only run: SELECTs, log greps, in-container health reads. No restarts, deploys, writes,
  config changes, or republishes. Docs-only PR: this file + rollup rows + a README §3.39
  addition.
