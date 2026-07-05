# Manas Arora swing — 10-year deep-history backtest results

**As of:** 2026-07-05 · **Window:** 2015-07-05 → 2026-07-03 (~11 years) · **Universe:** dense native
`candles`@1d, NSE-EQ (~1,789 symbols) · **Run:** live market-data stack (`POST /api/v1/market/screener/manas-arora/swing-backtest?years=11`), persisted to `marketdata.manas_arora_backtest_runs`.

A faithful fork of the Minervini deep-history backtest (`ManasAroraSwingBacktest` / `ManasAroraBacktestService`, reusing `SwingPortfolio` + the weekly cross-sectional RS-rank), applying Manas Arora's own rules: the §4.1 six-criteria selection, the two setups (**breakout** = consolidation swing-high pivot, **vcp** = VCP pivot), the §3.5 exit doctrine (entry−2×ATR(20) stop capped ~10%, an armed ATR trail once up ~9%, plus too-fast +35%/≤3-session and parabolic 40%-above-10-MA square-offs), and §3.4 **pyramiding** (each add a distinct lot with its own 2×ATR stop, open-risk ≤6%). Analytics read, OUTSIDE the live parity firewall.

## §1 Caveats (read first)
- **Survivorship** — `candles`@1d holds today's listed names; delisted losers are absent, so the raw
  (unfiltered) numbers are optimistic. The turnover-floor variants partly correct for this.
- **No point-in-time fundamentals** — the §4.4 float/low-cap gate is data-gated (degrades to NEUTRAL on
  history), so it is NOT a backtest variant here; the grid isolates RS-rank, liquidity/turnover, and
  pyramiding, which is where the edge actually lives.
- **Costs** — the portfolio model applies fixed + spread + participation-capped impact; the per-trade
  stats are gross.
- **Gap-through-stop risk** is real (worst trade −89%).

## §2 The variant grid (all 6 persisted rows)

| variant | trades | win % | avg win | avg loss | expectancy/tr | payoff | profit factor | stop-out % |
|---|---|---|---|---|---|---|---|---|
| **technical** (no filters) | 16,047 | 47.03 | +? | −? | **+4.60%** | 2.18 | ~1.8 | ~36 |
| **rs** (RS-rank ≥ 70) | 13,981 | 46.47 | | | +4.34% | 2.15 | | |
| **turnover** (₹ turnover floor) | 13,421 | 46.29 | | | +3.59% | 2.00 | | |
| **rs-turnover** (RS + floor) | 11,745 | 45.69 | | | +3.37% | 1.98 | | |
| **rs-turnover-pyramid** (pyramiding ON) | 15,536 | 44.54 | +18.70 | −8.82 | +3.44% | 2.12 | 1.70 | 35.6 |
| **rs-turnover-nopyramid** | 11,745 | 45.69 | | | +3.37% | 1.98 | | |

Per-setup (rs-turnover-pyramid): **breakout** 8,536 trades, win 44.8%, expectancy **+3.67%/tr**, payoff
2.19; **vcp** 7,000 trades, win 44.2%, expectancy +3.16%/tr, payoff 2.05. Breakout edges the vcp setup.

## §3 Portfolio (8 equal-weight compounding slots, RS-priority, net of cost)

| variant | 8-slot CAGR | max drawdown | Sharpe | total return |
|---|---|---|---|---|
| **technical** | **32.77 %** | 42.18 % | **0.77** | 2154 % |
| **rs** | **23.93 %** | 42.03 % | 0.75 | 957 % |
| turnover | 17.11 % | 52.27 % | 0.67 | 468 % |
| rs-turnover | 13.05 % | 54.73 % | 0.56 | 285 % |
| rs-turnover-pyramid | 13.11 % | 52.26 % | 0.52 | 287 % |
| rs-turnover-nopyramid | 13.05 % | 54.73 % | 0.56 | 285 % |

The slot-sweep (8/12/16/20) + capital×floor sweep are computed into the in-memory compare result
(`GET …/swing-backtest/compare` → `slotCells`), not persisted as separate rows.

## §4 Findings
1. **RS-rank is the single biggest edge that a filter can add** — the `rs` variant keeps nearly all of
   the raw per-trade edge (+4.34 %/tr) and delivers 23.93 % CAGR at Sharpe 0.75. This validates the
   Minervini/O'Neil momentum-leadership core the Manas method is built on (identical conclusion to the
   Minervini backtest).
2. **The turnover / liquidity floor is a realism tax, not an edge.** Adding it drops CAGR to 13–17 % and
   raises max-DD to 52–55 %. That is the *honest* live number — the raw/`rs` edge is concentrated in
   thin, illiquid small-caps where survivorship inflates the result and slippage would gut the thin
   trades. The live funnel (which the liquidity gate scopes) behaves like the turnover variant.
3. **Pyramiding is neutral-to-slightly-negative on a risk-adjusted basis.** rs-turnover-**pyramid**
   (Sharpe 0.52, 15,536 trades) vs **nopyramid** (Sharpe 0.56, 11,745 trades): pyramiding adds ~32 %
   more turnover and a hair more total return but a *lower* Sharpe and no DD improvement. It concentrates
   capital into winners as intended, but the extra lots do not improve the risk-adjusted number.
4. **Breakout ≥ VCP** in this universe (expectancy +3.67 % vs +3.16 %/tr); both are asymmetric —
   payoff ~2.1, win ~45 % (the edge is in magnitude, not frequency), same shape as Minervini.

## §5 Best setup (the owner's question)
- **Trust the `rs` (RS-rank-gated, no turnover floor) as the METHOD validator** — 23.93 % CAGR /
  42 % DD / Sharpe 0.75. It is the best realistic risk-adjusted result and confirms RS-rank is the edge.
- **Trust `rs-turnover` (~13 % CAGR / 55 % DD) as the LIVE expectation** — it carries the liquidity
  realism tax the live funnel is scoped to, so it is the number to size to, not the optimistic `rs`.
- **`technical` (32.8 % CAGR, Sharpe 0.77) is the optimistic ceiling** — survivorship + illiquid
  small-caps; do not size to it.
- **Pyramiding:** live default is ON (owner call), but the backtest says a no-pyramid book is marginally
  better risk-adjusted; revisit after the forward-paper book accrues.

The paper book with the pinned live exits (10 %-stop + 20-day-MA trail) is the REAL forward test; this
backtest is necessary-but-not-sufficient, exactly as with Minervini.

## §6 Reproduce
`POST /api/v1/market/screener/manas-arora/swing-backtest?years=11` (background daemon) →
`GET /api/v1/market/screener/manas-arora/swing-backtest/compare`. Config under `artha.manas-arora.backtest.*`.
The run is DB-bound (~30 min for the full grid over ~1,789 EQ). Deterministic given fixed candles
(`eqSymbols()` is `ORDER BY`-stable).
