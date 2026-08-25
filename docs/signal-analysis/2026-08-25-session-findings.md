# Session findings — 2026-08-25 (data date)

Analysis date: 2026-08-25 EOD (scheduled post-market agent, run ~15:45–16:10 IST). Analyst: Claude
(scheduled `session-analysis post`). Data: `signal_rejections` rows **1,478** (bounds
`2026-08-25T09:15:00+05:30`…`15:40`; rows 09:19–15:19), signals fired **0**, paper trades **0**,
shadow closes **0** (all variants).

Session character: **Tuesday, NSE MONTHLY expiry day-of** (as the 08-24 file predicted) · up day
(official o 24,175.75 → c 24,334.55 = +0.66% on a 0.91% range, eff **0.725 = trend**; continuous
freeze 24,260.05 = +0.35%, continuous eff **0.557 = mixed** — **6th straddle**, stamp = MIXED per
§3.33a; CAS delta **+74.50**, and the CAS print IS the daily high) · signal contract
**`NFO:NIFTY26SEPFUT`** (rolled yesterday; 1,480 log mentions) · **first fully-clean in-session
no-outage day after 6 consecutive outage days** (the only outage-pattern log lines are the 08:34
boot transient — kite-rest circuit open pre-login, 30 lines at 03:04:13Z, healed by 08:35:53).

## 1 Funnel numbers

| metric | value |
|---|---|
| rejections | 1,478 — **38 of 38 scalpers (FULL coverage — first 38/38 since this check began flagging silents)**; `premium_skew` n=34 |
| eval outcomes | chart-gate-failed 1,956 · confluence-blocked 1,478 · composite-below-threshold 136 · **fired 0** · discipline-paused 0 |
| fired reconciliation (§3.36) | 0 fired = 0 emitted + 0 risk-gate suppressions (grep 0) — trivially reconciled |
| coverage | **25 of 25** 15-min buckets 09:15–15:15 populated — no interior holes; `subscriber_health_events` 0 rows |
| boot health | boot 08:34:13 IST, 0/38-unresolved transient → **38/0/0 at 08:35:53 (~100 s — 2nd consecutive in-band day; watch stays relaxed)**; 08:40 reconcile clean; boot line `loaded 38 published strategies (0 dropped, 0 failed)` |
| paper (funded) | 0 entries, 0 closes — nothing passed confluence (see §3), so nothing reached emission, the risk gate, the governors, or the shadow books |

## 2 Rail findings

- **§2.2 chain-proximity: monthly-expiry day-of LANDED — `strike-pick` 531 all-fails, 17
  NIFTY-rooted slugs, 0 SENSEX** (first-block 0 — all sit behind earlier rails). Mon/Tue NSE series:
  235 / 604 / 322 / 452 / **531**. The 17th slug is `scalp-morning-trade-nifty` (3 fails) joining
  the usual 16. Expect the Wednesday-clean pattern tomorrow (Wednesdays have been clean on both
  roots every observed week).
- **volume-floor first-block 828/1,478 (56.0%)** — binding share DOWN from the 67.0% record
  (thicker expiry tape clears the band more often); banded and honest (avg blocking margin
  −15,401; zero flat thresholds).
- First-block tail: time-window 316 · time-of-day-preference 40 · rsi-band 38 · two-candle 34 ·
  flat-oi-stand-aside 34 · volume-pump 34 · pct-price-move 34 · option-side-constraint 24 ·
  divergence-vol-gate 24 · supertrend-15m 20 · oi-cross-required 18 · hero-zero 16 ·
  psar-durability 10 · directional-change-gate 6 · morning-opening-formation 2 (16 distinct
  rails). **`confluence-composite` first-blocked 0 rows** — with the composite structurally
  starved (§3), everything died at earlier rails or at composite-below-threshold (136 evals).

## 3 Composite + dots

- **S24 monthly-expiry OI suppression, textbook (§3.19), exactly as the 08-24 file predicted:**
  quadrants NEUTRAL **1,096/1,096** contextful · `spurtPricePct` NULL **1,096/1,096** ·
  `futuresBasis` LIVE **1,096/1,096** (the discriminator: an outage would not keep basis) · OI
  root `NIFTY 50` on 1,096/1,096 (+382 context-less rows) · capture healthy underneath (25,530
  snaps / 370 of ~375 minutes). **By design, not an outage.**
