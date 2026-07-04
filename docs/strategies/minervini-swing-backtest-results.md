# Minervini SEPA Swing — Backtest Results

**As of:** 2026-07-05 · **Window:** 2015-07-04 → 2026-07-04 (~11 years) · **Universe:** 1,789 NSE EQ names (dense native `candles`@1d)

This document collects every deep-history backtest of the Mark Minervini SEPA long-only
cash-equity swing strategy, most recent runs superseding older ones. It is the trade-level +
portfolio-level evidence that the slow-signal live paper book accrues too slowly to provide.

Endpoints (market-data-service): `POST /api/v1/market/screener/minervini/swing-backtest?years=N`
(trigger), `GET …/swing-backtest` (latest single, full-filter variant), `GET …/swing-backtest/compare`
(all variants side by side). Config: `artha.minervini.backtest.{years,min-turnover,rs-min,slots}`.

Shipped in PRs **#556** (v1/v2), **#557** (v3), **#558** (v4), **#559** (v5 costs), **#560** (v6 sweep).

---

## 1. Methodology

**Why a dedicated sim.** The live swing engine fires on a *seeded* VCP pivot, which the parity replay
engine cannot supply (it goes NEUTRAL), so a naive backtest of the setups produces 0 trades. This sim
(`MinerviniSwingBacktest`) seeds the geometry itself — it recomputes the `VcpDetector` pivot / cheat /
thrust at a **weekly** cadence (the live-screen rhythm) and applies the **same** entry gates + the
**same** exit doctrine the live strategies use, so the trade-level stats reflect the real strategy.

**The 4 setups.** `vcp` (breakout over the VCP pivot), `primary-base` (fresh 52-week-high breakout),
`cheat-3c` (low-cheat entry), `power-play` (thrust-confirmed pivot). Each runs an independent
single-position book per variant.

**Entry gate.** The 7 price-structure Trend-Template gates (close > 50 > 150 > 200-day MA, 200-day
rising, within 25% of the 52-week high, ≥30% above the 52-week low) + the setup-specific trigger
(crossover of the pivot / cheat / 52-week high, with volume expansion). Evaluated **every day** per
symbol — more faithful than a point-in-time screener snapshot.

**Exit doctrine (owner-pinned).** 8% protective stop first (close ≤ entry × 0.92), then a 50-day-MA
close trail. A third of trades stop out; the rest trail out.

**Portfolio model** (`SwingPortfolio`, v3+). Per-trade stats do not equal portfolio returns, so a
slot-limited equity model runs on top: **K = 8** equal-weight compounding sleeves (Minervini runs
concentrated, ~4–8 names), chronological, **skip-on-full** (a breakout is take-it-or-skip-it; skipped
signals are counted). Yields annual + monthly returns, CAGR, max drawdown, Sharpe, exposure, and
trades taken vs skipped. Two slot-allocation policies: **FIFO** (first-come) and **RS-priority** (the
strongest names claim scarce slots — v4).

**The 4 variants** (a clean 2×2 over the *same* bars — identical geometry/MAs/triggers; only the entry
filter differs):

| Variant | Cross-sectional RS-rank gate (≥70) | Turnover floor (₹37.5 L/day) |
|---|:--:|:--:|
| `technical` | — | — |
| `rs-only` | ✓ | — |
| `turnover-only` | — | ✓ |
| `rs-turnover` | ✓ | ✓ (the live-funnel analogue) |

RS-rank = a weekly cross-sectional percentile of the §4.10 weighted trailing return
(0.4·r63 + 0.2·r126 + 0.2·r189 + 0.2·r252), midpoint-ranked across the universe.

---

## 2. Caveats (read before trusting any number)

1. **No costs or slippage.** Gross returns. Real STT + brokerage + breakout slippage haircut every
   trade, and hit the thin-name trades (which the `technical` / `rs-only` variants take more of) hardest.
2. **Survivorship bias.** `candles@1d` holds only currently-listed names — delisted losers are absent.
   This inflates every variant, and **inflates the small-cap tilt most** (so `rs-only` is the most
   optimistic; the turnover floor removes exactly the illiquid names that survivorship flatters).
3. **Gap-through-stop risk is real.** The 8% stop is *close-based*, so an overnight −90% gap books the
   full loss — see the −94.93% worst trade. 8-slot sizing (~12.5%/name) caps single-name damage to
   ~−12% of the book, which is why drawdowns stay in the 28–61% band rather than catastrophic.
