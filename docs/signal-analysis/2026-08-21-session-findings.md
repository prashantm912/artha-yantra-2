# Session findings — 2026-08-21 (data date)

Analysis date: 2026-08-21 EOD (scheduled post-market agent, run ~15:45–16:05 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,210** (bounds
`2026-08-21T09:15:00+05:30`…`15:40`; rows 09:19–14:58), signals fired **0**, paper trades **0**,
shadow opens **0** — **the first genuine zero-fire clean session on record** (08-10 was an outage,
not a quiet day).

Session character: **Friday, post-BSE-expiry (no expiry either exchange)** · VIX 26 distinct values
(alive) · tiny-range drift-down (official o 24,284.05 → c 24,252.00 = −0.13% on a **0.32% range**;
continuous freeze 24,234.75 = −0.20%; continuous eff **0.638 = trend-down** vs official 0.415 =
mixed — **5th straddle**; CAS delta **+17.25**) · signal contract `NFO:NIFTY26AUGFUT`
(log-confirmed, 1,213 mentions) · **session itself CLEAN — first in-session no-outage day in 5**;
⚠️ a **POST-close Kite outage began 15:34:58 IST** (ticker disconnect; kite-rest circuit open from
~15:47, still down at 15:57 when this run's greps completed) — same environmental class as
08-19/08-20, evening 18:4x batch chain at risk (§6.2).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,210 — **36 of 38 scalpers** (silent: `scalp-hero-zero-nifty` + `scalp-hero-zero-sensex-niftyoi` only — the golden-crossover PE pair RETURNED on a mixed CE/PE day; `premium_skew` n=0 again) |
| eval outcomes | chart-gate-failed 1,922 · confluence-blocked 1,210 · composite-below-threshold 438 · **fired 0** · discipline-paused 0 |
| fired reconciliation (§3.36) | 0 fired = 0 emitted + 0 suppressed (suppression grep 0) — trivially reconciled |
| coverage | 26 of 27 15-min buckets 09:15–14:45 populated; **09:45 bucket EMPTY — chart-gate class, NOT a stall** (eval buckets 09:45–09:57 show 36 evals each, chart-gate-failed 20, confluence-blocked 0 — engine alive, gate simply owned that window); 14:45 last rejection 14:58 |
| boot health | boot 08:19:01 IST install 0/38-unresolved transient → **38/0/0 at 08:21:42 (~161 s — 4th consecutive above the 71–132 s band: 139/105/163/161 — watch continues)**. Mid-session reconcile 08:40:36 clean 38/0/0. `subscriber_health_events` 0 rows |
| paper (funded) | **0 entries, 0 exits.** Sub-accounts: 0 of 5 entered — §3.30 trivially no freeze; governors and risk gate blocked nothing (nothing reached them) |

## 2 Rail findings

- **§2.2 chain-proximity: post-BSE-expiry Friday CLEAN — `strike-pick` 0 fails on BOTH roots.**
  The Friday leg of the cluster shape is now **3 saturated / 2 clean** (07-24 550 / 07-31 374 /
  08-07 350 / 08-14 14 / **08-21 0**) — two consecutive clean Fridays. Consistent with the 08-14
  reframe: the fails track whether the fresh front weekly's delta-band strikes price inside the
  static premium band, not the calendar itself. Next cluster window: Mon/Tue 08-24/25 (NSE).
- **volume-floor first-block 806/1,210 (66.6%) — highest binding share recorded** (prior high
  62.9% on 08-19); banded and honest (all-fails avg operand 12,791 vs avg threshold 23,837 —
  a thin tape under a p75-class banded floor; zero flat thresholds).
- **`confluence-composite` never passed — 958 scored, 0 ≥ 0.600 (max 0.5585), the first
  zero-pass session on record** (prior low 12.4% on 08-18). Cause is regime, not defect — §3.
- First-block tail: time-window 206 · rsi-band 96 (all-fails 454, avg RSI 45.1 = mid-band chop) ·
  time-of-day-preference 24 · option-side-constraint 22 · confluence-composite 10 · volume-pump /
  pct-price-move / two-candle 8 each · oi-cross-required 6 · psar-durability / directional-change /
  divergence-vol 4 each · call-put-delta-filter / max-oi-sr-gate 2 each (15 distinct rails).

## 3 Composite + dots

- Distribution (scored n=958): **pass mass 0/958 (0.0%)** — max rejected 0.5585; CE/PE mixed
  through the mid-buckets (0.4 bucket: 132 CE / 166 PE). Zero fires is the mechanical outcome.
- **`vwap` (w 2.5 — the heaviest dot) 0% on BOTH sides — the §3.28 never-crosses class, proven
  on the operands**: `close`/`vwap` both alive (50/76 distinct values), but max |close−vwap|
  = **9.71 bps all session** (p50 3.18, min 0.03) against the dot's ≥15 bps rule — price never
  left ±10 bps of VWAP on any context-bearing bar. Not a defect: the operand moved, the
  threshold was simply unreachable on a 0.32%-range day. With 2.5/Σw dead on both sides plus
  the standing dead IV bloc, the composite ceiling sat below 0.600 all session — this one
  regime fact explains the zero-pass, zero-fire, zero-shadow day end to end.
- Dot support (complete session, n=958 unless noted): `iv_pair` **0% (24th — T3, owner)** ·
  `vwap` **0% (never-crosses, above)** · `iv_abs_band` **0% (n=146 — 2nd day the frozen daily
  stamp landed outside the 10–12 band; coin-flip class, 20th session)** · `iv_rank` 0%
  (withheld, standing) · iv_slope 2.7% (n=146) · oi_spurt 3.3% · rsi 9.8% · volume 15.9% ·
  breadth 16.1% · trending_cross 33.4% · sentiment_slope 44.5% · futures_oi 47.2% · vix 49.9% ·
  basis 50.1% · underlying_oi 51.1% · sentiment 56.2% · psar 71.8% · drastic_oi 87.1% · vwma
  92.9% · supertrend 100% · premium_skew — (n=0, hero-zero silent).
- **§3.28 breadth — FIRST mid-range day: the operand STRADDLED the threshold intra-session.**
  CE side dead (advances 17–23 vs `>32`, 0/480); PE side **crossed the line during the session**
  (declines 25–34 around `>32` → 154/478 = 32.2%). After eight sessions of saturated-side
  step-function behaviour, this is the first session where `>32` acted as a genuine per-bar
  discriminator on one side. T30 evidence, noted; one session.
- OI bloc fully live on BOTH roots: quadrants NEUTRAL **0/958** contextful (SHORT_BUILDUP 346 /
  LONG_BUILDUP 250 / SHORT_COVERING 212 / LONG_UNWINDING 150; 252 blank = context-less
  pre-fetch class); `futures_oi_snapshots` 25,737 snaps / **373 of ~375 minutes**.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,210/1,210 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,210/1,210 | by design (un-armed) |
| `fiiLongPct` | NULL 252 = exactly the context-less rows | daily EOD stamp, alive |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 20th) |
| vix | 32 distinct | alive |
| ceIvAvg6 / skew / basis | 40 / 76 / 67 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 4th consecutive |
| §3.17 canary | **2 WARNs + 0 straddles**: 09:34/09:40 IST on buckets 09:30/09:33 — shortfall **−975 then +975** (15 lots), a textbook ± pair reported unpaired by the documented boot-fresh empty-lot-cache class (first pair after the 08:19 boot). **Zero mid-session WARNs after 09:40 on the first no-outage session — the NEW-6 clean-day observation finally landed: no unexplained unpaired WARNs occurred** (§6.1) | benign |
| signal-future capture | 375/375 min (KITE 362 + TICK_AGG 13), 0 misaligned | ✓ |
| Kite session | validated 15:54 IST | ✓ |
| market-data canary | GREEN, 93 tokens (15:59 read — market closed, so it does not see the post-close ticker outage) | ✓ (with §6.2 caveat) |

## 5 Shadow-book outcomes + counterfactuals

**Zero shadow opens, zero closes, all variants** — the composite never passed, so no rejection was
shadow-eligible. All-time totals unchanged: champion **−₹208,836.45** (609 closes) ·
composite-055 −₹4,591.02 · vol-12k5 −₹44,983.14 · vol-off −₹60,621.33. Loosening prior stands at
**13 observations / 12 losses / 1 win**.

**Per-rail counterfactual P&L (owner directive 2026-08-20, champion all-time NET)** — first
scheduled report; today's delta is zero (no closes), so this IS the 08-20 baseline re-measured,
byte-identical: `volume-floor` 340 refused / **−₹95,241.23** (avg −₹280.12) · `rsi-band` 73 /
**−₹64,258.34** (avg −₹880.25) · `volume-pump` 27 / −₹10,775.32 · `two-candle` 28 / −₹9,161.48 ·
`call-put-delta-filter` 7 / −₹11,149.59 · … · `confluence-composite` 13 / **+₹6,152.40** (the one
positive rail — concentration caveat stands: 2 trades carried it, −₹3,053 without them; n too
small for any gate change). Root split unchanged: SENSEX −₹73.26/trade (255) vs NIFTY
−₹537.16/trade (354) — gap did not move (no new closes).

**§4.2 counterfactuals: NONE to run** — the would-have-fired set (composite ≥ threshold) is empty;
no rail was ever the sole veto of an otherwise-firing row. The gates refused nothing the composite
would have taken.

## 6 New data points / anomalies

### 6.1 NEW-6 resolved-favourably observation: first clean no-outage session, zero unexplained WARNs

08-19 left two unexplained unpaired mid-session WARNs (14:54 −910, 15:09 −8,970) on watch,
untestable on 08-20 (outage day). Today: **no in-session Kite outage** (the disconnect came
post-close) and the only WARNs were the 09:34/09:40 boot-fresh ± pair. **Zero unpaired
mid-session WARNs on a clean day** — one favourable observation; keep NEW-6 on watch for one more
clean session before closing (the 08-19 pair remains unexplained retrospectively).

### 6.2 Post-close Kite outage ongoing at run time — 5th consecutive outage day, first fully-clean session

Ticker disconnect **15:34:58 IST**, kite-rest circuit open from ~15:47 (EOD prefetch of BFO
option 1d bars failing with `kite-rest circuit open; serving cached data`), **no reconnect line
by 15:57** when greps completed. Trading impact zero (post-close); the **16:05
bhavcopy-close-prefetch and the 18:45–18:59 evening batch chain are at risk** if it persists —
the 08:45 ingest-coverage canary tomorrow is the designed detector, and `marketdata.ingest_runs`
the check. Same host-environmental class as the 08-19 triple + 08-20 single (register series);
nothing to fix in-repo, no action taken (read-only run). Note the pattern shift: the outage
class has now missed the session window once — today's session itself was the first clean one
in 5 days.

### 6.3 §3.29 audit — day delta +1, never-fired set unchanged

`close_reason` delta since the 08-20 run: TRAILING_STOP 20→21 (a swing-book close in the 08-20
evening batch, post-run). Never-fired unchanged: `take_profit premium_pct` (36 armed) ·
`signal_exit` (38) · `square_off` (2) · tag `oi-confluence-exit` (8). INDETERMINATE:
`trailing_stop atr_multiple` (2). Armed-path table re-verified against published configs —
identical 10 rows to 08-20.

### 6.4 §3.34 heat-gate — not evaluable (zero-fire session)

0 funded entries → the gate never ran; the grep proves nothing today (the §3.34 zero-fire case).
Streak unchanged at 7 consecutive funded-day PASSes. N23-A stands.

### 6.5 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines — the identical standing set** to
  08-17…08-20 (keyword-class false positives; no new line). Ledger consistent.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

### 6.6 §3.30 freeze telemetry — no entries, no freezes

0 of 5 sub-accounts entered (nothing fired). Trend: 08-17 3/5 · 08-18 2/5 · 08-19 2/5 · 08-20
3/5 · **08-21 0/5-trivial**. No governor or risk-gate action possible.

### 6.7 T10 stale OPEN paper positions

**18 OPEN (+1 vs 08-20's 17** — a swing entry from the 08:35 morning catch-up), oldest 07-08.
Chronic, owner-gated, as-of ~15:50 IST (pre-evening batch).

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-6 (08-19) | unpaired mid-session §3.17 WARNs w/o reconnect | **WATCH — first favourable clean-day observation (0 unexplained WARNs); hold for one more** | §6.1 |
| watch | `strike-pick` chain-proximity | **WATCH — post-expiry Friday CLEAN (0 both roots), Friday leg now 3-of-5** | §2; next window Mon/Tue 08-24/25 (NSE) |
| NEW-5 (08-14) | Redis candles.1m subscription drop | **CLOSED-CLEAN — no recurrence in the 5-session watch window (08-14…08-21)** | 0 stall rows all week; single in-process save stands as the only observation |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no mid-session deploy today; §3.38 unchallenged |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | not reached (zero-fire day) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | not evaluable today (§6.4); 7-PASS streak intact |
| T30 | `breadth` dot `>32` | **OPEN — first mid-range observation**: PE declines straddled the line intra-session (25–34, 32.2% support), CE dead (17–23) | §3 — first session the threshold discriminated per-bar on either side |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | banded, zero flat; binding share record 66.6% on a thin tape; loosening prior 13/12/1 |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (20th); `iv_abs_band` 0% 2nd day (stamp outside band) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (24th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs 0 straddles, both = boot-fresh first-pair class (±975); clean thereafter |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | no new observation (0 challenger rows) |
| T7 | composite threshold | **REJECTED — carried** | no new observation; note today even 0.55 would have passed nothing above 0.5585's neighbours — a floor change was irrelevant to the zero-fire day |
| T10 | stale OPEN paper positions | **OWNER — chronic** | 18 OPEN (+1 swing entry), oldest 07-08 |
| T8/T26 | latency | OPEN (data) | no entries → no sample today |
| T2 | `iv_rank` | carried, not open | NULL 1,210/1,210 |

## 8 Honesty caveats

- **This run executed ~15:45–16:05 IST** — before the 16:05 prefetch and 18:4x batch jobs; T10
  and ingest statements are as-of-run-time, and §6.2's outage was STILL OPEN at write time —
  tomorrow's run must check `marketdata.ingest_runs` for tonight's batch outcomes.
- Regime stamped from the CONTINUOUS session (§3.33a): eff **0.638 = trend-down**; official
  reads 0.415 = mixed — **5th straddle** (CAS delta +17.25, symmetric-noise series holds).
  ⚠️ The trend-down label sits on a **0.32%-range day with price glued to VWAP (≤9.71 bps)** —
  efficiency is scale-free, so a slow one-way drift on a tiny range earns "trend"; the label is
  correct under the doctrine but the tape traded like quiet chop. Recorded as-is; the
  cut-rederivation note in rollup.md (~every 40 sessions) is where a range overlay would land.
- Zero-fire session: no counterfactuals, no shadow rows, no latency sample — most per-session
  evidence tables are legitimately empty rather than missing.
- All log-derived checks ran while both containers were up (no recreate observed by run end);
  boot line read from `docker logs` directly and corroborated by `strategy.engine_reloads`.
- Read-only run: SELECTs, in-container health GETs, log greps. No restarts, deploys, writes,
  config changes, or republishes. Docs-only PR: this file + rollup rows.
