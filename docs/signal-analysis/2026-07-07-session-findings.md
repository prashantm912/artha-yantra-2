# Session findings — 2026-07-07 (data date)

**Analysis date:** 2026-07-07 (post-market, scheduled agent run). **Analyst:** Claude (automated).
**Data:** `strategy.signal_rejections` — **638 rows on 2026-07-07**, **31 strategies** (up from 17 —
the Manas/Minervini/SENSEX families now evaluate alongside the scalpers), spanning **09:19–14:22 IST
ONLY**. `strategy.signals` fired: **3** (FIRST fires in this analysis history — all `scalp-straddle-nifty`).
Paper trades: **0** (the 3 fires expired unactioned — advisory only). Shadow book: **0 positions opened**
(the would-have-fired class is now empty — see §5). Method: [README.md](README.md) §3 pass.

**Session character:** **expiry Tuesday (NSE weekly, 2026-07-07)**, **down-biased & rangebound** — front
future NIFTY26JULFUT **24,504 open → 24,410 close (−94 pts)**, range 24,406–24,565 (~159 pts). 3m
futures volume avg 23.7k / max 141.6k (DB candle); the gate's tick-agg operand ran far lower (1.4–4.6k),
the known undercount. RSI(3m) COOL today (rsi-band operand avg 47, vs 79 on the 07-06 trend day).

**Headline verdicts (two big ones + one alarm):**

1. **First-ever fires — and they LOST.** After 4 sessions of zero fires, `scalp-straddle-nifty` fired
   **2 long-ATM-straddle entries (0DTE, today's weekly)**. Both lost: **#30 round-trip −19%** (entry
   combined 75.05 → strategy's own EXIT at 12:12 = 60.80, midday theta bleed) and **#32 −6.8%** (held to
   square-off, never hit +35% TP). A long ATM straddle on a rangebound expiry morning is a theta bomb; the
   strategy correctly stopped #30 — then the PE ran *after* it was out (would have been +28% by 15:20, but
   that is hindsight the exit rule can't use). **These fires are unrelated to the relative-vol-floor arming**
   (the straddle path is delta-neutral, its own gate). No paper positions were created — advisory only.