4. **High-octane.** Drawdowns of 28–61% and losing streaks of 140–169 trades. This is not a low-vol
   strategy; the owner must be able to stomach ~40–50% peak-to-trough.
5. **Fundamentals are absent.** No point-in-time fundamentals history exists (Upstox is a current
   snapshot), so no fundamentals gate is modelled. Judge fundamentals on the forward live paper book.
6. `rs-only` / `technical` feed the RS gate a passing constant, so v1 reproduces byte-for-byte across
   re-runs (±a few tail-bar trades from live data accrual).

---

## 3. v1 — technical-only (baseline, PR #556)

The raw setup mechanics with neither filter. Per-setup, over all closed trades:

| Setup | Trades | Win% | Expectancy | Payoff | Avg win | Avg loss | Profit factor | Best | Worst | Stop-out% | Avg hold |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| power-play | 3,926 | 32.3% | **+8.00%** | 5.83 | +38.6% | −6.6% | 2.78 | +2,336% | −90.0% | 28.1% | 25d |
| primary-base | 10,524 | 37.3% | +6.27% | 3.76 | +30.4% | −8.1% | 2.24 | +2,138% | −88.4% | 41.6% | 34d |
| vcp | 13,877 | 32.6% | +5.16% | 4.76 | +27.9% | −5.9% | 2.31 | +2,336% | −94.9% | 21.2% | 27d |
| cheat-3c | 11,204 | 31.5% | +4.95% | 4.96 | +28.0% | −5.6% | 2.28 | +2,081% | −89.5% | 20.6% | 26d |
| **ALL** | **39,531** | **33.5%** | **+5.68%** | 4.61 | +29.7% | −6.4% | 2.33 | +2,336% | −94.9% | 27.1% | 28d |

**Read:** textbook Minervini asymmetry — win ~1-in-3, but winners run (+30% avg) while losers are cut
at −6%. Payoff 4.6×. `power-play` (thrust-confirmed) is the sharpest per-trade edge. But per-trade
expectancy over 39.5k trades is misleading when the book holds only 8 — see §5.

---

## 4. v2 — RS + turnover A/B (PR #556)

The first filtered comparison bundled both filters:

| Metric | v1 technical | v2 rs-turnover | Δ |
|---|--:|--:|--:|
| Trades | 39,531 | 22,714 | −42.5% |
| Win rate | 33.53% | 33.72% | +0.2pp |
| Expectancy/trade | +5.68% | +5.10% | −0.58pp |
| Payoff | 4.61 | 4.11 | −0.50 |

At the per-trade level the filters *looked* like they hurt. v3 (below) shows why that read was wrong:
per-trade expectancy is the wrong lens for a capacity-bound book, and the two filters have opposite
effects that cancelled when bundled.

---

## 5. v3 — 2×2 isolation + portfolio (PR #557) — the decision-grade view

### 5.1 Portfolio (8 slots, FIFO, all setups combined)

| Variant | CAGR | Total return | Max DD | Sharpe | Taken | Skipped | Exposure | +Months | Best mo | Worst mo |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| technical | 27.97% | +1,405% | −27.78% | 0.84 | 755 | 38,776 | 95.9% | 42.1% | +60.6% | −13.0% |
| **rs-only** | **43.37%** | **+5,147%** | −53.00% | **0.96** | 717 | 27,302 | 95.7% | 46.6% | +76.9% | −16.9% |
| turnover-only | 27.28% | +1,317% | −60.65% | 0.65 | 780 | 30,882 | 94.7% | 41.4% | +90.8% | −22.2% |
| rs-turnover | 22.78% | +854% | −41.76% | 0.70 | 745 | 21,969 | 94.5% | 43.6% | +71.5% | −21.3% |

**The key finding — the portfolio view flips the per-trade conclusion:**

1. **Capacity is the binding constraint.** 8 slots, ~95% exposure, **27–39k signals skipped, only
   ~750 taken.** The strategy is signal-rich / slot-poor. *Which* ~750 you take is the whole game — so
   per-trade averages over 39k trades are irrelevant.
2. **RS-rank is the edge.** `rs-only` alone nearly **doubles CAGR (28→43%)** and gives the **best
   Sharpe (0.96)**. Confirms Minervini's core thesis — relative strength is what works.
3. **The turnover floor alone hurts** (same CAGR, worst Sharpe 0.65, worst drawdown 61%): it strips
   small-caps without a quality rank, removing winners.
