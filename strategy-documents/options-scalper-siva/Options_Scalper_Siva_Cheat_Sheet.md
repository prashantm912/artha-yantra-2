# Strategy Cheat Sheet — Step-by-Step Execution

> **⚠ SUPERSEDED (2026-06-29) — historical.** Current execution guidance lives in the operative doc
> (`../options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md`). Retained for
> provenance only; do not edit for forward work.

> **What this is:** A ready-reference execution checklist for all **12 strategies** (Session 20 + the **Session 21 "Live Scalping 2024"**, **Session 22 "Live Mentoring Prog 2.0 2024"**, **Session 23 "Sensex Scalping with Siva 2025"** and **Session 24 "Big 5 Anniversary — Live Decoding 21 Days 2025"** refinements; **Session 22 adds the Straddle**, **Session 23 adds Trend Change** + a Sensex-scalping focus; **Session 24 adds no new strategy** — a 21-day live re-decoding that confirms S20–23 and sharpens Open=High into a fully-chasable system). Pure algorithm — no terminology, theory, or rationale. **Read [`Options_Scalper_Siva_Consolidated_Strategy.md`](Options_Scalper_Siva_Consolidated_Strategy.md) first** — this assumes you already know every term, indicator, and OI/IV/VIX concept used below. Items tagged **[S21]** / **[S22]** / **[S23]** / **[S24]** are Session-21 / -22 / -23 / -24 additions/refinements.
>
> **How to use:** Run **§0 Pre-Flight** on every trade, then jump to the strategy. Each strategy lists only what is *specific or overriding* relative to §0.
>
> **Abbreviations:** BN = Bank Nifty · N = Nifty · Fut = Futures · TF = timeframe · LB/SC/SB/LU = Long Build-up / Short Covering / Short Build-up / Long Unwinding · ST = Supertrend · OIP = OI Pulse · OSPL = OI Pulse AI scalping signal · R/S = Resistance/Support · prev = previous.

---

## 0. Pre-Flight (apply to EVERY trade)

### 0A. Risk rails (non-negotiable)

- **RR 1:2** — risk 0.5% : reward 1%. Set the RR tool *before* entry; once in, let it hit SL or target — no interference.
- **Hard SL in the system on every trade** (never mental). Cut losses fast.
- **Sizing:** ≤10–20% capital per trade, ≤20% per day. Never buy full qty at once — scale in nearer VWAP/ST/S-R.
- **Daily loss cap:** stop trading in **all** accounts at **0.5%** day loss. Never exceed **2–3%/day**.
- **Daily profit target 1–2%** — log out the moment the day's profit *or* loss target is hit.
- **Winning qty = losing qty** (no "HOPE TRADE").
- **5 accounts:** 1% per account; max 5 wins/day (1 per account); first trade in each account must win; if an account's first trade loses → stop that account for the day.
- **No averaging down**; no adds after VWAP/ST is broken. Pyramiding *toward* target is OK.
- **Trade with the trend only.** No-trade is a good trade.
- **[S21] Pyramid vs average:** pyramid (add to a winner) only *after price is above your entry LTP*, within the Risk-Calculator range, trailing as you add; average only *near support* (ST/VWAP). Never average a losing trade.
- **[S21] Symmetric days:** keep the loss-day size ≈ the profit-day size (make ~₹1–2k → cap loss ~₹2.5k); losing points ≈ your usual winning points.
- **[S21] SL vs target base:** the per-trade SL is ~10% of *deployed* capital; the target is on *overall* capital (why the Risk-Calc gap looks wide). Size the SL off delta to fit the trade's RR.
- **[S21] Never sell naked** — always hedge; a hedged sell may run to ~80% premium decay or expiry.
- **[S21] Wide-SL scale-in (profits only):** when the VWAP–ST gap (the stop) is wide, deploy <5% of qty initially, +5% on a ~50-pt move, +10% at VWAP.
- **[S21] Stop at target:** end the day once the target is hit (discipline pop-up after 11 AM); any re-entry uses smaller qty risking only a slice of profits. Sit out extreme volatility until it settles.
- **[S22] Symmetric days (numeric):** 2% day target → loss **≤2–3%**; if winning trades use **4 lots, losing trades ≤4–6 lots** (profit ~2% / loss ~5% = over-sized losers → cut lot count). Average **only in the first session**, never the later half / not into a sudden both-side-OI move. **Scale lot size up only every 3–6 months.** Put the whole capital into a low-probability bet (deep-OTM / Hero-Zero) **never** — only a slice of profits.
- **[S22] Index-scaled point SLs:** Bank Nifty ~**75 pts**, Nifty ~**30 pts**, wider for Sensex (~80000) / Bankex (~58000).
- **[S23] Capital allocation:** deploy only **5–10% of total capital** to trading (diversify the rest); within that, a single trade still takes ≤15–20% (max ~30%), ~10% risked.
- **[S23] Sensex sizing:** Sensex ≈ 3× Nifty value → ~3× the points (1% ≈ 800 pts) — **trade smaller qty + a wider point SL**; on brutal/gap days keep a tight absolute **50–100-pt SL** and exit fast. Not for those uncomfortable with volatility.
- **[S23] Average only near support/VWAP/ST inside a pre-set pyramiding band**; on wide-support-gap days (~150 pts apart) don't average through the gap — fixed 50–100-pt SL or 1:1 RR. Hero-Zero / low-prob bets: stake only a tiny slice of **profits** (~₹1–2k), never capital. Stay light after the day's target; one mistake on a low-conviction day = big loss.
- **[S24] Single-day hard loss cap 10–12%:** never lose >**10–12% of trading capital** in one day (~₹3 L on a ₹25 L account is the recoverable bound). Once a fall **breaks VWAP, accept it and exit** — averaging below VWAP wipes 70–80%.
- **[S24] Geometric-lot pyramiding (1 / 2 / 4 / 8, up to 16):** scale lots geometrically toward the **strongest support (VWAP)** so the heaviest size sits nearest the SL; may also add on the upside as a base forms. Day-20 frames it as **planned scaling, not averaging** (20-pt window, e.g. 2+1+2 lots, max 5). Once in profit, **hold qty constant and trail tighter** — don't add.
- **[S24] Instrument-scaled deep SLs:** **Nifty ~50–60 pts, Sensex ~200–250 pts** — a tight 5–10-pt SL gets hunted. Size to volatility & the Trending-OI gap: low-VIX/IV → larger qty, small target (~4 pts); high-VIX/IV → small qty, larger target (100–200) or stay out; wide OI gap = full size, narrow = reduced.
- **[S24] Recycle profit, not capital:** re-enter using **booked / prev-day profit** as the next trade's risk budget; keep only ~5–10% of total finances in markets, split across accounts. Target scales with capital: ~**0.5–1%/day at ₹5–10 L**, ~2%/week at ₹2–5 Cr. Overtrading (afternoon size-creep) is the killer — book the morning profit and stop.

### 0B. Universal intraday gates (assumed by every intraday strategy unless it overrides)

