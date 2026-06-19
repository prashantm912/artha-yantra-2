# OI Pulse Manual (V10) — Study Gap Analysis

**What this is.** A log of everything in the **OI Pulse User Manual (V10)** (`Oi-Pulse-Manual-file.pdf`, 317 PDF pages) that our existing study under `docs/oipulse-study/` is **missing or could be improved by**. It does **not** modify any existing study doc — it is a worklist of *additions* to fold in later.

**Source & method.** The whole manual was read page-by-page (text + screenshots) and cross-referenced against every existing study doc. The manual is an **older, smaller-feature edition** (it advertises "7 options sub-categories"; the live site has ~14). Our study, built from the **live site + captured API/Socket payloads**, is therefore *more current* on UI/feature inventory. So the value of the manual is almost entirely the **trading-interpretation / methodology layer** — the rules that turn the columns we already documented into a directional read and a trade — plus a handful of concept definitions and data-semantics our UI/API capture couldn't reveal.

**Scope rules (followed throughout).**
- **Additive only.** Nothing here removes, downgrades, or reduces the options of any existing study content.
- The manual being older means *absence* of one of our features in the manual is expected and is **not** a gap. Only "**manual has → study lacks / under-documents**" is logged.
- Items where the older manual may legitimately differ from the live site are tagged **[VERIFY]** and collected in §F to reconcile against live capture / Phase-B.

**Page convention.** Citations are **PDF page numbers**; the manual's printed footer = PDF page − 1 (e.g. PDF p027 = printed p26).

---

## Executive summary — the one big gap

Our study answers *"what does this screen show and what API feeds it."* The manual answers *"how do you read it to take a trade."* That second layer is missing across nearly the whole study. Concretely:

1. **OI interpretation has no strength/conviction layer.** We document the 4-state enum (Long/Short Build-Up, Short Covering, Long Unwinding) as a UI badge. The manual grades each by **Volume + Level-Break + significance** into STRONGEST/STRONG/WEAK, and says the prevailing bullish/bearish scenario flips how Covering/Unwinding read. (§B-futures)
2. **The options "four-quadrant" model + 50% strength filter is undocumented.** A strike in a quadrant is only a signal when **%ΔLTP > 50% AND %ΔOI > 50%**; each quadrant carries a specific trade action (Q1 buy / Q2 write / Q3 buy-but-short-lived / Q4 avoid). (§B-options, `oi-spurt.md`)
3. **The OI/LTP "X-crossover" signal is entirely absent** — a steep X between a strike's OI line and premium line marks Strong Short Build-Up / Short Covering, mirrored on the opposite leg, best on 15-min. (`oi-chart.md`)
4. **The "Magic of IV" ~10-point CE–PE IV-spread rule is absent** — buy the higher-IV side when the spread ≈ 10 pts; a tight spread = premium-erosion/sell regime. (`active-strikes-iv.md`)
5. **Five whole trading methodologies have no doc at all:** Expiry-Day Plan, Morning Trade, 3:20 Strategy, OSPL Signal, and the Open&High strategy (we have only its screener). (§A)
6. **Several data semantics our capture couldn't expose:** 15:30-EOD = NSE's *post-close adjusted* OI (readjustment only ever *decreases* OI, can flip the interpretation); options-chain **cell-highlighting** rule; Active-Strike **Sentiment %** sign convention; VIX is computed from Nifty OTM option premiums; option premium = **extrinsic value**. (§C)

The single highest-value follow-up is a **new methodology doc** consolidating items 1–4 (§A1), because those rules are referenced by almost every other page.

---

## A. New documents to add

These are standalone methodologies/features our study has **no doc** for.

### A1. `options/oi-interpretation-method.md` — the core OI read (NEW, highest value)
Consolidates Section B (PDF p022–p073), the manual's "how to actually trade OI" chapter. Should hold:
- **The 4-state matrix with meaning:** (price↑/↓ × OI↑/↓) → state, *plus* the fresh-vs-closing distinction — **Build-Ups = fresh positions = strong; Short Covering / Long Unwinding = position-closing = weak**.
- **Signal-strength grading** (see §B-futures): state × Volume(H/L) × Level-Break (D.H.B for bullish, D.L.B for bearish) × significant ΔLTP/ΔOI → **STRONGEST / STRONG / WEAK**.
- **Scenario-dependence:** Short Covering is "strongest during a bullish scenario," Long Unwinding "strongest during a bearish scenario" — the prevailing regime changes the read.
- **The options four-quadrant model + the >50%/>50% strength filter** and per-quadrant trade actions (§B-options).
- **OI/LTP X-crossover** (§B-options / `oi-chart.md`).
- **Timeframe roles:** 60-min = trend / overnight context (unreliable in the first half of the day), 15-min = intraday trend, 5-min = entry timing; confirm small-TF entries against the larger TF.
- **Strike selection (C5):** avoid OTM; prefer ATM/ITM; hunt the relatively-cheap ITM.
- Cross-link the pages that render each input (`oi-analysis`, `oi-statistics`, `oi-spurt`, `oi-chart`, `options-premium`).

