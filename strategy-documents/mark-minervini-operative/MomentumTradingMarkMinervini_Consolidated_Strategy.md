# Momentum Trading — Mark Minervini: Consolidated Strategy (SEPA)

> Implementation-ready consolidation of the trading method in **Mark Minervini, _Trade Like a Stock Market Wizard: How to Achieve Superperformance in Stocks in Any Market_** (McGraw-Hill, 2013), **its sequel _Think & Trade Like a Champion: The Secrets, Rules & Blunt Truths of a Stock Market Wizard_ (2017)**, **the _2020 Master Trader Program (MTP) Superperformance Workshop_ workbook (Mark Minervini with David Ryan, 2020)**, **the _2021 Master Trader Program Superperformance Workshop_ workbook (a year-later re-run of the same workshop, 2021)**, **the _2022 Master Trader Program Superperformance Workshop_ (a further re-run of the same workshop — two physical volumes, co-instructor Mark Ritchie II, 2022)**, **and the _2025 Master Trader Program Superperformance Workshop_ Course Workbook (a sixth rendering of the same workshop — two volumes, co-instructor Mark Ritchie II, 2025)**. Built for two readers: a **developer** (precise rules + a machine-readable JSON appendix, §6) and a **trader** (numbered, executable steps). A condensed companion — `MomentumTradingMarkMinervini_Cheat_Sheet.md` — derives the day-to-day execution steps from this document; read this document first.

**Category:** `MomentumTradingMarkMinervini` · **Sessions:** 2013 (introduced) + 2017 (refinement — adds NO new strategy) + 2020 (Master Trader Program workshop — refinement, adds NO new strategy) + 2021 (re-run of the MTP workshop — confirms 2020, adds NO new strategy and NO value changes) + 2022 (further re-run of the MTP workshop, co-instructor Mark Ritchie II — confirms 2020/2021, adds NO new strategy; one minor staggered-stop refinement) + 2025 (sixth rendering of the MTP workshop, co-instructor Mark Ritchie II — confirms 2013–2022, adds NO new strategy; two minor refinements + one re-frame) · **Class:** US-equity momentum, long-only, swing-to-position (holding days to many months).