4. **Together, turnover drags RS down** (43→23% CAGR) but **cuts drawdown** (53→42%). The turnover
   floor is a liquidity/realism tax: less return, less risk.

### 5.2 Per-setup trade stats, all four variants

**technical** (39,531 trades)

| Setup | Trades | Win% | Exp% | PF | Best | Worst | Max W-streak | Max L-streak | Stop-out% |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| vcp | 13,877 | 32.6 | 5.16 | 2.31 | +2,336% | −94.9% | 13 | 58 | 21.2 |
| primary-base | 10,524 | 37.3 | 6.27 | 2.24 | +2,138% | −88.4% | 14 | 58 | 41.6 |
| cheat-3c | 11,204 | 31.5 | 4.95 | 2.28 | +2,081% | −89.5% | 14 | 57 | 20.6 |
| power-play | 3,926 | 32.3 | 8.00 | 2.78 | +2,336% | −90.0% | 10 | 29 | 28.1 |
| ALL | 39,531 | 33.5 | 5.68 | 2.33 | +2,336% | −94.9% | 27 | 169 | 27.1 |

**rs-only** (28,019 trades)

| Setup | Trades | Win% | Exp% | PF | Best | Worst | Max W-streak | Max L-streak | Stop-out% |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| vcp | 9,119 | 33.2 | 5.54 | 2.26 | +2,336% | −94.9% | 13 | 58 | 27.6 |
| primary-base | 8,551 | 36.9 | 6.72 | 2.28 | +2,138% | −88.4% | 12 | 58 | 44.0 |
| cheat-3c | 7,137 | 32.0 | 5.28 | 2.23 | +2,081% | −89.5% | 13 | 57 | 26.7 |
| power-play | 3,212 | 32.7 | 8.45 | 2.79 | +2,336% | −90.0% | 8 | 37 | 31.5 |
| ALL | 28,019 | 33.9 | 6.17 | 2.32 | +2,336% | −94.9% | 22 | 169 | 32.8 |

**turnover-only** (31,662 trades)

| Setup | Trades | Win% | Exp% | PF | Best | Worst | Max W-streak | Max L-streak | Stop-out% |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| vcp | 11,114 | 32.2 | 4.23 | 2.07 | +1,220% | −94.9% | 18 | 52 | 21.7 |
| primary-base | 8,908 | 37.2 | 5.12 | 2.02 | +1,377% | −54.9% | 16 | 50 | 40.3 |
| cheat-3c | 8,908 | 31.3 | 4.24 | 2.08 | +1,220% | −89.5% | 12 | 63 | 21.6 |
| power-play | 2,732 | 31.9 | 6.49 | 2.43 | +1,220% | −54.9% | 9 | 51 | 29.4 |
| ALL | 31,662 | 33.3 | 4.68 | 2.09 | +1,377% | −94.9% | 27 | 144 | 27.6 |

**rs-turnover** (22,714 trades)

| Setup | Trades | Win% | Exp% | PF | Best | Worst | Max W-streak | Max L-streak | Stop-out% |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| vcp | 7,438 | 32.6 | 4.57 | 2.04 | +1,220% | −94.9% | 13 | 50 | 27.9 |
| primary-base | 7,183 | 36.9 | 5.49 | 2.06 | +1,377% | −54.9% | 12 | 50 | 42.7 |
| cheat-3c | 5,793 | 31.9 | 4.58 | 2.06 | +1,220% | −89.5% | 12 | 57 | 27.7 |
| power-play | 2,300 | 31.9 | 6.89 | 2.45 | +1,220% | −54.9% | 8 | 45 | 32.5 |
| ALL | 22,714 | 33.7 | 5.10 | 2.09 | +1,377% | −94.9% | 20 | 140 | 33.0 |

Note the turnover floor caps the biggest winners (best trade drops from +2,336% to +1,377%) — that is
the small-cap multibaggers being filtered out.

### 5.3 Annual portfolio returns (8 slots, FIFO; trades taken in parens)

