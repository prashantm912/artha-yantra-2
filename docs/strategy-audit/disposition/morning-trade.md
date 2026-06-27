# Morning Trade (Siva #9) — gap disposition

Every non-FULL doc-rule row from `docs/strategy-audit/morning-trade.md` (the audit table, lines 16–45) is
assigned exactly one disposition so no gap is left unaccounted. Source non-FULL rows = **22** (PARTIAL /
NONE / MANUAL_COVERED). The single `n/a` row (L45, `close > vwma20` entry gate) is **excluded** — it is an
automation note explicitly flagged "not a doc rule", not a strategy-rule gap. (The expected baseline of 20
predates the v2/v3 additive rows — RSI-secondary-exit, add-only-around-prev-close, align/oppose
lot-modulation; all three are included below.) Dispositions reference the two follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| Time exit / scalping-only — close inside the first 3-min candle / 250pts in 2 min; no carry | 3.9 / §6.9 time_exit | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — `max_bars:10` is a coarse proxy; tighten or add an opening-window forced square-off |
| Target = next resistance (CE) / support (PE) Futures level | 3.9 / §6.9 target | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — set the target at the next mapped Futures S/R level (S/R levels not computed for exits today) |
| RSI 14 (80:20): CE wants 60+ not overbought >75; 40–60 no-trade | 3.9 / §6.9 filters | PARTIAL | AUTOMATE_PKG | `rsi-band-per-strategy` — code caps CE at 80, doc says >75; lower the CE cap (variants are CE-only so PE band never fires) |
| Gap-down + already-oversold RSI: do NOT chase; wait for RSI to cool to resistance | 3.9 entry-bearish 4 | NONE | AUTOMATE_PKG | `rsi-band-per-strategy` — an RSI-floor cool-off gate on the opening-tick PE path (oversold cap exists only in HeroZeroGate today) |
| Rejection-wick entry on the failed attempt at prior-day close (the gap-rejection trigger) | 3.9 entry 3 | NONE | AUTOMATE_PKG | `morning-opening-formation` — a candle-shape rejection check on the first 1m/3m bars at the prior-day close |
| "2nd candle breaks the 1st" — read the 1-min candle, watch the 2nd-candle break direction | 3.9 entry 2 | NONE | AUTOMATE_PKG | `morning-opening-formation` — a 2-bar opening-formation check on the 1m series (gate evaluates a single closed bar today) |
| Previous-day VWAP is the defended/target level; do NOT use morning VWAP before 10:30 | 3.9 S21/S22 | PARTIAL | AUTOMATE_PKG | `vwap-distance-sizing` — the current-VWAP-before-10:30 suppression IS automated; the prior-day VWAP level is not computed (derivable) |
| OIP/AI direction must match pre-market (pre-open) direction | 3.9 setup 5 | NONE | COVERED_FU1 | FU1 `pre_open_bias` manual check (pre-open positioning + A/D agree with the morning bias); no AI-direction source exists, no pre-open feed wired |
| Global cues match direction (Dow30 futures, Dollar index, Asian markets, Oil) | 3.9 filter 2 | MANUAL_COVERED | COVERED_EXISTING | `global_cues_ok` (ScalperManualChecks, doc_ref 4.7) — shipped 7-item checklist (Dow live-LTP auto-feed to the scorer is a separate `global-cues-feed` pkg, not needed for coverage) |
| OI confluence confirmed at the prior-day 3:20 PM OI-Pulse (point-in-time alignment) | 3.9 setup 6 | PARTIAL | AUTOMATE_PKG | `intraday-positional-oi` — live OI dots are scored on the CURRENT bar; snapshot the 3:20 PM prior-session OI state and gate the next open |
| FII/DII activity feeds the EOD morning view | 3.9 setup 3 | PARTIAL | COVERED_FU1 | FU1 `fii_ls_ratio` manual check covers the FII L/S read; `fiiLongPct` is fetched but dead-wired (scoring it is the separate `fii-dii-bias` automation pkg) |
| India VIX not abnormally spiking (gap/whipsaw risk) | §4.5 / checklist | MANUAL_COVERED + PARTIAL | COVERED_EXISTING | `vix_normal` (ScalperManualChecks, doc_ref 4.5) covers the spike check; the VIX dot is inert (null level/dir) — wiring is the separate `directional-vix-gate` pkg (OUT of FU2) |
| EOD must be convincing — market closed at the day's HIGH or LOW (inside/near-open close = no trade) | 3.9 setup 4 | NONE | AUTOMATE_PKG | `morning-eod-precondition` — a prior-day "closed at high/low" convincing-close gate (prior-day OHLC is available) |
| Stand aside if post-close news invalidates the EOD positioning | 3.9 setup 2 / filter 8 | MANUAL_COVERED | COVERED_EXISTING | `news_clear` (ScalperManualChecks, doc_ref 2.13) — shipped 7-item checklist |
| Small position size / profits-only (deploy only a portion of profits, never core capital) | 3.9 risk 2 / S21 | PARTIAL | KEEP_MANUAL_NEW | A "% of realized profits, never core capital" discipline; no realized-profit-pool sizing primitive exists (budget is fixed) — trader discretion / future manual-check candidate |
| Take EVERY signal but modulate the lot — full lot when aligned, reduced lot when opposing/neutral (S21) | 3.9 S21 update (d) | NONE | AUTOMATE_PKG | `probability-graded-sizing` — a confluence-strength→size multiplier (no sizing primitive exists; budget is fixed today) |
| Profit-trail-to-breakeven once price runs in your favour (S22) | 3.9 S22 update (b) | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — a trailing/breakeven exit rule (stop is the static first-candle level today) |
| RSI secondary exit confirmation (worked short: RSI<30 was a reason to exit) | 3.9 time exit | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — add an RSI-cross exit rule to `exit_rules` (RSI is an entry band/dot only today) |
| Add to the position ONLY around the previous-day close, nowhere else (S22) | 3.9 S22 update (d) | NONE | KEEP_MANUAL_NEW | Moot under single-entry (`max_positions:1`, no-averaging enforced); a scale-in-location rule for manual averaging — trader discretion / no scale-in engine |
| Open=High doubles as exit-trigger + hedge against you (S22) | 3.9 S22 update (e) | NONE | KEEP_MANUAL_NEW | The OpenHighLowGate is a distinct strategy, not wired into the opening-tick path; an opposite-side OH exit/hedge is manual discretion (hedging is order-side, deferred) |
| >50% change in OI direction needed for a convincing same-day view (S22) | 3.9 S22 update (g) | PARTIAL | COVERED_FU2 | FU2 = the ≥50% call-put dOI imbalance (`oi-cross-filter`) is one of the promoted gates; morning-trade YAMLs just need the tag added |
| Experienced-traders-only / clean "one good trade" discipline | 3.9 risk 1 | MANUAL_COVERED | COVERED_EXISTING | `clean_setup` / `not_parabolic` / `regime_ok` (ScalperManualChecks) — shipped 7-item checklist |