## Scope & sources
- **Six sources, one method.** Session **2013** = _Trade Like a Stock Market Wizard_ (13 chapters) introduced every strategy in §3 and every shared concept in §4. Session **2017** = _Think & Trade Like a Champion_ (Introduction + 11 Sections) is a **continuation / refinement** of the same SEPA method: it re-teaches and confirms the 2013 framework (identical 5-element SEPA, 8-criteria Trend Template, VCP / 3-C Cheat / Power Play / Primary-Base setups) and **adds NO new §3 strategy**. Session **2020** = the _Master Trader Program (MTP) Superperformance Workshop_ workbook (two volumes, 28 sections; Mark Minervini with 3-time U.S. Investing Champion David Ryan) — the most comprehensive teaching of the **same SEPA method** and likewise **adds NO new §3 strategy**. Its sections (Failure Reset, Squats & Reversal Recoveries, Time Compression, Making the Turn, Tennis Ball/Natural Reaction, Primary Base, Power Play, Climax Run, PE Expansion, Base Count, Sell Rules, 50-Day Breakeven, Risk Management, Position Sizing, Daily Routine, Post Analysis) all reduce to the six registry setups + already-documented shared concepts; its genuinely new material is **operational** (a daily routine, post-trade grading, named screeners, a catalyst taxonomy, base-duration windows, and quantified risk tables) — folded into §2, §3.1, §3.6, and §4 with point-of-use **`[2020]`** tags and logged per area in the Strategy Evolution (§5). Session **2021** = the _2021 Master Trader Program Superperformance Workshop_ — a **year-later re-run of the same workshop** (same two-volume curriculum, same instructors, same teaching examples). It **adds NO new §3 strategy and NO value changes**: every numeric table read identically (Position-Sizing Guidelines, the Report Card 5/20/60/15…, the 8 Keys, the Trading Triangle, the Code-3 Matrix). Its only deltas vs 2020 are a few re-framings + one resolved uncertainty: §4 is retitled **"The Stock Maturation Cycle"** (was "The Primary Trend"); the **Earnings Maturation Cycle** diagram (title-only in 2020) is now fully shown, so §4.8 upgrades it from *inferred* to *confirmed*; the **Code-3 Matrix** and the **RBAF** position-sizing worksheet are re-shown (reconfirming the Code-33 reading and RBAF); Time Compression / Making-the-Turn are re-ordered; "Mind Blowing Math" + "Closing Remarks" cap Volume Two (compounding / opportunity-cost / feedback-loop content already documented). Logged in §5; point-of-use `[2021]` tags mark the confirmations. Session **2022** = the _2022 Master Trader Program Superperformance Workshop_ — **a further re-run of the same workshop** (two physical volumes; same SEPA curriculum, same teaching charts), this time with **co-instructor Mark Ritchie II** (2020/2021 paired Minervini with David Ryan). It **adds NO new §3 strategy** and confirms 2020/2021: every load-bearing table read identically (Position-Sizing Guidelines, the Report Card 5/20/60/15·70/15/15·65/20/15, the 8 Keys, the Code-3 Matrix, base-duration windows, the options implied-move tool with the same (3.50 call + 2.75 put)/162 ≈ 3.86% worked example). Its **one numeric refinement** is in the stop bands: the staggered-stop **"Uncle Point" (deepest blended leg) is now 10–12%** (2020/2021 showed 10%) and the alternate blend example becomes **4% + 12% ≈ 8%** (was 4% + 8% ≈ 6%) — the 10% hard cap, the 7–8% single-stop max, and the realized/blended-loss targets are unchanged (§2.2; §5.1). Logged in §5.5; point-of-use `[2022]` tags mark the confirmation/refinement. Session **2025** = the _2025 Master Trader Program Superperformance Workshop_ Course Workbook (two volumes, again with co-instructor **Mark Ritchie II**; pure image scans, every page rendered to PNG and read visually) — the **sixth rendering** of the same workshop and again **adds NO new §3 strategy**. It confirms 2013–2022: every load-bearing table read identically (stop bands, the staggered-stop "Uncle Point" 10–12%, the Code-3 Matrix, the Report Card 5/20/60/15·70/15/15·65/20/15, the 8 Keys, the options implied-move tool's (3.50 call + 2.75 put)/162 ≈ 3.86% example, the RBAF worksheet). Its **two numeric refinements** are: the typical per-trade equity-risk band tightens **1.25–2.5% → 1.00–1.50% (2.50% max)** (the ROTE worked example still uses 1.25% as the midpoint), and the **Darvas-Box base-duration window widens 4–5 → 4–6 weeks**. It also **renames the "Making the Turn" reversal entry the "Bottom Fishing Pivot"** — a re-frame of the documented A→B→C→D turn, not a new setup. Logged in §5.6; point-of-use `[2025]` tags mark the refinements/confirmations.
- **Versioning / conflict rule (per INSTRUCTIONS):** when a value differs across sessions, the **latest session wins** (2025 over 2022 over 2021 over 2020 over 2017 over 2013) and the older value is recorded in §5 as the prior reading (none are hard contradictions — they are tightenings, e.g. average-loss target 6–7% → 5–6%; working position count 4–6 → 4–8; difficult-market loss-cut 5–6% → 4–5% `[2020]`). **2021 changed no value** — it re-states the 2020 numbers verbatim. **2022 changes one value** — the staggered-stop "Uncle Point" ceiling 10% → **10–12%** `[2022]` (§2.2; §5.1) — and otherwise re-states the 2020/2021 numbers verbatim. **2025 changes two values** — the typical per-trade equity-risk band 1.25–2.5% → **1.00–1.50% (2.50% max)** `[2025]` (§2.4) and the **Darvas-Box base-duration window 4–5 → 4–6 weeks** `[2025]` (§4.6) — plus a re-frame (renames "Making the Turn" the **"Bottom Fishing Pivot"**); every other 2025 value re-states 2020–2022 verbatim.
- The method is **SEPA (Specific Entry Point Analysis)** — a single integrated system. §3 presents it as the master playbook (§3.1) plus the specific entry SETUPS and exit mechanics used inside it; the shared technical / fundamental / market concepts are defined once in §4 and referenced by name.
- **Long-only** in practice (buy confirmed Stage-2 uptrends). Shorting is noted only for Stage-4 downtrends and is not developed, so strategy entry rules are long-side only.
- Numbers are taken verbatim from the books. Stock-specific figures (a named company's % move) are labelled as **examples**, never general rules. Genuinely unsettled points are tagged **UNCERTAIN**.

## Strategy roster
| # | key | Name | Role | Introduced |
|---|-----|------|------|-----------|
| 3.1 | `sepa` | SEPA Master Playbook | the end-to-end method | 2013 / Ch 3 |
| 3.2 | `vcp` | Volatility Contraction Pattern (VCP) + Pivot Buy | primary entry setup | 2013 / Ch 10 |
| 3.3 | `cheat_3c` | The Cheat (3-C Low-Risk Entry) | earlier, lower-risk entry | 2013 / Ch 10 |
| 3.4 | `power_play` | Power Play (High, Tight Flag) | high-velocity continuation | 2013 / Ch 10 |
| 3.5 | `primary_base` | Primary Base & IPO Setup | young-leader first base | 2013 / Ch 11 |
| 3.6 | `selling` | Selling: Offensive & Defensive | shared exit discipline | 2013 / Ch 5, 12, 13 |

> All six strategies were **introduced in 2013** and **refined (not replaced) in 2017 and again in 2020**, then **re-confirmed in 2021, 2022, and 2025** (the 2022 re-run carries one minor staggered-stop refinement, §5.5; the 2025 rendering carries two minor refinements + one re-frame, §5.6) — none of the 2017 book, the 2020 workshop, or the 2021/2022/2025 re-runs adds a new strategy. The biggest 2017 reinforcement lands on `selling` (§3.6) and the global risk framework (§2); the 2020 MTP deepens the **operational layer** (daily routine §2.10, post-trade grading §2.11, named Trend-Template screeners §4.2, base-duration windows §4.6, the catalyst taxonomy & "commodity play" §4.11, and quantified risk tables §2.2–§2.5); the 2021 re-run **confirms 2020 with no value changes** (deltas are re-framings only — see §5); the 2022 re-run confirms 2020/2021 with **one** staggered-stop refinement (§5.5). Per-strategy 2013 → 2017 → 2020 → 2021 → 2022 → 2025 change logs are in §5.

**Shared components** (not standalone strategies; defined in §4): Stage Analysis · Trend Template · Pivot / Line of Least Resistance · Technical Footprint · Volume Analysis · Base & Correction Geometry · Leadership Profile (Fundamentals) · Earnings Quality · P/E & Valuation Context · Relative Strength & Market Leadership · Industry Groups, Catalysts & Categories · General Market Direction & Timing.

## Document map
- **§1 Introduction & Terminology** — overall approach + glossary.
- **§2 Global Risk Management Framework** — the risk-first core: loss math, stops, expectancy, position sizing, exposure, the trade plan (§2.8), self-measurement (§2.9), the daily routine (§2.10 `[2020]`), and post-analysis / trade grading (§2.11 `[2020]`).
- **§3 Strategies** — §3.1 master playbook + §3.2–§3.6 setups and exits.
- **§4 Common Rules & Shared Components** — every shared concept, defined once.
- **§5 Strategy Evolution** — per-strategy / per-component change log across the six sessions (2013 → 2017 → 2020 → 2021 → 2022 → 2025).
- **§6 Machine-Readable Appendix** — per-strategy JSON.

---
# 1. Introduction & Terminology

## A. Overview of the Approach

- **SEPA = Specific Entry Point Analysis** — Minervini's proprietary methodology that COMBINES corporate fundamentals with a stock's technical behavior to pinpoint the precise spot to enter a high-probability trade in terms of risk vs. reward. (Ch 3; p. 32)
- **Goal = superperformance via compounding, with losses kept small.** Pursue large capital gains while being risk-first: every trade evaluation starts with "How much can I lose?" before "How much can I gain?" Success "comes down to having your gains on average be larger than your losses, nailing down a profit, and repeating the process." (Ch 1)
- **The whole method services three decisions:** (1) WHAT to buy, (2) WHEN to buy, (3) WHEN to sell. (Ch 1; p. 5)
- **SEPA's stated objective:** buy only at the point of lowest risk / highest potential reward — ideally "purchase a stock and be at a profit immediately." (Ch 3; pp. 35–36)
- **The 5 Key Elements of SEPA** (the five foundational categories every candidate is judged on):
  1. **Trend** — virtually every superperformance phase happens while the stock is in a definite price UPTREND (qualified by the Trend Template, §4.2).
  2. **Fundamentals** — most superperformance phases are driven by improving EARNINGS, REVENUE, and MARGINS, typically materializing before the price phase.
  3. **Catalyst** — every huge winner has a catalyst that ignites institutional interest (new hot product, FDA approval, new contract, new CEO).
  4. **Entry Points** — most superperformers offer at least one low-risk entry; timing it right (in a bull market) can put you at a profit immediately.
  5. **Exit Points** — stop-loss points are mandatory to force you out of losers; the END of a superperformance phase must be identified to keep gains. (Ch 3; pp. 32–34)
- **The screening funnel** (huge universe → ranked shortlist):
  1. **Trend Template (§4.2)** — purely technical pass/fail qualifier; applied FIRST.
  2. **Growth / RS / Volatility screen** — Trend-Template survivors are filtered on earnings, sales- and margin-growth, relative strength, and price volatility. **≈95% of all stocks that qualify under the Trend Template still FAIL this screen.** (Ch 3; p. 34)
  3. **Leadership Profile (§4.7)** — remaining names are matched against the fundamental + technical attributes of historical superperformer models; removes most of what's left.
  4. **Manual review / "relative prioritizing"** — the narrowed list is scored and ranked by hand. (Ch 3; pp. 34–35)
- **Trade trigger = probability convergence.** Execute only where company fundamentals, stock price, volume activity, AND overall market conditions ALL align — "four cars arriving at the same time at a four-way intersection." (Ch 3; pp. 35–36)
- **Target stock profile:** young leaders (superperformance phase often occurs in the first ~10 years after IPO — illustrative, not a hard cutoff), with already-measurable strong earnings/sales growth, in leading industry groups, most often SMALL- or MID-cap with a small float, emerging from corrections/consolidations into new high ground. (Ch 3; pp. 36–38)
- **Market timing:** **more than 90% of superperformance stocks began their surges as the general market came OUT of a correction or bear market** — very few during a bear market. Be willing to move to cash when conditions deteriorate (§4.12). (Ch 3; p. 36; Ch 1)
- **Timeframe & instrument:** US common stocks, swing-to-position trading (days to many months). The method is LONG-ONLY in practice — buy only confirmed Stage-2 uptrends (§4.1). Shorting is mentioned only for Stage-4 downtrends and is not developed as a strategy. (Ch 5; Ch 10)
- **Operating discipline:** trade from a written rule set with clearly stated goals; specialize in one defined style; concentrate in a small list of well-selected names rather than diversify; use stop-loss protection; never average down into a falling position. (Ch 1)

## B. Glossary

| Term | Definition / Exact Settings |
|---|---|
| **SEPA (Specific Entry Point Analysis)** | Minervini's methodology combining corporate fundamentals with a stock's technical behavior to pinpoint the precise lowest-risk / highest-reward entry. (Ch 3) |
| **Superperformance / superperformance stock** | An elite big winner. Source-study definitions SEPA was built on: Love — a stock rising a MINIMUM of 300% in a TWO-YEAR period; Reinganum — "superior securities" rising a MINIMUM of 100% in a calendar YEAR. (Definitional/historical, not Minervini's live screen thresholds.) (Ch 3; p. 28) |
| **Superperformance phase** | The rapid price-advance period in which a stock posts its biggest gains. (Ch 3) |
| **The 5 SEPA Elements** | Trend, Fundamentals, Catalyst, Entry Points, Exit Points — the five categories every candidate is judged on. (Ch 3) |
| **Probability / supportive convergence** | The simultaneous alignment of fundamentals + price + volume + market conditions that defines the SEPA trade trigger ("four-way intersection"). (Ch 3) |
| **Stage Analysis** | Weinstein's 4-stage stock life-cycle framework (§4.1); used for perspective on where a stock sits in its cycle, NOT for pinpoint timing. (Ch 5) |
| **Stage 1 — Neglect / Consolidation** | Price moves sideways, oscillating around its 200-day (40-week) MA with no real trend; volume light. AVOID. (Ch 5) |
| **Stage 2 — Advancing / Accumulation** | The uptrend stage: price above a rising 200-day MA, 50>150>200-day MAs, higher highs/higher lows, accumulation volume. The ONLY stage Minervini buys. (Ch 5) |
| **Stage 3 — Topping / Distribution** | Rising volatility, erratic/wider swings, a major price break on heavy volume, 200-day MA flattening then rolling over. Sell into the start of Stage 3. (Ch 5) |
| **Stage 4 — Declining / Capitulation** | Mostly below a downtrending 200-day MA, lower lows/lower highs, near 52-week lows. Avoid long; short candidate only. (Ch 5) |
| **Trend Template** | The 8 nonnegotiable technical qualifying criteria (§4.2) defining a confirmed Stage-2 uptrend; a pass/fail screen applied to every candidate before fundamentals. ALL 8 must be met. (Ch 5; p. 79) |
| **Nonnegotiable criteria** | Strict, unambiguous technical standards (the initial filter), not open to interpretation. (Ch 5) |
| **VCP (Volatility Contraction Pattern)** | A constructive base where volatility contracts from LEFT to RIGHT (greater volatility on the left, lesser on the right) with volume drying up at specific tight points; defines a precise low-risk entry at the line of least resistance. (Ch 10) |
| **Contraction / "T"** | One pullback within a base, measured high-to-low; counted as Ts and written 1T…6T. Typical VCP = 2 to 4 contractions (sometimes 5 or 6). Rule of thumb: each successive contraction ≈ HALF (± a reasonable amount) of the prior one. (Ch 10; Fig 10.4) |
| **Technical footprint** | A consolidation's unique signature: Time (weeks since base start), Price (depth of largest correction AND narrowness of tightest final pullback), and Symmetry (number of Ts). (Ch 10; Fig 10.6) |
| **Footprint abbreviation** | Format `[weeks]W [deepest%/tightest%] [count]T`. Canonical example: `40W 31/3 4T` = 40 weeks long, deepest correction 31%, tightest final pullback 3%, 4 contractions. (Ch 10; Fig 10.6) |
| **Pivot point / line of least resistance** | The high of the final, tightest contraction; a breakout above it on rising volume is the entry. (§4.3) (Ch 10) |
| **Shakeout / undercut** | A sharp drop that flushes weak holders (often undercutting a prior low or support) before the stock reverses higher; tied to supply exhaustion in a base. (Ch 5 / Ch 10 context) |
| **The Cheat (3-C low-risk entry)** | An early, lower-risk entry within the formation of a base (detailed in §3 `cheat_3c`), taken before the conventional pivot. |
| **Power Play (High, Tight Flag)** | A high-momentum continuation setup off an explosive advance (detailed in §3 `power_play`). |
| **Primary base** | The first major base a stock forms (often the post-IPO base); the earliest, frequently most rewarding Stage-2 setup (detailed in §3 `primary_base`). |
| **Base / consolidation** | A pause/correction within a Stage-2 uptrend where the stock digests gains before continuing higher. Inspection window: 3 to as many as 60 weeks; bases within Stage 2 most commonly last 5 to 26 weeks. (§4.6) (Ch 5; Ch 10) |
| **Base count / base counting** | Counting the bases formed along a Stage-2 advance to gauge how late the trend is: a top generally forms after 3 to 5 bases; bases 1–2 are earliest/best, 4–5 late and failure-prone. (Ch 5; pp. 81–83) |
| **Darvas box** | A square/tight box base variation with no real volatility contraction (non-VCP). (Ch 10) |
| **Flat base** | A 4–7-week tight sideways box correcting only ~10–15% high→low; a non-VCP base variation. (Ch 10) |
| **Leadership Profile (™)** | Minervini's blueprint of the fundamental + technical qualities shared by the most successful past stocks (data from the late 1800s plus ~3 decades of his trading); used to RANK candidates by fit. (§4.7) (Ch 3) |
| **Catalyst** | The event/development that ignites institutional interest and a superperformance phase: new hot-selling product, FDA approval, newly awarded contract, new CEO. (§4.11) (Ch 3) |
| **Code 33** | The strongest earnings-quality signal: **three consecutive quarters of simultaneous acceleration in EPS, sales, AND profit margins.** (Likely "3 measures across 3 quarters" — the source names it "Code 33" but does not explain the name; editorial inference.) (§4.8) (Ch 8) |
| **Earnings surprise** | Reported earnings (or sales) coming in materially above analysts' expectations; a positive surprise history and future surprises are core ranking inputs. (§4.8) (Ch 3) |
| **Estimate revision** | Upward (or downward) changes by analysts to their future earnings estimates; positive revisions are a forward-looking target the ranking seeks. (§4.8) (Ch 3) |
| **Relative Strength (RS) rank** | IBD's relative price-strength ranking vs. the market, scaled 1–99. Trend Template requires RS ≥ 70, preferably in the 80s or 90s. (§4.10) (Ch 5; p. 79) |
| **50-day / 150-day / 200-day MA (10/30/40-week)** | The three moving averages used throughout. Equivalences: 50-day = 10-week, 150-day = 30-week, 200-day = 40-week. In a confirmed Stage 2: price above the 200-day, 200-day rising, 150 > 200, 50 > 150 > 200, price above the 50-day. (§4.1, §4.2) (Ch 5) |
| **Accumulation** | Institutional buying — the Stage-2 signature: big up days/weeks on abnormally large volume vs. lighter volume on pullbacks. (§4.5) (Ch 5) |
| **Distribution** | Stage-3 selling — stock changing hands from strong early buyers to weaker latecomers; topping action with rising volatility. (§4.5) (Ch 5) |
| **Capitulation** | Stage-4 exhaustion selling / decline phase. (Ch 5) |
| **Follow-through** | A confirming move that validates a new advance — for a stock, the breakout extending higher on volume after the pivot. For the MARKET, Minervini does NOT use the IBD "follow-through-day" concept; his uptrend-confirmation tells are the **new-52-week-high list outpacing/expanding past the new-low list**, **up days on higher volume / down days on lower volume**, and lockout behavior (§4.12). — UNCERTAIN: neither book states a counted market follow-through-day rule (day count / % gain). |
| **Overhead supply** | Trapped buyers from prior higher prices who sell into rallies to break even; its absorption is what produces the volume contraction in a VCP. (Ch 10; Fig 10.8) |
| **Float / shares in the float** | Number of shares available to trade; a SMALL float is named a key superperformance factor (a small float moves on less demand). (Ch 1; Ch 3) |
| **Bear trap** | A counter-rally within a Stage-4 decline that lures buyers before the downtrend resumes; do not mistake it for a new Stage 2. (Ch 5; Fig 5.9) |
| **Reverse factor modeling** | The research method — cross-referencing past big winners to extract their common characteristics ("studying the best to find the best") — from which SEPA's criteria were derived. (Ch 3) |
| **52-week high / low** | Reference points for the Trend Template: price ≥ 25% above the 52-week low `[2017]` (30% in 2013 — see §5.1) and within ~25% of the 52-week high to qualify; ≥ 25–30% off the 52-week low confirms a Stage-2 advance has begun. (§4.2) (Ch 5; p. 79) |

### B.2 Glossary — terms formalized, quantified, or added in 2017 (_Think & Trade Like a Champion_)

> Most rows below are genuine 2017 additions. A few — **tennis-ball action, squat, lockout rally, cheap trap, low cheat** — are **2013-origin concepts** that 2017 only re-named, quantified, reworded, or formalized; their origin is noted in-row, and the `[2017]` tag on those marks the *refinement*, not the origin.

| Term | Definition / Exact Settings |
|---|---|
| **The 50/80 Rule** `[2017]` | Once a secular market leader puts in a major top, there is a **50% chance it declines ~80%** and an **80% chance it declines ~50%**; topped leaders fall **>70% on average**. A cautionary probability for heeding the FIRST loss — never average down a topped leader. (§3.6, §4.9) (2017 §5) |
| **MVP indicator (a.k.a. "ants")** `[2017]` | David Ryan's early-momentum signal over a **15-day window**: **M**omentum = stock up **12 of 15 days**; **V**olume = volume up **≥25%** across the 15 days; **P**rice = stock up **≥20%** across the 15 days. Bullish hold/entry signal EARLY in a move; the same up-day cluster LATE in a move is an exhaustion *sell* tell (§3.6). Do not buy on MVP if extended; buyable only if the window begins near a base bottom. (§4.10) (2017 §1, §9) |
| **Velocity trade** `[2017]` | A fast mover off the line of least resistance targeting **~20 / 30 / 50% in a few weeks to a few months**, to compound capital rapidly (aim ~10%/month vs 20%/year). (§3.1, §4.3) (2017 §10) |
| **R-multiple (R / 2R / 3R)** `[2017]` | One unit of risk = the distance to your stop. A "2R trader" wins **2 units per unit risked**; a stock up **2R / 3R** is up 2×/3× your stop. Set the stop off your realized average gain, NOT a hoped target. (§2.3, §2.6, §3.6) (2017 §3, §9) |
| **Expectancy (formula)** `[2017]` | Minervini's edge ratio = **(% winning trades × average gain) ÷ (% losing trades × average loss)**; must be **> 1** to be a net winner. (§2.3) (2017 §3) |
| **Trading Triangle** `[2017]` | The three interdependent levers of expectancy: **average win %**, **average loss %**, and **batting average** (win rate). Improve at least one to stay profitable. (§2.3, §2.9) (2017 §4) |
| **TBA / RBA** `[2017]` | **Theoretical Base Assumption** = setting expectations from what you *think* a stock will do (projection — unreliable). **Result-Based Assumption** = setting risk from your *actual* realized average results. Always use RBA. (§2.3, §2.9) (2017 §3) |
| **ATR (Average True Range)** `[2017]` | Welles Wilder's volatility measure (higher ATR → wider stop). Minervini **defines it but does NOT use it to widen stops** — high volatility usually means a tough market, so cut losses *shorter*, not wider. (§2.2) (2017 §3) |
| **Staggered / bracketed stop** `[2017]` | Splitting the stop across the position so part survives a dip: e.g. ⅓ at 3% / ⅓ at 5% / ⅓ at 8% (≈5% total), or 4% on half + 8% on half (≈6% total). (§2.2) (2017 §3) |
| **Add-and-Reduce** `[2017]` | Pyramiding that holds dollar risk constant: add shares at a new buy point and raise the stop on the *full* position so total risk stays fixed (or goes to zero on the principal). (§2.4) (2017 §3) |
| **Breakeven-or-better rule** `[2017]` | A trailing stop anchored on the **50-day MA**: once the rising 50-day MA reaches your entry (breakeven), switch the stop to the 50-day line (exit on a close below — optionally the Friday weekly close). (§2.6, §3.6) (2017 §9) |
| **Back stop** `[2017]` | A *profit-protection* stop (distinct from a lockstep trailing stop): set manually at the amount of profit you want to protect — often **at or above your average gain** — letting the stock fluctuate above it; raised in steps as price advances. (§2.6, §3.6) (2017 §9) |
| **Free roll** `[2017]` | Once a stock is up **2R–3R**, **sell half** and either move the stop to breakeven (lock the taken profit) or keep the original stop (finance the back half with the front-half profit) — worst case ≈ breakeven on the remainder. (§2.6, §3.6) (2017 §9) |
| **Two-for-one rule** `[2017]` | Reallocation tactic: of 6 names, if 4 work and 2 lag, **sell half of each laggard** and use the combined proceeds to fund **one full new position**. (§2.5) (2017 §8) |
| **Di-worsification** `[2017]` | Over-diversifying so positions are too small to drive superperformance — the opposite of intelligent concentration. (§2.5) (2017 §8) |
| **Danger point** `[2017]` | The technical level (just below a low-risk entry) where, if breached, the trade is wrong — buy **as close to it as possible** so risk per share is small. (§2.2, §4.3) (2017 §2) |
| **Tennis-ball action** ("hold tennis balls, sell eggs") | **2013-origin** (Ch 10, "The Natural Reaction and Tennis Ball Action"). A healthy post-breakout pullback snaps back to new highs on expanding volume (a tennis ball, not an egg). `[2017]` coined the "splat like an egg" phrasing and quantified the recovery window (**2–5 days, sometimes 1–2 weeks**), folding it into the 5-point winner checklist. (§3.1, §4.5) (Ch 10; 2017 §1) |
| **Squat** | **2013-origin** (Ch 10, "Squats and Reversal Recoveries"): a breakout that **falls back into its range and closes off the day's high**; not an automatic sell — allow a reversal-recovery window (1–2 days up to ~10 days) above the stop. `[2017]` re-teaches the same definition (one Fig 1-13 example closed below the day's midpoint — illustrative, not a redefinition). (§3.1, §3.2) (Ch 10; 2017 §1) |
| **Serial gapper** `[2017]` | A downtrend (Stage-4) stock that repeatedly gaps DOWN — why buying a falling stock carries outsized overnight gap risk. (§4.1) (2017 §6) |
| **Lockout rally** | **2013-origin** (Ch 9, "The Lockout," p.164). Early in a new bull market, waves of stocks make new highs with only minimal general-market pullbacks — the dip never comes and late buyers get "locked out"; an overbought tape ignored = strength, raise exposure. `[2017]` reworded the pullback band from **3–5%** to **"a few percent."** (§4.12) (Ch 9; 2017 §7) |
| **Double bottom** `[2017]` | A "W" base (preferably **undercutting** the first low to shake out weak holders) that must form a **right-side pause / pivot**; a non-VCP base variation alongside the Darvas box and flat base. (§4.6) (2017 §7) |
| **Low cheat** | **2013-origin** (Ch 10 3-C section names the low cheat, e.g. GOOG 2004). A 3-C cheat forming in the **lower third** of the base — riskier than the middle-third (classic) or upper-third (handle) cheat, but lower cost / higher reward; used to START a position then add at higher pivots. `[2017]` made the lower/middle/upper-third framing explicit + the low-cheat-as-scale-in START. (§3.3) (Ch 10; 2017 §7) |
| **Extended** `[2017]` | (David Ryan) a stock **more than ~10% above its most recent consolidation** — do NOT buy; wait for a pullback or a new base. (§3.2, §4.3) (2017 §1) |
| **Involuntary investor** | A trader who turns a losing trade into a long-term "hold" only to avoid booking the loss — forbidden. (Livermore; in 2013 §2.7, restated 2017 §2.) (§2.7) |
| **Cheap trap** | **2013-origin** (Ch 4, "The Cheap Trap"). Buying a stock only because it has fallen / "looks cheap"; the cheaper it gets the harder it is to sell. `[2017]` extended it with the topped-leader angle (a topped leader is *expensive* — its P/E soars as earnings deteriorate). (§4.9) (Ch 4; 2017 §5) |

### B.3 Glossary — terms formalized, quantified, or added in 2020 (_Master Trader Program_ workshop)

> The 2020 MTP re-teaches the same SEPA method; the rows below are the genuinely new/clarified items. Several — **Code 3 / Code 3 Matrix, Failure Reset, Squat, Ledge** — clarify or re-name 2013/2017 concepts; the origin is noted in-row and the `[2020]` tag marks the *clarification*, not the origin.

| Term | Definition / Exact Settings |
|---|---|
| **5 Key Areas** `[2020]` | The MTP's re-framing of the SEPA selection inputs: **(1) Categories & Catalysts, (2) Study of Price & Volume, (3) Company Fundamentals, (4) Entry Points, (5) Exit Points.** Maps onto the 2013 "5 Elements" (Trend + Fundamentals + Catalyst collapse into Categories&Catalysts + Price&Volume + Fundamentals). (§3.1) (MTP S1) |
| **SEPA's 3 objectives / process triad** `[2020]` | Objectives: **1. Take Minimal Risk, 2. Capture Large Gains, 3. Maximize Compounding**, delivered through a 3-part process: **a. Selection Process, b. Trade Execution, c. Position Management.** (§3.1) (MTP S1) |
| **Code 3 / "Code 3 Matrix"** `[2020]` | The 2020 name + grid for what 2013 called **Code 33**. The slide ("The 'Code 3' Matrix") scores **EPS, Revenue, Net-Profit-Margin** each over recent quarters in a 3×3 grid (cells 11…33); "Code 3 stocks" = all three metrics simultaneously accelerating (the top all-3 cell). This is the **best-supported reading** of the Code-33 name (§5.3) — the "33"/"3" is the matrix's top score rather than an arbitrary label — though the slide does not explicitly equate "33" with that cell. (§4.8) (MTP S5) |
| **Transition Criteria** `[2020]` | The Stage 1→2 **early-turn** detection checklist (used before a full Stage-2 confirmation): price above the 150- & 200-day MAs; 150-day above 200-day; 200-day has turned up; higher highs and a higher low — PLUS volume tells (large up-weeks on volume spikes vs light pullbacks; more up-weeks on rising volume than down; quick supportive recoveries on increased volume). (§4.1) (MTP S4) |
| **Trend-Template screeners (1-Month / 5-Month / 5-Month Wide)** `[2020]` | Three named scans built on the Trend Template: **1-Month** for Power-Play setups & early turns; **5-Month** for clear established uptrends; **5-Month Wide** drops the RS-rating and the price-above-50-day requirement to surface stocks just below their 50-day MA (low-cheat hunting ground). (§4.2) (MTP S4) |
| **Earnings Maturation Cycle** `[2020]` → diagram **confirmed `[2021]`** | A named lifecycle curve. The 2020 deck captured the **title only** (shape was inferred); the **2021 re-run shows the full diagram**, confirming it: a stock arcs from a **"Value Stock"** (Stage 1) → **Positive Surprise → Positive-Surprise Models → Estimates Revised Up → EPS Momentum** (Stages 2–3, the **"Growth Stock"** peak) → **Loss of EPS Momentum → Negative Surprise → Negative-Surprise Models → Estimates Revised Down** → back to a **"Value Stock"** (Stage 4). Favor companies whose acceleration is still early (left side of the arc; the fundamental analogue of an early base count, §4.6). (§4.8) (MTP S5; 2021 Vol 1) |
| **Commodity Play** `[2020]` | A bullish sub-case within the cyclical category: rapidly **rising commodity prices** against **inventory stockpiled at lower cost** = **margin expansion** (a catalyst). (§4.11) (MTP S2) |
| **Catalyst taxonomy (5 types)** `[2020]` | The MTP groups catalysts into five named types: **1. Totally New Category, 2. Breakthroughs & Disruptive Technologies, 3. New Industry Conditions, 4. Industry Spin-Off Effect, 5. Superior Solutions.** (§4.11) (MTP S2) |
| **"95% Club" (big-winner study)** `[2020]` | The MTP's consolidated study of past biggest winners: **99%** traded above the 200-day MA, **96%** above the 50-day MA, **~96%** began off a correction/consolidation, **~95%** had a **small float (< 30M shares)**, **~95%** showed earnings acceleration, **~95%** had a catalyst, **~70%** reported **+20% EPS** in the most recent quarter, **~80%** were **IPOs within the previous 10 years**. (Study statistics — characteristic profile, not pass/fail thresholds.) Separately (S4): **98%** of big winners made the bulk of their gain in a Stage-2 uptrend. (§4.1) (MTP S2; 98%-stat S4) |
| **Failure Reset (Pivot Reset / Base Reset)** | **2013-origin** ("Squats and Reversal Recoveries" / re-entry). A breakout/pivot that **fails** (Pivot Failure) is not abandoned: the stock can re-form and offer a fresh low-risk entry — a **Pivot Reset** (re-forms the same pivot) or a **Base Reset** (builds a larger base before a new buy point). `[2020]` gives it a dedicated section + the reset vocabulary. (§3.2, §3.6) (MTP S14) |
| **Ledge** `[2020]` | The small consolidation that forms **just below the pivot after a failed breakout attempt**; labeled **Pivot Failure / Base Failure**. Overhead supply ("look left for traffic") is the usual cause. (§3.6, §4.3) (MTP S13) |
| **MPA Short Alert** `[2020]` | A Minervini Private Access short-side alert triggered when a failed breakout forms a ledge below the pivot/base. Noted for completeness — the method remains **long-only**; short entries are not developed here. (§3.6) (MTP S13) |
| **Bullish Breakout Criteria** `[2020]` | A named 3-point breakout-confirmation set: **(1) +20% or more, (2) up 12 of 15 days, (3) elevated volume during the advance** — the same metrics as the David-Ryan MVP indicator (§4.10), applied as a confirmation that a breakout is working. (§3.1) (MTP S13) |
| **Sell Alerts (13-signal watch list)** `[2020]` | The MTP's single consolidated, numbered pre-decline (distribution/blow-off) warning checklist; the 13 signals already live across §3.6.A.3/§3.6.C — see §3.6.E.1 for the enumerated list. (§3.6) (MTP S23) |
| **Stop-placement bands** `[2020]` | Explicit labels for stop size: **very tight ≤3%**, **average 4–6%**, **max 7–8% ("Uncle Point")**, **10% (generally only with staggered stops)**. The 10% line-in-the-sand is unchanged. (§2.2) (MTP S26) |
| **The Report Card / Grading the Trade** `[2020]` | A post-trade grading framework: score each trade's **entry** and **exit** quality after you are out, with target distributions (see §2.11). (§2.11) (MTP S28) |
| **8 Keys to Superperformance** `[2020]` | The MTP's summary framework — **4 Keys to Big Returns** (Timing, Concentration, Turnover, Managing risk/reward) + **4 Keys to Low Drawdowns** (Sell into strength, Trade directionally, Progressive exposure, Protect breakeven). (§2.11) (MTP S28) |
| **Daily Routine** `[2020]` | The MTP's prescribed end-of-day + pre-market workflow (evening screening into 3 lists; 5 nightly screens; a pre-market checklist with pivot pre-alerts). See §2.10. (§2.10) (MTP S24) |

### B.4 Glossary — terms re-framed or confirmed in 2021 (_Master Trader Program_ re-run)

> 2021 re-runs the 2020 workshop verbatim; the rows below are the only naming/clarification deltas. No new rule and no value change — see §5.

| Term | Definition / Exact Settings |
|---|---|
| **Stock Maturation Cycle** `[2021]` | The 2021 title for the §4 trend/stage block (2020 called it "The Primary Trend — Non-Negotiable Criteria"). Same content: a stock matures through the four **Stage Analysis** phases (§4.1) and the **8-criteria Trend Template** (§4.2) is the non-negotiable qualifier for the Stage-2 advance. A re-frame, not a new concept. (§4.1, §4.2) (MTP 2021 Vol 1) |
| **RBAF — Result-Based Assumption Forecast** `[2021]` | Already documented from 2017 (§2.9); 2021 shows the full MTP worksheet. Inputs: portfolio size, position-size %, desired % return, realized average gain / loss / % winning trades; outputs: $-per-trade, expected net return, **# of trades needed to reach the goal**, and **Optimal f**. Use it to pick a realistic position size and judge whether a return goal is achievable. (Worked example on the slide: $200K portfolio, 25% position, 100% target, 12% / 6% average gain/loss, 50% winning trades → **134 trades needed**, **Optimal f 25%**. A separate sensitivity panel shows smaller positions need more trades — at a 40% target, a 10% position needs ~334 trades vs ~134 at 25%.) (§2.9) (MTP 2021 Vol 2) |

### B.5 Glossary — terms confirmed / refined in 2022 (_Master Trader Program_ re-run, with Mark Ritchie II)

> 2022 is a third rendering of the same MTP workshop (now with co-instructor **Mark Ritchie II** in place of David Ryan, in two physical volumes). It defines **no new term** and changes no core rule; the single row below is its one numeric refinement. See §5.5.

| Term | Definition / Exact Settings |
|---|---|
| **Staggered "Uncle Point" — 10–12%** `[2022]` | In a staggered / bracketed stop, the **deepest single leg** ("Uncle Point", used **only** within a staggered stop) is shown as **10–12%** in 2022 (2020/2021 showed **10%**). The blended/realized loss still lands at target (the 2022 slide's blends are **6% + 10% ≈ 8%** and **4% + 12% ≈ 8%**; the 2020/2021 second blend was 4% + 8% ≈ 6%), and the **10% hard cap** on any position's realized loss and the **7–8% single-stop max** are unchanged. A widened backstop leg, not a loosening of the realized-loss cap. (§2.2) (MTP 2022 Vol 2) |

### B.6 Glossary — terms confirmed / refined in 2025 (_Master Trader Program_ Course Workbook, with Mark Ritchie II)

> 2025 is the sixth rendering of the same MTP workshop (again co-instructed by **Mark Ritchie II**, two volumes). It defines **no new term** and adds no new strategy; the rows below are its two numeric refinements + one re-frame. See §5.6.

| Term | Definition / Exact Settings |
|---|---|
| **Per-trade equity-risk band — 1.00–1.50% (2.50% max)** `[2025]` | The 2025 Position-Sizing slide states the **typical** Risk of Total Equity ("ROTE", §2.4) as **1.00–1.50%**, with **2.50%** the hard maximum (2017/2020/2021/2022 stated a flat **1.25–2.5%** band). The ROTE worked example on the next slide still solves to **1.25%** three equivalent ways ($1,250 on a $100k account: 12.5%×10% = 25%×5% = 50%×2.5%), so **1.25% remains the canonical worked midpoint** — sitting inside the new typical band. A tightening of the *typical* target, not of the 2.50% ceiling. (§2.4) (MTP 2025 Vol 2) |
| **Darvas-Box base-duration window — 4–6 weeks** `[2025]` | In the per-pattern base-duration list (§4.6), the **Darvas Box** typical window is shown as **4–6 weeks** in 2025 (2020/2022 showed **4–5 weeks**). The other four windows are unchanged (Cup-Cheat/Low-Cheat 6–52 wk; Cup-with-Handle 7–65 wk; Double Bottom 7–65 wk; Power Play 2–6 wk). (§4.6) (MTP 2025 Vol 1) |
| **"Bottom Fishing Pivot"** `[2025]` (re-frame) | The 2025 name for the documented **"Making the Turn"** reversal entry (§3.3). Same A→B→C→D schematic — a descending trendline drawn across the lower-high peaks of an intermediate decline (the decline visualised in **thirds**), with contracting bounces, broken to the upside as a small base forms at the bottom: **A** = downtrend, **B** = mark-up that breaks the downtrend, **C** = pullback within the base, **D** = breakout above the pullback high. A naming/visual re-frame, **not** a new setup (cf. 2021's "Stock Maturation Cycle" re-title). (§3.3) (MTP 2025 Vol 1) |
---
# 2. Global Risk Management Framework

SEPA is a **risk-first** method: every trade begins with "How much can I lose?" before "How much can I gain?" (Ch 1). Sizing, stops, and exposure are all driven by the loss math below — not the upside. These rules apply to **every** strategy in §3; the strategy sections reference them by subsection number rather than restating them.

## 2.1 The Loss Math (why losses are capped first)

- **Losses compound geometrically against you, not linearly.** The bigger the loss, the disproportionately larger the gain required just to get back to even. This is the entire justification for capping losses small.
- **Break-even table** — gain required to recover a given loss (Ch 12; Fig 12.1, general-rule values):

  | Loss | Gain to break even |
  |------|--------------------|
  | 5%   | 5.26% |
  | 10%  | 11% |
  | 20%  | 25% |
  | 30%  | 43% |
  | 40%  | 67% |
  | 50%  | 100% (a 50% loss needs a **double** to recover) |
  | 60%  | 150% |
  | 70%  | 233% |
  | 80%  | 400% |
  | 90%  | 900% |

- **"Two up, one down" lesson** (Ch 12; Fig 12.2): pairing two equal up-years with one equal-magnitude down-year decays fast as the swings grow. Best realistic case ≈ two +30% years and one −30% year ≈ **5.75%/yr**; at ±70% the three-year result turns **negative**. There is no good "two up, one down" outcome — the math forbids it.
- **Loss-Adjustment lesson (the book's worked example)** (Ch 12; Fig 12.3): take a real 1980s account of 10 gains + 10 losses. Gains (both versions): 6, 8, 10, 12, 15, 17, 18, 20, 28, 50% (≈ 18.40% average gain). Original losses 7, 8, 10, 12, 13, 15, 19, 20, 25, 30% (≈ 15.90% average loss) → **compounded return −12.05%**. Re-cap *every* loss at 10% (raise the small ones up, cut the big ones down) → **compounded return +79.89%**. Same gains, only the losses capped — the result flips from a loss to a big win.
  - *Note:* the order in which trades compound does not matter; the result is identical (Ch 12).
  - These specific percentages are Minervini's illustrative 1980s account — **an example, not a rule to copy**. The rule it proves is the 10% cap in §2.2.

## 2.2 Stop-Loss Rules

- **Absolute maximum — "line in the sand": no position may fall more than 10%** before you sell (Ch 12, Ch 13). This is a hard cap regardless of how large your average gains are. If you cannot be right with a 10% cushion for normal fluctuation, the fault is either flawed selection/timing or a hostile market in which you should be out of stocks.
- **Target average loss ≈ 5–6%** `[2017]` (2013 said **6–7%**; per the conflict rule the later, tighter value is primary) — much tighter than the 10% cap. This is the 2017 §8 position-sizing guideline ("losses should average no more than 5–6 percent"); Minervini's own realized average loss runs ~**4–5%** (about half his ~15% average gain). (Ch 12; 2017 §8 guideline, §3 realized)
- **Tie the stop to expected gain — stop ≤ one-half of your average gain** (Ch 13): if winning trades average +15%, stop no further than **7.5%** (e.g. buy $30 → initial stop $27.75).
  - But the 10% cap always overrides: even if half-of-average-gain would permit more, **never** go past 10%. Example: average gain 30% would mathematically allow a 15% stop — do **not** use it; a 10% decline already says something is wrong (Ch 13).
- **Trader's cardinal sin:** letting a single trade's loss exceed your average gain. Never let a loss grow larger than your average gain (Ch 13).
- **Write the stop price down before you buy** and treat it as an absolute maximum — once hit, **sell immediately**, no waiting "for the next rally" (Ch 13). On slippage (price gaps through your stop), exit at once at the next bid. "Your first loss is your best loss."
- **Monitor your average gain and re-set stops as it changes** (Ch 13).
- **Difficult-market tightening** (Ch 13; reaffirmed 2017 §3; tightened further **`[2020]`**): when the market turns hostile, cut at **4–5%** `[2020]` (2017 said 5–6%; both vs the normal 7–8% — see §5.1) and **take profit at 10–12%** (vs the normal 15–20%); get off margin **immediately**, **reduce position sizing and overall capital commitment**. Restore normal parameters only after batting average and risk/reward improve. **Never widen stops for volatile stocks** — high volatility usually means a tough market = lower batting average, so losses must be cut *shorter*, not given more room.

**`[2017]` stop-loss execution & placement additions** (2017 §2, §3):
- **No mental stops** — they are too easy to ignore. **Write the stop down** (a note/Post-it), set a price alert, or place an actual stop order with the broker that auto-triggers. Cut the loss the instant it hits, without vacillation.
- **Avoid the "emotional stop-loss."** Everyone has a pain threshold where they finally bail; for most it sits far *beyond* what makes mathematical sense. Place the stop where the math dictates, not where pain forces it.
- **Trade near the "danger point" (§4.3).** Enter as close as possible to the level that, if breached, proves the trade wrong — enough room for *normal* fluctuation, close enough that risk per share is small. Optimal stop placement separates normal price action from abnormal/dangerous action.
- **Avoid "bucking broncos" (highly volatile stocks).** A tight stop gets hit by normal gyrations; a stop wide enough for the swings exposes more downside than the math allows — so reject the candidate rather than widen the stop. This is the practical reason Minervini **defines ATR (Average True Range) but rejects volatility-widened stops** (see §1.B.2).
- **Staggered / bracketed stops** — split the stop so part of the position survives a dip while total loss stays at target: e.g. one-third at **3%**, one-third at **5%**, one-third at **8%** (≈5% total, two-thirds survive above a 5% loss); or **4% on half + 8% on half** (≈6% total). (Stock figures in the source — ISIS −6.10% etc. — are examples.)
- **Stop = a SELECTION filter, not just an exit ("backing into risk").** You control risk when you *buy* (choose a low-risk entry / right size / right time), not when you sell (you merely take the predetermined loss). Reject any trade where reward < risk — e.g. don't accept a 25% potential loss to chase only a 10–15% gain.
- **Post-trade stop calibration:** if you are stopped out *too often*, your stops are too tight; if your *losses are too large*, they're too loose. Tune from the spreadsheet (§2.9), not from emotion.

**`[2020]` stop-placement bands & tools** (MTP S26):
- **Stop-size bands (named):** **very tight = 3% or less**; **average = 4–6%**; **max = 7–8%**; the **"Uncle Point"** is the **staggered-only** ceiling — **10%** in 2020/2021, shown as **10–12%** `[2022]` (a deepest-leg backstop, used **only** within a staggered stop). The 10% hard cap (§2.2 top) on any position's *realized* loss is unchanged — 7–8% stays the practical single-stop ceiling, with the wider 10–12% leg reserved for the blended/staggered case. *`[2025]` re-shows the identical bands (≤3% / 4–6% / 7–8% / 10–12% Uncle Point) and both staggered blends (6%+10%≈8%, 4%+12%≈8%) — no change (§5.6).*
- **Staggered-stop blends restated:** **6% on half + 10% on half ≈ 8%** average; **4% on half + 8% on half ≈ 6%** average (cf. the 2017 thirds example). *`[2022]`: the second blend is re-illustrated as **4% on half + 12% on half ≈ 8%** — same principle (a tight leg + a wide "Uncle Point" leg netting the target average); the per-leg numbers are illustrative.*
- **"Try not to choke off the trade":** do **not** bail on the first wobble — a healthy winner is allowed a normal reaction before you act on violations. Illustrative tolerance from the workshop: hold through **3 lower lows AND a close below the 20- and 50-day AND ~3.5–4.25% below the buy point** (still above the hard stop) before reducing — balance stop discipline against premature exits. (Stock figures — e.g. Yelp 2013 — are examples.)
- **Gauge the "normal move" with options (volatility tool):** to size a stop around a stock's expected range, find the **at-the-money strike**, add the **ask of the ATM call + ATM put**, and divide by the current stock price. *Worked example: (3.50 call + 2.75 put) = 6.25; 6.25 ÷ 162 ≈ **3.86%** potential move in either direction.* Use it to avoid placing a stop inside the stock's normal noise. *`[2022]`: re-shown identically (same worked example), and paired with a **"Holding Through Earnings — Implied Odds"** slide that uses this implied move to judge the gap risk of holding into an earnings report (an earnings gap can be ~−20%, far past any stop) — reinforcing the gap-risk sizing in §2.4.*

## 2.3 Expectancy: Batting Average × Win/Loss Ratio

- **You need positive expectancy / an edge:** reward/risk must be greater than 1:1 net of costs; average losses contained below average gains (Ch 13).
- **Win/loss ratio targets:** maintain **at least 2:1**, **shoot for 3:1**, with the 10% max stop always in force (Ch 13).
  - At **2:1** you can be right only ~1/3 of the time and still avoid real trouble.
  - At **3:1**, even a **40% batting average** can yield a fortune — wins make money 3× faster than wrong trades lose it.
- **"Building in failure":** design risk/reward so a *low* batting average still survives. Do **not** rely on systems needing 70–80% win rates — they plan for the best and leave no adjustment when the edge slips (Ch 13). (Minervini's own ~30-year batting average is ≈ 50%; the dollar size of wins, not the hit rate, is what makes it work.)
- **Optimal gain/loss ratio by batting average** (Ch 13; Figs 13.1, 13.2):
  - **40% BA → 20%/10% (2:1)**, ROI ≈ **10.2% over 10 trades** (Fig 13.2).
  - **50% BA → 48%/24% (2:1)**, the table's 50%-BA peak (≈ 80% compounded over 10 trades). Fig 13.1's highlighted peak cell in the 50%-BA column sits on the 48/24 row, matching the text verbatim (Ch 13, p. 309: "the optimal result is achieved by having a 48 percent/24 percent win/loss ratio at a 50 percent batting average"). Every row in the table is a 2:1 pair — the optimum is the *absolute size* of the pair, not a change of ratio.
- **Below a 50% batting average → TIGHTEN stops, never widen them** (Ch 13): increasing risk proportionately to chase a higher expected gain drives you into **negative expectancy**, and the lower the batting average, the sooner that happens. Stated as a mathematical fact, not an opinion.
- **Geometric trap even at a fixed ratio** (Ch 13, illustrative): holding 2:1 at wider absolute numbers can still lose — e.g. +42%/−21% loses money vs. +20%/−10%; at 50% BA, +100%/−50% only breaks even; at 30% BA, +100%/−50% loses ≈ 93% in just 10 trades. Keep the *absolute* numbers small, not just the ratio. (2017 §3 restates this verbatim: at 40% BA, **4%/2%** nets ≈ **+3.63%** over 10 trades while the same-ratio **42%/21%** nets ≈ **−1.16%**.)

**`[2017]` expectancy framework** (2017 §3, §4):
- **Expectancy formula (the only "holy grail"):** the book defines it as a **ratio** — **Expectancy = (% winning trades × average gain) ÷ (% losing trades × average loss)** — which must be **> 1** ("positive") to win. *(Example: a 50/50 trader cutting losses at 10% but realizing only an 8% average gain → (50×8)/(50×10) = 0.8 < 1 = NEGATIVE expectancy = a loser.)*
- **The Trading Triangle** — expectancy rests on **three interdependent legs**: (1) average win %, (2) average loss %, (3) batting average. To stay profitable you must improve at least one: win more on winners, win more often, or cut losses tighter. *(Example: BA .500 with a 6% average loss but only a 5% average gain is unprofitable until you raise the gain, raise the win rate, or cut the loss below 5%.)*
- **Loss-as-fraction-of-gain rule of thumb:** to hold **2:1** you must contain losses to **half** your gains at a **50% BA**, or to **one-third** of your gains at a **40% BA**.
- **Win-rate reality:** expect to be right only ~**50%** of the time (the best traders ~**60–70%** in a healthy market). You can profit at a 1-in-2 *or even 1-in-3* win rate **if** losses are kept small. Do not build a system that needs a 70–80% hit rate.
- **Set risk from results, not projections (TBA vs RBA):** a **Theoretical Base Assumption** (what you *think* the stock will do) is unreliable; use a **Result-Based Assumption** — size the stop off your *realized* average gain. Mappings from the book: ~10% average gain at 50% BA → risk ≈ **5%**; a 4% average gain → cut at ≈ **2%**. Treat your average gain as a **"pace car"** you ride behind. Beware the **R-multiple crystal-ball error**: a "2R trader" who *believes* in a 40% return must NOT set a 20% stop when actual results show only a ~10% average gain.

**`[2020]` Win-rate / risk-reward breakeven table** (MTP S26) — the **minimum win rate** needed merely to break even at a given **risk : reward**. Anything where reward < risk (top rows) demands an unrealistic hit rate; favor reward ≥ risk (bottom rows):

| Risk : Reward | Win rate to break even |
|---|---|
| 50 : 1 | 98% |
| 10 : 1 | 91% |
| 5 : 1 | 83% |
| 3 : 1 | 75% |
| 2 : 1 | 67% |
| **1 : 1** | **50%** |
| 1 : 2 | 33% |
| 1 : 3 | 25% |
| 1 : 5 | 17% |
| 1 : 10 | 9% |
| 1 : 50 | 2% |

> Read "Risk : Reward" as *units risked : units sought*. Risking 1 to make 2 (**1 : 2**) needs only a **33%** win rate; risking 2 to make 1 (**2 : 1**) needs **67%**. This is the table behind the "shoot for ≥ 2:1 reward/risk" rule above.

## 2.4 Position Sizing & Progressive Exposure

- **Scale in (pro) — never average down (amateur)** (Ch 13). Worked example for a 5%-of-capital position: **2% on the first buy + 2% on the second + 1% on the third**; place the 10% stop off the **average** cost of the three buys → total account risk ≈ **0.50%**.
- **Pyramid UP into winners; never average DOWN into losers** — "only losers average losers." Doubling at a lower price doubles risk while the loss is unchanged; a falling high-growth stock becomes *less* attractive, not more (Ch 13).
- **Start small, add as trades prove out.** Move from cash into equities incrementally with "pilot buys" / "toe-in-the-water" positions (Ch 13). If you are not profitable at 25% or 50% invested, do **not** advance to 75%/100% or use margin — require a few trades to work first. Add to a position only **after** it shows a profit; even on a pullback, wait for the stock to turn up before buying ("never trust the first price unless the position shows you a profit").
- **Trade your largest when trading your best, smallest when trading your worst.** On an abnormal losing streak, **scale down** share lots — e.g. normal 5,000 → **2,000 → 1,000**, cutting further if trouble continues — and pyramid back up only when the plan works again (Ch 13). Never trade larger to "recoup."
- **Reentry** (Ch 13; reaffirmed 2017 §1): a stopped-out stock that still shows all winner characteristics is not discarded — look for a reentry point; the second setup is often stronger (more weak holders shaken out). It may take **2 or even 3 tries** to catch a big winner; stop-outs cluster when the market is weak or volatile. (Detailed buy mechanics in §3.)

**`[2017]` explicit position-sizing math** (2017 §3, §5, §8):
- **Cap per-trade risk at 1.25%–2.5% of total equity** (the 2020 MTP names this **Risk of Total Equity, "ROTE"** `[2020]`). Never pick a position size arbitrarily — **"back into risk":** *equity-at-risk = position size % × stop %*; adjust **either** the stop **or** the size to land in the band (less experienced traders → toward 1.25%). *Worked pairs on a $100k account: 25% position × 10% stop = $2,500 = 2.5%; 25% × 5% = 1.25%; 12.5% × 10% = 1.25%; 50% × 5% = 2.5%* (2020 ROTE grid: 12.5%×10% = 1.25%; 25%×5% = 1.25%; 50%×2.5% = 1.25%). **`[2025]` the typical band is restated slightly tighter — 1.00–1.50% of equity, with 2.50% the hard maximum** (2017/2020/2021/2022 stated a flat 1.25–2.5%). The 2025 ROTE worked example still solves to **1.25%** three equivalent ways (12.5%×10% = 25%×5% = 50%×2.5% = $1,250), so **1.25% remains the canonical worked midpoint**, now sitting inside the tighter typical band; the 2.50% ceiling is unchanged (§5.1/§5.6).
- **The four controllables** (2017 §2): before the trade you control **(1) what** you buy, **(2) how much** you buy, **(3) when** you buy; after the trade you control only **(4) when** you sell. Sizing and timing are risk levers, not afterthoughts.
- **Position-size doubling on wins** (geometric scale-up): typically start at a **quarter** position; after each win **double** it (¼ → ½ → full). Three consecutive wins let banked profit finance the risk on the larger trades — "trade your largest when trading your best."
- **Add-and-Reduce (risk-constant pyramiding):** add at a new buy point and raise the stop on the *full* position so dollar risk stays fixed. *Example: buy 1,000 @ $16.50, stop $15.50 ($1,000 risk); add 1,000 @ $17.50 and move the stop on all 2,000 to $16.50 → still $1,000 risk; a later add at $18.50 can take principal risk to zero.* Never average DOWN.
- **Exposure-gate ladder (confirms 2013):** start from cash with a pilot buy; if you are **not profitable at 25% or 50% invested, do NOT advance to 75%/100% or use margin** — require a few trades to work first.
- **Gap-risk sizing math:** a stock can gap down ~50% overnight, through any stop (you fill at the next price — "dead air"). An **80% position** in such a stock = a **40%** equity hit; a **25% position** = only **12.5%** — the case for the per-trade caps above.

## 2.5 Portfolio Concentration & Cash

- **Concentrate, don't diversify.** Diversification does not protect you in a bear market (almost all stocks fall together) and over-diversifying smooths results to mediocrity, prevents following each name, and slows exposure cuts (Ch 13).
- **Position-count guideline:** typically **4–8 names** `[2017]` (2013 said **4–6**); large portfolios maybe **10–12**, up to **16–20 for large professional portfolios** `[2017]`; **never more than ~20** positions (≈ **5% each** if equally weighted — too small to drive superperformance). Concentrate **20–25% in each of your top 4–5 picks**; **start a new name small (5–10%)** and add only after it proves itself. `[2017]` (Ch 13; 2017 §8) *`[2025]` re-confirms **4–5 big positions / 8–16 names max**, now noted as "**including partials**."*
- **Single-position ceiling: never more than 50% of the account** `[2017]` — even when running the whole account in 4–5 names, contain risk via sizing + stop. For a 2:1 / 50%-BA profile the math points to a ~25% optimal position (≈4 names); the book references **Optimal F / the Kelly Formula** as the sizing tools. (2017 §8)
- **Two-for-one reallocation** `[2017]`: of 6 names, if 4 work and 2 lag, **sell half of each laggard** and use the combined proceeds to fund **one full new position** — don't dump the laggards entirely, and don't "**di-worsify**." (2017 §8)
- **"Pull the weeds, water the flowers."** `[2017]` Continuously rank holdings; give stellar performers room to run, but **reallocate out of dead money** — a stagnant stock that has NOT hit its stop but isn't advancing is an opportunity cost (distinct from a stop-out exit). (2017 §8)
- **Raise cash / go to the sidelines in bear markets.** The individual's structural edge over funds is the freedom to move fully to cash (funds rarely raise more than 5–10%) — the discipline that sidesteps major declines (Ch 1, Ch 13). See General Market Direction & Timing (§4.12) for the regime read that triggers this.
- **`[2020]` Concentration costs almost nothing (opportunity-cost table)** (MTP S27): across equal-edge allocations the **total return is nearly the same** whether you hold one big position or twelve small ones — **1 × 75% ≈ 75%**, **3 × 20% ≈ 73%**, **6 × 10% ≈ 77%**, **12 × 5% ≈ 79%** — so over-diversifying buys very little return while it *dilutes* your ability to follow each name and slows exposure cuts. Reinforces "concentrate, don't di-worsify." (Illustrative spreadsheet, same per-name edge assumed.)

## 2.6 Breakeven & Profit Protection

- **Move the stop to breakeven once the stock advances ≈ 3× your initial risk** (Ch 13): buy $50 with a 5% / $2.50 risk (stop $47.50); at **$57.50** (= 3 × $2.50) move the stop to **at least $50** (breakeven). Move the stop up at **2× or 3× risk**, especially when that gain exceeds your historical average. If it then stops at breakeven — fine: capital intact, nothing lost.
- **Once a gain is a multiple of your stop, rarely let it turn into a loss** (Ch 13): e.g. stop 7%, gain 20% → move stop to breakeven or trail to lock in the majority.
- **Profit-protection mode after a strong run** (Ch 12): once a stock is up a decent amount, give it *less* room on the downside, not more — the leeway a stock earns has nothing to do with your past gain. Defend at least the breakeven point; never let a good gain become a loss.
- **No "house money."** Yesterday's profit is part of today's principal and is subject to the same risk rules as starting capital — do not hand a winner extra downside room because you have a cushion (Ch 12).
- **Two ways to sell — have a plan for both** (Ch 13): sell **into strength** (cash out while price is still rising and buyers are plentiful) or sell **into weakness** (exit at the first sign of breakdown). Full offensive/defensive sell mechanics live in §3 (Selling) and reference these stops.

**`[2017]` profit-protection mechanics** (2017 §1, §5, §9 — full sell rules in §3.6):
- **Three-priority hierarchy** (as the trade progresses): **(a) limit the loss** with the initial stop → **(b) protect principal** (move the stop toward breakeven once price has advanced a decent amount, generally after the first natural reaction and a recovery to new highs) → **(c) protect profit** (trail to lock gains). "Never let a good-size gain turn into a loss."
- **Breakeven-or-better rule (50-day-MA trailing stop):** start with a fixed initial stop (e.g. 8%); as price advances the **50-day MA** rises — once it reaches your entry (breakeven), **switch the stop to the 50-day MA** and let it trail, exiting on a **close below** it (optionally waiting for the Friday weekly close). Some leaders run a long way before closing below the 50-day line.
- **Back stop (profit-protection stop):** distinct from a lockstep trailing stop — set manually at the **amount of profit you want to protect** (often **at or above your average gain**), let the stock fluctuate above it, and ratchet it up in steps as price rises.
- **Free roll (2R/3R):** once a stock is up **2–3× your stop**, **sell half** and either (1) move the stop to breakeven (lock the booked profit, free-roll the back half), or (2) keep the original stop (finance the back half's risk with the front half's profit). Worst case ≈ breakeven on the remainder.
- **Sell-half to neutralize regret:** when a winner is well above your average gain and starts slipping, selling half (not 75/25) equalizes the regret of both outcomes and protects the psyche; the booked half means even a full give-back on the rest still leaves the trade ≈ breakeven. **Upside ONLY** — sell-half never applies to a losing position: when the stop is hit you exit the *full* position ("no wiggle room"); never sell half a loser and gamble with the rest. (2017 §4)

## 2.7 Global Do's and Don'ts

**Do:**
- Begin every trade by asking "How much can I lose?" — risk-first (Ch 1).
- Set and write down the stop **before** buying; honor it the instant it's hit (Ch 13).
- Keep the average loss ~5–6% `[2017]` (6–7% in 2013) and the absolute loss ≤ 10% (Ch 12, Ch 13).
- Keep win/loss ≥ 2:1 (shoot for 3:1) and let winners eclipse losers (Ch 13).
- Scale in; pyramid up into proven winners; add only after a profit shows (Ch 13).
- Move the stop to breakeven at ~3× risk and switch to profit-protection after a run (Ch 13).
- Concentrate in 4–8 names `[2017]` (4–6 in 2013); raise cash in hostile markets (Ch 1, Ch 13).
- Run an end-of-day review: hold only positions you are bullish on **today** whose original thesis still holds (Ch 12).
- Keep an Initial-Stop / Reentry / Sell-at-Profit / **Disaster** contingency plan and rehearse each position before the open (Ch 13).

**Don't:**
- Don't average down or double up into a loser — "only losers average losers" (Ch 12, Ch 13).
- Don't widen stops — not for volatility, not in a bad market, not to chase a higher expected gain (tighten instead) (Ch 13).
- Don't let any loss exceed your average gain (the trader's cardinal sin) (Ch 13).
- Don't run a negative-expectancy system or one that needs a 70–80% win rate (Ch 13).
- Don't become an "involuntary investor" — flipping from trader to long-term holder only to avoid booking a loss (Ch 12).
- Don't treat any stock as exempt from a stop — even blue chips can fall 70%+ or go to zero (Ch 12).
- Don't treat profits as "house money" — yesterday's gain is today's principal (Ch 12).
- Don't over-diversify (>~20 names) or stay fully invested into a deteriorating market (Ch 13).
- `[2017]` Don't use **mental stops** (§2), don't make an **"audible"** (an on-the-spot, off-plan decision from a news headline/interview — §5), and don't buy a **"bucking bronco"** whose volatility makes a sane stop impossible (§2) (2017 §2, §5).
- `[2017]` Don't buy a stock just because it has fallen / "looks cheap" — the **cheap trap** (§4.9); and don't buy a positive earnings report the market is *rejecting* (down hard on the largest volume) — the differential-disclosure veto (§3.6) (2017 §5).
- `[2017]` Don't **force a trade** — never enter a candidate that is "almost there but not quite"; let the market come to you and develop **"sit-out power"** (hold cash and wait for the full setup). Most premature near-miss entries reverse and stop you out. (2017 §5)

## 2.8 The Trading Plan & Contingency Playbook `[2017]`

2017's organizing principle ("Always Go in with a Plan"): **never enter a trade without a complete, written plan, and never improvise once you are in it.** "Hope is not a plan." Define expectations as a *schedule* — if the stock doesn't deliver "on time," your plan tells you to act rather than sit in dead money. (2017 §1, §2, §5 — the "no audible" and holding-into-earnings rules below are from §5)

**Every trade plan, set BEFORE entry, covers four elements:** (1) the **entry trigger** (precisely what makes you buy); (2) **risk handling** — what you do if it goes against you OR if the reason you bought changes; (3) how you'll **lock in profit**; (4) **position sizing** and when to reallocate.

**Maintain a "what-if" contingency playbook** — pre-worked responses to virtually any development, expanded each time a new scenario appears. It must cover **five situations:**
1. **Where you get out** if the position goes against you (the initial stop — written/automated, sold the instant it's hit).
2. **Reentry criteria** — what the stock must do to be bought again after a stop-out (it can take 2–3 tries; the second setup is often stronger).
3. **Selling into strength** — criteria to nail down a decent gain (§3.6).
4. **Selling into weakness** — when to exit to protect profit (§3.6).
5. **Disaster plan** — catastrophic/system events (lost power or internet, broker-wide outage, an SEC-probe gap-down). Concrete measure: keep a **second brokerage account** so you can short against your longs if your primary broker goes down.

**No audible:** define the entry, the exit-if-wrong, and what you must see to hold, all before entering — then execute without intraday improvisation (very few exceptions, even for pros). **Holding into earnings:** never hold a *large* position into a major report without a cushion — with ~**10% profit** you can usually justify holding through most reports; with no profit or a loss, **sell or cut size** to guard against a **10–15% gap** against you. `[2020]` The MTP illustrates the same hold/trim decision at smaller cushions too — worked chart examples at an **8% profit cushion** and a **6% profit cushion** — i.e. the larger the position and the smaller the cushion, the more you must trim before the report.

## 2.9 Know Your Numbers — Self-Measurement `[2017]`

"You can't manage what you don't measure." Few traders know their own stats; without them you cannot set the stop or the size intelligently. (2017 §4, §10)

- **Log every trade** (buy and sell price) in a spreadsheet — not just the memorable ones — to build: **average win**, **average loss**, **win/loss ratio**, **batting average**, an **adjusted win/loss ratio** (adjusted for batting average), **largest win/loss per month**, and **average holding time** for gains vs losses.
- **Keep records strategy-specific** — never blend day-trade, swing, and long-term results into one average.
- **Manage your personal bell curve:** the goal is to minimize losses (left side) and let profits run (right side). Any deterioration vs *your* historical norm → tighten stops.
- **"Stubborn-trader" diagnostics** (judge over a 6–12-month window): if your **largest gainers are smaller than your largest losers**, you are cutting winners and holding losers; if **average hold-time on gainers is shorter than on losers**, same problem.
- **Set the next trade's risk from these realized averages** (RBA, §2.3): the stop is derived from your *measured* average gain (gains average 15% → stop ≤ 7.5% to keep 2:1), not a hoped target.
- **Result-Based Assumption FORECAST (RBAF):** from your realized stats (avg gain, avg loss, batting average) + a target return + a chosen position size, **solve for the number of trades** required to hit the goal, then sanity-check it against how many setups your style actually generates per year. Larger position size = fewer trades needed; smaller = more (worked example, same goal: **25% position → ~60 trades, 50% → ~30, 12.5% → ~120**). Use it to pick a realistic position size and to judge whether a return goal is even achievable. (2017 §4, Fig 4-4)
- **Run the feedback loop on a fixed schedule** — daily review + monthly tracker + quarterly and annual evaluations (random/occasional review yields unreliable data).
- **Turnover is not taboo:** with an edge and a concentrated book, frequent rotation *accelerates* compounding (chase velocity, then move on); never make a sell decision for tax or commission reasons, and hold cash when nothing qualifies. *Compounding context (examples): two 40% gains = 96%; four 20% = 107%; twelve 10% (≈one/month) = 214%; a string of 15–20% winners can compound to triple-digit annual returns.*

**`[2020]` The five-step feedback loop** (MTP S28 — operationalizes §2.8's "go in with a plan"):
1. **Formulate a very specific plan BEFORE entry** (entry trigger, stop, profit-taking, sizing).
2. **Execute the plan** — don't vacillate.
3. **Avoid the audible** — no on-the-spot, off-plan decisions once in the trade.
4. **Evaluate only AFTER you are out** of the trade (judging mid-trade corrupts the decision).
5. **Formulate a new plan** from what you learned — feed it back into step 1.

**`[2020]` Two pre-decision questions** (MTP S28) — quick self-checks at the moment of action:
- Before a **BUY**: *"If I could only make 10 trades per year, would this be one of them?"* (forces selectivity).
- Before a **SELL**: *"How is this going to look on my spreadsheet?"* (ties the decision to your measured record, §2.9).

## 2.10 The Daily Routine & Operating Workflow `[2020]`

The MTP prescribes a repeatable end-of-day → pre-market workflow so candidates are pre-sorted and entries/stops are pre-set before the bell. (MTP S24)

**Evening screening — sort every candidate into 3 lists:**
1. **Immediately buyable** — potential base breakouts & pullback buys actionable tomorrow.
2. **On-deck** — very close to buyable.
3. **Watch list** — developing; not yet actionable.

**Run 5 separate nightly screens:**
- a. **Momentum / High RS** · b. **IPOs** · c. **New-highs list** · d. **Utility screens** · e. **Earnings surprises & big movers.**
(These feed the SEPA funnel, §3.1 / §1; the named Trend-Template screeners in §4.2 are the technical first pass.)

**Pre-market checklist:**
- Check **news, earnings, upgrades & downgrades.**
- Set **pivot alerts** on buyable names — *and a separate **pre-alert*** below the pivot so you are at the screen as price approaches the trigger (§4.3).
- Set **stop alerts** on every current holding (§2.2).
- Make **notations** and set stops for actionable ideas.
- **Mental rehearsal** of each position before the open (the §2.8 contingency plan, run live).

**Daily position re-evaluation triad** — for every open position, every day, ask:
1. **Would I buy this stock right now?** (if not, that is information).
2. **If not, why am I still holding it?** (guards against dead money / the involuntary investor — §2.5, §2.7).
3. **Where am I a seller?** (pre-define the exit, judged on a ≥ 2:1 / 3:1 reward-to-remaining-risk basis). See the §3.6.D Daily Sell Decision List for the full version.

## 2.11 Post-Analysis: Grading the Trade `[2020]`

After you are **out** of a trade, grade it — separating *process* quality from *outcome* so you improve the repeatable part. (MTP S28; extends §2.9 self-measurement.)

**The Report Card** — grade entry and exit quality and track the distribution; the MTP's target/observed distributions are:

| Phase | Buckets (target distribution) |
|---|---|
| **Entry point** | Bought perfect **60%** · bought too late **20%** · faulty set-up **15%** · bought too soon **5%** |
| **Exit at a profit** | Sold too soon **70%** · sold too late **15%** · sold perfect **15%** |
| **Exit at a loss** | Cut perfectly **65%** · cut too late **20%** · cut too soon **15%** |

> The biggest leak the card exposes is **selling winners too soon** (70%) — the discipline §2.6 / §3.6.B is built to counter. Annotate the chart afterward (Good/Bad Buy, Good/Bad Sell, Early/Late Sell) to see the geometry of your own errors.

**The 8 Keys to Superperformance** (MTP S28 summary framework):
- **4 Keys to Big Returns:** (1) **Timing**, (2) **Concentration**, (3) **Turnover**, (4) **Managing the risk/reward relationship**.
- **4 Keys to Low Drawdowns:** (1) **Sell into strength**, (2) **Trade directionally** (only with the trend), (3) **Progressive exposure**, (4) **Protect breakeven**.

---
# 3. Strategies

SEPA is one integrated system, not a menu of independent strategies. **§3.1** is the master playbook; **§3.2–§3.5** are the specific entry SETUPS used inside it — VCP is the primary setup, the Cheat and Power Play are variants for earlier or higher-velocity entries, and the Primary Base targets young post-IPO leaders; **§3.6** is the shared exit discipline. Every section references the shared definitions in §4 and the risk rules in §2 rather than restating them. All entry rules are **long** (the method is long-only).

---
## 3.1 SEPA Master Playbook (Specific Entry Point Analysis)

**Key:** `sepa` · **Introduced 2013 (Ch 3); single source.**

SEPA (Specific Entry Point Analysis) is Minervini's master methodology. It combines corporate **fundamentals** with a stock's **technical behavior** to pinpoint the precise lowest-risk / highest-reward entry. The other §3 sections (VCP §3.2, The Cheat §3.3, Power Play §3.4, Primary Base & IPO §3.5, Selling §3.6) are specific entry SETUPS and exit mechanics that operate *inside* this playbook — SEPA is the end-to-end funnel that decides *which* stock, in *what* condition, *when* to buy, and *how* to manage and exit it.

### Purpose & market context
- **Objective:** take all pertinent information and pinpoint the precise spot to enter a high-probability trade in terms of risk vs. reward. The stated goal is to "purchase a stock and be at a profit immediately" — buy only at the point of lowest risk / highest potential reward. (Ch 3)
- **Market timing is foundational:** More than **90%** of superperformance stocks began their price surges as the general market came OUT of a correction or bear market; very few had superperformance phases DURING a bear market. Trade leaders emerging off the market low — see General Market Direction & Timing (§4.12). (Ch 3)
- **Probability convergence (the trade trigger):** execute ONLY at the point where company fundamentals, stock price, volume activity, AND overall market conditions align — "four cars arriving at the same time at a four-way intersection." SEPA stacks supporting probabilities so the collective value is greater than the sum of the parts. (Ch 3)
- SEPA is **long-only in practice** (buy confirmed Stage-2 uptrends). Shorting is reserved for full-fledged Stage-4 downtrends and is not part of this playbook (see §4.1).

### Instruments & timeframe
- **Instruments:** US individual equities (stocks). Favor relatively *young* companies (a superperformance phase generally occurs while a stock is young — e.g., the first 10 years after IPO, illustrative). Favor **small- or mid-cap** names with a small float (all else equal, a small float can appreciate more on less demand). For smaller companies, confirm they are already profitable and the business model is proven scalable/duplicable. (Ch 3)
- **Timeframe:** momentum swing-to-position trading — holding periods of days to many months, capturing the Stage-2 advance.

### The five key elements of SEPA
1. **TREND** — virtually every superperformance phase occurs while the stock is in a definite price UPTREND, identifiable EARLY in the advance. Trend is qualified by the Trend Template (§4.2). (Ch 3)
2. **FUNDAMENTALS** — most phases are driven by improving EARNINGS, REVENUE, and MARGINS, usually measurable BEFORE the phase begins. See Leadership Profile — Fundamentals (§4.7) and Earnings Quality (§4.8). (Ch 3)
3. **CATALYST** — every huge winner has a catalyst that ignites institutional interest (new hot-selling product that is a meaningful portion of sales; FDA approval; newly awarded contract; new CEO). See Industry Groups, Catalysts & Categories (§4.11). (Ch 3)
4. **ENTRY POINTS** — most superperformers offer at least one low-risk entry; timing is critical (time it right and you can be at a profit immediately; time it wrong and you are stopped out needlessly). Entry is taken at the pivot (§4.3) off a constructive base/VCP (§4.6 / §3.2). (Ch 3)
5. **EXIT POINTS** — not all candidates work even with a correct buy point, so stop-loss points are mandatory; conversely the END of the superperformance phase must be identified to keep the gains. See risk rules (§2) and Selling (§3.6). (Ch 3)

### The SEPA screening funnel (4 stages — the candidate-narrowing process)
- **Funnel Stage 1 — Trend qualification.** A stock must first meet the **Trend Template (§4.2)** to be a potential SEPA candidate. This is a hard, pass/fail technical screen applied FIRST, regardless of how good the fundamentals look. (Ch 3, Ch 5)
- **Funnel Stage 2 — Fundamental + RS + volatility screen.** Trend-Template survivors are filtered on EARNINGS, SALES and MARGIN GROWTH (§4.8), RELATIVE STRENGTH (§4.10), and PRICE VOLATILITY. **≈95% of all stocks that qualify under the Trend Template FAIL to pass this screen.** (Ch 3) — KEY funnel stat.
- **Funnel Stage 3 — Leadership Profile match.** Remaining stocks are scrutinized for similarity to the Leadership Profile (the fundamental + technical fingerprint of historical superperformers — §4.7); this removes most of the rest. (Ch 3)
- **Funnel Stage 4 — Manual "relative prioritizing" rank.** The narrowed list is reviewed individually and scored on the 12 ranking items below. (Ch 3)
- NOTE: these four FUNNEL stages (a screening pipeline) are distinct from the four price-cycle STAGES in Stage Analysis (§4.1). Reference each by its full name to avoid confusion.

**Stage-4 "relative prioritizing" — the 12 scored items** (rank candidates by fit):
1. Reported earnings and sales (§4.8)
2. Earnings and sales **surprise** history (§4.8)
3. EPS growth **and acceleration** (§4.8)
4. Revenue growth **and acceleration** (§4.8)
5. Company-issued guidance (§4.8)
6. Revisions of analysts' earnings estimates (§4.8)
7. Profit margins (§4.8)
8. Industry and market position (§4.11)
9. Potential catalysts — new products/services or industry/company developments (§4.11)
10. Relative strength vs. the SAME SECTOR (§4.10)
11. Price and trading VOLUME analysis (§4.5)
12. Liquidity risk (§2)

**What the rank is trying to identify (3 forward-looking targets):** (1) future earnings & sales surprises + positive estimate revisions; (2) institutional VOLUME support (significant buying demand); (3) rapid price appreciation from a SUPPLY/DEMAND IMBALANCE. (Ch 3)

### Entry rules (the ordered playbook a trader runs)
1. **Confirm Stage 2.** The stock must pass all 8 criteria of the **Trend Template (§4.2)** — i.e., be in a confirmed Stage-2 uptrend (§4.1). Hard gate: never go long a stock trading below its declining 200-day MA, no matter how good the fundamentals. Require the Stage-2 confirmation of price ≥ **25–30% above the 52-week low** before concluding the advance is under way. (Ch 5)
2. **Demand leadership-profile fundamentals + earnings quality.** Require an improving fundamental story: meaningful positive earnings surprise, upward estimate revisions, and accelerating EPS/sales. Apply the Leadership Profile — Fundamentals (§4.7), Earnings Quality (§4.8), and P/E & Valuation Context (§4.9) screens. Minimum current-quarter gate (§4.8): ≥ **20–25% YoY** EPS growth in the most recent one to three quarters (raise the bar to **40–100%+** in a bull market). Fundamentals confirm the Stage-2 price structure — they are not a standalone trigger.
3. **Confirm convergence: catalyst + leading group + market + RS.** Verify a genuine **catalyst (§4.11)**, that the stock sits in a leading **industry group (§4.11)**, that the **General Market Direction (§4.12)** is favorable (preferably coming out of a correction), and that **Relative Strength (§4.10)** is strong (Trend Template requires RS rank ≥ **70**, prefer 80s–90s). This is the four-way-intersection convergence.
4. **Wait for a constructive base, then buy at the pivot on volume.** Inside the Stage-2 uptrend, wait for a proper consolidation per Base & Correction Geometry (§4.6) — typically a **VCP (§3.2)** that contracts volatility left-to-right with volume drying up at the tightest right-side pullback. BUY as price breaks above the **Pivot Point / line of least resistance (§4.3)** on a noticeable increase in **volume (§4.5)**. Better selections correct the least; the pivot is where reward most outweighs risk.
5. **Scale in, do not commit all at once.** Begin with a pilot buy / smaller-than-normal position; add larger size only after the position shows a profit. Pyramid up when trading well; never average down (§2). Move from cash into equities incrementally — pace yourself coming out of a bear market or correction.
6. **Buy in order of breakout / strength** `[2017]`. From a watch list of 5–6 (up to ~10) candidates, **buy the names that emerge first** from a proper buy point — let the market, not your favorites, direct capital (first-mover advantage). Target **velocity trades** (~20/30/50% in weeks-to-months) to compound rapidly. (2017 §1, §7, §10)

### Post-entry confirmation — "is the trade working?" `[2017]`
After buying the pivot, a real winner should confirm itself quickly (these are *health checks*, not new entries — manage with the §2 stop). (2017 §1)
- **Follow-through count:** in the first week or two, want **more up days than down** — e.g. **3 of 4** or **6 of 8** up days, ideally **7–8 up days in a row**; and more up *weeks* than down weeks. Stocks under institutional accumulation almost always show this.
- **Tennis-ball action** (2013-origin, Ch 10; recovery window quantified 2017): a healthy pullback is brief (**2–5 days, sometimes 1–2 weeks**), on **contracting volume**, then snaps back to new highs on **expanding volume** (a tennis ball, not an egg).
- **Good closes & "hard to buy":** price closes in the **upper half of the daily range** more often than the lower half; you rarely get a better fill than the breakout (a tight, ultra-low-volume exception aside).
- **Squat tolerance:** a breakout that falls back into range and **closes off the day's high** (a *squat*) is not an automatic sell — in a bull market allow a **reversal-recovery window (1–2 days, up to ~10)** above the protective stop.
- **Five-point winner checklist:** (1) follow-through after the breakout; (2) more up days *and* up weeks than down; (3) tennis-ball resilience; (4) volume bigger on up days/weeks than down; (5) more good closes than bad. (Inverse violations → see §3.6.)

### Exit rules (reference §2 + §3.6)
- **Initial stop is mandatory and set in advance.** Per Risk Management (§2): stop ≤ **one-half of your average gain**, with an **absolute cap of 10%** off the purchase price — never wider. Honor the stop the instant it is hit. (Ch 13)
- **Protect profit.** Once a gain reaches a multiple of the stop (≈ 3× risk), move the stop to at least breakeven / trail it; never let a meaningful gain turn into a loss. (Ch 13)
- **Sell per the Selling playbook (§3.6):** sell INTO STRENGTH when the stock runs up rapidly and buyers are plentiful, or INTO WEAKNESS at the first breakdown — have a plan for both.
- **End-of-phase / Stage-3 exit:** identify the end of the superperformance phase and sell as the stock transitions toward Stage 3 (§4.1) — distribution signals: a major price break on the largest volume since the advance began, sharply rising volatility, the 200-day MA flattening then rolling over. Take profits more quickly on a large-cap that advances rapidly than on a smaller, faster-growing name. (Ch 5, Ch 3)
- **Earnings-reaction failure:** on an upside surprise, if the stock pops then reverses and is **down 15%** and cannot rally, treat it as a major problem (§4.8).

### Strategy-specific filters (reference §4 by name)
- **Trend Template (§4.2)** — all 8 criteria; the non-negotiable Funnel-Stage-1 gate.
- **Stage Analysis (§4.1)** — buy only Stage 2; avoid Stage 1/3/4.
- **Earnings Quality (§4.8)** — positive earnings surprise, upward estimate revisions (the 5% / 30-day rules), accelerating EPS + sales (Code 33).
- **Relative Strength & Market Leadership (§4.10)** — RS rank ≥ 70 (prefer 80s–90s); outperform the sector.
- **Industry Groups, Catalysts & Categories (§4.11)** — a real catalyst in a leading group.
- **General Market Direction & Timing (§4.12)** — favor leaders coming out of a correction/bear market.
- **Volume Analysis (§4.5)** — accumulation signature; breakout on increased volume.
- **Base & Correction Geometry (§4.6)** — proper base; least-correction preference; early base count (§4.6) preferred over late.
- **P/E & Valuation Context (§4.9)** — winners can carry/expand high P/Es; valuation is context, not a disqualifier.

### Edge cases
- **Keep screens simple.** Do not over-stuff one screen — a stock that misses one of 12 criteria by a hair will never appear. Run SEPARATE screens on smaller lists of compatible criteria (e.g., one for relative price strength + trend, a separate one for earnings + sales); prioritize names that recur across the isolated screens. Computers weed out noise, but consistent results still require manual analysis. (Ch 3)
- **Late-stage bases.** A top generally forms after 3–5 bases in the Stage-2 advance; bases 1–2 (off a market correction) are the best boarding points, and base-failure risk rises by the 4th–5th base (§4.6).
- **A valid setup can still fail.** Even a textbook VCP can fail (e.g., a stock that stops acting as expected) — this is exactly why the predetermined stop is non-negotiable.
- **Post-earnings drift.** If you miss the initial reaction, a significant positive surprise can keep drifting for months — it may not be too late to buy after a strong report (§4.8).
- **Large-cap vs. small-cap profit-taking.** Be inclined to take profits more quickly on a fast-advancing large-cap than on a smaller, faster-growing company that may double or triple over months.
- **Bottom-fishing prohibited.** Do not buy near the lows in Stage 1 or Stage 4 even with appealing fundamentals — those stages lack upside momentum by definition (§4.1).

### `[2020]` MTP refinements

- **The "5 Key Areas" re-framing** of the SEPA selection inputs (the 2013 "5 Elements" re-organized): **(1) Categories & Catalysts, (2) Study of Price & Volume, (3) Company Fundamentals, (4) Entry Points, (5) Exit Points.** Same content, regrouped — Trend folds into Price & Volume; Catalyst joins Categories.
- **SEPA's 3 objectives & 3-part process:** objectives = **Take Minimal Risk · Capture Large Gains · Maximize Compounding**, executed through **Selection Process → Trade Execution → Position Management** (the §2.4/§2.6 sizing-and-management layer is the third leg, not an afterthought).
- **"Bullish Breakout Criteria"** (a named post-breakout confirmation set, same metrics as the MVP indicator §4.10): **(1) +20% or more, (2) up 12 of 15 days, (3) elevated volume during the advance** — use it alongside the five-point winner checklist above to confirm a breakout is working.
- **Operational layer:** run the SEPA funnel through the **Daily Routine (§2.10)** (evening 3-list screening + named Trend-Template screeners §4.2) and grade results with **Post-Analysis (§2.11)**.

UNCERTAIN — the 300%/2-yr and 100%/calendar-year "superperformance" thresholds are the *source-study* definitions (Love; Reinganum) that inspired SEPA, NOT stated as Minervini's own operative screening thresholds. Treat as historical/definitional.

UNCERTAIN — "first 10 years after IPO" (youthfulness) and "≈ last couple of quarters of beats" are phrased as illustrative guides, not hard cutoffs.
---
## 3.2 Volatility Contraction Pattern (VCP) + Pivot Buy

**Key:** `vcp` — THE primary entry setup of the SEPA method. Everything in §3.1 (the SEPA Master Playbook) ultimately funnels into a VCP pivot buy.

### Purpose / market context
- A VCP is a **constructive consolidation under accumulation**: a stock at rest, digesting a prior advance while institutions quietly absorb supply. (Ch 10)
- Volatility contracts **from left to right** — greater volatility on the left side of the base, lesser on the right — as **overhead supply** is absorbed and the pattern tightens toward a precise **Pivot Point / Line of Least Resistance (§4.3)**. This is the single most-relied-on distinction Minervini looks for. (Ch 10)
- Main role of the VCP: establish a **precise, low-risk entry at the line of least resistance** — buy "at the point where reward outweighs risk." (Ch 10)
- Mechanism (why volatility/volume contract): trapped buyers near the old high sell into rallies to break even, and bottom-fishers take quick gains; as that overhead supply is absorbed, trading activity dries up and price tightens. (Ch 10; Fig 10.8)

### Setup & preconditions
1. **Confirmed Stage 2 uptrend** — the candidate must already pass the **Trend Template (§4.2)** in full (all 8 criteria) and be in **Stage 2 (§4.1)**. A "great base" inside a long-term downtrend is disqualified — never buy below a declining 200-day MA. (Ch 5; Ch 10)
2. **Prior uptrend present** — the VCP is the continuation of an *existing* advance, not a bottom. Limit selections to stocks showing evidence of **institutional buying / accumulation (§4.5)**. (Ch 10)
3. **Proper base, not just any base** — a constructive consolidation (rest → profit-taking → equilibrium → continuation higher), with the **symmetry and time** to digest supply (see §4.6). (Ch 10)
4. **Base duration:** a proper basing period lasts **3 weeks to as long as 65 weeks**, depending on correction depth. (3-to-60-weeks is the general consolidation scan window; the VCP-specific proper-base bound is 3–65 weeks.) (Ch 10)
5. **Correction depth (Base & Correction Geometry §4.6):**
   - Preferred base depth: **10% to 35%** (concentrate on the stocks that correct the LEAST); `[2017]` some constructive bases correct as much as **~40%**.
   - **Avoid corrections of 60% or more** (rarely bought — deep sell-offs signal fundamental trouble and carry heavy overhead supply).
   - **Avoid a stock that corrects more than 2.5×–3× the general market's decline** `[2017]` (2× to 3× in 2013 — see §5.1) under most conditions.
   - Exception (context only): in a major bear-market correction some names can decline as much as **50%** and still work out. (Ch 10)
6. **Reject time-compressed / V-shaped bases** — if the stock runs up the right side too fast (no proper right-side development), wait for a more symmetric base before acting (§4.6). (Ch 10)

### Entry rules (numbered)
1. **Count the contractions ("T"s):** look for a succession of **2 to 6 contractions**, **typically 2 to 4** (sometimes 5 or 6). Each contraction is a high-to-low pullback within the base, written 1T…6T. (Ch 10)
2. **Each successive contraction ≈ half the prior** (plus or minus a reasonable amount). This halving-ish progression is the only firm depth rule. (Ch 10)
   - *(The "25% → 15% → 8%" and "25% → 10% → 5%" sequences in the text are illustrative of the shrinking progression, NOT fixed tiers — see Versioning.)*
3. **Volatility AND volume both contract left-to-right.** Volume must **dry up on the right side**, with the lowest volume at the tightest, final contraction (§4.5). (Ch 10)
4. **The final, tightest contraction forms the Pivot Point / Line of Least Resistance (§4.3)** — its high is the buy trigger.
5. **Final-contraction volume gate (§4.5):** volume on the final contraction should be **below the 50-day average**, with **one or two days when volume is extremely low** (often near the lowest in the entire base). This is the only EXACT pre-breakout volume threshold. (Ch 10)
6. **BUY on the breakout above the pivot on EXPANDING volume** — low volume *into* the pivot, then a noticeable increase *on the breakout itself*. Up-day volume must be much bigger than down-day volume. (Ch 10)
7. **Do not chase:** buy **as close to the pivot as possible, no more than a few percentage points** above it. (Ch 10)
8. **Always wait for the actual pivot** — never enter early to "save a few pennies." When the pivot is tight there is no material advantage to early entry, only added risk. The pivot is the final determinant of when to risk capital. (Ch 10)

### The Technical Footprint (§4.4)
- Record each base as the shorthand `[weeks]W [deepest%/tightest%] [count]T`. (Ch 10; Fig 10.6)
- **Illustrative example footprint:** `40W 31/3 4T` = 40-week base, deepest correction 31%, tightest final contraction 3%, four contractions (depths 31% → 17% → 8% → 3%). *(Meridian Bioscience / VIVO, Jan 2007 — example only, not a rule.)* (Ch 10; Fig 10.6)
- **A second illustrative footprint:** `8W 22/2 3T` = 8-week base; successively tighter pullbacks 22% → 8% → 2%; the 2% final fluctuation = the pivot. *(New Oriental Education / EDU, April 2007 — example only.)* (Ch 10; Fig 10.32)

### Shakeouts / undercuts (§4.6)
- **Shakeouts often precede the pivot.** A shakeout = price undercuts a prior support low, triggering weak holders' stops, then **reverses and rallies** — eliminating weak hands so the move can be sustained. (Ch 10)
- Ideally see a shakeout **one, two, or three times**, depending on base size, before entering. Shakeouts occur at (a) the **lows** of the base, (b) the **right side**, and (c) the **handle or pivot** area. (Ch 10)
- Prefer bases that have ALREADY exhibited and digested their shakeouts before you enter — obvious support is a trap full of everyone's stops ("what is too obvious seldom works").
- **CAUTION:** do NOT buy a declining stock just because you *think* it is a shakeout — "we are not forecasters; we are interpreters." Wait for price to actually reverse back up. (Ch 10)
- A good demand confirmation after a shakeout: a **price spike on overwhelming, above-average volume** (often a **gap** on a fundamental catalyst — earnings beat, industry news, upgrade). Avoid stocks that move up only on light/below-average volume. (Ch 10)

### Exit / stop (reference §2 risk module)
- **Initial stop:** just below the pivot, capped at the method's hard maximum loss (**≤10%** — see §2). The pivot entry is precisely what defines the high reward-to-risk point. (Ch 10; §2)
- **Post-breakout health gate:** after a successful breakout, price **should hold its 20-day moving average and in most cases should not close below it**. The pattern should **not widen** into wild back-and-forth swings — up is good, wild swings are not. (Ch 10)
- A close below the 20-day MA lowers the probability of success → judgment call; but as long as price holds above the protective stop, give it room. (Ch 10)
- A textbook VCP can still **fail** (illustrated by USG Corp., 2006, Fig 10.7) — a valid setup is never a guarantee, which is exactly why the stop is mandatory. If a stock "doesn't act as expected," that is a major red flag. (Ch 10; Fig 10.7)
- Full target/trailing/profit-protection logic lives in §3.6 Selling and §2.

### Filters
- Must pass the **Trend Template (§4.2)** and be in confirmed **Stage 2 (§4.1)** — non-negotiable.
- **Relative Strength & Market Leadership (§4.10):** RS rank ≥ 70 (prefer 80s–90s), under accumulation.
- **General Market Direction (§4.12):** a faulty setup or going long in a bear market makes pivots fail — align with the market trend.
- **Reject if price/volume do NOT quiet down on the right side** — supply is still coming to market; too risky, skip it. (Ch 10)
- **Prefer buying into a new high / new-high territory** (no overhead supply); avoid stocks near 52-week lows. A pivot may form at new highs OR below the structure high (e.g. cup-with-handle / 3-C cheat). (Ch 10)
- **Non-VCP base variations** that lack real volatility contraction: the square **Darvas box** and the **flat base** (4–7 weeks, ~10–15% range). For a flat base with no pivot other than the base high, buy above the base high **only if it corrected no more than 10–15%** (§4.6). (Ch 10)

### Edge cases
- **Early entry / squat:** a **squat** = the stock breaks out, then falls back into its range and closes off the day's high. Do NOT automatically bail — wait at least a day or two for a **reversal recovery** (especially in a bull market); recovery can take up to ten days or longer. If price tightens and volume subsides after the squat, the setup may be improving (you simply entered a bit early). Sell only if the reversal triggers the stop. (Ch 10)
- **Reversal recovery:** hold the original stop through the squat; if the stock recovers back into/above the breakout, the trade is intact (series of squat-then-recover moves are normal up the right side). (Ch 10)
- **Early-day reversal:** the stock rallies in the morning then returns to the breakout before noon–1:00 p.m. **Give it until the end of the day** unless the reversal is severe enough to trigger the protective stop; stick to the original game plan even if price briefly undercuts your purchase price. (Ch 10)
- **Intraday volume extrapolation:** before pulling the trigger, project full-day volume from partial-session volume to confirm above-average demand is building as price approaches the pivot. (Ch 10)
- **Left-side news spike:** a price spike on the **LEFT** side of a base (often news-driven) leaves the stock **extended and prone to a pullback** (more so if the general market begins correcting) — do not chase it; let it base and wait for the right-side contraction/pivot. (Ch 10, p.222)
- **Primary base / IPO** edge handling is covered in §3.5.

### `[2017]` refinements
- **VCP context:** a VCP forms *within* an established uptrend (a continuation pattern), typically **after the stock has already advanced 30 / 40 / 50% or more**. A stock under accumulation will almost always print VCP characteristics. (2017 §6–7)
- **The "wet-towel" mechanic:** each contraction tightens (corrects less) on successively **lower volume** as supply is wrung out — once supply is gone the stock moves one way easily. (2017 §6)
- **"Be the last weak holder"** (2013-origin, Ch 10; reinforced 2017 §6): because you are a stop-loss user (a weak holder), wait until the transfer from weak to strong hands is nearly complete — confirmed by **volume contraction + quieter price** on the right side — before buying. (The "reject if the right side doesn't quiet down" rule is the 2013 rule already stated above under Filters.)
- **"Extended" = do not chase:** a stock **more than ~10% above its most recent consolidation** (David Ryan's definition) is extended — wait for a pullback or a new base. This sharpens the 2013 "buy ≤ a few % above the pivot." (2017 §1; see §4.3)
- **Pull-back-to-breakout is normal:** a stock breaks out and dips back to (or slightly below) the pivot **~40–50% of the time** — acceptable as long as it recovers within a few days to 1–2 weeks (see tennis-ball action, §3.1). (2017 §1)
- **Double bottom** `[2017]`: a "W" base (preferably undercutting the first low) is a valid non-VCP base variation that still requires a right-side pause/pivot — defined in §4.6.

### Versioning
- **Introduced 2013; refined 2017** (the 2017 refinements above are tagged `[2017]`; see §5).
- **UNCERTAIN — illustrative vs. hard tiers:** the contraction sequences "25% → 15% → 8%" and "25% → 10% → 5%" are framed in the text as examples ("for example," "say"), so they are treated as illustrative. The only firm depth rule is "each contraction ≈ half the prior (± a reasonable amount)" (2017's "wet-towel" mechanic confirms the shrink-on-lower-volume progression without fixing tiers).
- **UNCERTAIN — relative-correction ratio:** the "23% correction = an acceptable 2.3× the market" figure (Cirrus Logic example) is presented as *an* acceptable multiple; the text does not fix a general maximum ratio beyond the stated "avoid >2–3× the market." Treated as the 2–3× rule, with 2.3× as an in-bounds example.
---
## 3.3 The Cheat (3-C Low-Risk Entry)

**Key:** `cheat_3c` · **Class:** US-equity momentum, long-only, swing-to-position (days–months). Earlier, lower-risk entry inside a *developing* base — Darvas/Livermore lineage. (Ch 10; "The 3C Pattern," Figs 10.50–10.54.)

### Purpose
- The **cup completion cheat ("3C")** is a *continuation* pattern and the **earliest point at which you should attempt to buy a stock** — "You don't want to be involved any earlier than this point." (Ch 10.)
- It buys the **cheat area** (a pause inside the base) *before* the textbook upper-base pivot, lowering cost basis and risk while timing the stock's upturn back in sync with the primary Stage-2 trend (§4.1).
- "3C" = the cheat **completes the cup** portion of the base; it gives an actionable pivot to enter as the intermediate downtrend turns back up.

### Qualification (prerequisites — these mirror a cup-with-handle because the cheat IS the cup being completed)
1. **Prior advance:** the stock has already moved up **25% to 100%** — and **in some cases 200% or 300%** — during the **previous 3 to 36 months** of trading.
2. **Long-term trend:** must be trading **above an upwardly-trending 200-day moving average** (provided ≥200 trading days exist) — i.e., must satisfy the Trend Template (§4.2) and be a confirmed Stage-2 uptrend (§4.1).
3. **Base duration:** can form in as few as **3 weeks** to as many as **45 weeks**; **MOST are 7 to 25 weeks**. (Cf. the general consolidation scan window in §4.6.)
4. **Correction depth (peak→low):** **15% or 20%** up to **35% or 40%**, and **as much as 50%** in some cases (market-condition dependent). **Corrections in excess of 60% are too deep → disqualify** (extremely prone to failure).
5. **Market timing (§4.12):** it is *common* for a cheat to develop during a general-market correction; the strongest names rally off the cheat just as — or close to when — the averages turn up from the correction.
6. **Footprint signature (§4.4 / §4.5):** the cheat area must show the same VCP signature as a handle — a **contraction in volume AND tightness in the price range** at the pause.

### "Making the Turn" — the A→B→C→D sequence (operational core)
This is the shape that produces the low / mid / high cheat. Map it onto Stage Analysis (§4.1) and Base & Correction Geometry (§4.6).

> `[2025]` The 2025 workshop re-frames this exact A→B→C→D turn as the **"Bottom Fishing Pivot."** Same schematic: a descending trendline drawn across the lower-high peaks of an intermediate decline (the decline drawn in **thirds**), with the bounces contracting as price flattens, then a small tight base at the bottom that breaks **up** through the down-trendline — **A** downtrend, **B** mark-up that breaks the downtrend, **C** pullback within the base, **D** breakout above the pullback high. A new *name* for the documented setup, **not** a new strategy (cf. 2021's "Stock Maturation Cycle" re-title). (§5.6; glossary §1.B.6)

| Step | What happens | Action |
|---|---|---|
| **A. Downtrend** | An intermediate price correction *inside* a longer-term Stage-2 uptrend; can run weeks or months. Large price spikes on increased volume along the down-leg are normal. | Watch only. |
| **B. Uptrend (rally)** | Price breaks the downtrend, **usually recouping about one-third to one-half of the prior decline**, then stalls on overhead supply (§4.6) into a pause/pullback. Price/volume have **not** yet confirmed the bottom. | **Do NOT buy — too early.** |
| **C. Pause (the cheat)** | Stock pauses days/weeks and forms a **plateau (the cheat) contained within 5%–10% high point to low point.** *Optimum:* the cheat drifts down **below a prior low** to create a **shakeout** (flushes weak holders, like a handle shakeout). **Readiness signal:** volume dries up dramatically with tightness in price. | Prepare order at the pause high. |
| **D. Breakout ("made the turn")** | Price rallies **above the high of the plateau (cheat)** → the stock has "made the turn," probably made its low, and resumes the longer-term Stage-2 primary trend. | **Buy** as the pause high is taken out (§4.3). |

### Cheat location → number of buy points (low / mid / high cheat)
- **High cheat = standard:** a handle normally forms in the **upper third** of the cup — this upper-third pause is effectively the handle and the standard cheat pivot.
- **Mid / low cheat:** if the pause forms in the **middle third of the cup, or just below the halfway point**, you can get **more than one buy point** — e.g., a mid-cup cheat *plus* an upper handle, each a distinct pivot for scaling.
  - UNCERTAIN — text says "more than one buy point"; whether that means strictly two vs. potentially more is not pinned down (Ch 10, p. 244).
- **Low cheat (rare):** the pause forms near the **bottom of the cup** and the stock can run directly off it with **no handle**. (Illustrative: GOOG 2004 formed a rare low cheat — example only, not a rule.)
  - `[2017]` 2017 frames the three locations explicitly as **low cheat (lower third) · classic cheat (middle third) · handle (upper third)**, and treats the **low cheat as a deliberate, riskier/higher-reward scale-in START** — buy a partial at the low cheat, then add at higher pivots (handle) to lower average cost; preferred for larger-cap names where the lower entry materially improves reward-to-risk. The 3-C remains the **earliest point you should attempt to buy** any stock. (2017 §7)

### Entry trigger
- **Primary rule:** "Don't buy just because a trendline is breached; wait for the stock to turn. **The spot to begin buying is just as the high of the pause is taken out** — this could occur at the **cheat area OR the handle**."
- Buy as price clears the **high of the pause** on a **noticeable increase in volume** (§4.5). The cheat/handle pivot can sit **below the structure's overall high** (it need not be a new-high pivot). See The Pivot Point (§4.3).
- **Do NOT chase / do NOT pre-empt:** wait for the actual breach of the pause high; entering before the turn only adds risk during the most volatile (bottoming) phase. Buy/sizing chase limits and the pivot-volume dry-up are defined in §4.3 / §4.5.

### Why wait for the turn (don't buy a bare trendline break)
- **The most dangerous time to trade is when a stock is trying to bottom** — a violently whipsawing period; repeated stop-outs in a whipsaw inflict repeated small losses that add up.
- A broken trendline can be temporary; the stock can make a momentary countertrend move and resume the prevailing trend, sometimes with *wider* volatility.
- Odds increase dramatically if you wait for the stock to **rally → break the downtrend → pause → follow through** before buying. This also gives the base time to bottom and complete an orderly correction. Waiting **reduces but does not eliminate** failure odds.

### Livermore confirmation (the conceptual basis of "the turn")
- Livermore's change-in-trend pivot logic: wait for the **trend to be broken AND two reactionary pullbacks** to take place; then **enter as the stock trades above the SECOND reaction high.**
- He only took positions in the direction of the trade and **never bought the low** — he bought on evidence the new trend was continuing (the high taken out *after* a natural reaction). This is the same "confirmation penetration" that defines the cheat's D-step. (Ch 10; Fig 10.54 schematic.)

### Exit / stop & failure handling
- **Stops, position sizing, and the protective-stop discipline are defined centrally in §2 (Global Risk Management Framework) and the Selling strategy (§3.6)** — apply them here unchanged.
- **The cheat fails MORE OFTEN than the standard (upper-base) pivot** because it is an earlier, less-confirmed entry → use a **tight stop** and keep the trade close to the pause low / shakeout low.
- **Scale-in:** the cheat lets you take a **partial position** first, then add at the handle/breakout — using cheat + handle as multiple scale-in pivots to lower average cost (§4.6). Do not deploy the full intended size at the cheat alone.
- **Failure (pivot) reset:** a stop-out/shakeout *within* the cheat or handle can reset a new entry point within days/weeks (vs. a base failure that needs a whole new base). If the fundamentals (§4.7/§4.8) remain intact, keep the name on the watch list for a reset rather than discarding it.

### Bearish / short note
- Long-only method. Shorting is reserved for confirmed **Stage-4 downtrends** (§4.1) and is *not* built as a bearish mirror of the cheat; no short variant of the 3-C is defined.

### Versioning
- **Introduced 2013; refined 2017** (the explicit low/mid/upper-third framework + low-cheat scale-in note above are tagged `[2017]`; see Strategy Evolution §5).
---
## 3.4 Power Play (High, Tight Flag)

**Key:** `power_play` · **Also called:** high tight flag, "velocity pattern" (Ch 10, p. 253).

The highest-velocity continuation setup in the SEPA toolkit. It captures a stock that has already exploded, paused tightly to digest the move, then resumes. Minervini's logic: such a stock is "discounting something major" and "velocity begets more velocity" — the fastest movers in the shortest time. This is the **only** SEPA setup he will enter with a *dearth of fundamentals* (price action substitutes for the Leadership Profile §4.7 / Earnings Quality §4.8) — but the constructive base behaviour is non-negotiable (see step Q3 below). (Ch 10; p. 253.)

### What it is (plain terms)
- A near-vertical price thrust (a doubling or more) out of a quiet period, on huge volume.
- Followed by a shallow, tight sideways flag where the stock barely gives ground.
- You buy the resumption — as the flag's high is taken out — not the trendline break.

### Qualification (ALL THREE must hold — EXACT thresholds, Ch 10; p. 255)
Candidate must already be in a confirmed **Stage 2** uptrend (Stage Analysis §4.1) and pass the **Trend Template (§4.2)** before any of the below is considered.

1. **The thrust.** An explosive price move commences on **HUGE volume** that shoots the price **up +100% or more in LESS THAN 8 weeks**. This generally occurs **after a period of relative dormancy**. `[2017]` Disqualifier: a stock that already booked a huge gain coming off a **late-stage base usually does NOT qualify** — the best power plays were **quiet in Stage 1** then suddenly exploded; look for **tight weekly closes over 3–6 weeks**. (2017 §7)
2. **The consolidation.** Price then moves **sideways in a relatively tight range, correcting NO MORE than 20–25%**, over a period of **3 to 6 weeks** (**some can emerge after as little as ~12 days**). `[2017]` 2017 tightens this: correct **no more than 20%** (some lower-priced stocks up to 25%), emerging after as little as **10 or 12 days**. (logged in §5.1)
3. **The tightness (VCP gate).** **Very tight price action correcting no more than ~10%, OR** the consolidation must display **VCP characteristics (§3.2 / §4.5–§4.6)**.
   - VCP behaviour is **MANDATORY** here: "I do require it to display VCP characteristics just as I do with all the other setups" (p. 253–254). Criterion 3 is the only place fundamentals are waived; the supply/demand digestion is never waived.
   - UNCERTAIN — criterion 3 is phrased as an OR: either an ultra-tight ≤10% flag **or** a fuller VCP qualifies, so the ≤10% figure is conditional, not an absolute floor (p. 255).

A power play is, in effect, a VCP riding on top of a violent prior thrust. Read its base with the **Technical Footprint (§4.4)** notation and the contraction/volume logic of **§4.5 Volume Analysis** and **§4.6 Base & Correction Geometry**.

### Entry (the buy) — Ch 10; pp. 249, 255–256
- **Buy as the high of the pause is taken out** — "the turn." Do **NOT** buy merely because a trendline is breached; **wait for the stock to turn** (p. 249).
- Trigger above the high of the tightest/final contraction = the **Pivot Point / line of least resistance (§4.3)**, confirmed on a **noticeable increase in volume** (§4.5).
- A power play often presents **TWO viable buy points: (1) "the turn," and (2) emergence from the handle** (Ch 10; Fig 10.63, Arena Pharmaceuticals).
- Confirming tell: watch for the **lowest-volume / super-tight day right before the breakout** (Ch 10; Fig 10.62, Best Buy).

### Exit / stop — see Selling §2 and §3.6 for the live rules (tight)
- No power-play-specific numeric stop appears in the source slice; apply the **Selling: Offensive & Defensive (§3.6)** framework. Because entry is at a tight pivot, the **stop sits just below the breakout pivot / final-contraction low** — the natural tight risk point.
- A textbook setup can still **fail** — a valid pattern is not a guarantee; the tight pivot is what keeps the loss small (cf. §3.2 VCP failure case).
- **Failure reset:** a stop-out does not mean the thesis is wrong; you may have been shaken out. Keep the name on the radar — it can **reset** (often within a few days for a pivot failure; a base failure needs a whole new base) and the reset setup is sometimes better than the original (Ch 10; pp. 249–250).

### Filters
- General Market Direction & Timing (§4.12) must be supportive — align fundamentals (or, here, exceptional price action), constructive price action, and a healthy overall market (p. 257).
- Require evidence of institutional accumulation; "a good company is not always a good stock" — what matters is what big institutions think (§4.10; p. 257).
- The thrust catalyst may be FDA approval, litigation resolution, a new product/service, an earnings surprise — **or no news at all** ("unexplained strength"); a catalyst is not required (p. 253).

### Edge cases
- **Fundamentals waived, base not waived.** Fundamentals (§4.7/§4.8) may be absent; the VCP/tightness behaviour of step Q3 may not.
- **~12-day minimum consolidation:** some power plays emerge after as little as ~12 days; UNCERTAIN whether this is an absolute floor on the 3–6 week window or just an observed minimum (p. 255).
- **Two-buy-point structures:** when both "the turn" and a handle exist, both are valid adds (Fig 10.63).
- **Squat / shakeout on breakout:** an initial breakout that squats or undercuts (e.g. the handle low) can be a constructive shakeout that then resets — do not assume failure on the first stall (Figs 10.56–10.58).

### Illustrative example moves (clearly labelled — NOT rules; do not generalise)
- TASER (TASR) 2004 — canonical power play, "Tight Price" flag + extreme "Volume Contraction" (Fig 10.60; ~+329% in 16 weeks, EXAMPLE).
- Quality Systems (QSII) 1995 — power play with classic VCP and accelerating EPS (Fig 10.61; ~+127% in 66 days, EXAMPLE).
- Arena Pharmaceuticals (ARNA) 2012 — two buy points, the turn then the handle (Fig 10.63; ~+70% in nine days, EXAMPLE).

### Versioning
- **Introduced 2013; refined 2017** (2017 §7 re-teaches the power play / high-tight-flag and **tightens criterion 2**: the consolidation should correct **no more than 20%** — *some lower-priced stocks up to 25%* — vs 2013's flat 20–25%, with min duration **10–12 days** vs ~12; it also adds the **late-stage-base disqualifier** ("the best were quiet in Stage 1") and the "**tight weekly closes over 3–6 weeks**" tell. Value change logged in §5.1). See Strategy Evolution §5.
---
## 3.5 Primary Base & IPO Setup

**Key:** `primary_base` · **Market context:** US equities, long-only, Stage 2 confirmed uptrend (§4.1, §4.12). Swing-to-position timeframe (days to many months). Buying a young company out of its *first* buyable base after going public.

### Why young companies (rationale)
- The biggest part of a company's growth usually occurs in the **first 5 to 10 years** after it goes public — products expand into new untapped markets on the freshly raised cash, management is at its entrepreneurial best, and margins/profit growth accelerate (Ch 11).
- Most superperformers go public **8 to 10 years before** the start of their superperformance phase (Ch 11; consistent with the SEPA youthfulness trait — superperformance typically occurs in the first ~10 years after IPO, §4.7).
- **Historical filter:** ~**80% of the stock-market winners that drove the 1990s tech boom were IPOs within the prior 8 years** (Ch 11). Every bull market is led by a handful of leadership stocks that were recent IPOs.
- Favor **youth with character** — pair the technical setup with sound, accelerating fundamentals (accelerating sales and earnings growth, §4.7, §4.8). "Recently gone public" does NOT mean newly *in business*: some firms operate privately for decades before IPO; others are brand-new start-ups.
- Hunt the **new, unfamiliar leaders**, not over-owned mature names. Many big primary-base leaders emerge right after a bear-market decline when the names are unfamiliar and trade at seemingly high multiples ("fall on deaf ears"). Avoid the "Innovator to Respirator" trap — well-known, over-owned "official growth stocks" that have already had their best earnings growth, whose supply overhang can bring down the price.

### What the Primary Base is
- **Primary base = the first buyable base after a company has gone public** — the first occurrence in the stock's trading history of the bullish pattern: correct/consolidate, then break to (or near) an all-time high. The first viable consolidation.
- A recent IPO must prove itself with a **minimum trading history** before it interests you — **at least a couple of months** of trading activity (qualitative track-record minimum). Some IPOs take **up to a year or more** to form a proper base.
- A fresh IPO can shoot straight up **25%, 50%, 100% or more** (sometimes on its very first trading day), OR sell off sharply soon after debut. Either way, **do not act until a proper primary base has formed** — never chase the raw IPO.
- Over a long advance a leader forms many bases/consolidations; the primary base is just the *first*. A subsequent **second-stage base** (a later consolidation clearing a further new high) is a valid later buy point in the same advance (Ch 11; Fig 11.4 Rambus A→D).

### Setup preconditions
1. Stock has a **minimum trading history** since IPO (at least a couple of months).
2. Stock is in a **confirmed Stage 2 uptrend** and passes the Trend Template (§4.2); a great-looking base inside a long-term downtrend is disqualified (§4.1).
3. **Base depth/duration limits (KEY RULE):** the base must run **at least 3 to 5 weeks** AND correct **no more than 25 to 35%** to be reliable.
   - A **short ~3-week consolidation must NOT correct more than 25%.**
   - A **long correction (usually around one year)** can decline **as much as 50%** and the setup can still be sound.
4. Evidence of institutional accumulation (volume support, §4.5, §4.10).
5. Sound/accelerating fundamentals (§4.7, §4.8) — youth with character.

### Entry (bullish)
1. Confirm Stage 2 uptrend + Trend Template pass (§4.2) and that the base satisfies the depth/duration limits above.
2. Mark the **Pivot Point / line of least resistance** (§4.3) — the breakout level out of the primary base into new-high ground.
3. **BUY on the successful emergence into new-high ground** out of the primary base, on a **noticeable increase in volume** (§4.5). This is the same breakout-on-volume entry mechanic used across the VCP playbook (§4.3, §4.5).
4. The primary base may present **multiple low-risk entries** (the primary breakout, then a second-stage-base breakout) — each qualifying breakout is an independent buy point.

### Bearish
- Long-only method. (Stage 4 downtrends are only shorted in the broader method; no bearish primary-base mirror is built here.)

### Failure case (illustrative — not a rule)
- An IPO that **never sets up properly and then corrects excessively is unbuyable.** *Example:* Facebook 2012 (Fig 11.2) — first-day high ~$45, closed $38.23, then ~**43% off its high** at $25.52 just 12 days later; no constructive base ever formed. This breaches the ≤25–35% depth limit and disqualifies it. (Stock-specific example, NOT a general number to apply.)

### Exit / stop & risk (§2)
- **Not every primary-base breakout works — there is no guarantee.** Always have an exit plan to cut losses if a primary base turns against you (Ch 11; Fig 11.8 iRobot 2006 — author sold at a *small loss* before the stock later slid ~65% from peak >$37 to ~$7). Stop-losses are mandatory under SEPA Element 5 (§3.1, §3.6, §2 risk rules).
- The base depth limits double as a **risk filter**: reject any setup that has corrected **>25–35%** (or **>25%** for a 3-week consolidation; up to ~50% allowed only for a ~1-year-long correction).
- For smaller companies, confirm they are already **profitable** and the business model has proven it can be **scaled/duplicated** (§4.7) before committing.
- Position sizing, stop placement, and profit-taking mechanics are defined in the Selling strategy (§3.6) and §2.

### `[2017]` The low-cheat IPO variant
For a recent IPO that **holds above its IPO price** and does not correct excessively, a **low cheat (§3.3)** can give an earlier entry than waiting for the full 3–5-week primary base: (2017 §7)
- The post-IPO basing period should be **at least ~10 days** (vs. the 3–5-week primary-base minimum) — a finer, shorter low-cheat variant, not a replacement for the primary-base rule.
- Best if the stock spends little time below the IPO price; a brief **undercut/shakeout of the low** can still work.
- Demand confirmation: a **gap up on heavy volume** followed by a **low-volume pull-back / gap-fill**, with tight **inside days on very low volume** = entry. Avoid buying into heavy overhead supply.
- *(Illustrative only: GOOG 2004 low cheat in ~14 days; Twitter 2013 base in ~19 days; Apple 2004 gap-up-then-low-volume-fill — stock-specific examples, not thresholds.)*

### Versioning
- **Introduced 2013; refined 2017** (the low-cheat IPO variant above is tagged `[2017]`; see §5).

### UNCERTAIN
- **UNCERTAIN — track-record minimum stated two ways:** "at least a couple of months of trading activity" (qualitative track record, Ch 11 p.260) vs. "at least 3 to 5 weeks" base (Ch 11 p.262). Treat the **3–5 week base + ≤25–35% correction** as the operative numeric rule and the "couple of months" as the qualitative minimum-history guideline.
- **UNCERTAIN — exact pivot mechanics:** the precise pivot price/volume trigger on each breakout is defined in the Pivot Point (§4.3) and VCP/buy-execution material (§3.2), not numerically re-specified in the IPO chapter.
---
## 3.6 Selling: Offensive & Defensive

**Key:** `selling`

Every position needs an exit plan *before* it is bought. Minervini runs two distinct selling modes, and a trader must have a written plan for both at all times:

- **Defensive selling** — protect capital. Cut a loser small, or get out of a winner that is breaking down, before damage compounds.
- **Offensive selling** — lock in gains *into strength* while buyers are still plentiful, instead of waiting for weakness to give the profit back.

"Your first loss is your best loss." (Ch 13) Avoiding large losses is "the single most important factor for winning big as a speculator." (Ch 12)

---

### A. Defensive Selling (protect capital)

#### A.1 The initial stop (the line in the sand)
Sizing rules are defined globally in §2 (Risk Management) and §4.5 (Volume) / §4.1 (Stages); restated here only as the sell triggers:

- Set the maximum stop **in advance, written down, before you buy** (e.g. a note on the screen). Not committing to a predetermined risk level "costs traders more money than any other mistake." (Ch 13)
- **Tie the stop to expected gain:** the stop should be **no more than one-half (½) of your average gain** from real trading results. *Illustrative:* if winners average +15%, stop a decliner no more than 7.5% off cost (buy $30 → stop $27.75). (Ch 13)
- **Absolute cap: never let any stock fall more than 10%** ("line in the sand"), no matter how large your average gains. Even when ½-of-average-gain would permit wider, do NOT exceed 10% — a 10% decline already signals something is wrong with the trade. (Ch 12; Ch 13)
- **Target average loss ≈ 5–6%** `[2017]` (6–7% in 2013) — well inside the 10% cap. (Ch 12; 2017 §8)
- The predetermined stop is an **absolute maximum**: the moment price hits it, sell — no exception, no "wait for the next rally." (Ch 13)
- **Slippage rule:** if the stock dives through your stop before you react, get out immediately at the next bid; a hard-falling stock is a warning, not a place to negotiate. (Ch 13)
- The initial stop matters most in a position's *early* stage; once the stock advances, it is replaced by the profit-protection logic in §B. (Ch 13)

#### A.2 Sell a failed breakout / a stock that can't hold support
- Sell a **failed breakout** — a stock that breaks out from a pivot (§4.3) and then **cannot hold the pivot / Line of Least Resistance**. (cross-ref §3.1 SEPA, §3.2 VCP)
- A **just-bought breakout that closes below its 20-day moving average** lowers the probability of success — but this is a **judgment-call warning subordinate to the predetermined stop**, NOT an automatic sell: give the stock room as long as it holds above the protective stop. The post-advance *defensive* moving-average reference in this method is the **200-day (40-week)** MA (Stage 4 — see A.4). (Ch 10)
- Sell a stock that simply **does not advance shortly after purchase**, even if it has not yet hit the stop — if it fails to do what you expected, step aside and reevaluate. (Ch 12)
- A stock falling *below your purchase price* is the market telling you the trade was wrong (at minimum on timing). (Ch 12)

#### A.3 The material-change sell tell (the core defensive signal)
- **The single most operational sell tell:** if the stock makes its **largest daily and/or weekly price decline since the START of the Stage-2 advance (§4.1) on overwhelming / huge volume (§4.5)**, that is a **SELL signal in most cases** — *even if it follows a seemingly great earnings report*. (Ch 5; Fig 5.14, 5.21–5.26)
- A **major break on huge volume** = institutions getting out ahead of news you do not yet have. "Take heed." Netflix, July 2011, plunged through its 40-week (200-day) MA on enormous volume as institutions ran for the exits (illustrative). (Ch 5; Fig 5.14)
- **Price leads the fundamental change** — institutions exit *before* a slowdown is reported. "Trust your eyes, not your ears": trust price/volume over analyst opinion and company hype. (Ch 5; Fig 5.17, 5.19, 5.25)
- **Do NOT treat a big break as a buying opportunity** and do NOT average down. When you see this price action, exit regardless of fundamentals — "shoot first and ask questions later." (Ch 5; Ch 12)

#### A.4 Sell into Stage-3 distribution; never hold into Stage 4
- When **topping signs appear after a long, extended run, take profit and exit** — do not wait for the earnings picture to dim. Stocks "very often top out while earnings still look good." (Ch 5)
- **Stage-3 distribution tells** (sell or progressively reduce): increased volatility, **high-volume churning** (heavy volume with little net price progress), double tops, a wide late-stage base, or a climax/blow-off run; breakdown below the moving averages. (Ch 5; §4.1)
- **Never hold into Stage 4** — price **below a declining 200-day (40-week) moving average** making lower lows (§4.1). If a holding enters Stage 4, **sell — or at minimum reduce the position progressively until in cash.** Even a buyer near the all-time high takes only a "relatively small loss" by heeding the Stage-4 decline. (Ch 5)
- **Do not buy or hold a broken stock on a brokerage upgrade.** Valuation-based upgrades on a stock in a Stage-4 downtrend ignore the major price break; such broken leaders often make good *short* candidates, not holds. (Ch 5; Fig 5.20)
- **Broken-leader syndrome:** do not rationalize holding (or re-buying) a former highflier because it "looks cheap" / "is down 70%" / "only 20x earnings on 40% growth." The top is usually discounting a coming growth slowdown — it is no bargain. Downside is always 100% of remaining capital regardless of how far it has already fallen. (Ch 4; Fig 4.10)

---

### B. Offensive Selling (lock in gains into strength)

- **Two ways to sell — have a plan for both:** (1) sell **into strength** (offensive) while price is still rising and buyers are plentiful — recognize when a stock is running up rapidly / exhausting itself and unload then; (2) sell **into weakness** (defensive, §A) at the first sign a run breaks down. (Ch 13)
- **Never round-trip a winner.** Once a gain is a *multiple* of your stop, rarely let it turn into a loss. *Illustrative:* stop 7%, gain reaches 20% → do not give it all back; move the stop to breakeven or trail to lock in the majority. (Ch 13)
  - Round-trip risk is real: roughly **1/3 of superperformance stocks give back all of their gains**, with an average subsequent decline of **50–70%**. (cross-ref §4.1 / §3.1)
- **Move stop to breakeven at 3× risk:** when the stock rises by **three times your initial risk**, almost always move the stop up to **at least breakeven**. *Illustrative:* buy $50, risk 5% ($47.50 stop = $2.50 risk); at $57.50 (3 × $2.50 above cost) move the stop to ≥ $50. Move it up at 2×–3× risk especially when that level exceeds your historical average gain. A trade stopped at breakeven is fine — "nothing gained but nothing lost." (Ch 13)
- **Trail behind the advance:** after the breakeven move, ratchet the sell point up under the rising price (trailing/back stop) to protect accumulated profit; do NOT give a winner extra downside room just because you have a cushion of gains — "yesterday's profit is part of today's principal." (Ch 12; Ch 13)
- **Climactic-strength sell:** sell partial or whole into a parabolic climax/blow-off run, or when the **P/E has expanded ~2× to 3× its starting level** alongside **decelerating earnings growth + price weakness** (§4.9). Pay extra attention once the P/E has expanded ~2×, especially ~2.5×–3× or greater over a 12–24 month run — the move may be in its later, too-widely-recognized stage. The trigger to reduce/sell is decelerating growth + price weakness, *not* the high P/E alone. (Ch 4; Fig 4.11)

---

### C. `[2017]` Selling refinements (from _Think & Trade Like a Champion_, §1, §5, §9)

These sharpen the 2013 defensive/offensive rules above with concrete, quantified signals. Sell mechanics referenced here (breakeven-or-better, free roll, back stop, sell-half) are defined in §2.6.

**C.1 The 50/80 Rule (why you heed the FIRST loss).** Once a secular market leader puts in a **major top**, there is a **50% chance it falls ~80%** and an **80% chance it falls ~50%**; topped leaders decline **>70% on average**. Every major decline starts as a minor pullback — so cut the first small loss and never average down a topped leader. (2017 §5)

**C.2 Post-breakout VIOLATIONS checklist (defensive — early in the trade).** A fresh breakout that starts violating is a reason to **reduce or exit, sometimes before the stop is hit**, especially when several pile up: (2017 §1)
1. **Low volume out, high volume in** — breaks out on light volume then comes back IN on heavy volume ("winning horses don't back up into the gate").
2. **Three (or four) lower lows on increased volume** — watch the third lower day and the next: a higher close / upper-half close on a volume rush may let you stay; a weak close = exit.
3. **More down days than up days**; **more bad closes than good closes** (lower-half-of-range).
4. **A close below the 20-day MA soon after the breakout** — Minervini's studies: this roughly **halves** the probability of success (a judgment-call warning subordinate to the predetermined stop, per §A.2).
5. **A close below the 50-day MA on heavy volume** — an even worse sign (a strong sell tell, especially alongside other violations).
- Reminder (Ryan): "I want to be at a profit very soon after I buy." A breakout that won't show a quick profit and starts violating is suspect.

**C.3 Climax-top sell-into-strength checklist (offensive — late in the move).** Only hunt these once the stock is confirmed **late-stage** (base count, §4.6) **AND** P/E has expanded **≥2×** (§4.9) **AND** price is **extended** above its last base (*swing-trader exception: a swing trader may act on the exhaustion signals at an earlier base count*): (2017 §9)
- New highs from **late 4th / 5th-stage bases**; **P/E expanded ≥2×** during the late-stage run.
- **Climax / blow-off run:** price up **25–50%+ in 1–3 weeks** (some +70–80% in 5–10 days), accelerating to a steeper angle than at any prior point in the advance.
- **Up-day exhaustion count:** on an extended stock, **≥70% up days over a 7–15-day window** (e.g. 7 of 10), or **6–10 days of accelerated advance with only 2–3 down days**.
- The **largest up day** and/or **widest daily high-to-low spread since the move began** (the last blast usually marks the top within a few days); recent **exhaustion gaps**.
- When these converge, **sell aggressively into the strength** (institutions liquidate on the way up while buyers are plentiful).

**C.4 Reversal-on-volume signals (the hand-off to weakness).** Watch the heaviest-volume day: if it lands on a **down** day, institutions are liquidating. Specific one-or-few-day tells: **high-volume key reversals**; **churning** (elevated volume, little price progress); the stock **down on the largest volume since the move began**. Subtlety: the largest down day need *not* be on the largest volume — a **−4–5% day on the largest volume since the move began**, combined with other violations, is a major warning. (2017 §9)

**C.5 Offensive sell mechanics (the live rules; defined §2.6).** As a profit grows, run the **three-priority hierarchy** (initial stop → protect breakeven → protect profit). Use **breakeven-or-better** (trail on the **50-day MA** once it reaches your entry; exit on a close below it), the **back stop** (profit-protection line at/above your average gain), the **free roll** (up 2R–3R → sell half, move stop to breakeven or finance the back half), and **sell-half** when a winner well above your average gain starts slipping. As the open gain grows, reward-to-risk on the *remaining* position degrades — once holding further means accepting ≈1:1 (give-back ≈ further upside), sell. (2017 §9, §10)

**C.6 Dead-money rotation & don't sell leaders too quickly.** Reallocate out of a stagnant stock that has **not** hit its stop but isn't advancing (opportunity cost). Conversely, early in a new bull market **don't dump your whole position in a true leader** to chase something else — take partial profits but **hold 25–50% of the original position** to let the strongest leaders run. (2017 §8)

**C.7 The differential-disclosure veto (buy-side, but a sell/avoid rule).** If a stock **beats earnings** yet **drops hard (≈15%) on the largest volume in years**, do NOT buy it even if it tops your watch list — institutions are dumping it and know something you don't. "Trust your eyes, not your ears" (price/volume over the report). (2017 §5)

**C.8 Early-stage exception (don't misread the signals).** Do **not** apply the late-stage exhaustion signals (C.3) to an EARLY-stage breakout. Early in a move, an up-day cluster / **MVP** strength (up 12 of 15 days, §4.10) and **tennis-ball action** (§3.1) are reasons to **HOLD**; the same up-day cluster is a *sell* tell only once the stock is late-stage and extended. Context (base count + P/E expansion + extension) decides buy vs. sell. (2017 §9)

---

### E. `[2020]` Selling & re-entry refinements (Master Trader Program)

These consolidate and sharpen §A–§C; mechanics referenced are defined in §2.6.

**E.1 The consolidated "Sell Alerts" watch list (13 signals before a big decline).** The MTP gathers the distribution/blow-off tells into one numbered pre-decline checklist — *"stocks flash warning signals before a big decline."* Its 13 members are the §A.3 material-change break plus the §C.3/§C.4 climax & reversal tells (accelerated/parabolic advance; +25–50% in 1–3 weeks; largest up day; widest daily/weekly spread; 6–10 days accelerated; exhaustion gaps; new-high-on-low-volume from a late base; stalling/churning; drop below the 50-day on the heaviest volume; largest one-day decline; largest weekly decline; down on the largest volume since the move began). Treat a *cluster* as the sell trigger. The §C.2 early-breakout violations + the E.2 directives below are a **separate "selling into weakness" list** from the same 2020 section — not members of the 13. (MTP S23)

**E.2 New / sharpened sell tells** (the 2020 "selling into weakness" list — MTP S23):
- **Exhaustion gaps — usually the 2nd or 3rd gap.** Once a stock is extended, count the gaps: the **second or third exhaustion gap** (not the first) typically marks the late, sell-into-strength zone (sharpens §C.3's "recent exhaustion gaps"). (MTP S23)
- **New high on LOW volume from a LATE-stage base** (4th, 5th, **or 6th** stage) — a fresh high that lacks volume from an already-late base is a distribution tell, not strength (extends §C.3's late-4th/5th-base note). (MTP S23)
- **Close below the 20-day MA, *below your purchase price*, soon after a proper-base breakout → REDUCE shares.** 2020 turns the §A.2 / §C.2 "judgment-call warning" into an **active position-reduction directive** for this specific case (below cost + below the 20-day soon after entry). The predetermined stop (§2.2) still governs the full exit. (See §5.2 — refines the 2013 judgment-call framing.) (MTP S23)
- **Heavy selling with a full retracement soon after a LOW-VOLUME breakout → SELL.** A thin breakout that gives the whole move back on heavy volume is a hard sell (sharpens §C.2 #1 "low volume out, high volume in"). (MTP S23)

**E.3 New violation members** (added to the §C.2 checklist — MTP S13):
- **Full retracement of the breakout** (price falls all the way back into the base).
- **Failed natural reaction** — the post-breakout pullback's recovery attempt *fails* (the opposite of healthy tennis-ball action, §3.1), typically alongside 4 lower lows on heavy "volume-in" + a close below the 20-day.

**E.4 Failure patterns & vocabulary** (MTP S13–S14):
- **Ledge / Pivot Failure / Base Failure.** A breakout attempt that fails forms a small **"ledge"** consolidation just below the pivot (labeled *Pivot Failure* or, for a whole base, *Base Failure*) — usually caused by left-side **overhead supply** ("always look left for traffic," §4.3). A ledge below the pivot is a failed-breakout exit/short-watch signal (a Minervini Private Access **"MPA Short Alert"** — noted only; the method stays long-only).
- **Failure Reset (the re-entry side).** A failed pivot/breakout does **not** retire the stock: keep it on the watch list for a **Pivot Reset** (it re-forms the same pivot and breaks out cleanly) or a **Base Reset** (it builds a larger base before a fresh buy point). This is the selling/re-entry pairing of the §2.4 reentry rule ("it can take 2–3 tries; the second setup is often stronger") and the §3.2 VCP buy. (MTP S14)

### D. Daily Sell Decision List (run on every open position, every day)

End each trading day (and pre-rehearse before each open) by asking, for each holding:

1. **Is my predetermined stop still in place and written down?** If price has hit it → **SELL now**, at the next bid if slipping. (defensive)
2. **Has the stock made its largest daily/weekly decline since the Stage-2 start (§4.1) on huge volume (§4.5)?** If yes → **SELL**, regardless of how good the last earnings looked. (defensive)
3. **Did it fail to hold its pivot (§4.3) / fail the breakout?** If yes → **SELL** the failed breakout (defensive). *(A fresh breakout closing below its 20-day MA is a judgment-call warning — 2013 Ch 10, quantified 2017 §1 (§3.6.C.2: ≈ halves the odds) — not an automatic sell; act on the predetermined stop.)*
4. **Are Stage-3 distribution tells present** (volatility up, high-volume churning, breakdown below MAs)? If yes → **reduce or exit.** Is it in Stage 4 (below a declining 200-day MA, §4.1)? If yes → **sell / progressively reduce to cash; never hold into Stage 4.** (defensive)
5. **Am I being tempted to hold/add on a broken stock because it "looks cheap" or got a brokerage upgrade?** If yes → **do NOT** — broken-leader trap (§4.9). (defensive)
6. **Is the stock up ≥ 3× my initial risk?** If yes and not yet done → **move the stop to at least breakeven**, then trail. (offensive)
7. **Is the stock in a climax run, or has its P/E expanded ~2×–3× with decelerating growth + price weakness (§4.9)?** If yes → **sell partial/whole into the strength**; do not wait for the breakdown. (offensive)
8. **Am I still bullish on THIS position today — does my original long thesis still hold?** If no → why hold it. Each day a stock must justify holding on a forward return-vs-risk basis. (defensive)
9. `[2017]` **Is the trade EARLY-stage and still violating?** Tally the §C.2 violations (low-vol-out/high-vol-in, 3–4 lower lows on volume, more down than up days/closes, close below the 20-day MA, close below the 50-day on heavy volume). On **multiple violations → reduce/exit, possibly before the stop.** (defensive)
10. `[2017]` **Is the trade LATE-stage and extended?** If late-stage base count + P/E expanded ≥2× + extended, run the §C.3 climax checklist (25–50%+ in 1–3 weeks; ≥70% up days over 7–15 days; largest up day / widest spread / exhaustion gaps) → **sell into strength.** (offensive)
11. `[2017]` **Is it dead money?** If it hasn't hit the stop but isn't advancing while better candidates beckon → **reallocate** (two-for-one, §2.5). And on a strong leader early in a bull, **hold 25–50%** rather than selling it all. (offensive)
12. `[2020]` **Run the "Sell Alerts" scan (§E.1) + the daily re-evaluation triad (§2.10).** Any cluster of the 13 alerts → reduce/exit. A close below the 20-day *below your cost* soon after a breakout → **reduce shares** (§E.2). If a breakout failed, mark the stock for a **Failure Reset** re-entry rather than discarding it (§E.4). (defensive + offensive)

> **Stage-4 short note (long-only method):** Minervini's method is long-only in practice. Stage-4 declines (broken leaders upgraded on valuation, stocks below a declining 200-day MA) are only *mentioned* as potential short candidates (Ch 5). This playbook builds no bearish mirror — Stage 4 means **exit**, not "go short."

### Versioning
- Introduced **2013**; substantially **refined 2017** — the 2017 book's heaviest reinforcement lands here. The 2013 rules (A.1–A.4 defensive, B offensive) are unchanged; §C (`[2017]` refinements) adds the 50/80 rule, the post-breakout violations checklist, the quantified climax-top sell-into-strength checklist, reversal-on-volume signals, the live sell mechanics (breakeven-or-better / free roll / back stop / sell-half, defined §2.6), dead-money rotation, the differential-disclosure veto, and the early-stage exception. **Refined again 2020** — §E adds the consolidated 13-signal "Sell Alerts" list, the 2nd/3rd-exhaustion-gap and new-high-on-low-volume-from-a-late-(4th–6th)-stage-base tells, the *full-retracement* and *failed-natural-reaction* violations, the "close below 20-day below cost → reduce" directive, and the **Ledge / Failure-Reset** failure-and-re-entry vocabulary. See Strategy Evolution §5.
---
# 4. Common Rules & Shared Components

Each concept below is defined ONCE here; §2 and §3 reference it by number instead of repeating it. Settings are exact; stock-specific figures are labelled examples.

---
### 4.1 Stage Analysis (4 stages)

Stan Weinstein's four-stage stock life cycle (adopted by Minervini) describes where a stock sits in its price/earnings cycle. The underlying driver of every stage is almost always **earnings**: lackluster → upside surprise + accelerating growth → decelerating growth → disappointment. Big-institution flows in/out track these shifts and show up as large **volume** spikes during both the advance and the decline. Use stages for *perspective*, not pinpoint timing. (Ch 5, p. 64–66, 80)

A full cycle through all four stages can take several years; a stock can cycle through the four stages many times. Virtually every superperformance stock makes its big gain while in **Stage 2**. (Ch 5, p. 65, 80)

**Stage 1 — Neglect / Consolidation** (Ch 5, p. 66–68; Fig 5.1)
- Price moves **sideways**, no sustained move up or down; "dead in the water" for months or even years.
- Price **oscillates around its 200-day (= 40-week) MA**, which is **flat** — no real trend.
- Often follows a Stage 4 decline of several months or more.
- **Volume contracts** and is relatively **light** vs the prior Stage 4 decline.
- Action: **do not buy** — even with appealing fundamentals. No upside momentum yet. Do not bottom-fish.

**Stage 2 — Advancing / Accumulation** (Ch 5, p. 71–72; Fig 5.3)
- Price is **above** its 200-day (40-week) MA, and the **200-day MA is itself in an uptrend**.
- The **150-day (30-week) MA is above the 200-day (40-week) MA**.
- Clear uptrend: **higher highs and higher lows** in a staircase pattern.
- **Short-term MAs above long-term MAs** (e.g. 50-day above 150-day).
- **Accumulation volume signature:** volume spikes on big up days/up weeks, contracting on normal pullbacks; **more** up days/up weeks on above-average volume than down days/down weeks. Accumulation signs should appear during *every* Stage 2 advance.
- Action: **the only stage in which Minervini buys long.** Qualify a confirmed Stage 2 via the Trend Template (§4.2).

**Stage 3 — Topping / Distribution** (Ch 5, p. 72–73; Fig 5.4)
- **Volatility increases**; price action becomes wider, looser, and far more erratic than Stage 2 even while it may still edge higher (high-volume churning as institutions distribute).
- Usually a major price break on **increased volume** — often the largest one-day decline since the Stage 2 advance began (on a weekly chart, possibly the largest weekly decline since the move began); these breaks almost always occur on overwhelming volume.
- Price may repeatedly **undercut the 200-day MA**; volatility around the 200-day (40-week) MA is common while topping.
- The **200-day MA loses upside momentum, flattens, then rolls over** into a downtrend.
- Top often forms as a double top, a wide late-stage base, or a climax/blow-off run (treat as patterns, not numbers). (Ch 5, p. 83)

**Stage 4 — Declining / Capitulation** (Ch 5, p. 74–76, 95–96; Fig 5.5, Fig 5.14)
- The vast majority of price action is **below the 200-day (40-week) MA**.
- The **200-day MA is in a definite downtrend**.
- Price is near or hitting **52-week new lows**, in a series of **lower lows and lower highs** stair-stepping down.
- **Short-term MAs below long-term MAs.**
- Volume spikes on big **down** days/weeks, contrasted by low-volume rallies; more down days/weeks on above-average volume than up. (Mirror image of Stage 2.)
- Action: **avoid all long entries.** Shorting is only briefly noted as a Stage-4 possibility — not developed here.

**Cardinal stage rule:** Only buy in **Stage 2**; never buy in Stage 1, 3, or 4. In particular, **never go long a stock trading below its declining 200-day MA** (assuming 200 days of trading history exist) — no matter how strong the fundamentals. (Ch 5, p. 63–64, 80)

**`[2017]` notes** (2017 §6, §10):
- **Stage labels:** Stage 1 = *Neglect / consolidation*, Stage 2 = *Advancing / accumulation*, Stage 3 = *Topping / distribution*, Stage 4 = *Declining / capitulation*. In Stages 1/3/4 you are either losing money or losing time.
- **Always trade directionally — never buy a falling stock.** If a name you like is under selling pressure, **wait for it to turn back up** before committing (critical with tight stops, which get hit buying against the trend).
- **Beware the "serial gapper":** Stage-4 downtrend stocks gap DOWN repeatedly — buying a downtrend dramatically raises overnight gap-down risk.

**`[2020]` notes** (MTP S4):
- **Stage 1→2 "Transition Criteria" (the early turn).** Before a full Stage-2 confirmation you can flag the turn when: (1) price is **above both the 150- and 200-day MAs**; (2) the **150-day is above the 200-day**; (3) the **200-day MA has turned up**; (4) price has made **higher highs and a higher low** — PLUS the volume tells: (a) **large up-weeks on volume spikes** vs **light-volume pullback weeks**, (b) **more up-weeks on rising volume than down-weeks**, (c) **quick, supportive recoveries on increased volume** after pullbacks. (Use the "Trend Template – 1 Month" screener, §4.2, to surface these early turns.)
- **Stage-2 dominance, quantified:** **98%** of big winners made the **largest portion of their gain in a Stage-2 uptrend** (sharpens the "virtually every superperformer rises in Stage 2" statement above).
- **The "95% Club" big-winner study** `[2020]` (from MTP **S2**; characteristic *profile*, NOT pass/fail thresholds; extends the §4.2 "99%/96% above the MAs" basis): of past biggest winners, ~**99%** were above the 200-day MA and ~**96%** above the 50-day MA before the move; ~**96%** began off a correction/consolidation; ~**95%** had a **small float (< 30M shares)**; ~**95%** showed earnings acceleration; ~**95%** had a catalyst; ~**70%** reported **+20% EPS** in the most recent quarter; ~**80%** were **IPOs within the previous 10 years**. (Confirms/quantifies the young-small-float-leader target profile, §3.1/§4.7/§4.10.)

---

### 4.2 Trend Template (8 criteria)

A stock must meet **ALL eight** criteria to be considered in a **confirmed Stage 2 uptrend**. The Trend Template is a hard, nonnegotiable **technical qualifier applied FIRST**, before any fundamental screen; a stock that fails it is not considered, no matter how compelling the company looks. (Ch 5, p. 63, 79)

Empirical basis: **99%** of superperformance stocks traded **above their 200-day MA** before their huge advance, and **96%** traded **above their 50-day MA**. (Ch 5, p. 79)

1. Current price is **above both the 150-day (30-week) and 200-day (40-week) MAs**.
2. The **150-day MA is above the 200-day MA**.
3. The **200-day MA is trending up for at least 1 month** (preferably 4–5 months minimum in most cases).
4. The **50-day (10-week) MA is above both the 150-day and 200-day MAs**.
5. Current price is **above the 50-day MA**.
6. Current price is **at least 25% above its 52-week low** `[2017]` (2013 said **30%**; per the conflict rule the looser 25% is primary — see §5.1). (Many of the best selections will be 100%, 300%, or more above their 52-week low before emerging from a solid consolidation — *illustrative*, not a requirement.)
7. Current price is **within at least 25% of its 52-week high** (the closer to a new high, the better).
8. **Relative Strength (RS) ranking** (as reported in Investor's Business Daily) is **no less than 70**, preferably in the **80s or 90s**. (See §4.10.)

Related confirmation gate (works with the template, not one of the 8): there must be a prior rally with **price escalation of at least 25–30% off the 52-week low** before concluding a Stage 2 advance is underway; buying earlier lacks confirmation and is premature. (Ch 5, p. 68)

- UNCERTAIN — Criterion #3 wording: "at least 1 month (preferably 4–5 months minimum in most cases)." The "4–5 months minimum" reads as a preference, not a hard floor; treat **1 month** as the strict minimum.
- UNCERTAIN — Criterion #7: whether "within at least 25% of its 52-week high" is a strict cap or a soft guide. Text adds "the closer to a new high, the better," suggesting a guide with a 25% ceiling.

**`[2017]` refinements** (2017 §6): the same eight criteria are re-taught (2017 re-orders them but the set is identical), with one value change and two additions — (a) **value change:** the 52-week-low floor is **eased from 30% to 25% above the 52-week low** (criterion #6 above; logged in §5.1); (b) criterion #3: 200-day MA up "for at least 1 month, **preferably 4–5 months or longer**" (2017 hardens 2013's parenthetical preference); (c) the **RS criterion** adds that the **RS line should not be in a strong downtrend** — prefer the RS line in an **uptrend for ≥6 weeks, ideally ≥13 weeks** (RS rank ≥70, preferably the **90s**), and that the price-above-50-day-MA criterion applies "**as the stock is coming out of a base**." See the RS combination rule in §4.10.

**`[2020]` refinements** (MTP S4): the same eight criteria are re-taught, with operational additions —
- **Named Trend-Template screeners (run nightly, §2.10):** **"Trend Template – 1 Month"** (looser MA-uptrend duration — finds **Power-Play setups & early Stage-1→2 turns**); **"Trend Template – 5 Month"** (the standard scan for clearly established uptrends); **"Trend Template – 5 Month Wide"** which **drops the RS-rating and the price-above-50-day-MA requirement** to surface strong stocks sitting **just below their 50-day MA** — the hunting ground for **low-cheat** entries (§3.3).
- **Criterion #5 (price above the 50-day MA) carries an explicit exception:** it is **waived for "Low Cheat" setups** (where the entry is taken in the lower third of the base, below the 50-day — §3.3). This is why the "5 Month Wide" screener removes the 50-day requirement.

**`[2021]` re-frame** (MTP re-run): the 2021 workshop retitles this whole trend/stage block **"The Stock Maturation Cycle — Non-Negotiable Criteria"** (2020 called it "The Primary Trend"). The **eight criteria are re-taught verbatim** (identical thresholds) and the named screeners are unchanged; "Stock Maturation Cycle" simply ties the Trend Template (the non-negotiables) to the four-stage life cycle a stock passes through (§4.1). No value change. **`[2022]`** re-confirms the same title, the same eight criteria (with the criterion-8 "Low Cheat" exception and the RS-line uptrend ≥6 wk / pref ≥13 wk qualifier shown), and the same four named Trend-Template screeners — verbatim.

---

### 4.3 The Pivot Point / Line of Least Resistance

The **pivot point** marks the completion of a consolidation and the cusp of the next advance — the price level that acts as the **trigger to enter** and the optimal buy point (a "call to action" level). Jesse Livermore's **"line of least resistance"** is the same idea: when price breaks through it, the odds of a quick move higher are greatest because **supply is low at that point**, so even small demand moves price rapidly. (Ch 10, p. 223–224)

- **Where it forms:** at the high of the **final, tightest contraction** of the base — e.g. the top of a VCP's last T, a cup handle, or a cheat (3-C) structure. It can occur **either** as the stock breaks into **new-high territory OR below the structure's high** (cup-with-handle and cheat pivots form below the high). (Ch 10, p. 223, 229)
- **Flat-base case:** when a flat base has no real pivot other than the high of the base, the pivot is the **highest price at the top of the base**, provided the base corrected no more than **10–15%**. (Ch 10, p. 229; see §4.6)
- **Entry discipline:** buy **as close to the pivot as possible without chasing more than a few percentage points** above it. **Always wait for the stock to actually pivot** — do not pre-position before the breach to save pennies; assuming a breakout is dangerous, and a tight pivot gives no material advantage to entering early. (Ch 10, p. 224, 229)
- The pivot is **only one part of the overall setup but the most important piece** — the final determinant of when to risk capital. A **correct pivot point coming out of a sound consolidation rarely fails**; pivot success is proportional to how well the setup has been established (a faulty setup, or trading long in a bear market, can make it fail). (Ch 10, p. 224, 229–230)

(Volume behavior at the pivot — dry-up into it, expansion on the breakout — is defined in §4.5.)

**`[2017]` notes** (2017 §1, §6, §10): a correct pivot out of a sound consolidation **rarely fails in a HEALTHY market** (pivot success is conditional on overall market direction, §4.12). The pivot/line of least resistance is the trigger for **velocity trades** (§1.B.2). Do not buy **"extended"** — David Ryan's threshold = more than **~10% above the most recent consolidation** (sharpens 2013's "buy ≤ a few % above the pivot"; buy near the **danger point**, §2.2).

**`[2020]` notes** (MTP S13, S24): set a **pivot pre-alert** (an earlier warning level just below the pivot) in addition to the pivot alert, so you are at the screen as price approaches the trigger (the daily-routine mechanic, §2.10). A failed breakout attempt that stalls just below the pivot forms a **"Ledge"** (Pivot Failure / Base Failure) — usually caused by left-side **overhead supply** ("look left for traffic"); a ledge is a failed-pivot exit/short-watch signal and the precursor to a **Failure Reset** re-entry (§3.6.E.4).

---

### 4.4 The Technical Footprint (notation)

The **footprint** is the unique signature of a stock's consolidation, capturing **3 components**: (Ch 10, p. 202; Fig 10.6)
1. **Time** — number of weeks since the base started.
2. **Price** — depth of the largest correction AND the narrowness of the smallest (final) pullback at the far right.
3. **Symmetry** — the number of contractions (Ts).

**Notation format:** `[weeks]W [deepest%/tightest%] [count]T`

**Canonical example (read off Fig 10.6):** `40W 31/3 4T` = a 40-week base, deepest correction **31%**, tightest final pullback **3%**, **4 contractions**. The four Ts in that figure step down roughly by halves: **−31% → −17% → −8% → −3%** (Meridian Bioscience / VIVO chart). *Example numbers — illustrative only.* (Ch 10, p. 202; Fig 10.6)

- A VCP typically forms **2 to 4 contractions** (sometimes 5 or 6). (Ch 10, p. 199, 201; see §3.vcp)
- The footprint lets a trader recall a base's shape without the chart. Other example footprints in the book: `6W 32/6 3T` (MELI, Fig 10.28) and `8W 22/2 3T` (EDU, Fig 10.32) — *example footprints, illustrative only.*
- `[2017]` 2017 §6 re-teaches the same three components (Time / Price / Symmetry), noting **Time = number of days OR weeks** since the base started, **Price = depth of the largest correction AND narrowness of the smallest right-most contraction**, and **Symmetry = the number of contractions** through the base. (The footprint also records the level of volume at key points in the structure.)

---

### 4.5 Volume Analysis

Volume is the confirmation layer beneath price across the whole setup: contraction during the base, dry-up at the pivot, surge on the breakout, and a health check afterward.

**Dry-up in the final contraction (before entry)** (Ch 10, p. 226–227)
- Every correct pivot develops with a **contraction in volume**, often well below average, with **at least one day of very significant contraction** — in many cases to almost nothing or near the lowest volume in the entire base.
- **Exact threshold:** volume on the **final contraction below the 50-day average**, with **one or two days when volume is extremely low**.
- Very low volume on the final tight pullback means selling/profit-taking has exhausted and supply has abated (the stock has "stopped coming to market").
- **Intraday volume extrapolation (pre-trigger, Ch 10, p.229):** before buying at the pivot, **project the full day's volume from partial-session volume** to confirm above-average demand is building — e.g. if roughly half the average daily volume has already traded ~2 hours into the session while price rises, the day projects to a large multiple of average; buy as price clears the pivot once the projection confirms expanding volume. *(The specific multiple is illustrative.)*

**Breakout on a volume surge (the buy trigger)** (Ch 10, p. 219, 227)
- Buy **just as price breaks above the pivot on increasing volume** — low volume *into* the pivot, then expanding volume *on* the breakout itself.
- Look for **significant, above-average increases in volume on upward moves** off the lows and up the right side of the base. It is not uncommon to see a surge of **several hundred percent, up to ~1,000%, of average volume** on these up moves.
- Volume must be **much bigger on up days than down days**; a few up-side spikes should be **large, dwarfing the lower-volume contractions**. Look for big up days that occur larger and more frequently than big down days.
- **Disqualifier:** avoid a stock that follows a big demand (up) day with **even bigger down days on volume**. (Ch 10, p. 219)

**Down on lower volume during the base** (Ch 5, p. 71–72; Ch 10, p. 219)
- Constructive accumulation = big up days/weeks on increased volume **contrasted with lower-volume pullbacks**. Pullbacks should occur on **light/declining volume**.

**Post-breakout health (the 20-day MA gate)** (Ch 10, p. 232)
- After a successful breakout, price **should hold its 20-day MA and in most cases should not close below it**.
- The pattern should **not widen** into large up-and-down swings — up is good, wild swings are not. (A close below the 20-day MA lowers the probability of success and becomes a judgment call.)

Note: a **price spike/gap on outsized volume** off the lows or on the right side of a base is a sign of institutional demand, ideally driven by a fundamental change (earnings beat, industry development, upgrade); such gaps often also appear on the weekly chart. *No single volume signal makes a setup — it is the combination of demand spikes + low-volume pullbacks + shakeouts + price contraction.* (Ch 10, p. 217–218)

**`[2017]` post-breakout volume confirmation & violations** (2017 §1, §6, §9):
- **20-day MA violation quantified:** a **close below the 20-day MA soon after a breakout** roughly **halves** the probability of success; a **close below the 50-day MA on heavy volume** is worse (a sell). (This quantifies the 2013 "should hold its 20-day MA" gate above; it remains a judgment-call warning subordinate to the predetermined stop — see §3.6.A.2 / §3.6.C.2.)
- **Follow-through & tennis-ball confirmation:** want multiple up days on rising volume (e.g. 3 of 4 / 6 of 8 / 7–8 in a row) and resilient, brief, low-volume pullbacks that snap back on expanding volume — full detail in §3.1. The "**low-volume out, high-volume in**" pattern is a red flag (§3.6.C.2).
- **Charts as a filter:** price+volume tells you not what a stock *will* do but what it *should* do (normal vs abnormal; accumulation vs distribution) — so when behavior deviates from the "schedule," the exit decision is clear.

---

### 4.6 Base & Correction Geometry

A "proper base" is a constructive consolidation within a confirmed Stage 2 uptrend — rest/digestion → temporary profit-taking → equilibrium/correction, then continuation higher. Concentrate on the stocks that correct the **least**, not the most. (Ch 10, p. 196–197)

**Depth** (Ch 10, p. 211)
- Preferred correction depth: **10% to 35%** `[2017]` (2017: occasionally as much as **~40%** for an otherwise constructive setup — logged in §5.1).
- **Rarely buy a stock that has corrected 60% or more** — a deep sell-off may signal a serious problem and always carries heavy overhead supply.
- **Relative-to-market filter:** under most conditions, avoid stocks that correct **more than 2× to 3× the decline of the general market** `[2017]` (2017 raises the floor to **2.5× to 3×** — logged in §5.1).
- Exception: in major bear-market corrections some names can decline as much as **50%** and still work out (context-dependent).

**Duration** (Ch 10, p. 197, 212; Ch 5, p. 80–81)
- A proper basing period can last anywhere from **3 weeks to as long as 65 weeks**, depending on correction depth. (General consolidation scan window: 3 to ~60 weeks.)
- Base patterns within a Stage 2 advance most commonly last **5 to 26 weeks**.
- Cheat bases mostly run **7 to 25 weeks** (see §3.cheat_3c).

**Symmetry / time compression** (Ch 10, p. 211–212)
- The **right side must quiet down** — price and volume must contract before entry. If they do not, supply is still coming to market and the stock is too risky; avoid.
- Constructive bases have a degree of **symmetry**, because digesting supply takes time.
- **Avoid V-shaped / time-compressed bases:** if a stock advances too quickly up the right side it forms a hazardous time compression (V-shape or absence of proper right-side development) — avoid, at least temporarily, until a proper base forms.

**Shakeouts / undercuts** (Ch 10, p. 213–216)
- A **shakeout** = price undercuts a prior support low, triggering weak holders' stops, then reverses and rallies — eliminating weak holders so the advance is unencumbered.
- Ideally see a shakeout **one, two, or three times** (depending on the base's size/magnitude) **before** entering. Shakeouts can occur at (a) the **lows** of the base, (b) the **right side**, and (c) the **handle or pivot** area.
- Obvious/major support areas are traps (full of everyone's stops); prefer bases that have **already digested their shakeouts** before your entry.
- Caution: do **not** buy a declining stock just because you *think* it is a shakeout — "we are not forecasters; we are interpreters." Wait for the undercut to actually reverse back up before acting.

**Base count up the Stage-2 leg** (Ch 5, p. 81–83)
- A top is generally put in after **3 to 5 bases** have formed along the Stage 2 advance.
- **Bases 1 and 2** generally come off a market correction = the **best time** to board a new trend. **Base 3** is more obvious but usually still tradable. By the **4th or 5th base** the trend is extremely obvious and late-stage, with abrupt base failures occurring more frequently.
- UNCERTAIN — Base counting **by itself** will not tell you whether a stock has topped; it only gives perspective on where you are in the Stage 2 advance. Treat the 3–5 count as guidance, combined with price/volume and fundamentals — not a standalone trigger.

**`[2017]` additions** (2017 §6, §7, §9):
- **Double bottom (new base variation, 2017 §7):** a "W" structure, preferably **undercutting** the first low (shakes out more weak holders), that must form a **right-side pause / pivot** (cheat area and/or handle). Structures that run straight up the right side with no pause/pivot are failure-prone. Adds to the non-VCP variations (Darvas box, flat base) above. *(Illustrative: LULU 2010 broke from a clean double bottom — example only.)*
- **Overhead-supply mechanism (why contractions shrink left→right):** trapped higher buyers sell near breakeven on rallies and earlier bottom-fishers take quick profits — together creating the right-side pullbacks; under accumulation these contractions get smaller on lower volume (the "wet-towel," §3.2).
- **Base count → sell timing (cycle tiers):** after a bear-market low the first plateau = **base 1**. **Bases 1–2** (off a correction) = best entries, give room to run; **bases 3–4** still work but are later-cycle — treat as trading opportunities; **bases 5–6** are extremely failure-prone — **sell into** late-stage strength rather than buy. (Sharpens the 2013 "top after 3–5 bases.")

**`[2020]` per-pattern base-duration windows** (MTP S6) — typical length by Stage-2 base type (refines the generic "3–65 weeks" / "5–26 weeks" durations above):

| Base / setup type | Typical duration |
|---|---|
| Cup-Completion **Cheat** & **Low Cheat** | **6–52 weeks** |
| **Cup-with-Handle** | **7–65 weeks** |
| **Double Bottom** | **7–65 weeks** |
| **Darvas Box** | **4–6 weeks** `[2025]` (2020/2022 showed 4–5) |
| **Power Play** (high, tight flag) | **2–6 weeks** |

- The MTP frames base-reading as **three questions**: (1) the **long-term trend**, (2) the **current price action**, (3) the **low-risk entry point**.
- Confirms (does not change) the **time-compression** caution above — an over-fast right side (V-shape / no proper right-side development) is a "No"; a base with proper right-side development is the "Yes."

### 4.7 Leadership Profile — Fundamentals

The earnings/sales engine that drives a superperformance phase. Most superperformance is driven by an improvement in **earnings, revenue, and margins**, and this is usually measurable BEFORE the price advance (Ch 3; Ch 7). Fundamentals are a confirmation layer on the Stage-2 price structure (§4.1) — screen for "stocks in Stage 2 with strong earnings, positive surprises, and upward-revised estimates" (Ch 7; Fig 7.4). They are not a standalone trigger.

**Three earnings questions** (Ch 7): How much? (profitability) · How long? (sustainability) · How certain? (visibility). These are the factors that move price.

**1. Current-quarter EPS growth gate (year-over-year).** Tiered thresholds — use the band that fits the market regime:
- **Minimum gate:** **20–25% YoY** increase in the most recent **one, two, or three quarters**. "The greater the percentage increase, the better." (Ch 7)
- **Superperformance band:** really successful companies generally post **30–40% or more** during the superperformance phase. (Ch 7)
- **Bull-market bar (raise it):** in a bull market, demand **40–100% or more** in the most recent **two to three quarters**. (Ch 7)
- **"3-out-of-4-times" rule:** the very best performers show a meaningful YoY increase in the **most recent quarter** vs. the same quarter a year earlier — AND good gains in the past 2–3 quarters. Current quarterly earnings show the highest correlation with big price moves. (Ch 7)

**2. Streak.** Prefer **four, five, or six strong quarters in a row** — gives assurance the trend is real, not a one-off. (Ch 7)

**3. Earnings ACCELERATION** (the core engine). Look for YoY growth at an *increased rate sequentially, quarter to quarter* — growth larger than it was in the prior period. More than **90% of the biggest winners showed some form of earnings acceleration** before/during their huge moves (stated as a study finding). (Ch 7; Fig 7.7)
- Illustrative acceleration ladder (EXAMPLE pattern, NOT fixed thresholds): **10% → 30% → 50%**; or the Fig 7.7 ramp **−34% → +12% → +44% → +83% → +244% (est.)**. (Ch 7; Fig 7.7)
- **Acceleration yardstick (Fig 7.13):** gauge acceleration by comparing recent quarters against the company's **own 3-year and 5-year growth-rate baselines** — recent quarters running materially above those multi-year rates (a triple-digit recent quarter especially) confirm acceleration / a turnaround. (This is the general acceleration test; §4.11 applies the same 3-/5-year comparison to the Turnaround category.) (Ch 7; Fig 7.13)

**4. SALES (revenue) must confirm earnings.** Require the same characteristics on the top line: strong quarterly **sales growth AND acceleration**, sequentially across recent quarters. EPS growth not backed by sales growth is lower quality. New leaders not uncommonly show **triple-digit sales growth** in the most recent two-to-three-plus quarters. What accounts for superior performance: strong earnings backed by brisk sales, not accounting gimmickry. (Ch 7; Fig 7.7, Fig 7.9)

**5. Earnings SURPRISE (report vs. consensus).** Defined in §4.8 below; the screen here: hunt companies that BEAT the analyst consensus — and demand a **meaningful** beat (a significant event, not a penny-or-two token beat). "The bigger the surprise, the better." Avoid firms with negative surprises (they tend to disappoint again). (Ch 7)

**6. Estimate REVISIONS** (analysts raising — these ARE hard rules):
- **±5% threshold:** when estimates are revised **up ≥5%**, stocks tend to outperform; revised **down ≥5%** → below-average performance. (Ch 7)
- **30-day trend rule:** at minimum, want the **current fiscal year OR the next year's** estimate trending higher than **30 days earlier**; if **BOTH** are trending higher, even better. "The bigger the revisions, the better." Screen current-quarter AND current-fiscal-year estimates. (Ch 7; Fig 7.3)
- Soft disqualifier: lacking upward revisions does not by itself disqualify a stock, BUT **large downward revisions are a definite red flag**. (Ch 7)
- Note: upward revisions mechanically lower the P/E (denominator grows). (Ch 7)

**7. The "breakout year" (annual EPS).** Strong quarterly results must translate into **strong annual results** — one or two good quarters is not enough. Go back **two to four years or more** to a record year and check whether current annual EPS is breaking out above the established multi-year range — ideally also **taking out the prior peak**. A range-bound period followed by a clean upside break is a significant development. Also look forward: check the **upcoming one or two quarters and the next fiscal year** for continued acceleration. (Ch 7; Fig 7.11, Fig 7.12)
- *(EXAMPLE only, Fig 7.12: four years range-bound, then the breakout year prints +152% above the prior high — illustrative, not a threshold.)*

**8. Two-quarter smoothing of lumpy numbers.** Quarterly results are noisy; one non-accelerating quarter here or there may not matter. Smooth EPS and sales with a **two-quarter rolling average** over the past **four, six, or eight quarters**, and look back **one to two years**. The goal: a steadily improving smoothed trend. (Ch 7; Fig 7.10)

**9. DECELERATION = red flag.** Judge growth relative to the company's OWN prior rate. A drop from "upward of **50–60% or more**" down to **20–30%** is a *material deterioration* — even though 20–30% would be fine for a different company. A trend of results sliding lower for several quarters should raise suspicion; a growth stock that has run its price ahead of earnings can roll over hard once growth decelerates. (Ch 7; Fig 7.14)
- *(EXAMPLE, Fig 7.14: Dell's EPS growth ~80% → ~65% → 28% marked the end of its major move — illustrative, not a rule.)*

**10. MARGINS expanding.** Look for expanding **gross / operating / net** margins (detail and metrics in §4.8). A well-run growth company shows consistent improvement in operating AND net profit margins. (Ch 8) — see §4.8.

**Institutional wish-list summary** (Ch 7): (1) earnings surprises · (2) accelerating EPS and revenues · (3) expanding margins · (4) EPS breakout · (5) strong annual EPS change · (6) signs acceleration will continue.

> Apply this profile only on a stock that already passes the Trend Template (§4.2) and is in Stage 2 (§4.1). Combine with the Surprise/Drift mechanics and quality screens of §4.8 and the valuation context of §4.9.

---

### 4.8 Earnings Quality

The *source and durability* of earnings. High quality = earnings from core operations driven by sustainable revenue growth — not from one-time gains, cost-cutting alone, or accounting timing (Ch 8).

**The three profit drivers** (Fig 8.1): exactly three levers can raise earnings —
1. **Higher sales / volume** (sell more existing product, sell a new product, enter new markets),
2. **Higher prices / margins** (pricing power),
3. **Lower costs** (cut costs, improve productivity, shed losing operations).

- Sustainable growth REQUIRES revenue growth. Earnings that come **only from cost-cutting** ("productivity enhancements," plant closures, job cuts) "walk on short legs" — **low quality**, limited life span. (Ch 8; Fig 8.1)
- The winning combination: higher sales volume **+** higher prices **+** reduced costs simultaneously → drives higher EPS AND P/E multiple expansion. (Ch 8; Fig 8.1)
- Worst profile to avoid: limited pricing power + capital-intensive + low/pressured margins + heavy regulation/competition + commodity-sensitivity (Ch 8 names the airline industry as the example of this bad profile).

**The earnings SURPRISE** (the catalyst metric). *Earnings surprise* = reported EPS minus the consensus (average) of analysts' estimates; positive = beat, negative = miss. *Consensus estimate* = the average of all covering analysts' EPS estimates. The "cockroach effect": one good surprise tends to portend more, and peers in the same industry/sector may surprise too (screen the read-through). (Ch 7)

**Post-earnings drift (PED).** Price keeps drifting in the **direction of a significant surprise for weeks to MONTHS** after the announcement (large buyers must accumulate over time; the market does not instantly fully price it in). Consequence: even if you miss the first reaction, an entry can stay valid for months after a better-than-expected report. (Ch 7; Ch 8; Fig 8.4)

**Code 33** (the convergence screen): **three consecutive quarters of simultaneous acceleration in EPS, SALES, AND profit margins** ("hitting on all cylinders") — the potent precondition for superperformance. Sales acceleration alone is good (e.g., the illustrative 25% → 35% → 45% rate ramp), but sales acceleration PLUS simultaneous margin expansion grows earnings far faster than either alone. (Ch 8; Fig 8.10)
- *(Real-world EXAMPLE, Fig 8.11: Monster Beverage / Hansen Natural 2003–2005 EPS, sales, and margins all accelerating — illustrative annual-version of Code 33.)*

**Margins** (the metrics referenced from §4.7):
- **Gross margin** = how much more customers pay vs. the company's costs — reflects pricing power + cost control. Best improvement comes from **pricing power driven by strong demand**; one-quarter raw-material or competitor effects can distort it short-term. (Ch 8)
- **Net margin** = net income ÷ sales — all-in profitability per sales dollar. A falling net margin = less profit per sale; the most worrisome cause is **falling prices from declining customer demand** (vs. a temporary cost spike). A strong net margin vs. the **industry average** = competitive advantage. Want consistent improvement in operating AND net margins. (Ch 8)

**One-time / nonrecurring items — STRIP THEM.** Remove nonrecurring/nonoperating income (e.g., an asset or property sale outside the core business) before judging EPS — a reported gain can flip to a decline once stripped. Habitual one-time CHARGES are a red flag: a one-time charge that **forms a pattern / shows up over and over** ("habitual abusers") means you should seriously question earnings quality, even at the largest, most respected firms. (Ch 8)
- *(EXAMPLE method, Ch 8: reported $3.01 EPS (+25%) became adjusted $2.17 (−7%) once an $0.84 one-time gain was stripped — illustrative math, not a rule.)*

**Receivables + inventory rising ≥2× the rate of sales = "double-trouble" red flag.** Get inventory and receivables from the **10-Q (quarterly) and 10-K (annual)** filings.
- The absolute amount is not meaningful; the **trend vs. sales** is. Inventory should normally rise/fall in a pattern similar to sales.
- RED FLAG: inventory (especially **finished goods**, worst if highly depreciable) OR receivables growing much faster than sales → demand misjudged, product piling up, collection trouble.
- **Double-trouble:** when BOTH receivables AND inventories rise faster than sales — specifically **twice or more** the rate of sales without explanation — it often forecasts trouble. Check benign explanations first (new stores, new product line, longer credit terms, shipment delays). (Ch 8; Fig 8.9)
- *(EXAMPLE, Fig 8.9: inventories rising ~4× and receivables ~3× the rate of sales — illustrative of the danger pattern; the RULE is ≥2× without explanation.)*
- Bullish counterpart: a sudden buildup in **raw materials** can mean management expects business to pick up — confirm with subsequent sales acceleration.

**Verify the STORY behind a strong report (pre-buy diligence, Ch 8, pp.144–145).** Before buying on a strong report, confirm the good news comes from **durable conditions, not a one-time event**, by asking: (1) new products/services or a positive industry change? (2) is the company **gaining market share**? (3) what is it doing to grow revenue and **expand margins**? (4) what is it doing to **cut costs / lift productivity**? Earnings that pass these are higher-quality and more likely to continue.

**Discount LONG-TERM forecasts (Ch 8, p.153).** Take 1–2-year-out projections "with a grain of salt" — weight the **upcoming quarter + current fiscal year** ("what have you done for me lately"). **SPIN red flag:** an upbeat long-term outlook released **alongside bad near-term news** (often paired with a buyback to soften it) is *spin*, not genuine positive guidance.

**Guidance games — beating a freshly-lowered bar.** Management can warn of a problem → analysts cut estimates → company then "beats" the LOWERED consensus, manufacturing a hollow surprise. RED FLAG screen: if estimates were recently lowered due to downside guidance AND the company then "beat," be skeptical. (What to WANT instead: better-than-expected earnings PLUS positive forward guidance — raising the bar.) (Ch 8; Fig 8.6)

**Differential disclosure — great earnings but little tax paid.** A company saying one thing to shareholders (accrual accounting) and another to the SEC/IRS (cash basis). Test: compare tax footnotes; a big gap is a red flag. Corollary: **if a company reports great earnings but pays little in taxes, be skeptical.** (Ch 8)

**Judge a report by its PRICE REACTION — the "three reactions" (Ch 8, p.147).** No matter how good the headline looks, grade the stock's reaction in three steps: (1) **Initial response** — did it rally or sell off? A sell-off that slides again after a dead-cat bounce is bad; one that "roars back" is good. (2) **Subsequent resistance** — how well does it HOLD the gains / resist profit-taking? (3) **Resilience** — does it recover quickly and powerfully, or fail to rally? Want a strong reaction that holds, confirmed by additional buying on reasonable pullbacks. (Trust the price action, not the headline EPS.)

**The "discounted earnings" trap (Ch 8, pp.148–149).** A genuine beat can still SELL OFF if the market had already expected / priced an even bigger number — e.g. consensus $0.50, reported $0.55 (+$0.05) can disappoint if the street hoped for $0.07–$0.10 more. "You can judge the true perception only from the response of the share price." So a "good" report the market is rejecting (down hard on heavy volume) is a do-not-buy, not a bargain (cf. the differential-disclosure veto, §3.6.C.7).

> Channel-stuffing / revenue-shifting mechanics (booking revenue + receivables at shipment, "big bath" quarters) are the timing games these tells catch. The price-reaction rules above (three reactions + discounted-earnings trap) drive the buy/hold decision; the −15% failed-reaction *sell* signal lives in §3.6 (and §3.1).

**`[2020]` notes** (MTP S5):
- **"Code 3" / the Code 3 Matrix — the same concept as Code 33, now illustrated.** The MTP slide ("The 'Code 3' Matrix") scores **EPS, Revenue, and Net-Profit-Margin** each across recent quarters; "**look for Code 3 stocks**" = all three accelerating together. The grid (a 3×3 matrix producing cells 11/21/31 … 13/23/33) **strongly suggests** the "**33**" / "Code 3" name derives from the matrix's **top all-three-accelerating cell** — the best-supported reading of the previously-unexplained name (the slide shows the grid but does not in words equate "33" with the top cell). *(Largely closes the §1 glossary "editorial inference" note and the §5.3 open item.)* Worked figures on the slide: EPS +12% → +44% → +83%, Revenue +3% → +16% → +38%, Net margin 4.9% → 5.8% → 6.6% — all three accelerating across the quarters. **`[2021]` reconfirms:** the 2021 re-run shows the identical Code-3 Matrix (same example figures and the same 11…33 grid), a second independent rendering supporting the top-cell reading of the "Code 3 / 33" name (§5.3).
- **Return on Equity (ROE) quality cutoff:** as a management-quality check, **ROE ≈ 15–17%** is "a good cutoff for most stocks" — compare to peers in the same industry. (Added as point 7 of the MTP's "really great earnings report" checklist.)
- **The "Earnings Maturation Cycle"** *(2020 title-only; full diagram **confirmed in the 2021 re-run** `[2021]`)*: a named lifecycle curve depicting a company's earnings-growth RATE accelerating, peaking, then decelerating. The 2021 slide shows the arc explicitly — **"Value Stock" (Stage 1) → Positive Surprise → Positive-Surprise Models → Estimates Revised Up → EPS Momentum (Stages 2–3, the "Growth Stock" peak) → Loss of EPS Momentum → Negative Surprise → Negative-Surprise Models → Estimates Revised Down → "Value Stock" (Stage 4)**. Favor names whose acceleration is still early (the left/up side of the arc), the fundamental analogue of an early base count (§4.6). *(2020 shape was inferred from the title; 2021 confirms it verbatim.)*

---

### 4.9 P/E & Valuation Context

Minervini's verdict: by themselves, P/E ratios "rank among the most useless statistics on Wall Street." Concentrate on the **potential for earnings growth**, not on the multiple (Ch 4).

**A high P/E is NOT a reason to avoid a leader.**
- Do not cross a stock off the buy list because its P/E "seems too high" — study high-P/E names, especially with a new/exciting catalyst for explosive earnings (better still if misunderstood/underfollowed). (Ch 4)
- Many of the **biggest winners traded at more than 30–40× earnings BEFORE their largest advance.** The best growth stocks seldom trade at a low P/E. (Ch 4)
- Growth stocks normally trade at a premium: fast-growers run **3–4× the overall market multiple** (higher when growth is in favor). (Ch 4)
- There is **no magic P/E** for superperformance; it can start low or high. Money is usually lost not because the P/E was too high but because **earnings did not grow fast enough** to sustain expectations. A true leader is usually a better value than a low-P/E laggard despite the higher multiple. (Ch 4)

**P/E expansion (the benchmark).** On average, superperformance stocks' P/E expands **100–200% (≈2× to 3×)** from the **beginning to the end** of the major price move — driven by price outrunning earnings as the stock grows popular. (Ch 4; Fig 4.11)
- **Use #1 — rough target gauge:** take the **initial-purchase P/E × 2 to 3**, apply that expanded multiple to estimated future earnings for a best-case target *(EXAMPLE: buy at 20× → potential ~40–60×)*. Valid for a dynamic leader in a bull market. (Ch 4)
- **Use #2 — topping warning (selling, see §3.6):** once the P/E has expanded ~2×, and ESPECIALLY ~2.5×–3× or more from the start (over a 12–24-month run), the move may be in its later stage / too widely recognized. The sell/reduce trigger is **decelerating earnings growth + price weakness**, not the high P/E alone.
- *(P/E can also CONTRACT or stay flat while price soars if earnings outrun price — an "expensive" entry becomes cheap, e.g., Fig 4.6/4.7 — illustrative.)*

**Very low P/E is a RED FLAG, not value.**
- Be very reluctant to buy an **excessively low P/E (≈3, 4, or 5× earnings, or far below the industry multiple)**, ESPECIALLY at or near a **52-week low** — it can signal a fundamental problem, deteriorating earnings, or pending bankruptcy. (Ch 4)
- A severe decline that pushes the P/E to the low end of its historical range usually **anticipates a poor earnings report** (bottom-fishing trap); prefer a strong-earnings stock at a higher P/E over a troubled one at a low P/E. (Ch 4)
- **Broken-leader syndrome:** do NOT buy a former highflier after it has topped and broken down (typically a Stage-4 decline, §4.1) just because it "looks like a bargain" / "is down 70%" / "only 20× with a 40% growth rate." A topped leader's price is discounting a coming growth slowdown. (Ch 4)
- The "**cheap trap**": don't buy solely because a stock is cheap — once owned, a falling price makes it look ever cheaper and hard to sell. Look for leaders, not bargains. (Ch 4)

**PEG context.** **PEG = P/E ÷ projected next-year EPS growth rate** *(EXAMPLE: P/E 20 with 40% growth → PEG 0.5)*. Theory: PEG < 1 may be undervalued, PEG > 1 overvalued; the farther from 1, the stronger the signal. LIMITATION: do NOT build a buy list from PEG — it excludes the most dynamic high- and low-P/E names and makes broken-leader plays look attractive. (Ch 4)

**The "75%-off" value-trap fallacy.** A stock down from $100 to $25 is "75% off," but your downside from $25 is still **100% of your remaining capital** — from $25 it can fall another 75% to ~$6.26, then another 75% again. Your money in a stock is ALWAYS 100% at risk; "% off the high" is not a margin of safety. (Ch 4)

> P/E is a sentiment gauge (high = high expectations), not a price predictor — "value doesn't move stock prices; people do, by placing buy orders." Use the expansion benchmark for sizing the opportunity (§4.7 growth × this multiple) and as a late-stage topping cue for Selling (§3.6).

**`[2017]` notes** (2017 §5, §9):
- **The "cheap trap":** never buy a stock just because it has fallen / "looks cheap." A topped leader is actually *expensive* — its **P/E soars after a big decline** as negative earnings comparisons and losses appear; the cheaper it gets, the harder it is to sell (you bought it for being cheap). Generalizes the 2013 very-low-P/E red flag + the 75%-off fallacy.
- **P/E expansion operationalized as a sell cue:** note the P/E at purchase (or at the first base if bought late) and compare to the P/E at a late-stage base (base 4–5). If it has **expanded to ≥2×** (e.g. 20 → 40) AND the base count is late-stage, start hunting the §3.6 climax sell signals. **Absolute P/E is irrelevant** — only the *expansion ratio* combined with late base count matters.
- **P/E expansion as a BUY-side guard (not just a sell cue):** before INITIATING a new position, count the bases since the last correction — if the stock is at a **late-stage base (4–5) AND its P/E has already doubled/tripled** from the move's start, be very cautious about buying; there may be little upside left. (2017 §9)

**`[2020]` note** (MTP S20, "PE Expansion"): confirms the expansion benchmark above with leader examples — a great leader's multiple can run from ~**20× to 40×, 50×, even 65×** over a multi-year advance (e.g. Home Depot 1988–2008) as popularity outruns earnings. Reinforces "don't reject a leader on a high/expanding P/E" (the buy-side reading) while the §3.6 / Use #2 topping cue (≥2×–3× expansion + decelerating growth + price weakness) governs the sell side. (No change to the 100–200% / 2×–3× benchmark.)

### 4.10 Relative Strength & Market Leadership

Two distinct tools share the name "relative strength": the IBD **RS Rank** (a ranking number) and the **RS Line** (a divergence chart). Use both.

**IBD Relative Strength (RS) Rank**
- A 1–99 percentile rank of a stock's price performance vs. all stocks. Minimum **>= 70**; preferred band **80s–90s** (this is Trend Template criterion #8 — see §4.2; defined once there, applied everywhere).
- Principle: **"Buy strength, not weakness."** True leaders always show *improving* relative strength, especially during a market correction. (Ch 9, p. 185)
- **Correction-depth quality (Ch 9, pp.185–186):** a healthy leader's peak-to-low correction stays within **25–35%** (as much as **50%** only in a severe bear decline — less is better); a stock that corrected **>50%** is generally too damaged — heavy **overhead supply** makes it prone to **fail as it reaches or slightly surpasses a new high**. (This leader-selection depth screen is distinct from the Ch 10 base-pattern depth rule in §4.6.)

**The RS Line (leaders bottom first / divergence read)**
- The RS Line plots a stock's price relative to a benchmark index. A **rising/improving RS line while the market falls** flags a forming leader.
- Bottoming divergence: at a market bottom the best stocks make their lows **ahead** of the absolute low in the indexes — as the indexes make **lower lows** on the last leg down, leaders make **higher lows**. A series of higher lows in a stock while the averages make lower lows is the tip-off that a potential leader is forming. (Ch 9, pp. 168, 179; Fig 9.11 LL vs Nasdaq 2012)
- Leaders break out and into new high ground **first** — "days, weeks, or even months" before the indexes turn. (Ch 9, p. 161; Figs 9.1, 9.3, 9.4)
- **Own-earnings-cycle filter (the WHY behind RS divergence, Ch 9, pp.172–173):** a stock that holds up during a bear correction is usually in its **own earnings up-cycle** (accelerating earnings/sales, a new product, a favorable industry change) — these decline less and "blast off" when the market turns. Conversely a stock in its **own bearish earnings cycle** can lag even a strong market — **avoid it** despite a tempting setup.

**Identifying the true leaders (timing window)**
- The true market leaders are the stocks that hold up best and rally into **new high ground** during the **first 4 to 8 weeks** of a new bull market. (Ch 9, p. 167)
- Hunt on the **new-52-week-high list**, NOT the new-52-week-low list (stay away from the low list and all laggards). (Ch 9, p. 185)
- Also watch stocks that held up in the decline and are within **5 to 15 percent** of a new 52-week high. (Ch 9, p. 185)
- Watch for **divergent price behavior** during a general-market decline — a growing set of stocks holding up / making higher lows tells you where the next leaders will emerge. Update the watch list frequently: **weed out** names that give up too much price AND **add** new divergent/resilient candidates via **"forced displacement,"** so each fresh decline auto-upgrades the list toward the strongest names. (Ch 9, pp. 176, 185)

**Which to buy and how many**
- **Buy the strongest first**, in **order of breakout** — the best names burst from a proper buy point into new high ground first; let market strength, not opinion, direct capital. (Ch 9, p. 178)
- **Own the top 1, 2, or 3 names** in an industry by relative performance and earnings power. (Ch 9, pp. 176; cross-ref §4.11)

**Cross-cycle caution**
- The leaders of one cycle are rarely the leaders of the next — **fewer than ~25 percent** of one cycle's market leaders lead the next; expect unfamiliar names and generally abandon prior-cycle leaders as buy candidates. (Ch 9, pp. 184–186)
- Caveat: leadership stocks/sectors that began emerging **near the end** of the prior bull cycle can, in some cases, lead the subsequent bull market. (Ch 9, pp. 184–185)

**`[2017]` additions** (2017 §1, §7):
- **The MVP indicator (a.k.a. "ants"; David Ryan)** — an early-momentum leadership tell over a **15-day window**: **M**omentum = up **12 of 15 days**; **V**olume = **+25% or more** across the 15 days; **P**rice = **+20% or more** across the 15 days (bigger move + stronger volume = better). Usage: do NOT buy on MVP if the stock is *extended*; it IS buyable when the 15-day window begins **near a base bottom** (not extended). MVP also helps decide to HOLD; the same cluster late-stage flips to a *sell* tell (§3.6).
- **Use relative strength as a combination, not a single number:** the IBD **RS rank (1–99)** alone is insufficient — combine (1) the rank, (2) the **RS line** (stock vs. market), and (3) the stock's technical action. Buy when the stock outperforms the market AND emerges from a sound base.
- **RS-line tip-off:** when the **RS line makes a NEW HIGH before the price breaks out** of its base, it flags institutional accumulation and that the stock is destined to emerge (AMZN/eBay/NFLX examples).
- **Youth filter:** most big winners went public **within the last 8–10 years** — embrace new/unfamiliar names rather than over-owned mature ones.
- **Buy in order of breakout / strength** (no favorites; first-mover advantage) — see §3.1.

---

### 4.11 Industry Groups, Catalysts & Categories

**Leading groups (breadth + concentration)**
- **3 or 4, up to 8–10, industry groups/subgroups** lead a new bull market. (Ch 6, p. 110)
- Historically **more than 60 percent** of superperformance stocks were part of an industry-group advance — the reason to trade within leading groups. (Ch 6, p. 113)
- Find leading groups **bottom-up**: track the **52-week new-high list**; a group with a healthy number of names hitting new highs early in a bull move is often a leader. Let individual stocks lead you into the group. (Ch 6, pp. 110–111)
- Track the **top 2–3 names** per group, ranked by earnings, sales, margins, and relative price strength. (Ch 6, pp. 101–102)
- Portfolio construction: hold the **best companies in the top 4 or 5 sectors**. (Ch 6, p. 110)
  - UNCERTAIN — "top 4–5 sectors" (portfolio) and "3–4 to 8–10 leading groups" (breadth) are stated at different granularity (sector vs. industry group) and are not reconciled in the source. (Ch 6, p. 110)
- Groups historically producing the **most** superperformers: (1) consumer/retail, (2) technology / computer / software & related, (3) drugs, medical & biotech, (4) leisure/entertainment. (Ch 6, p. 111)

**Industry life cycle — avoid saturation** (Ch 6, pp.114–115; Figs 6.4/6.5)
- Every innovation runs **penetration → high growth → SATURATION**. Once all likely buyers have access, the sector becomes a slow-growth **replacement market**: margins decline, the **number of firms falls** in a shakeout, consolidation and bankruptcies follow (autos 1920s, TV 1950s, PCs 1980s–90s; modern high-tech cycles run faster than older industries). A **saturated / post-shakeout industry ceases to be a growth industry → reject it as a superperformance play.** Prefer industries still in the penetration / high-growth phase.

**Catalysts (what ignites the move)**
- A catalyst is a material change that ignites earnings/price. Hunt for: **new product**, **new category**, **new management/CEO**, **new/changed industry conditions**, **deregulation**, **proprietary technology**, newly awarded contracts, positive regulatory/policy change, and **FDA approval** of a drug/device (pharma/biotech). (Ch 6, pp. 106, 112; Ch 9, p. 168)
- "A new product can bring new life to a dormant company." *(Illustrative example: Apple's iPod/iTunes/iPhone turnaround — Fig 6.2 sales/income timeline; figures are Apple-specific examples.)* (Ch 6, p. 106)

**Categories (every candidate falls into one)**
Six categories: (1) Market Leaders, (2) Top Competitors, (3) Institutional Favorites, (4) Turnarounds, (5) Cyclicals, (6) Past Leaders/Laggards. Key numbers per category:

- **Market Leaders (preferred):** earnings growth generally **20 percent or more**; many average **35–45 percent** during their best 5- or 10-year stretch (some triple-digit at peak). #1–#3 in sales/earnings and gaining share. Invest **early** in the growth phase when profits are accelerating; do not reject on high P/E alone (see §4.9). Qualify a leader with two questions — **competitive advantage?** and **scalable business model?** — confirmed by signs of good management: a **good balance sheet, expanding margins, high return on equity (ROE), and reasonable debt.** (Ch 6, pp. 96–98)
- **Institutional Favorites:** earnings growth only **low-to-middle teens** — too big/sluggish to produce a superperformance move; generally de-prioritize (exception: a quality name beaten down by a severe correction can advance coming out of it). (Ch 6, p. 102)
- **Turnarounds:** require strong results in the most recent **2–3 quarters** — at least **2 quarters** of strong earnings increases, OR **1 quarter** lifting trailing-12-month EPS to **near or above its old peak**; want recent growth **dramatically accelerating** vs. the 3- and 5-year rate (often **~100 percent or more** in the most recent two or three quarters on easy comparisons). Watch the balance sheet: **bank debt is the worst kind** (less favorable than bond debt); assess cash/burn rate and debt runway. (Ch 6, pp. 104–105)
- **Cyclicals:** an **inverse P/E cycle** — a **high P/E** signals the stock is poised to rally, a **low P/E** signals it is near a top. Top of cycle: earnings up, dividends rising, **P/E low**, news good. Bottom of cycle: earnings falling, dividends cut, **P/E high**, news bad. Inventories and supply/demand are the key timing variables. (Ch 6, pp. 107–108)
- **Cookie-cutter (store-rollout) metrics:** same-store sales (comps) should rise each quarter — **10 percent or more is healthy**; **25–30 percent or more is unsustainable** long term. Expansion red flag: opening **more than 100 stores per year** is hard to maintain. Quality checks: **franchised** earnings are lower-quality / less stable than company-owned-store earnings (a high franchised-opening mix raises store-failure / disappointment risk); require a proven rollout track record across **diverse geographies** (Northeast / South / Midwest / international) before trusting scalability. (Ch 6, pp. 99–100)
- **Laggards (AVOID):** same-group stocks with inferior price/earnings/sales performance; do not be tempted by a low P/E — there is always a reason for it. (Ch 6, p. 108)

**Group-top warning**
- "When the leader sneezes, the group can get a cold" — if a key top-2-or-3 name in a hot group **tops or breaks down after a big advance**, treat it as a warning the whole group (and its suppliers/customers) may weaken. (Ch 6, pp. 113–114)

**`[2020]` notes** (MTP S2):
- **Catalyst taxonomy (5 named types)** — organizes the flat catalyst hunt-list above into: **(1) Totally New Category**, **(2) Breakthroughs & Disruptive Technologies**, **(3) New Industry Conditions**, **(4) Industry Spin-Off Effect** (a leader's success lifts suppliers/peers/adjacent names), **(5) Superior Solutions** (a materially better product/service taking share). Use as the checklist when verifying the §3.1 "catalyst" element.
- **The "Commodity Play" (a bullish cyclical sub-case):** when **commodity prices rise rapidly** against **inventory stockpiled at lower cost**, the result is **margin expansion** — a genuine catalyst within the cyclical category (complements the §4.11 cyclical inverse-P/E read and the §4.8 raw-material-buildup bullish tell). (Distinct from commodity-sensitivity as a *worst-profile* trait in §4.7/§4.8 — there the company is a price-taker; here rising prices + cheap inventory work in its favor.)

---

### 4.12 General Market Direction & Timing

**Where superperformance begins**
- **More than 90 percent** of superperformance stocks emerge as the market comes **out of a correction or bear market**. Build the watch list while the market is down so you are ready when it turns up. (Ch 9, p. 164)

**Confirming a new uptrend (buy-side)**
- The **new-52-week-high list outpaces the new-52-week-low list** and begins to expand significantly. (Ch 9, pp. 164–165)
- **Up** days/weeks come on **higher** volume; **down** days/pullbacks come on **lower** volume (accumulation, not distribution). (Ch 9, p. 164)
- Early-bull leaders typically **advance ~15–20% then rest**, pulling back only **~5–10%** during the rest before resuming; most leaders should hold their ground (a few breakouts will fail). (Descriptive behavior, not a mechanical trigger.) (Ch 9, p. 169)
- *Illustrative confirmation example only (NOT a fixed threshold):* an up/down-volume confluence of roughly **9:1 on the Nasdaq and 21:1 on the NYSE** is cited at a real bottom as supporting evidence. Capture as an example, not a hard rule. (Ch 9, p. 178 — flagged UNCERTAIN as a numeric threshold)
- Early-bull "lockout" behavior: expect multiple waves of new highs; initial-leg pullbacks are minimal, typically **3 to 5 percent** peak-to-trough — do not wait for a deep pullback that rarely comes. (Ch 9, p. 164)

**"Too early" tells -> raise cash**
- **Repeated stop-outs** while buying early-bottom leaders = you are too early. (Ch 9, p. 165)
- **Rising volume on DOWN days vs. up days** (distribution) in the averages during a supposed bottom = too early -> revert to **cash** for protection. (Ch 9, p. 170)

**Topping / distribution (sell-side timing)**
- Leaders lead on the downside too: after an extended bull move, when leading names in leading groups start to **falter/buckle**, treat it as a danger signal of a market (or sector) top. (Ch 9, p. 183)
- **Down days on increasing volume (distribution)** building in the averages, and money **rotating into laggards/defensives** (drugs, tobacco, utilities, food) while indexes hold up "on the backs of the stragglers," warn the rally is in its late stage. (Ch 9, pp. 161–162, 170, 184)
- Watch the averages for distribution per the bottoming checklist's inverse: if down days carry increasing volume vs. up days, the move is suspect. (Ch 9, p. 170)

**The Lockout Rally** (Ch 9, p.164; reworded 2017 §7): early in a new bull market, expect waves of stocks breaking into new high ground with only **a few-percent** general-market pullbacks — demand is so strong the dip pullback-buyers wait for never comes (they get "locked out"). An overbought tape that the indexes *ignore*, alongside an expanding list of buyable breakouts, is **strength** — raise exposure rather than waiting for a deep pullback. (This is the 2013 "lockout" concept from the 3–5% initial-leg-pullback note above; `[2017]` only reworded the band to "a few percent.")

*Note: shorting is mentioned only briefly for Stage-4 downtrends (a valuation-upgraded broken stock can be a short candidate, §4.1/§4.2 context). This method is long-only in practice — no bearish mirror is built.*
---
# 5. Strategy Evolution

This category now has **six source sessions** of the same SEPA method. **None of 2017, 2020, 2021, 2022, or 2025 adds a new strategy** — each re-teaches and confirms the 2013 framework and refines it. Per the conflict rule, where a value differs the **latest session is primary** (2025 > 2022 > 2021 > 2020 > 2017 > 2013) and the older value is recorded below; none are hard contradictions (all are tightenings or added detail). **2021 changed no value** — it re-runs the 2020 workshop verbatim. **2022 changes one value** — the staggered-stop "Uncle Point" ceiling 10% → **10–12%** (§5.1). **2025 changes two values** — the typical per-trade equity-risk band 1.25–2.5% → **1.00–1.50% (2.50% max)** and the **Darvas-Box base-duration window 4–5 → 4–6 weeks** (§5.1) — plus a re-frame (renames "Making the Turn" the **"Bottom Fishing Pivot"**); it otherwise re-states the 2020–2022 numbers verbatim (again with co-instructor Mark Ritchie II). See §5.5 (2022) and §5.6 (2025).

| Session | Source | Role |
|---|---|---|
| **2013** | _Trade Like a Stock Market Wizard_ | Introduced all §3 strategies + all §4 shared concepts + the §2 risk core. |
| **2017** | _Think & Trade Like a Champion_ | Refinement: confirms 2013; deepens risk, position sizing, trade planning, self-measurement, and selling discipline (tagged `[2017]` throughout). |
| **2020** | _Master Trader Program (MTP) Superperformance Workshop_ (with David Ryan) | Refinement: confirms 2013/2017; adds the **operational layer** — daily routine (§2.10), post-trade grading & the 8 Keys (§2.11), named Trend-Template screeners (§4.2), per-pattern base-duration windows (§4.6), the Code-3 Matrix / ROE / Earnings-Maturation-Cycle (§4.8), the catalyst taxonomy & commodity play (§4.11), and quantified risk tables — stop bands, the breakeven win-rate table, the opportunity-cost table (§2.2–§2.5). Tagged `[2020]` throughout. |
| **2021** | _Master Trader Program Superperformance Workshop_ (re-run) | **Confirming re-run** of the 2020 workshop — same curriculum, same instructors, same examples; **no new strategy, no value changes**. Deltas vs 2020 are re-framings + one resolved uncertainty: §4 retitled "The Stock Maturation Cycle" (§4.2); the **Earnings Maturation Cycle** diagram now shown (was title-only) → §4.8 upgraded to confirmed; the **Code-3 Matrix** and **RBAF** worksheet re-shown (reconfirm Code-33 §5.3 and RBAF §2.9). Tagged `[2021]`. See §5.4. |
| **2022** | _Master Trader Program Superperformance Workshop_ (re-run, with **Mark Ritchie II**) | **Confirming re-run** — third rendering of the same two-volume workshop (co-instructor now Mark Ritchie II, not David Ryan); **no new strategy**. Every load-bearing table read identically (Position-Sizing Guidelines, Report Card 5/20/60/15·70/15/15·65/20/15, 8 Keys, Code-3 Matrix, base-duration windows, the options implied-move tool's worked example). **One numeric refinement:** the staggered-stop **"Uncle Point" ceiling 10% → 10–12%** (§2.2; §5.1), with the alt blend re-illustrated 4%+12%≈8%. Tagged `[2022]`. See §5.5. |
| **2025** | _Master Trader Program Superperformance Workshop_ Course Workbook (re-run, with **Mark Ritchie II**) | **Confirming re-run** — sixth rendering of the same two-volume workshop (co-instructor Mark Ritchie II); **no new strategy**. Every load-bearing table read identically (stop bands + staggered "Uncle Point" 10–12%, Report Card 5/20/60/15·70/15/15·65/20/15, 8 Keys, Code-3 Matrix, RBAF, the options implied-move tool's (3.50+2.75)/162 ≈ 3.86% example). **Two numeric refinements:** the typical per-trade equity-risk band **1.25–2.5% → 1.00–1.50% (2.50% max)** (1.25% stays the worked ROTE midpoint; §2.4) and the **Darvas-Box base-duration window 4–5 → 4–6 weeks** (§4.6). **One re-frame:** "Making the Turn" renamed the **"Bottom Fishing Pivot"** (§3.3). Tagged `[2025]`. See §5.6. |

## 5.1 Value changes (conflict rule applied — latest session primary)

> Column shows the value as of each session; **—** = unchanged that session. Primary = the rightmost non-dash value. **2021 introduced no value change** — its column is **—** throughout (the 2021 re-run re-states the 2020 numbers verbatim). **2022 introduces exactly one value change** — the staggered-stop "Uncle Point" ceiling 10% → **10–12%**; every other 2022 cell is **—**. **2025 introduces two value changes** — the typical per-trade equity-risk band 1.25–2.5% → **1.00–1.50% (2.50% max)** and the **Darvas-Box base-duration window 4–5 → 4–6 weeks** (its own row below); every other 2025 cell is **—** (re-states the 2020–2022 numbers verbatim).

| Item | 2013 | 2017 | 2020 | 2021 | 2022 | 2025 | Section |
|---|---|---|---|---|---|---|---|
| Target average loss | **6–7%** | **5–6%** (realized ≈ 4–5%) | — (confirms 5–6%; stop bands: ≤3% tight / 4–6% avg / 7–8% max / 10% staggered "Uncle Point") | — | — | — (confirms 5–6%) | §2.2 |
| Staggered-stop "Uncle Point" ceiling | — (no staggered concept) | staggered/bracketed stops introduced (thirds 3/5/8% ≈5%; 4%+8% ≈6%) | **10%** — named "Uncle Point", **staggered-only** deepest leg (blends 6%+10%≈8%, 4%+8%≈6%) | — | **10–12%** (alt blend re-illustrated 4%+12%≈8%; 10% hard cap & 7–8% single-stop max unchanged) | — (confirms 10–12%; blends 6%+10%≈8%, 4%+12%≈8%) | §2.2 |
| Difficult-market loss-cut | (cut "shorter," unquantified) | **5–6%** (vs normal 7–8%) | tightened to **4–5%** (vs normal 7–8%) | — | — | — (confirms 4–5%) | §2.2 |
| Working position count | **4–6** names | **4–8** (top 4–5 at 20–25% each); 16–20 for large pro books | — (confirms 4–5 big / 8–16 max) | — | — | — (confirms 4–5 big / 8–16 max, "including partials") | §2.5 |
| Per-trade equity risk | implicit (scale-in ≈0.5%) | **explicit 1.25–2.5% of equity** (new) | — (confirms 1.25–2.5%) | — | — | **1.00–1.50% typical (2.50% max)** — band tightened; 1.25% stays the worked ROTE midpoint | §2.4 |
| Single-position ceiling | ~5% each / never >20 names | adds explicit **never >50%** of account | — (confirms never >50%) | — | — (confirms; "overnight" wording) | — (confirms never >50% overnight) | §2.5 |
| Trend Template — 52-week-low floor | price **≥ 30%** above 52wL | eased to **≥ 25%** above 52wL | — | — | — | — | §4.2 |
| Power Play — consolidation depth & min duration | flat **20–25%**; min ~12 days | **≤ 20%** (lower-priced up to 25%); min **10–12 days** | — (base-window 2–6 wk) | — | — | — | §3.4 / §4.6 |
| Base — market-relative correction filter | avoid > **2×–3×** the market's decline | floor raised to > **2.5×–3×** | — | — | — | — | §4.6 |
| Darvas-Box base-duration window | — | — | **4–5 weeks** (introduced) | — | — (confirms 4–5) | **4–6 weeks** | §4.6 |
| Trend Template criterion #3 | 200-day up ≥1 mo (pref 4–5 mo, parenthetical) | hardens **preferably 4–5 months or longer**; adds RS-line uptrend ≥6 wk (pref ≥13 wk) | adds **"1-Month" screener** for early turns/Power Plays | — | — | — | §4.2 |
| Trend Template criterion #5 (price > 50-day) | required | "as the stock is coming out of a base" | adds explicit **"Low Cheat" exception** (waived for low-cheat entries) | — | — | — | §4.2 |
| Pivot "don't chase" | buy ≤ a few % above pivot | adds **"extended" = >10% above last consolidation → don't buy** | — | — | — | — | §3.2 / §4.3 |
| Give-back of leaders' gains | ~⅓ give back all gains, avg decline 50–70% | sharpened into the **50/80 Rule** (avg decline >70%) | — | — | — | — (50/80 Rule re-shown with 2020s leaders) | §3.6 / §4.9 |
| Close below 20-day soon after breakout | judgment-call warning (Ch 10) | quantified: ≈**halves** success (still subordinate to the stop) | when **below cost** soon after a proper-base BO → **reduce shares** (active directive) | — | — | — | §3.6 |

## 5.2 Per-strategy / per-component evolution (Introduced 2013 → 2017 adds → 2020 adds → 2021 confirms → 2022 confirms → 2025 confirms)

- **`sepa`** — *2013:* 5 elements, 4-stage funnel, probability convergence; tennis-ball action + squat (Ch 10). *2017 adds:* the post-entry **confirmation framework** — **quantified** follow-through count (3 of 4 / 6 of 8 / 7–8 in a row), the **good-closes** criterion, the 5-point winner checklist (tennis-ball + squat are *re-named/quantified*, not new) — plus buy **in order of breakout** and **velocity trades**. *2020 adds:* the **"5 Key Areas"** re-framing, the **3 objectives / Selection-Execution-Management** process triad, and the named **"Bullish Breakout Criteria"** confirmation set (+20% / 12 of 15 days / elevated volume). (§3.1)
- **`vcp`** — *2013:* 2–6 Ts (typ 2–4), each ~half prior, volume dry-up, pivot, "be the last weak holder" (Ch 10). *2017 adds:* the "wet-towel" mechanic, VCP forms after a 30–50%+ prior advance, **"extended" >10%** rule, **40–50% pull-back-to-breakout** is normal, **double bottom** base (the "last weak holder" rule is only *reinforced*, not new). *2020 adds:* the **Failure/Pivot Reset** re-entry pairing (a failed pivot can re-form for a fresh buy) and per-pattern base-duration windows (§4.6). (§3.2)
- **`cheat_3c`** — *2013:* A→B→C→D turn, low/mid/high cheat, 5–10% pause. *2017 adds:* explicit **low/middle/upper-third** framing + the **low cheat** as a deliberate riskier/higher-reward scale-in start. *2020 adds:* the **Trend-Template criterion-5 (price > 50-day) "Low Cheat" exception** + the **"5 Month Wide" screener** that drops the 50-day/RS requirement to hunt low-cheat areas (§4.2); Cup-Cheat/Low-Cheat base window **6–52 wk** (§4.6). (§3.3)
- **`power_play`** — *2013:* +100% in <8 wk, ≤20–25% consolidation, tight ≤10% / VCP mandatory. *2017 refines:* consolidation tightened to **≤20%** (lower-priced up to 25%), min duration **10–12 days**; adds the **late-stage-base disqualifier** ("the best were quiet in Stage 1") + the "tight weekly closes over 3–6 weeks" tell. *2020 adds:* base window **2–6 weeks** (§4.6) + the **"1-Month" Trend-Template screener** to surface Power-Play setups (§4.2). (§3.4)
- **`primary_base`** — *2013:* first buyable base, ≥3–5 wk, ≤25–35%. *2017 adds:* the **low-cheat IPO variant** (post-IPO base ≥~10 days; hold above IPO price; gap-up-then-low-volume-fill entry). *2020 adds:* the **"95% Club" study** (≈80% of big winners were IPOs within the prior 10 years; small float <30M shares) reinforcing the young-leader/IPO profile (§4.1). (§3.5)
- **`selling`** — *2013:* defensive (initial stop, material-change tell, Stage-3/4 exit) + offensive (into strength, 3× risk → breakeven, P/E 2–3× cue). *2017 adds (heaviest reinforcement):* **50/80 Rule**, post-breakout **violations checklist**, quantified **climax-top checklist** (25–50%/1–3 wk; ≥70% up days/7–15 d; largest up day/widest spread/exhaustion gaps), reversal-on-volume signals, live mechanics (**breakeven-or-better 50-day-MA trail / free roll / back stop / sell-half**), dead-money rotation, hold 25–50% of leaders, **differential-disclosure veto**, early-stage exception. *2020 adds (§3.6.E):* the consolidated **13-signal "Sell Alerts"** list, **2nd/3rd-exhaustion-gap** and **new-high-on-low-volume-from-a-late-(4th–6th)-stage-base** tells, the **full-retracement** & **failed-natural-reaction** violations, the **"close below 20-day below cost → reduce shares"** directive, and the **Ledge / Failure-Reset** failure-and-re-entry vocabulary. (§3.6)
- **Shared (§4):** *2017 adds* stage labels + serial gapper + "never buy a falling stock" (§4.1); RS-line uptrend duration (§4.2); healthy-market pivot conditionality + extended rule (§4.3); footprint Time = days/weeks (§4.4); **20-day-MA violation quantified (≈halves success)** + charts-as-filter (§4.5); **double bottom**, overhead-supply mechanism, **base-count sell tiers (1–2 buy / 3–4 trade / 5–6 sell)** (§4.6); the **cheap trap** + P/E-expansion operationalized (§4.9); the **MVP indicator**, RS 3-input combination, RS-line-new-high tip-off, 8–10-year youth filter (§4.10); a **reworded Lockout Rally** band (2013 concept — "a few percent" vs 2013's 3–5%, §4.12). *2020 adds:* **Transition Criteria** + the **"95% Club" study** + the **98%-of-gains-in-Stage-2** stat (§4.1); the **named Trend-Template screeners** + criterion-5 Low-Cheat exception (§4.2); the **pivot pre-alert** + the **Ledge** failed-pivot pattern (§4.3); **per-pattern base-duration windows** (§4.6); the **Code-3 Matrix** (clarifies the Code-33 name), **ROE 15–17% cutoff**, **Earnings Maturation Cycle** (§4.8); P/E-expansion leader examples to 40–65× (§4.9); the **catalyst taxonomy (5 types)** + the **Commodity Play** (§4.11).
- **Global risk (§2):** *2017 adds* explicit position sizing (1.25–2.5% equity, "backing into risk", Add-and-Reduce, position-doubling), no-mental-stops + staggered stops + ATR stance (§2.2), the expectancy formula + Trading Triangle + TBA/RBA + R-multiples (§2.3), the new **§2.8 Trading Plan & Contingency Playbook** and **§2.9 Know Your Numbers (self-measurement)**. *2020 adds:* the **stop-placement bands** + **"try not to choke off the trade"** tolerance + the **options-implied-move** tool (§2.2), the **win-rate/risk-reward breakeven table** (§2.3), the **opportunity-cost concentration table** (§2.5), the **difficult-market cut tightened to 4–5%** (§2.2; §5.1), the **5-step feedback loop** + two pre-decision questions (§2.9), and the new **§2.10 Daily Routine** and **§2.11 Post-Analysis / Report Card / 8 Keys**. *2021 confirms:* every risk table re-shown verbatim — Position-Sizing Guidelines (1.25–2.5% risk / 8–10% max stop / avg loss 5–6% / never >50% / optimal 20–25% / 4–5 big, 8–16 max), the Report Card (5/20/60/15 · 70/15/15 · 65/20/15), the 8 Keys, the Trading Triangle, the "Building in Failure" / "Not All Ratios Are Created Equal" expectancy lessons, and the **RBAF** worksheet (§2.9). No value change.
- **`[2021]` (MTP re-run — all six strategies + shared §4 confirmed):** no per-strategy rule changed. The re-run's only doc-relevant deltas are shared/§4 items — the §4 trend block retitled **"The Stock Maturation Cycle"** (§4.2), the **Earnings Maturation Cycle** diagram now confirmed (§4.8), and the **Code-3 Matrix** reconfirmed (§4.8/§5.3). See §5.4.
- **`[2022]` (MTP re-run with Mark Ritchie II — all six strategies + shared §4 confirmed):** no per-strategy rule changed; no §3 setup, §4 shared concept, or risk table differs from 2020/2021 (Position-Sizing Guidelines, Report Card, 8 Keys, Code-3 Matrix, base-duration windows, the options implied-move tool all read identically). The **only** doc-relevant delta is one risk-table refinement — the staggered-stop **"Uncle Point" ceiling 10% → 10–12%** (§2.2; §5.1). See §5.5.
- **`[2025]` (MTP Course Workbook re-run with Mark Ritchie II — all six strategies + shared §4 confirmed):** no per-strategy rule changed. No §3 setup or §4 shared concept differs from 2020–2022 — the Trend Template, VCP/3-C/Power-Play/Primary-Base setups, the Selling discipline, the Code-3 Matrix, the Report Card (5/20/60/15·70/15/15·65/20/15), the 8 Keys, the RBAF worksheet, and the options implied-move tool all read identically. The doc-relevant deltas are two risk/base refinements + one re-frame: the typical per-trade equity-risk band **1.25–2.5% → 1.00–1.50% (2.50% max)** (§2.4; 1.25% stays the worked ROTE midpoint), the **Darvas-Box base-duration window 4–5 → 4–6 weeks** (§4.6), and the **"Making the Turn" entry renamed the "Bottom Fishing Pivot"** (§3.3 — a re-frame, affecting `cheat_3c` only by name). See §5.6.

## 5.3 Open / UNCERTAIN items (status after 2025)

- **Code 33 name — LARGELY RESOLVED `[2020]`, reconfirmed `[2021]`, `[2022]`, and `[2025]`.** The 2020 MTP's **"Code 3 Matrix"** slide (a 3×3 grid of EPS/Revenue/Net-margin × quarters, cells 11…33) strongly suggests "Code 3"/"33" = the top all-three-accelerating cell — the best-supported reading of the previously-unexplained 2013 name. The **2021, 2022, and 2025 re-runs show the identical matrix** (same example figures — EPS −34/+12/+44/+83, revenue −22/+3/+16/+38, margins 4.5/4.9/5.8/6.6 — same 11…33 grid) — now four independent renderings of the same reading; still, no slide in words equates "33" with the top cell, so it stays "largely resolved" rather than verbatim-confirmed. (§4.8; glossary §1.B.3)
- **Earnings Maturation Cycle — RESOLVED `[2021]`, reconfirmed `[2022]` and `[2025]`.** Title-only in 2020 (shape inferred); the **2021 re-run shows the full diagram** (the 2022 and 2025 re-runs show it again), confirming the accelerate-peak-decelerate arc and its stage labels. No longer uncertain. (§4.8; glossary §1.B.3)
- **VCP contraction sequences** (25/15/8, 25/10/5) — still illustrative; 2017's "wet-towel" mechanic confirms the shrink-on-lower-volume progression and 2020–2025 fix no tiers either. The firm rule remains "each contraction ≈ half the prior." (§3.2 / §4.4)
- **Youthfulness** — 2017 gives a concrete "**public within the last 8–10 years**" filter; **2020's "95% Club" study (~80% IPO'd within the prior 10 years)** reinforces it (2021/2022/2025 re-show the same study, ~80% IPO'd within the prior 10 years), consistent with the 2013 "~first 10 years post-IPO" guide. The source-study 300%/2-yr (Love) & 100%/yr (Reinganum) superperformance definitions remain historical/illustrative, not operative screens. (§1 / §3.1 / §4.10)
- **Market follow-through-day rule** — STILL UNCERTAIN: none of the six sources states a precise IBD market follow-through-day count / %-gain. The **Lockout Rally** (minimal pullbacks off a bottom) and the *stock-level* follow-through count are taught, but no index follow-through-day threshold. 2021, 2022, and 2025 add nothing here. (§4.12)

## 5.4 Session 2021 (Master Trader Program re-run) — what changed

The 2021 workbook is a **year-later re-run of the 2020 Master Trader Program Superperformance Workshop** — same two-volume curriculum, same instructors (Minervini + David Ryan), same teaching charts (Amgen 1988–92, Lumber Liquidators 2013, Docusign, Netflix vs Blockbuster, Michael Kors, Beam Global, …). It is **the same deck re-distributed** (image-based scan rather than a fresh document). Verdict: **adds NO new strategy and NO value change.** Every load-bearing numeric table was read and matches 2020 exactly — Position-Sizing Guidelines, the Report Card, the 8 Keys, the Trading Triangle, the Code-3 Matrix, the win-rate/ratio expectancy lessons. The only doc-relevant deltas:

- **§4 retitled "The Stock Maturation Cycle"** (2020: "The Primary Trend"); same 8-criteria Trend Template under it (§4.2). Re-frame only.
- **Earnings Maturation Cycle diagram now confirmed** — 2020 had the title only; 2021 shows the full accelerate-peak-decelerate arc with stage labels (§4.8; §5.3 RESOLVED).
- **Code-3 Matrix reconfirmed** — identical grid/figures, a second independent rendering supporting the Code-33 reading (§4.8; §5.3).
- **RBAF worksheet re-shown** — the Result-Based Assumption Forecast (documented from 2017, §2.9) appears as the full MTP worksheet (inputs → # trades-to-goal, Optimal f). Reconfirms, does not change.
- **Cosmetic:** Time Compression / Making-the-Turn re-ordered; "Mind Blowing Math" + "Closing Remarks" cap Volume Two (compounding, opportunity-cost, feedback-loop, contingency-planning, important-trading-rules — all already documented in §2).

## 5.5 Session 2022 (Master Trader Program re-run, with Mark Ritchie II) — what changed

The 2022 workbook is a **further re-run of the same Master Trader Program Superperformance Workshop** — the third rendering after 2020 and 2021. Same two-volume curriculum (Vol 1 ≈ 281 pages SEPA→VCP/Failure-Reset, Vol 2 ≈ 236 pages Primary-Base→Closing-Remarks, continuously paginated), same SEPA method, same teaching charts (Amgen 1988–92, Yahoo +7900%, Amazon, Lumber Liquidators, Docusign, Netflix vs Blockbuster, …). The one structural change is the **co-instructor: Mark Ritchie II** (2020/2021 paired Minervini with David Ryan); the deck is delivered as **two physical PDF volumes** (pure image scans — no text layer — so every page was rendered to PNG and read visually). Verdict: **adds NO new strategy.** Every load-bearing table was read and matches 2020/2021 — Position-Sizing Guidelines (1.25–2.5% ROTE / 8% max stop / ≤5–6% avg loss / never >50% / optimal 20–25% / 4–5 big, 8–16 max), the Report Card (5/20/60/15 · 70/15/15 · 65/20/15), the 8 Keys, the Code-3 Matrix, the base-duration windows, the Earnings Maturation Cycle, and the options implied-move tool (the identical (3.50 call + 2.75 put)/162 ≈ 3.86% worked example). The doc-relevant deltas:

- **One numeric refinement (latest-wins, §5.1):** the staggered-stop **"Uncle Point" ceiling 10% → 10–12%** — the deepest *single leg* permitted inside a staggered/bracketed stop. The alternate blend example is re-illustrated **4% on half + 12% on half ≈ 8%** (2020/2021 showed 4% + 8% ≈ 6%). The **10% hard cap** on a position's *realized* loss and the **7–8% single-stop max** are unchanged — this widens a backstop leg, it does not loosen the realized-loss cap. (§2.2; glossary §1.B.5)
- **"Holding Through Earnings — Implied Odds" framing** — the options implied-move tool (documented `[2020]`, §2.2) is paired with a gap-risk slide (a fresh **META, 10/28/22** example showing back-to-back ~−20% earnings gaps) to judge whether to hold a position into an earnings report. A use-case for an existing tool, not a new rule (reinforces the gap-risk sizing in §2.4).
- **Confirmations:** "Stock Maturation Cycle" title (§4.2), the criterion-8 Low-Cheat exception and RS-line ≥6 wk / pref ≥13 wk qualifier, the four named Trend-Template screeners, the Code-3 Matrix, the Earnings Maturation Cycle, and the "95% Club"/winner-profile statistics all re-shown verbatim.
- **Vintage tell:** the META 10/28/22 chart dates this rendering to late-2022.

## 5.6 Session 2025 (Master Trader Program Course Workbook re-run, with Mark Ritchie II) — what changed

The 2025 workbook is the **sixth rendering of the same Master Trader Program Superperformance Workshop** — the "Course Workbook" edition, in **two volumes** (Vol 1 = 316 pages, printed 1–316, SEPA → Failure Reset; Vol 2 = 253 pages, printed 317–569, Primary Base → Markets & Emotions, continuously paginated — 569 pages total), again co-instructed by **Mark Ritchie II**. Both volumes are **pure image scans** (no text layer), so every one of the 569 pages was rendered to PNG and read visually. The official sponsor is now **MarketSurge** (IBD), and the **Minervini Markets 360** platform appears in screenshots — both 2025-era tooling. Verdict: **adds NO new strategy.** Every load-bearing table was read and matches 2020–2022 — the Trend Template (8 criteria), the Code-3 Matrix (EPS −34/+12/+44/+83 · revenue −22/+3/+16/+38 · margins 4.5/4.9/5.8/6.6), the RBAF worksheet ($200K / 25% / 100% / 12% / 6% / 50% → 134 trades, Optimal f 25%), the Report Card (5/20/60/15 · 70/15/15 · 65/20/15), the 8 Keys, the difficult-market cut (7–8% → 4–5%), the stop bands (≤3% / 4–6% / 7–8% / **10–12%** staggered "Uncle Point", blends 6%+10%≈8% and 4%+12%≈8%), and the options implied-move tool (the identical (3.50 call + 2.75 put)/162 ≈ 3.86% worked example, with a META 10/28/22 "Holding Through Earnings" gap-risk chart). The doc-relevant deltas:

- **Two numeric refinements (latest-wins, §5.1):**
  1. **Per-trade equity-risk band 1.25–2.5% → 1.00–1.50% (2.50% max)** — the 2025 Position-Sizing slide states the *typical* Risk of Total Equity ("ROTE", §2.4) as 1.00–1.50%, with 2.50% the hard maximum (2017–2022 stated a flat 1.25–2.5%). The very next slide's ROTE worked example still solves to **1.25%** three equivalent ways (12.5%×10% = 25%×5% = 50%×2.5% = $1,250 on $100k), so **1.25% remains the canonical worked midpoint** — sitting inside the tighter typical band. A tightening of the *typical* target, not of the 2.50% ceiling. (§2.4; glossary §1.B.6)
  2. **Darvas-Box base-duration window 4–5 → 4–6 weeks** — in the per-pattern base-duration list (§4.6); the other four windows (Cup-Cheat/Low-Cheat 6–52 wk, Cup-with-Handle 7–65 wk, Double Bottom 7–65 wk, Power Play 2–6 wk) are unchanged. (§4.6; glossary §1.B.6)
- **One re-frame:** the **"Making the Turn"** reversal entry (§3.3) is renamed the **"Bottom Fishing Pivot"** — the same A→B→C→D schematic (a descending trendline across the lower-high peaks of an intermediate decline drawn in thirds, contracting bounces, broken up as a small base forms: A downtrend, B mark-up that breaks the downtrend, C pullback within the base, D breakout above the pullback high). A new *name*, not a new setup (cf. 2021's "Stock Maturation Cycle" re-title). (§3.3; glossary §1.B.6)
- **Minor clarification:** the position-count line now reads "4–5 big positions / 8–16 stocks max **(including partials)**" (§2.5). (The difficult-market discipline — 4–5% loss-cut, take profits 10–12% vs normal 15–20% — is re-shown unchanged; it dates from 2013/2020, not 2025.)
- **Confirmations:** the "Stock Maturation Cycle" title (§4.2), the named Trend-Template screeners, the criterion-5 Low-Cheat exception, the 50/80 Rule (re-shown with 2020s leaders — Tesla −75%, Zoom −89%, Upstart −96%, etc.), the "95% Club"/winner-profile statistics, and the progressive-exposure ladder all re-shown verbatim.
- **Vintage tell:** the MarketSurge / Minervini Markets 360 sponsor pages and the 2024-dated NVDA base-count chart date this rendering to 2025.

---
# 6. Machine-Readable Appendix

One JSON object per §3 strategy, sharing a consistent schema (INSTRUCTIONS §6) for building a screener / backtest / bot. `entry_conditions.bearish` is empty (or carries a one-line Stage-4 short note) because the method is long-only. `session_introduced` stays **"2013"** for every strategy (none was introduced in 2017, 2020, 2021, 2022, or 2025); **refinements** appear as `(2017)`- and `(2020)`-tagged array items and as additional entries in `source_files`. The **2021** re-run added no rule and no value change. The **2022** re-run added no new rule either — its one numeric refinement (staggered "Uncle Point" 10→10–12%) is a risk-table value carried in §2.2/§5.1, not a per-strategy field. The **2025** re-run likewise adds no new strategy — its two refinements (per-trade equity-risk band 1.25–2.5%→1.00–1.50% (2.50% max); Darvas-Box base window 4–5→4–6 weeks) are risk/base-table values carried in §2.4/§4.6/§5.1, and "Bottom Fishing Pivot" is a rename of the §3.3 turn — so all three re-runs appear primarily as `source_files` entries. `valid_to_year` is `"current"` (sessions 2013 + 2017 + 2020 + 2021 + 2022 + 2025).
## 6.1 SEPA Master Playbook (Specific Entry Point Analysis) (`sepa`)

```json
{
  "name": "SEPA Master Playbook (Specific Entry Point Analysis)",
  "key": "sepa",
  "market_context": "US-equity momentum, long-only swing-to-position. Buy confirmed Stage-2 uptrends, preferably as the general market comes out of a correction/bear market (>90% of superperformers begin their surge then). Execute only on probability convergence of fundamentals + price + volume + market ('four-way intersection'). Shorting is reserved for full Stage-4 downtrends and is outside this playbook.",
  "instruments": ["US individual equities (stocks)", "favor young companies (e.g., first ~10 years post-IPO, illustrative)", "favor small/mid-cap with small float", "smaller names must already be profitable with a proven scalable business model"],
  "timeframe": "Swing-to-position; holding days to many months across the Stage-2 advance",
  "indicators": ["50-day (10-week) MA", "150-day (30-week) MA", "200-day (40-week) MA", "52-week high/low distance", "IBD Relative Strength (RS) rank", "volume (accumulation/breakout)", "EPS & sales growth/acceleration", "earnings surprise", "analyst estimate revisions", "profit margins", "VCP volatility-contraction footprint"],
  "setup_preconditions": [
    "Funnel Stage 1: passes all 8 Trend Template criteria (confirmed Stage 2) (see 4.2)",
    "Funnel Stage 2: passes earnings/sales/margin growth + relative strength + price-volatility screen (~95% of Trend-Template survivors fail here) (see 4.8, 4.10)",
    "Funnel Stage 3: matches the Leadership Profile fundamental + technical fingerprint (see 4.7)",
    "Funnel Stage 4: manually ranked via 'relative prioritizing' on the 12 scored items",
    "Identifiable catalyst present (new product / FDA approval / new contract / new CEO) (see 4.11)",
    "Stock sits in a leading industry group (see 4.11)",
    "General market direction favorable, preferably coming out of a correction/bear market (see 4.12)",
    "Constructive base / VCP forming inside the Stage-2 uptrend (see 4.6, 3.2)"
  ],
  "entry_conditions": {
    "bullish": [
      "Confirm Stage 2 via the full 8-criteria Trend Template (see 4.2); price >=25-30% above 52-week low",
      "Never long a stock below its declining 200-day MA regardless of fundamentals",
      "Demand improving fundamentals: meaningful positive earnings surprise + upward estimate revisions (5% / 30-day rules) + accelerating EPS and sales; current-quarter EPS >=20-25% YoY (40-100%+ in a bull market) (see 4.8)",
      "Confirm convergence: real catalyst (4.11) + leading group (4.11) + favorable market (4.12) + RS rank >=70 (prefer 80s-90s) (4.10)",
      "Wait for a proper base / VCP (volatility contracts left-to-right, volume dries up at the tightest right-side pullback) (see 4.6, 3.2)",
      "BUY on a breakout above the pivot point / line of least resistance (4.3) on a noticeable volume increase (4.5)",
      "Scale in: pilot buy first, add larger only after the position shows a profit; pyramid up when trading well; never average down",
      "(2017) Buy in order of breakout / strength (no favorites; first-mover advantage); target velocity trades (~20/30/50% in weeks-months)",
      "(2017) Post-entry confirmation (health checks, not new entries): follow-through count (3 of 4 / 6 of 8 up days, ideally 7-8 in a row), tennis-ball action (brief 2-5 day pullback then snap-back on expanding volume), closes in upper half of range; a squat (close below day mid-range) gets a 1-2 day up to ~10-day recovery window above the stop"
    ],
    "bearish": []
  },
  "exit_conditions": {
    "target": "No fixed price target; ride the Stage-2 advance and sell as the superperformance phase ends. Sell into strength when the stock runs up rapidly with plentiful buyers, or into weakness at the first breakdown (see 3.6). Take profits more quickly on a fast-advancing large-cap than on a smaller, faster-growing name.",
    "stop_loss": "Predetermined initial stop set before buying: <= one-half of average gain, absolute cap 10% off purchase price (never wider). Honor instantly when hit. Move stop to >= breakeven / trail once gain reaches ~3x risk. (see section 2)",
    "time_exit": "No fixed time exit; exit on the Stage-2 -> Stage-3 transition (major break on largest volume since the advance began, rising volatility, 200-day MA flattening then rolling over) (see 4.1, 3.6).",
    "scaling": "Scale in incrementally (e.g., pilot buy then add on profit; pro example 2% + 2% + 1% of capital with stop 10% off average cost). Pyramid up when trading well, taper when trading poorly. Never average down."
  },
  "risk_management": [
    "Stop-loss is a mandatory pillar (SEPA Element 5); set in advance and write it down before buying",
    "Stop <= 1/2 average gain, absolute max 10%",
    "Maintain >=2:1 win/loss ratio, shoot for 3:1; positive expectancy required",
    "Below 50% batting average: tighten stops, never widen",
    "Difficult market: cut at 4-5% (2020; 5-6% in 2017), take profits at 10-12%, get off margin, reduce exposure",
    "Move into equities incrementally; if not profitable at 25-50% invested, do not go to 75-100% or margin",
    "Never average down; hold ~4-8 stocks (2017; 4-6 in 2013), 10-12 for large portfolios (16-20 large professional), never >20",
    "For smaller companies, confirm profitability and a proven/scalable business model; weigh liquidity risk",
    "(2017) Cap per-trade risk at 1.25-2.5% of equity ('back into risk': position% x stop%); working count 4-8 names (top 4-5 at 20-25%), never >50% in one position; target average loss 5-6%; use a written/automated stop (no mental stops)",
    "(2025) Typical per-trade equity-risk band tightened to 1.00-1.50% (2.50% max); 1.25% remains the canonical worked ROTE midpoint; position count 4-5 big / 8-16 names max (including partials)"
  ],
  "filters": [
    "Trend Template - 8 criteria (4.2)",
    "Stage Analysis - buy only Stage 2 (4.1)",
    "Earnings Quality - surprise, upward revisions, accelerating EPS+sales / Code 33 (4.8)",
    "Relative Strength - RS rank >=70, prefer 80s-90s; beat the sector (4.10)",
    "Industry Groups & Catalysts - catalyst in a leading group (4.11)",
    "General Market Direction & Timing - favor leaders off a correction low (4.12)",
    "Volume Analysis - accumulation; breakout on increased volume (4.5)",
    "Base & Correction Geometry - proper base, least correction, early base count (4.6)",
    "P/E & Valuation Context - high/expanding P/E acceptable, valuation is context not a disqualifier (4.9)"
  ],
  "edge_cases": [
    "Keep screens simple; run separate screens on compatible criteria and prioritize recurring names",
    "Late-stage bases (4th-5th base) fail more often; bases 1-2 off a correction are best",
    "A valid VCP setup can still fail - the predetermined stop is non-negotiable",
    "Post-earnings drift can persist for months; it may not be too late to buy after a strong report",
    "Take profits faster on fast-advancing large-caps than on smaller faster-growing names",
    "No bottom-fishing - avoid Stage 1 and Stage 4 even with appealing fundamentals",
    "(2017) Always trade with a complete pre-set plan (no 'audible'); maintain a contingency playbook (initial stop / reentry / sell-into-strength / sell-into-weakness / disaster); know your numbers (avg win, avg loss, batting average) and set risk from realized results (RBA)",
    "(2020) Run the daily routine (§2.10): evening-screen candidates into 3 lists (immediately buyable / on-deck / watch) via 5 nightly screens + the named Trend-Template screeners (1-Month / 5-Month / 5-Month Wide, §4.2); set pivot pre-alerts and stop alerts pre-market; grade every closed trade with the Report Card (§2.11). Framing: '5 Key Areas' = Categories&Catalysts / Price&Volume / Fundamentals / Entry / Exit; confirm a working breakout via Bullish Breakout Criteria (+20% / up 12 of 15 days / elevated volume)"
  ],
  "session_introduced": "2013",
  "day_introduced": "",
  "valid_from_year": 2013,
  "valid_to_year": "current",
  "source_files": [
    "Trade Like a Stock Market Wizard (Minervini, 2013) Ch 3 - SEPA",
    "Ch 5 - Stages & Trend Template",
    "Ch 7 - Fundamentals",
    "Ch 8 - Earnings Quality",
    "Ch 10 - VCP & Technical Footprint",
    "Ch 13 - Risk Management 2 / Selling",
    "Think & Trade Like a Champion (Minervini, 2017) - Sections 1-4, 6-8, 10 (planning, risk-first, expectancy, self-measurement, buy mechanics, position sizing, eight keys)",
    "Master Trader Program (MTP) Superperformance Workshop (Minervini & D. Ryan, 2020) - Vol 1-2, Sections 1-28 (operational layer: daily routine, post-analysis/Report Card, named screeners, base-duration windows, catalyst taxonomy, quantified risk tables)",
    "Master Trader Program Superperformance Workshop (Minervini & D. Ryan, 2021) - re-run of the 2020 workshop; confirms all rules with NO value change (Earnings Maturation Cycle diagram confirmed, Code-3 Matrix + RBAF re-shown, 'Stock Maturation Cycle' re-frame)",
    "Master Trader Program Superperformance Workshop (Minervini & M. Ritchie II, 2022) - further re-run, two volumes; confirms all rules; one refinement: staggered-stop 'Uncle Point' ceiling 10->10-12% (alt blend 4%+12%=8%); options implied-move tool + Report Card + Code-3 Matrix re-shown identically",
    "Master Trader Program Superperformance Workshop Course Workbook (Minervini & M. Ritchie II, 2025) - sixth rendering, two volumes (569 pages, image scans); confirms all rules; two refinements: per-trade equity-risk band 1.25-2.5%->1.00-1.50% (2.50% max, 1.25% still worked ROTE midpoint), Darvas-Box base window 4-5->4-6 weeks; one re-frame: 'Making the Turn' renamed 'Bottom Fishing Pivot'; Report Card 5/20/60/15 + Code-3 Matrix + 8 Keys + RBAF + options implied-move tool re-shown identically"
  ],
  "uncertain": [
    "300%/2-yr and 100%/calendar-year superperformance thresholds are source-study (Love/Reinganum) definitions, not stated as Minervini's operative screening thresholds",
    "'First 10 years post-IPO' youthfulness and '~last couple quarters of beats' are illustrative guides, not hard cutoffs"
  ]
}
```
## 6.2 Volatility Contraction Pattern (VCP) + Pivot Buy (`vcp`)

```json
{
  "name": "Volatility Contraction Pattern (VCP) + Pivot Buy",
  "key": "vcp",
  "market_context": "US-equity momentum/swing trade: constructive consolidation under institutional accumulation within a confirmed Stage 2 uptrend; volatility and volume contract left-to-right as overhead supply is absorbed, defining a precise low-risk entry at the line of least resistance. Long-only (Stage 4 downtrends are short candidates but no bearish VCP mirror is built).",
  "instruments": ["US individual equities (stocks)"],
  "timeframe": "Swing-to-position; holding days to many months. Bases inspected on daily and weekly charts; proper base 3-65 weeks.",
  "indicators": ["50-day moving average", "150-day (30-week) moving average", "200-day (40-week) moving average", "20-day moving average (post-breakout health gate)", "Volume (vs 50-day average)", "Relative Strength (IBD RS rank)", "Technical Footprint notation (e.g. 40W 31/3 4T)"],
  "setup_preconditions": [
    "Confirmed Stage 2 uptrend passing the Trend Template (all 8 criteria, see 4.2)",
    "Prior existing uptrend; stock under institutional accumulation",
    "Proper, symmetric base (not just any base): rest -> profit-taking -> equilibrium -> continuation",
    "Base duration 3 to 65 weeks depending on correction depth",
    "Preferred base depth 10% to 35% (2017: occasionally as much as ~40% for an otherwise constructive setup)",
    "Avoid corrections of 60% or more",
    "Avoid stocks correcting more than 2x-3x the general market's decline (2017: floor raised to 2.5x-3x); major bear-market exception: up to ~50% can still work",
    "Reject time-compressed / V-shaped bases lacking proper right-side development"
  ],
  "entry_conditions": {
    "bullish": [
      "2 to 6 contractions (Ts), typically 2 to 4 (sometimes 5 or 6)",
      "Each successive contraction about half the prior depth (plus/minus a reasonable amount)",
      "Volatility AND volume both contract from left to right; volume dries up on the right side",
      "Final tightest contraction forms the Pivot Point / line of least resistance (4.3)",
      "Final-contraction volume below the 50-day average, with 1-2 days of extremely low volume",
      "BUY on breakout above the pivot on expanding volume (up-day volume much bigger than down-day volume)",
      "Buy as close to the pivot as possible, no more than a few percentage points above it (do not chase)",
      "Always wait for the actual pivot breach; never enter early",
      "Shakeouts (1-3 times, at base lows / right side / handle-pivot) ideally already digested before entry; confirm a shakeout reversed up before acting",
      "Demand confirmation: price spike / gap on overwhelming above-average volume (ideally a fundamental catalyst)",
      "(2017) Do NOT buy 'extended' = more than ~10% above the most recent consolidation (wait for a pullback or new base)",
      "(2017) VCP forms after a 30-50%+ prior advance; right side must quiet down (volume contraction + calmer price) or reject the trade; a 40-50% pull-back to the breakout level is normal if it recovers within days to 1-2 weeks",
      "(2017) Double bottom is a valid non-VCP base: a 'W' (preferably undercutting the first low) that must form a right-side pause/pivot"
    ],
    "bearish": ["Stage 4 downtrend only: shorting is briefly noted as the inverse but no bearish VCP setup is built in this long-only method."]
  },
  "exit_conditions": {
    "target": "No fixed price target in this module; ride the Stage 2 advance, sell into Stage 3 / per the Selling rules (3.6) and 2.",
    "stop_loss": "Initial stop just below the pivot, capped at the method's hard maximum loss (<=10%, per 2).",
    "time_exit": "None defined for VCP; allow squats/reversal recoveries up to ~10 days or longer before abandoning a fresh breakout (unless the stop is hit).",
    "scaling": "May add to the position on a confirmed reversal recovery after a squat; primary scaling/profit-protection logic lives in 2 and 3.6."
  },
  "risk_management": [
    "Do not chase: max a few percentage points above the pivot",
    "Do not enter before the pivot is breached",
    "Hold the original protective stop through squats and early-day reversals",
    "Post-breakout: price should hold its 20-day MA and not close below it; pattern should not widen into wild swings",
    "Flat-base buy only if base corrected no more than 10-15%",
    "A textbook VCP can still fail (e.g. USG 2006) - the stop is mandatory",
    "If the stock does not act as expected after the breakout, treat it as a major red flag"
  ],
  "filters": [
    "Must pass Trend Template (4.2) and be in confirmed Stage 2 (4.1)",
    "RS rank >= 70, preferably 80s-90s (4.10); under accumulation",
    "Align with general market direction (4.12) - pivots fail in a bear market",
    "Reject if price/volume do not quiet down on the right side (supply still coming)",
    "Prefer buying into new-high territory; avoid stocks near 52-week lows",
    "Distinguish non-VCP variations (Darvas box; flat base 4-7 weeks ~10-15%) - flat base bought above base high only if corrected <=10-15%"
  ],
  "edge_cases": [
    "Squat: breakout closes off the day's high and falls back into range - wait 1-2 days (recovery up to ~10 days or longer) for a reversal recovery; sell only if the stop triggers",
    "Reversal recovery: hold the stop; series of squat-then-recover moves up the right side are normal",
    "Early-day reversal: price returns to the breakout before noon-1pm - give until end of day unless the stop is hit; hold to the original plan even on a brief undercut",
    "Intraday volume extrapolation to confirm above-average demand building before the pivot",
    "Pivot may form at new highs OR below the structure high (cup-with-handle / 3-C cheat, see 3.3)",
    "Primary base / IPO setups handled in 3.5"
  ],
  "session_introduced": "2013",
  "day_introduced": "",
  "valid_from_year": 2013,
  "valid_to_year": "current",
  "source_files": [
    "Trade Like a Stock Market Wizard (Minervini, 2013), Ch 10 (Figs 10.1-10.41)",
    "Trade Like a Stock Market Wizard (Minervini, 2013), Ch 5 (Stage Analysis + Trend Template, Figs 5.1-5.10)",
    "Think & Trade Like a Champion (Minervini, 2017), Sections 6-7 (VCP, footprint, extended rule, double bottom)",
    "Master Trader Program (MTP) Superperformance Workshop (Minervini & D. Ryan, 2020) - Vol 1-2, Sections 1-28 (operational layer: daily routine, post-analysis/Report Card, named screeners, base-duration windows, catalyst taxonomy, quantified risk tables)",
    "Master Trader Program Superperformance Workshop (Minervini & D. Ryan, 2021) - re-run of the 2020 workshop; confirms all rules with NO value change (Earnings Maturation Cycle diagram confirmed, Code-3 Matrix + RBAF re-shown, 'Stock Maturation Cycle' re-frame)",
    "Master Trader Program Superperformance Workshop (Minervini & M. Ritchie II, 2022) - further re-run, two volumes; confirms all rules; one refinement: staggered-stop 'Uncle Point' ceiling 10->10-12% (alt blend 4%+12%=8%); options implied-move tool + Report Card + Code-3 Matrix re-shown identically",
    "Master Trader Program Superperformance Workshop Course Workbook (Minervini & M. Ritchie II, 2025) - sixth rendering, two volumes (569 pages, image scans); confirms all rules; two refinements: per-trade equity-risk band 1.25-2.5%->1.00-1.50% (2.50% max, 1.25% still worked ROTE midpoint), Darvas-Box base window 4-5->4-6 weeks; one re-frame: 'Making the Turn' renamed 'Bottom Fishing Pivot'; Report Card 5/20/60/15 + Code-3 Matrix + 8 Keys + RBAF + options implied-move tool re-shown identically"
  ],
  "uncertain": [
    "Contraction sequences 25/15/8 and 25/10/5 are illustrative examples, not hard tiers; only firm rule is each contraction ~half the prior.",
    "The 2.3x-the-market correction (Cirrus example) is an acceptable in-bounds multiple; text fixes only the >2-3x-the-market avoid rule, not a precise ceiling."
  ]
}
```
## 6.3 The Cheat (3-C Low-Risk Entry) (`cheat_3c`)

```json
{
  "name": "The Cheat (3-C Low-Risk Entry)",
  "key": "cheat_3c",
  "market_context": "US-equity momentum, long-only. Buy inside a developing base of a confirmed Stage-2 uptrend (above an upwardly-trending 200-day MA, satisfying the Trend Template). Cheats commonly form during a general-market correction; strongest names turn up as/near when the averages turn up.",
  "instruments": ["US common stocks (Stage-2 leaders under institutional accumulation)"],
  "timeframe": "Swing-to-position; holding period days to many months. Base forms over 3-45 weeks (most 7-25).",
  "indicators": ["200-day moving average (rising)", "20-day moving average (post-entry health gate, per shared components)", "50-day average volume (volume dry-up reference)", "Volume (contraction at the pause, expansion on breakout)", "Price-range tightness / VCP contraction"],
  "setup_preconditions": [
    "Prior advance of 25%-100% (in some cases 200%-300%) over the previous 3-36 months",
    "Trading above an upwardly-trending 200-day MA (>=200 trading days) and in a confirmed Stage-2 uptrend (Trend Template, see shared components)",
    "Base duration 3-45 weeks (most 7-25 weeks)",
    "Correction from peak to low of 15%-40%, up to 50% in some cases; corrections >60% are too deep and disqualify the setup",
    "Cheat area shows VCP signature: volume contraction AND price-range tightness",
    "'Making the Turn' A->B->C->D structure present: A downtrend, B rally recouping ~1/3 to 1/2 of the decline then stalling on overhead supply, C pause/plateau (cheat) of 5%-10% high-to-low (optimal shakeout below a prior low), D breakout above the pause high"
  ],
  "entry_conditions": {
    "bullish": [
      "Buy as price clears the HIGH of the pause (cheat or handle) on a noticeable increase in volume",
      "Cheat location sets the buy point(s): handle in the upper third of the cup = standard high cheat; pause in the middle third or just below halfway = more than one buy point (mid-cup cheat + upper handle); rare low cheat near the bottom of the cup can run with no handle",
      "Confirm the turn before buying: rally breaks the downtrend, price pauses, then follows through above the pause high (do not buy a bare trendline break)",
      "Livermore confirmation variant: after the trend breaks and two reactionary pullbacks occur, enter above the second reaction high",
      "Pivot may sit below the overall structure high (not necessarily a new-high pivot); do not chase more than a few percentage points above the pivot, do not pre-empt the breach",
      "(2017) Cheat location is explicit: low cheat (lower third) / classic cheat (middle third) / handle (upper third); use the low cheat as a deliberate riskier, lower-cost scale-in START, then add at higher pivots (handle). The 3-C is the EARLIEST point you should attempt to buy any stock."
    ],
    "bearish": ["Not applicable (long-only). Shorting reserved for confirmed Stage-4 downtrends; no short mirror of the 3-C is defined."]
  },
  "exit_conditions": {
    "target": "No fixed price target; ride the resumed Stage-2 advance per the centralized Selling rules (§2).",
    "stop_loss": "Protective stop per §2; keep it tight because the cheat fails more often than the standard pivot. Anchor near the pause low / shakeout low. Specific stop %/sizing are defined centrally in §2.",
    "time_exit": "None defined for the cheat itself.",
    "scaling": "Scale in: take a partial position at the cheat, add at the handle/breakout; cheat + handle act as multiple scale-in pivots to lower average cost. Do not deploy full size at the cheat alone."
  },
  "risk_management": [
    "The cheat fails more often than the standard upper-base pivot -> use a tight stop and a partial initial position",
    "Most dangerous time to trade is while a stock is bottoming (violent whipsaws); waiting for the turn reduces but does not eliminate failure odds",
    "Disqualify any base correcting more than 60%",
    "Hold to the original protective stop through squats/early-day reversals (per shared pivot rules); a failure (pivot) reset within the cheat/handle can offer a fresh entry within days/weeks if fundamentals remain intact"
  ],
  "filters": [
    "Must pass the Trend Template (8 criteria) and be in Stage 2",
    "Prior advance and base-depth/duration gates above",
    "Evidence of institutional accumulation (volume much bigger on up days than down days into the right side of the base)",
    "General market direction/timing favorable (cheats often form during a market correction and break out as the averages turn up)"
  ],
  "edge_cases": [
    "Low cheat (rare): cheat near the bottom of the cup, can break out with no handle (e.g. GOOG 2004 - illustrative only)",
    "Mid/low-third cheat yields more than one buy point (cheat + handle)",
    "Optimal cheat drifts below a prior low to create a shakeout before the breakout",
    "Failure (pivot) reset: a shakeout/stop-out within the cheat or handle can reset a new entry within days/weeks rather than requiring a whole new base"
  ],
  "session_introduced": "2013",
  "day_introduced": "",
  "valid_from_year": 2013,
  "valid_to_year": "current",
  "source_files": [
    "Trade Like a Stock Market Wizard (Minervini, 2013), Ch 10 - The 3C Pattern (Figs 10.50-10.54), pivot/volume/cheat, VCP footprint",
    "Think & Trade Like a Champion (Minervini, 2017), Section 7 - low/middle/upper-third cheat, low cheat scale-in",
    "Master Trader Program (MTP) Superperformance Workshop (Minervini & D. Ryan, 2020) - Vol 1-2, Sections 1-28 (operational layer: daily routine, post-analysis/Report Card, named screeners, base-duration windows, catalyst taxonomy, quantified risk tables)",
    "Master Trader Program Superperformance Workshop (Minervini & D. Ryan, 2021) - re-run of the 2020 workshop; confirms all rules with NO value change (Earnings Maturation Cycle diagram confirmed, Code-3 Matrix + RBAF re-shown, 'Stock Maturation Cycle' re-frame)",
    "Master Trader Program Superperformance Workshop (Minervini & M. Ritchie II, 2022) - further re-run, two volumes; confirms all rules; one refinement: staggered-stop 'Uncle Point' ceiling 10->10-12% (alt blend 4%+12%=8%); options implied-move tool + Report Card + Code-3 Matrix re-shown identically",
    "Master Trader Program Superperformance Workshop Course Workbook (Minervini & M. Ritchie II, 2025) - sixth rendering, two volumes (569 pages, image scans); confirms all rules; two refinements: per-trade equity-risk band 1.25-2.5%->1.00-1.50% (2.50% max, 1.25% still worked ROTE midpoint), Darvas-Box base window 4-5->4-6 weeks; one re-frame: 'Making the Turn' renamed 'Bottom Fishing Pivot'; Report Card 5/20/60/15 + Code-3 Matrix + 8 Keys + RBAF + options implied-move tool re-shown identically"
  ],
  "uncertain": [
    "Whether a mid/low-third cheat gives strictly two buy points or potentially more — text says 'more than one buy point' (Ch 10, p. 244) without bounding it.",
    "Example figures (e.g. GOOG and CRUS post-cheat advances) are stock-specific illustrations, NOT rules - exact %/duration vary by source edition (2017 cites GOOG +625% over 40 months, CRUS +162% in four months).",
    "Exact numeric stop % and position-sizing for the cheat are not in these extracts — they live in the centralized Selling/risk rules (§2)."
  ]
}
```
## 6.4 Power Play (High, Tight Flag) (`power_play`)

```json
{
  "name": "Power Play (High, Tight Flag)",
  "key": "power_play",
  "market_context": "US equities; long-only momentum continuation. Requires a confirmed Stage 2 uptrend (Trend Template §4.2 pass) and a supportive general market (§4.12). The highest-velocity SEPA setup; the only one entered with a dearth of fundamentals (price action substitutes), though VCP base behaviour is mandatory.",
  "instruments": ["US common stocks (Stage 2 leaders)"],
  "timeframe": "Swing-to-position (days to many months); thrust < 8 weeks, consolidation ~12 days to 6 weeks.",
  "indicators": ["Price (vertical thrust + tight flag)", "Volume (huge on thrust; dry-up into flag; expansion on breakout)", "Stage Analysis (§4.1)", "Trend Template / moving averages (§4.2)", "VCP contractions / Technical Footprint (§4.4)", "Pivot Point / line of least resistance (§4.3)"],
  "setup_preconditions": [
    "Stock in a confirmed Stage 2 uptrend (§4.1) and passing the Trend Template (§4.2).",
    "Q1 THRUST: explosive +100% or more in LESS THAN 8 weeks on HUGE volume, after a period of relative dormancy. (2017: the best power plays were quiet in Stage 1; a stock already up off a late-stage base usually does NOT qualify; look for tight weekly closes over 3-6 weeks.)",
    "Q2 CONSOLIDATION: tight sideways range correcting NO MORE than 20-25% (2017 tightens to <=20%, lower-priced up to 25%), lasting 3 to 6 weeks (some emerge after 10-12 days).",
    "Q3 TIGHTNESS/VCP gate: very tight price action correcting no more than ~10%, OR the consolidation displays VCP characteristics (§3.2). VCP behaviour is MANDATORY.",
    "Evidence of institutional accumulation (§4.10).",
    "Catalyst (FDA approval, litigation, new product, earnings) optional — may be 'no news at all' / unexplained strength."
  ],
  "entry_conditions": {
    "bullish": [
      "Buy as the HIGH OF THE PAUSE is taken out ('the turn') — i.e., breakout above the high of the tightest/final contraction (Pivot Point §4.3).",
      "Do NOT buy a trendline break alone; wait for the stock to turn.",
      "Confirm on a noticeable INCREASE in volume; look for the lowest-volume / super-tight day immediately before the breakout as the tell.",
      "Two viable buy points may exist: (1) the turn, (2) emergence from the handle — both valid."
    ],
    "bearish": []
  },
  "exit_conditions": {
    "target": "No fixed target in source; ride the continuation per Selling §3.6 (sell into strength / weakness rules). Example moves illustrative only.",
    "stop_loss": "Tight: just below the breakout pivot / final-contraction low. No power-play-specific numeric stop in the source slice; apply Selling §2/§3.6.",
    "time_exit": "",
    "scaling": "Optional second add at the handle emergence when a two-buy-point structure is present."
  },
  "risk_management": [
    "Entry only at the tight pivot keeps initial risk small (high reward-to-risk point).",
    "A textbook power play can still fail — pattern is not a guarantee; honor the stop.",
    "Failure reset: keep stopped-out names on the radar; pivot failures can reset within a few days, base failures need a whole new base; reset setup is sometimes better than the original."
  ],
  "filters": [
    "General market must be healthy/supportive (§4.12).",
    "Institutional accumulation present (§4.10); 'a good company is not always a good stock.'",
    "Must already be Stage 2 / Trend Template pass (§4.1, §4.2)."
  ],
  "edge_cases": [
    "Fundamentals (§4.7/§4.8) may be absent — this is the ONLY setup entered with a dearth of fundamentals; VCP base behaviour is NOT waived.",
    "~12-day minimum consolidation possible; UNCERTAIN whether absolute floor or observed minimum.",
    "Squat or shakeout on the initial breakout can be constructive and reset — not automatic failure.",
    "Two-buy-point structure (turn + handle) when both are present."
  ],
  "session_introduced": "2013",
  "day_introduced": "",
  "valid_from_year": 2013,
  "valid_to_year": "current",
  "source_files": [
    "Trade Like a Stock Market Wizard (Minervini, 2013), Ch 10 - Power Play / high-tight-flag buy execution (pp. 249, 253-257; Figs 10.56-10.63), VCP footprint",
    "Think & Trade Like a Champion (Minervini, 2017), Section 7 - power play refined: consolidation tightened to <=20% (lower-priced up to 25%), min 10-12 days, late-stage-base disqualifier",
    "Master Trader Program (MTP) Superperformance Workshop (Minervini & D. Ryan, 2020) - Vol 1-2, Sections 1-28 (operational layer: daily routine, post-analysis/Report Card, named screeners, base-duration windows, catalyst taxonomy, quantified risk tables)",
    "Master Trader Program Superperformance Workshop (Minervini & D. Ryan, 2021) - re-run of the 2020 workshop; confirms all rules with NO value change (Earnings Maturation Cycle diagram confirmed, Code-3 Matrix + RBAF re-shown, 'Stock Maturation Cycle' re-frame)",
    "Master Trader Program Superperformance Workshop (Minervini & M. Ritchie II, 2022) - further re-run, two volumes; confirms all rules; one refinement: staggered-stop 'Uncle Point' ceiling 10->10-12% (alt blend 4%+12%=8%); options implied-move tool + Report Card + Code-3 Matrix re-shown identically",
    "Master Trader Program Superperformance Workshop Course Workbook (Minervini & M. Ritchie II, 2025) - sixth rendering, two volumes (569 pages, image scans); confirms all rules; two refinements: per-trade equity-risk band 1.25-2.5%->1.00-1.50% (2.50% max, 1.25% still worked ROTE midpoint), Darvas-Box base window 4-5->4-6 weeks; one re-frame: 'Making the Turn' renamed 'Bottom Fishing Pivot'; Report Card 5/20/60/15 + Code-3 Matrix + 8 Keys + RBAF + options implied-move tool re-shown identically"
  ],
  "uncertain": [
    "Criterion 3 is phrased as an OR (ultra-tight <=10% flag OR full VCP); the <=10% figure is conditional, not an absolute floor (p. 255).",
    "Whether '~12 days' is an absolute floor on the 3-6 week consolidation window or just an observed minimum (p. 255).",
    "No power-play-specific numeric stop or target stated in the source slice; risk levels inferred from the tight pivot and deferred to Selling §3.6."
  ]
}
```
## 6.5 Primary Base & IPO Setup (`primary_base`)

```json
{
  "name": "Primary Base & IPO Setup",
  "key": "primary_base",
  "market_context": "US equities, long-only, confirmed Stage 2 uptrend; buying a young recently-public company out of its first buyable base. Swing-to-position timeframe (days to many months).",
  "instruments": ["US equities (recent IPOs / newly public companies)"],
  "timeframe": "Swing to position (days to many months); base inspection on daily/weekly charts",
  "indicators": ["Stage Analysis (Stage 2)", "Trend Template (8 criteria)", "Pivot Point / line of least resistance", "Volume", "Moving average (trend tracking)", "Relative strength"],
  "setup_preconditions": [
    "Minimum trading history since IPO: at least a couple of months of trading activity",
    "Confirmed Stage 2 uptrend; passes Trend Template (§4.2) — base inside a long-term downtrend is disqualified",
    "Primary base = first buyable base after IPO (first correct-then-break-to-new-high pattern)",
    "Base duration at least 3 to 5 weeks",
    "Base correction no more than 25 to 35 percent (short ~3-week consolidation must not correct more than 25%; long ~1-year correction may decline up to 50% and still be sound)",
    "Evidence of institutional accumulation (volume support)",
    "Sound / accelerating fundamentals (accelerating sales and earnings) — 'youth with character'",
    "For smaller companies: already profitable and business model proven scalable/duplicable"
  ],
  "entry_conditions": {
    "bullish": [
      "Breakout to new-high ground (or constructive emergence near all-time high) out of the primary base",
      "Breakout occurs above the pivot point / line of least resistance (§4.3)",
      "Breakout confirmed by a noticeable increase in volume (§4.5)",
      "Stock is in a confirmed Stage 2 uptrend at the breakout",
      "Multiple entries allowed: primary-base breakout and any subsequent second-stage-base breakout (each a separate buy point)",
      "(2017) Low-cheat IPO variant: for a recent IPO that holds above its IPO price and does not correct excessively, a low cheat (3.3) gives an earlier entry - post-IPO base at least ~10 days, a brief undercut/shakeout can still work, enter on a gap-up on heavy volume then a low-volume pull-back/gap-fill with tight inside days on very low volume; avoid heavy overhead supply"
    ],
    "bearish": []
  },
  "exit_conditions": {
    "target": "Ride the Stage 2 advance; sell mechanics governed by the Selling strategy (§3.6) and §2 — identify the end of the superperformance phase to lock in gains",
    "stop_loss": "Mandatory cut-loss plan if the primary base fails/turns against you (SEPA Element 5); reject/exit setups breaching the 25-35% depth limit; specific stop % defined in §3.6/§2",
    "time_exit": "",
    "scaling": "May add on subsequent second-stage-base breakouts; detailed sizing/scaling in §3.6/§2"
  },
  "risk_management": [
    "No guarantee on any primary-base breakout — always have an exit plan to cut losses",
    "Cut the loss small when a bought base fails (iRobot example: small loss taken vs. eventual ~65% slide)",
    "Base depth limit doubles as risk filter: reject if corrected >25-35% (>25% for a 3-week consolidation; up to ~50% only for ~1-year corrections)",
    "Confirm profitability and a scalable/duplicable business model for smaller companies",
    "Stop-losses are a non-negotiable pillar (SEPA Element 5)"
  ],
  "filters": [
    "Youth: recently public (within past few months to ~1-2 years; primary base may take up to a year+ to form)",
    "Most superperformers go public 8-10 years before their superperformance phase",
    "~80% of 1990s tech-boom winners were IPOs within the prior 8 years",
    "Favor new, unfamiliar leaders; avoid over-owned mature 'official growth stocks' (Innovator-to-Respirator)",
    "Many leaders emerge right after a bear-market decline",
    "'Recently public' does not mean newly in business"
  ],
  "edge_cases": [
    "Fresh IPO can spike 25%/50%/100%+ (even day one) or sell off sharply — wait for a proper primary base regardless; never chase the raw IPO",
    "IPO that never sets up and corrects excessively is unbuyable (illustrative: Facebook 2012, ~43% off-high in 12 days)",
    "Second-stage base provides additional buy point later in the advance (illustrative: Rambus 1997 points A-D)",
    "A textbook base/breakout can still fail (illustrative: iRobot 2006) — risk management required"
  ],
  "session_introduced": "2013",
  "day_introduced": "",
  "valid_from_year": 2013,
  "valid_to_year": "current",
  "source_files": [
    "Trade Like a Stock Market Wizard (2013), Ch 11 'Don't Just Buy What You Know' (pp. 259-268; Figs 11.1-11.8)",
    "Trade Like a Stock Market Wizard (2013), Ch 10 'A Picture Is Worth a Million Dollars' (VCP / footprint / pivot — buy-on-volume mechanics)",
    "Trade Like a Stock Market Wizard (2013), Ch 3 'Specific Entry Point Analysis: The SEPA Strategy' (youthfulness trait, Element 5 stops)",
    "Think & Trade Like a Champion (Minervini, 2017), Section 7 - low-cheat IPO variant (>=10-day post-IPO base; hold above IPO price; gap-up-then-low-volume-fill entry)",
    "Master Trader Program (MTP) Superperformance Workshop (Minervini & D. Ryan, 2020) - Vol 1-2, Sections 1-28 (operational layer: daily routine, post-analysis/Report Card, named screeners, base-duration windows, catalyst taxonomy, quantified risk tables)",
    "Master Trader Program Superperformance Workshop (Minervini & D. Ryan, 2021) - re-run of the 2020 workshop; confirms all rules with NO value change (Earnings Maturation Cycle diagram confirmed, Code-3 Matrix + RBAF re-shown, 'Stock Maturation Cycle' re-frame)",
    "Master Trader Program Superperformance Workshop (Minervini & M. Ritchie II, 2022) - further re-run, two volumes; confirms all rules; one refinement: staggered-stop 'Uncle Point' ceiling 10->10-12% (alt blend 4%+12%=8%); options implied-move tool + Report Card + Code-3 Matrix re-shown identically",
    "Master Trader Program Superperformance Workshop Course Workbook (Minervini & M. Ritchie II, 2025) - sixth rendering, two volumes (569 pages, image scans); confirms all rules; two refinements: per-trade equity-risk band 1.25-2.5%->1.00-1.50% (2.50% max, 1.25% still worked ROTE midpoint), Darvas-Box base window 4-5->4-6 weeks; one re-frame: 'Making the Turn' renamed 'Bottom Fishing Pivot'; Report Card 5/20/60/15 + Code-3 Matrix + 8 Keys + RBAF + options implied-move tool re-shown identically"
  ],
  "uncertain": [
    "Track-record minimum stated two ways: 'at least a couple of months' trading activity (p.260) vs. 'at least 3 to 5 weeks' base (p.262); operative numeric rule = 3-5 week base + 25-35% max correction, 'couple of months' = qualitative minimum history",
    "Exact pivot price/volume trigger not numerically re-specified in the IPO chapter; defined in Pivot Point (§4.3) and VCP/buy-execution (§3.2)"
  ]
}
```
## 6.6 Selling: Offensive & Defensive (`selling`)

```json
{
  "name": "Selling: Offensive & Defensive",
  "key": "selling",
  "market_context": "US-equity SEPA momentum/swing positions; exit discipline for stocks bought in confirmed Stage-2 uptrends. Long-only in practice; Stage-4 only noted as a possible short, not built out.",
  "instruments": ["US common stocks (Stage-2 leaders previously bought long)"],
  "timeframe": "Swing-to-position (days to many months); review every position daily at the close and pre-open.",
  "indicators": ["20-day moving average (fresh-breakout judgment-call warning, Ch 10)", "200-day (40-week) moving average (Stage-4 defensive exit)", "Volume (vs average; huge/overwhelming-volume spikes)", "Stage Analysis (1-4)", "P/E expansion vs starting level", "Pivot / Line of Least Resistance"],
  "setup_preconditions": [
    "Position was entered long in a confirmed Stage-2 advance (per SEPA/VCP entries).",
    "A maximum stop-loss price was written down BEFORE the buy.",
    "Trader knows their real-life average gain (to size the stop at <= 1/2 of it) and average loss (~5-6% target (2017); 6-7% in 2013)."
  ],
  "entry_conditions": {
    "bullish": [],
    "bearish": []
  },
  "exit_conditions": {
    "target": "No fixed price target. Offensive exit: sell partial/whole into climactic strength or once P/E has expanded ~2x-3x its starting level WITH decelerating earnings growth + price weakness. Avoid round-tripping (~1/3 of superperformers give back all gains, avg subsequent decline 50-70%). (2017) The 50/80 rule: a topped secular leader has an 80% chance of -50% and a 50% chance of -80% (avg decline >70%) - heed the FIRST loss. (2017) Climax-top sell-into-strength checklist once late-stage (base count) + P/E expanded >=2x + extended: blow-off +25-50%+ in 1-3 weeks (some +70-80% in 5-10 days); >=70% up days over a 7-15 day window (or 6-10 accelerated days with only 2-3 down); largest up day / widest daily spread / exhaustion gaps since the move began; reversal-on-volume (high-volume reversal, churning, down on the largest volume since the move began - even a -4-5% day on the largest volume + other violations).",
    "stop_loss": "Initial stop set in advance: <= 1/2 of average gain, ABSOLUTE CAP 10% (line in the sand); target average loss ~5-6% (2017; 6-7% in 2013). Hit the stop = sell immediately, next bid if slipping. After advance, move stop to >= breakeven once price rises ~3x initial risk, then trail behind the advance (never give a winner extra downside room). (2017) Three-priority hierarchy (initial stop -> protect principal/breakeven -> protect profit). Breakeven-or-better: trail on the 50-day MA once it reaches your entry, exit on a close below it (optionally Friday weekly close). Back stop: a manual profit-protection line set at/above your average gain. Free roll: up 2R-3R -> sell half and either move the stop to breakeven or finance the back half with the front-half profit.",
    "time_exit": "Sell if the stock fails to advance shortly after purchase even before hitting the stop. Daily: hold only positions still bullish today whose original long thesis remains valid.",
    "scaling": "Defensive: in Stage-3 warning / Stage-4 decline, reduce the position PROGRESSIVELY until in cash rather than all-at-once if exiting a large position. Offensive: sell partial into climactic strength while raising the trailing stop on the remainder."
  },
  "risk_management": [
    "Max loss per position <= 10%; stop <= 1/2 of average gain; average loss ~5-6% (2017; 6-7% in 2013).",
    "Never average down / never add to a losing position.",
    "Move stop to >= breakeven at ~3x initial risk; never let a meaningful gain turn into a loss (yesterday's profit is today's principal).",
    "Honor the predetermined stop without exception; on slippage exit at the next bid.",
    "Never hold into Stage 4 (price below a declining 200-day MA); sell or reduce to cash.",
    "(2017) Post-breakout violations (reduce/exit, possibly before the stop, on multiples): low-volume-out/high-volume-in; 3-4 lower lows on increased volume; more down days/closes than up; close below the 20-day MA soon after breakout (roughly halves success); close below the 50-day MA on heavy volume.",
    "(2017) Dead-money rotation: reallocate a stock that hasn't hit its stop but isn't advancing; but hold 25-50% of a true leader early in a new bull market.",
    "(2020) Sell Alerts (consolidated 13-signal pre-decline watch list); 2nd/3rd exhaustion gap once extended; new high on LOW volume from a late (4th/5th/6th) stage base; full retracement of the breakout and failed natural reaction added as violations; close below the 20-day MA BELOW your cost soon after a proper-base breakout -> REDUCE shares; heavy selling with full retracement after a LOW-volume breakout -> SELL."
  ],
  "filters": [
    "Sell signal valid even when it follows a seemingly great earnings report (price leads fundamentals).",
    "Trust price/volume over analyst opinion and company hype ('trust your eyes, not your ears').",
    "Do not buy or hold a broken stock on a brokerage upgrade; valuation-based upgrades on Stage-4 stocks are often short candidates.",
    "Do not treat a big break / sharp decline as a buying opportunity.",
    "(2017) Differential-disclosure veto: if a stock BEATS earnings yet drops hard (~15%) on the largest volume in years, do NOT buy it - institutions are dumping. Trust price/volume over the report."
  ],
  "edge_cases": [
    "Slippage: stock trades through the stop before you react -> exit at the next bid, do not wait for a rally.",
    "(2017) Early-stage exception: do NOT apply late-stage exhaustion signals to an early-stage breakout - early up-day clusters / MVP (up 12 of 15 days) and tennis-ball action mean HOLD; the same cluster sells only once late-stage + extended.",
    "Broken-leader syndrome: 'down 70%' / low P/E near 52-week low is NOT a reason to hold or re-buy; downside is always 100% of remaining capital.",
    "Stage-3 top can form while earnings still look good; do not wait for fundamentals to dim.",
    "Climax / blow-off run is a top type distinct from a normal base top - sell into the parabolic strength.",
    "Re-entry of a stopped-out name that still shows winner characteristics is a BUY-side concern (handled in entry strategies), not a reason to hold a broken position.",
    "(2020) Failure Reset / Ledge: a failed breakout forms a 'ledge' below the pivot (Pivot Failure / Base Failure) - exit the failed trade, but keep the name for a Pivot Reset (re-forms the same pivot) or Base Reset (builds a larger base) buy; it can take 2-3 tries to catch a winner."
  ],
  "session_introduced": "2013",
  "day_introduced": "",
  "valid_from_year": 2013,
  "valid_to_year": "current",
  "source_files": [
    "Trade Like a Stock Market Wizard (2013) Ch 12 - Risk Management Part 1 (pp. 269-290; Figs 12.1-12.3)",
    "Trade Like a Stock Market Wizard (2013) Ch 13 - Risk Management Part 2 (pp. 291-311+; Figs 13.1-13.2)",
    "Trade Like a Stock Market Wizard (2013) Ch 5 - Trading with the Trend, Stage 3/4 sell tells (pp. 83-108; Figs 5.11-5.26)",
    "Trade Like a Stock Market Wizard (2013) Ch 4 - Value Comes at a Price (pp. 41-62; Figs 4.10-4.11)",
    "Think & Trade Like a Champion (Minervini, 2017) Sections 1, 5, 9 - violations checklist, 50/80 rule, climax-top checklist, breakeven-or-better / free roll / back stop / sell-half, P/E-expansion sell cue",
    "Master Trader Program (MTP) Superperformance Workshop (Minervini & D. Ryan, 2020) - Vol 1-2, Sections 1-28 (operational layer: daily routine, post-analysis/Report Card, named screeners, base-duration windows, catalyst taxonomy, quantified risk tables)",
    "Master Trader Program Superperformance Workshop (Minervini & D. Ryan, 2021) - re-run of the 2020 workshop; confirms all rules with NO value change (Earnings Maturation Cycle diagram confirmed, Code-3 Matrix + RBAF re-shown, 'Stock Maturation Cycle' re-frame)",
    "Master Trader Program Superperformance Workshop (Minervini & M. Ritchie II, 2022) - further re-run, two volumes; confirms all rules; one refinement: staggered-stop 'Uncle Point' ceiling 10->10-12% (alt blend 4%+12%=8%); options implied-move tool + Report Card + Code-3 Matrix re-shown identically",
    "Master Trader Program Superperformance Workshop Course Workbook (Minervini & M. Ritchie II, 2025) - sixth rendering, two volumes (569 pages, image scans); confirms all rules; two refinements: per-trade equity-risk band 1.25-2.5%->1.00-1.50% (2.50% max, 1.25% still worked ROTE midpoint), Darvas-Box base window 4-5->4-6 weeks; one re-frame: 'Making the Turn' renamed 'Bottom Fishing Pivot'; Report Card 5/20/60/15 + Code-3 Matrix + 8 Keys + RBAF + options implied-move tool re-shown identically"
  ],
  "uncertain": [
    "'Largest decline since the start of the Stage-2 advance' - whether measured strictly from the first day of Stage 2 or from the most recent base is not fully specified (Ch 5 says 'since the beginning of the stage 2 advance')."
  ]
}
```
