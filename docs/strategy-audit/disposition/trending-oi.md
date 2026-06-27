# Trending OI Crossover — gap disposition

Dispositions EVERY non-FULL row of `docs/strategy-audit/trending-oi.md` (Siva strategy #5). 28 gap rows
(PARTIAL / NONE / MANUAL_COVERED). Each gets exactly one home so no audit gap is left unaccounted.

Legend for **Disposition**:
- `COVERED_EXISTING` — already carried by the shipped 7-item `ScalperManualChecks`.
- `COVERED_FU1` — one of the 9 manual checks Follow-up-1 adds.
- `COVERED_FU2` — one of the 4 soft dots Follow-up-2 promotes to a hard gate (indicator-alignment,
  futures-oi, breadth, basis). NOTE: VIX + Dow are explicitly OUT of FU2 scope.
- `AUTOMATE_PKG` — automatable, not in FU1/FU2 → assigned a work-package theme.
- `KEEP_MANUAL_NEW` — genuinely manual / judgement, not in FU1 → future manual-check candidate or discretion.
- `ACCEPT_BY_DESIGN` — soft-by-design / derived-history artifact / low-value → wontfix with reason.
- `UNCERTAIN_OWNER` — doc ambiguous or an owner choice → open point.

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| Defining trigger: PE-OI line crosses ABOVE CE-OI (bull) / mirror (bear) — a REAL fresh cross | 3.5 Entry-Bull.2 / Entry-Bear.2 / 6.5 | PARTIAL | AUTOMATE_PKG | `oi-cross-hard-gate` — promote `crossedThisWindow` to a required pre-gate (cross is currently one soft dot, weight 1.0; not in FU2's four). |
| Both %-change AND OI-sentiment-graph slope must agree | 3.5 Setup.2 / Entry / Filters / 6.5 | PARTIAL | AUTOMATE_PKG | `oi-cross-hard-gate` — require slope-sign agreement as a hard sub-gate (sentiment_slope is a soft dot today). |
| Lines diverge ~20–30% at the cross; ≥50% gap = conviction; widening gap = ride | 3.5 S21(a) / S22 / 6.5 | PARTIAL | AUTOMATE_PKG | `oi-divergence-magnitude` — threshold the ~20–30%/≥50% gap % off the trending series (only a boolean `gapWidening` today). |
| RSI(3-min): bullish <75 / bearish >25 (vs engine §4.2 band CE 60–80 / PE 20–40) | 3.5 Entry-Bull.3 / Entry-Bear.3 / 6.5 | PARTIAL | UNCERTAIN_OWNER | Doc-internal conflict: §3.5 card (<75/>25) vs §4.2 grid (60–80/20–40); doc itself flags bearish RSI UNCERTAIN. Engine chose §4.2 — owner must reconcile before tuning. |
| Read cross on 5–15 min interval; 60-min for broader trend; 3-min for entries | 3.5 Instruments/Setup.1 / 6.5 | PARTIAL | AUTOMATE_PKG | `oi-interval-and-60m-trend` — pass the explicit 5–15 min analytics interval + wire a 60-min OI broader-trend read (cross uses market-data default bucket; 60m OI not consulted). |
| Substantial rise/fall in price corroborating the OI cross | 3.5 Setup.4 / Entry.4 / 6.5 | PARTIAL | AUTOMATE_PKG | `oi-divergence-magnitude` — add a price-impulse % over the cross window (trend dots stand in today; same series-magnitude theme as the gap %). |
| Strength grade HIGH → drastic one-side OI fall + build-up + volume + short-covering; size accordingly | 3.5 Entry-Bull.5 / Entry-Bear.5 / Risk / 6.5 | PARTIAL | AUTOMATE_PKG | `probability-graded-sizing` — grade size off the confluence aggregate + drastic-OI; `drasticFloor` needs live calibration (sizing is fixed ₹15k today). |
| Bullish: Buy CE or Sell PE; Bearish: Buy PE or Sell CE | 3.5 Entry-Bull.6 / Entry-Bear.6 / 6.5 | PARTIAL | AUTOMATE_PKG | `bearish-pe-mirror-yaml` — add a mirror PE-LONG trending-oi YAML (these are CE-LONG-only). Short legs (Sell PE/CE) are SPAN/live-order deferred → see overnight/SPAN note below. |
| Instruments: Nifty, Bank Nifty, Fin Nifty | 3.5 Instruments / 6.5 | PARTIAL | ACCEPT_BY_DESIGN | BN/FinNifty owner-DEPRECATED (monthly-only / illiquid; Siva instrument model); SENSEX substituted per ADR-0003. NIFTY+SENSEX seeded. Wontfix. |
| Target: not more than 1–2% per scalp; ride while OI gap widens | 3.5 Exit.target / 6.5 | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — a % TP on premium (no take-profit encoded; exits are VWMA-break + 12-bar timeout). The gap-widening trail needs the live trending series. |
| Stop-loss: book SL when hit; on a double/fake cross switch to the other side | 3.5 Exit.stop_loss / Risk / 6.5 / edge_cases | PARTIAL | AUTOMATE_PKG | `fake-cross-side-flip` — automate the side-flip on a confirmed opposite cross. (No fixed numeric SL is doc-specified; structural VWMA-reclaim is the stop.) |
| Trail / book when RSI nears an extreme (≈25 bull / ≈75 bear) on a valid cross (S22(c)) | 3.5 S22(c) | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — an RSI-proximity trailing-exit rule (RSI is an entry-band gate only today, never an exit). |
| Failed/incomplete cross invalid if sellers re-write calls as price rises | 3.5 Exec-notes / Edge-cases / 6.5 | NONE | AUTOMATE_PKG | `incomplete-cross-reject` — needs in-window per-bucket monotonicity (cross derivation tests only last-vs-first sign today). |
| Failed-cross test (S22): one side reduces but the OTHER does NOT increase → only short-covering | 3.5 S22(b) / 6.5 | PARTIAL | AUTOMATE_PKG | `oi-cross-hard-gate` — promote the two-signed-delta requirement from a soft dot to a hard reject (captured but only as a soft dot). |
| Flat-OI trap: persistent 50% diff with unchanged absolute OI = no move; stand aside | 3.5 Setup.5 caveat / Edge-cases / 6.5 | PARTIAL | AUTOMATE_PKG | `flat-oi-stand-aside` — treat null/flat imbalance as a soft stand-aside, NOT the current degrade-to-PASS (the caveat inverts the doc's "stand aside" intent). |
| End-of-series ambiguity: both sides covering at series end → use next-series data | 3.5 Exit.time / Edge-cases / 6.5 | NONE | AUTOMATE_PKG | `intraday-positional-oi` — needs cross-series OI state (series-rollover / both-sides-covering detector); positional/series-state theme. |
| Monthly-expiry day: confirm BOTH positional and intraday OI | 3.5 S21(d) / 6.5 | MANUAL_COVERED (via suppression) | AUTOMATE_PKG | `intraday-positional-oi` — engine SUPPRESSES OI on monthly expiry (inert), it does NOT confirm positional-vs-intraday; a compare is buildable. (Also reminded by FU1 `oi_intraday_positional` for the live read.) |
| Direction-change arrows after a large gap/morning move flag a trend change | 3.5 S21 / Filters / 6.5 | NONE | AUTOMATE_PKG | `oi-direction-change-arrows` — an arrow = a slope-sign flip detector off the OI series. |
| Strike housekeeping: keep strikes if move <1%; reset to ATM ±7 if >1% | 3.5 S21(e) / 6.5 | PARTIAL | AUTOMATE_PKG | `dynamic-strike-recenter` — re-anchor the OI window on a >1% price-move threshold (fixed ATM ±3 today, no re-centring, width 3 not ±7). |
| Time-of-day: best 10–11:30 AM; avoid initiating after ~1:30–2 PM | 3.5 Setup.3 / S21(b) / Filters / 6.5 | PARTIAL | AUTOMATE_PKG | `time-of-day-preference` — a soft time-of-day preference dot (hard rails ≥09:45 / 11–13 block / ≤15:30 are encoded; the 10–11:30 preference + ~1:30 cutoff are not weighted). |
| Position sizing: SMALL on RSI-cooling / low-probability; full on drastic-shift+volume | 3.5 Risk / 6.5 | PARTIAL | AUTOMATE_PKG | `probability-graded-sizing` — grade size off confluence aggregate + RSI proximity (fixed ₹15k budget today). |
| >50% OI difference at the close signals next-day directional bias | 3.5 S21(f) / 6.5 | NONE | AUTOMATE_PKG | `intraday-positional-oi` — EOD-OI gap is computable; the overnight/next-day carry is SPAN/positional-gated. |
| Night-risk / overnight rails: ≤1 night, avoid Friday overnight; intraday is scalping-only | 3.5 Risk / 6.5 | PARTIAL | ACCEPT_BY_DESIGN | These YAMLs are forced intraday-flat (`square_off 15:15`), so overnight rails are moot; overnight itself is SPAN/live-order gated. Wontfix here. |
| News overrides the data (event risk) | 2.13 / common | MANUAL_COVERED | COVERED_EXISTING | Shipped `news_clear` check (ScalperManualChecks doc_ref 2.13). |
| Right S/R zone (reacting at S/R, not into a wall) | 4.11 / common | MANUAL_COVERED | COVERED_EXISTING | Shipped `level_respected` check (doc_ref 4.11). S/R lines are manual-marked. |
| Not chasing a parabolic/vertical move; clean one-good-trade setup | 3.1 / common | MANUAL_COVERED | COVERED_EXISTING | Shipped `not_parabolic` + `clean_setup` checks. |
| Regime suits the setup (trending, not choppy: >2–3 VWAP crossovers = chop) | 3.10 / common | MANUAL_COVERED | COVERED_EXISTING | Shipped `regime_ok` check (doc_ref 3.10). (VWAP-cross count is computable — a future automation, but the gap itself is already manual-covered.) |
| India VIX not abnormally spiking; global cues not against the trade | 4.5 / 4.7 / common | MANUAL_COVERED | COVERED_EXISTING | Shipped `vix_normal` (4.5) + `global_cues_ok` (4.7) checks. VIX-direction dot + Dow cues degrade to NEUTRAL on history and are OUT of FU2; the absolute-band read is additionally reminded by FU1 `vix_regime_bands`, but the spike/cue gap itself is already manual-covered. |

## Disposition counts

| Disposition | Count |
|-------------|-------|
| COVERED_EXISTING | 5 |
| COVERED_FU1 | 0 |
| COVERED_FU2 | 0 |
| AUTOMATE_PKG | 20 |
| KEEP_MANUAL_NEW | 0 |
| ACCEPT_BY_DESIGN | 2 |
| UNCERTAIN_OWNER | 1 |
| **Total** | **28** |

### AUTOMATE_PKG themes (for the synthesizer)

- `oi-cross-hard-gate` (×3) — promote the cross + slope-agreement + two-signed-delta from soft dots to hard pre-gates/rejects.
- `oi-divergence-magnitude` (×2) — threshold the ~20–30% / ≥50% gap % and the corroborating price-impulse %.
- `oi-interval-and-60m-trend` (×1) — explicit 5–15 min analytics interval + wire the 60-min OI broader-trend read.
- `probability-graded-sizing` (×2) — grade size off confluence aggregate + RSI proximity / drastic-OI.
- `bearish-pe-mirror-yaml` (×1) — add the mirror PE-LONG trending-oi YAML.
- `trade-management-targets-trailing` (×2) — % premium TP + RSI-proximity trailing exit.
- `fake-cross-side-flip` (×1) — side-flip on a confirmed opposite cross.
- `incomplete-cross-reject` (×1) — in-window per-bucket monotonicity to reject a stalled cross.
- `flat-oi-stand-aside` (×1) — null/flat imbalance → soft stand-aside instead of degrade-to-PASS.
- `intraday-positional-oi` (×3) — positional/series-state OI: end-of-series ambiguity, monthly-expiry positional-vs-intraday compare, EOD>50% next-day bias.
- `oi-direction-change-arrows` (×1) — slope-sign-flip arrow detector.
- `dynamic-strike-recenter` (×1) — re-anchor the OI window on a >1% move.
- `time-of-day-preference` (×1) — soft 10–11:30 AM preference / ~1:30 PM cutoff dot.

### Notes on FU1/FU2 overlap (no double-counting)

- FU1's `oi_intraday_positional` and `vix_regime_bands` manual checks *touch* the same subject as several
  rows here, but those rows are dispositioned to their stronger home: the VIX spike/cue rows are already
  `COVERED_EXISTING` (`vix_normal`/`global_cues_ok`), and the positional-OI rows are `AUTOMATE_PKG`
  (`intraday-positional-oi`) because the gap is an automatable compare, not merely a reminder. No
  trending-oi gap row is *only* covered by an FU1 check, so `COVERED_FU1 = 0`.
- FU2 promotes indicator-alignment / futures-oi / breadth / basis — none of which is a trending-oi
  audit gap row here (the futures-OI row 25 and VWAP/volume rows are FULL in this dimension), so
  `COVERED_FU2 = 0`.