| Year | technical | rs-only | turnover-only | rs-turnover |
|---|--:|--:|--:|--:|
| 2015 | −9.3% (22) | −6.6% (20) | −8.5% (27) | −7.4% (26) |
| 2016 | +119.9% (48) | +141.6% (34) | +70.2% (42) | +69.8% (37) |
| 2017 | +36.4% (63) | +118.2% (54) | +55.9% (53) | +62.3% (48) |
| 2018 | −5.6% (85) | +3.3% (88) | +3.8% (87) | −8.3% (91) |
| 2019 | −17.5% (80) | −7.5% (80) | −20.8% (79) | −17.4% (79) |
| 2020 | +131.8% (41) | +145.7% (42) | +167.4% (42) | +114.6% (42) |
| 2021 | +61.0% (63) | +135.7% (40) | +252.4% (58) | +79.9% (61) |
| 2022 | +18.2% (96) | +219.2% (90) | +11.2% (119) | +50.8% (97) |
| 2023 | +72.6% (51) | +28.4% (46) | +30.4% (55) | +17.8% (47) |
| 2024 | +14.1% (73) | −9.7% (75) | +12.7% (81) | +0.8% (77) |
| 2025 | −1.0% (67) | −31.3% (78) | −15.6% (70) | −8.6% (73) |
| 2026 (YTD) | −17.4% (66) | −24.2% (70) | −45.4% (67) | −21.9% (73) |

Monster bull years (2016, 2020, 2021) and ugly corrections (2019, 2025, 2026 YTD). The strategy is
strongly regime-dependent — it prints outsized gains in trending markets and bleeds in choppy/down ones.

---

## 6. v4 — RS-priority vs FIFO slot allocation (PR #558)

v3 fills scarce slots first-come. But you don't pick trades by arrival order — you pick the strongest.
v4 adds RS-priority allocation: when a day's entries compete for slots, the highest-RS names win. Each
variant now reports both `portfolio` (FIFO) and `portfolioRsPriority`.

### 6.1 FIFO vs RS-priority (8 slots)

| Variant | FIFO CAGR | RS-pri CAGR | Δ CAGR | FIFO DD | RS-pri DD | FIFO Sharpe | RS-pri Sharpe |
|---|--:|--:|--:|--:|--:|--:|--:|
| technical | 27.97% | **43.96%** | **+16.0** | −27.8% | −62.0% | 0.84 | 0.90 |
| rs-only | 43.37% | **51.49%** | +8.1 | −53.0% | −51.3% | 0.96 | 0.89 |
| turnover-only | 27.28% | 32.90% | +5.6 | −60.7% | −45.2% | 0.65 | 0.72 |
| rs-turnover | 22.78% | **29.55%** | +6.8 | −41.8% | −52.9% | 0.70 | 0.74 |

**RS-priority full portfolio:** technical CAGR 43.96% / total +5,387% · rs-only 51.49% / +9,511% ·
turnover-only 32.90% / +2,179% · rs-turnover 29.55% / +1,621%.

**Findings:**
1. **RS-priority helps every variant (+6 to +16pp CAGR).** With a capacity-bound book, *which* signals
   you pick matters as much as which you gate — and RS is the right selection key.
2. **Biggest lift is on `technical` (+16pp).** When you don't gate on RS at entry, using RS to *pick
   among* the flood of signals recovers most of the RS edge at the allocation stage. RS pays off twice
   — as an entry gate and as an allocation tiebreak.
3. **It buys return at a small Sharpe cost** on `rs-only` (0.96 → 0.89): RS-priority concentrates into
   the hottest names → more return, more volatility. Drawdowns stay in the 45–62% band.
4. **Best CAGR:** rs-only + RS-priority = **51.5%** (DD 51%). **Best Sharpe:** rs-only + FIFO = 0.96.

**The live-relevant upgrade.** The live funnel *already* presents its buyable list **RS-ranked**, so
live allocation ≈ RS-priority. That bumps the realistic live expectation from the FIFO **22.8% CAGR to
~29.6%** (`rs-turnover` RS-priority). The backtest now validates both the RS *gate* and the RS
*ranking* the live system already uses.

### 6.2 RS-priority annual returns (trades in parens)

