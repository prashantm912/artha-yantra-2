# Completeness sweep — Sections 1, 5, 7 + whole-document orphan check

**Scope.** This dimension audits the *latest-wins refined VALUES* of Section 5 (Strategy Evolution) and the
*Open Questions / UNCERTAIN* items of Section 7 against the scalper automation (the `scalper` package in
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/scalper`, the
`scalper-strategies` YAMLs, the `strategy-engine` indicators/eval, and the OI/VIX/IV feed in
`services/market-data-service/.../analytics/ConnectingDotsService.java`), and acts as the COMPLETENESS CRITIC:
it confirms every rule-bearing doc section is claimed by one of the audit dimensions and flags ORPHANED
rule-bearing content. Section 5 itself introduces no new *rules* (the doc states "no rule deprecated" 48×) —
it carries refined NUMERIC VALUES and resolutions, so the audit targets those values + the §7 resolutions.
The derived-history caveat (OI/Dow/IV degrade to NEUTRAL on backtests, per CLAUDE.md) is noted where the
factor is judged by code presence, not backtest behaviour.

## Section 5 (refined values) + Section 7 (open items) — rule-by-rule

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|---|---|---|---|---|
| **S22-RESOLVED Open=High premium bands: "Bank Nifty 250–550 (avoid >600/<200), Nifty 150–350 (avoid <130/>380)"** — the wider daily-note bands are operative | 5.2 / 7 | PARTIAL | `ScalperConfig.java:93-98` hard-codes the SUPERSEDED narrow bands `NIFTY 50` 100–250, `NIFTY BANK` 250–400, `SENSEX` 300–800 | Live StrikePicker uses the OLD/general bands, not the S22 resolution. Manual-check: confirm the chosen strike's premium falls in the resolved BN 250–550 / N 150–350 band before taking an Open=High scalp. Automatable: true (edit the `PREMIUM` map). |
| **S22-RESOLVED Hero-Zero numeric SL: "index-scaled point SL — Bank Nifty ~75 pts, Nifty ~30 pts, wider Sensex/Bankex" + 50%-premium + 3:20 hard close + conditional 3:10 no-move exit + deploy only ~10% of profits** | 5.7 / 7 | PARTIAL | `HeroZeroGate.java` arms the expiry-day after-14:30 buy gate + OPPOSITE-EXTREME structural stop (`ScalperConfig.java:147-148`); exits are the YAML `stop_loss`/`time_stop`/`square_off` | No index-scaled POINT SL (75/30), no 3:10 no-move exit, no "10% of profits" sizing in code/YAML. Manual-check: set the BN 75 / N 30 point SL and exit by 3:10 if no move; cap deployment at 10% of profits. Automatable: true (point SL + a 15:10 time exit are engine-expressible). |
| **S21-RESOLVED Golden Crossover SL = the Supertrend level (support form); structure-based SL (crossover-candle extreme/VWAP) for the breakout form** | 5.6 / 7 | PARTIAL | `ScalperConfig.java:149-150` `entry-candle-stop` → `StructuralStop.ENTRY_CANDLE` anchors the crossover candle extreme (`ScalperConfluenceGate.java:293-295`) | Encodes the breakout-form (crossover-candle) SL, NOT the support-form "= the Supertrend level" SL. Manual-check: on a support-trade golden crossover, place SL at the live Supertrend level. Automatable: true (the engine already computes Supertrend per bar). |
| **S21-RESOLVED BTST/STBT quadrants: STBT LU = Q4, SB = Q2; BTST SC = Q3, LB = Q1** | 5.8 / 7 | NONE | BTST yaml documents the mapping in a comment (`scalp-btst-stbt-nifty.yaml:6,49`) but the EOD OI-quadrant gate is listed as DEFERRED there; no `OiQuadrant` gate runs in the BTST carry path | Manual-check: at ~3:15pm confirm the close sits in the BTST SC/LB (CE carry) or STBT SB/LU (PE carry) quadrant before carrying overnight. Automatable: true (`OiQuadrant` + the EOD reader exist). |
| **S24 Two-Candle overbought-defer: "if the two candles form with RSI >85, wait for it to cool to ~70–80/75 and enter on the red/pullback candle"** | 5.1 | NONE | `TwoCandleGate.java` + the shared RSI band `ScalperGates.java:76-84` (CE 60–80) — no >85 defer/cool branch | Manual-check: if RSI >85 at the 2-candle, wait for it to cool to ~75 and enter on the pullback candle. Automatable: true (RSI is computed per bar). |
| **S24 Connect-the-Dots full RSI(14) zone table: OB 80 / OS 20; 40–50 no-trade; buy 50–75, book 75–80/85, >80–85 no fresh longs; sell 40–25, book 25–20, <20 avoid** | 5.10 | PARTIAL | `ScalperGates.rsiBand` (`ScalperGates.java:81`) encodes CE 60–80 / PE 20–40 entry band (§4.2 governs per the javadoc) | The BOOKING ladder (book 75–80, re-enter lower) is not engine-carried; only the entry band is. Manual-check: book at the 75–80 / 25–20 rungs and re-enter the same strike lower with booked profit. Automatable: partial (entry band done; the booking-ladder is an exit/scale concern not modelled). |
| **S24 hourly-new-high cadence: "a trending day prints a fresh high roughly every hour; new highs stop + a ~30-pt range = erosion → exit"** | 5.10 | NONE | no cadence/erosion detector in the scalper package or engine | Manual-check: if no fresh high in ~an hour and price holds a ~30-pt box, treat it as erosion and exit. Automatable: true (bar series + session highs are available). |
| **S24 "volume confirmation is mandatory for any break — a VWAP/level break without volume can reverse and trap"** | 5.10 | FULL | `ScalperGates.volume` (`ScalperGates.java:64-68`) is a HARD rail at `ScalperConfluenceGate.java:161`; NIFTY 125k / index 50k floors | (covered) — the volume floor gates every entry, including the breakout. |
| **S24 no-trade zone "price boxed between Super Trend/VWMA and VWAP → only 1–2 lots, wait for a boundary break"** | 5.10 / 5.6 | MANUAL_COVERED | `ScalperManualChecks.java:42-45` `regime_ok` ("trending, not choppy/range-bound; >2-3 VWAP crossovers = choppy, stand aside") | Manual-check is present but it is a *whole-day* choppiness check, not the per-bar ST/VWMA↔VWAP box. Manual-check: if price is boxed between ST/VWMA and VWAP, size down to 1–2 lots and wait for the break. Automatable: true (all three levels are per-bar engine outputs). |
| **S24 Gap Theory volume-direction validity + "30–60-min play only; if unfilled on volume in 30–40 min, ignore and trade with trend; SL ~50–60 pts"** | 5.4 | PARTIAL | `GapTheoryGate.java` arms the gap-fill pre-gate + pre-gap structural stop (`ScalperConfig.java:121,139`) | The "fall carried the volume → reverse" direction test, the 30–40-min give-up timer, and the 50–60-pt SL are not encoded. Manual-check: check whether the up-move or the fall carried the volume; abandon if unfilled in 30–40 min. Automatable: true. |
| **S24 Trend-Change monthly-expiry caveat: "on a monthly-expiry day, ignore the OI / Trending-OI data entirely"** | 5.12 | FULL | `MarketOiClient.java:268-269` suppresses chain-OI on `calendar.isMonthlyIndexExpiryDay(tradeDate)`; the suppressed OI flows into the shared `ctx.oi()` the TrendChangeGate reads | (covered) — OI factors degrade to NEUTRAL on monthly expiry for every gate sharing the context. |
| **S24 Trend-Change counter-trend volume rule: "take a counter-trend trade only if the counter-move is NOT backed by >125K (Nifty) volume"** | 5.12 | NONE | `TrendChangeGate.java` keys on structure break + ≥50% OI shift + 2-candle; no counter-move volume veto | Manual-check: skip a counter-trend entry whose counter-move carries >125K Nifty volume (that is the real reversal). Automatable: true. |
| **S24 BTST validity gate "after the morning move the market does NOT breach VWAP/Super Trend and holds into the close; size 5–10% of capital; exit early next morning"** | 5.8 | PARTIAL | BTST carry rides `style: btst` + a 1-day `time_stop` (`scalp-btst-stbt-nifty.yaml`); §0B VWAP-decisive side at the seam | The "did NOT breach VWAP/ST after the morning move" hold-check and the 5–10% sizing cap are not gated. Manual-check: confirm price held VWAP/ST into the close before carrying; risk only 5–10%. Automatable: partial. |
| **S22/S23/S24 Straddle entry = combined (Call+Put) premium breaks ABOVE/BELOW its own VWAP with volume; long needs LOW IV; SL at the combined-premium VWAP** | 5.11 / 3.11 / 7 | PARTIAL | `StraddleLegPicker.java` picks BOTH ATM legs; the gate skips the directional split (`ScalperConfluenceGate.java:132-147`); seeded as a DRAFT | By design the combined-premium-vs-its-VWAP trigger + the low-IV gate are deferred to LIVE management (the deterministic seam cannot recompute the combined-premium series). Short straddle (SELL legs) is SPAN-deferred. Manual-check: enter only when the combined premium breaks its VWAP with volume and IV is low; SL at the combined-premium VWAP. Automatable: false on the replay seam (needs a live combined-premium series). |
| **S5.x latest-wins POINT targets (e.g. Golden Crossover +200–300 BN / +50–100 N; Open=High ~40–50 pts; Market Movers ~1–2%)** | 5.6 / 5.2 / 5.3 / 7 | NONE | no `take_profit`/`target` exit type in any scalper YAML (grep: only `signal_exit`/`stop_loss`/`time_stop`); §7 itself flags "no explicit numeric profit target" for most strategies | Targets are intentionally live-managed/structural; §7 confirms most are unspecified. Manual-check: apply the per-strategy point/percent target on the option leg. Automatable: partial (a fixed-target exit is engine-expressible but the doc values are per-instrument and often "aim 1–2%" only). |
| **S7 Two-Candle "5-minute an officially allowed primary TF?" — UNCERTAIN** | 7 | UNCERTAIN | YAMLs pin `timeframes.primary: 3m`; the tick-wise runner now accepts 3m/5m/15m/1h (CLAUDE.md #228) | Doc unresolved (slides say 3/5m, manual says 3m). Manual-check: decide 3m vs 5m primary; not a code gap. Automatable: n/a (doc ambiguity). |
| **S7 Trending-OI ">=50% Call-vs-Put filter: day-cumulative or 5–15 min interval?" — UNCERTAIN** | 7 | UNCERTAIN | `ScalperGates.callPutDeltaFilter` (`ScalperGates.java:151-161`) uses the windowed dOI imbalance (interval), floor 50% (`ScalperOiProps.java:32`) | Code chose the interval reading; doc leaves it open. Manual-check: confirm the 50% gap is the intended (interval) reading. Automatable: n/a (resolved by choice; flag for owner sign-off). |
| **S7 Golden Crossover bearish RSI ">25 vs <25" — UNCERTAIN** | 7 | PARTIAL | `ScalperGates.rsiBand` PE band 20–40 (`ScalperGates.java:81`) — neither the deck's >25 nor the grid's <25 verbatim | Manual-check: confirm the bearish RSI cut governing a golden-crossover short. Automatable: n/a (doc conflict). |
| **S7 Hero-Zero bearish mirror (exact PE RSI band, strike offset, PE triggers) — UNCERTAIN** | 7 | PARTIAL | `HeroZeroGate.java` mirrors the side; `HeroZeroStrikeSelector.java` picks one strike inside the SC strike per side | Doc says only "vice versa for put side". Manual-check: verify the PE mirror band/offset on a put-side hero-zero. Automatable: n/a (doc silent on exacts). |
| **S7 Two-Candle bullish RSI upper cap "75 or 80?": "Day-5 manual says 50-75; chess slides say 50-75/80" — UNCERTAIN** | 7 | PARTIAL | `ScalperGates.rsiBand` CE band is **60–80** (`ScalperGates.java:81`), governed by §4.2 per the javadoc (`ScalperGates.java:70-74`) — neither the doc's 50-75 nor 50-75/80 verbatim | The code picked a band (60–80) that does not match either §7 reading (the §3.1 50-75 OR the chess 50-75/80); both the **floor (50 vs 60)** and the **cap (75 vs 80)** diverge. Manual-check: confirm you accept the §4.2 60–80 band for two-candle, not the §3.1 50–75 the card states. Automatable: n/a (doc-internal conflict; resolved by code choice, flag for owner sign-off). |
| **S22-RESOLVED Connect-the-Dots "Sell PE / Sell CE = naked option writing?": buy-side confirmed** — §5.10 S22 "**Resolves the §7 'Sell PE/CE = naked selling?' question** (buy-side confirmed)" | 5.10 / 7 | FULL | Every scalper leg is BUY-only: `StraddleLegPicker` returns only BUY legs (`ScalperConfluenceGate.java:130-131` comment + `:143` `new Leg(...)` legs are bought); the SELL/short-premium legs are SPAN-deferred (`scalp-btst-stbt-nifty.yaml:48`); the YAMLs are CE-LONG/buy-premium | (covered) — the engine never writes naked: it always BUYS the CE/PE leg, exactly the S22 resolution. The §7 "Sell PE/CE" wording is a directional synonym, not option writing. Short-premium selling is the SPAN-#47-deferred path, not this gap. |
| **S22-RESOLVED Morning-Trade "how to trade when morning data is unhelpful" (Q3): act off previous-day closed data + today's open** — §5.9 S22 "**Resolves 'how to trade when morning data is unhelpful'** (Q3: act off previous-day closed data + today's open)" | 5.9 / 7 | PARTIAL | The opening-tick path drops the current VWAP from the hard gate before 10:30 (keeps it a soft dot) (`ScalperConfig.java:126-127` opening-tick arms the Morning-Trade path + a FIRST-CANDLE SL; the pre-10:30 current-VWAP suppression is automated) | The "use yesterday's closed EOD data + today's open to form direction" decision is a *discretionary read*, not a scored gate — the engine only suppresses the unreliable current-day VWAP before 10:30 and anchors a first-candle SL; it does not itself score the prior-day-EOD-vs-open direction. Manual-check: form the morning direction from the prior session's closed OI/price + today's open, per the S22 Q3 resolution. Automatable: partial (the prior-EOD OI read is not modelled in the live macro). |

## Completeness critic — every rule-bearing section mapped to a dimension

| Doc section(s) | Rule-bearing? | Owning audit dimension | Orphan? |
|---|---|---|---|
| 1.1–1.2 (Intro, Glossary, Premium/Strike, Time Filters) | yes (delta 0.6–0.7, ATM±3, premium bands, RSI 80:20 / 40–60, vol 50K/125K, time 9:45 / 11–13 / 3:30, adv/dec >32) | intro-terminology | no |
| 2.1–2.14 (Risk framework + session risk refinements) | yes | risk-framework (s2) | no |
| 3.1 Two Candle | yes | two-candle | no |
| 3.2 Open=High/Low | yes | open-high-low | no |
| 3.3 Market Movers | yes | market-movers | no |
| 3.4 Gap Theory | yes | gap-theory | no |
| 3.5 Trending OI Crossover | yes | trending-oi | no |
| 3.6 Golden Crossover | yes | golden-crossover | no |
| 3.7 Hero-Zero | yes | hero-zero | no |
| 3.8 BTST/STBT | yes | btst-stbt | no |
| 3.9 Morning Trade | yes | morning-trade | no |
| 3.10 Connect the Dots | yes | connect-the-dots | no |
| 3.11 Straddle | yes | straddle | no |
| 3.12 Trend Change | yes | trend-change | no |
| 4.1–4.2 (Chart baseline, indicator settings) | yes | indicators-oi-vix-iv (4.1-4.6) | no |
| 4.3 OI build-ups + spurts (50% quadrants) | yes | indicators-oi-vix-iv | no |
| 4.4 Trending OI & Sentiment | yes | indicators-oi-vix-iv | no |
| 4.5 India VIX directional rules | yes | indicators-oi-vix-iv | no |
| 4.6 IV 6-strikes | yes | indicators-oi-vix-iv | no |
| 4.7 Global Cues | yes | gates-strike-sr-fiidii (4.7-4.13) | no |
| 4.8 Advance/Decline | yes | gates-strike-sr-fiidii | no |
| 4.9 Strike Selection | yes | gates-strike-sr-fiidii | no |
| 4.10 Time-of-Day Filters | yes | gates-strike-sr-fiidii | no |
| 4.11 Support & Resistance | yes | gates-strike-sr-fiidii | no |
| 4.12 OI Pulse "AI" confirmation | yes | gates-strike-sr-fiidii | no |
| 4.13 FII/DII participant-wise OI | yes | gates-strike-sr-fiidii | no |
| 4.14–4.17 (session additions) + 7 | yes | session-additions-and-manual-coverage (4.14-4.17,7) | no |
| 5.1–5.12 (Strategy Evolution refined values) | yes (values, not new rules) | **completeness-sweep (this dim)** | no |
| 6.1–6.12 (Machine-readable appendix) | yes — but it RESTATES §1–§5 as JSON | per-strategy dimensions (it mirrors §3/§5) | no (mirror, not new) |
| 7 (Open Questions) | yes (resolutions + UNCERTAINs) | **completeness-sweep (this dim)** | no |

**No orphaned rule-bearing section found.** Every rule-bearing heading is claimed. One note: §6 (Machine-Readable
Appendix) is a JSON restatement of §1–§5 and carries the same load-bearing resolutions (Golden Crossover SL,
Hero-Zero start time, Open=High bands, Hero-Zero numeric SL) — those are audited above under their §5/§7 rows,
so §6 needs no separate dimension. The §6 JSON is itself NOT consumed by the engine (the YAMLs + Java gates are
the live source); that is a documented design choice (it is "for backtest/bot implementation" reference), not a gap.

## Cross-cutting feed gaps surfaced during the sweep (apply to many strategies, not one §5 row)

| Rule | Doc § | Status | Evidence | Gap / manual-check |
|---|---|---|---|---|
| **India VIX directional gate (4.5 / 4.14.1 / 4.17.5 bands + Price↑VIX↓ rules)** | 4.5 | PARTIAL | The scorer HAS a `vix` dot (`ConnectTheDotsScorer.java:92` → `ScalperGates.vix`), but the live `Macro` is built with `vixLevel=null, vixRising=null` (`MarketOiClient.java:396-397`), so the dot ALWAYS degrades to "unknown → pass" in the strategy-signal path. (The market-data `ConnectingDotsService` DOES compute a VIX factor for its analytics page, but that feed is not wired into the scalper macro.) Also captured in `ScalperManualChecks.java:48-50` `vix_normal`. | Manual-check (already in checklist): glance at India VIX vs recent sessions. Automatable: true (wire a VIX endpoint into `MarketOiClient.macro` — flagged as a "§12.2 follow-up" gap in the code). |
| **FII/DII participant-wise OI directional bias (4.13 / 4.17.4 FII Long/Short-ratio gate)** | 4.13 | NONE (populated-but-unused) | `MarketOiClient.java:375-383` reads `fiiLongPct` into `Macro.fiiLongPct`, but NO gate or scorer dot consumes it (grep: `fiiLongPct` has zero readers outside the record). | Manual-check: read the FII index-future long/short ratio for directional bias before entering. Automatable: true (the data is already fetched — add a dot/gate). |
| **Global cues beyond Dow: Dollar index, Asian markets, Crude Oil (4.7)** | 4.7 | MANUAL_COVERED | Only the Dow factor is automated (`ConnectingDotsService.java:316` live LTP-direction, NEUTRAL in history) and it feeds the analytics page, not the scalper macro. Dollar/Asian/Crude have no automation. `ScalperManualChecks.java:51-55` `global_cues_ok` covers all four. | Manual-check (in checklist): check DOW futures + Asian indices + crude + USD direction. Automatable: partial (Dow LTP exists; DXY/crude/Asian need feeds). |

## Not automated (gaps)

- **S22-resolved Open=High premium bands are NOT live** — the StrikePicker uses the superseded narrow bands
  (N 100–250 / BN 250–400), not the resolved BN 250–550 / N 150–350. One-line fix to `ScalperConfig.PREMIUM`.
- **Hero-Zero index-scaled point SL (BN 75 / N 30), the 3:10 no-move exit, and "10% of profits" sizing** — none
  encoded; only the after-14:30 entry gate + premium/time exits exist.
- **Golden Crossover support-form SL (= Supertrend level)** — only the breakout-form (crossover-candle) SL is wired.
- **BTST/STBT EOD OI-quadrant gate (Q1/Q3 BTST, Q2/Q4 STBT) + the VWAP/ST hold-check + 5–10% sizing** — deferred;
  the carry rides only `style: btst` + a 1-day time-stop, side resolved by close location.
- **S24 numeric refinements with NO automation:** Two-Candle RSI>85 overbought-defer; the hourly-new-high erosion
  detector; the Connect-the-Dots RSI booking ladder (75–80 book + re-enter lower); the Trend-Change counter-trend
  ">125K Nifty volume" veto; the Gap-Theory volume-direction test + 30–40-min give-up timer + 50–60-pt SL.
- **India VIX directional gate is inert in the live scalper path** — the macro passes `null` VIX, so the VIX dot
  never blocks; relies on the `vix_normal` manual check. Wiring a VIX endpoint into `MarketOiClient.macro` is the fix.
- **FII/DII directional bias (§4.13) is fetched but unused** — `fiiLongPct` is read into the macro and consumed by
  no gate/dot; relies on no manual check (there is no FII item in `ScalperManualChecks`). True gap.
- **Global cues Dollar/Asian/Crude (§4.7)** — only Dow is automated (and not into the scalper macro); the rest live
  solely in the `global_cues_ok` manual check.
- **Per-strategy point/percent profit targets (§5.6/§5.2/§5.3)** — no `take_profit` exit in any YAML; exits are
  `signal_exit` + `stop_loss`/`time_stop` + square-off only (§7 confirms most targets are unspecified/structural).
- **Straddle combined-premium-vs-VWAP entry + low-IV gate + short straddle** — deferred to live management / SPAN
  (a genuine seam limitation: the deterministic replay cannot recompute the combined-premium series).

## v2 review notes

Independent second-pass review of §1/§5/§7 + the orphan check. The verdict: **v1's evidence is sound** — every
file:line / yaml key it cited was re-checked against the code and confirmed (`ScalperConfig.java:93-98` premium
bands, `:147-150` Hero-Zero/Golden-Crossover stops; `ScalperGates.java:64-68/81/151-161` volume/RSI/callPut;
`MarketOiClient.java:268-273` monthly-expiry suppress, `:396-397` null-VIX, `:375-383` fetched-but-unused
`fiiLongPct`; `ScalperConfluenceGate.java:132-147/293-295`; `scalp-btst-stbt-nifty.yaml:6,48-49`). No
false-coverage and no invented figure was found in any v1 row, and the README's only §-mislabel flags target
*other* dimensions (gap-theory / btst-stbt / connect-the-dots / risk-framework), not this one. The orphan map
(every §1–§7 heading claimed; §6 a JSON mirror) is correct as written.

The one real shortfall was **completeness of the §7 audit itself** — v1's scope note ("the audit targets those
values + the §7 resolutions") meant it enumerated all five §7 **[RESOLVED]** items as rows but only sampled the
~38 UNCERTAIN bullets (4 rows), and it omitted two §7 conflicts that §5 actually **resolves**. Four rows added:

- **MISSED — S7 Two-Candle bullish RSI cap "75 or 80?"** (doc line 2948). A distinct §7 UNCERTAIN co-located with
  the 5m-vs-3m item v1 *did* capture (row 33). It has a concrete code answer — `ScalperGates.java:81` uses the
  §4.2 **60–80** band, matching *neither* §7 reading (floor 50→60 AND cap 75→80 both diverge). Added as PARTIAL.
- **MISSED — S22-RESOLVED Connect-the-Dots "Sell PE/CE = naked writing?" → buy-side confirmed** (§5.10 S22, doc
  line 1734; §7 doc line 3000). v1 listed four of the five §7 resolutions but skipped this one. The engine BUYS
  every leg (`ScalperConfluenceGate.java:130-131,143`; SELL legs SPAN-deferred) — so it is FULL, a resolution the
  code already honours. Added.
- **MISSED — S22-RESOLVED Morning-Trade Q3 "trade when morning data is unhelpful" → act off prior-day EOD + open**
  (§5.9 S22, doc line 1725; §7 doc line 2993, the "convincing EOD close" / Q3 thread). v1 omitted it. PARTIAL:
  the pre-10:30 prev-day-VWAP degrade is automated (`ScalperConfig.java:126-127`) but the prior-EOD-vs-open
  *direction read* is discretionary, not scored. Added.
- **MISSED — S7 Trending-OI target "1-2% vs 30-50 index points" (doc line 2970)** is folded into the existing
  point-targets row (this table, the `S5.x latest-wins POINT targets` row) and the "no `take_profit`" gap — left
  there rather than duplicated, but called out here so the §7 reader can find it.

These corrections take the §7 [RESOLVED] coverage from 4/5 to **5/5** and the §5-resolves-a-§7-item coverage from
2 (Open=High bands, Hero-Zero SL — both already rows) to **4** (adding the Sell-PE/CE and Morning-Trade Q3
resolutions). All correct v1 rows were retained unchanged; no row was deleted.
