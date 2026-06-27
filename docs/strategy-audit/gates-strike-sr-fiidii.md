# Strategy-audit: Global cues / A-D / Strike / Time / S&R / OIP / FII-DII (doc §4.7–4.13)

**Scope.** Audits whether the discrete trade rules in doc sections §4.7 (Global Cues), §4.8
(Advance/Decline), §4.9 (Strike Selection), §4.10 (Time-of-Day Filters), §4.11 (Support &
Resistance), §4.12 (OIP "AI" Confirmation) and §4.13 (FII/DII participant-wise OI) are automated in
the scalper engine. Automation inspected: `ConnectTheDotsScorer`, `ScalperGates`, `ScalperConfluenceGate`,
`MarketOiClient`, `StrikePicker`, `ScalperManualChecks` (strategy-signal-service), and
`ConnectingDotsService` + the FII/breadth analytics (market-data-service). Status is judged by **code
presence**, not backtest behaviour; the OI/Dow/IV-derived-history caveat (factors degrade to NEUTRAL on
historical replay) is noted where relevant but does not lower a FULL rating.

| Rule | Doc § | Status | Evidence (file:line / yaml key) | Gap / manual-check |
|------|-------|--------|---------------------------------|--------------------|
| Global cues (DOW / DOW-30 futures, Dollar Index, Asian, Oil) must match trade direction | 4.7 | PARTIAL | `ConnectingDotsService.dowFactor` (market-data) wires DOW LTP-direction (lines 316-333) as ONE composite factor; the scalper scorer does NOT consume Dow — it has no global-cue dot (`ConnectTheDotsScorer.score` dot list, lines 73-98). Manual checklist item `global_cues_ok` (`ScalperManualChecks.java:51-55`) covers it for the trader. | Only DOW is even fetched, and only into the market-data Connecting-Dots matrix, NOT the scalper gate. Dollar Index / Asian / Oil are nowhere. Manual-check: confirm DOW futures + Asian indices + crude + USD all align before firing (already a checklist item). Automatable: partially (DOW LTP exists; Dollar/Asian/Oil would need new global feeds). |
| Re-check global cues at **3:15 PM** for EOD / next-day setups | 4.7 | NONE | no time-triggered 15:15 re-check anywhere in the scalper package | Live scalper is intraday-only; the BTST/next-day 3:15 PM cue re-check is not modelled. Manual-check: at 15:15 re-confirm global cues for any overnight setup. Automatable: false (no scheduled EOD-cue evaluator; needs the missing global feeds first). |
| **Advances > 32 → CE; Declines > 32 → PE**; A/D must match direction | 4.8 | FULL | `ScalperGates.breadth` (lines 127-133) hard-codes the `> 32` test (CE reads `m.advances()`, PE `m.declines()`, line 129); wired as the `breadth` dot in `ConnectTheDotsScorer.score` line 91; data from `MarketOiClient.macro` → `/api/v1/market/breadth?date=` (lines 368-373) via `advanceDecline` (`MarketOiClient.java:619-623`, reads `summary.advances`/`summary.declines`) | A *soft dot*, not a hard gate (a non-confirming breadth lowers the aggregate but never blocks alone). Breadth is EOD-bhavcopy-sourced (`MarketOiClient.context` javadoc lines 73-76): an intraday bar reads the prior session, so it is a day-bias not a live intraday A/D. The breadth endpoint is keyed by date only (a whole-market A/D), so the "Nifty"-specificity of §4.8 is not enforced index-wise. Manual-check: glance at live Nifty A/D > 32 on the trade side. Automatable: true (intraday breadth feed would replace the EOD proxy). |
| Strikes within **ATM ± 3** only | 4.9 | FULL | yaml `universe.options.strikes: { selector: atm_window, width: 3 }` (e.g. `scalp-connect-the-dots-nifty.yaml:20`); the band is the candidate window handed to `StrikePicker.pick` | — |
| **Delta 0.6–0.7** for the strike to buy | 4.9 | FULL | `ScalperConfig.DELTA_LO=0.6 / DELTA_HI=0.7` (lines 82-83), Black-76 |delta| band enforced in `StrikePicker.pick` lines 99-101 (picks nearest the 0.65 midpoint) | Expiry-phase refinements (0.7–0.8 near expiry end, ~0.5 first day) are doc-sanctioned-DEFERRED (`ScalperConfig.java:78-81`). |
| **Premium range: Nifty 100–250, Bank Nifty 250–400** | 4.9 | FULL | `ScalperConfig.PREMIUM` map (lines 93-98): NIFTY 50 → 100–250, NIFTY BANK → 250–400 (SENSEX 300–800 added per 2b grill); enforced in `StrikePicker.pick` lines 93-95 | Backtest selector ignores the band (nearest-strike); live StrikePicker enforces it (per `ScalperConfig.java:89-92`). |
| Choose the **AI-suggested strike** within the price range | 4.9 / 4.12 | NONE | no OIP/AI-suggested-strike source is consumed; `StrikePicker` selects purely on delta+premium | The doc's "AI-suggested strike" (oipulse OIP recommendation) is not ingested. Manual-check: confirm the chosen strike matches the OIP AI suggestion. Automatable: false (no OIP recommendation feed). |
| **Avoid** strikes with OI liberally populated on **both** call AND put sides ("Desirables" avoid) | 4.9 | PARTIAL | only the #2 open-high-low path has a *both-sides* stand-aside, and it keys off the per-strike OH *footprint*, not chain OI: `OpenHighLow.java:32,146` ("Both-sides OH footprint ... sideways stand-aside") via `OpenHighLowGate` | Not a general rule for the other 11 strategies, and it is OH-footprint not "OI on both sides". Manual-check: skip strikes with heavy CE and PE OI together. Automatable: true (chain CE/PE OI per strike is already fetched in `MarketOiClient.toChainSnapshot`). |
| Option-strike confirmation: **open=high call + open=low put** (bullish) / reverse (bearish), ~90% prob | 4.9 | PARTIAL (MANUAL-adjacent) | only the #2 strategy (`open-high-low` tag) runs the per-strike OH/OL grading: `ScalperConfluenceGate.java:218-229` → `OpenHighLowGate` over `MarketOiClient.openHighStats` (`/options/strike-session-stats`) | Encoded ONLY for #2; the other strategies never apply the open=high/open=low strike confirm. The "~90% red dot" probability tier is informational. Manual-check (non-#2): verify open=high on the CE / open=low on the PE before entry. Automatable: true (the strike-session-stats endpoint already exists; could be a shared dot). |
| Freshness: option price not changed **>50%** vs prev day; identified-strike OI change not **>50%** | 4.9 | PARTIAL | #2 only: `OpenHighLowGate.java:69,106-114` rejects when `spurtPricePct` OR `spurtOiPct` exceeds the 50% barrier (reuses Tier-1 spurt magnitudes) | #2-only and uses index/aggregate spurt %, not the *specific identified strike's* prev-close move. Manual-check: confirm the chosen strike's premium has not already moved >50% and its OI change is <50%. Automatable: true (per-strike prev-close + OI delta are available in the chain/strike-stats endpoints). |
| Take trades **after 9:45 AM** | 4.10 | FULL | `ScalperGates.NO_TRADE_BEFORE = 09:45`, `timeWindow` blocks before it (lines 22, 33-35); enforced pre-flight in `ScalperConfluenceGate.evaluate` lines 112-118 | #9 Morning-Trade opening-tick path intentionally uses 09:15-09:30 instead (`ScalperConfig.OPENING_FROM/TO`, owner-confirmed). |
| Ideal window 9:15–10:00 / let RR ~1% after 9:45 | 4.10 | NONE | only the hard `≥09:45` floor is coded; no "ideal window" preference or RR-1% relaxation | The qualitative 9:15–10:00 preference and the post-9:45 RR-1% guidance are not modelled. Manual-check: prefer the 9:15–10:00 window; expect ~1% RR after 9:45. Automatable: false (a soft preference, not a discrete gate). |
| **Avoid sideways midday ~11:00 AM – 1:00 PM** | 4.10 | FULL | `ScalperGates.MIDDAY_BLOCK_FROM=11:00 / _TO=13:00`, hard-blocked in `timeWindow` lines 23-24, 37-39 | — (the opening-tick overload deliberately omits it as dead, `ScalperGates.java:46-52`). |
| **No fresh entries after 15:30**; events after **3:30 PM → keep off** | 4.10 | PARTIAL | `ScalperGates.NO_FRESH_ENTRY_AFTER=15:30` hard block (lines 25, 40-42) | The TIME cap is FULL; the *"impending event after 3:30 → keep off"* event-awareness is not automated (no event-calendar gate). Manual-check covered by `news_clear` (`ScalperManualChecks.java:26-30`, §2.13). Automatable: partially (an economic-calendar feed could gate). |
| Morning trade is scalping only — finish on target/SL | 4.10 | MANUAL_COVERED | exit handled by per-yaml `exit_rules` (signal_exit + `time_stop max_bars`) + `risk.session.square_off` (e.g. `scalp-connect-the-dots-nifty.yaml:44-56`); the "scalp-only" discipline is operator behaviour | The time_stop/square-off automate exit mechanics; the "don't convert a scalp to a hold" discipline is human. Manual-check: close the morning scalp once target/SL hits. Automatable: n/a (mechanics already automated). |
| Expiry-day Hero-Zero only on expiry day **after 2:00 PM**; watch SC at 2:30–3:00 PM around S/R | 4.10 | PARTIAL | `HeroZeroGate` enforces expiry-day-only + after-14:30 + the SC/OI+price break (`ScalperConfluenceGate.java:236-245`); the §7 deck says >14:30, not 14:00 | Time gate is 14:30 (deck), not the §4.10 "2:00 PM"; the "observe SC 2:30–3:00 around S/R" observation is not coded (no S/R input — see §4.11). Manual-check: on expiry watch short-covering near S/R between 2:30–3:00 PM. Automatable: partially (S/R zones absent). |
| Mark S/R on 1-Day, refine zones on 15-min; trade retrace below resistance / pullback above support; targets = next S/R | 4.11 | NONE | no support/resistance detection in the strategy engine (`Grep` of `libs/strategy-engine` for support/resistance/pivot/zone finds only unrelated `EngineSeries`/`IndicatorBank` matches) | S&R zones are entirely un-automated; entries/targets/stops never reference S/R levels. Already a manual checklist item `level_respected` (`ScalperManualChecks.java:31-35`, §4.11). Manual-check: confirm price is reacting at a marked 1-day/15-min S/R zone, not mid-range. Automatable: true (pivot/zone detection on 1d+15m candles is feasible but unbuilt). |
| OIP **AI direction must match pre-market direction** + own view before firing | 4.12 | NONE | no OIP-AI-direction signal is ingested by the scalper; the scorer's confluence is the in-house Connect-the-Dots approximation, not the oipulse OIP "AI" verdict | The external OIP AI direction is not consumed (the codebase reimplements the dots, it does not read oipulse's AI). Manual-check: confirm OIP AI direction == pre-market == your view. Automatable: false (no OIP AI feed). |
| At **3:20 PM** your view and OI Pulse view should match (BTST/next-day) | 4.12 | NONE | no 15:20 OIP-alignment check exists | BTST/next-day 3:20 PM alignment is not modelled (live scalper is intraday). Manual-check: at 15:20 confirm your view matches OI Pulse for next-day setups. Automatable: false. |
| OIP consolidated tools (Trending OI, Sentiment, Interpretation, VIX-with-BankNifty, 3-min Advance Charts: SC + RSI rising + price>VWAP w/ volume) | 4.12 | FULL (re-implemented) | the dots are re-implemented in-house: `ConnectTheDotsScorer` (trending_cross, sentiment, sentiment_slope, vwap, rsi, volume, vix dots, lines 74-92); 3-min primary per yaml `timeframes.primary: 3m` | These approximate oipulse's tools (not the literal OIP product). VIX dot data is a v1 gap (see VIX row). Judge on forward paper (derived-history degrades OI/IV to NEUTRAL). |
| VIX read (falling→CE / rising→PE) used in confluence | 4.12 / 4.5 | PARTIAL | `ScalperGates.vix` (lines 135-143) + `vix` dot (`ConnectTheDotsScorer.java:92`) are CODED, but `MarketOiClient.macro` passes `null` VIX level + `null` direction (lines 394-397: "VIX has no market-data endpoint yet") — so the dot never fires live | The VIX confluence is plumbed but starved: no VIX feed into the scalper gate. Manual checklist `vix_normal` (`ScalperManualChecks.java:46-50`, §4.5) covers it. Manual-check: glance at India VIX vs recent sessions and its intraday direction. Automatable: true (INDIA VIX candles already exist in market-data — used by ConnectingDotsService.vixByBucket — just not exposed to MarketOiClient). |
| FII/DII participant-wise OI as a Dot for next-day bias; importance **FII > Pro > DII > Client**; absolute-position + change-in-OI reads | 4.13 | NONE | `MarketOiClient.macro` fetches only `/fii-dii/long-short` → `fiiLongPct` (`MarketOiClient.java:375-383`, derived via `latestFiiLongPct` lines 625-641), and that value is stored in `Macro` (`ScalperGateContext.java:66`) but is **NEVER read by any dot/gate** — confirmed: `ConnectTheDotsScorer.score` (dot list lines 74-98) has no `fii` entry, and `ScalperGates` has no FII gate; `grep fiiLongPct` over `scalper/` hits only the `Macro` field declaration + assignment, no consumer. `/long-short` is the derived FII-index-futures L/S ratio only (`FiiDiiController.java:57`, record `LongShortRow` line 31), NOT the 4-participant matrix | The full participant-OI table (FII/DII/Pro/Client × index/stock futures+calls+puts) IS captured in market-data (`ParticipantOiFetcher`, `/api/v1/market/fii-dii/participant-oi`, `FiiDiiController.java:49`) but the scalper consumes none of it, and even the one fetched FII figure is dead-wired (unused). §4.13's importance ordering, absolute-position read, and change-in-OI bullish/bearish table are entirely un-automated. Manual-check: read NSE participant-wise OI (FII/Pro majority + change-in-OI) for next-morning bias. Automatable: true (data already in `nse_eod_participant_oi`; needs a scorer dot + the L/U/B/C change-in-OI classifier). |
| §4.13 **change-in-OI classifier** (compare two consecutive days): Long-OI↑+Short-OI↓ = Aggressively Bullish (LB+SC); the Cautiously-Bullish / Aggressively-Bearish / Cautiously-Bearish rows of the 4×2 table | 4.13 | NONE | no consumer of any participant change-in-OI exists in the scalper; `ConnectTheDotsScorer` scores only chain/futures-OI deltas (`ceOiDelta`/`peOiDelta`, lines 82-90), never participant-wise day-over-day Long/Short OI change | The doc's explicit 4-scenario table (line 1486-1491: Aggressively Bullish = Long "Increase (≈LB)" + Short "Decrease (≈SC)", etc.) is the single most concrete + automatable FII sub-rule, yet has zero code. Distinct from the absolute-position read in the row above. Manual-check: compare today-vs-yesterday participant Long/Short OI and classify LB/SC/LU/SB. Automatable: true (`nse_eod_participant_oi` already stores both days; needs the L/U/B/C delta classifier). |
| §4.13 **leg-level seller read:** across all 4 participants over Index/Stock Futures, Index/Stock Calls, Index/Stock Puts — net-seller of Calls = bearish on that leg, net-seller of Puts = bullish on that leg | 4.13 | NONE | no per-leg participant net-position read anywhere in the scalper; the only participant-derived value reaching `MarketOiClient` is the single aggregate `fiiLongPct` (futures L/S), which is itself unused | The doc (line 1493) names six instrument legs × 4 participants and a Calls-vs-Puts seller polarity; none of it is scored. Manual-check: read who is net-selling Calls vs Puts across participants. Automatable: true (data captured; needs a leg-level classifier + dot). |
| §4.13 **bias validity is next-morning-only**, and only if global factors don't come up strongly | 4.13 | NONE | n/a — the whole FII bias is unautomated (rows above), so its temporal-validity qualifier has nothing to gate | A scope qualifier on the FII bias (line 1495): valid mainly for next-morning hours, voided by strong global moves. Moot until the FII bias itself is scored. Manual-check: only apply the FII bias in the first trading hour and drop it on a strong global move. Automatable: n/a (depends on the FII dot existing first). |