| Year | technical | rs-only | turnover-only | rs-turnover |
|---|--:|--:|--:|--:|
| 2015 | −9.3% (22) | −6.6% (20) | −8.6% (27) | −7.4% (26) |
| 2016 | +125.1% (42) | +118.2% (43) | +65.2% (41) | +67.4% (36) |
| 2017 | +138.9% (50) | +73.9% (53) | +143.0% (35) | +161.0% (38) |
| 2018 | +6.2% (91) | −19.4% (103) | −10.0% (98) | −16.0% (108) |
| 2019 | −34.0% (80) | −26.0% (80) | −31.8% (84) | −32.3% (84) |
| 2020 | +149.1% (51) | +151.7% (51) | +48.5% (55) | +60.6% (55) |
| 2021 | +155.1% (45) | +313.1% (45) | +265.4% (37) | +222.2% (43) |
| 2022 | +150.8% (83) | +137.2% (82) | +128.4% (90) | +80.6% (88) |
| 2023 | +74.7% (44) | +31.4% (44) | +7.8% (69) | +35.2% (65) |
| 2024 | +9.2% (85) | +52.1% (84) | +4.9% (80) | +17.7% (74) |
| 2025 | −26.2% (68) | +25.7% (66) | −4.5% (70) | −28.7% (74) |
| 2026 (YTD) | −28.6% (62) | −26.6% (62) | −24.5% (62) | −29.4% (62) |

RS-priority is higher-variance than FIFO — bigger bull years (rs-only 2021 +313% vs FIFO +136%) but
deeper corrections (2019 −26% vs FIFO −8%) — because it concentrates into the strongest momentum, which
both runs furthest and reverses hardest.

---

## 6b. v5 — net of transaction costs (PR #559)

The v4 result raised a sharp question: under RS-priority the turnover floor gives up ~22pp CAGR for ~0
drawdown benefit, so it looks like pure cost. But the backtest is **gross** — it charges nothing for
trading illiquid names, which is exactly where `rs-only`'s edge (and the floor's value) lives. v5 nets
each trade of round-trip cost = statutory (~0.25%) + half-spread + **market impact**, where impact per
side = `coeff · (orderValue / avgTurnover)` capped at 5%/side. Order value = book ÷ slots, so impact
scales with participation — illiquid names pay far more. Model config (this run): book **₹10 L**,
`impact-coeff` 0.10, cap 5%/side.

### RS-priority: gross vs net (₹10 L book)

| Variant | Gross CAGR | Net CAGR | Cost drag | Net DD | Net Sharpe |
|---|--:|--:|--:|--:|--:|
| technical | 43.96% | 28.80% | −15.2pp | −69.1% | 0.67 |
| **rs-only** | 51.49% | **38.76%** | −12.7pp | −65.8% | **0.75** |
| turnover-only | 32.90% | 28.25% | −4.7pp | −51.8% | 0.65 |
| **rs-turnover** | 29.55% | **24.82%** | −4.7pp | −57.0% | 0.66 |

**Verdict — does the turnover floor earn its 22pp?** At a ₹10 L book, **no.** `rs-only` net (38.8%)
still beats `rs-turnover` net (24.8%) by ~14pp and wins on Sharpe (0.75 vs 0.66). The cost model works
— the no-floor variants are haircut ~3× harder (rs-only −12.7pp vs rs-turnover −4.7pp), confirming the
illiquidity penalty — but it only closed ~8pp of the 22pp gross gap, not enough to flip the ranking.

**The answer is capital-dependent** (order value = book ÷ slots):

| Book size | Thin-name cost | Turnover-floor call |
|---|---|---|
| ₹1.5 L (pilot) | ~0.4% (negligible) | **Drop it** — `rs-only` wins by ~20pp; you can trade thin names |
| ₹10 L (this run) | up to the 5%/side cap | **Lower it** — floor costs ~14pp of CAGR |
| ₹50 L – 1 Cr+ | genuinely unfillable* | **Keep it** — thin trades can't be executed at size |

*The 5%/side impact cap *under*-charges the most illiquid trades (participation > 1× daily volume is
effectively unfillable, not 5% costly), so at a large book `rs-only`'s net is still optimistic — the
real gap narrows or flips.

Net drawdowns are 57–69% (worse than gross — costs add churn on every entry/exit).

---

## 6c. v6 — turnover-floor sweep (PR #560)

v5 showed the floor's value is capital-dependent but at one book size. v6 sweeps the grid: for each
(book size, turnover floor) it keeps the RS-gated signals above the floor and runs the net-of-cost
RS-priority portfolio. Trades taken are book-invariant per floor (book size changes only the cost, not
which signals fill the 8 slots), so the grid is pure cost sensitivity.

### Net CAGR % by book size (rows) × turnover floor (columns)