### Disposition counts

- COVERED_EXISTING: 4
- COVERED_FU1: 2
- COVERED_FU2: 1
- AUTOMATE_PKG: 12
- KEEP_MANUAL_NEW: 3
- ACCEPT_BY_DESIGN: 0
- UNCERTAIN_OWNER: 0
- **Total non-FULL doc-rule rows: 22** (PARTIAL/NONE/MANUAL_COVERED in `morning-trade.md` lines 18–44; the L45 `n/a` automation-note row and the 5 FULL rows L18/19/22/30/36 are excluded)

### AUTOMATE_PKG themes (for the synthesizer)

- `trade-management-targets-trailing` — tight opening-window exit, trail-to-breakeven, RSI-cross exit (3 rows fold into this theme)
- `sr-levels-targets-stops` — next-S/R-level profit target
- `rsi-band-per-strategy` — CE cap to 75 + gap-down oversold cool-off
- `morning-opening-formation` — rejection-wick + "2nd candle breaks the 1st" opening-tick trigger (2 rows)
- `vwap-distance-sizing` — prior-day VWAP as the defended/target level
- `intraday-positional-oi` — prior-day 3:20 PM OI-Pulse point-in-time alignment snapshot
- `morning-eod-precondition` — prior-day convincing-close-at-high/low gate
- `probability-graded-sizing` — align/oppose lot-modulation (confluence-strength→size)
