# Disposition — Connect the Dots (Options Scalping Framework)

Every non-FULL row from `docs/strategy-audit/connect-the-dots.md` (24 gaps: PARTIAL / NONE /
MANUAL_COVERED) is assigned exactly one disposition so no gap is left unaccounted. Cross-referenced
against the two follow-up plans:
- **FU1** (`2026-06-27-followup1-expand-manual-checks.md`) adds 9 manual checks: `fii_ls_ratio`
  (§4.17.4), `constituent_contribution`, `pre_open_bias`, `sensex_participation`,
  `oi_intraday_positional`, `iv_crush_awareness`, `straddle_vwap_entry`, `time_of_day_vwap`,
  `vix_regime_bands` (§4.14.1 absolute bands + VIX/price grid).
- **FU2** (`2026-06-27-followup2-soft-dots-to-hard-gates.md`) promotes 4 soft dots to hard gates:
  indicator-alignment, futures-OI quadrant, ≥50% ΔOI imbalance (the `oi-cross-filter` ≥50% Call-vs-Put
  gate), breadth. **VIX + Dow are OUT of FU2 scope.**

| Gap (rule) | Doc § | Audit status | Disposition | Work-package / note |
|---|---|---|---|---|
| No new fresh entry after 3:30pm (impending event, not blanket cap) | 3.10 Filters | PARTIAL | KEEP_MANUAL_NEW | Time-cap is encoded; the "impending event" semantics need an event calendar (not wired). Manual-only: confirm no event before sitting after 15:30. Beyond FU1. |
| Supertrend intraday variant (7,3) on 15m/1h (separate selectable mode + 15m) | 3.10 §6.10 | PARTIAL | AUTOMATE_PKG | `multi-timeframe-supertrend` — ST(7,3) wired only as the 60m bias; add a selectable 15m/intraday ST variant. |
| RSI 14 band fidelity (§3.10 buy 50–75 vs code's §4.2 CE 60–80) | 3.10 Setup 2 / Entry 3 | PARTIAL | UNCERTAIN_OWNER | Code deliberately follows §4.2 (CE>60, no-trade 40–60), blocking a 50–60 CE the §3.10 text allows. Doc-fidelity choice — owner to confirm which band stands. |
| RSI multi-timeframe cross-check: RSI(5m)<75/80 & RSI(Daily)<75 for longs (mirror shorts) | 3.10 Filters / 6.10 | NONE | AUTOMATE_PKG | `multi-timeframe-rsi` — add 5m + Daily RSI dots/gate; scorer reads RSI only on the 3m primary today. |
| Parabolic SAR (0.02, 0.2) params not pinned in YAML | 3.10 Setup 2 | PARTIAL | AUTOMATE_PKG | `indicator-param-pinning` — pin PSAR step/max in the YAML rather than rely on engine defaults (also covers other unpinned params). |
| Entry: 2 GREEN/RED candles, 2nd strong, all indicators aligned | 3.10 Entry 2/4 | PARTIAL | AUTOMATE_PKG | `two-candle-pattern-arming` — the `two-candle-pattern` tag exists but is not armed on connect-the-dots YAMLs; arm it (own follow-up to FU2's tag-promotion pattern). |
| ≥50% Call-vs-Put OI difference (directional-conviction gate) | 3.10 S22(b) | PARTIAL | COVERED_FU2 | The ≥50% imbalance gate (`callPutDeltaFilter` / `oi-cross-filter`) is exactly one of FU2's four soft-dot→hard-gate promotions. |
| India VIX directional rule (up+cooling=bull / up+rising=bear) | 3.10 Entry 10 / Filters | PARTIAL | AUTOMATE_PKG | `directional-vix-gate` — wire live VIX level+direction into `MarketOiClient.macro` (today null,null → dot degrades to pass). VIX is OUT of FU2 scope; the absolute-band manual read is separately carried by FU1 `vix_regime_bands`, but the DIRECTIONAL automation is its own package. |
| Global cues — DOW 30 futures must align | 3.10 Setup 3 / Entry / Filters | PARTIAL | AUTOMATE_PKG | `global-cues-feed` — Dow LTP exists via `GlobalQuoteSource` but the scalper `Macro` has no Dow field; wire Dow into the scorer. (Coarsely reminded by the shipped `global_cues_ok` manual check; Dow is OUT of FU2 scope.) |
| Global cues — Dollar index / Asian / European / Crude / Bond yields / USD-INR | doc-line 406 (§3.1), ref by §3.10 | NONE | KEEP_MANUAL_NEW | No live feeds for these on the platform → not automatable now. Coarsely covered by the shipped `global_cues_ok` manual check; remains a manual global-cue scan. |
| FII-DII positioning | 3.10 (implicit global cue) | PARTIAL | AUTOMATE_PKG | `fii-dii-bias` — `fiiLongPct` is fetched into `Macro` but scored by no dot; add an FII confluence dot (FU1 adds the `fii_ls_ratio` manual reminder; the dot automation is its own package). |
| AI-suggested (OSPL) strike inside the range | 3.10 Setup 6 / S21(d) | NONE | KEEP_MANUAL_NEW | No OSPL/AI strike integration on the platform; manual cross-check if used. Beyond FU1. |
| Target: not more than 1–2% (RR ~1%); next resistance/support | 3.10 Exit | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — a % take-profit is feasible (S/R targeting is not); add the partial-TP. Doc itself gives no numeric target. |
| RSI profit-booking ladder (long 75–80 book 90% / 85 book 10%; short mirror) | 3.10 Exit / S21(c) | NONE | AUTOMATE_PKG | `trade-management-targets-trailing` — staged partial scale-out is a real engine feature gap (no RSI-laddered exit in `ExitEvaluator`). |
| VWAP scalp exit: break with volume = exit; without = fake, don't chase | 3.10 Exit / Edge cases | PARTIAL | AUTOMATE_PKG | `trade-management-targets-trailing` — exit fires on a bare `close < vwap`; add the volume qualifier to discriminate fake breakouts. |
| Stop-loss = 1st candle low (bull) / high (bear); gap-trail 5pts | 3.10 Exit | PARTIAL | AUTOMATE_PKG | `structural-stop-arming` — `StructuralStop` code path exists but connect-the-dots YAMLs set `NONE`; arm `entry-candle-stop`/`two-candle-pattern` so the 1st-candle SL applies. |
| Support-trade / pullback entry: enter at ST level on a pullback; take pullbacks near VWMA/ST/VWAP (don't chase) | 3.10 Exit-Scaling / Edge cases | NONE | AUTOMATE_PKG | `pullback-entry-trigger` — the defining "wait for a retrace to ST/VWAP/VWMA" discipline is not modelled (seam fires on any `close > vwap`); a pullback-proximity entry condition is feasible. |
| VWAP-distance position sizing (max qty near VWAP; wait if gap wide) | 3.10 S22(a) | NONE | AUTOMATE_PKG | `vwap-distance-sizing` — fixed `premium_budget` today; a VWAP-distance sizer is feasible and unbuilt. |
| Choppy-day stand-aside (>2–3 VWAP crossovers = sit out) | 3.10 (regime) | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `regime_ok` (`ScalperManualChecks.java:41-45`, §3.10). (Crossover count is also automatable → optional future `regime-crossover-count` package, but already manual-covered.) |
| No market-moving news/event against the trade | 2.13 (global) | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `news_clear` (`ScalperManualChecks.java:26-30`, §2.13). Not automatable. |
| Price at the right S/R zone, not mid-range/into a wall | 4.11 (global) | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `level_respected` (`ScalperManualChecks.java:31-35`, §4.11). |
| Entry not chasing a parabolic/vertical move | 3.1 (global) | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `not_parabolic` (`ScalperManualChecks.java:36-40`, §3.1). |
| "One good trade" clean-setup discipline | 3.10 philosophy (filed §3.1) | MANUAL_COVERED | COVERED_EXISTING | Carried by the shipped `clean_setup` (`ScalperManualChecks.java:56-60`, §3.1). Pure discipline, not automatable. |
| Not more than 1 night risk; avoid Friday | 3.10 Risk | NONE | ACCEPT_BY_DESIGN | Framework is intraday-only (`square_off: 15:15`), so night-risk is moot for these variants — wontfix (BTST/overnight context is N/A here). |

## Counts (24 non-FULL rows, all accounted for)

- COVERED_EXISTING: 5
- COVERED_FU1: 0
- COVERED_FU2: 1
- AUTOMATE_PKG: 13
- KEEP_MANUAL_NEW: 3
- ACCEPT_BY_DESIGN: 1
- UNCERTAIN_OWNER: 1
- **Total: 24**

### AUTOMATE_PKG themes (13)

- `multi-timeframe-supertrend` — selectable 15m/intraday ST(7,3) variant
- `multi-timeframe-rsi` — 5m + Daily RSI cross-check dots/gate
- `indicator-param-pinning` — pin PSAR (0.02/0.2) and other unpinned params in YAML
- `two-candle-pattern-arming` — arm the `two-candle-pattern` tag on these variants
- `directional-vix-gate` — wire live VIX level+direction into `MarketOiClient.macro`
- `global-cues-feed` — wire Dow into the scalper `Macro`/scorer
- `fii-dii-bias` — add an FII confluence dot (consume the dead-wired `fiiLongPct`)
- `trade-management-targets-trailing` — % take-profit + RSI partial-exit ladder + VWAP-break volume qualifier (3 rows)
- `structural-stop-arming` — arm `entry-candle-stop`/`two-candle-pattern` for the 1st-candle SL
- `pullback-entry-trigger` — pullback-proximity (retrace-to-ST/VWAP/VWMA) entry condition
- `vwap-distance-sizing` — VWAP-distance position sizer
