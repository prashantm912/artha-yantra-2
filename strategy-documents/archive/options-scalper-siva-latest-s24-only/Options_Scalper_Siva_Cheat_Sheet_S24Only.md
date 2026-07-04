# Options Scalper Siva — Cheat Sheet (Session-24-Only Edition)

> **⚠ ARCHIVED (2026-07-04) — superseded + orphaned.** Superseded by the operative doc
> (`../../options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md`). Retained for
> provenance only.

> **What this is:** A ready-reference execution checklist for the **12-strategy** framework **as taught/decoded in Session 24 only** ("Big 5 Anniversary — Live Decoding 21 Days", 2025). Built strictly from the Session-24 sources (21 daily live-decoding transcripts + the strategy decks physically in the S24 folder + all 328 rendered page images); earlier sessions (S20–S23) are intentionally excluded. **Session 24 adds no new strategy** — it confirms the framework and sharpens **Open=High** into a fully-chasable system. Pure algorithm — no terminology, theory, or rationale.
>
> **Read [`Options_Scalper_Siva_Consolidated_Strategy_S24Only.md`](Options_Scalper_Siva_Consolidated_Strategy_S24Only.md) first** — this assumes you already know every term, indicator, and OI/IV/VIX concept used below. Day-citations (e.g. Day 14) are the S24 provenance.
>
> **How to use:** Run **§0 Pre-Flight** on every trade, then jump to the strategy. Each strategy lists only what is *specific or overriding* relative to §0.
>
> **Abbreviations:** BN = Bank Nifty · N = Nifty · Fut = Futures · TF = timeframe · LB/SC/SB/LU = Long Build-up / Short Covering / Short Build-up / Long Unwinding · ST = Super Trend (10,2) · VWMA = VWMA(20) · PSAR = Parabolic SAR (0.02, 0.02, 0.2) · OIP = OI Pulse · OSPL = OI Pulse AI signal · O=H / O=L = Open=High / Open=Low · R/S = Resistance/Support · prev = previous.

---

## 0. Pre-Flight (apply to EVERY trade)

### 0A. Risk rails (non-negotiable)

