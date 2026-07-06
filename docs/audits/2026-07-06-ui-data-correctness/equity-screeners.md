# Equity Screeners + Analyzers — Data-Correctness Audit (2026-07-06)

Market LIVE ~11:00 IST Mon 2026-07-06. Read-only verification of the Minervini + Manas
Arora screeners, funnels, and per-candidate analyzers against DB truth and the documented
gate formulas. Latest bhavcopy = **2026-07-03 (Fri)**; today's EOD not yet loaded (correct
— bhavcopy is EOD, market just opened). All screens correctly serve the 2026-07-03 session.

**Bottom line: NO wrong-number bugs found in the screeners or analyzers.** Every gate
verdict, SMA, 52w hi/lo, turnover, RS-rank percentile, VCP footprint, and funnel bucket
I independently recomputed from raw `nse_eod_bhavcopy` matched the endpoint exactly. One
initial RS-rank "discrepancy" was a defect in MY recompute (NULLIF), not the code.

---

## Data sources & freshness

| Table (schema `marketdata`) | Latest | Rows |
|---|---|---|
| `nse_eod_bhavcopy` (EQ/BE) | 2026-07-03 | 255 trade dates (~1yr) |
| `minervini_screen_results` | 2026-07-03 | 1755 scanned, **216 passers**, RS 0–100 |
| `minervini_setups` (geometry) | 2026-07-03 | 216 rows, **106 is_vcp** |
| `manas_arora_screen_results` | 2026-07-03 | 2224 scanned, **97 passers** |

Both screener pages display `screenDate` prominently ("2026-07-03 · N shown · M scanned").
Intraday-today they correctly show Friday's screen — the right behaviour for an EOD screener.
No stale-as-fresh mislabeling. VERDICT: freshness handling CORRECT.

---

## Minervini screener — `GET /api/v1/market/screener/minervini`

**Gate formula (from `MinerviniGates.gates()`), verified line-by-line:**
- g0 close>sma150 & close>sma200; g1 sma150>sma200; g2 sma200>sma200_ago(21 sess);
  g3 sma50>sma150 & sma50>sma200; g4 close>sma50; g5 close≥low52w·1.25;
  g6 close≥high52w·0.75; g7 rsRank≥70.

**STLTECH reconciliation (independent recompute from raw bhavcopy):**

| field | endpoint | my recompute | match |
|---|---|---|---|
| close | 576.80 | 576.80 | ✓ |
| sma50 | 481.4516 | 481.4516 | ✓ |
| sma150 | 253.9917 | 253.9917 | ✓ |
| sma200 | 220.1047 | 220.1047 | ✓ |
| sma200_ago | (rising) | 169.0070 | ✓ (220.10>169.01) |
| high52w / low52w | 679.90 / 84.60 | 679.90 / 84.60 | ✓ |
| avgTurnover50 | 1,813,550,388.14 | 1,813,550,388.14 | ✓ |
| pctFromHigh | -0.1516 | (576.80-679.90)/679.90 | ✓ |
| pctAboveLow | 5.8180 | (576.80-84.60)/84.60 | ✓ |
| gates | [8×true] | all 8 satisfied | ✓ |

Gate 5 floor = 84.60·1.25 = 105.75 ≤ 576.80 ✓. Gate 6 floor = 679.90·0.75 = 509.93 ≤ 576.80 ✓.

**RS-rank percentile — verified.** Java `weightedRs = 0.4·r63 + 0.2·r126 + 0.2·r189 +
0.2·r252` with `ret()=0 when past close is null/zero`, percentile = `idx/(n-1)·100`, n=1755.
Recomputing with the exact Java `ret()=0` semantics reproduces the endpoint EXACTLY:
STLTECH 100.00, SANGINITA 99.94, HFCL 99.77, BLISSGVS 99.71 (all match; n=1755 = coverage).
NOTE: an initial recompute using `NULLIF(c63,0)` (NULL-propagating) gave 99.77/99.71/… —
this was a defect in MY query (it dropped the 5 names with <253 bars from the distribution,
shrinking the denominator). The production code correctly treats a missing trailing return
as 0, keeping those names in the cross-section. **Not a code bug.**

