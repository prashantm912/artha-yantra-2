# Consolidated Trading-Strategy Document

> **Scope:** Implementation-ready consolidation of every trade-execution rule extracted from `StrategySources/OptionsScalperSiva/` — **Session 20 (Live Mentoring Program 2023)**, **Session 21 (Live Scalping Mentoring 2024)**, **Session 22 (Live Mentoring Prog 2.0 2024)**, **Session 23 (Sensex Scalping with Siva 2025)** and **Session 24 (Big 5 Anniversary — Live Decoding 21 Days 2025)**.  
> Built for both a developer (backtest / trading bot — see §6 JSON appendix) and a manual trader (numbered steps).  
> Sessions 20, 21, 22, 23 and 24 (all Options) exist today; the structure absorbs later sessions (25, …) and new instrument categories (Futures, Stocks) — see `INSTRUCTIONS.md`. Sessions 21, 22, 23 and 24 re-teach the same finite strategy set with a scalping emphasis; **Session 22 adds the Straddle (§3.11)** and **Session 23 adds the Trend Change strategy (§3.12)** plus a Sensex-scalping focus (trade Sensex via the Nifty chart, ~3× point scaling — §4.16). **Session 24 introduces no new strategy** — it is a 21-day live re-decoding of the same set that confirms Sessions 20–23 and adds refinements (§2.14, §4.17, §5); its "Kingdom Trading Strategy" deck is a chess-metaphor re-framing of Two Candle Theory + the indicator framework, not a 13th strategy. Per the conflict rule the latest session's value wins, with the older value logged in §5.

> **Disclaimer (from sources):** Educational use only. The mentor is NISM-certified but **not** a SEBI-registered advisor. Options trading carries large risk. Do not trade solely on this document.

*Generated: 2026-06-14 (Session 24 integrated 2026-06-15) · Sources parsed: **Session 20** — 13 session/Q&A files + 10 dedicated strategy decks + master checklist (`Sivas 9s.xlsx`); **Session 21** — 11 daily live-scalping sessions + 8 re-taught strategy decks + pre-mentoring docs (VIX/OI/Global, Index Trading, Scalping-vs-Intraday, Intro to F&O); **Session 22** — 12 daily synopses (LMP 2.0) + a 40k-word master "Consolidated Synopsis" + re-taught strategy decks + a new Straddle deck and a new Hero-Zero deck + pre-mentoring docs; **Session 23** — 12 daily live-scalping synopses (Sensex Scalping with Siva) + re-taught strategy decks + a new Trend Change deck + a new "How To Scalp Sensex Using Nifty Charts" deck + pre-mentoring docs; **Session 24** — 21 daily live-decoding transcripts (Big 5 Anniversary) + reused strategy decks surfaced in the daily folders (a "Kingdom Trading Strategy" chess-metaphor deck, an updated "How To Scalp Sensex Using Nifty Charts" deck, an "OI Expiry Strategy" deck) + pre-mentoring docs. Sessions 22, 23 and 24 have **no `Sivas 9s.xlsx`-equivalent**; their rules come from the daily/consolidated synopses + the decks. Psychology/motivation material (and the JaneStreet/SEBI index-manipulation report in the S23 folder) excluded per the relevance filter.*

---

## Strategy Roster

| # | Strategy | Type | Introduced | Sessions | Status |
|---|----------|------|-----------|----------|--------|
| 1 | Two Candle Theory | Momentum-breakout scalping | S20 · Day 5 (21 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 2 | Open = High / Open = Low (O=H / O=L) | Open-type intraday (index opt/fut) | S20 · Day 12 (05 Oct 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 3 | Market Movers | 8/9-day breakout momentum | S20 · Day 12 (05 Oct 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 4 | Gap Theory | Gap fill / continuation | S20 · Day 6 (22 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 5 | Trending OI Crossover | OI crossover trend | S20 · Day 7 (25 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 6 | Golden Crossover | ST+VWMA vs VWAP crossover | S20 · Day 6 (22 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 7 | Hero-Zero (Expiry-Day OI Strategy) | Expiry-day OI play | S20 · Day 8 (26 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 8 | BTST / STBT | Overnight (BTST/STBT) | S20 · Day 8 (26 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 9 | Morning Trade (Opening Trade Strategy) | Opening/morning trade | S20 · Day 8 (26 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 10 | Options Scalping Framework (Connect the Dots) | Core indicator framework | S20 · Day 2 (07 Sep 2023) | S20 · S21 · S22 · S23 · S24 | Current |
| 11 | Straddle (Long & Short) | Neutral / volatility (ATM straddle) | **S22 · Day 11 (Live Mentoring Prog 2.0 2024)** | S22 · S23 · S24 | Current |
| 12 | Trend Change | Reversal / momentum-shift capture | **S23 · Day 10 (Sensex Scalping with Siva 2025)** | S23 · S24 | Current |

**Session coverage:** Session 20 introduced all 10 strategies; **Session 21 (Live Scalping Mentoring 2024)** re-teaches and refines all 10 with a scalping emphasis (8 strategy decks re-issued + live demonstration across daily sessions Day 1–12; no standalone Market Movers/BTST deck — both are taught inside the daily sessions, chiefly Day 12). **Session 22 (Live Mentoring Prog 2.0 2024)** again re-teaches all 10 (reused decks + 12 daily synopses + a 40k-word "Consolidated Synopsis" master) — **largely confirming** Sessions 20/21 — and **adds the Straddle (strategy 11)** via a dedicated Day-11 deck. S22 also ships a **new Hero-Zero deck** (dated 18 Feb expiry, not a reused 2022–23 deck). Like Session 21, Session 22 has **no `Sivas 9s.xlsx`-equivalent checklist matrix**; its rules come from the daily/consolidated synopses + the two new decks. S22 resolves two prior open questions (Open=High premium bands; Hero-Zero numeric stop-loss — see §7).

**Session coverage (cont.):** **Session 23 (Sensex Scalping with Siva 2025)** re-teaches all 11 prior strategies with a **Sensex-scalping focus** — trading Sensex via the Nifty chart as a proxy (~80% stock overlap, Banking+IT overweight) with ~3× point scaling (§4.16) — and **adds the Trend Change strategy as strategy 12** (§3.12; introduced Day 10). Like Sessions 21/22 it has **no `Sivas 9s.xlsx`-equivalent** checklist matrix and most strategy decks are **reused S20/S21/S22 decks** (the same 2022–23 examples, so old deck figures are not reported as new S23 data); the richest sources are the **12 daily live-scalping write-ups (Day 1–12)**. The JaneStreet/SEBI report circulating in the source folder is **excluded** as non-execution (market-regulation reading, not a trade rule). S23 also delivers new risk confirmations (§2.13): deploy only 5–10% of total capital, size down + use a wider point-SL for Sensex's higher volatility, average only near support/VWAP/Supertrend, and stake only a slice of profits on Hero-Zero.

**Session coverage (cont.):** **Session 24 (Big 5 Anniversary — Live Decoding 21 Days 2025)** is a **21-day live re-decoding** of the same finite set — Shiva decodes the framework trade-by-trade each day on a tracked ~₹25-lakh demo account (₹24.97 L → ~₹28.63 L, ~+₹3.8 L over the 21 days). It **introduces no new strategy** and **largely confirms** Sessions 20–23, re-teaching all 12 strategies (the 10 baseline + Straddle + Trend Change) across the daily transcripts; Nifty and **Sensex (via the Nifty chart, Sensex options only)** are the focus, with **Bank Nifty deprioritized** now that its weekly expiries are gone (traded mainly in the last week of the month). Like Sessions 21–23 it has **no `Sivas 9s.xlsx`-equivalent**; the richest sources are the **21 daily live-decoding transcripts (Day 1–21)**, plus three reused decks surfaced in the daily folders. The **"Kingdom Trading Strategy" deck is a chess-metaphor re-framing** of Two Candle Theory + the indicator framework (Queen = OI, Rook = VWAP, Knight = Super Trend, Pawn = VWMA(20), Bishop = Parabolic SAR, Territory = RSI, Weapons = Volume, Battle = Candles, King = the trader) — a teaching mnemonic, **not a 13th strategy** (handled like the S22 "Sic Bo" analogy). S24's substantive additions are: a sharper, fully-chasable **Open=High system** (§5.2), risk refinements (§2.14 — geometric-lot pyramiding, a single-day 10–12% loss cap, instrument-scaled deep SLs), and shared-input refinements (§4.17 — Sensex participation/volume gate, the Trending-OI 15-strike read, FII Long/Short-ratio gate, expiry-day IV crash).

Shared, non-standalone components (defined once in **§4**): Support & Resistance · India VIX rules · OI interpretation (LB/SC/SB/LU + OI Spurts 4 quadrants) · IV 6-strikes · Global cues · Advance/Decline · Strike selection (ATM±3, delta 0.6–0.7, premium bands) · Time-of-day filters · OI Pulse / OIP AI · FII/DII OI.

**How to read:** §1 terminology → §2 global risk (applies to all) → §3 per-strategy playbooks (they cite §4 components by name) → §4 shared components → §5 evolution → §6 machine-readable JSON → §7 items needing confirmation.

---

# 1. Introduction & Terminology

## 1.1 Overall Trading Approach

This strategy is a rules-based, intraday and **scalping** system for trading **index options and futures** (primarily **Nifty 50** and **Bank Nifty**, with Fin Nifty referenced). Positions are created and squared off the same day to avoid overnight risk; scalping trades last from a few seconds to ~3 minutes while intraday trades may run minutes to hours. The directional view is built each morning by **"Connecting the Dots"** — combining global cues (DOW/DOW30 futures, Dollar index, Asian markets, Crude Oil), **India VIX**, **Open Interest (OI)** behaviour, **Implied Volatility (IV)**, and price action through a fixed indicator set read on the **3-minute chart** (with a 60-minute chart for the broad view). Trades are taken only when the dots align: OI Spurts, Trending OI, VIX/IV and the chart indicators (VWAP, Supertrend, VWMA/WMA, Parabolic SAR, RSI, Volume) all confirm the same direction. The core philosophy is "One Good Trade" — patience, fewer trades, disciplined entries on pullbacks to defended levels rather than chasing price.

Execution is driven by OI analysis (treated as the most reliable read of market sentiment), confirmed by VIX and chart structure. Buyers prefer slightly ITM strikes (delta ~0.6–0.7) within ATM ±3 strikes; entries favour retracements to defended levels (VWAP, Supertrend) over chasing. The toolkit also covers expiry-day **Gamma / Hero-Zero** plays and overnight **BTST/STBT** carry-forward setups. Risk is managed through small initial deployment with averaging only at defended levels ("Art of Averaging"), the first candle as stop loss in the 2-candle setup, VWAP as an alternate SL, low quantity / cautious sizing in mixed-signal or high-VIX environments, and avoiding "falling knife" markets when VIX is at extreme levels.

## 1.2 Glossary of Terms, Indicators, Instruments & Abbreviations

### Instruments & Market Structure

| Term / Abbr. | Definition |
|---|---|
| **Derivative** | A financial contract that derives its value from an underlying asset (stocks, indices, commodities, currencies, interest rates). |
| **Futures** | Contract to buy (go long) or sell (go short) an underlying at an agreed price; index futures used to read direction (e.g., Bank Nifty 3m futures chart). |
| **Options** | Contract giving the buyer the right, not the obligation, to buy (call) or sell (put) an underlying at a strike price on/before expiry. |
| **CE (Call Option)** | Bought when bullish; benefits when the underlying rises. |
| **PE (Put Option)** | Bought when bearish; benefits when the underlying falls. |
| **Spot** | Current market price of the underlying index/stock. |
| **Strike Price** | The predetermined price at which an option can be exercised. |
| **Expiry** | Index options have weekly (Thursday) and monthly (last Thursday) expiries; stock options expire monthly. |
| **Lot Size** | Fixed units per contract: Bank Nifty 25, Nifty 50, Fin Nifty 40 (per source; UNCERTAIN — needs confirmation against current exchange lot sizes). |
| **Nifty 50 (N)** | Index of 50 stocks across sectors; less volatile (intraday range typically <1%). |
| **Bank Nifty (BN)** | Index of banking stocks; spot value >2× Nifty, so wider range / higher volatility (>1%). Top 3 constituents carry ~60% weightage. |
| **Mother Market** | The US markets (DOW Jones primary; S&P, Nasdaq 100) whose moves are reflected in other markets; tracked via futures all day. |

### Moneyness & Pricing

| Term / Abbr. | Definition |
|---|---|
| **ATM (At The Money)** | Strike with intrinsic value ≈ zero; market price equals strike. |
| **ITM (In The Money)** | Option with positive intrinsic value (call: spot > strike). Preferred strikes are within **ATM ±3**. |
| **OTM (Out of The Money)** | Option with zero intrinsic value; entire premium is time value; expires worthless if not breached. |
| **Premium** | Price paid to buy an option = Intrinsic Value + Time Value + IV component. |
| **Intrinsic Value** | For a CE = Spot − Strike (never negative). Near expiry with no time value, price is all intrinsic. |
| **Time Value** | Portion of premium tied to time remaining; decays toward expiry (Theta). |
| **Futures Premium / Discount** | Futures above spot = participants expect spot higher by expiry (bullish); futures below spot = bearish near-term. |
| **Discount (option)** | Option trading below its intrinsic value. |

### Options Greeks

| Term / Abbr. | Definition |
|---|---|
| **Delta (Δ)** | Rate of change of option price per ₹1 move in the underlying (−1 to +1). **Buy strikes with delta ~0.6–0.7** (slightly ITM premium moves ~60–70 paise per ₹1) vs ~0.14 OTM (moves only ~14 paise). |
| **Gamma (Γ)** | Rate of change of Delta per ₹1 move; drives sharp expiry-day "Gamma moves" (around 3:00 PM) when no time value remains. |
| **Theta (Θ)** | Daily time decay of option value; favours option sellers. |
| **Vega (ν)** | Sensitivity of premium to changes in implied volatility. |
| **Rho (ρ)** | Sensitivity of premium to a 1% change in interest rates (least significant). |

### Open Interest (OI)

| Term / Abbr. | Definition |
|---|---|
| **OI (Open Interest)** | Total outstanding contracts at a given moment; the most reliable gauge of market sentiment ("God" of options trading). |
| **Long Build-up (LB)** | Price ↑ + OI ↑ (fresh buyers / long positions). |
| **Short Build-up (SB)** | Price ↓ + OI ↑ (fresh sellers / short positions). |
| **Short Covering (SC)** | Price ↑ + OI ↓ (existing shorts buying back); rallies are violent but short-lived. |
| **Long Unwinding (LU)** | Price ↓ + OI ↓ (existing longs exiting); also called Q4. |
| **OI Spurts (4 Quadrants)** | Classifies strikes by OI/price change: **Q1** OI↑ Price↑ (Long Build-up — buyers' focus), **Q2** OI↑ Price↓ (Short Build-up — writers' focus), **Q3** OI↓ Price↑ (Short Covering — buying opportunity), **Q4** OI↓ Price↓ (Long Unwinding — retail avoid). Action threshold: **>50% change in both OI and LTP** (extreme readings ~200% OI / 300% price = strong confirmation). Be a buyer only when both 50% conditions are met across quadrants. |
| **Trending OI** | OI graph (5–15 min) showing which side is being built; should be unidirectional to confirm a trade (continuously rising for bullish, falling for bearish). Whipsaws signal caution. |
| **Sentiment Graph** | OI-based display of prevailing market sentiment. |
| **OI Pulse / OIP** | The charting/data platform (OIPulse.com) decoding OI; includes OI Spurts, Trending OI, Active Strike IV, and an "AI" directional read. |
| **OI Pulse Dashboard** | Tool used to track Crude and USD/INR (plus OI/sentiment data). |

### Volatility

| Term / Abbr. | Definition |
|---|---|
| **India VIX** | NSE volatility / "Fear Index"; reflects expected near-term move (~next 30 days). Calculated on Nifty 50 (Bank Nifty watched due to weightage). **Rising VIX = fresh shorts being built; falling VIX + rising price = bullish.** Levels: 10–11 low (bullish), 12–14 medium, 15–16 mildly higher (seller-controlled), 17+ higher (active shorts, highly volatile). |
| **VIX Correlation Rules** | Price↑ & VIX↓ = bullish; Price↑ & VIX↑ = bearish (may revert); Price↓ & VIX↑ = bearish; Price↓ & VIX stable = may revert up; VIX erratic/sideways = do not use VIX as a factor. |
| **IV (Implied Volatility)** | Market's forecast of likely future movement, priced into options; critical for option buyers. **Averaged over 3 strikes above + 3 below (6 strikes) per side.** Higher IV = buyers holding max positions / big move expected; lower IV = buyers not interested. IV is higher on the side the market is trending; **10–12 IV good for trend play**. |
| **IV Crash** | Sharp drop in IV when buyers exit/unwind (typically after an event); severely hurts buyers holding high-IV premiums. |
| **Historical Volatility** | Volatility from past price movements; reference for comparing current IV. |
| **Falling Knife** | A sharply falling market; never catch it when VIX is at extreme levels (e.g., 41), where Nifty can drop 500–600 pts and Bank Nifty 2000+ pts. |
| **Basket Order Selling** | Investors offloading multiple stocks/sectors at once; begins as VIX rises above ~17, widespread above ~25. |

### Chart Indicators & Settings

| Indicator | Setting | Use |
|---|---|---|
| **VWAP** | Default | Volume Weighted Average Price; the most important intraday level that bulls/bears defend at least once. Use as pullback-entry reference and alternate stop loss. Use yesterday's VWAP from open until ~10:30 AM, then today's morning VWAP. Wider candle-to-VWAP gap = stronger trend. |
| **Supertrend (ST)** | 10, 2 (3-min); 7, 3 (15-min / 1-hour broad view) | Trend/averaging defence level; convergence with WMA forms the Golden Crossover. The 10,2 setting is for the 3-min scalping chart; the source pairs 7,3 with the 15-min/1-hour broad-trend chart. |
| **VWMA / WMA** | — | Volume-weighted / weighted moving average; defence and crossover reference. |
| **Parabolic SAR (PSAR / SAR)** | 0.02, 0.2 | Trend-following stop-and-reverse indicator. |
| **RSI** | 14 | Band 80:20; common **no-trade zone 40–60**; overbought >75/80, oversold <25/20. |
| **Volume Candle Threshold** | BN 50K, Nifty 125K | Minimum volume to validate a breakout/move. |

### Setups, Signals & Trade Types

| Term / Abbr. | Definition |
|---|---|
| **Connecting the Dots (DOTS)** | Consolidated framework combining global markets, VIX, Volume, active-strike IV and OI with VWAP, Supertrend, WMA and PSAR to form a complete view; selectable across timeframes (60-min broad, smaller TF for entries). |
| **2-Candle Formation** | Two-candle setup; entry can be on the 3rd candle if the market is inching in the trade direction with confirming Futures OI; the **first candle is the stop loss**. |
| **Golden Crossover** | Supertrend and WMA converge (below VWAP = bearish, above = bullish); with volume can yield ~200-pt moves, without volume leads to sideways/reversal. |
| **Gamma Move / 3:00 PM Move** | Sharp expiry-day price/premium move from the Gamma effect when no time value remains; sellers without stops badly impacted. |
| **Hero-Zero** | Expiry-day far-OTM play (after 2 PM) where premiums either multiply or expire worthless. |
| **Art of Averaging** | Deploy small initially; average (add) only at known/defended levels (Supertrend, VWAP, VWMA), never when SL is breached. |
| **Scalping** | Trades lasting seconds to 3 minutes; multi-lot, small targets, small stop loss, fast execution. |
| **Intraday Trading** | Trades lasting minutes to hours; bigger targets, bigger SL, pyramiding preferred to averaging. |
| **BTST / STBT** | Buy Today Sell Tomorrow / Sell Today Buy Tomorrow — overnight carry-forward plays. |
| **Advance / Decline** | Breadth of index constituents; **Nifty: advances >32 favours CE, declines >32 favours PE.** |

### Premium & Strike Selection Guidelines

| Parameter | Value |
|---|---|
| **Buy strike delta** | 0.6–0.7 (slightly ITM) |
| **Strike range** | ATM ±3 |
| **Premium range (buying)** | Nifty ~100–250; Bank Nifty ~250–400 |

### Time Filters

| Rule |
|---|
| Trade after **9:45 AM**; ideal entry window **9:15–10:00 AM**. |
| Avoid sideways action **11:00 AM – 1:00 PM**. |
| **No new entries before events** / after **3:30 PM**. |
| **Expiry-day Hero-Zero after 2:00 PM**; Gamma moves around **3:00 PM**. |

---

Section file references (sources read): `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Pre-Mentoring Documents/6. Open Interest Basics.pdf`, `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Pre-Mentoring Documents/9. Understanding Options Greeks_Pre.pdf`, `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Pre-Mentoring Documents/10. Volatility Basics in Stock Market.pdf`, `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Pre-Mentoring Documents/5. Mentoring VIX   OI _ Global Markets.pdf`, `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Pre-Mentoring Documents/4. Index Trading Made Easy.pdf`, `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Pre-Mentoring Documents/1. Scalping Vs Intraday trading.pdf`, `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Introduction to FNO Market 10th Mentoring Day 2.pdf`, `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 4/India Vix _ Implied Volatility LMP23.pdf`.

---

# 2. Global Risk Management Framework

*Source: Session 20 — Live Mentoring Program 2023 ("My Rules! My Trade!", "Why Protecting Trading Capital Is Vital In Day Trading", LMP-2023 Q&A).*

This framework governs every trade regardless of strategy. Capital preservation is the first priority; profit is secondary. *"Save your trading capital to live to trade another day."* *"Take profits like a KING and losses like a Squirrel."*

## 2.1 Risk-Reward Ratio

1. Strictly follow a **1:2 risk-to-reward ratio**, expressed as **0.5% risk : 1% reward** per trade.
2. Beginners must trade **no less than 1:2** — make a healthy risk/reward a habit early; never let losses run larger than profitable trades.
3. Use the **Risk/Reward tool in TradingView / Advanced Chart** to set the RR target *before* entry.
4. Once a trade is entered with all parameters met, **wait for it to hit either the stop loss or the target** — do not interfere.

## 2.2 Per-Trade Risk and Position Sizing

5. Plan **risk = 0.5% of capital** per trade against a **1% reward** target (the 1:2 RR above).
6. **Never deploy more than 10–20% of capital in a single trade** (sources state the cap as a 10–20% range, 20% being the conservative upper bound), and **never deploy more than 20% of total capital in a single day**.
7. Within a trade, **never buy the full quantity in one go.** Deploy the smallest portion first; add (average) only when price comes **closer to VWAP / Super trend** or to a major support/resistance.
8. If the gap between the candles and VWAP/Super trend is **too wide, wait** for a closer entry or skip the trade — a wide gap only widens the SL. Choose only the best trades with the **smallest SL**.
9. Keep **winning-trade quantity = losing-trade quantity.** Larger size on losing trades = a "HOPE TRADE." (Example flagged: 90 qty on wins but 270 qty on losses produces big losses.)
10. **Fix your SL limit based on your previous trade's profit** — risk the prior gains first.

## 2.3 Daily Loss Cap (The 0.5% Rule)

11. **Decide your profit and loss target for the day *before* the first trade.** Close the terminal/app the moment either is achieved.
12. **The 0.5% rule:** stop trading in *all* accounts once losses reach **0.5% of capital** for the day.
13. **Never lose more than 2-3% of capital on any given day.** Losing more than that means **position sizing is wrong** (not based on overall capital). *(Note: when 1% per-account is the target, the per-account daily loss must not exceed 2%.)*
14. **Daily profit target: 1-2% of capital, consistently.** Do not fix a rupee amount to earn each day; aim for a consistent percentage.

## 2.4 Capital Split Across Accounts

15. **Split capital and deploy across 5 different accounts** ("don't put all eggs in one basket"). *(Q&A also accepts splitting across 2-3 accounts for smaller capital — UNCERTAIN whether 5 is mandatory or a maximum; needs confirmation.)*
16. Compute **1% of overall capital; once a target is achieved in one account, move to the next account.**
17. **Maximum 5 successful trades per day — one successful trade per account.**
18. **Your very first trade should be a successful trade.**
19. **Stop-account-after-loss rule:** if the first trade in an account is unsuccessful, **do not trade further in that account for the day.**

## 2.5 Mandatory Hard Stop Loss

20. **Never trade without a Stop Loss.** Keep the SL **set in the system, not in the mind** (no mental stops).
21. **Cut your losses quickly — very quickly, very very quickly.** "Win big, lose small." Do not marry your trades.
22. If a trade you entered correctly still goes against you, **exit and wait for the next opportunity** — it is not necessary to be right every time.
23. **Caution (option sellers near expiry):** in sudden Gamma spikes, SLs can be *skipped* (no buyer at the stop price), leaving an open losing position. Low capital amplifies this risk — trade only one lot and limit trade count if capital is small.

## 2.6 No Averaging / No Revenge / No Over-Trading

24. **Do not over-trade or revenge-trade.**
25. Once you **hit the day's loss target, stop trading and return fresh the next day.** Continuing pushes you sub-consciously into revenge mode and aggressive, emotion-driven errors.
26. **Avoid averaging down** — it adds to a position when your view is *wrong* and the market is moving against you; it can compound into an 8-10% loss even with a 70-80% win rate. Pyramiding (adding when your view is *right*, toward target) is acceptable; averaging down is not.
27. **Do not be in the trade at all if VWAP or Super trend is broken** — that is the level beyond which you should not average.
28. **Trade what you see, not what you think.** Trade like a disciplined professional, **not a gambler** — strictly follow your own rules.

## 2.7 Trend Alignment

29. **"Trend is your friend" — do not trade against the trend.**
30. Try to guess the trend, but **do not believe it or stick to it 100%** — anything can happen in the market at any time.
31. Trade only when it **fits your trading setup**; otherwise **no trade. "No-trade is also a good trade."**

## 2.8 Time-of-Day and Market-Condition Filters

32. **Avoid trading in a sideways trend, mostly 11:00 AM - 1:00 PM.** Use that window to observe and learn techniques from mentors/experts.
33. It is **not compulsory to trade every day** — trade only when you feel good, confident, and the **market is trending**.

## 2.9 Pre-Market / Post-Market Routine

34. **Do pre-market and post-market analysis; be prepared for the next session.** Analyse the previous day (where our markets closed, how global markets performed) and plan how to trade based on how the market opens next day.
35. **Check prerequisites before trading:** internet speed, laptop/mobile, battery.
36. **Trade in a calm place;** do not trade while travelling or busy with other work. Do not trade if you are not at ease — a stressed mind reacts to emotions.
37. **Maintain a trade history book / journal;** register daily trade activity. The journal reveals emotional triggers and helps judge performance on probabilities, not single trades.

## 2.10 Capital Preservation Principles

38. **Trade only with money you can afford to lose;** never trade on borrowed money or with the habit of re-adding funds (re-adding funds adds pressure and breeds recovery-driven, emotional trading).
39. **Capital is your asset — first priority is to preserve capital, then to make profit.** Treat it as if preserved in a fixed deposit.
40. Use a **risk/capital plan that can survive a continuous streak of losses for one full quarter** while keeping the same deployment plan.
41. Accept that **no strategy guarantees a no-loss trade.** Of the five outcomes — small profit, big profit, small loss, break-even, **big loss** — never entertain the **big loss**.
42. **Back-test any strategy for at least 1 year** (a thoroughly back-tested ~70% plan is the right standard).

---

## 2.11 Session-21 Risk Refinements (Live Scalping 2024)

Confirms the Session-20 framework (RR, 0.5%/2-3% daily cap, ≤20% per trade, no averaging down, hard SL, winning-qty = losing-qty, multiple accounts). Additions/clarifications:

43. **Pyramiding vs averaging (made explicit):** *pyramid* (add to a winner) only **after price moves above your entry LTP**, within the range the Risk Calculator shows, and trail your stop as you pyramid; *average* (add at a planned level) only **near support** (Supertrend/VWAP). Never average a losing **trade** — you may add to a losing **position** only if it is part of the pre-set plan, never beyond it (Day 10/11).
44. **Symmetric loss/profit days:** keep the loss-day size in line with the profit-day size — e.g. if you typically make ₹1–2k, cap a loss day at ~₹2.5k. Losing points should be similar in size to your typical winning points (Day 11). Reinforces §2 "winning qty = losing qty".
45. **SL on deployed capital, target on overall capital:** the per-trade stop is ~10% of **deployed** capital (looks large) while the profit target is measured on **overall** capital — this is why the Risk-Calculator SL-vs-target gap looks wide. Size the SL off delta when trading price action; it must fit the trade's RR (Day 11).
46. **Selling only hedged:** never sell naked options (opposite-side risk is enormous); always hedge. A hedged sell may run to ~80% premium decay or to expiry (Day 11). See §4.14.8.
47. **Wide-SL scale-in with profits only:** when the VWAP–Supertrend gap (i.e. the stop) is wide, scale in only when playing with profits — e.g. **<5% of quantity initially, +5% on a ~50-pt move, +10% at VWAP** (Day 5).
48. **Stop the day at target:** once the day's target is hit, end the day (a discipline pop-up fires after 11 AM); any re-entry on continuing momentum must use smaller quantity and risk only a slice of profits. On big/volatile moves, enter small and trail rather than add; sit out extreme volatility until the market settles (Day 3/6/8/11/12).

---

## 2.12 Session-22 Risk Confirmations (Live Mentoring Prog 2.0 2024)

Session 22 **confirms** the whole framework above (RR, ≤10–20% per trade, deploy smallest first / add nearest VWAP, hard SL in the system, no averaging down, winning-qty = losing-qty, multiple accounts, previous-day profit as the first SL, capital-preservation-first). S22 states the daily cap only as **2–3%** (the stricter S20 "0.5% rule" is neither restated nor contradicted). Additions/clarifications:

49. **Loss-symmetry made numeric (Q21):** if the day's profit target is ~2%, the day's loss must not exceed **2–3%**; if winning trades use **4 lots, losing trades must not cross 4–6 lots**. Profit days running ~2% while loss days run ~5% is the signature of over-sized/over-traded losing trades — cut lot count, not increase it. Reinforces §2.2 rule 9 and §2.11 rule 44.
50. **Averaging timing (Q13):** average only in the **first session** of the day, never in the later half, and **not** when both-side OI is balanced and a sudden one-directional move is underway (averaging fails in high volatility). Tightens §2.6 rule 26.
51. **Scale lot size slowly (Q14):** step lot size up little by little, increasing only at **3–6-month** intervals once you are comfortable handling the quantity. (Extends §4.14.9.)
52. **Risk only where probability is higher:** never put the whole capital into a low-probability bet (deep-OTM buying / Hero-Zero) — it can go to zero in a moment; deploy only a **slice of profits** there. Trade small even on the best setups — high probability + small size still gives good returns. (The "Sic Bo / casino" teaching analogy in the synopsis is excluded as a strategy but reduces to this rule: go with the trend, prefer ATM/ITM over OTM, and size to the probability of the specific setup.)
53. **Index-scaled point stop-losses:** because higher-value indices swing more in points, scale the stop to the index — **Bank Nifty ~75-pt SL, Nifty ~30-pt SL**, wider for Sensex (~80000) / Bankex (~58000). (Strategy-specific application is in §3.7 Hero-Zero.)

---

## 2.13 Session-23 Risk Confirmations (Sensex Scalping with Siva 2025)

Session 23 **confirms** the whole framework above (RR, ≤10–20% per trade — max ~30%, deploy smallest first / add nearest VWAP, hard SL, no averaging down, winning-qty = losing-qty, previous-day profit as the first SL, capital-preservation-first, stop the day at target, loss-symmetry, sell only hedged). S23 reframes everything for **Sensex scalping** — trade Sensex via the Nifty chart with ~3× point scaling (§4.16) — and adds a capital-allocation rule plus volatility-scaled sizing. Additions/clarifications:

54. **Allocate only 5–10% of total capital to trading:** deploy only **5–10% of total capital** into the market for trading and diversify the rest (real estate, gold, debt funds); never deploy the whole capital at higher levels — those who do panic and over-deploy when the market falls (Day 1). Within the trading capital, the Risk Calculator still bounds a single trade to **15–20% (max ~30%)** of it: Day 11's example takes a **₹5 L trading capital**, deploys **~₹1.5 L (≈30%)** in a single trade and risks **~10% of that (~₹15k)**.

55. **Size DOWN for Sensex's higher volatility + scale the point-SL to the index:** Sensex (~80000) is ~3× Nifty's value and moves ~3× the points (1% ≈ 250 pts Nifty / ~800 pts Sensex), a double-edged amplification — so trade smaller quantity and use a **wider point stop-loss** than Nifty/Bank Nifty; on brutal/gap/volatile days keep a tight but absolute **50–100-pt SL** and exit fast on a trigger; those uncomfortable with high volatility should not trade Sensex at all (Day 1/4/11/12). Extends §2.12 rule 53 (index-scaled point SLs) to Sensex/Bankex.

56. **Average ONLY near support/VWAP/Supertrend, inside a pre-defined pyramiding band:** define a pyramiding/averaging range on **both** the upside and downside, bounded by known support/resistance, and average **only near S/R or a key indicator (VWAP / Supertrend)** so the loss on the larger position stays small; multi-lot players cluster orders near VWAP to keep the stop tight (Day 1/11). On wide-support-gap Sensex days (e.g. supports ~150 pts apart) do **not** average through the gap — keep a fixed 50–100-pt SL or a 1:1 RR instead (Day 4). Reinforces §2.11 rule 43 / §2.12 rule 50.

57. **Trade light — or stand aside — when chart and OI data diverge or both-side OI is balanced:** when the chart shows one picture and the OI data shows the opposite (divergence), it is a tricky/risky day — **reduce quantity**; when call- and put-side OI are roughly equal (no >50% gap), clarity is absent — stay light or sidelined and let sellers/premium-decay days pass (Day 1/2/5/9/11). One mistake on a low-conviction day can give a big loss.

58. **Hero-Zero / low-probability bets: stake only a tiny slice of PROFITS, never capital:** never over-deploy on a Hero-Zero or deep-OTM expiry bet — treat it like a lottery ticket you lose ~99% of the time; stake only a small amount you can afford to lose (~₹1,000–2,000), i.e. only a slice of profits, never base capital (Day 10). Reinforces §2.12 rule 52 / §5.7 (~10% of profits).

59. **Avoid morning prints on volatile Sensex; news overrides data on gap/event days:** skip the opening prints / morning volatility (especially newbies) — a presumed big move can reverse within 2–3 candles and lose 50–70% if naked; wait for the intraday trend to form, then trade with it (Day 10). On gap-up/gap-down and war/event days, **news takes precedence over charts and data** (data is right only ~8/10) — follow the news, trade smaller, prefer deep-ITM over OTM, and don't over-commit (Day 11/12).

60. **Stop at target; symmetric loss/profit days; one big move can still come:** once the day's target is hit, **stay light** — no aggressive trades (greed/FOMO after target, and the false belief you must trade 9:15–3:30, is why most traders give back profits and revenge-trade, e.g. ramping 200→500 qty then booking a ~₹5k loss) (Day 1/12). High, non-falling premiums plus high IV warn that one big move can still occur even on an "untradeable" day — size accordingly (Day 2). Reinforces §2.11 rule 44 / §2.11 rule 48.

61. **Sellers only hedged; clustered orders near VWAP to cap loss:** never sell naked (a 1–2% single-candle move can wipe out capital; seller margin is far larger than a buyer's); small 1–2-lot sellers are fine only if hedged (Day 9). Multi-lot directional players place several laddered orders near VWAP to keep the stop tight and minimize loss (Day 1/2/11). Confirms §2.11 rule 46.

---

## 2.14 Session-24 Risk Confirmations (Big 5 Anniversary — Live Decoding 21 Days 2025)

Session 24 **confirms** the whole framework above across 21 live-decoded trading days (RR, ≤10–20% per trade / ~10% risk on deployed, deploy smallest first + add nearest VWAP, hard SL, no averaging below VWAP, winning-qty = losing-qty, previous-day profit as the first SL, stop the day at target, loss-symmetry, sell only hedged, 5–10% of total capital in the market). It keeps the daily aim at **~1% per day** (₹25 L → ₹25k/day; ₹5 L → ₹5k/day) then stop and log out. Additions/clarifications:

62. **Single-day hard loss cap (10–12% of trading capital):** never lose beyond **~10–12% of trading capital in one day** — on a ~₹25 L account ~₹3 L is the recoverable bound; a 40–50% draw is account-ending (Day 21). Per-trade still ≤5–10% (10% max) (Day 4/20). Once a fall **breaks VWAP, accept the failure and exit** — averaging below VWAP wipes 70–80% of capital (Day 21). Extends §2.3 / §2.12 rule 49.

63. **Geometric-lot pyramiding into the strongest support (1/2/4/8, up to 16):** when averaging/scaling, step the lots geometrically toward the **strongest support (usually VWAP)** so the heaviest size sits **nearest the stop-loss**; averaging may also be done on the **upside as a base forms**, and the maximum size is concentrated at the area of **maximum support/resistance** (Day 5/8/16/19/20/21). Day 20 reframes this as **planned scaling**, not averaging (e.g. a 20-pt window 26,130 → 26,110, lots 2+1+2, max 5). Once in profit, **hold quantity constant and trail tighter** rather than add. Reinforces §2.11 rule 43 / §2.12 rule 50 / §2.13 rule 56.

64. **Instrument-scaled deep stop-losses (shallow SLs get hunted):** size the point-SL to the instrument's swing — **Nifty ~50–60 pts, Sensex ~200–250 pts** (Sensex initial ~200 pts, tightened as it moves in favour); a tight 5–10-pt SL gets hunted/taken out (Day 1/15/19). Extends the index-scaled point-SL rule of §2.12 rule 53 / §2.13 rule 55.

65. **Size to volatility and to the Trending-OI gap:** low-VIX/IV ⇒ **larger quantity, small point target (~4 pts)**; high-VIX/IV ⇒ **small quantity (~100), larger target (100–200 pts) or stay out** (Day 3); in a dead narrow range, many tiny scalps (+3–4 / −2–3 rupees) beat chasing points (Day 9). Confidence is **conditional on the Trending-OI gap** — wide gap = full size, narrow gap = reduced; a fall **without** volume is low-concern, the first volume-backed (>50–60K) drop = prepare to exit at the final SL (Day 5).

66. **Target scales with capital; overtrading is the killer:** ~**0.5–1%/day at ₹5–10 L**, ~**2%/week at ₹2–5 Cr**, with occasional 3–4% days (Day 16/21); raise quantity gradually (never jump 200 → 1,000–2,000 — a loss far above your appetite forces revenge-trading); afternoon size-creep is the main killer, so **book the morning profit and stop** (Day 13/16). Newbies: 1 lot, RR 1:1 or 1:2, scale up over 6 months–1 year (Day 8/14). Reinforces §2.6 / §2.11 rule 48.

67. **Recycle realized profit, not capital, as the next trade's risk budget:** after booking, re-enter the same strike lower using the **booked / previous-day profit** as the next trade's risk (e.g. risk ₹11k / ₹14k already booked) — the 21-day demo compounded ~₹24.97 L → ~₹28.63 L this way; keep only ~5–10% of total finances in markets and split across multiple accounts to lock psychological profit and cap blow-up (Day 16/17/21). Confirms §2.4 / §2.12 rule 52.

---

## Do's and Don'ts

**Do's**
- Do set the day's profit and loss targets before the first trade, and log out once either is hit.
- Do keep a hard SL in the system on every trade and cut losses fast.
- Do follow 1:2 RR (0.5% risk : 1% reward) and aim for 1-2% daily profit.
- Do split capital across accounts; move to the next account only after the target is met.
- Do make the first trade in each account count — stop that account if its first trade loses.
- Do trade with the trend, only when the setup fits, only with money you can afford to lose.
- Do pre- and post-market analysis and maintain a trade journal.

**Don'ts**
- Don't trade without a stop loss, and don't keep it only in your mind.
- Don't over-trade or revenge-trade; don't trade further once the 0.5% loss cap (and never more than 2-3%/day) is hit.
- Don't average down or add to a position after SL/VWAP/Super trend is breached.
- Don't deploy more than ~20% of capital in one trade or in one day, and don't buy full quantity at once.
- Don't increase size on losing trades (no "HOPE TRADE").
- Don't trade against the trend, in the 11 AM-1 PM sideways window, or when stressed/unconfident.
- Don't trade on borrowed money or get into the habit of re-adding blown-up capital.

---

Source file paths read for this section:
- `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/My Rules My Trade.pdf`
- `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Pre-Mentoring Documents/3. Why Protecting Trading Capital Is Vital In Day Trading.pdf`
- `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf`

---

# 3. Strategies

*Each strategy is self-contained for execution but references the shared indicators, filters and risk rules defined in §2 and §4 by name (to avoid duplication).*

### 3.1 Two Candle Theory (original label: 2 CANDLE THEORY / 2 Candle Theory)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 5 (21st Sep 2023, "2 Canlde Theory LMP23" and "2 Candle Theory With Chess Characters"); also actively traded/demonstrated on Day 1 (06th Sep 2023), Day 2 (07th Sep 2023). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.1.

- **Session 21 update (Live Scalping 2024 — re-taught Day 5 deck + live demos Day 2/3/4/5/6/8/10):** Confirms all Session-20 rules. Refinements: (a) **volume gate** — if the 2nd candle misses 50K (BN) / 125K (N), the 1st + 3rd candles may substitute, but two qualifying candles are still required (Day 10); (b) **stop-loss on a big first candle** — when the 1st candle is very large, for a 3rd-candle entry use the 1st-candle high OR the 2nd-candle low as SL (Day 3); (c) take **only ONE** Supertrend/VWAP rejection (support) trade after a 2-candle move — a second at the same level rarely repeats (Day 5/10/11); (d) a 2-candle formation occurring **together with a Golden Crossover** on the same side is a high-conviction combo to ride the whole trend (Day 5/6/8); (e) for a **positional** ride keep the SL at the 1st-candle low (Day 5); (f) do not enter while RSI is >80 at the 2nd-candle close — wait for cool-off, as a volume-backed cool-off may not revisit the price (Day 5/8); (g) prefer a **full-body** 2nd candle — a big wick means buyers/sellers are arresting the move, so trade smaller and only be aggressive when the Trending-OI directional difference exceeds 50% (Day 4).

- **Session 22 update (re-taught in the "Kingdom Trading Strategy" chess section of the Consolidated Synopsis + live demos across the daily synopses):** Confirms every Session-20/21 rule verbatim (chess/soldier mapping; 3-min TF; futures-vs-VWAP; 2 consecutive candles each ≥50K BN / 125K N; all soldiers on the far side; enter 3rd candle; SL = 1st-candle low/high; ST 10,2 with the 15m/1h 7,3 variant; VWMA(20) = Pawn; VWAP = "the most important indicator"). Refinements: (a) **RSI-40 is the bearish trigger** — if operators defend RSI and do not let it fall below 40, the move fails and you exit at SL; the move comes only once RSI breaks below 40 (Consolidated Synopsis Q15); (b) the volume gate is a **hard threshold** — a 2nd candle at ~47K (vs the 50K gate) with otherwise favourable data did NOT produce the move (Q15); (c) **trail aggressively when RSI nears an extreme** — even after a valid 3rd-candle entry, holding too long with RSI ~25 (bull: ~75–80) can turn the winner into a loss; (d) **WMA→ST→VWAP sizing ladder** for the rejection/support entry: ~25% of deployable funds at the WMA, more at the Supertrend, the **maximum at VWAP** (closest to the SL) (Day 3). No rule deprecated. (Generic S22 option-buying guidance widens the buyer delta to 0.7–0.8/0.9 — see §4.15 — but the live 2-candle demo still used 0.7 delta, so the §3.1 0.6–0.7 baseline is retained.)

- **Purpose & Market Context:** A momentum-breakout scalping strategy for finding breakout trades in Index Futures and Index Options. The scalper's intention is to find one good strategy and keep refining it; one or two good trades a day are enough to set up consistent wins ("win battles, not the war"). One good trade can fulfil the day's 1% target. The setup is framed as a "Kingdom/chess" game where indicators are soldiers: Open Interest = Queen (most important, gives build-up clue), Volume = Weapons, RSI = Territory (boundaries within which you fight), each 3-minute candle = a Battle (green = bulls win, red = bears win), Parabolic SAR = Bishop (first directional indication), Supertrend = Knight (supports SAR/direction), VWMA(20) = Pawn (defends/holds ground, plays both sides), VWAP = Rook (the final level/door; when VWAP and ST are close, buyer and seller averages are equal and neither side wants to give up). The core idea: when momentum, volatility, OI build-up and strength all align on consecutive candles, trade the breakout. The second candle is the key — its structure (during its 3 minutes) tells whether momentum will continue or die. The 3 minutes of the second candle are to be used to analyze all parameters, NOT to rush an entry.

- **Instruments & Timeframes:** Bank Nifty / Nifty Index Futures, or Index (Bank Nifty / Nifty) Call / Put options. Primary chart = 3-minute Futures chart of Bank Nifty (slides also mention "3/5 Min TF"; the LMP23 day-5 manual standardizes on 3-minute TF). Direction read from the index Futures relative to VWAP. (Day-2 demo also referenced RSI on the index; for the matrix RSI checks across 5m/Daily see Filters.)

- **Setup & Preconditions:**
  1. Trade only after 9:45 AM. (Matrix: "are you taking trade after 9.45am.")
  2. Two consecutive candles in the trade direction with volume above threshold: 2 GREEN candles (bullish) or 2 RED candles (bearish), each with volume above 50K (Bank Nifty) / 125K (Nifty). Each 3-min candle must independently meet the volume threshold ("Volume of 2 consecutive bars is above 50K for BN & 125K for Nifty").
  3. The 2nd candle must be strong (a genuine momentum/follow-up candle). Beware: a 50K green candle is NOT enough to confirm continuation if its structure is weak — if the candle's shadow/wick is twice the body, it indicates rejection from higher levels and momentum may not continue (and can reverse). Use the second candle's full 3 minutes to analyze, not to pre-commit.
  4. Index Futures position relative to VWAP must agree with direction: above VWAP for longs, below VWAP for shorts (Rook check).
  5. RSI in the correct band/territory (see Entry Rules) and not overbought/oversold beyond the allowed zone.
  6. OI build-up confirms direction (Long Build-up / Short Covering for longs; Short Build-up / Long Unwinding for shorts) with a HIGH difference in change of OI on one side (vs opposite side). Marginal OI difference = low probability / no-trade.
  7. Trading zone matters: bullish high-probability when at/breaking from Support; bearish high-probability when at/breaking from Resistance. A major resistance nearby (for longs) or major support enroute (for shorts) lowers probability.
  8. Candle position: all indicators (SAR, VWAP, VWMA, Supertrend) must be on the far side of the candles — BELOW the candles for a long, ABOVE the candles for a short.

- **Entry Rules — Bullish (buy Index Futures / buy CALL):** All conditions must be satisfied; enter on the 3rd candle.
  1. Index Futures trading ABOVE the VWAP (Rook).
  2. Open Interest (Queen) shows Long Build-up (better) OR Short Covering in Futures; OI build-up: call OI declining / put OI increasing on the relevant strikes (matrix bullish OI line); difference in change of OI on one side should be HIGH.
  3. RSI (Territory) ABOVE 50 and between 50–75 (slides also allow up to 80: "50-75/80"); RSI not overbought — if it has just cooled off / is in 50–75 it is fine. If RSI is just below 80, be very cautious and do NOT go heavy on quantity (market can dip as RSI cools).
  4. Volume (Weapons) of 2 consecutive bars above 50K (Bank Nifty) / 125K (Nifty); the 2 candles are GREEN.
  5. 2nd candle is strong AND ALL soldiers (Bishop = PSAR, Pawn = VWMA, Knight = Supertrend, Rook = VWAP) are BELOW the candles/price.
  6. Strike selection (shared buyer guidance — see §4.9; the ATM±3 + premium-band figures live in the O=H/O=L matrix column, the Two-Candle-specific cue is the delta): index Call slightly ITM with delta 0.6–0.7 (0.7 preferred for max benefit per 1-point move); ATM +/- 3 strikes; premium 100–250 for Nifty, 250–400 for Bank Nifty.
  7. Deploy in the 3rd candle ("deploy your resources in 3rd candle"). Entry may also be taken on the 3rd candle when the market is still inching in the trade direction and Futures OI confirms the build-up (Day-1 note).
  8. After a 2-candle formation, expect one round of pull-back/support when price returns to the WMA/Supertrend level — can be used to add or re-enter (see Exit/scaling and Risk).

- **Entry Rules — Bearish (sell Index Futures / buy PUT):** Mirror of bullish; all conditions must be satisfied; enter on the 3rd candle.
  1. Index Futures trading BELOW the VWAP (Rook).
  2. Open Interest (Queen) shows Short Build-up (better) OR Long Unwinding in Futures; OI build-up: Call OI increasing / Put OI declining on the relevant strikes; difference in change of OI on one side should be HIGH.
  3. RSI (Territory) BELOW 40 and between 40–25/20; RSI not oversold beyond ~25/20 (between 40–25 preferable). CAUTION: if RSI is already below ~20–25 (oversold), the 2-candle trade may NOT be taken because a bounce can occur from oversold territory (Day-5 live note: 2-candle theory trade skipped because RSI below 20; a Supertrend-rejection reversal trade was preferred instead).
  4. Volume (Weapons) of 2 consecutive bars above 50K (Bank Nifty) / 125K (Nifty); the 2 candles are RED.
  5. 2nd candle is strong AND ALL soldiers (PSAR, VWMA, Supertrend, VWAP) are ABOVE the candles/price.
  6. Strike selection (shared buyer guidance — see §4.9): index Put slightly ITM with delta 0.6–0.7; ATM +/- 3 strikes; premium 100–250 for Nifty, 250–400 for Bank Nifty.
  7. Deploy in the 3rd candle. Entry may also be taken on the 3rd candle when the market is inching downwards and Futures OI shows a Short Build-up (Day-1 demo: 44500 PE).

- **Exit Rules:**
  - **Stop-loss (primary):** For LONG = 1st candle LOW; for SHORT = 1st candle HIGH ("with 1st candle low/high as SL"; "if 1st candle low/high is hit, your fort is breached, time to pack off"). Matrix: "1st candle low is the stop loss" (bullish) / "1st candle high is the stop loss" (bearish). Day-2 example: 44709 first-candle low used as SL.
  - **Stop-loss (alternate, when market has already moved before entry):** Use VWAP as the SL instead of the first-candle level when the market has already fallen/risen substantially before entry; VWAP is the most crucial level for the defending side (sellers defend below VWAP for shorts) (Day-1 note).
  - **Target:** Ride the momentum / next resistance (long) or support (short). Aim 1–2% (matrix "Aim not more than 1-2%"); one good trade can fulfil the day's 1% target. Conservative quick-scalp option: exit at the first sign of momentum dying.
  - **Trailing / scaling-out:** Trail SL to profit zones and ride momentum. The example progression (12th Apr 2022 high-probability trade) shows three trailing exits: conservative (~14 min after entry), then trailing on Parabolic SAR, then trailing on Supertrend for the longest hold. Bearish 7th Apr 2022 example: conservative exit at ~6 min, aggressive exit ~33 min later. Trail SL ~5 points below a reference level when using gap/level trails (matrix cross-ref).
  - **Time/structure exit:** Quick scalp must be managed at least to the VWAP level; if VWAP breaks WITH volume the trend is reversing — exit. A fake breakout (above/below VWAP without volume, no follow-up) signals reversal back into VWAP territory. On a trend day, new 2-candle moves recur after ~45 min to 1 hour from the previous high/low.

- **Strategy-Specific Risk Management:** (Refer to the Global Risk Framework for shared sizing/daily-cap rules; strategy-specific points below.)
  - Art of averaging — deploy small initially, average ONLY at known levels: e.g. 3% of capital deployed initially, add 7% more when price reaches VWAP or WMA; total exposure for this kind of setup no more than ~20% of capital in this range (Day-1 note).
  - Average (add to position) ONLY at the Supertrend level, or at VWAP/VWMA where the defending side holds. Do NOT average if your stop-loss levels are breached (Day-1).
  - Do not go heavy on quantity when RSI is near the overbought boundary (just below 80) — market can dip as RSI cools (Day-2).
  - One or two good trades per day are the goal; do not over-trade. Position size scaled to account size and risk capability.

- **Filters & Conditions:** (Reference Common Components — VWAP, Supertrend (10,2), VWMA(20), Parabolic SAR (0.02, 0.2), RSI 14, OI interpretation, Connecting-the-Dots — by name.)
  - **Time-of-day:** Trade only after 9:45 AM. Two-candle moves that play out after 10 AM frequently set up follow-on Supertrend-rejection / VWAP-rejection reversal trades. Avoid taking trades during the dead/sideways midday range (Common Components time filter).
  - **RSI bands (matrix detail):** Bullish — RSI not overbought, 50–75 (allow it to cool off); RSI(5m) below 75/80; RSI(Daily) below 75. Bearish — RSI not oversold, 40–25 preferable; RSI(5m) above 25/20; RSI(Daily) above 25. No-trade zone roughly RSI 40–60.
  - **OI / Trending OI:** Confirm Long Build-up or Short Covering (bull) / Short Build-up or Long Unwinding (bear) on the Futures chart; check Trending OI cross-over and whether the OI gap is widening; volume and price action must confirm the OI action; the difference in change of OI must be substantial (HIGH) on one side, opposite on the other (marginal = no trade).
  - **VIX:** Bullish — VIX going DOWN is supportive; Bearish — VIX going UP is supportive. (Day-5 caution: VIX behaving abnormally — falling while market falls — is a warning sign.)
  - **Market structure:** Avoid chasing a vertical/parabolic up move (a parabolic up move can reverse into a parabolic down move). Remain cautious when the index has not crossed the previous swing resistance. Major resistance nearby (long) or major support enroute (short) = low probability.
  - **Global cues / Connecting the Dots:** Take cues from Dow futures (mother market — prev close + live), Asian indices (Nikkei, Shanghai, Hang Seng), European indices (DAX, CAC, FTSE), India VIX, Crude, US Dollar Index (above 105 negative for emerging markets; under 90 ideal), Bond yields & USD/INR, Events & News. Use Connecting-the-Dots (60-min view for broad bias; smaller TF for entries) to confirm bullish/bearish alignment.
  - **Strike/option filters:** delta 0.6–0.7 (0.7 preferred), ATM +/- 3 strikes, premium 100–250 Nifty / 250–400 Bank Nifty, slightly ITM; check IV rising (bull) or falling (bear) on the chosen strike (Desirables).
  - **Desirables (matrix):** any Supertrend & VWMA cross-over and SAR switching aligning with direction; any breakout from a support/resistance line confirming direction; IV rising (bull) / falling (bear) in the strike.

- **Execution Notes & Edge Cases:**
  - **2nd-candle structure trap:** A second candle that has volume but a long shadow (wick ≥ 2x body) = rejection from higher levels; momentum likely dies or reverses — skip even if volume is met (Day-5 manual).
  - **Oversold/overbought skip:** Do not take the bearish 2-candle trade when RSI is already below ~20 (oversold) — bounce risk; prefer the subsequent Supertrend-rejection reversal trade. Mirror caution for longs near RSI 80 (Day-5, Day-2 live notes).
  - **Entry on 3rd candle still valid:** Even if the 2-candle formation completes a little late, entry on the 3rd candle is acceptable when price is still inching in the trade direction and Futures OI confirms the build-up (Day-1).
  - **Already-extended move:** If the market has already moved far before your entry, switch SL to VWAP (the defenders' level) rather than the 1st-candle level (Day-1).
  - **Expiry / premium behaviour:** On expiry, premium-management moves can fake out direction; manage the scalp to VWAP and watch for fake breakouts without volume (Day-5).
  - **Trend day:** Expect repeated 2-candle setups at ~45-min to 1-hour intervals as new highs/lows form with volume; can be played positionally if the trend persists (but watch EOD OI — Desirables).

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Canlde Theory LMP23.pdf (Session 20, Day 5) — examples & parameter checklist; original slide PDF: StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Canlde Theory LMP23.pdf
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Candle Theory_With Chess Characters.pdf (Session 20, Day 5) — chess/kingdom framework & long/short entry rules; original slide PDF: StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Candle Theory_With Chess Characters.pdf
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/Day 05_LMP23 21st Sep 2023.pdf (Session 20, Day 5) — full manual write-up & live application
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 1/Day 01_LMP23 06th Sep 2023.pdf (Session 20, Day 1) — live demo, averaging, VWAP SL
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Day 02_LMP23 07th Sep 2023.pdf (Session 20, Day 2) — live demo, RSI caution, 1st-candle-low SL
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (Session 20, master checklist matrix — "2 CANDLE" column: Bullish, Bearish, Desirables blocks)

---

### 3.2 Open = High / Open = Low (O=H / O=L) (original label: OPEN & HIGH — Strategy In Index Options & Futures)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 12 (LMP23, 5th Oct 2023). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.2.

- **Session 21 update (re-taught Day 9 deck + live Day 7/8/10/11):** Confirms Session-20 rules (90%-probability + badge gate; ATM ±3; reject on >50% premium fall or >50% OI change). Refinements: (a) it is a **quick momentum scalp** — target ~40–50 points then end the day (Day 11); set the target **2–5 points below the OH** level (Day 7); (b) if the move continues, **trail the SL upward from the OH number** (Day 7); (c) require the **Q1 quadrant** fulfilled and candles above ALL indicators for a CE (below all for a PE); chase OH on the call side only when candles are above the indicators, with extra confidence when the put-side OL sits at the ATM and other puts are OTM (Day 10/11); (d) **skip OH/OL on a strong trend day** unless call prices have already fallen >50% (Day 8).

- **Session 22 update (reused Day-9 deck — identical 2022–23 examples — + Day-9 synopsis + live trades in the Consolidated Synopsis):** Confirms every baseline rule (≥3 strikes above/below ATM match; ATM/ITM only; 90%+badge gate; reject on >50% premium fall / >50% OI change; ≤30% capital; adverse >50K-volume = exit; never target above the OH; same-day/next-day). **Resolves the §7 premium-band open question:** the **wider daily-note bands are operative** — Bank Nifty **250–550** (avoid >600 / <200), Nifty **150–350** (avoid <130 / >380); buyers look near-ATM, sellers OTM, around 250–400. Refinements: (a) **~90% of the time the OH is hit before 10:30 AM** — if not hit by then it becomes a low-probability setup (players may instead revive it in the 2nd half); (b) **skip the day** when probability/price is low and an ATM strike would need ~100% premium movement to reach the OH; (c) a **Put-side OH** needs only a significant **Call OI ↑ + Put OI ↓** (data need not be fully bearish) **and all indicators above the candles** (price below VWAP); a **Call-side OH** is high-probability only while price trades **above VWAP**; (d) on a live OH momentum scalp the **VWAP is the stop-loss**; (e) a bullish-side OH not getting hit does **not** mean its creators are losing — they can average at lower levels and exit near breakeven without revisiting the OH, so the level may simply not be hit (Q10). No rule deprecated.

- **Purpose & Market Context:** Intraday options-scalping setup applicable purely to index Futures & Options (Bank Nifty / Nifty). It reads big-players' game plan for the day: when a large financial institution deploys capital with a bullish (or bearish) outlook, it pushes the underlying up (down) and the strikes it bought/wrote open at their high (low) — Open=High on Calls / Open=Low on Puts for a bullish play, and the mirror for a bearish play. The trade assumes the institution will use its remaining capital to drag the option premium back to that opening High level (or push the Put premium back to its Low), so the open extreme tends to be revisited the same day, or occasionally the next day (most often the same day). Purely discretionary.

- **Instruments & Timeframes:** Index Futures & Options only — Bank Nifty (primary, examples use BN) and Nifty. No stock options for the option leg (cash/futures may be used for an underlying leg). Primary chart: 3-min Bank Nifty Futures chart for direction; option strike charts for OH/OL identification; daily RSI as a secondary band. Positions can be taken in both Futures and Options simultaneously.

- **Setup & Preconditions:**
  1. Identify Open=High forming on the Futures AND simultaneously on Call-side strikes (bullish), and ideally Open=Low forming on the Put side at the same time — this combination (OH on Futures + OH on Calls + OL on Puts) is the highest-probability configuration.
  2. Require confluence: at least 3 strikes above and below the ATM showing OH matching, together with a matching OH on the Futures. (Rare "very high probability": 4-5 strikes continuously formed around the ATM strike with OH on Futures; "highest probability": the above plus OL also forming on the Put side.)
  3. Restrict to ATM and ITM strikes (where liquidity/volume exists). Use only strikes within ATM +/- 3. Avoid OTM and deep ITM — no liquidity means you cannot make money or get an exit.
  4. Premium of identified strikes must NOT have fallen more than 50% from the previous day's close (for OH-on-CE bullish; mirror: PE premium must not have risen >50% from previous close).
  5. Change in OI for the identified strike must NOT have increased more than 50% (an OI jump >50% signals a bigger player has taken the opposite view).
  6. Take the trade only when there is momentum (price/volume/RSI in agreement). Probability is higher in the 1st half of the session; avoid the 2nd half due to time-value/premium erosion.
  7. Trend alignment is a strong precondition: O=H on the Call side in a bullish market (and O=L on Put side) is high-probability; O=H on the side opposite the market trend produces a sideways, low-probability day where players fight to drag price to the OH.
  8. Confirm with Trending OI (5-15 min) showing traces of positions being created across all aspects; confirm direction with OI Pulse (OIP) AI badge.

- **Entry Rules — Bullish:**
  1. Trade in the ideal 9:15-10:00 window (the O=H/O=L grid Desirable; the general "after 9:45am" gate is a cross-strategy guideline, not an O=H-specific rule). Confirm OH on Bank Nifty Futures (3-min) with Long Build-up (preferred) or Short Covering.
  2. Confirm substantial OH on Call-side strikes AND substantial OL on Put-side strikes; require ≥3 strikes above/below ATM matching (use strikes within ATM +/- 3 only).
  3. Require OI Pulse probability 90% and above WITH a badge (red dot preferable). Do NOT chase when probability is below 90% (e.g., 20-30% or 60% setups get caught in volatility / may not get hit).
  4. Enter only when momentum is up: price rising with volume, RSI > 50 and moving above 50 (RSI(5m) below 75/80; RSI(D) below 75 — not yet overbought). All indicators (VWAP, Supertrend 10,2, VWMA) should have moved below price.
  5. Strike selection: choose a strike whose premium is nearest to its target; for buys prefer 0.6-0.7 delta. Premium ranges — Nifty 100-250, Bank Nifty 250-400. Daily-note refinement: chase OH in ATM + 3 strikes on either side; Bank Nifty 250-550 premium (avoid above 600 / below 200); Nifty 150-350 (avoid below 130 / above 380).
  6. Verify identified-strike premium has not fallen >50% from previous close and change in OI on that strike has not increased >50%; verify OI build-up (Call OI declining / Put OI increasing) and VIX going down.
  7. Make small scalp entries on a confirmed breakout with volume. Example: OH = 300, current price 200; once it moves to 250 and the chart breaks out with volume, enter @ 250 and exit @ 290 (the slide's worked example; note 290 is ~10 pts below the OH of 300 — the separate grid rule "exit ~5 pts below the OH" would put the exit near 295).

- **Entry Rules — Bearish:** (mirror; the source's O=L column gives the explicit short variant)
  1. Trade in the ideal 9:15-10:00 window (O=H/O=L grid Desirable; "after 9:45am" is a cross-strategy guideline). Confirm OL on Bank Nifty Futures (3-min) with Short Build-up (preferred) or Long Unwinding.
  2. Confirm substantial OL on Call-side strikes AND substantial OH on Put-side strikes (i.e., the Put is the leg being played); require ≥3 strikes above/below ATM matching (ATM +/- 3).
  3. Require OI Pulse probability 90%+ WITH a badge (red dot preferable). Do not chase below 90%.
  4. Enter only when momentum is down: RSI < 50 and moving below 50 (RSI(5m) above 25/20; RSI(D) above 25 — not yet oversold). All indicators should have moved above price.
  5. Strike selection: premium Nifty 100-250 / Bank Nifty 250-400 (O=H/O=L grid); delta 0.6-0.7 for buys (cross-strategy generic buyer-delta — the O=H grid column itself carries no delta row). Verify the Put premium has NOT increased >50% from previous close and the identified-strike change in OI has NOT decreased >50%.
  6. Verify OI build-up (Call OI increasing / Put OI declining) and VIX going up. Enter on confirmed breakdown with volume; exit 5 points above the Open=Low.

- **Exit Rules:**
  - Target: aim for small scalps, 30-50 points "good enough" (the O=H/O=L grid target row); the high-level target is the Open=High itself but NEVER place the target above the OH (bullish) — exit ~5 points below the OH. For bearish, target the Open=Low and exit ~5 points above it. (Note: the "aim not more than 1-2%" figure is from the Market Movers grid column, not the O=H/O=L column.)
  - Choose the strike whose premium is nearest to its target.
  - Scaling / trailing: ALWAYS trail the stop once in profit. Never let the target sit beyond the OH/OL extreme.
  - Time exit: probability is in the 1st half; avoid initiating in the 2nd half (time-value/premium erosion). Scalp only — close the trade once target/SL is hit.
  - Abort/avoid rule: exit/avoid the setup if (a) option premium decreases by more than 50% (bullish CE) AND/OR (b) change in OI for the identified strike increases by more than 50% — this means a bigger player has taken the opposite view.

- **Strategy-Specific Risk Management:** These are highly risky trades — never deploy more than 30% of capital on this trade (see Global Risk Framework for shared sizing/daily-cap rules). Even if price moves against you, it should not do so on candles with volume > 50K (Bank Nifty; 125K Nifty) — a high-volume adverse move is an exit signal; a low-volume drift may be tolerated. Trail once in profit. Avoid OTM/deep-ITM strikes to ensure you can always exit. (This is an intraday-scalp-only setup — the grid's "1 night risk / avoid Friday" and "SL 50% close at 3:20pm" rows belong to the BTST and Hero-Zero columns, not O=H/O=L.)

- **Filters & Conditions:**
  - Time-of-day: ideal window 9:15-10:00 (O=H/O=L grid Desirable); the general "after 9:45am" gate is a cross-strategy guideline; favor 1st half, avoid 2nd half. (See Common Components — Time Filters.)
  - Volume: confirming/adverse volume threshold 50K Bank Nifty, 125K Nifty (Volume candle threshold).
  - RSI: bullish RSI > 50 (RSI(5m) < 75/80, RSI(D) < 75); bearish RSI < 50 (RSI(5m) > 25/20, RSI(D) > 25). (See Common Components — RSI 14.)
  - OI: confirm via Trending OI (5-15 min) and Sentiment graph; bullish build-up Call OI declining / Put OI increasing; OI Pulse AI badge ≥90%. Reject if change in OI on the strike crosses the 50% barrier. (See Common Components — OI interpretation, Trending OI, OI Pulse.)
  - Premium-change filter: identified strike premium must not fall (bullish CE) / rise (bearish PE) more than 50% from previous close.
  - VIX (cross-strategy generic — not in the O=H/O=L grid column): supportive when VIX falls for longs / rises for shorts.
  - Strike scope: ATM +/- 3 only; delta 0.6-0.7 for buys.
  - Desirables (O=H/O=L grid column): after 9:15 watch the Futures chart for the open high/low; 9:15-10:00 is the ideal trade window; option strikes liberally populated on both Call & Put = avoid (no clear directional intent); if a trade goes against you check volume (high → exit, low → may pursue). Cross-strategy generics (from the 2-Candle Desirables, usable here): ST & VWMA cross-over with SAR (0.02, 0.2) switching; breakout from S/R confirming direction; IV rising (bull) / falling (bear) in that strike.

- **Execution Notes & Edge Cases:**
  - Two-sided OH/OL: when both OH and OL appear on BOTH Call and Put sides, expect a sideways market (two big players with opposing positions, mild probability on both sides). Both extremes get hit only if OH is hit on one side while the other side has not fallen >50%; if one side keeps making new highs, the other may not hit because its price has already fallen >50%.
  - >50% rule timing (OI Spurts): if the more-than-50% price-increase criterion is already fulfilled, wait until it comes back down so momentum can rebuild (visible in Trending OI) before acting.
  - Probability tiers from FNO data: OH on Futures + OH on Calls + OL on Puts = HIGH; few Calls OH + few Puts OL = MILD; Puts OH + Calls OL = HIGH (bearish); few Puts OH + few Calls OL = MILD; Calls OH AND Puts OH together = MILD on both sides.
  - Subsequent price/volume read: Call OH falls on <50K volume → probability INCREASES; Call OH falls on >50K → DECREASES; Call OH flat / flat volume → INCREASES (mirror for Put OH rises).
  - Liquidity: avoid deep ITM and deep OTM — they may not get hit or may not give an exit.
  - Do not jump straight to buying on seeing OH on CE/PE — always time entry to confirmed probability/momentum.

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Open _ High Strategy - Index Options _ Futures.pdf (Session 20, Day 12 — slide deck)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Day 12_LMP23 5th Oct 2023.pdf (Session 20, Day 12 live mentoring notes, 5th Oct 2023)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (master checklist matrix — "O=H / O=L" column block, Bullish / Bearish / Desirables)
  - Original slide PDF: StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Open _ High Strategy - Index Options _ Futures.pdf

---

### 3.3 Market Movers (original label: Market Movers Strategy)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 12. Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.3.

- **Session 21 update (re-taught live Day 12; + pre-doc "Index Trading Made Easy"; no standalone deck in S21):** Confirms Session-20 rules (long = 8/9-day high + Long Build-up/Short Covering + daily RSI < 75; short = 8/9-day low + Short Build-up (best)/Long Unwinding + daily RSI 40–30, not < 30). Refinements: (a) the mover's move normally happens **by 9:45 AM** and typically gives **~1% at the open** followed by a Supertrend support trade — target a quick ~1% (Day 12); (b) **Open=Low** printed next to the long-side OI (**Open=High** for shorts) is an added advantage; prefer 8/9-day-high names with Open=Low and a positive Futures open, after reviewing the past **2–3 days' EOD** data (Day 12); (c) read direction from the **top constituents** — Bank Nifty's top 3 carry ~60% of the index weight (pre-doc *Index Trading Made Easy*); estimate a stock's index impact from its weightage (e.g. HDFC Bank = 29.46% of Nifty Bank, Day 10) — see §4.14.

- **Session 22 update (reused Day-8 deck — identical 2022–23 example cards — + Day-8 synopsis + live trades in the Consolidated Synopsis):** Confirms the baseline (8/9-day high + OL + LB long; 8/9-day low + OH + SB short; long entry after price > VWAP; high-volume names; daily-RSI cool-off). Refinements: (a) **large-cap-only filter** — explicitly avoid small/mid-cap (operator-driven); trade only large-caps that trade in volume; (b) **operator low-volume trap** — a name that gives its whole move in the morning on no intraday volume then ranges; late entrants gain nothing; stay long only while price is **above the intraday VWAP**; (c) observed moves can far exceed the 1–2% objective — **minimum ~1% on the breakout side intraday, with 5–10%+ on some days**; (d) **next-day continuation edge** — a stock at an 8/9-day high can run further the next day; enter carefully, checking daily RSI and RSI at open (Q23); (e) **15-day-extreme variant** — a 15-day-low name with completely bearish data is a strong short/STBT candidate (HAL/REC examples); (f) **short-side overnight (STBT)** is explicit: an SB stock breaking an **8/9-day low** is an ideal STBT, carried only if Futures OI is **closing at the day's high and price at the day's low**. Daily-RSI screen restated as 75 (bull) / 40 (bear) — note an internal S22 inconsistency (a tool summary states 70/30); §4.15 keeps 75/40 as the stock screen. No rule deprecated.

- **Purpose & Market Context:** A momentum/breakout strategy that trades individual F&O stocks (not the index) that are leading the day's move. It uses the OI Pulse "Market Movers" screener, which segregates Top Gainers and Top Losers for the day in F&O stocks (and can be filtered to Nifty 50 / Nifty Bank constituents). The core thesis: when a stock breaks an 8-day or 9-day high (long) or 8-day/9-day low (short), it tends to continue, and you can extract a 1-2% directional move (often a 1% move in the first morning hour). The screener surfaces Script name, LTP, LTP change %, OI% change, OH/OL flag, OI Interpretation and — most importantly — the minimum number of breakout days (Min. B.O. Days).

- **Instruments & Timeframes:**
  - **Instruments:** Cash-market stock or stock futures. Per the master matrix Bullish block: "Buy futures or stock in cash market (no stock options)"; Bearish block: "Sell futures or sell stocks." No stock options.
  - **Direction/confirmation tool:** OI Pulse Market Movers screener + Futures OI Analysis table + the stock's price chart.
  - **Chart timeframe:** The worked-example cards (ADANIENT, ZEEL, ABCAPITAL) show price relative to VWAP, SuperTrend and WMA, but the Market Movers source does NOT state an explicit chart timeframe or indicator parameters. Indicator settings (VWMA 20, SuperTrend 10,2, RSI 14, Volume 20) are cross-references to Common Components, not values stated in these examples. RSI confirmation is cited on 5m and Daily. UNCERTAIN — needs confirmation: the primary chart timeframe (the global glossary lists 3m as the primary scalping TF; the Market Movers matrix only states "Take trade after 9.45am").

- **Setup & Preconditions:**
  1. Open OI Pulse > Market Movers. Set Mode = Live data, Asset = All F&O Stocks (or filter Nifty 50 / Nifty Bank), Expiry = Current Month.
  2. Scan the **Top Gainers** section for long candidates and the **Top Losers** section for short candidates. Read each row's columns: Min. B.O. Days, OH/OL flag, OI% change, LTP change %, and OI Interpretation.
  3. Require the stock to be at a **minimum 8-day high** (8D or 9D High) for longs, or **minimum 8-day low** (8D or 9D Low) for shorts.
  4. Prefer **high-volume stocks** for liquidity (easy entry/exit).
  5. Cross-check the stock's chart to confirm price action, and check the Futures OI Analysis table for the OI interpretation.
  6. Daily-RSI filter (from daily notes): on the Daily timeframe the stock should NOT have crossed RSI 75 on the bullish side, nor be below RSI 40 on the bearish side, because RSI tends to cool off / reverse from there. Check Daily RSI to gauge whether the desired move is still available, and intraday RSI to decide entry vs overbought/oversold.
  7. Radar-building (from post-mentoring notes): when a stock first reaches a 1- or 2-day high/low, add it to the radar and watch price action; momentum confirmation comes as it advances to a 3-4 day high with OL (bullish) or OH (bearish), and full conviction at the 8-9 day breakout.

- **Entry Rules — Bullish:**
  1. Trade only **after 9:45am** (Market Movers matrix). Market Movers Desirable: by 9:45 many moves may already have happened — let risk/reward stay at ~1%. (The "9:15-10:00 ideal window" is the O=H/O=L Desirable, not Market Movers.)
  2. Stock must be at **8D or 9D High** (high probability requires this).
  3. **High-probability alignment — require all 3:** (a) Min. days >= 8 (8-day high), (b) **OL (Open = Low)** flag on the Top Gainers side, and (c) **Long Build-up (LB)** in OI interpretation (Long Build-up is best; Short Covering also acceptable). Rationale: stock already at 8-day high; OL means the day's low is not being tested; LB means bulls are in charge — probability of a 1% move is higher.
  4. Confirm on the chart: **RSI(5m) below 75/80 and RSI(Daily) below 75** (matrix); the example cards cite RSI above 60. Volume and price action must confirm the OI action.
  5. Entry trigger: take the trade on a **pullback near VWMA / SuperTrend (10,2) / VWAP**, entering a **long after price moves above VWAP** (trade execution wording used in all three long examples). Alternatively (daily/post-mentoring note) enter when there is considerable change in OI and more than 1% change in price, or take support/resistance trades during intraday.
  6. Scalping cue (daily notes): an **Open & Low in Top Gainers combined with an 8D/9D high** is a strong opening; conviction is highest when there is simultaneously an Open & Low in Top Gainers and an Open & High in Top Losers.

- **Entry Rules — Bearish:**
  1. Trade only **after 9:45am** (Market Movers matrix).
  2. Stock must be at **8D or 9D Low** (high probability requires this); check the Short Build-up category / Top Losers section.
  3. **High-probability alignment — require all 3:** (a) Min. days >= 8 (8-day low), (b) **OH (Open = High)** flag on the Top Losers side, and (c) **Short Build-up (SB)** in OI interpretation (SB is best; Long Unwinding also acceptable). Rationale: stock already at 8-day low; OH means the day's high is not being tested; SB means bears are in charge — probability of a 1% move is higher.
  4. Confirm on the chart: **RSI(5m) above 25/20 and RSI(Daily) above 25** (matrix). Volume and price action must confirm the OI action.
  5. Entry trigger: take the trade on a **pullback near VWMA / SuperTrend / VWAP**; enter the short as price moves below VWAP (mirror of the long execution). Alternatively enter on considerable OI change + >1% price change, or take resistance/rejection trades intraday.
  6. Scalping cue: an **Open & High in Top Losers combined with an 8D/9D low** is a very weak opening; highest conviction when paired with an Open & Low in Top Gainers on a separate name.

- **Exit Rules:**
  - **Target:** Aim for **1-2%** on the stock ("Aim not more than 1-2%" per the Market Movers matrix); ~1% is the typical objective on an 8/9-day high/low breakout. (The example cards — ADANIENT, ZEEL, ABCAPITAL — are tagged only by probability, e.g. ZEEL/ABCAPITAL "High (Intraday only)"; the source gives no per-example %-move or time-span figures.)
  - **Stop-loss:** No rigid SL — stocks are dynamic and can be manipulated (unlike the index), so there is no fixed OI% threshold either. Set SL by your own risk management / risk appetite. Practical reference from the broader 2-candle framework cited in the matrix: 1st candle low (long) / 1st candle high (short) can serve as SL.
  - **Time / hold:** Intraday by default (ZEEL and ABCAPITAL examples are tagged "High (Intraday only)").
  - **Positional / overnight:** Can be used positionally because the trend may persist — but **watch EOD OI** (Desirable: "can be used positional also, as trend will stay for a while, but watch EOD OI"). Generic rule: prefer carrying only when the close shows **Long Build-up**; avoid carrying through **Long Unwinding**. (Note: the ADANIENT/ZEEL/ABCAPITAL example cards are tagged "High (Intraday only)" — the source states no overnight result for any of them.)
  - **Scaling:** Not specified in sources.

- **Strategy-Specific Risk Management:**
  - No fixed/rigid stop-loss and no set OI%-change threshold for stocks (stocks can be manipulated, unlike the index); size and SL per your own risk appetite. Otherwise defer to the Global Risk Framework for position sizing, daily loss cap, and night-risk limits (e.g., not more than 1 night risk, avoid Friday).
  - Prefer high-volume / liquid names so entries and exits are clean.

- **Filters & Conditions:**
  - **Time-of-day:** Trade after 9:45am (Market Movers matrix; the "9:15-10:00 ideal" window is the O=H/O=L Desirable, not Market Movers). See Common Components — Time Filters.
  - **Breakout-days filter:** minimum 8-day high (long) / 8-day low (short); 9-day even better.
  - **OH/OL filter:** OL for bullish, OH for bearish (Open=Low / Open=High flags in the screener).
  - **OI Interpretation filter:** Long Build-up (best) or Short Covering for longs; Short Build-up (best) or Long Unwinding for shorts (see Common Components — OI interpretation: LB/SC/SB/LU). Watch EOD OI before any overnight hold.
  - **RSI bands:** RSI(5m) below 75/80 and RSI(Daily) below 75 for longs; RSI(5m) above 25/20 and RSI(Daily) above 25 for shorts. Daily-note tightening: do not be already past RSI 75 (bull) or below RSI 40 (bear) on the daily.
  - **Volume:** high-volume stocks for liquidity; volume must confirm price/OI action (see Common Components — Volume thresholds, though those 50K/125K candle thresholds are index-specific).
  - **Indicators (Common Components):** VWAP, VWMA 20, SuperTrend (10,2), RSI 14, Volume 20, Open Interest. Desirables also reference ST & VWMA cross-over with SAR switching, breakouts from support/resistance lines, and IV rising (bull) / falling (bear) on the relevant strike.
  - **Screener structure note:** the right-side "New High/Low Maker" panel shows live new intraday highs/lows; use it for support (bullish) or rejection (bearish) trades based on whether the name appears in Gainers or Losers. Also refer the OI Spurt (4 quadrants) for the stock for an additional cue.

- **Execution Notes & Edge Cases:**
  - **Trade execution wording (all long examples):** "long entry after price moves above VWAP"; combined with pullback entries near VWMA/ST/VWAP.
  - **Overnight caveat:** carry only when closing OI = Long Build-up (long) and avoid carrying through Long Unwinding (generic rule; not tied to a specific example).
  - **By ~9:45 the move may be done** — accept a ~1% RR rather than chasing; do not force entries late.
  - **If the trade goes against you, check volume:** if volume is high, exit; if volume is low, you may pursue (Desirables).
  - **Avoid names with liberally populated OI on both call and put sides** (ambiguous positioning) — Desirables (this note appears in the O=H/O=L column but applies to picking clean names).
  - **Stocks can be manipulated** — there is no reliable fixed OI% trigger; rely on price action + volume confirmation.

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Market Movers Strategy.pdf (Session 20, Day 12 — slide text)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Market Movers Strategy.pdf (Session 20, Day 12 — slides + chart annotations: ADANIENT, ZEEL, ABCAPITAL)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx ("Market Movers" column block — Bullish / Bearish / Desirables)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Day 12_LMP23 5th Oct 2023.pdf (Session 20, Day 12 live mentoring — 5th Oct 2023 derived notes)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf (Session 20 post-mentoring notes — radar building, entry/risk)

---

### 3.4 Gap Theory (original label: GAP / Gap Theory)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 6 (Gap Theory 10th Mentoring; reinforced in Day 6 live session 22nd Sep 2023, Day 9 28th Sep, Day 10 3rd Oct, Day 11 4th Oct). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.4. Further re-taught/refined in Sessions 23–24 (logged in §5.4); the Session-24 Day-21 timeframe/SL refinement is folded into Exit Rules → Time-exit below.

- **Session 21 update (re-taught Day 6 deck + live Day 2/6):** Confirms Session-20 rules (3-min Bank Nifty gap, ~90% fill, wait for the fill then trade in the trend direction; runaway gaps may not fill; never trade INTO the fill). Refinement — **player-size entry timing** (Day 2): a one-lot player must wait for the gap to **fully** fill before entering (an early entry risks a big loss); a multi-lot player may take small quantity as price heads toward the gap and add as it nears the Supertrend. Small players exit longs if price falls below the Supertrend.

- **Session 22 update (reused Day-7 deck — identical 9-Jan-23 & 31-Jan-23 worked examples — + Day-7 synopsis + Consolidated Synopsis Q11):** **Confirms** the baseline with no new execution rule (3-min BN gap, ~90% fill, wait-for-fill then trade the prevailing trend, runaway gaps may not fill, SL = Supertrend, 1:2.5 / 1:1.6–1.7 targets). Q11 only restates the order-mechanics ("orders are predefined; players bring price back to fill them, then continue with the trend"). No new threshold, no rule deprecated.

- **Purpose & Market Context:** A short-term scalping setup that exploits intraday price gaps on the 3-minute Bank Nifty Futures chart. A "gap" is the void between the previous 3-min candle's close/high/low and the current candle's open, created when a burst of unfilled orders pushes price quickly away. Such 3-min Futures gaps are filled ~90% of the time on the same day or the following day, after which the market typically resumes its prior short-term journey. The edge is to wait for the gap to fill and then trade in the direction of the overall/prevailing trend. Gaps are NOT filled when they are runaway gaps or when momentum on the opposite side is strong. Note: this logic applies only to intraday 3-min Futures gaps — it does NOT transfer to higher timeframes (daily gaps can be runaway gaps that may not fill), and option-price gaps may or may not fill (theta decay / lack of retrace), so do not rely on option gaps closing.

- **Instruments & Timeframes:** Primary chart: Bank Nifty Futures (current month), 3-minute timeframe ("YOU ARE IN 3 MTS FUTURE CHART BANK NIFTY"). Direction is read on the 3-min Futures chart; the executed trade is taken as a CE/PE option buy (scalp) or as Futures/cash (the matrix lists "Buy futures or stock in cash market — no stock options" / "Sell futures or sell stocks" under the Market Movers analog, but the Gap deck examples are CE/PE scalps). Bank Nifty premium band 250-400; Nifty 100-250 (per Common Components premium ranges). Per daily notes, gap-up handling also applies to Nifty (look for support/long, not shorts).

- **Setup & Preconditions:**
  1. You are on the Bank Nifty Futures 3-minute chart (current month).
  2. A gap exists between the previous candle and the current candle: for a bullish setup, a jump-up from previous candle close to current candle open; for a bearish setup, a jump-down.
  3. High-probability variant: the gap is measured between previous candle HIGH and current candle open (bullish) / previous candle LOW and current candle open (bearish).
  4. The gap is significant: above 3 points or 60 ticks. (Worked examples: 4.30 pt gap on 9-Jan-23; 15.05 pt gap on 31-Jan-23.)
  5. The jump/gap is NOT already filled by the current candle (neither by body nor by wick).
  6. Take trade only after 9:45 AM (per the matrix time filter; the 9:15-10:00 ideal window is a global / O=H-column time filter — see §4.10 — not a Gap-column Desirable; the Gap Desirable is "trade towards gap filling").
  7. Standard indicators loaded on the 3-min chart: VWAP, VWMA 20, SuperTrend (10,2), RSI 14 (with SMA 14), Volume, Open Interest (per the deck chart panels).

- **Entry Rules — Bullish (CE / long):**
  1. Trend on the 3-min Futures chart is UP and a gap was created below current price.
  2. Confirm the gap is significant (above 3 points / 60 ticks) and not yet filled by the current candle's body or wick.
  3. WAIT for price to come back and quickly fill the gap. Do not enter on the gap-up itself — price can come back to fill first and then move on (a gap-up on the upside is "risky" without the fill, e.g., the 10:42 AM Nifty Futures gap-up flagged as risky / "wait it out").
  4. The moment the gap is filled, take the CE (call) trade in the SAME direction as the overall/prevailing trend ("call trade the moment the gap is filled"). After the fill, decide direction by the overall trend, not by continuation of the shorter move.
  5. Preferred entry location is the gap-filled area, ideally at a pullback near VWMA / SuperTrend / VWAP (Market Movers analog), or at intraday trendline support (conviction variant). Enter when momentum is up.
  6. Worked example (9-Jan-2023, BN Fut): high 09:27 = 42530, open 09:30 = 42534.30 (4.30 pt gap up); gap filled 09:48 (low 42465.50); CE entry 09:51 AM at 42536 (gap-filled area), reason = gap filled and "reversal back into the current trend" (source wording; the Day-6 commentary describes that day's prevailing trend as bearish — do not assume up-trend).
  7. Conviction/positional variant (31-Jan-2023, BN Fut): close 12:48 = 40957.90, open 12:51 = 40942.85 (15.05 pt gap); gap filled 13:03 (close 41020); CE entry 12:54 PM at 40883 on intraday trendline support (3-min TF), trading in the same direction as the short up-trend (higher highs / higher lows).

- **Entry Rules — Bearish (PE / short):**
  1. Mirror of bullish: trend on the 3-min Futures chart is DOWN and a gap was created above current price (jump-down from previous close to current open; high-prob = gap between previous candle low and current open).
  2. Confirm the gap-down is significant (above 3 points / 60 ticks) and not yet filled by the current candle (body or wick).
  3. WAIT for price to come back and fill the gap; PE (put) trade the moment the gap is filled, in the direction of the overall down-trend. If the trend is down and the gap was created when the market tried to go up, look for a SELLING opportunity based on data rather than chasing the shorter up-move.
  4. Risky/aggressive variant — trading TOWARD the gap (gap-fill trade): take the trade on price rejection toward the gap, targeting the gap level itself. Worked example (9-Jan-2023): PE trade flagged at 09:42 AM, reason = price rejection toward the gap; entry 09:45 AM at 42595; this is explicitly a "Risky Trade Opportunity" / scalping only.
  5. Caveat from daily notes: on a gap UP, do NOT look for a shorting opportunity in Bank Nifty — look for a support/long opportunity instead (and a support trade in Nifty too).

- **Exit Rules:**
  - **Targets:** In-trend trade — next resistance/support, examples at risk:reward of 1:2.5 (CE 9-Jan: target 42700 vs SL 42431) and 1:1.6 (aggressive) / 1:1.7 (conservative) on 31-Jan (target 41024). Gap-fill (counter-trade toward gap) — target is the gap level itself (PE 9-Jan: target 42530 = GAP). General scalping aim: not more than 1-2% per the matrix; Desirables note moves may already be done by 9:45, so let R:R be ~1%.
  - **Stop-loss:** Use SuperTrend as SL for in-trend entries (CE 9-Jan SL 42431 = SuperTrend). For the conviction trendline variant: aggressive SL = trendline (40803), conservative SL = below the bullish candle above the trendline (40840). Matrix alternative: SL can be the low of the previous candle (the candle before the gap candle) for longs, or the high of that prior candle for shorts. For a counter-trend gap-fill trade, SL = day high (PE 9-Jan SL 42657.85 = Day high) for shorts (mirror: day low for longs).
  - **Trailing:** Trail SL ~5 points below price for longs (mirror: 5 points above for shorts) once in profit (matrix "trail SL 5 pts below").
  - **Time-exit / scaling:** Morning trade is for scalping only — finish the trade once target or SL is hit. The conviction example (31-Jan) gives a single target (41024) framed two ways — aggressive R:R 1:1.6 and conservative R:R 1:1.7 — not a two-step scale-out. Avoid the sideways 11 AM-1 PM zone for fresh entries (Common time filter); no new entries before events after 3:30 PM.
  - **[S24] Gap-trade time box (Session 24, Day 21; see §5.4):** a gap trade is a **30–60-minute play only** — wait ~**30–40 minutes** for the fill; if it has **not** filled **on volume** by then, abandon the gap and trade **with the prevailing trend** instead. Stop-loss **~50–60 points** or a nearby S/R level.

- **Strategy-Specific Risk Management:** Treat strictly as an intraday scalp — see Global Risk Framework for sizing, per-trade max loss, and daily cap. Do NOT carry gap-fill expectation into higher timeframes or into option-price gaps (they may not fill). Avoid entering immediately on an unfilled gap-up/down (high risk of fill-then-reverse). Keep total open risk within the framework's night-risk rule (not more than one overnight position; avoid Friday) if any conviction/positional extension is used (Desirables note: "can be used positional also, as trend will stay for a while, but watch EOD OI").

- **Filters & Conditions:**
  - Time-of-day: trade only after 9:45 AM; ideal window 9:15-10:00 (Common Components time filters). Avoid 11 AM-1 PM sideways drift; no new entries before post-3:30 PM events.
  - RSI 14 (3-min): for bullish/CE, RSI less than 75 (not overbought); for bearish/PE, RSI higher than 25 (not oversold). RSI 60-40 = no-trade zone; below 40 favours PE, above 60 favours CE (Common Components RSI bands).
  - Indicators on the 3-min chart (Common Components): VWAP, VWMA 20, SuperTrend (10,2); take pullback entries near VWMA/ST/VWAP. Volume confirmation on the gap candle / fill is desirable (BN threshold 50K, Nifty 125K per Common Components, though the deck does not set a numeric gap-candle volume rule — see Uncertain).
  - OI / global cues: align with Trending OI, India VIX and DOW direction and global cues per Common Components; on a gap up, bias is to look for support/long (BN and Nifty), not shorts.
  - Strike/Delta selection (when traded as options, per Common Components): strikes within ATM ±3, delta 0.6-0.7 for buys, premium 250-400 (Bank Nifty) / 100-250 (Nifty).

- **Execution Notes & Edge Cases:**
  - Runaway gaps and strong opposite-side momentum: gaps may never fill — do not force a fill trade.
  - Unfilled gap-up = risky: wait for the fill rather than entering on the gap (explicit 10:42 AM Nifty Futures example).
  - Higher timeframes: the 3-min gap logic does NOT apply to daily/longer timeframes (daily gaps can be runaway gaps).
  - Option-price gaps: may or may not close (theta decay, no retrace) — do not rely on option gaps filling; gap-fill logic is a Futures (3-min) phenomenon (~90% same/next day).
  - Post-fill direction is governed by the overall trend, not by the short move that created the gap (e.g., trend down + gap made on an up-attempt = look for a sell).
  - The counter-trend "trade toward the gap" (targeting the gap level) is explicitly labelled risky and reserved for scalping; Desirables: "Can place a trade towards gap filling too (risky - use for scalping)."

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Gap Theory 10th Mentoring.pdf (Session 20, Day 6 — Gap Theory slide deck text)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Gap Theory 10th Mentoring.pdf (Session 20, Day 6 — slide deck with charts: VWAP / VWMA 20 / SuperTrend 10 2 / RSI 14)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (master checklist matrix — GAP column: Bullish / Bearish / Desirables)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Day 06_LMP23 22nd Sep 2023.pdf (Session 20, Day 6 live commentary)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf (Session 20, Day 9)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 10/Day 10_LMP23 3rd Oct 2023.pdf (Session 20, Day 10)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf (Session 20, Day 11)

---

### 3.5 Trending OI Crossover (original label: TRENDING OI CROSSOVER STRATEGY / "Trending OI Cross")

- **Status / Versioning:** Introduced in Session 20 (2023), Day 7 (slide deck "TRENDING OI CROSSOVER STRATEGY_LMP"), with live-commentary refinements across Day 9 (28 Sep 2023), Day 10 (3 Oct 2023) and Day 11 (4 Oct 2023). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.5.

- **Session 21 update (re-taught Day 6 deck + live Day 2/4/5/6/7/8/10/11/12; + pre-doc VIX/OI/Global):** Confirms Session-20 rules (PE-over-CE cross = long, CE-over-PE = short; ≥50% OI-change difference; volume; both %-change AND sentiment-slope must agree). Refinements: (a) at the cross the two OI lines should **immediately diverge ~20–30%**; a ≥50% gap gives conviction for the follow-on support trade — lines moving together = low-probability / range-bound (Day 6/7); (b) **best window 10–11:30 AM**; avoid initiating after ~1:30–2 PM (Day 6); (c) the **sentiment-line slope is critical** for big moves (Day 7); (d) on a **monthly-expiry day confirm BOTH positional and intraday OI**, since prior-day positions get defended at least once (Day 7); (e) **strike housekeeping** — keep Trending-OI strikes if the move is <1%, reset to ATM ±7 (close/reopen the tool) if >1% (Day 11); read the **15-min** for the major cross, **60-min** for the longer view, smaller TFs only for entries (Day 10); (f) a >50% OI difference at the close signals next-day directional bias (Day 10/12).

- **Session 22 update (reused Day-9 deck — identical 2022 examples — + Day-9 synopsis embedded in the Consolidated Synopsis):** Confirms the baseline (PE-over-CE = long, CE-over-PE = short; ≥50% OI-change difference; both %-change AND sentiment-slope agree; sentiment meter >0 bullish / <0 bearish; widening gap = ride the trend; flat/together OI = range; double/fake cross traps both sides; small qty on RSI cool-off). Refinements: (a) **VWAP is the decisive confirmation** — a cross with price oscillating around VWAP is low-probability and traps both sides; the real move comes only after the OI gap widens **and** price decisively holds the correct side of VWAP (above for long, below for short); (b) **failed-cross test** — if one side's OI reduces but the **other side's OI does not increase**, the move is only short-covering on the reducing side (sellers re-add after the pop) — not a valid cross, don't chase; (c) **trail when RSI nears an extreme** (≈25 bull / ≈75 bear) even on a valid cross. No rule deprecated. (The deck's bearish RSI>25 vs Sivas-grid RSI<25 conflict in §7 is unchanged — S22 restates >25.)

- **Purpose & Market Context:** A trend-following / momentum scalp that detects a live shift of strength from one side to the other (bulls↔bears) by watching the two Open Interest (OI) lines in the Trending OI tool cross over. A crossover occurs when one side gives up / squares off positions while the other side adds to existing positions — i.e., catching "big money's" directional commitment as it happens. Best used to trade with the trend on a clean one-way move; the bigger the gap that opens between the two OI lines after the cross, the bigger the expected move.

- **Instruments & Timeframes:** Index options/futures — Nifty (N), Bank Nifty (BN), Fin Nifty. Trending OI graph read on the 5–15 minute interval (Day 7 spec: "5 to 15 mins"). Confirmation / execution on the 3-minute chart (RSI checked on 3-min). Day-10/11 timeframe doctrine: use the 60-minute Trending OI to read the broader trend, then drop to 3-min/15-min to plan and time the trade; 15-min and 3-min OI data are equivalent (one 15-min candle = five 3-min candles), and 15-min ≈ 50/60-min give the short-vs-long-time views. Futures chart (Bank Nifty 3m) used to read direction / OI build-up type. See Common Components for shared chart/indicator definitions.

- **Setup & Preconditions:**
  1. Open the Trending OI graph on the 5–15 minute interval and identify a developing crossover: one side squaring off / OI falling, the other side adding / OI rising.
  2. Confirm the shift on TWO inputs: (a) % change in OI on each side, and (b) the slope of the OI Sentiment graph (sloping up for bullish, down for bearish). Both must agree to give "confidence and conviction."
  3. Trade only after 9:45 AM (shared/global time filter — Common Components; the Trending OI Cross grid column has no time-of-day or Desirables rows of its own).
  4. Confirm a "substantial rise in price AND difference in OI on one side (opposite on the other side)" — price action must corroborate the OI cross.
  5. OI-difference filter (Day 10): require at least a 50% difference between the change in Call OI and the change in Put OI for BN / Fin Nifty / Nifty; a 50% difference on BOTH call and put sides signals the market may give moves. Caveat: if OI stays flat all day, even a 50% difference will not produce big moves (sellers hold a range) — ideally one side's OI should keep increasing and the other keep decreasing as the day progresses.
  6. Volume confirmation: the crossover should come with volume — the Trending OI decks say only "associated with high volume" (no number). The numeric ≥ 50K Bank Nifty / ≥ 125K Nifty is the **shared universal volume gate** (Common Components — Volume threshold; in Sivas 9s this number sits in the Golden-Cross column, not the Trending-OI column), applied here as the house volume floor.
  7. Verify futures OI interpretation supports direction (Long Build-up / Short Covering for bullish; Short Build-up / Long Unwinding for bearish).

- **Entry Rules — Bullish (Long):**
  1. OI condition: PE (Put) OI increasing AND CE (Call) OI decreasing.
  2. Crossover: PE OI line crosses ABOVE the CE OI line on the Trending OI graph (5–15 min). OI Sentiment shifts from low to high (sentiment moving bearish → bullish); Sentiment graph slopes up.
  3. RSI filter: RSI (3-min) less than 75 (i.e., not overbought).
  4. Confirm substantial rise in price with the widening OI gap; volume during the cross ≥ 50K (BN) / 125K (N).
  5. Strength grade: HIGH probability when Put OI is increasing quickly AND Call OI is falling faster, the cross is associated with high volume, and short covering is happening with a drastic fall in OI — producing a huge one-way move. Bigger gap between the two OI lines = bigger move.
  6. Trade: Buy CE or Sell PE. (Confirmed example from deck: 28 Nov 2022 cross at 12:00 PM, RSI 75 → Buy CE.)

- **Entry Rules — Bearish (Short):**
  1. OI condition: CE (Call) OI increasing AND PE (Put) OI decreasing.
  2. Crossover: CE OI line crosses ABOVE the PE OI line on the Trending OI graph (5–15 min). OI Sentiment graph slopes down (sentiment moving bullish → bearish).
  3. RSI filter: RSI (3-min) greater than 25 (i.e., not oversold).
  4. Confirm substantial fall in price with the widening OI gap; volume during the cross ≥ 50K (BN) / 125K (N).
  5. Strength grade: HIGH probability when Call OI is increasing quickly AND Put OI is falling faster, coupled with high volume → huge one-way move.
  6. Trade: Buy PE or Sell CE. (Confirmed example from deck: 30 Nov 2022 cross at 10:00 AM, RSI 49 → Buy PE.)

- **Exit Rules:**
  - Target: aim not more than 1–2% per scalp (the only target traceable to this strategy; the "30–50 points good enough" figure belongs to the O=H/O=L grid column, not Trending OI Cross). Built for quick scalps (a fresh opportunity can appear within ~2 minutes when combined with Two Candle Theory).
  - Stop-loss: book the loss when SL is hit. On a DOUBLE / fake crossover (low volume + small, non-drastic OI change), exit at SL and switch to the other side where the next genuine crossover happens — both sides can be traded.
  - Trailing: matrix general note — "next day trail once a decent profit is earned"; positional use is loosely supported by the Day-10/11 timeframe doctrine (ride while the OI gap keeps widening). (The "can be used positional… watch EOD OI" phrasing is from the Market Movers grid column, not the Trending OI Cross column.)
  - Time / structure exit: defer to next-series data at the very end of the current series if Trending OI shows short build-up on both sides and both sides start covering (no clear direction). Wait for the OI crossover to confirm before flipping direction.

- **Strategy-Specific Risk Management:**
  - Position sizing: take only a SMALL quantity when the crossover occurs while RSI is cooling off, and on LOW-probability crossovers (low volume / non-drastic OI change) take smaller size or avoid entirely.
  - High-probability crossovers (drastic OI shift + volume + one-way move) warrant normal/full conviction sizing.
  - Defer to the Global Risk Framework for daily loss cap, per-trade max loss, and night-risk limits (matrix shared note: not more than 1 night risk; avoid Friday for overnight; if positional, monitor EOD OI). Morning/intraday use is scalping-only.

- **Filters & Conditions:**
  - Time-of-day: trade after 9:45 AM; ideal window 9:15–10:00 AM (see Common Components — Time Filters). Avoid the typical sideways 11 AM–1 PM unless a fresh high-probability cross prints.
  - RSI bands (Common Components — RSI 14): bullish requires RSI(3m) < 75; bearish requires RSI(3m) > 25 (Day-7 deck). UNCERTAIN — the Sivas grid states bearish RSI < 25 (3-min) for the cross, contradicting the deck's > 25; bearish deck examples include RSI as low as 20 — reconcile before automating. (Day-7 example crossovers ranged RSI 20–76, clustering bearish near 20–49 and bullish near 57–76.)
  - OI filters: ≥ 50% difference between Call-OI change and Put-OI change (BN/Fin Nifty/Nifty); one side rising while the other falls through the day; drastic one-side OI fall with equal-or-greater build-up on the other side for high probability.
  - Volume: ≥ 50K (BN) / 125K (N) during the crossover.
  - Sentiment graph slope must agree with direction (up = bullish, down = bearish).
  - Direction-change arrows (Day 10): after a large gap up/down or large morning move, the Trending OI direction-change arrows flag a trend change; a huge OI change on the opposite side accompanying the arrow signals a larger move / trend change.
  - Reference Common Components (Sentiment graph, OI interpretation LB/SC/SB/LU, Trending OI tool, global cues) rather than redefining shared items.

- **Execution Notes & Edge Cases:**
  - Failed / incomplete crossover: an up move that almost completes a cross is INVALID if sellers begin writing calls as price rises (the cross never finishes) — do not enter (Day 9).
  - Double / fake crossover: low volume + small OI change produces a whipsaw; book SL and rotate to the side where the next true cross forms.
  - End-of-series ambiguity: short build-up on both sides with both sides covering at series end gives no clear direction — use next-series data (Day 9).
  - Flat-OI trap: a 50% OI difference that persists with unchanged absolute OI all day yields no big move (range-bound sellers) — wait for OI to actually diverge (Day 10).
  - Confirmation combo: pairs well with Two Candle Theory; can yield repeated quick scalps (another opportunity within ~2 minutes).
  - The Day-7 slide deck's trade-example pages are chart screenshots; the only rule-bearing data on them are date / crossover time / RSI / trade direction (all captured above) — no additional numeric rules are embedded in the images beyond the stated Long/Short condition slides.

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 7/TRENDING OI CROSSOVER STRATEGY_LMP.pdf — Session 20, Day 7 (primary strategy deck).
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 7/TRENDING OI CROSSOVER STRATEGY_LMP.pdf — Session 20, Day 7 (source slides; chart examples).
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx — "Trending OI Cross" column (Bullish / Bearish / Desirables checklist matrix).
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 7/Day 07_LMP23 25th Sep 2023.pdf — Session 20, Day 7 live notes.
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf — Session 20, Day 9.
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 10/Day 10_LMP23 3rd Oct 2023.pdf — Session 20, Day 10.
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf — Session 20, Day 11.

---

### 3.6 Golden Crossover (original label: GOLDEN CROSSOVER)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 6 (22nd Sep 2023). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.6. Within-session reinforcement appears in Day 4 (20th Sep), Day 7 (25th Sep), and Day 9 (28th Sep) live commentary, and in the Post-Mentoring notes (DOC-20231129-WA0011).

- **Session 21 update (re-taught Day 6 deck + live Day 3/5/6/7/8):** Confirms Session-20 rules (ST AND VWMA cross VWAP on the SAME candle; full-body/volume mandatory; drastic OI change on both sides). (The Session-20 "rare ~3–4×/month" and "stronger in Bank Nifty" characterisations are not restated in S21 — they remain in the §3.6 main body above.) Updates: (a) **Stop-loss now stated** — on the support-trade form of this setup the **Supertrend level is the stop-loss** (Day 7); this resolves the Session-20 open item (§7) that no explicit SL was given. (b) Expect roughly a **200–300-point Bank Nifty move (~200 per side)** from a clean crossover (Day 6), vs the Session-20 figure of ~100–150 (volume-backed ~200) — logged in §5.6. (c) The best / most-trending crossovers occur **between 10–11 AM** (Day 6). (d) Enter the call side only once **bulls take out VWAP**; a short-covering move must clear BOTH the Supertrend and VWAP (Day 7). (e) Do not expect further extension if RSI is already oversold (bull: overbought) — wait for VWAP to hold and trade the move from there (Day 6).

- **Session 22 update (reused Day-7 deck — identical 2022 examples — + Day-7 synopsis + Consolidated Synopsis):** Confirms the baseline (ST AND VWMA pierce VWAP together — below = bull, above = bear; volume mandatory 50K BN / 125K N; drastic OI change on BOTH CE & PE; Trending OI of 5/7 strikes around ATM; ~3–4×/month, stronger in Bank Nifty; BN ~100–150 / Nifty ~50–70 pts). The reused deck states the RSI gates as **bullish 50–75** and **bearish 40–25** (3-min) and pins the moving average to **VWMA(20)** — consistent with §4.2/§4.15. No new numeric SL appears (the S21 resolution stands: support-trade form SL = the **Supertrend** level — §5.6/§7). No rule deprecated.

- **Purpose & Market Context:** A momentum scalping setup for index options. A "Golden Crossover" is the simultaneous crossing of both the Supertrend (ST) and the VWMA through VWAP — piercing from below for a bullish move and from above for a bearish move. It signals a confluence of trend (ST), volume-weighted price (VWMA), and the session's fair-value line (VWAP) flipping together, which can produce a fast directional move. It is treated as a rare, high-quality opportunity (per daily notes, ~3-4 times per month) that only delivers a meaningful move when accompanied by volume; without volume the move stalls, goes sideways, or reverses and traps retail traders. Expected better in Bank Nifty than Nifty.

- **Instruments & Timeframes:**
  - Primary signal/direction chart: 3-minute Futures chart, "Bank Nifty" (the matrix block explicitly states "in Futures chart 3mts" and the 2-candle reference notes "YOU ARE IN 3 MTS FUTURE CHART 'BANK NIFTY'"). RSI is read on the 3-min chart.
  - Trade instrument: index options — Buy CE or Sell PE (bullish); Buy PE or Sell CE (bearish). Crossover examples in the source are on Nifty/Bank Nifty/Midcap indices.
  - "Time From: 3 mins" — crossover is confirmed/measured on the 3-minute timeframe.

- **Setup & Preconditions:**
  1. You are on the 3-minute Futures (Bank Nifty) chart with VWAP, Supertrend (ST, settings 10,2) and VWMA plotted.
  2. Take trade only after 9:45 am (general program time filter; ideal window 9:15-10:00). See Common Components — Time Filters.
  3. Both ST and VWMA must cross VWAP together (the defining condition). A move where only one indicator crosses, or where there is no candle body, is not a Golden Crossover.
  4. Volume confirmation is mandatory for a tradeable Golden Crossover: Bank Nifty volume candle 50K+, Nifty 125K+. A crossover on low volume / no body is rejected (no trade) — look for the next support trade instead.
  5. Confirm with Trending OI (5-15 min) across 5/7 strikes above and below ATM: a drastic change in change-of-OI on BOTH CE and PE sides accompanies the bigger move. No drastic OI change implies a small move even if the crossover occurs.

- **Entry Rules — Bullish:**
  1. Crossover: ST AND VWMA cross ABOVE VWAP together (pierce VWAP from below) on the 3-min chart.
  2. Time From: 3 mins (confirmed on the 3-minute timeframe).
  3. RSI: less than 75 (on 3-min) — not overbought. (Matrix variant for related setups uses "RSI 50-75 / allow it to cool off"; the Golden Cross block states simply RSI < 75.)
  4. Volume during crossover: Bank Nifty = 50K+, Nifty = 125K+. Do not take the trade if volume is below threshold or the crossover candle has no body.
  5. OI confirmation: drastic change in change-of-OI on both sides; bullish OI build-up favorable (e.g., Put OI increasing / Call OI falling — short covering / long build-up bias). Track Trending OI of 5/7 strikes around ATM.
  6. Trade: Buy CE or Sell PE.

- **Entry Rules — Bearish:**
  1. Crossover: ST AND VWMA cross BELOW VWAP together (pierce VWAP from above) on the 3-min chart.
  2. Time From: 3 mins (confirmed on the 3-minute timeframe).
  3. RSI: higher than 25 (on 3-min) — not oversold. (The matrix Golden Cross block states RSI "Higher than 25"; note one matrix cell on a related row reads "RSI less than 25 in 3 mts chart" for confirming weakness — UNCERTAIN — needs confirmation whether the operative bearish gate is RSI > 25 from the strategy card or RSI < 25 from that matrix row. The strategy card value RSI > 25 is the primary stated rule.)
  4. Volume during crossover: Bank Nifty = 50K+, Nifty = 125K+. Do not take the trade if volume is below threshold or the crossover candle has no body.
  5. OI confirmation: drastic change in change-of-OI on both sides; bearish OI build-up favorable (e.g., Call OI increasing quickly and Put OI falling faster). Track Trending OI of 5/7 strikes around ATM.
  6. Trade: Buy PE or Sell CE.

- **Exit Rules:**
  - Targets (move expectation, index points, from daily notes): Bank Nifty ~100-150 points; Nifty ~50-70 points. A volume-backed Golden Crossover can give "~200 points easily" (Day 4 note). Live examples: ~100-point fall in <15 min (Midcap, Day 7); Bank Nifty 19590→19700 (~110 pts) on a volume-backed crossover (Day 9).
  - Stop-loss: The Golden Cross block in the matrix does not list an explicit numeric SL. The first-candle high can act as resistance/no-trade reference in the bullish case (per Post-Mentoring note). UNCERTAIN — needs confirmation: a dedicated numeric SL for this strategy is not stated in the Golden Cross sources; default to the Global Risk Framework / structure-based SL (e.g., crossover candle extreme or VWAP reclaim against the position).
  - Scaling: not specified for this strategy. As a scalper you can capture some points even in low-volume crossovers, but reserve full position/expectation for volume + drastic-OI crossovers.
  - Time-exit: governed by Common Components time filters (avoid sideways 11am-1pm; no new entries before events after 3:30pm). No strategy-specific time-exit stated.

- **Strategy-Specific Risk Management:** Refer to the Global Risk Framework for sizing, max loss, and daily cap. Strategy-specific notes: (1) This is a rare setup — do not force trades; only act when volume AND drastic OI change on both sides confirm. (2) Low-volume / no-body / no-drastic-OI crossovers are the primary trap for retail traders — skip them. (3) Strike/delta selection per Common Components: buy 0.6-0.7 delta, strikes within ATM +/- 3; premium ranges Nifty 100-250, Bank Nifty 250-400. (4) If two volume-backed Golden Crossovers occur in a day (rare), either or both may be traded.

- **Filters & Conditions:**
  - Time-of-day: after 9:45 am; ideal 9:15-10:00; avoid sideways 11am-1pm (Common Components — Time Filters).
  - Volume (defining filter): BN 50K+, N 125K+ during the crossover candle.
  - RSI (3-min): bullish < 75 (not overbought); bearish > 25 (not oversold). See Common Components — RSI bands.
  - OI: Trending OI graph (5-15 min) across 5/7 strikes around ATM; require drastic change in change-of-OI on both CE and PE sides. (Sentiment-graph slope agreement is a cross-reference to the Trending OI Crossover column / Common Components — the Golden Cross grid column has no sentiment-slope row.) See Common Components — OI / Trending OI.
  - VIX / global cues: not specifically required by the Golden Cross block; defer to Common Components if used for overall direction.

- **Execution Notes & Edge Cases:**
  - No-volume crossover: no follow-through — expect sideways or opposite-side action; do not trade (Day 4, Post-Mentoring).
  - No-drastic-OI-change crossover: move stays small even if the crossover happens; volume usually also unsupportive — skip (Day 6).
  - No-body crossover candle: not counted as a Golden Crossover even if the next candle meets all parameters; wait and look for the next support trade rather than chase (Post-Mentoring).
  - First-candle high as resistance (bullish): when the crossover came on low volume and the first candle high acts as resistance, there is no trade (Post-Mentoring).
  - Partial crossover with volume: a crossover that is "not even a full Golden Crossover" but comes with volume can still be traded in the direction of the move — volume is the key requirement (Day 9).
  - Rarity: ~3-4 times per month; more frequent/larger in Bank Nifty than Nifty (Day 6).

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Golden Crossover Strategy.pdf (Session 20, Day 6 — strategy slide deck: long/short conditions, trade examples)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (Session 20 — master checklist matrix, "Golden Cross" column: Bullish / Bearish / Desirables)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Golden Crossover Strategy.pdf (original slide PDF — not separately needed; text extraction complete)
  - Daily notes: Day 4 (20th Sep 2023), Day 6 (22nd Sep 2023), Day 7 (25th Sep 2023), Day 9 (28th Sep 2023), Post-Mentoring DOC-20231129-WA0011 — all Session 20 (Live Mentoring Program 2023).

---

### 3.7 Hero-Zero (Expiry-Day OI Strategy) (original label: HERO - ZERO / OI Expiry Strategy)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 8 (Oi Expiry Strategy Mentoring; reinforced across Day 2, Day 3, Day 9 live commentary). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.7.

- **Session 21 update (re-taught Day 6 deck + live Day 3/6/7/8/11):** Confirms Session-20 rules (expiry-day, buy-side only, deploy only profits never capital, hard close by 3:20 PM). The S21 deck frames strike choice as **1–2 strikes above the ATM priced ~20–50**, picking the side by premium decay and where shorts are building (the Session-20 "one strike adjacent to the short-covered strike" wording stays in the §3.7 main body). Refinements: (a) **do not trade before ~2:30–2:45 PM** — wait so premiums settle and sellers commit at S/R (Day 6), tightening Session-20's "after 2 PM"; (b) **wait for the soon-to-expire option's premium to decay** (e.g. puts down to ~15–20) and trade the side where shorts are building / premium is evaporating; decide the side after **1:30–2 PM** by which side carries higher premium and where shorts are being built (Day 6/7); (c) **prep** — review ~4 round strikes around the ATM one day before, using the last ~4 days of OI for weekly/monthly expiries (now ~2 days suffices given daily expiries) to find the maximum OI change (Day 8); (d) confirm the side only when **BOTH price and OI change >50%** on that strike (Day 8/11); both-side OI increase near the close (writers on both sides) is **unfavourable** (Day 6/7).

- **Session 22 update (new S22 Day-9 deck "How To Identify Hero Or Zero" dated 18 Feb expiry — NOT a reused deck — + reused Day-8 OI-Expiry deck + Day-8/9 synopses + Consolidated Synopsis Q&A):** Confirms the baseline (expiry-day, buy-side only, only from profits, decide the side after ~1:30–2 PM, hard close 3:20 PM, sit out when both sides build / on VWAP pinning, BOTH price & OI change >50% to confirm the side). The new deck and dailies add: (a) **resolves the §7 numeric-SL question** — use an **index-scaled point SL: Bank Nifty ~75 pts, Nifty ~30 pts**, wider for Sensex (~80000) / Bankex (~58000) (Q9), on top of the existing "50% of premium / hard close 3:20 PM"; (b) **deploy only ~10% of profits** into a hero-zero trade (puts a number on "only profits"); (c) **payoff scale** — on a serious short-covering squeeze the cheap option **doubles/triples within ~30–45 points** of underlying move and Bank Nifty can give a quick **100–200-point** move; (d) **strike-pick window 2:30–3 PM** for the 1–2-strikes-above-ATM (~20–50 priced) option; (e) **conditional 3:10 PM no-move exit** — do not stay past 3:10 PM if the move is not happening (tighter than the 3:20 hard close); (f) **scale in parts** at lower levels with the **largest quantity at the lowest price** near support/VWAP; (g) when direction is unclear near the close you may take **both sides with small quantity** rather than sit out; (h) **round-strike double-zero pin warning** — when sellers pin price at a round strike, BOTH the CE and PE there expire at zero, avoid either side; (i) **IV flat on both sides = no trade** (sellers control both sides, only erosion); (j) prep refined to **~4 strikes above + 4 below the ATM, last 3–4 days of OI**; (k) the expiry-day read can also feed a **morning/intraday** trade off the previous day's build-up (Q5). No rule deprecated.

- **Purpose & Market Context:** A directional option-buying strategy executed only on weekly/monthly expiry day, exploiting the fact that on expiry day option sellers (writers) dominate and the buyer's edge is to identify where sellers are forced to exit/unwind their positions and sit on the opposite side. Premise: "sellers create positions; as a buyer I need to know where exactly the seller is going to be and where as a buyer I need to be — you make profits where the sellers are likely to exit their positions." On expiry day only ATM strikes carry premium, so the underlying is rangebound and sellers panic only when a level is breached on one side; the trade buys a cheap soon-to-expire option ("hero-zero" — it either multiplies or expires worthless) on the side opposite to where sellers are pumping funds. Sellers write the side with higher premium and create positions where buyers are exiting (longs unwinding); the buyer sits opposite. Late-day short covering near support/resistance is the primary fuel.

- **Instruments & Timeframes:** Index options on expiry day — Nifty (weekly), Bank Nifty (weekly), and monthly expiry / Fin Nifty (monthly perspective). Direction is read primarily from the OI / option-chain / short-covering reads the Hero-Zero column lists; the 3-min Bank Nifty Futures chart is used as a general house convention for the underlying direction (not a Hero-Zero-specific checklist line). Decision data is read on the OI Expiry analysis page combined with Option Chain, OI Spurts, Options Premium, Trending OI (5-15 min) and Sentiment graph. Execution window is intraday expiry day, after 2pm; observation of short covering between 2:30pm and 3pm; hard close by 3:20pm.

- **Setup & Preconditions:**
  1. You are on expiry day, and the time is after 2pm (precondition — do not run this before 2pm).
  2. Identify where the highest OI and volume build-up is happening (this defines the boundaries of the expected range).
  3. Determine from that OI build-up which strike is support and which is resistance: Put = support, Call = resistance, based on where the heaviest writing sits. (Largest change in OI marks where sellers expect price will NOT cross — e.g. max OI on a CE strike = sellers expect close below it; max OI on a PE strike = sellers expect close above it.)
  4. Observe where short covering is happening between 2:30pm and 3pm around support/resistance (this signals which seller is exiting and where price will be pushed).
  5. Prepare the prior evening from a week's data: select 5 strikes either side of ATM to analyse (10 strikes either side if huge volatility/spread expected); always include round strikes (e.g. 40000, 40500, 41000, 42000) as that is where most action happens. For monthly-expiry / Fin Nifty, select 2-3 strikes from the previous close plus the close round strikes and analyse a week of data from a monthly-expiry perspective.
  6. Confirm via combined view of Option Chain page, OI Spurts, Options Premium and OI Expiry analysis page; US/global markets give the clue for the next move.
  7. Strong-move confirmation requires BOTH OI and price changing more than 50% on the same side (e.g. short build-up = OI up >50% AND price down >50%); if OI rises but price does not move on similar lines, the move will not continue.

- **Entry Rules — Bullish:**
  1. Confirm market is closing toward the day's high and OI is at day's low/high (expiry-strength read).
  2. Confirm the underlying direction on the 3-min Futures chart (Bank Nifty 3m).
  3. Confirm RSI is not overbought (RSI should NOT be >75). *(UNCERTAIN — this RSI gate sits in the BTST grid column; no Hero-Zero / OI-Expiry deck specifies an RSI threshold. Treat as a soft cross-strategy check, not a Hero-Zero-native rule.)*
  4. Confirm short covering is happening significantly with a drastic fall in OI (validate in OI statistics that the change in OI is significant).
  5. If the market is up, take a CALL one strike below the strike where short covering (SC) is happening — this is the Hero-Zero strike. (Vice versa for the put side.)
  6. Do NOT buy a cheap option in the short-covered hero-zero strike priced 10-14 (rupees) — i.e. avoid the strike that has already been short-covered and gone cheap; pick the adjacent strike per step 5 instead.
  7. Confirm your view matches the OI Pulse view at 3:20pm before firing. *(The OI-Pulse-view-match step is from the BTST grid column — a shared/house gate, not a Hero-Zero-deck rule; the 3:20pm hard close/SL itself IS Hero-Zero-grounded.)*
  8. Supporting expiry-day OI reads that confirm bullish: short build-up on the Call side together with long build-up on the Put side = double confirmation to be a buyer on the Put side and a seller on the Call side; and when sellers shift from writing Calls to writing Puts (PUT buyers unwinding, sellers write PE where buyers exit), sit as a buyer on the Call side — this is why Calls move higher on expiry day.

- **Entry Rules — Bearish:** The checklist's Hero-Zero column does not spell out a separate numbered bearish block (the bearish branch is given as "Vice versa for put side"). Mirror of the bullish rules:
  1. Confirm market is closing toward the day's low and OI at day's low/high.
  2. Confirm direction on the 3-min Bank Nifty Futures chart.
  3. Confirm RSI is not oversold (the mirror of "not >75").
  4. Confirm short covering on the Put side / call writing pressure with significant OI change in OI statistics. (Day 9 example: continuous call writing after a minor short covering on the call side = persistent bearish pressure, spot example 44600.)
  5. If the market is down, take a PUT one strike above the strike where short covering is happening (vice versa of the bullish rule).
  6. Avoid the already short-covered cheap hero-zero strike (10-14).
  7. CAUTION on PE trades when calls are trading at a discount: do NOT take a PE trade when calls are at a discount on expiry day — a single up move to adjust PE-side premiums can dent capital; trade PE cautiously and wait for an up move to sell into instead. With bearish data you will still see upside moves whose purpose is to take out weak hands and adjust PE-side premiums — do not be faked out by these premium-adjustment moves.

- **Exit Rules:**
  - Stop loss: 50% (of option premium / position), BUT mandatorily close the position at 3:20pm regardless ("Stop loss 50% but close at 3:20pm").
  - Target: not explicitly numbered in the Hero-Zero column; the trade is a hero-zero (cheap option that either multiplies or expires near-zero), so the gain is captured by riding the short-covering squeeze and exiting by the time close.
  - Time exit: hard exit at 3:20pm (also the time to confirm your view matches OI Pulse before the final decision).
  - Note the Gamma/short-squeeze potential on the favorable side: a short-cover squeeze can move a cheap option (e.g. ~6 to ~70, multiples of 100% gain) — this is the buyer's payoff; OI cooling ~50% of the move tends to mark the settling point.

- **Strategy-Specific Risk Management:** Buy cheap soon-to-expire options only — but NOT the already short-covered ultra-cheap hero-zero strike priced 10-14. Mandatory hard time-stop at 3:20pm; SL at 50% of premium. Recognize uncapped Gamma risk for sellers near expiry (no time value, all intrinsic) — this is precisely why the strategy is buy-side only on expiry day. Do NOT take PE trades when calls trade at a discount (one up move can dent capital). Sit out entirely when long unwinding happens on both sides (sellers take over both sides, only premium erosion occurs, a buyer cannot make money on either side). No new entries before impending events. Refer to the Global Risk Framework for position sizing and daily caps. No overnight/night-risk dimension here — this is an intraday expiry-day trade closed by 3:20pm.

- **Filters & Conditions:**
  - Time-of-day: expiry day only, after 2pm (precondition); observe short covering 2:30pm-3pm; OI Pulse view-match check at 3:20pm (shared/house gate borrowed from the BTST column); hard close 3:20pm.
  - OI filters: highest OI/volume build-up defines S/R; significant short covering with drastic OI fall required; require >50% OI and >50% price move on the same side for a confirmed/continuing move (see Common Components — OI interpretation LB/SC/SB/LU); avoid trading when long unwinding occurs on both sides.
  - RSI: not overbought (>75) for the bullish/call side (3-min chart per Common Components RSI 14). UNCERTAIN — borrowed from the BTST grid column; no Hero-Zero deck states an RSI threshold.
  - Trending OI (5-15 min) and Sentiment graph: read alongside OI Expiry analysis; "Trending OI was giving the clue" used to flag index direction (e.g. Nifty bearish, Bank Nifty bullish).
  - VWAP filter: when sellers keep the market pinned at/near VWAP, expect premium erosion — "No trades when they are keeping the markets in the VWAP level."
  - Global cues: US/global markets give the clue for the next move (reference Common Components — Global cues).
  - Premium / data confirmation: combined view of Option Chain, OI Spurts, Options Premium and OI Expiry analysis pages.

- **Execution Notes & Edge Cases:**
  - Expiry pinning / VWAP: no trades when sellers hold the market at VWAP (only premium erosion).
  - Premium-adjustment fake moves: with bearish data, upside moves occur to take out weak hands and adjust PE-side premiums — do not be faked out; wait for the up move to sell into on the PE side.
  - Both-sides long unwinding: sit out (sellers dominate both sides, buyers cannot profit).
  - Distinguish long unwinding from short covering: when OI is falling and price is closing lower but NOT at the day's high, it is long unwinding (not pure short covering) and shorts will then enter — confirm before trading.
  - Persistent writing: continuous call writing after a minor short covering indicates persistent bearish pressure (Day 9 spot example 44600).
  - Gamma squeeze: a cheap option can spike on a short-cover squeeze (example ~6 to ~70); sellers without stops can be wiped out, and in sudden spikes even stops get skipped — be the buyer, not the seller.
  - Rangebound start: on expiry, when calls and puts have roughly equal volume at the start, expect a rangebound day (only ATM carries premium); sellers panic only when a level is breached on one side.

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Oi Expiry Strategy Mentoring.pdf (Session 20, Day 8 — primary)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (Session 20 — master checklist matrix, "HERO - ZERO" column block)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Oi Expiry Strategy Mentoring.pdf (Session 20, Day 8 — original slides)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Day 02_LMP23 07th Sep 2023.pdf (Session 20, Day 2 — expiry rangebound note)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 3/Day 03_LMP23 08th Sep 2023.pdf (Session 20, Day 3 — Gamma risk, OI read)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Day 08_LMP23 26th Sep 2023.pdf (Session 20, Day 8 — live preparation/build-up reads)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf (Session 20, Day 9 — bearish/PE-discount edge cases)

---

### 3.8 BTST / STBT (original label: BTST / STBT — Buy Today Sell Tomorrow / Sell Today Buy Tomorrow)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 8 (BTST / STBT Strategy Mentoring, 26th Sep 2023). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.8. Reinforced in daily mentoring on Day 2 (07th Sep), Day 11 (4th Oct), and Day 12 (5th Oct 2023).

- **Session 21 update (re-taught live Day 8/12; no standalone deck in S21):** Confirms Session-20 rules (close-at-high + OI quadrant → BTST; close-at-low → STBT; legs BTST = Buy Fut / Sell PE / Buy CE, STBT = Sell Fut / Sell CE / Buy PE; no fresh carry if daily RSI > 75; EOD + global-cue confirmation). Updates: (a) **Quadrant numbering resolved (Day 12)** — BTST: Short Covering = **Quadrant 3**, Long Build-up = **Quadrant 1**; STBT: Short Build-up = **Quadrant 2**, Long Unwinding = **Quadrant 4**. This resolves the Session-20 uncertainty over the Long-Unwinding quadrant (§7). (b) The carry is delivered via the AI **"320 Strategy"** — a probability signal at **3:20 PM** with a deliberately **wide** stop-loss (overnight gap risk); buy at the indicated price and exit when the SL is hit (Day 12). (c) **Carry a buy side only if the strike is closing near the day's high** (long build-up / short covering); if longs are unwinding, do **not** carry calls overnight — sellers read the weakness and write that side heavily (Day 8).

- **Session 22 update (reused Day-7 deck — identical 2022 examples — + Day-7 synopsis + Consolidated Synopsis live diary):** Confirms the baseline including the **resolved quadrant numbering** (BTST SC = Q3, LB = Q1; STBT SB = Q2, LU = Q4), the legs (BTST = Buy Fut / Sell PE / Buy CE; STBT = Sell Fut / Sell CE / Buy PE), RSI bands (BTST >60, STBT <40 in examples), and "never carry fresh stock positions if daily RSI > 75." *(Caution: an older deck-style block inside the Day-7 deck file still mislabels BTST SC as "Q4" / STBT LU as "Q3" — the original S20 mislabels S21 corrected; ignore those, use the resolved numbers.)* New material is all about **stock overnight carries:** (a) carry an overnight stock short (STBT) when its Futures show **OI closing at the day's high and price at the day's low** (completely bearish); (b) a **15-day-low** name with completely bearish data is a valid STBT candidate (extends the 8/9-day-low rule); (c) before an overnight stock short, confirm the stock is **not already over-sold intraday** in addition to the daily-RSI 75/40 screen; (d) **do not take a BTST after a parabolic up-move** at the close (expect a next-day reversal); (e) post-market routine — review the **Expiry-day analysis feature after 8:30 PM** plus Dow close / Dow Futures / Asian markets before the next open. No rule deprecated.

- **Purpose & Market Context:** BTST ("Buy Today Sell Tomorrow") and STBT ("Sell Today Buy Tomorrow") are short-term overnight carry strategies that magnify profits in a short period — and equally magnify losses. The aim is to take a directional position late in the trading session, when end-of-day data (price action, OI behaviour, RSI, global cues) builds strong conviction in a continuation move, hold it overnight, and exit the following morning. BTST is the bullish/long carry; STBT is the bearish/short carry. The edge comes from reading data correctly at the close (price closing at day's high/low aligned with the right OI quadrant). It is a risky trade requiring an understanding of overnight gap and news risk; trade only if you understand the risks.

- **Instruments & Timeframes:**
  - **Primary chart / timeframe:** 3-minute Futures chart (Bank Nifty 3m) for direction; also the live mentoring example days were assessed on the 3-minute timeframe.
  - **Decision data:** EOD analyser, OI expiry-day analysis (where sellers built positions at close), Futures OI Analysis, Trending OI graph, Sentiment graph, OI Spurts (4 quadrants), OI Pulse / OIP "AI" direction.
  - **Instruments (BTST / bullish):** Buy Futures, Sell PE, or Buy CE. In stocks, buy Futures or buy stock in cash market (no stock options); BTST in stocks does not require delivery when sold next day.
  - **Instruments (STBT / bearish):** Sell Futures, Sell Call options, or Buy PE. In stocks, sell Futures or sell stocks.
  - **Strike/premium guidance (where options are used — shared with Common Components):** Strikes within ATM +/- 3; Delta 0.6–0.7 for buys; Premium range Nifty 100–250, Bank Nifty 250–400.

- **Setup & Preconditions:**
  1. Determine the late-day picture: is the market closing at the day's high (BTST candidate) or day's low (STBT candidate), and where is OI sitting (day low vs day high)?
  2. Map the close to the OI quadrant (see Entry Rules) to confirm BTST vs STBT and the underlying OI behaviour (Short Covering, Long Build-up, Short Build-up, Long Unwinding).
  3. Identify where the highest OI and volume build-up is happening — establish Put as support and Call as resistance (BTST) / vice versa (STBT) based on this.
  4. Observe where short covering (BTST) / short build-up (STBT) is happening between 2:30pm and 3:00pm around support/resistance.
  5. At 3:15pm, confirm Futures OI direction (Bullish for BTST / Bearish for STBT).
  6. At 3:15pm, confirm Option OI direction (Bullish for BTST / Bearish for STBT) via Trending OI graph and Sentiment graph check.
  7. Confirm global cues are positive (BTST) / negative (STBT) as of 3:15pm.
  8. Confirm RSI is not overbought (>75) for BTST; for STBT, look for over-sold RSI levels (see Filters).
  9. At 3:20pm, confirm your view matches the OI Pulse view.
  10. Volume should be high and bullish (BTST) / bearish (STBT) in the last 30 minutes.
  11. For stocks: a stock on Short Build-up that breaks an 8-day or 9-day low is an ideal STBT candidate (mirror for BTST: short covering / long build-up at day's high).
  12. Confirm setup with EOD data (does it confirm what the charts show?) and global market cues. On event days, data at times takes a back-seat.

- **Entry Rules — Bullish (BTST):**
  1. **Trigger condition (from matrix):** Market is closing at the day's high, with OI at day's low (Short Covering) or day's high (Long Build-up).
  2. **OI-quadrant confirmation:**
     - Market closing at day's high AND OI at day's low → BTST, Quadrant 3 (Short Covering) — best entered by buying calls or buying Futures, exit next morning.
     - Market closing at day's high AND OI at day's high (or a long build-up forming toward end of day) → BTST, Quadrant 1 (Long Build-up).
  3. **Best setups (ranked from source):** (a) Long Build-up + market closing at day's high = best to trade; (b) BTST best traded after consolidation and long build-up; (c) Short covering that shifts to long build-up gives the best results.
  4. **Example "good day" criteria (live mentoring):** 3-minute timeframe; RSI above 60; Futures in Short Covering; price closing at day's high. Next-day confirmation: previous day's high not tested (i.e., it holds / continues up).
  5. **3:15pm checks all bullish:** Futures OI bullish, Option OI bullish (Trending OI & Sentiment), global cues positive, RSI not overbought (>75).
  6. **3:20pm:** your view and OI Pulse view match.
  7. **Instrument execution:** Buy Futures, Sell PE, or Buy CE; if options, choose the AI-suggested strike within the premium range (Nifty 100–250 / Bank Nifty 250–400), ATM +/- 3 strikes, delta 0.6–0.7.
  8. Enter near the close (late session) and carry overnight.

- **Entry Rules — Bearish (STBT):**
  1. **Trigger condition (from matrix):** Market is closing at the day's low, with OI at day's low or day's high.
  2. **OI-quadrant confirmation:**
     - Market closing at day's low AND OI at day's high → STBT, Quadrant 2 (Short Build-up).
     - Market closing at day's low AND OI at day's low → STBT, Quadrant 4 (Long Unwinding). (The Day 8 slide PDF labelled this "Quadrant 3"; **Session 21 Day 12 confirms Long Unwinding = Quadrant 4** — use Q4. Other quadrants per S21 Day 12: BTST Short Covering = Q3, Long Build-up = Q1; STBT Short Build-up = Q2.)
  3. **Example "good day" criteria (live mentoring):** 3-minute timeframe; RSI below 40; Futures in Long Unwinding (one slide example shows Short Build-up); price closing at day's low. Next-day confirmation: previous day's low tested (continuation down).
  4. **Stock candidate:** Stock on Short Build-up that breaks an 8-day or 9-day low.
  5. **3:15pm checks all bearish:** Futures OI bearish, Option OI bearish (Trending OI & Sentiment), global cues negative, RSI not over-sold beyond comfort.
  6. **3:20pm:** your view and OI Pulse view match.
  7. **Instrument execution:** Sell Futures, Sell Call options, or Buy PE; for stocks, sell Futures or sell stocks.
  8. Enter near the close (late session) and carry overnight.

- **Exit Rules:**
  - **Time / morning exit:** Exit positions the next morning (the strategy is an overnight carry). For the BTST short-covering case the source explicitly states "exit positions the next morning."
  - **Trailing:** Next day, trail once a decent profit is earned.
  - **Stop-loss (from matrix):** Stop loss 50%, but close at 3:20pm (i.e., cap losses; the carried position is squared/managed and morning trailing applies to winners).
  - **Confirmation before continuing the hold:** If yesterday's 3:20pm view, the morning trade read, premarket, and global cues all match, then the position is sound; if not aligned, manage out.
  - **Scaling:** No explicit scale-in/scale-out steps given beyond "trail once decent profit is earned" next day. (UNCERTAIN — needs confirmation.)

- **Strategy-Specific Risk Management:**
  - **Night risk:** Not more than 1 night risk; avoid Friday (weekend gap risk). Refer to the Global Risk Framework for overnight exposure limits.
  - **RSI hard limit:** Never carry fresh positions for stocks if daily RSI is above 75.
  - **Improper-trade warning:** BTST not traded correctly with proper data backing can wipe out the traded amount when nearing expiry and the market moves in the opposite direction. Do not do improper BTST near expiry against the trend.
  - **Short-selling penalty (STBT in stocks):** Short selling in stocks can attract a penalty if the seller cannot deliver the stocks or square off the position on time during monthly expiries (cited example: Hindalco).
  - **Overnight gap risk:** The end-of-session price rise (or fall) may not sustain the next day if world markets / specific news move adversely.
  - **Sizing / max loss / daily cap:** Per Global Risk Framework. Stop loss 50% (then close at 3:20pm) as noted in Exit Rules — the source states "Stop loss 50%" without scoping it to a specific leg.

- **Filters & Conditions:**
  - **Time-of-day:** Decisioning is end-of-day — observe short covering / short build-up between 2:30–3:00pm; Futures OI, Option OI and global-cue checks at 3:15pm; OI Pulse alignment at 3:20pm. Keep off if any impending event is scheduled after 3:30pm. (General intraday time filters — trade after 9:45am, etc. — are in Common Components but apply to the next-morning trade, not the carry decision.)
  - **VIX / volatility:** India VIX rules apply (VIX and Dow should confirm the market direction — VIX going down supports bullish/BTST, VIX going up supports bearish/STBT, per the matrix conventions). On Day 2 it was noted BTST can be considered when VIX closes at the day's low and the market closes at the day's high (along with other parameters).
  - **OI:** Core filter — OI quadrant mapping (LB / SC / SB / LU) as above; confirm via Trending OI graph, Sentiment graph, Futures OI Analysis, OI expiry-day analysis, and OI Pulse / OIP AI direction. Check OI statistics for significant change in OI.
  - **Global cues:** DOW/Dow30 futures, Dollar index, Asian markets, Oil — must match the intended direction; confirmed positive (BTST) / negative (STBT) as of 3:15pm. Reference Common Components (Global Cues).
  - **RSI bands:** When in Short Covering and BTST, look for RSI levels when over-bought; when in Short Build-up / Long Unwinding and STBT, look for RSI levels when over-sold. BTST example days used RSI above 60; STBT example days used RSI below 40. Hard limit: do not carry fresh stock positions with daily RSI above 75.
  - **EOD data confirmation:** Look for EOD data to confirm what the charts show; on event days, data at times takes a back-seat.

- **Execution Notes & Edge Cases:**
  - **Positional view formation:** Use the EOD analyser plus OI expiry-day analysis (where sellers built positions as they close the day) and Futures OI Analysis to form the BTST/STBT positional view.
  - **Expiry:** Improper BTST near expiry against the trend can wipe out capital; STBT short-selling in stocks risks delivery/square-off penalty during monthly expiries.
  - **Events / news:** On event days data takes a back-seat; keep off if an impending event is scheduled after 3:30pm; overnight world-market/news moves can negate the end-of-session move.
  - **Screen time:** BTST (and morning-trade) strategies take less screen time but are risky; commodities (evening hours) are an alternative for time-constrained traders.
  - **Next-day continuation read:** BTST is confirmed when the previous day's high is NOT tested (price continues up); STBT is confirmed when the previous day's low IS tested (price continues down).

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/BTST _ STBT Strategy Mentoring.pdf (Session 20, Day 8)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/BTST _ STBT Strategy Mentoring.pdf (Session 20, Day 8 — slide source)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (Master checklist matrix — "BTST" column block: Precondition / Check points)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Day 02_LMP23 07th Sep 2023.pdf (Session 20, Day 2 — VIX/close note)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Day 08_LMP23 26th Sep 2023.pdf (Session 20, Day 8 — daily notes)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf (Session 20, Day 11 — positional view tooling)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Day 12_LMP23 5th Oct 2023.pdf (Session 20, Day 12 — 8D/9D low STBT candidate)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf (Session 20 — screen-time/risk note)

---

### 3.9 Morning Trade (Opening Trade Strategy) (original label: MORNING TRADE / "Opening Trade")

- **Status / Versioning:** Introduced in Session 20 (2023), Day 8 (Opening Trade Strategy Mentoring; with live application on Day 9 / 28th Sep 2023, refinements on Day 10 / 3rd Oct 2023 and Day 11 / 4th Oct 2023). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.9.

- **Session 21 update (re-taught Day 6 deck + live Day 6/7/11/12):** Confirms Session-20 rules (EOD-formed view valid only without overnight news; enter on a rejection wick after the failed attempt at prior close; the whole trade can live in the first 3-min candle; experienced-only; small size). (The "market closed at the day's high/low" convincing-close condition is a Session-20 morning-trade rule retained in the §3.9 main body; S21's morning sources do not restate it.) Refinements: (a) delivered via the AI **"Morning Trade" signal at 9:11 AM with a mandatory exit by 9:18 AM** (Day 12), tightening the Session-20 9:16→9:18 worked example; (b) use the **previous day's VWAP** as the reference level — intraday/morning indicators are unreliable at the open (Day 7); (c) **deploy only a portion of profits, never core capital** — these are very risky AI-driven trades (Day 12); (d) take **every** signal but size to risk-reward — normal lot when the signal aligns with the overall market, **reduced lot** when it opposes or is neutral (Day 11); a flat open or a two-sided opening move crashes premiums fast, so expect quick decay (Day 7).

- **Session 22 update (reused Day-7 deck — identical 2nd-Feb-23 / 250-pts-in-2-min example — + Day-7 synopsis + live PE morning trade & Q&A in the Consolidated Synopsis):** Confirms the baseline (experienced-only; EOD-formed view valid only without after-close news; rejection-wick entry on the failed attempt at prior close; whole trade can live in the first 3-min candle; 9:16→9:18 / S21 AI 9:11→9:18 timing; small size; SL = first-candle low/high). Refinements: (a) the **previous day's VWAP is the defended level** the trade respects/targets (bears protect it on a PE morning trade); (b) **profit-trail-to-breakeven** on top of the mechanical SL — once price runs in your favour, trail and set the buy price as SL (a fast snap-back can still hurt); (c) **slight-ITM strike sized off the gap** — pick a slightly ITM strike and price it expecting ~100-pt premium volatility at the open plus a 30–40-pt buffer; (d) **add only around the previous-day close**, nowhere else; (e) **Open=High doubles as exit-trigger + hedge** — if price hits the opposite-side OH against your position you must exit, and the opposite-side OH trade can be taken as a hedge; (f) **pre-open data** (after 9:07 AM, available 9:08) gives the eagle-eye open across Nifty50/Bank Nifty/F&O stocks; (g) a **>50% change in OI direction** is needed for a convincing same-day view; (h) the **expiry-day read can feed the morning trade** (Q5); (i) **market-maker caveat** — several conflicting market makers may be active, so on an ambiguous open watch how the data pans out before firing (Q3, which also **resolves "how to trade when morning data is unhelpful"**: act off previous-day closed data + today's open). No rule deprecated.

- **Purpose & Market Context:** A high-risk, scalping-only trade taken at/just after the market open to capture a single sharp directional move (typically "one dip" or "one pop") in the first few minutes of the session. The edge comes from forming a directional view the previous evening using end-of-day (EOD) Futures positioning, Trending OI, sentiment and global cues, then executing on the opening tick when price action (rejection wick on a gap) confirms that view. Explicitly flagged for experienced traders only who can handle morning volatility and interpret data. The worked example (2nd Feb 2023) captured 250 points in 2 minutes on the short side.

- **Instruments & Timeframes:** Index options (CE/PE) for the scalp — e.g. Bank Nifty PE/CE such as "45000 PE", "40000" Futures target referenced. Direction is read on the 3-minute Bank Nifty Futures chart; assess the 1-minute candle and watch how the 2nd candle breaks the 1st (Morning-Trade matrix cell). The entire trade can be initiated and closed within the first 3-minute candle. Primary execution timeframe: 1-minute / 3-minute on the open.

- **Setup & Preconditions:**
  1. Do previous-day EOD analysis the evening before: where our market closed, how global markets are performing, and plan how to trade based on how the market opens next day. EOD Futures data is the major input for forming the next-day opening-tick view.
  2. EOD data holds good ONLY if there is no external news or market movement after close that would impact positions created before close.
  3. Data points to study: (a) Futures data showing where positions are created (LB / SC / SB / LU); (b) Trending OI data; (c) market sentiment / OI sentiment; (d) FII/DII activity (per the dedicated OI Pulse manual) to form the morning view.
  4. EOD data must be convincing AND the market must have closed at the day's high or the day's low to take a trade in that direction. An inside candle / close near the open is NOT a convincing close — do not take the morning trade.
  5. Matrix preconditions ("For CE or PE trade — scalping"): OIP AI direction and pre-market direction must match; global cues (Dow, Dollar index, Asian markets, Oil trend) must match; Nifty advance/decline must match market direction (adv>32 = CE, dec>32 = PE).
  6. Confirm at the prior-day **3:20 PM OI-Pulse check** whether Futures OI and Option OI (Trending OI & Sentiment) align with the intended next-day direction. (The 3:15 PM Futures-OI / Option-OI direction check is the BTST column's precondition, §3.8; the Morning-Trade column's own matrix stamp is the 3:20 PM OI-Pulse alignment.) Alignment of "yesterday's 3:20, morning trade view, pre-market & global cues" = all is well.
  7. Choose the AI-suggested strike within the given premium range. (Per glossary premium ranges: Nifty 100-250, Bank Nifty 250-400; strikes within ATM +/- 3; delta 0.6-0.7 for buys — referenced from Common Components.)

- **Entry Rules — Bullish (CE):**
  1. Confirm bullish alignment: OIP AI direction + pre-market direction bullish; global cues positive; Nifty advances >32 (CE); Futures OI and Option OI bullish at the prior-day 3:20 PM OI-Pulse check.
  2. On the open, read the 3-minute Bank Nifty Futures chart. Assess the 1-minute candle and watch the direction in which the 2nd candle breaks the 1st.
  3. Enter when a rejection wick forms — i.e. a gap-down/dip is rejected and price turns back up toward (or away from) previous day close — confirming the upside one-pop move. RSI should NOT be overbought (>75); RSI 60 and above supports a CE; RSI 40-60 is a no-trade zone.
  4. If the 2nd candle direction aligns with AI and pre-market, then fire. If it did not align immediately, wait for the 3-minute candle to form and trade the breakout.
  5. Trade with a small quantity (especially when expected index movement is brief). The full entry/exit can occur inside the first 3-minute candle.

- **Entry Rules — Bearish (PE):**
  1. Confirm bearish alignment: OIP AI direction + pre-market direction bearish; global cues negative (e.g. Dow negative); Nifty declines >32 (PE); Futures OI and Option OI bearish at the prior-day 3:20 PM OI-Pulse check. Justification pattern: Futures showing short build-up / long unwinding at EOD (buyers not confident), EOD analyzer showing run-up-to-event longs unwinding (profit booking / exhaustion), prior-day shorts still held, gap-down opening on news.
  2. On the open, read the 3-minute Bank Nifty Futures chart; assess the 1-minute candle and watch how the 2nd candle breaks the 1st.
  3. On a gap-down/gap-up open, the attempt to reach previous day close fails — enter when a rejection wick starts forming. Worked example (2nd Feb 2023): Entry 9:16 AM on rejection; RSI still above 30 at open so not yet over-sold.
  4. Edge case for gap-down opens: if RSI has ALREADY reached oversold territory at open, do NOT chase the fall — wait for RSI to cool off and come back to resistance before taking the PE; entering immediately into the fall can fail.
  5. RSI should not be overbought; for PE, RSI 40 and below supports the trade (40-60 = no-trade zone). Fire when direction aligns with AI and pre-market; if not immediate, wait for the 3-minute candle to form and trade the breakout. Use small quantity.

- **Exit Rules:**
  - **Target:** The next resistance (CE) or support (PE) as the case may be — a defined Futures level. Worked example: Futures reaching 40000 was the exit. Move "as per data analysis for one dip in morning." Take profit on a small rejection from the first 3-minute candle (book even with small quantity).
  - **Stop-loss:** The first candle's low (for CE / long) or first candle's high (for PE / short) is the stop-loss, as the case may be. (Matrix also references a separate 50% SL line tied to BTST — that 50%/close-at-3:20 rule belongs to BTST, not the morning scalp; the morning-trade SL is the first-candle high/low.)
  - **Time exit / scaling:** Morning trade can be initiated AND closed within the first 3-minute candle. Worked example: Entry 9:16 AM, Exit 9:18 AM — 250 points in 2 minutes. Finish the trade once target/SL is hit; this is for scalping only. RSI secondary exit confirmation: in the worked short, RSI dropping below 30 was a reason to exit.

- **Strategy-Specific Risk Management:**
  1. Designated high-risk — only for experienced traders who can handle morning volatility and interpret data; trade only if ready to take the risk.
  2. Position size small, especially when little index movement is expected (live example booked profit with a small quantity on a near-flat open).
  3. Strictly scalping — exit when target or SL hits; do not hold/convert to positional.
  4. Stop-loss is mechanical: first candle low (long) / first candle high (short).
  5. For sizing caps, daily loss limits and night-risk policy, reference the Global Risk Framework (shared rules) — not restated here.

- **Filters & Conditions:**
  1. **Direction/AI:** OIP AI direction must match pre-market direction (Common Components — OI Pulse AI direction).
  2. **Global cues:** Dow/Dow30 futures, Dollar index, Asian markets, Oil must match the intended direction (positive for CE, negative for PE).
  3. **Breadth:** Nifty advance/decline must match — adv>32 = CE, dec>32 = PE.
  4. **OI:** Futures OI and Option OI (Trending OI + Sentiment graph) confirmed bullish/bearish at the prior-day 3:20 PM OI-Pulse check.
  5. **RSI (Common Components — RSI 14, band 80:20):** Not overbought >75 for CE; RSI 60+ for CE, RSI 40 and below for PE; 40-60 is a no-trade zone. On gap-down opens where RSI is already oversold, wait for cool-off back to resistance.
  6. **VWAP timing filter:** Do NOT use the morning VWAP for trades until 10:30 AM. Before 10:30 AM use ONLY analysis of the previous day close + global cues. If the market is below yesterday's VWAP, keep looking for a selling opportunity until it moves above yesterday's VWAP.
  7. **Time-of-day:** Trade is taken on the open (worked example 9:16 AM); the broader strategy-suite "ideal window 9:15-10:00 / trade after 9:45am" applies as a general filter, but the morning trade itself is explicitly an opening-tick scalp executed in the first candle(s). (Note the apparent tension between the general "after 9:45am" matrix rule and the opening-trade execution at 9:16 — for the morning trade, the opening-tick timing governs.)
  8. **News/events:** If external news/movement after prior close invalidates EOD positioning, the EOD-based view no longer holds — stand aside.

- **Execution Notes & Edge Cases:**
  1. Gap opens are the core context: on a gap-down (e.g. 2nd Feb 2023 due to news flow), the attempt to reach previous day close fails and a rejection wick is the trigger.
  2. Whole trade can live inside the first 3-minute candle (initiate and close).
  3. Flat / no-movement opens: with hardly any index movement (e.g. flat first 5 minutes on Day 9, 28th Sep 2023), still possible to take the data-driven side (45000 PE example) but with small quantity and expecting only a brief move; book on the first small rejection.
  4. Gap-down + already-oversold RSI: do not enter into the fall; wait for RSI to recover to resistance.
  5. VWAP is not actionable before 10:30 AM — rely on previous-day close analysis and global cues only.
  6. Despite gap-up breadth (advances >100, declines <50 on Day 9), data + global cues can still point the opposite way (PE side) — let the data/cues, not raw breadth alone, set direction.

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Opening Trade Strategy Mentoring.pdf (Session 20, Day 8 — primary slide deck)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (Session 20 — master checklist matrix, "MORNING TRADE" column)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Day 08_LMP23 26th Sep 2023.pdf (Session 20, Day 8 live notes)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf (Session 20, Day 9 live application)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 10/Day 10_LMP23 3rd Oct 2023.pdf (Session 20, Day 10 — VWAP-before-10:30 filter)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf (Session 20, Day 11 — oversold-RSI gap-down edge case)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf (Session 20 — prior-day EOD/FII-DII prep)
  - Original PDF: StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Opening Trade Strategy Mentoring.pdf

---

### 3.10 Options Scalping Framework (Connect the Dots) (original label: Connect The Dots — Become Successful Options Scalper)

- **Status / Versioning:** Introduced in Session 20 (2023), Day 2 (slide deck "Connect The Dots — Become Successful Options Scalper Day 2"), with indicator settings and execution refinements added across the same Session-20 live-mentoring days (Day 4 / 20-Sep, Day 5 / 21-Sep, Day 6 / 22-Sep). Current; re-taught & refined in Session 21 (Live Scalping Mentoring 2024) — see the Session-21 note below and §5.10.

- **Session 21 update (re-taught Day 2 deck + live Day 1/2/5/11; + pre-doc VIX/OI/Global):** Confirms the Session-20 framework (5 chart dots on 3-min: VWAP / ST 10,2 / Volume 50K BN-125K N / RSI 14 80:20 / PSAR 0.02,0.2; macro dots: Dow 30, India VIX, OI Spurts 4 quadrants, OI strikes & futures, IV across 6 strikes; aggregate must read Bullish/Bearish; intraday variant uses ST 7,3 on 15m/1h). Refinements: (a) Connect-the-Dots is **intraday-only**; pick the timeframe by trader type (scalp vs intraday) (Day 11); (b) the confirmation stack is read at a **single decision moment** and, when signals net to "cautious," trade low quantity, do not chase up-moves, and enter only on a retrace to Supertrend/VWAP support (pre-doc VIX/OI/Global); (c) the RSI **profit-booking ladder** is reaffirmed — long: book 90% at 75–80, last 10% at 85; short mirror 25–20 / 15; RSI 40–50 = no-trade (Day 5); (d) trade **OSPL (AI) signals** regularly and confirm each with Trending OI + Futures data (Day 11); the recommended small-capital set is OSPL, Trending OI Crossover, Open=High and 2-Candle (Day 10). New shared inputs feeding the "dots" — VIX regime bands, futures-vs-spot basis, index-constituent contribution, pre-open data — are defined in §4.14.

- **Session 22 update (reused Day-2 decks — "Connect The Dots" / "How Can One Become A Successful Option Scalper" — + Day-1/2 synopses + the chart-indicator & 2-candle blocks of the Consolidated Synopsis):** Confirms the entire framework verbatim (5 dots on 3-min: VWAP / ST 10,2 / Volume 50K BN-125K N / RSI 14 80:20 / PSAR 0.02,0.2; macro dots; the RSI booking ladder 90% at 75–80, last 10% at 85 / mirror 25–20, 15; ST 7,3 on 15m/1h; VWAP = "the most important indicator"). Refinements: (a) **VWAP-distance position sizing** — deploy **maximum quantity when price is close to VWAP**; if the VWAP-to-candles gap is wide, wait for fresh entries; (b) **≥50% Call-vs-Put OI difference** is needed for the market to trend on the heavier side (a directional-conviction gate distinct from the OI-Spurts quadrant rule); (c) prefer **ATM/ITM for quick 10–20-pt scalps** (deep-OTM buyers get wiped ~90% of the time); (d) **scalp hold = ~1 to 30 minutes** on a 3/5-min chart; (e) a **profit-as-SL floor** — use the previous day's profit (or 10% of deployed capital) as the day's first stop-loss; (f) a concrete fund cap for a ~1-lakh scalping account — **≤₹25–30k deployed per trade**, targeting 1% of deployed capital; (g) VWMA pinned to **length 20**; (h) the new **Trending OI + PA** feature overlays LTP change on OI change (see §4.15). **Resolves the §7 "Sell PE/Sell CE = naked selling?" question** in the buy-side direction — the framework is buying CE/PE (or futures); "Sell PE/Sell CE" are directional synonyms only. No rule deprecated.

- **Purpose & Market Context:** A confluence ("connect the dots") scalping framework for index options. The trader aggregates every available market factor into a single directional read before firing a trade: global cues (DOW 30 futures), India VIX, OI Spurts (4 quadrants), OI on strikes & futures, IV across 6 strikes, plus a 5-indicator price chart (VWAP, Supertrend, Volume candles, RSI, Parabolic SAR). The core principle: only trade when the dots line up (all/most factors agree on direction). VWAP is treated as the single most important chart factor — it is where buyers/sellers defend, and the wider the gap between price and VWAP the stronger the trend. The framework is for intraday CE/PE scalping (buy options), with the option of buying futures / cash (no stock options) on the long side. The 60-minute aggregated view sets the day's bias; a smaller (3-min) timeframe confirms entries/exits.

- **Instruments & Timeframes:**
  - Instruments: Index options — CE (calls) / PE (puts) on Nifty and Bank Nifty (buy side; "Sell PE / Sell CE" referenced as directional synonyms). Direction read on the Bank Nifty 3-min futures chart. Long side may also be expressed as Buy Futures or Buy stock in cash market (no stock options).
  - Primary scalping timeframe: 3 minutes (the "5 Chart Dots — 3 Min TF").
  - Broader bias: 60-minute aggregated "Connecting the Dots" view (if most factors red → bearish day; mirror for bullish).
  - Supplementary: RSI checks on 5-min and Daily TF; Trending OI graph 5–15 min; OI Spurts 4-quadrants.
  - Supertrend timeframe note: scalping uses the 3-min chart with ST(10,2); for an *intraday* (non-scalp) variant the ST settings change to 15-min/1-hour, Length 7, Factor 3 — only the Supertrend settings change between scalping and intraday.

- **Setup & Preconditions:**
  1. Trade only after 9:45am — the framework-native time gate; let risk:reward be ~1%. (The "ideal window 9:15–10:00am / by 9:45 many moves may already have happened" caveat is the **O=H/O=L grid Desirable** (§3.2), borrowed when scalping an O=H setup — the 2-CANDLE / Connect-the-Dots column's only time rule is "after 9:45am".)
  2. Build the chart with the 5 dots on the 3-min TF: VWAP (default), Supertrend (10, 2), Volume candle threshold 50K for Bank Nifty / 125K for Nifty, RSI 14 (band 80:20), Parabolic SAR (0.02, 0.2). VWMA (default length) is added as a confirmation overlay alongside Supertrend.
  3. Read the macro dots before entry: DOW 30 futures, India VIX, OI Spurts (4 quadrants), OI on strikes & futures, IV on 6 strikes. Global cues (DOW, dollar index, Asian markets, oil) must align with intended direction.
  4. Confirm the "Connecting Dots" aggregate is Bullish (for longs) or Bearish (for shorts) — i.e. most factors agree.
  5. Volume must confirm: a valid signal/volume candle needs ≥50K (Bank Nifty) or ≥125K (Nifty).
  6. Strike selection: framework-native cue = buy-side **delta 0.6–0.7** + the AI-suggested strike inside the price range. (The **ATM ±3** count and the **premium bands 100–250 Nifty / 250–400 Bank Nifty** are borrowed from the **O=H/O=L grid column** (§3.2 / shared §4.9 strike-selection) — the 2-CANDLE / Connect-the-Dots column carries only the delta rule, no ATM-count or premium-band row.)
  7. Check the strike's OI interpretation: Long Build-up (LB) or Short Covering (SC) preferred for longs; Short Build-up (SB) or Long Unwinding (LU) for shorts.

- **Entry Rules — Bullish:**
  1. Confirm trade is taken after 9:45am.
  2. On the 3-min Bank Nifty futures chart, see 2 GREEN candles with volume above 50K (125K Nifty).
  3. RSI not overbought — RSI in the 50–75 Buy Zone (or allow it to cool off). RSI 40–50 is a No-Trade Zone; RSI must be moving above 50.
  4. Second candle is strong and all indicators are below price.
  5. No major resistance nearby (low probability if there is).
  6. Futures chart shows Long Build-up or Short Covering.
  7. Strike's OI interpretation is LB or SC; OI build-up = Call OI declining / Put OI increasing.
  8. Trending OI cross-over present and the gap is widening (Put crosses Call OI in the 5–15 min trending OI graph; Sentiment graph sloping up).
  9. Selected buy strike has 0.6–0.7 delta (framework-native); within ATM ±3; premium 100–250 (Nifty) / 250–400 (BN) (ATM ±3 + premium band = O=H-column borrows, see Setup 6).
  10. VIX is going down (cooling) while market moves higher → bullish; "Connecting Dots Bullish?" must be YES.
  11. (Desirables / confirmations) ST & VWMA cross above VWAP together (Golden Cross) with cross-over volume ≥50K BN / 125K N; Parabolic SAR switches to bullish; breakout from a support/resistance line confirms direction; IV rising in that strike. Advance/Decline: adv>32 = CE side. IV interpretation cue: CE/PE 30/20 (≥10-point higher-IV difference on the up-side) supports a bullish trend play; 10/10 low IV is good for a trend play.
  12. Enter when momentum is up / volume is picking up.

- **Entry Rules — Bearish:**
  1. Confirm trade is taken after 9:45am.
  2. On the 3-min Bank Nifty futures chart, see 2 RED candles with volume above 50K (125K Nifty).
  3. RSI not oversold — RSI between 40–25 (Sell Zone) preferable; RSI moving below 50; RSI higher than 25 on the 3-min chart.
  4. Second candle is strong and all indicators are above price.
  5. No major support enroute (low probability if there is).
  6. Futures chart shows Short Build-up or Long Unwinding.
  7. Strike's OI interpretation is SB or LU; OI build-up = Call OI increasing / Put OI declining; substantial vs its CE strike on the options chart.
  8. Trending OI cross-over present and the gap is widening (Call crosses Put OI in the 5–15 min trending OI graph; Sentiment graph sloping down).
  9. Selected buy (PE) strike has 0.6–0.7 delta (framework-native); within ATM ±3; premium 100–250 (Nifty) / 250–400 (BN) (ATM ±3 + premium band = O=H-column borrows, see Setup 6).
  10. VIX is going up while market moves lower → bearish; "Connecting Dot Bearish?" must be YES.
  11. (Desirables / confirmations) ST & VWMA cross below VWAP together with cross-over volume ≥50K BN / 125K N; Parabolic SAR switches to bearish; breakout/breakdown from S/R confirms direction; IV falling in that strike. Advance/Decline: dec>32 = PE side. IV interpretation: 40/40 → stay away or play short straddle.
  12. Enter when momentum is down / volume is picking up.

- **Exit Rules:**
  - Target: aim not more than 1–2% (let RR ~1%); target the next resistance (longs) / support (shorts) as the case may be. (The "30–50 points good enough / exit ~5 pts below the open-high / above the open-low" figure is the **Open=High/Open=Low** exit, §3.2 — borrowed only when this framework is used to scalp an O=H setup, not a native Connect-the-Dots target; the matrix 2-CANDLE / Connect-the-Dots column carries no point target.)
  - RSI-based profit booking (long side): RSI 75–80 = Profit Booking Zone → book 90% quantity; RSI >80 = Overbought; at RSI 85 book the remaining 10%. (Short side mirror): RSI 25–20 → book 90%; RSI <20 = Oversold; at RSI 15 book remaining 10%. Do NOT over-extend holding above RSI 85 / below RSI 15 — bounces and reversals can be huge and quick.
  - VWAP-based scalp exit: a counter-trend / quick scalp bounce can be managed only up to the VWAP level. If price breaks VWAP **with volume**, the trend is reversing → exit. If price breaks VWAP **without volume** it is a fake breakout (price returns into VWAP, no follow-up, opposite-side SLs get hit, then sharp move) — do not chase.
  - Stop-loss: 1st candle low is the SL (bullish); 1st candle high is the SL (bearish). On gap-fill entries, trail SL 5 points below (longs). Trail SL 5 points below the gap reference on gap trades.
  - Scaling/management: support trade — enter only at the Supertrend level when price pulls back to it after an upside crossover with bullish data; the trade stays defined even if price breaks ST toward VWAP (you knew VWAP was a possibility). Take pull-back entries near VWMA / ST / VWAP.
  - Time exit: morning trade is for scalping only — finish the trade once target/SL is hit; do not carry. (For any positional spillover: trail once a decent profit is earned.)

- **Strategy-Specific Risk Management:**
  - Position aim: not more than 1–2% per trade; let risk:reward target ~1%.
  - Stop-loss anchored to the structural candle (1st candle low/high) — see Exit Rules. Reference the Global Risk Framework for sizing, daily loss cap, and per-trade risk %.
  - Night-risk: not more than 1 night risk; avoid Friday (carry context — primarily relevant to BTST, included here as a stated cap).
  - VWAP fake-breakout discipline: never add against a no-volume VWAP break; opposite-side stops getting hit is a trap, not a signal.
  - Do not over-extend beyond RSI 85 / below RSI 15.

- **Filters & Conditions:**
  - Time-of-day: after 9:45am only (framework-native); ideal 9:15–10:00 (O=H Desirable borrow, see Setup 1); no new entries before an impending event after 3:30pm (keep off). (Sideways 11am–1pm caution from the common framework.)
  - VIX/volatility — see **§4.5** (India VIX directional rules); directional cue: Market up + VIX cooling = Bullish, Market up + VIX rising = Bearish.
  - OI Spurts (4 quadrants, ≥50% OI/price gates) — see **§4.3.2**; cue: Rise in OI + Rise in Price = Bullish, Rise in OI + Slide in Price = Bearish.
  - OI build-up / Trending OI: Trending OI cross-over with widening gap; LB/SC for longs, SB/LU for shorts.
  - IV (6 strikes) interpretation — see **§4.6**; cue: 10/10 = low IV, good for trend play; 40/40 = stay away or play short straddle.
  - Global cues: DOW 30 futures, dollar index, Asian markets, oil must match direction. Advance/Decline (Nifty): adv>32 = CE, dec>32 = PE.
  - RSI bands (RSI 14, low-TF band 80:20): 40–50 No-Trade; long Buy Zone 50–75; short Sell Zone 40–25; overbought >75/80; oversold <25/20. RSI(5m) below 75/80 and RSI(Daily) below 75 for longs; mirror (above 25/20, above 25) for shorts.
  - Strike filter: delta 0.6–0.7 + AI-suggested strike (framework-native); ATM ±3 and premium 100–250 N / 250–400 BN are O=H-column borrows (see Setup 6).

- **Execution Notes & Edge Cases:**
  - Fake VWAP breakout: price taken above VWAP without volume, then dragged back into VWAP territory with no follow-up; opposite-side stop-losses get hit and the market can then fall sharply (live example: Bank Nifty fake breakout above VWAP without volume). Treat a no-volume VWAP break as a fake, not a trend change.
  - Support trade at Supertrend: after an upside crossover with the market consolidating and data turning bullish, take the support (buy) trade only when price comes close to the Supertrend level; price may break ST toward VWAP and shake confidence, but because entry was at ST the trade stays within plan.
  - Parabolic SAR is the first indicator that shows where the trend is and how long it can persist in the same direction — use SAR flips as an early trend cue.
  - Intraday vs scalping: only the Supertrend settings change (scalp 3-min 10,2 vs intraday 15m/1h 7,3); the rest of the dots stay the same.
  - UNCERTAIN — needs confirmation: the "Sell PE / Sell CE" labels in the matrix appear as directional synonyms for the buy-side trade rather than explicit naked-option-selling instructions; the framework's primary expressed instrument is buying CE/PE (or buying futures/cash). Confirm before treating as a short-option-selling strategy.

- **Source References:**
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Connect The Dots - Become Successful Options Scalper Day 2.pdf (Session 20, Day 2 — primary slide deck)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx (master checklist matrix — "2 CANDLE" / Connect-the-Dots Bullish / Bearish / Desirables column block)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Connect The Dots - Become Successful Options Scalper Day 2.pdf (original slides; image-embedded rules — text extraction was complete, PDF renderer unavailable)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 4/Day 04_LMP23 20th Sep 2023.pdf (Session 20, Day 4 — indicator settings, RSI zones)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/Day 05_LMP23 21st Sep 2023.pdf (Session 20, Day 5 — aggregation, VWAP scalp/fake-breakout)
  - StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Day 06_LMP23 22nd Sep 2023.pdf (Session 20, Day 6 — support trades at ST/VWAP)

---

### 3.11 Straddle (Long & Short) (original label: Straddle Strategy)

- **Status / Versioning:** **Introduced in Session 22 (Live Mentoring Prog 2.0 2024), Day 11** ("Straddle Strategy 10th Mentoring Day 11" deck + the dedicated Straddle section of the Consolidated Synopsis). This is the only strategy NOT present in Sessions 20/21. No prior-session baseline exists, so every rule below is Session-22 content. (Some dated examples in the deck reuse 2023 chart days, but the execution rules — VWAP entry/SL, 5-min TF, time windows, exit triggers — are the genuine S22 spec.)

- **Purpose & Market Context:** A **neutral / volatility** options strategy that trades BOTH legs (Call and Put) of the **same ATM strike and same expiry** at once, used when the direction is unclear but a particular volatility outcome is expected. Two variants:
  - **Long Straddle** — BUY the ATM Call + ATM Put. Used when you expect a **wild move on one side** (typically around a news/event — Budget, election result) but don't know which side. You profit when the underlying moves **more than the total premium paid** away from the strike. Best deployed when **IV/premiums are LOW**; a long straddle bought in a high-IV environment can lose on BOTH legs when the IV crashes even if the market moves.
  - **Short Straddle** — SELL the ATM Call + ATM Put. Used when you expect the market to stay **range-bound**, harvesting **premium decay** on both legs. Best when Call-side and Put-side IV are similar and the Trending-OI change on both sides is moving gradually together (a sideways day). Carries **unlimited risk** if volatility breaks out.

- **Instruments & Timeframes:** Index options — Bank Nifty (deck examples) / Nifty / Fin Nifty. Both legs = **ATM, same strike, same expiry**. Charted on a **5-minute** straddle chart (the combined-premium series) read against its own VWAP; the individual Call and Put charts/VWAPs are also watched. See §4.15 for the straddle-chart concept.

- **Setup & Preconditions:**
  1. Decide the variant from the volatility view: **event / expected big move + LOW IV → Long**; **range expectation + similar both-side IV → Short**.
  2. Compute the **breakeven**: the underlying must move more than the **combined premium** of the two legs from the strike (e.g. a combined premium of ~1000 needs a >1000-point move — do not buy a long straddle when premiums are already that rich).
  3. Confirm with **Trending OI**: change-in-OI on both sides moving **together** (no large Call-vs-Put divergence) supports a **short** straddle (sideways day); a Trending-OI clue of an impending break supports a **long** straddle.
  4. **Strike:** default **ATM** (highest extrinsic/time value — most sensitive to the move). For a safer bet, OTM strikes can be used (one leg becomes ITM for the other side), but ATM is preferred over deep OTM.

- **Entry Rules — Long Straddle:**
  1. Deploy ONLY when **IV and premiums are low**.
  2. **Entry trigger:** the **combined straddle price breaks ABOVE its VWAP line, with volume** (signals sellers are in panic — the buyer's edge). On the event/budget form: after **~12:30 PM**, enter after **price closes above the VWAP of both the Call and the Put**.
  3. **One-leg management:** once the combined straddle is above VWAP and only one leg is producing the gains, **exit the losing leg and hold the single winning leg** to capture more of the directional move.
  4. Worked example (12 Sep 2024): a long straddle entered at **2:10 PM** on the straddle-price VWAP break made ~**200 points** by end of day (slightly more if one leg was dropped).

- **Entry Rules — Short Straddle:**
  1. Deploy when a **range-bound** day is expected and **both-side IV is similar**.
  2. **Entry trigger (Bank Nifty, 5-min chart):** after **9:30 AM**, enter after **price falls BELOW the VWAP of both the Call and the Put**.
  3. Hold while VWAP holds — the trade works as long as the combined price stays below VWAP.
  4. Worked example: selling the 54000 Call + Put at ~9:30 AM at a combined ~**280** made **100+ points** by 11 AM because **VWAP was never breached**.

- **Exit Rules:**
  - **Long Straddle:** exit on a **lower-low candle formation**, or when the **combined premium peaks and begins to roll over** (e.g. retracing from ~1800 to ~1700/1600 — book whatever profit/loss is there rather than waiting for a full reversal). Optionally hold only the winning leg (see Entry 3).
  - **Short Straddle:** exit on **considerable premium decay** or at **end of day**; exit immediately if price breaks back through VWAP (the combined-price VWAP break is the cue to be **out**, and is itself a long-side/buyer cue — see §4.14.8).
  - **Numeric P&L example (long, market 54000):** Call ATM 70 + Put ATM 50 = combined **130**. If it closes at 54000, both legs go to **zero** (lose 130, all time value gone). If it closes at 54300: Call ATM **300**, Put ATM **0** → profit = 300 − 130 = **170 points**.

- **Stop-Loss:**
  - **Long Straddle: SL = BELOW the VWAP** (of both legs).
  - **Short Straddle: SL = ABOVE the VWAP** (of both legs).

- **Strategy-Specific Risk Management:**
  - **Short straddle has UNLIMITED risk** in a high-volatility breakout — both legs' stop-losses can be hit. On low-volume days, **freak candles around the VWAP/WMA can trigger an ATM straddle seller's SL multiple times intraday** (observed: a 23700 Fin Nifty straddle's SLs could have been hit at least 4 times). A **hard SL above VWAP is mandatory.** The source does allow deploying a short straddle **before an event when there is no clear direction** (a range/decay expectation) — but the unlimited-breakout risk means it must stay a range bet with the SL respected, never a directional gamble.
  - **Long straddle** risk is the **combined premium paid**, but an **IV crash** (after premiums peak / once an event is done) can erode both legs even if the market moves — hence the low-IV-entry gate.
  - Trade only from a slice of profits (Global Risk Framework §2). Use the combined-premium breakeven to size the expected move realistically (do not pay 1000 of premium expecting a 100–200-point move).

- **Filters & Conditions:**
  - **IV (see §4.6):** Long → enter only at **LOW IV/premiums** (a high-IV long straddle loses both legs on an IV crash). Short → wants Call-side and Put-side IV **similar/equal**; S22 also gives the general rule **IV above 40 → stay away as a buyer**, and the existing §4.6 IV table lists a **40/40** reading as the "stay away / play short straddle" condition.
  - **Trending OI:** change-in-OI moving together = range = short-straddle day; a divergence/break = the long-straddle/directional case.
  - **VWAP** is the single anchor for both entry trigger and SL on every variant (Long: above VWAP with volume / SL below; Short: below VWAP / SL above).
  - **Events:** Long straddles are the **event play** (Budget, election result, major news) where a big move is likely but the direction is unknown.

- **Execution Notes & Edge Cases:**
  - The **straddle chart** (combined Call+Put premium as one series, read against its own VWAP) is the key tool — it both times the entry (VWAP break) and enables the one-leg-management exit.
  - Long straddle on an event: if the combined premium is already rich (e.g. ~500 each = ~1000 combined), the required move is too large — skip; a long straddle only makes sense when premiums/IV are low.
  - A short-straddle price breaking VWAP is the **buyer's** entry cue (trend day / near the close) — see §4.14.8.

- **Source References:**
  - StrategySources/OptionsScalperSiva/22 Live Mentoring Prog 2.0 2024/Day 11/Straddle Strategy 10th Mentoring Day 11.pdf (Session 22, Day 11 — Straddle deck: definitions, long/short execution, examples)
  - StrategySources/OptionsScalperSiva/22 Live Mentoring Prog 2.0 2024/Consolidated Synopsis LMP 2.0.pdf (Session 22 — "Straddle strategy" section: live 12 Sep'24 long straddle, 54000 short straddle, budget long straddle, one-leg management, ATM/OTM strike selection, freak-candle SL warning)
  - StrategySources/OptionsScalperSiva/22 Live Mentoring Prog 2.0 2024/Day 11/Day 11 Synopsis LMP 2.0.pdf (Session 22 — expiry-day market view; note: contains no straddle execution rules)

---

### 3.12 Trend Change Strategy (original label: Trend Change Strategy)

- **Status / Versioning:** **Introduced in Session 23 (Sensex Scalping with Siva, June 2025), Day 10** (dedicated deck "Trend Change Strategy" + live teaching in the Day 10 synopsis, which lectures Trend Change alongside Hero-Zero on a Nifty-expiry sideways day). This is the 12th strategy and is NOT present in Sessions 20/21/22, so there is no prior-session baseline — every rule below is Session-23 content. (The deck's two worked trade examples are dated 2023 chart days — a sideways→uptrend day and a 21 Feb 2023 lower-low→higher-low day, each ~400 points — but the execution spec [Trending-OI shift + RSI>60 + volume + 2-candle 3rd-candle entry] is the genuine S23 teaching, reinforced live across the S23 dailies: Day 03 [failed-resistance reversal + heavy-volume confirmation], Day 07 [live ~10 AM bearish flip], Day 11 [intraday-bearish/positional-bullish precondition], Day 12 [morning-bearish→bullish flip held above the prior-day trendline].)

- **Purpose & Market Context:** A **reversal-capture** strategy that trades a **SHIFT IN DIRECTION** rather than a continuation breakout — "a trend change is simply a shift in direction; a shift in direction can result in a spurt/squirt of momentum." The premise is that catching the turn (sideways→up, sideways→down, downtrend→uptrend, or vice-versa) can give **magnified profits** and sometimes better results than a breakout. The trader must **adapt intraday** — do not stay a call-side buyer all day just because you bought in the morning; learn to play up-moves and down-moves as conditions flip. Three trend types are defined: **upward, downward, sideways** (sideways = a zigzag that goes higher, comes back, goes lower, returns to the median). The change is read off **DATA that indicates a shift in momentum** — primarily the **Trending OI** (see §4.4), supported by **RSI, VWAP** and the chart indicators (see §4.2). Crucially, the trend-change information is often visible in the Trending-OI **~15–30 minutes BEFORE** price confirms (Day-10 synopsis: the OI flipped bearish by ~11:00–11:15 AM while the two-candle/price confirmation printed ~11:30 AM; the deck's own two trade examples show the OI shift starting ~7–15 min before entry — 12:20→12:27 PM and 2:00→2:15 PM). **Momentum is mandatory:** even if price moves, if the OI data does not shift, do NOT take the trade — without continuing momentum the "move" is just a 5–10-point fake-out.

- **Instruments & Timeframes:** Index options/futures — in S23 the live vehicle is **Sensex options traded off the Nifty chart** (Nifty/Bank Nifty also applicable; see the Sensex methodology in §2.13). Trend identified via **trend lines** (horizontal/vertical/diagonal; also triangles, pyramids, harmonic patterns) and **price-action swings** (higher-highs/higher-lows vs lower-highs/lower-lows). Confirmation/entry uses the **2-candle (true-candle) theory** on the 3-minute chart per §3.1 (3rd-candle entry). The momentum shift is read on the **Trending OI** tool (§4.4). Multi-timeframe S/R: weekly/monthly trend lines and key event lows (e.g. the 4 Jun 2024 election-result low) act as multi-year support; converging 20- and 50-day moving averages act as major resistance; a long lower wick after a continuous fall marks a temporary 2–3 month base.

- **Setup & Preconditions:**
  1. **Identify the prevailing trend first** (upward / downward / sideways) using trend lines and/or price-action swings; a reversal is only meaningful against a defined prior trend. Most reversal setups in S23 start from a **sideways/range day** (e.g. the Nifty 24,800 support / 24,900 resistance box).
  2. **Spot the swing-structure break:** higher-highs/higher-lows pause and a lower-high/lower-low forms (down-reversal), or lower-lows/lower-highs pause and a higher-low/higher-high forms (up-reversal).
  3. **OR a trendline break** in the reversal direction.
  4. **Require a momentum shift in the Trending OI** that corroborates the price/trendline break — this is the primary confirmation (see Entry Rules for the directional OI conditions). A big call/put **OI crossover** marks the shift (Day-10 synopsis live example: the ~11:00 AM crossover; the deck's two trade examples mark the shift at ~12:20 PM and ~2:00 PM). Without an actual change in OI, treat moves as 5–10-point fake-outs and skip.
  5. **Precondition cue (live, Day 11/12):** an intraday-data-bearish but positionally-drifting-bullish read (or vice-versa) is the structural set-up Siva watches before a reversal; the flip is confirmed when BOTH intraday and positional OI rotate to the same side.
  6. **Timing window:** a trend change can occur **any time between ~9:45 AM and ~2:30 PM** — it does NOT have to print by 10 or 11; watch the charts and data all day. **Avoid the morning prints/volatility** — a presumed big move can reverse within 2–3 candles and lose 50–70% if naked; wait for the intraday trend to form.

- **Entry Rules — Bullish (up-reversal):** All conditions required.
  1. Prior structure is **sideways or a downtrend**; price forms a **higher-low / higher-high** (swing-structure break) OR breaks the down-trendline to the upside.
  2. **Trending-OI momentum shift to bullish:** **Call-side OI falling and Put-side OI rising** — call writers exiting positions and put writers adding puts gradually (shorts covering + put writers adding = momentum). The deck quantifies the required shift live: for the (mirror, bearish) example the call OI had to move **1.72 cr → 2.5–3 cr** while put OI dropped **4.5 cr → ~3 / 2.5 cr**; for an up-reversal apply the mirror (call OI falling, put OI rising) to a comparable degree before acting.
  3. **RSI above 60** (per the deck's explicit "RSI is above 60 … then you can change it") AND the sideways range / trendline is **broken**.
  4. **Confirm with VOLUME:** the breakout candle must come **with an increase in volume + follow-up volume bars** (volume thresholds per §4.2 — 50K BN / 125K N).
  5. **2-candle (true-candle) confirmation:** once the level breaks with volume, **enter on the 3rd candle** per §3.1; exit once it moves in your favour.
  6. Instruments: buy CE / sell PE / buy futures (S23 live = ITM Sensex CE off the Nifty chart).

- **Entry Rules — Bearish (down-reversal):** Mirror of bullish; all conditions required.
  1. Prior structure is **sideways or an uptrend**; price forms a **lower-high / lower-low** (swing-structure break) OR breaks the up-trendline to the downside.
  2. **Trending-OI momentum shift to bearish:** **Call-side OI rising and Put-side OI falling** — call writers building from a high level while put writers unwind (deck example: call OI 1.72 cr → 2.5–3 cr while put OI 4.5 cr → ~3 / 2.5 cr; live Day 07: call OI 4 cr → 6.5 cr while put OI 4.4 cr → 3.92 cr around 10:00 AM confirmed the flip). Without that OI change, moves are just 5–10-point fakes.
  3. **RSI below ~40** (mirror of the >60 up-reversal trigger; per the §4.2 bands the bearish/no-trade boundary is 40) AND the sideways range / trendline is **broken to the downside**.
  4. **Confirm with VOLUME** on the breakdown candle + follow-up bars (§4.2 thresholds). On a downside break, volume is mandatory; **if it is already after ~2:30 PM, AVOID the trade.**
  5. **2-candle (true-candle) confirmation:** enter on the **3rd candle** after the level breaks with volume; exit once it moves in your favour.
  6. Instruments: buy PE / sell CE / sell futures.

- **Exit Rules:**
  - **Target:** ride the reversal move; the deck's two worked examples each captured **~400 points** (a sideways→uptrend day and a 21 Feb 2023 lower-low→higher-low day). No fixed per-trade point target is specified beyond "exit once it moves in your favour" / ride to VWAP.
  - **VWAP as the patience/target line:** "the line which I needed to be respected the most is the VWAP" — once the trend is formed, **do not worry unless VWAP breaks**; sellers/buyers will test your patience and try to hit SLs before the real move, so holding to VWAP was the rewarded play. Do NOT enter on a VWAP break that **lacks volume** (price snaps back fast when VWAP is not broken with volume).
  - **Entering late is acceptable:** you may not capture the entire move — entering after the OI crossover yields smaller profit, and that is fine ("sometimes we may not be able to capture the entire trend-change move").
  - **Time exit:** on a downside reversal, avoid the trade after ~2:30 PM (the deck's stated "if it is already after 2.33, avoid it").

- **Stop-Loss:**
  - **No explicit numeric (point/percent) stop-loss is given for this strategy in the S23 sources.** The 2-candle entry implies the §3.1 structure SL (1st-candle low for a long / 1st-candle high for a short), and **VWAP is the structural defend line** (exit the reversal if VWAP breaks WITH volume). **Size the SL off structure & VWAP per the Global Risk Framework (§2).**
  - **Benefit-of-doubt allowance:** Siva permits a small **~10–20 extra points** of leeway against the SL **only when the OI data is convincingly confirming** the direction; SL hits while following the process are acceptable. Trail/raise the SL after a profitable leg so gains are not given back.

- **Strategy-Specific Risk Management:** (Global Risk Framework §2 governs sizing/daily-cap; strategy-specific points below.)
  - **Wait out the morning volatility** — newbies especially should avoid morning prints; a wrong naked entry on a presumed big move can lose 50–70% when it reverses in 2–3 candles. Trade only once the intraday trend forms; if no trend, stay light.
  - **Do not chase a direction when premiums are higher on that side and the market has no positive cues** — a higher premium on one side into a stuck market is an indirect warning and can trap you completely.
  - **VWAP discipline:** never enter on a VWAP break without volume; once a trend is formed, treat a VWAP break (with volume) as the invalidation.
  - **Scale expectations to the regime:** on a low-VIX expiry a 10–15-point Nifty move is "a big hit" — do not expect large moves when the index value is small / VIX is suppressed.
  - **Benefit-of-doubt only with convincing data** (~10–20 pts); otherwise honour the SL.

- **Filters & Conditions:**
  - **Trending OI (§4.4) is the primary momentum filter:** a big call/put OI **crossover** marks the shift (call OI up + put OI down = bearish shift; reverse = bullish). When the two OI graphs **climb together**, it is a **strict AVOID** as a buyer (no directional edge). After ~2:30 PM both OIs falling = participants squaring off morning positions — the action is over, do not chase.
  - **RSI (§4.2):** up-reversal needs **RSI above 60**; down-reversal needs RSI below ~40 (the §4.2 no-trade band is 40–60). Momentum must be present.
  - **VWAP (§4.2):** the most-respected line — defines whether the reversal holds; a VWAP break must come with volume to be trusted.
  - **Volume (§4.2):** the validator for the breakout/breakdown — increase in volume + follow-up bars (50K BN / 125K N) is mandatory; a break on no/low volume is unreliable and tends to reverse.
  - **Support/Resistance from seller OI:** max call OI = resistance, max put OI = support (e.g. 24,900 resistance / 24,800 support on the Day 10 box); these define the range that must break for the reversal.
  - **VIX (live cue, Day 10):** a rising VIX into the reversal direction supports it (e.g. VIX climbing as a down-reversal develops = shorts coming in); a flat VIX warns the move lacks conviction.
  - **Index-contribution / heavyweights:** a real reversal needs the heavyweight drivers (Reliance, Infosys, TCS, banks) to stop pushing the prior way and support the new direction; on Day 10 a handful of stocks (Reliance, M&M, Kotak, L&T up ~66 pts vs others dragging ~70–80 pts) kept the index range-bound, blocking the change.
  - **Time-of-day:** valid 9:45 AM–2:30 PM; avoid morning prints; avoid a fresh down-reversal after ~2:30 PM.

- **Execution Notes & Edge Cases:**
  - **Data leads price by ~15–30 min:** the trend-change clue is often in the Trending OI before price confirms (Day-10 synopsis: ~11:00–11:15 AM OI flip vs ~11:30 AM two-candle confirm; the deck's trade examples ~7–15 min; live Day 07 confirmation around 10:00 AM after the early-morning bullish read flipped). Watching the OI lets you prepare, but you still wait for the volume + 2-candle confirmation to enter.
  - **Failed-attempt reversal (Day 03):** multiple failed attempts (1-2-3) to clear a resistance area, followed by failure, precede a reversal/tank; confirm a downside reversal with heavy volume (Day 03 cited 2–3 consecutive red bars >125K each) while mild volume at support favours recovery.
  - **Trendline/structure pivot (Day 12):** a held prior-day trendline / critical support that price "never went below" can be the reversal pivot for an up-reversal; a deep Parabolic-SAR position below price gave at least one bounce-possibility cited as a reason to take the long reversal.
  - **Consolidation vs continuation:** after a trend change, positions being added on BOTH call and put sides = consolidation / sideways (a pause, not a continuation); for a genuine reversal of an established down-move the dominant unwinding volume must be beaten on an hourly basis AND there must be a long build-up or short covering — otherwise the counter-move is weak (Day 07).
  - **Post-vertical bounce caution (Day 07):** after a vertical fall, expect a possible severe vertical bounce as RSI nears oversold (~20–23); do NOT take a reversal trade until RSI recovers toward ~40 and a defined level is reached; a reversal into a structure where sellers created NO new put-side positions (only giving up positions) gets sold into.
  - **News overrides data:** on gap/event/war-news days "throw the data out of the window" — news takes precedence; trade smaller and confirm with how price behaves after the open.
  - **Strong-trend / late-entry difficulty:** on a strong reversal/trend day, entries are hard to catch — once you miss the move, wait for pullbacks to support to enter; do not chase.

- **Source References:**
  - StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Session PPT_s/Day 10/Trend Change Strategy.pdf (Session 23, Day 10 — dedicated Trend Change deck: definition of trend/trend-change; identification via swing-break, trendline break, and Trending-OI momentum shift; the 6-point trade-opportunity checklist [data shift in Trending OI → call writers exiting → put writers adding → shorts covering + put writers adding → RSI>60 + sideways/trendline broken → volume + follow-up bars + 2-candle formation]; both ~400-point worked examples)
  - StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 10 Synopsis - Sensex Scalping with Siva.pdf (Session 23, Day 10 — live teaching of Trend Change on a Nifty-expiry sideways box [24,800/24,900]: adapt intraday; trend-line/price-action identification; the OI-shift threshold 1.72 cr→2.5–3 cr call vs 4.5 cr→3/2.5 cr put; momentum mandatory; data leads price by ~15–30 min [~11 AM OI flip → ~11:30 two-candle confirm]; VWAP patience/SL line; 9:45–2:30 window; avoid after 2:30 on a down-break; ~10–20-pt benefit-of-doubt; index-contribution heavyweights)
  - StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 03 Synopsis - Sensex Scalping with Siva.pdf (Session 23, Day 03 — failed-resistance-attempt reversal [1-2-3 failed attempts to clear resistance precede the tank], heavy-volume down-confirmation 2–3 red bars >125K; mild volume at support favours recovery)
  - StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 07 Synopsis - Sensex Scalping with Siva.pdf (Session 23, Day 07 — live ~10:00 AM bearish flip [call OI 4→6.5 cr, put 4.4→3.92 cr]; consolidation-on-both-sides cue; hourly-volume reversal rule; post-vertical bounce / RSI-recovery caution)
  - StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 11 Synopsis - Sensex Scalping with Siva.pdf (Session 23, Day 11 — intraday-bearish / positionally-bullish precondition; volume-break requirement; heavyweights must support; day-high-candle break as the ultra-bullish trigger)
  - StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 12 Synopsis - Sensex Scalping with Siva.pdf (Session 23, Day 12 — morning-bearish→bullish flip [both intraday + positional OI rotate]; held prior-day trendline/critical-support pivot; deep Parabolic-SAR bounce cue; >50% call/put OI demarcation confirms direction)

---

# 4. Common Rules & Shared Components

> These are the reusable building blocks referenced by name throughout the strategy sections. Each component below is defined ONCE here with its exact settings/thresholds; individual strategies cite them rather than re-defining them. All components are drawn from Session 20 (Live Mentoring Program 2023) source material.

---

## 4.1 Chart & Timeframe Baseline

- **Primary scalping timeframe:** **3-minute (3m)** chart for all entry/exit decisions and the "5 Chart Dots".
- **Directional / structure chart:** **Bank Nifty Futures, 3-minute chart** — used to read overall direction (open=high / open=low, build-ups, trend). All directional checks reference "the 3m Bank Nifty Futures chart".
- **Support & Resistance marking:** mark levels on a **larger timeframe (1-Day)**, then refine the trade zones on a **15-minute** chart (see §4.11).
- **Trending OI graph window:** **5–15 minute** view (see §4.4).

---

## 4.2 Indicator Set & Exact Settings ("5 Chart Dots — 3 Min TF")

All applied on the 3-minute chart unless stated otherwise.

- **VWAP** — Default (no modification).
- **Supertrend (ST)** — settings **10, 2**.
- **VWMA** — Volume Weighted Moving Average (used together with ST relative to VWAP for crossovers).
- **RSI 14** — band **80:20**.
  - **No-trade zone: RSI 40–60** (sideways/indecision — no trade).
  - For CE (call) trades: RSI above 60; not overbought (keep below 75/80; "50–75 or allow it to cool off").
  - For PE (put) trades: RSI below 40; not oversold (keep above 25/20).
  - Daily RSI cross-check: CE side RSI(D) below 75; PE side RSI(D) above 25.
- **Parabolic SAR (SAR)** — settings **0.02, 0.2**. Used for direction switch confirmation (SAR flipping sides — a "Desirable").
- **Volume Candle threshold:**
  - **Bank Nifty (BN): 50K**
  - **Nifty (N): 125K**
  - Used to qualify candles and crossover volume (e.g. "2 green/red candles with volume above 50K (125K Nifty)" and "Volume during crossover: 50K for BN, 125K for N").

**Indicator alignment rule (entry context):**
- Bullish: 2nd candle strong, **all indicators below price**; ST & VWMA cross **above** VWAP together (Golden Cross).
- Bearish: 2nd candle strong, **all indicators above price**; ST & VWMA cross **below** VWAP together.

---

## 4.3 OI Interpretation (Build-up Types & OI Spurts)

### 4.3.1 OI Build-up Types
- **Long Build-up (LB):** Price up + OI up → bullish (preferred for long/CE entries in Futures).
- **Short Covering (SC):** Price up + OI down → bullish (acceptable for long/CE).
- **Short Build-up (SB):** Price down + OI up → bearish (preferred for short/PE entries in Futures).
- **Long Unwinding (LU):** Price down + OI down → bearish (acceptable for short/PE).

**Strike-level OI for entries:**
- Bullish: Call OI declining / Put OI increasing; strike interpretation LB or SC.
- Bearish: Call OI increasing / Put OI declining.

### 4.3.2 OI Spurts — 4 Quadrants (with 50% thresholds)
Source: NSE OI Spurts page. Read price-vs-OI in four quadrants:

| Quadrant | OI | Price | Interpretation |
|----------|----|-------|----------------|
| 1 | Rise in OI | Rise in Price | **Bullish** — qualified by **50% increase in OI & 50% increase in price** |
| 2 | Rise in OI | Slide in Price | **Bearish** — qualified by **50% increase in OI & 50% decrease in price** |
| 3 | Slide in OI | Rise in Price | **Short Covering & Long Liquidation** |
| 4 | Slide in OI | Slide in Price | **Better to avoid** (unless hedging large positions) |

> Use the stock's OI Spurt (4 quadrants) as a directional cue for stock trades.

---

## 4.4 Trending OI & Sentiment Graph

- **Trending OI graph window:** 5–15 minutes.
- **Bullish (long/CE):** **Put OI crosses above Call OI** in the Trending OI graph; Put OI increasing quickly while Call OI falls faster; gap between the two **widening**.
- **Bearish (short/PE):** **Call OI crosses above Put OI**; Call OI increasing quickly while Put OI falls faster; gap widening.
- **Sentiment graph:** must slope **up** for bullish, **down** for bearish.
- Crossover must be confirmed by **high volume** (50K BN / 125K N) and **RSI < 75** (bull) / **RSI > 25** (bear) on the 3m chart.
- Trade with conviction from S/R zones when Call OI increases drastically with Put OI covering (resistance/short) or the reverse (support/long) — confirm via Trending OI.

---

## 4.5 India VIX — Directional Rules

VIX is for **Nifty 50 only**; because banks carry the highest weightage in Nifty 50, also watch Bank Nifty (OI Pulse provides a VIX-with-Bank-Nifty representation).

| Market | VIX | Bias |
|--------|-----|------|
| Moving Higher | Cooling Down | **Bullish** |
| Moving Higher | Rising | **Bearish** |
| Moving Lower | Rising | **Bearish** |
| Moving Lower | Cooling Down / Stable | **Bullish** |
| Sideways | Erratic | **Don't consider VIX as a factor** |

**Supporting inferences:**
- VIX rising = sellers creating **fresh short** positions (VIX spikes only on fresh shorts).
- VIX falling + price rising = bullish.
- VIX stable + price falling = longs being exited (not fresh shorting).
- For trade alignment: **CE trades → VIX going down; PE trades → VIX going up.** VIX and DOW should confirm the market direction.

---

## 4.6 Implied Volatility (IV) — 6-Strikes Interpretation

- Look at **3 strikes above and 3 strikes below** ATM; compute **average CE IV** and **average PE IV** (6 strikes total). Compare the two averages.

**IV-pair interpretation table:**

| CE IV | PE IV | Interpretation |
|-------|-------|----------------|
| 10 | 10 | Low IV — **good for Trend Play** (most of the move captured) |
| 10 | 15 | **Premium erosion** on high-IV side if market goes against the trend |
| 20 | 20 | Mostly **premium erosion** |
| 30 | 20 | **Bullish on the higher-IV side** when there is a **10-point (or more) difference** AND market moves in that direction |
| 40 | 40 | **Stay away** from markets, or play **short straddle** |

**Key rules:**
- **10–12 IV is good for Trend play.**
- IV higher → buyers entering, expecting big moves; premiums high.
- IV lower → buyers not interested; premiums low.
- If IV is higher in a particular direction, market should move in that direction (it doesn't make sense to have higher IV on PUT side when market is going up — expect premium erosion).
- IV is a **double-edged sword**: high IV rewards a correct view but punishes harder when wrong.
- IV crashes when buyers unwind positions; IV spikes before event days (then crashes once the event is factored in).
- For an entry, prefer **rising IV in that strike for bull, falling IV for bear** (a "Desirable").

---

## 4.7 Global Cues

Check before/during the trade; all should align with the intended direction:
- **DOW / DOW 30 Futures** — source: in.investing.com indices-futures.
- **Dollar Index.**
- **Asian markets.**
- **Oil** (trend to match).

**Rule:** Global cues (DOW, dollar index, Asian, oil) must match the trade direction (positive for CE, negative for PE), including a re-check at **3:15 PM** for end-of-day / next-day setups.

---

## 4.8 Advance/Decline Filter (Nifty)

- **Advances > 32 → CE (bullish)**
- **Declines > 32 → PE (bearish)**
- Nifty advance/decline must match the market direction.

---

## 4.9 Strike Selection

- **Strikes within ATM ± 3** only (3 strikes above or below ATM).
- **Delta 0.6–0.7** for the strike selected to buy.
- **Premium range:**
  - **Nifty: 100–250**
  - **Bank Nifty: 250–400**
- Choose the **AI-suggested strike within the given price range** (see §4.12).
- **Avoid** strikes where option OI is liberally populated on **both** call and put sides (a "Desirables" avoid rule).
- Option-strike confirmation: substantial open=high in call side + open=low in put side (bullish); reverse for bearish. Probability ~90% (red dot preferable).
- Freshness checks: option price not decreased >50% from previous day (bull) / not increased >50% (bear); change in OI for the identified strike not increased >50% (bull) / not decreased >50% (bear).

---

## 4.10 Time-of-Day Filters

- **Take trades after 9:45 AM.**
- **Ideal window: 9:15–10:00 AM** ("915 to 1000am is the ideal time for the trade"; by 9:45 many moves may already have happened — let RR be ~1%).
- **Avoid sideways midday: ~11:00 AM – 1:00 PM.** (Directly supported by "My Rules! My Trade!" rule 4 — avoid trading in a sideways trend, mostly 11am–1pm.)
- **Events after 3:30 PM:** any impending event after 3:30 PM → **keep off** (no new entries).
- **Morning trade is for scalping only** — finish once target/SL is hit.
- **Expiry-day Hero-Zero:** apply only on **expiry day after 2:00 PM** (see strategy section). Observe where short covering happens between 2:30–3:00 PM around S/R.

---

## 4.11 Support & Resistance Method

- **Foundational concept:** support = level/zone where demand matches/overwhelms supply (price stops falling); resistance = level/zone where supply overwhelms demand (price stops rising). Treat both as **zones, not single price points.**
- **Marking procedure:**
  - Mark S/R on a **larger timeframe → 1-Day.**
  - Refine S/R **zones** on a **smaller timeframe → 15-minute.**
- **Trading the zones:**
  - **Short:** initiate only when price **retraces below the resistance zone** and sellers show conviction; join momentum after the retrace. Confirm with Trending OI (Call OI increasing drastically + Put OI covering).
  - **Long:** initiate only when price **pulls back above the support zone** and buyers show conviction; join momentum after the pull-away.
- **Targets/SL:** target is the **next resistance (for longs) / next support (for shorts)**; S/R levels are used as stops. Note that big players spike price through these levels to flush weak hands before the market continues.
- **Source:** `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Suppor _ Resistance LMP23.pdf` (Session 20, Day 8).

---

## 4.12 OI Pulse / OIP "AI" Confirmation

- **OIP AI direction** must match the **pre-market direction** and your own view before firing a trade ("if that direction aligns with AI and pre-market, then fire").
- At **3:20 PM**, your view and the OI Pulse view should match for next-day/BTST setups.
- Choose the **AI-suggested strike within the price range** given (§4.9).
- OI Pulse provides the consolidated tools used elsewhere: Trending OI, Sentiment graph, Interpretation (Bullish/Bearish from change in OI), VIX-with-Bank-Nifty representation, and Advance Charts (3-minute) for cross-confirming SC + RSI rising + price above VWAP with volume.

---

## 4.13 FII/DII Participant-Wise OI (Directional Bias)

Use NSE participant-wise OI as **one of the Dots** (not the only factor) to set next-day morning bias. NSE classifies derivative participants into 4 categories:

1. **FII** — Foreign Institutional Investors
2. **DII** — Domestic Institutional Investors (Mutual Funds, LIC, etc.)
3. **Pro** — HNIs, Professional Trading Houses
4. **Client** — Retail traders

**Relative importance:** **FII > Pro > DII > Client.**

**Absolute-position read:** If **FII/Pro hold the majority** of positions on Long or Short side → directional view. Typical bullish setup: FII, DII, Pro net Long while Clients net Short → expect bullish next day (provided world markets don't drop aggressively). Reverse → bearish.

**Change-in-OI read (compare two consecutive days):**

| Scenario | Long OI | Short OI |
|----------|---------|----------|
| **Aggressively Bullish** | Increase (≈LB) | Decrease (≈SC) |
| **Cautiously Bullish** | Dominant increase (≈LB) — *or* — decrease (≈LU) | Increase (≈SB) — *or* — dominant decrease (≈SC) |
| **Aggressively Bearish** | Decrease (≈LU) | Increase (≈SB) |
| **Cautiously Bearish** | Dominant increase (≈LB) — *or* — dominant decrease (≈LU) | Dominant increase (≈SB) — *or* — decrease (≈SC) |

**Data to analyse together (all 4 participants):** Index Futures, Stock Futures, Index Calls, Index Puts, Stock Calls, Stock Puts. Net-seller of Calls = bearish on that leg; net-seller of Puts = bullish on that leg.

**Validity:** the bias is valid mainly for **next morning trading hours**, and only if global factors don't come up strongly; sentiment can change independently later in the day.

**Source:** `StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Fii Dii Activity - Participants wise Oi Analysis.pdf` (Session 20).

---

## 4.14 Session-21 Additions (Live Scalping 2024 — shared inputs)

New shared inputs introduced or made explicit in Session 21. They feed the same "Connect the Dots" decision (§3.10) and are referenced by the strategy sections above. No Session-20 component is removed.

### 4.14.1 India VIX — regime bands & relative read (extends §4.5)
- **Absolute regime bands:** **10–11 Lower** → bullish, buy intraday dips of 100–200 pts; **12–14 Medium** → bullish but sluggish, expect consolidation; **15–16 Mildly higher** → seller-favoured, controlled moves, sell the rise; **17+ Higher** → high volatility, active shorts, sharp falls but short-covering bounces.
- **VIX-vs-price grid:** price↑ & VIX↓ = bullish; price↑ & VIX↑ = up-move likely to revert (bearish); price↓ & VIX↑ = bearish; price↓ & VIX stable = longs exiting, fall unlikely to sustain (may revert up).
- Compare absolute VIX to the **previous day's close** (higher = bearish) and read the **intraday** move (VIX falling while price rises = bullish). Erratic intraday VIX → ignore VIX that day. VIX is a Nifty-50 measure; on index-management days Bank Nifty can diverge from VIX/Nifty.

### 4.14.2 Futures basis (premium / discount to spot)
- Futures **above** spot = participants expect spot higher by expiry → bullish bias; futures **below** spot (discount) = bearish near-term bias. Present-series discount + next-series premium = bearish very-near-term, bullish near-term. On expiry, futures converge to spot.

### 4.14.3 Q1/Q2 buyer-seller gate (extends §4.3.2 OI Spurts)
- To be an option **buyer** with conviction, require **both >50% change in price (Q1) AND >50% change in OI (Q2)** on the correct quadrant — long build-up for a call buyer, short build-up for a put buyer / seller. If either is missing, the edge is the seller's — don't buy.
- Read demand by price-move-per-OI: a large price move on small OI addition shows stronger demand than a small move on huge OI.

### 4.14.4 Index-constituent contribution
- Index direction is driven by weighted constituents; read the **top movers** to anticipate it. Bank Nifty's **top 3 ≈ 60%** of the weight; estimate a stock's index impact by its weightage (e.g. **HDFC Bank = 29.46% of Nifty Bank**). A move driven by only 1–2 heavyweights is narrow/less reliable; confirm Bank Nifty only when its banks are in sync (mixed = sideways).
- **Lot sizes:** Bank Nifty 25, Nifty 50, Fin Nifty 40 units/lot. Index weeklies expire Thursday; monthlies last Thursday; stock options monthly only.
- Crude up → Bank Nifty tends to move adversely (India imports most of its oil).

### 4.14.5 Pre-open data & time-of-day data weighting (extends §4.10)
- **Pre-open (9:00–9:07 AM):** only big institutions can create positions; read pre-open positioning + advances/declines to form the day's directional view.
- **Data weighting:** weight previous-day data until **11 AM**, intraday data after 11 AM; use the **previous day's VWAP** as S/R until 11 AM and the current day's VWAP after 11 AM. On expiry, weight intraday OI only if significant OI was created intraday, else previous-day data.

### 4.14.6 OI / Trending-OI timeframes & housekeeping (extends §4.3/4.4)
- OI interval reads: last **5 / 15 / 30 / 60 / 120 / 240 min**; Futures OI on **3–60 min** TFs. Use **15-min for the major crossover**, **60-min for the longer intraday view**, smaller TFs only for entries (too small = noise).
- Ignore a high-volume OI signal whose price impact is negligible. Refresh Trending-OI strikes (close/reopen) to ATM ±7 once the move exceeds 1%.

### 4.14.7 Strike & delta selection by expiry phase / VIX (extends §4.9)
- Delta by expiry phase: ~**0.7–0.8 (delta 7–8) near the end** of a weekly expiry; ~**0.5 (delta 5–6) on the first day** of a new weekly expiry. (The Session-20 baseline 0.6–0.7 remains the general case.)
- Low-VIX → lower-premium strikes; high-VIX → higher-premium strikes. Around expiry avoid far-OTM strikes (demand collapses). Read IV only at ~3 LTPs around the ATM; ignore deep ITM/OTM IV (especially on expiry).

### 4.14.8 Options selling / hedging (new in S21 — buy-side framework still primary)
- **Never sell naked options** — always hedge; opposite-side risk is enormous.
- Short straddle/strangle (range expectation): sell once price is on the relevant side of VWAP; **SL = the straddle/strangle VWAP + a 10–15-pt buffer** (respected ~80–90% of the time, in the ~60–70% of days that are range-bound). Exit when the combined price breaks back through VWAP; a hedged sell may be run to ~80% premium decay or to expiry. A short-straddle price break is the **buyer's** entry cue (trend day / near the close).

### 4.14.9 Scalping definition & instrument notes
- **Scalping cadence:** hold a few seconds up to ~3 minutes; multi-lot / bigger quantity for small per-trade targets; SL always small and cut immediately; a missed/delayed entry is **let go**, not chased. Kit = VWAP + Supertrend + moving averages + volume (intraday variant adds MACD/ADX, ST 7,3 on 15m/1h, S/R & trendlines).
- **Account size:** on the 1% rule, 1–2 lakh suffices for a small account but ~5–6 lakh gives consistency/opportunities; after compounding, trade from withdrawn profits. Use **basket orders** to punch large quantity in one click.
- **Sensex options:** thin/immature derivative — trade it off Nifty/spot, cautiously; do not bet on its own (negligible) volume.
- **Recommended small-capital set:** OSPL (AI) signals, Trending OI Crossover, Open=High, 2-Candle Theory.

**Sources (Session 21):** daily live-scalping write-ups Day 1–12 + pre-mentoring docs `5. Mentoring VIX   OI _ Global Markets.pdf`, `4. Index Trading Made Easy.pdf`, `1. Scalping Vs Intraday trading.pdf`, `Introduction to FNO Market  Day 2.pdf` (all under `StrategySources/OptionsScalperSiva/21 Live Scalping Mentoring 2024/`).

---

## 4.15 Session-22 Additions (Live Mentoring Prog 2.0 2024 — shared inputs)

Session 22 **confirms** every Session-20/21 shared component above (VIX grid + regime bands, OI build-up types + OI Spurts 4 quadrants with the 50% gates, IV 6-strikes, Greeks/delta, S&R as zones, futures basis, constituent contribution, time filters, OSPL/OI-Pulse). The pre-mentoring decks (VIX, OI Basics, Greeks, Volatility, Index Trading, Scalping-vs-Intraday) are reused S20/S21 theory with no new thresholds. The genuinely-new shared inputs:

### 4.15.1 Trending OI + PA (Price Action) — new OI-Pulse feature
- Overlays **LTP (price) change alongside change-in-OI** for each side. When OI reduces on one side (writers exiting) the LTP should rise; if LTP is **not** moving significantly, it is premium erosion, not a real move. Both-side LTP change negative + stable OI = premium decay (a sellers'/short-straddle day). A **gradual drop in the negative LTP-change** on one side flags a buyer opportunity on that side. Use it to confirm that a Trending-OI signal carries real price follow-through.

### 4.15.2 Straddle chart (combined-premium series)
- The combined Call+Put premium plotted as a **single series read against its own VWAP** — the core tool of the new Straddle strategy (§3.11): it times the entry (a VWAP break **with volume**) and enables one-leg management (hold the winning leg, drop the loser once the combo is on the right side of VWAP).

### 4.15.3 Indicator-setting refinements (extend §4.2)
- **VWMA period = 20** (the default is explicitly 20; "Pawn = VWMA(20)").
- **Parabolic SAR distance read:** dots forming **close to the candles** = a short-lived move (likely to flip soon); dots forming with a **wide gap** = the rally can last longer; as the dots approach the candles again the rally is ending. (PSAR remains the first/weakest directional cue — dots below = bullish, above = bearish.)
- **OSPL volume colour-coding:** a high-volume candle (>50K BN / >125K N) turns **dark green** when bulls pumped more volume, **dark red** when bears did.
- **VWAP reaffirmed as "the most important indicator"** — the big players' average; deploy maximum quantity nearest VWAP and wait out a wide VWAP-to-candles gap (sizing rule, see §2.2 rule 7 and §3.10).

### 4.15.4 Strike, delta & IV refinements (extend §4.6 / §4.9 / §4.14.7)
- **Buyer delta can run higher:** 0.7–0.8 or **0.9** for buyers; **sellers ~0.4**. (The §4.9 baseline 0.6–0.7 and §4.14.7 expiry-phase deltas remain the general case; treat 0.9/0.4 as the wider buyer/seller band.)
- **IV trending-difference band = 7–10 points** (refines the §4.6 "10-point or more" — a 7–10-pt CE-vs-PE IV difference, with IV higher on the trending side, is ideal for a trending move). **IV above 40 → stay away as a buyer.**
- **Open=High strike bands now operative (resolves §7):** Bank Nifty **250–550** (avoid >600 / <200); Nifty **150–350** (avoid <130 / >380); buyers near-ATM, sellers OTM (~250–400). See §3.2.

### 4.15.5 Market-structure & screening notes
- **Trending day = a new high or new low printed roughly every 45–60 minutes** (structural definition of a trending vs range day).
- **Stock daily-RSI screen (Market Movers / stock BTST-STBT):** a stock should not have crossed **RSI 75 (bullish) / 40 (bearish)** on the daily timeframe (RSI cools off/reverses there). *Internal S22 inconsistency:* one tool-summary states **70/30** instead; this doc keeps **75/40** as the stock screen and **75/25** as the index intraday default (§4.2).
- **Pre-open data:** available **after 9:07 AM (in 9:08 AM)** — an eagle-eye view of how Nifty 50 / Bank Nifty / F&O stocks opened (previous close, open, change, day-break), used to set the morning bias (extends §4.14.5).

**Sources (Session 22):** `Consolidated Synopsis LMP 2.0.pdf` (master) + daily synopses Day 1–12 + pre-mentoring docs (`5. Mentoring VIX   OI _ Global Markets`, `6. Open Interest Basics`, `9. Understanding Options Greeks_Pre`, `10. Volatility Basics in Stock Market`, `4. Index Trading Made Easy`, `1. Scalping Vs Intraday trading`) — all under `StrategySources/OptionsScalperSiva/22 Live Mentoring Prog 2.0 2024/`.

---

## 4.16 Session-23 Additions (Sensex Scalping with Siva 2025 — shared inputs)

Session 23 **confirms** the entire shared-input framework above (global cues + VIX + OI data + VWAP + Super Trend as the core view-forming inputs, OI build-up/unwinding reads, divergence-then-trade-light discipline, support/resistance and pyramiding-range averaging, the 5–10% capital-deployment rule). It re-teaches the same finite strategy set with a scalping emphasis on a new instrument — the **Sensex** — and adds one genuinely-new shared input: a methodology for **trading Sensex off Nifty charts**, plus the instrument-selection and point/SL-scaling consequences of moving to a ~3×-value index. The new shared content:

### 4.16.1 Why Sensex now — value & point-scaling
- **Why the switch:** With Bank Nifty's weekly expiries ended, **Sensex is the remaining index to scalp** for traders comfortable with a volatile, high-value index. It is a personal choice — for quick momentum/quick points Sensex is easier than Nifty, but anyone uncomfortable with high volatility should not trade it.
- **~3× the value ⇒ ~3× the point move:** Nifty ≈ **25000**, Sensex ≈ **80000**, so Sensex is **~3× Nifty in value** and moves **~3× the points for the same percentage move**:
  - Nifty **0.5%** ≈ **125 pts** → Sensex 0.5% ≈ **375–400 pts**.
  - Nifty **1%** ≈ **250 pts** → Sensex 1% ≈ **800 pts**.
- **Double-edged:** the 3× amplification is the advantage *and* the risk — bigger gains when right, bigger losses when wrong. Size and SL must respect the larger swing (see §4.16.4).

### 4.16.2 Trade Sensex via Nifty charts
- **The proxy works because of stock overlap:** Sensex's **30 stocks are all in the Nifty 50**, and those 30 contribute **~80% of Nifty's total weightage**; the extra **21 Nifty stocks (~20%)** add sectors absent from Sensex (Oil & Gas, Steel, Life Insurance; notable names Adani Enterprises, Grasim, JSW Steel, Cipla). So Sensex makes **similar moves to Nifty**, while the broader Nifty chart gives a better read of overall **market sentiment** and can act as a **leading indicator** (the extra ~20% covers sectors/stocks that may signal direction first).
- **Banking & IT are overweight in Sensex → study those two sectors:** Sensex concentrates on core sectors, with **Banking ~23.71% (vs Nifty ~19.43%)** and **IT ~19.35% (vs Nifty ~17.49%)** carrying heavier weight than in Nifty. A study of the **Banking and IT** sectors does most of the job of reading Sensex; because Sensex spans many sectors it needs a **broader sector study than Bank Nifty** (which is banking-only).
- **Practical rule:** analyze the highest-weighted Sensex sectors (which are also in Nifty) on the **Nifty chart** to trade the Sensex instrument; the omitted ~20% is small enough not to break the proxy.

### 4.16.3 Instrument selection — Sensex options, not futures
- **Sensex Futures are illiquid → not tradeable:** futures volumes are too thin (a day's **highest volume seen was ~418**) to trade. Only trade Sensex Futures if/when volumes rise materially.
- **Trade Sensex OPTIONS:** Sensex options are an instant hit with good, instant liquidity — that is the executable instrument for scalping Sensex. (A long-build-up read can still be confirmed on the futures OI even though the futures themselves aren't traded.)

### 4.16.4 Sensex strike & SL scaling
- **Strike selection (live S23 examples):** deep-ITM/ITM Sensex strikes were used for scalps — e.g. **81500** (Day 1) and **82000** (Day 4) (cited as examples only). A multi-lot player ladders quantity across levels — some at the chosen ITM strike, more at the day's high, more at the previous-day close — clustered near VWAP to keep the stop small.
- **Point-based stop-losses scale up ~3×:** because Sensex swings ~3× the points of Nifty for the same percentage, fixed-point stops and averaging/pyramiding bands must be widened accordingly. This is the same **index-scaled point-SL** rule already established in **§2.12 (item 53)** — Bank Nifty ~75-pt / Nifty ~30-pt SL, **wider for Sensex (~80000)** — and the Hero-Zero application in §3.7; reference those rather than restating numbers here.

**Sources (Session 23):** `Session PPT_s/Day 1/How To Scalp Sensex Using Nifty Charts.pdf` (= pre-mentoring `Pre-Mentoring Documents/11. How To Scalp Sensex Using Nifty Charts.pdf`) + `Daily Synopsis/Day 01 Synopsis - Sensex Scalping with Siva.pdf` (Sensex instrument selection, value/point-scaling, Nifty-chart proxy, sizing; strike example 81500) + `Daily Synopsis/Day 04 Synopsis - Sensex Scalping with Siva.pdf` (strike example 82000) — all under `StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/`.

---

## 4.17 Session-24 Additions (Big 5 Anniversary — Live Decoding 21 Days 2025 — shared inputs)

Session 24 **confirms** the entire shared-input framework above (the 3-min indicator suite, OI four-quadrant reads, IV 6-strikes, India VIX rules, global cues, strike selection, OI-Pulse tooling, the Sensex-via-Nifty proxy of §4.16). It adds no new indicator, but across 21 live-decoded days it sharpens several shared inputs:

### 4.17.1 "Kingdom" chess-metaphor mnemonic (no new mechanics)
- S24's Day-5 **"Kingdom Trading Strategy"** deck restates the existing framework as a chess game: **Queen = Open Interest** (most important), **Rook = VWAP** (final defender), **Knight = Super Trend (10,2)**, **Pawn = VWMA(20)**, **Bishop = Parabolic SAR (0.02/0.2)**, **Territory = RSI(14)**, **Weapons = Volume**, **Battle = each 3-min candle**, **King = the trader**. The long/short conditions are the verbatim Two Candle Theory rules (§3.1). Kept only as a memory aid — **no rule changes** (cf. the S20 "2 Candle Theory With Chess Characters" deck the framing originates from).

### 4.17.2 Sensex participation / volume gate (extends §4.16)
- **Trade Sensex only when there is genuine volatility/participation.** When Sensex option volume is thin, premium erosion is faster and fills are poor — **prefer Nifty** (the higher-volume index) so you can carry higher quantity (Day 11/13). Live read (Day 11): Sensex strikes at ~36 & ~59 lakh OI vs Nifty at ~10 cr & ~18 cr → Sensex skipped.
- **Pick the instrument by the nearer expiry / richer premium** (Sensex expiry = **Thursday**, Nifty expiry = **Tuesday**); Sensex needs a different skill set — **wider SL + higher transaction / index-value cost** (Day 13/15/16).
- When trading a **Sensex expiry, monitor Nifty AND Sensex** intraday + positional data together (Nifty support can prop Sensex); the pre-open Nifty-vs-Sensex spread is an alignment check, and large NSE-vs-BSE pre-open gaps are **HFT arbitrage, not retail signal** (Day 12). Sensex point-moves run **~3–4× Nifty** (§4.16.1): 5–10 Nifty pts ≈ 40–50 Sensex; 50–60 Nifty ≈ ~200 Sensex; a single candle can swing thousands of Sensex points → staggered entries, wide SL, never a tight rupee SL (Day 18/21).

### 4.17.3 Trending-OI 15-strike read (extends §4.4)
- The Trending-OI dashboard read is most reliable on **15 strikes = 7 above ATM + 7 below + ATM** (tested against 5/9/11; 15 won) (Day 2). This is **context-specific** and does not conflict with the **5–7-strike** companion read used to confirm a Golden Crossover (Day 6) or the **5/10-strike** expiry-decode of the OI-Expiry deck (§3.7) — different tools, different windows.
- **Intraday vs positional must agree.** Intraday = today only (scalpers); positional = yesterday + today (swing, held 2–3 days max). A **big** move wants a **>50% call-vs-put gap on BOTH** intraday and positional; extreme-bullish needs both bullish, while positional-bullish + intraday-turning-bearish = only minor-bullish (wait and watch). An ideal bullish positional ≈ **~5 cr call vs 10–12 cr put** (put writing > call writing) (Day 1/9/11/19). A crossover is **not required** when the gap is already very wide — the divergence itself is the signal (watch PCR 1.2 → 1.5 → 2) (Day 8).

### 4.17.4 FII Long/Short-ratio gate (extends §4.13)
- Read the **FII futures Long/Short ratio** as a directional gate: heavily short (**~87–94% short**) = FIIs expect no sustained up-move and short every level; the ratio **crossing the ~50% mark = a short-covering-rally trigger** toward all-time highs. **DII buying alone** (~₹10–11k cr/day) may not lift the index if FIIs sell the heavyweights; an FII net-short that stays low **without covering during a rally** is a caution flag (rally lacks FII participation) (Day 1/3/6/7/11/13/16).

### 4.17.5 IV behaviour refinements (extend §4.6)
- For a bullish buy, want the **call-side IV higher by an ~8–10-pt gap** (e.g. 16/8, 15/8, 25/15); **equal IV** (10/10, 13/15) = range/erosion; IV ~20/20, 40/40, 50/50 = erosion / avoid (or a short-straddle day); **IV > 40–50 favours sellers** (Day 3/14).
- **IV crashes in the second half of expiry day** — call IVs fall as call-buyers exit (put IV stays firmer if put-buyers hold); IV also crashes once an event completes (Day 21). The CE-vs-PE time-value difference is **demand/supply-driven (typically 10–20%, up to ~40%), not Black-Scholes** (Day 21).

### 4.17.6 OI-bar plotting & support/resistance refinements (extend §4.11 / §4.12)
- Plot **OI bars on Nifty SPOT (not futures)** for resistance/support, since spot is the settlement reference; the **largest call-OI bar = resistance, largest put-OI bar = support**, and a **shrinking put-OI bar on a fall signals reversal** (Day 8/9). Spot-OI and futures-OI bars can read **opposite at the same level** — both correct; the difference is premium vs intrinsic (Day 8/11/20).
- Draw S/R **only from volume-driven turning points** (a high a fall came from = resistance; a low a bounce came from = support), **not from OI**; keep the lines ~2–3 months (Day 8/21).

**Sources (Session 24):** 21 daily transcripts `Daywise Sessions/Day 01–21/Day NN - Live Decoding Session.pdf` (the substantive source) + reused decks surfaced in the daily folders — `Daywise Sessions/Day 5/Kingdom Trading Strategy-1.pdf` (chess mnemonic = §3.1), `Daywise Sessions/Day 4/How To Scalp Sensex Using Nifty Charts.pdf` (updated 2025 Sensex-proxy deck), `Daywise Sessions/Day 9/Oi Expiry Strategy 10th Mentoring.pdf` (OI-expiry decode = §3.7) — all under `StrategySources/OptionsScalperSiva/24 Big 5 Anniversery - Live Decoding 21 Days 2025/`.

---

# 5. Strategy Evolution

Five sessions in sources today: **Session 20 (Live Mentoring 2023)**, **Session 21 (Live Scalping Mentoring 2024)**, **Session 22 (Live Mentoring Prog 2.0 2024)**, **Session 23 (Sensex Scalping with Siva 2025)** and **Session 24 (Big 5 Anniversary — Live Decoding 21 Days 2025)**. Sessions 21, 22, 23 and 24 each re-teach the same finite strategy set with a scalping emphasis and **largely confirm** Session 20 — there are **no full deprecations**; the changes are refinements, tightened thresholds, resolved open questions, and new shared inputs (§4.14, §4.15, §4.16, §4.17). **Session 22 adds the Straddle (§5.11)** and resolves two further open questions (Open=High premium bands; Hero-Zero numeric stop-loss); **Session 23 adds the Trend Change strategy (§5.12)** and a Sensex-scalping focus (§4.16); **Session 24 adds no new strategy** — it is a 21-day live re-decoding that confirms Sessions 20–23 and contributes refinements (a fully-chasable Open=High system in §5.2, risk items in §2.14, shared inputs in §4.17). Per the conflict rule the latest session's value is primary and the older value is retained here. Logged per strategy, including within-session refinements taught across mentoring days. Session-21/22/23/24 sources are cited per strategy below.

### 5.1 Two Candle Theory
- **S20 (2023):** Introduced, Day 5; live demos Day 1/2.
- **S21 (2024):** Re-taught (deck `Day 5/2 Candle Theory.pdf`) + live Day 2/3/4/5/6/8/10. Refinements: 1st+3rd candle may substitute if the 2nd misses the volume gate (Day 10); SL = 1st-candle high OR 2nd-candle low when the 1st candle is very large (Day 3); only ONE ST/VWAP rejection trade per 2-candle event (Day 5/10/11); 2-candle + Golden Crossover = high-conviction combo (Day 5/6/8); positional SL = 1st-candle low (Day 5); prefer a full-body 2nd candle, be aggressive only on >50% Trending-OI difference (Day 4). No rule deprecated.

- **S22 (2024):** Re-taught (Kingdom-Trading chess section of the Consolidated Synopsis + dailies). Confirms all. Refinements: RSI-40 is the bearish trigger + the 47K-volume near-miss case (Q15); trail when RSI nears an extreme; WMA→ST→VWAP **25% / more / max** sizing ladder (Day 3). No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (entry on the 3rd candle after two confirming candles; a candle formed right at the open is not valid — wait for a properly formed candle ~9:42–9:45; valid only well before 2:30 PM; pair with the OI/data check and trail; missed entry → one ST/VWAP rejection trade). Sensex-instrument application — read the setup on the Nifty chart, execute in Sensex ITM options, ~3× point scaling (§4.16). Note: the new **Trend Change strategy (§3.12)** builds the 2-candle confirmation into its reversal entry. No rule deprecated.

- **S24 (2025):** Re-taught/confirmed across the daily decodings and the Day-5 "Kingdom" chess deck (3-min TF; OI quadrants; RSI 50–75/80 long, 40–25/20 short; two consecutive bars >50K BN / 125K N; deploy on the 3rd candle; 1st-candle low/high = SL; window 9:45–2:30). Refinements: (a) **overbought defer** — if the two candles form with RSI >85, wait for it to cool to ~70–80/75 and enter on the red/pullback candle (a perfect two-green setup can still be followed by a big-volume red 3rd candle — only take it once RSI cools) (Day 5/20); (b) **SL by trader type** — a scalper trails the previous-candle high/low, a positional player keeps the 1st-candle high/low and can run it all day (Day 5); (c) a **high-volume formation gives a deep 1st-candle SL** — size the trade for that wider risk (Day 4). 2025 examples: a 130K-volume candle ~10:20 needed a second 130K to confirm (Day 14); an expiry breakout-trap on Sensex 82,000 ran 208 → 34 → 205–210 within a few candles (scalpers win, holders stopped out) (Day 13). No rule deprecated.

### 5.2 Open = High / Open = Low (O=H / O=L)
- **S20 (2023):** Introduced, Day 12.
- **S21 (2024):** Re-taught (deck `Day 9/Open & High Strategy.pdf`) + live Day 7/8/10/11. Refinements: quick momentum scalp, target ~40–50 pts then end the day (Day 11); target 2–5 pts below OH (Day 7, refining S20's "~5 pts"); trail SL up from the OH if the move continues (Day 7); require Q1 quadrant + candles above all indicators (Day 11); skip on a strong trend day unless calls fell >50% (Day 8). No rule deprecated.

- **S22 (2024):** Re-taught (reused Day-9 deck + Day-9 synopsis + live trades). Confirms all. **Resolves the §7 premium-band question** — the wider bands are operative (BN 250–550, N 150–350). Refinements: ~90% of OHs are hit before 10:30 AM; skip when an ATM strike would need ~100% premium move; a Put-OH needs only Call-OI↑ + Put-OI↓ and all indicators above the candles; a Call-OH is valid only above VWAP; VWAP = the SL on the live scalp. No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (big players defend the OH levels they create, so the platform probability badge often climbs toward ~90% on a valid setup; hit on the opening ticks ~9:17–9:28 / before ~10:00–12:31; require the EXACT open=high match on futures + ~4 call strikes with put open=low; target ~5 pts below the OH, never chase past it, always trail; ≤30% capital — beginners 1–5%; avoid deep-OTM/-ITM, only ~3 strikes around the dominant strike; skip if calls fell >50% or both Q1+Q2 fulfilled). Confirms the §5.2 premium bands and extends them to Sensex (BN/Sensex ~250–500). Sensex-instrument application via the Nifty chart, ~3× point scaling (§4.16). No rule deprecated.

- **S24 (2025):** **Day 14 sharpens Open=High into a fully-chasable system.** Scope: **index futures & options only** (stocks → Market Movers). Pairing: a call-side Open=High normally pairs with a put-side Open=Low (bullish big player); the mirror is bearish. Timing: data is visible only after the open (~9:16, not pre-market); **~90% of Open=High levels are hit in the first half** (by ~9:45/10:00/10:30), only ~20% later and only on a trend change. **Chase filter (CE):** require ≥3 strikes above AND below ATM **plus the futures** all showing Open=High; round strikes weigh more than 50-pt strikes; the strike must **not** have fallen >50% from open (20–30% is fine); Open=Low present on the put side; prefer ITM over OTM (liquidity is key). **Entry trigger:** only when price **recovers back toward the Open=High** with momentum + ≥50K BN volume on ~3 consecutive candles and indicators below price — probability then climbs 70–80–90%. **Target discipline:** **NEVER place the target above the Open=High** (O=H 183 → target ≤182); once the level is hit it reverses strongly ~90% of the time. **Abort/exit:** an against-move with ≥50K BN / ≥125K Nifty volume or a downside 2-candle disregards the call-side Open=High, and a >50% premium fall + >50% OI rise = exit. **Both-sided Open=High (call AND put) = ignore entirely** (two opposing big players); it also won't trigger if Q1/Q2 are already fulfilled (Day 14/20). 2025 examples: Sensex 83,900 Open=High 563.65 was defended and tagged by the morning buyer at the ~10:45 candle for his exit; an illiquid Nifty 24,850 strike printing Open=High ~450 at ~zero volume was ignored (Day 14). No rule deprecated.

### 5.3 Market Movers
- **S20 (2023):** Introduced, Day 12.
- **S21 (2024):** Re-taught live Day 12 (+ pre-doc `Index Trading Made Easy`); **no standalone deck**. Refinements: move usually by 9:45 AM, ~1% at open + Supertrend support trade (Day 12); Open=Low (long) / Open=High (short) printed by the OI is an added advantage; review past 2–3 days' EOD; constituent-weightage read (top-3 BN ≈ 60%, pre-doc *Index Trading Made Easy*; HDFC Bank = 29.46%, Day 10) — §4.14. No rule deprecated.

- **S22 (2024):** Re-taught (reused Day-8 deck + Day-8 synopsis + Consolidated Synopsis). Confirms 8/9-day + 1–2%. Adds: large-cap-only (avoid small/mid operator-driven); operator low-volume trap; 5–10% observed moves on some days; next-day continuation edge (Q23); 15-day-extreme variant; explicit short-side **STBT** (8/9-day low + SB; carry only on OI-at-day-high + price-at-day-low). Daily-RSI 75/40 (an internal 70/30 variant noted in §4.15). No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (stock open-type filter run *opposite* to the index — Open=Low in top gainers = bullish, Open=High in top losers = bearish; confirm a multi-day high/low actually broke on the daily; RSI ~65–70 has room while ~75–80 is too extended; buy the confirmed-bullish stock on a pullback to Supertrend/VWAP). Sensex-scalping focus — Banking + IT heavyweights (Reliance/Infosys/TCS/HDFC/ICICI) must support a move for the index to follow (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (Day 10): high-prob LONG = an 8–9-day-high breakout + Open=Low on the stock + long build-up/short covering, stock up 3–4%+ → expect a further 1–2%; the short is the mirror (8-day-low / >15% fall). Refinements: (a) **trade the FUTURES, not stock options** — illiquid stock options can fail to move even when the future moves; the underlying must be **liquid** (Day 10); (b) **daily-RSI filter** — daily RSI >70 at the open = no fresh long, a dip to ~67–68 = the buy window, book ~80; a falling stock at RSI ~25–30 has historically bounced, so be careful shorting it (Day 10). 2025 liquidity reads (Day 10): Maxhealth ~150K/120K = "no volume" (avoid), PayTM ~100K = at least liquid. Sensex application still reads the Banking/IT heavyweights on the Nifty chart (§4.16/§4.17). No rule deprecated.

### 5.4 Gap Theory
- **S20 (2023):** Introduced, Day 6; reinforced Day 9/10/11.
- **S21 (2024):** Re-taught (deck `Day 6/Gap Theory.pdf`) + live Day 2/6. Refinement: one-lot players wait for the gap to fully fill before entering; multi-lot may scale toward the gap/Supertrend; small players exit longs below the Supertrend (Day 2). No rule deprecated.

- **S22 (2024):** Re-taught (reused Day-7 deck + Day-7 synopsis + Q11). **Confirms all with no new execution rule** — same 2023 worked examples; Q11 only restates the predefined-order mechanics. No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (gaps fill ~90% same-day, ~99% on a 3-min chart — sometimes next day; wait for the fill before going aggressive, act near the Supertrend, exit promptly once filled as it can reverse sharply; **never take a gap trade against the trend**; on a major-news day the gap-day high becomes key resistance; deep-ITM, not OTM, on gap days). No new execution rule beyond the explicit "never fade against the trend" emphasis and the deep-ITM gap-day strike note. Sensex application reads gaps on the Nifty/futures chart (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (the 3-min gap fills ~80–90% — stated "99%" — same or next day, gaps act as magnets, SL = the low of the candle the gap formed from, exit ~RSI 70–80) (Day 2/4/6). Refinements: (a) **validity hinges on volume direction** — an up-move WITH volume = a valid gap (a bigger move is likely on the return), but if the FALL carried the volume the market can reverse fast; runaway/momentum gaps may never fill (Day 6); (b) **don't chase a call merely to fill a gap into bearish texture** — wait for the fill, let price tag resistance, then take the down-side; a no-volume fill is bearish-friendly while a rising-volume fill warns of an upside reversal; the unfilled gap is a level its holder defends (Day 17); (c) **a gap trade is a 30–60-min play only** — wait 30–40 min; if it hasn't filled on volume, ignore it and trade with the trend; SL ~50–60 pts or a nearby S/R level (Day 21). 2025 examples: a ~13-pt Nifty gap (24,806.60 → 24,819) did NOT fill (large unfilled buy order) (Day 3); a post-2:30 false-breakout ~100-pt fall left a 3-min gap that filled next morning (Day 4). No rule deprecated.

### 5.5 Trending OI Crossover
- **S20 (2023):** Introduced, Day 7; refinements Day 9/10/11.
- **S21 (2024):** Re-taught (deck `Day 6/Trending Oi Crossover Strategy.pdf`) + live Day 2/4/5/6/7/8/10/11/12 (+ pre-doc VIX/OI/Global). Refinements: immediate ~20–30% OI divergence at the cross, ≥50% gap for the support trade (Day 6/7); best window 10–11:30 AM, avoid after ~1:30–2 PM (Day 6); sentiment-slope critical (Day 7); confirm positional + intraday OI on monthly expiry (Day 7); strike housekeeping <1% keep / >1% reset to ATM±7 (Day 11); 15-min for the cross, 60-min for the longer view (Day 10). No rule deprecated. (The S20 RSI deck-vs-grid contradiction in §7 is unchanged by S21.)

- **S22 (2024):** Re-taught (reused Day-9 deck + Day-9 synopsis + Consolidated Synopsis). Confirms all. Adds: **VWAP is the decisive confirmation** — a cross with price stuck around VWAP is low-probability and traps both sides; the real move comes only after the OI gap widens AND price holds the correct side of VWAP. **Failed-cross test:** if one side's OI reduces but the other side's OI does not increase, it is only short-covering (sellers re-add) — don't chase. The deck's RSI>25-bear vs grid RSI<25 conflict (§7) is unchanged. No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (a true crossover read on the **15-min** timeframe — call/put OI must diverge/cross, not merely travel together; want a >50% call-vs-put gap, best in the morning 10:00–11:00, avoid after ~2:30; buy needs the chart up AND OI flipping bearish→bullish; OI Spurts four quadrants — LB/SC favour bulls, SB/LU favour sellers — with the ≥50%-OI / ≥50%-price confirmation; short covering is the most ferocious reversal; don't trust the platform's auto-quadrant label, check whether the candle closed near the day's high or low). Sensex-instrument application, ~3× point scaling (§4.16). The new **Trend Change strategy (§3.12)** uses the Trending-OI crossover as its primary momentum-shift trigger. No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (call-OI vs put-OI crisscross marks a trend shift — long = the PE line crossing above CE, short = mirror — enter the next candle, both-lines-together = no trade, read on **15-min (best)** / 5-min, window 9:40–2:30) (Day 7). Refinements: (a) **15-strike read** — the dashboard is most reliable on **15 strikes (7 above + 7 below + ATM)**, which beat 5/9/11 (Day 2; §4.17.3); (b) **intraday + positional must agree** — a big move wants a >50% call-vs-put gap on BOTH, an ideal bullish positional ≈ ~5 cr call vs 10–12 cr put (Day 1/9/19); (c) a **crossover is not required** when the gap is already very wide — the divergence itself is the signal (watch PCR 1.2 → 1.5 → 2) (Day 8); (d) **don't turn bearish on a VWAP-volume break alone** — require trending OI to also flip (Day 11/12); (e) **fake-crossover handling** — a second crossover against you = exit immediately; 2–3 crossovers in a day = sideways, avoid (Day 7); (f) for a clean trending-down expiry want call-side OI far above put (~11–12 cr vs 4–5 cr), and don't panic while the gap holds >40–50% (Day 15). 2025: **16 Sep 2025 was the model trending day** — a ~50% gap held all day, a fresh high almost hourly, the trending OI held till ~1:30 and Super Trend was never broken (Day 7). No rule deprecated.

### 5.6 Golden Crossover
- **S20 (2023):** Introduced, Day 6; reinforced Day 4/7/9 + Post-Mentoring. SL was **not stated** (open question); target ~100–150 pts BN (volume-backed ~200).
- **S21 (2024):** Re-taught (deck `Day 6/Golden Crossover Strategy.pdf`) + live Day 3/5/6/7/8. **Resolves the SL open item** — on the support-trade form the Supertrend level is the SL (Day 7). **Refines the target** — ~200–300 pts BN (~200/side) per a clean crossover (Day 6); the S20 ~100–150 figure is retained as the conservative case. Best window 10–11 AM (Day 6); call entry only above VWAP, short-covering must clear ST+VWAP (Day 7).

- **S22 (2024):** Re-taught (reused Day-7 deck + Day-7 synopsis + Consolidated Synopsis). Confirms all (3–4×/month, BN 100–150 / N 50–70, ST+VWMA pierce VWAP, Trending OI 5/7 strikes around ATM). Deck states RSI bands bull **50–75** / bear **40–25** and pins **VWMA(20)**. No new numeric SL appears (the S21 resolution stands: support-form SL = the Supertrend level). No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (ST + VWMA(20) pierce VWAP together on the same candle with volume and all parameters; if only one line crosses or it crosses without volume it is not a golden crossover; RSI bull 50–75 / bear 40–25; entry = the candle after the cross, SL = the crossover/Supertrend level; best ~9:45–2:00/2:30; typical payoff BN ~200–300 / N ~50–100 pts; combine with a 2-candle setup for a stronger entry). Sensex application via the Nifty-chart framework (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (VWMA + Super Trend pierce VWAP together on the **same candle with volume**, rare 3–5×/month, long RSI 50–75 / short 40–25, enter the next candle, SL = the crossover-candle level) (Day 4/6). Refinements: (a) expected follow-through **+200–300 pts Bank Nifty, +50–100 pts Nifty** (Day 6); (b) when VWAP, Super Trend and Parabolic SAR **cluster together**, bulls can clear all three in one big-volume candle — that is **not** strong resistance, so be careful (Day 6); (c) **dip-buy shape** — pyramid from Super Trend down toward VWAP with the SL ~30–40 pts below the defended zone, deploying ~20% at the Super Trend zone and reserving ~80–90% for VWAP (Day 5); (d) **no-trade zone** when Super Trend breaks early and price ranges between Super Trend and VWAP (Day 5/13). (The Day-6 bearish-crossover OI figures are **reused-2022-deck** examples, not new 2025 data.) No rule deprecated.

### 5.7 Hero-Zero (Expiry-Day OI Strategy)
- **S20 (2023):** Introduced, Day 8; reinforced Day 2/3/9. Timing "after 2 PM"; prep = 5 strikes either side, a week of data.
- **S21 (2024):** Re-taught (deck `Day 6/How To Identify Hero Or Zero - Expiry Day.pdf`) + live Day 3/6/7/8/11. Refinements: **tighten timing to ~2:30–2:45 PM** (Day 6); decide the side after 1:30–2 PM by premium/where shorts build, wait for the option to decay (e.g. puts to ~15–20) (Day 6/7); prep now = ~4 round strikes around ATM, last ~4 days OI (2 days suffices given daily expiries) (Day 8); confirm both price & OI change >50% on the strike (Day 8/11). S20's "after 2 PM" superseded by ~2:30–2:45 PM.

- **S22 (2024):** Re-taught + a **NEW Day-9 deck** (18 Feb expiry, not reused). **Resolves the §7 numeric-SL question** — index-scaled point SL (BN ~75 / N ~30 / wider Sensex-Bankex) on top of the existing 50%-premium / hard-close-3:20-PM. Adds: deploy ~**10% of profits**; cheap option **doubles/triples in 30–45 pts**, BN 100–200-pt squeeze; strike-pick **2:30–3 PM**; conditional **3:10 PM no-move exit**; scale-in parts (max qty at lowest price); optional both-sides-small-qty when unclear; round-strike double-zero pin warning; IV-flat-both-sides = no trade; prep = 4-above/4-below ATM, 3–4 days OI; usable for a morning/intraday trade (Q5). No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (bet OTM strikes near expiry-end ~2:30–2:45 hoping OTM→ATM/ITM on a late short-covering/gamma move; the strike whose entire premium is time value and is OTM is the zero candidate, while a near-money strike trading below intrinsic gets pulled to intrinsic; pick the slightly-OTM strike trading ~10–15 to keep an edge; stake only a tiny slice of profits ~₹1,000–2,000 — lottery odds; anything entered after 2:30 is effectively hero-or-zero). Sensex-instrument refinement — prefer the **higher-value index** (Sensex/Bank Nifty) because a same-% move yields far more premium (50 Nifty pts ≈ 150–200 Sensex/BN pts), so a 100–200-pt Hero-Zero move is realistic on Sensex but not Nifty (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (an expiry-day-only play worked ~2:45–3:15 as writers cover in the last 30–40 min — short covering / long unwinding, **not a gamma blast** — reward typically doubles, 5×–10× possible; prep = 5 strikes either side of ATM, 10 if volatile, round strikes a must) (Day 9 deck/Day 10). Refinements: (a) **sizing** — deploy only ~10% of **profits**, never capital; don't average a loser — set a level, let it go to zero if broken, trail if it rises (Day 10); (b) **strike** — ATM/one-OTM for the aggressive play, ITM/1–2-above for the safer bet, favour the side trading at a **discount** and the lower premium (Day 10); (c) **direction by second-half flow** — if call-side OI is rising fast while the other side covers, take the **PUT** side (and vice-versa) (Day 10); (d) **expiry-day OI read** — scan 2–3 strikes above / 2 below the close; an ideal CE long-build closes ≥50% up near the day-high with a big OI jump (a put-side short-build wants a ~70–78% fall + ~85% OI jump); if only one side qualifies, don't be aggressive on the other (Day 17); (e) sellers adding on **both** sides = pin/erosion → avoid; **on a monthly-expiry day, ignore the OI/expiry data** (it reflects the prior month, not the new series) (Day 9/20/21); (f) prefer **low-premium strikes** near expiry (loss capped at the small premium), but with high premiums prefer **ITM/deep-ITM** (Day 11/18); (g) **Bank Nifty caution** — with daily expiries gone, BN volumes dried up and it no longer reacts as before (Day 10). 2025 example: 24,500 CE OI 21 L → 1 cr with premium 670 → 247 then popping ~377–383 (late writers lose, not day-1 writers) (Day 9). No rule deprecated.

### 5.8 BTST / STBT
- **S20 (2023):** Introduced, Day 8; reinforced Day 2/11/12. Long-Unwinding quadrant number was **uncertain** (slide labelled it "Quadrant 3").
- **S21 (2024):** Re-taught live Day 8/12; **no standalone deck**. **Resolves the quadrant numbering (Day 12):** BTST SC = Q3, LB = Q1; STBT SB = Q2, LU = **Q4**. Adds the AI **"320 Strategy"** — a 3:20 PM probability signal with a wide overnight SL (Day 12). Carry a buy side only if closing near the day's high; do not carry calls on long unwinding (Day 8). No rule deprecated.

- **S22 (2024):** Re-taught (reused Day-7 deck + Day-7 synopsis). Confirms the resolved quadrants (BTST SC=Q3/LB=Q1; STBT SB=Q2/LU=Q4) and legs. Adds **stock-overnight** rules: STBT trigger = Futures OI at day-high + price at day-low; 15-day-low variant; intraday-not-oversold check before entry; **no BTST after a parabolic up-move**; review the Expiry-day analysis feature after 8:30 PM. No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (carry a bullish position only when the day **closes near its HIGH** with OI at/near the day-high — long buildup, or short-covering shifting to long buildup; **do not** carry on long unwinding or after a parabolic move; risk only 10–20% of capital, never 100%; index options/futures only, never cash stocks; strike ~2 away from spot — never OTM overnight; two distinct gap reads (not a gap-sized SL ladder): an adverse overnight gap can crush the OTM-side option ~80–90% at the open (carry risk), and on the favored strike watch the premium NOT falling below ~50% of its run-up — a drop below ~50% signals buyers are unwinding; build the decision from ~3 PM EOD OI+price quadrants; news overrides data). Sensex-scalping context (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (size only **5–10% of capital**, exit early next morning; validity gate = after the morning move the market does NOT breach VWAP/Super Trend and holds into the close; BTST is right ~6–7/10) (Day 2/3/9/18). Refinements: (a) a **news-driven single-day rally after a multi-day fall is not trustworthy** — it needs 2–3 days of consistent volume-backed follow-up (Day 9); (b) **don't take a near-expiry BTST** just because price inches up in the last 30 min — if the morning high isn't reclaimed in the second half, carrying overnight is risky (Day 8); (c) after a big intraday hit, if you've recovered ~80–90% of profit, **square off rather than carry**; protect previous-day + ≥70–80% of intraday profit before taking fresh risk (Day 18). 2025 examples: a textbook BTST reclaimed VWAP with volume and never broke back below, running ~500 pts from 25,550 toward ~26,000 (Day 18); a would-be perfect BTST (24,500 CE +55% Wed) became the trap case when Day 9 opened 350–370, low 300 on global pressure (Day 9). No rule deprecated.

### 5.9 Morning Trade (Opening Trade Strategy)
- **S20 (2023):** Introduced, Day 8; live Day 9, refinements Day 10/11. Worked example entry 9:16 → exit 9:18; "no VWAP before 10:30 AM, use prev-close + cues".
- **S21 (2024):** Re-taught (deck `Day 6/Opening Trade Strategy.pdf`) + live Day 6/7/11/12. Refinements: AI **"Morning Trade" signal at 9:11 AM, mandatory exit by 9:18 AM** (Day 12); use the **previous day's VWAP** as the reference level (Day 7) — complements S20's "no current-day VWAP before 10:30 AM"; deploy only a portion of profits (Day 12); take every signal, reduce lot when it opposes the market (Day 11). No rule deprecated.

- **S22 (2024):** Re-taught (reused Day-7 deck + Day-7 synopsis + live PE morning trade + Q&A). Confirms all. Adds: **previous-day VWAP = the defended level**; profit-trail-to-buy-price SL on top of the mechanical SL; **Open=High as exit-trigger + CE-hedge**; slight-ITM strike sized off the gap (+30–40-pt buffer); add only around the prev-day close; pre-open data after 9:07 AM; >50% OI-change for a convincing same-day view. **Resolves "how to trade when morning data is unhelpful"** (Q3: act off previous-day closed data + today's open). No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (opening trades are high-risk, for experienced traders only; EOD futures data of the prior day is the main input but holds only absent overnight news; bearish recipe = second-session short buildup + end-of-day long unwinding → short any gap-up bounce; on a news gap-down let the market react, short every bounce until the prev-close retest fails; a sudden big call-OI build with a fall elsewhere = news-driven data reversal). Sensex-scalping emphasis — avoid the morning prints on the volatile high-value index, wait for the structure (trendline/support hold) to declare direction (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (the riskiest trade — only with a pre-defined exit; freshers avoid unless direction is known) (Day 12). Refinements: (a) **sizing** — risk only ~10–20% of capital, previous-day profit as the SL (Day 12/17); (b) **wait for the pre-market to settle** (~9:07–9:08), ignore the initial ±200-pt swings, use the settled open to compute fair value and the premium range (Day 12); (c) **read the gap** — on a big gap-down (~300–400 pts) do **not** buy a put (already oversold, yesterday's put-buyers book); prefer using a gap-up (~30–40 pts, global cues flat/green) to short **once** (a gap-up is expected to be sold at least once) (Day 12); (d) **check the pre-market heavyweights before shorting** — Reliance/Infy/HDFC Bank/TCS opening 2–4% can add ~80–100 Nifty pts and trap a short (Day 12); (e) **strike** — 2–3 strikes from the settle (not deep OTM), slightly ITM in the morning then rotate to higher-priced strikes near expiry; SL at the strike's prior-day VWAP / bounce bottom — honour it and do **not** average on adverse news (Day 12/17); (f) on expiry with put-buyers holding tight, **restrict to the call side** (Day 11). 2025 examples: a Sensex **82,200 PE** opening trade entered 9:15:02 and exited 9:16:13 for >1% (<₹2 L deployed) (Day 12); an opening CE on the Delhi-blast news was exited at SL with no averaging, the day still netting ~₹2 L (Day 17). No rule deprecated.

### 5.10 Options Scalping Framework (Connect the Dots)
- **S20 (2023):** Introduced, Day 2; settings/refinements Day 4/5/6.
- **S21 (2024):** Re-taught (deck `Day 2/Connect The Dots ... Day 2.pdf`) + live Day 1/2/5/11 (+ pre-doc VIX/OI/Global). Confirms the 5-dot + macro-dot framework and the RSI booking ladder (90% at 75–80, last 10% at 85; mirror 25–20 / 15). Refinements: intraday-only, TF by trader type (Day 11); net-cautious read → low quantity, no chasing, enter on retrace to ST/VWAP (pre-doc); trade OSPL AI signals confirmed with Trending OI + Futures (Day 11). Adds new feeder inputs in §4.14 (VIX bands, futures basis, constituent contribution, pre-open). No rule deprecated.
- **S22 (2024):** Re-taught (reused Day-2 decks + dailies + the chart-indicator/2-candle blocks of the Consolidated Synopsis). Confirms the 5-dot framework + RSI booking ladder + VWAP-primacy. Adds: VWAP-distance position sizing; ≥50% Call-vs-Put OI trending gate; ATM/ITM for quick 10–20-pt scalps; scalp hold ~1–30 min; profit-as-SL floor; ≤₹25–30k/trade for a ~1-lakh account; VWMA pinned to 20; the new **Trending OI + PA** feature (§4.15). **Resolves the §7 "Sell PE/CE = naked selling?" question** (buy-side confirmed). No rule deprecated.

- **S23 (2025):** Re-taught/confirmed (the full Kingdom-Strategy 3-min indicator suite — Parabolic SAR, Super Trend, VWMA(20), VWAP, OI, Volume, RSI, Candles; futures VWAP for S/R, prev-day VWAP until ~10:30 then intraday VWAP; confluence of WMA+ST+VWAP for a trend trade; no-trade zone between ST and VWAP / RSI 40–50; volume gate two bars >50K BN / >125K N; RSI zone map; trade window ~9:45/10:00–2:30; out within 3–5 min, 15 max; form the view from global cues + OI + VIX). Sensex-instrument refinement — the genuinely new S23 layer is **trading Sensex off the Nifty chart** (~80% stock overlap, Banking+IT overweight), via Sensex options not low-liquidity futures, with ~3× point scaling (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed (VWAP central + Super Trend gate + VWMA(20) first defence + Parabolic SAR + RSI(14) bands; data window 9:45–2:30; scalp 3/5-min, positional 15-min/hourly; OI four quadrants; chart AND data must align) — including via the Day-5 "Kingdom" chess mnemonic (§4.17.1). Refinements: (a) **full RSI(14) zone table** (Day 4) — OB 80 / OS 20; 40–50 no-trade; buy 50–75, book 75–80/85, >80–85 no fresh longs; sell 40–25, book 25–20, <20 avoid; (b) **hourly-new-high cadence** — a trending day prints a fresh high roughly every hour (~10:00 / ~11:00 / ~12:00–12:15 ±15 min); if new highs stop and price holds a ~30-pt range = erosion (Day 2/6/18); (c) **volume confirmation is mandatory for any break** — a VWAP/level break **without** volume can reverse in one candle and trap you (it points to the next support, not a real breakdown) (Day 11/12/15/18/21); (d) **no-trade zone** = price boxed between Super Trend/VWMA and VWAP → only 1–2 lots, wait for a boundary break (Day 5/13/16/17/19); (e) **support-strength tiering** for sizing — deploy small at weak support, reserve max size for very-strong (usually VWAP) (Day 2); (f) previous-day VWAP is the first support until ~10:00–10:30, then switch to **intraday VWAP** (Day 9); (g) **breadth** — advancing-vs-declining + the top 5–6 heavyweights moving together (+1–3%) confirm follow-through (Day 1/4/10); (h) book then **re-enter the same strike lower** using booked/prior-day profit as the next risk budget (Day 10/16/21). Sensex-instrument layer per §4.16/§4.17. No rule deprecated.

### 5.11 Straddle (Long & Short)
- **S22 (2024):** **Introduced, Day 11** — the only strategy absent from S20/S21, so there is no prior baseline (see §3.11). **Long straddle:** buy ATM Call+Put (same strike/expiry) when IV/premiums are LOW (event play); entry = the combined straddle price breaks ABOVE its VWAP with volume (or, on the event form, after ~12:30 PM once price closes above the VWAP of both legs); SL below VWAP; hold the winning leg / drop the loser. **Short straddle:** sell ATM Call+Put when both-side IV is similar (range/decay play); entry after 9:30 AM once price falls BELOW the VWAP of both legs (5-min BN chart); SL above VWAP; exit on premium decay / EOD; **unlimited risk** on a volatility breakout. No deprecations possible (new strategy).

- **S23 (2025):** Re-taught/confirmed (**short straddle** when both-side IV/OI are similar and premiums erode on both sides — a slow-grind/range or high-IV pre-event play, ~1 cr added on both sides = a sideways "writer's paradise"; **long straddle** when IV is LOW and you expect a wild one-directional move — enter only in the second half once both legs are dirt-cheap, net cost-to-lose ~1–2; keep a combined-premium SL at the EMA/VWAP; do NOT short-straddle on a low-premium expiry where a 200–300-pt move overruns the thin combined premium; demonstrated on the OI-Pulse strategy builder/simulator). Sensex-specific — Sensex expiry premiums are smaller, leaving a thinner cushion (higher catch-22 risk); examples on Sensex 81,500/82,000/83,000 strikes (§4.16). No rule deprecated.

- **S24 (2025):** Re-taught/confirmed lightly (S24 ships no Straddle deck). Uses/refinements: the **~50% call-vs-put OI gap** (with a higher market) is itself a parameter confirming bullish/buy-the-dip even without a Two-Candle setup (Day 5); a flat/erosion day is a **double-edged sword for straddle buyers** (premium bleeds on both sides) and a win for straddle sellers (Day 3/14/19); the **combined (call+put) premium vs its VWAP is the straddle's directional gate** — once the combined premium drops below its VWAP into the close, straddle buyers lose (Day 17). No rule deprecated.

### 5.12 Trend Change
- **S23 (2025):** **Introduced, Day 10** — the 12th strategy, absent from S20/S21/S22, so there is no prior baseline (see §3.12). A **reversal-capture** play (not a continuation breakout): identify the trend (up/down/sideways) via trend lines and price-action swings, then catch the **shift in direction** three ways — (a) swing-structure break (HH/HL pause → LH/LL, or vice-versa), (b) trendline break, (c) a **Trending-OI momentum shift** (call writers exiting + put writers adding for an up-reversal; reverse for a down-reversal). Trigger = **RSI above 60** (up-reversal; below ~40 for down) AND the sideways range / trendline broken, **confirmed with increasing volume + follow-up bars + a 2-candle (3rd-candle) entry** per §3.1. The OI clue typically leads price by ~15–30 min (Day-10 synopsis: ~11 AM OI flip → ~11:30 two-candle confirm). Momentum is mandatory (no OI change → 5–10-pt fakeout → skip). Window 9:45 AM–2:30 PM; **avoid a down-break after ~2:30 PM**. Worked examples captured ~400 points; live reinforcement across Day 03/07/11/12. No numeric SL specified — size off structure & VWAP per §2. No deprecations possible (new strategy).

- **S24 (2025):** Re-taught/confirmed lightly (S24 ships no Trend Change deck; the §3.12 S23 mechanics stand). Reinforced live on trend-shift reads across Day 7/11/12 and refined Day 21: on an **intraday-bearish / positional-bullish divergence**, take a counter-trend (e.g. call) trade only if the counter-move is **not** backed by >125K (Nifty) volume — a volume-backed counter-move signals the real reversal, an unbacked one fades. The Trending-OI crossover (§5.5 / §4.17.3) remains the primary momentum-shift trigger and momentum (volume + follow-up bars + the 2-candle confirm) is still mandatory. **Monthly-expiry caveat (new in S24):** on a monthly-expiry day, **ignore the OI / Trending-OI data entirely** — the expiring series' writers are unwinding, so the OI read is corrupted and cannot signal a trend change (Days 9/20/21). No rule deprecated.

---

# 6. Machine-Readable Appendix

One JSON object per strategy (consistent schema) for backtest/bot implementation. `valid_to_year:"current"` until a later session changes a rule. Each baseline block carries `sessions_present: ["20","21","22","23","24"]` and `updated_session: "24"` (all 10 baseline strategies were re-taught through Session 24); the **Straddle (§6.11)** is `sessions_present: ["22","23","24"]` and the new **Trend Change (§6.12)** is `sessions_present: ["23","24"]`, `updated_session: "24"`. The blocks hold the Session-20 baseline plus the **load-bearing resolutions** (Golden Crossover SL & Hero-Zero start time from S21; **Open=High premium bands & Hero-Zero numeric SL from S22**); Sessions 23 and 24 add no new resolutions but the **Trend Change strategy (§6.12)**, the **Sensex-scalping inputs (§4.16)** and the S24 refinements (the fully-chasable Open=High system, §2.14 risk items, §4.17 shared inputs). The full narrative of the Session-21/22/23/24 refinements lives in the **evolution log (§5)** and the per-strategy **"Session 21/22 update"** notes (§3) — consult those before implementing.

### 6.1 Two Candle Theory
```json
{
  "name": "Two Candle Theory",
  "key": "two_candle",
  "market_context": "Momentum-breakout scalping for Index Futures and Index Options; goal one or two good trades a day (one can meet the 1% daily target). Trade only when momentum, volatility, OI build-up and strength align on two consecutive candles; 2nd candle structure is the decision key. Chess framing: OI=Queen(most important), Volume=Weapons, RSI=Territory, each 3-min candle=a Battle, PSAR=Bishop, Supertrend=Knight, VWMA(20)=Pawn, VWAP=Rook(final level).",
  "instruments": [
    "Bank Nifty Index Futures",
    "Nifty Index Futures",
    "Bank Nifty index options (CALL/PUT)",
    "Nifty index options (CALL/PUT)"
  ],
  "timeframe": "3-minute (primary; slides also note 3/5-min); direction from index Futures vs VWAP; matrix RSI checks on 5m and Daily",
  "indicators": [
    "VWAP (Rook)",
    "Supertrend / ST (10,2) (Knight)",
    "VWMA(20) (Pawn)",
    "Parabolic SAR (0.02,0.02,0.2) (Bishop)",
    "RSI 14 (Territory)",
    "Volume",
    "Open Interest / OI (Queen)",
    "Trending OI graph",
    "Connecting the Dots",
    "India VIX",
    "IV"
  ],
  "setup_preconditions": [
    "Trade only after 9:45 AM",
    "Two consecutive candles in direction (2 GREEN for bull / 2 RED for bear) each with volume above 50K (Bank Nifty) / 125K (Nifty)",
    "2nd candle must be strong; if wick/shadow is >=2x body it signals rejection from higher levels (skip even with volume)",
    "Use the full 3 minutes of the 2nd candle to analyze, not to rush entry",
    "Index Futures above VWAP for longs / below VWAP for shorts",
    "RSI in correct band and not beyond overbought/oversold boundary",
    "OI build-up confirms direction with HIGH difference in change of OI on one side (marginal = no trade)",
    "Trading zone: bull high-prob from Support, bear high-prob from Resistance; major opposing S/R nearby = low probability",
    "All indicators (SAR, VWAP, VWMA, Supertrend) below candles for long / above candles for short"
  ],
  "entry_conditions": {
    "bullish": [
      "Index Futures trading ABOVE VWAP (Rook)",
      "OI (Queen) shows Long Build-up (better) or Short Covering; call OI declining / put OI increasing; OI change difference HIGH",
      "RSI (Territory) above 50 and between 50-75 (slides allow up to 80); not overbought; reduce size if RSI just below 80",
      "Volume of 2 consecutive GREEN bars above 50K (BN) / 125K (Nifty)",
      "2nd candle strong AND all soldiers (PSAR, VWMA, Supertrend, VWAP) BELOW the candles/price",
      "Strike: slightly ITM CALL, delta 0.6-0.7 (0.7 preferred), ATM +/- 3, premium 100-250 Nifty / 250-400 Bank Nifty",
      "Deploy on the 3rd candle (entry on 3rd candle valid if still inching up and Futures OI confirms)",
      "Expect one pull-back/support at WMA/Supertrend after the 2-candle formation for add/re-entry"
    ],
    "bearish": [
      "Index Futures trading BELOW VWAP (Rook)",
      "OI (Queen) shows Short Build-up (better) or Long Unwinding; call OI increasing / put OI declining; OI change difference HIGH",
      "RSI (Territory) below 40 and between 40-25/20; 40-25 preferable; SKIP if RSI already below ~20 (oversold bounce risk) and prefer Supertrend-rejection reversal instead",
      "Volume of 2 consecutive RED bars above 50K (BN) / 125K (Nifty)",
      "2nd candle strong AND all soldiers (PSAR, VWMA, Supertrend, VWAP) ABOVE the candles/price",
      "Strike: slightly ITM PUT, delta 0.6-0.7 (0.7 preferred), ATM +/- 3, premium 100-250 Nifty / 250-400 Bank Nifty",
      "Deploy on the 3rd candle (entry on 3rd candle valid if still inching down and Futures OI shows Short Build-up)"
    ]
  },
  "exit_conditions": {
    "target": "Ride momentum to next resistance (long) / support (short); aim 1-2% (one good trade can meet 1% daily target); conservative quick-scalp exit when momentum dies; manage scalp at least to VWAP",
    "stop_loss": "LONG = 1st candle LOW; SHORT = 1st candle HIGH (if hit, exit). Alternate: use VWAP as SL when market has already extended before entry. Trail SL ~5 pts below reference level.",
    "time_exit": "No fixed clock exit; exit if VWAP breaks WITH volume (trend reversing); fake breakout = price returns into VWAP without follow-up. On trend days new 2-candle setups recur ~45min-1hr apart.",
    "scaling": "Deploy small first; average ONLY at Supertrend or VWAP/VWMA levels (e.g. 3% initial, +7% at VWAP/WMA, total <=~20% of capital). Never average after SL breach. Trail out in stages: conservative, then on PSAR, then on Supertrend."
  },
  "risk_management": [
    "Art of averaging: 3% deployed initially, add 7% at VWAP/WMA, total exposure <=~20% of capital for this setup range",
    "Average only at known defending levels (Supertrend / VWAP / VWMA); do not average if SL levels breached",
    "Do not go heavy on quantity when RSI near overbought boundary (just below 80)",
    "Target one or two good trades per day; avoid over-trading",
    "Position size scaled to account size and risk capability",
    "Refer Global Risk Framework for shared daily cap / night-risk rules"
  ],
  "filters": [
    "Trade only after 9:45 AM; two-candle moves after 10 AM often set up Supertrend/VWAP rejection follow-on trades",
    "RSI bands: bull 50-75 (5m below 75/80, Daily below 75); bear 40-25/20 (5m above 25/20, Daily above 25); no-trade ~40-60",
    "OI: confirm LB/SC (bull) or SB/LU (bear); Trending OI cross-over and widening gap; volume+price confirm OI; HIGH one-sided OI change",
    "VIX going down supports bull; VIX going up supports bear; abnormal VIX behaviour = warning",
    "Avoid chasing vertical/parabolic moves; be cautious if index has not crossed previous swing resistance",
    "Global cues / Connecting the Dots: Dow futures, Asian (Nikkei/Shanghai/Hang Seng), European (DAX/CAC/FTSE), India VIX, Crude, US Dollar Index (>105 negative, <90 ideal), Bond yields & USD/INR, Events & News",
    "Strike filters: delta 0.6-0.7, ATM +/- 3, premium 100-250 Nifty / 250-400 BN, slightly ITM, IV rising (bull)/falling (bear)",
    "Desirables: ST & VWMA cross-over + SAR switch aligning direction; breakout from S/R line confirming direction"
  ],
  "edge_cases": [
    "2nd candle with long wick (>=2x body) = rejection, skip even if volume met",
    "Skip bearish 2-candle when RSI already <~20 (bounce risk); prefer Supertrend-rejection reversal",
    "Entry on 3rd candle valid if price still inching in direction and Futures OI confirms",
    "Use VWAP as SL when move already extended before entry",
    "Expiry-day premium management can fake direction; watch for fake VWAP breakouts without volume",
    "Trend day: repeated 2-candle setups ~45min-1hr apart; can be positional but watch EOD OI"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 5 (21st Sep 2023); also Day 1 (06 Sep) and Day 2 (07 Sep)",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Canlde Theory LMP23.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Candle Theory_With Chess Characters.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/Day 05_LMP23 21st Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 1/Day 01_LMP23 06th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Day 02_LMP23 07th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Canlde Theory LMP23.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/2 Candle Theory_With Chess Characters.pdf"
  ],
  "uncertain": [
    "RSI long upper bound stated as 50-75 in Day-5 manual but 50-75/80 in chess slides (UNCERTAIN — needs confirmation whether 75 or 80 is the hard cap)",
    "Slides reference '3/5 Min TF' while Day-5 manual standardizes 3-minute (UNCERTAIN — whether 5-min is an allowed alternate primary TF)",
    "Day-2 matrix shows '30-50 points good enough' under an adjacent O=H/O=L column; not confirmed as a 2-Candle target (UNCERTAIN — needs confirmation)"
  ]
}
```

### 6.2 Open = High / Open = Low (O=H / O=L)
```json
{
  "name": "Open = High / Open = Low (O=H / O=L)",
  "key": "open_high_low",
  "market_context": "Intraday options scalping in index Futures & Options that reads big-players' day plan: institutional capital pushes the underlying in its direction so bought/written strikes open at their high (Calls) or low (Puts); the open extreme tends to be revisited same day (occasionally next day) as the player drags premium back. Purely discretionary.",
  "instruments": [
    "Bank Nifty (primary) index Futures & Options",
    "Nifty index Futures & Options",
    "No stock options (cash/futures allowed for underlying leg)"
  ],
  "timeframe": "3-min Bank Nifty Futures chart (direction); option strike charts for OH/OL; daily RSI secondary; Trending OI 5-15 min",
  "indicators": [
    "VWAP (default)",
    "Supertrend ST (10,2)",
    "VWMA",
    "Parabolic SAR (0.02,0.2) - desirable",
    "RSI 14 (bull >50, RSI5m <75/80, RSI-D <75; bear <50, RSI5m >25/20, RSI-D >25)",
    "Volume candle threshold 50K Bank Nifty / 125K Nifty",
    "Trending OI graph (5-15 min)",
    "Sentiment graph",
    "OI Spurts (4 quadrants)",
    "OI Pulse / OIP AI probability badge",
    "India VIX",
    "Implied Volatility (IV) per strike",
    "Delta 0.6-0.7 for buys"
  ],
  "setup_preconditions": [
    "OH on Futures AND on Call-side strikes (bullish), ideally with OL on Put side simultaneously - highest-probability config",
    "At least 3 strikes above and below ATM matching OH together with matching OH on Futures",
    "Rare very-high prob: 4-5 strikes continuously formed around ATM + OH on Futures; highest prob: above plus OL on Put side",
    "Trade only ATM and ITM strikes (ATM +/- 3); avoid OTM and deep ITM (liquidity/volume key)",
    "Identified-strike premium not fallen >50% from previous close (bullish CE) / not risen >50% (bearish PE)",
    "Change in OI for identified strike not increased >50% (>50% = opposite player entered)",
    "Momentum present (price/volume/RSI agree); probability higher in 1st half, avoid 2nd half (time-value/premium erosion)",
    "Trend alignment: OH on Call side in bullish market = high prob; OH opposite market trend = sideways low-prob day",
    "Confirm via Trending OI showing positions created across all aspects; OI Pulse AI badge"
  ],
  "entry_conditions": {
    "bullish": [
      "Trade after 9:45am (ideal 9:15-10:00); OH on Bank Nifty Futures 3-min with Long Build-up (pref) or Short Covering",
      "Substantial OH on Call strikes AND OL on Put strikes; >=3 strikes above/below ATM matching (ATM +/- 3)",
      "OI Pulse probability >=90% WITH badge (red dot preferable); do not chase below 90%",
      "Momentum up: price rising with volume, RSI >50 and moving above 50; RSI5m <75/80, RSI-D <75; all indicators below price",
      "Strike: premium nearest target, 0.6-0.7 delta; OPERATIVE bands (S22-confirmed, resolves prior open item) = ATM+3, Bank Nifty 250-550 avoid >600/<200, Nifty 150-350 avoid <130/>380 (the 100-250/250-400 slide bands are the older/general case); buyers near-ATM, sellers OTM ~250-400",
      "Verify premium not fallen >50% from prev close, OI change not >50%, Call OI declining/Put OI increasing, VIX going down",
      "Enter on breakout with volume; e.g., OH=300, price 200, enter @250 on volume breakout, exit @290 (slide example; ~10 pts below OH=300, vs grid rule exit ~5 pts below OH)"
    ],
    "bearish": [
      "Trade after 9:45am; OL on Bank Nifty Futures 3-min with Short Build-up (pref) or Long Unwinding",
      "Substantial OL on Call strikes AND OH on Put strikes (Put is the leg played); >=3 strikes above/below ATM (ATM +/- 3)",
      "OI Pulse probability >=90% WITH badge; do not chase below 90%",
      "Momentum down: RSI <50 and moving below 50; RSI5m >25/20, RSI-D >25; all indicators above price",
      "Strike: 0.6-0.7 delta; premium Nifty 100-250 / Bank Nifty 250-400; Put premium not risen >50% from prev close; strike OI change not decreased >50%",
      "Verify Call OI increasing/Put OI declining, VIX going up; enter on breakdown with volume; exit 5 pts above the Open=Low"
    ]
  },
  "exit_conditions": {
    "target": "Small scalps 30-50 points (O=H/O=L grid; the 1-2% figure is from the Market Movers column, not this column); target the OH/OL extreme but never beyond it - exit ~5 pts below OH (bull) / ~5 pts above OL (bear); choose strike whose premium nearest target",
    "stop_loss": "Abort/exit if option premium decreases >50% AND/OR identified-strike change in OI increases >50% (bigger opposite player). Adverse move on >50K (BN)/125K (N) volume candle = exit; low-volume drift tolerable. Intraday scalp only; target the OH/OL extreme but never beyond (exit ~5 pts inside)",
    "time_exit": "Favor 1st half; avoid initiating 2nd half (time-value/premium erosion); scalp only, close once target/SL hit",
    "scaling": "Always trail SL once in profit; never set target beyond OH/OL"
  },
  "risk_management": [
    "Highly risky trades - never deploy more than 30% of capital on this trade",
    "Adverse move must not occur on >50K (BN)/125K (N) volume candles - high-volume adverse move = exit",
    "Trail once in profit",
    "Avoid OTM/deep-ITM strikes to guarantee exit liquidity",
    "Intraday-scalp only (the grid's 1-night-risk/avoid-Friday and SL-50%-close-3:20pm rows belong to BTST/Hero-Zero, not O=H/O=L)",
    "Reference Global Risk Framework for shared sizing, max-loss and daily-cap rules"
  ],
  "filters": [
    "Time: after 9:45am, ideal 9:15-10:00, favor 1st half, avoid 2nd half",
    "Volume threshold 50K Bank Nifty / 125K Nifty",
    "RSI 14: bull >50 (5m<75/80, D<75); bear <50 (5m>25/20, D>25)",
    "OI: Trending OI 5-15 min + Sentiment graph; bull Call OI declining/Put OI increasing; OI Pulse AI badge >=90%; reject if strike OI change crosses 50%",
    "Premium-change filter: not >50% move from prev close on identified strike",
    "VIX down (bull) / up (bear)",
    "Strike scope ATM +/- 3; delta 0.6-0.7 for buys",
    "Desirables (O=H/O=L grid): watch Futures for open high/low after 9:15; 9:15-10:00 ideal; strikes liberally populated on both Call & Put = avoid; if trade goes against check volume. Cross-strategy generics: ST & VWMA cross + SAR switching; S/R breakout; IV rising (bull)/falling (bear)"
  ],
  "edge_cases": [
    "Two-sided OH+OL on both Call and Put = sideways market (two opposing players, mild prob both sides); both hit only if OH hit on one side while other not fallen >50%",
    "If >50% price-increase criterion already fulfilled, wait (OI Spurts/Trending OI) until it comes down so momentum rebuilds before acting",
    "Probability tiers: OH-Fut+OH-Call+OL-Put=HIGH; few Calls OH+few Puts OL=MILD; Puts OH+Calls OL=HIGH (bear); few Puts OH+few Calls OL=MILD; Calls OH AND Puts OH=MILD both sides",
    "Subsequent price/volume: Call OH falls <50K vol -> prob INCREASES; falls >50K -> DECREASES; flat/flat -> INCREASES (mirror for Put OH rises)",
    "Avoid deep ITM/OTM - may not get hit or give exit due to liquidity",
    "Do not jump straight to buying on seeing OH on CE/PE - time entry to confirmed probability/momentum",
    "O&H can be achieved same day or next day, most often same day"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 12 (LMP23, 5th Oct 2023)",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Open _ High Strategy - Index Options _ Futures.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Day 12_LMP23 5th Oct 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Open _ High Strategy - Index Options _ Futures.pdf"
  ],
  "uncertain": []
}
```

### 6.3 Market Movers
```json
{
  "name": "Market Movers",
  "key": "market_movers",
  "market_context": "Momentum/breakout strategy on individual F&O stocks (not index) using OI Pulse Market Movers screener. Trades day-leading stocks: an 8-day/9-day high (long) or 8-day/9-day low (short) tends to continue for a 1-2% directional move, often ~1% in the first morning hour. Screener shows Script name, LTP, LTP chg %, OI% change, OH/OL flag, OI Interpretation, and Min. breakout days.",
  "instruments": [
    "Stock futures",
    "Stock in cash market",
    "No stock options (explicitly excluded)"
  ],
  "timeframe": "5-minute chart (worked examples); intraday by default, can be positional. Trade after 9:45am.",
  "indicators": [
    "VWAP (default)",
    "VWMA 20",
    "SuperTrend (10,2)",
    "RSI 14 (5m and Daily)",
    "Volume 20",
    "Open Interest",
    "Min. Breakout Days (screener)",
    "OH/OL flag (screener)",
    "OI Interpretation LB/SC/SB/LU (screener + Futures OI Analysis table, e.g. 60-min)",
    "OI Spurt 4 quadrants (cue)",
    "Parabolic SAR (Desirables: ST & VWMA cross + SAR switching)",
    "IV on relevant strike (Desirables)"
  ],
  "setup_preconditions": [
    "Open OI Pulse Market Movers: Mode=Live data, Asset=All F&O Stocks (or filter Nifty50/NiftyBank), Expiry=Current Month",
    "Scan Top Gainers for longs, Top Losers / Short Build-up category for shorts",
    "Require minimum 8-day high (long) or 8-day low (short); 9-day even better",
    "Prefer high-volume / liquid stocks for clean entry/exit",
    "Cross-check stock chart for price action and Futures OI Analysis table for OI interpretation",
    "Daily RSI filter: not already past RSI 75 (bull) or below RSI 40 (bear), as RSI cools off/reverses there",
    "Radar-building: add at 1-2 day high/low, confirm momentum as it moves to 3-4 day high with OL (bull)/OH (bear), full conviction at 8-9 day breakout"
  ],
  "entry_conditions": {
    "bullish": [
      "Trade after 9:45am; by 9:45 much of the move may be done, accept ~1% RR",
      "Stock at 8D or 9D High",
      "High-probability alignment requires all 3: (a) min 8-day high, (b) OL (Open=Low) flag on Top Gainers side, (c) Long Build-up in OI (LB best; Short Covering acceptable)",
      "Confirm RSI(5m) below 75/80 and RSI(Daily) below 75; RSI holding above ~50-60 (examples cite >60)",
      "Volume and price action confirm the OI action",
      "Entry: long after price moves above VWAP; take trade on pullback near VWMA/ST/VWAP. Alternative: considerable OI change + >1% price change, or intraday support trades",
      "Scalping cue: OL in Top Gainers + 8D/9D high = strong opening; highest conviction when paired with OH in Top Losers on another name"
    ],
    "bearish": [
      "Trade after 9:45am",
      "Stock at 8D or 9D Low; check Short Build-up / Top Losers section",
      "High-probability alignment requires all 3: (a) min 8-day low, (b) OH (Open=High) flag on Top Losers side, (c) Short Build-up in OI (SB best; Long Unwinding acceptable)",
      "Confirm RSI(5m) above 25/20 and RSI(Daily) above 25",
      "Volume and price action confirm the OI action",
      "Entry: short as price moves below VWAP (mirror); take trade on pullback near VWMA/ST/VWAP. Alternative: considerable OI change + >1% price change, or intraday resistance/rejection trades",
      "Scalping cue: OH in Top Losers + 8D/9D low = very weak opening"
    ]
  },
  "exit_conditions": {
    "target": "1-2% on the stock (not more than 1-2%); ~1% commonly captured in first morning hour",
    "stop_loss": "No rigid SL and no fixed OI% threshold (stocks can be manipulated, unlike index); set per own risk management/appetite. Reference from 2-candle framework: 1st candle low (long) / 1st candle high (short)",
    "time_exit": "Intraday by default (examples tagged High Intraday only). Can hold positional as trend may persist, but watch EOD OI",
    "scaling": "Not specified in sources"
  },
  "risk_management": [
    "No fixed/rigid stop-loss; no set OI%-change trigger for stocks (manipulation risk)",
    "Size and SL per own risk appetite; defer to Global Risk Framework for sizing, daily loss cap, night-risk",
    "Prefer high-volume/liquid names",
    "Overnight only when closing OI = Long Build-up; avoid carrying through Long Unwinding (ZEEL example: not ideal overnight)",
    "Not more than 1 night risk; avoid Friday (Global Risk Framework)"
  ],
  "filters": [
    "Time-of-day: after 9:45am",
    "Breakout-days: min 8-day high (long) / 8-day low (short), 9-day better",
    "OH/OL: OL for bullish, OH for bearish",
    "OI Interpretation: LB best or SC for longs; SB best or LU for shorts; watch EOD OI before overnight",
    "RSI: 5m below 75/80 & Daily below 75 (long); 5m above 25/20 & Daily above 25 (short); not already past Daily RSI 75 (bull) / below 40 (bear)",
    "Volume: high-volume for liquidity; volume confirms price/OI action",
    "Use right-side New High/Low Maker panel for support (bull)/rejection (bear) trades",
    "Refer OI Spurt 4 quadrants for the stock as extra cue",
    "Desirables: ST & VWMA cross + SAR switching; S/R line breakout confirming direction; IV rising (bull)/falling (bear) on the strike",
    "Avoid names with OI liberally populated on both call and put sides"
  ],
  "edge_cases": [
    "Trade execution wording: long entry after price moves above VWAP, plus pullback entries near VWMA/ST/VWAP",
    "Overnight carry only if closing OI = Long Build-up; avoid through Long Unwinding",
    "By ~9:45 the move may be done; accept ~1% RR, do not chase late",
    "If trade goes against you, check volume: high volume = exit, low volume = may pursue",
    "Avoid stocks with both call and put OI heavily populated (ambiguous)",
    "Stocks can be manipulated; no reliable fixed OI% trigger, rely on price action + volume"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 12",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Market Movers Strategy.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Market Movers Strategy.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Day 12_LMP23 5th Oct 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf"
  ],
  "uncertain": [
    "UNCERTAIN - needs confirmation: chart timeframe. PDF examples use 5m chart, but master-matrix indicates intraday scalping and the global glossary lists 3m as primary scalping TF. Documented as 5m per this strategy's examples.",
    "UNCERTAIN - needs confirmation: source slide for short-trade reasons reuses 'Long build up indicating Bears are in charge' (apparent slide typo for Short build-up)."
  ]
}
```

### 6.4 Gap Theory
```json
{
  "name": "Gap Theory",
  "key": "gap",
  "market_context": "Short-term scalp exploiting intraday gaps on the 3-min Bank Nifty Futures chart. 3-min Futures gaps fill ~90% of the time same/next day; after the fill price resumes the prior short-term trend. Wait for the gap to fill, then trade in the direction of the overall trend. Gaps may not fill when runaway or when opposite momentum is strong. Logic does NOT apply to higher timeframes or to option-price gaps.",
  "instruments": [
    "Bank Nifty Futures (current month) - primary direction chart",
    "Bank Nifty CE/PE options (scalp execution)",
    "Nifty (support/long bias on gap up)",
    "Futures or cash (no stock options) per matrix"
  ],
  "timeframe": "3-minute (Bank Nifty Futures)",
  "indicators": [
    "VWAP",
    "VWMA 20",
    "SuperTrend (10,2)",
    "RSI 14 (SMA 14); CE RSI<75, PE RSI>25; 40-60 no-trade zone",
    "Volume (BN 50K / Nifty 125K threshold per Common Components)",
    "Open Interest / Trending OI",
    "India VIX",
    "DOW / global cues"
  ],
  "setup_preconditions": [
    "On Bank Nifty Futures 3-min chart (current month)",
    "Gap between previous candle and current candle (jump-up bullish / jump-down bearish)",
    "High-prob: gap measured prev-candle high-to-open (bull) or prev-candle low-to-open (bear)",
    "Gap significant: above 3 points or 60 ticks",
    "Gap NOT already filled by current candle body or wick",
    "Take trade only after 9:45 AM (ideal 9:15-10:00)",
    "Standard indicators loaded: VWAP, VWMA 20, SuperTrend 10 2, RSI 14, Volume, OI"
  ],
  "entry_conditions": {
    "bullish": [
      "3-min Futures trend is UP with a gap created below price",
      "Gap significant (>3 pts / 60 ticks) and not yet filled by current candle",
      "WAIT for price to return and fill the gap; do NOT enter on the unfilled gap-up (risky - fill then reverse)",
      "CE (long) the moment the gap is filled, in the direction of the overall/prevailing up-trend",
      "Entry at gap-filled area; ideally pullback near VWMA/ST/VWAP or at intraday 3-min trendline support; enter when momentum up",
      "Example 9-Jan-23: high 09:27=42530, open 09:30=42534.30 (4.30 pt gap), filled 09:48 (low 42465.50), CE entry 09:51 @42536",
      "Conviction/positional example 31-Jan-23: close 12:48=40957.90, open 12:51=40942.85 (15.05 pt gap), filled 13:03 (close 41020), CE entry 12:54 @40883 on trendline support"
    ],
    "bearish": [
      "3-min Futures trend is DOWN with a gap created above price (jump-down; high-prob = prev low to open)",
      "Gap-down significant (>3 pts / 60 ticks) and not yet filled by current candle (body or wick)",
      "WAIT for fill; PE (short) the moment the gap is filled, in the direction of the overall down-trend",
      "If trend down and gap made on an up-attempt, look for a SELL based on data, not the short up-move",
      "Risky gap-fill variant: trade TOWARD the gap on price rejection, target = gap level (PE 9-Jan-23 entry 09:45 @42595, scalping only)",
      "On a gap UP do NOT short Bank Nifty - look for support/long instead (and support trade in Nifty)"
    ]
  },
  "exit_conditions": {
    "target": "In-trend: next resistance/support; examples R:R 1:2.5 (CE 9-Jan target 42700), 1:1.6 aggressive / 1:1.7 conservative (31-Jan target 41024). Gap-fill counter-trade: target = gap level (PE 9-Jan target 42530=GAP). Scalp aim not more than 1-2% (let R:R ~1% if moves already done by 9:45).",
    "stop_loss": "In-trend: SuperTrend (CE 9-Jan SL 42431=ST). Trendline variant: aggressive=trendline (40803), conservative=below bullish candle above trendline (40840). Matrix alt: low of candle before the gap candle (longs) / high of that candle (shorts). Counter-trend gap-fill: day high (PE 9-Jan SL 42657.85) for shorts / day low for longs.",
    "time_exit": "Morning/intraday scalp only - finish once target or SL hit; avoid 11am-1pm sideways for fresh entries; no new entries before events after 3:30pm.",
    "scaling": "Conviction example (31-Jan) gives a single target (41024) framed two ways (aggressive R:R 1:1.6, conservative 1:1.7), not a two-step scale-out; trail SL ~5 pts below price (longs) / 5 pts above (shorts) once in profit."
  },
  "risk_management": [
    "Treat strictly as intraday scalp; defer sizing/max-loss/daily-cap to Global Risk Framework",
    "Do NOT extend gap-fill expectation to daily timeframe or option-price gaps",
    "Avoid entering on an unfilled gap (high risk of fill-then-reverse)",
    "If used positional, watch EOD OI; not more than 1 night risk, avoid Friday (per Common Risk)"
  ],
  "filters": [
    "Trade only after 9:45 AM; ideal 9:15-10:00; avoid 11am-1pm; no new entries before post-3:30pm events",
    "RSI 14 (3-min): CE RSI<75 (not overbought), PE RSI>25 (not oversold); 40-60 no-trade",
    "Pullback entry near VWMA / SuperTrend / VWAP",
    "Align OI / India VIX / DOW & global cues per Common Components; gap-up = support/long bias (BN & Nifty)",
    "Options: strikes within ATM +/-3, delta 0.6-0.7 for buys, premium 250-400 BN / 100-250 Nifty"
  ],
  "edge_cases": [
    "Runaway gaps / strong opposite momentum: gap may never fill - do not force a fill trade",
    "Unfilled gap-up is risky: wait for the fill (10:42 AM Nifty Futures example)",
    "3-min gap logic does NOT apply to daily/longer timeframes (daily gaps can be runaway)",
    "Option-price gaps may or may not close (theta decay / no retrace) - do not rely on them",
    "Post-fill direction follows the OVERALL trend, not the short move that created the gap",
    "Counter-trend trade toward the gap (target = gap level) is explicitly risky, scalping only"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 6",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Gap Theory 10th Mentoring.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Gap Theory 10th Mentoring.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Day 06_LMP23 22nd Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 10/Day 10_LMP23 3rd Oct 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf"
  ],
  "uncertain": [
    "UNCERTAIN - needs confirmation: numeric volume threshold on the gap candle / gap-fill candle (deck shows Volume panel but sets no number; Common Components 50K BN / 125K Nifty assumed)",
    "UNCERTAIN - needs confirmation: whether to execute as option buy vs Futures/cash - deck examples are CE/PE scalps while matrix mentions 'buy futures or stock in cash market (no stock options)'",
    "UNCERTAIN - needs confirmation: exact India VIX / DOW alignment rule specific to Gap Theory (inherited from Common Components, not stated in the Gap deck)"
  ]
}
```

### 6.5 Trending OI Crossover
```json
{
  "name": "Trending OI Crossover",
  "key": "trending_oi_crossover",
  "market_context": "Trend-following momentum scalp that detects a live shift of strength between bulls and bears via a crossover of the two OI lines in the Trending OI tool; one side squares off while the other adds, signaling big-money directional commitment. Wider post-cross gap between the OI lines implies a bigger move.",
  "instruments": [
    "Nifty (N)",
    "Bank Nifty (BN)",
    "Fin Nifty"
  ],
  "timeframe": "Trending OI graph on 5-15 min interval; RSI/execution on 3-min; 15-min and 3-min OI data equivalent (1x15m = 5x3m); 60-min for broader trend then 3-15 min to plan/time trade",
  "indicators": [
    "Trending OI graph (5-15 min) - PE vs CE OI lines",
    "OI Sentiment graph (slope and high/low)",
    "% change in OI per side",
    "Volume (>=50K BN, >=125K N during cross)",
    "RSI 14 (3-min, band <75 bullish / >25 bearish)",
    "Futures chart Bank Nifty 3m for OI interpretation (LB/SC/SB/LU)",
    "Trending OI direction-change arrows"
  ],
  "setup_preconditions": [
    "Open Trending OI graph on 5-15 min and spot developing cross (one side OI falling/squaring off, other side OI rising/adding)",
    "Confirm on two inputs: % change in OI and slope of OI Sentiment graph - both must agree",
    "Trade only after 9:45 AM; ideal window 9:15-10:00 AM (accept ~1% RR if later)",
    "Confirm substantial rise/fall in price plus OI difference on one side (opposite on other)",
    "Require >=50% difference between change in Call OI and Put OI (BN/Fin Nifty/Nifty); 50% on both sides signals moves",
    "Caveat: flat OI all day yields no big move even at 50% diff - need one side rising and other falling through the day",
    "Volume during cross >=50K (BN) / >=125K (N)",
    "Verify futures OI interpretation supports direction (LB/SC bullish; SB/LU bearish)"
  ],
  "entry_conditions": {
    "bullish": [
      "OI: PE OI increasing AND CE OI decreasing",
      "Crossover: PE OI line crosses ABOVE CE OI line (5-15 min); Sentiment shifts low->high (bearish->bullish), slope up",
      "RSI (3-min) < 75",
      "Confirm substantial price rise with widening OI gap; volume >=50K BN / >=125K N",
      "High probability: Put OI rising quickly + Call OI falling faster + high volume + short covering with drastic OI fall = huge one-way move",
      "Trade: Buy CE or Sell PE"
    ],
    "bearish": [
      "OI: CE OI increasing AND PE OI decreasing",
      "Crossover: CE OI line crosses ABOVE PE OI line (5-15 min); Sentiment graph slopes down (bullish->bearish)",
      "RSI (3-min) > 25",
      "Confirm substantial price fall with widening OI gap; volume >=50K BN / >=125K N",
      "High probability: Call OI rising quickly + Put OI falling faster + high volume = huge one-way move",
      "Trade: Buy PE or Sell CE"
    ]
  },
  "exit_conditions": {
    "target": "1-2% per scalp; ride while OI gap keeps widening",
    "stop_loss": "Book loss when SL is hit; on double/fake crossover (low volume + small non-drastic OI change) exit at SL and switch to the other side where the next genuine cross prints (both sides tradable)",
    "time_exit": "Defer to next-series data at series end if short build-up on both sides and both sides start covering (no clear direction); wait for a confirmed OI crossover before flipping direction",
    "scaling": "Quick scalps - fresh opportunity can appear within ~2 min when paired with Two Candle Theory; positional possible as trend persists but watch EOD OI; trail next day once decent profit earned"
  },
  "risk_management": [
    "Take only small quantity when crossover occurs with RSI cooling off",
    "Low-probability cross (low volume / non-drastic OI change): smaller size or avoid",
    "High-probability cross (drastic OI shift + volume + one-way move): normal/full conviction sizing",
    "Defer to Global Risk Framework for daily loss cap, per-trade max loss, night risk",
    "Not more than 1 night risk; avoid Friday for overnight; intraday use is scalping-only"
  ],
  "filters": [
    "Time-of-day: after 9:45 AM; ideal 9:15-10:00 AM; avoid sideways 11am-1pm unless fresh high-prob cross",
    "RSI: bullish <75, bearish >25 (3-min)",
    "OI: >=50% difference Call-OI vs Put-OI change (BN/Fin Nifty/Nifty); one side up while other down through day; drastic one-side fall with equal/greater buildup on other for high prob",
    "Volume >=50K (BN) / >=125K (N) during crossover",
    "Sentiment graph slope must agree with direction (up=bullish, down=bearish)",
    "Direction-change arrows after large gap/morning move flag trend change; huge opposite-side OI change = larger move"
  ],
  "edge_cases": [
    "Failed/incomplete crossover invalid if sellers start writing calls as price rises (cross never completes) - do not enter",
    "Double/fake crossover (low vol + small OI change) whipsaws - book SL, rotate to next true cross",
    "End-of-series ambiguity (short build-up both sides, both covering) - use next-series data",
    "Flat-OI trap: persistent 50% diff with unchanged absolute OI = no big move (range-bound sellers) - wait for real OI divergence",
    "Pairs well with Two Candle Theory for confirmation and repeated quick scalps",
    "Day-7 trade-example slides are chart screenshots; only date/cross-time/RSI/direction are rule-bearing"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 7",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 7/TRENDING OI CROSSOVER STRATEGY_LMP.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 7/TRENDING OI CROSSOVER STRATEGY_LMP.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 7/Day 07_LMP23 25th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 10/Day 10_LMP23 3rd Oct 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf"
  ],
  "uncertain": [
    "UNCERTAIN - needs confirmation: exact target - this strategy's only sourced target cue is the shared 1-2% per scalp; the '30-50 points' figure belongs to the O=H/O=L column (Sivas 9s), not Trending OI Crossover",
    "UNCERTAIN - needs confirmation: precise stop-loss in points/percent for this strategy (sources only say book loss at SL / switch sides; no fixed SL value given specific to Trending OI Crossover)",
    "UNCERTAIN - needs confirmation: whether the >=50% Call-vs-Put OI-change filter applies to all timeframes or only the day-cumulative reading"
  ]
}
```

### 6.6 Golden Crossover
```json
{
  "name": "Golden Crossover",
  "key": "golden_crossover",
  "market_context": "Momentum scalping setup for index options. A Golden Crossover is the simultaneous crossing of both Supertrend (ST) and VWMA through VWAP — from below for bullish, from above for bearish. Signals a confluence flip of trend, volume-weighted price, and fair-value line that can produce a fast directional move. Treated as a rare, high-quality opportunity (~3-4 times/month) that only delivers a meaningful move with volume; without volume it stalls, goes sideways, or reverses. Expected better in Bank Nifty than Nifty.",
  "instruments": [
    "Bank Nifty index options (CE/PE)",
    "Nifty index options (CE/PE)",
    "index options on indices showing the crossover (e.g., Midcap)",
    "Bank Nifty 3-min Futures chart for direction/signal"
  ],
  "timeframe": "3-minute (primary; crossover confirmed on 3-min Futures Bank Nifty chart; RSI read on 3-min)",
  "indicators": [
    "VWAP (default)",
    "Supertrend / ST (10,2)",
    "VWMA",
    "RSI 14 (3-min)",
    "Trending OI graph (5-15 min, 5/7 strikes above/below ATM)",
    "Sentiment graph",
    "Volume"
  ],
  "setup_preconditions": [
    "On 3-min Bank Nifty Futures chart with VWAP, ST(10,2), VWMA plotted",
    "Take trade only after 9:45 am (ideal window 9:15-10:00)",
    "Both ST and VWMA must cross VWAP together (defining condition); single-indicator cross or no-body candle is not a Golden Crossover",
    "Volume confirmation mandatory: Bank Nifty 50K+, Nifty 125K+ on the crossover candle",
    "Confirm via Trending OI (5-15 min) across 5/7 strikes around ATM: drastic change in change-of-OI on BOTH CE and PE sides; no drastic OI change implies small move"
  ],
  "entry_conditions": {
    "bullish": [
      "Crossover: ST AND VWMA cross ABOVE VWAP together (pierce from below) on 3-min",
      "Time From: 3 mins (confirmed on 3-min)",
      "RSI < 75 on 3-min (not overbought)",
      "Volume during crossover: Bank Nifty 50K+, Nifty 125K+",
      "OI: drastic change in change-of-OI on both sides; bullish bias (Put OI increasing / Call OI falling; short covering or long build-up)",
      "Trade: Buy CE or Sell PE"
    ],
    "bearish": [
      "Crossover: ST AND VWMA cross BELOW VWAP together (pierce from above) on 3-min",
      "Time From: 3 mins (confirmed on 3-min)",
      "RSI > 25 on 3-min (not oversold) per strategy card",
      "Volume during crossover: Bank Nifty 50K+, Nifty 125K+",
      "OI: drastic change in change-of-OI on both sides; bearish bias (Call OI increasing quickly and Put OI falling faster)",
      "Trade: Buy PE or Sell CE"
    ]
  },
  "exit_conditions": {
    "target": "Move expectation (index points): Bank Nifty ~100-150 pts (S20 conservative), Nifty ~50-70 pts; volume-backed crossover ~200 pts. S21 Day 6: clean crossover ~200-300 pts BN (~200/side); best window 10-11 AM. Live examples: ~100-pt move in <15 min (Midcap); Bank Nifty 19590->19700 (~110 pts).",
    "stop_loss": "Support-trade form: SL = the Supertrend level (S21 Day 7, resolves prior open item). Breakout form: structure-based SL (crossover-candle extreme or VWAP reclaim against position) per Global Risk Framework. Bullish: a first-candle high can act as resistance/no-trade reference.",
    "time_exit": "Per Common Components time filters (avoid sideways 11am-1pm; no new entries before events after 3:30pm). No strategy-specific time-exit stated.",
    "scaling": "Not specified. Scalpers can capture some points even in low-volume crossovers; reserve full expectation for volume + drastic-OI confirmed crossovers."
  },
  "risk_management": [
    "Defer to Global Risk Framework for sizing, max loss, daily cap",
    "Rare setup — do not force; act only on volume AND drastic two-sided OI change",
    "Skip low-volume / no-body / no-drastic-OI crossovers (primary retail trap)",
    "Strike/delta: 0.6-0.7 delta, strikes within ATM +/- 3",
    "Premium ranges: Nifty 100-250, Bank Nifty 250-400",
    "If two volume-backed Golden Crossovers occur in a day (rare), trade either or both"
  ],
  "filters": [
    "Time-of-day: after 9:45 am; ideal 9:15-10:00; avoid sideways 11am-1pm",
    "Volume (defining): BN 50K+, N 125K+ on crossover candle",
    "RSI 3-min: bullish < 75, bearish > 25",
    "OI: Trending OI (5-15 min) 5/7 strikes around ATM; drastic change in change-of-OI on both CE and PE sides; Sentiment graph slope agreement",
    "Global cues / VIX per Common Components if used for overall direction"
  ],
  "edge_cases": [
    "No-volume crossover: no follow-through; expect sideways or opposite-side action; do not trade",
    "No-drastic-OI-change crossover: small move even if crossover happens; usually unsupportive volume; skip",
    "No-body crossover candle: not a Golden Crossover even if next candle qualifies; wait for next support trade",
    "First-candle high acts as resistance on a low-volume bullish crossover -> no trade",
    "Partial crossover with volume (not full Golden Crossover) can still be traded in move direction; volume is the key requirement",
    "Rarity: ~3-4 times/month; more frequent and larger in Bank Nifty than Nifty"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 6 (22nd Sep 2023)",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Golden Crossover Strategy.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Golden Crossover Strategy.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 4/Day 04_LMP23 20th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Day 06_LMP23 22nd Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 7/Day 07_LMP23 25th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf"
  ],
  "uncertain": [
    "Bearish RSI gate: strategy card states RSI > 25 (not oversold), but a matrix row reads 'RSI less than 25 in 3 mts chart' for confirming weakness — needs confirmation which is operative; primary = RSI > 25.",
    "No explicit numeric stop-loss is stated for this strategy in the Golden Cross sources; SL approach (structure-based vs fixed points) needs confirmation."
  ]
}
```

### 6.7 Hero-Zero (Expiry-Day OI Strategy)
```json
{
  "name": "Hero-Zero (Expiry-Day OI Strategy)",
  "key": "hero_zero",
  "market_context": "Expiry-day directional option-buying. Sellers (writers) dominate on expiry day; the buyer's edge is to identify where sellers are forced to exit/unwind and sit on the opposite side. Only ATM strikes carry premium so the underlying is rangebound; sellers panic only when a level is breached. Buy a cheap soon-to-expire option on the side opposite where sellers pump funds; late-day short covering near S/R is the fuel.",
  "instruments": [
    "Nifty options (weekly expiry)",
    "Bank Nifty options (weekly expiry)",
    "Monthly expiry / Fin Nifty options (monthly perspective)"
  ],
  "timeframe": "Intraday expiry day; direction read on 3-min Futures chart (Bank Nifty 3m); execution after 2pm, observe short covering 2:30-3:00pm, hard close 3:20pm",
  "indicators": [
    "OI Expiry analysis page",
    "Option Chain page",
    "OI Spurts (4 quadrants)",
    "Options Premium",
    "Trending OI graph (5-15 min)",
    "Sentiment graph",
    "OI Pulse / OIP AI direction",
    "RSI 14 (3-min)",
    "VWAP",
    "OI interpretation LB/SC/SB/LU"
  ],
  "setup_preconditions": [
    "You are on expiry day and time is after 2pm",
    "Identify where highest OI and volume build-up is happening",
    "Put is support, Call is resistance based on heaviest writing; largest change in OI marks where sellers expect price will NOT cross",
    "Observe where short covering is happening between 2:30pm and 3:00pm around support/resistance",
    "Prepare from a week of data: 5 strikes either side of ATM (10 if huge volatility/spread); always include round strikes (e.g. 40000,40500,41000,42000)",
    "For monthly/Fin Nifty: 2-3 strikes from previous close plus close round strikes, analyse a week from monthly-expiry perspective",
    "Confirm via combined view of Option Chain, OI Spurts, Options Premium and OI Expiry analysis; US/global cues give clue for next move",
    "Strong-move confirmation needs BOTH OI >50% and price >50% on the same side; if OI rises but price does not follow, move will not continue"
  ],
  "entry_conditions": {
    "bullish": [
      "Market closing toward day's high and OI at day's low/high",
      "Confirm direction on 3-min Bank Nifty Futures chart",
      "RSI not overbought (must NOT be >75) [UNCERTAIN - BTST-column borrow; no Hero-Zero deck states an RSI threshold]",
      "Significant short covering with drastic fall in OI; verify change in OI is significant in OI statistics",
      "If market is up, take CALL one strike below the strike where short covering (SC) is happening = Hero-Zero strike",
      "Do NOT buy the cheap already-short-covered hero-zero strike priced 10-14",
      "Confirm your view matches OI Pulse view at 3:20pm before firing [BTST-column/shared gate, not a Hero-Zero-deck rule; the 3:20pm hard close itself is Hero-Zero-grounded]",
      "Confirmation reads: short build-up on Call + long build-up on Put = double confirmation to buy Put / sell Call; when sellers shift from writing Calls to writing Puts (PUT buyers unwinding), sit as buyer on the Call side (why Calls move higher on expiry)"
    ],
    "bearish": [
      "Vice versa of bullish (checklist gives bearish branch as 'vice versa for put side'; no separate numbered bearish block in Hero-Zero column)",
      "Market closing toward day's low and OI at day's low/high",
      "Confirm direction on 3-min Bank Nifty Futures chart",
      "RSI not oversold (mirror of not >75)",
      "Continuous call writing after minor short covering = persistent bearish pressure (Day 9 spot example 44600); verify significant OI change",
      "If market is down, take PUT one strike above the strike where short covering is happening",
      "Avoid the already short-covered cheap hero-zero strike (10-14)",
      "CAUTION: do NOT take PE trade when calls trade at a discount (one up move to adjust PE premiums can dent capital); wait for an up move to sell into; ignore premium-adjustment fake-out moves"
    ]
  },
  "exit_conditions": {
    "target": "Not explicitly numbered; hero-zero payoff captured by riding the short-covering/Gamma squeeze (cheap option can multiply e.g. ~6 to ~70) and exiting by time-close; OI cooling ~50% of the move tends to mark the settling point",
    "stop_loss": "50% of premium, but mandatorily close at 3:20pm regardless. S22 (resolves prior open item): index-scaled point SL = Bank Nifty ~75 pts, Nifty ~30 pts, wider for Sensex (~80000)/Bankex (~58000). Conditional 3:10pm no-move exit if the move is not happening (tighter than the 3:20pm hard close). Deploy only ~10% of profits.",
    "time_exit": "Do not trade before ~2:30-2:45 PM (S21 Day 6, tightens S20 'after 2 PM'); decide the side after 1:30-2 PM by premium/where shorts build; hard exit at 3:20pm (also the OI Pulse view-match check time - that match step is a BTST-column/shared gate)",
    "scaling": "Not specified in source"
  },
  "risk_management": [
    "Buy-side only on expiry day due to uncapped seller Gamma risk (no time value, all intrinsic near expiry)",
    "Do NOT buy the already short-covered ultra-cheap strike priced 10-14",
    "Mandatory hard time-stop at 3:20pm; SL 50% of premium",
    "Do NOT take PE trades when calls trade at a discount",
    "Sit out when long unwinding happens on both sides (only premium erosion, buyer cannot profit)",
    "No trades when market is pinned at VWAP (premium erosion)",
    "No new entries before impending events",
    "Intraday only - closed by 3:20pm, no overnight/night risk",
    "Reference Global Risk Framework for sizing and daily caps"
  ],
  "filters": [
    "Expiry day only, after 2pm (precondition)",
    "Observe short covering 2:30-3:00pm; OI Pulse view-match at 3:20pm (BTST-column/shared gate); hard close 3:20pm",
    "Highest OI/volume build-up defines S/R",
    "Significant short covering with drastic OI fall required",
    "Require >50% OI and >50% price move on same side for confirmed/continuing move",
    "RSI not overbought (>75) on 3-min for call side [UNCERTAIN - BTST-column borrow]",
    "Trending OI (5-15 min) and Sentiment graph read alongside OI Expiry analysis",
    "No trades when sellers hold market at VWAP",
    "US/global cues give clue for next move",
    "Combined confirmation: Option Chain + OI Spurts + Options Premium + OI Expiry analysis"
  ],
  "edge_cases": [
    "Expiry pinning at VWAP = no trades (premium erosion only)",
    "Bearish-data upside moves are premium-adjustment fake-outs to take out weak hands; wait for up move to sell PE",
    "Both-sides long unwinding = sit out",
    "Distinguish long unwinding from short covering: OI falling + price closing lower but NOT at day's high = long unwinding (shorts will then enter)",
    "Continuous call writing after minor short covering = persistent bearish pressure (spot 44600)",
    "Gamma squeeze can spike cheap option (~6 to ~70); sellers without stops wiped out, stops can be skipped in sudden spikes",
    "Rangebound start when calls and puts have roughly equal volume; only ATM carries premium; sellers panic only on level breach"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 8",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Oi Expiry Strategy Mentoring.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Oi Expiry Strategy Mentoring.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Day 02_LMP23 07th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 3/Day 03_LMP23 08th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Day 08_LMP23 26th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf"
  ],
  "uncertain": [
    "Bearish entry block: checklist gives only 'vice versa for put side' with no separate numbered bearish steps in the Hero-Zero column - bearish steps mirrored from bullish, UNCERTAIN - needs confirmation",
    "Profit target: no explicit numeric target stated for Hero-Zero; payoff inferred from hero-zero/Gamma-squeeze nature - UNCERTAIN - needs confirmation",
    "RSI not-oversold threshold for bearish side not explicitly stated in Hero-Zero column (only 'RSI should not be overbought >75' for the bullish/call side) - UNCERTAIN - needs confirmation"
  ]
}
```

### 6.8 BTST / STBT
```json
{"name":"BTST / STBT","key":"btst_stbt","market_context":"Short-term overnight carry strategies. BTST (Buy Today Sell Tomorrow) is bullish/long carry; STBT (Sell Today Buy Tomorrow) is bearish/short carry. Take a directional position late in the session when EOD data (price closing at day's high/low + correct OI quadrant + RSI + global cues) builds conviction in a continuation move, hold overnight, exit next morning. Magnifies both profits and losses; trade only if risks are understood.","instruments":["Bank Nifty / Nifty Futures (3m chart for direction)","BTST: Buy Futures","BTST: Sell PE","BTST: Buy CE","BTST stocks: buy Futures or buy stock in cash market (no stock options; no delivery needed when sold next day)","STBT: Sell Futures","STBT: Sell Call options","STBT: Buy PE","STBT stocks: sell Futures or sell stocks","Options (when used): strikes within ATM +/-3, delta 0.6-0.7 for buys, premium Nifty 100-250 / Bank Nifty 250-400"],"timeframe":"3-minute (primary; Futures chart Bank Nifty 3m). Decisioning is end-of-day with checks at 2:30-3:00pm, 3:15pm, 3:20pm; exit next morning.","indicators":["RSI 14 (BTST example days >60; no fresh stock carry if daily RSI >75; STBT example days <40; over-bought watch for BTST, over-sold watch for STBT)","OI behaviour: Long Build-up (LB), Short Covering (SC), Short Build-up (SB), Long Unwinding (LU)","OI Spurts 4 quadrants","Trending OI graph (5-15m)","Sentiment graph","OI Pulse / OIP AI direction","Futures OI Analysis","OI expiry-day analysis","EOD analyser","India VIX","Global cues (DOW/Dow30, Dollar index, Asian markets, Oil)"],"setup_preconditions":["Determine the late-day picture: market closing at day's high (BTST) or day's low (STBT) and where OI sits (day low vs day high)","Map close to OI quadrant to confirm BTST vs STBT and OI behaviour (SC/LB/SB/LU)","Identify where highest OI and volume build-up is happening; set Put as support / Call as resistance for BTST (vice versa STBT)","Observe where short covering (BTST) / short build-up (STBT) happens between 2:30pm and 3:00pm around S/R","At 3:15pm confirm Futures OI direction (bullish BTST / bearish STBT)","At 3:15pm confirm Option OI direction (Trending OI & Sentiment check)","Confirm global cues positive (BTST) / negative (STBT) as of 3:15pm","RSI not overbought >75 for BTST; over-sold watch for STBT","At 3:20pm confirm view matches OI Pulse view","Volume high & bullish (BTST) / bearish (STBT) in last 30 minutes","Stocks: short build-up + 8D or 9D low break = ideal STBT candidate (mirror for BTST)","Confirm with EOD data and global cues; on event days data takes a back-seat"],"entry_conditions":{"bullish":["Market closing at day's high; OI at day's low (SC, Quadrant 3) or OI at day's high / long build-up forming (LB, Quadrant 1)","Best: Long Build-up + market closing at day's high; or short covering shifting to long build-up; best after consolidation","Example good day: 3m TF, RSI above 60, Futures in Short Covering, price closing at day's high; next-day confirm: previous day high NOT tested","3:15pm: Futures OI bullish, Option OI bullish, global cues positive, RSI not overbought >75","3:20pm: view matches OI Pulse","Execute: Buy Futures / Sell PE / Buy CE; AI-suggested strike within premium range, ATM +/-3, delta 0.6-0.7","Enter near the close and carry overnight"],"bearish":["Market closing at day's low; OI at day's high (SB, Quadrant 2) or OI at day's low (LU, Quadrant 4 — confirmed S21 Day 12)","Example good day: 3m TF, RSI below 40, Futures in Long Unwinding (one slide example Short Build-up), price closing at day's low; next-day confirm: previous day low tested","Stock candidate: short build-up + 8D or 9D low break","3:15pm: Futures OI bearish, Option OI bearish, global cues negative","3:20pm: view matches OI Pulse","Execute: Sell Futures / Sell Call options / Buy PE; for stocks sell Futures or sell stocks","Enter near the close and carry overnight"]},"exit_conditions":{"target":"Exit the next morning (overnight carry). No fixed point target stated; trail next day once a decent profit is earned.","stop_loss":"Stop loss 50% (option leg) but close at 3:20pm. Manage out if next-day premarket / global cues / morning-trade read do not align with prior 3:20pm view.","time_exit":"Exit positions the next morning; carry not held beyond next session.","scaling":"Not explicitly specified beyond trailing next-day winners (UNCERTAIN - needs confirmation)."},"risk_management":["Not more than 1 night risk; avoid Friday (weekend gap)","Never carry fresh positions for stocks if daily RSI is above 75","Improper BTST near expiry against the trend can wipe out capital","STBT short-selling in stocks can attract penalty if cannot deliver/square off on time during monthly expiries (e.g., Hindalco)","Overnight gap risk: end-of-session move may not sustain due to world markets / news","Stop loss 50% on option leg; sizing/max-loss/daily-cap per Global Risk Framework"],"filters":["Time-of-day: observe SC/SB 2:30-3:00pm; OI & global checks at 3:15pm; OI Pulse alignment at 3:20pm; keep off if event after 3:30pm","India VIX: VIX/Dow confirm direction (VIX down = BTST, VIX up = STBT); Day 2 note: BTST when VIX closes at day's low and market closes at day's high","OI: quadrant mapping (LB/SC/SB/LU); Trending OI, Sentiment, Futures OI Analysis, OI expiry-day analysis, OI Pulse; check OI statistics for significant change in OI","Global cues: DOW/Dollar index/Asian/Oil must match direction as of 3:15pm","RSI bands: SC+BTST watch over-bought; SB/LU+STBT watch over-sold; BTST examples RSI>60, STBT examples RSI<40; daily RSI >75 = no fresh stock carry","EOD data must confirm chart read; on event days data takes a back-seat"],"edge_cases":["Form positional view via EOD analyser + OI expiry-day analysis (where sellers built positions at close) + Futures OI Analysis","Expiry: improper BTST against trend near expiry can wipe out capital; STBT stock short-sell risks delivery penalty in monthly expiries","Events/news: keep off if event after 3:30pm; on event days data takes a back-seat; overnight world-market/news can negate the move","BTST/morning-trade take less screen time but are risky; commodities (evening) an alternative for time-constrained traders","Next-day continuation read: BTST confirmed if previous day high NOT tested; STBT confirmed if previous day low IS tested","S21 Day 12: the carry is delivered via the AI '320 Strategy' — a 3:20pm probability signal with a deliberately wide overnight SL; buy at the indicated price and exit when the SL is hit. Carry a buy side only if the strike is closing near the day's high; do not carry calls on long unwinding (S21 Day 8)."],"session_introduced":"20","sessions_present":["20","21","22","23","24"],"updated_session":"24","day_introduced":"Day 8 (26th Sep 2023)","valid_from_year":2023,"valid_to_year":"current","source_files":["StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/BTST _ STBT Strategy Mentoring.pdf","StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/BTST _ STBT Strategy Mentoring.pdf","StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx","StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Day 02_LMP23 07th Sep 2023.pdf","StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Day 08_LMP23 26th Sep 2023.pdf","StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf","StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 12/Day 12_LMP23 5th Oct 2023.pdf","StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf","StrategySources/OptionsScalperSiva/21 Live Scalping Mentoring 2024/Day 8/Day 08 - Live Scalping Mentorship 28 Mar 2024.pdf","StrategySources/OptionsScalperSiva/21 Live Scalping Mentoring 2024/Day 12/Day 12 - Live Scalping Mentorship 05 Apr 2024.pdf"],"uncertain":["[RESOLVED in S21 Day 12] STBT Long Unwinding = Quadrant 4 (BTST SC=Q3, LB=Q1; STBT SB=Q2); the S20 Day-8 slide's 'Quadrant 3' label for LU was the inconsistency.","Explicit scaling / partial-exit steps not stated beyond next-day trailing — needs confirmation.","STBT slide example day (23rd Dec 2022) shows 'Futures: Short build-up' in the Day-2 line while the Day-1 line shows 'Long Unwinding'; whether STBT good-day requires LU or SB on the carry day needs confirmation."]}
```

### 6.9 Morning Trade (Opening Trade Strategy)
```json
{
  "name": "Morning Trade (Opening Trade Strategy)",
  "key": "morning_trade",
  "market_context": "High-risk scalping-only trade at/just after market open to capture a single sharp directional move (one dip/one pop) in the first few minutes. Edge from prior-evening EOD Futures positioning + Trending OI + sentiment + global cues, executed on opening-tick rejection wick. For experienced traders only.",
  "instruments": [
    "Index options CE/PE (e.g. Bank Nifty PE/CE such as 45000 PE)",
    "Direction read on Bank Nifty 3-minute Futures chart"
  ],
  "timeframe": "Opening tick; 1-minute and 3-minute on the open (trade can be initiated and closed within the first 3-minute candle)",
  "indicators": [
    "3-min Bank Nifty Futures chart (direction)",
    "1-min candle (assess 2nd candle break of 1st)",
    "RSI 14 (band 80:20; overbought >75, oversold <30 referenced; 40-60 no-trade; 60+ CE, 40- PE)",
    "VWAP / yesterday's VWAP (not actionable before 10:30 AM)",
    "Trending OI graph (5-15m)",
    "Sentiment graph",
    "OIP / OI Pulse AI direction",
    "Futures OI & Option OI (prior-day 3:20 PM OI-Pulse check; 3:15 PM Fut/Option-OI check is BTST's)"
  ],
  "setup_preconditions": [
    "Do previous-day EOD analysis (our market close, global markets) and plan next-day open; EOD Futures data is the major input for the opening-tick view",
    "EOD data holds good only if no external news/market movement after close impacts positions created before close",
    "Study Futures data (where positions created: LB/SC/SB/LU), Trending OI, market/OI sentiment, and FII/DII activity (OI Pulse manual)",
    "EOD data must be convincing AND market must close at day's high or day's low to trade that direction; inside candle/close-near-open is NOT convincing",
    "OIP AI direction and pre-market direction must match; global cues (Dow, Dollar index, Asian, Oil) must match; Nifty adv/dec must match (adv>32=CE, dec>32=PE)",
    "Prior-day 3:20 PM OI-Pulse: Futures OI and Option OI (Trending OI & Sentiment) confirmed in intended direction (the 3:15 PM Fut/Option-OI check is BTST's, 3.8)",
    "Choose AI-suggested strike within premium range (Nifty 100-250, Bank Nifty 250-400; ATM +/-3; delta 0.6-0.7 for buys)"
  ],
  "entry_conditions": {
    "bullish": [
      "Confirm bullish alignment: OIP AI + pre-market bullish; global cues positive; Nifty advances >32 (CE); Futures & Option OI bullish at prior-day 3:20 PM OI-Pulse",
      "Read 3-min Bank Nifty Futures chart on open; assess 1-min candle and watch how 2nd candle breaks the 1st",
      "Enter when rejection wick forms (gap/dip rejected, turning up); RSI not overbought >75 (RSI 60+ supports CE; 40-60 no-trade)",
      "If 2nd candle aligns with AI and pre-market, fire; if not immediate, wait for 3-min candle to form and trade the breakout",
      "Use small quantity; full entry/exit can occur inside first 3-min candle"
    ],
    "bearish": [
      "Confirm bearish alignment: OIP AI + pre-market bearish; global cues negative (e.g. Dow negative); Nifty declines >32 (PE); Futures & Option OI bearish at prior-day 3:20 PM OI-Pulse. Justification: EOD short build-up/long unwinding (buyers not confident), run-up-to-event longs unwinding (profit booking/exhaustion), prior-day shorts still held, gap-down on news",
      "Read 3-min Bank Nifty Futures chart on open; assess 1-min candle and 2nd-candle break of 1st",
      "On gap open the attempt to reach previous day close fails; enter when rejection wick starts forming (worked example 2nd Feb 2023: entry 9:16 AM, RSI still above 30 so not yet oversold)",
      "Edge case: if RSI already oversold at gap-down open, do NOT chase the fall; wait for RSI to cool off back to resistance before taking PE",
      "RSI not overbought; RSI 40 and below supports PE (40-60 no-trade); fire when aligned with AI and pre-market, else wait for 3-min candle breakout; small quantity"
    ]
  },
  "exit_conditions": {
    "target": "Next resistance (CE) / next support (PE) as a defined Futures level; worked example exit at Futures 40000; one-dip/one-pop move per data analysis; book on small rejection from first 3-min candle",
    "stop_loss": "First candle low (long/CE) or first candle high (short/PE), as the case may be",
    "time_exit": "Initiate and close within the first 3-min candle; worked example entry 9:16 AM, exit 9:18 AM (250 points in 2 minutes); finish once target/SL hit; scalping only",
    "scaling": "Small quantity; can book even with small quantity on first 3-min-candle rejection; no positional conversion"
  },
  "risk_management": [
    "High-risk; experienced traders only who can handle morning volatility and interpret data; trade only if ready to take the risk",
    "Small position size, especially on near-flat / low-movement opens",
    "Strictly scalping; exit on target/SL hit; no holding/positional conversion",
    "Mechanical SL: first candle low (long) / first candle high (short)",
    "Reference Global Risk Framework for sizing caps, daily loss limits and night-risk policy"
  ],
  "filters": [
    "OIP AI direction must match pre-market direction",
    "Global cues (Dow/Dow30 futures, Dollar index, Asian, Oil) must match direction",
    "Nifty advance/decline: adv>32=CE, dec>32=PE",
    "Futures OI & Option OI (Trending OI + Sentiment) confirmed at prior-day 3:20 PM OI-Pulse check",
    "RSI 14: not overbought >75 for CE; 60+ CE, 40- PE, 40-60 no-trade; on gap-down already-oversold wait for cool-off to resistance",
    "VWAP not used for trades before 10:30 AM; before 10:30 use only previous-day close analysis + global cues; if below yesterday's VWAP keep looking for selling opportunity until above it",
    "Stand aside if post-close news invalidates EOD positioning"
  ],
  "edge_cases": [
    "Gap opens are core context; on gap-down (e.g. 2nd Feb 2023 news flow) attempt to reach prev day close fails and rejection wick is the trigger",
    "Whole trade can live inside the first 3-min candle",
    "Flat / no-movement opens: still possible to take data-driven side (e.g. 45000 PE on 28th Sep 2023) with small quantity, brief move expected, book on first small rejection",
    "Gap-down with already-oversold RSI: do not enter the fall, wait for RSI back to resistance",
    "VWAP not actionable before 10:30 AM",
    "Despite gap-up breadth (advances >100, declines <50) data + global cues can still point to PE side; let data/cues set direction, not raw breadth"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 8 (Opening Trade Strategy Mentoring, 26th Sep 2023); applied Day 9 (28th Sep 2023); refined Day 10 (3rd Oct 2023) and Day 11 (4th Oct 2023)",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Opening Trade Strategy Mentoring.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Day 08_LMP23 26th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 9/Day 09_LMP23 28th Sep 2023 Live Commentary session.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 10/Day 10_LMP23 3rd Oct 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 11/Day 11_LMP23 4th Oct 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/DOC-20231129-WA0011.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 8/Opening Trade Strategy Mentoring.pdf"
  ],
  "uncertain": [
    "UNCERTAIN — needs confirmation: General matrix rule 'trade after 9.45am / ideal 9:15-10:00' appears to conflict with the opening-tick execution at 9:16 AM in the worked example; for the morning trade the opening-tick timing governs.",
    "UNCERTAIN — needs confirmation: The 'Stop loss 50% but close at 3:20pm' line sits in the matrix block shared with BTST and likely belongs to BTST, not the morning scalp; morning-trade SL is the first-candle high/low.",
    "UNCERTAIN — needs confirmation: Premium ranges (Nifty 100-250, BN 250-400), ATM +/-3 strikes, and 0.6-0.7 delta are inferred from Common Components/glossary and the matrix; not explicitly restated in the Day 8 opening-trade slide text."
  ]
}
```

### 6.10 Options Scalping Framework (Connect the Dots)
```json
{
  "name": "Options Scalping Framework (Connect the Dots)",
  "key": "scalping_framework",
  "market_context": "Confluence ('connect the dots') intraday scalping of index options; aggregate global cues, India VIX, OI Spurts/Trending OI, IV across 6 strikes, and a 5-indicator price chart into one directional read; trade only when most factors agree. VWAP is the most important chart factor; wider price-to-VWAP gap = stronger trend.",
  "instruments": [
    "Nifty options (CE/PE, buy side)",
    "Bank Nifty options (CE/PE, buy side)",
    "Direction read on Bank Nifty 3-min futures chart",
    "Long side may also be Buy Futures or Buy cash (no stock options)"
  ],
  "timeframe": "Primary scalping 3-min; 60-min aggregated 'Connecting the Dots' for day bias; RSI cross-checks 5-min and Daily; Trending OI 5-15 min",
  "indicators": [
    {
      "name": "VWAP",
      "settings": "default",
      "note": "most important; buyers/sellers defend here"
    },
    {
      "name": "Supertrend",
      "settings": "scalping 10,2 on 3-min; intraday variant 7,3 on 15-min/1-hour (only ST changes between scalp and intraday)"
    },
    {
      "name": "VWMA",
      "settings": "default length"
    },
    {
      "name": "Parabolic SAR",
      "settings": "0.02, 0.2 (default)",
      "note": "first indicator showing trend direction and how long it persists"
    },
    {
      "name": "RSI",
      "settings": "RSI 14, band 80:20",
      "note": "long Buy 50-75, No-Trade 40-50, OB >75/80; short Sell 40-25, OS <25/20"
    },
    {
      "name": "Volume candle",
      "settings": "threshold 50K Bank Nifty / 125K Nifty"
    },
    {
      "name": "India VIX",
      "settings": "directional rules"
    },
    {
      "name": "OI Spurts (4 quadrants)",
      "settings": "50% OI / 50% price thresholds"
    },
    {
      "name": "IV (6 strikes)",
      "settings": "CE/PE pair interpretation"
    },
    {
      "name": "DOW 30 futures / global cues",
      "settings": "DOW, dollar index, Asian, oil"
    }
  ],
  "setup_preconditions": [
    "Trade only after 9:45am (framework-native gate) + RR ~1%; the 'ideal 9:15-10:00 / moves may already have happened' caveat is an O=H-column Desirable borrow (3.2)",
    "Build 3-min chart with 5 dots: VWAP default, ST 10,2, Volume 50K BN/125K N, RSI 14 (80:20), PSAR 0.02,0.2; add VWMA default",
    "Read macro dots: DOW30 futures, India VIX, OI Spurts 4 quadrants, OI strikes/futures, IV 6 strikes; global cues align",
    "Connecting Dots aggregate must be Bullish (long) or Bearish (short) - most factors agree",
    "Volume confirmation >=50K BN / >=125K N",
    "Strike selection: framework-native delta 0.6-0.7 + AI-suggested strike; ATM +/-3 and premium 100-250 Nifty / 250-400 Bank Nifty are O=H-column borrows (3.2 / shared 4.9)",
    "Strike OI interpretation LB or SC for longs; SB or LU for shorts"
  ],
  "entry_conditions": {
    "bullish": [
      "Trade after 9:45am",
      "2 GREEN candles with volume above 50K (125K Nifty) on 3-min BN futures chart",
      "RSI not overbought: 50-75 Buy Zone (or cool off); 40-50 No-Trade; RSI moving above 50",
      "2nd candle strong and all indicators below price",
      "No major resistance nearby (low prob if present)",
      "Futures Long Build-up or Short Covering",
      "OI: Call OI declining / Put OI increasing; strike LB or SC",
      "Trending OI cross-over with widening gap (Put crosses Call OI, 5-15 min); Sentiment graph sloping up",
      "Buy strike delta 0.6-0.7 (framework-native); ATM +/-3 + premium 100-250 N / 250-400 BN (O=H-column borrows, see setup)",
      "VIX going down while market up; 'Connecting Dots Bullish?' = YES",
      "Desirables: ST & VWMA cross above VWAP together (Golden Cross) with vol >=50K BN/125K N; PSAR switches bullish; S/R breakout confirms; IV rising in strike; adv>32=CE; IV 30/20 (>=10pt diff up-side) or 10/10 supports trend play",
      "Enter when momentum up / volume picking up"
    ],
    "bearish": [
      "Trade after 9:45am",
      "2 RED candles with volume above 50K (125K Nifty) on 3-min BN futures chart",
      "RSI not oversold: 40-25 Sell Zone preferable; RSI moving below 50; RSI higher than 25 on 3-min",
      "2nd candle strong and all indicators above price",
      "No major support enroute (low prob if present)",
      "Futures Short Build-up or Long Unwinding",
      "OI: Call OI increasing / Put OI declining; strike SB or LU; substantial vs CE strike",
      "Trending OI cross-over with widening gap (Call crosses Put OI, 5-15 min); Sentiment graph sloping down",
      "Buy PE strike delta 0.6-0.7 (framework-native); ATM +/-3 + premium 100-250 N / 250-400 BN (O=H-column borrows, see setup)",
      "VIX going up while market down; 'Connecting Dot Bearish?' = YES",
      "Desirables: ST & VWMA cross below VWAP together with vol >=50K BN/125K N; PSAR switches bearish; S/R breakdown confirms; IV falling in strike; dec>32=PE; IV 40/40 = stay away or short straddle",
      "Enter when momentum down / volume picking up"
    ]
  },
  "exit_conditions": {
    "target": "Aim not more than 1-2% (RR ~1%); next resistance (long)/support (short). The '30-50 points / exit 5 pts below open-high / above open-low' figure is the Open=High/Open=Low exit (3.2), applicable only when this framework scalps an O=H setup - not a native Connect-the-Dots target",
    "stop_loss": "1st candle low (bullish) / 1st candle high (bearish); on gap-fill entries trail SL 5 pts below",
    "time_exit": "Morning/scalp trade only - finish once target/SL hit; do not carry; trail if positional spillover and decent profit earned",
    "scaling": "RSI long: 75-80 book 90%, at 85 book remaining 10% (short mirror 25-20 book 90%, at 15 book 10%); do not hold above RSI 85 / below 15. VWAP scalp: manage bounce only up to VWAP; break with volume = exit (reversal); break without volume = fake breakout, do not chase. Support trade: enter only at Supertrend level on pullback; pull-back entries near VWMA/ST/VWAP"
  },
  "risk_management": [
    "Aim not more than 1-2% per trade; RR target ~1%",
    "SL anchored to 1st candle low/high; reference Global Risk Framework for sizing and daily cap",
    "Not more than 1 night risk; avoid Friday (carry context)",
    "Never add against a no-volume VWAP break (fake-breakout trap)",
    "Do not over-extend beyond RSI 85 / below RSI 15 - bounces/reversals huge and quick"
  ],
  "filters": [
    "Time: after 9:45am only (framework-native); ideal 9:15-10:00 (O=H Desirable borrow); no new entries before impending event after 3:30pm",
    "India VIX rules: up+VIX cooling=Bullish; up+VIX rising=Bearish; down+VIX rising=Bearish; down+VIX stable=Bullish; sideways+VIX erratic=ignore VIX",
    "OI Spurts 4 quadrants: OI up+price up=Bullish(50/50); OI up+price down=Bearish(50/50); OI down+price up=Short Covering & Long Liquidation; OI down+price down=avoid unless hedging",
    "Trending OI cross-over with widening gap; LB/SC longs, SB/LU shorts",
    "IV 6-strike: 10/10 low IV trend play; 10/15 erosion if against trend; 20/20 mostly erosion; 30/20 bullish higher-IV side (>=10pt diff); 40/40 stay away / short straddle",
    "Global cues: DOW30 futures, dollar index, Asian, oil match direction; Nifty adv>32=CE, dec>32=PE",
    "RSI 14 (80:20): No-Trade 40-50; long Buy 50-75; short Sell 40-25; OB >75/80, OS <25/20; RSI(5m)<75/80 & RSI(D)<75 longs (mirror shorts)",
    "Strike: delta 0.6-0.7 + AI strike (framework-native); ATM +/-3 and premium 100-250 N / 250-400 BN are O=H-column borrows (see setup)"
  ],
  "edge_cases": [
    "Fake VWAP breakout: price above VWAP without volume then back into VWAP, no follow-up, opposite-side SLs hit, then sharp move (Bank Nifty live example) - treat as fake, not trend change",
    "Support trade at Supertrend: enter only when price pulls back to ST after upside crossover with bullish data; price may break ST toward VWAP but entry at ST keeps trade in plan",
    "Parabolic SAR flips are the first/early trend-direction and trend-duration cue",
    "Intraday vs scalping: only Supertrend settings change (3-min 10,2 scalp vs 15m/1h 7,3 intraday)"
  ],
  "session_introduced": "20",
  "sessions_present": ["20", "21", "22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 2",
  "valid_from_year": 2023,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Connect The Dots - Become Successful Options Scalper Day 2.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Post-Mentoring Documents/Sivas 9s.xlsx",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 2/Connect The Dots - Become Successful Options Scalper Day 2.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 4/Day 04_LMP23 20th Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 5/Day 05_LMP23 21st Sep 2023.pdf",
    "StrategySources/OptionsScalperSiva/20 Live Mentoring 2023/Day 6/Day 06_LMP23 22nd Sep 2023.pdf"
  ],
  "uncertain": [
    "'Sell PE / Sell CE' labels in the matrix appear as directional synonyms for the buy-side trade rather than explicit naked-option-selling instructions; primary expressed instrument is buying CE/PE (or buying futures/cash). Confirm before treating as a short-option-selling strategy."
  ]
}
```

### 6.11 Straddle (Long & Short)
```json
{
  "name": "Straddle (Long & Short)",
  "key": "straddle",
  "market_context": "NEW in Session 22 (Day 11). Neutral/volatility options strategy trading BOTH legs (Call+Put) of the SAME ATM strike and expiry. Long straddle = buy ATM Call+Put when direction is unclear but a big move (event/news) is expected and IV/premiums are LOW; profit only when the move exceeds total premium paid. Short straddle = sell ATM Call+Put when a range-bound day is expected and both-side IV is similar; harvest premium decay; unlimited risk on a volatility breakout. The combined-premium 'straddle chart' read against its own VWAP is the core tool.",
  "instruments": [
    "Bank Nifty index options (deck examples)",
    "Nifty / Fin Nifty index options",
    "Both legs ATM, same strike, same expiry"
  ],
  "timeframe": "5-minute straddle (combined-premium) chart read vs its own VWAP; individual Call & Put charts/VWAPs also watched",
  "indicators": [
    "VWAP (of the combined straddle and of each leg) — entry trigger and SL anchor",
    "Volume",
    "Implied Volatility (IV) — low for long, similar both sides for short",
    "Trending OI (change-in-OI moving together = range = short straddle)",
    "WMA (freak-candle SL caution)"
  ],
  "setup_preconditions": [
    "Pick the variant from the volatility view: event + LOW IV -> Long; range + similar both-side IV -> Short",
    "Breakeven = the underlying must move more than the COMBINED premium from the strike (combined ~1000 needs >1000-pt move)",
    "Trending OI change moving together on both sides supports a Short straddle (sideways day)",
    "Strike: default ATM (highest extrinsic value); OTM for a safer bet (one leg ITM for the other side); ATM preferred over deep OTM"
  ],
  "entry_conditions": {
    "bullish": [
      "LONG STRADDLE (volatility-expansion / event play): deploy ONLY when IV and premiums are LOW",
      "Entry trigger: the COMBINED straddle price breaks ABOVE its VWAP line WITH volume (sellers in panic)",
      "Event/budget form: after ~12:30 PM, enter after price CLOSES ABOVE the VWAP of both the Call and the Put",
      "One-leg management: once the combo is above VWAP and only one leg is gaining, exit the losing leg and hold the winner",
      "Example 12-Sep-2024: long straddle entered 2:10 PM on the VWAP break made ~200 points by EOD"
    ],
    "bearish": [
      "SHORT STRADDLE (range/decay play): deploy when both-side IV is similar and Trending-OI change moves together",
      "Entry trigger (Bank Nifty, 5-min): after 9:30 AM, enter after price FALLS BELOW the VWAP of both the Call and the Put",
      "Hold while VWAP holds (the trade works as long as the combined price stays below VWAP)",
      "Example: sell 54000 Call+Put ~9:30 AM at combined ~280 -> 100+ points by 11 AM (VWAP never breached)"
    ]
  },
  "exit_conditions": {
    "target": "Long: ride the directional move (example ~200 pts); P&L example market 54000, Call 70 + Put 50 = 130 combined, close 54300 -> Call 300/Put 0 -> profit 170 pts; close 54000 -> both zero (lose 130). Short: harvest premium decay (example 100+ pts).",
    "stop_loss": "Long straddle: SL = BELOW the VWAP (of both legs). Short straddle: SL = ABOVE the VWAP (of both legs).",
    "time_exit": "Long: exit on a lower-low candle formation OR when the combined premium peaks and rolls over (book, do not wait for full reversal). Short: exit on considerable premium decay or end of day; exit immediately if price breaks back through VWAP.",
    "scaling": "Long: optionally hold only the winning leg after the combo clears VWAP. Trade only from a slice of profits."
  },
  "risk_management": [
    "Short straddle = UNLIMITED risk on a volatility breakout (both legs' SLs hit); low-volume freak candles around VWAP/WMA can hit an ATM straddle seller's SL multiple times intraday (observed: 23700 Fin Nifty SLs could have hit at least 4 times)",
    "Short straddle carries unlimited risk on a volatility breakout, so a hard SL above VWAP is mandatory; the source allows pre-event deployment 'when there is no clear direction' (a range/decay expectation), so treat it as a range bet not a directional gamble",
    "Long straddle risk = the combined premium; an IV crash erodes both legs even if the market moves -> hence the LOW-IV entry gate",
    "Use the combined-premium breakeven to size the expected move realistically (do not pay 1000 of premium expecting a 100-200-pt move)",
    "Deploy only from profits; Global Risk Framework (sizing, daily cap) applies"
  ],
  "filters": [
    "IV: Long -> enter only at LOW IV/premiums; Short -> wants Call/Put IV similar; S22 general rule IV above 40 = stay away as a buyer; per the 4.6 IV table a 40/40 reading = 'stay away / play short straddle'",
    "Trending OI: change moving together = range = short-straddle day; divergence/break = the long/directional case",
    "VWAP is the single entry + SL anchor on every variant",
    "Events: long straddles are the event play (Budget, election result, major news)"
  ],
  "edge_cases": [
    "The straddle chart (combined Call+Put premium vs its own VWAP) both times the entry (VWAP break) and enables one-leg management",
    "A short-straddle price breaking VWAP is the buyer's entry cue (trend day / near the close) — see 4.14.8",
    "Skip a long straddle when the combined premium is already rich (required move too large)"
  ],
  "session_introduced": "22",
  "sessions_present": ["22", "23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 11 (Live Mentoring Prog 2.0 2024)",
  "valid_from_year": 2024,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/22 Live Mentoring Prog 2.0 2024/Day 11/Straddle Strategy 10th Mentoring Day 11.pdf",
    "StrategySources/OptionsScalperSiva/22 Live Mentoring Prog 2.0 2024/Consolidated Synopsis LMP 2.0.pdf"
  ],
  "uncertain": [
    "No explicit per-trade point/percent SL beyond 'above/below VWAP'; size off the VWAP distance and the combined-premium breakeven.",
    "Deck example dates (2/3 Jan'23, 1 Feb'23) reuse 2023 chart days; the execution rules (VWAP entry/SL, 5-min TF, time windows) are the operative S22 spec."
  ]
}
```

---

### 6.12 Trend Change Strategy

```json
{
  "name": "Trend Change Strategy",
  "key": "trend_change",
  "market_context": "NEW in Session 23 (Day 10). A reversal-capture strategy that trades a SHIFT IN DIRECTION (sideways<->up, sideways<->down, downtrend->uptrend or vice-versa) rather than a continuation breakout. 'A trend change is simply a shift in direction; a shift in direction can result in a spurt of momentum' and can outperform a breakout. The trader must adapt intraday and not stay a morning-side buyer all day. The shift is read off DATA (primarily Trending OI, supported by RSI/VWAP/chart indicators) and is often visible in the Trending OI ~15-30 min BEFORE price confirms (Day-10 synopsis: ~11:00-11:15 AM OI flip vs ~11:30 AM two-candle confirm; deck trade examples ~7-15 min). Momentum is mandatory: if price moves but OI does not shift, do NOT trade (moves are 5-10-pt fakeouts). Live S23 vehicle = Sensex options traded off the Nifty chart.",
  "instruments": [
    "Sensex index options traded off the Nifty chart (S23 live vehicle)",
    "Nifty / Bank Nifty index options and futures",
    "buy CE / sell PE / buy futures for up-reversal; buy PE / sell CE / sell futures for down-reversal"
  ],
  "timeframe": "Trend identified via trend lines (horizontal/vertical/diagonal, triangles/pyramids/harmonic) and price-action swings; momentum shift read on the Trending OI tool (15-min crossover / 60-min trend per 4.4); confirmation + entry on the 3-minute 2-candle (true-candle) theory; multi-timeframe S/R from weekly/monthly trend lines, event lows, and 20/50-DMA convergence",
  "indicators": [
    "Trending OI (primary momentum-shift filter; call/put OI crossover) — see 4.4",
    "RSI 14 — above 60 for up-reversal, below ~40 for down-reversal (no-trade band 40-60 per 4.2)",
    "VWAP — the most-respected line; reversal holds unless VWAP breaks WITH volume",
    "Volume — increase + follow-up bars on the break (50K BN / 125K N per 4.2)",
    "Parabolic SAR / Supertrend / WMA (chart indicators, 4.2)",
    "Seller OI levels (max call OI = resistance, max put OI = support)",
    "India VIX (rising into the reversal direction supports it)",
    "Index-contribution / heavyweight stocks"
  ],
  "setup_preconditions": [
    "Identify the prevailing trend first (upward / downward / sideways); most setups start from a sideways/range day",
    "Spot a swing-structure break (HH/HL pause -> LH/LL for a down-reversal; LL/LH pause -> HL/HH for an up-reversal) OR a trendline break in the reversal direction",
    "Require a corroborating momentum shift in the Trending OI (a call/put OI crossover); without an actual OI change, treat moves as 5-10-pt fakeouts and skip",
    "Live precondition: intraday-data-bearish but positionally-bullish read (or vice-versa); the flip confirms when BOTH intraday and positional OI rotate to the same side",
    "Timing: can occur any time 9:45 AM-2:30 PM (need not print by 10/11); avoid morning prints/volatility — wait for the intraday trend to form"
  ],
  "entry_conditions": {
    "bullish": [
      "UP-REVERSAL: prior structure sideways/downtrend; price forms a higher-low/higher-high OR breaks the down-trendline upward",
      "Trending-OI shift to bullish: Call-side OI falling + Put-side OI rising (call writers exiting, put writers adding gradually; shorts covering + put writers adding = momentum). Apply a shift comparable to the deck's quantified mirror (call OI falling, put OI rising) before acting",
      "RSI above 60 AND the sideways range / down-trendline is broken",
      "Confirm with an INCREASE in volume + follow-up volume bars (50K BN / 125K N)",
      "2-candle (true-candle) confirmation: enter on the 3rd candle after the level breaks with volume (per 3.1); exit once it moves in favour",
      "Instruments: buy CE / sell PE / buy futures (S23 live = ITM Sensex CE off the Nifty chart)"
    ],
    "bearish": [
      "DOWN-REVERSAL: prior structure sideways/uptrend; price forms a lower-high/lower-low OR breaks the up-trendline downward",
      "Trending-OI shift to bearish: Call-side OI rising + Put-side OI falling (deck: call OI 1.72 cr -> 2.5-3 cr while put OI 4.5 cr -> ~3/2.5 cr; live Day 07: call OI 4 -> 6.5 cr, put 4.4 -> 3.92 cr around 10:00 AM)",
      "RSI below ~40 (mirror of the >60 up-trigger; 4.2 no-trade band is 40-60) AND the sideways range / up-trendline is broken downward",
      "Confirm with volume on the breakdown candle + follow-up bars (50K BN / 125K N); if it is already after ~2:30 PM, AVOID the trade",
      "2-candle (true-candle) confirmation: enter on the 3rd candle after the level breaks with volume; exit once it moves in favour",
      "Instruments: buy PE / sell CE / sell futures"
    ]
  },
  "exit_conditions": {
    "target": "Ride the reversal move; both deck worked examples captured ~400 points (sideways->uptrend day; 21 Feb 2023 lower-low->higher-low day). No fixed per-trade point target beyond 'exit once it moves in favour' / ride to VWAP. Entering late (after the OI crossover) yields smaller profit and is acceptable.",
    "stop_loss": "No explicit numeric (point/percent) SL in the S23 sources. The 2-candle entry implies the 3.1 structure SL (1st-candle low for a long / 1st-candle high for a short); VWAP is the structural defend line (exit if VWAP breaks WITH volume). Size off structure & VWAP per the Global Risk Framework (2). A ~10-20-pt benefit-of-doubt is allowed ONLY when the OI data convincingly confirms the direction.",
    "time_exit": "Valid 9:45 AM-2:30 PM; AVOID a fresh down-reversal after ~2:30 PM (deck: 'if it is already after 2.33, avoid it'); after ~2:30 PM both OIs falling = squaring off, do not chase.",
    "scaling": "Hold to VWAP (the patience line) once the trend is formed; trail/raise the SL after a profitable leg so gains are not given back. Trade only from a slice of profits."
  },
  "risk_management": [
    "Wait out morning volatility — a wrong naked entry on a presumed big move can lose 50-70% when it reverses in 2-3 candles; trade only after the intraday trend forms, else stay light",
    "Do not chase a direction when premiums are higher on that side and the market has no positive cues (an indirect warning that can trap you)",
    "VWAP discipline: never enter on a VWAP break without volume; once trend is formed, a volume-backed VWAP break is the invalidation",
    "Scale expectations to the regime: on a low-VIX expiry a 10-15-pt Nifty move is 'a big hit'",
    "Benefit-of-doubt against the SL (~10-20 pts) only with convincing OI data; otherwise honour the SL; Global Risk Framework (sizing, daily cap) applies"
  ],
  "filters": [
    "Trending OI (4.4) is the primary filter: a call/put OI crossover marks the shift (call OI up + put OI down = bearish; reverse = bullish). Both OI graphs climbing TOGETHER = strict AVOID as a buyer; after ~2:30 PM both falling = action over",
    "RSI (4.2): up-reversal needs RSI > 60; down-reversal needs RSI < ~40 (no-trade band 40-60); momentum must be present",
    "VWAP (4.2): the most-respected line; a VWAP break must come with volume to be trusted",
    "Volume (4.2): increase + follow-up bars (50K BN / 125K N) on the break is mandatory; a no/low-volume break tends to reverse",
    "Support/Resistance from seller OI: max call OI = resistance, max put OI = support (e.g. 24,900 / 24,800 box) — defines the range that must break",
    "VIX rising into the reversal direction supports it; a flat VIX warns the move lacks conviction",
    "Heavyweight drivers (Reliance, Infosys, TCS, banks) must support the new direction for a real reversal",
    "Time-of-day: 9:45 AM-2:30 PM; avoid morning prints; avoid a fresh down-reversal after ~2:30 PM"
  ],
  "edge_cases": [
    "Data leads price by ~15-30 min: the trend-change clue is in the Trending OI before price confirms (Day-10 synopsis ~11:00-11:15 AM OI flip vs ~11:30 AM two-candle confirm; live Day 07 ~10:00 AM flip) — prepare on the OI but still wait for volume + 2-candle confirmation to enter",
    "Failed-attempt reversal (Day 03): 1-2-3 failed attempts to clear a resistance precede a tank; confirm a down-reversal with 2-3 consecutive red bars >125K; mild volume at support favours recovery",
    "Trendline/structure pivot (Day 12): a held prior-day trendline / critical support never broken can be the up-reversal pivot; a deep Parabolic-SAR below price gives at least one bounce possibility",
    "Consolidation vs continuation: after a flip, OI added on BOTH sides = consolidation (pause); a genuine reversal of an established down-move needs the dominant unwinding volume beaten on an hourly basis PLUS a long build-up or short covering (Day 07)",
    "Post-vertical bounce caution (Day 07): after a vertical fall expect a severe bounce as RSI nears ~20-23; do NOT take a reversal until RSI recovers toward ~40 and a defined level is reached; a reversal where sellers added NO new put-side positions gets sold into",
    "News overrides data on gap/event/war days — trade smaller and confirm with post-open price behaviour",
    "Strong-trend / late entry is hard to catch — wait for pullbacks to support; do not chase"
  ],
  "session_introduced": "23",
  "sessions_present": ["23", "24"],
  "updated_session": "24",
  "day_introduced": "Day 10 (Sensex Scalping with Siva 2025)",
  "valid_from_year": 2025,
  "valid_to_year": "current",
  "source_files": [
    "StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Session PPT_s/Day 10/Trend Change Strategy.pdf",
    "StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 10 Synopsis - Sensex Scalping with Siva.pdf",
    "StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 03 Synopsis - Sensex Scalping with Siva.pdf",
    "StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 07 Synopsis - Sensex Scalping with Siva.pdf",
    "StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 11 Synopsis - Sensex Scalping with Siva.pdf",
    "StrategySources/OptionsScalperSiva/23 Sensex Scalping with Siva june 2025/Daily Synopsis/Day 12  Synopsis - Sensex Scalping with Siva.pdf"
  ],
  "uncertain": [
    "No explicit per-trade point/percent stop-loss is given for Trend Change; size off structure (1st-candle low/high per 3.1) & VWAP per the Global Risk Framework (2). The only quantified leeway is a ~10-20-pt benefit-of-doubt allowed when OI data strongly confirms.",
    "The down-reversal RSI threshold (<~40) is inferred as the mirror of the deck's explicit up-reversal 'RSI above 60' using the 4.2 no-trade band (40-60); the deck states only the >60 up-trigger numerically.",
    "No fixed numeric profit target — exit is VWAP/structure/momentum-based; the ~400-point figure is the example outcome, not a target.",
    "Deck worked-example dates reuse 2023 chart days (a sideways->uptrend day and 21 Feb 2023); the execution spec (OI shift + RSI>60 + volume + 2-candle 3rd-candle entry) is the operative S23 teaching, reinforced live across S23 dailies (Day 03/07/11/12)."
  ]
}
```

---

# 7. Open Questions / UNCERTAIN — needs confirmation

Points where sources conflict or are silent; confirm before hard-coding into a bot. **Session 21 resolved two of these** (Golden Crossover SL and the BTST/STBT Long-Unwinding quadrant) — marked **[RESOLVED in S21]** — and tightened Hero-Zero timing to ~2:30–2:45 PM. **Session 22 resolved two more** (the Open=High premium bands and the Hero-Zero numeric stop-loss) — marked **[RESOLVED in S22]** below. **Session 23 adds no new conflicts** — it largely confirms the prior sessions and adds the Trend Change strategy (§3.12, whose only open item is the absence of an explicit numeric SL — size off structure & VWAP) and the Sensex-scalping inputs (§4.16). Remaining items are still open.

**Two Candle Theory**
- Is the bullish RSI upper cap 75 or 80? Day-5 manual says 50-75; chess slides say 50-75/80.
- Is 5-minute an officially allowed primary timeframe, or is 3-minute the only standard? Slides say '3/5 Min TF'; Day-5 manual says 3-minute.
- Exact profit target in points/percent for the scalp beyond the 1-2% aim is not numerically specified for this strategy in the sources.
- Whether the alternate VWAP stop-loss fully replaces the 1st-candle SL or is used only in extended-move cases (Day-1 implies the latter but it is not stated as a general rule).

**Open = High / Open = Low (O=H / O=L)**
- **[RESOLVED in S22]** Premium bands: the **wider daily-note bands are operative** — Bank Nifty **250–550** (avoid >600/<200), Nifty **150–350** (avoid <130/>380); buyers near-ATM, sellers OTM (~250–400). The slide deck's Nifty 100-250 / Bank Nifty 250-400 are the older/general case (S22 Consolidated Synopsis, §3.2/§4.15.4).
- Master-grid global exit rows ('SL 50% but close at 3:20pm', 'not more than 1 night risk / avoid Friday', 'aim not more than 1-2%') appear in a shared column area; confirm these apply specifically to O=H/O=L as a holdable/positional trade versus being generic checklist boilerplate, since the strategy is described as pure intraday scalping.

**Market Movers**
- Confirm the intended primary chart timeframe (PDF examples are 5m; glossary suggests 3m for scalping).
- Confirm whether the short-trade slide's reason line ('Long build up indicating Bears are in charge') is a typo for Short build-up.
- No explicit position-sizing or daily-loss numbers are given in-strategy; confirm these come from the Global Risk Framework.
- Scaling/partial-booking rules are not specified in sources; confirm whether any apply.

**Gap Theory**
- Is the gap-candle volume confirmation governed by the 50K BN / 125K Nifty Common Components thresholds, or is there a Gap-specific volume rule?
- Should the executed instrument be CE/PE options (as in the deck examples) or Futures/cash (as the matrix Market-Movers analog suggests)?
- Are India VIX and DOW alignment checks mandatory for Gap Theory entries, or only advisory inherited from Common Components?
- For the risky counter-trend gap-fill trade, is there a maximum allowable distance/size before it should be skipped?

**Trending OI Crossover**
- Exact target value for this strategy: 1-2% vs 30-50 index points (both cited; not reconciled per instrument).
- Fixed stop-loss specification: sources give only 'book SL / switch sides' with no defined point/percent SL for Trending OI Crossover.
- Whether the >=50% Call-vs-Put OI-change filter is a day-cumulative reading or applies on the 5-15 min interval reading.
- Delta/strike-selection (0.6-0.7 delta, ATM +/- 3, premium ranges) appear in the shared matrix but are not explicitly tied to this strategy's cross trigger - confirm applicability.

**Golden Crossover**
- Bearish RSI threshold conflict: strategy card says RSI > 25 (not oversold) while a matrix row says RSI < 25 in 3-min — which governs entry?
- **[RESOLVED in S21 Day 7]** Stop-loss for the support-trade form of the Golden Crossover = the **Supertrend level**. For the breakout form, a structure-based SL (crossover-candle extreme / VWAP reclaim) per the Global Risk Framework still applies.
- Exact profit-booking/scaling rule (full target vs partial) — sources give move expectations but no explicit booking ladder.
- Is the direction chart strictly Bank Nifty 3-min Futures for all instruments, or the underlying's own 3-min chart when trading Nifty/Midcap?

**Hero-Zero (Expiry-Day OI Strategy)**
- **[RESOLVED in S22]** Numeric stop-loss: use an **index-scaled point SL — Bank Nifty ~75 pts, Nifty ~30 pts**, wider for Sensex (~80000) / Bankex (~58000) — alongside the existing "50% of premium / hard close 3:20 PM," plus a **conditional 3:10 PM no-move exit** and **deploy only ~10% of profits** (S22 Day-9 deck + Q9; §3.7/§5.7). (The strategy still lacks an explicit numeric *profit target* — exit remains squeeze/time/SL-based.)
- Bearish variant is only 'vice versa for put side' in the checklist — confirm the exact mirrored RSI band, strike-selection offset, and any PE-specific triggers.
- Confirm the exact meaning/units of the '10-14' avoid-cheap-strike threshold (rupee premium range of the already short-covered strike).
- Confirm whether the >50% OI / >50% price confirmation rule applies to the Hero-Zero entry strike specifically or only to the directional read.

**BTST / STBT**
- **[RESOLVED in S21 Day 12]** STBT Long Unwinding = **Quadrant 4** (Short Build-up = Q2); BTST Short Covering = Q3, Long Build-up = Q1. The S20 slide's "Quadrant 3" label for LU was the internal inconsistency — use Q4.
- Does the STBT carry-day require Long Unwinding or Short Build-up in Futures? Slide examples show both.
- Are there explicit partial-scaling / profit-booking percentages for the next-morning exit beyond 'trail once decent profit earned'?
- What exact next-morning time/price trigger defines the BTST/STBT exit (open, first 3m candle, or VWAP-based)?

**Morning Trade (Opening Trade Strategy)**
- Does the general matrix filter 'trade after 9.45am / ideal 9:15-10:00' apply to the morning trade, or is the opening-tick execution (9:16 AM in the worked example) an explicit exception?
- Does the 'Stop loss 50% but close at 3:20pm' line in the shared matrix block apply to the morning trade or only to BTST? Source placement suggests BTST.
- Are the premium ranges (Nifty 100-250, BN 250-400), ATM +/-3 strike limit, and 0.6-0.7 delta selection meant to govern the morning scalp specifically, or are they carried over from Common Components?
- What is the precise quantitative definition of a 'convincing' EOD close beyond closing at day's high/low, and how is an inside/close-near-open candle measured?

**Options Scalping Framework (Connect the Dots)**
- Does 'Sell PE / Sell CE' in the checklist mean actual option writing, or is it just a directional synonym for the buy-side scalp? Primary deck implies buying CE/PE.
- Exact RSI Daily/5m thresholds for the bullish vs bearish desirables (matrix lists RSI(5m) below 75/80, RSI(D) below 75 for longs) - confirm these are intended as hard filters vs guidance.
- Whether the '1 night risk / avoid Friday' cap applies to this intraday scalp framework or is carried over from the BTST column of the shared matrix.

**Straddle (Long & Short) — new in S22**
- No explicit numeric point/percent SL beyond "above/below VWAP" — confirm whether the SL is purely the VWAP level or VWAP plus a buffer (the S21 short-straddle note in §4.14.8 used "straddle VWAP + 10–15-pt buffer").
- The long-straddle deck specifies a ~12:30 PM event-form entry (close above VWAP of both legs) while the live example uses "combined straddle price breaks above VWAP with volume" at any time — confirm whether the 12:30 PM window is event-specific only.
- Strike is "ATM default, OTM for a safer bet" — confirm the conditions under which OTM is preferred over ATM and whether any delta/premium band applies to the legs.

**Trend Change — new in S23**
- No explicit numeric point/percent stop-loss is given — size off structure (the 2-candle 1st-candle low/high per §3.1) and VWAP per the Global Risk Framework (§2). The only quantified leeway is a ~10–20-pt benefit-of-doubt against the SL, allowed only when the OI data strongly confirms.
- The down-reversal RSI threshold (<~40) is inferred as the mirror of the deck's explicit up-reversal "RSI above 60" using the §4.2 no-trade band (40–60); the deck states only the >60 up-trigger numerically — confirm the exact bearish band.
- No fixed numeric profit target — exit is VWAP/structure/momentum-based; the ~400-point figure is the example outcome, not a target. Confirm whether a partial-booking/trailing ladder applies.
