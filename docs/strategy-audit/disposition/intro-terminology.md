# Introduction & Terminology / Glossary (Section 1) — gap disposition

Every non-FULL row from `docs/strategy-audit/intro-terminology.md` (the audit table, lines 17–49) is
assigned exactly one disposition so no gap is left unaccounted. Source non-FULL rows = **18** (PARTIAL /
NONE / MANUAL_COVERED / UNCERTAIN; 33 table rows − 15 FULL). Section 1 is mostly definitional and largely
duplicates Section-4 thresholds, so most gaps cross-reference the §4-sourced packages. Dispositions
reference the two follow-up plans:
- **FU1** = `docs/superpowers/plans/2026-06-27-followup1-expand-manual-checks.md` (adds 9 manual checks).
- **FU2** = `docs/superpowers/plans/2026-06-27-followup2-soft-dots-to-hard-gates.md` (promotes 4 soft dots —
  indicator-alignment, futures-OI quadrant, breadth, basis — to hard gates; VIX + Dow OUT of scope there).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| VWAP — use yesterday's VWAP open→~10:30, then today's | 1.2 Chart Indicators | NONE | COVERED_FU1 | FU1 `time_of_day_vwap` manual check; prior-session VWAP overlay + 10:30 switch automation is the `vwap-distance-sizing` pkg |
| VWAP — wider candle-to-VWAP gap = stronger trend | 1.2 Chart Indicators | NONE | AUTOMATE_PKG | `vwap-distance-sizing` — VWAP is a boolean side check today; add a distance/ATR magnitude factor |
| VWMA / WMA — defence & crossover reference (no §1 period) | 1.2 Chart Indicators | PARTIAL | UNCERTAIN_OWNER | §1 is silent on the period; the 20-period is an engine default — owner confirms it matches the WMA Siva actually uses |
| Premium range (buying) — Nifty ~100–250 / BN ~250–400 | 1.2 Premium & Strike | PARTIAL | AUTOMATE_PKG | `strike-premium-band-backtest` — live StrikePicker enforces the band; the backtest premium-replay selector intentionally bypasses it (make it honour the band) |
| Time — trade after 9:45; ideal entry 9:15–10:00 | 1.2 Time Filters | PARTIAL | ACCEPT_BY_DESIGN | The 9:45 floor is hard; "ideal 9:15–10:00" is an advisory soft preference — low value to harden, trader prefers the early window |
| Time — no new entries before events / after 3:30 PM | 1.2 Time Filters | PARTIAL | AUTOMATE_PKG | `event-calendar-lockout` — the 3:30 cap is FULL; "before events" needs an economic-calendar feed (`news_clear` covers it manually) |
| Time — expiry-day Hero-Zero after 2:00 PM | 1.2 Time Filters | PARTIAL | UNCERTAIN_OWNER | Code waits to 14:30 vs §1's 14:00 (§3.7/§7 detail drives it) — owner/hero-zero dimension confirms the intended start time (config knob) |
| Time — gamma moves around 3:00 PM | 1.2 Time Filters / Greeks | NONE | ACCEPT_BY_DESIGN | A descriptive market-behaviour note, not a trigger — not meaningfully automatable; trader awareness |
| Connecting the Dots — global cues = DOW/Dollar/Asian/Crude | 1.1 | PARTIAL | COVERED_EXISTING | `global_cues_ok` (ScalperManualChecks, doc_ref 4.7) covers it; only DOW is partly wired — USD/Asia/Crude auto-feed is the `global-cues-feed` pkg |
| India VIX — directional gate + level bands (10–11/12–14/15–16/17+) + correlation | 1.2 Volatility | NONE | COVERED_FU1 | FU1 `vix_regime_bands` (bands + grid) + `vix_normal` (spike); the directional automation is `directional-vix-gate` (needs a VIX endpoint, OUT of FU2) |
| IV — 6-strike avg; 10–12 good for trend; higher on trending side | 1.2 Volatility | PARTIAL | AUTOMATE_PKG | `iv-per-strike` — only the CE-vs-PE IV gap is gated; add an absolute-ATM-IV band gate (10–12 trend-play) |
| Falling Knife — never catch a sharp fall when VIX extreme (~41) | 1.2 Volatility | NONE | AUTOMATE_PKG | `directional-vix-gate` — an extreme-VIX falling-knife stand-aside (once the VIX feed exists; OUT of FU2) |
| Basket Order Selling — onset ~VIX 17, widespread ~25 | 1.2 Volatility | NONE | AUTOMATE_PKG | `directional-vix-gate` — VIX-level bands (17/25) need the null VIX feed wired first (`vix_normal` is the nearest manual cover) |
| IV Crash — sharp IV drop post-event hurts buyers | 1.2 Volatility | NONE | COVERED_FU1 | FU1 `iv_crush_awareness` manual check; the time-series IV-slope detector automation is the `iv-per-strike` pkg (gated on richer IV history) |
| OI Spurts — ~200% OI / 300% price = strong confirmation | 1.2 OI | NONE | AUTOMATE_PKG | `oi-cross-hard-gate` — only the 50% floor is encoded; add a 200%/300% strong-confirmation escalation band |
| One Good Trade — patience, fewer trades, pullback, not parabolic | 1.1 philosophy | MANUAL_COVERED | COVERED_EXISTING | `not_parabolic` / `clean_setup` / `regime_ok` (ScalperManualChecks) — shipped 7-item checklist (a daily-trade-count cap is a separate minor enhancement) |
| Art of Averaging — add only at defended levels (ST/VWAP/VWMA), never after SL | 1.1 / 1.2 Setups | UNCERTAIN | ACCEPT_BY_DESIGN | The signal layer is single-entry (`max_positions:1`); averaging is an execution-side concern outside the signal scope — by-design, manual if managed by hand |
| Lot sizes — BN 25 / Nifty 50 / FinNifty 40 (doc marks UNCERTAIN) | 1.2 Instruments | UNCERTAIN | ACCEPT_BY_DESIGN | Sizing resolves lot size from the instrument master (current, authoritative); the doc's literals are doc-flagged stale — no signal-side gap, trader verifies at trade time |

### Disposition counts

- COVERED_EXISTING: 2
- COVERED_FU1: 3
- COVERED_FU2: 0
- AUTOMATE_PKG: 7
- KEEP_MANUAL_NEW: 0
- ACCEPT_BY_DESIGN: 4
- UNCERTAIN_OWNER: 2
- **Total non-FULL rows: 18** (matches the 18 non-FULL rows in `intro-terminology.md` lines 17–49: 33 table rows − 15 FULL)

### AUTOMATE_PKG themes (for the synthesizer)

- `vwap-distance-sizing` — candle-to-VWAP gap magnitude factor (+ prior-day VWAP overlay shared with the FU1 row)
- `strike-premium-band-backtest` — backtest premium-replay selector honours the Nifty/BN premium band
- `event-calendar-lockout` — "before events" economic-calendar no-entry gate
- `directional-vix-gate` — wire the VIX feed for the level bands, falling-knife, and basket-selling 17/25 bands (3 rows; OUT of FU2)
- `iv-per-strike` — absolute-ATM-IV trend-play band gate
- `oi-cross-hard-gate` — 200%/300% OI-spurt strong-confirmation escalation band
- `global-cues-feed` — (referenced for the §1.1 Dollar/Asian/Crude auto-feed; the row itself is COVERED_EXISTING)
