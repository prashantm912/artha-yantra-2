# Golden Crossover — gap disposition

Every non-FULL row from `docs/strategy-audit/golden-crossover.md` (the source audit table) is assigned
exactly ONE disposition here, so no gap is left unaccounted. Source has 19 table rows; 4 are FULL (delta/
ATM, premium band, time filter, broad-trend ST(7,3)) and are excluded. The remaining **15 non-FULL rows**
(PARTIAL / NONE / MANUAL_COVERED / UNCERTAIN) are all dispositioned below.

Coverage recognition used:
- **Follow-up-1** (`2026-06-27-followup1-expand-manual-checks.md`) adds 9 manual checks: `fii_ls_ratio`,
  `constituent_contribution`, `pre_open_bias`, `sensex_participation`, `oi_intraday_positional`,
  `iv_crush_awareness`, `straddle_vwap_entry`, `time_of_day_vwap`, `vix_regime_bands`.
- **Follow-up-2** (`2026-06-27-followup2-soft-dots-to-hard-gates.md`) promotes 4 soft dots to opt-in hard
  gates: `indicator-alignment`, futures-OI quadrant, breadth (Adv/Dec>32), futures basis. The prompt
  frames the `>=50% dOI imbalance` confirmer as the FU2-covered OI promotion. **VIX + Dow are OUT of FU2
  scope.**

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|---|---|---|---|---|
| Both ST AND VWMA cross VWAP on the SAME candle (real body) — a crossover EVENT, not the static above/below STATE the gate encodes | 3.6 setup-3 / 6.6 setup_preconditions[3] | PARTIAL | AUTOMATE_PKG | `same-candle-crossover-event` — add a VWAP-aware crossover op + no-body-candle exclusion so the entry bar is the actual same-candle pierce, not a stale already-above state. Not in FU1/FU2. |
| Volume mandatory on the crossover candle: Nifty 125K+ / others 50K+ | 3.6 setup-4 / 6.6 setup_preconditions[4] | PARTIAL | AUTOMATE_PKG | `volume-floor-per-index` — floor is keyed to the NIFTY signal-future for ALL three variants, so SENSEX-options gate on NIFTY 125K not a 50K floor, and on the future bar's volume not the crossover candle's. Re-key the floor to the traded index and read the crossover candle's own volume. |
| RSI(3m,14) bullish **< 75** (doc card) vs engine §4.2 band 60–80 | 3.6 entry bull-3 / 6.6 entry bullish[3] | PARTIAL | AUTOMATE_PKG | `rsi-band-per-strategy` — a doc-eligible Golden-Cross bull at RSI 50–60 is blocked by the shared 60–80 band; add a per-strategy RSI override (the `requireOpenHighLow`-style override pattern already exists). |
| RSI(3m) bearish **> 25** (doc card) vs engine 20–40; bearish (Buy PE / Sell CE) side **entirely unseeded** (all 3 YAMLs `direction: long` / `[CE]`) | 3.6 entry bear-3 / 6.6 entry bearish[3] | PARTIAL | AUTOMATE_PKG | `bearish-side-seeding` — seed the PE/short half of §3.6 (new `direction: short` / `[PE]` variants) plus the bearish RSI band. (The operative bearish RSI value itself is owner-gated — see the UNCERTAIN row below.) |
| OI confirmation: **drastic change in change-of-OI on BOTH CE and PE sides** (no drastic OI ⇒ skip) | 3.6 setup-5 / entry-5 / 6.6 setup_preconditions[5] | PARTIAL | COVERED_FU2 | The `>=50% two-sided dOI imbalance` confirmer is the OI soft-dot FU2 promotes to a hard gate (tag the existing `oi-cross-filter`/`callPutDeltaFilter` hard pre-gate onto this strategy). FU2 also calibrates the 50000 `drasticFloor` placeholder. |
| Trending OI across **5/7 strikes above and below ATM** (5–15 min window) | 3.6 setup-5 / filters / 6.6 indicators | PARTIAL | AUTOMATE_PKG | `trending-oi-strike-window` — OI dots exist but the specific 5/7-strikes-around-ATM window is service-level, not a per-strategy knob. Expose the window as a Golden-Cross-tunable. (Partial automation — service-level today.) |
| Stop-loss: **Supertrend-level SL** (support-trade form, the S21 resolution) | 3.6 exit / 5.6 / 6.6 exit_conditions.stop_loss | PARTIAL | AUTOMATE_PKG | `supertrend-level-stop` — only the breakout crossover-candle stop (`entry-candle-stop`) is automated; the `SUPERTREND` indicator is direction-only (+1/−1), so expose the ST band PRICE level to size the support-form stop. |
| Targets: BN ~100–150 / Nifty ~50–70 pts; S21 clean ~200–300 BN | 3.6 exit / 6.6 exit_conditions.target | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — no point-target/take-profit encoded (exits on VWMA-undone or 12-bar timeout). An index-point target on a premium leg is indirect (partially automatable). |
| RSI-exhaustion caveat: don't expect extension if RSI already overbought/oversold — wait for VWAP to hold | 3.6 S21(e) | NONE | KEEP_MANUAL_NEW | Judgement (audit: "Automatable: false"); not in FU1. The 60–80/20–40 band partially caps exhaustion but the "wait for VWAP to hold then trade" nuance stays trader discretion — a candidate manual check beyond FU1 or accept as discretion. |
| News overrides the data (no trade against market-moving news) | Common §2.13 | MANUAL_COVERED | COVERED_EXISTING | Shipped `ScalperManualChecks` key `news_clear`. |
| VIX not abnormally spiking | 3.6 filters / §4.5 | MANUAL_COVERED | COVERED_EXISTING | Shipped key `vix_normal` (abnormal-spike magnitude). (FU1's `vix_regime_bands` adds the separate absolute-band read; the spike check itself is already shipped.) |
| Global cues / DOW not against the trade | 3.6 filters / §4.7 | MANUAL_COVERED | COVERED_EXISTING | Shipped key `global_cues_ok` (Dow soft dot is live-only, NEUTRAL on history). |
| Regime: choppy/range-bound stand-aside (>2–3 VWAP crossovers ⇒ choppy day) | 3.6 (implied) / §3.10 | MANUAL_COVERED | COVERED_EXISTING | Shipped key `regime_ok` (closest proxy for the §3.6 no-body/no-volume trap edge cases). |
| Not chasing a parabolic / forced entry; "one good trade" (rare ~3–4×/month) | 3.6 risk-1 / §3.1 | MANUAL_COVERED | COVERED_EXISTING | Shipped keys `not_parabolic` + `clean_setup`. |
| Bearish RSI gate ambiguity (card >25 vs matrix <25) | 3.6 entry bear-3 (UNCERTAIN) | UNCERTAIN | UNCERTAIN_OWNER | The doc itself marks this UNCERTAIN; the bearish side is unseeded. Resolve the operative bearish RSI rule (>25 vs <25) with the owner before automating the PE side (gates the `bearish-side-seeding` package). |

## Disposition counts

- COVERED_EXISTING: 5
- COVERED_FU1: 0
- COVERED_FU2: 1
- AUTOMATE_PKG: 7
- KEEP_MANUAL_NEW: 1
- ACCEPT_BY_DESIGN: 0
- UNCERTAIN_OWNER: 1
- **Total non-FULL rows: 15**

## AUTOMATE_PKG themes (for the synthesizer)

| Work-package theme | Gap rule |
|---|---|
| `same-candle-crossover-event` | Same-candle ST+VWMA pierce of VWAP + no-body exclusion |
| `volume-floor-per-index` | Volume floor keyed to the wrong (NIFTY) index for SENSEX variants; future-bar vs crossover-candle volume |
| `rsi-band-per-strategy` | Bullish RSI <75 doc card vs engine 60–80 band (per-strategy override) |
| `bearish-side-seeding` | Bearish (Buy PE / Sell CE) half of §3.6 entirely unseeded + bearish RSI band |
| `trending-oi-strike-window` | 5/7-strikes-around-ATM Trending-OI window not a per-strategy knob |
| `supertrend-level-stop` | Supertrend-level SL (support-trade form) needs the ST band price level |
| `trade-management-targets-trailing` | Point targets (~50–300 pts) not encoded; exits only on VWMA-undone / 12-bar timeout |