- Consequence: **all seven OI-derived dots 0/1,096** (futures_oi, underlying_oi, oi_spurt,
  drastic_oi, sentiment, sentiment_slope, trending_cross) — NEUTRAL dots stay in the denominator
  (§3.12), so the composite is structurally starved: **0 of 1,096 scored rows passed threshold,
  max 0.5303** (32 rows ≥0.5; vs max 0.3457 on 07-28, the prior NSE monthly). **Zero fires is the
  mechanical outcome — REGIME session, no calibration conclusions (§3.19 rule).**
- Live-dot support (complete session, n=1,096 unless noted): `iv_pair` 0% (26th — T3, owner) ·
  `iv_abs_band` 0% (n=164, 4th day — atmIv stamp **0.0983**, below the 10–12 band) · `iv_rank` 0%
  (withheld, standing) · vwap 5.8% · premium_skew 5.9% (n=34) · volume 24.5% · breadth 42.0% ·
  rsi 49.3% · basis 57.8% · vix 58.0% · iv_slope 68.3% (n=164) · psar 71.2% · vwma 95.6% ·
  supertrend 100%.
- **§3.28 breadth (T30) — 3rd consecutive mid-range-operand day, side-aware:** CE dead (advances
  5–29, never crossed `>32`, 0/636); PE **460/460 = 100%** — declines spanned 21–45 across the
  session, so the line was crossed intra-session, and every bar that produced a scored PE row sat
  above it. Per-side saturation on a trend-up day (the §3.28 08-11 amendment shape).

## 4 Data health

| field | today | verdict |
|---|---|---|
| `ivRank` | NULL 1,478/1,478 | dead-data, standing (since 07-02) |
| `dowUp` | NULL 1,478/1,478 | by design (un-armed) |
| `fiiLongPct` | **RECOVERED — null only on the 382 context-less rows** | **08-24 §6.2's self-heal prediction CONFIRMED**: the 08-24 evening chain ran clean (18:44–18:50 all SUCCESS) and this morning's 08:34 catch-up all SUCCESS; `nse_eod_participant_oi` max trade_date = 2026-08-24 |
| `atmIv` | 1 distinct (0.0983) | frozen daily stamp — correct (G12/T28, 22nd) |
| vix / ceIvAvg6 / skew / basis | 39 / 96 / 102 / 86 distinct | alive |
| misaligned 1m candles (§3.15) | **0 rows** | clean — 6th consecutive |
| §3.17 canary | **2 WARNs + 0 straddles — ±910 (14 lots) on CONSECUTIVE buckets 14:18/14:21 IST, both logged UNPAIRED.** Explained against code (§6.1): the day's FIRST non-benign event hit the lazily-filled, boot-empty lot-size cache — the documented fail-closed first-pair class, landing mid-session instead of at open | benign — mechanism verified |
| signal-future capture | **375/375 min** (KITE 360 + TICK_AGG 15, 0 BACKFILL — no outage to repair) | ✓ |
| Kite session | validated (canary GREEN at run time) | ✓ |
| morning ingest | 08:34 catch-up: BHAVCOPY / FII_DII / PARTICIPANT_OI / FII_DERIVATIVE all SUCCESS; one INSTRUMENT_SYNC FAILURE 08:29:58 inside the boot kite-circuit transient, SUCCESS on the 09:05 rerun | ✓ |

## 5 Shadow-book outcomes + counterfactuals

**Zero shadow opens and zero closes on every variant** — 0 composite passes means the book had
nothing to take (same class as 08-21's zero-fire day, but here the cause is S24 starvation, not a
dead vwap dot). All-time totals **byte-identical to the 08-24 baseline**: champion **−₹177,926.63**
(637 closes, 233 net wins) · composite-055 −₹19.78 · vol-12k5 −₹39,033.05 · vol-off −₹54,671.24.
Loosening ledger stands **15 measured / 12 losses / 3 wins**.

**Per-rail counterfactual P&L (owner directive 08-20): re-measured, zero movement** — volume-floor
360 / −₹100,901.33 · rsi-band 74 / −₹59,687.10 · `confluence-composite` 14 / **+₹10,723.64** (still
the one materially positive rail; n=14, not decision-grade) · pct-price-move 38 / +₹5,432.42 ·
oi-cross-required 20 / +₹5,212.90 · supertrend-15m 7 / +₹3,643.32. Root split unchanged: SENSEX
**+₹36.10**/trade (275) vs NIFTY −₹518.94 (362) — the 08-24 flip holds by default (no new closes).

