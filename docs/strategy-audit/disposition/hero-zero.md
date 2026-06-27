# Hero-Zero (Expiry-Day OI) — gap disposition

Dispositions every non-FULL row from `docs/strategy-audit/hero-zero.md` (every PARTIAL / NONE /
MANUAL_COVERED / UNCERTAIN row = a gap). Each gap gets exactly one home so no gap is left unaccounted.

Disposition legend:
- **COVERED_EXISTING** — already carried by the shipped 7-item `ScalperManualChecks`.
- **COVERED_FU1** — one of the 9 manual checks Follow-up-1 adds.
- **COVERED_FU2** — one of the 4 soft dots Follow-up-2 promotes to a hard gate.
- **AUTOMATE_PKG** — automatable, not in FU1/FU2 → a work-package theme.
- **KEEP_MANUAL_NEW** — genuinely manual-only (judgement / no data source), not in FU1.
- **ACCEPT_BY_DESIGN** — soft-by-design / low-value / derived-history artifact → wontfix.
- **UNCERTAIN_OWNER** — doc ambiguous or an owner choice → open point.

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|---|---|---|---|---|
| Do not trade before ~2:30-2:45 PM (S21 tightens S20 "after 2 PM") | §3.7 S21 (a); §5.7 S21 | PARTIAL | AUTOMATE_PKG | **expiry-entry-timing** — move `HeroZeroGate.RANGE_FROM` from 14:30 to ~14:45; trivial constant change, fully automatable. |
| Decide the SIDE after 1:30-2 PM by premium / where shorts build | §3.7 S21 (b); §6.7 exit.time_exit | PARTIAL | AUTOMATE_PKG | **strike-premium-band-backtest** — premium-decay / where-shorts-build read needs the live per-option-chain premium series; side currently a VWAP+extreme proxy. Partially automatable once per-strike premium series exists. |
| Conditional 3:10 PM no-move exit (S22, tighter than 3:20) | §5.7 S24 (e); §6.7 exit.stop_loss | NONE | AUTOMATE_PKG | **trade-management-targets-trailing** — a time-conditional MTM-flat exit (exit at 15:10 if flat). Fully automatable. |
| Identify highest OI/volume build-up = the day's range (Put=support, Call=resistance) | §3.7 setup-2/3; §6.7 setup_preconditions[1][2] | PARTIAL | COVERED_EXISTING | Loosely carried by the shipped `level_respected` manual check (`ScalperManualChecks.java:31`, §4.11) — the gate anchors off max-OI strike but does not assert price reacting at S/R; the manual item is the existing on-card reminder. |
| Strong-move confirmation: BOTH OI >50% AND price >50% on the SAME side | §3.7 setup-7 / Bullish-4; §6.7 setup_preconditions[7] | PARTIAL | COVERED_FU2 | The `>=50% dOI imbalance` soft dot is one of FU2's promoted hard gates; FU2 hardens the OI leg of this >50% confirmation. (The remaining per-strike-price leg is tracked separately under `strike-premium-band-backtest` — see next row.) |
| Strong-move confirmation runs on index spurt + call/put imbalance, NOT the specific entry strike (S24 (d) per-strike thresholds) | §3.7 setup-7; §6.7 setup_preconditions[7] | PARTIAL | AUTOMATE_PKG | **strike-premium-band-backtest** — the per-strike >50% OI+price (CE ≥50% up + big OI jump; PE ~70-78% fall + ~85% OI jump) needs per-strike premium %-change, not the index spurt. (Same source row as above; this captures the per-strike-price residual FU2 does not cover.) |
| Significant short covering with a drastic FALL in OI on the side | §3.7 Bullish-4; §6.7 entry.bullish[3] | PARTIAL | AUTOMATE_PKG | **drastic-oi-floor** — wire the existing `ScalperOiProps.drasticFloor` (default 50000) into `HeroZeroGate.shortCovering()`; today any negative side delta passes. Fully automatable, data already present. |
| Confirm the underlying direction on the 3-min Futures chart (doc: Bank Nifty 3m) | §3.7 Bullish-2, Bearish-2; §6.7 timeframe / entry.bullish[1] | PARTIAL | ACCEPT_BY_DESIGN | The 3m-future direction read IS automated; the only delta is it runs on NIFTY-FUT-CONT not the deck's Bank-Nifty 3m — a deliberate ADR-0003 / continuous-future-spine choice, not a defect. Wontfix. |
| Expiry-day OI double-confirmation pattern (short-build Call + long-build Put ⇒ buy Put; sellers shifting Call→Put writing ⇒ buy Call) | §3.7 Bullish-8; §6.7 entry_conditions.bullish[7] | NONE | AUTOMATE_PKG | **intraday-positional-oi** — a cross-side LB/SB build-up classifier; `ScalperGateContext.Oi` quadrants partly carry it. Partially automatable; distinct from the same-side short-covering the gate uses. |
| Index-scaled point SL: BN ~75, Nifty ~30, wider Sensex/Bankex (S22 resolution) | §3.7 S22 (a); §6.7 exit.stop_loss; §2.12-53 | NONE | AUTOMATE_PKG | **trade-management-targets-trailing** — a points-based stop on the index future, scaled per index. Fully automatable. |
| Deploy only ~10% of profits / ₹1,000-2,000, small size, never capital | §5.7 S24 (a); §2.12-58; §5.7 | NONE | AUTOMATE_PKG | **profit-slice-sizing** — needs a running realised-PnL feed to size off profits instead of the flat `budget_inr: 2000`. Partially automatable. |
| Do NOT average a loser (set a level, let it go to zero) | §5.7 S24 (a) | NONE | ACCEPT_BY_DESIGN | Already incidentally enforced by `max_positions_per_underlying: 1` (a second add cannot fire). No explicit anti-averaging rule needed — the position cap is the de-facto guard. Wontfix. |
| No trades when sellers pin price at VWAP (premium erosion) | §3.7 filters / edge_cases[0]; §6.7 filters[7] | NONE | AUTOMATE_PKG | **vwap-distance-sizing** — a low-range / VWAP-distance sit-out skip; VWAP is already computed (used only to route the side today). Fully automatable. |
| Round-strike double-zero pin warning (both CE+PE expire at zero) | §3.7 S22 (h) | NONE | KEEP_MANUAL_NEW | Pin judgement near a round strike into the close — discretionary read with no clean data trigger; a future manual-check candidate beyond FU1. |
| IV flat on BOTH sides = no trade (sellers control both, only erosion) | §3.7 S22 (i); §5.7 S22 | NONE | AUTOMATE_PKG | **iv-flat-both-sides** — `ceIvAvg6`/`peIvAvg6` already sit in `ScalperGateContext.Macro` (line 67); wire an IV-gap check like #5's `ivPairMinGap` into `HeroZeroGate`. Fully automatable, data present. (Distinct from FU1's `iv_crush_awareness`, which is expiry-afternoon IV decay, not the both-sides-flat skip.) |
| CAUTION: do NOT take a PE trade when calls trade at a discount | §3.7 Bearish-7; §6.7 entry.bearish[7] | NONE | AUTOMATE_PKG | **strike-premium-band-backtest** — needs a CE-vs-fair-premium discount read off the per-strike premium series; partially automatable. |
| Premium-adjustment fake-out moves (don't be faked out) | §3.7 edge_cases; §6.7 edge_cases[1] | NONE | KEEP_MANUAL_NEW | Purely discretionary "real squeeze vs PE-premium-adjust fake" read; audit itself marks Automatable: no. Manual-only, beyond FU1. |
| US/global cues give the clue for the next move | §3.7 setup-6 / filters; §6.7 filters[8] | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `global_cues_ok` check (`ScalperManualChecks.java:51-55`, §4.7). (A DOW-futures direction gate exists for Connect-the-Dots but is not wired into Hero-Zero — that automation is the out-of-scope `global-cues-feed` theme, but the gap itself is already manually covered.) |
| India VIX not abnormally spiking | §3.7 (general risk) | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `vix_normal` check (`ScalperManualChecks.java:46-50`, §4.5). (The richer VIX absolute-band read is FU1's separate `vix_regime_bands`; the abnormal-spike gap here is already on the shipped checklist.) |
| No market-moving news/event against the trade; no new entries before events | §3.7 risk; §6.7 risk_management[6] | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `news_clear` check (`ScalperManualChecks.java:26-30`, §2.13). Automatable: no. |
| Not chasing a parabolic/vertical move | §3.7 (clean-setup discipline) | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `not_parabolic` check (`ScalperManualChecks.java:36-40`, §3.1). |
| Prep: 5 strikes either side of ATM (10 if volatile), round strikes a must; last 3-4 days OI | §3.7 setup-5; §5.7 S24 | NONE | KEEP_MANUAL_NEW | Evening pre-market human ritual; the chain-fetch ATM±3 window is a different concern. A pre-market OI-change report is partially automatable, but the prep ritual itself is a manual-only candidate beyond FU1. |
| Payoff scale / Gamma-squeeze target (no explicit numeric target) | §3.7 exit.target (UNCERTAIN); §6.7 exit.target | UNCERTAIN | ACCEPT_BY_DESIGN | Doc states NO explicit numeric target (ride the squeeze, exit by time-close). Correctly NOT encoded — doc-silent by design, no gap to fill. |

## Disposition counts

- COVERED_EXISTING: 5
- COVERED_FU1: 0
- COVERED_FU2: 1
- AUTOMATE_PKG: 9
- KEEP_MANUAL_NEW: 3
- ACCEPT_BY_DESIGN: 3
- UNCERTAIN_OWNER: 0

Total gap rows: 21 source non-FULL rows. The "Strong-move confirmation" PARTIAL row carries two
distinct sub-gaps (the OI-leg, hardened by FU2; the per-strike-price leg, an AUTOMATE_PKG residual)
and is split across two table rows here so each sub-gap has its own home — so the disposition table
has 22 rows while accounting for all 21 source non-FULL rows.

## AUTOMATE_PKG themes (for the synthesizer)

- **expiry-entry-timing** — tighten the 14:30 entry floor to the S21/S23 ~14:45.
- **strike-premium-band-backtest** — per-strike premium %-change series (side-by-premium decay,
  per-strike >50% confirmation, PE-at-CE-discount caution).
- **trade-management-targets-trailing** — 3:10 PM no-move time-exit + index-scaled point SL.
- **drastic-oi-floor** — wire `ScalperOiProps.drasticFloor` into `HeroZeroGate.shortCovering()`.
- **intraday-positional-oi** — cross-side LB/SB build-up double-confirmation classifier.
- **profit-slice-sizing** — size off realised PnL (~10% of profits) instead of a flat budget.
- **vwap-distance-sizing** — VWAP-pin / low-range sit-out skip.
- **iv-flat-both-sides** — wire `ceIvAvg6`/`peIvAvg6` IV-gap check into `HeroZeroGate`.
</content>
</invoke>
