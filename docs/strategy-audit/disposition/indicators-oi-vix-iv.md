# Gap disposition — Indicators + OI build-up/spurts + Trending-OI + VIX + IV (§4.1–4.6)

Source: `docs/strategy-audit/indicators-oi-vix-iv.md`. Every NON-FULL row in that section's table
(status PARTIAL / NONE / MANUAL_COVERED / UNCERTAIN) is dispositioned exactly once below. The source
table has **18 non-FULL rows** → **18 disposition rows** (no gap dropped).

Disposition legend: `COVERED_EXISTING` (shipped 7-item ScalperManualChecks) · `COVERED_FU1`
(follow-up-1, the 9 added manual checks) · `COVERED_FU2` (follow-up-2, the 4 promoted soft dots —
indicator-alignment / OI-quadrant / ≥50% ΔOI imbalance; VIX + Dow are OUT of FU2 scope) ·
`AUTOMATE_PKG` (automatable, not in FU1/FU2 → work-package theme) · `KEEP_MANUAL_NEW` ·
`ACCEPT_BY_DESIGN` · `UNCERTAIN_OWNER`.

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| Directional/structure chart = Bank Nifty Futures 3m | 4.1 | NONE | ACCEPT_BY_DESIGN | BankNifty weeklies are dead (§4.16); no BN weekly product exists. The NIFTY/SENSEX continuous front-future spine is the intended replacement — wontfix (obsolete doc rule). |
| S/R on 1-Day, refined on 15-min | 4.1 / 4.11 | MANUAL_COVERED | COVERED_EXISTING | Carried by `level_respected` (`ScalperManualChecks.java:33`, docRef 4.11). |
| Trending-OI graph window 5–15 min | 4.4 | PARTIAL | AUTOMATE_PKG | `trending-oi-window-fidelity` — make the `/options/trending` read a selectable 5–15-min view instead of the fixed `SERIES_WINDOW=20` bucketing. |
| Daily-RSI cross-check: CE RSI(D) < 75 / PE RSI(D) > 25 | 4.2 | NONE | AUTOMATE_PKG | `daily-rsi-crosscheck` — add an `RSI@1d` indicator + a CE<75 / PE>25 gate; only the 3m RSI is wired today. |
| Intraday-variant kit adds MACD / ADX (+ ST 7,3 on 15m/1h) | 4.14.9 | NONE | ACCEPT_BY_DESIGN | Doc scopes MACD/ADX to the non-scalp "intraday variant," not the core 3m scalp; engine-capable but unseeded. Low value — wontfix for the scalper. |
| Indicator-alignment "Golden Cross": ALL indicators one side + simultaneous ST&VWMA VWAP cross | 4.2 | PARTIAL | COVERED_FU2 | FU2 promotes the `indicator-alignment` soft dot to a hard gate (wires `ScalperGates.indicatorAlignment`, tag-gated default-off). |
| Q3 SC/LL; Q4 (slide OI + slide price) = avoid | 4.3.2 | PARTIAL | AUTOMATE_PKG | `oi-quadrant-avoid-veto` — turn the off-side Q3/Q4 quadrant from "fails to confirm" into an active avoid-veto block. |
| Demand read = price-move-per-OI (big move on small ΔOI = stronger) | 4.14.3 | NONE | AUTOMATE_PKG | `price-move-per-oi-demand` — compute a move-per-OI ratio; `drasticOi` uses absolute ΔOI magnitude only. |
| Trending-OI reliable on 15 strikes (7+ATM+7); 5–7 for golden-cross confirm | 4.17.3 / 4.14.6 | UNCERTAIN | UNCERTAIN_OWNER | Strike window of `/options/trending` is server-side in market-data and not surfaced; verify the aggregation reads 15 strikes (then automatable if the endpoint takes a strike-count param). Open point. |
| Intraday vs positional OI must agree; ~5cr call vs 10–12cr put; PCR 1.2→1.5→2 | 4.17.3 | NONE | COVERED_FU1 | FU1 manual check `oi_intraday_positional` (docRef 4.17.3). |
| India VIX directional grid (price↑&VIX↓=bull); CE→VIX down, PE→VIX up | 4.5 / 4.14.1 | NONE (signal gate) / PARTIAL (OI page) | AUTOMATE_PKG | `directional-vix-gate` — wire a VIX series/endpoint into `MarketOiClient.macro()` (currently null-fed) so `ScalperGates.vix` can confirm/block; FU2 explicitly excludes VIX. The OI-page `vixFactor` matrix is display-only. |
| VIX absolute regime bands (10–11 low … 17+ high) | 4.14.1 | NONE | COVERED_FU1 | FU1 manual check `vix_regime_bands` (docRef 4.14.1 — absolute bands + VIX/price grid). |
| VIX supporting inferences: rising VIX = fresh shorts; VIX stable + price falling = longs exiting | 4.5 / 4.14.1 | NONE | AUTOMATE_PKG | `directional-vix-gate` — the richer VIX-vs-price interpretation rides on the same VIX-feed wiring (needs the feed first, then the fresh-short / longs-exiting rule). Grouped with the directional VIX gate. |
| VIX vs previous-day close (higher=bearish); erratic intraday VIX → ignore | 4.5 / 4.14.1 | MANUAL_COVERED | COVERED_EXISTING | Carried by `vix_normal` (`ScalperManualChecks.java:46`, docRef 4.5 — "not abnormally spiking"). The prev-close/erratic nuance is not encoded but the row is manual-covered today. |
| 10–12 IV good for Trend play (low IV = most of move captured) | 4.6 | PARTIAL | AUTOMATE_PKG | `iv-absolute-band` — gate the absolute 10–12 ATM-IV low band; only an `iv_rank < 50` soft dot exists today. |
| Prefer rising IV in that strike for bull / falling IV for bear | 4.6 | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — add per-strike IV-slope (rising for the buy side) to the emitter; today it uses only the static CE-vs-PE IV gap. |
| IV crashes 2nd half of expiry day / post-event; CE-vs-PE time-value demand-driven | 4.17.5 | NONE | COVERED_FU1 | FU1 manual check `iv_crush_awareness` (docRef 4.17.5). |
| Volume colour-coding dark-green/dark-red (>50K/125K bull/bear pump) | 4.15.3 | PARTIAL | AUTOMATE_PKG | `volume-pump-attribution` — attribute the high-volume candle to a bull vs bear pump (price-signed), like the matrix `volumeFactor`; the gate only checks the volume floor. |

