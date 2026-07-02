# Session findings — 2026-07-02 (data date)

**Analysis date:** 2026-07-03 (off-hours). **Analyst:** Claude (owner-directed).
**Data:** `strategy.signal_rejections` — 530 rows total at analysis time: **524 on 2026-07-02**
(the first full observable session; gate observability #404 deployed 2026-07-01 evening) + 6
evening rows on 2026-07-01. 16 strategies represented. `strategy.signals` fired: **0** (ever).
Paper trades: 0. Method: [README.md](README.md) §3 v1 pass (this session DEFINED the v1 pass).
**Session character:** quiet, mild up-bias day — VIX ≈ 12.2, futures quadrant mostly
SHORT_BUILDUP/LONG_UNWINDING flips, RSI(14) on the 3m signal future ranged ~52–58 most of the day.
Front future: NIFTY26JULFUT (July became front ~Jul 1). No expiry.

**Headline verdict:** the gate is working as designed on rails that have real data — but FOUR
inputs are structurally dead (threshold outside the operand's physical range, or feeding on
null/zero data), and ONE of them (`volume-floor`) single-handedly vetoed all 12 entries that passed
everything else. "0 signals" on this day was NOT purely a quiet-market outcome.

---

## 1 Funnel numbers

By day: 2026-07-01 → 6 rows (6 strategies, post-deploy evening); 2026-07-02 → 524 rows (16 strategies).

First-blocking-rail histogram (2026-07-02 + the 6):

| blocking_rail | rows | strategies | avg margin | note |
|---|---|---|---|---|
| volume-floor | 350 | 16 | −118,829 | operand avg ≈ 5.1k vs threshold 125,000 |
| time-window | 151 | 9 | −69 (const) | known-blocked bars logged repeatedly = noise |
| time-of-day-preference | 25 | 2 | — | same class |
| confluence-composite | 1 | 1 | −0.18 | |
| rsi-band / flat-oi-stand-aside / max-oi-sr-gate | 1 each | | | |

All-failed-rails expansion (unnest `checks[]`, pass=false — rails can co-fail):
volume-floor 350 · confluence-composite 191 (avg 0.472–0.522 vs 0.6) · time-window 151 ·
divergence-vol-gate 53 · trend-change 53 · two-candle 36 · volume-pump 36 · pct-price-move 36
(avg 0.57 vs 1.0) · oi-cross-required 29 · oi-divergence-magnitude 29 (avg 5.98 vs 20) ·
rsi-band 30 (avg operand 55.4, CE wants 60–80) · max-oi-sr-gate 25 · time-of-day 25 ·
oi-interval-and-60m-trend 22 · hero-zero 17 · oi-slope-agree 16 · rising-volume 17 ·
psar-durability 13 · directional-vix 5 · constituent-gate 5 · call-put-delta 5 (35.2 vs 50) · misc ≤6.

## 2 Rail findings

### 2.1 `volume-floor` — UNPASSABLE (P0 tuning item)

- Every one of the 17 live strategies runs the hardcoded Siva §0B default
  `NIFTY_VOL = 125000` (`ScalperGates.java:35`); the per-strategy YAML override
  `scalper.params.volume_floor` exists but is **null in every shipped YAML**.
- Ground truth (§3.8): NIFTY26JULFUT 1m→3m rolled bar volume, 2026-07-02 09:15–15:30, 125 bars:
  **min 1,885 · p50 12,350 · p90 52,520 · max 116,870**. The 125,000 floor is **above the session
  maximum** — the rail passed 0% of bars and cannot pass on a day like this.
- Impact: 350/524 first-blocks (67%). **118 rows had composite ≥ threshold** and died here. Of those,
  **12 rows passed EVERY evaluated rail except volume** (checks: time-window ✓, rsi-band ✓,
  supertrend-15m ✓, iv-buyer-cap ✓, confluence-composite ✓):
  `scalp-golden-crossover-nifty` ×10 (12:39–14:21 IST, composites 0.65–0.69) and
  `scalp-connect-the-dots-nifty` ×2 (13:18, 13:24, ~0.644). **These were the day's signals.**
- Provenance suspicion: Siva's "1.25 lakh volume candle" reads off a broker/TradingView chart whose
  volume series need not match our tick-agg/backfill capture. Either way the fix is to calibrate to
  OUR series.
- **Proposed tune:** set `volume_floor` ≈ **15,000–20,000** (≈p55–p65 of this session) per strategy
  via the existing YAML knob (or change the `ScalperGates` NIFTY default). Longer-term candidate: a
  RELATIVE floor (bar ≥ k × rolling 20-bar median volume) — regime-proof. Before fixing the number,
  one sanity compare of our bar volume vs the broker chart for the same bars. Status: **PROPOSED**.

### 2.2 Working-as-designed rails (no tune)

- `rsi-band` (CE 60–80): RSI sat 52–58 → the Siva 40–60 no-trade zone did its job on a flat day.
- `oi-divergence-magnitude` avg 5.98 vs 20 threshold — strict but that is the doc's number; watch
  across sessions before touching.
- `pct-price-move` (mover ≥1%): avg 0.57% on a quiet day — plausibly correct behavior.
- `time-window` / `time-of-day`: 176 rows are pure logging noise (bars outside windows re-logged,
  e.g. morning-trade blocked all day after its 09:30 cutoff — it WAS evaluated in-window from 09:18,
  4 pre-09:30 rows). Optional polish only (see README §7.3/§7.5).

## 3 Composite + dots

Composite distribution (rounded 0.1 buckets): 0.1→3 · 0.2→9 · 0.3→8 · 0.4→40 · 0.5→79 · **0.6→85 ·
0.7→63 · 0.8→20**. Every row ≥0.4 was CE; every PE row ≤0.35 (consistent with the up-bias day).
168 rows ≥0.65 — the composite was NOT the binding constraint; volume-floor was.

Dot support rates across the 306 confluence-evaluated rows:

| dot | w | support % | verdict |
|---|---|---|---|
| volume | 1.0 | **0%** | dead — same 125k floor (§2.1) |
| iv_rank | 0.8 | **0%** | dead — `ivRank` NULL in 530/530 rows (insufficient iv_daily_summary history live; honest-null) and null scores AGAINST |
| iv_pair | 0.8 | **0%** | dead — needs 0.10 (10 IV pts) gap between CE/PE 6-strike AVERAGES; observed 0.0979 vs 0.0980. Real index put-skew ≈1–2 pts. Threshold outside physical range |
| breadth | 1.0 | **0%** | dead — advances/declines = 0/0 in all 306 rows: source is EOD-bhavcopy `/market/breadth?date=` → 422 intraday → degrades to 0/0; gate wants >32. **Dead during every live session by construction** |
| oi_spurt | 1.0 | **0%** | dead-by-calibration — needs spurt OI% ≥50 AND price% ≥50; observed price% max 22.2 (49 rows exactly 0), OI% avg 4.5 / max 100 |
| iv_abs_band (E4 strategies) | 0.8 | 0% (n=54) | ATM IV 12.3–12.9% sat just above the 10–12 band all day — near-miss, watch |
| iv_slope (E4) | 0.8 | 5.6% | rare by nature, fine |
| sentiment_slope | 1.0 | 41% | healthy |
| rsi | 1.0 | 43% | healthy |
| futures_oi | 1.5 | 44% | healthy |
| underlying_oi / trending_cross | 1.0 | 53/54% | healthy |
| sentiment | 1.0 | 69% | healthy |
| psar / vwma | 1.0 | 74/82% | healthy |
| vix / supertrend / basis | 1.0 | 94% | healthy |
| drastic_oi | 1.0 | **96%** | suspiciously FREE — 50k placeholder floor passes nearly always; opposite problem, watch (already a tracked tune-later) |
| vwap | 2.5 | 100% | by construction (side chosen BY vwap) |

**Dead-weight cap math:** Σw = 19.6 (standard 18-dot set). Dead dots (volume 1.0 + iv_rank 0.8 +
iv_pair 0.8 + breadth 1.0 + oi_spurt 1.0) = 4.6 → **max achievable composite = 15.0/19.6 ≈ 0.765**
against threshold 0.6. The observed ceiling (~0.84 incl. E4 variants) confirms it. The composite is
structurally starved: threshold consumes 78% of the reachable range before market conditions say
anything.

**Proposed tunes (README §7 has the build shapes):**
- `artha.scalper.oi.ivPairMinGap`: 0.10 → **0.01–0.02** (fraction scale). PROPOSED.
- `artha.scalper.oi.spurtPricePct`: 50 → **~5–10**. PROPOSED.
- breadth: build a LIVE advances/declines producer (the N50 batch-quote path from
  index-contribution `mode=live` #468 already fetches the needed quotes). PROPOSED (build).
- iv_rank null semantics: null → NEUTRAL (match the dow dot) or exclude-from-denominator; today
  null scores against. PROPOSED (code, one-line + tests).
- Do NOT lower the 0.6 threshold as a shortcut — fix the inputs, keep the bar.

## 4 Data health (2026-07-02 rows)

| field | state | classification |
|---|---|---|
| macro.ivRank | NULL 530/530 | honest-null (insufficient IV history live) — but scores against (§3) |
| macro.dowUp | NULL 529/530 | by-design (Dow un-armed, owner decision) — null is NEUTRAL here (correct) |
| macro.fiiLongPct | NULL 530/530 | fii-bias dot un-armed on most; fiiBiasSign DID populate (−1) |
| macro.advances/declines | 0/0 in all confluence rows | broken-for-intraday feed (EOD bhavcopy source) |
| oi.spurtPricePct | 0.00 in 49 rows; max 22.2 | plausible but floor 50 unreachable |
| chart.rsi5m / rsiDaily | null | E5 higher-TF rails un-armed — expected |
| everything else (chart/oi) | populated, values plausible | ✓ capture pipeline healthy |

## 5 Counterfactuals

Not computed for this session (method v1 written after close; snapshots available so it CAN be done
retroactively). The 12 would-have-fired rows (§2.1) are the candidates: golden-crossover CE entries
12:39–14:21 at spot ~24,244–24,262, connect-the-dots CE 13:18/13:24. NIFTY drifted mildly up
into the 14:00s then faded — proper premium-path replay (README §4.2) deferred; **do it live for the
NEXT session** and backfill this one if useful for the rollup.

## 6 New data points / anomalies for future passes

- `iv_abs_band` near-miss behavior (ATM IV hovering just above 12%) — worth a distribution plot
  across sessions before touching the 10–12 band.
- `drastic_oi` ~free (96%) — collect per-session support % to pick a real `drasticFloor`.
- time-window margin constant −69 across all rows — margin encoding for time rails looks degenerate
  (cosmetic; possibly minutes-past-cutoff frozen at eval cadence).
- PE-side composite starvation on an up day — expected, but track CE/PE split per session to verify
  the PE mirror fires on down days.

## 7 Tuning candidates (status ledger)

| # | knob | current | proposed | evidence | status |
|---|---|---|---|---|---|
| 1 | `scalper.params.volume_floor` (all NIFTY YAMLs) or `ScalperGates.NIFTY_VOL` | null → 125,000 | ~15,000–20,000 (or relative k×median) | §2.1: floor > session max; 12 complete entries vetoed | PROPOSED |
| 2 | `artha.scalper.oi.ivPairMinGap` | 0.10 | 0.01–0.02 | §3: 10-pt gap between 6-strike avgs never occurs | PROPOSED |
| 3 | `artha.scalper.oi.spurtPricePct` | 50 | 5–10 | §3: observed max 22.2, 0% dot support | PROPOSED |
| 4 | breadth live producer | EOD bhavcopy → 0/0 intraday | live A/D from N50 batch quotes | §3/§4: dot+FU2 gate dead in every session | PROPOSED (build) |
| 5 | iv_rank null semantics | null scores against | null = neutral / excluded | §3/§4: honest-null punished for months | PROPOSED (code) |
| 6 | composite threshold 0.6 | keep | keep (fix inputs instead) | §3 cap math | DECIDED-KEEP |

**Single-session caveat:** one quiet VIX-12 session. Items 1–3 are structural (threshold outside the
operand's physical range — not day-dependent) and safe to tune now; everything else waits for
multi-session confirmation.