## Not automated (gaps)

- **§4.13 FII/DII participant-wise OI — true gap.** The 4-participant OI matrix (FII/DII/Pro/Client),
  the **FII > Pro > DII > Client** importance order, the absolute-position read, and the change-in-OI
  (LB/SC/LU/SB) bullish/bearish table are NOT scored. The data exists end-to-end in market-data
  (`ParticipantOiFetcher` → `nse_eod_participant_oi` → `/fii-dii/participant-oi`), and `MarketOiClient`
  even fetches one derived `fiiLongPct`, but it is **dead-wired** — no dot/gate reads it. Highly automatable.
- **§4.11 Support & Resistance — true gap.** No S/R zone detection in the engine; entries, targets, and
  stops never reference S/R. Only the manual `level_respected` checklist item covers it. Automatable.
- **§4.12 OIP "AI" direction / strike — true gap.** The external OIP AI direction (§4.12) and the
  AI-suggested-strike (§4.9) are not ingested; the scorer reimplements the dots rather than reading
  oipulse's verdict. Not automatable without an OIP feed.
- **§4.12 VIX dot starved.** The VIX gate + dot are coded but `MarketOiClient.macro` feeds `null` VIX
  (no endpoint wired), so the VIX confluence never fires live despite INDIA VIX candles existing.
  Automatable (re-expose the existing VIX series to the scalper).
