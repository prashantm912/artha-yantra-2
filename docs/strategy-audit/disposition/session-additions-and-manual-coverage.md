# Session-21..24 additions (§4.14–4.17), §7 open-questions, manual-coverage — gap disposition

Every non-FULL row from the MAIN automation table of
`docs/strategy-audit/session-additions-and-manual-coverage.md` (lines 17–59) is assigned exactly one
disposition so no gap is left unaccounted. Source non-FULL rows = **36** (PARTIAL / NONE; 43 main-table rows
− 7 FULL at L20/L21/L30/L36/L48/L49/L58). (The expected baseline of 33 predates the three v2-added rows —
§4.14.9 cadence, §4.14.9 account/order-mechanics, §7 consolidated; all are included below. The
`ScalperManualChecks coverage` sub-table at lines 67–82 is a coverage cross-check, NOT a gap table, so it is
not dispositioned here.) This dimension is the direct source of FU1's 9 manual checks, so many rows resolve
to COVERED_FU1. Dispositions reference the two follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| India VIX absolute regime bands (10–11/12–14/15–16/17+) | 4.14.1 | NONE | COVERED_FU1 | FU1 `vix_regime_bands` manual check (absolute bands + ignore-erratic); INDIA VIX candles exist for a future `directional-vix-gate` automation |
| India VIX direction dot (falling=CE / rising=PE) | 4.14.1 | PARTIAL | AUTOMATE_PKG | `directional-vix-gate` — the dot/gate exist but `macro()` feeds null; wire the existing INDIA VIX candle read (OUT of FU2; FU1's `vix_regime_bands` covers the manual band) |
| VIX-vs-price grid; compare to prev-day close; ignore erratic intraday VIX | 4.14.1 | NONE | COVERED_FU1 | FU1 `vix_regime_bands` manual check explicitly includes the VIX-vs-price grid + prev-day-close compare |
| Price-move-per-OI demand read (big move on small OI = stronger) | 4.14.3 | NONE | AUTOMATE_PKG | `intraday-positional-oi` — eyeball the price-impact-per-OI ratio; derivable but unbuilt |
| Index-constituent contribution (top movers; BankNifty top3 ≈60%; crude→BankNifty adverse) | 4.14.4 | NONE | COVERED_FU1 | FU1 `constituent_contribution` manual check; the automation (`constituent-contribution` pkg) needs a constituent-weight + per-stock quote feed |
| Lot sizes; weekly Thu / monthly last-Thu expiry | 4.14.4 | PARTIAL | ACCEPT_BY_DESIGN | Expiry calendar is automated; lot size is an order/margin-layer concern, not a signal gate — no signal-side gap |
| Time-of-day data weighting (prev-day VWAP until 11 AM, current after) | 4.14.5 | PARTIAL | COVERED_FU1 | FU1 `time_of_day_vwap` manual check; the prev-day-VWAP series automation is the `vwap-distance-sizing` pkg |
| Pre-open (9:00–9:07) positioning + advances/declines for morning bias | 4.14.5 / 4.15.5 | NONE | COVERED_FU1 | FU1 `pre_open_bias` manual check; a pre-open snapshot feed does not exist (automation deferred) |
| OI interval reads; 15-min major crossover vs 60-min longer view | 4.14.6 | PARTIAL | AUTOMATE_PKG | `multi-timeframe-supertrend` — multi-TF OI separation (15m crossover vs 60m view) unbuilt; today a single trending window + 60m bias |
| Refresh Trending-OI strikes to ATM±7 once move >1% | 4.14.6 | NONE | ACCEPT_BY_DESIGN | UI/OI-page housekeeping concern, not a scalper-signal gate — low value to harden in the signal path |
| Strike/delta by expiry phase (0.7–0.8 near weekly-end; ~0.5 day 1) + VIX-conditional | 4.14.7 / 4.15.4 | PARTIAL | UNCERTAIN_OWNER | Doc-sanctioned v1 simplification (fixed 0.6–0.7 band, deferred by explicit code comment) — owner design choice whether to add the expiry-phase/VIX-conditional delta |
| Options selling / hedging (never naked; short straddle; SL = straddle VWAP +10–15pt) | 4.14.8 | NONE (SPAN-deferred) | ACCEPT_BY_DESIGN | Short premium is SPAN-gated (#47, dormant); StraddleLegPicker only returns BUY legs — out of scope until the SPAN appliance is live |
| Scalping cadence & discipline (hold sec–3min; cut small; missed entry = let go; multi-lot small targets) | 4.14.9 | PARTIAL | AUTOMATE_PKG | `probability-graded-sizing` — cadence/no-chase are implicit in the per-bar engine; the multi-lot small-per-trade-target sizing is unbuilt (flat premium_budget) |
| Account size & order mechanics (1% rule, basket orders, recommended small-cap set) | 4.14.9 | NONE | ACCEPT_BY_DESIGN | Account/order-layer concern + owner judgement (recommended-set); not a signal gate |
| Trending-OI + PA (LTP change beside ΔOI; flat LTP = erosion not a real move) | 4.15.1 | PARTIAL | AUTOMATE_PKG | `intraday-positional-oi` — trending cross + spurt already require real ΔOI + price%; the explicit gradual-negative-LTP buyer read is unbuilt (LTP-change series exists) |
| Straddle chart = combined Call+Put premium vs own VWAP, entry on VWAP break with volume; one-leg mgmt | 4.15.2 / 3.11 | NONE (LIVE-deferred) | COVERED_FU1 | FU1 `straddle_vwap_entry` manual check; the automation needs a live (non-replay) seam (`strike-premium-band-backtest`/live straddle seam) |
| PSAR distance read (close dots = short-lived / wide gap = lasting) | 4.15.3 | PARTIAL | AUTOMATE_PKG | `multi-timeframe-supertrend` — PSAR value-vs-price distance is computable; the durability interpretation is unbuilt (only a side dot today) |
| OSPL volume colour-coding (>50K BN / >125K N green/red attribution) | 4.15.3 | PARTIAL | ACCEPT_BY_DESIGN | Volume floors are encoded; the bull-vs-bear green/red attribution is a UI/manual read — low signal value |
| VWAP most-important + decisive + max-quantity-nearest-VWAP sizing | 4.15.3 | PARTIAL | AUTOMATE_PKG | `vwap-distance-sizing` — VWAP is already the decisive highest-weight gate; the "deploy max qty nearest VWAP" sizing is unbuilt |
| Buyer delta up to 0.9 / seller ~0.4 (wider band) | 4.15.4 | PARTIAL | UNCERTAIN_OWNER | Same deferred fixed-0.6–0.7-band design choice as the expiry-phase delta row — owner decides whether to widen |
| IV trending-difference band = 7–10 pts (CE-vs-PE, higher on trending side) | 4.15.4 / 4.17.5 | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — the 10-pt edge is encoded as a single threshold (`ivPairMinGap`); make it the 7–10 band (tunable today) |
| IV above 40 → buyer stays away (unilateral per-side cap) | 4.15.4 / 4.17.5 | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — only the 40/40 both-high+narrow-gap stand-aside exists; add a per-side IV>40 buyer cap (6-strike IV averages already computed) |
| Open=High premium bands (Nifty 150–350 operative; BN 250–550) — superseded set encoded | 4.15.4 / 3.2 / 7 | PARTIAL | AUTOMATE_PKG | `strike-premium-band-backtest` — the live band is the superseded NIFTY 100–250; swap to the S22-resolved operative band (constant change) |
| Trending day = new high/low ~every 45–60 min | 4.15.5 | NONE | ACCEPT_BY_DESIGN | A regime-cadence read; closest proxy is the `regime_ok` manual check — soft/judgement, low value to harden |
| Stock daily-RSI screen: not crossed 75 (bull) / 40 (bear) on daily | 4.15.5 | PARTIAL | AUTOMATE_PKG | `multi-timeframe-rsi` — the stock daily-RSI BTST screen (Market Movers stock-universe) is not gated; daily RSI is computable |
| Pre-open data available after 9:07 (in 9:08) | 4.15.5 | NONE | COVERED_FU1 | FU1 `pre_open_bias` (same pre-open snapshot gap as the §4.14.5 pre-open row) |
| Sensex value & ~3× point-scaling (Nifty 0.5%≈125 → Sensex 375–400) | 4.16.1 / 4.17.2 | PARTIAL | AUTOMATE_PKG | `sensex-point-scaling` — structural stop scales implicitly; the explicit ~3× point multiplier for SL/target is not encoded |
| Sensex strike & SL ladder near VWAP; point-SL scales ~3× | 4.16.4 | PARTIAL | AUTOMATE_PKG | `sensex-point-scaling` — structural VWAP/swing stops scale with the instrument; no explicit 3× ladder (no-averaging is intentional) |
| Sensex participation / volume gate (skip thin Sensex, prefer Nifty) | 4.17.2 | NONE | COVERED_FU1 | FU1 `sensex_participation` manual check; a runtime Sensex-vs-Nifty comparator (the automation) is the `sensex-point-scaling` family — today a static A/B |
| Monitor Nifty AND Sensex on Sensex expiry; pre-open NSE-vs-BSE gap = HFT arb | 4.17.2 | NONE | KEEP_MANUAL_NEW | Cross-index alignment + HFT-gap judgement; partly a spread compute, partly judgement — trader discretion |
| Trending-OI 15-strike read (7 above + ATM + 7 below) | 4.17.3 | PARTIAL | AUTOMATE_PKG | `intraday-positional-oi` — the strike-window count is a market-data endpoint param, not surfaced as a scalper-config knob |
| Intraday vs positional OI must agree (>50% on BOTH; PCR 1.2→1.5→2) | 4.17.3 | PARTIAL | COVERED_FU1 | FU1 `oi_intraday_positional` manual check; the positional two-window + PCR-ladder automation is the `intraday-positional-oi` pkg |
| FII futures Long/Short-ratio gate (~87–94% short; ~50% crossover trigger) | 4.17.4 | NONE (plumbed-but-dead) | COVERED_FU1 | FU1 `fii_ls_ratio` manual check; `fiiLongPct` is fetched but dead-wired — the consuming dot/gate is the `fii-dii-bias` automation |
| IV crashes 2nd-half of expiry day / post-event; CE-vs-PE TV diff demand-driven | 4.17.5 | NONE | COVERED_FU1 | FU1 `iv_crush_awareness` manual check; the time-of-day/expiry IV-decay automation (`iv-per-strike`) is unbuilt |
| OI bars on Nifty SPOT for S/R (largest call-OI bar=resistance, put-OI bar=support) | 4.17.6 / 4.11 | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — spot-OI-bar + volume-turning-point S/R is computable but unbuilt; weakly covered by `level_respected` today |
| §7 [RESOLVED]/open-question status the engine should reflect (O=H bands, Hero-Zero SL, GC/BTST resolutions, open ambiguities) | 7 | PARTIAL | UNCERTAIN_OWNER | Mostly doc-sanctioned one-way picks per the §4.2 conflict rule; verify the superseded O=H bands + Hero-Zero SL are intentionally still as-is and the open ambiguities are owner-confirmed |

### Disposition counts

- COVERED_EXISTING: 0
- COVERED_FU1: 11
- COVERED_FU2: 0
- AUTOMATE_PKG: 15
- KEEP_MANUAL_NEW: 1
- ACCEPT_BY_DESIGN: 6
- UNCERTAIN_OWNER: 3
- **Total non-FULL main-table rows: 36** (matches the 36 non-FULL rows in the main automation table lines 17–59: 43 rows − 7 FULL at L20/L21/L30/L36/L48/L49/L58; the checklist-coverage sub-table is excluded as a coverage cross-check, not a gap table)

### AUTOMATE_PKG themes (for the synthesizer)

- `directional-vix-gate` — wire the existing INDIA VIX candle read into the scalper macro (OUT of FU2)
- `intraday-positional-oi` — price-per-OI demand, Trending-OI+PA follow-through, 15-strike window, positional two-window agreement (4 rows)
- `multi-timeframe-supertrend` — 15m-crossover vs 60m-view OI separation + PSAR-distance durability (2 rows)
- `probability-graded-sizing` — multi-lot small-per-trade-target scalping sizing
- `vwap-distance-sizing` — prev-day VWAP series + max-quantity-nearest-VWAP sizing (2 rows)
- `iv-per-strike` — 7–10 pt IV band + per-side IV>40 buyer cap + expiry/event IV-decay (3 rows)
- `strike-premium-band-backtest` — swap to the S22-operative O=H premium bands
- `multi-timeframe-rsi` — stock daily-RSI BTST screen
- `sensex-point-scaling` — ~3× point SL/target scaling + runtime Sensex-vs-Nifty participation comparator (3 rows)
- `sr-levels-targets-stops` — spot-OI-bar + volume-turning-point S/R derivation
