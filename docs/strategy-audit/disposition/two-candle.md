# Two Candle Theory (Siva #1) — gap disposition

Every non-FULL row from `docs/strategy-audit/two-candle.md` (the audit table, lines 16–55) is assigned
exactly one disposition so no gap is left unaccounted. Source non-FULL rows = **34** (PARTIAL/NONE/
MANUAL_COVERED). Dispositions reference the two follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| 1st+3rd candle SUBSTITUTE when 2nd misses volume gate | 3.1 S21(a) / 5.1 | NONE | AUTOMATE_PKG | `two-candle-volume-substitution` — encode the S21(a) 1st-or-3rd fallback when the 2nd candle is light |
| RSI band CE 50–75 vs §4.2's 60–80 (40–60 no-trade) | 3.1 #3 / 4.2 | PARTIAL | UNCERTAIN_OWNER | Code uses §4.2's 60–80; §3.1 says 50–75. Which band fires is an owner design choice (band differs by design) |
| RSI cool-off: don't enter while RSI >80 (>85→wait ~70–80), re-enter on pullback candle | 3.1 S21(f)/S24(a) | NONE | AUTOMATE_PKG | `rsi-cooloff-pullback-entry` — RSI series available; add cool-off + pullback-candle re-entry |
| All soldiers (PSAR, VWMA, ST, VWAP) on far side — conjunctive hard gate | 3.1 #8 | PARTIAL | COVERED_FU2 | FU2 WI-1 promotes `indicator-alignment` (the "ALL soldiers far side" conjunction) to a hard gate |
| OI build-up confirms direction (LB/SC bull, SB/LU bear) — hard not soft | 3.1 #2,#6 | PARTIAL | COVERED_FU2 | FU2 WI-2 promotes the futures-OI quadrant dot to a hard gate (`futures-oi-gate`, forward-only) |
| HIGH one-sided dOI difference (marginal = no trade) | 3.1 #6 / filters | NONE | COVERED_FU2 | FU2 = the ≥50% call-put dOI imbalance is one of the 4 promoted dots (the `oi-cross-filter` 50% imbalance gate) |
| ">50% Trending-OI difference → be aggressive" (sizing tie) | 3.1 S21(g) | NONE | AUTOMATE_PKG | `intraday-positional-oi` — imbalance % is derivable; tie position size to the Trending-OI directional gap |
| Trading zone: bull from Support / bear from Resistance; opposing S/R nearby = low prob | 3.1 #7 / 4.11 | MANUAL_COVERED | COVERED_EXISTING | `level_respected` (ScalperManualChecks, doc_ref 4.11) — shipped 7-item checklist |
| Avoid chasing parabolic/vertical move | 3.1 filters | MANUAL_COVERED | COVERED_EXISTING | `not_parabolic` (ScalperManualChecks, doc_ref 3.1) — shipped 7-item checklist |
| VIX: down supports CE / up supports PE; abnormal VIX = warning | 3.1 filters / 4.5 | PARTIAL | AUTOMATE_PKG | `directional-vix-gate` — wire VIX direction (India VIX candles exist; explicitly OUT of FU2 scope). Abnormal-spike portion already carried by `vix_normal` manual check; FU1 adds `vix_regime_bands` for the absolute bands |
| Global cues (DOW/Asian/crude/USD) match direction; 3:15 PM re-check | 3.1 filters / 4.7 | MANUAL_COVERED | COVERED_EXISTING | `global_cues_ok` (ScalperManualChecks, doc_ref 4.7) — shipped 7-item checklist (Dow LTP auto-wiring is a separate pkg, not needed for coverage) |
| Premium 100–250 Nifty / 250–400 BankNifty band (backtest ignores band) | 3.1 #6 / 4.9 | PARTIAL | AUTOMATE_PKG | `strike-premium-band-backtest` — make the backtest selector honour the premium band (live StrikePicker already does) |
| IV rising in strike for bull / falling for bear (per-strike Desirable) | 3.1 desirables / 4.6 | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — live per-strike IV trend series; only a 6-strike avg pair + IV-rank proxy exists today |
| SL alternate: use VWAP when move already extended before entry | 3.1 exit / edge | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — VWAP available; add "switch SL to VWAP if extended" branch |
| SL alt: 1st-candle HIGH or 2nd-candle LOW when 1st candle very large | 3.1 S21(b)/S24(c) | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — large-1st-candle alternate SL + size-for-deeper-risk |
| SL by trader type: positional 1st-candle anchor vs scalper previous-candle trail | 5.1 S24(b) | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — add previous-candle trailing + positional-vs-scalper SL mode |
| Target: ride momentum to next S/R; aim 1–2%; manage at least to VWAP | 3.1 exit | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — add a 1–2% profit target (next-S/R target weakly automatable, no S/R engine) |
| Time exit: exit if VWAP breaks WITH volume; fake breakout = no follow-up | 3.1 exit | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — add the "with volume" / fake-breakout discrimination to the VWAP-cross exit |
| Trailing / scale-out: conservative → PSAR trail → Supertrend trail | 3.1 exit / scaling | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — PSAR-then-Supertrend trailing (both indicators present) |
| Averaging ladder: 3%→+7%→≤~20%, add only at ST/VWAP/VWMA, never after SL breach | 3.1 risk / S22(d) | NONE | KEEP_MANUAL_NEW | Manual scale-in ladder by hand at known levels; no scale-in engine (`max_positions:1`, fixed budget) — future manual-check candidate / trader discretion |
| Only ONE ST/VWAP-rejection trade per 2-candle event | 3.1 S21(c)/S23 | NONE | AUTOMATE_PKG | `two-candle-event-controls` — per-event rejection-trade counter |
| 2-candle + Golden Crossover on same side = high-conviction combo | 3.1 S21(d) | NONE | AUTOMATE_PKG | `two-candle-event-controls` — cross-strategy combo detection (GC gate exists) |
| Skip bearish 2-candle when RSI already < ~20 (bounce risk) | 3.1 bear #3 / edge | PARTIAL | ACCEPT_BY_DESIGN | Coincidentally covered by the PE `>20` band floor; no explicit "prefer Supertrend-rejection reversal" routing — acceptable per audit |
| Window upper bound: valid well before 2:30 PM (no fresh entry after) | 3.1 S23/S24 / 5.1 | PARTIAL | AUTOMATE_PKG | `entry-window-230pm` — enforce the S24 9:45–2:30 fresh-entry window at the seam (currently only blocks >=15:30) |
| Candle right at the open is not valid — wait ~9:42–9:45 for a formed candle | 5.1 S23 | PARTIAL | ACCEPT_BY_DESIGN | The 09:45 `NO_TRADE_BEFORE` floor already enforces a formed candle — acceptable per audit |
| Sensex application: read setup on NIFTY, ~3× point scaling, ITM Sensex options | 3.1 S23 / 4.16 | PARTIAL | AUTOMATE_PKG | `sensex-point-scaling` — signal/strike/execute decoupling is automated; the ~3× SL/target point-scaling is not |
| News/event overrides the data — keep off on impending events | 3.1 filters / 2.13 | MANUAL_COVERED | COVERED_EXISTING | `news_clear` (ScalperManualChecks, doc_ref 2.13) — shipped 7-item checklist |
| Direction: CE-only / PE-only per YAML (no PE variants seeded) | 3.1 bull/bear | PARTIAL | AUTOMATE_PKG | `seed-pe-variants` — code supports the PE side; add `scalp-two-candle-*-pe` YAMLs to register the bearish leg |
| Multi-TF RSI cross-check: CE RSI(5m)<75/80 & RSI(D)<75; PE RSI(5m)>25/20 & RSI(D)>25 | 4.2 / 3.1 / 6.1 | NONE | AUTOMATE_PKG | `multi-tf-rsi-crosscheck` — read 5m + Daily RSI series and gate the higher-TF caps (only 3m rsi14 gated today) |

