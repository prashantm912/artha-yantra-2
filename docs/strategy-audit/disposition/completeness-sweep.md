# Completeness sweep — Sections 1, 5, 7 + orphan check — gap disposition

Every non-FULL row from BOTH gap tables of `docs/strategy-audit/completeness-sweep.md` is assigned exactly
one disposition so no gap is left unaccounted: the §5/§7 main table (lines 18–39, 22 rows − 3 FULL at
L25/L28/L38 = 19 non-FULL) + the cross-cutting feed-gaps table (lines 86–88, 3 non-FULL). Source non-FULL
rows = **22**. (The orphan-map table at lines 45–74 is a completeness mapping, not a gap table — every row is
"Orphan? no" — so it is not dispositioned. The expected baseline of 21 predates one of the three v2-added
§5/§7 rows.) Dispositions reference the two follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| S22-resolved Open=High premium bands (BN 250–550 / N 150–350 operative) — superseded set encoded | 5.2 / 7 | PARTIAL | AUTOMATE_PKG | `strike-premium-band-backtest` — swap the `PREMIUM` map from the superseded N 100–250 to the S22-resolved N 150–350 / BN 250–550 |
| S22-resolved Hero-Zero numeric SL (BN ~75 / N ~30) + 3:10 no-move exit + 10%-of-profits sizing | 5.7 / 7 | PARTIAL | AUTOMATE_PKG | `sr-levels-targets-stops` — index-scaled point SL + a 15:10 no-move time exit are engine-expressible (only the after-14:30 entry gate exists); sizing rides `probability-graded-sizing` |
| S21-resolved Golden Crossover support-form SL = the Supertrend level | 5.6 / 7 | PARTIAL | AUTOMATE_PKG | `sr-levels-targets-stops` — only the breakout-form (crossover-candle) SL is wired; add the support-form Supertrend-level SL (engine computes Supertrend per bar) |
| S21-resolved BTST/STBT EOD OI quadrants (BTST SC=Q3/LB=Q1; STBT SB=Q2/LU=Q4) | 5.8 / 7 | NONE | AUTOMATE_PKG | `oi-cross-hard-gate` — the EOD OI-quadrant carry gate is deferred (documented in a YAML comment); `OiQuadrant` + the EOD reader exist |
| S24 Two-Candle overbought-defer: RSI>85 → wait to cool to ~75, enter on the pullback candle | 5.1 | NONE | AUTOMATE_PKG | `rsi-band-per-strategy` — no >85 defer/cool branch; RSI is computed per bar |
| S24 Connect-the-Dots RSI booking ladder (book 75–80 / 25–20, re-enter lower) | 5.10 | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — only the entry band is encoded; the booking-ladder is an exit/scale concern not modelled |
| S24 hourly-new-high cadence: no fresh high in ~1h + ~30-pt box = erosion → exit | 5.10 | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — a cadence/erosion-exit detector; bar series + session highs are available |
| S24 no-trade box: price boxed between ST/VWMA and VWAP → 1–2 lots, wait for break | 5.10 / 5.6 | MANUAL_COVERED | COVERED_EXISTING | `regime_ok` (ScalperManualChecks) covers the whole-day choppiness; the per-bar ST/VWMA↔VWAP box is a future `probability-graded-sizing` refinement |
| S24 Gap Theory volume-direction test + 30–40-min give-up timer + 50–60-pt SL | 5.4 | PARTIAL | AUTOMATE_PKG | `gap-theory-controls` — the fill-direction volume test, give-up timer, and point SL are not encoded (only the gap-fill pre-gate + structural stop) |
| S24 Trend-Change counter-trend volume veto: skip a counter-move backed by >125K Nifty volume | 5.12 | NONE | AUTOMATE_PKG | `trend-change-controls` — no counter-move volume veto today (keys on structure break + ≥50% OI + 2-candle) |
| S24 BTST validity: held VWAP/ST into the close after the morning move; size 5–10%; exit early next AM | 5.8 | PARTIAL | AUTOMATE_PKG | `oi-cross-hard-gate` — the VWAP/ST hold-check rides the same BTST EOD gate; the 5–10% sizing cap is `probability-graded-sizing` |
| S22/23/24 Straddle entry = combined premium breaks its own VWAP w/ volume; long needs LOW IV; SL at that VWAP | 5.11 / 3.11 / 7 | PARTIAL | COVERED_FU1 | FU1 `straddle_vwap_entry` manual check; the combined-premium series cannot be recomputed on the replay seam (live-only automation) |
| S5.x latest-wins POINT/percent targets (GC +200–300 BN/+50–100 N; O=H ~40–50; Movers ~1–2%) | 5.6 / 5.2 / 5.3 / 7 | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — no `take_profit` exit in any YAML; a fixed-target exit is engine-expressible (doc values are per-instrument/structural) |
| S7 Two-Candle: 5-minute an allowed primary TF? — UNCERTAIN | 7 | UNCERTAIN | UNCERTAIN_OWNER | Doc-internal ambiguity (slides 3/5m, manual 3m); the runner now accepts 3m/5m — owner picks the primary TF |
| S7 Trending-OI ≥50% Call-vs-Put: day-cumulative or interval? — UNCERTAIN | 7 | UNCERTAIN | UNCERTAIN_OWNER | Code chose the interval reading; owner sign-off that the interval (windowed dOI) is the intended one |
| S7 Golden Crossover bearish RSI >25 vs <25 — UNCERTAIN | 7 | PARTIAL | UNCERTAIN_OWNER | Doc conflict (deck >25 vs grid <25); code uses the §4.2 PE 20–40 band — owner confirms the bearish RSI cut |
| S7 Hero-Zero bearish mirror (exact PE band/offset/triggers) — UNCERTAIN | 7 | PARTIAL | UNCERTAIN_OWNER | Doc says only "vice versa for puts"; owner verifies the PE mirror band/offset |
| S7 Two-Candle bullish RSI cap "75 or 80?" — UNCERTAIN | 7 | PARTIAL | UNCERTAIN_OWNER | Code uses §4.2 60–80, matching neither §7 reading (50–75 / 50–75/80); owner sign-off on the band (both floor and cap diverge) |
| S22-resolved Morning-Trade Q3: act off prior-day EOD + today's open when morning data is unhelpful | 5.9 / 7 | PARTIAL | COVERED_FU1 | FU1 `pre_open_bias` / `time_of_day_vwap` cover the prior-EOD-vs-open read; the prior-EOD OI direction is not in the live macro (discretionary today) |
| India VIX directional gate (4.5 / 4.14.1 / 4.17.5 bands + Price↑VIX↓ rules) | 4.5 | PARTIAL | AUTOMATE_PKG | `directional-vix-gate` — the `vix` dot is starved (null macro); wire a VIX endpoint into `MarketOiClient.macro` (OUT of FU2; `vix_normal` + FU1 `vix_regime_bands` cover it manually) |
| FII/DII participant-wise OI directional bias (4.13 / 4.17.4 L/S ratio) | 4.13 | NONE (populated-but-unused) | AUTOMATE_PKG | `fii-dii-bias` — `fiiLongPct` is fetched but has zero readers; add the consuming dot/gate (FU1 `fii_ls_ratio` adds the manual read) |
| Global cues beyond Dow: Dollar index, Asian markets, Crude Oil (4.7) | 4.7 | MANUAL_COVERED | COVERED_EXISTING | `global_cues_ok` (ScalperManualChecks, doc_ref 4.7) covers all four; only Dow is partly wired (into analytics, not the scalper macro) — DXY/Asia/Crude auto-feed is `global-cues-feed` |

