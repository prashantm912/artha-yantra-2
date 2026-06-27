# Options Scalper Siva — Consolidated Strategy (Session-24-Only Edition)


_This is the **OPERATIVE strategy authority**: the Session-24-only doc as base, plus the owner-ratified keep-rails woven in (each tagged **[keep-rail]**) and the drift/ruling resolutions applied per `docs/strategy-audit/RATIFICATION-PACK.md`. The multi-session OLD doc (`options-scalper-siva/`) is the historical **ARCHIVE**; the S24-only doc remains the clean single-session reference. The S24 base is built strictly from Session 24 (Big 5 Anniversary — Live Decoding 21 Days, 2025): the 21 daily live-decoding transcripts plus the decks in the S24 folder; earlier sessions (S20–S23) are excluded from the base and re-enter only as tagged keep-rails. Where S24 ships no deck and does not re-teach a strategy's base mechanics, the section is marked **reference-only**; Day-citations are kept as S24 provenance, and reused 2022–23 deck figures are marked _(illustrative deck example)_, not 2025 live data._


---


# 1. Introduction & Terminology

## 1.1 What this doc is

This is a single-session consolidation of **Session 24 — "Big 5 Anniversary — Live Decoding 21 Days" (2025)**, a 21-consecutive-day live-decoding programme in which Shiva re-decodes the established Options Scalper framework live, trade-by-trade, on a tracked ~₹25 lakh demo account (start ₹24.97 lakh → end ~₹28.63 lakh, net ~₹3.8 lakh over the 21 days, with ~80–90% of trades taken live). S24 **introduces NO new strategy** — it confirms and refines the existing 12-strategy framework; the only candidate, the Day-5 "Kingdom Trading Strategy" deck, is a chess-metaphor re-framing of existing rules (a teaching mnemonic), not a 13th strategy.

## 1.2 Current strategy roster (12 strategies)

S24's source material is the 21 daily live-decoding transcripts (no `Sivas 9s.xlsx`) plus three reused decks surfaced in the daily folders: "Kingdom Trading Strategy" (Day 5), an updated "How To Scalp Sensex Using Nifty Charts" (Day 4), and the "OI Expiry Strategy" deck (Day 9). Coverage below marks each strategy as **deck-taught** (re-taught/re-decoded via a deck or sustained daily decode in S24) vs **reference-only** (carried forward from earlier sessions, mentioned in passing but with no S24 deck and no re-teach).

| # | Strategy | Key | S24 coverage |
|---|----------|-----|--------------|
| 1 | Two Candle Theory | `two_candle` | Deck-taught (Kingdom deck Day 5 re-frames it; decoded Days 4–6, 12–14, 20) |
| 2 | Open=High / Open=Low | `open_high_low` | Deck-taught (substantially sharpened into a chasable system, Day 14; Days 6, 20) |
| 3 | Market Movers | `market_movers` | Deck-taught (decoded Day 10) |
| 4 | Gap Theory | `gap` | Deck-taught (decoded Days 2, 4, 6, 17, 21) |
| 5 | Trending OI Crossover | `trending_oi_crossover` | Deck-taught (decoded Days 1–2, 7–9, 11–15, 19–20) |
| 6 | Golden Crossover | `golden_crossover` | Deck-taught (decoded Days 4–6, 13) |
| 7 | Hero-Zero / Expiry OI | `hero_zero` | Deck-taught (OI Expiry Strategy deck Day 9; Days 10, 11, 17, 18, 19, 21) |
| 8 | BTST / STBT | `btst_stbt` | Deck-taught (decoded Days 2, 3, 8, 9, 18) |
| 9 | Morning / Opening Trade | `morning_trade` | Deck-taught (decoded Days 11, 12, 17, 20) |
| 10 | Options Scalping (Connect the Dots) | `scalping_framework` | Deck-taught (the core read, decoded throughout Days 2–21) |
| 11 | Straddle (Long & Short) | `straddle` | Reference-only (no S24 deck; appears only as a confirming parameter, Days 3, 5, 14, 17, 19) |
| 12 | Trend Change | `trend_change` | Reference-only (no S24 deck; logged only as a Day-21 refinement, never re-taught) |

## 1.3 Glossary — terms & indicators S24 actually uses

**Timeframes.** Scalp on the **3-min** chart (1-min avoided); 5-min for confirmation; **15-min / hourly** for positional reads. Intraday data window = **9:45–2:30** (post-2:30 = next-day positioning).

**VWAP (Volume-Weighted Average Price).** The session's central gravity and most important indicator after OI; the **final line of defence** for support/resistance. A break is only trusted with volume. [keep-rail] VWAP is also the hard stop / entry gate: enter only above VWAP, and use VWAP as the structural stop — a clean break of VWAP on volume = exit, not an averaging level. [keep-rail] VWAP-timing: before ~10:30 the live VWAP is still settling — suppress VWAP-based reads early and use the prior day's VWAP as the reference level until ~10:30.

**Super Trend (10,2).** Trend gate / **second line of defence**, on the 3/5-min chart. Used as the positional stop reference alongside VWAP. [keep-rail] Super Trend (7,3) on the higher timeframe (15-min / hourly) is the broad-trend bias gate — a separate read from the 3/5-min (10,2) trend gate; the higher-TF ST bias must agree before taking a fresh position.

**VWMA(20) (Volume-Weighted Moving Average, 20-period).** The **first line of defence** on a dip (also written WMA(20)).

**Parabolic SAR (0.02, 0.02, 0.2)** — start / increment / max; the S24 decks (2 Candle Theory, Kingdom) write this three-value form, while the live platform display shows it as "0.02 … 0.2". The first/earliest signal indicator; when VWAP, Super Trend and PSAR cluster, bulls can clear all three in one big-volume candle. [keep-rail] Volume-colour and PSAR-distance reads: candle volume-colour confirms conviction, and the distance of price from the Parabolic SAR dots gauges trend strength / room before a flip.

**RSI(14).** Momentum band filter. Zones: overbought 80 / oversold 20; 40–50 = no-trade; buy 50–75 (profit-book 75–80/85, no fresh longs >80–85); sell 40–25 (profit-book 25–20, avoid <20). Never short into overbought. [keep-rail] Daily-RSI cross-check: confirm the intraday RSI read against the daily-timeframe RSI (the ~75 overbought / ~25 oversold daily cross-check) before a fresh entry — multi-TF RSI must agree.

**Open Interest (OI) — four quadrants.** Read from price-vs-OI direction:
- **LB — Long Build-Up:** price ↑ + OI ↑ (bullish).
- **SC — Short Covering:** price ↑ + OI ↓ (bullish; shorts exiting).
- **SB — Short Build-Up:** price ↓ + OI ↑ (bearish).
- **LU — Long Unwinding:** price ↓ + OI ↓ (bearish; longs exiting; signature = OI↓ + price↓ + IV↓ together).

**Trending OI.** The criss-cross of call-OI vs put-OI lines that marks a trend shift; read on 15-min (best). S24 reads **15 strikes** (7 above ATM + 7 below + ATM); intraday and positional reads must both agree for a big move. [keep-rail] A wider call-vs-put OI gap = a stronger trend; an already-wide gap is itself the signal — a fresh crossover is not required to act.

**India VIX.** Nifty-50 volatility index (vs per-strike IV, which applies to any instrument). Rises only on serious futures short build-up; positional bands: 10–11 buy-on-dips, 12–14 add with caution, 15–18 cautious/unwind, 18–20+ portfolios unwind, 20–25+ market tanks. Low VIX (10–12) → ~90% second-half short-covering bounce. [keep-rail] IV-Crash / Historical-Vol awareness: a sudden IV crush (e.g. on expiry / post-event) collapses option premium even on a correct directional read — weigh historical vs current IV before buying premium.

**Advance/Decline breadth.** [keep-rail] A confirming dot fires when advancing-vs-declining breadth exceeds the threshold — **>32 on the CE side / >32 on the PE side** (the explicit engine number S24's breadth read drops).

**Kingdom chess mapping (teaching mnemonic — not a strategy).** The Day-5 "Kingdom Trading Strategy" deck maps chess pieces onto the existing indicator framework as a memory aid: **Queen = Open Interest, Rook = VWAP, Knight = Super Trend, Pawn = VWMA(20), Bishop = Parabolic SAR (0.02, 0.02, 0.2), Territory = RSI, Weapons = Volume, Battle = Candles, King = the trader.** Each piece is an indicator already in this doc; the metaphor adds no new mechanics.


---


# 2. Global Risk Management Framework

These rules govern every trade regardless of strategy. They are the non-negotiable guardrails of the Session 24 system; satisfy them before sizing any position.

## 2.1 Daily target and stop

- Aim for **1% per day**, then stop and log out (a ₹25 lakh account targets ₹25,000/day; a ₹5 lakh account targets ₹5,000/day) (Days 1, 2, 3, 8).
- The daily target scales with account size — roughly **0.5–1%/day at ₹5–10 lakh, ~2%/week at ₹2–5 Cr**, with occasional 3–4% days (Days 16, 21).
- Recycle realized profit as the next trade's budget rather than enlarging risk (Day 21).
- [keep-rail] Decide both targets — the per-trade target and the overall-day target — before the first trade of the day (r11).
- [keep-rail] Hold a minimum 1:2 risk-reward (e.g. risk ~0.5% to make ~1%).

## 2.2 Capital deployment caps

- Keep only **~5–10% of total finances in the markets** at any time (Days 1, 8, 9, 12, 16).
- Deploy **at most ~15–20% of capital** at once, with **risk capped at ~10% of the deployed amount** (Days 1, 8, 9, 12, 16).
- Never take loans to trade and be wary of MTF (margin trading facility) (Days 1, 8, 9, 12, 16).
- Diversify across instruments and asset classes (Days 1, 8, 9, 12, 16).
- **Split capital across multiple accounts** to lock in psychological profit and cap blow-up risk (Days 16, 17). Example: ~₹5 Cr split into 25/50/75 lakh + 1 Cr accounts, deploying only ~10–15 lakh (max ~25–27 lakh) so total exposure stays ~5–10% of finances (Day 16).
- [keep-rail] Across the split accounts, lock ~1% profit per account and rotate: stop an account after ~5 winning trades, and freeze it on its first loss of the day.

## 2.3 Loss caps

- **Single-day loss cap: never lose beyond 10–12% of capital in one day.** ~₹3 lakh on a ~₹25 lakh account is the recoverable bound; avoid 40–50% losses (Day 21).
- **Per-trade risk — conservative guideline: ~1–2% of total capital on a single trade** (Day 4).
- **Per-trade / single-trade hard cap ≤10%:** ~10% of the *deployed* amount (Day 8 risk-calculator framing) and never lose beyond ~10% on any single trade (Day 20). (The 1–2%-of-total-capital guideline is the tighter rule; ~10% is the outer cap.)
- A loss far above your appetite forces revenge trading — keep size within tolerance (Day 13).
- [keep-rail] Tighter owner option: a 0.5%-of-capital micro-stop applied across all accounts as the per-trade loss bound.
- [keep-rail] Capital preservation first: size so the account can survive a full bad quarter, and backtest any strategy over ≥1 year before deploying it.

## 2.4 Stop-loss sizing by instrument

- **Deep stop-losses are mandatory.** Shallow 5–10 pt SLs get hunted and taken out (Days 15, 19).
- **Nifty SL ~50–60 pts; Sensex SL ~200–250 pts** (Day 15); **Bank Nifty SL ~100 pts** (Day 19).
- [keep-rail] Index point-SL floors (kept alongside the deep-SL set above, not in place of it): Bank Nifty ~75 pts, Nifty ~30 pts. Do not conflate the two SL sets.
- Set a wide Sensex initial SL (~200 pts) and revise it as the trade moves in your favour (Day 1).
- [keep-rail] Place a hard stop-loss in the system on every trade — no mental stops. The SL must be a live bounding order before the position is held (r20).

## 2.5 Scaling in — geometric-lot pyramiding

- **Always scale entries; never go all-in** (Days 1, 2, 3, 8).
- **Pyramid in geometric lots (1 / 2 / 4 / 8, up to 16)** toward the strongest support (VWAP) so the heaviest size sits nearest the SL (Days 5, 8, 16, 20, 21).
- Averaging may also be done on the **upside** as a base forms (Days 5, 8, 16, 20, 21).
- Concentrate maximum averaging at the area of **maximum resistance/support** (Day 19).
- Increase quantity **gradually** — do not jump from 200 to 1,000–2,000 lots (Day 13).
- **Day-20 "planned scaling" reframe:** treat scaling as a pre-planned ladder (e.g. 26,130 → 26,110, a 20-pt window, 2+1+2 lots, max 5 lots), explicitly "not averaging" (Day 20).
- Once in profit, **keep quantity constant and trail tighter** rather than adding (Days 5, 8, 16, 20, 21).
- [keep-rail] Scale in toward a wide structural SL: add at roughly <5% / +5% / +10% steps from the entry; skip the trade entirely when the gap to VWAP is too wide to support averaging.

## 2.6 Never average below VWAP

- Once a fall **breaks VWAP, accept failure and exit** — averaging below VWAP wipes out 70–80% of capital (Day 21).

## 2.7 Confidence conditional on the trending-OI gap

- **Sizing confidence follows the trending-OI gap:** a wide gap = full size; a narrow gap = reduced size (Day 5).
- A fall **without volume** is low concern; a fall **with volume** (the first >50–60K drop) signals preparation to exit at the final SL (Day 5).

## 2.8 Volatility-based sizing

- **Low VIX/IV → large quantity, small point target (~4 pts).** In a low-vol narrow range, many small scalps (+3–4 / −2–3 rupees) beat chasing point-count (Days 3, 9).
- **High VIX/IV → small quantity (~100), larger point target (100–200 pts), or stay out** (Day 3).

## 2.9 Behavioural guardrails

- Book partials and trail stop-losses (Days 1, 2, 3, 8).
- Never revenge-trade (Days 1, 2, 3, 8).
- Never trade against the trend (Days 1, 2, 3, 8).
- Never contra-trade in options — opposite-side premiums melt, and RSI can keep dipping (20→9) while the call halves (Days 1, 2, 3, 8).
- **Overtrading is the killer** — quantities tend to double in the afternoon; book the morning profit and stop (Days 13, 16).
- **Newbies:** trade 1 lot, R:R 1:1 or 1:2, and scale up over 6 months to 1 year (Days 8, 14).
- Use **3-min candles; avoid the 1-min** (Day 8).
- [keep-rail] Keep winning-trade quantity equal to losing-trade quantity — never size up after a loss to win it back.
- [keep-rail] Journal every trade (entry, exit, reason, outcome) and review the log.
- [keep-rail] Once a trade is on, let it run to its stop-loss or its target — do not interfere or exit early on impulse.

## 2.10 Session-24 demo benchmark (2025)

- 21-day demo account: start **₹24.97 lakh → end ~₹28.63 lakh** (net ~₹3.8 lakh), with ~80–90% of trades taken live (Day 21).


---


# 3. Strategies (as covered in Session 24)


### 3.1 Two Candle Theory

**S24 Coverage:** deck-taught — full base mechanics carried in the reused "2 Candle Theory" deck (Day 4 folder) and re-framed in the "Kingdom Trading Strategy" deck (Day 5 folder); live-refined and exampled across the 21 daily decoding transcripts (Days 4, 5, 6, 12, 13, 14, 20).

**Mechanics (S24)**

Two Candle Theory is the scalper's core breakout play — "find one good strategy and keep refining it." It hunts breakout trades in **index futures and index options only** (Bank Nifty / Nifty); one or two clean trades a day are enough for consistency ("win battles, not the war") (deck Day 4; deck Day 5). All conditions are read on the **3-min timeframe** (the deck title says 3/5-min; S24 live work uses 3-min) (Day 4, Day 5; deck Day 5 restates the framework on 3-min).

The Day-5 "Kingdom" deck maps the indicator set onto chess pieces as a teaching mnemonic — Queen = Open Interest, Rook = VWAP, Knight = Super Trend (10,2), Pawn = VWMA(20), Bishop = Parabolic SAR (0.02, 0.02, 0.2), Territory = RSI, Weapons = Volume, Battle = Candles, King = the trader — but it is a re-framing of these same Two-Candle rules, not a separate strategy (Day 5; digest §B/§G).

**Long trade (buy Index Futures or Call Options).** With the index future trading **above VWAP**, check:
- Open Interest shows **Long Build-Up or Short Covering**.
- RSI **above 50, in the 50–75/80 band**.
- Volume of **2 consecutive bars** above **50K (Bank Nifty) / 125K (Nifty)**.
- All indicators (Parabolic SAR, VWMA, Super Trend, VWAP) sitting **below the candles**.
- Then **deploy on the 3rd candle**, with the **1st candle's low as the SL**. If the 1st candle low is hit, the fort is breached — exit (deck Day 4; deck Day 5).

**Short trade (buy Index Futures short or Put Options).** Mirror image, with the index future trading **below VWAP**:
- Open Interest shows **Short Build-Up or Long Unwinding**.
- RSI **below 40, in the 40–25/20 band**.
- Volume of **2 consecutive bars** above **50K (Bank Nifty) / 125K (Nifty)**.
- All indicators **above the candles**.
- **Deploy on the 3rd candle**, **1st candle's high as the SL**; if the high is hit, exit (deck Day 4; deck Day 5).

Trade only inside the intraday data window **9:45–2:30** (Days 4, 5, 6).

**S24 Refinements**

- **Overbought defer:** if the two qualifying candles form in an overbought zone (**RSI > 85**), do NOT enter on the 3rd candle — wait for RSI to cool to ~70–80 (~75) and enter on the red/pullback candle. If RSI is not yet overbought, enter on the 3rd candle as normal (Day 5). Even a "perfect" two-green-candle setup can be followed by a big-volume red 3rd candle — only take it after RSI cools (Day 20).
- **SL handling by trader type:** a scalper trails the **previous-candle high/low** as SL; a positional player keeps the original **1st-candle high/low** and can run the position all day (Day 5).
- **Deep-SL sizing:** a high-volume two-candle formation produces a deep 1st-candle SL — size the trade for that wider risk rather than assuming a tight stop (Day 4).
- [keep-rail] Strike cue: take a slightly-ITM strike (delta ~0.6-0.7), around ATM±3, premium ~100-250 (Nifty) / 250-400 (Sensex) band.
- [keep-rail] A light/small 2nd candle is acceptable if the 1st and 3rd candles together carry the volume and body — the 1st+3rd may substitute for a weak 2nd.
- [keep-rail] Sizing tie: a full-body 2nd candle combined with a >50% Trending-OI shift justifies a larger size on the setup.
- [keep-rail] Large 1st candle: when the 1st candle is large, set the SL at the 1st-candle high (short) or the 2nd-candle low (long) rather than the deep 1st-candle level.
- [keep-rail] Pull-back add / re-entry: on a pull-back to VWMA(20) or Super Trend that holds, add to or re-enter the position in the trend direction.
- [keep-rail] Bearish oversold skip: if RSI is already < 20 (oversold) do not initiate a fresh short on the 3rd candle — prefer a Super Trend-rejection entry instead.
- [keep-rail] Alternate SL: when price is extended far from the entry, use VWAP as the stop instead of the 1st-candle level.
- [keep-rail] Target / exit: aim for ~1-2%; trail with PSAR then Super Trend as the move matures, and exit on a VWAP break confirmed with volume.
- [keep-rail] Trail aggressively as RSI nears an extreme (toward overbought/oversold) — tighten the trail rather than holding for more.
- [keep-rail] Trade-zone read: prefer setups at established support/resistance and avoid entering on parabolic / already-extended moves.
- [keep-rail] Cross-check RSI on a higher timeframe (5m + Daily) — do not trade against the higher-TF RSI read.
- [keep-rail] Midday avoid: the ~11:00-13:00 window is typically choppy — avoid fresh setups there; clean two-candle formations tend to recur on roughly a ~45-min to ~1-hr cadence.
- [keep-rail] Sensex point-scaling: when the signal reads on NIFTY but execution is on SENSEX, scale the index point levels ~3x (signal-NIFTY / exec-SENSEX).

**2025 Live Examples**

- **Day 14:** a candle printed **130K volume around 10:20–10:30**; a second consecutive 130K bar would have confirmed the setup (the volume-confirmation step shown live).
- **Day 13 (recapping the prior session):** a signal fired **~2:27** (just before the 2:30 candle) and ran near the day's high. A breakout-trap played out on Sensex 82,000 expiry options — a candle high of **208 collapsed to 34 in ~3–5 candles**, then recovered to **205–210**: scalpers who hit-and-ran profited while holders were stopped out.
- **Day 12:** a "perfect two-candle" formed after the data turned bullish — entered calls on a dip near **VWAP (~320 mark)**, added on dips, and trailed.

Note: the deck's "Perfect Trade Setup" / "CrossOver" slides are **illustrative reused Bank Nifty index-futures charts** (date axes 10-Aug-21 and '25), not new 2025 live figures — do not read their levels as 2025 data.

---

### 3.2 Open = High / Open = Low (O=H / O=L)

**S24 Coverage:** Deck-taught — the full deck "Open & High Strategy - Index Options & Futures" was taught on Day 14, and the Day 14 live transcript sharpens it into a fully chasable system (digest §C open_high_low).

#### Mechanics (S24)

Open=High / Open=Low is a **purely intraday options-scalping** read for **index futures & options only** (stocks fall under Market Movers); it is discretionary and reflects the big players' game plan for the day (Day 14 deck).

- **Concept.** Open=High on a CALL strike means the option was bought right at its opening peak — a big financial institution that turned bullish deployed at the open (e.g. opening tranche split across futures + OTM/ATM/ITM CE + PE writing) and will use its resources to push the market back toward that opening high, so there is a high probability the premium revisits that high level once again. The mirror, Open=Low on the PUT side, is the bearish read (Day 14 deck).
- **Do NOT buy on sight.** Seeing Open=High on CE (or Open=Low on PE) is not itself an entry — you must time entry to when probability is high (Day 14 deck).
- **High-probability confirmation (CE long):**
  - At least **3 strikes above AND 3 below ATM** show Open=High, AND the **FUTURES** also show Open=High (Day 14 deck).
  - [keep-rail] Futures build-up must confirm: long build-up / short covering on the futures side, plus option-OI build-up on the identified strike, alongside the Open=High confluence.
  - **Open=Low present on the PUT side**, with the put price not increased more than 50% from previous close (Day 14 deck).
  - The identified CE strike must **not have fallen more than 50%** from its open (Day 14 deck).
  - **Avoid deep OTM / deep ITM** — liquidity and volume are the key (Day 14 deck).
- **F&O-data probability matrix (Day 14 deck):**
  - CALL Open=High + Futures Open=High + PUT Open=Low = **HIGH probability** (premiums revisit the high once again).
  - PUT Open=High + Futures Open=Low + CALL Open=Low = **HIGH probability** (bearish mirror).
  - Only a *few* CE Open=High with a *few* PE Open=Low (or the put-side mirror) = **MILD probability**.
  - CALL Open=High **and** PUT Open=High together = **MILD probability on both sides** (two opposing big players — effectively a no-trade, see Refinements).
- **Price/volume confirmation (Day 14 deck):**
  - Premiums of the identified strikes must **not fall more than 50%** from previous close.
  - Probability is higher in the **1st half**; avoid the 2nd half due to time value / premium erosion.
  - Even if price moves against the side, it should **not** do so on candles with volume **>50K**.
  - CALL Open=High that falls on **<50K** volume (or stays flat on flat volume) = probability **increases**; falls on **>50K** volume = probability **decreases**. **PUT Open=High** (rising premium) reads the mirror: a rise on **<50K** = probability increases, a rise on **>50K** = probability decreases (Day 14 deck, "subsequent-candle" table).