## Disposition counts

- COVERED_EXISTING: 2
- COVERED_FU1: 3
- COVERED_FU2: 1
- AUTOMATE_PKG: 9
- KEEP_MANUAL_NEW: 0
- ACCEPT_BY_DESIGN: 2
- UNCERTAIN_OWNER: 1

**Total: 18** (= non-FULL rows in the source section).

## AUTOMATE_PKG themes (for the synthesizer)

| Theme | Rule | Doc § |
|-------|------|-------|
| `trending-oi-window-fidelity` | Trending-OI graph window 5–15 min | 4.4 |
| `daily-rsi-crosscheck` | Daily-RSI cross-check CE<75 / PE>25 | 4.2 |
| `oi-quadrant-avoid-veto` | Q3/Q4 slide-OI/slide-price avoid veto | 4.3.2 |
| `price-move-per-oi-demand` | Demand read = price-move-per-OI ratio | 4.14.3 |
| `directional-vix-gate` | India VIX directional grid into the signal gate | 4.5 / 4.14.1 |
| `directional-vix-gate` | VIX fresh-short / longs-exiting interpretation | 4.5 / 4.14.1 |
| `iv-absolute-band` | 10–12 absolute IV trend-play band | 4.6 |
| `iv-per-strike` | Per-strike IV-trend direction in the emitter | 4.6 |
| `volume-pump-attribution` | Volume bull/bear pump attribution | 4.15.3 |