### Disposition counts

- COVERED_EXISTING: 2
- COVERED_FU1: 2
- COVERED_FU2: 0
- AUTOMATE_PKG: 13
- KEEP_MANUAL_NEW: 0
- ACCEPT_BY_DESIGN: 0
- UNCERTAIN_OWNER: 5
- **Total non-FULL rows: 22** (main §5/§7 table lines 18–39: 22 rows − 3 FULL at L25/L28/L38 = 19; plus the cross-cutting feed-gaps table lines 86–88: 3; the orphan-map table lines 45–74 is excluded as a completeness mapping, not a gap table)

### AUTOMATE_PKG themes (for the synthesizer)

- `strike-premium-band-backtest` — swap to the S22-operative O=H premium bands
- `sr-levels-targets-stops` — Hero-Zero point SL + 3:10 exit, Golden-Crossover support-form Supertrend SL, per-strategy point/percent targets (3 rows)
- `oi-cross-hard-gate` — BTST/STBT EOD OI-quadrant carry gate + VWAP/ST hold-check (2 rows)
- `rsi-band-per-strategy` — Two-Candle RSI>85 overbought-defer/cool branch
- `trade-management-targets-trailing` — RSI booking ladder + hourly-new-high erosion exit (2 rows)
- `gap-theory-controls` — fill-direction volume test + 30–40-min give-up timer + 50–60-pt SL
- `trend-change-controls` — counter-trend >125K Nifty volume veto
- `directional-vix-gate` — wire a VIX endpoint into the scalper macro (OUT of FU2)
- `fii-dii-bias` — consume the dead-wired `fiiLongPct` into a dot/gate
- `global-cues-feed` — (referenced for the §4.7 Dollar/Asian/Crude auto-feed; the row itself is COVERED_EXISTING)
