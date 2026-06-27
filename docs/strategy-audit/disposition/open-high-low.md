# Open=High / Open=Low (Siva #2) — gap disposition

Every non-FULL row from `docs/strategy-audit/open-high-low.md` (the audit table, lines 16–47) is assigned
exactly one disposition so no gap is left unaccounted. Source non-FULL rows = **23** (PARTIAL / NONE /
MANUAL_COVERED; 30 table rows − 7 FULL). (The expected baseline of 22 predates one of the v2-added rows —
"Confirm OH on Futures LB/SC" / "Do not jump straight to buying"; all are included below.) Dispositions
reference the two follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| Restrict to ATM/ITM strikes, ATM ±3; avoid OTM / deep ITM | §3.2 / §6.2 setup[3] | PARTIAL | AUTOMATE_PKG | `strike-premium-band-backtest` — footprint window is symmetric ATM±3; exclude OTM legs from selection |
| Change in OI on identified strike not increased >50% (per-strike ΔOI) | §3.2 / §6.2 setup[5] | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — needs per-strike ΔOI% in strike-session-stats; today only chain-wide OI-spurt % is checked (groups with the per-strike-stat work) |
| Momentum up: RSI>50 AND RSI5m<75/80, RSI(D)<75 | §3.2 / §6.2 entry[3] | PARTIAL | AUTOMATE_PKG | `multi-timeframe-rsi` — only the >50 floor on 3m RSI is gated; the <75/80 5m cap + daily-RSI<75 cap are unenforced |
| All indicators (VWAP/Supertrend/VWMA) below price for CE (above for PE) | §3.2 / §6.2 entry[3] | PARTIAL | COVERED_FU2 | FU2 WI-1 promotes `indicator-alignment` (the "all soldiers on the far side" conjunction) to a hard gate — today only VWAP is hard, ST/VWMA/PSAR are soft dots |
| OI build-up: Call OI declining / Put OI increasing (option-side bullish) | §3.2 / §6.2 entry[5] | PARTIAL | COVERED_FU2 | FU2 WI-2 promotes the futures-OI quadrant to a hard gate; the option-side build-up direction folds into the same OI hard-leg work (today a soft `underlying_oi`/`trending_cross` dot, NEUTRAL on derived history) |
| Trade in the ideal 9:15–10:00 window; ~90% of OH hit before 10:30 | §3.2 / S22 | NONE | AUTOMATE_PKG | `event-calendar-lockout` — encode the 9:15–10:00 ideal window + 10:30 freshness cut at the seam (time-of-day windowing is automatable; today only ≥09:45 floor + 09:45–12:00 session window) |
| OI Pulse probability ≥90% WITH badge (red dot); do not chase below 90% | §3.2 / §6.2 entry[2] | NONE | KEEP_MANUAL_NEW | No parity model / external feed (explicitly an unavailable Phase-4 OiPulse-parity model, "never required"); read the OI-Pulse AI badge ≥90% manually — future manual-check candidate / trader discretion |
| Premium bands (S22-operative): Nifty 150–350 avoid <130/>380; BN 250–550 | §3.2 / §6.2 entry[4] | PARTIAL | AUTOMATE_PKG | `strike-premium-band-backtest` — the S22 bands are not used (NIFTY hardcoded to older 100–250) and the backtest selector ignores the band entirely |
| Choose strike whose premium is nearest its OH target | §3.2 / §6.2 exit | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — the "premium nearest the OH magnet" selection needs per-strike OH levels read as live targets |
| Target: 30–50 pts; target the OH/OL extreme but never beyond; exit ~5 pts inside | §3.2 / §6.2 target | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — profit target unautomated (engine carries only VWAP signal-exit + 20-bar time stop) |
| Always trail SL once in profit; trail up from the OH number | §3.2 / S21 / §6.2 scaling | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — add a trailing-stop exit rule (only the static VWAP signal-exit exists) |
| Abort/exit if premium falls >50% AND/OR strike ΔOI >50% (bigger opposite player) | §3.2 / §6.2 stop_loss | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — encoded as an ENTRY reject only; add an in-trade abort/exit monitor |
| Adverse move on >50K(BN)/125K(N) volume candle = exit | §3.2 / §6.2 risk[1] | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — the 50K floor is only a Table-2 entry downgrade; add an in-trade adverse-volume exit |
| Never deploy more than 30% of capital on this trade | §3.2 / §6.2 risk[0] | NONE | AUTOMATE_PKG | `probability-graded-sizing` — a per-strategy capital-fraction cap (today only `budget_inr` + `max_daily_loss_pct` + `max_positions:1`) |
| VIX down (bull) / up (bear) supportive | §3.2 / §6.2 filters | PARTIAL / MANUAL_COVERED | AUTOMATE_PKG | `directional-vix-gate` — wire VIX direction (live feed exists; explicitly OUT of FU2). Spike-avoidance already carried by `vix_normal`; FU1 adds `vix_regime_bands` for the absolute bands |
| IV rising (bull) / falling (bear) in that strike; IV-rank low = cheap | §3.2 / §6.2 indicators | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — per-strike IV direction for the bought strike is not gated; today only aggregate iv_rank/iv_pair dots (NEUTRAL on derived history) |
| Volume floor confirmation (50K BN / 125K N) for the breakout entry | §3.2 / §6.2 filters | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — a signal-index volume floor is enforced, but the doc means the per-strike option breakout volume (needs per-strike stats; groups with the strike-stat work) |
| Confirm OH on Futures with Long Build-up (preferred) / Short Covering (mirror SB/LU for PE) | §3.2 / §6.2 entry[0] | PARTIAL | COVERED_FU2 | FU2 WI-2 promotes the futures-OI quadrant (`futures-oi-gate`, forward-only) to a hard OH leg — today only the soft `futures_oi` dot, NEUTRAL on derived history |
| Do not jump straight to buying on the bare OH sighting — wait for confirmed probability/momentum | §3.2 / §6.2 edge_cases[5] | MANUAL_COVERED | COVERED_EXISTING | `not_parabolic` / `clean_setup` (ScalperManualChecks) + the RSI>50 / hard-VWAP momentum rails cover "don't chase, wait for confirmation" |
| Trend alignment: OH WITH the day's trend = high-prob; opposite = low-prob | §3.2 setup[7] | PARTIAL | ACCEPT_BY_DESIGN | The optional 60-min `bias60m` SUPERTREND is the only trend filter and only blocks when present (absent ⇒ never blocks) — soft-by-design / degrades on derived history; trader confirms the trend side |
| No market-moving news against the trade (news overrides data) | §2.13 | MANUAL_COVERED | COVERED_EXISTING | `news_clear` (ScalperManualChecks, doc_ref 2.13) — shipped 7-item checklist |
| Global cues not against the trade (DOW futures, Asian indices, crude, USD) | §4.7 | MANUAL_COVERED | COVERED_EXISTING | `global_cues_ok` (ScalperManualChecks, doc_ref 4.7) — shipped 7-item checklist |
| Regime suits the setup (trending, not choppy); not parabolic; clean one-good-trade | §3.1 / §3.10 | MANUAL_COVERED | COVERED_EXISTING | `regime_ok` / `not_parabolic` / `clean_setup` (ScalperManualChecks) — shipped 7-item checklist |

