## Options Scalping Framework (Connect the Dots) — automation audit

**Scope.** Audits the Connect-the-Dots master confluence framework (doc §3.10 / §6.10) against its
automation: the three `scalp-connect-the-dots-*.yaml` strategies, the `ScalperConfluenceGate` seam, the
pure `ConnectTheDotsScorer` + `ScalperGates`/`ScalperConfig`/`ScalperOiProps`, and the market-data
`ConnectingDotsService` (note: the latter feeds the **OI-page matrix UI**, NOT the scalper signal scorer —
the scalper's OI/macro inputs come from `MarketOiClient`). Judged by **code presence**; the derived-history
caveat (OI/Dow/IV degrade to NEUTRAL on backtests) is noted where it changes the live-vs-backtest read but
does not downgrade an automation that is present in code.

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| Trade only after 9:45am (framework-native time gate) | 3.10 Setup 1 | FULL | `ScalperGates.java:22,33` `NO_TRADE_BEFORE=09:45`; called `ScalperConfluenceGate.java:115` | — |
| Block the 11:00–13:00 sideways window | 3.10 Filters / 6.10 | FULL | `ScalperGates.java:23-24,37-39` `MIDDAY_BLOCK 11:00-13:00` | — |
| No new fresh entry after 3:30pm (impending event) | 3.10 Filters | PARTIAL | `ScalperGates.java:25,40-41` blocks fresh entry **after 15:30** | Time-cap is encoded; the "impending event after 3:30" semantics (it is event-driven, not a blanket cap) is not modelled — confirm no event before sitting after 15:30. Automatable: false (event calendar not wired) |
| Primary scalp timeframe = 3-minute chart | 3.10 Instruments | FULL | `*.yaml` `timeframes.primary: 3m` | — |
| 60-minute aggregated bias must agree | 3.10 Setup/§6.10 | FULL | `*.yaml` `timeframes.additional:[1h]` + `bias60m` ST(7,3)@1h; `ConnectTheDotsScorer.java:111,115` `biasAligned` hard-gates | Bias=ST(7,3)@1h is a proxy for the doc's "60-min aggregated Connecting-Dots view (most factors red→bearish)" — confirm the 1h Supertrend agrees with the broad multi-factor read. Automatable: true (could aggregate the 1h dot matrix) |
| VWAP (default) — most important; decisive hard gate | 3.10 Setup 2 | FULL | `ConnectTheDotsScorer.java:32 W_VWAP=2.5`, `:71,114-115` VWAP hard gate | — |
| Supertrend (10,2) on 3-min | 3.10 Setup 2 | FULL | `*.yaml` `supertrend params {period:10, multiplier:2.0}` @3m | — |
| Supertrend intraday variant (7,3) on 15m/1h | 3.10 §6.10 | PARTIAL | `*.yaml` `bias60m` uses ST(7,3)@1h | The 7,3 setting is wired only as the 60m bias, not as a separate selectable intraday-vs-scalp mode. No 15m variant. Automatable: true |
| VWMA pinned to length 20 | 3.10 S22(g) | FULL | `*.yaml` `vwma20 params {period:20}`; scored `ConnectTheDotsScorer.java:76` | — |
| RSI 14, band 80:20 (long Buy 50–75 / no-trade 40–50; short Sell 40–25) | 3.10 Setup 2 / Entry 3 | PARTIAL | `*.yaml` `rsi14 {period:14}`; **gate band is CE 60–80 / PE 20–40, no-trade 40–60** `ScalperGates.java:76-84` | Code deliberately follows §4.2 (CE>60, no-trade 40–60), NOT the §3.10 "buy 50–75 / no-trade 40–50" text — a 50–60 CE entry the doc allows is BLOCKED. Confirm you accept the §4.2 band. Automatable: true (a doc-fidelity choice, not a data gap) |
| Parabolic SAR (0.02, 0.2) | 3.10 Setup 2 | PARTIAL | `*.yaml` `psar` (no params → engine default); scored `ConnectTheDotsScorer.java:77` | PSAR step/max are not pinned in the YAML (rely on engine defaults). Confirm engine default = 0.02/0.2. PSAR is a soft dot only, never the "first/early trend cue" the doc calls it. Automatable: true (pin params) |
| Volume candle ≥125K Nifty / ≥50K BankNifty/Sensex | 3.10 Setup 5 / Entry 2 | FULL | `ScalperGates.java:27-30,64-68` `NIFTY 125k`, `INDEX 50k`; hard rail `ScalperConfluenceGate.java:161` | Floor keys off the **signal future's index** (NIFTY-FUT-CONT→125k) for every variant, incl. the SENSEX-options ones (they signal on NIFTY). Confirm that is intended. |
| Entry: 2 GREEN/RED candles, 2nd strong, all indicators below/above price | 3.10 Entry 2/4 | PARTIAL | Two-candle formation is a separate tag (`TwoCandleGate`), NOT armed on connect-the-dots YAMLs (`tags:[scalper,options,intraday,nifty]`) | The connect-the-dots variants do NOT require the 2-candle pattern; the coarse YAML gate is `close > vwap` only. Manually confirm the 2-green/2-red structure + "2nd candle strong" before taking. Automatable: true (add the `two-candle-pattern` tag) |
| OI Spurts 4 quadrants (≥50% OI / ≥50% price) | 3.10 Setup 3 / Filters | FULL | `ConnectTheDotsScorer.java:90,159-167` `oi_spurt` dot; `ScalperOiProps.java:42-43` spurt 50/50 | Soft dot (not a hard gate). MUTED on derived history (NEUTRAL) — judge on forward paper. |
| OI build-up: strike LB/SC for longs, SB/LU for shorts | 3.10 Setup 7 / Entry 7 | FULL | `ScalperGates.java:121-125` `oiQuadrant`; `ConnectTheDotsScorer.java:80-81` futures+underlying OI dots | Soft (futures-OI is W_OI weighted); MUTED on derived history. |
| Trending OI cross-over with widening gap (5–15 min) | 3.10 Entry 8 | FULL | `ConnectTheDotsScorer.java:83,125-134` `trendingCross` (crossedThisWindow/gapWidening) | Soft dot; MUTED on derived history. |
| ≥50% Call-vs-Put OI difference to trend (directional-conviction gate) | 3.10 S22(b) | PARTIAL | `ScalperGates.java:151-161` `callPutDeltaFilter` + `ScalperOiProps.java:32` 50%, BUT armed only by `oi-cross-filter` tag — **not on connect-the-dots YAMLs** | The ≥50% imbalance gate exists but is NOT armed on this framework's variants; it is also a degrade-to-pass when null. Manually confirm the heavier side is ≥50% ahead. Automatable: true (add the tag) |
| India VIX directional rule (up+cooling=bull / up+rising=bear) | 3.10 Entry 10 / Filters | PARTIAL | Dot exists `ConnectTheDotsScorer.java:92`, `ScalperGates.java:136-143`; BUT `MarketOiClient.macro` passes `vixLevel=null, vixRising=null` (file:396-397) → dot **degrades to pass (never blocks/confirms)** | VIX is effectively a no-op in the scalper scorer (no live VIX direction wired into `Macro`). Manually read India VIX direction vs price. Also a `vix_normal` manual check (`ScalperManualChecks.java:48`, §4.5). Automatable: true (wire VIX level+direction into `MarketOiClient.macro`) |
| IV across 6 strikes — 10/10 trend, 40/40 stay away/short straddle, 30/20 bullish (≥10pt diff) | 3.10 Setup 3 / Filters | FULL | `ConnectTheDotsScorer.java:96-98,173-195` `ivPair` + `ivBothHigh standAside`; `ScalperOiProps.java:38-40` gap 0.10 / both-high 0.40 | 40/40 stand-aside is a hard suppressor (`:115`). MUTED on derived history (IV null). Confirm live IV pair on forward paper. |
| Global cues — DOW 30 futures must align | 3.10 Setup 3 / Entry / Filters | PARTIAL | Dow rides `ConnectingDotsService.dowFactor` (file:316-333, OI-page UI only); the **scalper scorer `Macro` has NO dow field** — not consulted by `ConnectTheDotsScorer` | Dow is shown on the OI matrix page but does NOT gate the scalp signal. Manually check DOW futures direction. Covered as `global_cues_ok` manual check (`ScalperManualChecks.java:51`, §4.7). Automatable: partial (Dow LTP exists via GlobalQuoteSource; would need wiring into the scalper Macro) |
| Global cues — Dollar index (>105 neg / <90 ideal), Asian, European, Crude, Bond yields, USD/INR | 3.10 Setup 3 / Filters | NONE | No field in `ScalperGateContext.Macro` (file:59-68); not in any gate/scorer | None of dollar-index / Asian / European / crude / bonds / USD-INR are automated anywhere. Manually scan global cues. Covered (coarsely) by `global_cues_ok` manual check (§4.7). Automatable: false (no live feeds for these in the platform) |
| Advance/Decline: adv>32=CE, dec>32=PE | 3.10 Entry 11/Filters | FULL | `ScalperGates.java:127-133` `breadth`; `ConnectTheDotsScorer.java:91`; data `MarketOiClient.java:619-622` | Soft dot. NEUTRAL when the breadth summary is absent (history). |
| FII-DII positioning | 3.10 (implicit global cue) | PARTIAL | `Macro.fiiLongPct` populated `MarketOiClient.java:375-383`; carried in context but **NOT scored as a dot** in `ConnectTheDotsScorer` | FII long% is fetched but not used in the confluence aggregate. Manually weigh FII-DII. Automatable: true (add an FII dot) |
| Strike selection: buy-side delta 0.6–0.7 | 3.10 Setup 6 / Entry 9 | FULL | `ScalperConfig.java:82-83` `DELTA_LO=0.6/HI=0.7`; `StrikePicker.pick(...)` `ScalperConfluenceGate.java:273` | Live `StrikePicker` only; the backtest selector ignores the band (picks nearest-strike). |
| Strike within ATM±3 + premium 100–250 N / 250–400 BN (O=H-column borrows) | 3.10 Setup 6 | FULL | `*.yaml` `strikes {selector:atm_window, width:3}`; `ScalperConfig.java:93-98` premium bands | Premium band live-only (backtest ignores). |
| AI-suggested (OSPL) strike inside the range | 3.10 Setup 6 / S21(d) | NONE | No OSPL/AI strike input anywhere | The "AI-suggested strike" cue is not automated. Manually cross-check the AI/OSPL strike if used. Automatable: false (no OSPL integration) |
| Target: not more than 1–2% (RR ~1%); next resistance/support | 3.10 Exit | NONE | YAML `exit_rules` are `signal_exit (close<vwap)` + `time_stop(10 bars)` only; no take-profit, no S/R target | No native profit target encoded (doc itself states none — exit is squeeze/time/SL based). Manually target the next S/R. Automatable: partial (a % TP could be added; S/R targeting cannot) |
| RSI profit-booking ladder: long 75–80 book 90% / 85 book 10% (short mirror 25–20 / 15) | 3.10 Exit / S21(c) | NONE | No RSI-laddered scale-out in `ExitEvaluator.java` (only stop/trailing/take_profit/time/signal) or in any YAML | The staged RSI book-out is entirely manual. Manually book 90% at RSI 75–80, last 10% at 85. Automatable: true (a partial-exit ladder is a real engine feature gap) |
| VWAP scalp exit: break VWAP **with volume** = exit; **without volume** = fake, don't chase | 3.10 Exit / Edge cases | PARTIAL | YAML `signal_exit {rule:"close < vwap"}` exits on any VWAP break | Exit fires on a bare close-below-VWAP; the volume qualifier (the fake-breakout discrimination) is NOT modelled. Manually distinguish a with-volume break (real) from a no-volume fake. Automatable: true (add volume to the exit rule) |
| Stop-loss = 1st candle low (bull) / high (bear); gap-trail 5pts below | 3.10 Exit | PARTIAL | `ScalperConfluenceGate.java:288-302` structural stop supports `TWO_CANDLE_FIRST`/`ENTRY_CANDLE`/`FIRST_CANDLE`, BUT connect-the-dots YAMLs arm NONE (no SL tag) → `StructuralStop.NONE` | The "1st candle low/high" SL is available in code but NOT armed on these variants (they carry no two-candle/entry-candle tag). The "gap-trail 5pts" is a gap-theory concern, not armed here. Manually set the structural SL. Automatable: true (add `entry-candle-stop`/`two-candle-pattern` tag) |
| Max daily loss / per-trade risk 1–2%; sizing | 3.10 Risk | FULL | `*.yaml` `risk.max_daily_loss_pct: 2.0`; `position_sizing premium_budget 15000` | Sizing is a fixed premium budget, not the doc's VWAP-distance sizing (below). |
| VWAP-distance position sizing (max qty near VWAP; wait if gap wide) | 3.10 S22(a) | NONE | Fixed `premium_budget` `*.yaml`; no VWAP-distance sizing in `risk` | Not automated. Manually size up near VWAP, wait out a wide VWAP-to-candle gap. Automatable: true (a VWAP-distance sizer is feasible) |
| Choppy-day stand-aside (>2–3 VWAP crossovers = sit out) | 3.10 (regime) | MANUAL_COVERED | `ScalperManualChecks.java:41-45` `regime_ok` (§3.10) | Manual check present; count today's VWAP crossovers. Automatable: true (crossover count is computable) |
| No market-moving news/event against the trade | 2.13 (global) | MANUAL_COVERED | `ScalperManualChecks.java:26-30` `news_clear` (§2.13) | Manual: scan news/calendar. Automatable: false |
| Price at the right S/R zone, not mid-range/into a wall | 4.11 (global) | MANUAL_COVERED | `ScalperManualChecks.java:31-35` `level_respected` (§4.11) | Manual: check pre-marked S/R. Automatable: partial |
| Entry not chasing a parabolic/vertical move | 3.1 (global) | MANUAL_COVERED | `ScalperManualChecks.java:36-40` `not_parabolic` (§3.1) | Manual: wait for pullback if vertical. Automatable: true |
| "One good trade" clean-setup discipline | 3.10 philosophy | MANUAL_COVERED | `ScalperManualChecks.java:56-60` `clean_setup` (§3.1) | Manual discipline check. Automatable: false |
| Not more than 1 night risk; avoid Friday | 3.10 Risk | NONE | Intraday square-off `*.yaml` `square_off: 15:15` (so no overnight); no explicit "1 night / avoid Friday" rule | Framework is intraday-only so night-risk is moot for these variants; not modelled. Automatable: true (BTST context, N/A here) |

### Not automated (gaps)

- **RSI profit-booking ladder (75–80→90%, 85→10%; short mirror)** — no staged partial scale-out in the
  engine or YAML; entirely manual. (PARTIAL→NONE; engine feature gap, automatable.)
- **VWAP break volume qualifier** — exit fires on any `close < vwap`; the with-volume / no-volume
  fake-breakout discrimination (§3.10 Exit) is not modelled. (PARTIAL, automatable.)
- **Structural stop-loss (1st-candle low/high) not armed** — the code path exists (`StructuralStop`) but
  the connect-the-dots variants set `StructuralStop.NONE`; SL is manual. (PARTIAL, automatable via a tag.)
- **2-candle entry structure not armed** — the 2-green/2-red + "2nd candle strong" + all-indicators-aligned
  formation is a separate `TwoCandleGate` not tagged onto these YAMLs; coarse gate is `close > vwap` only.
  (PARTIAL, automatable via the `two-candle-pattern` tag.)
- **≥50% Call-vs-Put OI imbalance gate not armed** — `callPutDeltaFilter` exists but is gated on the
  `oi-cross-filter` tag, absent here. (PARTIAL, automatable via the tag.)
- **VIX directional dot is a live no-op** — `MarketOiClient.macro` passes `vixLevel/vixRising = null`, so
  the VIX dot degrades to pass and never confirms or blocks. (PARTIAL, automatable by wiring live VIX.)
- **DOW 30 futures not consulted by the scalp scorer** — Dow is only on the OI-page matrix
  (`ConnectingDotsService`), not in the scalper `Macro`. (PARTIAL, covered by the `global_cues_ok` manual
  check; partially automatable.)
- **Dollar index / Asian / European / crude / bond yields / USD-INR** — no fields, no feeds; fully manual
  global-cue scan. (NONE, not automatable on current feeds; coarsely covered by `global_cues_ok`.)
- **FII-DII not scored** — `fiiLongPct` is fetched into context but not weighted as a confluence dot.
  (PARTIAL, automatable.)
- **AI/OSPL-suggested strike** — not integrated; the delta/premium-band `StrikePicker` is the only strike
  cue. (NONE, not automatable without OSPL.)
- **Native target (1–2% / next S/R) and VWAP-distance position sizing** — neither is encoded; doc itself
  gives no numeric target, but VWAP-distance sizing (S22) and a % TP are feasible and unbuilt. (NONE,
  partially automatable.)
- **RSI band fidelity** — the scorer/gate uses §4.2's CE 60–80 / no-trade 40–60, deliberately overriding
  §3.10's "buy 50–75 / no-trade 40–50"; a doc-fidelity decision to confirm, not a data gap.

> Derived-history caveat: every OI/IV/Dow/breadth/VIX dot above degrades to NEUTRAL on a backtest over
> derived history, so the Connect-the-Dots composite rarely reaches strong confluence on backtests. Judge
> these OI/macro automations on **forward paper with live captured OI**, not on a historical backtest.