### Disposition counts

- COVERED_EXISTING: 4
- COVERED_FU1: 0
- COVERED_FU2: 3
- AUTOMATE_PKG: 18
- KEEP_MANUAL_NEW: 1
- ACCEPT_BY_DESIGN: 2
- UNCERTAIN_OWNER: 1
- **Total non-FULL rows: 29** (matches the 29 non-FULL rows in `two-candle.md` lines 16–55: 40 table rows − 11 FULL)

### AUTOMATE_PKG themes (for the synthesizer)

- `two-candle-volume-substitution` — 1st+3rd candle volume fallback (S21a)
- `rsi-cooloff-pullback-entry` — RSI >80/85 cool-off + pullback-candle re-entry
- `intraday-positional-oi` — >50% Trending-OI difference → size-up tie
- `directional-vix-gate` — wire VIX direction (OUT of FU2; VIX feed)
- `strike-premium-band-backtest` — backtest selector honours premium band
- `iv-per-strike` — live per-strike IV trend
- `trade-management-targets-trailing` — VWAP-extended SL, large-1st-candle SL, trader-type/previous-candle
  trail, 1–2% target, VWAP-break-with-volume exit, PSAR/Supertrend trailing (6 rows fold into this theme)
- `two-candle-event-controls` — one-rejection-trade cap + 2-candle×Golden-Crossover combo
- `entry-window-230pm` — enforce the 9:45–2:30 fresh-entry window at the seam
- `sensex-point-scaling` — ~3× SL/target point-scaling for Sensex
- `seed-pe-variants` — register bearish PE two-candle YAMLs
- `multi-tf-rsi-crosscheck` — 5m + Daily RSI confirmation gate
