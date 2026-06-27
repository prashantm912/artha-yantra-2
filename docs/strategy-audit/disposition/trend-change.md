# Trend Change (Siva #12) — gap disposition

Dispositions every non-FULL row in `docs/strategy-audit/trend-change.md` (every PARTIAL / NONE /
MANUAL_COVERED row = a gap). Source had 31 table rows; 6 are FULL-anchored and excluded
(Trending-OI >=50% shift = FULL; RSI band = FULL(CE)/dead(PE); 2-candle confirm = FULL;
DOWN-reversal 14:30 cap = FULL-but-unreachable; structural stop = FULL; data-leads-price = FULL-implicit).
**25 gap rows below — equal to the non-FULL count.**

Disposition legend: COVERED_EXISTING (shipped 7-item ScalperManualChecks) · COVERED_FU1 (one of the 9
checks Follow-up-1 adds) · COVERED_FU2 (one of the 4 dots Follow-up-2 promotes: indicator-alignment,
futures-OI quadrant, breadth, basis) · AUTOMATE_PKG (automatable work-package) · KEEP_MANUAL_NEW
(manual-only, beyond FU1) · ACCEPT_BY_DESIGN (wontfix / soft-by-design / derived-history artifact) ·
UNCERTAIN_OWNER (owner decision / ambiguous).

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|------------|-------|--------------|-------------|---------------------|
| Identify prevailing trend (up/down/sideways) via trendlines/price-action swings first | 3.12 Setup 1 | PARTIAL | KEEP_MANUAL_NEW | 1h-Supertrend `bias60m` is gated live; explicit up/down/sideways + trendline classification is a judgement read with no detector. Manual-only beyond FU1 (FU1 has no trend-classification check). |
| Swing-structure break OR **trendline break** in the reversal direction | 3.12 Setup 2-3, Entry b.1 | PARTIAL | AUTOMATE_PKG | `trendline-break-detector` — fractal swing-break is automated; the diagonal/horizontal trendline-break alternative needs a trendline engine. |
| **Chart-indicator alignment** (Supertrend 10,2 / VWMA / PSAR all on the far side of price/VWAP) | 6.12; 3.12; 4.2 | PARTIAL | COVERED_FU2 | FU2 promotes `indicator-alignment` (PSAR+VWMA+ST+VWAP all-on-side) from a soft dot to a hard gate; `ScalperGates.indicatorAlignment` already written, FU2 wires it. |
| Confirm with **volume increase + follow-up bars** (rising-volume sequence, not just a floor) | 3.12 Entry b.4; 4.2 | PARTIAL | AUTOMATE_PKG | `rising-volume-confirm` — only an absolute per-bar floor is enforced; model the expanding-volume sequence. |
| Timing window **09:45-14:30** (live gate blocks 11:00-13:00, stricter than doc) | 3.12 Setup 6; 6.12 | PARTIAL | ACCEPT_BY_DESIGN | Documented behaviour divergence (live midday 11:00-13:00 block is intentionally stricter than the doc's continuous window); per source gap-list it is "not a gap to fill". Trader must know a midday OI-flip reversal will not auto-fire. |
| Avoid morning prints / wait for intraday trend | 3.12 Setup 6, Risk | MANUAL_COVERED | COVERED_EXISTING | `not_parabolic` + the 09:45 floor. |
| **VWAP break must carry volume** to invalidate the hold-to-VWAP line | 3.12 Exits, Stop-Loss; 6.12 | PARTIAL | AUTOMATE_PKG | `vwap-break-volume-qualified` — exit fires on any `close<vwap`; gate the exit on a volume-backed break so a no-volume wick does not exit prematurely. |
| **~10-20-pt benefit-of-doubt SL leeway**, only when OI convincingly confirms | 3.12 Stop-Loss, Risk | NONE | AUTOMATE_PKG | `oi-confirmed-sl-leeway` — automatable (pad the persisted pivot stop by 10-20pt when OI confirms) but YAML header deliberately defers to live SL-management; owner may keep deferred. |
| **Target:** ride to VWAP / exit once it moves in favour (no fixed point target) | 3.12 Exits; 6.12 | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — exit is VWAP-break or a crude 30-bar time-stop; model the ride-to-VWAP / move-in-favour management. |
| Trending-OI **crossover** marks the shift; both OI graphs climbing = strict AVOID | 3.12 Filters; 6.12 | PARTIAL | ACCEPT_BY_DESIGN | The directional sign-test (`ceDelta<0 && peDelta>0`) already implicitly rejects both-climbing; source rates this "Adequate". Soft-by-design, no separate rule needed. |
| **VIX rising into the reversal direction** supports it; flat VIX warns | 3.12 Filters; 6.12 | PARTIAL | AUTOMATE_PKG | `directional-vix-gate` — VIX direction is only a soft dot (unknown never blocks) and `MarketOiClient.macro()` passes null VIX. FU2 explicitly excludes VIX (feed-starved); wire the India VIX feed + a directional VIX gate. |
| **India VIX not abnormally spiking** (gap/whipsaw risk) | 3.12 Risk (regime) | MANUAL_COVERED | COVERED_EXISTING | `vix_normal` manual check. |
| **Index-contribution / heavyweights** must support the new direction | 3.12 Filters, Edge-cases; 6.12 | NONE | AUTOMATE_PKG | `constituent-contribution` — no per-constituent contribution feed in the scalper path; FU1 adds a `constituent_contribution` manual reminder, but the underlying analytics is an automation package. (Reminder side = FU1; data side = AUTOMATE_PKG.) |
| **Support/Resistance from seller OI** (max call OI = resistance, max put OI = support) | 3.12 Filters; 6.12 | PARTIAL | AUTOMATE_PKG | `max-oi-sr-gate` — max-CE/PE-OI S/R range is not computed as a gate; only the generic `level_respected` manual check covers it. |
| **Intraday-bearish but positionally-bullish** flip; BOTH intraday + positional OI must rotate | 3.12 Setup 5; 6.12 | NONE | COVERED_FU1 | FU1 adds `oi_intraday_positional` (intraday-vs-positional OI agreement + PCR ladder) as a manual check; the positional series itself is derivable-but-unbuilt (see `intraday-positional-oi` package for the automation). |
| **News overrides data** on gap/event/war days | 3.12 Edge-cases; 6.12 | MANUAL_COVERED | COVERED_EXISTING | `news_clear` manual check. |
| Failed-attempt (1-2-3) reversal; confirm down-reversal with 2-3 consecutive red bars >125K | 3.12 Edge-cases (Day 03) | NONE | KEEP_MANUAL_NEW | Niche multi-failed-attempt pattern; automatable but low-value, no FU1 check. Future manual-check candidate / trader discretion. |
| Trendline/structure pivot held + deep PSAR bounce cue (Day 12) | 3.12 Edge-cases | PARTIAL | KEEP_MANUAL_NEW | PSAR position is scored; the held prior-day trendline/pivot cue has no detector and is judgement-led. Manual-only beyond FU1 (the trendline engine is the `trendline-break-detector` package; the "held pivot" read stays manual). |
| Consolidation: OI added on BOTH sides after a flip = pause; genuine reversal needs hourly unwinding-volume beaten | 3.12 Edge-cases (Day 07) | NONE | AUTOMATE_PKG | `oi-both-sides-consolidation` — partly automatable (a both-sides-building / hourly-unwinding-volume detector); not in FU1/FU2. |
| Post-vertical bounce caution: don't reverse until RSI recovers toward ~40 + a level prints | 3.12 Edge-cases (Day 07) | NONE | AUTOMATE_PKG | `post-vertical-rsi-recovery` — oversold-recovery sequencing after a vertical fall; automatable, niche; not in FU1/FU2. |
| Don't chase a side when **premiums are higher on that side with no positive cues** | 3.12 Risk; 6.12 | NONE | AUTOMATE_PKG | `per-side-premium-skew` — per-side premium/IV-skew warning; automatable (IV-pair dot is a different signal); not in FU1/FU2. |
| **Strong-trend / late entry — wait for pullbacks, do NOT chase** | 3.12 Exec notes; 6.12 | MANUAL_COVERED | COVERED_EXISTING | `not_parabolic` + `clean_setup` carry the don't-chase discipline. |
| Scale expectations to regime (low-VIX expiry: 10-15pt = a big hit) | 3.12 Risk; 6.12 | MANUAL_COVERED | COVERED_EXISTING | `regime_ok` + `vix_normal` manual checks. |
| **Global risk: sizing + daily-loss cap** (max_daily_loss_pct / max_positions are dead YAML keys) | 3.12 Risk (Global §2) | PARTIAL | AUTOMATE_PKG | `daily-loss-maxpositions-wiring` — sizing is read; wire the dead `max_daily_loss_pct` / `max_positions*` YAML keys into the compiler/engine (or formally rely on the paper-runtime `daily_loss_limit`). |
| Instruments: buy CE / sell PE / buy futures (up); mirror (down) — only buy-CE shipped | 3.12 Entry b.6 / r.6; 6.12 | PARTIAL | UNCERTAIN_OWNER | Only the long-CE up-reversal leg is shipped; sell-PE, futures legs, and the whole `direction: short` PE down-reversal are unbuilt. Whether to author a PE/short trend-change YAML + futures legs is an owner scope decision. |

## AUTOMATE_PKG roll-up (themes for the synthesizer)

- `trendline-break-detector` — diagonal/horizontal trendline-break trigger (swing-break already automated).
- `rising-volume-confirm` — expanding-volume sequence on the break, not just an absolute floor.
- `vwap-break-volume-qualified` — gate the `close<vwap` exit on a volume-backed VWAP break.
- `oi-confirmed-sl-leeway` — 10-20pt SL pad applied only with convincing OI (currently live-deferred).
- `trade-management-targets-trailing` — ride-to-VWAP / move-in-favour target management beyond the 30-bar time-stop.
- `directional-vix-gate` — wire India VIX feed + a directional VIX gate (FU2 excludes VIX as feed-starved).
- `constituent-contribution` — per-constituent index-contribution feed (FU1 adds the manual reminder only).
- `max-oi-sr-gate` — max-CE/PE-OI support/resistance range as a hard gate.
- `oi-both-sides-consolidation` — both-sides-building / hourly-unwinding-volume consolidation detector.
- `post-vertical-rsi-recovery` — oversold RSI-recovery (~40) + level sequencing after a vertical fall.
- `per-side-premium-skew` — per-side premium/IV-skew "don't chase higher-premium side" warning.
- `daily-loss-maxpositions-wiring` — wire the dead `max_daily_loss_pct` / `max_positions*` YAML keys.
- `intraday-positional-oi` — positional/day-cumulative OI series alongside the intraday window (automation behind the FU1 `oi_intraday_positional` reminder).