**§4.2 manual counterfactuals: none possible or needed** — would-have-fired set empty (0 composite
passes), risk-suppressed class empty, and the 531 strike-pick fails are the §3.27 non-shadowable
class (expiry-day regime). Counterfactual W/L: 0W/0L.

## 6 New data points / anomalies

### 6.1 §3.17 first-pair fail-closed class can land MID-SESSION — both halves of a textbook ± pair logged UNPAIRED

Today's only two WARNs: bucket 14:18 IST shortfall **−910**, bucket 14:21 **+910** — consecutive
buckets, equal-and-opposite, exact lot multiple (14 × 65), both ≤2.5% of their own buckets: the
benign boundary-straddle fingerprint, which G9's suppressor exists to suppress. It didn't; both
WARNed as "UNPAIRED". Ground-truthed against `PartialBucketCanary.java` (not a defect):

- Deferral requires `isLotMultiple(...)`, and the lot cache is **lazily filled on first miss**
  (`cachedLotSize` is non-blocking; a miss kicks an async master fetch and returns null =
  "cannot prove", fail CLOSED). Today's boot was 08:34; the day's **first** non-benign event was
  the 14:22:31 sweep — first cache read, miss, so the leading −910 half could not be deferred and
  WARNed immediately (its message honestly says the next bucket carried no provable partner —
  the partner wasn't classifiable yet).
- The trailing +910 half then **deferred correctly** (lot resolved by its sweep), waited for a
  partner in bucket 14:24 (benign — none), and was released as unpaired at its documented
  deadline: heldBucketStart+2×3m+one sweep ≈ 14:28:31 — the observed second WARN timestamp,
  to the second.

So the §3.17 "boot-fresh first-pair" class keys on the day's **first non-benign event**, not on
boot time — it can land at any hour. README §3.17 amended (this PR). Consequence for NEW-6:
08-19's unexplained 14:54 **−910** now has a plausible benign reading (same magnitude, same
mid-session shape, leading-half-of-a-pair whose partner may have been suppressed or benign);
unprovable retroactively (logs gone), but the alarming shape has a mundane sibling.

### 6.2 08-24 predictions verified

- **fii self-heal: CONFIRMED** (§4 table) — evening chain clean, participant OI current through
  08-24, `fiiLongPct` live on all contextful rows today.
