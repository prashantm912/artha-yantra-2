# Audit — Introduction & Terminology / Glossary (Section 1)

**Scope.** Audits the rule-bearing content of the consolidated-strategy **Section 1** (1.1 overall
"Connecting the Dots" / "One Good Trade" approach; 1.2 glossary) against the scalper automation
(`scalper-strategies/*.yaml`, `strategysignal.scalper.*`, `strategy-engine` indicators, market-data
analytics). Section 1 is mostly definitional, but several glossary sub-tables carry **tradeable
thresholds** — the **Chart Indicators & Settings** table, the **Premium & Strike Selection
Guidelines** table, the **Time Filters** table, and the **Advance/Decline** and **Delta-band**
rows. Most of these are duplicated in Section 4 (§4.2 indicators, §4.9 strike, §4.10 time, §4.8
breadth, §4.5 VIX, §4.6 IV) and are audited in depth by the `indicators-oi-vix-iv` and
`risk-framework` dimensions; this file judges them from the **§1 glossary wording** and **flags what
is unique to §1**. Derived-history caveat applies to OI/VIX/IV factors (they degrade to NEUTRAL on
backtests) — automation is judged by code presence, not backtest behaviour.

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| **Primary chart = 3-minute; 60-minute chart for the broad view** | 1.1 (line 46) | FULL | yaml `timeframes: { primary: 3m, additional: [1h] }` (`scalp-connect-the-dots-nifty.yaml:23-25`); the 3m series drives every chart dot and the 1h `bias60m` Supertrend is the agree-with-the-60m gate (`ConnectTheDotsScorer.java:111,114-115`). The doc's secondary timeframe is "60-minute"; the YAML uses `1h` (= 60m). | — |
| **Futures Premium/Discount used to read direction** — "Futures above spot = bullish; futures below spot = bearish near-term" | 1.2 Moneyness & Pricing (line 79) | FULL | `ScalperGates.futuresBasis` (`ScalperGates.java:163-171`): basis > 0 (premium) supports CE, basis < 0 (discount) supports PE; scored as the `basis` dot (`ConnectTheDotsScorer.java:93`). Basis is also the StrikePicker forward input (`StrikePicker.java:86`). Soft confluence dot, not a hard gate; `null` basis degrades to pass. | — |
| **VWAP** — "Default" setting; cumulative session VWAP, pullback-entry + alternate SL reference | 1.2 Chart Indicators | FULL | `IndicatorRegistry.java:41` (`VWAP` → `SessionIndicators.sessionVwap`); `vwap` is a hard decisive gate in `ConnectTheDotsScorer.java:74` (W_VWAP=2.5) and `ScalperConfluenceGate.java:149` | — |
| **VWAP** — "Use **yesterday's VWAP** from open until **~10:30 AM**, then today's morning VWAP" | 1.2 Chart Indicators | NONE | `SessionIndicators.sessionVwap` is today-session-only (cumulative from session start). No prior-session-VWAP overlay before 10:30. The #9 path only *degrades* VWAP before 10:30 (`ScalperConfig.VWAP_ACTIONABLE_FROM=10:30`, `ScalperConfluenceGate.java:249`), it does not substitute yesterday's VWAP. | Before ~10:30, eyeball yesterday's VWAP level on the chart as the defended reference; the engine's VWAP is unreliable that early. Automatable: **true** (compute prior-session VWAP and switch at 10:30). |
| **VWAP** — "Wider candle-to-VWAP gap = stronger trend" | 1.2 Chart Indicators | NONE | No gap-magnitude factor; `vwap` dot is a boolean side check only (`ConnectTheDotsScorer.java:71,74`). | Judge trend strength by how far price has separated from VWAP. Automatable: **true** (distance/ATR ratio). |
| **Supertrend (3-min) = 10, 2** | 1.2 Chart Indicators | FULL | yaml `indicators: SUPERTREND … params: { period: 10, multiplier: 2.0 }` (`scalp-connect-the-dots-nifty.yaml:32`); `Ta4jIndicators.supertrendDirection` | — |
| **Supertrend (15-min / 1-hour broad view) = 7, 3** | 1.2 Chart Indicators | FULL | yaml `bias60m` indicator `SUPERTREND … timeframe: 1h, params: { period: 7, multiplier: 3.0 }` (`scalp-connect-the-dots-nifty.yaml:34`); consumed as the 60-min bias gate (`ConnectTheDotsScorer.java:111`). Note: doc says "15-min / 1-hour"; YAML uses 1h only. | — |
| **VWMA / WMA** — defence & crossover reference (no explicit period in §1) | 1.2 Chart Indicators | PARTIAL | yaml `VWMA … params: { period: 20 }` (`scalp-connect-the-dots-nifty.yaml:28`); `IndicatorRegistry.java:79`. §1 lists "VWMA / WMA" with a blank setting — the 20-period is an engine default, not a §1-stated value. | Confirm the 20-period VWMA matches the WMA Siva actually uses (§1 is silent on the period). Automatable: **true** (already a tunable YAML key). |
| **Parabolic SAR = 0.02, 0.2** | 1.2 Chart Indicators | FULL | yaml `PSAR` (no params → registry defaults `step 0.02, max 0.2`, `IndicatorRegistry.java:87-88`); `Ta4jIndicators.psar` | — |
| **RSI = 14; band 80:20; no-trade 40–60; overbought >75/80; oversold <25/20** | 1.2 Chart Indicators (dup §4.2) | FULL | yaml `RSI … params: { period: 14 }` (`scalp-connect-the-dots-nifty.yaml:30`); band gate `ScalperGates.rsiBand` (`ScalperGates.java:76-84`): CE 60–80, PE 20–40, 40–60 no-trade. (§4.2 governs the exact band; see `indicators-oi-vix-iv`.) | — |
| **Volume Candle Threshold — BN 50K, Nifty 125K** | 1.2 Chart Indicators (dup §0B) | FULL | `ScalperGates.java:28-30` (`NIFTY_VOL=125000`, `INDEX_VOL=50000`), gate `ScalperGates.volume` (`ScalperGates.java:64`) | — |
| **Buy strike delta = 0.6–0.7 (slightly ITM)** | 1.2 Premium & Strike Selection / Greeks | FULL | `ScalperConfig.java:82-83` (`DELTA_LO=0.6`, `DELTA_HI=0.7`); selected via Black-76 delta in `StrikePicker.java:99-105`. (§4.14.7/§4.15.4 expiry-phase delta refinements are doc-sanctioned-deferred, see `ScalperConfig.java:78-81`.) | — |
| **Strike range = ATM ±3** | 1.2 Premium & Strike Selection / Moneyness | FULL | yaml `strikes: { selector: atm_window, width: 3 }` (`scalp-connect-the-dots-nifty.yaml:20`) | — |
| **Premium range (buying) — Nifty ~100–250; Bank Nifty ~250–400** | 1.2 Premium & Strike Selection | PARTIAL | `ScalperConfig.java:93-98` (NIFTY 100–250, NIFTY BANK 250–400, SENSEX 300–800). LIVE `StrikePicker` enforces the band (`StrikePicker.java:93`); **the backtest premium-replay selector ignores the band and picks nearest-strike-to-spot** (`ScalperConfig.java:90-92`). | On a live signal, confirm the picked premium is inside the band; on backtests the band is NOT applied (data-fidelity, not a strategy choice). Automatable: **partly** (band exists live; backtest selector intentionally bypasses it). |
| **Advance / Decline — Nifty: advances >32 favours CE, declines >32 favours PE** | 1.2 Setups/Signals (dup §4.8) | FULL | `ScalperGates.breadth` (`ScalperGates.java:128-133`, `count > 32`); fed from `/api/v1/market/breadth` (`MarketOiClient.java:368-373`). Soft confluence dot (`ConnectTheDotsScorer.java:91`), not a hard gate. | — |
| **Time filter — Trade after 9:45 AM; ideal entry 9:15–10:00 AM** | 1.2 Time Filters (dup §4.10) | PARTIAL | `ScalperGates.NO_TRADE_BEFORE=09:45` enforced (`ScalperGates.java:22,34`). The **"ideal 9:15–10:00" preference is NOT modelled** (no preferential weighting); #9 Morning Trade uses its own 09:15–09:30 opening window (`ScalperConfig.java:72-73`). | The 9:45 floor is hard-coded; "ideal 9:15–10:00" is advisory only — prefer the early window manually. Automatable: **true** (time-of-day preference weight). |
| **Time filter — Avoid sideways 11:00 AM – 1:00 PM** | 1.2 Time Filters (dup §4.10) | FULL | `ScalperGates.MIDDAY_BLOCK_FROM=11:00 / _TO=13:00` (`ScalperGates.java:23-24,37`), hard block | — |
| **Time filter — No new entries before events / after 3:30 PM** | 1.2 Time Filters | PARTIAL | "after 3:30 PM" FULL: `ScalperGates.NO_FRESH_ENTRY_AFTER=15:30` (`ScalperGates.java:25,40`). **"before events" is NOT automated** — there is no economic-calendar/event feed; covered manually by `ScalperManualChecks` `news_clear` (§2.13). | `news_clear` manual check ("No market-moving news or event… news overrides the data") covers the event side. Automatable: **partly** (would need an event-calendar feed). |
| **Time filter — Expiry-day Hero-Zero after 2:00 PM** | 1.2 Time Filters (dup §3.7/§7) | PARTIAL | Hero-Zero gate fires only **after 14:30**, not 14:00: `HeroZeroGate.RANGE_FROM=14:30` (`HeroZeroGate.java:75`). §1 says "after 2:00 PM" (14:00). | Discrepancy: code waits to 14:30 vs §1's 14:00 (§3.7/§7 detail drives this — flag to the hero-zero dimension). Automatable: **true** (config the start time). |
| **Time filter — Gamma moves around 3:00 PM** | 1.2 Time Filters / Greeks | NONE | No 3:00 PM gamma-move detector or factor. | Be aware of expiry-day ~3 PM gamma acceleration when managing far-OTM legs. Automatable: **false** (descriptive market-behaviour note, not a trigger). |
| **Connecting the Dots — combine global cues + VIX + OI + IV + price action; trade only when dots align** | 1.1 / 1.2 Setups | FULL | `ConnectTheDotsScorer.score` aggregates VWAP/ST/VWMA/PSAR/RSI/volume + OI quadrants/spurts/sentiment + breadth/VIX/basis/IV into one confluence with a threshold (`ConnectTheDotsScorer.java:63-118`); threshold `0.6` (`ScalperConfig.java:88`). | — |
| **Connecting the Dots — Global cues = DOW/DOW30 futures, Dollar index, Asian markets, Crude Oil** | 1.1 (dup §4.7) | PARTIAL | Only a **DOW** factor is wired (market-data `ConnectingDotsService` Dow factor, task #13). **Dollar index, Asian markets, Crude Oil are NOT** in the scalper macro context (`MarketOiClient.macro` returns IV/rank/breadth/FII only, `MarketOiClient.java:350-398`). Covered manually by `ScalperManualChecks` `global_cues_ok` (§4.7). | `global_cues_ok` manual check covers DOW futures + Asian + crude + USD. Automatable: **partly** (DOW partly wired; USD/Asia/crude need feeds — OI Pulse dashboard tracks Crude+USD/INR per §1.2). |
| **India VIX — directional gate + levels (10–11 low/bullish, 12–14 med, 15–16, 17+ active shorts) + correlation rules** | 1.2 Volatility (dup §4.5) | NONE | **VIX is a v1 gap in the live scalper path** — `MarketOiClient.macro` returns `null` VIX level + `null` direction (`MarketOiClient.java:394-397`); the `vix` gate treats unknown direction as **non-blocking** (`ScalperGates.java:136-143`). No band classification (10/12/15/17) anywhere in the scalper. Covered manually by `ScalperManualChecks` `vix_normal` (§4.5). | `vix_normal` manual check ("India VIX is not abnormally spiking"). The doc's specific bands/correlation are NOT encoded — read India VIX level & direction yourself before entry. Automatable: **true** (needs a VIX market-data endpoint — explicitly noted as a §12.2 follow-up). |
| **IV — averaged over 6 strikes (3 above + 3 below per side); 10–12 IV good for trend; IV higher on trending side** | 1.2 Volatility (dup §4.6) | PARTIAL | 6-strike CE/PE IV pair IS derived (`MarketOiClient.deriveIvPair`, called at `MarketOiClient.java:385-392`) and feeds the `iv_pair` / stand-aside dots (`ConnectTheDotsScorer.java:97,173-195`); `ivPairMinGap=0.10`, `ivBothHighFloor=0.40` (`ScalperOiProps.java:38-40`). **The "10–12 IV good for trend play" absolute band is NOT a gate** (no absolute-IV trend-play threshold; only the CE-vs-PE gap is used). | Confirm absolute ATM IV is in a tradeable range (Siva's ~10–12 "good for trend") — only the directional IV gap is automated. Automatable: **true** (add an absolute-IV band gate). |
| **Falling Knife — never catch a sharply falling market when VIX is extreme (e.g., 41)** | 1.2 Volatility | NONE | No extreme-VIX falling-knife block (and VIX itself is null in the scalper path, see above). | In an extreme-VIX crash, stand aside — not enforced. Automatable: **true** (once VIX feed exists). |
| **Basket Order Selling — investors offload multiple sectors at once; begins as VIX rises above ~17, widespread above ~25** | 1.2 Volatility (line 117) | NONE | No basket-selling / VIX-level (17 / 25) detector anywhere in the scalper path; VIX level itself is null (`MarketOiClient.java:394-397`), so neither the ~17 onset nor the ~25 widespread band can be tested. Closest manual coverage is `ScalperManualChecks` `vix_normal` (§4.5). | `vix_normal` manual check ("India VIX is not abnormally spiking"). The specific 17/25 basket-selling bands are NOT encoded — watch VIX rising through ~17/25 yourself. Automatable: **true** (once a VIX feed + level bands exist). |
| **IV Crash — sharp drop in IV when buyers exit/unwind (typically after an event); severely hurts buyers holding high-IV premiums** | 1.2 Volatility (line 114) | NONE | No IV-crash (IV-slope-collapse) detector. The scalper derives a CE/PE 6-strike IV *pair* and a 40/40 both-high stand-aside (`ConnectTheDotsScorer.java:97,173-195`; `ScalperOiProps.java:38-40`), but that is a cross-sectional CE-vs-PE gap, NOT a time-series IV-drop guard — a collapsing-IV bar is not flagged. | Avoid buying into a post-event IV crash (high premium about to deflate) — not enforced. Automatable: **true** (an IV-slope / IV-rank-falling factor; gated on richer IV history). |
| **OI Spurts — action threshold >50% change in both OI and LTP; be a buyer only when both 50% met** | 1.2 OI (dup §4.3.2) | FULL | `ConnectTheDotsScorer.oiSpurt` requires both OI% and price% ≥ floors (`ConnectTheDotsScorer.java:159-167`); floors `spurtOiPct=50`, `spurtPricePct=50` (`ScalperOiProps.java:42-43`). The #5 cross-filter ≥50% imbalance is a hard pre-gate (`ScalperGates.callPutDeltaFilter`, `crossFilterPct=50`, `ScalperOiProps.java:32`). | — |
| **OI Spurts — extreme ~200% OI / 300% price = strong confirmation** | 1.2 OI | NONE | Only the 50% floor is encoded; no 200%/300% "strong confirmation" escalation tier. | Treat ~200% OI / ~300% price spurts as extra-strong — not separately scored. Automatable: **true** (add an escalation band). |
| **Trending OI — 5–15 min OI graph, must be unidirectional; whipsaws = caution** | 1.2 OI (dup §4.4) | FULL | `ConnectTheDotsScorer.trendingCross` requires a directional dOI cross / widening gap (`ConnectTheDotsScorer.java:125-134`); slope dot `sentiment_slope` (`ConnectTheDotsScorer.java:88`). | — |
| **"One Good Trade" — patience, fewer trades, pullback entries, not chasing parabolic** | 1.1 philosophy | MANUAL_COVERED | `ScalperManualChecks` `not_parabolic` (§3.1) + `clean_setup` (§3.1) + `regime_ok` (§3.10) | Manual checks enforce the discipline; no automated "trade count today" cap surfaced in §1 scope. Automatable: **partly** (a daily-trade-count limiter could be added). |
| **Art of Averaging — add only at defended levels (ST/VWAP/VWMA), never when SL breached** | 1.1 / 1.2 Setups | UNCERTAIN | No averaging/pyramiding logic in the scalper signal path (signals are single-entry; `max_positions: 1` in yaml). Averaging is an execution-management behaviour outside the signal layer's scope. | If managing manually, average only at ST/VWAP/VWMA — the engine does not average. Automatable: **false** for the signal layer (execution-side concern). |
| **Lot sizes — BN 25 / Nifty 50 / Fin Nifty 40 (doc marks UNCERTAIN)** | 1.2 Instruments | UNCERTAIN | Position sizing is `premium_budget` (yaml `risk.position_sizing`, `scalp-connect-the-dots-nifty.yaml:49`), lot size resolved from the instrument master, not these literals. Doc itself flags these as UNCERTAIN vs current exchange lot sizes. | Verify current exchange lot sizes at trade time; the doc's numbers are stale. Automatable: **true** (instrument master already carries lot size). |

## Not automated (gaps)

- **VIX is entirely unwired in the live scalper path** — `MarketOiClient.macro` returns null VIX
  level + null direction and the VIX gate is non-blocking, so the §1.2 VIX **bands** (10–11 / 12–14 /
  15–16 / 17+), the **correlation rules**, and the **extreme-VIX "falling knife" block** are NOT
  enforced. Only the `vix_normal` manual check covers it. (Explicitly a §12.2 follow-up: no VIX
  market-data endpoint.)
- **VWAP "yesterday's VWAP until ~10:30, then today's"** is not implemented — the engine VWAP is
  today-session cumulative only; before 10:30 the prior-session VWAP reference is a manual eyeball.
- **"Wider candle-to-VWAP gap = stronger trend"** is not scored (VWAP is a boolean side check, not a
  magnitude factor).
- **Global cues beyond DOW** (Dollar index, Asian markets, Crude Oil) are not in the scalper macro
  context — only DOW is partly wired; the rest ride the `global_cues_ok` manual check.
- **"Ideal entry 9:15–10:00 AM" preference** is advisory only (the hard floor is 9:45); no
  preferential time-of-day weighting.
- **"Before events" no-entry** has no event/economic-calendar feed — covered by the `news_clear`
  manual check.
- **Absolute IV "10–12 good for trend play"** band is not a gate (only the CE-vs-PE IV *gap* is
  automated); **falling-knife extreme-VIX** block absent; **OI-spurt 200%/300% "strong"** escalation
  tier absent.
- **Gamma / 3:00 PM move** is a descriptive note with no detector (not meaningfully automatable as a
  trigger).
- **Hero-Zero start time discrepancy**: §1.2 says "after 2:00 PM" (14:00); the gate fires only after
  14:30 (`HeroZeroGate.RANGE_FROM`) — flag to the hero-zero dimension.
- **Lot sizes** in §1.2 are doc-flagged UNCERTAIN and stale; sizing is premium-budget-based, so
  verify current exchange lot sizes manually.
- **Basket Order Selling VIX bands (~17 onset / ~25 widespread)** and **IV Crash** (post-event IV
  collapse) are §1.2 volatility rules with NO automation — both ride the VIX-feed gap (VIX level is
  null) and the absence of a time-series IV-slope factor; only the `vix_normal` manual check is near.

## v2 review notes

Independent second pass. Re-derived every tradeable item in §1.1–§1.2 and re-traced the cited code.
Changes:

- **+4 MISSED rows added** (real §1 rules v1 never enumerated):
  1. **Primary 3-minute / 60-minute broad-view chart** (§1.1 line 46) — FULL: yaml
     `timeframes: { primary: 3m, additional: [1h] }` + the 1h `bias60m` agree-gate
     (`ConnectTheDotsScorer.java:111,114-115`). v1 only referenced the timeframes inside the two
     Supertrend rows, never as the standalone primary-clock rule.
  2. **Futures Premium/Discount as a direction read** (§1.2 Moneyness, line 79) — FULL:
     `ScalperGates.futuresBasis` + the `basis` confluence dot (`ConnectTheDotsScorer.java:93`). A
     genuine §1.2 glossary rule with live automation that v1 omitted.
  3. **Basket Order Selling — VIX >~17 / >~25** (§1.2 line 117) — NONE (rides the VIX-feed gap).
  4. **IV Crash** (§1.2 line 114) — NONE: the CE/PE IV-pair gap is cross-sectional, not a
     time-series IV-drop guard, so a collapsing-IV bar is unflagged.

- **No INACCURATE rows.** Every v1 file:line citation was spot-checked and holds: VWAP
  `IndicatorRegistry.java:41`/`ScalperConfluenceGate.java:149`/`:249`; Supertrend, RSI, VWMA, PSAR,
  ATM±3, premium-budget yaml lines (`scalp-connect-the-dots-nifty.yaml:20,28,30,32,34,49`); PSAR
  defaults `IndicatorRegistry.java:87-88`; VIX-null `MarketOiClient.java:394-397`; breadth `:368-373`;
  IV-pair `:385-392`; `ScalperOiProps.java:32,38-40,42-43`; Hero-Zero `HeroZeroGate.java:75` (14:30 vs
  doc 14:00). Statuses (FULL/PARTIAL/NONE/UNCERTAIN/MANUAL_COVERED) are all defensible as written.

- **README "Audit-quality flags" raised nothing for this dimension** (its false-coverage items target
  gap-theory / btst-stbt / connect-the-dots / risk-framework). The one nearby concern — the
  Hero-Zero "after 2:00 PM" (14:00) vs code 14:30 discrepancy — is already correctly flagged in the v1
  row and the gaps list, deferred to the hero-zero dimension.

- **Confirmed accurate as-is:** all 29 original v1 rows.
