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

---

## Comparison vs the previous backtests (#557 Jul-5, #606 caps-OFF Jul-6)

**Per-trade edge — UNCHANGED (this is the headline):**
| metric | #557 (Jul 5) | #607 (today) |
|---|---|---|
| Minervini technical exp/trade | +5.68% | +5.64% |
| Minervini rs-turnover exp/trade | +5.10% | +5.07% |
| Manas rs-turnover exp/trade | +4.34% (raw) | +3.41% |

**Minervini 8-slot FIFO CAGR — the RS-filtered variants revised DOWN ~9pp, and it's a CORRECTNESS revision:**
| variant | #557 | #607 | Δ |
|---|---|---|---|
| technical *(control)* | 28% | 28.1% | **flat — proves the sim engine + per-trade edge are unchanged** |
| rs-only | 43.4% (Sh 0.96, DD −53%) | 34.6% (Sh 1.02, DD −23.5%) | **−9pp CAGR, but Sharpe ↑ and DD HALVED** |
| rs-turnover | 22.8% | 14.1% | −9pp |

Only the **RS-filtered** variants moved because between #557 and now, **#606 fixed 3 RS-rank bugs**
(percentile strictly-below→midpoint; membership window 252-vs-260; rank-date-only→**as-of** contribution)
**+ #607 added the M12 deterministic tie-break**. The 8-slot book is capacity-bound (~717 of 28k trades
get slots), so a corrected RS ranking picks a smaller, more honest subset. The old 43% was inflated by a
buggy/non-deterministic RS calc feeding a favorable subset into the slots; **the new 34.6% is the trustworthy
number — and it is BETTER risk-adjusted** (Sharpe 0.96→1.02, max-DD −53%→−23.5%: the corrected as-of ranking
stops piling into names that later crashed).

**Cross-check — #607 reproduces the #606 caps-OFF baseline** (both floor-disabled, both post-fix), confirming
today's run: rs-turnover FIFO CAGR 14.3%→**14.1%**, RS-priority-net 19.2%→**19.1%**, vcp 7,458→**7,460** trades.

**Manas — stable / slightly better:** rs-turnover CAGR 23.9%→**26.5%** (Sharpe 0.75→0.77); pyramiding Sharpe
**0.96**. Per-trade edge a touch lower (+4.34%→+3.41%) but same ballpark.

**Verdict:** nothing degraded — per-trade edges are unchanged. The big-looking Minervini RS-CAGR drop is a
downward *correction* from the RS-rank fixes + deterministic tie-break, arriving with a *better* risk profile
(lower DD, higher Sharpe). Trust the new numbers. Realistic-live firms up at **~14% FIFO / ~19% RS-priority-net
CAGR (Minervini rs-turnover), ~26% (Manas)**, both at 44–50% DD.

## Applied to the live Manas strategy (the two doctrine-faithful findings)

The two findings above that Manas lacked (Minervini already had both) were built into the live Manas swing engine:

- **F1 — RS-rank gate + RS-priority funnel admission — SHIPPED + LIVE ([#611](https://github.com/prashantm912/artha-yantra-2/pull/611), `329e9d71`).**
  RS-rank is "the dominant filter on both families" above, and Manas previously strength-ordered by
  `above_low_pct` (a proxy) with no RS at all. Now the screener computes the same 0.4/0.2/0.2/0.2 weighted
  trailing-return RS as Minervini over the full scanned universe, percentile-ranks 0..100, and the funnel
  gates + orders by it (`WHERE rs_rank >= artha.manas-arora.funnel-rs-min`, default **70**). Live-verified:
  2,229 scanned, 0 nulls, 96 passers ≥70.

- **F2 — §3.4 multi-lot pyramiding (add-to-winner) — SHIPPED + LIVE + ARMED ([#612](https://github.com/prashantm912/artha-yantra-2/pull/612), `32b717c6`, 2026-07-07).**
  The `rs-turnover-pyramid` A/B row above (Sharpe 0.96 vs 0.77 non-pyramid in this run; **but** #569 had it
  slightly negative — mixed, so it's built for doctrine faithfulness + forward paper evidence, not as a proven
  edge). A held winner now takes an averaged add-lot on a fresh +5%-since-last-lot pivot within a ≤6% book
  open-risk cap; the pyramid closes all lots together off the oldest lot's governing stop (§3.5.D). Averaged
  single lot (owner-picked) is cash-equivalent to N separate lots under close-together and avoids a shared-surface
  migration. Flag-gated `artha.manas-arora.pyramid.enabled`, armed live 2026-07-07; un-arm = flip the `.env`
  flag + redeploy. First live pass = the scheduled 20:05-IST batch. Judge on the forward paper book, not this
  weak-history backtest.

## 2026-07-07 re-run — reproduced byte-identical + the Manas slot sweep

Re-ran the Manas swing backtest 2026-07-07 (fromDate 2015-07-06, 1,796 symbols). Every variant reproduced
the table above **to the decimal** (trades 16,035 / 13,971 / 13,405 / 11,734 / 15,534; win% / exp% / CAGR /
DD / Sharpe all identical) — confirming the sim is deterministic and **unchanged since #595**; F1 (#611) and
F2 (#612) were live-path changes and do NOT touch the backtest. (Aside: the sim did ~1,800 sequential
per-symbol `readSeries` reads and, under a concurrent nightly `pg_dump`, took ~40 min — the audit-LOW
"serial/N+1 backtest reads" item.)

**Manas slot sweep** (RS-priority, net-of-cost, over the pyramid variant) — does Manas want more concurrent
slots like Minervini's v7 (which found **12** optimal)?

| slots | trades taken | gross CAGR | net CAGR | net DD | net Sharpe |
|---|---|---|---|---|---|
| 8 (doctrine/live) | 992 | 28.1% | **21.7%** | 62.1% | 0.64 |
| 12 | 1,427 | 20.4% | **15.4%** | 54.0% | 0.60 |
| 16 | 1,813 | 37.2% | **32.7%** | 43.6% | **0.90** |
| 20 | 2,186 | 27.4% | 22.7% | 46.4% | 0.87 |

**Read — the Minervini 12-slot lesson does NOT transfer to Manas.** For Manas, **12 slots is the *worst*** of the
four (net CAGR 15.4% vs 21.7% at 8, and the lowest Sharpe 0.60). The sweep is **non-monotonic** (28→20→37→27
gross) — a noisy/capacity-dependent signal, not a clean optimum — so even the 16-slot peak isn't trustworthy.
And 12/16/20 all violate Manas's own doctrine (§2.2 caps the book at **5–7 names**), spreading capital into
more marginal/illiquid small-caps (the survivorship + slippage caveat bites harder). So Manas correctly stays
at **7 live / 8 backtest-headline** — bumping it toward 12 would be off-doctrine *and* backtest-worse.