- **Daily target ~1%/day, then stop and log out** (₹25 L account → ~₹25,000/day; ₹5 L → ~₹5,000/day). Target scales with capital: ~**0.5–1%/day at ₹5–10 L**, ~**2%/week at ₹2–5 Cr**, with occasional 3–4% days (Days 1, 2, 3, 8, 16, 21).
- **Recycle realized profit** as the next trade's risk budget — never enlarge risk to chase (Days 10, 16, 21).
- **Deployment caps:** keep only **~5–10% of total finances in the market**; deploy **≤~15–20% of capital at once**, with **risk ≤~10% of the deployed amount**. Never take loans; be wary of MTF; diversify; **split capital across accounts** to lock psychological profit and cap blow-ups (Days 1, 8, 9, 12, 16, 17).
- **Loss caps:** single-day **≤10–12% of capital** (~₹3 L on a ₹25 L account is the recoverable bound — avoid 40–50%). Per-trade conservative guideline **~1–2% of total capital** (Day 4); outer hard cap **≤10%** (≈10% of the *deployed* amount / single-trade max) (Days 4, 8, 20, 21).
- **Deep SLs are mandatory** — a tight 5–10-pt SL gets hunted: **Nifty ~50–60 pts, Sensex ~200–250 pts, Bank Nifty ~100 pts** (Sensex initial ~200, revise as it moves in favour) (Days 1, 15, 19).
- **Always scale in; never go all-in.** **Pyramid in geometric lots (1 / 2 / 4 / 8, up to 16)** toward the strongest support (usually **VWAP**) so the heaviest size sits nearest the SL; may add on the upside as a base forms. Concentrate maximum averaging at the area of maximum S/R; increase qty **gradually** (don't jump 200 → 1,000). Day-20 frames this as **planned scaling, not averaging** (e.g. a 20-pt window, 2+1+2 lots, max 5). Once in profit, **hold qty constant and trail tighter** (Days 5, 8, 13, 16, 19, 20, 21).
- **Never average below VWAP** — once a fall breaks VWAP, accept failure and exit; averaging below it wipes 70–80% of capital (Day 21).
- **Sizing confidence follows the Trending-OI gap:** wide gap = full size, narrow = reduced. A fall **without volume** = low concern; a fall **with volume** (first >50–60K drop) = prepare to exit at the final SL (Day 5).
- **Volatility sizing:** low VIX/IV → **large qty, small target (~4 pts)** (many small scalps beat chasing points); high VIX/IV → **small qty (~100), larger target (100–200 pts), or stay out** (Days 3, 9).
- **Behavioural guardrails:** book partials and trail; **never revenge-trade**; **never trade against the trend**; **never contra-trade in options** (opposite-side premiums melt). **Overtrading is the killer** — afternoon size-creep; book the morning profit and stop. Newbies: **1 lot, RR 1:1 / 1:2**, scale up over 6 mo–1 yr. Use **3-min candles; avoid 1-min** (Days 1, 2, 3, 8, 13, 14, 16).
- **Demo benchmark (S24):** ₹24.97 L → ~₹28.63 L over 21 days (~₹3.8 L net), ~80–90% trades live (Day 21).

### 0B. Universal intraday gates (assumed by every intraday strategy unless overridden)

| Gate | Rule |
|------|------|
| **Time** | Intraday window **9:45–2:30** (post-2:30 = next-day positioning). Trending day = a fresh high ~every hour; profit-book after ~1:30; stay away after ~2:30. Expiry-eve erosion can run till 2:00–2:30+ (Days 4, 5, 17, 18). |
| **Volume candle** | **≥50K (BN) / 125K (N).** Volume confirmation is **mandatory for any break** — a VWAP/level break *without* volume can reverse in one candle and trap you (points to the next support, not a real breakdown) (Days 11, 12, 15, 18, 21). |
| **RSI(14) 3m, 80:20** | **40–50 = NO TRADE.** Buy **50–75** (profit-book 75–80/85; no fresh longs >80–85). Sell **40–25** (profit-book 25–20; avoid <20). **Never short into overbought** (Day 4). |
| **Indicator alignment** | All indicators **below** price = bull; **above** = bear. Defence ladder on a fall: **VWMA = 1st line, ST = 2nd, VWAP = final** (VWAP is the biggest support after OI). When VWAP+ST+PSAR cluster, bulls can clear all three in one big-volume candle — not strong resistance (Days 4, 6, 13). |
| **Strike / delta** | Buyers use **ATM or 1–2 ITM**, avoid deep-OTM. **Buyer delta ≥0.7** (~85 paise/pt at 0.7–0.8); near expiry delta **~0.8, premium 300–500**; early series 0.5–0.6; high VIX (20–25) → ATM delta 0.2–0.3 (go 1–2 OTM only for a large expected move). Prefer the side with **fewer writers**; **avoid ₹120–130 premiums, prefer 150+**; avoid OTM for momentum plays (Days 2, 3, 5, 15, 20, 21). |
| **OI (4 quadrants)** | Fut: **LB/SC = long, SB/LU = short.** Only the seller (writer) creates a position. A **≥50% premium move with OI dropping** = the controlling side. **Sellers adding both sides = pin/erosion → avoid.** Classify a strike by **where it closed in its range**, not just OI change. Decode from ATM using 5–6 strikes each side; plot OI bars on **Nifty SPOT** (settlement reference) (Days 4, 9, 10, 21). |
| **Trending OI** | Read on **15 strikes (7 above + 7 below + ATM)**, 15-min best. For a **big move** want a **>50% call-vs-put gap on BOTH intraday and positional**; near-even positional = muted. A crossover is **not required** when the gap is already very wide. Both intraday + positional turning together = stronger confirmation (Days 1, 2, 7, 8, 14, 15, 19). |
| **IV (6-strike)** | Read 3 above + 3 below ATM. Lower IV favours buyers; a bullish buy wants **call-side IV higher by ~8–10 pts**. **Equal IV (10/10) = erosion**; 40/40, 50/50, 60/60 = erosion (avoid / short straddle); **IV >40–50 favours sellers**. **IV crashes in the 2nd half of expiry day** (call IVs fall as call-buyers exit) (Days 3, 14, 21). |
| **VIX** | Bands: **10–11 buy-dips (full)** · 12–14 caution · 15–18 cautious/unwind · 18–20+ portfolios unwind · 20–25+ tanks. Up+cooling = bull; up+rising = danger; down+rising = bear; down+stable (10–11) = longs unwinding. Genuine rise = climbing every 15 min (vertical). **Low VIX (10–12) → ~90% 2nd-half short-covering bounce** (Days 3, 6, 7, 10, 12, 13). |
| **FII L/S ratio** | FII futures Long/Short ratio gates direction: heavily short (~87–94%) = shorts every level; **ratio crossing ~50% = short-covering-rally trigger**. DII buying alone (~₹10–11k cr/day) may not lift the index if FIIs sell the heavyweights (Days 1, 3, 7, 11, 13, 16). |
| **Global cues** | **Dow futures (US30) = primary, all-day**; Asian (Nikkei/Hang Seng) AM, European (DAX/CAC/FTSE) from ~12:30. Crude good while low (~$60; worry >$70–80). Dollar Index <100 (ideal 92–93); a depreciating USD-INR is bearish (~88.5–88.8, concern near 90). **Don't act on Gift Nifty / overnight Dow at the open — wait for the local pre-market (9:07–9:08)**; re-check Dow right before the open (Days 1, 3, 4, 5, 12, 16). |
| **Breadth** | Advancing-vs-declining count + the top 5–6 heavyweights moving together (+1–3%) confirm follow-through; indicators sitting **far from candles after a breakout = avoid scalping** (Days 1, 4, 10). |
| **Futures basis** | Present-series future at a **discount** to spot = bearish near-term; next-month future at a **premium** = bullish near-term (S24 pre-mentoring primer; consolidated §4.12). |
| **Sensex instrument** | Scalp **Sensex via the Nifty chart** (Sensex 30 ⊂ Nifty ≈ 80% weight; Banking + IT overweight). Trade **Sensex OPTIONS** (futures illiquid). **Participation gate:** trade Sensex only with genuine volume — when thin, prefer **Nifty**. Pick the instrument by nearer expiry / richer premium (**Sensex Thu / Nifty Tue**); Sensex moves **~3–4× Nifty in points** → staggered entries, wide SL, no tight rupee SL (Days 1, 11, 12, 13, 18, 21). |
| **Tooling (OIP)** | **Show OI Bar** (largest put bar = support, largest call bar = resistance; a shrinking put bar on a fall = reversal). **OSPL AI** (red = bearish, green = bullish, ~70–80%; avoid the 1-min "quick scalp"). Risk calculator (price/range/SL/target per strike); **Disable trading in 1 Cliq** once the target is hit (Days 1, 8, 9, 20, 21). |

---

## Strategy Index

| # | Strategy | When / Instrument | Format | S24 coverage |
|---|----------|-------------------|--------|--------------|
| 1 | [Two Candle Theory](#1-two-candle-theory) | Intraday · index Fut / CE / PE | Bull vs Bear table | Deck-taught |
| 2 | [Open = High / Open = Low](#2-open--high--open--low) | Intraday 1st-half · index F&O | Bull vs Bear table | Deck-taught (chasable system, Day 14) |
| 3 | [Market Movers](#3-market-movers) | Intraday · F&O **stock futures** | Bull vs Bear table | Deck-taught |
| 4 | [Gap Theory](#4-gap-theory) | Intraday · 3m N/BN Fut → CE/PE | Bull vs Bear table | Deck-taught |
| 5 | [Trending OI Crossover](#5-trending-oi-crossover) | Intraday · index | Bull vs Bear table | Reference-only (no deck; live-decoded) |
| 6 | [Golden Crossover](#6-golden-crossover) | Intraday (rare) · index | Bull vs Bear table | Deck-taught |
| 7 | [Hero-Zero (Expiry-Day OI)](#7-hero-zero-expiry-day-oi) | Expiry day ~2:30–3:15 PM · index options (buy only) | Timeline | Deck-taught |
| 8 | [BTST / STBT](#8-btst--stbt) | Overnight carry · index | EOD timeline | Reference-only (live-decoded) |
| 9 | [Morning Trade](#9-morning-trade) | Market open · index CE/PE | Open timeline | Reference-only (live-decoded) |
| 10 | [Options Scalping Framework (Connect the Dots)](#10-options-scalping-framework-connect-the-dots) | Intraday · 3m index CE/PE | Checklist | Deck-taught |
| 11 | [Straddle (Long & Short)](#11-straddle-long--short) | Volatility/neutral · ATM index options | Read-only note | Reference-only (parameter read) |
| 12 | [Trend Change](#12-trend-change) | Reversal capture · index | Note | Reference-only (Day-21 refinement) |

---

## 1. Two Candle Theory

> **In plain terms:** When two strong candles push the same way in a row — each with real volume behind it — the move usually has momentum to keep going. You let the second candle finish to confirm it's genuine, then enter on the third candle and ride the breakout with a stop just behind where the move began. One or two clean trades a day is enough.

**Common setup:** **3-min** index Fut chart (BN/N), index futures & options **only**. Two consecutive candles in the trade direction, **each** with volume **≥50K BN / 125K N**. **Enter on the 3rd candle.**

| Step | Bullish (buy Fut / CALL) | Bearish (buy PUT / sell Fut) |
|------|--------------------------|------------------------------|
| Price vs VWAP | Fut **above** VWAP | Fut **below** VWAP |
| Candles | 2 in-direction, each ≥50K/125K | 2 in-direction, each ≥50K/125K |
| OI | **LB or SC** | **SB or LU** |
| RSI (3m) | 50–75/80 (>50) | 40–25/20 (<40) |
| Indicators | PSAR, VWMA, ST, VWAP **all below** price | all **above** price |
| Entry | 3rd candle; CE slightly ITM | 3rd candle; PE slightly ITM |
| **SL** | **1st candle LOW** (scalper trails prev-candle low) | **1st candle HIGH** (scalper trails prev-candle high) |
| Target | Next R; trail; hit-and-run | Next S; trail; hit-and-run |

**Skip / guardrails:**
- **Overbought defer:** if the two candles form with **RSI >85**, do NOT enter on the 3rd candle — wait for RSI to cool to ~70–80/75 and enter on the **red/pullback candle**. A perfect two-green setup can still be followed by a big-volume red 3rd candle (Days 5, 20).
- **SL by trader type:** scalper trails the **previous-candle** high/low; a positional player keeps the **1st-candle** high/low and can run it all day (Day 5).
- A **high-volume formation gives a deep 1st-candle SL** — size the trade for that wider risk (Day 4).
- If the 1st-candle low/high is breached, the fort is breached — **exit** (deck Day 4/5).
- Trend day: fresh 2-candle setups recur ~hourly.

---

## 2. Open = High / Open = Low

> **In plain terms:** Big institutions tip their hand at the open. When bullish, the calls they buy open right at their high of the day (Open=High) and puts open at their low (Open=Low); they then spend the day dragging price back toward that opening extreme, so it usually gets revisited — most often in the first half. You don't buy on sight: you wait for price to recover back toward the Open=High *with volume*, ride it, and step out **just below** the extreme. Index F&O only.

**Common setup:** Index futures & options only (stocks → Market Movers). Data visible only after the open (~9:16). **~90% of Open=High levels are hit in the 1st half** (by ~9:45/10:00/10:30); only ~20% in the 2nd half and only on a trend change. **Don't buy on sight** — time the entry.

| Step | Bullish (CALL O=H) | Bearish (PUT O=H mirror) |
|------|--------------------|--------------------------|
| Confluence | **≥3 strikes above AND below ATM + FUTURES all show O=H** | mirror: ≥3 strikes + Fut show the bearish O=H/O=L pattern |
| Pairing | **O=Low present on the PUT side** (put not up >50%) | O=Low on call side |
| Strike | round strikes weigh more; **prefer ITM over OTM**; avoid deep OTM/ITM (liquidity is the gate) | same |
| Reject if | identified strike fell **>50%** from open (20–30% OK); **both call AND put show O=H → ignore entirely** (two opposing players) | same |
| OIP badge | flagged from 9:16; **>90% badge = high chance** | same |
| Entry trigger | price **recovers toward the O=H with momentum** + **≥50K (BN) volume on ~3 consecutive candles**, indicators below price → prob 70–80–90% | mirror |
| **Target** | the O=H level itself — **NEVER target above the O=H** (e.g. O=H 183 → target ≤182); once hit it reverses ~90% | the traded PUT's O=H level — **never target above it** (premium mirror); the index falls toward its O=L |
| Abort/exit | against-move on **≥50K BN / ≥125K N** volume, or a downside 2-candle; **>50% premium fall + >50% OI rise (crossover) = exit** | mirror |

**Risk / guardrails:**
- **High-risk trade — never deploy >30% of capital** (Day 14 deck).
- **Directional-change precondition (Day 20):** O=H only triggers on a confirmed directional change (price below VWAP with volume); it won't work once the call-side >50%-OI-&-price and put-side >50%-fall quadrants are already fulfilled.
- Ignore illiquid strikes even with a textbook O=H (e.g. Day-14 Nifty 24,850 O=H ~450 but ~0 volume all day).
- Positional data can override: a chasable O=H (CE fell <50%) was dropped on Day 14 because positional data was bearish.
- If not hit by ~11:00–12:30, probability keeps falling — avoid late entries.

---

## 3. Market Movers

> **In plain terms:** A scanner play. OI Pulse's "Market Movers" sorts the day's biggest F&O gainers and losers and flags how many days' high/low each one just broke. When a liquid stock breaks an 8–9-day high with the right OI and an Open=Low (its low isn't being tested), it's in strong hands and tends to add another 1–2%. Trade the **stock future**, never stock options.

**Common setup:** OIP Market Movers (filter Nifty 50 / Nifty Bank); read script · LTP · OI%Δ · OH/OL · OI interpretation · **minimum breakout days**. **Trade FUTURES, not stock options** (stock options can be illiquid / not move). Underlying must be **liquid**.

| Step | Long (high-prob) | Short (mirror) |
|------|------------------|----------------|
| Breakout | **≥8–9-day high**, stock up **3–4%+** | **8-day low** breakdown (a >15% fall / 15-day low = strong) |
| O=H/O=L | **Open=Low** on the stock (low not tested) | **Open=High** not tested |
| OI | **long build-up / short covering** | **long unwinding / short build-up** |
| RSI(D) | >70 by open = no fresh long; dip to **~67–68 = buy**; book ~80 | falling stock at RSI ~25–30 (oversold) often recovers — careful |
| Entry | **long after price moves above VWAP** | short after price below VWAP |
| Target | a further **1–2%** | mirror |
| Manage | book as RSI nears ~80; if it then falls, **VWAP = level to take a final trade / average / close** | mirror |

**Skip / guardrails:** strict **no** on stock options; skip illiquid names (e.g. Day-10: Maxhealth ~150K/120K = "no volume"). Deck trade examples (ADANIENT, ZEEL, ABCAPITAL) are reused 2022–23 illustrations, not 2025 data.

---

## 4. Gap Theory

> **In plain terms:** When orders pile up faster than they can fill, price jumps and leaves a gap on the 3-min chart. The players who missed those fills drag price back to complete them, so gaps act like magnets and fill ~90% of the time — usually same day or next. You wait for the gap to fill (with volume) and then trade in the trend's direction.

**Common setup:** 3-min N/BN. A gap fills ~90% of the time (live: "99% same day or next"). Runaway/momentum gaps may never fill. **It's a 30–60 min play.**

| Step | Long | Short |
|------|------|-------|
| Context | uptrend, gap below; up-move **with** volume = valid gap | downtrend; if the **fall** carried the volume, expect fast reversal |
| Entry | wait for price to fill the gap, then buy in trend direction; use a nearby VWAP/ST as the best-bet level | after gap fills, let price tag R, then take the down-side trade |
| **SL** | **low of the candle from which the gap formed** (or ~50–60 pts / nearby S/R) | high of the gap candle (or ~50–60 pts) |
| Exit | RSI ~70–75–80, or prev-candle-low stop | mirror |

**Skip / guardrails:**
- Wait ~30–40 min; if not filled **with volume**, ignore it and trade with the trend (Day 21).
- **Don't chase a call just to fill a gap when texture is bearish** — a fill *without* volume is bearish-friendly; a fill *with* rising volume warns of an upside reversal (Day 17).
- A gap unfilled past 1–2 days → discount it (a bigger player is overriding). A strong ~200-pt rally makes a same-day fill unlikely.

---

## 5. Trending OI Crossover

> **In plain terms:** Plot the total call-side OI line against the total put-side OI line. When they crisscross, sentiment is flipping — puts building while calls fall is bullish (and vice-versa). You enter on the candle after the cross, but only when the gap between the two lines is wide and widening, and you bail the instant a second cross goes against you.

**Common setup:** Read the call-OI vs put-OI lines on **15 strikes (7+7+ATM)**, **15-min best**. Valid **9:40–2:30**. While both lines travel together = **no trade**. A genuine crossover comes with a volume spike ~90% of the time.

| Step | Long | Short |
|------|------|-------|
| Signal | put OI rising + call OI falling; **PE line crosses above CE** | call OI rising + put OI falling; **CE crosses above PE** |
| Gate | **≥50% (ideally 50–100%) call-vs-put gap, widening**; intraday + positional both agree | same |
| Entry | candle **following** the crossover | candle following |
| RSI | 50–75 | 40–25 (<20 oversold → wait for bounce) |
| **SL** | high/low of the **crossover candle** (scalper); positional uses ST/VWAP | same |

**Skip / guardrails:**
- **Crossover not required** when the gap is already very wide — the divergence *is* the signal (watch the lagging side's OI build; Day 8 was a bearish sell-on-rise day, ~7cr call vs ~1.5cr put).
- **Fake-crossover handling:** a 2nd crossover against you = **exit immediately** (the trap); never average without watching the crossover. **2–3 crossovers in a day = totally sideways → avoid.**
- To **flip direction** you need **VWAP broken AND trending OI changed direction** — not a VWAP/volume break alone.
- Don't take an aggressive trade on contradictory signals (price above all indicators but OI bearish = no long).
- Ideal bullish positional ≈ **5cr call vs 10–12cr put** (put writing > call writing).

---

## 6. Golden Crossover

> **In plain terms:** A rare, high-conviction signal — only 3–5 times a month. When the VWMA and Super Trend both punch through VWAP in the *same* candle with volume, the trend is flipping hard. You enter on the next candle and expect a sizeable follow-through.

**Common setup:** **VWMA and ST pierce VWAP together in the same candle**, 3-min, with trending OI of 5–7 strikes either side. Enter on the **2nd/next** candle. Rare (~3–5×/month).

| Step | Long | Short |
|------|------|-------|
| Crossover | ST & VWMA pierce VWAP together | ST & VWMA pierce VWAP together |
| RSI (3m) | 50–75 | 40–25 |
| Volume | ≥50K BN / 125K N | ≥50K BN / 125K N |
| Trade | Buy CE / Sell PE | Buy PE / Sell CE |
| **SL** | the crossover candle's level | the crossover candle's level |
| Follow-through | **+200–300 pts BN / +50–100 pts N** | mirror |

**Skip / guardrails:**
- **No-trade zone:** if ST breaks in the morning and price then trades **between ST and VWAP** = range/erosion → avoid (Days 5, 13).
- Clustered VWAP+ST+PSAR can be cleared in one big-volume candle — don't treat the cluster as strong resistance.
- **Dip-buy / pyramiding shape** (a general support-zone technique, demonstrated on a Day-5 ST-support trade that was *not itself* a Golden Crossover): pyramid from ST down toward VWAP; SL ~30–40 pts below the defended territory; ~20% at the ST zone, ~80–90% reserved for the VWAP zone.
- S24 surfaced **no new 2025 live Golden Crossover trade** — the deck's dated examples are reused 2022–23 illustrations.

---

## 7. Hero-Zero (Expiry-Day OI)

> **In plain terms:** An expiry-day closing-hours play. In the last 30–40 minutes the option writers who are winning scramble to cover, which can double (sometimes 5–10×) the cheap option on the side they're vacating. It's not a blind lottery — you read the OI to find which side the writers are abandoning and sit there as a buyer, staking only a slice of your *profits*.

**Timeline (expiry day):**
1. **After close the prior day:** pull the week's derivative data (NSE / OIP "OI Expiry Strategy"). Analyse **5 strikes either side of ATM** (10 if high volatility); **round strikes only**.
2. **Read who controls the move** via OI quadrants: a long build-up on the put side with call writing → buyers on the put side; when put buyers unwind, writers shift to puts and the **call side moves up** → sit as a call buyer. Favor the side **trading at a discount** and the side whose **premium is lower**; if call-side OI is rapidly rising while the other covers, take the **PUT** side (and vice-versa).
3. **Execute ~2:45–3:15 PM** (deck frames the window ~2:30–3 PM), driven by writers covering (short covering / long unwinding) — **not a gamma blast**.
4. **Strike:** ATM or 1 above (OTM) = aggressive; ITM or 1–2 above = safer. Prefer low-premium strikes (max loss = the small premium); with high premiums prefer ITM/deep-ITM (OTM crash is bigger on a fall).
5. **Manage:** set a level — if it breaks, **let it go to zero**; if it rises, **keep trailing**. **Do NOT average** a losing hero-zero.

**Skip / guardrails:**
- **Stake only ~10% of your PROFITS — never capital.**
- **Sellers adding on BOTH call and put = pin/erosion → avoid the day.** Strikes closing near VWAP = erosion, no trade.
- **Monthly-expiry day: ignore the OI/expiry data** (writers unwind prior-month positions; the data describes the past month, not the new series).
- **Bank Nifty caution:** with daily expiries removed, BN volumes dried up — it no longer reacts as before.
- Direction is two-sided depending on VIX + OI: high VIX favours an end-of-day downside sell-off, but positive ~2:45 news / strong call-side short-covering can spark a fast upside move.
- Day-10 deck figures (e.g. the 36400 CE 92→280 move) are reused 2022–23 illustrations, not 2025 data.

---

## 8. BTST / STBT

> **In plain terms:** Carry an options position overnight to catch tomorrow's gap, but only when today closed strong and held its key levels. It works ~6–7 times out of 10; the other 3–4 are deliberate overnight traps, so keep size tiny and exit early at the open.

**EOD timeline:**
1. **Validity gate:** only carry overnight when, after the day's move, the market does **NOT breach VWAP or Super Trend** and **holds strength into the close** (reclaimed/held VWAP on volume, never broke back below VWAP/ST).
2. **Size at only 5–10% of capital.**
3. **Exit early next morning** — the play targets the open/morning move, not a multi-day hold.

**Skip / guardrails:**
- BTST is right ~**6–7 of 10**; ~3–4 get trapped — operators trap overnight players on the off days.
- A **news-driven single-day rally after a multi-day fall is not trustworthy** for an overnight carry — needs 2–3 days of volume-backed follow-up.
- **Don't take a BTST near expiry** just because the market inches up in the last 30 min; if the morning high wasn't reclaimed in the 2nd half, carrying is risky.
- After a big intraday hit, if you've recovered ~**80–90%+**, **square off rather than carry**; protect prior-day profit + ≥70–80% of intraday profit before taking fresh overnight risk.

---

## 9. Morning Trade

> **In plain terms:** The trade taken right at the 9:15 open — the single riskiest trade of the day. You wait for the pre-market to settle, read the gap to pick a side (sell a gap-up rather than buy an oversold gap-down), check that heavyweights aren't holding the index up, and enter a near-the-money strike with a hard, pre-set stop. Freshers should skip it.

**Open timeline:**
1. **Wait for the pre-market to settle (~9:07–9:08).** Ignore the initial ±200-pt swings; use the settled open to compute fair value + the strike's premium range.
2. **Read the gap to pick a side:** on a big gap-down (~300–400 pts) do **NOT** buy a put (already oversold); prefer using a **gap-up (~30–40 pts, global cues flat/green) to short once** (a gap-up is expected to be sold off at least once).
3. **Check heavyweights before shorting:** Reliance / Infy / HDFC Bank / TCS up 2–4% can add ~80–100 N pts and hold the index up — shorting can trap you.
4. **Strike:** 2–3 strikes from the settle (not deep OTM); prefer slightly ITM in the morning, rotate to higher-priced strikes as expiry nears.
5. **SL:** the strike's prior-day VWAP / bounce bottom; exit on a close below; on a news-driven adverse move honor the SL and **do NOT average**.

**Skip / guardrails:**
- **Size: risk only ~10–20% of capital**; use the previous day's profit as the SL budget.
- Always have a **pre-defined exit** before entering; freshers/uncomfortable traders should never attempt it.
- On expiry, if prior-day OI shows put buyers holding tight, restrict the opening trade to the **call side only** (Day 11).
- On a gap-up overbought open the morning opportunity is small; a later consolidation window opens for those who missed it (Day 20).

---

## 10. Options Scalping Framework (Connect the Dots)

> **In plain terms:** The master checklist that "connects the dots" before any scalp — global cues, VIX, OI quadrants, IV, then the five chart indicators. Chart *and* data (intraday + positional) must agree, or you don't trade. Everything else in this sheet sits on top of this read.

**The dots to connect (in order):** Dow 30 futures → India VIX → OI Spurts (4 quadrants) → OI strikes & futures → IV (6 strikes) → VWAP → Super Trend → volume candles → RSI → Parabolic SAR.

**The 5 chart dots (3-min):** VWAP (default) · ST (10,2) · Volume candle (50K BN / 125K N) · RSI(14) 80:20 · PSAR (0.02, 0.02, 0.2). **Defence order on a fall:** VWMA(20) → ST → VWAP (VWAP is the biggest support after OI).

**Core rules:**
- Intraday data window **9:45–2:30**; scalp 3/5-min, positional 15-min/hourly.
- **Chart AND data (intraday + positional) must align**, or it's an avoid.
- **Volume confirms every break** — a VWAP/level break without volume traps you.
- **No-trade zone** = price boxed between ST/VWMA and VWAP → 1–2 lots only, wait for a boundary break.
- **VWAP switch:** prev-day VWAP is the first support on a fall but valid only until ~10:00–10:30; switch to intraday VWAP after ~10:30–11:00.
- **Hourly-new-high cadence:** a trending day prints a fresh high ~every hour; if hourly highs stop and price holds a ~30-pt range = erosion. Escalating premium (~20 → 60 → 90 → 110) confirms a genuine trend.
- **Support tiering:** weak / strong / very-strong — small at weak, max size + firefighting funds at very-strong (usually VWAP).
- **Recycle profit:** book initial profit, re-enter the same strike lower using booked / prev-day profit as the next risk budget.
- **Discount-premium read:** ITM call below intrinsic / puts at ~5% discount with no buyers = market expects an up-move; LTP − premium = intrinsic (below-intrinsic = a discount, favourable to the buyer).
- **Indicators far from candles after a breakout = avoid scalping** (price won't return to support).

---

## 11. Straddle (Long & Short)

> **In plain terms:** S24 doesn't re-teach how to build a straddle — it uses the **combined call+put premium read against its own VWAP** as a read of the day's character: while the combined premium is above its VWAP the move favours straddle *buyers*; once it slips below into the close, the *sellers* (writers) win, which happens on flat/erosion days.

**Reference-only in S24 (no deck; base mechanics from earlier sessions).** What S24 actually uses:
- **Combined-premium-vs-VWAP is the directional gate** — above VWAP = buyers' day; **once it drops below VWAP into the close, straddle buyers lose** and writers take the day (Day 17).
- **A flat / premium-erosion day** = combined premium grinds lower, both legs bleed → **straddle sellers win** (high IV / high premium is a double-edged sword on a flat day) (Days 3, 14, 19).
- **High / equal both-side IV (40/40, 50/50, 60/60) ⇒ short straddle or stay out**; 20/20 is the lower "mostly premium-erosion" band. If you can't run a short straddle in that regime, stay out (Day 3).
- The **~50% call-vs-put OI gap** (with a higher market) is itself a parameter confirming a bullish / buy-the-dip read even with no Two-Candle setup (Day 5).

**Guardrail:** treat it as a character read, not a standalone entry — sizing follows the global §0 caps (never go all-in). (No reused-deck figures exist for this strategy in S24; all numbers above are 2025 transcripts.)

---

## 12. Trend Change

> **In plain terms:** A reversal-capture play, only referenced (not re-taught) in S24. The one rule S24 adds: don't call a trend change off a VWAP break alone — the Trending OI must also flip; and a counter-trend trade is only allowed when the counter-move is *not* backed by heavy volume.

**Reference-only in S24 (no deck; base mechanics from earlier sessions).** What S24 states:
- **A trend change must be confirmed by Trending OI, not the chart or a VWAP break alone:** to flip from long to short you need **VWAP broken AND Trending OI direction changed**. While Trending OI stays positive, keep trading with the bulls (Days 5, 11, 12).
- **Divergence counter-trend trade with a 125K-volume gate (Day 21):** when **intraday** Trending OI has turned bearish but **positional** is still bullish, a counter-trend (call-side) trade is allowed **only if the down-move is NOT backed by >125K (Nifty) volume** — a volume-backed counter-move = the real reversal (go with the trend instead).
- **Monthly-expiry caveat:** on a monthly-expiry day, ignore the OI/trend data (writers unwind the expiring month — it doesn't describe the new series) (Days 20, 21).

---

> **Footer.** Derived solely from [`Options_Scalper_Siva_Consolidated_Strategy_S24Only.md`](Options_Scalper_Siva_Consolidated_Strategy_S24Only.md) (the single source of truth). No values, thresholds, or rules appear here that are not in that document. For any uncertain item, defer to its §5 "Open Questions / To Confirm". Reused 2022–23 deck example figures are illustrative — never trade them as 2025 data.
