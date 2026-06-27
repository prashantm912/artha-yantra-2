# Global cues / A-D / Strike / Time / S&R / OIP / FII-DII (§4.7–4.13) — gap disposition

Every non-FULL row from `docs/strategy-audit/gates-strike-sr-fiidii.md` (the audit table, lines 14–38) is
assigned exactly one disposition so no gap is left unaccounted. Source non-FULL rows = **18** (PARTIAL /
NONE / MANUAL_COVERED; 25 table rows − 7 FULL at L16/L17/L18/L19/L24/L26/L33). (The expected baseline of 17
predates one of the three v2-added §4.13 sub-rows; all are included below.) Dispositions reference the two
follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| Global cues (DOW/DOW-30 fut, Dollar Index, Asian, Oil) must match direction | 4.7 | PARTIAL | COVERED_EXISTING | `global_cues_ok` (ScalperManualChecks, doc_ref 4.7) covers the trader; only DOW is even fetched (into market-data, not the scalper gate) — Dollar/Asian/Oil auto-feed is the separate `global-cues-feed` pkg |
| Re-check global cues at 3:15 PM for EOD / next-day setups | 4.7 | NONE | KEEP_MANUAL_NEW | Live scalper is intraday-only; a 15:15 BTST cue re-check needs the missing global feeds + a scheduled EOD evaluator — trader discretion |
| Choose the AI-suggested strike within the price range | 4.9 / 4.12 | NONE | KEEP_MANUAL_NEW | No OIP/AI-suggested-strike feed exists; trader confirms the strike matches the OIP AI suggestion — not automatable without an external feed |
| Avoid strikes with OI heavy on BOTH call AND put sides ("Desirables" avoid) | 4.9 | PARTIAL | AUTOMATE_PKG | `oi-cross-hard-gate` — chain CE/PE OI per strike is already fetched; make the both-sides-OI avoid a shared dot/gate (today only #2's OH-footprint stand-aside) |
| Option-strike confirm: open=high call + open=low put (bullish) / reverse, ~90% prob | 4.9 | PARTIAL (MANUAL-adjacent) | AUTOMATE_PKG | `oi-cross-hard-gate` — encoded only for #2; promote the open=high/open=low strike-session-stats grading to a shared dot for the other strategies |
| Freshness: option price not moved >50% vs prev day; identified-strike OI change not >50% | 4.9 | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — #2-only and uses aggregate spurt %; needs the specific strike's prev-close + per-strike ΔOI (strike-session-stats has it) |
| Ideal window 9:15–10:00 / let RR ~1% after 9:45 | 4.10 | NONE | ACCEPT_BY_DESIGN | A soft qualitative preference, not a discrete gate (only the hard ≥09:45 floor is coded) — low-value to harden; trader prefers the early window |
| No fresh entries after 15:30; events after 3:30 PM → keep off | 4.10 | PARTIAL | AUTOMATE_PKG | `event-calendar-lockout` — the 15:30 TIME cap is FULL; the "impending event after 3:30 → keep off" needs an economic-calendar feed (news_clear covers it manually) |
| Morning trade is scalping only — finish on target/SL | 4.10 | MANUAL_COVERED | ACCEPT_BY_DESIGN | Exit mechanics (time_stop + square_off) are already automated; the "don't convert a scalp to a hold" discipline is human-by-design |
| Expiry-day Hero-Zero only after 2:00 PM; watch SC at 2:30–3:00 PM around S/R | 4.10 | PARTIAL | AUTOMATE_PKG | `sr-levels-targets-stops` — the time gate is coded (14:30 per deck); the "observe SC near S/R 2:30–3:00" needs the absent S/R input |
| Mark S/R on 1d, refine on 15m; trade retrace/pullback; targets = next S/R | 4.11 | NONE | AUTOMATE_PKG | `sr-levels-targets-stops` — S/R zones entirely un-automated; pivot/zone detection on 1d+15m feasible but unbuilt (`level_respected` covers it manually) |
| OIP AI direction must match pre-market direction + own view before firing | 4.12 | NONE | COVERED_FU1 | FU1 `pre_open_bias` covers the pre-market-direction read; the external OIP "AI" verdict itself is not ingestable (no OIP feed) |
| At 3:20 PM your view and OI Pulse view should match (BTST/next-day) | 4.12 | NONE | KEEP_MANUAL_NEW | No 15:20 OIP-alignment check; live scalper is intraday — trader confirms next-day alignment manually (no OIP AI feed) |
| VIX read (falling→CE / rising→PE) used in confluence | 4.12 / 4.5 | PARTIAL | AUTOMATE_PKG | `directional-vix-gate` — the VIX dot is coded but starved (null feed); INDIA VIX candles exist (used by ConnectingDotsService) but aren't exposed to MarketOiClient. FU1 adds `vix_regime_bands`; spike covered by `vix_normal` |
| FII/DII participant-wise OI as a Dot; importance FII>Pro>DII>Client; absolute + change-in-OI | 4.13 | NONE | AUTOMATE_PKG | `fii-dii-bias` — full participant matrix is captured (`nse_eod_participant_oi`) but the scalper reads none of it; even the one fetched `fiiLongPct` is dead-wired. FU1 `fii_ls_ratio` adds the manual L/S read |
| §4.13 change-in-OI classifier (LB/SC/LU/SB 4×2 table) | 4.13 | NONE | AUTOMATE_PKG | `fii-dii-bias` — the most concrete + automatable FII sub-rule, zero code; both days are in `nse_eod_participant_oi`, needs the L/U/B/C delta classifier + dot |
| §4.13 leg-level seller read (net-seller of Calls = bearish / Puts = bullish, 4 participants × 6 legs) | 4.13 | NONE | AUTOMATE_PKG | `fii-dii-bias` — no per-leg participant net-position read; data captured, needs a leg-level classifier + dot |
| §4.13 bias validity is next-morning-only, voided by strong global moves | 4.13 | NONE | AUTOMATE_PKG | `fii-dii-bias` — a scope qualifier on the FII bias; moot until the FII dot exists (rides the same pkg) |

### Disposition counts

- COVERED_EXISTING: 1
- COVERED_FU1: 1
- COVERED_FU2: 0
- AUTOMATE_PKG: 11
- KEEP_MANUAL_NEW: 3
- ACCEPT_BY_DESIGN: 2
- UNCERTAIN_OWNER: 0
- **Total non-FULL rows: 18** (matches the 18 non-FULL rows in `gates-strike-sr-fiidii.md` lines 14–38: 25 table rows − 7 FULL at L16/L17/L18/L19/L24/L26/L33)

### AUTOMATE_PKG themes (for the synthesizer)

- `oi-cross-hard-gate` — both-sides-OI avoid + open=high/open=low strike confirm as shared dots/gates (2 rows)
- `iv-per-strike` — per-strike >50% prev-close-move + per-strike ΔOI freshness
- `event-calendar-lockout` — "event after 3:30 → keep off" economic-calendar gate
- `sr-levels-targets-stops` — S/R zone detection (1d+15m) for entries/targets/stops + Hero-Zero SC-near-S/R (2 rows)
- `directional-vix-gate` — expose the existing INDIA VIX series to the scalper gate (OUT of FU2)
- `fii-dii-bias` — participant-OI dot, change-in-OI LB/SC/LU/SB classifier, leg-level seller read, next-morning validity (4 rows)
- `global-cues-feed` — (referenced for the §4.7 Dollar/Asian/Oil auto-feed; the row itself is COVERED_EXISTING)
