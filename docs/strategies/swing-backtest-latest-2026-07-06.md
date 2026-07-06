# Minervini & Manas swing backtest — latest deployed code (2026-07-06)

Fresh event-driven swing backtests on the **live-deployed #607 code** (min-base-weeks floor DISABLED /
depth+65wk caps active; M12 deterministic RS tie-break; M35 liquidity 50×). Both over `candles`@1d,
**~11 years (2015-07-06 → 2026-07-06), 1,796 symbols**. Exit doctrine: 8%-stop → 50d-MA trail (Minervini)
/ 2×ATR trail (Manas), scaled tiers. Portfolio = 8 concurrent compounding slots.

Triggered via `POST /api/v1/market/screener/{minervini,manas-arora}/swing-backtest`; single-permit gated so
run sequentially (Minervini ~30 min → Manas ~30 min). Numbers are per-trade edge + the 8-slot portfolio.

## Minervini — 4 variants (RS-rank ✕ turnover-floor isolation)

| variant | trades | win% | exp%/trade | 8-slot FIFO CAGR | DD | Sharpe | RS-priority-NET CAGR |
|---|---|---|---|---|---|---|---|
| technical (no filter) | 39,551 | 33.5 | **+5.64** | 28.1% | 29.0% | 0.97 | 23.2% |
| **rs-only** | 28,099 | 33.9 | **+6.12** | **34.6%** | **23.5%** | **1.02** | 25.4% |
| turnover-only | 31,682 | 33.3 | +4.64 | 16.2% | 50.1% | 0.61 | 18.3% |
| **rs-turnover** (live analogue) | 22,793 | 33.6 | **+5.07** | 14.1% | 43.9% | 0.59 | **19.1%** |

Per-setup expectancy (technical): **power-play +7.94%** · primary-base +6.27% · vcp +5.10% · cheat-3c +4.90%.

**Read:** RS-rank is the edge — `rs-only` is the best portfolio (CAGR 34.6%, Sharpe 1.02, lowest DD 23.5%),
validating the Minervini core. The turnover floor is a realism/liquidity tax (cuts CAGR to 14–16%), but the
LIVE funnel ≈ `rs-turnover`, so trust **~+5.1%/trade, ~19% CAGR (RS-priority-net) / ~14% FIFO at ~44% DD**.
`rs-only`'s 34.6% is the optimistic upper bound (illiquid small-cap edge).

## Manas Arora — 6 variants (+ pyramiding A/B)

| variant | trades | win% | exp%/trade | 8-slot FIFO CAGR | DD | Sharpe | RS-priority-NET CAGR |
|---|---|---|---|---|---|---|---|
| technical (no filter) | 16,035 | 47.1 | +4.63 | **45.0%** | 50.1% | 0.63 | 36.0% (Sh 0.92) |
| rs | 13,971 | 46.5 | +4.37 | 21.7% | 37.3% | 0.89 | 25.6% |
| turnover | 13,405 | 46.4 | +3.62 | 12.6% | 50.5% | 0.61 | 21.5% |
| **rs-turnover** (live analogue) | 11,734 | 45.8 | +3.41 | 26.5% | 50.3% | 0.77 | 11.8% |
| rs-turnover-pyramid (add-to-winner) | 15,534 | 44.6 | +3.45 | 26.3% | 49.9% | **0.96** | 21.7% |

Per-setup expectancy (technical): vcp +4.72% · breakout +4.55%.

**Read:** Manas wins **more often** (46–47% vs Minervini's 33%) but with **smaller per-trade edge** (+3.4–4.6%
vs +5–6%) — the classic momentum asymmetry inverted between the two families (Minervini = lower hit-rate /
bigger winners; Manas = higher hit-rate / smaller winners). Technical CAGR 45% carries a 50% DD; **pyramiding
(add-to-winner) lifts the rs-turnover Sharpe 0.77 → 0.96** — the one variant tweak that improves risk-adjusted
return. Live analogue `rs-turnover` ≈ **+3.4%/trade, ~26% CAGR / ~50% DD**.

## Verdict (both, latest code)
- **Edges intact** — the #607 min-base-weeks-disable kept the VCP population, so these match the pre-M39
  baseline (#556/#557): Minervini ~+5%/trade, Manas ~+3.5–4.6%/trade. M39's guillotine is correctly gone.
- **RS-rank is the dominant filter** on both families (Minervini rs-only Sharpe 1.02; Manas rs Sharpe 0.89).
- **Realistic live numbers** (`rs-turnover`, the funnel analogue): Minervini ~14–19% CAGR / ~44% DD;
  Manas ~26% CAGR / ~50% DD. **Drawdowns are large (24–63%)** — aggressive momentum sleeves; slot/position
  discipline is what makes them tradeable.
- Both fed by the live daily EOD swing batch (fires 20:00/20:05 IST) — the FORWARD paper book is the real
  reliability test; these backtests are the historical edge estimate.

*Run 2026-07-06 ~20:20–21:22 IST on the deployed image (main @ #607). `GET .../swing-backtest/compare`
holds the full per-variant portfolio + annual returns + slot/turnover sweeps.*