### A2. `strategies/expiry-day-trading-plan.md` — Expiry-Day methodology (NEW)
Source PDF p161–p190. Distinct from `oi-expiry-strategy.md` (which is the *feature* "Options EOD Oi Analysis" this plan consumes). Two independent sessions:
- **Morning plan** (first ~1 hr) read off six "connecting dots": Price-Action & Volume, OI, Global Markets, FII/DII, Crude & USD-INR, immediate news.
  - Strike rule: round prev-day spot close to nearest 500 = ATM; take ±5–6 strikes, all multiples of 500.
  - Decision thresholds (worked on BankNifty 04-Mar-2021): **~55–60% Call covering = strong bullish; ~40% = capped**; check whether premiums closed near day-high vs day-low; up-move on *lower* volume than the prior down-move = weak rally.
  - Execution tools: VWAP, RSI (the open had RSI<20), opening-candle behaviour.
- **Closing plan** (last 1–1.5 hr before 15:30): only two factors — day's price action + last-hour ΔOI; read per-15-min OI-interpretation badges; enter on a VWAP cross.
- Risk framing: always trade a pre-made plan ("an idiot with a plan beats a genius without one").

### A3. `strategies/morning-trade.md` — Morning Trade signal (NEW)
Source PDF p279–p289. App route under Strategies: **"Morning Trade"** (missing from our README menu map). Signal-assisted opening scalp.
- 5-factor pre-open checklist: prev-day price action → OI analysis (Total OI + ΔOI) → broader markets (Nifty futures + FII data) → overnight global (Dow) → pre-open data (~09:08).
- **Gap-size decision tree** vs an important support: gap too big → no trade; 3 cases by where price opens relative to support (above = best for buying PE; well-below = no trade).
- Signal output table: **Call/Put Signal Entry** → Date, symbol, Expiry, Strike, CE/PE, **buy price-range**; "signal updates after 09:11 if available"; enter within ~10% of strike premium.
- Exit rules: **trail once average price reaches cost price**, and **exit within the first 3 minutes**; size = only 10% of prior day's profit. Worked: 14-Dec BANKNIFTY 36900 PE, range 296–396, opened 306 → high 462.

### A4. `strategies/3-20-strategy.md` — 3:20 Strategy (NEW)
Source PDF p290–p296. App route under Strategies: **"3:20 Strategy"** (missing from README map). Overnight/positional counterpart to Morning Trade.
- Premise: a strong trend into the close tends to continue at next open; the overnight move should cover theta.
- Decision ~15:20 from three factors: day's price action, OI analysis, broader markets.
- Overnight-risk caveats: global markets turning, domestic news.
- Trade rules: don't risk capital (use 10% of day's profit); trail once price > buy price; **SL = 50% of premium** (or risk appetite, whichever lower).
- Signal output at 15:20 (type + strike + level), Call/Put Signal Entry tables, a Candles+OI result chart with Entry/Out/Stop-Loss/Opening-Performance markers. Worked: 37100 PE @355 → next-day first-candle high 594.

### A5. `advance-chart/ospl-signal.md` — OSPL Signal (NEW)
Source PDF p266–p273. Already flagged unobserved in `NOT-CAPTURED.md` item 4 — the manual fills the whole gap. Proprietary AI signal added on the Advance Chart (Indicators → search "ospl"; "OSPL Signal" and "OSPL Volume" are two separate studies; Annual-plan gated).
- Inputs: Price, Volume, OI, VIX, Global markets (+ SuperTrend).
- Display: green up-arrow (look long) / red down-arrow (look short) at the signal candle, plus **"In:" / "Out:"** price labels. **In = conditions triggered (start considering entry); Out = momentum ended (stop taking new trades).** Explicitly: *In/Out are condition flags, not literal entry/exit points*; direction is "likely," never certain.
- Playbook: green→buy CE / red→buy PE; enter after the signal candle closes; phased averaging; ~1%-of-capital target; trail; **place SL near the "Out" level**; be aggressive on the first (highest-probability) signal and reduce size on later ones.
- Audio Alert toggle fires a sound when OSPL Signal triggers (this is what the Advance-Chart "Audio Alerts" button actually controls — see §D).

> Note: the averaging/Martingale and 1%-target guidance is OI Pulse's own risk method — record it as *their* recommendation, not an ArthaYantra endorsement.

---

## B. Interpretation / methodology to add to existing feature docs

### B-futures

**`futures/oi-analysis.md`**
- 4-state *meaning*: Build-Ups = fresh positions (strong); Covering/Unwinding = closing positions (weak). (p027–p030)
- **Signal-strength grading** (STRONGEST/STRONG/WEAK) from {state × Volume H/L × Level-Break × significant ΔLTP/ΔOI}, with the asymmetry that **bullish states pair with D.H.B (Day-High-Break), bearish with D.L.B (Day-Low-Break)**. Add as a derived concept/optional column. (p033)
- **Context flips meaning:** read Short Covering / Long Unwinding relative to the day's bullish/bearish scenario. (p033)
- **OI vs Volume fundamentals:** OI = distinct outstanding contracts, Volume = times traded; **Volume ≥ OI always**; OI rises on fresh writing, falls on buy-back. (p026–p027)
- **Volume = conviction:** high-volume regimes strengthen the signal; high-volume asymmetry (longs dominate → Long Build-Up/Short Covering; sellers dominate → Short Build-Up/Long Unwinding). (p035)
- **Interval methodology:** longer interval = trend context, shorter = entry timing; 60-min unreliable in the first half. (p011, p043, p046)
- Mode purpose: Live = real-time from NSE; Historical = back-analysis. (p010)
- Rows-per-page options 25/50/75/All. (p012)
- **15:30-EOD concept** → see §C (NSE post-close *adjusted* OI). (p083–p087)
- Historical look-back ≈ 2 months (platform-wide). (p088, p095)
- Table↔chart cross-check workflow: scan OI-interpretation per interval, weight by Volume + Level-Break, confirm on the OI-vs-price chart. (p028–p034)

