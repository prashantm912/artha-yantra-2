# Momentum Swing Trading — Manas Arora — Consolidated Strategy

> **Category:** `MomentumTradingManasArora` · **Instruments:** Indian equities (NSE/BSE **cash/delivery**), **long-only** · **Style:** EOD-based momentum **swing-to-position** trading.
> **Single source event:** the **May 2018 Webinar** (`@iManasArora`), delivered as **three sessions** — Session I (universe → setup → entry → post-buy evaluation), Session II (stops, position sizing, pyramiding, targets, drawdown control), Session III (psychology, trade journal). All rules below are from that event's deck + Q&A + the position-sizing / trade-journal spreadsheets.
> **Method DNA:** an India-adapted implementation of Mark Minervini's momentum method (VCP, "Superperformance") + William O'Neil (CANSLIM-style demand/leadership) + Mark Douglas (*Trading in the Zone*) psychology. The author credits `@markminervini` directly.
> This document is the **single source of truth**; the cheat sheet and changelog are derived from it.

---

## Header / Strategy Roster

| key | Strategy | Introduced | Type |
|-----|----------|-----------|------|
| `momentum_swing` | Momentum Swing **Master Playbook** (end-to-end) | Session I–III (May'18) | Master playbook |
| `breakout` | **Breakout** (consolidation / swing-high breakout) | Session I | Entry setup |
| `vcp` | **Volatility Contraction Pattern (VCP)** + Pivot Buy | Session I | Entry setup |
| `pyramiding` | **Pyramiding** (add-to-winner) | Session II | Position-building technique |
| `selling` | **Exit & Stop Management** (ATR stop, trail, square-off) | Session II | Exit discipline |

**Shared components** (defined once in §4, referenced by strategies): Selection & Trend Filter (6-criteria watchlist screen) · Moving Averages · Liquidity Filter · Float & Market-Cap Filter · Position Sizing · Post-Buying Evaluation · EOD Workflow & Timeframes · Trade Journal.

**No deprecations.** This is a single-event category (all rules dated May 2018); there are no cross-session value conflicts to resolve.

---

## §1 Introduction & Terminology

### 1.1 Overall approach
- **Objective — "Superperformance":** turn a small amount of capital into a large fortune by holding stocks with a high probability of rising **200–300%+ in a relatively short period (a year or two)**.
- **One strategy, mastered:** trade **one** setup family well rather than many. "You cannot make money in every market environment. Be a master of one strategy and make the most from it."
- **Demand/supply lens:** price rising = stock in demand. Retail traders don't move stocks — **institutions / mutual funds** do. Chase stocks that big money is chasing (great earnings, higher sales, turnarounds).
- **Long-only, cash segment, low market-cap:** only low-float / low-market-cap stocks can rise (and fall) dramatically in a short time. **No F&O / derivatives** (large float → no superperformance). No short selling.
- **EOD, part-time:** analyse only after market close; ~15–30 min/day with the right tool. Weekly chart to find the setup, daily chart to time the entry.

### 1.2 Glossary / terms
- **Superperformance** — a stock move of ~200–300%+ over ~1–2 years; the whole method targets these.
- **Pivot / buy point** — the nearest swing high of the setup (breakout level). Entry bids are placed **above** it.
- **VCP (Volatility Contraction Pattern)** — Minervini pattern: a series of progressively **tighter, shorter** price contractions (reduced volatility) that precedes a breakout.
- **Tennis-ball action** — after a dip the stock "bounces" back up quickly (like a tennis ball), a sign of demand/strength (read on the **daily** chart).
- **Follow-up buying** — visible continued institutional buying after your entry (green days, up-volume) confirming the move.
- **Shallow pullback** — a small drop from the recent high (healthy). **Brief** = short in *time*; **Shallow** = small in *price*.
- **Pyramiding** — adding to a winning position as it rises, treating each add as its own trade.
- **R (risk multiple)** — profit or loss expressed in multiples of the trade's initial risk (Entry − Stop).
- **Original / initial risk** — the % of capital put at risk on a position when first entered (kept 0.5–1%).
- **Open risk (portfolio)** — total capital currently at risk across all open positions (capped 5–6%).
- **Drawdown** — loss of portfolio value from its peak (distinct from a single position's stop).

### 1.3 Indicators & exact settings
| Indicator | Setting | Where |
|-----------|---------|-------|
| Simple Moving Average (short/long) — **daily** chart | **50** & **200** period | Trend filter, parabolic check (10-period short MA for extension) |
| Simple Moving Average — **weekly** chart | **10** & **40** period | Setup identification |
| ATR (Average True Range) | **20 period, daily chart** | Stop-loss & trailing distance |
| Chart type | **Bar charts** (not candlesticks) | To see volatility contraction — "small bars after big bars" |

### 1.4 Instruments, tools, sources
- **Market:** NSE / BSE cash (delivery). Brokerage assumed ≈ **0.23%** (23 paisa) delivery in the journal/sizing sheets.
- **Scanning/charting tools cited:** **Spider Software EOD** (paid, ≈₹8–10k/yr, the webinar sponsor — used to scan/sort/time in ~15–30 min); free alternatives **TradingView**, **Investing.com** screener, **StockEdge** app (expect 2–3× more time). Use **bseindia.com** to check float.
- **Reading list cited:** *How I Made $2,000,000 in the Stock Market* (Darvas), *Trading in the Zone* (Douglas), *How to Make Money in Stocks* (O'Neil).

---

## §2 Global Risk Management Framework

> Risk management — not chart-reading — is the core. The author states his early errors were **all on the risk-management side** (not cutting losses, holding losers on hope). "Cut your losses short" is the one habit common to every successful trader.

### 2.1 Per-trade risk (position level)
- **Original risk per position = 0.5–1% of capital.** (Position-sizing sheet models 0.5% / 1% / 1.5% / 2% bands; 0.5–1% is the stated operating range.)
- **Maximum initial stop ≈ 10%** of entry price (a single position's stop should not exceed ~10%; combined with ≤1% capital risk this bounds share quantity — see §4.5).
- Position size is **derived from** risk, never fixed rupees — see §4.5 Position Sizing.

### 2.2 Portfolio-level caps
- **Max open (portfolio) risk at any time = 5–6%.** Never expose more than 5–6% of the portfolio to risk across all open positions combined. As a position rises and its stop is trailed up, open risk falls → freeing room to add a new position (or pyramid).
- **Max concurrent positions = 5–7 names.** Circulate capital among them; weed out the weak and replace with a fresh name.

### 2.3 Drawdown & losing-streak circuit breakers
- **Monthly drawdown cutoff = 5–6%.** If portfolio value falls 5–6% within a month → **forced break for the rest of that month.** Clear the head, return with a fresh, relaxed outlook.
- **Losing-streak protocol:** if you've lost **5–6% of capital**, take a **7–10 day break**. Return at **half the original risk** (reduced risk), and keep halving/rebuilding until your rhythm ("mojo") returns.

### 2.4 Stop discipline (see §3.5 for mechanics)
- **Always place stops** — even though setups rarely fail this way, be prepared for "anything can happen."
- **Place stops every morning** (GTC not required); with only 5–7 open names it is a ~2–10 min job. You do **not** need to watch live prices — placing the stop is enough.
- **A hit stop = close the position**, no second-guessing, regardless of closing-basis vs intraday. If price gaps below the stop, **act twice as fast.**

### 2.5 Entry-side risk rules
- **No chasing:** if price is **more than 5% above** the entry/pivot point, skip the trade — don't chase and regret. Opportunities recur.
- **No setup → no trade.** Don't force trades. In bear/consolidation markets, few names qualify (and those that hold up are the next leaders); that's acceptable.
- **Re-entry after a stop-out is encouraged** if the setup reforms — it means buyers absorbed the supply. A 3rd attempt is rare but even higher-quality.

### 2.6 Why the method is structurally safe
- Trading **only liquid** stocks (see §4.3) means you can (almost) always exit — the author reports never being trapped in hundreds of trades.
- Drawdown math: with original risk kept **<1%** and a ~10% stop, even a **50% crash** in a gap-locked stock erodes only ~**5%** of capital (30-pt stop example ≈ 6–7%) — recoverable.

### 2.7 Global Do's & Don'ts
**Do:** cut losses short; trade only your setup; keep risk small and pre-defined; place stops daily; pyramid winners; keep a trade journal; be patient for setups.
**Don't:** trade F&O/derivatives; short; average **down** a loser; chase >5% past the pivot; force trades with no setup; trade illiquid / gap-only / single-digit stocks; hold losers on hope; monitor live tick-by-tick.

---

## §3 Strategies

### §3.1 `momentum_swing` — Momentum Swing Master Playbook

**Purpose & context.** The end-to-end sequence that ties the setups, sizing, and exits together. Applies in an uptrending market to leadership stocks showing institutional demand.

**Instruments & timeframe.** NSE/BSE cash, long-only. Weekly chart to find the setup; daily chart to time entry and manage. Typical hold: weeks to a few months (swing-to-position).

**The playbook (numbered):**
1. **Build the universe** — list stocks **within 25% of their 52-week high**, preferring names already up 100%+ (institutional demand; strength read from price gain + rising sales/EPS). Add to a watchlist (~700–800 names before filtering).
2. **Filter** the watchlist with the **6-criteria screen** (§4.1) — all must match — then apply the **liquidity** (§4.3) and **float/market-cap** (§4.4) filters.
3. **Find the setup** on the **weekly** chart — a **Breakout** (§3.2) or a **VCP** (§3.3).
4. **Time the entry** on the **daily** chart — place a **buy-stop bid above the pivot** (nearest swing high). You are in only once the level triggers; do **not** chase >5% past it.
5. **Size the position** from risk (§4.5): shares = (capital × risk%) / (entry − stop), risk% = 0.5–1%.
6. **Set the stop** = entry − 2×(20-period daily ATR), max ~10% (§3.5).
7. **Evaluate post-buying price action** (§4.6) — follow-up buying, green vs red days, up vs down volume, tennis-ball action, shallow pullbacks.
8. **Pyramid** into strength (§3.4) if the position moves up ~5–10% quickly and a fresh entry presents.
9. **Manage & exit** (§3.5) — trail the ATR stop once up 8–10%; square off on a too-fast/parabolic move or a trailing-stop hit.
10. **Respect global risk** (§2) — open risk ≤5–6%, ≤5–7 names, monthly-drawdown and losing-streak circuit breakers.
11. **Journal** every trade (§4.8) and review weekend/EOD.

**Versioning.** Introduced and complete within the May'18 webinar (Sessions I–III). No later revision in-category.

---

### §3.2 `breakout` — Breakout (Consolidation / Swing-High Breakout)

**Purpose & context.** "The only breakouts I trade." Capture the resumption of an established uptrend after a healthy pause. Only on names that already passed the §4.1/§4.3/§4.4 filters.

**Setup & preconditions:**
1. Stock meets **all** selection criteria (§4.1).
2. It has **already run up ~50–100% in a short time (≈3–6 weeks)** — demonstrated momentum.
3. It then **consolidates on the weekly chart**, staying inside a range for **~4–8 weeks** (Q&A: prefer **4–6 weeks**; **minimum ~2 weeks**; **shorter is better** — shows urgency of demand).
4. It **resumes the uptrend and takes out the last swing high** = the breakout.

**Entry rules (long-only):**
1. Identify the **pivot** = the nearest **swing high** of the consolidation.
2. Place a **buy-stop order (bid) above the pivot.** You are in **only when the level triggers** — not the next day, not at CMP.
3. **No chasing:** if the trigger would fill **>5% above** the pivot, skip it.
4. If multiple qualifying names trigger the same day, bid on all high-probability ones and take them **in the order they trigger** until open-risk cap (5–6%) is reached; cancel the rest.

**Exit rules.** Per §3.5 (initial ATR stop → trail after +8–10% → square-off triggers).

**Strategy-specific filters.** Consolidation must be genuine (≥~2 wks); avoid names that only gap up (illiquidity — §4.3). Weekly setup + daily timing.

**Versioning.** Session I (May'18).

---

### §3.3 `vcp` — Volatility Contraction Pattern (VCP) + Pivot Buy

**Purpose & context.** Minervini's VCP, applied to Indian leaders. A tightening of price/volatility that precedes an explosive breakout. One of the two patterns traded.

**Setup & preconditions:**
1. Name passes §4.1/§4.3/§4.4 filters and shows a prior uptrend.
2. A **series of contractions**, each **tighter (shallower)** than the last — volatility reduces; on a **bar chart** this reads as **small bars after big bars**.
3. **Healthy geometry:** each successive base takes about **half the time** of the previous one (e.g. **12 wk → 6 or less → 3 or less**), and each drawdown is **shallower** than the last.
   - *Worked example (BPL, from Q&A — illustration only):* high 45.40 (20-Jul-15) → low 23.10 (15-Feb-16) = **48%** contraction; next high 42.20 (11-Jul-16) → low 36.45 (01-Aug-16) = **16%** contraction. Successively tighter = healthy VCP.

**Entry rules (long-only):**
1. **Pivot = the nearest swing high** of the pattern.
2. Place a **buy-stop bid above that pivot** (same mechanism as §3.2). In only on trigger. **No chasing >5%.**

**Exit rules.** Per §3.5.

**Edge cases.** VCP on **monthly/hourly** charts appears only once or twice a year and results can take much longer — not the primary timeframe (weekly-to-find / daily-to-time).

**Versioning.** Session I (May'18).

---

### §3.4 `pyramiding` — Pyramiding (Add-to-Winner)

**Purpose & context.** Build a large position in a winner **without increasing total risk** — the engine behind "compounding." Concentrate capital into the best-performing names.

**Rules:**
1. **Trigger to add:** the existing entry has **moved up ~5–10% (often almost instantly)** — evidence of a strong move — **and** a fresh valid entry (new pivot/breakout) is available.
2. **Treat each add as a brand-new position:** it gets its **own entry, its own 2×ATR stop, and the same sizing/rules** as the first entry. Position size and ATR stop are calculated **separately** for each add.
3. **Do not increase overall risk exposure:** add only when trailing the earlier lots' stops has reduced open risk enough to make room (open risk stays ≤5–6%) — you can add even a **3rd time "without increasing risk exposure."**
4. **Concentrate** capital on your best-performing names.
   - *Examples (from the author's live-trade tweets — illustration only, not thresholds):* BEPL added at 39.5 → 42 → 45 (a 3rd add "without increasing risk exposure"), which the author reported contributed ~14–16% to his whole portfolio in ~2 weeks; SUNFLAG 49 → 53 → 67 (3 entries ≈ 5% of the portfolio); RCF 62 → 72 → 84.

**Exit of a pyramided position:** if the (trailed) stop is hit, **close the entire position** (all lots) without second-guessing — see §3.5.

**Versioning.** Session II (May'18).

---

### §3.5 `selling` — Exit & Stop Management

**Purpose & context.** The exit discipline: define risk with an ATR stop, trail it as the trade works, and square off on exhaustion. Exits are rule-based, not pure discretion.

**A. Initial stop (set at entry):**
1. Plot **20-period ATR on the daily chart.**
2. Compute **2 × ATR.**
3. **Stop = Buy Price − 2×ATR.** *(Example: ATR 5 → 2×ATR 10 → buy 100 → stop 90.)*
4. Cap the initial stop at **~10%** of entry; if 2×ATR implies a wider stop, the position is smaller (risk stays 0.5–1%) — see §4.5.

**B. Trailing:**
- Once the position is **up ~8–10%**, begin trailing the stop (e.g. **Cost/price − 2×ATR**, an **ATR trailing stop**).
- Trailing up **reduces open risk**, which frees room to pyramid/add elsewhere (§2.2).

**C. Square-off (take profits / reduce) triggers:**
1. **Too-fast move:** stock jumps **~30–50% in just 2–4 days** → **take at least 50% off**; such names usually go sideways before resuming — reinvest later on a fresh opportunity.
2. **Parabolic move:** the distance between price and the **short (10-period) MA** has extended **far beyond its normal historical distance** → trim/exit.
3. **ATR trailing stop hit** → close.

**D. Execution rules:**
- A hit stop (initial or trailing) = **close, no second-guessing.** Closing-basis vs intraday doesn't matter — act.
- On a **pyramided** position, a trailing-stop hit closes **all lots** at once.
- **Never widen or lower a stop** — it only ever trails **up** (§B); once hit, you close.
- If it **gaps below** the stop, close **twice as fast**; keep losses small.

**Versioning.** Session II (May'18).

---

## §4 Common Rules & Shared Components

### §4.1 Selection & Trend Filter (6-criteria watchlist screen)
Applied to the "within 25% of 52-week high" universe. **All must match:**
1. **Price ≥ ₹30** — eliminate sub-₹30 / single-digit "penny" names (sick companies; let institutions lift them to 30–35 first, proving demand).
2. **200-period MA rising for ≥ 3 months** — long-term uptrend established.
3. **50 MA above 200 MA** — the longer it has held, the better.
4. **Current price above 200 MA** (sufficient) and **preferably above 50 MA** too.
5. **Current price ≥ 100% up from the 52-week low** — strength / institutional accumulation.
6. **New 52-week high made at least once every 4–6 months** — drop slow/average names.

> *Exceptions exist* ("trading is an art, not a science") — but beginners should trade only names meeting **almost all** criteria for the first ~year / ~50 trades before tweaking. Best demand indicators = **sales & EPS**; deep fundamental analysis is not required (qualifying names are usually already fundamentally sound).

### §4.2 Moving Averages
- **Daily chart:** 50 & 200 **SMA** (trend filter; 10-period short MA used for the parabolic-extension check, §3.5).
- **Weekly chart:** 10 & 40 **SMA** (setup identification).

### §4.3 Liquidity Filter
- **10-week average volume must be ≥ 50× (ideally 50–100×) the quantity you intend to buy.** Ensures you can enter/exit at your size.
- **Absolute low-volume veto:** even if *all* other criteria pass, **reject any name with a very low ~20-day average traded volume (≈5,000 shares or lower)** — no good supply means no fair entry/exit price.
- **Avoid names that only gap up** — a sign of a liquidity crisis (no ample buyers/sellers at one price). Illiquidity, not market cap, is the disqualifier.

### §4.4 Float & Market-Cap Filter
- Trade **only low-market-cap** stocks (only they can move 50–100%+ in weeks).
- **Float ≤ ₹5,000 crore worth of shares** in the market (lower is better; check on **bseindia.com**).
- **Free-float / total market cap preferably under 30–35%** (lower is better) — low supply amplifies moves.
- (Low market cap is a *boon* for upside — but its falls are equally sharp, which is why the liquidity filter and small position risk matter.)

### §4.5 Position Sizing (risk-based)
- **Shares to buy = (Capital × Risk%) ÷ (Entry − Stop per share).**
- **Risk% = 0.5–1%** of capital (calculator models 0.5 / 1 / 1.5 / 2%).
- Because size is derived from the **stop distance**, a wider stop → fewer shares (constant rupee risk). A hard **~10% max stop** bounds this.
- *Calculator inputs → outputs:* Net Worth, Risk%, Entry Price, Stop Loss → number of shares + investment-per-trade (₹) + **"Risk on Investment"** (the loss-if-stopped as a % of the invested amount, ≈ stop-distance% + costs; e.g. entry 183 / stop 169 / 0.23% brokerage ≈ **8.07%**). Brokerage ≈ 0.23% (delivery) is included.

### §4.6 Post-Buying Evaluation
After entering, judge whether to hold/add by the **health of the price action**:
- **Follow-up buying** — continued (institutional) buying after entry.
- **Green days vs red days** — more/stronger green = healthy.
- **Up volume vs down volume** — up-volume dominance = accumulation.
- **Tennis-ball action** (daily chart) — quick snap-backs up after dips = demand.
- **Shallow pullbacks** — small drops from recent highs = healthy (vs deep = warning).

### §4.7 EOD Workflow & Timeframes
- **Analyse EOD only** — no live-market decisions.
- **Weekly chart to find** the setup; **daily chart to time** the entry.
- **Bar charts** (not candlesticks) — easier to see volatility contraction.
- **Re-scan** the universe **every 15–20 days**; a weekend review takes ~30 min with the right tool.
- Total time ≈ 15–30 min/day with a paid EOD scanner (more on free tools).

### §4.8 Trade Journal
Log every trade to learn your own edge. Template columns: Type (long/short) · Symbol · Quantity · Buy Price · Buy Date · **Initial Stop %** · Sell Price · Sell Date · Net P/L · **% change** · **Days Held** · **R** (risk multiple) · **Risk %** · Net R; header tracks capital **Limit**, **Open Position**, **% Limit used/left**, brokerage (0.23%).
Metrics to extract: **# winners, # losers, average win size, average loss size, risk/reward ratio, average holding days for winners, average holding days for losers** (holding measured in **calendar days**).

### §4.9 Market Environment & Psychology (context)
- Make money in **one** environment with **one** mastered strategy; expect **few setups** in bear/consolidation markets and don't force trades.
- **Mindset (from *Trading in the Zone*):** anything can happen; you don't need to know what happens next; think in probabilities; live in the now; every moment is unique.
- **"Holy Grail" = Right Mindset + Risk & Trade Management + Trading System.**

---

## §5 Strategy Evolution

> **Single-event category.** Every rule originates in the **May 2018 webinar** (Sessions I–III). There are **no prior/later sessions in-category**, therefore **no deprecated or replaced values and no cross-session conflicts.** The "evolution" is simply the intra-event teaching order.

| Session | Role | Introduced |
|---------|------|-----------|
| **Session I** | Setup & entry | Superperformance objective; universe (within 25% of 52-wk high); 6-criteria selection filter; liquidity & float/market-cap filters; MAs (50/200 daily, 10/40 weekly); Breakout & VCP patterns; buy-stop-above-pivot entry; no-chase >5%; post-buying evaluation; EOD/weekly-daily workflow |
| **Session II** | Risk, sizing, management | 20-period ATR stop (Buy − 2×ATR, ≤~10%); risk-based position sizing (0.5–1%); daily stop placement; trailing after +8–10%; square-off triggers (too-fast 30–50%/2–4d, parabolic vs 10-MA, trailing-stop hit); pyramiding (add-to-winner, each add a new position); portfolio open-risk cap 5–6%; 5–7 names |
| **Session II/III** *(combined)* | Staying-in-the-game risk | monthly-drawdown 5–6% → forced break; losing-streak 7–10d half-risk protocol *(the deck places these in the Session III psychology/review run; the Q&A groups them under "Session II & III combined")* |
| **Session III** | Psychology & review | *Trading in the Zone* self-test & mindset truths; "Holy Grail" formula; trade-journal metrics; reading list |

### §5.1 Per-strategy notes
- `momentum_swing`, `breakout`, `vcp`, post-buying evaluation, all filters → **Session I**.
- `pyramiding`, `selling` (stops/trail/square-off), position sizing, portfolio open-risk cap (5–6%) & 5–7-names cap → **Session II**.
- Monthly-drawdown (5–6% break) and losing-streak (7–10d, half-risk) circuit breakers → **Session II/III (combined)** — the deck slides them into the Session III psychology/review run, and the Q&A files them under the "Session II & III combined" heading.
- Journal + psychology → **Session III**.
- **No rule was introduced and later removed; no value changed within the event.**

---

## §6 Machine-Readable Appendix (per-strategy JSON)

```json
{
  "name": "Momentum Swing Master Playbook",
  "key": "momentum_swing",
  "market_context": "Uptrending Indian market; leadership low-cap stocks with institutional demand; long-only, EOD swing-to-position.",
  "instruments": ["NSE/BSE cash equities (delivery)"],
  "timeframe": "Weekly chart to find setup; daily chart to time entry; hold weeks to months",
  "indicators": ["50 & 200 SMA (daily)", "10 & 40 SMA (weekly)", "20-period ATR (daily)", "bar charts"],
  "setup_preconditions": [
    "Stock within 25% of 52-week high; prefer up 100%+",
    "Passes 6-criteria selection filter (see key selection_filter in §4.1)",
    "Passes liquidity filter (10wk avg vol >= 50x intended qty)",
    "Passes float/market-cap filter (float <= 5000cr; free-float < 30-35%)"
  ],
  "entry_conditions": {
    "bullish": [
      "Find Breakout or VCP setup on weekly chart",
      "Place buy-stop bid above pivot (nearest swing high) on daily chart",
      "Enter only on trigger; do not chase if fill would be >5% above pivot"
    ],
    "bearish": []
  },
  "exit_conditions": {
    "target": "Ride the trend; square off on too-fast (30-50% in 2-4 days -> take >=50% off) or parabolic (price far above 10-MA) move",
    "stop_loss": "Entry - 2x(20-period daily ATR), capped ~10%",
    "time_exit": "None fixed; hold while trend/stop intact",
    "scaling": "Pyramid on +5-10% quick move (each add = new position); trail stop after +8-10%"
  },
  "risk_management": [
    "Original risk 0.5-1% of capital per position",
    "Max portfolio open risk 5-6%",
    "Max 5-7 concurrent positions",
    "Monthly drawdown 5-6% -> forced break rest of month",
    "Losing streak: lost 5-6% -> 7-10 day break, resume at half risk",
    "Always place stops daily; close on hit, no second-guessing",
    "Long-only; no F&O/derivatives; no averaging down"
  ],
  "filters": [
    "Within 25% of 52-week high; price >= 30",
    "200 MA rising >= 3 months; 50 MA > 200 MA; price > 200 MA (pref > 50 MA)",
    "Price >= 100% up from 52-week low",
    "New 52-week high at least once every 4-6 months",
    "Liquidity: 10wk avg vol >= 50x intended qty; avoid gap-only names",
    "Low market cap; float <= 5000cr; free-float < 30-35%"
  ],
  "edge_cases": [
    "Exceptions to 100%-up rule exist; beginners trade only near-perfect setups for ~1 year/50 trades",
    "Bear/consolidation markets yield few setups; do not force trades"
  ],
  "session_introduced": "May'18 Webinar (Sessions I-III)",
  "day_introduced": "Session I-III",
  "valid_from_year": 2018,
  "valid_to_year": "current",
  "source_files": ["Webinar.pptx", "swing trading manas.pdf", "Q&A.pdf / Q manas.pdf", "Webinar Notes.txt", "Quantity Calculator Update Sample.xlsx", "Trade Sheet Final.xlsx", "Trading sheet log (Editable).xlsx"],
  "uncertain": []
}
```

```json
{
  "name": "Breakout (Consolidation / Swing-High Breakout)",
  "key": "breakout",
  "market_context": "Established uptrend resuming after a healthy pause; leadership low-cap names.",
  "instruments": ["NSE/BSE cash equities (delivery)"],
  "timeframe": "Weekly to find, daily to time",
  "indicators": ["50 & 200 SMA (daily)", "10 & 40 SMA (weekly)", "20-period ATR (daily)"],
  "setup_preconditions": [
    "Passes selection, liquidity, float filters",
    "Already ran up ~50-100% in ~3-6 weeks",
    "Consolidated on weekly chart ~4-8 weeks (prefer 4-6; min ~2; shorter better)"
  ],
  "entry_conditions": {
    "bullish": [
      "Pivot = nearest swing high of consolidation",
      "Buy-stop bid above pivot; in only on trigger (not next day, not CMP)",
      "Skip if fill would be >5% above pivot",
      "If several trigger same day, take in order until open-risk 5-6% cap, cancel rest"
    ],
    "bearish": []
  },
  "exit_conditions": {
    "target": "Ride trend; square off on too-fast/parabolic move (see selling)",
    "stop_loss": "Entry - 2x(20-period daily ATR), <=~10%",
    "time_exit": "None fixed",
    "scaling": "Pyramid per pyramiding; trail after +8-10%"
  },
  "risk_management": ["Original risk 0.5-1%", "Part of 5-6% portfolio open-risk cap", "Daily stop placement"],
  "filters": ["Genuine consolidation >= ~2 weeks", "Avoid gap-only names", "Weekly setup + daily timing"],
  "edge_cases": ["4-5 same-day triggers is rare"],
  "session_introduced": "May'18 Webinar (Session I)",
  "day_introduced": "Session I",
  "valid_from_year": 2018,
  "valid_to_year": "current",
  "source_files": ["Webinar.pptx", "swing trading manas.pdf", "Q&A.pdf / Q manas.pdf", "Webinar Notes.txt"],
  "uncertain": []
}
```

```json
{
  "name": "Volatility Contraction Pattern (VCP) + Pivot Buy",
  "key": "vcp",
  "market_context": "Minervini VCP on Indian leaders; tightening volatility preceding breakout.",
  "instruments": ["NSE/BSE cash equities (delivery)"],
  "timeframe": "Weekly to find, daily to time",
  "indicators": ["Bar charts (volatility contraction)", "50 & 200 SMA (daily)", "20-period ATR (daily)"],
  "setup_preconditions": [
    "Passes selection, liquidity, float filters; prior uptrend",
    "Series of progressively tighter (shallower) contractions; small bars after big bars",
    "Each base ~half the time of the previous (12wk -> 6 or less -> 3 or less); each drawdown shallower"
  ],
  "entry_conditions": {
    "bullish": [
      "Pivot = nearest swing high of pattern",
      "Buy-stop bid above pivot; in only on trigger; no chase >5%"
    ],
    "bearish": []
  },
  "exit_conditions": {
    "target": "Ride trend; square off per selling rules",
    "stop_loss": "Entry - 2x(20-period daily ATR), <=~10%",
    "time_exit": "None fixed",
    "scaling": "Pyramid per pyramiding; trail after +8-10%"
  },
  "risk_management": ["Original risk 0.5-1%", "Part of 5-6% portfolio open-risk cap"],
  "filters": ["Weekly-to-find/daily-to-time primary; monthly/hourly VCP rare (1-2x/yr) and slow"],
  "edge_cases": ["BPL illustration: 48% then 16% successive contractions (example only)"],
  "session_introduced": "May'18 Webinar (Session I)",
  "day_introduced": "Session I",
  "valid_from_year": 2018,
  "valid_to_year": "current",
  "source_files": ["Webinar.pptx", "swing trading manas.pdf", "Q&A.pdf / Q manas.pdf", "Webinar Notes.txt"],
  "uncertain": []
}
```

```json
{
  "name": "Pyramiding (Add-to-Winner)",
  "key": "pyramiding",
  "market_context": "Building a large position in a proven winner without raising total risk; concentrate on best names.",
  "instruments": ["NSE/BSE cash equities (delivery)"],
  "timeframe": "Same as base trade",
  "indicators": ["20-period ATR (daily)"],
  "setup_preconditions": [
    "Existing entry up ~5-10% (often quickly)",
    "A fresh valid entry (new pivot/breakout) available"
  ],
  "entry_conditions": {
    "bullish": [
      "Treat each add as a brand-new position: own entry, own 2xATR stop, same rules",
      "Add only when trailed earlier lots keep total open risk <= 5-6% (do not increase risk)"
    ],
    "bearish": []
  },
  "exit_conditions": {
    "target": "Ride trend; square off per selling",
    "stop_loss": "Each add: entry - 2x(20-period daily ATR)",
    "time_exit": "None",
    "scaling": "Trailing-stop hit closes ALL lots at once"
  },
  "risk_management": ["No increase to portfolio open risk (stay <= 5-6%)", "Concentrate on best names"],
  "filters": ["Add on strength only, never to a losing position (no averaging down)"],
  "edge_cases": ["Examples: BEPL 39.5/42/45; SUNFLAG 49/53/67; RCF 62/72/84 (illustration only)"],
  "session_introduced": "May'18 Webinar (Session II)",
  "day_introduced": "Session II",
  "valid_from_year": 2018,
  "valid_to_year": "current",
  "source_files": ["Webinar.pptx", "swing trading manas.pdf", "Q&A.pdf / Q manas.pdf"],
  "uncertain": []
}
```

```json
{
  "name": "Exit & Stop Management",
  "key": "selling",
  "market_context": "Rule-based exit discipline: define risk with ATR stop, trail into strength, square off on exhaustion.",
  "instruments": ["NSE/BSE cash equities (delivery)"],
  "timeframe": "Daily management",
  "indicators": ["20-period ATR (daily)", "10-period short MA (parabolic check)"],
  "setup_preconditions": ["An open long position"],
  "entry_conditions": {"bullish": [], "bearish": []},
  "exit_conditions": {
    "target": "Square off >=50% on too-fast move (30-50% in 2-4 days); trim/exit on parabolic move (price far above 10-MA)",
    "stop_loss": "Initial: Buy - 2x(20-period daily ATR), capped ~10%",
    "time_exit": "None fixed",
    "scaling": "Trail (price - 2xATR) after +8-10%; trailing-stop hit -> close (all pyramided lots)"
  },
  "risk_management": [
    "Place stops every morning; close on hit, no second-guessing (basis irrelevant)",
    "Gap below stop -> act twice as fast",
    "Original per-position risk 0.5-1%"
  ],
  "filters": ["Long-only exits"],
  "edge_cases": ["RCF illustration: closed half at 84, 36% up, 4.5x risk/return (example only)"],
  "session_introduced": "May'18 Webinar (Session II)",
  "day_introduced": "Session II",
  "valid_from_year": 2018,
  "valid_to_year": "current",
  "source_files": ["Webinar.pptx", "swing trading manas.pdf", "Q&A.pdf / Q manas.pdf"],
  "uncertain": []
}
```
