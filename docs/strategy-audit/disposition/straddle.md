# Straddle — gap disposition

Every non-FULL row from `docs/strategy-audit/straddle.md` (the table at lines 17-40) is assigned
exactly one disposition so that no gap is left unaccounted. Source: 24 table rows, 3 FULL (both-legs-ATM,
long=BUY-both, volume-floor) excluded -> **21 gap rows dispositioned below**.

Disposition legend: COVERED_EXISTING (shipped 7-item `ScalperManualChecks`) · COVERED_FU1 (one of the 9
checks Follow-up-1 adds) · COVERED_FU2 (one of the 4 dots Follow-up-2 promotes) · AUTOMATE_PKG (automatable,
not in FU1/FU2 -> work-package theme) · KEEP_MANUAL_NEW (manual-only judgement, beyond FU1) · ACCEPT_BY_DESIGN
(wontfix, reason) · UNCERTAIN_OWNER (owner's call / ambiguous).

Key routing fact: the straddle path runs `neutralConfluence()` and returns BEFORE the directional side /
OI fan-out (`ScalperConfluenceGate.java:132-147`). FU2's four hard gates sit on the **directional** path and
the FU2 plan states the neutral straddle path "is never reached by these gates" — so NO straddle row routes to
COVERED_FU2.

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|---|---|---|---|---|
| Strike: ATM default automated; OTM "safer bet" alternative (one leg ITM) not selectable, no offset knob | 3.11 setup #4 / 6.11 | PARTIAL | AUTOMATE_PKG | `straddle-strike-offset` — add an offset/width knob to `StraddleLegPicker` so the OTM-safer-bet variant is selectable (ATM default already automated). |
| Short straddle = SELL ATM Call + Put (range/decay play) — entirely unbuilt | 3.11 / 6.11 | NONE | AUTOMATE_PKG | `short-premium-span` — SPAN-deferred (#47); short straddle (entry/exit/VWAP-break/hard-SL) unbuilds once the SPAN appliance gates short premium. Groups with all short-side rows below. |
| Entry trigger: combined straddle premium breaks ABOVE its own VWAP **with volume** (long) | 3.11 / 4.15.2 / 6.11 | NONE | COVERED_FU1 | FU1 item 7 `straddle_vwap_entry` (§4.15.2) — on-card reminder; the live combined-premium-VWAP seam itself is `strike-premium-band-backtest` automation (§7 downstream), not in FU1/FU2. |
| Short entry trigger: after 9:30 AM, price FALLS BELOW the VWAP of both Call and Put | 3.11 / 6.11 | NONE | AUTOMATE_PKG | `short-premium-span` — no short path exists; automatable once the short straddle is built (#47). |
| Event/budget long form: after ~12:30 PM closes ABOVE both-leg VWAP — and collides with the engine 11:00-13:00 midday block | 3.11 / 6.11 | NONE | AUTOMATE_PKG | `straddle-event-window` — needs an event-aware time window that overrides `MIDDAY_BLOCK_FROM/TO` for the event-long form (`ScalperGates.java:23-24,37-39`). |
| Long SL = BELOW the (combined) VWAP; Short SL = ABOVE the VWAP | 3.11 / 6.11 | NONE | AUTOMATE_PKG | `strike-premium-band-backtest` — real SL on the combined-premium VWAP (engine uses a 50%-premium proxy); both leg premiums known at entry, computable. Short-side SL groups with `short-premium-span`. |
| Long exit: lower-low candle / combined premium peaks and rolls over | 3.11 / 6.11 | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — rollover/lower-low on the combined-premium series is computable; engine has only a generic 30-bar time-stop + 15:15 square-off. |
| Short exit: premium decay / EOD / immediately if price breaks back through VWAP | 3.11 / 6.11 | NONE | AUTOMATE_PKG | `short-premium-span` — no short path / no VWAP-break exit; automatable once short straddle built. |
| One-leg management: once combo clears VWAP and only one leg gains, drop the loser, hold the winner | 3.11 / 6.11 | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — per-leg P&L is available live; leg-drop logic not modelled (gate emits a static two-leg draft). FU1 item 7's reminder mentions it, but the *automation* belongs here. |
| Pick LONG vs SHORT from volatility view (event+LOW IV -> long; range+similar IV -> short) | 3.11 / 6.11 | NONE | UNCERTAIN_OWNER | Open point — the IV/range half is automatable (`iv-per-strike`) but the "event" half is judgmental; whether to build variant auto-selection or leave it discretionary is the owner's call. |
| LOW-IV gate for the long straddle (high-IV long loses both legs on an IV crash) | 3.11 / 6.11 filters | NONE | AUTOMATE_PKG | `iv-per-strike` — per-strike IV (`ConnectingDotsService.activeStrikeIv`) + VIX feeds exist but the neutral gate does not read them; wire a LOW-IV precondition for the long. |
| Short wants Call-side & Put-side IV similar/equal | 3.11 / 6.11 filters | NONE | AUTOMATE_PKG | `iv-per-strike` — per-leg IV available; CE-vs-PE IV-symmetry check unbuilt (and gated behind the short path / `short-premium-span`). |
| IV > 40 -> stay away as a buyer; a 40/40 reading -> play short straddle | 3.11 / 6.11 filters / 4.6 | NONE | AUTOMATE_PKG | `iv-per-strike` — IV-threshold gate (skip long when IV>40; 40/40 -> short condition). IV feed exists; not in the straddle path. |
| Breakeven: underlying must move > combined premium from the strike (don't pay ~1000 for a 100-200pt move) | 3.11 / 6.11 setup/risk | NONE | AUTOMATE_PKG | `straddle-breakeven-sizing` — compute combined-premium breakeven + expected-move check; sizing is currently a flat `premium_budget`. Both leg premiums known at entry. |
| Trending-OI confirmation: change-in-OI together = range = short; divergence/break = long/directional | 3.11 / 6.11 filters | NONE | ACCEPT_BY_DESIGN | The neutral straddle path uses `neutralConfluence()` and disables the OI gate (`oi_confluence_gate.enabled:false`); OI factors degrade to NEUTRAL on derived history, so this reads MUTED on backtests and is a forward-paper-only signal — not a backtest-gatable rule (data-fidelity artifact, judge on forward paper). |
| 5-minute straddle-chart timeframe (engine runs 3m on the index-future chart, not the combined-premium chart) | 3.11 / 6.11 | PARTIAL | AUTOMATE_PKG | `strike-premium-band-backtest` — resample to 5m + build the combined-premium series the gate reads (it currently reads the index-future chart). Pairs with the entry/SL seam. |
| Hard SL above VWAP mandatory for short (unlimited breakout risk; freak candles can hit SL 4x) | 3.11 / 6.11 risk | NONE | AUTOMATE_PKG | `short-premium-span` — risk note unmodelled; automatable once the short path exists (#47). |
| Trade only from a slice of profits; Global Risk Framework (sizing, daily cap) | 3.11 / 6.11 risk | PARTIAL | KEEP_MANUAL_NEW | Global rails ARE enforced account-side (`RiskService` daily_loss_limit / max_open_paper_positions + §0B `ScalperRisk` floor); the YAML caps are DEAD keys. "Trade only from a profit slice" is a discretionary capital rule with no data source — a manual-check candidate beyond FU1 / trader discretion. |
| News overrides the data / no event against the trade | Global 2.13 | MANUAL_COVERED | COVERED_EXISTING | `ScalperManualChecks.java:26-30` key `news_clear`. |
| India VIX not abnormally spiking | Global 4.5 | MANUAL_COVERED | COVERED_EXISTING | `ScalperManualChecks.java:46-50` key `vix_normal` (spike only; the straddle's own IV gates are the `iv-per-strike` rows above). |
| Global cues not against the trade (DOW, Asia, crude, USD) | Global 4.7 | MANUAL_COVERED | COVERED_EXISTING | `ScalperManualChecks.java:51-55` key `global_cues_ok`. |

## Disposition counts

- COVERED_EXISTING: 3
- COVERED_FU1: 1
- COVERED_FU2: 0
- AUTOMATE_PKG: 13
- KEEP_MANUAL_NEW: 1
- ACCEPT_BY_DESIGN: 1
- UNCERTAIN_OWNER: 1
- **Total: 21** (matches the 21 non-FULL rows in `straddle.md`)

## AUTOMATE_PKG items by theme

- `short-premium-span` (5): short straddle unbuilt; short entry below-VWAP; short exit decay/EOD/VWAP-rebreak; both-side-IV-symmetry (short); hard-SL-above-VWAP (short). All SPAN-deferred (#47).
- `iv-per-strike` (3): LOW-IV gate for long; IV>40 stay-away / 40-40-short; (short both-side-IV symmetry also listed under short-premium-span as it is short-gated).
- `strike-premium-band-backtest` (2): real combined-premium-VWAP SL; 5-min combined-premium chart/series.
- `trade-management-targets-trailing` (2): long rollover/lower-low exit; one-leg management (drop loser, hold winner).
- `straddle-strike-offset` (1): OTM-safer-bet offset knob on `StraddleLegPicker`.
- `straddle-event-window` (1): event-long ~12:30 window overriding the midday block.
- `straddle-breakeven-sizing` (1): combined-premium breakeven + expected-move sizing.

Note: the FU1-covered combined-premium-VWAP entry trigger has its *automation* home in `strike-premium-band-backtest`
(FU1 only adds the on-card reminder; the live seam is a downstream automation).