- **§4.7 global cues — mostly gap.** Only DOW LTP-direction is fetched (and only into the market-data
  Connecting-Dots matrix, not the scalper gate); Dollar Index, Asian markets, Oil are absent. The
  3:15 PM re-check is unmodelled. Manual checklist `global_cues_ok` covers the trader.
- **§4.9 strike-quality refinements — partial.** The both-sides-OI avoid, the open=high/open=low strike
  confirmation, and the >50% freshness rejects are encoded ONLY for the #2 open-high-low strategy (and
  on OH-footprint / aggregate-spurt %, not the specific strike's chain-OI/prev-close). Not applied to
  the other 11 strategies. Automatable (the per-strike chain + strike-session-stats endpoints exist).
- **§4.10 soft time guidance — gap.** The 9:15–10:00 "ideal window" preference, the post-9:45 RR-1%
  relaxation, and the "impending event after 3:30 → keep off" event-awareness are not gated (only the
  hard ≥09:45 / 11:00-13:00 / ≤15:30 boundaries are). Event-awareness is covered by manual `news_clear`.
- **§4.8 breadth is EOD-proxied + soft.** The `> 32` test is FULL and correct, but it reads the prior
  session's bhavcopy (not live intraday A/D) and is a soft dot (never blocks alone). Automatable with an
  intraday breadth feed.