| Gate | Rule |
|------|------|
| **Time** | After **9:45 AM**; ideal **9:15–10:00**; avoid sideways **11:00–1:00**; no fresh entry before an event after **3:30 PM**. |
| **Volume candle** | ≥ **50K (BN)** / **125K (N)**. |
| **RSI 14 (3m, 80:20)** | **40–60 = NO TRADE.** CE: >50 (zone 50–75), keep <75/80, RSI(D)<75. PE: <50 (zone 40–25), keep >25/20, RSI(D)>25. |
| **Indicator alignment** | All indicators **below** price = bull; **above** price = bear. |
| **Strike** | ATM ±3 only; delta **0.6–0.7**; premium **N 100–250 / BN 250–400**; prefer AI-suggested strike. **[S21]** delta **0.7–0.8 near expiry-end, ~0.5 on a new weekly's first day**; low-VIX → lower-premium strikes, high-VIX → higher. Read IV at ~3 LTPs around ATM only. **[S22]** buyer delta may run **0.7–0.8/0.9**, sellers ~**0.4**; ideal trending IV gap = **7–10 pts** (IV higher on the trending side); **IV >40 → stay away as a buyer**; prefer **ATM/ITM** for quick 10–20-pt scalps. |
| **OI** | Futures: **LB/SC = long, SB/LU = short.** Strike: Call OI ↓ / Put OI ↑ = bull (reverse = bear). **[S21] Q1/Q2 buy gate:** buy only when BOTH price >50% AND OI >50% on the correct quadrant. **[S21]** OI intervals 5/15/30/60/120/240 min; Futures OI 3–60 min; **15-min for the cross, 60-min for the longer view**. |
| **VIX / cues** | CE → VIX down; PE → VIX up. Global cues (Dow / Dollar / Asia / Oil) + OIP AI must align. **Adv>32 = CE / Dec>32 = PE.** **[S21] regime bands:** 10–11 bullish (buy 100–200-pt dips) · 12–14 bullish-sluggish · 15–16 sell the rise · 17+ high-vol (active shorts, SC bounces); price↑VIX↓ = bull, price↑VIX↑ = revert; erratic VIX → ignore. |
| **[S21] Futures basis** | Future **>** spot = bullish bias; future **<** spot (discount) = bearish near-term. |
| **[S21] Data weighting** | Weight **prev-day data + prev-day VWAP until 11 AM**, then intraday data + current-day VWAP. Pre-open **9:00–9:07** institutional positioning + advances/declines set the day's bias. |
| **[S21] Constituents** | Read the index's **top movers** (BN top-3 ≈ 60% weight); a move from only 1–2 heavyweights is narrow — confirm BN only when its banks are in sync. |
| **[S23] Sensex instrument** | Scalp **Sensex via the Nifty chart** (Sensex 30 ⊂ Nifty ≈ 80% weight; Banking + IT overweight → study those two). Trade **Sensex OPTIONS** (futures illiquid — a day's high vol ~418). News overrides data on gap/event days → trade smaller, prefer deep-ITM. |
| **[S24] Sensex participation gate** | Trade Sensex **only with genuine volume/participation** — when its option volume is thin, prefer **Nifty** (higher-volume index). Pick the instrument by nearer expiry/richer premium (**Sensex Thu / Nifty Tue**); on a Sensex expiry monitor **Nifty + Sensex** data together. Sensex moves **~3–4× Nifty** in points → staggered entries, wide SL, never a tight rupee SL. |
| **[S24] Trending-OI 15-strike** | Read Trending OI on **15 strikes (7 above + 7 below + ATM)**. For a **big** move want a **>50% call-vs-put gap on BOTH intraday and positional**; a crossover is **not required** when the gap is already very wide (watch PCR 1.2 → 1.5 → 2). |
| **[S24] FII L/S ratio** | FII futures **Long/Short ratio** gates direction: heavily short (~87–94%) = shorts every level; the ratio **crossing ~50% = short-covering-rally trigger**. DII buying alone (~₹10–11k cr/day) may not lift the index if FIIs sell the heavyweights. |
| **[S24] IV gate** | Bullish buy wants **call-side IV higher by ~8–10 pts**; **equal IV** (10/10, 13/15) = range/erosion; IV >40–50 favours sellers. **IV crashes in the 2nd half of expiry day** (call IVs fall as call-buyers exit). |

---

## Strategy Index

| # | Strategy | When / Instrument | Format |
|---|----------|-------------------|--------|
| 1 | [Two Candle Theory](#1-two-candle-theory) | Intraday · index Fut / CE / PE | Bull vs Bear table |
| 2 | [Open = High / Open = Low](#2-open--high--open--low) | Intraday 1st-half · index F&O | Bull vs Bear table |
| 3 | [Market Movers](#3-market-movers) | Intraday · F&O stocks (cash/stock-fut) | Bull vs Bear table |
| 4 | [Gap Theory](#4-gap-theory) | Intraday · 3m BN Fut → CE/PE | Bull vs Bear table |
| 5 | [Trending OI Crossover](#5-trending-oi-crossover) | Intraday · index | Bull vs Bear table |
| 6 | [Golden Crossover](#6-golden-crossover) | Intraday (rare) · index | Bull vs Bear table |
| 7 | [Hero-Zero (Expiry-Day OI)](#7-hero-zero-expiry-day-oi) | Expiry day after ~2:30 PM [S21] · index options (buy only) | Timeline |
| 8 | [BTST / STBT](#8-btst--stbt) | Overnight carry · index / stocks | EOD timeline |
| 9 | [Morning Trade](#9-morning-trade) | Market open · index CE/PE | Open timeline |
| 10 | [Options Scalping Framework (Connect the Dots)](#10-options-scalping-framework-connect-the-dots) | Intraday · 3m index CE/PE | Bull vs Bear table |
| 11 | [Straddle (Long & Short)](#11-straddle-long--short) **[S22]** | Neutral/volatility · ATM index options | Long vs Short timeline |
| 12 | [Trend Change](#12-trend-change) **[S23]** | Reversal capture · index (Sensex via Nifty chart) | Bull vs Bear table |

---

## 1. Two Candle Theory

> **In plain terms:** When two strong candles push the same way in a row — both with real volume behind them — the move usually has the momentum to keep going. You let the second candle finish to make sure it's genuine and not a fakeout, then enter on the third candle and ride the breakout with a tight stop just behind where the move began.

**Common setup:** 3m BN Fut chart. Two consecutive candles in the trade direction, **each** with volume ≥50K BN / 125K N. 2nd candle must be strong — **reject if its wick ≥ 2× body** (rejection). Use the 2nd candle's full 3 min to analyse, not to pre-commit. **Enter on the 3rd candle.**

| Step | Bullish (buy Fut / CALL) | Bearish (sell Fut / PUT) |
|------|--------------------------|--------------------------|
| Price vs VWAP | Fut **above** VWAP | Fut **below** VWAP |
| Candles | 2 GREEN, each ≥50K/125K | 2 RED, each ≥50K/125K |
| OI | LB (best) or SC; Call OI ↓ / Put OI ↑; OI-change diff **HIGH** on one side | SB (best) or LU; Call OI ↑ / Put OI ↓; diff HIGH |
| RSI (3m) | 50–75 (>50, keep <80) | 40–25 (<40, keep >20) |
| Indicators | PSAR, VWMA, ST, VWAP **all below** price | all **above** price |
| Zone | At/breaking from Support | At/breaking from Resistance |
| Entry | 3rd candle; buy CE slightly ITM, delta 0.6–0.7 | 3rd candle; buy PE slightly ITM |
| **SL** | 1st candle **LOW** (alt: VWAP if move already extended) | 1st candle **HIGH** (alt: VWAP) |
| Target | Next resistance; aim 1–2%; trail PSAR → ST | Next support; aim 1–2%; trail PSAR → ST |

**Skip / guardrails:**
- Bear: do **not** take if RSI already <20 (bounce risk) → prefer the follow-on ST-rejection reversal. Bull: don't go heavy on qty near RSI 80.
- Major resistance near (long) / major support enroute (short) = low probability.
- Average **only** at ST / VWAP / VWMA — **never** past the SL.
- VWAP break **with volume** = trend reversing → exit. Fake breakout (no volume) → reverses into VWAP.
- Trend day: fresh 2-candle setups recur ~45 min–1 hr apart.
- **[S21]** 1st+3rd candle may meet the volume gate if the 2nd misses (two qualifying candles still required). On a huge 1st candle, SL = 1st-candle high **or** 2nd-candle low. Take **only ONE** ST/VWAP rejection trade after the move. A 2-candle **+ Golden Crossover** on the same side = high-conviction ride; positional SL = 1st-candle low. Skip entry if RSI >80 at the 2nd-candle close (wait for cool-off); prefer a full-body 2nd candle, be aggressive only on >50% Trending-OI difference.

- **[S22]** Bearish move needs **RSI to break below 40** — if operators hold RSI above 40 the move fails (exit at SL); a ~47K 2nd candle (vs the 50K gate) did NOT produce the move. Trail aggressively when RSI nears an extreme. Rejection/support entry sizing ladder: **~25% at the WMA, more at ST, max at VWAP.**

- **[S24]** **Overbought defer:** if the two candles form with **RSI >85**, don't enter on the 3rd candle — wait for RSI to cool to ~70–80/75 and enter on the **red/pullback candle** (a perfect two-green setup can still be followed by a big-volume red 3rd candle). **SL by trader type:** a scalper trails the **previous-candle** high/low; a positional player keeps the **1st-candle** high/low and can run it all day. A **high-volume formation gives a deep 1st-candle SL** — size for that wider risk.

---

## 2. Open = High / Open = Low

> **In plain terms:** Big institutions tip their hand at the open. When they're bullish, the calls they buy open right at their high of the day (Open = High) and puts open at their low (Open = Low). The bet is that they'll spend the rest of their money dragging price back toward that opening extreme, so that level usually gets revisited the same day. You ride that pull and step out just before the extreme.

**Common setup:** Index F&O only (BN primary). Ideal window **9:15–10:00, 1st half only** (premium erodes in 2nd half). Restrict to ATM/ITM, ATM ±3. Require **≥3 strikes above/below ATM matching** the OH/OL. **Reject the setup** if the identified strike's premium fell >50% from prev close (bull CE) / rose >50% (bear PE), **or** its OI change >50% (bigger player took the opposite view).

| Step | Bullish | Bearish |
|------|---------|---------|
| Futures | **OH** on BN Fut + LB/SC | **OL** on BN Fut + SB/LU |
| Strikes | ≥3 Call strikes show **OH** (+ Put side **OL**) | ≥3 Call strikes **OL** (+ Put side **OH**, Put is the leg) |
| OIP probability | **≥90% with badge** (red dot preferred). Don't chase <90%. | ≥90% with badge |
| RSI | >50 and rising | <50 and falling |
| Indicators | below price; VIX down | above price; VIX up |
| Entry | Buy CE (premium nearest its target), delta 0.6–0.7; on breakout **with volume** | Buy PE; on breakdown **with volume** |
| **SL/Target** | Target = the OH itself but **never place target above OH** → exit **2–5 pts below OH** [S21]; scalp **~40–50 pts** [S21]; if it keeps running, **trail SL up from the OH** [S21] | Target = OL; exit **~5 pts above OL** |

**Risk / guardrails:**
- **High-risk trade — max 30% capital.** Adverse move on **>50K** volume = exit (low-volume drift may be tolerated).
- Avoid OTM / deep-ITM (no exit liquidity).
- OH+OH on both sides, or OI liberally populated both sides = sideways → avoid.
- If the >50% price-rise criterion is *already* met, wait for a pullback (visible in Trending OI) before acting.
- **[S21]** Require **Q1 quadrant + candles above ALL indicators** (CE; below all for PE); chase OH on the call side only when candles are above the indicators (extra confidence when the put OL sits at the ATM). **Skip OH/OL on a strong trend day** unless call prices have fallen >50%.

- **[S22]** Premium bands now **operative**: BN **250–550** (avoid >600/<200), N **150–350** (avoid <130/>380); buyers near-ATM, sellers OTM. **~90% of OHs hit before 10:30 AM** — if not, low-prob (skip if an ATM strike would need ~100% premium move). A **Put-OH** needs only Call-OI↑ + Put-OI↓ and **all indicators above the candles**; a **Call-OH** is valid only **above VWAP**. On a live OH momentum scalp, **VWAP = the SL.**

- **[S23]** OH often hit on the **opening ticks ~9:17–9:28** (or before ~10:00–12:31); big players defend the levels they create → probability badge climbs toward ~90% on a valid setup. Bands extend to **BN/Sensex ~250–500**. **≤30% capital — beginners 1–5%**; only ~3 strikes around the dominant strike, always trail.

- **[S24] Fully-chasable system (Day 14):** **index F&O only** (stocks → Market Movers); a call-side OH pairs with a put-side OL. Data is visible only **after the open (~9:16)** and **~90% of OH levels are hit in the first half** (by ~9:45/10:00/10:30), only ~20% later. **Chase filter (CE):** require **≥3 strikes above AND below ATM plus the FUTURES** all showing OH; round strikes weigh more than 50-pt strikes; the strike must **not** have fallen **>50%** from open (20–30% OK); prefer **ITM over OTM** (liquidity). **Entry trigger:** only when price **recovers back toward the OH** with momentum + **≥50K BN** volume on ~3 consecutive candles, indicators below price → probability climbs **70–80–90%**. **Never target above the OH** (OH 183 → target ≤182); the level reverses ~90% once hit. **Abort/exit:** an against-move **≥50K BN / ≥125K N** or a **>50% premium fall + >50% OI rise** = exit. **Both-sided OH (call AND put) = ignore entirely.**

---

## 3. Market Movers

> **In plain terms:** Find the stocks leading the day's move. A stock breaking out to a fresh 8–9 day high (or low) with the right open-interest backing tends to keep running, so you hop on for a quick 1–2%. A screener does the hunting across all F&O stocks; you just confirm the setup and ride the momentum.

**Common setup:** F&O **stocks** — trade cash/stock-futures, **no stock options**. OI Pulse → **Market Movers** (Mode = Live, Asset = All F&O / N50 / BN, Expiry = Current month). Longs from **Top Gainers**, shorts from **Top Losers**. Read each row: Min. B.O. Days, OH/OL, OI%, LTP%, OI Interpretation. Prefer high-volume/liquid names. After 9:45 (move may already be done → keep RR ~1%).

| Step | Bullish | Bearish |
|------|---------|---------|
| **Require all 3** | ≥**8D High** (9D better) **+ OL flag + LB** (SC ok) | ≥**8D Low** (9D better) **+ OH flag + SB** (LU ok) |
| RSI | RSI(5m) <75/80 & RSI(D) <75 (not already past 75) | RSI(5m) >25/20 & RSI(D) >25 (not below 40) |
| Entry | Pullback near VWMA/ST/VWAP; **long after price > VWAP**. Buy futures or cash stock | Pullback near VWMA/ST/VWAP; **short as price < VWAP**. Sell futures/stock |
| **SL** | No rigid SL (stocks are manipulable) — set by own risk; ref 1st candle low | Ref 1st candle high |
| Target | 1–2% (~1% typical) | 1–2% |
| Hold | Intraday default; positional only if EOD = LB (avoid LU) — watch EOD OI | Positional only with confirming EOD OI |

**Guardrails:** Highest conviction = OL in Top Gainers **and** OH in Top Losers simultaneously. If trade goes against you → volume high = exit, low = may pursue. Avoid names with OI liberal on both sides.
- **[S21]** The move usually happens **by 9:45 AM** and gives **~1% at the open** followed by a Supertrend support trade; prefer 8/9-day-high names with Open=Low + a positive Futures open, after reviewing the past 2–3 days' EOD data.

- **[S22]** **Large-cap only** — avoid small/mid-cap (operator-driven). Operator low-volume trap: a name that gives its whole move in the morning on no volume then ranges; stay long **only while above the intraday VWAP**. Moves can hit **5–10%** on some days; an 8/9-day-high name can **continue the next day**. Short side: a **15-day-low** name with fully bearish data is a strong STBT; carry overnight only when **Futures OI closing at day-high + price at day-low**. Daily-RSI screen **75 (bull) / 40 (bear)**.

- **[S24]** Trade the **FUTURES, not stock options** (illiquid stock options can fail to move even when the future moves) — the underlying must be **liquid**. Daily-RSI filter: **>70 at the open = no fresh long**, a dip to ~67–68 = the buy window, book ~80; a falling stock at RSI ~25–30 has historically bounced, so be careful shorting it.

---

## 4. Gap Theory

> **In plain terms:** Sometimes price jumps suddenly and leaves an empty "gap" on the chart. On the 3-min Bank Nifty chart these gaps almost always get filled the same or next day. So you wait for price to come back and fill the gap, and once that void is closed you trade in the direction of the bigger prevailing trend.

**Common setup:** 3m BN Fut chart (current month). Gap must be **>3 pts / 60 ticks** (high-prob = prev candle **HIGH→open** bull / prev **LOW→open** bear) and **not yet filled** by the current candle's body or wick. After 9:45.

| Step | Bullish (CE / long) | Bearish (PE / short) |
|------|---------------------|----------------------|
| Trend / gap | Trend up; gap created below price | Trend down; gap created above price |
| Core action | **WAIT for the gap to fill** (don't enter on the gap-up — risky) | **WAIT for the gap to fill** |
| Entry | The moment gap fills, take CE **in the direction of the overall trend**; prefer gap-filled area / pullback to VWMA-ST-VWAP or trendline support | PE the moment gap fills, in direction of overall down-trend |
| **SL** | ST (in-trend); or low of pre-gap candle; trail 5 pts below price | ST; or high of pre-gap candle; trail 5 pts above price |
| Target | Next R/S (~1:2.5 / 1:1.6 in examples); aim ≤1–2% | Next R/S; aim ≤1–2% |

**Risky variant (scalp only):** trade *toward* the gap on a rejection, **target = the gap level itself**; counter-trend SL = day high (short) / day low (long).

**Guardrails:** Runaway gaps / strong opposite momentum may **never** fill — don't force. On a gap-**up** (BN & N), look for **support/long, not shorts**. The fill logic is a 3m Futures phenomenon only — **does not apply** to daily gaps or option-price gaps.
- **[S21]** One-lot players must wait for the gap to **fully** fill before entering; multi-lot players may take small qty toward the gap and add near the Supertrend. Exit longs if price falls **below the Supertrend**.

- **[S22]** Confirms the baseline — no new execution rule (same 2023 examples; the predefined-order mechanics only restate "orders are brought back to fill, then the trend resumes").

- **[S24]** A gap trade is a **30–60-min play only** — wait 30–40 min; if it hasn't filled **on volume**, ignore it and trade with the trend; SL ~**50–60 pts** or a nearby S/R. **Validity hinges on volume direction:** an up-move *with* volume = a valid gap (bigger move likely on the return); if the **fall** carried the volume, the market can reverse fast, and runaway/momentum gaps may **never** fill. Don't chase a call merely to fill a gap into bearish texture — wait for the fill, let price tag resistance, then take the down-side (a no-volume fill is bearish-friendly).

---

## 5. Trending OI Crossover

> **In plain terms:** Open interest shows where the big money is committing. When one side — bulls or bears — starts giving up while the other piles in, their two OI lines cross over, and that crossover catches the exact moment momentum flips. You trade with the side that's now winning; the wider the gap that opens between the two lines after the cross, the bigger the expected move.

**Common setup:** Index (N/BN/Fin). Read **Trending OI graph on 5–15 min**, execute on 3m. Identify a cross: one side's OI falling/squaring while the other adds. **Confirm on BOTH:** (a) %OI change each side, (b) Sentiment-graph slope (up=bull/down=bear). Require **≥50% difference** between Call-OI change and Put-OI change. Volume ≥50K BN/125K N. Futures OI supports direction. After 9:45.

| Step | Bullish (long) | Bearish (short) |
|------|----------------|-----------------|
| OI condition | **Put OI ↑ & Call OI ↓** | **Call OI ↑ & Put OI ↓** |
| Cross | **PE line crosses ABOVE CE line**; Sentiment slopes **up** | **CE line crosses ABOVE PE line**; Sentiment slopes **down** |
| RSI (3m) | <75 | >25 |
| Confirm | Price rising, OI gap widening, volume ok | Price falling, OI gap widening, volume ok |
| Trade | Buy CE / Sell PE | Buy PE / Sell CE |
| **HIGH prob** | Put OI ↑ fast + Call OI falling faster + high volume + SC with drastic OI fall → one-way move | Call OI ↑ fast + Put OI falling faster + high volume |

**SL/Target:** Book at SL; on a **double/fake cross** (low vol + small OI change) exit and rotate to the side where the next true cross prints. Target ≤1–2%; positional ride while the OI gap keeps widening (trail next day once decent profit).

**Skip:** Failed cross (sellers write calls as price rises = cross never completes). Flat OI all day (50% diff but no real divergence = no move). End-of-series both sides covering = use next-series data. Small qty when RSI is cooling / on low-prob crosses.

**[S21]:** Want an **immediate ~20–30% OI divergence** at the cross (≥50% gap → conviction for the follow-on support trade; lines moving together = low-prob/range). Best window **10–11:30 AM**, avoid initiating after ~1:30–2 PM. Sentiment-slope is critical for big moves. On **monthly expiry confirm BOTH positional and intraday OI**. Reset Trending-OI strikes (close/reopen) to ATM ±7 once the move exceeds 1%.

- **[S22]** **VWAP is the decisive confirmation** — a cross with price stuck oscillating around VWAP is low-prob and traps both sides; the real move comes only after the OI gap widens **and** price holds the correct side of VWAP. **Failed-cross test:** if one side's OI reduces but the other side's OI does **not** increase, it's only short-covering (sellers re-add) — don't chase. Trail when RSI nears an extreme.

- **[S24]** Read Trending OI on **15 strikes (7+7+ATM)**; **intraday + positional must agree** (>50% gap on both for a big move; ideal bullish positional ≈ ~5 cr call vs 10–12 cr put). A crossover is **not required** when the gap is already very wide. **Don't turn bearish on a VWAP-volume break alone — require trending OI to also flip.** Fake-crossover: a **second crossover against you = exit immediately**; 2–3 crossovers in a day = sideways, avoid.

---

## 6. Golden Crossover

> **In plain terms:** A rare, high-quality signal where Supertrend and VWMA both punch through the VWAP line at the same time. When that happens with real volume behind it, price often makes a fast, clean run. Without volume it's a trap, so you only take the ones with conviction — it shows up just 3–4 times a month, and works better on Bank Nifty than Nifty.

**Common setup:** 3m BN Fut chart. The signal = **ST AND VWMA cross VWAP together** (both, same time). After 9:45. **Volume is mandatory** (≥50K BN / 125K N) — reject any low-volume or no-body crossover candle. Confirm **drastic change-in-OI on BOTH CE & PE** sides (Trending OI, 5/7 strikes around ATM). Rare: ~3–4×/month, stronger in BN than N.

| Step | Bullish | Bearish |
|------|---------|---------|
| Cross | ST + VWMA cross **ABOVE** VWAP (pierce from below), 3m | ST + VWMA cross **BELOW** VWAP (from above), 3m |
| RSI (3m) | <75 | >25 |
| Volume | ≥50K BN / 125K N (mandatory) | ≥50K BN / 125K N (mandatory) |
| OI | Drastic OI change both sides; bull build-up | Drastic OI change both sides; bear build-up |
| Trade | Buy CE / Sell PE | Buy PE / Sell CE |
| Target (pts) | BN ~100–150, N ~50–70 (vol-backed ~200); **[S21] clean cross ~200–300 BN (~200/side)** | BN ~100–150, N ~50–70 |

**SL:** **[S21]** Support-trade form = the **Supertrend level** (resolves the S20 "not stated" gap). Breakout form → structure (crossover-candle extreme / VWAP reclaim against the position) + Global Risk Framework.

**[S21] timing/entry:** Best/most-trending crossovers **10–11 AM**; enter the call side only once bulls take out VWAP; a short-covering move must clear **both** the Supertrend and VWAP.

**Skip:** No-volume / no-body / no-drastic-OI crossover = the retail trap (sideways or reverse) → look for the next support trade instead. On a low-volume bull cross, the 1st candle high acting as resistance = no trade. A **partial** cross *with* volume can still be traded in the move's direction.

- **[S22]** Confirms all (3–4×/month, BN 100–150 / N 50–70, ST+VWMA pierce VWAP, Trending OI 5/7 strikes). Deck states RSI bands **bull 50–75 / bear 40–25** and **VWMA(20)**. No new numeric SL (support-form SL = the Supertrend level still stands).

- **[S23]** Both lines must pierce VWAP *with volume* (one line / no-volume = not a golden crossover). Entry = the candle after the cross, **SL = the crossover / Supertrend level**; typical payoff **BN ~200–300 / N ~50–100 pts**; combine with a 2-candle setup for a stronger entry.

- **[S24]** Dip-buy shape: pyramid from Super Trend down toward VWAP — deploy **~20% at the ST zone, reserve ~80–90% for VWAP**, SL **~30–40 pts below** the defended zone. When VWAP, ST and PSAR **cluster together**, bulls can clear all three in one big-volume candle — not strong resistance, be careful. **No-trade zone** when ST breaks early and price ranges between ST and VWAP.

---

## 7. Hero-Zero (Expiry-Day OI)

> **In plain terms:** On expiry day, option sellers run the show and a cheap soon-to-expire option either explodes in value or dies at zero — "hero or zero." The trick is to work out where the sellers are trapped and being forced to exit, then sit on the opposite side with a cheap option that can multiply fast during the final-hour squeeze. Everything is closed out by 3:20 PM.

**Scope:** Expiry day only, **after ~2:30–2:45 PM [S21]** (S20: "after 2 PM" — S21 tightens the window so premiums settle and sellers commit). Index options, **buy side only** (cheap soon-to-expire option). Decide the side after **1:30–2 PM** by which side carries higher premium and where shorts are building; wait for the option to decay (e.g. puts to ~15–20). Prep: review ~4 round strikes around the ATM one day before (last ~2–4 days of OI). Confirm the side only when **BOTH price and OI change >50%** on the strike.

**Prep (prev evening):** Select 5 strikes either side of ATM (10 if high volatility), always include round strikes (e.g. 40000, 40500, 41000); analyse a week of data. Monthly/Fin Nifty: 2–3 strikes from prev close + round strikes, monthly-expiry perspective.

**Expiry-day timeline:**
1. **After ~2:30–2:45 PM [S21]** — find where highest OI/volume build-up sits = the range boundaries. **Put = support, Call = resistance** (max OI = where sellers expect price will NOT cross).
2. **2:30–3:00 PM** — watch where **short covering** happens around S/R (signals which seller is exiting / where price gets pushed).
3. **Confirm a real move:** BOTH OI **and** price move **>50% on the same side** (e.g. SB = OI ↑>50% **and** price ↓>50%). If OI rises but price doesn't follow → move won't continue. RSI not overbought (bull). Confirm underlying direction on 3m BN Fut.
4. **Fire (Bullish):** market closing toward day **high** + drastic SC fall in OI → buy a **CALL one strike BELOW** the strike where SC is happening. **Do NOT buy the already short-covered ultra-cheap (₹10–14) strike** — pick the adjacent strike.
5. **Fire (Bearish):** market closing toward day **low** → buy a **PUT one strike ABOVE** the SC strike. ⚠ **Do not take a PE when calls trade at a discount** — one up-move can dent capital; wait for an up-move to sell into.
6. **3:20 PM** — confirm your view matches OIP, then **hard close the position at 3:20 PM**.

**SL:** **50% of premium**, but mandatory close **3:20 PM** regardless.

**Skip:** Sellers pinning price at VWAP (only premium erosion). **Long unwinding on BOTH sides → sit out** (buyers can't profit). Ignore premium-adjustment fake up-moves on bearish data.

- **[S22]** **Resolves the numeric SL** — index-scaled point SL **BN ~75 / N ~30**, wider for Sensex/Bankex, on top of the 50%-premium / 3:20-PM close. Deploy only **~10% of profits**. On a serious short-covering squeeze the cheap option **doubles/triples in 30–45 pts** (BN can give a 100–200-pt move). Pick the 1–2-strikes-above-ATM (~20–50 priced) option **2:30–3 PM**; **exit by 3:10 PM if the move isn't happening.** Scale in parts — **largest qty at the lowest price** near support/VWAP. If direction is unclear, take **both sides with small qty.** Skip: **round-strike pin** (both CE & PE go to zero) and **IV flat on both sides** (only erosion).

- **[S23]** Prefer the **higher-value index** (Sensex/BN over Nifty): a 50-pt Nifty move ≈ **150–200 pts** on Sensex/BN, so a 100–200-pt Hero-Zero move is realistic on Sensex but not Nifty. Pick the slightly-OTM strike trading **~10–15**; stake only **~₹1–2k** (lottery odds — anything entered after 2:30 is effectively hero-or-zero).

- **[S24]** **Direction by 2nd-half flow:** if **call-side OI is rising fast while the other side covers, take the PUT side** (and vice-versa); favour the side trading at a **discount** / lower premium. Deploy only **~10% of profits**, never average a loser (set a level — let it go to zero if broken, trail if it rises). **On a monthly-expiry day, ignore the OI/expiry data** (it reflects the prior month, not the new series). Prefer low-premium strikes near expiry, but with high premiums prefer **ITM/deep-ITM**. **Bank Nifty caution** — with daily expiries gone, BN volumes dried up.

---

## 8. BTST / STBT

> **In plain terms:** Hold a trade overnight to catch a move that's still going. Late in the day, if the data strongly says the trend will continue, you buy near the close and sell the next morning (BTST), or sell short near the close and buy back next morning (STBT). It can magnify profits in a short time — but overnight gaps and news cut both ways, so it's a risky carry.

**Scope:** Overnight carry. **BTST = bullish long** carry; **STBT = bearish short** carry. Decision is end-of-day; exit next morning. Risky — only with proper data backing.

**EOD decision timeline:**
1. **Close + OI quadrant:**

   | Close | OI | → | Type · Quadrant [S21] |
   |-------|----|----|-----------------|
   | Day **High** | day low | → | **BTST** · SC = **Q3** (best after consolidation → LB) |
   | Day **High** | day high | → | **BTST** · LB = **Q1** (best to trade) |
   | Day **Low** | day high | → | **STBT** · SB = **Q2** |
   | Day **Low** | day low | → | **STBT** · LU = **Q4** |

2. **2:30–3:00 PM** — SC (BTST) / SB (STBT) forming around S/R.
3. **3:15 PM** — Futures OI **+** Option OI (Trending OI & Sentiment) **+** global cues all bullish (BTST) / bearish (STBT). RSI not overbought >75 (BTST) / oversold (STBT). *Example days: BTST RSI >60, STBT RSI <40.*
4. **3:20 PM** — your view matches OIP.
5. Last 30 min volume high and aligned.
6. **Stocks:** an SB stock breaking an **8D/9D low** = ideal STBT (mirror for BTST).

**Execute (near close, carry overnight):**
- **BTST:** Buy Fut / Sell PE / Buy CE. Stocks: buy fut or cash (no stock options; no delivery needed if sold next day).
- **STBT:** Sell Fut / Sell CE / Buy PE. Stocks: sell fut or sell stocks.
- Options: AI strike, ATM ±3, delta 0.6–0.7, premium N 100–250 / BN 250–400.

**Exit:** Next morning; trail once a decent profit is earned. SL **50%** (close-at-3:20 context). Keep the hold only if yesterday's 3:20 view + morning read + pre-market + global cues all align.

**[S21]:** Delivered via the AI **"320 Strategy"** — a probability signal at **3:20 PM** with a deliberately **wide** overnight SL; buy at the indicated price and exit when the SL is hit. Carry a buy side only if the strike is **closing near the day's high**; on long unwinding, do **not** carry calls overnight.

**Risk:** **≤1 night risk; avoid Friday** (weekend gap). Never carry a stock with **daily RSI >75**. No improper BTST near expiry against the trend (can wipe the position). STBT stock short = delivery/penalty risk on monthly expiry.

- **[S22]** **Stock overnight (STBT):** carry a short when Futures **OI closing at day-high + price at day-low**; a **15-day-low** name with fully bearish data qualifies; confirm the stock is **not already over-sold intraday**. **No BTST after a parabolic up-move** (expect a next-day reversal). Post-market: review the **Expiry-day analysis after 8:30 PM** + Dow close / Dow Futures / Asian markets before the open.

- **[S23]** Carry only when the day **closes near its HIGH** (BTST) with OI at/near day-high; **never on long unwinding or after a parabolic move.** Strike **~2 away from spot — never OTM overnight.** Favourable-gap SL: gap **>100% → SL ~80–90%**; gap **~70–80% → SL above 50% of prev close.** Index options/futures only, never cash stocks; risk 10–20% of capital, never 100%.

- **[S24]** Size the carry at only **~5–10% of capital**, exit early next morning. A **news-driven single-day rally after a multi-day fall is not trustworthy** — it needs 2–3 days of volume-backed follow-up. **Don't take a near-expiry BTST** just because price inches up in the last 30 min (if the morning high isn't reclaimed in the 2nd half, the carry is risky). After a big intraday hit, if you've **recovered ~80–90% of profit, square off rather than carry** (protect prev-day + ≥70–80% of intraday profit before fresh risk).

---

## 9. Morning Trade

> **In plain terms:** Catch the single sharp move that often happens right at the open. You do your homework the night before to form a view, then act on the very first candle when the price action — a rejection wick — confirms it. The whole trade can be over in two minutes. For experienced hands only, because the open is fast and volatile.

**Scope:** Opening-tick scalp (first candle[s]). Index CE/PE. **Experienced traders only.** Opening-tick timing governs (overrides the general "after 9:45" gate).

**Prep (prev EOD):** Form the view from Futures positioning (LB/SC/SB/LU), Trending OI, sentiment, FII/DII. Valid **only if no after-close news** invalidates it. **Market must have closed at the day's high or low** — an inside/near-open close = **no trade**. Confirm prior-day **3:20 PM OI-Pulse** (Fut OI + Option OI align) — the 3:15 PM Fut/Option-OI check is BTST's (§8).

**Open gates:** OIP AI = pre-market direction; global cues align; **Adv>32 = CE / Dec>32 = PE**.

| Step | Bullish (CE) | Bearish (PE) |
|------|--------------|--------------|
| Alignment | OIP AI + pre-market bullish; cues positive; Adv>32; prior-day 3:20 Fut+Option OI bullish | bearish; cues negative (e.g. Dow neg); Dec>32; OI bearish |
| Read open | 3m BN Fut; assess 1m candle + how 2nd candle breaks the 1st | same |
| Entry | **Rejection wick** — dip rejected, price turns up (attempt to reach prev close fails up) | **Rejection wick** on the failed attempt to reach prev close |
| RSI | Not >75; ≥60 supports CE (40–60 no-trade) | ≤40 supports PE; if **already oversold at open → do NOT chase the fall**, wait for cool-off back to resistance |
| Size | Small qty | Small qty |
| **SL** | 1st candle **LOW** | 1st candle **HIGH** |
| Target | Next resistance (a Fut level); book on a small rejection from the 1st 3m candle | Next support |

**Notes:** Whole trade can live inside the first 3m candle (e.g. 9:16→9:18, 250 pts). If the 2nd candle doesn't align immediately, wait for the 3m candle to form and trade its breakout. **Do not use VWAP before 10:30 AM** — rely on prev-close analysis + global cues only. After-close news invalidating EOD view → stand aside.

**[S21]:** AI **"Morning Trade" signal at 9:11 AM, mandatory exit by 9:18 AM**. Use the **previous day's VWAP** as the reference level (morning indicators are unreliable). **Deploy only a portion of profits**, never core capital. Take **every** signal but size to RR — normal lot when it aligns with the market, **reduced lot** when it opposes/neutral. A flat or two-sided open crashes premiums fast.

- **[S22]** Anchor to the **previous day's VWAP** (the defended level). **Profit-trail-to-buy-price SL** once it runs. **Open=High doubles as exit-trigger + opposite-side-OH hedge** (if price hits the opposite-side OH against you, exit; take the opposite-side OH as a hedge). Pick a **slight-ITM strike sized off the gap** (~100-pt open volatility + 30–40-pt buffer); **add only around the prev-day close.** Pre-open data after **9:07 AM**; need **>50% OI-change** for a convincing same-day view. When morning data is ambiguous, act off **previous-day closed data + today's open** and watch the data pan out.

- **[S24]** **Wait for the pre-market to settle (~9:07–9:08)**, ignore the initial ±200-pt swings, use the settled open to compute fair value + the premium range. Risk only **~10–20% of capital**, prev-day profit as the SL. **Read the gap:** on a big gap-down (~300–400 pts) do **not** buy a put (already oversold); prefer using a gap-up (~30–40 pts) to short **once**. **Check the heavyweights before shorting** — Reliance/Infy/HDFC Bank/TCS opening 2–4% can add ~80–100 N pts and trap a short. Strike **2–3 from the settle** (not deep OTM), slightly ITM in the morning; honour the SL, **don't average on adverse news**.

---

## 10. Options Scalping Framework (Connect the Dots)

> **In plain terms:** The master framework that ties everything together. Before firing a trade you "connect the dots" across every available signal — global cues, VIX, open interest, IV, and a 5-indicator chart — and only act when they all point the same way. VWAP is the single most important line on the chart. It's the disciplined, all-confirmations-aligned way to scalp index options.

**Common setup:** The core framework. Build the 3m chart's **5 dots**: VWAP · ST (10,2) · Volume (50K BN / 125K N) · RSI 14 (80:20) · PSAR (0.02, 0.2); add VWMA overlay. After 9:45. Read the macro dots: Dow 30 futures, India VIX, OI Spurts (4 quadrants), OI on strikes & futures, IV across 6 strikes. The **"Connecting the Dots" aggregate must read Bullish/Bearish** (60m sets the day bias; 3m times entry). *Intraday (non-scalp) variant: only ST changes to 15m/1h, 7,3.*

| Step | Bullish | Bearish |
|------|---------|---------|
| Candles | 2 GREEN ≥50K/125K; 2nd strong, indicators below | 2 RED ≥50K/125K; 2nd strong, indicators above |
| RSI (3m) | 50–75 zone, moving >50 (40–50 no-trade) | 40–25 zone, moving <50, keep >25 |
| Structure | No major resistance near | No major support enroute |
| Futures OI | LB or SC | SB or LU |
| Strike OI | LB/SC; Call OI ↓ / Put OI ↑ | SB/LU; Call OI ↑ / Put OI ↓ |
| Trending OI | Put crosses Call, gap widening, sentiment up | Call crosses Put, gap widening, sentiment down |
| VIX / breadth | VIX down; Adv>32; "Connecting Dots Bullish?" = YES | VIX up; Dec>32; "Connecting Dot Bearish?" = YES |
| Desirables | ST+VWMA cross above VWAP w/ volume; PSAR flips bull; S/R breakout; IV rising in strike | ST+VWMA cross below VWAP w/ volume; PSAR flips bear; IV falling |

**Exit / SL:**
- **SL:** 1st candle **LOW** (bull) / **HIGH** (bear); on gap entries trail **5 pts** below (long).
- **Target:** aim ≤1–2%; next R/S. (The "30–50 pts / exit ~5 pts below OH / above OL" target is **Open=High's** (§2) — applies only when scalping an O=H setup, not a native framework exit.)
- **RSI profit-booking (long):** 75–80 → book 90%; at **85 book last 10%**. (Short mirror: 25–20 → book 90%; at **15 book last 10%**.) Don't hold past 85 / below 15.
- **VWAP rule:** break **with volume** = trend reversing → exit. Break **without volume** = fake (returns into VWAP, opposite-side SLs get hit) → don't chase.

**IV (6 strikes) quick read:** 10/10 = low IV, good trend play · 30/20 = bullish on higher-IV side (≥10-pt diff + market that way) · 40/40 = stay away / short straddle.

**[S21]:** Connect-the-Dots is **intraday-only** — pick the TF by trader type (scalp vs intraday). On a **net-cautious** read of the dots: trade **low quantity, don't chase up-moves, enter only on a retrace to ST/VWAP**. Trade **OSPL (AI)** signals regularly, each confirmed with Trending OI + Futures data. Feeder dots now include the VIX regime bands, futures basis, constituent contribution and pre-open data (see §0B).

**[S22]:** **Deploy maximum quantity nearest VWAP**; a wide VWAP-to-candles gap = wait for a fresh entry. Need a **≥50% Call-vs-Put OI difference** for the market to trend on the heavier side. Prefer **ATM/ITM** for quick **10–20-pt** scalps; scalp hold ~**1–30 min**. **Profit-as-SL floor:** use the previous day's profit (or 10% of deployed capital) as the day's first SL. Concrete sizing for a ~1-lakh account: **≤₹25–30k deployed/trade.** New feature: **Trending OI + PA** overlays LTP change on OI change (LTP not moving while OI shifts = premium erosion, not a real move).

- **[S23]** **Out within 3–5 min (15 max).** Use the **previous day's VWAP until ~10:30 AM**, then intraday VWAP; no-trade zone between ST and VWAP / RSI 40–50; volume gate two bars >50K BN / >125K N. The genuinely-new S23 layer = trade **Sensex off the Nifty chart** (~80% stock overlap, Banking+IT overweight) via Sensex options not low-liquidity futures, ~3× point scaling (§0).

- **[S24]** **Volume confirms every break** — a VWAP/level break **without** volume can reverse in one candle (it points to the next support, not a real breakdown). **No-trade zone** = price boxed between ST/VWMA and VWAP → only **1–2 lots**, wait for a boundary break. A trending day prints a **fresh high roughly every hour** (~10:00/11:00/12:00 ±15); if new highs stop and price holds a ~30-pt range = erosion. **Book then re-enter the same strike lower**, using booked/prev-day profit as the next risk budget. Breadth: top 5–6 heavyweights moving together (+1–3%) confirm follow-through.

---

## 11. Straddle (Long & Short)

> **In plain terms:** You trade both a call and a put at the same at-the-money strike, betting on volatility rather than direction. A **long straddle** buys both legs and wins if the market makes a big move *either* way — only worth it when options are cheap (low IV), so an event with an unknown direction is the classic case. A **short straddle** sells both legs and pockets the time decay if the market stays in a range — but a sharp breakout can hand you unlimited losses.

**Common setup:** ATM, **same strike & same expiry**. Read the combined-premium **"straddle chart" against its own VWAP** (it times the entry and lets you hold only the winning leg). **Breakeven = the move must exceed the combined premium** (combined ~1000 → need a >1000-pt move).

| Row | **Long Straddle** (buy CE+PE) | **Short Straddle** (sell CE+PE) |
|-----|-------------------------------|----------------------------------|
| When | Event / big move expected, direction unclear | Range-bound day expected |
| IV | **LOW** (a high-IV long straddle loses both legs on an IV crash) | **Similar both sides**; Trending-OI change moving together |
| Entry | Combined straddle price breaks **ABOVE VWAP with volume** (event form: after ~12:30 PM once price closes above the VWAP of both legs) | BN **5-min**, after **9:30 AM**, once price falls **BELOW the VWAP of both legs** |
| Manage | Hold the winning leg, **drop the loser** once the combo clears VWAP | Hold while VWAP holds |
| Exit | **Lower-low candle** / when the combined premium peaks and rolls over (book, don't wait for full reversal) | **Premium decay** / EOD; exit if price breaks back through VWAP |
| **SL** | **BELOW VWAP** | **ABOVE VWAP** |

**Worked numbers:** market 54000, Call ATM 70 + Put ATM 50 = combined **130**; close 54000 → both zero (lose 130); close 54300 → Call 300 / Put 0 → **+170 pts**. *(Examples: long straddle 12 Sep'24 entered 2:10 PM → ~200 pts by EOD; short 54000 sold ~280 at 9:30 → +100 pts by 11 AM, VWAP never breached.)*

**Skip / guardrails:**
- **Long:** only at low IV/premiums; skip when premiums are already rich (required move too large).
- **Short:** **UNLIMITED risk** on a breakout — a hard SL above VWAP is mandatory; low-volume freak candles can hit the SL repeatedly (a 23700 straddle's SLs could have hit 4×); never run it as a directional gamble (it may be deployed pre-event only when no clear direction is expected).
- **Strike:** ATM by default; OTM for a safer bet (one leg becomes ITM for the other side); avoid deep OTM.
- **[S23]** On a low-premium Sensex expiry, do **not** short-straddle (a 200–300-pt move overruns the thin combined premium); long straddle = enter only in the **second half** once both legs are dirt-cheap (net cost-to-lose ~1–2). Sensex strike examples 81,500 / 82,000 / 83,000.

- **[S24]** The **combined (call+put) premium vs its VWAP is the directional gate** — once the combined premium drops **below** its VWAP into the close, straddle buyers lose. A **flat/erosion day** is a double-edged sword for buyers (premium bleeds both legs) and a win for sellers; the **~50% call-vs-put OI gap** (with a higher market) confirms buy-the-dip even without a 2-candle setup.

---

## 12. Trend Change

> **In plain terms:** Most setups ride a trend that's already running; this one catches the moment a trend *turns*. You first read the prevailing trend (up, down, or sideways), then wait for it to break — price stops making higher-highs and rolls over (or stops making lower-lows and turns up), the open-interest data flips to the new side, and momentum confirms. Catching the turn early can pay more than a breakout. The data (Trending OI) usually shows the shift before price does, but you still wait for volume and a 2-candle confirmation before entering. The live vehicle is Sensex options read off the Nifty chart.

**Common setup:** Identify the prior trend (trend lines + price-action swings; most setups start from a **sideways/range box**, e.g. Nifty 24,800 support / 24,900 resistance). The reversal needs **(a)** a swing-structure break or trendline break, **(b)** a Trending-OI momentum shift (a call/put OI **crossover**), and **(c)** volume + a **2-candle (3rd-candle) entry** per §1. The OI clue typically **leads price** (the ~11 AM crossover preceded the ~11:30 price confirm). **Momentum is mandatory** — no OI shift → it's a 5–10-pt fakeout → skip. Window **9:45 AM–2:30 PM**.

| Step | Bullish (up-reversal) | Bearish (down-reversal) |
|------|----------------------|-------------------------|
| Prior structure | Sideways / downtrend; price prints a **higher-low / higher-high** OR breaks the down-trendline up | Sideways / uptrend; price prints a **lower-high / lower-low** OR breaks the up-trendline down |
| OI shift | **Call OI ↓ + Put OI ↑** (call writers exiting, put writers adding; SC + put-adds) | **Call OI ↑ + Put OI ↓** (call writers building, put writers unwinding; e.g. Day 07 call 4→6.5 cr / put 4.4→3.92 cr) |
| RSI (3m) | **>60** and range/trendline broken | **<~40** and range/trendline broken |
| Confirm | Volume increase + follow-up bars (≥50K BN / 125K N) | Volume increase + follow-up bars; **avoid after ~2:30 PM** |
| Entry | 3rd candle after the break (§1); buy CE / sell PE / buy Fut | 3rd candle after the break; buy PE / sell CE / sell Fut |
| **SL** | Structure (1st-candle low per §1) + **VWAP** (exit if VWAP breaks *with* volume); ~10–20-pt benefit-of-doubt only with convincing OI | Structure (1st-candle high) + VWAP; same benefit-of-doubt rule |
| Target | Ride to VWAP / momentum exhaustion (examples ~400 pts); no fixed point target | same |

**Skip / guardrails:**
- **Both OI graphs climbing together = strict AVOID** (no directional edge); after ~2:30 PM both OIs falling = participants squaring off → don't chase.
- **Wait out morning prints** — a wrong naked entry can reverse in 2–3 candles (−50–70%); trade only once the intraday trend forms.
- Don't chase a direction when **premiums are higher on that side** and there are no positive cues (trap). Never enter on a **VWAP break without volume**.
- After a **vertical fall**, expect a severe bounce as RSI nears ~20–23 — wait for RSI to recover toward ~40 + a defined level before a reversal trade.
- A real reversal needs the **heavyweights** (Reliance / Infosys / TCS / banks) to support the new direction; news overrides data on gap/event days.
- No explicit numeric SL — size off structure & VWAP (§0).
- **[S24]** On an **intraday-bearish / positional-bullish divergence**, take a counter-trend (e.g. call) trade **only if the counter-move is NOT backed by >125K (N) volume** — a volume-backed counter-move signals the real reversal, an unbacked one fades.

---

*Source: derived entirely from [`Options_Scalper_Siva_Consolidated_Strategy.md`](Options_Scalper_Siva_Consolidated_Strategy.md) (Session 20 — Live Mentoring 2023, **Session 21 — Live Scalping Mentoring 2024**, **Session 22 — Live Mentoring Prog 2.0 2024**, **Session 23 — Sensex Scalping with Siva 2025**, and **Session 24 — Big 5 Anniversary Live Decoding 21 Days 2025**; **[S21]** / **[S22]** / **[S23]** / **[S24]** tags mark Session-21 / -22 / -23 / -24 additions/refinements; the Straddle (§11) is new in S22, Trend Change (§12) is new in S23; Session 24 adds no new strategy). No rules added. Where the consolidated doc marks an item UNCERTAIN, consult it before automating.*
