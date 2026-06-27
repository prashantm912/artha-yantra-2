# Disposition — Gap Theory (Siva #4)

Every NON-FULL row from `docs/strategy-audit/gap-theory.md` (every PARTIAL / NONE / MANUAL_COVERED
row = the gaps for this dimension) is assigned exactly ONE disposition so that no gap is left
unaccounted. Source table has 26 rows, 9 FULL → **17 non-FULL rows dispositioned below**.

Disposition legend: COVERED_EXISTING (shipped 7-item `ScalperManualChecks`) · COVERED_FU1 (one of the
9 manual checks added by `2026-06-27-followup1-expand-manual-checks.md`) · COVERED_FU2 (one of the 4
soft dots promoted by `2026-06-27-followup2-soft-dots-to-hard-gates.md`: indicator-alignment,
futures-OI quadrant, breadth, basis) · AUTOMATE_PKG (automatable, not in FU1/FU2 → work-package theme)
· KEEP_MANUAL_NEW (manual-only judgement, not in FU1) · ACCEPT_BY_DESIGN (soft/derived-history artifact,
wontfix) · UNCERTAIN_OWNER (owner's call / ambiguous).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| After the fill, trade WITH the overall/prevailing trend (not the short move that made the gap) | §3.4 L605/L638; §6.4 | PARTIAL (live FULL on bias) | ACCEPT_BY_DESIGN | Live path already HARD-enforces the 1h-Supertrend `bias60m` prevailing-trend AND-term; only the chart-only BACKTEST proxy lacks it. Backtest fidelity is a known derived-history split, not a missing capability — wontfix. |
| High-probability variant: measure gap from prior candle HIGH→open (bull) / LOW→open (bear) | §3.4 L595; §6.4 setup | NONE | AUTOMATE_PKG | `gap-highlow-variant` — candle high/low are in the series; add the stricter prior-high/low→open gap detector as an opt-in superset of the close→open `GapState`. |
| Stop-loss = SuperTrend level for in-trend entries (CE 9-Jan SL 42431 = ST) | §3.4 L619; §6.4 stop_loss | PARTIAL | AUTOMATE_PKG | `supertrend-level-stop` — engine exposes Supertrend only as a +1/-1 direction; expose the ST price band so the in-trend SL can anchor on the ST level (shared with other dimensions' ST-stop gap). |
| [S24] Gap is a 30–60 min play; if unfilled on volume by ~30–40 min, abandon gap & trade the prevailing trend | §3.4 L622 | PARTIAL | AUTOMATE_PKG | `gap-fill-deadline-switch` — add a pre-entry fill-deadline counter (~40 min) that drops the gap setup and reverts to the trend trade; the "on volume" qualifier needs a volume gate on the fill bar. |
| [S24] Gap-trade stop-loss = ~50–60 points or a nearby S/R level | §3.4 L622 | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — a fixed ~50–60 pt SL is trivially encodable; the S/R-level alternative needs an S/R-levels source (shared theme with targets / pullback-to-S/R rows). |
| Counter-trend "trade TOWARD the gap" (entry on rejection toward gap, target = gap level, SL = day-high/low) — risky/scalping-only | §3.4 L614/L639; §6.4 bearish[5] | NONE | ACCEPT_BY_DESIGN | Gap level + day high/low are derivable, but the play is deliberately DEFERRED as risky/scalping-only (`...nifty.yaml:12-14`, `GapTheoryGate.java:23-25`). Wontfix by explicit design decision; remains a manual scalp if traded at all. |
| Ideal window 9:15–10:00; avoid 11 AM–1 PM sideways; no new entries before post-3:30 PM events | §3.4 L627; §6.4 filters | PARTIAL | AUTOMATE_PKG | `event-calendar-lockout` — the 11–1 block and 15:30 cut ARE hard live rails; genuinely unautomated = the soft 9:15–10:00 ideal preference (accept as discretion) + a TRUE post-3:30 event-calendar lockout (needs an economic-event feed). |
| RSI 14 (3-min): CE RSI < 75, PE RSI > 25; 40–60 no-trade zone | §3.4 L628; §6.4 indicators/filters | PARTIAL | AUTOMATE_PKG | `backtest-fidelity-rails` — RSI band is a HARD live rail; gap is BACKTEST-only (no RSI clause in `gate.all`). Add RSI clauses to the YAML `gate.all` so the backtest matches live. |
| Volume confirmation on gap/fill candle (BN 50K / Nifty 125K, per Common Components) | §3.4 L629; §6.4 indicators; §6.4 uncertain | PARTIAL | AUTOMATE_PKG | `backtest-fidelity-rails` — volume is a HARD live rail; gap is BACKTEST-only (no volume gate in `gate.all`). Doc-uncertain on the exact numeric gap-candle rule (§6.4 L2180) → confirm threshold with owner, then add to `gate.all`. |
| Entry at gap-filled area, pullback near VWMA / SuperTrend / VWAP | §3.4 L606; §6.4 entry/filters | PARTIAL | AUTOMATE_PKG | `vwap-distance-sizing` — encode a distance-to-VWAP/VWMA/ST proximity band so entries are a pullback, not extended away (doc §3.4 L186 warns wide gap-to-VWAP = wider SL). |
| Align OI / Trending OI, India VIX, DOW & global cues per Common Components | §3.4 L630; §6.4 filters | PARTIAL / MANUAL_COVERED | COVERED_EXISTING | OI confluence runs live; VIX + global cues are carried by the shipped `vix_normal` (§4.5) + `global_cues_ok` (§4.7) in `ScalperManualChecks` (the MANUAL_COVERED part). OI/VIX/Dow degrade to NEUTRAL on derived history by design. (Deeper VIX bands + a Dow feed are the FU1 `vix_regime_bands` check + an out-of-FU2 Dow-dot plan, but THIS row's manual coverage is the existing two items.) |
| Gap-UP bias: do NOT short on a gap up — look for support/long instead | §3.4 L605/L615/L630; §6.4 bearish[6] | NONE | ACCEPT_BY_DESIGN | The strategies are long/CE-only, so they can NEVER short a gap-up — the "do not short" half is fully honoured by construction. The active "seek support/long on a gap-up" half is a discretionary entry-location preference, low-value to encode given long-only side. Wontfix. |
| Targets: in-trend next S/R (R:R 1:2.5 / 1:1.6–1.7); scalp aim ≤1–2% | §3.4 L618; §6.4 target | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — add a take-profit exit (fixed-R:R or ATR target is encodable); a TRUE next-S/R target needs the `sr-levels-targets-stops` S/R source. |
| Trail SL ~5 pts below price (longs) / above (shorts) once in profit; trail 5 pts below gap reference on gap trades | §3.4 L620; §6.4 scaling; §6.4 stop_loss | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — add a trailing-stop exit type (~5 pt offset; 5 pt below the gap reference for gap trades). |
| Strike/Delta selection: ATM ±3, delta 0.6–0.7 buys, premium 250-400 BN / 100-250 Nifty | §3.4 L631; §6.4 filters | PARTIAL | AUTOMATE_PKG | `backtest-fidelity-rails` — live `StrikePicker` already applies the 0.6–0.7 delta + premium band; gap is BACKTEST-only (YAML selects by ATM window). Surface the delta/premium band into the backtest selector. |
| News overrides the data on gap/event days ("throw the data out") | §3.4 risk; doc §2.13 | MANUAL_COVERED | COVERED_EXISTING | Carried by `news_clear` (doc_ref 2.13) in `ScalperManualChecks.java:26-30`. Genuinely manual (judgement / news feed) — not automatable. |
| Avoid parabolic / forced entries; clean "one good trade"; regime suits (not choppy) | §3.4 risk; doc §3.1/§3.10 | MANUAL_COVERED | COVERED_EXISTING | Carried by `not_parabolic` / `clean_setup` / `regime_ok` / `level_respected` in `ScalperManualChecks.java:31-60`. Trader-discretion / judgement; a VWAP-crossover-count proxy exists in assist text only, not gated. |

## Disposition counts

- COVERED_EXISTING: 3 (OI/VIX/Dow row's manual half; news; clean/parabolic/regime/level)
- COVERED_FU1: 0
- COVERED_FU2: 0
- AUTOMATE_PKG: 10
- KEEP_MANUAL_NEW: 0
- ACCEPT_BY_DESIGN: 3 (with-trend backtest split; counter-trend deferred-risky; gap-up long-only)
- UNCERTAIN_OWNER: 0

Total = 17 (matches the 17 non-FULL source rows).

### AUTOMATE_PKG items → themes (for the synthesizer)

| Theme | Gap rule | Doc § |
|-------|----------|-------|
| `gap-highlow-variant` | High/low high-prob gap variant (prior-high/low→open) | §3.4 L595 |
| `supertrend-level-stop` | SuperTrend-level in-trend stop-loss (ST as a price band, not direction) | §3.4 L619 |
| `gap-fill-deadline-switch` | [S24] abandon unfilled gap at ~40 min on volume, switch to trend | §3.4 L622 |
| `sr-levels-targets-stops` | [S24] gap-trade SL ~50–60 pts or nearby S/R level | §3.4 L622 |
| `event-calendar-lockout` | True post-3:30 PM pre-event lockout (event-calendar feed) | §3.4 L627 |
| `backtest-fidelity-rails` | RSI band into backtest `gate.all` | §3.4 L628 |
| `backtest-fidelity-rails` | Volume gate into backtest `gate.all` | §3.4 L629 |
| `vwap-distance-sizing` | Pullback-proximity (distance-to-VWAP/VWMA/ST) entry band | §3.4 L606 |
| `trade-management-targets-trailing` | Next-S/R targets / R:R take-profit | §3.4 L618 |
| `trade-management-targets-trailing` | Trailing stop ~5 pts once in profit | §3.4 L620 |
| `backtest-fidelity-rails` | Delta 0.6–0.7 / premium band into backtest strike selector | §3.4 L631 |

Note: the gap-theory primitive's soft dots that FU2 promotes (indicator-alignment, futures-OI, breadth,
basis) and FU1's 9 manual checks (FII L/S, constituent contribution, pre-open, sensex participation,
intraday-vs-positional OI, IV crush, straddle VWAP, time-of-day VWAP, VIX regime bands) do NOT appear as
distinct gap rows in THIS dimension's table — they surface in other audit sections — so no row here maps
to COVERED_FU1 / COVERED_FU2. The OI/VIX/Dow alignment row's manual coverage is the already-shipped
`vix_normal` + `global_cues_ok` (COVERED_EXISTING), not the deeper FU1 `vix_regime_bands` add.