## v2 review notes

Independent second-pass review (fresh-derived doc §4.7–4.13 against `ScalperConfluenceGate`,
`ScalperGates`, `ConnectTheDotsScorer`, `ScalperGateContext`, `ScalperManualChecks`, `MarketOiClient`,
`StrikePicker`, `ScalperConfig`, `ConnectingDotsService`, then diffed against the v1 table above).

**v1 quality: HIGH.** Every FULL/PARTIAL/NONE verdict traces to real code; no false-coverage
(no FULL the code doesn't do), no false-gap (no NONE/PARTIAL the code actually automates), and no
invented figures — the `>32`, `0.6–0.7`, `100–250 / 250–400`, `>50%`, `14:30/15:20` numbers each
verify against both the doc line and the cited `file:line`. The README "Audit-quality flags" raised
NO false-coverage for this dimension (its flagged rows are gap-theory/btst/connect-the-dots/risk).

**Changes applied (all ADDITIONS / precision — no v1 row downgraded or overturned):**

1. **Added 3 MISSED §4.13 sub-rows (all NONE).** v1 collapsed all of §4.13 into one row + the gaps
   prose, but the doc states three *distinct, concrete, separately-automatable* sub-rules that deserve
   their own tracking:
   - the **change-in-OI 4×2 classifier** (LB/SC/LU/SB → Aggressively/Cautiously Bullish/Bearish, doc
     lines 1486-1491) — the most automatable FII rule, zero code;
   - the **leg-level seller read** (4 participants × 6 legs; net-seller-of-Calls = bearish / Puts =
     bullish, doc line 1493);
   - the **next-morning-only validity** qualifier (doc line 1495), moot until the FII dot exists.
   Confirmed against code: `fiiLongPct` is fetched but has NO consumer (`grep` over `scalper/` finds
   only the `Macro` declaration + assignment); `ConnectTheDotsScorer` scores no participant OI.

2. **Precision-tightened 2 existing rows** (status unchanged, evidence sharpened):
   - **§4.13 FII row** — added the exact derivation refs (`latestFiiLongPct` lines 625-641, `LongShortRow`
     record line 31, participant-OI `FiiDiiController.java:49`) and the explicit "no consumer" grep proof.
   - **§4.8 breadth row** — added the `advanceDecline` mapper ref (`MarketOiClient.java:619-623`), the
     CE/PE branch line, and the note that the breadth endpoint is whole-market (date-keyed), so §4.8's
     "(Nifty)" index-specificity is not enforced index-wise.

**Confirmed-accurate v1 rows (spot-checked file:line, left as-is):** §4.7 Dow/global (Dow only, in
ConnectingDots not the scalper gate, lines 316-333); §4.9 ATM±3 / delta 0.6–0.7 / premium bands /
AI-strike-NONE / both-sides-OI-PARTIAL / open=high-confirm-PARTIAL / freshness-PARTIAL (the #2-only
`OpenHighLowGate` reject at `:69,106-114` verified); §4.10 all four time boundaries (`ScalperGates`
09:45 / 11:00-13:00 / 15:30 verified, Hero-Zero 14:30 vs doc-2:00 discrepancy correctly surfaced via
`HeroZeroGate.RANGE_FROM` line 75); §4.11 S&R-NONE; §4.12 OIP-AI-NONE + the re-implemented-dots FULL +
the VIX-starved PARTIAL (`MarketOiClient.macro` null VIX, lines 394-397, verified).