**`futures/oi-chart.md`**
- Line/bar render toggle is a first-class control; bar form colours OI by up/down. (p091)
- Hover tooltip surfaces the OI-interpretation label per point (maps to `toolTipData`). (p091–p092)
- The dual-axis OI-vs-price chart is the per-strike **decision** tool; axis scaling is intentional (don't naively auto-scale both axes). 6-item ECharts toolbox with **Line as default**. (p014)

**`futures/oi-spurt.md`**
- Per-quadrant trading read: Short Build-Up ⇒ likely **resistance zone**; Short Covering ⇒ limited/weak rally; Long Build-Up ⇒ fresh longs/bullish; Long Unwinding ⇒ profit-booking, "price↓ + OI↓ ⇒ book profits." (p089–p090)
- Historical mode = previous 2 months. (p088)

**`futures/eod-oi-analyzer.md`**
- **"Show Detail View"** button → ~2-month detailed rows (Date / Total OI / Day O-H-L / LTP / LTP-chg / OI-chg / OI% / OI-Interpretation). **[VERIFY]** vs current checkboxes. (p094–p095)
- Chart price series = **adjusted close**; intraday Day-High/Low live only in the detail view. (p095)
- Line/bar toggle on the EOD chart. (p094)
- Indices expose Current / Next / Far month inline in the Name dropdown. (p094)
- Expiry-day tactic: read ~10 days of Future OI to decode big-player activity. (p035)

**`futures/oi-buzz.md`**
- Purpose = OI-change-relative-to-price per constituent (the tile's OI-interpretation is the headline, not just a tooltip); works for Nifty and Bank Nifty universes. (p117)
- Tooltip's exact 6 fields: OI-interpretation, Change %, Open, High, Low, LTP. (p118)
- Export: **Download SVG / PNG / CSV**; CSV = `category | %Change`, sorted descending. (p118–p119)
- EOD/next-day interpretive use (read the day that went by → next-day implication). (p119)

**`futures/banks-analysis.md`**
- 6 banks = the **6 highest-contribution Bank Nifty constituents**. (p120)
- **Consensus vs divergence read:** all/most banks one direction ⇒ Bank Nifty may move strongly; divergence ⇒ consolidation / irrational one-candle moves. (p122)
- Cell baseline **[VERIFY]** — see §F. (p120, p122)

**`futures/pre-open-market.md`** (+ `equity/pre-open-market.md`)
- Trading read: advances/declines skew → day bias; **Prev-Day-Break** badges → continuation candidates; require sector-index agreement. (p275–p277)
- Threshold heuristic: already down ~5% = **"too high"** (avoid); down ~1–3% = **"good opportunity"** (≈1% intraday target). (p277)
- Historical pre-open is an **exchange-unavailable** differentiator ⇒ ArthaYantra must persist daily pre-open snapshots. (p275)
- Timing: exchange publishes pre-open at ~09:08 → market opens 09:15. (p274)
- Manual IA is one combined "Pre open market" page + tab toggle + "Global Actions" **[VERIFY]** vs our split routes. (p274–p275)

### B-options

**`options/oi-analysis.md`**
- Options OI = the same futures OI-interpretation primitive applied to **CE and PE mirrored**. (p013)
- **Read both legs at one strike:** pick ATM + high-OI strikes; read CE and PE interpretation together; opposite-side agreement (e.g. CE Short-Build-Up + PE Long-Build-Up) = high-confidence direction; gate on strength (ΔOI/ΔLTP) and confirm with Volume + D.H.B/D.L.B. (p042–p043)
- Timeframe roles (60/15/5) as in A1. (p043, p046)

**`options/oi-chart.md`**
- **C4 OI/LTP "X-crossover":** a steep-angle X between a strike's OI line and its premium line marks momentum — price↓/OI↑ = **Strong Short Build-Up**, price↑/OI↓ = **Strong Short Covering**; the opposite-type option shows the mirror; short-lived; **best on 15-min**. Derivable as an overlay/marker on the existing dual-axis chart. (p046–p048)

**`options/oi-statistics.md`**
- **C1 support/resistance/range:** max Put-OI strike = support, max Call-OI strike = resistance, band between = probable day range; on the Individual-OI chart, green bars right-of-ATM = resistance walls, red bars left-of-ATM = support walls; a breach of a high-OI wall → short covering → buy-the-option moment. (p035–p036)
- Cumulative-OI bar = **writer-dominance** gauge; add an ATM-specific read (which side dominates at-the-money). (p015–p016)
- ATM marked with a **double-arrow** **[VERIFY]** vs our recorded single ▲; legend-click to isolate one side. (p015–p017)
- **Select Period** purpose: restrict OI/ΔOI bars to a trailing 15/30/45/60-min window to locate where option writing is concentrated *now*; pairs with "Show Chg. in OI"; the bridge feature for confirming a Trending-OI sentiment. (p124, p126, p138)

**`options/oi-spurt.md`**
- **C2 four-quadrant model + STRENGTH filter:** a strike "appearing" in a quadrant is not a signal until **%ΔLTP > 50% AND %ΔOI > 50%**; Calls and Puts in the same quadrant imply opposite direction; per-quadrant action — **Q1** (OI↑/price↑, Long Build-Up) buyer focus; **Q2** (OI↑/price↓, Short Build-Up) writer focus; **Q3** (OI↓/price↑, Short Covering) buyer but short-lived; **Q4** (OI↓/price↓, Long Unwinding) avoid (retail); read all four together. (p037–p039)
- `%Chng in LTP` and `%Chng in OI` are the load-bearing decision columns. (p037, p040)

**`options/options-chain.md`**
- **Cell-highlighting rule:** highlight the cell holding **Max OI, Max OI Change, Min OI Change, Max Volume** on *each* of the Call and Put sides (8 cells), distinct from the proportional data-bars. Our max-reference set already has 5 of 6 extrema — add **max Volume per side**; capture the highlight colour from live **[VERIFY]**. (p095–p096)
- Stated advantages vs NSE: per-minute OI, live PCR, historical chain, **IV on weekly-expiry days** **[VERIFY]**. (p018)

**`options/options-premium.md`**
- Premium = **extrinsic value**: `LTP = intrinsic + extrinsic`; OTM = pure extrinsic; the chart plots extrinsic (premium/discount), not raw LTP **[VERIFY the bar's basis vs our capture]**. (p018–p019, p049–p051)
- **C5 strike selection:** avoid OTM, prefer ATM/ITM, hunt the relatively-cheap ITM strike (higher leverage). Worked intrinsic/extrinsic cheapness example; "Show LTP" overlays each strike's LTP for the comparison. (p019, p049)
- Extrinsic ≈ market-priced probability (the "Risk Value") — the conceptual basis for reading the bars. (p049–p051)

**`options/active-strikes-oi.md`**
- Active strike = strike with greatest **Volume / ΔOI** (auto/AI pick), not simply ATM. (p098–p099)
- **Active Strike Sentiment %:** **> 0 = bullish, < 0 = bearish**; rising-while-positive = strengthening bullishness; value is an **unbounded %** (manual shows up to ~4000%), so the y-axis is not a fixed small band. (p101–p104)
- Sentiment is a **confirmation** tool — time entries off the price chart, not the sentiment spike. (p104)
- Line/bar toggle on both charts. (p098)
- Manual-era scope = Nifty/BankNifty present-month intraday **[VERIFY]**; Call/Put line colours **[VERIFY]** (Put=red stable; Call may differ green↔blue). (p097–p099)

**`options/active-strikes-iv.md`**
- IV framing: IV reflects **seller-perceived risk**; HIGH IV = sellers see more risk / buyers more demand, LOW IV = less of both. (p051)
- **IV bands** (≈10–15 & 15–18 = trend/buy; 18–20 = premium erosion; ~20+ = sell-side; 40+ = very volatile, book fast) + strike-distance rule (low IV → ATM/near; high IV → far OTM expensive). **[VERIFY exact numerals]**. (p051–p052)
- **"Magic of IV" — the CE–PE IV-spread rule:** when `|PutIV − CallIV| ≈ 10`, buy the **higher-IV side** in the trade direction (faster premium appreciation); a tight spread = both sides erode (sell-side regime). Compute `PutIV − CallIV` as a tradable signal. (p065, p072)

**`options/trending-oi.md`** (and reference from `trending-oi-pa.md`)
- **Meaning of "trending":** the widening gap between ΔCall-OI and ΔPut-OI; **use Change-in-OI, not Total OI, intraday**. (p111, p128–p129)
- **5-level sentiment truth table** from {Diff sign × ΔCall (vs prev interval) × ΔPut}: Extreme Bullish / Bullish / Neutral / Bearish / Extreme Bearish (our doc has only binary Bullish/Bearish and no Neutral). (p116)
- **Strength ladder Simple → Moderate → Extreme** keyed to the remark column: no remark = Simple; a Day-Break = Moderate; a Day-Break **plus opposite-side OI reduction** (+ continuous breaks) = Extreme. Refinement: Call-OI still rising alongside Put writing = only Moderate bullish; Call-OI falling (unwinding) = Strong. (p141, p146, p147)
- Call/Put OI columns = cumulative Δ vs **prior-day EOD** baseline; table reads **bottom-to-top** (newest on top). (p128–p129)
- **Difference in OI = ΔPut-OI − ΔCall-OI** (positive = bullish) — **[VERIFY]** sign vs our doc's "Call − Put". (p109, p129)
- **Day High/Low Break** exact trigger: Diff is positive(neg), greater(less) than the prior interval, AND a new day extreme of the Difference-in-OI (green/red badge); seeds after ~09:16. (p109–p110, p130–p131)
- Strike-picker modal: "Total Strike Prices: 15", **Clear-all / Reset** buttons, unbounded multi-select. (p107–p108)
- Legacy control naming: **"Show detail view"** is the inverse of our "Show Graph View" (detail-on = table; off = graph). (p108)
- Graph view = two line panels (ΔCall-OI and ΔPut-OI over time). (p111)
- **Trading playbook:** focus OTM CE/PE strikes; long needs price > VWAP, volume > ~50K, RSI rising > 60; **veto if RSI > 80 or major resistance near**; bearish mirror (price < VWAP, RSI < 40); "Buy on Dips / Sell on Rise"; don't trade Trending-OI alone. **[VERIFY numerals]**. (p111–p115, p133, p136, p146)

**`options/big-oi-movement.md`**
- Row-read recipe: ΔOI × ΔLTP → {OI↑/price↑ Long Build-Up; OI↑/price↓ Short Build-Up; OI↓/price↑ Short Covering; OI↓/price↓ Long Unwinding}. (p193)
- **Trading methodology:** Big-OI is a **confirmation / position-sizing gate**, not a standalone entry. Big-OI confirms ⇒ size up (aggressive); doesn't confirm or **empty table** ⇒ size down (cautious). Bull/bear-trap read ("two candles that remove weak hands"). Confluence recipe: 2 consecutive candles + volume > ~50K + SuperTrend + RSI, then confirm via Big-OI. Four worked scenarios (Aggressively/Cautiously Bullish/Short). (p193–p202)
- Column labels (`Chg in LTP`, `Chg in OI`), the 4 interpretation badges + colours (Long Build-Up green, Short Covering blue, Long Unwinding grey, Short Build-Up red), Moneyness badges (ATM yellow / ITM green / OTM orange). (p193, p196, p199)
- Purpose = position-sizing aid; "big" = AI/server significance filter; manual-era index-only. (p191–p192)

**`options/multiple-oi-chart.md`**
- Legend-deselect + historical-date support confirmed; overlay freely mixes CE and PE. Older manual lacks the underlying-price overlay our study captured **[VERIFY]** (expected version difference — do not downgrade). (p020)

### B-fii-dii

**`fii-dii/capital-market.md`**
- Read: green bar = institutions invested (bullish), red = redemption (bearish); judge **collective action over multiple days**, not one bar. (p156–p157)
- Framing: FII/DII activity **affects but does not determine** the next session; never use alone. (p155)
- **"In Market" column meaning [VERIFY]** — see §F. (p157–p159)

**`fii-dii/fii-derivative-stats.md`**
- Exact composition of the four segments: **Index Futures** = Nifty/BankNifty/FinNifty (near+next+far); **Index Options** = CE+PE combined for those; **Stock Futures** = all F&O stocks, all months; **Stock Options** = CE+PE for all F&O stocks. (p157)
- Hover tooltip layout (`Date | Idx Fut | Idx Opt | Stk Fut | Stk Opt`, ₹Cr); a large **positive Index-Options** figure usually = Put-buying/hedging (bearish), not "long" — read options sign carefully. (p158)
- Combine cash + 4 derivative segments → next-**morning** bias (worked example → next-day gap-down). (p158–p159)
- Conflict rule: when FII and DII disagree, **weight FII**; treat futures-buy/cash-sell divergence cautiously. (p181)

**`fii-dii/participant-wise-oi.md`**
- **Net OI = Long − Short per participant**; total longs always = total shorts; read who is net-long vs net-short. (p204–p207)
- **Smart-money vs retail:** FII/DII/Pro = smart money, Client = retail; align with institutions, fade clients; importance order **FII > Pro > DII > Client**; valid mainly next-morning, can flip on global cues. (p205–p208)
- **Four ΔLong/ΔShort regimes:** Aggressively Bullish (Long↑ & Short↓), Cautiously Bullish, Aggressively Bearish (Short↑ & Long↓), Cautiously Bearish — mapped to the same build-up/unwind primitives. (p209–p210)
- Column glossary (Long/Short = absolute open for the day; Change = day-over-day) + the 6 instrument types. (p211)
- **FII Index-Future Long%/Short% ratio = the headline bias**; writer rules (more Put selling = bullish, more Call selling = bearish); tally the per-segment Bullish/Bearish (majority wins). (p212–p215)

### B-strategies

**`strategies/straddle-chart.md` + `strategies/strangle-chart.md`**
- Strategy definitions: **Straddle** = CE+PE at the **same ATM** strike; **Strangle** = CE+PE at **different OTM** strikes; objective = harvest premium decay. (p216–p217)
- **When to use:** high IV on both sides + **flat / no-trend OI** ⇒ sell premium (Straddle when directionless; Strangle when OI not building on either side + IV high). (p217)
- **Pre-trade qualification:** confirm a non-trending day — Trending-OI flat + Active-IV high — before deploying. (p219–p220)
- Chart read: yellow line = combined CE+PE premium (the tradable series), blue line = its VWAP; **premium reverting to VWAP = entry/scale-in trigger**; select OTM strikes outside the established day high/low; worked premium decay 960→682. (p220–p221)
- Break-even/payoff is a Strategy-Builder concern — cross-link, don't fabricate formulas. (p216–p221)

**`strategies/strategy-builder.md`**
- **"Strategy Market view" enum:** Not Known / Bullish / Bearish / Range Bound / Big move either side. (p305–p315)
- Saved-strategy table columns (incl. **RR**); Simulator controls (Hour/Min/Time, **Min. Gap, Start/Stop Autoplay, Reset Time**); position-edit modal fields (Order Type, Entry/Exit Price, No. of Lots, "Is position exited?"). Partly fills `NOT-CAPTURED.md` item 4. (p305–p315)

**`strategies/oi-expiry-strategy.md`**
- Manual places it under the **Options** menu **[VERIFY]** (study says Strategies); manual-era data = **last-5-day** per-strike OI+premium with **AI-highlighted** important Calls/Puts and a multiples-of-500 strike selector **[VERIFY 5-day vs full-cycle]**. (p159, p166)

**`strategies/calendar-spread.md`**
- Chart overlay = **VWAP + 20-EMA + volume candles**; tooltip gives O/H/L/C + VWAP + EMA **[VERIFY study mentions the 20-EMA + VWAP overlay]**. (p313–p315)

**`strategies/open-high-strategy.md` + `equity/open-high-low.md`** — the methodology (big addition; consider a `strategies/open-high-concept.md`)
- **Definition:** Open == High (or Open == Low) at the open, **strict equality, no tolerance**; Day-High == Day-Open at formation. (p225–p226, p251)
- **Polarity nuance:** the option-premium trade is **contrarian** to the usual cash "O=H ⇒ bearish" intuition — OH on a Call (OL on the Put) is read bullish for that Call (premium expected to revisit the high). (p226, p234, p258)
- **Why OH forms:** institutional aggressive pre-open bidding sweeps the book so the open prints at the top → reverts. (p226–p230)
- **Table 1 (confluence):** Futures OH + Call OH + Put OL = **High** probability; option-only = **Mild**; bearish twin mirrors. (p233–p234)
- **Table 2 (confirmation):** wait for follow-up candles vs a **50K-contract** volume line — Call OH: pullback on <50K → probability up, on >50K → down. (p238, p244)
- Extra filters: global markets and India VIX must not move against the position. (p238)
- **Entry = momentum scalp** (price returning toward the OH level with volume > 50K), **not** a level-cross; scalp, not positional; pyramid modestly. (p239–p240, p243)
- **Strike selection:** ATM ±3–4 strikes only, premium ≈ ₹200–300; ignore deep ITM/OTM even if they show OH. (p240–p241)
- **Validity ≤ ~11:00 AM** (odds decay exponentially after). (p240)
- **Position sizing** by confidence; OH on both CE and PE → reduce size. (p239, p241, p243)
- **Exit:** trail; don't wait for price to cross the OH level. (p240)
- **LTP-distance gate:** the smaller the gap of LTP below the OH level, the higher the reversion odds (this is the interpretation behind our existing "Far from High?" %). (p231–p232, p256)
- **Probability column = AI output**, with operational rule **trade only when Probability > 90%** (a *prepare* signal, not an entry); plus a separate **"Red Dot"** AI trigger on the O=H badge **[VERIFY tiers vs live]**. (p251–p252)
- Triggered Time = the time the OH level was **breached**; "Hit ✓" badge marks the breach; only OH strikes are listed (missing strike = no OH). (p250–p251)
- Menu placement under **Options [VERIFY]** (study says Strategies); intended to be **watched live** through the open. (p249–p250)
- **Failure modes / limitations:** reversion fails when a bigger player enters later (heavy-volume fall); OH **cannot predict the day's direction** — one scalp, not a trend tool. Provenance: Mr. Sivakumar Jayachandran. (p254–p258)

### B-features / shell

**`features/connecting-dots.md`**
- The **"connect the dots" framework:** six inputs — Global Markets, Futures OI, Options OI, India VIX, Implied Volatility, Price Action. (p022)
- Global-input priority: **Dow Jones → Crude → USD-INR → SGX/GIFT Nifty → Europe**; USD-INR is **inverse**; SGX/GIFT Nifty leads the pre-open; **discount globals on big domestic-news days**. (p022–p024)
- Naming origin: the product thesis is to "connect all the dots." (p006)

**`features/world-indices.md`**
- IST exchange open/close-time reference table (Japan/Australia/Korea/HK/Singapore/China/Germany/UK/US/Brazil/Canada). (p022–p024)
- Directional relationships: Dow direct (Bank Nifty ↔ Dow), USD-INR inverse, Crude influence. (p022–p024)

**`features/vix-index.md`**
- **VIX definition:** "fear index" = collective perceived risk (vs IV = *seller* risk); computed from **Nifty OTM call/put premiums** (variance formula); rising VIX = bearish, falling = bullish; CE-side proportional / PE-side inverse to price. (p020, p052–p053)
- Signals: steep **VIX–Price "X/V-shape" crossover = momentum-build-up trigger**; ignore VIX when erratic; computed from Nifty options but applies to Bank Nifty. (p053–p055)

**`features/dashboard.md`**
- Default-symbol rationale: **Dow = "mother of all markets"**, Crude + USD-INR are Bank Nifty drivers. (p009–p010)

**`features/multiple-window.md`**
- Manual-era canonical layout = **4 panes** (2×2); per-pane widget picker enumerates 7 widgets (Futures OI Analysis, Options OI Analysis/Chart/Statistics/Spurt, Options Chain, Options Premium); each pane is fully **independent** (own Mode/Name/Date/Interval/Strike, no cross-sync); rationale = single-screen fast decision. (p074–p081)

**`00-global-shell.md`**
- Account-menu items (Profile settings / Plan billings / Contact Us / Sign out), a full-screen toggle, and a sidebar-minimise control (likely still present — verify). (p009)

**`README.md`**
- Add the **"Morning Trade"** and **"3:20 Strategy"** routes to the menu map.
- Add a short "data cadence / OI philosophy" note (per-minute OI; OI = dominant-participant footprint). (p006–p007)

---

## C. Concept definitions & data semantics to add

These are facts our UI/API capture could not reveal — worth capturing precisely.

- **Per-minute OI vs NSE's 3-minute lag** — NSE publishes OI with a ~3-min delay; OI Pulse serves OI "every minute" on the options chain. This is the product's headline differentiator and validates our forward-capture cadence goal. → `README.md`, `options/options-chain.md`, `options/oi-analysis.md`. (p007, p018)
- **15:30-EOD = NSE's post-close *adjusted* OI**, distinct from the live 15:15–15:30 close; the gap is much larger for single-stock futures; caused by Clearing-Member reconciliation; **readjustment only ever decreases OI**, so the EOD OI-interpretation can flip vs intraday (e.g. Long Build-Up → Long Liquidation). → `futures/oi-analysis.md`, `futures/eod-oi-analyzer.md`. (p083–p087)
- **OI vs Volume:** create-vs-transfer mechanic; **Volume ≥ OI** invariant. → `futures/oi-analysis.md`. (p026–p027)
- **Option premium = intrinsic + extrinsic**; OTM = pure extrinsic; the premium chart plots extrinsic (premium/discount). → `options/options-premium.md`. (p018–p019, p049–p051)
- **VIX is computed from Nifty OTM option premiums** (a fear/volatility gauge), not a standalone line. → `features/vix-index.md`. (p052–p053)
- **Active Strike Sentiment %** sign convention (>0 bullish / <0 bearish, unbounded). → `options/active-strikes-oi.md`. (p101–p104)
- **Historical retention ≈ 2 months** across OI Spurt / EOD Analyzer / Trending OI; historical **pre-open** is stored despite the exchange not providing it (we must persist daily snapshots). → respective docs. (p088, p095, p275)

---

## D. Discrete feature / column / control details to add

- **"Pattern" column** in the Futures OI Analysis table (between Level Break and Volume) **[VERIFY vs live]**. → `futures/oi-analysis.md`. (p011–p012)
- **ECharts toolbox = 6 actions** (zoom, restore-zoom, line [default], bar, restore-chart, save-image). → chart docs. (p014)
- **Line/bar render toggle** is a documented first-class control on OI Chart, EOD chart, and both Active-Strike charts. (p091, p094, p098)
- **Options-chain cell-highlighting** (Max OI / Max OI-Change / Min OI-Change / Max Volume per side); add **max Volume** to our max-reference set. → `options/options-chain.md`. (p095–p096)
- **Select Period** dropdown (Full day / Last 15/30/45/60 min) + its purpose (windowed Δ-OI). → `options/oi-statistics.md`. (p124–p126)
- **OI Buzz export** (SVG/PNG/CSV). → `futures/oi-buzz.md`. (p118–p119)
- **Trending-OI strike modal** (Clear-all / Reset / "Total: 15" / unbounded). → `options/trending-oi.md`. (p107–p108)
- **Audio Alerts** button on the Advance Chart is the **OSPL-Signal** sound alert (Yes/No enable dialog), not a generic price alert — fix the scope note. → `advance-chart/advance-chart.md`. (p271–p272)
- **Advance Chart:** Open Interest as a named live sub-pane study; **OSPL Volume dark-candle threshold** (>50K BankNifty / >125K Nifty); **multi-template save** (Remember Symbol / Remember Interval; "MY TEMPLATES" switcher; `INTRADAY_SCALPING` = VWAP+SuperTrend+VWMA+OSPL-Volume+RSI+OI as a suggested default); unlimited indicator count; weekly→daily→3-min top-down workflow with a Double-SMA(100,200) daily preset. → `advance-chart/advance-chart.md`, `advance-chart/multiframe-chart.md`. (p260–p266)
- Account-menu / full-screen / sidebar-minimise. → `00-global-shell.md`. (p009)
- Rows-per-page {25,50,75,All}. → `futures/oi-analysis.md`. (p012)

---

## E. Cross-cutting theme — "Connect the dots" confluence

The manual's actual trading method is **multi-signal confluence**: a trade is taken only when the *majority* of independent "dots" align (OI analysis + OI chart crossover + Call-vs-Put OI + OI Spurt quadrants + VIX crossover + global/Dow + IV spread + price-action volume); dissenting dots are *discounted* on the day. Trading is framed as **probability — "no holy grail"** — judged over a series of trades, aligning with dominant participants.

- **Price-action volume gate:** the final confirmation is **two consecutive same-colour volume candles ≥ ~50K on 1-/3-min futures** before entry **[VERIFY 50K]**.
- The end-to-end worked examples (PDF p057–p072, and the expiry/morning/3:20 plans) are the clearest template of this confluence and would make strong "worked example" appendices to A1–A4.

Worth a short "how this feature combines with the others" cross-link in each major page, and capturing the confluence rule once in A1 (or `connecting-dots.md`).

---

## F. VERIFY flags — reconcile against live capture / Phase-B

Where the older V10 manual may differ from the current live site; check before adopting.

| # | Item | Manual (V10) | Study / live | Where |
|---|---|---|---|---|
| 1 | Banks-Analysis cell baseline | LTP% vs prev-day **adjusted close**, OI% vs **prev-day OI**, interpretation vs prev interval (daily metric + interval badge mixed) | study says "cumulative-from-day-open" | `futures/banks-analysis.md` |
| 2 | Trending-OI "Difference in OI" sign | **ΔPut − ΔCall** (positive = bullish) | study formula reads "Call − Put" | `options/trending-oi.md` |
| 3 | FII/DII "In Market" column | read as a cash-market net per row | study assumes "FII Net + DII Net" | `fii-dii/capital-market.md` |
| 4 | Active-Strikes-OI Call line colour | Call ≈ blue/dark (Put = red) | study says green = Call | `options/active-strikes-oi.md` |
| 5 | Open&High menu + probability | under **Options** menu; AI % with >90% gate + Red-Dot | study: "Strategies" menu; discrete 60/80/90/95 | `strategies/open-high-strategy.md` |
| 6 | Futures "Pattern" column | present between Level Break and Volume | not in study column list | `futures/oi-analysis.md` |
| 7 | IV bands + 50K volume numerals | band upper-bounds + 50K figure from dense low-res pages | n/a (new) | `options/active-strikes-iv.md`, price-action |
| 8 | EOD Analyzer "Show Detail View" | a button → ~2-month detail | study has 3 checkboxes | `futures/eod-oi-analyzer.md` |
| 9 | Options-premium bar basis | extrinsic value (premium/discount) | study implies raw LTP | `options/options-premium.md` |
| 10 | Options-chain IV on weekly-expiry days | provided | confirm vs capture | `options/options-chain.md` |
| 11 | OI-Statistics ATM marker | double-arrow | study recorded single ▲ | `options/oi-statistics.md` |
| 12 | oi-expiry-strategy data window | last-5-day + AI highlight; Options menu | study: ~31-session full cycle; Strategies menu | `strategies/oi-expiry-strategy.md` |
| 13 | Multiple-OI-chart underlying overlay | absent (older) | study shows dual-axis w/ price | `options/multiple-oi-chart.md` |
| 14 | Pre-open routing | one combined page + tab | study: split futures/equity routes | `*/pre-open-market.md` |
| 15 | Calendar-spread overlays | VWAP + 20-EMA + volume | confirm study mentions 20-EMA | `strategies/calendar-spread.md` |

---

## Appendix — manual structure map (PDF page ranges)

| Section | PDF pages | Content |
|---|---|---|
| Front matter / Intro | 1–7 | Cover, Contents, OI philosophy, per-minute-OI claim |
| Section A | 8–21 | Feature/menu tour: Login, Home, Dashboard, Futures, Options E1–E7, VIX & Index |
| Section B | 22–73 | **Interpretation core:** connecting dots, Global markets, Future OI states + strength, Options OI (C1 range, C2 quadrants, C3 timeframes, C4 crossover, C5 strike selection), IV, VIX, Price Action, worked Examples, Magic of IV |
| Addendum 1 | 74–81 | Multiple Window |
| Addendum 2 | 82–105 | OI Analysis/15:30-EOD, OI Spurt, OI Chart, EOD OI Analyzer, Options-Chain cell-highlighting, Active Strikes OI |
| Addendum 3 | 106–122 | Trending OI, OI Buzz, Banks Analysis |
| Addendum 4 | 123–152 | Select Period, Trending OI (deep) |
| Addendum 5 | 153–190 | FII/DII Activity, **Expiry-Day Strategy + Trading Plan** |
| Addendum 6 | 191–223 | Big OI Movement, Participant-wise OI, Straddle & Strangle |
| Addendum 7 | 224–258 | **Open & High** strategy + screener |
| Addendum 8 | 259–317 | Advance Chart, **OSPL Signal**, Pre-open Market, **Morning Trade**, **3:20 Strategy**, + revisited UI features (Event Days, Announcement, Delivery Data, Strategy Builder, Calendar Spread) |

---

*Generated from a full read of the V10 manual cross-referenced against the live-capture study. All items are additive; none reduces existing study coverage. Phase-B (live Socket.IO capture) remains the other open audit task and is the natural place to resolve several §F verify-flags.*
