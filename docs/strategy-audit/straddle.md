## Straddle (Long & Short) — automation audit

**Scope.** Audits the §3.11 / §6.11 Straddle strategy against its automation: the three
`scalp-straddle-*.yaml` drafts (`scalp-straddle-nifty.yaml`, `scalp-straddle-sensex-niftyoi.yaml`,
`scalp-straddle-sensex-sensexoi.yaml`), the `straddle`-tag NEUTRAL path of `ScalperConfluenceGate`,
`StraddleLegPicker`, `ScalperGates`, and `ScalperManualChecks`. The strategy is seeded as a **DRAFT**
(opt-in, never auto-emits). Headline finding: only the LONG variant is built; the SHORT straddle is
deliberately SPAN-deferred; and the strategy's *core* triggers — combined-premium-vs-its-own-VWAP entry
and SL, the LOW-IV / both-side-IV gates, one-leg management, and the event-form time windows — are NOT
in the deterministic engine and are left to live order-layer management. The engine emits a two-leg ATM
BUY draft on generic time + volume rails only. (Derived-history caveat: OI/Dow/IV degrade to NEUTRAL on
backtests, but the straddle path never scores directional confluence anyway, so backtest behaviour is
not the lens here — code presence is.)

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| Both legs = ATM, same strike, same expiry | 3.11 / 6.11 | FULL | `StraddleLegPicker.java:52-93` (nearest-forward strike with both CE+PE); yaml `universe.options.strikes.selector: atm_window`, `option_types: [CE, PE]` | — |
| Strike: default ATM; OTM strikes allowed "for a safer bet" (one leg becomes ITM for the other side), but ATM preferred over deep OTM | 3.11 (setup #4) / 6.11 (setup_preconditions) | PARTIAL | The ATM default IS automated (`StraddleLegPicker.java:78-84` selects the strike with smallest \|strike−forward\|). The OTM-safer-bet alternative is NOT selectable — the picker only ever returns the single nearest-forward ATM strike; there is no width/offset knob to shift to OTM (yaml `atm_window, width: 3` only feeds candidates; the picker picks the nearest) | Manual: if you want the safer OTM variant (one leg ITM), pick the OTM legs yourself. Automatable: true (add an offset param to `StraddleLegPicker`). **[v2 added — v1 row only covered the ATM default, missed the OTM-alternative sub-rule]** |
| Long straddle = BUY ATM Call + BUY ATM Put | 3.11 | FULL | `StraddleLegPicker` returns both legs as BUY; gate `ScalperConfluenceGate.java:143` emits `Leg(CE)`+`Leg(PE)`; yaml `entry_rules.direction: long` | — |
| Short straddle = SELL ATM Call + ATM Put (range/decay play) | 3.11 / 6.11 | NONE | `StraddleLegPicker.java:24` only ever returns BUY legs; yaml header lines 18-21 "Short straddle … DEFERRED" | Short variant not built — SPAN-deferred (#47). To run a short straddle the trader must place/manage it entirely manually. Automatable: true (once #47 SPAN appliance gates short premium). |
| Entry trigger: combined straddle premium breaks ABOVE its own VWAP **with volume** (long) | 3.11 / 4.15.2 / 6.11 | NONE | NOT in gate — `ScalperConfluenceGate.java:128-131` states the combined-premium-vs-VWAP series "the deterministic seam cannot recompute … NOT enforced". Engine emits draft on time+volume only | Manual: on the combined Call+Put premium chart, enter only when it closes above its own VWAP on volume. Automatable: true (combined-premium straddle chart already exists FE-side, task #6) — but not in the deterministic gate. |
| Short entry trigger: after 9:30 AM, price FALLS BELOW the VWAP of both Call and Put | 3.11 / 6.11 | NONE | No short path exists; engine time window is the generic ≥09:45 (`ScalperGates.java:33-43`), not 09:30 | Manual: short straddle entered only when combined premium is below both legs' VWAP after 09:30. Automatable: true. |
| Event/budget long form: after ~12:30 PM, price CLOSES ABOVE the VWAP of both legs | 3.11 / 6.11 | NONE | Not encoded. Worse, the engine time gate BLOCKS the 11:00–13:00 midday window (`ScalperGates.java:32`), which *conflicts* with the ~12:30 PM event entry | Manual: event long straddle entered ~12:30 PM on the both-leg VWAP close — and the trader must override the engine's midday block. Automatable: true (needs an event-aware window). |
| Long SL = BELOW the (combined) VWAP; Short SL = ABOVE the VWAP | 3.11 / 6.11 | NONE | Engine SL is `stop_loss basis: premium_pct value: 50` (yaml line 84) — a 50% premium proxy, explicitly "[ASSUMED] v1 bounding stop (combined-premium SL is LIVE)" | Manual: set/trail the real SL on the combined-premium VWAP (long: below; short: above). Automatable: true. |
| Long exit: lower-low candle / combined premium peaks and rolls over | 3.11 / 6.11 | PARTIAL | Only a generic `time_stop max_bars: 30` (~90 min, yaml line 85) + 15:15 square-off; yaml line 86 "the faithful exit … is LIVE-managed" | Manual: exit when the combined premium rolls over from its peak / lower-low forms; book, don't wait for full reversal. Automatable: true (rollover/lower-low on the combined series is computable). |
| Short exit: considerable premium decay / EOD / immediately if price breaks back through VWAP | 3.11 / 6.11 | NONE | No short path; no VWAP-break exit in the gate | Manual: exit short on decay, at EOD, or instantly on a VWAP re-break. Automatable: true. |
| One-leg management: once combo clears VWAP and only one leg gains, drop the losing leg, hold the winner | 3.11 / 6.11 | NONE | Not modelled — gate emits a static two-leg draft; no leg-drop logic. yaml line 31/40 "one-leg management … LIVE management" | Manual: after the combo clears VWAP, exit the losing leg and ride the winner. Automatable: true (per-leg P&L is available live). |
| Pick LONG vs SHORT from volatility view (event+LOW IV → long; range+similar IV → short) | 3.11 / 6.11 | NONE | No variant selection — only the long draft exists | Manual: decide long vs short from the IV / event / range view before deploying. Automatable: partly (IV feed exists; "event" is judgmental). |
| LOW-IV gate for the long straddle (a high-IV long loses both legs on an IV crash) | 3.11 / 6.11 (filters) | NONE | Not gated. yaml line 30 "the LOW-IV gate … left to LIVE management"; per-strike IV feeds exist (`ConnectingDotsService.java:37,71` activeStrikeIv) but the neutral path does not read them | Manual: confirm IV/premiums are low before buying the straddle. Automatable: true (per-strike IV + VIX feeds exist; not wired to the neutral gate). |
| Short wants Call-side & Put-side IV similar/equal | 3.11 / 6.11 (filters) | NONE | No short path; no CE-vs-PE IV-symmetry check anywhere | Manual: confirm both-side IV is similar before selling. Automatable: true (per-leg IV available). |
| IV > 40 → stay away as a buyer; a 40/40 reading → play short straddle | 3.11 / 6.11 (filters, §4.6) | NONE | No IV-threshold gate in the straddle path | Manual: skip the long buy when IV > 40; treat a ~40/40 reading as the short-straddle condition. Automatable: true (IV feed exists). |
| Breakeven: underlying must move > combined premium from the strike (don't pay ~1000 for a 100–200-pt move) | 3.11 / 6.11 (setup/risk) | NONE | Not computed/gated; sizing is a flat `premium_budget budget_inr: 15000` (yaml line 89) | Manual: compute the combined-premium breakeven and confirm the expected move exceeds it. Automatable: true (both leg premiums are known at entry). |
| Trending-OI confirmation: change-in-OI moving together = range = short; divergence/break = long/directional | 3.11 / 6.11 (filters) | NONE | The neutral path uses `neutralConfluence()` (`ScalperConfluenceGate.java:144,328`) and skips the OI confluence; yaml `oi_confluence_gate.enabled: false` | Manual: read Trending-OI both-side together (short) vs divergence (long) before deploying. Automatable: partial (OI factors exist but degrade to NEUTRAL on derived history; live OI real). |
| Volume floor on entry | 3.11 (entry "with volume") / §0B | FULL | `ScalperConfluenceGate.java:132-135` (the straddle path) calls `ScalperGates.volume(cfg.signalIndex(), chart.volume())` before picking legs and BLOCKS on fail. The hard floor is keyed on the **signal future's index** = NIFTY 50 for all three variants (signal_underlying `NIFTY-FUT-CONT`) → **125,000** (`ScalperGates.java:28,30,64-68`); the yaml `volume > 0` expr is a soft pass-through, not the enforced floor | — (a coarse floor, not the doc's "VWAP break with volume" — that specific conjunction is the entry-trigger gap above) |
| 5-minute straddle-chart timeframe | 3.11 / 6.11 | PARTIAL | Engine primary is `3m` (yaml `timeframes.primary: 3m`), backtest `interval: 1m` — neither is the doc's 5-min straddle chart; and the gate reads the index-future chart, not the combined-premium chart | Manual: read entries on the 5-min combined-premium chart. Automatable: true (resampling + combined series). |
| Hard SL above VWAP mandatory for short (unlimited breakout risk; freak candles can hit SL 4×) | 3.11 / 6.11 (risk) | NONE | No short path; risk note unmodelled | Manual: if running a short straddle, a hard SL above VWAP is mandatory; beware freak-candle multi-hits. Automatable: true (once short path exists). |
| Trade only from a slice of profits; Global Risk Framework (sizing, daily cap) | 3.11 / 6.11 (risk) | PARTIAL | Global rails are enforced ACCOUNT-side by `RiskService.java:27,60-69` (`daily_loss_limit`, off by default) + `RiskService.java:26` (`max_open_paper_positions`) and the §0B hard-stop floor `ScalperRisk.java:21-24`. The YAML `risk.max_daily_loss_pct: 2.0` + `max_positions: 1` (yaml lines 90-92) are **DEAD keys** — neither is read by `StrategyCompiler` or `strategy-engine` (no match in `libs/strategy-engine`). "Trade only from profits" is not modelled | Manual: deploy only from a profit slice; the daily cap must be set in the paper-account RiskService, not the YAML. Automatable: false (a discretionary capital rule). **[v2 fix: was false-coverage — cited the dead YAML keys as enforcing the cap]** |
| News overrides the data / no event against the trade | (Global §2.13) | MANUAL_COVERED | `ScalperManualChecks.java:26-30` key `news_clear` | Checklist item — owner ticks before Take. |
| India VIX not abnormally spiking | (Global §4.5) | MANUAL_COVERED | `ScalperManualChecks.java:46-50` key `vix_normal` | Checklist item. (Distinct from the straddle's own IV gates above, which are NOT covered.) |
| Global cues not against the trade (DOW, Asia, crude, USD) | (Global §4.7) | MANUAL_COVERED | `ScalperManualChecks.java:51-55` key `global_cues_ok` | Checklist item. |

### Not automated (gaps)
- **Short straddle entirely unbuilt** — `StraddleLegPicker` only ever returns BUY legs; the SELL-both-legs range/decay play (its entry, exit, VWAP-break exit, and mandatory hard SL-above-VWAP) is SPAN-deferred (#47). 100% manual today.
- **The core entry trigger is not enforced** — combined-premium-breaks-above-its-own-VWAP-with-volume (long) / falls-below-VWAP (short). The engine emits the two-leg draft on a generic ≥09:45 time window + a volume floor only.
- **The real SL is not set** — doc SL = combined-premium VWAP (long below / short above); engine uses a 50%-premium proxy + a 30-bar time-stop, explicitly an [ASSUMED] bounding stop.
- **The real exits are not modelled** — combined-premium roll-over / lower-low (long), decay / VWAP-re-break (short), and one-leg management (drop loser, hold winner) all ride live management.
- **No volatility gating** — LOW-IV-for-long, both-side-IV-similar-for-short, IV>40-stay-away / 40-40-go-short, and the long-vs-short variant decision are not in the gate, though per-strike IV and VIX feeds exist (`ConnectingDotsService`).
- **No breakeven / expected-move sizing** — the "underlying must move > combined premium" check (don't pay ~1000 for a 100-200-pt move) is not computed; sizing is a flat premium budget.
- **Trending-OI variant confirmation** (together = short / divergence = long) is skipped — the neutral path uses a NEUTRAL stand-in confluence and disables the OI gate.
- **Timeframe / chart mismatch** — engine runs 3m on the index-future chart; the doc's tool is the 5-min combined-premium straddle chart. The event-long ~12:30 PM entry additionally collides with the engine's 11:00-13:00 midday block.
- **VIX/global-cue/news checks ARE covered** by `ScalperManualChecks`, but those are the *global* gates; the straddle's *own* IV gates are not.

### v2 review notes

Independent second-pass review (fresh-derived from doc §3.11 / §6.11, then diffed vs v1). **v1 was high quality**
— every gap verdict (short straddle unbuilt, combined-premium-VWAP entry/SL not enforced, no IV/OI gating,
one-leg management live-managed) traced to the live code; the MANUAL_COVERED rows (`news_clear` 2.13,
`vix_normal` 4.5, `global_cues_ok` 4.7) match `ScalperManualChecks.java:26-30,46-55` exactly; and the
NEUTRAL-path claim (time + volume only, no CE/PE split, no OI confluence) is confirmed at
`ScalperConfluenceGate.java:132-146` (early return before the `client.context` OI fetch at line 191).

Changes made:
1. **MISSED (added row):** *Strike — OTM "safer bet" alternative, ATM preferred over deep OTM* (§3.11 setup #4 /
   §6.11). v1's strike row covered only the ATM default; the doc also offers an OTM variant. The picker
   (`StraddleLegPicker.java:78-84`) only ever returns the nearest-forward ATM strike with no offset knob →
   PARTIAL (ATM default automated, OTM alternative not selectable).
2. **INACCURATE → corrected (false-coverage):** the *"Global Risk Framework (daily cap)"* row cited the YAML
   `max_daily_loss_pct: 2.0` + `max_positions: 1` as encoding the caps. Those are **DEAD YAML keys** — neither is
   read by `StrategyCompiler` or `strategy-engine` (no match in `libs/strategy-engine`; corroborated by the
   README §4 "DEAD YAML key never read" flag). The actual global rails are ACCOUNT-side `RiskService`
   (`daily_loss_limit` off-by-default `:27,60-69`; `max_open_paper_positions` `:26`) + the §0B hard-stop floor
   (`ScalperRisk.java:21-24`). Status stays PARTIAL; evidence corrected to point at the real enforcer.
3. **Evidence sharpened (volume floor, still FULL):** named the actual enforced floor — `ScalperGates.volume`
   keys off `cfg.signalIndex()` = NIFTY 50 (signal_underlying `NIFTY-FUT-CONT`) → **125,000**
   (`ScalperGates.java:28,30,64-68`), not the soft yaml `volume > 0` expr. No status change.

No row was deleted. All other v1 rows confirmed accurate as written. README §5 raised no false-coverage flag
against this dimension; its one straddle parking item (combined-premium VWAP entry + LOW IV + combined-VWAP SL,
LIVE-deferred) is correctly reflected by v1 rows for the entry-trigger / SL / LOW-IV gaps.
