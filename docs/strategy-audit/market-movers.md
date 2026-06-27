## Market Movers (Siva #3) — automation audit

**Scope:** Audits the Market Movers strategy (doc §3.3 narrative, §6.3 machine-readable) against its
automation — the three `scalp-market-movers-*.yaml` files (`-nifty`, `-sensex-niftyoi`,
`-sensex-sensexoi`) and the shared scalper engine
(`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/scalper/`,
`libs/strategy-engine/`). The YAML itself is explicit (its own header, lines 9–46) that v1 ports only
the deterministic *trend-continuation core* on a NIFTY-50 front-future surrogate and **defers the
entire Market-Movers screener** — the F&O-equity universe, the 8/9-day breakout filter, the per-stock
OH/OL flag, the per-stock OI interpretation, the RSI(Daily) screen, and the short side. The audit
confirms that gap against the doc rules and judges automatability against the existing feeds (note:
the per-strategy doc instrument is *stock futures / cash, "no stock options"*, yet all three YAMLs buy
a NIFTY/SENSEX index CE — a fidelity gap in itself, since the engine has no equity-option/equity-future
execution path). Derived-history caveat applies to the OI/breadth/VIX dots (NEUTRAL on backtests), but
status below is judged by code presence, not backtest behaviour.

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|------|-------|--------|----------------------------------|--------------------|
| Universe = day-leading **F&O equity stocks** (not the index), via OI-Pulse Market Movers screener; instruments = **stock futures / cash, "no stock options"** | §3.3 Instruments; §6.3 `instruments` | NONE | YAMLs trade index options: `universe.underlying` = `NIFTY 50` / `SENSEX`, `option_types: [CE]` (`scalp-market-movers-nifty.yaml:57-64`); `ScalperConfig` has no equity/screener mode | The whole equity-universe screener is absent; manually run the OI-Pulse Market Movers screen (All F&O / N50 / N-Bank) to pick the actual mover. Automatable=partly: `FuturesMoversService` already computes gainers/losers + OI-interp + day OHLC, but its javadoc states "only index futures … captured; a bank-**stock** futures grid needs a capture expansion" — needs an equity-futures capture first. |
| Stock must be at **minimum 8-day high (long) / 8-day low (short); 9-day even better** (Min. B.O. Days) | §3.3 Entry Bull 2 / Bear 2; §6.3 `entry_conditions` | NONE | No breakout-days computation anywhere in the scalper engine or `FuturesMoversService`; YAML gate is only `close > vwap` AND `close > vwma20` (`scalp-market-movers-nifty.yaml:81-83`) | Manually confirm the candidate prints an 8D/9D high (long) or low (short) on the screener before entry. Automatable=true: an N-day rolling high/low is trivial **once** per-stock candles exist (they don't today). |
| **OH/OL flag** — require **OL (Open=Low)** for longs / **OH (Open=High)** for shorts | §3.3 Entry Bull 3(b) / Bear 3(b); §6.3 `filters` | NONE | No per-stock Open=Low/High mark fed to the gate; the `OpenHighLow`/`OpenHighLowGate` primitives exist but are armed only by the `open-high-low` tag, which this YAML does **not** carry (`tags: […, entry-candle-stop]`, `scalp-market-movers-nifty.yaml:54`) | Manually read the OL/OH flag column on the screener for the chosen stock. Automatable=true (the OH/OL primitive already exists; it just needs per-stock OHLC and to be wired to this strategy). |
| **OI interpretation** — Long Build-up (best) or Short Covering for longs; Short Build-up (best) or Long Unwinding for shorts; "all 3 required" | §3.3 Entry Bull 3(c) / Bear 3(c); §6.3 `filters` | NONE | The shared confluence reads **index** OI quadrants (`ScalperGates.oiQuadrant`, `ScalperGates.java:121`) on the option-root index, **not the picked stock's** OI; no per-stock LB/SC/SB/LU feed | Manually read the per-stock OI Interpretation column (Futures OI Analysis table) for the candidate. Automatable=true given an equity-futures OI capture (`OiInterpretation.classify` already exists, used by `FuturesMoversService:85`). |
| **Trade only after 9:45am** (Market Movers matrix floor) | §3.3 Entry Bull 1 / Bear 1; §6.3 `filters` | FULL | `risk.session.window.from: "09:45"` (`scalp-market-movers-nifty.yaml:98`) + the §0B hard `ScalperGates.timeWindow` floor `NO_TRADE_BEFORE = 09:45` (`ScalperGates.java:22,34`) | — (note: the shared gate also blocks 11:00–13:00 and after 15:30, which the doc does not state for Market Movers; not a gap, just stricter) |
| Daily-RSI screen — **not already past RSI 75 (bull) nor below RSI 40 (bear)** on the Daily timeframe | §3.3 Setup 6 / Filters (RSI bands); §6.3 `filters`, `setup_preconditions` | NONE | Only a **3m** RSI is wired (`indicators … rsi14 … timeframe: 3m`, `scalp-market-movers-nifty.yaml:72`); no Daily RSI indicator declared; `ScalperGates.rsiBand` reads the 3m value | Manually check the candidate's **Daily** RSI is < 75 (long) / > 40 (bear) before entry. Automatable=true: add an RSI on a daily timeframe per stock (data-dependent). |
| Intraday **RSI(5m) below 75/80 (long) / above 25/20 (short)**; examples cite RSI > 60 | §3.3 Entry Bull 4 / Bear 4; §6.3 `entry_conditions` | PARTIAL | A 3m (not 5m) RSI band gate runs via the shared seam: CE wants 60–80, PE wants 20–40 (`ScalperGates.rsiBand`, `ScalperGates.java:76-84`) — but on the **NIFTY future**, not the stock | Confirm the **stock's** intraday RSI sits in band; the automated band is on the index surrogate, on 3m not 5m. Automatable=true once per-stock series exist. |
| Entry trigger: **long after price moves above VWAP**, on a pullback near VWMA/SuperTrend/VWAP | §3.3 Entry Bull 5; §6.3 `entry_conditions` | PARTIAL | Encoded on the index surrogate: gate `close > vwap` AND `close > vwma20` (`scalp-market-movers-nifty.yaml:82-83`); ST/VWMA/PSAR/VWAP also weighted in `ConnectTheDotsScorer`/`ScalperGates.indicatorAlignment` | The VWAP-reclaim trigger is automated but on the **NIFTY future, not the actual mover stock**; manually confirm the stock itself is above its own VWAP. Automatable=true once per-stock series exist. |
| Indicator settings: **VWAP, VWMA 20, SuperTrend (10,2), RSI 14, Volume 20** | §3.3 Filters (Indicators); §6.3 `indicators` | PARTIAL | VWMA 20, SuperTrend (10,2), RSI 14 present (`scalp-market-movers-nifty.yaml:70-75`); VWAP is the engine builtin; **Volume 20 (a 20-period vol MA) is NOT declared** — only a static §0B volume **floor** is gated (`ScalperGates.volume`, 125k NIFTY / 50k other) | The settings exist but apply to the index future. No Volume-20 MA indicator; the volume rule is a fixed candle floor, not the doc's Volume-20. Automatable=true (declare a `Volume` MA indicator). |
| Desirables: **ST & VWMA crossover with SAR (PSAR) switching**; S/R-line breakout confirming direction; **IV rising (bull) / falling (bear)** on the relevant strike | §3.3 Filters (Indicators/Desirables); §6.3 `filters`, `indicators` | PARTIAL | PSAR + SuperTrend + VWMA are computed and scored on the index future (`scalp-market-movers-nifty.yaml:71,74`; `ConnectTheDotsScorer`); **IV** is read at index level (`Macro.atmIv`/`ceIvAvg6`, `ScalperGateContext.java:59-68`) but not as a per-stock-strike rising/falling gate; S/R-line breakout is not automated | These desirables are soft index-level dots only; manually confirm the ST/VWMA/SAR cross, the S/R breakout, and IV direction on the **stock's** strike. Automatable=partly (IV per stock-strike needs equity-option IV; S/R lines are discretionary). |
| **Prefer high-volume / liquid stocks** for clean entry/exit | §3.3 Setup 4 / Filters (Volume) | PARTIAL | A static volume **floor** is gated (`ScalperGates.volume`, `ScalperGates.java:64-68`) on the index future; no per-stock liquidity ranking | Manually pick a liquid, high-volume name. Automatable=true once equity volumes are captured (rank by ADV). |
| **Top-constituent direction / index weightage** read (e.g. HDFC Bank 29.46% of Nifty Bank) as a directional cue | §3.3 S21 update (c); §4.14 ref | NONE | No constituent-weight or top-mover-direction input in the scalper engine | Manually read direction from the top index constituents. Automatable=partly (static weight table + per-stock moves; data-dependent). |
| Breadth / advance-decline as a directional confirmation | §3.3 (implicit in screener cues); shared §0B | FULL (index level) | `ScalperGates.breadth` (adv>32 → CE, dec>32 → PE; `ScalperGates.java:128-133`) fed from `Macro.advances/declines` (`BreadthService`) and weighted in the confluence | — (present generically; the doc does not state a Market-Movers-specific advance-decline threshold — UNCERTAIN whether it even belongs here) |
| **Target = 1–2%** ("aim not more than 1–2%"); ~1% in first morning hour | §3.3 Exit (Target); §6.3 `exit_conditions.target` | NONE | No percent-target encoded; exits are `signal_exit: close < vwap` + `time_stop: max_bars 20` (`scalp-market-movers-nifty.yaml:88-89`); YAML header line 21 calls the 1–2% target "a live-management note, not engine-carried" | Manually book ~1–2% on the stock. Automatable=true (a percent-target exit rule is a standard primitive). |
| **Stop-loss = no rigid SL / no fixed OI% threshold**; practical reference = 1st-candle low (long) / 1st-candle high (short) | §3.3 Exit (Stop-loss) + Risk; §6.3 `exit_conditions.stop_loss` | PARTIAL | The `entry-candle-stop` tag anchors the stop on the entry (breakout) candle's low (`ScalperConfig.java:149-150`, `ScalperConfluenceGate.java:293-295`) — the doc's "1st candle low" reference; but it is on the **NIFTY future**, not the stock, and "no rigid SL / risk-appetite sizing" is inherently manual | Set SL by own risk appetite on the stock; the automated structural stop is on the index surrogate. Automatable=partly (the structural anchor exists; discretionary sizing cannot be). |
| Time / hold: **intraday by default**; can be positional but **watch EOD OI** (carry only if closing OI = Long Build-up; avoid through Long Unwinding) | §3.3 Exit (Time/Positional) + Risk; §6.3 `exit_conditions.time_exit`, `risk_management` | PARTIAL | Intraday window + `square_off: "15:15"` (`scalp-market-movers-nifty.yaml:97-100`) encode the intraday default; the **overnight-carry-on-LB** logic is **not** automated (YAML header line 43 lists "the EOD-OI Long-Build-up overnight-carry option" as DEFERRED) | If carrying overnight, manually verify the close shows Long Build-up. Automatable=true given EOD per-stock OI. |
| **Short side** (8/9-day low + OH + Short Build-up / Long Unwinding) | §3.3 Entry — Bearish; §6.3 `entry_conditions.bearish` | NONE | `entry_rules.direction: long`, `option_types: [CE]` only (`scalp-market-movers-nifty.yaml:64,80`); YAML header lines 30-32 mark the SHORT mirror "faithful but DEFERRED" | The entire bearish path is unautomated; trade shorts manually (or via a future PE/short variant). Automatable=true (mirror of the long side). |
| Edge case: **if the trade goes against you, check volume — high volume = exit, low volume = may pursue** | §3.3 Execution/Edge; §6.3 `edge_cases` | NONE | No volume-conditional exit logic; only `close < vwap` and a 20-bar time stop | Manual on adverse moves: high volume → cut, low volume → may hold. Automatable=partly (volume on the stock is needed). |
| Edge case: **avoid names with OI heavily populated on both call and put sides** (ambiguous) | §3.3 Execution/Edge; §6.3 `edge_cases` | NONE | No CE/PE both-sides-loaded ambiguity check per stock | Manually skip ambiguous-positioning names. Automatable=true given per-stock chain OI. |
| **Alternative entry trigger** — "considerable change in OI **and more than 1% change in price**" (or intraday S/R trades) | §3.3 Entry Bull 5 / Bear 5; §6.3 `entry_conditions` ("Alternative: considerable OI change + >1% price change") | NONE | The only entry gate is `close > vwap` AND `close > vwma20` (`scalp-market-movers-nifty.yaml:81-83`); no >1% price-move threshold and no per-bar ΔOI trigger is wired into the gate. The shared OI dots are soft confluence (`ConnectTheDotsScorer.java:80-90`), not a ">1% price + drastic-OI" entry condition | Manually require a >1% intraday price move with a clear OI shift (or an S/R trade) before entry. Automatable=partly (a >1% price-change gate is trivial; the ΔOI leg needs per-stock OI). **MISSED by v1.** |
| **Radar-building progression** — add at a 1–2-day high/low, confirm momentum at a 3–4-day high with OL (bull)/OH (bear), full conviction at the 8–9-day breakout | §3.3 Setup 7; §6.3 `setup_preconditions` | NONE | No multi-day-extreme staging or radar/watchlist progression anywhere in the scalper engine; no N-day high/low is computed at all | Manually build the radar at the 1–2-day stage and escalate conviction toward the 8–9-day breakout. Automatable=true once per-stock candles exist (rolling N-day extremes). **MISSED by v1.** |
| **OI Spurt 4-quadrant cue** (refer the stock's OI Spurt quadrants for an extra entry cue) | §3.3 Filters (screener-structure note); §6.3 `indicators`, `filters` | PARTIAL | An `oi_spurt` dot IS computed and scored — the spurt quadrant must match the side AND both ΔOI% and price% magnitudes clear their floors (`ConnectTheDotsScorer.java:89-90,159-166`) — but on the **option-root index**, not the picked stock; it is a soft confluence dot, not a per-stock cue | The spurt-quadrant cue is automated at index level only; manually read the **stock's** OI Spurt 4-quadrant panel. Automatable=true once equity OI is captured. **MISSED by v1** (v1 listed no row for the OI-Spurt cue although the §0B confluence scores it). |
| **Right-side "New High/Low Maker" panel** — use live new intraday highs/lows for support (bullish, in Gainers) or rejection (bearish, in Losers) trades | §3.3 Filters (screener-structure note); §6.3 `filters` | NONE | No intraday-new-high/low maker panel or per-stock support/rejection trade input in the scalper engine | Manually watch the New High/Low Maker panel and take support (Gainers)/rejection (Losers) trades. Automatable=partly (needs the equity-screener live high/low feed). **MISSED by v1.** |
| **Large-cap-only filter + operator low-volume trap** — trade only liquid large-caps; a name that gave its whole move on no intraday volume then ranges traps late entrants; stay long only while price holds the intraday VWAP | §3.3 S22 update (a)/(b); §5.3 (S22/S24 liquidity reads) | NONE | No market-cap classification and no operator-trap / VWAP-hold-only-above filter per stock; the only liquidity rule is the static index volume floor (`ScalperGates.volume`, `ScalperGates.java:64-68`) on the surrogate future | Manually restrict to liquid large-caps and abandon a name that ran on no volume; hold long only above the stock's VWAP. Automatable=partly (needs a large-cap list + per-stock intraday volume). **MISSED by v1.** |
| **Short-side overnight (STBT)** — an 8/9-day-low Short-Build-up name is an ideal STBT, carried only if Futures OI is **closing at the day's high with price at the day's low** | §3.3 S22 update (f); §5.3 | NONE | The short side itself is unautomated (`direction: long`, CE only, `scalp-market-movers-nifty.yaml:64,80`); no STBT overnight-carry-on-close-OI-extreme logic exists | Manually evaluate the STBT carry on the close (OI at day-high + price at day-low). Automatable=true given a short variant + EOD per-stock OI. **MISSED by v1** (v1's "short side" row covers the intraday mirror; the STBT overnight-carry condition is a distinct deferred rule). |

### Not automated (gaps)

- **Equity-universe screener (the strategy's entire vehicle):** the F&O-equity Top-Gainers / Top-Losers
  Market Movers screen is absent. The YAMLs substitute a NIFTY-50 / SENSEX **index-option CE** surrogate;
  the doc instrument is *stock futures / cash, "no stock options."* (`FuturesMoversService` covers index
  futures only — its own javadoc flags the equity-futures grid as a needed capture expansion.)
- **8-day / 9-day high/low breakout filter (Min. B.O. Days):** not computed anywhere — the core
  selection rule of the strategy.
- **Per-stock OH/OL flag** (OL for longs / OH for shorts): not fed to the gate (the OH/OL primitive
  exists but is not wired to this strategy and has no per-stock OHLC).
- **Per-stock OI interpretation** (LB/SC long; SB/LU short): the confluence reads index OI, not the
  picked stock's.
- **RSI(Daily) cool-off screen** (< 75 bull / > 40 bear): only a 3m RSI is wired; no daily RSI.
- **Volume-20 MA + per-stock liquidity ranking:** only a static index volume floor is gated.
- **Short side:** `direction: long`, CE only — the entire bearish mirror is deferred.
- **1–2% percent target** and the **volume-conditional adverse exit:** neither is an engine rule.
- **EOD-OI overnight-carry rule** (carry only on Long Build-up): deferred.
- **Top-constituent / index-weightage direction cue** and **S/R-line breakout / both-sides-OI
  ambiguity** checks: not automated.
- **Alternative ">1% price change + considerable OI change" entry trigger:** not gated — the only entry
  condition is the VWAP/VWMA reclaim.
- **Radar-building progression** (1–2-day → 3–4-day → 8–9-day extreme staging): no N-day extreme is
  computed at all.
- **OI Spurt 4-quadrant cue:** scored as a soft `oi_spurt` confluence dot but at the **option-root
  index**, never the picked stock.
- **Right-side New High/Low Maker panel** (support/rejection trades): no live intraday-extreme maker feed.
- **Large-cap-only filter + operator low-volume trap** (hold long only above the stock's VWAP): no
  market-cap class and no per-stock operator-trap filter; only the static index volume floor.
- **Short-side overnight (STBT)** carry-on-close-OI-extreme (OI at day-high + price at day-low): the
  short side is unseeded and the STBT overnight condition is unautomated.
- **Manual checklist coverage:** `ScalperManualChecks.CHECKS` is a fixed, strategy-agnostic 7-item list
  (news / level / not-parabolic / regime / VIX / global-cues / clean-setup) — **none** of its items
  covers any of the Market-Movers-specific gaps above (no breakout-day, OH/OL, per-stock OI, daily-RSI,
  or equity-universe item). So these gaps are true NONE gaps, not MANUAL_COVERED.

## v2 review notes

Independent second-pass review (fresh-derived from §3.3 + §6.3, then diffed against v1). **v1 is high
quality:** every spot-checked file:line cite was verified accurate against the code, with no
false-coverage, no false-gap, and no invented figures. The 19 original rows stand as written. The
review added the rules v1 had not enumerated and noted one omission of an automated cue.

**Cite-checks confirmed (no change):** the 09:45 floor + the 11:00–13:00 / post-15:30 stricter blocks
(`ScalperGates.java:22-25,34,37-42`); the RSI band CE 60–80 / PE 20–40 (`ScalperGates.java:76-84`); the
futures-OI quadrant (`ScalperGates.java:121`); breadth adv/dec > 32 (`ScalperGates.java:128-133`); the
`entry-candle-stop` → `StructuralStop.ENTRY_CANDLE` anchor (`ScalperConfig.java:149-150`,
`ScalperConfluenceGate.java:293-295`); index-level IV in `Macro` (`ScalperGateContext.java:59-68`); and
`FuturesMoversService`'s index-only-capture javadoc + `OiInterpretation.classify` at line 85. The
`scalp-market-movers-*.yaml` set carries ONLY the `entry-candle-stop` tag — confirming the screener
gates (`open-high-low`, `oi-cross-filter`, `two-candle-pattern`) are genuinely NOT armed, so the
NONE/PARTIAL verdicts on those legs are correct.

**MISSED rules added (6 new rows):**
1. **Alternative entry trigger** — "considerable OI change + **>1% price change**" (§3.3 Bull/Bear 5;
   §6.3 `entry_conditions`). Distinct from the VWAP/VWMA reclaim that v1 row 8 covered; **NONE** — no
   >1% price-move or per-bar ΔOI entry gate is wired (only `close > vwap` AND `close > vwma20`).
2. **Radar-building progression** (1–2-day → 3–4-day → 8–9-day extreme staging) — §3.3 Setup 7; §6.3
   `setup_preconditions`. **NONE** — no N-day extreme is computed at all.
3. **OI Spurt 4-quadrant cue** — §3.3 Filters; §6.3 `indicators`/`filters`. v1 listed no row, yet the
   §0B confluence **does** score an `oi_spurt` dot (`ConnectTheDotsScorer.java:89-90,159-166`) — at the
   option-root index, not the picked stock → **PARTIAL** (a soft index-level dot exists; per-stock absent).
4. **Right-side "New High/Low Maker" panel** (support/rejection trades) — §3.3 Filters; §6.3 `filters`.
   **NONE** — no live intraday-extreme maker feed.
5. **Large-cap-only filter + operator low-volume trap** (hold long only above the stock's VWAP) — §3.3
   S22 update (a)/(b); §5.3. **NONE** — only the static index volume floor exists.
6. **Short-side overnight (STBT)** — carry only on close OI-at-day-high + price-at-day-low (§3.3 S22 (f);
   §5.3). **NONE**; distinct from v1's intraday short-side mirror row (the STBT close condition is a
   separate deferred rule).

(Row 3 above is the one place v1 under-listed an *automated* cue; the others are genuine gaps v1 simply
did not enumerate. None changes a v1 verdict — they extend coverage of the doc rule set.)

**Caveat carried forward (not a correction):** the **Breadth** row is marked FULL (index level) while
the doc states no advance-decline rule *specific* to Market Movers — the §0B breadth gate is real and
the cite is accurate, and v1 already hedges this ("UNCERTAIN whether it even belongs here"), so the row
is left intact rather than re-flagged. Likewise the 5m-vs-3m primary-timeframe ambiguity (§6.3
`uncertain`) is a documented doc-side UNCERTAIN, not an automation gap: the engine runs a 3m primary
while the worked examples cite a 5m chart — a faithful divergence, already reflected in the RSI(5m) and
indicator-settings PARTIAL rows.
