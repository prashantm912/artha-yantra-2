# Strategy-automation audit — Indicators + OI build-up/spurts + Trending-OI + VIX + IV (§4.1–4.6)

**Scope.** Audits how the Siva scalper's chart-indicator suite (§4.1/4.2), OI interpretation & OI-spurts
(§4.3/4.3.1/4.3.2), Trending-OI/sentiment (§4.4), India VIX (§4.5) and the 6-strike IV read (§4.6) — plus the
S21–S24 shared-input extensions that touch these dimensions — are realised in the automation: the strategy-engine
indicator registry (`Ta4jIndicators`/`IndicatorRegistry`/`SessionIndicators`), the scalper signal-emission gate
(`ScalperConfluenceGate` → `ConnectTheDotsScorer` + `ScalperGates`, fed by `MarketOiClient`/`ScalperOiProps`), and
the market-data read-time analytics (`ConnectingDotsService`). Two distinct code paths compute these factors and
must be kept separate: the **signal-emission gate** (what actually decides a live scalp; `MarketOiClient.macro`)
and the **OI-page matrix** (`ConnectingDotsService`, a read-time display surface). Derived-history caveat applies to
both (OI/Dow/IV degrade to NEUTRAL on backtests); automation is judged by code presence, not backtest behaviour.

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|------|-------|--------|----------------------------------|--------------------|
| Primary scalp timeframe = 3-minute | 4.1 | FULL | every scalper yaml `timeframes.primary: 3m` (e.g. `scalp-connect-the-dots-nifty.yaml:24`) | — |
| 60-minute longer-intraday bias confirmation | 4.14.6 | FULL | yaml `timeframes.additional: [1h]` + `SUPERTREND@bias60m` (`scalp-connect-the-dots-nifty.yaml:34`); gate `ConnectTheDotsScorer.java:111` biasAligned | — |
| Directional/structure chart = **Bank Nifty Futures 3m** | 4.1 | NONE | signal future is `NIFTY-FUT-CONT` / `SENSEX-FUT-CONT` (`scalp-connect-the-dots-nifty.yaml:17`); no BankNifty-futures spine | BankNifty weeklies are dead (§4.16); doc's BN-futures structure chart is obsolete — confirm the NIFTY-fut spine is the intended replacement. automatable: false (no BN weekly product) |
| S/R on 1-Day, refined on 15-min | 4.1 / 4.11 | MANUAL_COVERED | `ScalperManualChecks.java:33` (`level_respected`, docRef 4.11) | Trader marks 1d + 15m S/R zones pre-market and confirms price reacts at a zone. automatable: partially (S/R levels), but discretionary read stays manual |
| Trending-OI graph window 5–15 min | 4.4 | PARTIAL | yaml `backtest.oi_confluence_gate.interval: "5m"` (`scalp-connect-the-dots-nifty.yaml:60`); `MarketOiClient` reads `/options/trending` with a fixed `SERIES_WINDOW=20` buckets (`MarketOiClient.java:49`) | Live gate uses whatever bucketing the trending endpoint returns; the 5/15-min window is not selectable per the doc. Confirm the trending read is on a 5–15-min view. automatable: true |
| VWAP — default | 4.2 | FULL | engine builtin `vwap`, `SessionIndicators.sessionVwap` (`SessionIndicators.java:20`); gate reads `bank.builtin("vwap")` (`ScalperConfluenceGate.java:310`) | — |
| Supertrend **10, 2** | 4.2 | FULL | yaml `params: { period: 10, multiplier: 2.0 }` (`scalp-connect-the-dots-nifty.yaml:32`); `IndicatorRegistry.java:60-64` | — |
| VWMA (used with ST vs VWAP) — period **20** | 4.2 / 4.15.3 | FULL | yaml `VWMA period: 20` (`scalp-connect-the-dots-nifty.yaml:28`); `SessionIndicators.vwma` (`SessionIndicators.java:47`); scorer `vwma` dot (`ConnectTheDotsScorer.java:76`) | — |
| RSI **14**, band **80:20** | 4.2 | FULL | yaml `RSI period: 14` (`scalp-connect-the-dots-nifty.yaml:30`); gate exhaustion caps 80/20 in `ScalperGates.rsiBand` (`ScalperGates.java:81`) | — |
| RSI **40–60 = no-trade**; CE>60 (<80), PE<40 (>20) | 4.2 | FULL | `ScalperGates.rsiBand` CE `>60 && <80`, PE `>20 && <40` (`ScalperGates.java:76-84`); HARD gate at `ScalperConfluenceGate.java:157-163` | — |
| Daily-RSI cross-check: CE side RSI(D) < 75; PE side RSI(D) > 25 | 4.2 | NONE | no daily-RSI indicator in any scalper yaml (only 3m RSI); not in `ScalperManualChecks` | Confirm daily RSI is < 75 (CE) / > 25 (PE) before entry. automatable: true (add RSI@1d indicator + gate) |
| Parabolic SAR **0.02, 0.2** (direction-switch confirm) | 4.2 | FULL | yaml `PSAR` no params → registry default `step 0.02 / max 0.2` (`IndicatorRegistry.java:86-88`; `Ta4jIndicators.psar:74`); scorer `psar` dot (`ConnectTheDotsScorer.java:77`) | doc's "SAR flipping sides" is a flip event; the gate only reads price-vs-PSAR level, not the flip. PARTIAL on the flip nuance — note below. automatable: true |
| Volume-candle threshold: Nifty **125K**, BankNifty/other **50K** | 4.2 | FULL | `ScalperGates.java:28-30` NIFTY 125k / index 50k; HARD volume gate `ScalperConfluenceGate.java:161` | doc says BN 50K / N 125K; SENSEX maps to the 50k index floor (reasonable, doc silent on SENSEX volume number) |
| Intraday-variant kit adds **MACD / ADX** (+ ST 7,3 on 15m/1h) on top of the scalp suite | 4.14.9 | NONE | `MACD_HIST` + `ADX` ARE in the engine registry (`IndicatorRegistry.java:46-55,44-45`; `Ta4jIndicators.macdHistogram:43`/`adx:39`), but **no scalper YAML wires either** (grep of `scalper-strategies/` for MACD/ADX is empty) | The doc lists MACD/ADX only for the *intraday variant*, not the core 3m scalp; the engine could express them but the seeded scalpers don't. automatable: true (add MACD_HIST/ADX aliases + a YAML/gate) — low priority (doc treats them as the non-scalp variant) |
| Indicator-alignment: all indicators below price (bull) / above (bear); ST & VWMA cross VWAP together = Golden Cross | 4.2 | PARTIAL | `ScalperGates.indicatorAlignment` exists (`ScalperGates.java:102-118`) but the confluence scorer treats each as a *soft weighted dot*, not the all-aligned HARD requirement; only VWAP is decisive (`ConnectTheDotsScorer.java:74,114`) | The strict "ALL indicators on one side + simultaneous ST&VWMA VWAP cross" is scored, not required. Confirm the golden-cross alignment visually. automatable: true (wire `indicatorAlignment` as a hard gate / golden-cross detector) |
| OI build-up types LB/SC/SB/LU (futures price-vs-OI 4-state) | 4.3.1 | FULL | `OiQuadrant.fromInterpretation`; `MarketOiClient.frontFuturesQuadrant` (`MarketOiClient.java:401-404`); gate `ScalperGates.oiQuadrant` (`ScalperGates.java:121`); matrix `futOiFactor` (`ConnectingDotsService.java:249`) | — |
| Strike-level OI: bull = Call-OI declining / Put-OI increasing; bear = reverse | 4.3.1 | FULL | trending cross `peOiDelta>0 && ceOiDelta<0` for CE (`ConnectTheDotsScorer.java:131-133`); `underlying_oi`/`sentiment` dots | — |
| OI-Spurts 4 quadrants, qualified by **50% increase in OI & 50% in price** (Q1 bull / Q2 bear) | 4.3.2 / 4.14.3 | FULL | `oiSpurt` dot requires quadrant + both magnitudes ≥ floor (`ConnectTheDotsScorer.java:159-167`); defaults `spurtOiPct=50` / `spurtPricePct=50` (`ScalperOiProps.java:42-43`) | — |
| Q3 Short-Covering/Long-Liquidation; Q4 (slide OI + slide price) = avoid | 4.3.2 | PARTIAL | quadrant enum carries the 4 states (`OiQuadrant`); but Q3/Q4 are only *non-confirming* (no support), not an explicit "avoid" block | Q4 "better to avoid" is implicit (just fails to confirm), not an active veto. Confirm you are not entering on a slide-OI/slide-price chop. automatable: true |
| #5 Q1/Q2 buyer gate: require **>50% Δprice AND >50% ΔOI** on the correct quadrant to buy | 4.14.3 | FULL | `requireCallPutDeltaFilter` HARD pre-gate `crossFilterPct` default 50 (`ScalperConfluenceGate.java:196-199`; `ScalperGates.callPutDeltaFilter:151`; `ScalperOiProps.java:32`) | — |
| Demand read = price-move-per-OI (big price move on small ΔOI = stronger demand) | 4.14.3 | NONE | no price-per-OI ratio computed; `drasticOi` looks at absolute ΔOI magnitude only (`ConnectTheDotsScorer.java:141-153`) | Eyeball whether the move came on light OI (strong) vs heavy OI (weak). automatable: true |
| Trending-OI: bull = **Put OI crosses above Call OI**, gap widening; bear = mirror | 4.4 | FULL | `trendingCross` requires `crossed \|\| gapWidening` + correct delta signs (`ConnectTheDotsScorer.java:125-134`); `deriveTrending` cross+widening (`MarketOiClient.java:456-488`) | — |
| Sentiment graph slopes up (bull) / down (bear) | 4.4 | FULL | `sentiment` level dot + `sentiment_slope` dot `last−first` sign (`ConnectTheDotsScorer.java:84,88`; `MarketOiClient.deriveSentiment:526`) | — |
| Crossover confirmed by high volume (50K/125K) AND RSI<75 (bull)/>25 (bear) | 4.4 | FULL | volume HARD gate + RSI exhaustion caps 80/20 already enforced (`ScalperGates.java:81`,`ScalperConfluenceGate.java:161`) | doc says <75/>25; gate caps at 80/20 — slightly looser. note below |
| #5 trending-OI ≥50% call-vs-put ΔOI imbalance pre-gate | 4.17.3 | FULL | `callPutDeltaFilter` ≥50% (`ScalperGates.java:151`); imbalance `\|peΔ−ceΔ\|/max ×100` (`MarketOiClient.imbalancePct:508`) | — |
| Trending-OI most reliable on **15 strikes (7+ATM+7)**; 5–7 for golden-cross confirm | 4.17.3 / 4.14.6 | UNCERTAIN | strike-window for the trending read is server-side in market-data; not surfaced/selectable in the scalper gate | Cannot confirm the trending endpoint reads 15 strikes. Verify the strike window the `/options/trending` feed aggregates. automatable: true (if endpoint takes a strike-count param) |
| Intraday vs positional OI must agree; ideal bull ≈ 5cr call vs 10–12cr put; PCR 1.2→1.5→2 | 4.17.3 | NONE | only an intraday trending read; no positional (yesterday+today) series, no PCR-progression watch | Manually compare intraday vs positional Trending-OI and watch PCR trend. automatable: true (positional series + PCR endpoint exist on the OI pages) |
| India VIX directional grid (price↑&VIX↓=bull, etc.); CE→VIX down, PE→VIX up | 4.5 / 4.14.1 | **NONE (signal gate)** / PARTIAL (OI page) | **Signal gate: `MarketOiClient.macro` hardcodes `vixLevel=null, vixRising=null`** (`MarketOiClient.java:394-397`) → `ScalperGates.vix` always "direction unknown → pass" (`ScalperGates.java:136-143`), so VIX **never** confirms or blocks a live scalp. The OI-page matrix DOES compute a falling=bull/rising=bear factor (`ConnectingDotsService.vixFactor:263`) but that is a display surface, not the emitter | **True gap on the signal path.** No VIX endpoint feeds the scalper gate ("VIX is a v1 gap" per `MarketOiClient.java:350,394`). Manually check India VIX direction (down for CE / up for PE) before every entry. automatable: true — wire a VIX series/endpoint into `macro()` (a `VIX_LEVEL` indicator already exists in the registry, `IndicatorRegistry.java:128-131`, but no scalper yaml uses it) |
| VIX absolute regime bands (10–11 low/bull … 17+ high/active-shorts) | 4.14.1 | NONE | no absolute-VIX banding anywhere in the scalper gate | Read the VIX regime band to size/expect-move. automatable: true |
| VIX supporting inferences: rising VIX = **fresh short** positions (VIX spikes only on fresh shorts); VIX stable + price falling = longs being exited (fall unlikely to sustain) | 4.5 / 4.14.1 | NONE | not modelled anywhere — the signal-gate `vix` is null-fed (see VIX-grid row); the `ConnectingDotsService.vixFactor` matrix factor (`ConnectingDotsService.java:263`) is a pure falling=bull / rising=bear *direction* read and carries no fresh-short / longs-exiting interpretation | The "VIX-up = fresh shorts vs VIX-stable = longs exiting" read (a richer-than-direction interpretation) is unautomated. Manual judgement. automatable: partially (needs a VIX feed first, then a VIX-vs-price interpretation rule) |
| VIX vs previous-day close (higher = bearish); erratic intraday VIX → ignore VIX | 4.5 / 4.14.1 | MANUAL_COVERED | `ScalperManualChecks.java:46` (`vix_normal`, docRef 4.5) — "India VIX not abnormally spiking" | Glance at VIX vs last few sessions. The "compare to prev-day close / erratic→ignore" nuance is not encoded. automatable: true |
| IV — 6-strikes: avg CE-IV vs avg PE-IV (3 above + 3 below ATM) | 4.6 | FULL | `MarketOiClient.deriveIvPair` averages 3-above+3-below ATM, excludes ATM (`MarketOiClient.java:567-617`); `iv_pair` dot (`ConnectTheDotsScorer.java:97`) | — |
| IV-pair: ~10-point CE>PE gap = bullish on higher-IV side (with the move) | 4.6 / 4.15.4 | FULL | `ivPair` requires gap ≥ `ivPairMinGap` default 0.10 (=10 IV pts on the 0..1 fraction scale) (`ConnectTheDotsScorer.java:173-180`; `ScalperOiProps.java:38`) | doc 4.15.4 refines to a **7–10-pt** band; default encodes 10. note below |
| IV 40/40 (both high) = stay away / short-straddle | 4.6 / 4.15.4 / 4.17.5 | FULL | `ivBothHighStandAside` forces the signal invalid when both ≥ `ivBothHighFloor` 0.40 and gap < minGap (`ConnectTheDotsScorer.java:96,115,186-195`; `ScalperOiProps.java:40`) | — |
| 10–12 IV good for Trend play (low IV = most of move captured) | 4.6 | PARTIAL | `iv_rank` soft dot fires when IV-rank < 50 (cheap premium) (`ConnectTheDotsScorer.java:94`) — a *rank*, not the absolute 10–12 level | The absolute "10–12 IV ideal for trend" is not gated (only IV-rank). Confirm ATM IV is in the trend-friendly low band. automatable: true |
| Prefer rising IV in that strike for bull / falling IV for bear ("Desirable") | 4.6 | PARTIAL | gate uses the static CE-vs-PE IV *gap*; no per-strike IV *direction* (rising/falling) in the signal path. Matrix has `ivFactor` direction (`ConnectingDotsService.java:274`) but inverse-signed (falling=bull) and display-only | Per-strike IV-trend (rising for the buy side) is not in the emitter. Confirm the chosen strike's IV is trending the right way. automatable: true |
| IV crashes in 2nd half of expiry day / after events; CE-vs-PE time-value is demand-driven | 4.17.5 | NONE | not modelled in the scalper gate | Be aware of expiry-day IV crush. automatable: false (judgement/event-timing) |
| Futures basis: future>spot (premium)=bull, discount=bear | 4.14.2 | FULL | `basis` dot `ScalperGates.futuresBasis` sign (`ScalperGates.java:163-171`); `MarketOiClient.futuresBasis` from `/futures/term-structure` (`MarketOiClient.java:339,407`); engine `BASIS_PCT` indicator also exists (`IndicatorRegistry.java:132`, unused by scalper yamls) | basis is a soft dot, not a hard gate (consistent with doc as a bias). present-series-discount + next-series-premium nuance not modelled (automatable: true) |
| Volume colour-coding dark-green/dark-red (>50K/125K bull/bear pump) | 4.15.3 | PARTIAL | `volumeFactor` biases volume with price direction in the matrix (`ConnectingDotsService.java:241`); the scalper gate only checks the volume *floor*, not bull-vs-bear pump attribution | Read whether the high-volume candle was a bull or bear pump. automatable: true |