### Disposition counts

- COVERED_EXISTING: 4
- COVERED_FU1: 0
- COVERED_FU2: 3
- AUTOMATE_PKG: 14
- KEEP_MANUAL_NEW: 1
- ACCEPT_BY_DESIGN: 1
- UNCERTAIN_OWNER: 0
- **Total non-FULL rows: 23** (matches the 23 non-FULL rows in `open-high-low.md` lines 16–47: 30 table rows − 7 FULL)

### AUTOMATE_PKG themes (for the synthesizer)

- `strike-premium-band-backtest` — backtest selector honours premium band + ATM/ITM-only (excludes OTM)
- `iv-per-strike` — per-strike IV direction, per-strike ΔOI%, per-strike breakout volume (strike-session-stats)
- `multi-timeframe-rsi` — 5m<75/80 + daily-RSI<75 overbought caps on top of the 3m >50 floor
- `event-calendar-lockout` — 9:15–10:00 ideal window + 10:30 freshness cut at the seam
- `sr-levels-targets-stops` — "premium nearest the OH target" strike selection (needs per-strike OH levels)
- `trade-management-targets-trailing` — 30–50pt target / ~5pt-inside, trailing stop, in-trade >50% abort, adverse-volume exit (4 rows fold into this theme)
- `probability-graded-sizing` — 30%-of-capital fraction cap
- `directional-vix-gate` — wire VIX direction (OUT of FU2; VIX feed)