| Book ↓ / Floor → | ₹0 | ₹5 L | ₹10 L | ₹25 L | ₹37.5 L | ₹75 L | ₹1.5 Cr | ₹3 Cr | **Best** |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| ₹1.5 L | **45.5** | 29.3 | 34.1 | 34.9 | 20.7 | 20.5 | 24.3 | 26.6 | floor **0** |
| ₹10 L | **38.8** | 25.9 | 31.5 | 33.9 | 20.0 | 20.1 | 24.1 | 26.4 | floor **0** |
| ₹50 L | 28.7 | 13.7 | 20.2 | **29.3** | 16.6 | 18.1 | 23.0 | 25.8 | floor **₹25 L** |
| ₹1 Cr | 22.8 | 6.5 | 12.6 | 23.7 | 12.4 | 15.6 | 21.7 | **24.9** | floor **₹3 Cr** |
| ₹5 Cr | 7.2 | −9.2 | −3.7 | 6.3 | −5.1 | −0.6 | 11.8 | **18.6** | floor **₹3 Cr** |

**Findings:**
1. **The optimal floor rises monotonically with book size** — small book: no floor (thin names are
   cheap at small size); big book: high floor (thin names become cost-prohibitive). ₹1.5 L → floor 0
   (45.5%); ₹10 L → floor 0 (38.8%); ₹50 L → ₹25 L (29.3%); ₹1 Cr+ → ≥₹3 Cr (the grid max — the true
   optimum is likely beyond it).
2. **The current ₹37.5 L live floor is a poor choice at every book size** — it lands in a local-minimum
   band. At ₹10 L it yields 20.0% vs 38.8% (floor 0) or 33.9% (₹25 L): ~15–19 pp/yr left on the table.

**Caveat:** the row is non-monotonic (the ₹37.5–75 L band dips then recovers) — a few big-winner names
cluster at specific turnover levels and survivorship amplifies it. Treat the *rule* (floor scales with
book) as robust and the exact optimum as noisy. Also, this sweep is a portfolio-level (allocation)
filter over the rs-only signals, not a per-floor re-run of the setup books.

---

## 7. Synthesis + recommendation

- **Keep RS-rank ON.** It is the single most valuable filter — nearly doubles CAGR and gives the best
  Sharpe. The live funnel already gates on cross-sectional RS-rank; this validates it hard.
- **Size the turnover floor to your capital (v5/v6).** The optimal floor scales with book size: the
  v6 sweep gives floor 0 at ₹1.5–10 L (net CAGR 38–45%), ~₹25 L at ₹50 L, ≥₹3 Cr at ₹1 Cr+. The
  current ₹37.5 L live default is a local minimum at *every* book size (~20% at ₹10 L vs 38.8% at floor
  0) — **lower it well below ₹37.5 L for a pilot** (₹0–10 L), raise it as the book scales.
- **Pick candidates by RS-rank when slots are scarce (v4).** RS-priority allocation lifts every
  variant's CAGR by 6–16 points — the cheapest improvement available (pure allocation policy, no new
  data). The live funnel already RS-ranks its buyable list, so live behaviour already captures this.
- **Realistic live expectation (net of costs, RS-priority, ₹10 L book): ~25% CAGR for `rs-turnover`,
  ~39% for `rs-only`** — both with ~57–66% drawdowns. The gross figures (23–51%) are upper bounds;
  costs, survivorship, and the impact cap all cut the true number, most for the illiquid small-cap edge.
- **This is high-octane.** Budget for 40–50% drawdowns and long losing streaks before committing real
  capital. The forward live paper book (with the pinned 8%-stop + 50d-trail exits) is the real test; the
  backtest establishes the mechanics have edge, not the exact return you will realise.

---

## 8. Reproduce

```
# trigger an N-year run (background, ~30–45 min for the full 2×2)
POST /api/v1/market/screener/minervini/swing-backtest?years=11

# read the latest full-filter (rs-turnover) single report
GET  /api/v1/market/screener/minervini/swing-backtest

# read all four variants side by side (per-setup + FIFO + RS-priority portfolio)
GET  /api/v1/market/screener/minervini/swing-backtest/compare
```

Config (`artha.minervini.backtest.*`): `years` (11), `min-turnover` (₹3,750,000/day), `rs-min` (70),
`slots` (8), `capital` (₹1,000,000), `cost.fixed-pct` (0.25), `cost.spread-pct` (0.05),
`cost.impact-coeff` (0.10), `cost.impact-cap-pct` (5.0). Each variant's `/compare` report carries three
portfolios: `portfolio` (FIFO, gross), `portfolioRsPriority` (RS-priority, gross), and
`portfolioRsPriorityNet` (RS-priority, net of costs). Results persist to
`marketdata.minervini_backtest_runs` (one row per variant per run).