## Not automated (gaps)

- **India VIX is entirely absent from the live signal-emission gate.** `MarketOiClient.macro` returns
  `vixLevel=null, vixRising=null` (`MarketOiClient.java:394-397`), so `ScalperGates.vix` always degrades to
  "direction unknown → pass" — the §4.5 VIX directional rules (CE→VIX down, PE→VIX up) and the §4.14.1 regime
  bands never gate or score a real scalp. The VIX factor that *does* exist is in `ConnectingDotsService`
  (the read-time OI-page matrix), not the emitter. A `VIX_LEVEL` engine indicator exists in the registry but no
  scalper YAML wires it. **Automatable** — feed a VIX series/endpoint into `macro()`. Until then, VIX direction
  and band are a manual pre-entry check (partly covered by the `vix_normal` checklist item, which only asks "not
  spiking", not the directional rule).
- **Daily-RSI cross-check (CE RSI(D)<75 / PE RSI(D)>25, §4.2)** — no daily-RSI indicator is wired; only the 3m RSI.
  Automatable (add `RSI@1d` + gate).
- **VIX absolute regime bands (§4.14.1)** and **VIX-vs-previous-day-close / erratic-ignore (§4.5)** — not encoded.
  Automatable.
- **Positional (yesterday+today) Trending-OI agreement + the 5cr-call/10–12cr-put ideal + PCR 1.2→1.5→2 watch
  (§4.17.3)** — only an intraday trending read exists; no positional series or PCR-progression in the gate.
  Automatable (the OI pages already have positional + PCR series).
- **Price-move-per-OI demand read (§4.14.3)** — `drasticOi` uses absolute ΔOI only, not the move-per-OI ratio that
  flags strong demand. Automatable.
- **Strict indicator-alignment "Golden Cross" (§4.2)** — the "ALL indicators on one side + simultaneous ST&VWMA
  VWAP cross" is scored as soft weighted dots, not required (`ConnectTheDotsScorer.java`); only VWAP is decisive.
  `ScalperGates.indicatorAlignment` is implemented but unused by the scorer. Automatable (wire as a hard gate /
  golden-cross detector).
- **PSAR flip event (§4.2 "SAR flipping sides")** — the gate reads price-vs-PSAR level, not the direction-switch
  flip the doc treats as the confirmation. PARTIAL; automatable.
- **Q4 "better to avoid" + Q3 SC/LL (§4.3.2)** — the off-side quadrants merely fail to confirm; there is no active
  avoid-veto. Automatable.
- **Absolute "10–12 IV good for trend play" (§4.6)** — only an IV-*rank* < 50 soft dot exists, not the absolute
  low-IV-level gate. Automatable.
- **Per-strike IV-trend direction for the chosen strike (§4.6 "rising IV for bull / falling for bear")** — the
  emitter uses the static CE-vs-PE IV gap, not the per-strike IV slope. Automatable.
- **Trending-OI strike-window fidelity (15 strikes / 5–7 / 5–15-min window, §4.4/§4.14.6/§4.17.3)** — the strike
  count and time window of the `/options/trending` feed the gate consumes are not surfaced/selectable; cannot be
  confirmed from the scalper code. UNCERTAIN — verify the market-data trending aggregation, then automatable.

### Notes (thresholds that differ from the doc, not gaps)
- RSI exhaustion caps are encoded as **80/20** (`ScalperGates.java:81`) while §4.4 crossover-confirm says **<75/>25**
  — the engine deliberately uses §4.2's 80:20 band as the single source (documented in the `rsiBand` javadoc).
- IV-pair min gap default is **0.10 (10 IV pts)** (`ScalperOiProps.java:38`) while §4.15.4 refines the ideal band to
  **7–10 pts** — the default sits at the top of that band; it is DB-tunable.
- The §4.5 doc names **Bank Nifty Futures 3m** as the structure chart, but BankNifty weeklies are dead (§4.16); the
  automation substitutes the NIFTY/SENSEX continuous front future as the signal spine.

## v2 review notes

Independent second-pass review (fresh-derived §4.1–4.6 + the S21/S22/S24 extensions, then re-traced every
v1 row against the code). **v1 quality: HIGH** — the table is accurate and well-cited; the VIX-null gap, the
6-strike IV pair, the 50% OI-spurt gates and the indicator settings (ST 10,2 / VWMA 20 / PSAR 0.02,0.2 /
RSI 14) all check out at the cited lines. Changes made:

**Added (MISSED doc rules):**
- **Intraday-variant kit MACD / ADX (§4.14.9).** `MACD_HIST` and `ADX` are registered in the engine
  (`IndicatorRegistry.java:46-55,44-45`) but NO scalper YAML wires them — a real doc indicator-set rule v1
  did not list. Status NONE (engine-capable, unseeded). Low priority: the doc scopes them to the non-scalp
  "intraday variant," not the core 3m scalp.
- **VIX supporting inferences — fresh-short / longs-exiting (§4.5).** The doc's "rising VIX = fresh shorts"
  and "VIX stable + price falling = longs exiting" are a richer interpretation than the bare direction read;
  unautomated (the matrix `vixFactor` is direction-only). Status NONE. Reinforces the existing VIX-gap theme.

**Corrected (INACCURATE cite):**
- **VWAP — default** row cited `ScalperConfluenceGate.java:309` for `bank.builtin("vwap")`; the actual call
  is at **line 310** (off-by-one, likely a post-edit line shift). Cite corrected. The FULL verdict is unchanged.

**Confirmed accurate (spot-checked, no change):** the VIX signal-gate NONE verdict — `MarketOiClient.macro`
returns `vixLevel=null, vixRising=null` (`MarketOiClient.java:396-397`), so `ScalperGates.vix`
(`ScalperGates.java:136-143`) always degrades to "direction unknown → pass"; the matrix factor
(`ConnectingDotsService.vixFactor:263`) is display-only — v1's split "NONE (signal gate) / PARTIAL (OI page)"
is correct. The §4.14.3 #5 Q1/Q2 ≥50%-Δprice-AND-≥50%-ΔOI buyer gate (`ScalperConfluenceGate.java:196-199`),
the 6-strike IV pair (`MarketOiClient.deriveIvPair:567-617`), the 40/40 stand-aside
(`ConnectTheDotsScorer.java:186-195`), and the 60-minute bias AND-term (`ConnectTheDotsScorer.java:111`) were
each re-traced and stand. No false-coverage and no invented figures were found in v1's rows.
