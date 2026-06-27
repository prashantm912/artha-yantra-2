# Market Movers (Siva #3) — gap disposition

Every non-FULL row from `docs/strategy-audit/market-movers.md` (every PARTIAL / NONE; the two FULL rows
— the 09:45 floor and the index-level breadth gate — are excluded) is assigned exactly ONE disposition
home. 23 gap rows total.

Disposition legend: COVERED_EXISTING (shipped 7-item `ScalperManualChecks`) · COVERED_FU1 (one of the 9
manual checks added by `2026-06-27-followup1-expand-manual-checks.md`) · COVERED_FU2 (one of the 4 dots
promoted to hard gates by `2026-06-27-followup2-soft-dots-to-hard-gates.md`) · AUTOMATE_PKG (automatable,
assign a work-package theme) · KEEP_MANUAL_NEW (genuinely manual-only, not in FU1) · ACCEPT_BY_DESIGN
(soft-by-design / derived-history artifact / low value) · UNCERTAIN_OWNER (ambiguous / owner's call).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| Universe = day-leading **F&O equity stocks** via OI-Pulse Market Movers screener; instruments = stock futures / cash, "no stock options" | §3.3 Instruments; §6.3 `instruments` | NONE | AUTOMATE_PKG | `equity-fno-universe-screener` — the strategy's whole vehicle; needs an equity-futures (+cash) capture expansion (`FuturesMoversService` is index-only today) then a Top-Gainers/Losers screener + equity-option/equity-future execution path. Foundational; all other equity-per-stock packages depend on it. |
| Stock at **8-day high (long) / 8-day low (short); 9-day better** (Min. B.O. Days) | §3.3 Entry Bull/Bear 2; §6.3 `entry_conditions` | NONE | AUTOMATE_PKG | `nday-breakout-extremes` — rolling N-day high/low per stock; trivial once per-stock candles exist (depends on equity-fno-universe-screener). |
| **OH/OL flag** — OL (Open=Low) for longs / OH (Open=High) for shorts | §3.3 Entry Bull/Bear 3(b); §6.3 `filters` | NONE | AUTOMATE_PKG | `per-stock-ohlc-flags` — wire the existing `OpenHighLow`/`OpenHighLowGate` primitive to this strategy + feed per-stock OHLC (depends on equity-fno-universe-screener). |
| **OI interpretation** — LB (best)/SC for longs; SB (best)/LU for shorts | §3.3 Entry Bull/Bear 3(c); §6.3 `filters` | NONE | AUTOMATE_PKG | `per-stock-oi-interpretation` — `OiInterpretation.classify` already exists; needs an equity-futures OI capture so the confluence reads the picked stock's OI, not the index. |
| Daily-RSI screen — not past RSI 75 (bull) / below RSI 40 (bear) on **Daily** TF | §3.3 Setup 6 / Filters; §6.3 `filters`, `setup_preconditions` | NONE | AUTOMATE_PKG | `per-stock-daily-rsi` — add a daily-timeframe RSI per stock (data-dependent on the equity universe). |
| Intraday **RSI(5m) below 75/80 (long) / above 25/20 (short)**; examples cite >60 | §3.3 Entry Bull/Bear 4; §6.3 `entry_conditions` | PARTIAL | AUTOMATE_PKG | `per-stock-intraday-series` — the band gate runs but on the NIFTY-future surrogate at 3m not the stock at 5m; needs per-stock 5m series (depends on equity-fno-universe-screener). |
| Entry trigger: **long after price moves above VWAP**, pullback near VWMA/ST/VWAP | §3.3 Entry Bull 5; §6.3 `entry_conditions` | PARTIAL | AUTOMATE_PKG | `per-stock-intraday-series` — VWAP-reclaim trigger is automated on the index surrogate; needs the actual mover stock's own VWAP/VWMA/ST series. |
| Indicator settings: **VWAP, VWMA 20, SuperTrend (10,2), RSI 14, Volume 20** | §3.3 Filters; §6.3 `indicators` | PARTIAL | AUTOMATE_PKG | `volume-ma-indicator` — declare a Volume-20 MA indicator (the doc's "Volume 20" is missing; only a static volume floor is gated). The other settings exist but apply to the index surrogate (covered by `per-stock-intraday-series`). |
| Desirables: **ST & VWMA crossover with SAR switching**; S/R-line breakout; **IV rising (bull)/falling (bear)** on the strike | §3.3 Filters/Desirables; §6.3 `filters`, `indicators` | PARTIAL | AUTOMATE_PKG | `per-stock-strike-iv-direction` — IV is read at index level; needs equity-option per-strike IV rising/falling. The ST/VWMA/SAR cross rides `per-stock-intraday-series`; the **S/R-line breakout is discretionary** (see KEEP_MANUAL note in the S/R-ambiguity row). |
| **Prefer high-volume / liquid stocks** for clean entry/exit | §3.3 Setup 4 / Filters (Volume) | PARTIAL | AUTOMATE_PKG | `per-stock-liquidity-ranking` — rank by ADV once equity volumes are captured; only a static index volume floor exists today. |
| **Top-constituent direction / index weightage** read (e.g. HDFC Bank 29.46% of Nifty Bank) | §3.3 S21 update (c); §4.14 ref | NONE | COVERED_FU1 | FU1 check `constituent_contribution` (§4.14.4) — "index direction confirmed by its heaviest constituents and sector sync." Manual reminder; full automation (static weight table + per-stock moves) is data-dependent and deferred. |
| **Target = 1–2%** (~1% in first morning hour) | §3.3 Exit (Target); §6.3 `exit_conditions.target` | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — a percent-target exit rule is a standard primitive; YAML header calls it a live-management note, but it is engine-automatable. |
| **Stop-loss = no rigid SL / no fixed OI% threshold**; reference = 1st-candle low (long)/high (short) | §3.3 Exit (Stop-loss) + Risk; §6.3 `exit_conditions.stop_loss` | PARTIAL | KEEP_MANUAL_NEW | The structural `entry-candle-stop` anchor exists (on the index surrogate); "no rigid SL / risk-appetite sizing" is inherently discretionary. Future manual-check candidate beyond FU1 (trader sizes the SL by own risk appetite on the stock). |
| Time/hold: **intraday default**; positional only if closing OI = Long Build-up (avoid through Long Unwinding) | §3.3 Exit (Time/Positional) + Risk; §6.3 `exit_conditions.time_exit`, `risk_management` | PARTIAL | AUTOMATE_PKG | `intraday-positional-oi` — the intraday default is encoded; the EOD-OI overnight-carry-on-LB logic is deferred and automatable given EOD per-stock OI. |
| **Short side** (8/9-day low + OH + Short Build-up / Long Unwinding) | §3.3 Entry — Bearish; §6.3 `entry_conditions.bearish` | NONE | AUTOMATE_PKG | `short-side-mirror` — the entire bearish path (`direction: long`, CE only) is a deferred mirror of the long side; automatable as a PE/short variant. |
| Edge case: **adverse move → check volume** (high volume = exit, low volume = may pursue) | §3.3 Execution/Edge; §6.3 `edge_cases` | NONE | AUTOMATE_PKG | `volume-conditional-exit` — a volume-conditional adverse-exit rule; needs the stock's volume (depends on the equity universe). |
| Edge case: **avoid names with OI heavily on both call and put sides** (ambiguous) | §3.3 Execution/Edge; §6.3 `edge_cases` | NONE | AUTOMATE_PKG | `per-stock-chain-both-sides-oi` — a CE/PE both-sides-loaded ambiguity check per stock; automatable given per-stock chain OI. |
| **Alternative entry** — considerable ΔOI **and >1% price change** (or intraday S/R trades) | §3.3 Entry Bull/Bear 5; §6.3 `entry_conditions` | NONE | AUTOMATE_PKG | `pct-price-move-gate` — the >1% intraday price-move threshold is a trivial gate; the ΔOI leg needs per-stock OI; the S/R-trade variant is discretionary (KEEP_MANUAL). **MISSED by v1.** |
| **Radar-building progression** — 1–2d high → 3–4d high w/ OL → 8–9d breakout | §3.3 Setup 7; §6.3 `setup_preconditions` | NONE | AUTOMATE_PKG | `nday-breakout-extremes` — multi-day-extreme staging is the same rolling-N-day-extreme machinery as the 8/9-day filter; no N-day extreme is computed today. **MISSED by v1.** |
| **OI Spurt 4-quadrant cue** (refer the stock's OI Spurt quadrants) | §3.3 Filters; §6.3 `indicators`, `filters` | PARTIAL | AUTOMATE_PKG | `per-stock-oi-spurt` — the `oi_spurt` dot IS scored, but at the option-root index, not the picked stock; needs equity OI capture. **MISSED by v1.** |
| **Right-side "New High/Low Maker" panel** — live intraday new highs/lows for support (Gainers) / rejection (Losers) | §3.3 Filters; §6.3 `filters` | NONE | AUTOMATE_PKG | `equity-fno-universe-screener` — the live new-intraday-high/low maker feed is part of the equity-screener live data (no separate source); folds into the screener package. **MISSED by v1.** |
| **Large-cap-only filter + operator low-volume trap** (hold long only above the stock's VWAP) | §3.3 S22 update (a)/(b); §5.3 | NONE | AUTOMATE_PKG | `per-stock-liquidity-ranking` — large-cap classification + per-stock intraday-volume; the VWAP-hold-only-above leg rides `per-stock-intraday-series`. The "operator trap / abandon a name that ran on no volume" judgement leans discretionary but the data filter is automatable. **MISSED by v1.** |
| **Short-side overnight (STBT)** — 8/9-day-low Short-Build-up carried only if Futures OI closing at day-high with price at day-low | §3.3 S22 update (f); §5.3 | NONE | AUTOMATE_PKG | `intraday-positional-oi` — the STBT close-OI-extreme carry condition; needs a short variant (`short-side-mirror`) + EOD per-stock OI. **MISSED by v1.** |

## Summary

- **23 gap rows** dispositioned (25 table rows − 2 FULL: the 09:45 floor and the index-level breadth gate).
- **COVERED_EXISTING: 0** — the shipped 7-item `ScalperManualChecks` is strategy-agnostic (news / level /
  not-parabolic / regime / VIX / global-cues / clean-setup) and covers **none** of the Market-Movers-specific
  gaps (confirmed by the source-doc "Manual checklist coverage" note, lines 75–78).
- **COVERED_FU1: 1** — `constituent_contribution` (the top-constituent / index-weightage cue).
- **COVERED_FU2: 0** — FU2 promotes index-level dots (indicator-alignment, futures-OI-quadrant, breadth,
  basis) on the NIFTY-future surrogate. Market Movers' gaps are all **per-stock** (the picked mover's own
  OI / OHLC / VWAP / RSI), which those index-level promotions do not address; none map to FU2.
- **AUTOMATE_PKG: 21** — dominated by per-stock equity-universe themes (all gated on
  `equity-fno-universe-screener`).
- **KEEP_MANUAL_NEW: 1** — discretionary risk-appetite stop sizing.
- **ACCEPT_BY_DESIGN: 0.**
- **UNCERTAIN_OWNER: 0.**

### AUTOMATE_PKG themes (for the synthesizer)

- `equity-fno-universe-screener` — the foundational equity-futures/cash capture + Top-Gainers/Losers screener
  + equity-option/future execution path + live New-High/Low-Maker feed (rows: universe, New High/Low Maker).
- `nday-breakout-extremes` — rolling N-day high/low per stock (rows: 8/9-day breakout, radar-building staging).
- `per-stock-ohlc-flags` — wire the OH/OL primitive + per-stock OHLC.
- `per-stock-oi-interpretation` — LB/SC/SB/LU on the picked stock (needs equity-futures OI).
- `per-stock-daily-rsi` — daily-TF RSI per stock.
- `per-stock-intraday-series` — per-stock 5m VWAP/VWMA/ST/RSI series (rows: intraday RSI 5m, VWAP-reclaim
  trigger, and the ST/VWMA/SAR-cross + VWAP-hold legs of two other rows).
- `volume-ma-indicator` — declare the Volume-20 MA indicator.
- `per-stock-strike-iv-direction` — equity-option per-strike IV rising/falling.
- `per-stock-liquidity-ranking` — ADV ranking + large-cap classification (rows: liquid-stock preference,
  large-cap-only filter).
- `trade-management-targets-trailing` — 1–2% percent-target exit rule.
- `intraday-positional-oi` — EOD-OI overnight-carry-on-LB + STBT close-OI-extreme carry.
- `short-side-mirror` — PE/short bearish variant.
- `volume-conditional-exit` — adverse-move volume-conditional exit.
- `per-stock-chain-both-sides-oi` — both-sides-OI ambiguity skip per stock.
- `pct-price-move-gate` — >1% intraday price-move alternative-entry gate.
- `per-stock-oi-spurt` — OI-Spurt 4-quadrant cue on the picked stock.