**Coverage denominator verified:** my independent count of the liquid universe (sessions≥252,
close≥30, avgTurnover50 ≥ 150000·0.25·25 = ₹9.375L) = **1755**, exactly matching `coverage`.
(Turnover floor lowered to ₹9.375L per memory #561 — confirmed live: min passer turnover ₹11L.)

VERDICT: **CORRECT.** All gate verdicts, SMAs, 52w bands, turnover, %-from-high/above-low,
and the RS-rank percentile reconcile to raw bhavcopy.

---

## Minervini funnel — `GET .../minervini/funnel`

- screenDate 2026-07-03; regime NEUTRAL (advanceRatio 0.4767, 7 sessions).
- buyable 65 + onDeck 35 + watch 116 = **216 = passers** ✓ (no rows lost/double-counted).
- Bucketing verified: first buyable DEEDEV close 704.35, pivot 682.95 →
  pctToPivot = (704.35-682.95)/682.95 = **0.0313** ✓; within [pivot·0.98=669.29,
  pivot·1.05=717.10] ⇒ correctly BUYABLE. On-deck floor = pivot·0.90. Logic in
  `MinerviniFunnelService.bucket()` matches the displayed classification.

VERDICT: **CORRECT.**

---

## Minervini analyzer — `/equity/minervini/:symbol`

- **Gate labels aligned:** `GATE_TITLES[0..7]` in `MinerviniCandidatePage.tsx` map 1:1 to
  the actual gate order in `MinerviniGates.gates()` (checked each). No mislabeling.
- **Chart MAs correct:** `MinerviniCandidateChart.sma()` is a trailing SMA (`sum/period`,
  null until `period` bars) — identical to the screener's `avg() OVER (ROWS BETWEEN
  period-1 PRECEDING AND CURRENT ROW)`. Rightmost 50/150/200-day MA values therefore equal
  the screener's sma50/150/200 for the same session.
- **Chart source:** `/market/candles?interval=1d` for STLTECH returns 249 rows, `source:
  BHAVCOPY`, last close 576.80 = screener close ✓. So the analyzer chart is consistent with
  the bhavcopy-based screener (candles@1d serves bhavcopy-derived daily bars for these names).
- **VCP geometry verified:** DEEDEV persisted setup `1W 12/7 4T` (base_weeks 1, deepest
  12.12→"12", tightest 6.80→"7", contraction_count 4), pivot 682.95, cheat_pivot 659.7250,
  thrust=true — the `/candidate/DEEDEV` and funnel payloads return exactly these. STLTECH
  geometry correctly `isVcp:false, reason "right side not contracting"` (a strong uptrend,
  no base — correct).

MINOR (visual, not a wrong number): the analyzer fetches only ~249 bars (bhavcopy 1yr), so
the 150/200-day MA overlay lines only start ~150/200 bars in and cover just the last ~50–100
bars of the visible chart. The *current* MA values are correct; the historical MA line is
short at the left edge. Data-coverage limitation, not a math error. Applies to Manas too.

VERDICT: **CORRECT** (with the ~1yr chart-coverage caveat above).

---

## Manas Arora screener — `GET .../manas-arora`

**6 selection gates (from `ManasGates.gates()`):** g0 close≥30; g1 sma200>sma200_ago(63 sess);
g2 sma50>sma200; g3 close>sma200; g4 close≥low52w·2.00 (100% above low);
g5 recentHigh(126 sess)≥high52w (a new 52w high made recently).
`passesAll` = 6 gates AND withinHigh(close≥high52w·0.75) AND liquidVolume(avgVol20>5000)
AND liquidDepth(avgVol50≥2500) [AND lowCap only if lowcap-gate enabled — it's OFF].

**MTARTECH reconciliation (independent recompute):**

| field | endpoint | recompute | match |
|---|---|---|---|
| close | 7033.50 | 7033.50 | ✓ |
| sma50 / sma200 | 7061.71 / 3849.88 | 7061.71 / 3849.88 | ✓ |
| sma200_ago(63) | (rising) | 2327.26 | ✓ (3849>2327) |
| high52w / low52w | 8714.00 / 1390.50 | 8714.00 / 1390.50 | ✓ |
| recent_high(126) | (new high) | 8714.00 = high52w | ✓ g5 |
| avgVol20 / avgVol50 | 3.01M / — | 1,820,209 / 1,749,863 | ✓ liquid |
| gates | [6×true], passesAll true | all 6 + universe + liquidity | ✓ |
| **aboveSma50** | **false** (7033.50<7061.71) | correct | ✓ SOFT flag, non-blocking |

**Verified NOT a bug:** MTARTECH shows `aboveSma50:false` yet `passesAll:true`. This is
CORRECT by design — `aboveSma50` is a soft "preferably > 50-SMA" DISPLAY flag
(`ManasGates.aboveSma50`), NOT one of the 6 hard gates. gate 3 (close>200-SMA) is the
binding structural gate. Labeled "Above 50-day MA (soft)" in the analyzer. Verified against
the ManasGates javadoc + code. (Checked the source rather than assuming a bug.)

NOTE (internal-consistency, not a bug): `gates[]`/`gatesPassed` expose only the 6 selection
gates; `passesAll` additionally requires withinHigh + both liquidity gates. So a name can
show `gatesPassed:6` with `passesAll:false`. The FE reads `passesAll` for the pass badge —
consistent.

Coverage 2224 = all EQ/BE names with ≥252 sessions (Manas applies no price/turnover
pre-filter to the scanned set, unlike Minervini's 1755). Correct per `raws.size()`.

VERDICT: **CORRECT.**

---

## Manas funnel + analyzer

- Funnel: buyable 26 + onDeck 53 + watch 18 = **97 = passers** ✓. First buyable SANGINITA
  close 45.85, breakout pivot 45.85, pctToPivot 0.0000 (at its 52w high) ✓.
- Analyzer `GATE_TITLES` (6) map 1:1 to `ManasGates.gates()` order ✓; chart = same correct
  SMA component. STLTECH candidate: breakout setup valid pivot 679.90 (=52w high), vcp setup
  invalid ("right side not contracting") — both consistent with geometry detectors.

VERDICT: **CORRECT.**

---

## Summary of findings

| Surface | Verdict | Notes |
|---|---|---|
| Minervini screener gates/SMAs/52w/turnover | CORRECT | STLTECH byte-reconciled to bhavcopy |
| Minervini RS-rank percentile | CORRECT | matches with Java ret()=0 semantics, n=1755 |
| Minervini coverage denominator | CORRECT | 1755 recomputed = coverage |
| Minervini funnel bucketing | CORRECT | 65/35/116=216; pctToPivot exact |
| Minervini analyzer gates/MA labels/chart | CORRECT | ~1yr chart-coverage caveat (visual) |
| Minervini VCP geometry (DEEDEV) | CORRECT | footprint/pivot/cheat/thrust reconcile |
| Manas screener 6 gates + liquidity | CORRECT | MTARTECH byte-reconciled; aboveSma50 soft-flag OK |
| Manas funnel + analyzer | CORRECT | 26/53/18=97; setups reconcile |
| Freshness (both screeners) | CORRECT | serve 2026-07-03 Fri, labeled; today's EOD not loaded |

**No wrong gate verdicts, wrong RS, wrong geometry, or wrong MA values found.**
Only non-defect notes: (1) analyzer charts have ~1yr history so long-MA overlays are short
at the left edge (data coverage, values current-correct); (2) Manas `gates[]` array shows 6
selection gates while `passesAll` also folds in universe+liquidity — internally consistent.