- **Entry (Day 14 deck).** Enter **only when there is momentum** — price rising with volume, RSI >50, chart breaking out. Look for small scalps. Worked example (illustrative deck example): O=H 300, current price 200; once price moves to 250 and the chart breaks out with volume, enter @250 and exit ~290 — i.e. enter on the recovery toward the O=H, never set a target above it.
- [keep-rail] RSI framework: buy band 50-75, overbought 80, oversold 20; no-trade 40-50; PE-side bearish trigger 40→25. Enter on momentum with RSI in the buy band; book as RSI nears overbought 80.
- **Strike choice / target (Day 14 deck).** Choose the strike whose premium is **nearest to its target**; always trail once in profit; the target IS the opening-high level. **NEVER place the target above the Open=High** (e.g. O=H 300 → exit below it). [keep-rail] Target sizing: small scalps of ~30-50 points toward the Open=High level (never set the target above the Open=High).
- [keep-rail] Buyer strike: pick ATM/slightly-ITM with delta >=0.7 (avoid deep OTM/deep ITM — liquidity is the gate).
- **Sizing (Day 14 deck).** Never deploy more than **30% of capital** on this trade — these are highly risky trades.
- **When to avoid / abort (Day 14 deck).** Analyse Open Interest: if the option premium **decreases by more than 50%** AND/OR the **change in OI for the identified strike increases by 50%**, avoid/exit the setup — it means a bigger player has taken the opposite view.
- [keep-rail] Stop: trail behind VWAP — exit if price closes back below VWAP after entry (VWAP-as-stop, the implemented SL).
- **Tooling (Day 14 deck) — our OIP probability.** The setup is flagged from ~9:16 AM; our **OH-probability model** (the deck's Table-1 FNO-footprint + Table-2 price/volume read, implemented as `OpenHighLow.tier`) grades it **LOW / MILD / HIGH ≈ 30 / 60 / 90%** success-probability (STAND-ASIDE / AVOID = no-trade). HIGH = high chance the premium reaches back to its high. This is our transparent rule-based replacement for the oipulse "AI probability" (no black-box; >90% = the strong/"badge" read).

#### S24 Refinements

The Day 14 live decoding tightens the deck into a chasable system (digest §C open_high_low; Days 14, 20, 6):

- **Scope:** Open=High is **index futures & options only**; stocks fall under Market Movers (Day 14).
- **Pairing:** Open=High on the CALL side normally pairs with Open=LOW on the PUT side (bullish big player); the mirror is bearish (Day 14).
- **Timing window:** data is visible only after the open (~9:16, not pre-market); **~90% of Open=High levels are hit in the first half** (by ~9:45/10:00/10:30), only ~20% in the second half and then only on a trend change. If the level is not hit by ~11:00–12:30, probability keeps falling — avoid late entries (Day 14).
- **Chase filter (CE), consolidated:** (1) ≥3 strikes above AND below ATM **plus FUTURES** all show Open=High; (2) **round strikes weigh more** than 50-pt strikes; (3) the strike must NOT have fallen >50% from open (a 20–30% fall is OK); (4) Open=Low present on the put side, **prefer ITM over OTM**; avoid deep OTM/ITM — liquidity is key (Day 14).
- **Entry trigger, consolidated:** enter only when price **recovers back toward the Open=High with momentum** plus **≥50K (Bank Nifty) volume on ~3 consecutive candles**, with indicators sitting below price; then probability is in the 70–80–90% band (Day 14).
- **Abort/exit, consolidated:** an against-move on **≥50K (BN) / ≥125K (Nifty)** volume, or a downside 2-candle, disregards the call-side Open=High; a **>50% premium fall together with a >50% OI rise (crossover)** = exit (Day 14).
- **Target discipline:** **never** place the target above the Open=High price (e.g. O=H 183 → target ≤182); once the level is hit it reverses strongly ~90% of the time — do not hold past it unless trend is confirmed (Day 14).
- **Both-sided Open=High (call AND put) = ignore entirely** — two opposing big players (Day 14).
- [keep-rail] An Open=High level that is not hit does NOT mean the creators are losing — they may simply be holding; do not flip the read on a single un-hit level.
- **Directional-change precondition (Day 20):** Open=High only triggers on a **confirmed directional change** (price below VWAP with volume). It will NOT work if the call-side >50%-OI-and-price and put-side >50%-fall quadrants are already fulfilled; for it to work the call-side fall must drop below 50% and the put-side fall must also be <50%.
- **Baseline confirmation (Day 6):** Open=High on an ATM/ITM call in the morning = bullish (bought at the opening peak), confirmed by ≥3 strikes above and 3 below also showing Open=High.
- [keep-rail] Sensex moves ~3x Nifty — size and target the Sensex leg off the Nifty read accordingly.
- [keep-rail] Advance/Decline breadth filter: require advances >32 (decliners <32) for the bullish side; the mirror for the bearish side.

#### 2025 Live Examples

- **Day 14 (Sensex):** ATM ~84,000; strike **83,900 Open=High 563.65** — candles approached (546.9 / 555 / 558) without clearing, fell to ~396, then the morning buyer pushed the index up to hit 83,900's Open=High at the ~10:45 candle for his exit; the strike then traded ~400.
- **Day 14 (Nifty, illiquid avoid):** Nifty **24,850 strike printed Open=High ~450 at 9:45 but had ~0 volume all day** — ignore illiquid strikes (liquidity is the key gate).
- **Day 14 (Nifty futures, overridden):** Nifty futures **Open=High 25,890/25,890**; the morning CE fell only ~15–20% (<50%, theoretically chaseable) but the **positional data was bearish and overrode** the Open=High setup.
- **Day 6 (Nifty, not chased):** **25,000 CE** opening trade 166 (open=high), fell to 110, trading 136 — **not chased** (no golden crossover, unfavorable OI); would wait for a move back above ~150.
- **Day 14 deck worked example (illustrative deck example, not 2025 live):** O=H 300, current 200; enter @250 on a volume breakout, exit ~290.

---

### 3.3 Market Movers

**S24 Coverage:** deck-taught — full teaching deck present (Day 10 "Market Movers Strategy") plus live refinements and a 2025 example in the Day-10 transcript.

**Mechanics (S24):**
- **What it is:** Market Movers is a feature in OI Pulse that segregates the day's top gainers and losers in F&O stocks (filterable to Nifty 50 and Nifty Bank). For each script it shows: script name, LTP, LTP change, OI% change, OH/OL (Open=High / Open=Low), OI interpretation, and — most importantly — the minimum number of breakout days. It has two sections: Top Gainers and Top Losers for the day (Day 10 deck).
- **Core trigger:** whenever a stock breaks an **8- or 9-day high**, look for **1–2% returns** on it. The Top Gainers section signals which stocks are long (change in OI%, change in LTP%, OH/OL); compare against the chart to form a view to go long. Favor **high-volume stocks** for liquidity (clean entry/exit). The short-build-up category gives candidates to go short; live movement is shown to the right of the tool (Day 10 deck).
- **High-probability LONG (3 aspects aligned):** minimum days ≥ **8 days** (stock already at an 8-day high), **Open=Low** indication on the stock (the day's low is not being tested = strength), and **long build-up** in OI interpretation (bulls in charge → probability of a 1% trade is higher) (Day 10 deck). Digest restates the long setup as: min **8–9-day high** breakout + Open=Low + **long build-up / short covering**, stock up **3–4%+** → expect a further **1–2%**.
- **High-probability SHORT (mirror):** minimum days ≥ 8 (stock at an 8-day low), **Open=High** indication (the day's high is not being tested), and **short build-up** in OI interpretation (bears in charge) (Day 10 deck). Digest adds: an **8-day low breakdown + Open=High not tested + long unwinding / short build-up**; a **>15% fall / 15-day low with Open=High is a strong short** (Day 10).
- **Confirmation read per candidate** (deck examples list these fields): Futures build-up type, RSI, OI interpretation, Price (vs VWAP/ST/WMA), Volume, and trade execution = **long entry after price moves above VWAP** (Day 10 deck).

**S24 Refinements:**
- **Trade FUTURES, not stock options** — strict no on stock options. Stock options can fail to move even when the spot/future moves (low IV / illiquid). The underlying must be **liquid** (Day 10).
- **RSI filter:** if the daily RSI is **>70 by the open → no fresh long**; a dip to ~**67–68** is the buy window; book profit around **~80**. For shorts, a falling stock at RSI ~**25–30** (oversold) has historically recovered — be careful (Day 10).
- **Exit / manage:** book profit as RSI approaches **~80**; if the stock then falls, treat **VWAP** as the level to take a final trade / average / close (Day 10).
- [keep-rail] Large-cap / index-constituent names only — Market Movers is read on liquid large-caps (Nifty 50 / Nifty Bank constituents), not thin mid/small-caps.
- [keep-rail] Operator low-volume trap: a name can show a big LTP/OI move on thin volume because an operator is moving it — discount low-volume movers even when they top the list (volume is the trap-filter, not the headline move).
- [keep-rail] STBT carry read: a stock holding OI-at-high with price-at-low into the close is an STBT (short-tomorrow) overnight candidate — note it for the next session.
- [keep-rail] Alternative entry: a clean break above the prior day's high (a >1% move) is itself a valid long trigger even before the full 8-9-day-high three-aspect alignment.
- [keep-rail] Radar staging: watch a name as it climbs the breakout ladder — 1-2-day high → 3-4-day high → 8-9-day high; the 8-9-day stage is the trade trigger, the earlier stages are early-warning only.
- [keep-rail] Both-sides-OI = avoid: if a candidate shows heavy OI building on BOTH call and put sides (no clear directional dominance), skip it — there is no edge.
- [keep-rail] Adverse-volume exit: exit if volume turns against the position (rising volume on the counter-move), even before VWAP/SL is hit.
- [keep-rail] Dual-name cue: an Open=Low name in Top Gainers AND an Open=High name in Top Losers, read together, is the higher-conviction long/short pair cue.
- [keep-rail] Pre-trade prep: review the past 2-3 days' EOD reads on the candidate before entering, to confirm the breakout/OI-build is genuine and not a one-session spike.
- [keep-rail] Indicator settings / Desirables: confirm price vs VWAP, Super Trend and WMA with standard settings; the desirable candidate has volume rising, OI build-up aligned with direction, and price holding above (long) / below (short) all three.
- [keep-rail] Time-of-day floor: take Market Movers entries only after ~09:45 — the breakout/OH-OL read needs the opening range to settle first.

**2025 Live Examples:**
- **Day 10 — Fortis (long, Open=Low setup):** Open=Low 986.90, climbed 1012 → 1050/1055 (a move of >1–2%).
- **Day 10 — liquidity reads:** Maxhealth ~150K/120K treated as "no volume" (avoid); BSE ~50–60; PayTM ~100K = at least liquid.
- **Deck examples (illustrative — reused 2022–23 deck figures, NOT new 2025 data):** ADANIENT (5–6 Dec 2022): long build-up after initial short build-up, RSI >60, price moving up, volume increasing, long entry after price above VWAP — high probability (8-day high, long OI, Open=Low). ZEEL (2 Jan '23): short covering, RSI >60, price above VWAP/ST/WMA, high probability intraday (8-day high, short-covering OI). ABCAPITAL (2 Jan '23): long build-up, RSI >60, price above VWAP/ST/WMA, high probability intraday (8-day high, long build-up).

---

### 3.4 Gap Theory

**S24 Coverage:** Deck-taught — full teaching deck present (`Gap Theory.txt`, Day 6) and live-decoded across multiple daily sessions (Days 2, 3, 4, 6, 17, 21).

#### Mechanics (S24)

Gap Theory trades the small price gap that forms on the **3-minute timeframe** in Nifty / Bank Nifty (Day 6 deck names Bank Nifty; the rule applies to Nifty/Bank Nifty per the live transcripts).

- **What a gap is:** a gap is created when many orders are punched in but are not filled because price moved higher or lower too quickly. The players who punched those unfilled orders bring the market back to fill them; once the gap is filled, the market resumes its short-term direction (Day 6 deck).
- **Fill probability:** the gap is filled ~90% of the time in the immediate next few candles (deck); in the live session this is stated more strongly — "99% of the time, whatever gap gets created on a 3-minute time frame, either it gets filled on the same day or the next following day" (Day 6). If it is not filled the same day, players come back to fill it the following morning or afternoon; if it is *still* not filled, discount it — a bigger player is overriding the one trying to fill it (Day 6).
- **When gaps do NOT fill:** runaway / momentum gaps where the move keeps going one direction and price never comes back (e.g. everybody wants to buy, so it cannot be dragged back) (Day 6 deck + transcript).
- **Gaps as magnets:** an unfilled gap acts as a magnet / a level the market is pulled toward to fill (Day 4).
- **Ignore unfilled gaps:** if a gap stays unfilled past 1–2 days, discount it (Day 2).
- **How to trade (long example):** in an uptrend on the 3-min timeframe, if a gap is created below, wait for price to come back and quickly fill the gap; once filled, place buy orders in the same direction as the trend (Day 6 deck).
- **Entry:** the gap-filled area is the entry level; if a VWAP or Super Trend sits nearby, use that as the entry/best-bet level. While the stop (the gap candle's low) is not triggered, keep adding positions as price comes lower and lower, then replenish as it moves back up (Day 6).
- [keep-rail] VWMA20 is the named volume-weighted moving average used alongside VWAP / Super Trend as the entry / best-bet level near the gap fill.
- **Stop-loss:** the low of the candle from which the gap formed is the stop-loss territory (Day 6 deck + transcript).
- [keep-rail] Trail the stop by ~5 points behind price once the trade moves in favour (engine StructuralStop.GAP_TREND 5-pt trail), keeping the pre-gap-candle low as the structural floor.
- **Exit:** exit when RSI reaches ~70–75–80, or on the previous-candle-low stop being triggered (Day 6).
- [keep-rail] Significance floor: a gap counts only if it spans ~3 points / ~60 ticks; smaller gaps are noise (engine MIN_POINTS=3).
- [keep-rail] Read the gap on the index-FUTURE 3-minute chart (the gap-detection series is the index future, not the spot/option).
- [keep-rail] Strike cue: trade a slightly-ITM option (delta ~0.6–0.7) at a tradeable premium.

#### S24 Refinements

- **Volume direction validates the gap (Day 6):** an up-move made *with* volume = a valid gap, and a bigger move is likely on the return to fill it; but if the up-candle came yet the *fall* carried the volume, the market can reverse fast. Runaway / strong-momentum gaps may never fill.
- **Don't chase a call just to fill a gap when the texture is bearish (Day 17):** wait for the gap to fill, let price tag resistance, then take the down-side trade. A gap fill *without* volume is bearish-friendly; a fill *with* rising volume warns of an upside reversal. The unfilled gap is a level the holder defends.
- **Gap trade is a 30–60 min play only (Day 21):** wait ~30–40 min; if the gap is not filled with volume, ignore it and trade with the trend instead. A strong ~200-point rally makes a same-day fill unlikely. Use a SL of ~50–60 points from entry, or a nearby support/resistance level.
- [keep-rail] Exclude a gap when a higher-timeframe trend overrides it, or when it is an option-side gap with no fill — these do not fill and are not traded.
- [keep-rail] Breadth confluence: require advancing/declining breadth >32 on the CE side (and >32 on the PE side) as a confluence dot (engine ScalperGates.breadth).

#### 2025 Live Examples

- **Day 3:** a ~13-point Nifty gap (high 24,806.60 → next open 24,819) did NOT fill — read as a large unfilled buy order with players still active.
- **Day 4:** the prior day's post-2:30 false breakout fell ~100 points, creating a 3-min gap that filled the next morning.
- **Day 11:** low 25,217 = high 25,217 → no gap, confirming the predicted support held.

*Deck trade examples are reused 2022–23 figures (illustrative deck examples, not new 2025 data):* Day 6 deck Example 1 — 9 Jan 2023 Bank Nifty (gap created 09:30, filled 09:48; CE entry 42536 / SL 42431 Super Trend / target 42700 at 1:2.5); Example 2 — 31 Jan 2023 Bank Nifty (15.05-pt gap created 12:51, filled 13:03; CE entry 40883 trendline support / aggressive SL 40803 / target 41024).

---

### 3.5 Trending OI Crossover

**S24 Coverage:** reference-only — S24 ships NO dedicated deck for this strategy; all content comes from the live-decoding transcripts (re-taught/refined live, principally Day 7), not from a teaching slide deck.

#### Mechanics (S24)

Trending OI Crossover trades the **crisscross of the call-OI line versus the put-OI line** on the option chain as a trend-shift signal (Day 7):

- **The signal:** a crossover (crisscross) of the two OI lines indicates a shift in direction. As long as both lines travel together, there is **no trade** — wait for the crossover to print before acting (Day 7).
- **LONG setup:** put OI rising + call OI falling, with the **PE line crossing above the CE line**; enter on the candle following the crossover (Day 7).
- **SHORT setup:** call OI rising + put OI falling, with the **CE line crossing above the PE line**; enter on the following candle (Day 7).
- **Timeframe:** read on the 5-minute or **15-minute chart (15-minute is best)** for this signal (Day 7).
- **Operating window:** the crossover read is valid only **9:40–2:30**; ignore any crossover before ~9:30/9:40 and any after ~2:30/2:40 (Day 7).
- **Stop-loss:** scalper uses the **high/low of the crossover candle** as the SL; a positional player uses a nearby Super Trend / VWAP level instead (Day 7).
- **RSI confirmation:** after a long crossover, RSI should read **50–75**; for a put buy after a short crossover, RSI **40–25** (below 20 = oversold — wait for a bounce) (Day 7).
- **Volume:** a genuine crossover is accompanied by a volume spike roughly **90% of the time** (Day 7).

#### S24 Refinements

- **15-strike read (Day 2):** the Trending OI dashboard tracks **15 strikes — 7 above ATM + ATM + 7 below**; configurations of 5 / 9 / 11 strikes were tested and 15 gave the best reliability. (This is the trending-OI-specific count; the golden-crossover companion read uses 5–7 strikes either side, Day 6 — a context difference, not a conflict.)
- **Intraday and positional must both agree (Days 1, 2, 9, 11, 14, 19):** intraday OI = today only (scalpers); positional OI = yesterday + today (swing, held 2–3 days max). For a **big move**, want a **>50% gap** between call and put OI on BOTH intraday and positional; a near-even positional read = muted move. Extreme-bullish requires both positional AND intraday bullish; if positional is bullish but intraday is turning bearish, treat it as only minor-bullish — wait and watch. An ideal bullish positional read is roughly **5cr call vs 10–12cr put** (put writing exceeding call writing).
- **Gap-quality gate (Day 7):** need a **≥50% (ideally 50–100%) call-vs-put OI gap that is widening** for the crossover to be trade-worthy.
- [keep-rail] Gap-quality is measured on the CHANGE-in-OI imbalance, not just the static OI level: need a ≥50% imbalance on ΔOI (call-vs-put change-in-OI), with a 20–30% change as a weaker sub-signal, for the crossover to be trade-worthy. The engine measures ΔOI; the prose "gap" above is the loose form of this same gate.
- **Crossover not always required (Day 8):** when the call-vs-put gap is already very wide, the existing divergence is itself the signal — no crossover needed (Day 8 was a bearish **sell-on-rise** day: ~7cr call vs ~1.5cr put intraday, ~9cr vs ~3cr positional). Watch the lagging side's OI build (e.g. put OI climbing ~**1.2 → 1.5 → 2cr**) together with a fresh crossover as the cue for a possible reversal. Trade with the **dominant** side only while trending OI stays in that direction.
- **Flipping direction requires confirmation (Days 5, 11, 12):** to flip short you need **VWAP broken AND trending OI changed direction**; do not turn bearish on a VWAP/volume break alone — trending OI must also turn bearish.
- **Fake-crossover handling (Day 7):** a second crossover against your position = **exit immediately** (it is the trap); never average a position without watching the crossover. **2–3 crossovers in a single day = totally sideways — avoid the day.**
- **Trending-down expiry read (Day 15):** for a clear down day, call-side OI should far exceed put-side (~**11–12cr call vs 4–5cr put**); a ~60–40 ratio is weak. Don't panic while the gap stays >40–50%; only worry when it narrows (e.g. 13cr vs 8–10cr).
- **OI-sentiment color code (Day 15):** green = bullish (call OI reducing + put OI rising); red = bearish. Both intraday and positional turning together is the stronger confirmation.
- **New-series / contradiction discipline (Day 20):** confidence builders = start of a new monthly series + no major negative news + supportive global markets. Do **not** take an aggressive trade on contradictory signals (e.g. price above all indicators but OI bearish = no long).
- [keep-rail] Volume confirmation thresholds: a genuine crossover's volume spike means Bank Nifty ≥ 50K / Nifty ≥ 125K on the break candle.
- [keep-rail] Target: book a scalp move of roughly 1-2% on the option leg.
- [keep-rail] RSI-extreme trailing exit: once in a long, an RSI reading into the overbought extreme (≥80) is a trailing-exit / book cue (mirror on the short side near oversold 20).
- [keep-rail] VWAP-decisive: probability of a successful entry is low when price is sitting right at/near VWAP — VWAP is the decisive line, prefer entries with clear distance from it.
- [keep-rail] Failed-crossover test: a real (non-fake) crossover shows two opposite-signed ΔOI deltas — one line's OI building while the other's falls; if both ΔOI deltas point the same way the cross is fake/sideways noise.
- [keep-rail] Futures-OI quadrant co-confirmation: confirm the crossover direction against the futures-OI quadrant — Long Build-up (LB) supports longs, Short Cover (SC) / Short Build-up supports the matching side.
- [keep-rail] OI-sentiment-slope co-confirmation: read the slope/direction of the OI-sentiment line (not just its colour) alongside the crossover — a turning sentiment slope agreeing with the cross is the stronger confirm.
- [keep-rail] Probability-graded sizing: size the position by signal quality — full size only when all confirms (gap ≥50% & widening, volume, RSI, both intraday+positional agree) line up; trim size when confluence is partial.
- [keep-rail] HIGH-probability strength grade: a drastic OI fall on one side + a fresh opposite-side build + a Short-Cover (SC) confirmation together grades the setup HIGH-probability (the strongest trending-OI read).
- [keep-rail] Price-corroboration precondition: take the crossover only when price action corroborates the OI read; do not fire on the OI cross alone if price is contradicting it.
- [keep-rail] Flat-OI caveat: when OI is flat / not changing on both sides (no ΔOI imbalance), there is no trending-OI signal — degrade to no-trade and wait for the ΔOI to build (engine inverts the ≥50% gate here).

#### 2025 Live Examples

- **Day 7 — ideal trending/crossover day (16 Sep 2025):** at 9:30 (30-min read) call OI 2.67 / 5.32 vs put 1.8 / 10.2, a ~50% gap that held all day; Nifty made a new high almost every hour, **held the trending OI until ~1:30** (broke it then but never returned to VWAP), and never let price drop below Super Trend / touch VWAP. The same Day-7 Sensex-expiry read was sideways by contrast — 2.5cr call / 2.68cr put (combined ~2.95–3.01cr), no differentiation, so no trade.
- **Day 1:** at ~12:45 the positional read flipped bullish (both intraday and positional bullish); call OI moved to 4.3cr and put OI to 8.9cr from the morning's 2.9 / 3.1.
- **Day 20:** data flipped bullish — call OI ~50–60 lakhs with the put side rising; trending OI **~2.92cr call vs ~6.77cr put** (calls then moving ~100% / puts falling >50% on the option chain).
- **Day 13:** a massive change-in-OI skewed to puts (calls 5 lakh → 11cr) = strong bullish — any dip a buy.

---

### 3.6 Golden Crossover

**S24 Coverage:** deck-taught — the dedicated deck "How To Trade Using Golden Crossover" is present in the Day 6 folder (the base-mechanics deck is a reused 2022-23 deck whose trade-example figures are illustrative, not new 2025 data), and S24 layers live refinements on it (Days 4, 5, 6).

#### Mechanics (S24)

The Golden Crossover fires when **both VWMA and Super Trend (ST) pierce VWAP together in the same candle** on a bullish or bearish move, with supporting parameters in place (Day 6 deck). It is a rare, high-conviction signal — S24 puts it at roughly **3–5 times per month** (Day 6).

Required parameters (Day 6 deck):
- **Crossover:** ST and VWMA pierce VWAP together (same candle).
- **Trending OI:** of 5/7 strikes above & below ATM (the golden-crossover companion read uses 5–7 strikes either side — Day 6).
- **Timeframe:** 3-min (the deck states "Time From: 3 mins").

**Long conditions** (Day 6 deck):
- Crossover: ST & VWMA pierce VWAP together.
- RSI: above 50 to 75 (in 3 min).
- Volume: Bank Nifty ≥ 50K, Nifty ≥ 125K.
- Trade: Buy CE or Sell PE.
- Enter on the 2nd / next candle after the crossover (Day 6).

**Short conditions** (mirror — Day 6 deck):
- Crossover: ST & VWMA pierce VWAP together.
- RSI: between 40 to 25 (in 3 min).
- Volume: Bank Nifty ≥ 50K, Nifty ≥ 125K.
- Trade: Buy PE or Sell CE.

**Stop-loss:** the crossover candle's level (Day 6).

**Indicator hierarchy (Day 4):** VWAP is the most important indicator after OI; Super Trend is set (10, 2) on the 3/5-min chart; the defence ladder runs VWMA(20) as the first line of defence, Super Trend as the second, and VWAP as the final line of defence.

- [keep-rail] **Higher-TF trend bias (Day 4 / engine hard gate):** confirm the broad trend with **Super Trend (7,3) on the 15-min / 1-hour** before taking the 3-min crossover — only fire crossovers that align with the higher-timeframe ST(7,3) bias.

#### S24 Refinements

- **Expected follow-through after a crossover:** roughly **+200–300 points on Bank Nifty** and **+50–100 points on Nifty** (Day 6).
- **Clustered indicators warning:** when VWAP, Super Trend and Parabolic SAR cluster together, bulls can clear all three in a single candle on big volume — so the cluster is *not* strong resistance; be careful treating it as such (Day 6).
- **Dip-buy / pyramiding shape** (a general support-zone technique Siva demonstrated on a Day-5 Super-Trend-support trade he noted was *not itself* a Golden Crossover; it applies to crossover dips): pyramid from Super Trend down toward VWAP; place the SL ~30–40 points below the defended territory; deploy ~20% at the Super Trend zone and reserve ~80–90% for the VWAP zone (Day 5).
- **No-trade zone:** when Super Trend breaks in the morning and price then trades *between* Super Trend and VWAP, that is a range/erosion zone — avoid it (Days 5, 13).
- [keep-rail] **Two-sided OI confirm:** confirm the crossover with a drastic two-sided change-in-OI on the 5–7 strikes either side of ATM (the companion Trending-OI read should shift hard in the crossover's direction, not be flat).
- [keep-rail] **Directional OI build-up read:** the crossover side must be backed by the matching OI build-up — a long build-up (bulls in charge) under a bullish crossover, a short build-up (bears in charge) under a bearish one.
- [keep-rail] **No-body / partial-crossover volume nuance:** a no-body or only-partial pierce of VWAP needs strong volume behind it to count as a valid crossover; a thin-volume partial crossover is not a fire.

#### 2025 Live Examples

- S24 surfaced **no new 2025 live Golden Crossover trade**. The Day 6 deck examples are reused 2022-23 deck figures: a bearish crossover example (OI 60–66 → 72/36, ~50% gap) and a 23-Aug Nifty long (12:48, OI 90 → 60/70) (illustrative deck examples — explicitly flagged as not new 2025 live data) (Day 6).
- The deck's other tabled trade examples are likewise 2022 figures: 16-Sep-22 short (13:09, bearish, RSI 27, volume 50K), 23-Aug-22 long (12:48, bullish, RSI 70), 26-Sep-22 long (12:39, bullish, RSI 69), 8-Aug-22 long (10:15, bullish, RSI 58) (illustrative deck examples).

---

### 3.7 Hero-Zero (Expiry-Day OI Strategy)

**S24 Coverage:** deck-taught — two reused/teaching decks surfaced in S24 ("OI Expiry Strategy 10th Mentoring", Day 9; "How To Identify Hero Or Zero — Expiry Day", Day 10) plus heavy live refinement across the daily transcripts (Days 9, 10, 11, 17, 18, 19, 20, 21).

#### Mechanics (S24)

Hero-Zero is an **expiry-day-only** play. It is framed not as a blind hero-or-zero gamble but, in the deck's own words, as *"a strategy to identify where a buyer and a seller exist"* — you sit as a buyer on the side the writers are about to vacate (Day 9 deck). The move is driven by **writers covering in the last 30–40 minutes** of the session (short covering / long unwinding), executed roughly **2:45–3:15 PM**; it is a closing-hours play, **not a gamma blast**. Reward typically doubles, sometimes 5×–10× (Day 10).

Preparation and strike-set (Day 9 deck):
- Pull derivative data after close (NSE / OI Pulse "OI Expiry Strategy" feature) and review the **entire week's** data to understand how to play the weekly expiry.
- Select **5 strikes either side of ATM** to analyse; widen to **10 strikes either side** if huge volatility/spread is expected.
- **Round strikes are a must** (e.g. 40,000 / 40,500 / 41,000 / 42,000) — that is where most of the action happens.

Reading who controls the move (Day 9 deck):
- Decode each side via the OI quadrants. A **long build-up on the put side** (OI up + price up >50%) with sellers writing calls means buyers are on the put side; when **put buyers start unwinding**, sellers shift from writing calls to writing puts — so the **call side moves higher on expiry day**, and you sit as a buyer on the call side.
- **Short build-up** = OI up >50% with price down >50%; **short covering** + long unwinding signals buyers are exiting that side.
- Sellers will **write the side that has the higher premium** and pump funds where they look for weakness in buyers — sit on the opposite side as the buyer.
- **Both-sides long unwinding (or sellers adding on both call and put) = premium erosion only / pin** — a buyer cannot make money on either side; avoid the day (Day 9 deck; Days 9, 4).
- If strikes are **closing near VWAP**, expect premium erosion — no trades when the market is held at the VWAP level (Day 9 deck).

Strike selection and sizing (Day 10 deck — "Key Things To Remember"):
- **Deploy ~10% of your PROFITS** — never deploy your capital in hero-zero.
- **Let the price come to you; do NOT average a losing position.**
- Watch for the side **trading at a discount** and expect writers to cover their position in maximum.

#### S24 Refinements

- **Sizing/risk:** deploy only ~10% of your **profits** (not capital); never risk capital; do not average a losing hero-zero position — set a level, and if it breaks let it go to zero, if it rises keep trailing (Day 10).
- **Strike selection by aggression:** ATM or one strike above ATM (OTM) for the aggressive play; ITM or 1–2 above for a safer bet. Favor the side trading at a **discount** (sellers wanting out must give up the discount plus the move) (Day 10).
- **Direction by second-half flow:** if call-side OI is rapidly rising while the other side covers, take the **PUT** side (and vice-versa); favor the side whose premium is **lower** (Day 10).
- **Bank Nifty caution:** with daily expiries removed, BN volumes have dried up — it no longer reacts as before; be careful (Day 10).
- **Expiry-day OI read (Day 17):** scan 2–3 strikes above and 2 below the close. An ideal CE long-build needs the strike closing **≥50% up AND near the day's high with a big OI jump**; for a put-side short-build want price falling **~70–78% AND a large OI jump (~85%)**. If one side qualifies but the other does not, the data is inconclusive — do not be aggressive on the non-qualifying side.
- On expiry-eve, a **discount at a strike** signals writers may do a morning adjustment / possible gap; **elevated CE vs PE** = crowd expecting an up-move and paying up, which can crash if the move fails (Day 17).
- **Premium-level preference:** prefer low-premium strikes near expiry (max loss capped at the small premium); with high premiums prefer **ITM/deep-ITM** (the OTM crash is bigger on a fall) (Days 11, 18).
- **Monthly-expiry day: ignore the OI/expiry data** — writers unwind prior-month positions, so the data reflects the past month, not the coming series (Days 9, 20, 21).
- **Direction is two-sided depending on VIX + OI:** hero-zero can fire on either side. High VIX favors an end-of-day downside sell-off, but positive ~2:45 news or strong call-side short-covering can produce a fast upside move (Day 21).

#### 2025 Live Examples

- **Day 9 (live):** 24,500 CE — OI 21L → 35L → 78L → 1cr; premium 670 / 536 / 310 / 296 / 247, then popped to ~377–383. Late writers (at the bottom) lose, not day-1 writers. Meanwhile 24,800 ATM showed **both-sides short build-up** (call −29% premium with OI +250%; other side −65% with OI +366%) = a pin, no directional move.
- **Day 11 (live):** prior-day **call short-covering** (32 → 137, +260%) plus put OI +380% (price −85%) gave a **bullish bias to the bull side**; the 25,200 strike fell 80 → ~12 with OI still high (can go to zero).
- **Day 19 (live):** 26,000 strike OI held ~3.56 despite price moving 16 → 30 (~100% jump) — writers held the fort and capped the move; OI crossing the 40-mark would have signalled a big breakout.
- **18 Feb Bank Nifty expiry (illustrative deck example, Day 10 deck — NOT new 2025 live data):** a thin-volume BN-future expiry. Call OI moved higher all day with significant premium erosion; at 1:45 PM active-strike call OI crossed 1cr, then dropped to 97L by 2 PM with no further put covering. On serious short covering, BN can give a quick 100–200 point move; the deck recommends looking at **1–2 strikes above ATM (≈20–50 price) between 2:30 and 3 PM**, where **price usually doubles or triples in 30–45 points** — and it must be done during the closing hours. (an active CE strike — the deck's 36400 CE & PE slide — moved 92 → 280 in 30 minutes.)

---

### 3.8 BTST / STBT

**S24 Coverage:** Reference-only — S24 ships **no dedicated deck** for BTST/STBT. All content below is drawn live from the daily decoding transcripts (the trades and refinements Shiva narrated as they occurred), not from a teaching slide.

**Mechanics (S24):**
BTST ("Buy Today, Sell Tomorrow") = carry an options position overnight to capture an expected gap-up the next morning; STBT is the bearish mirror. As decoded in S24:
- **Sizing:** commit only **5–10% of capital** to the overnight position (Days 2, 3).
- **Always exit early next morning** — the play targets the open / morning move, not a multi-day hold (Days 2, 3, 18).
- **Validity gate:** only carry overnight when, after the day's move, the market does **NOT breach VWAP or Super Trend** and **holds the strength into the close** (Days 2, 3, 18). A close that reclaimed/held VWAP with volume and never broke back below VWAP/Super Trend is the textbook BTST condition (Day 18).
- **Hit rate / honesty about traps:** BTST works roughly **6–7 of 10 times; 3–4 of 10 get trapped** — out of 10 you can't be right every time, and the operators deliberately trap overnight players on the off days (Day 9).
- [keep-rail] Strike / leg selection: carry an ATM±3 strike at delta ~0.6-0.7; by side use Buy-Future / Sell-PE for a bullish carry and Buy-CE as the option leg (bearish STBT mirrors).
- [keep-rail] S23 premium behaviour: expect ~80-90% premium crush against you on the wrong side and ~50% run-up on the right side; carry the INDEX option only, never an OTM leg.

**S24 Refinements:**
- A **news-driven single-day rally after a multi-day fall should NOT be blindly trusted** for an overnight carry — it needs **2–3 days of consistent, volume-backed follow-up** before treating the up-move as real (Day 9).
- **Do not take a BTST near expiry** just because the market inches up in the **last 30 minutes**; if the **morning high was not reclaimed in the second half**, carrying overnight is risky (Day 8).
- **Profit-protection override:** after a big intraday hit, if you've already recovered ~**80–90%+** of the day's profit, **square off rather than carry overnight** — once you've taken a big hit it is hard to justify an overnight position with the parameters against you; protect the previous-day profit **plus ≥70–80% of intraday profit** before taking fresh overnight risk (Day 18).
- [keep-rail] Carry-validity OI confluence: read the OI four quadrants on the carry-day close — Long Build-Up (Q1) and Short Covering (Q3) support a bullish BTST carry; Short Build-Up (Q2) and Long Unwinding (Q4) warn against it. Muted on derived history; judge on forward paper with real captured OI.
- [keep-rail] 3:15pm confluence check before carrying: confirm Futures-OI direction, Option-OI Trending + Sentiment, and global cues all align with the intended overnight side; a 3:15 read that contradicts the carry voids the BTST.
- [keep-rail] India VIX gate: a day where VIX printed its day-low while the market made a day-high favours the overnight carry (low-VIX regime rewards holders).
- [keep-rail] RSI directional gate (per the operative RSI framework: buy 50-75 / no-trade 40-50 / sell 40-25 / overbought 80 / oversold 20): take the bullish carry only when RSI is directional and NOT overbought (>75), and the bearish STBT mirror only when not oversold (<20).
- [keep-rail] Stock-side hard limit: do not carry an overnight position in a stock whose daily RSI is already >75.
- [keep-rail] Overnight stop-loss: alongside the validity gate and 5-10% cap, hold a 50%-premium stop on the carried leg; carry only ONE night and avoid a fresh Friday carry into the weekend.

**2025 Live Examples:**
- **Day 2:** BTST taken Day-1 → Day-2 on a strong VWAP / Super Trend / WMA close, ATM **24,500 CE** at ~10% of capital. Expected a 100+ point gap-up, but overnight US news capped it to **~20 points** — still exited in profit at the open.
- **Day 18:** textbook BTST — taken the prior afternoon and flagged in the evening message; it paid off with a gap-up and a **~500-point rally** from the prior day's low **25,550** toward **~26,000** (the move reclaimed VWAP with volume and never broke back below VWAP/Super Trend). Separately, on a leg where Shiva had taken a big intraday hit and then recovered **~80–90%+** of the day's profit, he chose to square off rather than carry overnight — protecting the prior-day profit plus ~70–80% of intraday profit.
- **Day 9 (the trap case):** **24,500 CE** closed Wednesday ~**385 (+55%)** on short covering — on paper a perfect BTST. But Day-9 opened only **350–370** with a low of **300** (no gap-up, global pressure), illustrating the ~3–4-in-10 trap outcome where overnight players are caught.

---

### 3.9 Morning Trade (Opening Trade Strategy)

**S24 Coverage:** Reference-only — S24 ships NO dedicated deck for this strategy; all content comes from the daily live-decoding transcripts (chiefly Day 12 and Day 17), where Shiva runs the opening trade live and narrates the read.

#### Mechanics (S24)

The Morning (Opening) Trade is the play taken right at / just after the 9:15 open, before the regular intraday data window settles. S24 treats it as **the single riskiest trade of the day** — only to be attempted with a pre-defined exit, and freshers are told never to attempt it unless direction is already known (Day 12). The execution flow as taught in S24:

- **Wait for the pre-market to settle (~9:07–9:08).** Ignore the initial ±200-pt pre-open swings; use the settled pre-market open to compute fair value and the operative premium range for the strike you intend to trade (Day 12).
- [keep-rail] Cross-check the OI-confluence gate (low / mid / high) against the settled pre-market read before entry; a ~9:11 gate read and a ~9:18 exit-timing check frame the window (timing kept loosely).
- **Read the gap to pick a side.** On a big gap-down (~300–400 pts) do NOT buy a put — the move is already oversold and yesterday's put buyers are booking; there is no fresh edge. Prefer to use a modest gap-up (~30–40 pts up with global cues flat/green) to short once, because a gap-up is expected to be sold off at least once (Day 12).
- [keep-rail] Form the opening-trade view the prior evening (EOD): a convincing close in the trend direction is the precondition, corroborated by prior-day 3:20pm OI alignment and a >50% direction-change in OI (the prior session's close-based bias carries into the open).
- [keep-rail] On a big gap-down, wait for the oversold-RSI cool-off (RSI lifting back off the oversold 20 floor) before any continuation read — don't chase the already-oversold knife.
- **Check the pre-market heavyweights before shorting.** Reliance / Infosys / HDFC Bank / TCS opening up 2–4% can add ~80–100 Nifty points and hold the index up, so a short into that can trap you (Day 12).
- [keep-rail] Breadth gate before shorting: confirm advancing >32 / declining >32 of the index constituents move with the side; thin or split breadth caps the move.
- **Strike selection:** pick a strike **2–3 strikes away from the settle** (not deep OTM); prefer slightly ITM in the morning (some intrinsic value, less time-value bleed), then rotate to higher-priced strikes as expiry nears (Days 12, 17).
- **Exit / stop-loss:** set the stop at the strike's prior-day VWAP / bounce bottom and exit quickly on a close below it. On a news-driven adverse move, honor the pre-set SL and do NOT average (Days 12, 17).
- [keep-rail] Entry fire trigger: do NOT enter on the gap alone — wait for the second candle to break the first candle's high (CE) / low (PE) and confirm with a rejection wick at the level; that break-and-rejection is the actual opening-trade trigger (engine SL/trigger anchors off the first candle).
- [keep-rail] Prior-day VWAP is the first support level on a fall but is valid only until ~10:30; before ~10:30 suppress VWAP-based reads and lean on the strike's prior-day VWAP / bounce bottom as the SL level.

#### S24 Refinements

- **Sizing:** risk only ~10–20% of capital on the opening trade, and use the previous day's profit as the stop-loss budget (Days 12, 17).
- **Expiry-day constraint:** when the prior-day OI shows put buyers holding tight, restrict the opening trade to the **call side only** (Day 11).
- **Gap-up overbought open:** if the market opens gap-up and overbought, the morning opportunity is small; a later consolidation window opens for those who missed the open (Day 20).
- Reinforced discipline: opening trades are "the most, most risky trades" — anyone not comfortable with them should never attempt them, and must always have an exit strategy in place before entering (Days 12, 17).
- [keep-rail] Once the trade moves in favour, trail the stop to breakeven and book initial profit; a secondary exit fires if RSI rolls back below 30. Treat Open=High on the position as an exit trigger / CE-hedge signal, and add only around the prior close.

#### 2025 Live Examples

- **Day 12 — Sensex 82,200 PE (expiry day):** index settled ~81,900; fair entry estimated ~340–380 via delta ~0.80 (a ~100-pt index drop ≈ ~80-point premium move). Entered ~370–380 at 9:15:02 and exited 9:16:13 (~1 minute), with <₹2 lakh deployed for >1% return.
- **Day 17 — opening CE on the Delhi-blast news:** the call went against plan, fell 260 → 222 and ran down to ~145 (where the ~150–160 SL triggered; 222 was an intermediate print). When the SL was hit and the OI data turned bearish, the position was exited with no averaging; the day still netted ~₹2 lakh overall.

---

### 3.10 Options Scalping Framework (Connect the Dots)

**S24 Coverage:** deck-taught — the "Connect The Dots — Become Successful Options Scalper" deck is present in the Day 3 folder, and the indicator framework is re-taught chess-style in the Day 5 "Kingdom Trading Strategy" deck; both are layered with live refinements across the 21 daily transcripts.

#### Mechanics (S24)

The framework is the master read that "connects the dots" across the other strategies: a checklist of cues that must align before any scalp. The deck (Day 3) lists the dots to connect, in order:

- **Dow 30 futures** — global directional cue.
- **India VIX** — volatility regime.
- **OI Spurts — 4 quadrants** — Long Build-Up / Short Build-Up / Short Covering / Long Unwinding (the OI quadrant read).
- **OI strikes & futures** — option-chain OI by strike.
- **IV data — 6 strikes** — implied-volatility read across strikes.
- **VWAP** — central gravity / final defence.
- **Super Trend** — gate / second line of defence.
- **Volume candles** — confirmation of any move/break.
- **RSI** — momentum band.
- **Parabolic SAR** — first signal.

The **5 chart "dots" on the 3-min timeframe** with their settings (Day 3 deck):

- **VWAP** — default.
- **Super Trend** — (10, 2).
- **Volume candle** — 50K (Bank Nifty) & 125K (Nifty).
- **RSI(14)** — 80:20 bands.
- **Parabolic SAR** — (0.02, 0.2).

**Indicator hierarchy (Day 4):** VWAP is the most important indicator after OI. Defence order on a fall: **VWMA(20) = first line of defence, Super Trend = second, VWAP = final** (VWAP ranked the biggest support after OI, above Super Trend and WMA — Day 13).

**OI four quadrants (Day 3 deck):**
- Rise in OI + rise in price = bullish (look for ~50% rise in OI & price).
- Rise in OI + slide in price = bearish.
- Slide in OI + rise in price = short covering / long liquidation.
- Slide in OI + slide in price = better to avoid (unless hedging large positions).

**IV interpretation (6 strikes, Day 3 deck):** CE/PE 10/10 = low IV, good for a trend play; 10/15 = premium erosion on high IV if market is against the trend; 20/20 = mostly premium erosion; 30/20 = bullish on the higher-IV side when the gap is 10 points or more and the market moves that way; 40/40 = stay away or play short straddle.

**VIX four-scenario read (Day 3 deck):** market higher + VIX cooling = bullish; higher + VIX rising = bearish; lower + VIX rising = bearish; lower + VIX stable = *bullish per the deck slide* — the live Day-3 read clarifies this case as **longs unwinding / no aggressive fall** (see §4.5); sideways + VIX erratic = ignore VIX as a factor.

**Core operating rules (Days 2, 4, 6):**
- Intraday data window is **9:45–2:30** (post-2:30 = next-day positioning).
- Scalp on **3/5-min**; positional on **15-min / hourly**.
- **Chart AND data (intraday + positional) must align**, or it is an avoid.
- The Kingdom deck (Day 5) confirms the same indicator settings on the 3-min TF — Super Trend (10, 2), VWMA(20), Parabolic SAR (0.02/0.2) — as the soldiers behind the read (the chess metaphor itself is a teaching mnemonic, not separate mechanics).

#### S24 Refinements

- **RSI(14) zone table (Day 4):** overbought 80 / oversold 20; **40–50 = no-trade**; buy 50–75, profit-book 75–80/85, >80–85 = no fresh longs; sell 40–25, profit-book 25–20, <20 = avoid; never short into overbought.
- [keep-rail] Book profits on the RSI ladder: scale out near the overbought top (RSI ~80-85, the ~90% band) and treat the oversold floor (RSI ~20, the ~10% band) as the cover/avoid edge — profit-book 75-80/85 on the long side and 25-20 on the short side, never holding into the extreme.
- [keep-rail] Cross-check RSI on multiple timeframes: confirm the entry-timeframe RSI band against the 5-min and Daily RSI before committing — a scalp band that agrees with the higher-TF / daily momentum read is trustworthy; divergence between them is a caution.
- **Hourly-new-high cadence (Days 2, 6, 18):** a trending day makes a fresh high roughly every hour (~10:00, ~11:00, then ~12:00–12:15 ±15 min). If new hourly highs stop and price holds a ~30-pt range, it is stuck/erosion. An escalating premium move (~20 pts by morning → 60 post-10:00 → 90 post-10:30 → 110 post-11:00) confirms a genuine trend (Day 6).
- **Support-strength tiering for sizing (Day 2):** classify support as weak / strong / very-strong; deploy small at weak, reserve maximum size plus firefighting funds for very-strong (usually VWAP).
- [keep-rail] Size by distance from VWAP: the further price has run from VWAP the smaller the add (chase risk is highest far from the mean); concentrate the heaviest size near VWAP, the strongest support, and keep adds light when price is extended away from it.
- **Volume confirmation is mandatory for any break (Days 11, 12, 15, 18, 21):** a VWAP/level/breakdown break WITHOUT volume can be reversed in one candle and trap you. A clean VWAP break NOT on volume points to the next big support, not a genuine breakdown (Day 11).
- **No-trade zone (Days 5, 13, 16, 17, 19):** price boxed between Super Trend/VWMA and VWAP — take only 1–2 lots and wait for a boundary break.
- [keep-rail] On a support trade, enter on the pullback INTO the support (VWAP / Super Trend / VWMA) rather than chasing the bounce — wait for price to come back and hold the level, then enter on the recovery candle.
- **VWAP switch (Day 9):** previous-day VWAP is the first support on a fall but valid only until ~10:00–10:30; switch to intraday VWAP after ~10:30–11:00.
- **Index Contribution / breadth read (Days 1, 4, 10):** advancing-vs-declining count and the top 5–6 heavyweights moving together (+1–3%) confirm follow-through; big traders may suppress 1–2 heavy stocks to cap the index.
- **Indicators far from candles after a breakout = avoid scalping (Day 10)** — price won't return to support.
- **Recycle profit (Days 10, 16, 21):** book initial profit, then re-enter the same strike lower, using the booked / prior-day profit as the next trade's risk budget.
- **Option-chain checklist for a clear trend (Day 20):** call-side % move from ATM (>50/100/200%), put-side fall (>50%), IV, max change-in-volume location, unwinding-vs-build location, then delta for strike selection.
- **Discount-premium read (Days 3, 8):** ITM call below intrinsic / puts at a ~5% discount with no buyers = market expects an up-move; LTP − premium = intrinsic, below-intrinsic = discount (favourable to the buyer).
- **Plot OI bars on Nifty SPOT, not futures (Days 8, 9)** for resistance, since spot is the settlement reference.
- **Dow futures = primary all-day global cue (Days 3, 4)** (US30 trades ~20h/day); a sudden 100–200 pt Dow move can drag Nifty even absent local news.
- **IV-equal both sides (Day 1):** 8&8, 9&9, 10&10 ⇒ premium erosion unless a new high forms every 30 min. Long-unwinding signature = OI↓ + price↓ + IV↓ together (Days 1, 16).

#### 2025 Live Examples

- **Day 2 — three live Nifty scalps:** a support scalp ~10:18 (24,500 CE in tranches of 2/4/8 lots, +10–15 pts); a continuation long (~5–11 Nifty pts ≈ 15–40 Sensex pts); a noon breakout (24,600 CE, +~20 pts on OI confirmation).
- **Day 20 — trend-day call:** entered ~461, trailed/exited ~520; the same strike later ran 550–560 in a single candle (~100 pts).
- **Day 14 — box-range scalping:** Nifty boxed 25,950–960 / 25,800; inside the box take only ₹1,000–2,000 scalps, with a breakout-buy on a clean boundary break with volume.

> Note: the Day 3 "Connect The Dots" deck and the Day 5 "Kingdom Trading Strategy" deck are reused teaching decks (the chess example slides — RSI=67/77/23/73 stop-loss illustrations — are illustrative deck examples, not new 2025 live data). The numbered 2025 examples above are from the live daily transcripts.

---

### 3.11 Straddle (Long & Short)

> **S24 coverage: reference-only — no S24 deck; base mechanics not re-taught in Session 24.** Shiva never opens a Straddle deck or re-teaches the strategy's construction (ATM call + put, breakeven, long-vs-short variant selection, leg management) in the 21 daily decoding sessions. The Straddle appears only as a *live read of the day's character* — i.e. who is winning (straddle buyers vs straddle sellers) and what the combined-premium-vs-VWAP relationship is telling you. The full base mechanics live in the earlier-session body of this strategy; the items below are ONLY what S24 itself states.

**S24 Coverage:** reference-only (no dedicated S24 deck; referenced live across Days 3, 5, 14, 17, 19).

**Mechanics (S24):**
S24 does not re-teach the construction of the strategy. What it uses live is the **combined (call + put) premium series at the ATM strike, read against its own VWAP**, as a sentiment/character gauge for the trading day:
- The **straddle chart** = the combined call-side + put-side premium plotted as one series. Watching it against its VWAP tells you whether the day favours **straddle buyers** (a real move develops) or **straddle sellers / writers** (a range/erosion day where both legs decay). (Days 3, 17)
- A **flat / premium-erosion day** is the straddle's defining condition in S24: combined premium grinds lower all day, both legs bleed → **straddle sellers win, straddle buyers lose on both sides** (high IV / high premium is a double-edged sword — on a flat day both legs decay). (Days 3, 14, 19)
- [keep-rail] Long & Short are two variants: a LONG straddle (buy ATM call + put) profits from a real expansion/move; a SHORT straddle (sell ATM call + put) profits from a flat/erosion day. Variant selection is governed by the regime read below.

**S24 Refinements:**
- **Combined-premium-vs-VWAP is the straddle's directional gate.** While the combined (call+put) premium trades **above** the VWAP of both legs, the day is going the buyers' way; **once it drops below that VWAP into the close, straddle buyers lose** and writers take the day. (Day 17)
- **High / equal both-side IV ⇒ short straddle (or stay out).** When IV reads roughly equal and elevated on both sides (e.g. **40/40, 50/50, 60/60**; **20/20** is the lower "mostly premium-erosion" band, not itself a short-straddle trigger), the instruction is **"stay away from the markets or play short straddle"** — an even, high-IV reading is an erosion/range condition, not a directional buy. If you do not know how to run a short straddle in that regime, stay out. (Day 3)
- **The ~50% call-vs-put OI gap is itself a parameter** confirming a bullish / buy-the-dip read (with a higher market) even when there is **no Two-Candle setup** — used as a sentiment confirmation alongside the straddle read, not as a standalone straddle entry. (Day 5)
- [keep-rail] Short-straddle entry trigger: enter the short once the combined (call+put) premium breaks BELOW its VWAP after ~9:30 (the writers'-day cue); a clean below-VWAP break is also the buyer's long cue to stand down.
- [keep-rail] Straddle stop-loss is VWAP-anchored: stop on a clean cross of the combined-premium VWAP plus a ~10-15pt buffer (cite §4.14.8).
- [keep-rail] Exit & leg management: take the long off when the combined premium rolls back below VWAP into the close; on a short, one-leg management is permitted (let the winning leg run / close the threatened leg) rather than holding both rigidly.
- [keep-rail] Breakeven sizing: a long straddle needs the combined premium move to exceed the total premium paid; sized so a real move clears both legs (e.g. a 70 + 50 = 130 combined-premium P&L frame).
- [keep-rail] LOW-IV is the gate for a LONG straddle (cheap premium, room for IV to expand); high/equal IV is the short-straddle / stay-out regime.
- [keep-rail] Trending OI building on BOTH sides together (sellers adding both call and put) marks a short-straddle / erosion day with no directional bias.
- [keep-rail] Strike default is ATM (both legs); going slightly OTM is the safer bet when sizing the legs.
- [keep-rail] The straddle is an EVENT play (run it around events / expected volatility expansions or erosions), not an all-day momentum scalp.
- [keep-rail] Risk gates (S23): do NOT short a low-premium straddle near expiry (insufficient cushion), and Sensex straddles carry a thinner cushion than Nifty — size the short more conservatively. Short straddle carries unlimited risk.
- [keep-rail] Automation reality: only the LONG straddle leg-engine is built (paper); the SHORT straddle is SPAN-deferred (margin/appliance gated) and its YAML keys are presently dead/unwired.

**2025 Live Examples:**
- **Day 17 (expiry-eve erosion):** the combined call+put premium traded above its VWAP intraday, but in the second half it rolled over and closed back below that VWAP — straddle buyers lose, writers take the day. Textbook premium-erosion / sellers' day one day before expiry. (Day 17)
- **Day 3 (range / sellers' day):** the day stayed pinned in a small range, so **"it is the straddle players who are winning it,"** i.e. the writers. Cited alongside the **IV 40/40 → "stay away or play short straddle"** rule. (Day 3)
- **Day 14 / Day 19 (erosion days favour writers):** flat/erosion sessions explicitly called out as days **"dominated by the straddle players"** where straddle buyers bleed on both legs while sellers profit. (Days 14, 19)

*(Note: S24 ships NO dedicated Straddle deck ; every worked number above is from a 2025 daily decoding transcript. The IV-band → short-straddle rule is also restated from the reused Day-3 "Connect The Dots" deck IV table (§3.10), but it is timeless mechanics re-spoken live on Day 3, not a 2022–23 example figure passed off as new.)*

---

### 3.12 Trend Change

**S24 coverage: reference-only — no S24 deck; base mechanics not re-taught in Session 24.**

#### S24 Coverage

Reference-only. Session 24 ships **no dedicated Trend Change deck** and does **not re-teach the base mechanics** of the strategy (the reversal-capture play built on a Trending-OI flip plus a 2-candle confirm). S24 only invokes the trend-change idea *live*, as a condition that gates the other plays, and adds one Day-21 refinement. The full mechanics are not reconstructed here.

#### Mechanics (S24)

S24 references the strategy only at the level of "when has the trend actually changed, and may I now trade against the prior direction":

- **A trend change must be confirmed by Trending OI, not by the chart or a VWAP break alone.** To flip from long to short you need **both** conditions together: price has **broken VWAP** *and* the **Trending OI direction has changed**; a VWAP/volume break on its own is not a trend change (Days 11, 12). While Trending OI stays positive, keep trading with the bulls — do not pre-empt a reversal (Days 5, 11).
- [keep-rail] Three-way reversal trigger taxonomy (the strategy's identity, S24 reference-only): a confirmed trend change fires on (a) a Trending-OI criss-cross flip (call-OI vs put-OI lines cross), (b) price breaking VWAP in the new direction, and (c) a 2-candle confirm in that direction — all three together, never the chart or a VWAP break alone.
- [keep-rail] Volume break-confirm: the directional break must carry volume — ≥50K (Bank Nifty) on the confirming candles for the flip, and the 125K (Nifty) counter-move gate already stated separates a fading unbacked move from a genuine reversal.

#### S24 Refinements

- **Divergence-based counter-trend trade with a 125K-volume gate (Day 21).** When **intraday** Trending OI has turned bearish but **positional** Trending OI is still bullish, a counter-trend (e.g. call-side) trade is permissible **only if the counter (down) move is NOT backed by >125K (Nifty) volume** — a volume-backed counter-move signals the *real* reversal (go with the trend instead), while an unbacked one tends to fade.
- **Monthly-expiry caveat on the reversal read.** On a monthly-expiry day, ignore the OI/trend data entirely — writers are unwinding the *expiring* month, so the apparent trend or reversal does not describe the new series (Days 20, 21; the underlying writers-unwinding-the-expiring-series concept is also Day 9).
- [keep-rail] Trade-window discipline: take reversal entries only inside 09:45–14:30; cap fresh down-side reversal entries after 14:30 (S24 §4 9:40–2:30 window governs the floor/cap).
- [keep-rail] Structural stop-loss: place the stop at the broken swing pivot (the structural level the reversal broke), with ~10–20 pt leeway — not a fixed rupee stop. This is the encoded StructuralStop, which S24's point-SLs do not replace.
- [keep-rail] VWAP-without-volume discipline: a VWAP break with no volume behind it is not a trend change — hold the prevailing side; treat VWAP as the defend line until both OI flips and volume confirms.
- [keep-rail] Max-OI support/resistance box: frame the reversal against the max-call-OI (resistance) and max-put-OI (support) box — a flip that stalls into the opposite OI wall is suspect; a flip that breaks the wall confirms.
- [keep-rail] Index-heavyweights confirm: cross-check the reversal against the index heavyweights (Reliance/TCS/banking-IT leaders) — the trend change is more reliable when the heavyweight stocks confirm the same direction.
- [keep-rail] Data leads price: the Trending-OI data turns ~15–30 min ahead of price — the OI flip is the early read, the VWAP/candle break is the confirmation that follows.
- [keep-rail] Consolidation-both-sides + post-vertical-RSI edge cases (AUTOMATE_PKG): when sellers add on BOTH sides the market consolidates — no reversal trade; and after a vertical climb, only re-enter once RSI cools (per the §4 RSI framework: overbought 80, cool to ~70–80 before a fresh entry).
- [keep-rail] Reversal RSI confirm band (operative): for a long-side (call) reversal, RSI 50–75; for a short-side (put) reversal, RSI 40–25 (below 20 = oversold, wait for a bounce) — per the §4 operative RSI framework.
- [keep-rail] Multi-timeframe S&R + chart-pattern vocabulary: mark support/resistance on 1d, refine on 15m, and read the reversal against named chart patterns (the multi-TF S&R framework that the audit confirmed is part of the strategy's identity).

#### 2025 Live Examples

- **Day 21 (live divergence read):** intraday Trending OI had turned bearish while positional stayed bullish; a counter-trend call was justifiable only because the down-move was **not** backed by >125K volume — had the down-candles exceeded ~125K, the divergence would have been treated as a genuine reversal and the trade dropped.

---

# 4. Common Rules & Shared Components

These components are the cross-strategy building blocks — the reads, gates, and parameters every Session-24 strategy draws on. They are not standalone plays; each strategy section (§3) assumes these are already understood and satisfied. All content below is Session-24-only, mined from the 21 daily live-decoding transcripts (Day 1–21) plus the reused decks surfaced in the daily folders (the "OI Expiry Strategy" deck, Day 9; the updated "How To Scalp Sensex Using Nifty Charts" deck, Day 4). The six execution-relevant S24 Pre-Mentoring primers were also scanned (text + rendered images); they restate already-covered basics, except the one futures-basis read folded into §4.12 below.

## 4.1 Open Interest interpretation

The four OI quadrants are the bedrock read behind every strategy.

- **Quadrant rules:** price↓ + OI↑ = **short build-up**; price↑ + OI↓ = **short covering** (the long-build / long-unwinding pair complete the four) (Days 4, 9; deck Day 9).
- **Only the seller (writer) can create an options position** — the buyer merely takes the other side; read the OI to find where the writers are (Days 4, 9; deck Day 9).
- A **≥50% premium move accompanied by OI dropping** identifies the controlling side (Days 4, 9; deck Day 9).
- **Sellers adding on both sides** = a pin / erosion day, with no directional bias to trade (Days 4, 9; deck Day 9).

**S24 refinements:**
- **Classify a strike by where it CLOSED in its range, not just by the OI change** — a close near the high after a big up-move is a long build-up even with a big OI add; a close near the low with rising OI is a short build-up (Day 10).
- Decode the OI starting from ATM, reading **5–6 strikes above and below** (Day 21).
- An **OTM penny-strike whose OI is now decreasing** signals sellers turning cautious (Day 15).
- **Data, not the chart alone, gives the true read** — the chart can show price up while the trending OI stays bearish (Day 12).
- [keep-rail] Trending-OI + price-action overlay: read the LTP move against the change-in-OI on the same strike — price rising while ΔOI on that side is falling (or vice-versa) corroborates / contradicts the quadrant read; the chart alone can lie (e.g. price up while Trending OI stays bearish).
- [keep-rail] EOD bias: a >50% OI imbalance (call-vs-put) still standing at the close carries a next-day directional bias into the following session.
- [keep-rail] Price-move-per-OI (demand read): gauge how far premium/price moves for a given change in OI — a large price move on small added OI signals strong one-sided demand; a small move on heavy OI adds signals absorption/writing.
- [keep-rail] The defining OI discipline is the ≥50% imbalance gate — a ≥50% call-vs-put change-in-OI imbalance (and the both-OI-and-price->50% / call-vs-put-gap forms) is the hard pre-gate every OI-led play sits behind.

## 4.2 Strike selection (delta-based)

- **Buyers use ATM or 1–2 strikes ITM; avoid lottery (deep-OTM) strikes** (Days 2, 3, 5).
- **Delta gate:** a buyer wants **delta ≥ 0.7**, where the premium moves roughly one-to-one (~85 paise per index point at delta 0.7–0.8); a seller sits far-OTM at **delta ~0.3–0.4** (Days 2, 5).
- **Theta / time-value framing:** buyers are hit-and-run (minimize theta bleed); sellers harvest it (Days 2, 5).

**S24 refinements:**
- **Delta target varies with VIX and time to expiry:** prefer delta ~0.8 near expiry (avoid 0.4 — dangerous), 0.5–0.6 early in the series; under high VIX (20–25) the ATM delta sits at 0.2–0.3, so go 1–2 OTM only for a large expected move (Day 21).
- On expiry, prefer delta ~0.8 with premium in the **300–500 range** (Day 21).
- **Prefer the side with fewer writers** so you can actually exit; **avoid 120–130 rupee premiums, prefer 150+** (Day 15).
- [keep-rail] Operative premium band (S22-resolved): Nifty ~150-350, Bank Nifty ~250-550 (the engine still holds the stale ~100-250 / ~250-400 band — reconcile in code).
- **Avoid OTM for momentum strategies** (too slow, wrong mindset); index options are far more liquid than stock options (Day 20).
- [keep-rail] Strike width: read/select around ATM ±3 strikes (the live strike window, strikes.width:3).
- [keep-rail] Freshness gate on the chosen strike: skip/abort if the strike's premium has already fallen >50% from open OR its change-in-OI has risen >50% (a bigger player has taken the opposite side).
- [keep-rail] OI-confluence-gate-suggested strike (manual): the OI-confluence gate (low/mid/high) can surface a candidate strike — treat it as a manual input, not an auto-fire.
- [keep-rail] Per-strike Open=High / Open=Low confirm: a strike showing Open=High confirms the CE side; Open=Low confirms the PE side (read per-strike, not just on the index).

## 4.3 Implied Volatility — the 6-strike read (and expiry IV crash)

- **Read 3 strikes above + 3 below ATM** (the strikes carrying the max OI / volume) (Days 3, 9).
- **Lower IV favours buyers, higher IV favours sellers** (Day 3).
- IV is computed **per strike on any instrument**, unlike India VIX, which is Nifty-50 only (Day 3).

**S24 refinements:**
- For a **bullish buy, want the call-side IV higher by an 8–10 point gap** (e.g. 16/8, 15/8, 25/15) (Day 3).
- **Equal IV (10/10) = range / erosion;** ~20/20 = mostly premium erosion; 40/40, 50/50, 60/60 = erosion (stay away, or short a straddle); **IV > 40–50 favours sellers** (Day 3). **A higher put-side IV while the market is up (e.g. call 13 / put 15) warns the put premiums can crash later in the day** (Day 3).
- In an up-drifting market, **want call IV > put IV**; low put IV means put premiums barely move (Day 14).
- **Expiry-day IV crash:** the IV crash occurs in the **second half of expiry day** — call IVs fall as call buyers exit, while put IV stays stable if put buyers hold; IV also crashes after an event completes (Day 21).
- **Erosion-day worked example (Day 4):** at the 24,800 strike the combined CE+PE (straddle) premium opened ~148 and fell to ~126 within the first hour, with IV ~9–10 on both sides — a textbook premium-erosion day favouring writers.
- The **CE-vs-PE time-value difference is demand-supply driven** (typically 10–20%, up to ~40%), not Black-Scholes (Day 21).
- [keep-rail] **IV 10–12 = the trend-play / directional band:** an IV reading around 10–12 (vs the equal-IV erosion bands) is where directional buying works — premium moves with the trend rather than eroding.
- [keep-rail] **Per-strike IV-direction is a Desirable filter:** for a bullish buy want the call-side IV rising (and falling for a bearish buy) — the IV on the traded strike should move in the trade's direction, not just sit at a favourable level.

## 4.4 Trading Sensex via the Nifty chart (§4.16)

- **Trade Sensex by reading the Nifty chart:** Sensex's 30 stocks are a subset of Nifty and contribute **~80% of Nifty's weight**; Nifty's broader 21-stock coverage (including sectors absent from Sensex) gives earlier, better sentiment signals, especially in volatile conditions (Day 1; deck Day 4).
- **Trade Sensex options, not futures** — Sensex futures are illiquid ("hardly any volume") (Day 1; deck Day 4).

**S24 refinements:**
- **Updated 2025 weightages (reference context, not triggers):** Reliance 10.70% Sensex / 8.77% Nifty; TCS 9.62% / 7.88%; **Banking 23.71% Sensex vs 19.43% Nifty (overweight); IT 19.35% vs 17.49% (overweight)**; Crude Oil 11.13% Nifty vs 10.70% Sensex; **Sensex-missing stocks = 19.42% of Nifty** (the Nifty roster now includes Zomato) (deck Day 4).
- **Sensex moves ~3–4× Nifty in points** (5–10 Nifty pts ≈ 40–50 Sensex; 50–60 Nifty ≈ ~200 Sensex). Plan Sensex targets at ~4× the Nifty plan within a ~200-pt range; a single candle can move ~5000 Sensex points, so use a wide SL, staggered entries, and no tight rupee SL (Days 18, 21).
- **Volume / participation gate (new):** trade Sensex only when there is genuine volatility / participation; when Sensex option volume is thin, premium erosion is faster — prefer Nifty (the higher-volume index) for higher quantity (Days 11, 13). Day 11 example: Sensex showed 36 & 59 lakh vs Nifty 10cr & 18cr → Sensex avoided.
- **Choose the instrument by nearer expiry / richer premium** (Sensex Thu vs Nifty Tue) (Day 13). Sensex requires a different skill set — wider SL plus higher transaction / index-value charges (Days 15, 16).
- When trading **Sensex expiry, monitor Nifty AND Sensex intraday + positional data together** (Nifty support can prop Sensex); use the pre-open Nifty-vs-Sensex spread as an alignment check; large NSE-vs-BSE pre-open gaps are HFT arbitrage, not retail flow (Day 12).

**2025 live examples:** Day 5 Sensex 81,400 CE ~350–393; Day 8 Sensex 83,000 call ~480 (entries 494–513, 20–25 pt trail); Day 16 Sensex 83,500 → target 84,000–84,200 (Sensex intraday range ~60–70 pts vs Nifty ~15–20); Day 21 Sensex spot ~85,800, resistance ~86,000 (max OI), support ~85,500–85,900.

## 4.5 India VIX

- **VIX rises only on serious futures short build-up;** long build-up, short covering, and long unwinding all make VIX **fall** (Day 3).
- **Four scenarios** (Day 3):
  - Market **up + VIX cooling** = bullish.
  - Market **up + VIX rising** = danger.
  - Market **down + VIX rising** = bearish.
  - Market **down + VIX stable (10–11)** = longs unwinding.
- **Positional bands** (Day 3):
  - **10–11** = buy-on-dips (deploy full).
  - **12–14** = add with caution (~20–30 of 100).
  - **15–18** = cautious / start unwinding.
  - **18–20+** = portfolios unwind.
  - **20–25+** = market tanks.

**S24 refinements:**
- A **genuine rise is VIX climbing every 15 min (vertical)**; a flat / hovering VIX is not a rise (Day 13).
- For a **downside trending day, VIX must climb all day and close near its high** (Day 12).
- **Low VIX (10–12) ⇒ ~90% second-half reversal / short-covering bounce** — after a 70–80 pt fall, expect a recovery between 2:00–3:30 (Days 6, 7, 10).
- **VIX may under-react on expiry day** if positions build into the next series (Day 19).
- [keep-rail] **Read VIX against its own previous close**, not just the absolute band — a VIX above its prior-day close (even within a calm band) is the directional warning; below prior close is the all-clear.

**2025 context:** VIX was historically low (~10–10.9) through the period (Day 3); historical context — March spike ~10→20–23, 2020 9.4→80–87, 2007 / COVID 80–90 (Day 3).

## 4.6 FII / DII flow — the Long/Short ratio gate

- **The FII Long/Short ratio gates direction:** heavily short (~87–94% short) means FIIs expect no sustained up-move and will short every level; the ratio **crossing the 50% mark = a short-covering rally trigger** toward all-time highs (Days 1, 3, 7, 13, 16).
- **DII buying alone (~10,000–11,000 cr/day) may not lift the index** if FIIs are selling heavyweights (Days 1, 3, 7, 13, 16).

**S24 refinements:**
- **FII net-short staying at its lowest with no covering during a rally = a caution flag** — the rally lacks FII participation (Day 11).
- [keep-rail] **FII participant-wise OI matrix (read alongside the L/S ratio; manual/judgment read):** beyond the headline Long/Short ratio, classify each participant class's change-in-OI in a 4×2 (long/short × add/cut) classifier — long build-up / short build-up / long unwinding / short covering — weighting players in importance order **FII > Pro(prop) > DII > Client**, and take a leg-level seller read (who is the net writer of calls vs puts). The leg-level read is the most automatable FII sub-rule and is NOT deducible from the L/S ratio alone.
- [keep-rail] **FII data is next-morning-valid:** the prior session's FII positional read carries into and gates the next morning's bias (validity does not expire at the close).

**2025 examples:** Day 6 FII L/S 7.43 (a historic low) → 11.81 (still ~88–89% short); Day 14 ratio ~7/8/10 → ~20, then faltering; Day 16 ratio still ~13% (below the 50% mark); Day 17 ratio 13.22 → 13.02 (a two-day decline).

## 4.7 Support & Resistance

**S24 refinements:**
- [keep-rail] S&R marking method (foundational, S24 builds on but never restates): mark levels on the 1d chart, refine on 15m, draw zones not single lines, enter on a retrace/pullback into the level, and set targets at the next S/R.
- **Draw S/R only from volume-driven turning points** — the high from which a fall happened = resistance; the low from which a bounce happened = support — **NOT from OI** (Day 21).
- Keep S/R lines for **~2–3 months** (Day 21) / **~6 months** (Day 8).
- The **max-call-OI strike = resistance, max-put-OI strike = support** (Days 8, 11, 20).
- **Spot OI bars and futures OI bars can read opposite at the same level** — both are correct; the difference is premium / intrinsic (Days 8, 11, 20).
- **Daily-RSI positional bias:** >70 overbought, <30 oversold; oversold + below the 200-DMA can favour a bounce (Day 1).

## 4.8 Global cues — Dow futures primary

**S24 refinements:**
- **Track Dow futures (US30) as the primary, all-day global cue** (it trades ~20h/day), then Asian markets (Nikkei / Hang Seng) in the morning and European markets (DAX / CAC / FTSE) from ~12:30 (Days 1, 3, 4, 5, 12, 16).
- **Crude is good while low** (S24 traded ~$60); worry starts as it crosses ~$70–80, "soup" above ~$80–100 (India imports 80–85% of its oil) (Days 5, 12, 16).
- **Dollar Index is good below 100** (ideal 92–93); above 100 = FII selling India (Days 1, 3, 4, 5, 12, 16).
- **A depreciating USD-INR is sentimentally bearish** (it reflects FIIs pulling funds): S24 levels ran ~88.5–88.8 ("higher bracket"), with concern as it approaches ~90 (a ~99–100 caution surfaced on Day 14) (Days 1, 12, 14).
- **Do not act on Gift Nifty / overnight Dow at the open** — wait for the local pre-market (9:07–9:08) (Days 1, 3, 4, 5, 12, 16).
- **Re-check Dow futures right before the open** — a sudden +50–100 pt swing changes the setup (Day 12).
- [keep-rail] **Advance/Decline breadth confluence:** require an advancing count >32 (CE side) / declining count >32 (PE side) as the coded breadth threshold confirming follow-through — the explicit number behind the advancing-vs-declining read.
- [keep-rail] **Premarket-match confluence read (timing kept loosely):** when the OI-confluence gate (low/mid/high) aligns with the settled premarket bias, treat it as confirmation; re-check the same view-match around 3:20pm for next-day positioning. (The old OIP-AI premarket-match feed is replaced by our OI-confluence gate, which outputs low/mid/high rather than a percentage.)

## 4.9 Time of day

- **Intraday data window = 9:45–2:30**; post-2:30 is next-day positioning (Days 4, 5).

**S24 refinements:**
- On a **trending day, expect a fresh hourly high, profit-booking after ~1:30, and stay away after ~2:30** (Day 18).
- **Expiry-eve premium erosion can run till 2:00–2:30+** (theta has no fixed timeline) (Day 17).
- A **low-VIX regime can reward overnight holders while capping intraday to a 50–60 pt band** (Day 7).

## 4.10 Straddle (shared-parameter note)

The straddle is a shared parameter read here (its full strategy mechanics live in §3, the straddle section); S24 surfaces three parameter-level uses:

- The **~50% call-vs-put gap (with a higher market)** is a parameter confirming a bullish / buy-the-dip bias even when there is no Two-Candle setup (Day 5).
- A **flat / erosion day is a double-edged sword for straddle buyers** (they lose premium on both sides); straddle sellers win (Days 3, 14, 19).
- The **combined (call + put) premium vs its VWAP is the straddle's directional gate** — once it drops below VWAP into the close, straddle buyers lose (Day 17).

## 4.11 OI Pulse (tooling)

The OI-Pulse / OSPL tool platform supports several shared reads:

- **"Show OI Bar" on the 50-50 chart** plots OI support / resistance — the largest put bar = support, the largest call bar = resistance; a **shrinking put-OI bar on a fall signals a reversal** (Days 8, 9, 11).
- **"Disable trading in 1 Cliq"** locks broker access once the target is hit (Days 1, 21).
- The **risk calculator** outputs price / high-range / low-range / SL / target per strike (Day 8).
- The two oipulse "AI" features map to our own **transparent, rule-based** equivalents (no black-box AI): (a) the **OSPL chart signal** → our SuperTrend(10,2)-derived directional signal (spec: `docs/oipulse-study/advance-chart/ospl-signal.md`); (b) the **OIP-AI probability** (the Open=High success %) → our **OH-probability tier** (the Day-14 Table-1/Table-2 model, `OpenHighLow.tier`: **LOW / MILD / HIGH ≈ 30 / 60 / 90%**, with an **AVOID** veto on a >50% premium-fall or >50% ΔOI-rise). The general scalp **OI-confluence gate** (the connect-the-dots composite) likewise reads a **LOW / MID / HIGH** response, not a percentage (bearish vs bullish is its directional read). Avoid the high-risk 1-min "quick scalp" timing (Day 8).
- A **forthcoming asset-based SL tool** sets exits off chart levels instead of the option price (Day 20).
- [keep-rail] **ScalperManualChecks card (7-item operator pre-trade checklist):** before firing, confirm the live S24 gates as a one-glance card — (1) OI quadrant / trending-OI direction, (2) ≥50% OI+price confirmation, (3) VWAP side & defence ladder, (4) RSI band, (5) IV / VIX regime, (6) global cue + FII-DII direction, (7) time-of-day window (9:45–2:30). This is the implementation operator surface mapping to the live rules — keep regardless of doc edition.

## 4.12 Futures basis — near-term directional filter

*(Source: the S24 Pre-Mentoring primer "VIX + OI / Global Markets" — a generic reused onboarding primer, not re-taught in the 21 live sessions; included for completeness of the S24 folder.)*

- The **present-series index future trading at a DISCOUNT to spot = bearish for the very near term** (casts doubt on a bullish breakout); the **next-month future at a PREMIUM to spot = bullish for the near term**.
- *Primer example (24-May-2022 Bank Nifty — illustrative, NOT 2025 data):* present future 34,467 < spot 34,495 = discount = bearish near-term; next-month future 34,544 = premium = bullish near-term.


---


# 5. Open Questions / To Confirm

Items a bot-builder must confirm before encoding S24 mechanics. Resolve each against the source decks/dailies before trusting the value.

1. **Open=High — refinement vs rewrite.** S24 turns Open=High into a full chasable system (probability badge, futures+options confirmation, ≥3-strike confluence, never target above the O=H level, both-sided = avoid). Confirm whether this is encoded as a refinement to the existing open_high_low logic or as a substantially rewritten section, since it goes well beyond the S20 baseline.

2. **Sensex participation/volume gate — placement.** S24 adds a Sensex-vs-Nifty participation/volume gate (trade Sensex only when volume/volatility supports it, else prefer Nifty) layered on top of the S23 §4.16 thesis. Confirm whether this gate lives as a new sub-point under the Sensex section or is folded into the existing "why Sensex now" subsection.

3. **Trending-OI strike-count context (15 vs 5–7 vs 5/10).** Three strike-count reads appear across S24: the Trending-OI dashboard read of 15 strikes (Day 2: 7 above + 7 below + ATM), the golden-crossover companion read of 5–7 strikes either side (Day 6), and the expiry-OI read of 5/10 strikes (Day 9 deck). Confirm these are context-specific (different tools/purposes) and not a single value in conflict — so they are encoded as separate inputs, not logged as a §5 conflict.

4. **trend_change — S24 attach-point.** Day 21 logs trend_change as a refinement (intraday-bearish / positional-bullish divergence; take the counter-trend call only if the counter-move is NOT >125K volume). Confirm this attaches to the existing S23 trend_change mechanics rather than implying new mechanics, since S24 never re-teaches the trend_change deck.

5. **Reused-deck figures must not be treated as 2025 data.** Several golden_crossover and Hero-Zero figures in S24 days are explicitly reused-deck examples (e.g. Day 6 2022-deck crossover OI 60–66→72/36; Day 10 18-Feb Bank Nifty hero-zero; Day 7 22/28-Nov-2022 charts). Confirm none of these old example figures are encoded or reported as new 2025 live data.


---

# 6. Machine-Readable Appendix

One JSON object per strategy (consistent schema) for backtest/bot implementation, **built strictly from Session 24** (2025). Each block carries `session: "24"`, `valid_year: 2025`, and `s24_coverage` — **deck-taught** (re-taught/decoded via a deck or sustained daily decode in S24) vs **reference-only** (carried forward from earlier sessions, used live but with no S24 deck and no base re-teach). For reference-only strategies (Trending OI Crossover, BTST/STBT, Morning Trade, Straddle, Trend Change) the blocks capture only what S24 itself states; base-mechanic gaps are listed in each block's `uncertain`. `source_files` are kept generic (deck name / "Day NN transcript"). The full narrative lives in §3 (per-strategy) and §4 (shared components); consult those before implementing.

### 6.1 Two Candle Theory
```json
{
  "name": "Two Candle Theory",
  "key": "two_candle",
  "s24_coverage": "deck-taught",
  "market_context": "The scalper's core breakout play — find one good strategy and keep refining it. Hunts breakout trades in index futures and index options only (Bank Nifty / Nifty); one or two clean trades a day are enough for consistency ('win battles, not the war'). All conditions read on the 3-min timeframe (deck title says 3/5-min; S24 live work uses 3-min). The Day-5 'Kingdom' deck re-frames the same indicator set onto chess pieces (Queen=OI, Rook=VWAP, Knight=Super Trend, Pawn=VWMA(20), Bishop=Parabolic SAR, Territory=RSI, Weapons=Volume, Battle=Candles, King=trader) as a teaching mnemonic only — not a separate strategy.",
  "instruments": [
    "Index futures (Bank Nifty / Nifty)",
    "Index options (Call / Put on Bank Nifty / Nifty)"
  ],
  "timeframe": "3-min (deck title says 3/5-min; S24 live work uses 3-min)",
  "indicators": [
    "VWAP (price above/below VWAP gates direction)",
    "Open Interest — four quadrants (LB / SC / SB / LU)",
    "RSI(14) — momentum band filter",
    "Volume (2 consecutive bars)",
    "Parabolic SAR (0.02, 0.02, 0.2)",
    "VWMA(20)",
    "Super Trend (10,2)"
  ],
  "setup_preconditions": [
    "Trade index futures or index options only (Bank Nifty / Nifty)",
    "Read all conditions on the 3-min timeframe",
    "Trade only inside the intraday data window 9:45-2:30",
    "Aim for one or two clean trades a day; do not overtrade"
  ],
  "entry_conditions": {
    "bullish": [
      "1. Index future trading ABOVE VWAP.",
      "2. Open Interest shows Long Build-Up or Short Covering.",
      "3. RSI above 50, in the 50-75/80 band.",
      "4. Volume of 2 consecutive bars above 50K (Bank Nifty) / 125K (Nifty).",
      "5. All indicators (Parabolic SAR, VWMA, Super Trend, VWAP) sitting BELOW the candles.",
      "6. Deploy on the 3rd candle (buy Index Futures or Call Options), with the 1st candle's low as the SL.",
      "7. Overbought defer: if the two qualifying candles form with RSI > 85, do NOT enter on the 3rd candle — wait for RSI to cool to ~70-80 (~75) and enter on the red/pullback candle. If RSI is not yet overbought, enter on the 3rd candle as normal."
    ],
    "bearish": [
      "1. Index future trading BELOW VWAP.",
      "2. Open Interest shows Short Build-Up or Long Unwinding.",
      "3. RSI below 40, in the 40-25/20 band.",
      "4. Volume of 2 consecutive bars above 50K (Bank Nifty) / 125K (Nifty).",
      "5. All indicators ABOVE the candles.",
      "6. Deploy on the 3rd candle (buy Index Futures short or Put Options), with the 1st candle's high as the SL."
    ]
  },
  "exit_conditions": {
    "target": "No fixed target stated; trail the position (Day 12 example trailed after entering on a dip near VWAP). Scalpers hit-and-run / book and stop; positional players can run the position all day.",
    "stop_loss": "Long: 1st candle's low — if hit, the fort is breached, exit. Short: 1st candle's high — if hit, exit. By trader type: a scalper trails the previous-candle high/low as SL; a positional player keeps the original 1st-candle high/low and can run all day.",
    "time_exit": "Trade only inside the intraday data window 9:45-2:30 (post-2:30 is next-day positioning).",
    "scaling": "Day 12 example: entered calls on a dip near VWAP (~320 mark), added on dips, and trailed."
  },
  "risk_management": [
    "Stop-loss = 1st candle's low (long) / 1st candle's high (short); a hit means the fort is breached — exit.",
    "Deep-SL sizing: a high-volume two-candle formation produces a deep 1st-candle SL — size the trade for that wider risk rather than assuming a tight stop.",
    "SL handling by trader type: scalper trails the previous-candle high/low; positional player keeps the original 1st-candle high/low and can run all day.",
    "One or two clean trades a day are enough — 'win battles, not the war'.",
    "[keep-rail] Pull-back add / re-entry on a VWMA(20)- or Super Trend-hold in the trend direction.",
    "[keep-rail] Large 1st candle: SL = 1st-candle high (short) / 2nd-candle low (long), not the deep 1st-candle level.",
    "[keep-rail] Alternate SL = VWAP when price is extended; trail aggressively as RSI nears an extreme.",
    "[keep-rail] Sensex ~3x point-scaling (signal-NIFTY / exec-SENSEX)."
  ],
  "filters": [
    "Direction gated by price vs VWAP (above = long only; below = short only).",
    "OI-quadrant gate: long needs Long Build-Up or Short Covering; short needs Short Build-Up or Long Unwinding.",
    "RSI band gate: long 50-75/80; short 40-25/20.",
    "Volume gate: 2 consecutive bars > 50K (Bank Nifty) / 125K (Nifty).",
    "Indicator-cluster gate: PSAR, VWMA, Super Trend, VWAP all below the candles (long) / above the candles (short).",
    "Overbought filter: RSI > 85 defers entry to the cooled red/pullback candle (~RSI 70-80).",
    "Time filter: act only within 9:45-2:30.",
    "[keep-rail] Strike cue: take a slightly-ITM strike (delta ~0.6-0.7), around ATM±3, premium ~100-250 (Nifty) / 250-400 (Sensex).",
    "[keep-rail] Higher-timeframe RSI cross-check: confirm the RSI band on 5m + Daily; do not trade against the higher-TF RSI.",
    "[keep-rail] Trade-zone filter: prefer support/resistance; avoid parabolic / already-extended entries.",
    "[keep-rail] Midday avoid (~11:00-13:00 chop); two-candle setups recur on a ~45-min to ~1-hr cadence."
  ],
  "edge_cases": [
    "Overbought defer: even a 'perfect' two-green-candle setup can be followed by a big-volume red 3rd candle — only take it after RSI cools (Day 20).",
    "Breakout-trap risk: on a Day-13 Sensex 82,000 expiry option, a candle high of 208 collapsed to 34 in ~3-5 candles then recovered to 205-210 — scalpers who hit-and-ran profited while holders were stopped out.",
    "A signal can fire ~2:27 (just before the 2:30 candle) and still run near the day's high (Day 13).",
    "The deck's 'Perfect Trade Setup' / 'CrossOver' slides are illustrative reused Bank Nifty index-futures charts (date axes 10-Aug-21 and '25) — do not read their levels as 2025 live data."
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "2 Candle Theory deck (Day 4 folder)",
    "Kingdom Trading Strategy deck (Day 5 folder)",
    "Day 04 transcript",
    "Day 05 transcript",
    "Day 06 transcript",
    "Day 12 transcript",
    "Day 13 transcript",
    "Day 14 transcript",
    "Day 20 transcript"
  ],
  "uncertain": [
    "No explicit profit target / point or premium target stated for the play — exits are described as trail / hit-and-run (scalper) vs run-all-day (positional), but no numeric target rule is given in the S24 doc.",
    "The 50K (Bank Nifty) / 125K (Nifty) two-consecutive-bar volume gate is the only volume figure given; no equivalent Sensex volume threshold is stated in the Two Candle section.",
    "Deck title says 3/5-min but S24 live work uses 3-min — the doc notes the 3-min timeframe is what S24 actually uses; 5-min framing is not separately specified for this play."
  ]
}
```

### 6.2 Open = High / Open = Low
```json
{
  "name": "Open = High / Open = Low",
  "key": "open_high_low",
  "s24_coverage": "deck-taught",
  "market_context": "Purely intraday options-scalping read for index futures & options ONLY (stocks fall under Market Movers). Discretionary; reflects the big players' game plan for the day. Open=High on a CALL strike means the option was bought right at its opening peak by a big financial institution that turned bullish at the open (opening tranche split across futures + OTM/ATM/ITM CE + PE writing) and will use its resources to push the market back toward that opening high, so there is high probability the premium revisits that high level once again. The mirror, Open=Low on the PUT side, is the bearish read. Probability is higher in the 1st half; ~90% of Open=High levels are hit in the first half (by ~9:45/10:00/10:30), only ~20% in the second half and only on a trend change.",
  "instruments": [
    "Index futures & options ONLY (Nifty / Bank Nifty / Sensex)",
    "Stocks are excluded (they fall under Market Movers)"
  ],
  "timeframe": "Intraday options scalping; data visible only after the open (~9:16, not pre-market); first-half-of-session bias (level usually hit by ~9:45/10:00/10:30, avoid late entries after ~11:00-12:30)",
  "indicators": [
    "Open=High / Open=Low flag per strike and on futures (from ~9:16 AM); the OH-probability model (Day-14 Table-1/Table-2, OpenHighLow.tier) grades the setup LOW / MILD / HIGH ~30/60/90% (STAND-ASIDE/AVOID = no-trade) = our rule-based OIP probability",
    "Option premium vs previous close (>50% fall = invalidation gate)",
    "Open Interest (change in OI for the identified strike)",
    "Volume per candle (50K Bank Nifty / 125K Nifty thresholds)",
    "RSI (>50 for momentum entry)",
    "VWAP (price below VWAP with volume = confirmed directional change, Day 20)",
    "Indicators sitting below price (chart breaking out)"
  ],
  "setup_preconditions": [
    "Index futures/options only; stocks excluded (Day 14)",
    "Open=High on CALL side normally pairs with Open=Low on PUT side = bullish big player; the mirror (PUT Open=High + CALL Open=Low) = bearish (Day 14)",
    "Do NOT buy on sight: seeing Open=High on CE (or Open=Low on PE) is not itself an entry; time entry to when probability is high (Day 14 deck)",
    "Avoid deep OTM / deep ITM strikes; liquidity and volume are the key gate (Day 14)",
    "Round strikes weigh more than 50-pt strikes (Day 14)",
    "Open=High only triggers on a confirmed directional change (price below VWAP with volume); will NOT work if the call-side >50%-OI-and-price and put-side >50%-fall quadrants are already fulfilled (Day 20)"
  ],
  "entry_conditions": {
    "bullish": [
      "1. F&O confluence: at least 3 strikes above AND 3 below ATM show Open=High on the CALL side, AND the FUTURES also show Open=High (Day 14 deck).",
      "2. PUT-side mirror present: Open=Low on the PUT side, with the put price not increased more than 50% from previous close; prefer ITM over OTM puts (Day 14).",
      "3. Probability matrix HIGH: CALL Open=High + Futures Open=High + PUT Open=Low (premiums revisit the high once again) (Day 14 deck).",
      "4. The identified CE strike must NOT have fallen more than 50% from its open (a 20-30% fall is OK); premiums of the identified strikes must not fall more than 50% from previous close (Day 14 deck).",
      "5. Avoid deep OTM/deep ITM; favor round strikes; liquidity is the key gate (Day 14).",
      "6. Entry only on momentum: enter when price recovers back TOWARD the Open=High with momentum (price rising with volume, RSI >50, chart breaking out), with >=50K (Bank Nifty) volume on ~3 consecutive candles and indicators sitting below price; then probability is in the 70-80-90% band (Day 14).",
      "7. Worked example (illustrative deck example): O=H 300, current 200 -> once price moves to 250 and the chart breaks out with volume, enter @250 and exit ~290 (enter on the recovery toward the O=H, never set a target above it).",
      "8. Confirmed directional change present (price below VWAP with volume); call-side fall must drop below 50% and put-side fall must also be <50% (Day 20)."
    ],
    "bearish": [
      "1. Mirror setup: PUT Open=High + Futures Open=Low + CALL Open=Low = HIGH probability bearish (Day 14 deck).",
      "2. PUT-side confluence: at least 3 strikes above AND 3 below ATM show Open=High on the PUT side, AND the FUTURES also show Open=Low (mirror of the bullish confluence) (Day 14 deck).",
      "3. PUT Open=High reads on rising premium: a rise on <50K volume = probability increases, a rise on >50K volume = probability decreases (Day 14 deck 'subsequent-candle' table).",
      "4. Identified PUT strike must not have moved more than 50% adversely; premiums must not fall more than 50% from previous close (mirror gate, Day 14).",
      "5. Avoid deep OTM/deep ITM; favor round strikes; liquidity is the key gate (Day 14).",
      "6. Enter only on momentum on the put side, mirroring the bullish trigger (recovery toward the put-side Open=High with volume) (Day 14)."
    ]
  },
  "exit_conditions": {
    "target": "Target IS the opening-high (Open=High) level; choose the strike whose premium is nearest to its target. NEVER place the target above the Open=High price (e.g. O=H 300 -> exit below it; O=H 183 -> target <=182). Once the level is hit it reverses strongly ~90% of the time; do not hold past it unless trend is confirmed (Day 14).",
    "stop_loss": "No fixed numeric SL given for this strategy in the doc. Abort/exit triggers: an against-move on >=50K (Bank Nifty) / >=125K (Nifty) volume, or a downside 2-candle, disregards the call-side Open=High; a >50% premium fall together with a >50% OI rise (crossover) = exit (Day 14). Even if price moves against the side, it should not do so on candles with volume >50K (Day 14 deck).",
    "time_exit": "First-half bias: ~90% of Open=High levels are hit in the first half (by ~9:45/10:00/10:30); only ~20% hit in the second half and only on a trend change. If the level is not hit by ~11:00-12:30, probability keeps falling; avoid late entries (Day 14). Avoid the 2nd half due to time value / premium erosion (Day 14 deck).",
    "scaling": "Always trail once in profit (Day 14 deck). Look for small scalps. (No explicit add/pyramid ladder is given for this strategy beyond trailing.)"
  },
  "risk_management": [
    "Sizing: never deploy more than 30% of capital on this trade; these are highly risky trades (Day 14 deck).",
    "Always trail the stop once in profit (Day 14 deck).",
    "Analyse Open Interest to abort: if the option premium decreases by more than 50% AND/OR the change in OI for the identified strike increases by 50%, avoid/exit the setup (a bigger player has taken the opposite view) (Day 14 deck).",
    "Never place the target above the Open=High price; do not hold past the level unless trend is confirmed (Day 14)."
  ],
  "filters": [
    "Index futures & options only; exclude stocks (Day 14)",
    "Liquidity gate: avoid deep OTM/deep ITM; ignore illiquid strikes (e.g. a strike printing Open=High but with ~0 volume all day) (Day 14)",
    "Round strikes weigh more than 50-pt strikes (Day 14)",
    "Probability is higher in the 1st half; avoid the 2nd half (time value / premium erosion) (Day 14 deck)",
    "Prefer ITM over OTM on the paired put side (Day 14)",
    "Volume-direction filter: a CALL Open=High that falls on <50K volume (or stays flat on flat volume) = probability increases; a fall on >50K volume = probability decreases. PUT Open=High (rising premium): rise on <50K = probability increases, rise on >50K = probability decreases (Day 14 deck subsequent-candle table)",
    "The setup is flagged from ~9:16 AM; the OH-probability model (Day-14 Table-1/Table-2, OpenHighLow.tier) grades it LOW / MILD / HIGH ~30/60/90% (STAND-ASIDE/AVOID = no-trade) — our rule-based OIP probability (Day 14 deck)",
    "Positional/F&O data can override the Open=High setup (e.g. bearish positional data overriding an otherwise-chaseable futures Open=High) (Day 14)"
  ],
  "edge_cases": [
    "Both-sided Open=High (CALL Open=High AND PUT Open=High together) = MILD probability on both sides = two opposing big players = effectively no-trade; ignore entirely (Day 14).",
    "Only a few CE Open=High with a few PE Open=Low (or the put-side mirror) = MILD probability (Day 14 deck).",
    "Directional-change failure (Day 20): Open=High will NOT work if the call-side >50%-OI-and-price and put-side >50%-fall quadrants are already fulfilled; needs call-side fall below 50% and put-side fall also <50%.",
    "Day 14 Sensex example: ATM ~84,000; strike 83,900 Open=High 563.65 approached (546.9/555/558) without clearing, fell to ~396, then the morning buyer pushed the index up to hit 83,900's Open=High at the ~10:45 candle for his exit; strike then traded ~400.",
    "Day 14 Nifty illiquid-avoid example: 24,850 strike printed Open=High ~450 at 9:45 but had ~0 volume all day -> ignore illiquid strikes.",
    "Day 14 Nifty-futures overridden example: futures Open=High 25,890/25,890; morning CE fell only ~15-20% (<50%, theoretically chaseable) but bearish positional data overrode the setup.",
    "Day 6 not-chased example: 25,000 CE opening trade 166 (open=high), fell to 110, trading 136 -> not chased (no golden crossover, unfavorable OI); would wait for a move back above ~150."
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Open & High Strategy - Index Options & Futures (deck, Day 14)",
    "Day 14 transcript",
    "Day 20 transcript",
    "Day 6 transcript"
  ],
  "uncertain": [
    "§5 Open Question #1: Open=High refinement vs rewrite. S24 turns Open=High into a full chasable system (probability badge, futures+options confirmation, >=3-strike confluence, never target above the O=H level, both-sided = avoid). Confirm whether this is encoded as a refinement to the existing open_high_low logic or as a substantially rewritten section, since it goes well beyond the S20 baseline.",
    "Bearish (PUT-side) entry conditions are largely the mirror of the bullish path; the doc states the bullish confluence and probability matrix explicitly but spells out fewer step-by-step bearish numeric thresholds, so some bearish mechanics are inferred mirrors rather than separately stated.",
    "No fixed numeric stop-loss in points/premium is given for this strategy; exits are defined by the Open=High target level plus the OI/volume abort triggers rather than a set SL distance."
  ]
}
```

### 6.3 Market Movers
```json
{
  "name": "Market Movers",
  "key": "market_movers",
  "s24_coverage": "deck-taught",
  "market_context": "Intraday momentum play on individual F&O stocks (not the index). Market Movers is an OI Pulse feature that segregates the day's top gainers and losers in F&O stocks (filterable to Nifty 50 and Nifty Bank), into a Top Gainers and a Top Losers section. The edge: when a stock breaks an 8- or 9-day high (or low), look for a further 1-2% move in the breakout direction. Trade the underlying via FUTURES only (strict no on stock options) and favour high-volume / liquid stocks for clean entry and exit.",
  "instruments": [
    "F&O stocks (single-stock futures) — filterable to Nifty 50 and Nifty Bank",
    "Trade FUTURES only; strict no on stock options (they can fail to move even when spot/future moves due to low IV / illiquidity)"
  ],
  "timeframe": "Intraday scalp; per-candidate confirmation read uses price vs VWAP/Super Trend/WMA. Core trigger is a daily 8- or 9-day high/low breakout. (Doc gives no explicit chart-interval for the entry candle.)",
  "indicators": [
    "Market Movers feature in OI Pulse — per script shows: script name, LTP, LTP change, OI% change, OH/OL (Open=High / Open=Low), OI interpretation, and minimum number of breakout days (most important)",
    "Minimum breakout days (>= 8-9 day high/low)",
    "OI interpretation / Futures build-up type (long build-up, short build-up, short covering, long unwinding)",
    "OH/OL — Open=Low (strength, for longs) / Open=High (weakness, for shorts)",
    "RSI (daily)",
    "VWAP (entry trigger and manage/close level)",
    "Super Trend (ST) and WMA (price-location confirmation)",
    "Volume (liquidity gate)"
  ],
  "setup_preconditions": [
    "Stock appears in the Market Movers Top Gainers (long candidates) or Top Losers / short-build-up (short candidates) section",
    "Stock has broken an 8- or 9-day high (long) or 8-day low (short) — minimum breakout days >= 8",
    "Underlying must be liquid / high-volume for clean entry and exit (e.g. PayTM ~100K = at least liquid; Maxhealth ~150K/120K treated as 'no volume' = avoid; BSE ~50-60)",
    "Compare the tool's signal against the chart to form a directional view before entering",
    "[keep-rail] Time-of-day floor: take Market Movers entries only after ~09:45 — the breakout/OH-OL read needs the opening range to settle first."
  ],
  "entry_conditions": {
    "bullish": [
      "1. Stock is in Top Gainers and at an 8-9 day high (minimum breakout days >= 8); change in OI% and LTP% and OH/OL all point long",
      "2. High-probability LONG requires 3 aspects aligned: (a) minimum days >= 8 (already at an 8-day high), (b) Open=Low indication (the day's low is not being tested = strength), and (c) long build-up in OI interpretation (bulls in charge)",
      "3. Digest restatement of the long setup: min 8-9-day high breakout + Open=Low + long build-up / short covering, stock already up 3-4%+, then expect a further 1-2%",
      "4. RSI filter: if daily RSI is >70 by the open, take NO fresh long; a dip to ~67-68 is the buy window",
      "5. Confirmation per candidate: Futures build-up type long, RSI supportive, OI interpretation long, price above VWAP/ST/WMA, volume increasing",
      "6. Trade execution: enter the long after price moves above VWAP"
    ],
    "bearish": [
      "1. Stock is in the short-build-up / Top Losers category and at an 8-day low (minimum breakout days >= 8)",
      "2. High-probability SHORT (mirror) requires 3 aspects aligned: (a) minimum days >= 8 (at an 8-day low), (b) Open=High indication (the day's high is not being tested = weakness), and (c) short build-up in OI interpretation (bears in charge)",
      "3. Digest restatement: 8-day low breakdown + Open=High not tested + long unwinding / short build-up",
      "4. A >15% fall / 15-day low with Open=High is a strong short",
      "5. Caution: a falling stock at RSI ~25-30 (oversold) has historically recovered — be careful taking the short there"
    ]
  },
  "exit_conditions": {
    "target": "A further 1-2% move on the stock after the 8-9 day high/low breakout (the core edge: look for 1-2% returns when a stock breaks an 8-9 day high). Book profit as RSI approaches ~80.",
    "stop_loss": "Doc gives no explicit numeric/point stop for Market Movers. Manage via VWAP: if the stock falls, treat VWAP as the level to take a final trade / average / close. (Global stop-sizing in 2.4 is for index instruments — Nifty/Sensex/Bank Nifty — and is not specified for individual stocks here.)",
    "time_exit": "",
    "scaling": "Not specified in the Market Movers section. If the stock falls toward VWAP, VWAP is the level to take a final trade / average / close (the only averaging reference given)."
  },
  "risk_management": [
    "Trade FUTURES only; strict no on stock options (stock options can fail to move even when the spot/future moves due to low IV / illiquidity)",
    "Underlying must be liquid / high-volume — Siva treats ~150K/120K as 'no volume' (avoid); ~100K = at least liquid",
    "RSI gate: no fresh long if daily RSI >70 at open; buy on a dip to ~67-68; book profit around RSI ~80",
    "For shorts, beware oversold RSI ~25-30 — such falls have historically recovered",
    "Use VWAP as the final manage/average/close level once the stock falls",
    "Global guardrails (2.x) apply: aim ~1% per day; per-trade risk ~1-2% of total capital (outer cap ~10% of deployed); single-day loss cap 10-12% of capital; deploy at most ~15-20% of capital at once with risk ~10% of deployed; keep only ~5-10% of total finances in markets; always scale entries, never go all-in; book partials and trail stops; never revenge-trade; never trade against the trend; use 3-min candles, avoid 1-min"
  ],
  "filters": [
    "Minimum breakout days >= 8 (8-9 day high for longs, 8-day low for shorts; >15% fall / 15-day low strengthens a short)",
    "OH/OL filter: Open=Low confirms a long, Open=High confirms a short",
    "OI interpretation filter: long build-up / short covering for longs; short build-up / long unwinding for shorts",
    "Liquidity / volume filter: favour high-volume stocks; avoid 'no volume' names",
    "RSI filter: >70 at open = no fresh long, buy ~67-68, book ~80; shorts beware ~25-30 oversold",
    "Price-location filter: enter long only after price is above VWAP (confirm vs VWAP/ST/WMA)",
    "OI quadrant read (4.1): price up + OI up = long build-up; price down + OI up = short build-up; price up + OI down = short covering; classify a strike by where it CLOSED in its range, not just the OI change"
  ],
  "edge_cases": [
    "Stock options may not move even when the spot/future moves (low IV / illiquid) — hence futures-only",
    "Low-liquidity names (e.g. ~150K/120K) are 'no volume' and should be avoided despite appearing in the list",
    "Oversold shorts (RSI ~25-30) can snap back / recover — historically risky to short there",
    "Deck trade examples (ADANIENT 5-6 Dec 2022, ZEEL 2 Jan '23, ABCAPITAL 2 Jan '23) are reused 2022-23 deck figures, illustrative only — NOT new 2025 data"
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Day 10 deck — 'Market Movers Strategy'",
    "Day 10 transcript (live refinements + 2025 Fortis example + liquidity reads)",
    "Shared component: OI Pulse tooling; OI interpretation (4.1)"
  ],
  "uncertain": [
    "No explicit numeric/point stop-loss is given for Market Movers stocks; the only manage/close reference is VWAP. The 2.4 instrument SLs (Nifty ~50-60, Sensex ~200-250, Bank Nifty ~100) are for index instruments, not single stocks.",
    "No explicit time-exit / holding window is stated for the strategy.",
    "Scaling/averaging mechanics for this strategy are not detailed beyond using VWAP as the final average/close level.",
    "The chart interval for the entry/confirmation candle is not stated (the 8-9 day high/low is a daily-breakout read; entry uses price vs VWAP/ST/WMA).",
    "The 1-2% target is the documented edge but the section gives no explicit point/percent stop to pair it into a fixed R:R."
  ]
}
```

### 6.4 Gap Theory
```json
{
  "name": "Gap Theory",
  "key": "gap",
  "s24_coverage": "deck-taught",
  "market_context": "Trades the small price gap that forms on the 3-minute timeframe in Nifty / Bank Nifty. A gap is created when many orders are punched in but go unfilled because price moved too quickly; the players who punched those unfilled orders bring the market back to fill them, and once filled the market resumes its short-term direction. The gap is filled ~90% of the time in the immediate next few candles (deck); live, stated more strongly that 99% of 3-min gaps fill the same day or the next day. An unfilled gap acts as a magnet pulling price toward it. Runaway / strong-momentum gaps may never fill.",
  "instruments": [
    "Nifty options",
    "Bank Nifty options"
  ],
  "timeframe": "3-minute (gap detection and trading)",
  "indicators": [
    "VWAP (entry/best-bet level when sitting near the gap fill)",
    "Super Trend (entry/best-bet level when sitting near the gap fill)",
    "RSI (exit at ~70-75-80)",
    "Volume (validates the gap)"
  ],
  "setup_preconditions": [
    "Identify a gap on the 3-minute timeframe in Nifty / Bank Nifty",
    "Establish the short-term trend direction on the 3-min timeframe (trade gap fill in the same direction as the trend)",
    "An up-move made with volume = a valid gap (Day 6); a gap that fills without volume is bearish-friendly, a fill with rising volume warns of an upside reversal (Day 17)",
    "Exclude runaway / momentum gaps where the move keeps going one direction and never comes back (e.g. everybody wants to buy, so price cannot be dragged back)",
    "If a gap stays unfilled past 1-2 days, discount it; if still unfilled, a bigger player is overriding the one trying to fill it"
  ],
  "entry_conditions": {
    "bullish": [
      "1. In an uptrend on the 3-min timeframe, identify a gap created below price (Day 6 deck).",
      "2. Wait for price to come back and quickly fill the gap (the gap-filled area is the entry level).",
      "3. If a VWAP or Super Trend sits nearby, use that as the entry / best-bet level.",
      "4. Once the gap is filled, place buy orders in the same direction as the trend (Buy CE / Sell PE).",
      "5. While the stop (the gap candle's low) is not triggered, keep adding positions as price comes lower and lower, then replenish as it moves back up (Day 6).",
      "6. Gap-fill should be validated by volume; an up-move made with volume implies a bigger move on the return to fill it (Day 6)."
    ],
    "bearish": [
      "1. When the texture is bearish, do not chase a call just to fill a gap (Day 17).",
      "2. Wait for the gap to fill, let price tag resistance, then take the down-side trade.",
      "3. A gap fill without volume is bearish-friendly (supports the short); a fill with rising volume warns of an upside reversal instead.",
      "4. Treat the unfilled gap as a level the holder defends."
    ]
  },
  "exit_conditions": {
    "target": "Exit when RSI reaches ~70-75-80 (Day 6). Deck illustrative target examples (reused 2022-23 figures): 9 Jan 2023 Bank Nifty target 42700 at 1:2.5; 31 Jan 2023 Bank Nifty target 41024.",
    "stop_loss": "The low of the candle from which the gap formed is the stop-loss territory (Day 6 deck + transcript). Alternatively, exit on the previous-candle-low stop being triggered. For the 30-60 min play (Day 21): SL of ~50-60 points from entry, or a nearby support/resistance level.",
    "time_exit": "Gap trade is a 30-60 min play only (Day 21): wait ~30-40 min; if the gap is not filled with volume, ignore it and trade with the trend instead.",
    "scaling": "While the stop (gap candle's low) is not triggered, keep adding positions as price comes lower and lower, then replenish as price moves back up (Day 6)."
  },
  "risk_management": [
    "Stop-loss = low of the candle from which the gap formed (Day 6 deck + transcript), or previous-candle-low trigger",
    "For the 30-60 min play, use a SL of ~50-60 points from entry, or a nearby support/resistance level (Day 21)",
    "Do not chase a call just to fill a gap when the texture is bearish (Day 17)",
    "Avoid runaway / strong-momentum gaps that may never fill",
    "If a gap stays unfilled past 1-2 days, discount it (Day 2)",
    "[keep-rail] Trail the stop ~5 points behind price (StructuralStop.GAP_TREND) while holding the pre-gap-candle low as the structural floor."
  ],
  "filters": [
    "Volume direction validates the gap: up-move with volume = valid gap; if the up-candle came but the fall carried the volume, market can reverse fast (Day 6)",
    "Gap fill without volume is bearish-friendly; fill with rising volume warns of an upside reversal (Day 17)",
    "A strong ~200-point rally makes a same-day fill unlikely; if not filled with volume within ~30-40 min, ignore and trade with the trend (Day 21)",
    "Operating window for the gap play is effectively a 30-60 min play (Day 21)",
    "[keep-rail] Breadth confluence: require advancing/declining breadth >32 (CE) / >32 (PE) as a coded confluence dot (ScalperGates.breadth)."
  ],
  "edge_cases": [
    "Runaway / momentum gaps where the move keeps going one direction never come back (e.g. everybody wants to buy, so price cannot be dragged back) (Day 6)",
    "If a gap is not filled the same day, players may come back to fill it the following morning or afternoon; if still unfilled, discount it -- a bigger player is overriding the one trying to fill it (Day 6)",
    "An unfilled gap acts as a magnet -- a level the market is pulled toward to fill (Day 4)",
    "Day 3 example: a ~13-point Nifty gap (high 24,806.60 -> next open 24,819) did NOT fill -- read as a large unfilled buy order with players still active",
    "Day 4 example: prior day's post-2:30 false breakout fell ~100 points, creating a 3-min gap that filled the next morning",
    "Day 11 example: low 25,217 = high 25,217 -> no gap, confirming the predicted support held"
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Gap Theory.txt (Day 6 deck)",
    "Day 2 transcript",
    "Day 3 transcript",
    "Day 4 transcript",
    "Day 6 transcript",
    "Day 17 transcript",
    "Day 21 transcript"
  ],
  "uncertain": [
    "Deck trade-example figures (9 Jan 2023 and 31 Jan 2023 Bank Nifty: entries, SLs, targets, fill times) are reused 2022-23 illustrative deck examples, NOT new 2025 data, per the doc.",
    "The deck names Bank Nifty for the base example, but the rule is applied to both Nifty and Bank Nifty per the live transcripts -- exact per-instrument tuning (e.g. volume thresholds for gap validity) is not specified for Gap Theory in S24."
  ]
}
```

### 6.5 Trending OI Crossover
```json
{
  "name": "Trending OI Crossover",
  "key": "trending_oi_crossover",
  "s24_coverage": "reference-only",
  "market_context": "Trades the crisscross of the call-OI line versus the put-OI line on the option chain as a trend-shift signal. As long as both OI lines travel together there is no trade; the crossover (criss-cross) marks the shift in direction. S24 ships NO dedicated deck for this strategy; all content comes from the live-decoding transcripts (re-taught/refined live, principally Day 7).",
  "instruments": [
    "Nifty options",
    "Bank Nifty options",
    "Sensex options"
  ],
  "timeframe": "5-minute or 15-minute chart for the crossover signal (15-minute is best)",
  "indicators": [
    "Trending OI (call-OI line vs put-OI line crisscross)",
    "Open Interest / option chain (call OI vs put OI, intraday and positional)",
    "RSI(14)",
    "Volume",
    "Super Trend (10,2)",
    "VWAP"
  ],
  "setup_preconditions": [
    "Wait for the crossover (crisscross) of the two OI lines to print; while both lines travel together there is no trade (Day 7)",
    "Read on the 5-minute or 15-minute chart (15-minute is best) (Day 7)",
    "Trade only inside the valid operating window 9:40-2:30; ignore any crossover before ~9:30/9:40 and any after ~2:30/2:40 (Day 7)",
    "Trending OI dashboard tracks 15 strikes - 7 above ATM + ATM + 7 below (5/9/11 tested; 15 gave best reliability) (Day 2)",
    "Need a >=50% (ideally 50-100%) call-vs-put OI gap that is widening for the crossover to be trade-worthy (Day 7)",
    "Intraday OI (today only, scalpers) and positional OI (yesterday + today, swing held 2-3 days max) reads should both agree; for a big move want a >50% gap between call and put OI on BOTH intraday and positional (Days 1, 2, 9, 11, 14, 19)",
    "A near-even positional read = muted move; extreme-bullish requires both positional AND intraday bullish (Days 1, 2, 9, 11, 14, 19)",
    "Avoid the day entirely when 2-3 crossovers print in a single day (totally sideways) (Day 7)",
    "[keep-rail] Price-corroboration precondition: take the crossover only when price action corroborates the OI read; do not fire on the OI cross alone if price is contradicting it.",
    "[keep-rail] Flat-OI caveat: when OI is flat / not changing on both sides (no ΔOI imbalance) there is no trending-OI signal — degrade to no-trade and wait for the ΔOI to build (engine inverts the >=50% gate here)."
  ],
  "entry_conditions": {
    "bullish": [
      "1. Put OI rising + call OI falling, with the PE line crossing above the CE line (LONG crossover) (Day 7)",
      "2. Confirm the call-vs-put OI gap is >=50% (ideally 50-100%) and widening (Day 7)",
      "3. RSI reads 50-75 after the long crossover (Day 7)",
      "4. Look for the accompanying volume spike (present on a genuine crossover ~90% of the time) (Day 7)",
      "5. Enter on the candle following the crossover (Day 7)",
      "6. Ideal bullish positional read is roughly 5cr call vs 10-12cr put (put writing exceeding call writing); both intraday and positional bullish (Days 1, 2, 9, 11, 14, 19)"
    ],
    "bearish": [
      "1. Call OI rising + put OI falling, with the CE line crossing above the PE line (SHORT crossover) (Day 7)",
      "2. Confirm the call-vs-put OI gap is >=50% (ideally 50-100%) and widening (Day 7)",
      "3. For a put buy after a short crossover, RSI reads 40-25 (below 20 = oversold - wait for a bounce) (Day 7)",
      "4. Look for the accompanying volume spike (present on a genuine crossover ~90% of the time) (Day 7)",
      "5. Enter on the following candle after the crossover (Day 7)",
      "6. To flip short you need VWAP broken AND trending OI changed direction; do not turn bearish on a VWAP/volume break alone (Days 5, 11, 12)"
    ]
  },
  "exit_conditions": {
    "target": "[keep-rail] Book a scalp move of roughly 1-2% on the option leg.",
    "stop_loss": "Scalper uses the high/low of the crossover candle as the SL; a positional player uses a nearby Super Trend / VWAP level instead (Day 7).",
    "time_exit": "Crossover read is valid only 9:40-2:30; ignore crossovers before ~9:30/9:40 and after ~2:30/2:40 (Day 7).",
    "scaling": ""
  },
  "risk_management": [
    "A second crossover against your position = exit immediately (it is the trap) (Day 7)",
    "Never average a position without watching the crossover (Day 7)",
    "Trade with the dominant side only while trending OI stays in that direction (Day 8)",
    "Below-20 RSI on a short crossover = oversold; wait for a bounce rather than buying puts (Day 7)",
    "[keep-rail] RSI-extreme trailing exit: once in a long, an RSI reading into the overbought extreme (>=80) is a trailing-exit / book cue (mirror on the short side near oversold 20).",
    "[keep-rail] Probability-graded sizing: full size only when all confirms (gap >=50% & widening, volume, RSI, both intraday+positional agree) line up; trim size when confluence is partial.",
    "[keep-rail] HIGH-probability strength grade: a drastic OI fall on one side + a fresh opposite-side build + a Short-Cover (SC) confirmation together grades the setup HIGH-probability (the strongest trending-OI read)."
  ],
  "filters": [
    "Operating window: 9:40-2:30 only (Day 7)",
    "Gap-quality gate: >=50% (ideally 50-100%) call-vs-put OI gap that is widening (Day 7)",
    "Volume confirmation: genuine crossover accompanied by a volume spike ~90% of the time; break-candle volume Bank Nifty >= 50K / Nifty >= 125K (Day 7) [keep-rail]",
    "RSI band: long 50-75, short 40-25 (avoid <20 oversold) (Day 7)",
    "[keep-rail] Futures-OI quadrant co-confirmation: confirm crossover direction against the futures-OI quadrant (LB supports longs; SC / Short Build-up the matching side).",
    "[keep-rail] OI-sentiment-slope co-confirmation: read the slope/direction of the OI-sentiment line (not just colour); a turning sentiment slope agreeing with the cross is the stronger confirm.",
    "Both intraday and positional reads must agree for a big move (>50% gap on both) (Days 1, 2, 9, 11, 14, 19)",
    "Avoid days with 2-3 crossovers (sideways) (Day 7)",
    "OI-sentiment color code: green = bullish (call OI reducing + put OI rising); red = bearish; both intraday and positional turning together is the stronger confirmation (Day 15)",
    "New-series / contradiction discipline: do not take an aggressive trade on contradictory signals (e.g. price above all indicators but OI bearish = no long) (Day 20)"
  ],
  "edge_cases": [
    "Crossover not always required (Day 8): when the call-vs-put gap is already very wide, the existing divergence is itself the signal - no crossover needed (Day 8 bearish sell-on-rise day: ~7cr call vs ~1.5cr put intraday, ~9cr vs ~3cr positional)",
    "Watch the lagging side's OI build (e.g. put OI climbing ~1.2 -> 1.5 -> 2cr) together with a fresh crossover as the cue for a possible reversal (Day 8)",
    "Fake crossover: a second crossover against your position is the trap - exit immediately; 2-3 crossovers in a single day = totally sideways, avoid the day (Day 7)",
    "Flipping direction requires VWAP broken AND trending OI changed direction together; a VWAP/volume break alone is not a trend change (Days 5, 11, 12)",
    "If positional is bullish but intraday is turning bearish, treat as only minor-bullish - wait and watch (Days 1, 2, 9, 11, 14, 19)",
    "Trending-down expiry read (Day 15): for a clear down day call-side OI should far exceed put-side (~11-12cr call vs 4-5cr put); ~60-40 is weak; don't panic while gap stays >40-50%, only worry when it narrows (e.g. 13cr vs 8-10cr)",
    "Golden-crossover companion read uses 5-7 strikes either side (Day 6) vs the trending-OI-specific 15-strike count - a context difference, not a conflict (Day 2)",
    "Day-7 Sensex-expiry contrast: a sideways read (2.5cr call / 2.68cr put, combined ~2.95-3.01cr) showed no differentiation, so no trade",
    "[keep-rail] VWAP-decisive: probability of a successful entry is low when price is sitting right at/near VWAP — prefer entries with clear distance from VWAP (the decisive line).",
    "[keep-rail] Failed-crossover test: a real (non-fake) crossover shows two opposite-signed ΔOI deltas (one line's OI building while the other's falls); if both ΔOI deltas point the same way the cross is fake/sideways noise."
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Day 7 transcript",
    "Day 1 transcript",
    "Day 2 transcript",
    "Day 5 transcript",
    "Day 6 transcript",
    "Day 8 transcript",
    "Day 9 transcript",
    "Day 11 transcript",
    "Day 12 transcript",
    "Day 13 transcript",
    "Day 14 transcript",
    "Day 15 transcript",
    "Day 19 transcript",
    "Day 20 transcript"
  ],
  "uncertain": [
    "S24 ships NO dedicated deck for Trending OI Crossover; the section is flagged reference-only, all content from live-decoding transcripts (principally Day 7), not a teaching slide deck.",
    "No explicit profit target / target rule is stated for this strategy in the S24 doc (exit_conditions.target left empty).",
    "No scaling / pyramiding rule is stated specifically for Trending OI Crossover in the S24 doc (exit_conditions.scaling left empty).",
    "Doc inconsistency: the §1 roster line labels this strategy 'Deck-taught' while the §3.5 section header explicitly states 'reference-only — S24 ships NO dedicated deck'; per the section text and task instruction, coverage is treated as reference-only.",
    "Base-mechanics details beyond what S24 re-teaches live (e.g. precise position-sizing, full target framework) are not reconstructed in the S24-only doc."
  ]
}
```

### 6.6 Golden Crossover
```json
{
  "name": "Golden Crossover",
  "key": "golden_crossover",
  "s24_coverage": "deck-taught",
  "market_context": "Rare, high-conviction same-candle crossover signal fired roughly 3-5 times per month, on a bullish or bearish move (Day 6). S24 layers live refinements on a reused 2022-23 base-mechanics deck (Days 4, 5, 6, 13); the deck trade-example figures are illustrative, not new 2025 data. Indicator hierarchy: VWAP is the most important indicator after OI and the final line of defence; VWMA(20) is the first line of defence on a dip, Super Trend the second (Day 4).",
  "instruments": [
    "Bank Nifty (index options/futures)",
    "Nifty (index options/futures)"
  ],
  "timeframe": "3-min (deck states 'Time From: 3 mins'; Super Trend set (10,2) on the 3/5-min chart)",
  "indicators": [
    "VWAP (final line of defence; most important indicator after OI)",
    "Super Trend (10,2) (second line of defence)",
    "VWMA(20) (first line of defence on a dip; also written WMA(20))",
    "RSI(14)",
    "Trending OI (companion read of 5-7 strikes above & below ATM)",
    "Volume",
    "Parabolic SAR (referenced in the clustered-indicators warning)"
  ],
  "setup_preconditions": [
    "Crossover: Super Trend (ST) and VWMA pierce VWAP together in the same candle (Day 6 deck)",
    "Trending OI of 5/7 strikes above & below ATM; the golden-crossover companion read uses 5-7 strikes either side (Day 6)",
    "Read on the 3-min timeframe (deck: 'Time From: 3 mins') (Day 6)",
    "Required parameters in place on a bullish or bearish move (Day 6 deck)"
  ],
  "entry_conditions": {
    "bullish": [
      "1. Crossover: ST & VWMA pierce VWAP together (same candle) on a bullish move (Day 6 deck).",
      "2. RSI above 50 to 75 (on the 3-min) (Day 6 deck).",
      "3. Volume: Bank Nifty >= 50K, Nifty >= 125K (Day 6 deck).",
      "4. Trade: Buy CE or Sell PE (Day 6 deck).",
      "5. Enter on the 2nd / next candle after the crossover (Day 6)."
    ],
    "bearish": [
      "1. Crossover: ST & VWMA pierce VWAP together (same candle) on a bearish move (mirror) (Day 6 deck).",
      "2. RSI between 40 to 25 (on the 3-min) (Day 6 deck).",
      "3. Volume: Bank Nifty >= 50K, Nifty >= 125K (Day 6 deck).",
      "4. Trade: Buy PE or Sell CE (Day 6 deck)."
    ]
  },
  "exit_conditions": {
    "target": "Expected follow-through after a crossover is roughly +200-300 points on Bank Nifty and +50-100 points on Nifty (Day 6). No fixed rupee target stated.",
    "stop_loss": "The crossover candle's level (Day 6).",
    "time_exit": "Trade only inside the intraday data window 9:45-2:30 (general S24 window; post-2:30 = next-day positioning); no Golden-Crossover-specific time exit stated.",
    "scaling": "Dip-buy / pyramiding shape (a general support-zone technique demonstrated on a Day-5 Super-Trend-support trade that was NOT itself a Golden Crossover, but applies to crossover dips): pyramid from Super Trend down toward VWAP; place the SL ~30-40 points below the defended territory; deploy ~20% at the Super Trend zone and reserve ~80-90% for the VWAP zone (Day 5)."
  },
  "risk_management": [
    "Stop-loss is the crossover candle's level (Day 6)",
    "Dip-buy pyramiding: place SL ~30-40 points below the defended territory; deploy ~20% at the Super Trend zone, reserve ~80-90% for the VWAP zone (Day 5)",
    "Defence ladder for the position: VWMA(20) first line, Super Trend second, VWAP final line of defence (Day 4)"
  ],
  "filters": [
    "Bullish RSI must be above 50 to 75; bearish RSI between 40 to 25 (3-min) (Day 6 deck)",
    "Volume gate: Bank Nifty >= 50K, Nifty >= 125K (Day 6 deck)",
    "Companion Trending-OI read of 5-7 strikes either side of ATM (Day 6)",
    "Signal is rare (~3-5 times per month) - high-conviction only (Day 6)",
    "[keep-rail] Strike selection for the crossover follows the global delta gate: buyer delta 0.6-0.7 within the ATM±3 band (see §4.2), not deep-OTM."
  ],
  "edge_cases": [
    "Clustered indicators warning: when VWAP, Super Trend and Parabolic SAR cluster together, bulls can clear all three in a single candle on big volume - the cluster is NOT strong resistance; be careful treating it as such (Day 6)",
    "No-trade zone: when Super Trend breaks in the morning and price then trades between Super Trend and VWAP, that is a range/erosion zone - avoid it (Days 5, 13)",
    "The Day-5 dip-buy/pyramiding shape was demonstrated on a Super-Trend-support trade Siva noted was NOT itself a Golden Crossover, but it applies to crossover dips (Day 5)"
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "How To Trade Using Golden Crossover (deck, Day 6 folder)",
    "Day 4 transcript",
    "Day 5 transcript",
    "Day 6 transcript",
    "Day 13 transcript"
  ],
  "uncertain": [
    "Base mechanics live in a reused 2022-23 deck; its trade-example figures are illustrative, not new 2025 live data - do not encode them as 2025 data (doc note + §5 item 5).",
    "S24 surfaced NO new 2025 live Golden Crossover trade; all tabled deck examples (bearish crossover OI 60-66 -> 72/36; 23-Aug-22 Nifty long 12:48 OI 90 -> 60/70; 16-Sep-22 short 13:09 RSI 27 vol 50K; 26-Sep-22 long 12:39 RSI 69; 8-Aug-22 long 10:15 RSI 58) are illustrative 2022 deck figures, not 2025 live data.",
    "§5 item 3: confirm the three S24 strike-count reads are context-specific and not in conflict - Trending-OI dashboard = 15 strikes (7 above + 7 below + ATM), golden-crossover companion = 5-7 strikes either side (Day 6), expiry-OI = 5/10 strikes (Day 9 deck); they are separate inputs, not a single conflicting value.",
    "Doc gives no explicit Golden-Crossover-specific time exit or rupee profit target beyond the +200-300 BN / +50-100 N follow-through expectation.",
    "The dip-buy/pyramiding sizing (~20% at Super Trend, ~80-90% at VWAP, SL ~30-40 pts below territory) is a general support-zone technique imported into the Golden Crossover from a non-crossover Day-5 example; confirm its applicability before encoding as a crossover-specific rule."
  ]
}
```

### 6.7 Hero-Zero (Expiry-Day OI)
```json
{
  "name": "Hero-Zero (Expiry-Day OI)",
  "key": "hero_zero",
  "s24_coverage": "deck-taught",
  "market_context": "Expiry-day-only play. Framed not as a blind hero-or-zero gamble but as 'a strategy to identify where a buyer and a seller exist' — you sit as a buyer on the side the writers are about to vacate. The move is driven by writers covering in the last 30-40 minutes of the session (short covering / long unwinding); it is a closing-hours play, NOT a gamma blast. Reward typically doubles, sometimes 5x-10x. Direction is two-sided depending on VIX + OI: high VIX favors an end-of-day downside sell-off, but positive ~2:45 news or strong call-side short-covering can produce a fast upside move.",
  "instruments": [
    "Index options (Nifty)",
    "Bank Nifty options (caution: with daily expiries removed, BN volumes have dried up and it no longer reacts as before)",
    "Sensex / Bankex options (deck example uses round strikes like 40,000 / 40,500 / 41,000 / 42,000)"
  ],
  "timeframe": "Expiry day only; executed roughly 2:45-3:15 PM (closing hours). Deck guidance: look at 1-2 strikes above ATM between 2:30 and 3 PM.",
  "indicators": [
    "Open Interest (four quadrants) — the bedrock read",
    "OI Pulse / NSE 'OI Expiry Strategy' feature (pull derivative data after close)",
    "Premium / discount across strikes",
    "VWAP (no trades when market is held at VWAP level — premium erosion)",
    "India VIX (high VIX favors end-of-day downside sell-off)",
    "Implied Volatility (expiry-day IV crash in the second half; elevated CE vs PE = crowd expecting an up-move)"
  ],
  "setup_preconditions": [
    "Trade only on expiry day",
    "Pull derivative data after close (NSE / OI Pulse 'OI Expiry Strategy' feature) and review the entire week's data to understand how to play the weekly expiry",
    "Select 5 strikes either side of ATM to analyse; widen to 10 strikes either side if huge volatility/spread is expected",
    "Round strikes are a must (e.g. 40,000 / 40,500 / 41,000 / 42,000) — that is where most of the action happens",
    "Deploy only ~10% of your PROFITS — never deploy your capital in hero-zero",
    "On monthly-expiry day, ignore the OI/expiry data — writers unwind prior-month positions so the data reflects the past month, not the coming series"
  ],
  "entry_conditions": {
    "bullish": [
      "1. Identify that buyers are on the call side: a long build-up on the put side (OI up + price up >50%) with sellers writing calls; when put buyers start unwinding, sellers shift from writing calls to writing puts, so the call side moves higher on expiry day — sit as a buyer on the call side.",
      "2. Confirm the side trading at a DISCOUNT and expect writers to cover their position in maximum; favor the side whose premium is LOWER.",
      "3. Expiry-day OI read (Day 17): an ideal CE long-build needs the strike closing >=50% up AND near the day's high with a big OI jump (scan 2-3 strikes above and 2 below the close).",
      "4. Direction by second-half flow: if call-side OI is rapidly rising while the other side covers, take the PUT side (and vice-versa) — i.e., take the side the writers are vacating.",
      "4b. [keep-rail] Cross-side LB/SB double-confirmation — a long-build-up on one side that mirrors a short-build-up on the other confirms the directional vacate; require the cross-side pattern before committing direction.",
      "5. Strike selection by aggression: ATM or one strike above ATM (OTM) for the aggressive play; ITM or 1-2 above for a safer bet.",
      "6. Enter in the closing hours (~2:45-3:15 PM; deck: 1-2 strikes above ATM between 2:30-3 PM) to ride writers covering in the last 30-40 minutes.",
      "7. An upside fire can come from positive ~2:45 news or strong call-side short-covering.",
      "8. [keep-rail] Strike pick = one strike below the short-covering strike; avoid initiating fresh hero-zero entries in the 10:00-14:00 dead window (the play is the closing-hours cover)."
    ],
    "bearish": [
      "1. Identify a short build-up signalling buyers exiting that side: OI up >50% with price down >50%; short covering + long unwinding signals buyers are exiting that side — sit opposite as the buyer.",
      "2. Expiry-day OI read (Day 17): for a put-side short-build want price falling ~70-78% AND a large OI jump (~85%).",
      "3. Sellers write the side with the higher premium and pump funds where they look for weakness in buyers — sit on the opposite (discount/lower-premium) side as the buyer.",
      "4. Direction by second-half flow: if put-side OI is rapidly rising while the call side covers, take the CALL side; in general favor the side whose premium is lower.",
      "5. High VIX favors an end-of-day downside sell-off — supports a put-side hero-zero into the close.",
      "6. Enter in the closing hours (~2:45-3:15 PM) on writers covering / unwinding."
    ]
  },
  "exit_conditions": {
    "target": "Reward typically doubles, sometimes 5x-10x. Deck: at 1-2 strikes above ATM (~20-50 price) between 2:30-3 PM, price usually doubles or triples in 30-45 points; on serious short covering Bank Nifty can give a quick 100-200 point move.",
    "stop_loss": "Set a level: if it breaks, let it go to zero; if it rises, keep trailing. Do NOT average a losing hero-zero position. (Instrument-scaled deep SLs apply generally: Nifty ~50-60 pts, Sensex ~200-250 pts, Bank Nifty ~100 pts — §2.4.) [keep-rail] 50%-of-premium stop on the long option — if the bought premium halves, cut it (in addition to the 'let it go to zero on a level break' framing). [keep-rail] Index point-SL set ALONGSIDE the deep-SL set — index-scaled point stops Bank Nifty ~75 pts / Nifty ~30 pts apply as the tighter intraday point-SL; do NOT conflate with the deeper §2.4 set.",
    "time_exit": "Closing-hours play — must be done during the closing hours (~2:45-3:15 PM; deck 2:30-3 PM); driven by writers covering in the last 30-40 minutes of the session. [keep-rail] Hard square-off by 3:20 PM — if the writers' cover has not paid by ~3:20, exit flat rather than carry into settlement. [keep-rail] 3:10 PM no-move exit — if the expected writers'-cover move has not begun by ~3:10, abandon the trade; do not wait for the close hoping it fires.",
    "scaling": "Let the price come to you; do NOT average a losing position. Set a level — if it breaks let it go to zero, if it rises keep trailing."
  },
  "risk_management": [
    "Deploy only ~10% of your PROFITS — never deploy/risk your capital in hero-zero",
    "Never average a losing hero-zero position",
    "Set a level: if it breaks let it go to zero; if it rises keep trailing",
    "Prefer low-premium strikes near expiry so max loss is capped at the small premium; with high premiums prefer ITM/deep-ITM (the OTM crash is bigger on a fall)",
    "Global caps still apply: never lose beyond 10-12% of capital in one day; per-trade ~10% outer cap (§2.3); deep instrument-scaled SLs (§2.4)",
    "[keep-rail] RSI gate applies (overbought/oversold guard) — use the operative global RSI framework (buy 50-75 / no-trade 40-50 / sell 40-25 / overbought 80 / oversold 20); the gate stays, but no S7-local RSI number is carried."
  ],
  "filters": [
    "Both-sides long unwinding (or sellers adding on both call and put) = premium erosion only / pin — a buyer cannot make money on either side; AVOID the day",
    "[keep-rail] IV-flat-on-both-sides = no-trade — when implied volatility is flat across both call and put sides there is no writer-cover asymmetry to harvest; skip the day.",
    "[keep-rail] Round-strike double-zero pin warning — at a heavily-written round strike a 'double-zero' pin can hold price exactly at the strike into expiry, eroding both sides to zero; flag it and stand aside.",
    "If strikes are closing near VWAP, expect premium erosion — no trades when the market is held at the VWAP level",
    "Monthly-expiry day: ignore the OI/expiry data (writers unwind prior-month positions; data reflects the past month, not the coming series)",
    "Day 17 inconclusive-data filter: if one side qualifies (CE long-build or PE short-build thresholds) but the other does not, the data is inconclusive — do not be aggressive on the non-qualifying side",
    "Bank Nifty caution: with daily expiries removed, BN volumes have dried up and it no longer reacts as before — be careful",
    "Favor the side trading at a discount / lower premium (sellers wanting out must give up the discount plus the move); prefer the side whose premium is lower"
  ],
  "edge_cases": [
    "Late writers (at the bottom) lose, not day-1 writers — a strike can keep building OI while premium falls before it pops",
    "On expiry-eve, a discount at a strike signals writers may do a morning adjustment / possible gap; elevated CE vs PE = crowd expecting an up-move and paying up, which can crash if the move fails",
    "Expiry-day IV crash occurs in the second half: call IVs fall as call buyers exit while put IV stays stable if put buyers hold",
    "A strike whose OI stays high while price has already jumped (writers holding the fort) caps the move — OI crossing a key mark (Day 19: the 40-mark) would signal a big breakout",
    "Strike selection by aggression: ATM/1-above-ATM (OTM) = aggressive; ITM or 1-2 above = safer bet",
    "[keep-rail] LU-vs-SC discriminator — distinguish long-unwinding (buyers exiting, OI falling) from short-covering (writers buying back the written side, OI falling); both drop OI but only the writer-cover side is the one to sit as buyer."
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "OI Expiry Strategy 10th Mentoring deck (Day 9)",
    "How To Identify Hero Or Zero — Expiry Day deck (Day 10)",
    "Day 9 transcript",
    "Day 10 transcript",
    "Day 11 transcript",
    "Day 17 transcript",
    "Day 18 transcript",
    "Day 19 transcript",
    "Day 20 transcript",
    "Day 21 transcript"
  ],
  "uncertain": [
    "§5 item 5: several Hero-Zero figures in S24 days are explicitly reused-deck examples (e.g. Day 10 18-Feb Bank Nifty hero-zero: thin-volume BN-future expiry, call OI crossing 1cr at 1:45 PM, 36400 CE moving 92->280 in 30 min) — these are NOT new 2025 live data and must not be encoded/reported as such.",
    "Precise numeric stop-loss / max-loss for hero-zero is given only qualitatively ('let it go to zero', deploy ~10% of profits) — no fixed rupee/point SL is specified beyond the general instrument-scaled deep SLs in §2.4.",
    "Exact OI/price percentage thresholds differ by source (Day 9 deck uses >50% premium move + OI drop; Day 17 uses CE >=50% up near high vs PE ~70-78% down + ~85% OI jump) — confirm which threshold set to encode for which read.",
    "The two-sided direction logic (VIX-driven downside sell-off vs 2:45-news/short-covering upside) is described conceptually; the doc does not give a single deterministic gate for choosing the side when both pulls are present."
  ]
}
```

### 6.8 BTST / STBT
```json
{
  "name": "BTST / STBT",
  "key": "btst_stbt",
  "s24_coverage": "reference-only",
  "market_context": "Overnight options carry. BTST ('Buy Today, Sell Tomorrow') carries an options position overnight to capture an expected gap-up the next morning; STBT is the bearish mirror. S24 ships no dedicated deck for BTST/STBT; all content is drawn live from the daily decoding transcripts (Days 2, 3, 8, 9, 18), not a teaching slide. Best context is a low-VIX regime, which can reward overnight holders.",
  "instruments": [
    "Index options (Nifty CE/PE; ATM or near-ATM strikes, e.g. 24,500 CE example)"
  ],
  "timeframe": "Overnight hold (carry today's position into the next session); intraday read on the carry day for the validity gate",
  "indicators": [
    "VWAP (validity gate: market must not breach VWAP and must hold strength into the close; a close that reclaimed/held VWAP with volume and never broke back below VWAP is the textbook condition)",
    "Super Trend (validity gate: market must not breach Super Trend; never broke back below VWAP/Super Trend)",
    "WMA / VWMA (a strong VWAP / Super Trend / WMA close confirms the carry, Day 2)",
    "Volume (the close must hold strength with volume; news-driven up-move needs volume-backed follow-up)",
    "OI / short covering (Day 9 example: 24,500 CE closed +55% on short covering)",
    "[keep-rail] Strike/leg: ATM±3 at delta ~0.6-0.7; by side Buy-Future / Sell-PE (bullish) and Buy-CE option leg; carry the INDEX option only, never an OTM leg; expect ~80-90% premium crush on the wrong side / ~50% run-up on the right side (S23)"
  ],
  "setup_preconditions": [
    "After the day's move, the market does NOT breach VWAP or Super Trend and holds the strength into the close (Days 2, 3, 18)",
    "Textbook BTST condition: a close that reclaimed/held VWAP with volume and never broke back below VWAP/Super Trend (Day 18)",
    "Do NOT blindly trust a news-driven single-day rally after a multi-day fall; require 2-3 days of consistent, volume-backed follow-up before treating the up-move as real (Day 9)",
    "Do NOT take a BTST near expiry just because the market inches up in the last 30 minutes; if the morning high was not reclaimed in the second half, carrying overnight is risky (Day 8)",
    "Profit-protection override: after a big intraday hit where you have already recovered ~80-90%+ of the day's profit, square off rather than carry overnight (Day 18)"
  ],
  "entry_conditions": {
    "bullish": [
      "1. (BTST) Confirm a strong close: the market holds strength into the close and does NOT breach VWAP or Super Trend after the day's move (Days 2, 3, 18).",
      "2. Confirm the textbook condition: the close reclaimed/held VWAP with volume and never broke back below VWAP/Super Trend (Day 18); a strong VWAP / Super Trend / WMA close is the confirming signal (Day 2).",
      "3. Reject false triggers: do not carry on a news-driven single-day rally after a multi-day fall without 2-3 days of volume-backed follow-up (Day 9); do not carry near expiry on a last-30-minute uptick if the morning high was not reclaimed in the second half (Day 8).",
      "4. Carry an ATM / near-ATM call (e.g. 24,500 CE) overnight, committing only 5-10% of capital to the overnight position (Days 2, 3).",
      "5. Plan to exit early the next morning, targeting the expected gap-up / morning move (Days 2, 3, 18)."
    ],
    "bearish": [
      "1. (STBT) The bearish mirror of BTST: carry an overnight position for an expected gap-down the next morning. S24 names STBT as the bearish mirror but the daily transcripts decode the bullish BTST side; the symmetric bearish validity gate (must hold weakness / fail VWAP-Super Trend on the downside) is not separately spelled out in S24 (see uncertain)."
    ]
  },
  "exit_conditions": {
    "target": "Capture the next-morning gap-up / open move (Day 2 expected a 100+ point gap-up; Day 18 textbook BTST paid off with a gap-up and a ~500-point rally from the prior day's low 25,550 toward ~26,000). Exit at the open even if the gap is small (Day 2 capped to ~20 points by overnight US news, still exited in profit).",
    "stop_loss": "S24 gives no explicit overnight stop-loss level for the carry; the validity gate (no VWAP/Super Trend breach, strength held into the close) and the 5-10% capital cap are the risk controls. Profit-protection override: protect the previous-day profit plus >=70-80% of intraday profit before taking fresh overnight risk; square off instead of carrying if you have already recovered ~80-90%+ of the day's profit after a big hit (Day 18).",
    "time_exit": "Always exit early the next morning at / near the open; the play targets the open / morning move, not a multi-day hold (Days 2, 3, 18).",
    "scaling": "Commit only 5-10% of capital to the overnight position (Days 2, 3); the Day 2 example used ~10% of capital on the ATM 24,500 CE."
  },
  "risk_management": [
    "Size the overnight position at only 5-10% of capital (Days 2, 3)",
    "Honesty about traps: BTST works roughly 6-7 of 10 times; 3-4 of 10 get trapped, and operators deliberately trap overnight players on the off days (Day 9)",
    "Profit-protection override: after a big intraday hit, if ~80-90%+ of the day's profit is already recovered, square off rather than carry overnight (Day 18)",
    "Before taking fresh overnight risk, protect the previous-day profit plus >=70-80% of the intraday profit (Day 18)",
    "Always exit early the next morning; do not let an overnight carry become a multi-day hold (Days 2, 3, 18)",
    "[keep-rail] Hold a 50%-premium stop on the carried leg alongside the validity gate and the 5-10% capital cap; carry only one night and avoid a fresh Friday carry",
    "[keep-rail] Do not carry an overnight stock position whose daily RSI is already >75"
  ],
  "filters": [
    "Validity gate: carry overnight only when the market does NOT breach VWAP or Super Trend and holds strength into the close (Days 2, 3, 18)",
    "Distrust news-driven single-day rallies after a multi-day fall; require 2-3 days of consistent volume-backed follow-up (Day 9)",
    "Near expiry, do not carry just because the market inches up in the last 30 minutes when the morning high was not reclaimed in the second half (Day 8)",
    "A low-VIX regime can reward overnight holders (cross-referenced from time-of-day read, §4.9)",
    "[keep-rail] carry-day OI four-quadrant read (Long Build-Up / Short Covering support a bullish carry; Short Build-Up / Long Unwinding warn against it; muted on derived history)",
    "[keep-rail] 3:15pm confluence: Futures-OI direction + Option-OI Trending/Sentiment + global cues must align with the carry side before holding overnight",
    "[keep-rail] RSI directional gate: carry bullish only when RSI is directional and not overbought (>75), STBT mirror only when not oversold (<20) (operative bands buy 50-75 / no-trade 40-50 / sell 40-25 / overbought 80 / oversold 20)"
  ],
  "edge_cases": [
    "Overnight global news can cap the gap: Day 2 expected a 100+ point gap-up but overnight US news limited it to ~20 points (still exited in profit at the open)",
    "Trap outcome (the ~3-4-in-10 case): Day 9 the 24,500 CE closed Wednesday ~385 (+55%) on short covering (a perfect-looking BTST), but Day-9 opened only 350-370 with a low of 300 (no gap-up, global pressure), catching overnight players",
    "Big-hit recovery case: on Day 18, after a big intraday hit and recovering ~80-90%+ of the day's profit, square off rather than carry overnight (parameters against you make the overnight position hard to justify)",
    "Last-30-minute expiry uptick is a false trigger if the morning high was not reclaimed in the second half (Day 8)"
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Day 2 transcript",
    "Day 3 transcript",
    "Day 8 transcript",
    "Day 9 transcript",
    "Day 18 transcript"
  ],
  "uncertain": [
    "S24 coverage is flagged reference-only in the §3.8 body ('no dedicated deck'), but the §2 strategy roster table labels it 'Deck-taught (decoded Days 2, 3, 8, 9, 18)' — an internal discrepancy; treated as reference-only per the §3 body.",
    "STBT (bearish mirror) base mechanics are not separately re-taught in S24 — only named as the bearish mirror; the symmetric bearish validity gate, sizing, and exit are not decoded in the transcripts.",
    "No explicit overnight stop-loss price/level is given for the carry; risk is controlled only via the validity gate, the 5-10% capital cap, and the profit-protection override.",
    "Base BTST/STBT mechanics (full entry trigger sequence, target/SL math) are not taught from a dedicated deck; only the live refinements and examples Shiva narrated are captured."
  ]
}
```

### 6.9 Morning Trade
```json
{
  "name": "Morning / Opening Trade",
  "key": "morning_trade",
  "s24_coverage": "reference-only",
  "market_context": "The opening trade is the single riskiest trade of the day, taken right at / just after the 9:15 open, before the regular intraday data window (9:45-2:30) settles. S24 ships NO dedicated deck; all content is from the daily live-decoding transcripts (chiefly Day 12 and Day 17) where Shiva runs the opening trade live. It is a directional, discretionary play on the index, sized off a pre-defined exit and the prior day's profit. Read the gap to pick a side: a gap-up is expected to be sold off at least once (short candidate), while a big gap-down is already oversold (no fresh put edge).",
  "instruments": [
    "Index options (Nifty / Sensex) at the open"
  ],
  "timeframe": "Opening minutes after the 9:15 open (often a sub-1-minute scalp, e.g. Day 12 entry 9:15:02 exit 9:16:13); pre-market settles ~9:07-9:08; sits before the 9:45-2:30 intraday data window settles",
  "indicators": [
    "Settled pre-market open (~9:07-9:08) used to compute fair value and the operative premium range",
    "Gap size and direction (gap-up ~30-40 pts vs big gap-down ~300-400 pts)",
    "Pre-market heavyweights (Reliance / Infosys / HDFC Bank / TCS) moving +2-4% can add ~80-100 Nifty points and hold the index up",
    "Prior-day VWAP / bounce bottom of the chosen strike (used as the stop level)",
    "Prior-day OI (put buyers holding tight restricts to call side; on expiry day)",
    "Delta (~0.80) to estimate the premium move from the index move (Day 12: ~100-pt index drop ~= ~80-pt premium move)"
  ],
  "setup_preconditions": [
    "Wait for the pre-market to settle (~9:07-9:08); ignore the initial +/-200-pt pre-open swings before reading fair value (Day 12)",
    "Have a pre-defined exit / stop strategy in place BEFORE entering - never attempt the opening trade without one (Days 12, 17)",
    "Freshers must never attempt it unless direction is already known (Day 12)",
    "On expiry day with prior-day OI showing put buyers holding tight, restrict the opening trade to the CALL side only (Day 11)",
    "[keep-rail] Wait for the second candle to break the first candle's high (CE) / low (PE) with a rejection wick at the level before entering — the gap alone is not the trigger (Days 12, 17)",
    "[keep-rail] Pre-open bias formed the prior evening: a convincing close in the trend direction + prior-day 3:20pm OI alignment + a >50% direction-change in OI carry the view into the open (Day 11; EOD read)"
  ],
  "entry_conditions": {
    "bullish": [
      "1. Wait for the pre-market to settle (~9:07-9:08) and compute fair value / the operative premium range from the settled open (Day 12)",
      "2. On expiry day, when prior-day OI shows put buyers holding tight, restrict the opening trade to the call side only (Day 11)",
      "3. Select a strike 2-3 strikes away from the settle (not deep OTM); prefer slightly ITM in the morning for intrinsic value / less time-value bleed, rotating to higher-priced strikes as expiry nears (Days 12, 17)",
      "4. If the market opens gap-up AND overbought, the morning opportunity is small - wait instead for the later consolidation window for those who missed the open (Day 20)",
      "5. Enter the opening CE with a pre-set exit defined first; honor the pre-set SL and do NOT average on an adverse move (Days 12, 17)"
    ],
    "bearish": [
      "1. Wait for the pre-market to settle (~9:07-9:08) and compute fair value / operative premium range from the settled open (Day 12)",
      "2. Read the gap: prefer a modest gap-up (~30-40 pts up with global cues flat/green) to short once, because a gap-up is expected to be sold off at least once; on a big gap-down (~300-400 pts) do NOT buy a put - already oversold, yesterday's put buyers are booking, no fresh edge (Day 12)",
      "3. Before shorting, check pre-market heavyweights - Reliance / Infosys / HDFC Bank / TCS opening up 2-4% can add ~80-100 Nifty points and hold the index up, so a short into that can trap you (Day 12)",
      "4. Select a strike 2-3 strikes away from the settle (not deep OTM); prefer slightly ITM in the morning, rotating to higher-priced strikes as expiry nears (Days 12, 17)",
      "5. Estimate fair entry premium via delta (~0.80: a ~100-pt index drop ~= ~80-pt premium move) (Day 12)",
      "6. Enter the opening PE with the pre-set exit defined first; honor the pre-set SL and do NOT average on an adverse move (Days 12, 17)"
    ]
  },
  "exit_conditions": {
    "target": "Quick scalp aimed at the morning move only - e.g. Day 12 took >1% return in ~1 minute (entry 9:15:02, exit 9:16:13) with <Rs.2 lakh deployed",
    "stop_loss": "Set the stop at the strike's prior-day VWAP / bounce bottom and exit quickly on a close below it; on a news-driven adverse move honor the pre-set SL and do NOT average (Days 12, 17). Use the previous day's profit as the stop-loss budget (Days 12, 17).",
    "time_exit": "An opening-minutes play; exit quickly (Day 12 example was a ~1-minute scalp). Sits before the 9:45-2:30 intraday window settles.",
    "scaling": ""
  },
  "risk_management": [
    "The single riskiest trade of the day - 'the most, most risky trades'; only attempt with a pre-defined exit in place (Days 12, 17)",
    "Risk only ~10-20% of capital on the opening trade (Days 12, 17)",
    "Use the previous day's profit as the stop-loss budget (Days 12, 17)",
    "Honor the pre-set SL and do NOT average on a news-driven adverse move; exit when the SL is hit and OI data turns against the position (Days 12, 17)",
    "Anyone not comfortable with opening trades should never attempt them; freshers must never attempt unless direction is already known (Days 12, 17)",
    "[keep-rail] Trail stop to breakeven after the move goes in favour; book initial profit; secondary exit if RSI rolls back below 30; treat Open=High on the position as an exit/CE-hedge trigger; add only around the prior close (Days 12, 17)"
  ],
  "filters": [
    "Wait for the settled pre-market (~9:07-9:08); ignore the initial +/-200-pt pre-open swings (Day 12)",
    "Do not act on Gift Nifty / overnight Dow at the open - wait for the local pre-market; re-check Dow futures right before the open since a sudden +50-100 pt swing changes the setup (Days 1, 3, 4, 5, 12, 16; Day 12)",
    "Big gap-down (~300-400 pts) = no fresh put edge (already oversold, yesterday's put buyers booking) (Day 12)",
    "Modest gap-up (~30-40 pts, global cues flat/green) = a short candidate (gap-up expected to be sold off at least once) (Day 12)",
    "Check pre-market heavyweights (Reliance / Infosys / HDFC Bank / TCS up 2-4%) before shorting - they can add ~80-100 Nifty points and hold the index up (Day 12)",
    "Gap-up + overbought open = small morning opportunity; wait for the later consolidation window (Day 20)",
    "Expiry-day with prior-day OI showing put buyers holding tight = restrict to call side only (Day 11)",
    "[keep-rail] Confirm breadth before shorting: advancing >32 / declining >32 of constituents must move with the side (FULL/live gate) (Day 12)",
    "[keep-rail] Cross-check the OI-confluence gate (low / mid / high) against the settled pre-market read (~9:11 gate read, ~9:18 exit-timing check; timing loose) (Day 12)"
  ],
  "edge_cases": [
    "Strike selection: 2-3 strikes from the settle (not deep OTM); slightly ITM in the morning for intrinsic value / less time-value bleed, rotating to higher-priced strikes as expiry nears (Days 12, 17)",
    "News-driven adverse move (e.g. Day 17 Delhi-blast opening CE: went against plan, fell 260 -> 222, ran to ~145 where the ~150-160 SL triggered) - exit at the SL with no averaging once OI data turns bearish; day still netted ~Rs.2 lakh overall (Day 17)",
    "Day 12 expiry-day example (Sensex 82,200 PE): index settled ~81,900; fair entry ~340-380 via delta ~0.80; entered ~370-380 at 9:15:02, exited 9:16:13 (~1 min), <Rs.2 lakh deployed for >1% return"
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Day 11 transcript",
    "Day 12 transcript",
    "Day 17 transcript",
    "Day 20 transcript"
  ],
  "uncertain": [
    "Reference-only: S24 ships NO dedicated Morning/Opening Trade deck and does not re-teach the strategy's base mechanics; only the live opening-trade reads from the daily transcripts (chiefly Days 12, 17) are captured here.",
    "Base mechanics not stated by S24: the full opening-trade construction (formal entry trigger, fixed target rule, range/scenario classification, position-sizing ladder) is carried in earlier-session bodies and not reconstructed in the S24-only doc.",
    "No scaling rule given for this strategy in S24 (exit_conditions.scaling empty).",
    "Strike-side direction for the bullish entry is only explicitly gated by S24 via the expiry-day call-side-only constraint (Day 11) and the gap-up-overbought 'small opportunity' note (Day 20); S24 does not lay out a standalone bullish opening-trade trigger comparable to the gap-up short read."
  ]
}
```

### 6.10 Options Scalping Framework (Connect the Dots)
```json
{
  "name": "Options Scalping Framework (Connect the Dots)",
  "key": "scalping_framework",
  "s24_coverage": "deck-taught",
  "market_context": "The master read that 'connects the dots' across all other strategies: a checklist of cues that must align before any scalp. Scalp on 3/5-min, positional on 15-min/hourly. Both chart AND data (intraday + positional) must align or it is an avoid. Indicator hierarchy: VWAP is the most important indicator after OI; on a fall the defence order is VWMA(20) first, Super Trend second, VWAP final (VWAP is the biggest support after OI). Indicators far from candles after a breakout = avoid scalping (price won't return to support). Low-VIX regime (~10-10.9 through the 2025 period); a low-VIX day rewards overnight holders but caps intraday to a 50-60 pt band.",
  "instruments": [
    "Nifty options (3-min scalp timeframe)",
    "Bank Nifty options",
    "Sensex options (traded via the Nifty chart)",
    "Index options preferred over stock options (far more liquid)"
  ],
  "timeframe": "Scalp on 3-min / 5-min candles (avoid 1-min); positional read on 15-min / hourly. Intraday data window 9:45-2:30 (post-2:30 = next-day positioning).",
  "indicators": [
    "VWAP (default settings) - central gravity / final line of defence, most important indicator after OI",
    "Super Trend (10, 2) - gate / second line of defence",
    "Volume candle - 50K (Bank Nifty) & 125K (Nifty)",
    "RSI(14) with 80:20 bands",
    "Parabolic SAR (0.02, 0.2) - first signal",
    "VWMA(20) - first line of defence on a fall",
    "Open Interest - OI spurts / 4 quadrants; OI strikes & futures",
    "India VIX - volatility regime",
    "IV - 6-strike read (3 above + 3 below ATM)",
    "Dow 30 futures (US30) - primary all-day global directional cue"
  ],
  "setup_preconditions": [
    "[keep-rail] Defining aggregate gate: the Connecting-Dots read is only a trade when the AGGREGATE of the dots resolves to a single Bullish or Bearish verdict — i.e. chart AND data (intraday + positional) point the same way. A mixed / unresolved aggregate is a no-trade; this aggregate Bullish/Bearish alignment is the strategy's hard gate, not optional confluence.",
    "Connect the dots before any scalp: Dow 30 futures, India VIX, OI spurts (4 quadrants), OI strikes & futures, IV (6 strikes), VWAP, Super Trend, volume candles, RSI, Parabolic SAR all read and aligned",
    "Operate within the intraday data window 9:45-2:30",
    "Chart AND data (intraday + positional) must align, else avoid",
    "5 chart dots plotted on the 3-min timeframe with their settings: VWAP default, Super Trend (10,2), Volume candle 50K/125K, RSI(14) 80:20, Parabolic SAR (0.02,0.2)",
    "Dow futures re-checked right before the open (a sudden 50-100 pt swing changes the setup); do not act on Gift Nifty / overnight Dow at the open - wait for the local pre-market (9:07-9:08)"
  ],
  "entry_conditions": {
    "bullish": [
      "1. OI quadrant read: rise in OI + rise in price = bullish (look for ~50% rise in OI & price)",
      "2. OI quadrant: slide in OI + rise in price = short covering / long liquidation (controlling side identified by a >=50% premium move with OI dropping)",
      "3. VIX scenario: market higher + VIX cooling = bullish; positional band 10-11 = buy-on-dips, deploy full",
      "4. IV (6-strike) read favours a bullish buy: call-side IV higher by an 8-10 point gap (e.g. 16/8, 15/8, 25/15; 30/20 bullish on the higher-IV side when the gap is 10+ pts and the market moves that way); CE/PE 10/10 = low IV good for a trend play; lower IV favours buyers",
      "5. Parabolic SAR (0.02,0.2) gives the first signal; Super Trend (10,2) acts as the gate; price holds above VWAP (final defence) and above VWMA(20)",
      "6. RSI(14) in the buy zone 50-75 (profit-book 75-80/85; >80-85 = no fresh longs; 40-50 = no-trade); never short into overbought",
      "7. Volume candle confirms the break (>50K Bank Nifty / >125K Nifty) - a level/VWAP break without volume can reverse in one candle",
      "8. Index Contribution / breadth confirms: advancing-vs-declining count and the top 5-6 heavyweights moving together (+1-3%)",
      "9. Trending day cadence confirms: a fresh hourly high roughly every hour (~10:00, ~11:00, ~12:00-12:15); escalating premium (~20 pts morning -> 60 post-10:00 -> 90 post-10:30 -> 110 post-11:00) confirms genuine trend",
      "10. Discount-premium read supports an up-move: ITM call below intrinsic / puts at a ~5% discount with no buyers (LTP - premium = intrinsic; below-intrinsic = discount, favourable to the buyer)",
      "11. Buy ATM or 1-2 strikes ITM (avoid deep-OTM lottery strikes); delta >= 0.7 (~0.8 near expiry); Dow futures (primary cue) supportive"
    ],
    "bearish": [
      "1. OI quadrant read: rise in OI + slide in price = bearish (short build-up); identify controlling side via a >=50% premium move with OI dropping",
      "2. VIX scenario: market higher + VIX rising = bearish/danger; market lower + VIX rising = bearish; for a downside trending day VIX must climb all day (vertical, every 15 min) and close near its high",
      "3. IV (6-strike) read: a higher put-side IV while the market is up (e.g. call 13 / put 15) warns put premiums can crash later; higher IV favours sellers",
      "4. Parabolic SAR (0.02,0.2) first signal turns down; price loses VWMA(20) (first defence), then Super Trend (second), then VWAP (final) - a confirmed break of VWAP on volume is required for a real breakdown",
      "5. RSI(14) in the sell zone 40-25 (profit-book 25-20; <20 = avoid; 40-50 = no-trade); never short into overbought",
      "6. Volume candle confirms the breakdown (>50K Bank Nifty / >125K Nifty) - a clean VWAP break NOT on volume points to the next big support, not a genuine breakdown",
      "7. Breadth confirms downside: declining count exceeds advancing; heavyweights falling together (big traders may suppress 1-2 heavy stocks to cap the index)",
      "8. Long-unwinding signature confirms a fade: OI down + price down + IV down together; IV-equal both sides (8&8, 9&9, 10&10) => premium erosion unless a new high forms every 30 min",
      "9. Buy ATM or 1-2 strikes ITM on the put side, delta >= 0.7; Dow futures (primary cue) confirm the down-bias"
    ]
  },
  "exit_conditions": {
    "target": "Aim for 1% per day then stop and log out (~Rs25,000/day on a Rs25 L account; ~Rs5,000/day on Rs5 L); daily target scales with account size (0.5-1%/day at Rs5-10 L, ~2%/week at Rs2-5 Cr, occasional 3-4% days). Low VIX/IV -> large quantity, small point target (~4 pts), many small scalps (+3-4/-2-3 rupees). High VIX/IV -> small quantity (~100), larger point target (100-200 pts), or stay out. Trending day: expect a fresh hourly high, book profits after ~1:30, stay away after ~2:30.",
    "stop_loss": "Deep stop-losses are mandatory (shallow 5-10 pt SLs get hunted). Nifty SL ~50-60 pts; Bank Nifty SL ~100 pts; Sensex SL ~200-250 pts (set ~200 wide and revise as the trade moves in favour). Once a fall breaks VWAP, accept failure and exit - never average below VWAP (wipes out 70-80% of capital). Single-day loss cap 10-12% of capital; per-trade conservative guideline ~1-2% of total capital (outer cap ~10% of deployed). [keep-rail] Structural stop-loss mode (alongside the deep point-SLs): use the 1st-candle low (long) / 1st-candle high (short) as the structural stop — if breached, the fort is gone, exit; a scalper then trails the previous-candle high/low while a positional player keeps the original 1st-candle level and can run all day. The wide point-SLs (N ~50-60, BN ~100, Sensex ~200-250) and this structural stop are two SL modes, not one — do not conflate.",
    "time_exit": "Trade within the intraday data window 9:45-2:30; post-2:30 is next-day positioning. On a trending day stay away after ~2:30. Expiry-eve premium erosion can run till 2:00-2:30+. Previous-day VWAP is valid as first support only until ~10:00-10:30; switch to intraday VWAP after ~10:30-11:00.",
    "scaling": "Always scale entries, never go all-in. Pyramid in geometric lots (1/2/4/8, up to 16) toward the strongest support (VWAP) so the heaviest size sits nearest the SL; concentrate maximum averaging at maximum resistance/support. Support-strength tiering: small at weak support, reserve maximum size plus firefighting funds for very-strong (usually VWAP). Increase quantity gradually. Once in profit keep quantity constant and trail tighter rather than adding. Recycle profit: book initial profit then re-enter the same strike lower, using booked/prior-day profit as the next trade's risk budget. Sizing confidence follows the trending-OI gap (wide gap = full size, narrow gap = reduced)."
  },
  "risk_management": [
    "Aim for ~1% per day then stop and log out; daily target scales with account size",
    "Keep only ~5-10% of total finances in the markets; deploy at most ~15-20% of capital at once, risk capped at ~10% of the deployed amount",
    "Single-day loss cap: never lose beyond 10-12% of capital in one day; avoid 40-50% losses",
    "Per-trade risk conservative guideline ~1-2% of total capital; per-trade hard cap ~10% of deployed (outer)",
    "Deep stop-losses mandatory; size SL by instrument (Nifty ~50-60, Bank Nifty ~100, Sensex ~200-250 pts)",
    "Never average below VWAP - once a fall breaks VWAP, accept failure and exit",
    "Always scale entries (geometric 1/2/4/8 up to 16); never go all-in; increase quantity gradually",
    "Sizing confidence follows the trending-OI gap (wide gap = full size, narrow = reduced)",
    "Volatility-based sizing: low VIX/IV -> large qty/small target; high VIX/IV -> small qty (~100)/large target or stay out",
    "Book partials and trail stop-losses; once in profit keep quantity constant and trail tighter",
    "Never revenge-trade; never trade against the trend; never contra-trade in options (opposite-side premiums melt)",
    "Overtrading is the killer - quantities tend to double in the afternoon; book the morning profit and stop",
    "Newbies: trade 1 lot, R:R 1:1 or 1:2, scale up over 6 months to 1 year",
    "Recycle realized profit as the next trade's risk budget rather than enlarging risk"
  ],
  "filters": [
    "Both chart AND data (intraday + positional) must align, else avoid",
    "[keep-rail] A >=50% Call-vs-Put OI gap (ideally widening) is itself a directional confluence gate for the scalp: a wide call>put gap with a higher market confirms the bullish / buy-the-dip read, a wide put>call gap confirms the bearish read — usable even without a separate candle setup.",
    "Volume confirmation mandatory for any break - a VWAP/level/breakdown break without volume can reverse in one candle and trap you",
    "OI 'slide in OI + slide in price' quadrant = better to avoid (unless hedging large positions)",
    "Sellers adding OI on both sides = a pin/erosion day, no directional bias to trade",
    "Equal/elevated both-side IV (40/40, 50/50, 60/60) = stay away or play short straddle; 20/20 = mostly premium erosion; IV > 40-50 favours sellers",
    "VIX four-scenario read gates direction; positional VIX bands 10-11 buy-on-dips up to 20-25+ market tanks",
    "Dow futures (US30) is the primary all-day global cue; Dollar Index good below 100 (above 100 = FII selling India); crude good while low (worry above ~$70-80); do not act on Gift Nifty/overnight Dow at the open",
    "FII Long/Short ratio gates direction (~87-94% short = shorts every level; crossing 50% = short-covering rally trigger); DII buying alone may not lift the index",
    "Buyers use ATM or 1-2 strikes ITM, delta >= 0.7; avoid deep-OTM lottery strikes; avoid OTM for momentum",
    "Plot OI bars on Nifty SPOT not futures (spot is the settlement reference); draw S/R from volume-driven turning points, not from OI; max-call-OI = resistance, max-put-OI = support",
    "No-trade RSI zone 40-50; price boxed between Super Trend/VWMA and VWAP = no-trade zone (take only 1-2 lots, wait for a boundary break)"
  ],
  "edge_cases": [
    "Indicators far from candles after a breakout = avoid scalping; price won't return to support",
    "A clean VWAP break NOT on volume points to the next big support, not a genuine breakdown",
    "Previous-day VWAP is first support on a fall but valid only until ~10:00-10:30; switch to intraday VWAP after ~10:30-11:00",
    "If new hourly highs stop and price holds a ~30-pt range, the move is stuck/erosion",
    "Big traders may suppress 1-2 heavyweights to cap the index even when breadth looks positive",
    "Low VIX (10-12) => ~90% second-half reversal / short-covering bounce after a 70-80 pt fall (between 2:00-3:30)",
    "Low-VIX regime can reward overnight holders while capping intraday to a 50-60 pt band",
    "Expiry-day IV crash in the second half: call IVs fall as call buyers exit; put IV stays stable if put buyers hold; VIX may under-react on expiry day",
    "Sensex moves ~3-4x Nifty in points - use a wide SL, staggered entries, no tight rupee SL",
    "Box-range scalping (Day 14): inside a box take only Rs1,000-2,000 scalps; buy a breakout only on a clean boundary break with volume",
    "Day 3 'Connect The Dots' and Day 5 'Kingdom Trading Strategy' are reused teaching decks - the chess RSI examples (67/77/23/73) are illustrative, not new 2025 live data"
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Connect The Dots - Become Successful Options Scalper deck (Day 3)",
    "Kingdom Trading Strategy deck (Day 5)",
    "Day 1-21 live-decoding transcripts"
  ],
  "uncertain": [
    "Reused-deck figures must not be treated as 2025 live data: the Day 3 'Connect The Dots' and Day 5 'Kingdom Trading Strategy' decks are reused teaching decks; the chess/RSI illustration figures (RSI 67/77/23/73 stop-loss slides) are deck examples, not new 2025 data (§5 item 5).",
    "VIX 'market lower + VIX stable' case: the deck slide labels it bullish, but the live Day-3 read clarifies it as longs unwinding / no aggressive fall (see §4.5) - encode the live clarification, not the raw deck label.",
    "Trending-OI strike-count context (15 vs 5-7 vs 5/10) appears in three places (Day 2 dashboard 7+7+ATM; Day 6 golden-crossover 5-7 either side; Day 9 expiry-OI 5/10); confirm these are context-specific separate inputs, not a single conflicting value (§5 item 3).",
    "Per-trade risk has two stated guidelines: the tighter ~1-2% of total capital vs the ~10%-of-deployed outer cap; confirm which is operative for any given trade (§2.3)."
  ]
}
```

### 6.11 Straddle (Long & Short)
```json
{
  "name": "Straddle (Long & Short)",
  "key": "straddle",
  "s24_coverage": "reference-only",
  "market_context": "Reference-only in Session 24: Shiva never opens a Straddle deck or re-teaches construction (ATM call + put, breakeven, long-vs-short variant selection, leg management). In S24 the straddle appears only as a live read of the day's character (who is winning, straddle buyers vs straddle sellers/writers) and as a sentiment/character gauge, via the combined (call+put) ATM premium read against its own VWAP. The straddle's defining S24 condition is a flat / premium-erosion day where combined premium grinds lower all day and both legs bleed, so writers (sellers) win and buyers lose on both sides; high IV / high premium is a double-edged sword on a flat day. Referenced live across Days 3, 5, 14, 17, 19.",
  "instruments": [
    "Index options at the ATM strike (combined ATM call + put / straddle), per the doc's options framing"
  ],
  "timeframe": "Intraday (live read across the trading day, e.g. above-VWAP intraday rolling below VWAP into the close); no explicit candle/bar timeframe given by S24 for the straddle",
  "indicators": [
    "Combined (call + put) ATM premium plotted as one series (the straddle chart)",
    "VWAP of the combined straddle premium (both legs)",
    "Implied Volatility read on both sides (call-side IV vs put-side IV)",
    "Call-vs-put OI gap (~50% gap as a sentiment parameter)"
  ],
  "setup_preconditions": [
    "S24 reference-only: base construction (ATM call + put, breakeven, long-vs-short variant selection, leg management) is NOT re-taught in Session 24 and lives in the earlier-session strategy body",
    "Read the combined (call+put) ATM premium against its own VWAP to judge whether the day favours straddle buyers (a real move develops) or straddle sellers/writers (a range/erosion day where both legs decay)",
    "Identify whether the session is a flat / premium-erosion day (combined premium grinding lower all day, both legs bleeding) versus a real-move day"
  ],
  "entry_conditions": {
    "bullish": [
      "1. (Sentiment confirmation, not a standalone straddle entry) A ~50% call-vs-put OI gap with a higher market confirms a bullish / buy-the-dip read even when there is no Two-Candle setup; used as a parameter alongside the straddle read",
      "2. While the combined (call+put) premium trades above the VWAP of both legs, the day is going the buyers' way (straddle buyers favoured)"
    ],
    "bearish": [
      "1. High / equal both-side IV (e.g. 40/40, 50/50, 60/60) reads as an erosion / range condition: instruction is to stay away from the markets or play a short straddle; an even, high-IV reading is not a directional buy (20/20 is the lower 'mostly premium-erosion' band, not itself a short-straddle trigger)",
      "2. A flat / premium-erosion day (combined premium grinds lower all day, both legs bleed) favours straddle sellers / writers; straddle buyers lose on both sides",
      "3. Once the combined (call+put) premium drops below the VWAP of both legs into the close, straddle buyers lose and writers take the day"
    ]
  },
  "exit_conditions": {
    "target": "",
    "stop_loss": "[keep-rail] VWAP-anchored: stop on a clean cross of the combined-premium VWAP plus a ~10-15pt buffer (cite §4.14.8).",
    "time_exit": "Directional gate resolves into the close: combined premium that was above VWAP intraday but rolls over and closes back below VWAP means straddle buyers lose and writers take the day (e.g. second half of an expiry-eve session, Day 17)",
    "scaling": ""
  },
  "risk_management": [
    "High IV / high premium is a double-edged sword: on a flat day both legs decay, so straddle buyers lose on both sides while writers profit",
    "If you do not know how to run a short straddle in a high/equal-IV regime, stay out (stay away or play short straddle)",
    "[keep-rail] Exit & leg management: take the long off when combined premium rolls back below VWAP into the close; on a short, one-leg management is permitted (run the winner / close the threatened leg) rather than holding both rigidly.",
    "[keep-rail] Do NOT short a low-premium straddle near expiry (insufficient cushion); Sensex straddles carry a thinner cushion than Nifty — size the short more conservatively.",
    "[keep-rail] Short straddle carries unlimited risk."
  ],
  "filters": [
    "Combined-premium-vs-VWAP is the straddle's directional gate (above VWAP = buyers' day; below VWAP into the close = writers' day)",
    "Equal / high both-side IV (40/40, 50/50, 60/60) = erosion/range (stay away or short straddle); 20/20 = mostly premium erosion (lower band, not a short-straddle trigger)",
    "The ~50% call-vs-put OI gap is a sentiment parameter (bullish / buy-the-dip with a higher market), not a standalone straddle entry",
    "[keep-rail] LOW-IV is the gate for a LONG straddle (cheap premium, room for IV to expand); high/equal IV is the short-straddle / stay-out regime."
  ],
  "edge_cases": [
    "Day 17 (expiry-eve erosion): combined call+put premium traded above its VWAP intraday but rolled over and closed back below it in the second half; straddle buyers lose, writers take the day; textbook premium-erosion / sellers' day one day before expiry",
    "Day 3 (range / sellers' day): day stayed pinned in a small range, so 'the straddle players (writers) are winning it'; cited alongside the IV 40/40 -> 'stay away or play short straddle' rule",
    "Day 14 / Day 19: flat/erosion sessions explicitly called days 'dominated by the straddle players' where buyers bleed on both legs while sellers profit",
    "Day 4 erosion-day worked example (shared §4.3): at the 24,800 strike the combined CE+PE premium opened ~148 and fell to ~126 within the first hour with IV ~9-10 on both sides, a textbook premium-erosion day favouring writers",
    "[keep-rail] Worked short example: selling the 54000 C + 54000 P combined (short straddle) on an even-IV erosion day — the writer collects decay on both legs."
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Day 3 transcript",
    "Day 5 transcript",
    "Day 14 transcript",
    "Day 17 transcript",
    "Day 19 transcript",
    "Day 4 transcript (shared §4.3 erosion worked example)",
    "reused Day-3 'Connect The Dots' deck (IV table, §3.10) — timeless mechanics re-spoken live, not new figures"
  ],
  "uncertain": [
    "S24 does not re-teach base construction of the strategy (ATM call + put assembly, breakeven calculation) — reference-only; full base mechanics live in the earlier-session strategy body, not in S24",
    "Long-vs-short straddle variant selection rules are not re-taught in S24 beyond the high/equal-IV 'short straddle or stay out' read",
    "Leg management (managing the call and put legs independently, adjustments) is not re-taught in S24",
    "No explicit numeric target, stop-loss, or scaling rule is given by S24 for the straddle itself",
    "No standalone straddle entry trigger is defined in S24; the straddle is surfaced only as a sentiment/character gauge and a confirming parameter (combined-premium-vs-VWAP gate, ~50% OI gap, flat/erosion-day read)"
  ]
}
```

### 6.12 Trend Change
```json
{
  "name": "Trend Change",
  "key": "trend_change",
  "s24_coverage": "reference-only",
  "market_context": "Intraday options scalping on Nifty/Sensex index options. Trend Change is a reversal-capture play (built on a Trending-OI flip plus a 2-candle confirm) that S24 does not re-teach as a standalone deck; in Session 24 it is invoked only live, as a condition that gates the other plays (when has the trend actually changed and may I now trade against the prior direction), plus one Day-21 refinement.",
  "instruments": [
    "Nifty index options",
    "Sensex index options",
    "[keep-rail] Instrument 6-leg mapping: this strategy maps across the 6 instrument legs (Nifty options and Sensex options, with the signal read on the index/futures and execution on the option root) — instrument-agnostic, register/tune across both NIFTY-options and SENSEX-options."
  ],
  "timeframe": "Intraday (15-min Trending OI read is the canonical trend-shift read; Trending OI read on intraday and positional data together)",
  "indicators": [
    "Trending OI (criss-cross of call-OI vs put-OI lines marking a trend shift; read on 15-min, 15 strikes = 7 above ATM + ATM + 7 below; intraday and positional reads must both agree for a big move)",
    "VWAP (used as the price-break confirmation for a flip)",
    "Volume (Nifty 125K counter-move gate)",
    "Candles / 2-candle confirm (base mechanic, not re-taught in S24)",
    "[keep-rail] Named chart-indicator set: Super Trend (10,2), VWMA(20), and Parabolic SAR are the named chart indicators that frame the reversal read (alongside VWAP, Trending OI, RSI, and candles already listed)."
  ],
  "setup_preconditions": [
    "A trend change must be confirmed by Trending OI, not by the chart or a VWAP break alone",
    "While Trending OI stays positive (with the prior direction), keep trading with that side and do not pre-empt a reversal (Days 5, 11)",
    "Data, not the chart alone, gives the true read — the chart can show price up while the trending OI stays bearish (Day 12)"
  ],
  "entry_conditions": {
    "bullish": [
      "1. Counter-trend (call-side) divergence setup: intraday Trending OI has turned bearish while positional Trending OI is still bullish (Day 21).",
      "2. Take the counter-trend (call-side) trade ONLY if the counter (down) move is NOT backed by >125K (Nifty) volume — an unbacked counter-move tends to fade, so the prevailing (positional bullish) trend is expected to reassert (Day 21).",
      "3. Do NOT take the counter-trend call if the down-candles exceed ~125K volume — a volume-backed counter-move signals the real reversal, so go with the (down) trend instead and drop the trade (Day 21)."
    ],
    "bearish": [
      "1. To flip from long to short (turn bearish), require BOTH conditions together: price has broken VWAP AND the Trending OI direction has changed (turned bearish) (Days 11, 12).",
      "2. A VWAP/volume break on its own is NOT a trend change — do not turn bearish until trending OI also turns bearish (Days 5, 11, 12).",
      "3. A counter (down) move backed by >125K (Nifty) volume signals the genuine reversal — treat it as a real trend change and trade with the down move rather than fading it (Day 21)."
    ]
  },
  "exit_conditions": {
    "target": "",
    "stop_loss": "",
    "time_exit": "",
    "scaling": ""
  },
  "risk_management": [
    "Do not pre-empt a reversal while Trending OI still favours the prevailing direction — keep trading with the dominant side until trending OI flips (Days 5, 11).",
    "Distinguish a fading unbacked counter-move from a genuine reversal using the 125K (Nifty) volume gate before committing to a counter-trend trade (Day 21).",
    "On a monthly-expiry day, ignore the OI/trend data entirely for the reversal read — writers are unwinding the expiring month, so the apparent trend or reversal does not describe the new series (Days 20, 21; concept also Day 9)."
  ],
  "filters": [
    "125K (Nifty) volume gate distinguishes a fading counter-move (counter-trend trade permissible) from a real reversal (go with the trend) (Day 21).",
    "Require dual confirmation to flip direction: VWAP broken AND Trending OI direction changed (Days 11, 12).",
    "Monthly-expiry caveat: discard OI/trend data on a monthly-expiry day because expiring-series writers are unwinding (Days 20, 21; Day 9)."
  ],
  "edge_cases": [
    "Intraday vs positional Trending OI can diverge (intraday bearish, positional bullish) — this divergence is itself the Day-21 counter-trend setup, resolved by the 125K volume gate (Day 21).",
    "Monthly-expiry day: apparent trend/reversal in the OI data is an artifact of writers unwinding the expiring series and does not describe the new series — ignore it (Days 20, 21; Day 9).",
    "Chart can show price up while trending OI stays bearish — the data, not the chart alone, gives the true read (Day 12)."
  ],
  "session": "24",
  "valid_year": 2025,
  "source_files": [
    "Day 5 transcript",
    "Day 9 transcript",
    "Day 11 transcript",
    "Day 12 transcript",
    "Day 20 transcript",
    "Day 21 transcript"
  ],
  "uncertain": [
    "S24 coverage is reference-only: Session 24 ships NO dedicated Trend Change deck and does not re-teach the base mechanics; the full mechanics are not reconstructed in the S24-only doc.",
    "Base mechanics (the underlying reversal-capture play built on a Trending-OI flip plus a 2-candle confirm) are not re-taught in S24 — they come from the prior (S23) trend_change mechanics, which this S24-only doc does not contain.",
    "Concrete target, stop-loss, time-exit, and scaling rules for Trend Change are not stated in the S24-only doc.",
    "The 2-candle confirm referenced as a base component is not detailed within §3.12 in S24.",
    "§5 open item: confirm the Day-21 refinement (intraday-bearish / positional-bullish divergence; take the counter-trend call only if the counter-move is NOT >125K volume) attaches to the existing S23 trend_change mechanics rather than implying new mechanics, since S24 never re-teaches the trend_change deck."
  ]
}
```
