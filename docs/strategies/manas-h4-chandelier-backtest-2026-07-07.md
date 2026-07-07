# Manas Arora — H4 canonical Chandelier trail: backtest before/after (2026-07-07)

**Audit H4** found the live Manas 2×ATR trail and the deep backtest computed the trail three different
ways, so the go-live evidence was measured under exits that don't match live. Owner call: make **BOTH**
engines faithful to the doctrine (a canonical **Chandelier**), then re-run the backtest.

## The canonical trail (now identical doctrine in both engines)
`highest-HIGH since entry − 2×ROLLING-Wilder-ATR(20)`, ratcheted monotone-up, **floored at breakeven**
(cost basis) once armed, **armed at +9%** off the high, **exit on the CLOSE** (EOD closing basis).

- **Before**, live was: highest-high anchor + **ENTRY-pinned** ATR + **no** floor.
- **Before**, backtest was: **close** anchor + rolling ATR + floor.
- **Now**, both: highest-high anchor + rolling ATR + floor + close exit + +9% arm.

> Parity note: the two engines are now **doctrine-equivalent** (identical rules, agree on exit
> decisions) but **not byte-identical** — the deep sim is `double[]`, the live engine is ta4j
> DecimalNum-32 (BigDecimal), so the rolling ATR differs sub-tick (converges long before the 260/520-bar
> warmup). True byte-parity would need re-hosting the ~11yr×1800-symbol sim on the BigDecimal engine.

## Backtest re-run vs previous (deep sim, 2015-07-06 → 2026, ~1,800 EQ)

| variant | trades (was → H4) | exp%/tr (was → H4) | FIFO CAGR (was → H4) | RS-prio-net CAGR (was → H4) | max-DD (was → H4) | Sharpe (was → H4) |
|---|---|---|---|---|---|---|
| technical | 16,035 → **18,111** | 4.63 → **3.19** | 45.0 → **30.2** | 36.0 → **33.3** | 50.1 → **46.5** | 0.63 → **0.98** |
| rs | 13,971 → **15,667** | 4.37 → **3.08** | 21.7 → **37.2** | 25.6 → **32.8** | 37.3 → **40.0** | 0.89 → **1.18** |
| turnover | 13,405 → **15,161** | 3.62 → **2.35** | 12.6 → **18.7** | 21.5 → **24.3** | 50.5 → **49.4** | 0.61 → **0.74** |
| **rs-turnover** (live analogue) | 11,734 → **13,198** | 3.41 → **2.22** | 26.5 → **45.0** | 11.8 → **23.8** | 50.3 → **50.8** | 0.77 → **0.98** |
| rs-turnover-**pyramid** | 15,534 → **16,416** | 3.45 → **2.03** | 26.3 → **16.7** | 21.7 → **12.9** | 49.9 → **60.6** | 0.96 → **0.61** |

*H4 run 2026-07-07 05:22 IST on the deployed image (main @ #594) + the H4 branch (`c5302423…59de2b03`).
Previous = `docs/strategies/swing-backtest-latest-2026-07-06.md`.*

## What the Chandelier does to the edge
1. **Per-trade edge SHRINKS** (rs-turnover **+3.41% → +2.22%/trade**) — the highest-high, breakeven-floored
   trail cuts winners shorter than the old close-anchored, entry-pinned, unfloored trail. Trade count is
   up ~12% (tighter trail → faster round-trips into fresh setups).
2. **…but risk-adjusted returns IMPROVE broadly** — **Sharpe rises on every non-pyramid variant**
   (technical 0.63→0.98, rs 0.89→1.18, rs-turnover 0.77→0.98). The **live-analogue rs-turnover** portfolio
   CAGR nearly doubles (FIFO 26.5→45.0%, RS-priority-net 11.8→23.8%) at a similar ~50% DD.
3. **technical** trades fat-tail CAGR (45→30%) for a much smoother curve (Sharpe 0.63→0.98).
4. **Pyramiding turns COUNTERPRODUCTIVE under the Chandelier** — the pyramid variant is the ONLY one
   that worsens (FIFO 26.3→16.7%, Sharpe 0.96→0.61, DD 49.9→**60.6%**). A tight highest-high Chandelier
   trail stops out the pyramid adds fast. **F2 pyramiding + a Chandelier trail do not mix.**

## Owner decision points
- **The live-relevant number improves.** The funnel-analogue rs-turnover reads **~+2.2%/trade, ~45% FIFO
  CAGR / ~24% RS-priority-net, Sharpe ~0.98** under the doctrine-faithful exits — better risk-adjusted
  than the old ~+3.4%/tr / 26.5% it was being measured against. The forward-paper A/B baseline moves to
  these numbers.
- **Reconsider live pyramiding** (`ARTHA_MANAS_ARORA_PYRAMID_ENABLED`, currently armed per F2): under the
  Chandelier it is a drag (−10pp CAGR, +11pp DD, Sharpe halved). If H4 deploys live, strongly consider
  disarming pyramiding, or A/B it fresh — the F2 evidence was under the old trail.
- **Disclosed approximations:** (a) the double-vs-BigDecimal ATR (sub-tick); (b) the pyramid breakeven
  basis (backtest uses the FIRST lot's cost, the live averaged single-lot uses the weighted average —
  inherited from the F2 averaged-vs-per-lot model, not new here).

## Decision (2026-07-07 — owner approved both recommendations)
Owner: *"deploy chandelier and disarm pyramid on both live and back."* Both recommendations improve
risk-adjusted performance, so:
1. **Chandelier deployed live** — the canonical trail is now the operative live-paper Manas exit
   (`ExitEvaluator` rolling-ATR/highest-high/+9%-arm/breakeven-floor/close-exit) via strategy-signal.
2. **Pyramiding disarmed** —
   - **Live**: `ARTHA_MANAS_ARORA_PYRAMID_ENABLED=false` (was `true`); the live engine reverts to
     single-lot. F2 pyramiding stays code-present but un-armed (reversible `.env` flip).
   - **Backtest**: the headline/primary variant flips `rs-turnover-pyramid → rs-turnover-nopyramid`
     (`ManasAroraBacktestService.PRIMARY_VARIANT` + the FE `ManasAroraBacktestPage`). The pyramid
     variant is retained in the run set purely as a labeled A/B probe (ongoing regression evidence that
     it degrades Sharpe under the Chandelier), not the operative config.

## Status
Built + 2-reviewer adversarial PASS + tested (37 strategy-engine incl. a new Chandelier regression test,
15 Manas strategy-signal, goldens byte-identical). Shipped in #628 (rebased on main); market-data +
strategy-signal + frontend rebuilt and redeployed on the merged build. Live-verified: running sha == HEAD,
pyramid flag off in the strategy-signal container, Manas strategies loaded and the trail level computes.