2. **Relative volume floor (#605) is LIVE and behaving correctly on its first session.** The NIFTY
   directional scalpers now show volume-floor threshold **~4,972 (k×median)** instead of the fixed 125,000
   — first-block avg margin shrank from −118,670 (07-06) to **−38,919**. On THIS quiet expiry morning the
   relative floor STILL (rightly) blocked them: bar volume 1.4–3.4k gave no ≥1.5× volume *expansion* over
   the trailing median, which is exactly what the floor demands. **The single-rail "volume wall"
   would-have-fired class has DISSOLVED** (§3.5 = 0 rows, vs 31 on 07-06) → **the shadow book opened
   nothing today** (expected consequence, not a break). Un-armed SENSEX scalpers still carry the fixed
   125,000 (avg threshold pulled up in the aggregate).

3. **ALARM — strategy-signal EVAL STALL at 14:22:45 IST.** The per-bar `signal-eval` loop went silent
   after 14:22:45 IST and never resumed (only the 15:45 scheduled sweep fired afterward, on a different
   thread). **Market-data capture was healthy the whole session** (candles to 15:29, chain snapshots to
   15:31) — so the FEED was fine; the strategy-signal consumer hung, silently (no exception logged). This
   truncates the session to ~5h of 6h15m and is a **NEW signature** distinct from 07-03's CandleBuilder
   poison (which was a market-data tick/bar divergence). Read all rates below as **morning+midday only**.

---

## 1 Funnel numbers

Rows 638 (09:19–**14:22** IST — truncated by the §6 stall). First-blocking-rail histogram:

| blocking_rail | rows | strategies | avg margin | note |
|---|---|---|---|---|
| volume-floor | 458 | 28 | −38,919 | RELATIVE now (~5k armed / 125k un-armed) — margin ⅓ of 07-06 |
| time-window | 78 | 4 | — | known-blocked bars re-logged = noise |
| time-of-day-preference | 35 | 4 | — | same class |
| pct-price-move | 9 | 2 | −0.851 | FAIL_OPEN |
| two-candle / volume-pump | 9 / 9 | 2 / 2 | — | tail |
| oi-cross-required / divergence-vol-gate / max-oi-sr-gate | 8 / 7 / 7 | 1 | — | tail |
| directional-change-gate / confluence-composite / strike-pick | 6 / 5 / 5 | 1–3 | — | tail |

All-failed-rails expansion (unnest `checks[]`, pass=false; top rows):
volume-floor 458 (4,556 vs 43,476 avg — armed ~5k / un-armed 125k) · **strike-pick 376** (elevated,
expiry-day strike resolution) · confluence-composite 320 (0.41 vs 0.6) · rsi-band 129 (**47** — cool) ·
divergence-vol-gate 98 · trend-change 98 · time-window 78 · pct-price-move 64 (FAIL_OPEN) ·
two-candle 64 · volume-pump 64 (FAIL_OPEN) · directional-vix-gate 58 (FAIL_OPEN, 11.7) ·
oi-cross-required 43 · **oi-divergence-magnitude 42 (24.65 vs 20 — ABOVE threshold, 4th rising value)** ·
constituent-gate 38 (FAIL_OPEN, new family) · rising-volume 37 · directional-change-gate 36 ·
open-high-low 36 · psar-durability 33 (0.04 vs 0.05) · oi-slope-agree 24 · max-oi-sr-gate 23 ·
call-put-delta-filter 5 (37.7 vs 50) · morning-opening-formation 3 · gap-fill 2.

## 2 Rail findings

### 2.1 `volume-floor` — RELATIVE floor now live (#605); correctly blocked a no-expansion expiry morning

- The 21 NIFTY scalpers now evaluate `volume ≥ k × median(prior-N bars)` (k=1.5 / N=20, `artha.scalper.oi.relativeVolume*`).
  Observed thresholds today ranged **4,972.5 → 6,386.25** (relative, moving with the trailing median) for
  armed strategies; SENSEX scalpers still show the fixed **125,000** (un-armed). First-block avg margin
  −38,919 (was −118,670 on 07-06 under the fixed wall).
- **It still blocked the directional scalpers today, and that is correct.** On a quiet expiry morning the
  tick-agg bar volume (1,430–3,380 in the logs) sat *below* 1.5× the trailing median, i.e. **no volume
  expansion** — precisely the chop the relative floor is designed to filter. This is NOT the old
  "unpassable-by-calibration" failure; operand and threshold are now on the same scale and within ~1.5–3×
  of each other. **Do NOT re-propose lowering a fixed floor.** Status: **SHIPPED/ARMED — now in the
  1-month tuning window (owner: judge whether k=1.5 fires too much/little across sessions).**
- **Consequence:** the single-rail would-have-fired class is empty (§3.5 = 0) → no shadow positions (§5).
  The floor no longer produces a clean "blocked ONLY by volume" population, so the shadow-book counterfactual
  has nothing to trade. Future counterfactual evidence for this rail must come from **real fires** (paper),
  not the shadow book.

### 2.2 Working-as-designed / regime rails (no tune)

- `rsi-band`: operand avg **47** today (vs 79 on the 07-06 trend day) — cool, regime-responsive.
- `oi-divergence-magnitude`: operand **24.65 vs 20** — fourth session value (5.98 → 16.5 → 23.9 → 24.65),
  a clear rising regime trend; threshold 20 is reachable in active-OI regimes. Aggregate in the rollup;
  do not touch off single sessions.
- `strike-pick` 376 fails — **elevated on expiry day** (0DTE strike resolution is thinner). Reached only
  after earlier rails pass (mostly the straddle path, which evaluates all rails). Cosmetic for tuning;
  watch whether it stays elevated on non-expiry days.
- `time-window` / `time-of-day-preference`: 113 rows are logging noise (bars outside windows re-logged).

## 3 Composite + dots

Composite distribution (0.1 buckets), **truncated session**: 0.2→36 (all PE) · 0.3→66 · 0.4→69 ·
0.5→111 · **0.6→99 · 0.7→107 · 0.8→16**. Max observed **0.783** (p-cap 0.816 — see below). **184 rows ≥
threshold**, all in the 0.6/0.7/0.8 buckets and **all CE**. **PE side is ALIVE today**: 189 PE rows
(0.2–0.5 buckets) — the first real PE population (07-02/03/06 were 0–1 PE). PE scored low (max PE bucket
0.5, none passed), but it *evaluated* — the "PE mirror silence" is looking **regime** (down-biased day
brought PE online), not structural.

Dot support rates (504 confluence-evaluated main-path rows; 98 straddle-path rows for E4 dots):

| dot | w | support % | vs 07-06 | verdict |
|---|---|---|---|---|
| oi_spurt | 1.0 | **0%** | 0% | dead-by-calibration (price% floor 50 unreachable) |
| **breadth** | 1.0 | **0%** | 44.9% | **DATA ALIVE (A/D non-zero on all rows) but 0% support = REGIME** (breadth against CE all session on a down day) — NOT the old 0/0 dead-feed |
| volume | 1.0 | **0%** | 0% | dead — same relative-floor no-expansion (§2.1) |
| iv_rank | 0.8 | **0%** | 0% | dead — `ivRank` NULL 638/638 (honest-null) |
| iv_pair | 0.8 | **0%** | 0% | dead — 0.10 gap between 6-strike avgs never occurs |
| trending_cross | 1.0 | 27.6% | 42.0% | regime |
| iv_slope (straddle, n=98) | 0.8 | 40.8% | 47.0% | straddle-path-only; regime |
| sentiment_slope | 1.0 | 44.4% | 49.8% | healthy |
| rsi | 1.0 | 44.6% | 66.4% | healthy (cool-RSI regime) |
| underlying_oi | 1.0 | 49.0% | 59.2% | healthy |
| futures_oi | 1.5 | 56.2% | 59.6% | healthy |
| sentiment | 1.0 | 57.7% | 74.8% | healthy |
| **vix** | 1.0 | **60.9%** | 2.3% | **regime-flipped** (2.3%→60.9%; swings hard — NOT dead) |
| psar | 1.0 | 62.1% | 91.0% | healthy |
| supertrend | 1.0 | 62.5% | 91.4% | healthy |
| basis | 1.0 | 62.5% | 100% | healthy |
| iv_abs_band (straddle, n=98) | 0.8 | 80.6% | 100% | straddle-path; regime |
| vwma | 1.0 | 85.7% | 96.7% | healthy |
| drastic_oi | 1.0 | 98.0% | 96.9% | free again (swings hard — hold for rollup) |
| vwap | 2.5 | 100% | 100% | by construction (side chosen BY vwap) |

**Dead-weight cap — UNCHANGED at 0.816.** Main-composite Σw = 19.6. Structurally-dead dots = **4**:
volume 1.0 + iv_rank 0.8 + iv_pair 0.8 + oi_spurt 1.0 = 3.6. **breadth's 0% today is REGIME (data alive,
A/D non-zero) not structural-dead**, so it is NOT added back to the dead list — the cap stays
(19.6 − 3.6)/19.6 = **0.816**, confirmed by the populated 0.8 bucket (16 rows) and max 0.783.

