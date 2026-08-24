# Session findings — 2026-08-24 (data date)

Analysis date: 2026-08-24 EOD (scheduled post-market agent, run ~15:50–16:15 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,196** (bounds
`2026-08-24T09:15:00+05:30`…`15:40`; rows 09:19–15:19), signals fired **4** (+ 3 exit signals),
paper trades **4 opens / 3 closes (one pyramid average-in)**, shadow champion closes **28**.

Session character: **Monday, eve of the NSE MONTHLY expiry (Tue 08-25)** · down day (official
o 24,285.05 → c 24,219.05 = −0.27% on a 0.69% range, eff 0.391 = mixed; continuous freeze
24,182.80 = −0.42%, continuous eff **0.606 = mixed** — both closes agree on the label, **no
straddle**; CAS delta **+36.25**) · signal contract **`NFO:NIFTY26SEPFUT` — the monthly ROLL
happened at the ~08:40 re-resolve** (log-confirmed, 1,205 mentions; never compare volume
thresholds across the roll, §3.18) · ⚠️ **mid-session host-network outage 09:58–~10:22 IST**
(§6.1) — 6th consecutive outage day, and the class moved back INSIDE the session window.

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,196 — **36 of 38 scalpers** (silent: the `golden-crossover` CE pair — nifty + sensex-niftyoi — chart-gated on a down tape; their PE twins fired the day's actual signals; `premium_skew` n=34, hero-zero back) |
| eval outcomes | chart-gate-failed 1,888 · confluence-blocked 1,196 · composite-below-threshold 184 · **fired 4** · discipline-paused 0 |
| fired reconciliation (§3.36) | 4 fired evals = 4 emitted BUY signals (ids 251/253/255/256) + 0 risk-gate suppressions (grep 0); the 3 SELL rows are exit signals — reconciled |
| coverage | 24 of 25 15-min buckets 09:15–15:15 populated; **10:00 bucket EMPTY = the outage window, NOT a stall** (§6.1: eval buckets 10:00–10:21 all-zero because no live bars arrived; engine self-healed at 10:23, clean reload 10:24:15) |
| boot health | boot 08:33:06 IST, 0/38-unresolved transient → **38/0/0 at 08:34:47 (~100 s — BACK INSIDE the 71–132 s band after 4 consecutive above: 139/105/163/161/100; watch relaxes)**. Reconciles 08:40 + 10:24 clean 38/0/0. `subscriber_health_events` 0 rows |
| paper (funded) | **4 OPENED events → 3 positions, all closed STRUCTURAL_STOP, day −₹1,950.26**: sub-1 12:52 `SENSEX26AUG78000PE` 20 @713.80 → −₹9.82 (12:55); sub-2 13:07 `SENSEX26AUG77900PE` 20 @716.45 → −₹1,364.01 (13:31); sub-3 13:37 `SENSEX26AUG77900PE` **40** @680.75 → −₹576.43 (13:40) — the 13:36 bar had `golden-crossover-…-pe` + `gap-theory-…-pe` converge on ONE leg and **pyramid-average into one position** (G14 class, 2 slugs) |

## 2 Rail findings

- **§2.2 chain-proximity: the Mon/Tue NSE window LANDED — `strike-pick` 452 all-fails, 16 of 16
  NIFTY-rooted slugs, 0 SENSEX** (first-block only 4; the fails sit behind earlier rails). Series
  holds: NIFTY saturation clusters Mon–Tue around the NSE expiry (08-03 235 / 08-04 604 / 08-11
  322 / **08-24 452**); tomorrow is the monthly expiry day-of — expect S24 OI suppression on the
  NIFTY root (§3.19) plus continued strike-pick saturation.
- **volume-floor first-block 801/1,196 (67.0%) — new binding-share record** (66.6% on 08-21);
  banded and honest (blocking-margin avg −15,763; zero flat thresholds; §3.14 armed 38/38, pub
  07-28 ×2 + 08-13 ×36, unchanged).
- First-block tail: time-window 166 · rsi-band 143 · time-of-day-preference 18 · pct-price-move 10
  · two-candle 10 · volume-pump 8 · divergence-vol 8 · max-oi-sr 7 · oi-cross 6 · supertrend-15m 4
  · strike-pick 4 · option-side 4 · confluence-composite 3 · constituent-gate 2 ·
  call-put-delta-filter 2 (16 distinct rails).

## 3 Composite + dots

- Distribution (scored n=1,006): **pass mass 440/1,006 (43.7%) — from 0.0% on 08-21 in one
  session**; max 0.8511 on rejections, and signal id 253 fired at **composite 1.0000** (every
  evaluated dot supported — first perfect-composite fire observed by this routine). The regime
  whiplash (glued-to-VWAP Friday → trending Monday) is the whole story.
- Dot support (complete session, n=1,006 unless noted): `iv_pair` **0% (25th — T3, owner)** ·
  `iv_abs_band` **0% (n=164 — 3rd day the frozen daily stamp landed outside the 10–12 band)** ·
  `iv_rank` 0% (withheld, standing) · basis 2.0% · oi_spurt 4.4% · volume 20.4% · trending_cross
  36.6% · iv_slope 47.6% (n=164) · sentiment_slope 50.7% · underlying_oi 51.3% · futures_oi 57.6%
  · sentiment 64.1% · rsi 66.1% · psar 69.7% · breadth 76.9% · drastic_oi 79.6% · vwma 80.1% ·
  **vwap 81.9% (0% on 08-21 — the never-crosses day was pure regime, as called)** · supertrend
  95.2% · vix 97.2% · premium_skew 100% (n=34).
- **§3.28 breadth — SECOND consecutive mid-range day (T30):** PE side straddled the line
  intra-session (declines 20–37 around `>32` → 774/986 = 78.5%), CE dead (n=20, advances 29–31).
  Two sessions running the threshold discriminates per-bar on the PE side.
- OI bloc fully live: quadrants NEUTRAL **0/1,006** contextful (LONG_UNWINDING 341 /
  SHORT_BUILDUP 226 / LONG_BUILDUP 226 / SHORT_COVERING 213; 190 context-less);
  `futures_oi_snapshots` 24,219 snaps / **351 of ~375 minutes** — the ~24 missing minutes are the
  outage window, not a capture defect.

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,196/1,196 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,196/1,196 | by design (un-armed) |
| `fiiLongPct` | **NULL 1,196/1,196 — NEW vs 08-21 (was null only on context-less rows)** | **outage-induced, not a feed defect** — §6.2; expected to self-heal at tonight's 18:45–18:46 batch |
| `atmIv` | 1 distinct | frozen daily stamp — correct (G12/T28, 21st) |
| vix / ceIvAvg6 / skew / basis | 40 / 60 / 102 / 95 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 5th consecutive (notable: the outage backfill wrote ALIGNED buckets) |
| §3.17 canary | **2 WARNs + 0 straddles**: 09:28/09:34 IST on buckets 09:24/09:27, shortfall **−1,885 then +1,885** (29 lots) — the boot-fresh empty-lot-cache first-pair class (boot 08:33). **Zero WARNs after 09:34 — including NO reconnect-inflation WARN after the 10:22 recovery** (the §3.17 08-19 class predicted one; none fired — noted, favourable) | benign |
| signal-future capture | **375/375 min** (KITE 338 + BACKFILL 25 + TICK_AGG 12) — the 09:58–10:22 gap fully repaired by aligned backfill | ✓ |
| Kite session | validated 15:58 IST | ✓ |
| market-data canary | GREEN, 73 tokens (16:01 read, market closed) | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Champion +₹30,909.82 net today (28 closes, 16 net wins, +1,548.80 pts) — the book's best net day
on record (prior: +₹10,299.48 on 08-20). ⚠️ Deduped (§3.24) it is 5 events, and ONE carries
everything: the 11:00 `SENSEX26AUG78200PE` TAKE_PROFIT cluster (8-slug fan-out, +₹4,571.24 each)
= +₹36,569.92 — 118% of the day's net. Without it the day is −₹5,660.10.** The other events:
11:30 NIFTY PE SQUARE_OFF +₹2,300.08; 11:36 SENSEX 78000PE mixed −₹6,314.10; 11:42 / 12:39
stops −₹594.48 / −₹1,051.60. All-time: champion **−₹177,926.63** (637 closes, 233 net wins) ·
composite-055 **−₹19.78** (≈ break-even for the first time) · vol-12k5 −₹39,033.05 · vol-off
−₹54,671.24.

**Challenger-only class (the true loosening delta): ONE distinct trade** — 11:21
`SENSEX26AUG78100PE` (golden-crossover-pe), SQUARE_OFF **+₹1,091.34**, taken by `vol-12k5` AND
`vol-off` (one variant-day observation each). composite-055's TP close was champion-shared (adds
no observation). **Loosening ledger: 15 measured / 12 losses / 3 wins** — both wins-with-n≥2 sit
on trend days; REJECTED statuses stand (a trend-day n=1 win is the known shape of the 1-in-13
exception, now 3-in-15; still nowhere near decision-grade).

**Per-rail counterfactual P&L (owner directive 08-20, champion all-time NET), movers vs the 08-21
baseline:** `volume-floor` 340→**360** refused / −₹95,241→**−₹100,901.33** (today's stops) ·
`rsi-band` 73→74 / −₹64,258→**−₹59,687.10** (a TP recovered ₹4,571) · `confluence-composite`
13→**14 / +₹10,723.64** (still the one positive rail — now **3 TP trades carry it**; n=14, not
decision-grade) · newly-surfaced positives: `pct-price-move` 38 / +₹5,432.42 ·
`oi-cross-required` 20 / +₹5,212.90 · `supertrend-15m` 7 / +₹3,643.32. **Root split FLIPPED:
SENSEX +₹36.10/trade (275) — positive for the first time (was −₹73.26)** vs NIFTY −₹518.94 (362);
today's SENSEX PE take-profit did it — watch whether it holds.

**§4.2 manual counterfactuals: none needed** — the composite-passing set is fully shadowed; the
risk-suppressed class is empty (0 suppressions); strike-pick fails (452) are the §3.27
non-shadowable class, all NIFTY-rooted, expiry-eve regime.

**Funded-vs-shadow exit disagreement, same entries:** the funded book stopped all 3 positions
(−₹1,950.26) while the shadow book's 11:36/12:39 entries on the same legs also stopped — models
agree today; the funded 13:37 pyramid (40 qty) lost less than 2× the single-lot stop because the
average-in lowered the basis (680.75 vs 716.45).

## 6 New data points / anomalies

### 6.1 Mid-session host-network outage 09:58–~10:22 IST — 6th consecutive outage day, back inside the session window

All outbound destinations failed together (register class): Kite WS
(`Failed to connect to 'ws.kite.trade:443'`, ticker disconnected 09:58:28), kite-rest circuit
open (options snapshots failing from 09:58:39), Telegram getUpdates EOF/null from 09:58:36, and
the external liveness-heartbeat ping ConnectException at 10:00. **Consequence: zero live bars →
`signal_eval_outcomes` all-zero 10:00–10:21 (7 buckets, ~22 min of no evaluation)** — a
receive-side starvation, environmental, self-healed: KITE bars resume 10:23, clean engine reload
10:24:15, Telegram recovered by ~10:20, heartbeat pinging 10:30. Candle repair complete (375/375,
aligned backfill, 0 misaligned); OI snapshots lost ~24 minutes. Trading impact: the outage window
produced no rejections/evals — every §3 table is net of those ~22 minutes. The 08-21 file's
"pattern shift: outage class missed the session window" lasted one day. Nothing to fix in-repo;
the external dead-man's-switch got a real gap to alarm on (its job — §ask-the-external-watcher:
the provider dashboard, not our logs, records whether it fired).

### 6.2 fiiLongPct dead ALL session — outage-induced ingest gap, self-heals tonight

The 08:32:58 boot-time catch-up ingest ran DURING the outage: `NSE_FII_DII`,
`NSE_PARTICIPANT_OI`, `NSE_FII_DERIVATIVE` all FAILURE (I/O errors). NSE publishes participant-OI
for trade-date T at ~T+1 evening, so Friday 08-21's file was due in that morning fetch; with it
missing, `marketdata.nse_eod_participant_oi` max trade_date = 08-20, and the gate's
`/fii-dii/long-short?from=<eodDate=08-21>` query returns empty → `fiiLongPct` AND `fiiBiasSign`
null on all 1,196 rows (fii rail degrades to pass, by design). **No intra-day retry exists — the
next attempt is tonight's 18:45–18:46 batch.** Tomorrow's run must confirm the 08-21 row landed
(the 08-45 ingest-coverage canary is the designed detector). Note the 08-21 evening chain itself
ran CLEAN (18:04–18:50 all SUCCESS) — Friday's post-close outage recovered before it; that watch
item resolves favourably.

### 6.3 §3.29 audit — day delta +3 STRUCTURAL_STOP; never-fired set unchanged

`close_reason` delta since the 08-21 run: STRUCTURAL_STOP 15→**18** (today's 3 funded stops —
the `stop_loss` non-premium basis exercised again). Never-fired unchanged: `take_profit
premium_pct` (36 armed, zero FUNDED TP closes since 07-01 — today's TAKE_PROFIT hits are SHADOW
closes, a different book) · `signal_exit` (38) · `square_off` (2) · tag `oi-confluence-exit` (8).
INDETERMINATE: `trailing_stop atr_multiple` (2), `stop_loss atr_multiple` (2). Armed-path table
re-verified — identical 10 rows.

### 6.4 §3.34 heat-gate — evaluable, PASS (8th consecutive funded-day)

3 funded entries, heat grep **0** (calls succeeded); `margin_pct` 0.00 on all rows = the known
long-option zero-SPAN shape — evaluability proven, coverage remains the open owner question
(N23-A).

### 6.5 §3.30 freeze telemetry — 3 of 5 frozen by 13:40 (task flag: ≥3 before 14:30 → YES)

Sub-1 entered 12:52, first-loss froze ~12:55 · sub-2 13:07 → ~13:31 · sub-3 13:37 (2 OPENED
events, the pyramid) → ~13:40. All three first-loss freezes; subs 4/5 never entered. Trend:
08-17 3/5 · 08-18 2/5 · 08-19 2/5 · 08-20 3/5 · 08-21 0/5-trivial · **08-24 3/5 by 13:40**.
`discipline-paused` 0 all session — nothing passed confluence after 13:39, so the freeze bound
nothing today (2 of 5 subs stayed available).

### 6.6 Emit latency (G8 family) — 17.2–20.2 s

Today's 4 entry emissions: 17,182–20,152 ms vs G8's measured 16.7–17.6 s band — the 13:06 signal
paid 20.2 s, first above-18 s observation. Shadow entry-stamp latency p50 77.5 s / p95 80.5 s =
the standing level (77–80 s every session since 08-11). One-line watch, not a new alarm.

### 6.7 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines — the identical standing set** since
  08-17 (keyword-class false positives; no new line). Ledger consistent.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

### 6.8 T10 stale OPEN paper positions

**18 OPEN (unchanged vs 08-21)**, oldest 07-08. Chronic, owner-gated, as-of ~16:00 IST
(pre-evening batch).

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-7 (08-24) | **fii ingest has no intra-day retry after a failed morning catch-up** | **PROPOSED (build, small)** — a failed 08:3x FII/participant fetch leaves the gate's fii inputs null ALL session with no retry until 18:45; today it was outage-induced and harmless (rail degrades to pass), but the same gap after a mere NSE-side hiccup would silently blind the fii bias every session it happens | §6.2 |
| NEW-6 (08-19) | unpaired mid-session §3.17 WARNs w/o reconnect | **WATCH — 2nd consecutive session with zero unexplained WARNs** (today even the reconnect-inflation class stayed silent); today is NOT a clean no-outage day, so the "one more clean session" condition is still unmet — hold | §4 |
| watch | `strike-pick` chain-proximity | **WATCH — Mon/Tue NSE window LANDED (452, all NIFTY-rooted)**; tomorrow = NSE monthly expiry day-of: expect S24 OI suppression (NIFTY root) + saturation | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no deploy today; the outage demonstrated the same starvation §3.38 predicts, from the environment side |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | not reached (day −₹1,950.26) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | 8th consecutive funded-day evaluability PASS (§6.4) |
| T30 | `breadth` dot `>32` | **OPEN — 2nd consecutive mid-range day**: PE declines straddled the line (20–37, 78.5%), CE dead (n=20) | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | banded, zero flat; new binding-share record 67.0%; loosening ledger 15/12/3 — the 2 new wins are challenger-only n=1 on a trend day |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct (21st); `iv_abs_band` 0% 3rd day (stamp outside band) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (25th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs 0 straddles, boot-fresh ±1,885 first-pair class; clean thereafter |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | vol-12k5/vol-off each won their n=1 challenger-only trade (+₹1,091.34) — 14th/15th measured loosenings, noted, not decision-grade |
| T7 | composite threshold | **REJECTED — carried** | composite-055 all-time now −₹19.78, but today's gain was champion-shared (no loosening observation) |
| T10 | stale OPEN paper positions | **OWNER — chronic** | 18 OPEN, oldest 07-08 |
| T8/T26 | latency | OPEN (data) | emit 17.2–20.2 s (one >18 s first); shadow p50 77.5 s standing | 
| T2 | `iv_rank` | carried, not open | NULL 1,196/1,196 |

## 8 Honesty caveats

- **This run executed ~15:50–16:15 IST** — before the 18:4x evening batch; §6.2's fii self-heal
  and tonight's ingest outcomes are PREDICTIONS to be verified tomorrow; T10 as-of-run-time.
- Regime stamped from the CONTINUOUS session (§3.33a): eff **0.606 = mixed** (official 0.391 also
  mixed — no straddle; CAS delta +36.25). Not a G11 chop day (count unchanged at 7).
- Every §3 table excludes the ~22-minute outage window 10:00–10:21 (no evals ran) — coverage
  claims are net of it; the session is CLEAN-except-outage, not clean.
- The shadow book's best-day headline is one 8-slug TP cluster (§3.24 fan-out); the deduped
  independent sample is 5 events. The T21 +35% bracket paying +₹36.5k in shadow says nothing
  about the FUNDED book, which exited the same regime via structural stops for −₹1,950.
- Counterfactual/shadow exits replicate brackets + structural + square-off only (no time-stop /
  signal-exit fidelity) — standing §3.16 caveat.
- All log-derived checks ran while both containers were up (no recreate observed by run end);
  boot line read from `docker logs` and corroborated by `strategy.engine_reloads`.
- Read-only run: SELECTs, in-container health GETs, log greps. No restarts, deploys, writes,
  config changes, or republishes. Docs-only PR: this file + rollup rows.