- **NEW-7 (#1450 intra-day fii retry): shipped but NOT exercised** — this morning's catch-up
  succeeded, so the 09:50/11:50/14:50 retries correctly skipped (no ingest_runs rows — the
  skip-on-SUCCESS design). First live exercise still pending a failed morning fetch.
- **S24 suppression + strike-pick saturation on the NIFTY root: both landed exactly as predicted**
  (§2, §3).

### 6.3 §3.29 audit — zero delta; never-fired set unchanged

No funded closes today. Fired vocabulary since 07-01 unchanged: TRAILING_STOP 22 ·
STRUCTURAL_STOP 18 · TIME_STOP 17 · STOP_LOSS 8 · MANUAL 2. Armed-path table re-verified —
identical 10 rows (incl. tag `oi-confluence-exit` 8). Never-fired unchanged: `take_profit
premium_pct` (36 armed, zero funded TP closes since 07-01) · `signal_exit` (38) · `square_off`
(2) · tag `oi-confluence-exit` (8). INDETERMINATE: `trailing_stop atr_multiple` (2), `stop_loss
atr_multiple` (2).

### 6.4 §3.34 heat-gate — not evaluable (zero funded fires; the gate only runs at entry)

### 6.5 §3.30 freeze telemetry — trivially 0 of 5 (zero entries)

Trend: 08-18 2/5 · 08-19 2/5 · 08-20 3/5 · 08-21 0/5-trivial · 08-24 3/5 by 13:40 ·
**08-25 0/5-trivial**.

### 6.6 §6.9 trail-should-have-fired watch (NEW-8) — 2nd measurement: 0 rows, operand non-empty

run_date 2026-08-25 (morning 08:35 catch-up pass): 23 sell_decisions rows, 23 with `stop_level`,
20 with `trail_level` — real data to fail on; none breached. Clean.

### 6.7 Mechanical pre-checks

- `tools/ledger-consistency-check.py`: **11 REVIEW lines — the identical standing set** since
  08-17. Ledger consistent.
- `tools/published-config-drift.py`: **69 published — 69 matched (45 clean, 24 drifted = the
  standing #1075 disabled-scalper drafts), 0 DB-only, 0 YAML-only.** Unchanged; nothing
  republished by this run.

## 7 Tuning candidates

Ledger §0 group G is the authoritative status; nothing applied by this run.

| # | knob | status | today's evidence |
|---|---|---|---|
| NEW-6 (08-19) | unpaired mid-session §3.17 WARNs w/o reconnect | **CLOSED-FAVOURABLE** — 4 sessions with zero unexplained WARNs incl. 2 clean no-outage days (08-21, today); today's ±910 pair gives 08-19's −910 a plausible benign sibling (§6.1). The 15:09 −8,970 stays unexplained but never recurred | §6.1 |
| NEW-7 (08-24) | fii intra-day retry #1450 | **SHIPPED, unexercised** — morning catch-up SUCCESS so retries correctly skipped; first live exercise pending | §6.2 |
| watch | `strike-pick` chain-proximity | **WATCH** — monthly day-of landed (531, 17 NIFTY slugs); expect Wednesday-clean tomorrow | §2 |
| NEW (08-04) | mid-session deploys | **PROPOSED — carried** | no deploy today |
| NEW-3 (08-12) | `daily_profit_target` 1.5% | **OBSERVATION (owner) — carried** | not reached (0 fires) |
| NEW-1 (08-05) | paper heat-cap margin timeout | **PROPOSED — carried** | not evaluable (0 funded fires) |
| T30 | `breadth` dot `>32` | **OPEN — 3rd consecutive mid-range day, side-aware**: CE dead (adv 5–29), PE 100% on its rows (declines 21–45 crossed intra-session) | §3 |
| T27 | relative-floor window | **OPEN; arming rec unchanged (NO)** | binding share 56.0% (off the 67.0% record — thick expiry tape); banded, zero flat; loosening ledger 15/12/3 unchanged |
| T28 | `atmIv` frozen daily stamp | **OPEN** | 1 distinct = 0.0983 (22nd); `iv_abs_band` 0% 4th day (stamp below band) |
| T3 | `iv_pair` | **OPEN (owner)** | 0% (26th session) |
| T23 | partial-bucket tolerance | **OPEN** | 2 WARNs 0 straddles — the mid-session first-pair class (§6.1); mechanism verified benign |
| T1 | `relativeVolumeMultiplier` | **REJECTED — carried** | no challenger observations (0 opens) |
| T7 | composite threshold | **REJECTED — carried** | no observations; composite-055 all-time −₹19.78 unchanged |
| NEW-8 (08-24) | trail-should-have-fired watch | **STANDING — 2nd clean measurement (0 rows, 23/20 operand)** | §6.6 |
| T8/T26 | latency | OPEN (data) | not measurable (0 emissions, 0 shadow opens) |
| T2 | `iv_rank` | carried, not open | NULL 1,478/1,478 |

## 8 Honesty caveats

- **This run executed ~15:45–16:10 IST** — before the 18:4x evening batch, the 18:52/18:53 swing
  settles (EXITS-only — 0 candidates AND 0 exits is the normal correct outcome, per H27) and the
  18:59 buyable-alerts; tonight's ingest outcomes and today's fii row are predictions for
  tomorrow's run to verify.
- Regime stamped from the CONTINUOUS session (§3.33a): eff **0.557 = mixed**; official 0.725 =
  trend — **6th straddle**; CAS delta +74.50 and the CAS print is the daily high. Not a G11 chop
  day (count unchanged at 7).
- Zero fires / zero passes is the **mechanical S24 outcome** — this session contributes NOTHING
  for or against any entry-gate calibration (§3.19); marked REGIME in the rollup.
- §6.1's timeline reconstruction (leading half = cache miss, trailing half = deadline release) is
  computed from code + log timestamps; the cache-state at 14:22 is inferred, not directly
  observable (labelled as such).
- Counterfactual/shadow exits replicate brackets + structural + square-off only (no time-stop /
  signal-exit fidelity) — standing §3.16 caveat (vacuous today: zero legs).
- All log-derived checks ran while both containers were up (no recreate observed by run end);
  boot line read from `docker logs` and corroborated by `strategy.engine_reloads`.
- Read-only run: SELECTs, log greps, in-container health reads. No restarts, deploys, writes,
  config changes, or republishes. Docs-only PR: this file + rollup rows + a §3.17 README
  amendment.