## 4 Data health (2026-07-07 rows)

| field | state | classification | vs 07-06 |
|---|---|---|---|
| macro.advances/declines | **NON-ZERO on all rows** (breadth_zero = 0/638) | **ALIVE** (#486 holding) | same (alive) |
| macro.ivRank | NULL 638/638 | honest-null (insufficient IV history); scores against | same |
| macro.dowUp | NULL 638/638 | by-design (Dow un-armed); null = NEUTRAL | same |
| macro.fiiLongPct | NULL 638/638 | fii-bias dot un-armed | same |
| macro.spurtPricePct | populated non-zero but 0 dot support (below floor 50) | floor unreachable (calibration) | same |
| macro.vix | NULL 638/638 in `context.macro` | vix DOT reads a separate path (60.9% support) — macro mirror still blind | **same watch (§6, ledger #10)** |
| everything else (chart/oi) | populated, plausible **through 14:22** | ✓ capture healthy until the eval stall | truncated (§6) |

**Nothing that was alive went dead** (breadth still alive; the 0% support is regime). **NEW alarm is
operational, not a dead feed:** the strategy-signal EVAL loop stalled at 14:22 (§6) while market-data
capture stayed fully alive — so the data pipeline is healthy; the *consumer* hung.

## 5 Shadow-book outcomes + fire counterfactuals

**Shadow book: 0 positions opened today** (all variants). This is the **expected** consequence of the
relative-floor arming: the shadow book opens on rejections blocked **only** by the volume rail with
composite passing (the "would-have-fired" class), and that class is now **empty** (§3.5 = 0 — every
composite-passing rejection today also failed another rail). Not a shadow-book defect — verified the
book's last opens were 07-06 and no OPEN rows linger. Going forward, this rail's live evidence comes
from **real fires**, not shadow rows.

**Fire counterfactuals (the only P&L evidence this session; ADVISORY — no paper fill, 3-min LTP
granularity, combined premium = per-leg LTP summed at matched minutes, no slippage/fees):**

| # | type | time IST | position | entry (comb) | exit / square-off | result |
|---|---|---|---|---|---|---|
| 30 | ENTRY BUY | 10:42 | long straddle 24500 CE+PE (0DTE) | 75.05 | **strategy EXIT #31 @ 12:12 = 60.80** | **−19.0% LOSS** (−14.25 pts) |
| 31 | EXIT SELL | 12:12 | (closes #30) | — | — | — |
| 32 | ENTRY BUY | 13:39 | long straddle 24450 CE+PE (0DTE) | 50.20 | square-off (EXPIRED) ≈ 46.80 | **−6.8% LOSS** (−3.40 pts) |

- **#30/#31**: the straddle bled from 75.05 to 60.80 over 10:42→12:12 (midday theta, neither leg moved
  enough) and the strategy's own exit cut it at **−19%**. Post-exit the spot fell and the PE ran (combined
  reached 101.65 / +35% by ~mid-afternoon, 96.40 by 15:20) — but that is unusable hindsight; the exit rule
  fired first. Composite 0.6268.
- **#32**: entered late (13:39) at 50.20, never expanded (max 51.95 = +3.5%, below the +35% E9 TP), drifted
  to ~46.80 by square-off = **−6.8%**. Composite **0.5000** — *below* the 0.6 confluence threshold, so the
  straddle path evidently fires on a lower/optional-activation threshold; flag for the owner (§6).
- **Net advisory: both straddle entries lost (−19% and −6.8%).** First-ever fires are 0DTE expiry-day long
  straddles that lost to theta on a rangebound morning — a coherent, unsurprising outcome, but a caution
  that the straddle path fires 0DTE ATM straddles with no paper execution behind them.

**Variant league (cumulative — no change today, 0 opens):** champion 35 closed / +₹19,274.61 net (07-06
only) / −201.0 pts; vol-off 2 / +₹4,051.27; vol-12k5 1 / −160.15; composite-070 still 0 rows all-time.

## 6 New data points / anomalies

- **STALL (operational, HIGH) — strategy-signal `signal-eval` loop hung at 14:22:45 IST.** Last per-bar log
  08:52:45 UTC; silent until the 10:14 UTC (15:45 IST) scheduled sweep on thread `scheduling-2043`. No
  exception. **Market-data capture healthy all session** (candles→15:29, chain→15:31), so the feed is fine
  — the strategy-signal consumer stalled. NEW signature vs 07-03 (that was a market-data tick/bar
  divergence; this is a strategy-signal-side hang with a live feed). **Owner action: if it recurs,
  thread-dump ay-strategy-signal-service (`docker exec ay-strategy-signal-service sh -c 'kill -3 1'`) to
  catch the hung thread; check whether DataHealthCanary/DotHealthCanary paged.** (Read-only run — no
  restart taken.)
- **FIRST FIRES EVER — 3× `scalp-straddle-nifty`.** After 4 zero-fire sessions the straddle path produced 2
  entries + 1 exit (0DTE ATM straddles). Independent of the relative-floor arming (delta-neutral path). Both
  lost (§5). The straddle path fired one entry (#32) at composite **0.5000 < 0.6** — confirm the straddle
  gate's activation threshold is intended to be below the directional 0.6.
- **Relative volume floor's first live session** — behaved correctly (§2.1); the would-have-fired/shadow
  class dissolved as designed. Next binding rail once the floor relaxes is **not yet a fixed wall** — on
  this quiet day the funnel dies across volume-floor + strike-pick + confluence-composite together, not at
  one rail. Watch on a higher-volume day whether real fires multiply.
- **PE side alive (189 rows)** — down-biased day brought the PE mirror online for the first time; supports
  "PE silence = regime" over "structural suppression". Keep watching on a clean trend-down day whether PE
  composites can pass.
- **31 strategies evaluated** (up from 17) — the SENSEX/Manas/Minervini families now appear in the
  rejection stream (constituent-gate, morning-opening-formation, gap-fill rails are theirs). SENSEX
  scalpers still on the fixed 125,000 floor (un-armed).
- **`oi-divergence-magnitude` operand 24.65** — fourth rising value (5.98→16.5→23.9→24.65). Regime; rollup
  aggregates.
- **`context.macro.vix` NULL while vix dot works (60.9%)** — watch-item continues (ledger #10).

## 7 Tuning candidates (status ledger — carried forward from 2026-07-06)

| # | knob | current | proposed | evidence | status |
|---|---|---|---|---|---|
| 1 | `volume_floor` / relative floor | **relative k×median (k=1.5,N=20) ARMED #605** | tune k over 1 month | §2.1: first live session, floor correctly blocked a no-expansion expiry morning; would-have-fired class dissolved | **SHIPPED/ARMED — in tuning window (owner: is k=1.5 right?)** |
| 2 | `artha.scalper.oi.ivPairMinGap` | 0.10 | 0.01–0.02 | §3: 0% dot support all 4 sessions | PROPOSED |
| 3 | `artha.scalper.oi.spurtPricePct` | 50 | 5–10 | §3: 0% dot support all 4 sessions | PROPOSED |
| 4 | breadth live producer | live A/D | — | §4: ALIVE, holding (#486) | **SHIPPED #486 (holding 07-07)** |
| 5 | iv_rank null semantics | null scores against | null = neutral / excluded | §3/§4: honest-null punished all 4 sessions | PROPOSED (code) |
| 6 | composite threshold 0.6 | keep | keep (fix inputs) | §3 cap 0.816; headroom fine | DECIDED-KEEP |
| 7 | `DataHealthCanary` staleness watcher | BUILT #484/#491 | **verify it pages on a strategy-signal EVAL hang (not just market-data)** | §6: eval stalled 14:22, feed alive — did the canary catch a consumer-side hang? | **RE-OPENED (verify coverage for the §6 signature)** |
| 8 | candle upsert provenance preserve | BUILT | — | 07-03 A1 | SHIPPED |
| 9 | covered-range 1m re-fetch treadmill | FIXED | — | 07-03 A1 | SHIPPED |
| 10 | `context.macro.vix` NULL while vix dot works | macro mirror blind | populate macro.vix or add a data-health flag | §4/§6: NULL 638/638, dot path OK (60.9%) | PROPOSED (data-health, low urgency) |
| 11 | shadow entry latency p95 ~105s | structural 3m cadence | investigate | no new shadows today (0 opens) — carry | PROPOSED (measure across sessions) |
| 12 | composite-070 variant never opens | configured, 0 rows all-time | owner: loosen or drop | §5: still 0 all-time | WATCH (owner) |
| 13 | `scalp-straddle-nifty` fires 0DTE ATM straddles, no paper fill | fires at composite 0.5, both lost | owner: confirm straddle-path threshold + whether 0DTE straddles should auto-paper | §5/§6: 2 entries both lost (−19%, −6.8%), advisory-only | **NEW — WATCH (owner)** |
| 14 | strategy-signal eval STALL 14:22 | silent hang, feed alive | RCA the consumer-side hang; thread-dump on recurrence | §6: eval silent 14:22→close, market-data healthy | **NEW — HIGH (operational; owner RCA)** |

**Four-session caveat:** items 1–3 are structural (threshold vs operand physical range). Item 1 is now
ARMED and in a live tuning window — judge k=1.5 on real fires over the coming month, not on shadow rows
(the shadow class is gone). Dot rates still swing hard across sessions (breadth 45%→0%, vix 2%→61%,
rsi 66%→45%, oi-divergence 6→16→24→25) — hold ALL dot-threshold moves for the multi-session rollup
(now **4** of ~5 sessions logged). **This session is TRUNCATED (09:19–14:22)** — treat afternoon as
missing, not empty.
