# S24-W4-IMPLEMENTATION — disposition of the 27 PARAM candidates (built vs deferred)

Follow-up to [S24-W4-TRIAGE.md](S24-W4-TRIAGE.md). The triage isolated **27** un-wired deterministic
PARAM candidates from the 95 S24-only adds; the owner asked to **build all 27 + the OIP-AI 6c
surfacing**. This doc records what was actually built, what is deferred, and **why** — each disposition
verified against the live code by an adversarial multi-agent pass (a `general-purpose` panel + skeptic,
2026-06-27).

**The hard constraint that shapes everything:** a candidate is buildable as a **parity-safe default-OFF
scalper gate** only if its inputs are available **point-in-time at the confluence seam**
(`ScalperConfluenceGate.evaluate` — `Chart{close, vwap, vwma20, psar, supertrendDir(±1 only), rsi14,
volume}` + `Oi{quadrants, deltas, imbalance%, crossedThisWindow, spurt%}` + `Macro{vix, breadth, fii…}`
+ the index-future OHLCV series). It is **not** buildable there if it needs: a SuperTrend price *level*
(only ±1 exists), a *daily* RSI series, a *positional/second* OI window, **cross-bar session state**, an
**exit-side** hook (the seam is entry-only), or a **new market-data read**. Forcing those into a
half-faithful gate would violate the architecture + the simplicity-first principle, so they are deferred
with the precise seam + what-it-takes.

## Tally (27 + 6c)

| Disposition | Count | Meaning |
|---|---:|---|
| **BUILT** (default-OFF tag) | **7** | merged, parity byte-identical, owner arms per-strategy |
| **6c surfacing** | shipped (3 of 4 surfaces) | signal side-channel + scalp alert + Cockpit badge; EOD page-column deferred |
| **Buildable-but-deferred** | 3 | clean but marginal/redundant — available on request |
| **DEFER** (live-mgmt / engine-expansion) | 13 | needs a new read / daily series / session-state / exit hook / ST-level |
| **CONFIG_NOT_CODE** | 3 | a DB-row / data-sourcing decision, not a gate |

---

## 1. BUILT — 7 parity-safe default-OFF tags

| # | Tag | S24 rule | What it does | PR |
|---|---|---|---|---|
| #5 | `indicator-distance-veto` | §3.10 "indicators far from candles = avoid" | block when price ran >1.5% from the nearest of vwap/vwma/psar (overextension) | [#258](https://github.com/prashantm912/artha-yantra-2/pull/258) |
| #10 | `divergence-vol-gate` | Day-21 counter-trend confirm | require a ~125k bar regardless of the index floor | [#258](https://github.com/prashantm912/artha-yantra-2/pull/258) |
| #3 | `herozero-side-oi` | Day-17 per-side OI | raise the Hero-Zero PUT-side OI "real-move" floor to 70% (CE stays 50%) | [#259](https://github.com/prashantm912/artha-yantra-2/pull/259) |
| — | `overbought-defer` | §3.1 "RSI>85 → defer" | stand a CE buy aside at rsi≥85 / PE at rsi≤15 | [#260](https://github.com/prashantm912/artha-yantra-2/pull/260) |
| — | `directional-change-gate` | Day-20 directional-change precondition | enter only when the OI PE-CE tilt crossed this window | [#260](https://github.com/prashantm912/artha-yantra-2/pull/260) |
| — | `s24-trade-window` | Shared-S2 "09:45-14:30" | swap to a single 09:45-14:30 window (no midday block, hard 14:30 cap) | [#261](https://github.com/prashantm912/artha-yantra-2/pull/261) |
| — | `gap-size-side-gate` | #9 "300-400 gap-down → no-put" | suppress the PE side on a ≥300pt session gap-down | [#261](https://github.com/prashantm912/artha-yantra-2/pull/261) |

Every one: WORLD-2 scalper gate, absent on all 36 YAMLs ⇒ `GoldenDeterminismTest` 5/5 + `BacktestParityTest`
5/5 byte-identical. Adversarial faithfulness notes (all conservative, none a defect): #3 collapses the
graded "70-78%+85%" band to a single 70% floor; #10 implements the single-bar 125k half (not the
multi-red-bar counter); #5's cluster is vwap/vwma/psar (the seam has no EMA and ST has no level).

## 2. 6c — OIP-AI Open=High probability surfacing

The W3 PR-5 model (`OpenHighLow.probabilityPct` HIGH 90 / MILD 60 / LOW 30 / 0; `badge = HIGH`) is now
**surfaced live** ([#262](https://github.com/prashantm912/artha-yantra-2/pull/262)) wherever the read is actionable:
- **Signal side-channel** — `{oh_tier, oh_prob_pct, badge}` on the `scalper_detail` JSON + the
  `SignalEmitted.ScalpDetail` event (open-high-low strategies only; null otherwise).
- **Scalp alert** — the ntfy/telegram body appends `· OIP <tier> <pct>%`.
- **Scalping Cockpit** — an `OIP-AI <tier> · <pct>%` badge on the ticket panel (HIGH gets the ring).

**Deferred (with rationale): the Open=High EOD *page* column.** That page is served by market-data's
`OpenHighStrategyService`, which computes a *different* number (EOD historical-frequency %) from daily
rows and has **no intraday OH/OL footprint** to grade the OIP-AI tier. The tier grader (`OpenHighLow.tier`)
lives in strategy-signal. Surfacing it on the page would require a cross-service tier grader / new
endpoint — disproportionate to a presentational column, and the live surfaces above already carry the
read to where the trade decision is made.

## 3. Buildable-but-deferred (clean, but marginal / redundant)

| Candidate | Why deferred (not built) |
|---|---|
| round-strike weighting (StrikePicker) | a fuzzy tie-break; "round" is index-step-dependent (NIFTY 50-step vs SENSEX 100-step) and the picker has no step context; faithful test is fiddly. Buildable as a `pick(...,preferRound)` overload on request. |
| gap-volume-validity (GapTheoryGate) | **redundant** — the §0B deploy-bar volume floor (`ScalperGates.volume`) already runs *before* `GapTheoryGate` in the seam, so the fill bar's volume is already gated. |
| rsi-cooloff (full §3.1 pullback re-entry) | **redundant** with the shipped `overbought-defer` — once a later bar's RSI cools back in-band the same gate naturally passes. The fuller 2-bar detector (`bank.previousValueAt`) is buildable but adds no behaviour the block-half lacks. |

## 4. DEFER — live-management / engine-expansion (13)

Each needs an input/subsystem the entry seam cannot supply. Seam + blocker:

| # / candidate | Blocker (verified in code) |
|---|---|
| #2 daily-RSI caps | needs a **daily** RSI series; `Chart` carries only the 3m `rsi14`. Requires a new `rsi@1d` YAML indicator + a `Chart.rsiDaily` field (backlog `rsi-multi-timeframe.md`). |
| #7 OI-dual-timeframe (5cr/10-12cr) | needs a **second/positional** OI window + **absolute crore** OI; the seam has one 20-bucket window of signed deltas/percentages. New market-data read. |
| #8 fake-crossover (re-cross exit + 2-3-cross avoid) | (a) **exit-side** hook (seam is entry-only); (b) a **session-cumulative cross count** — only `crossedThisWindow` (a single bool) exists. |
| #12 OI-gap-exit (<50-60K) | explicitly an **exit** rail; `evaluate` returns an entry `Decision`, no exit branch (`ExitEvaluator` owns exits). |
| #4 BTST carry-validity | `style:btst` goes through `SignalEngine.preCloseEvaluate`, **never** the confluence gate; also needs an ST *level* + hold-into-close session state. |
| #9 combined-premium-VWAP (straddle) | needs a **combined-premium series + its VWAP**; the seam carries only the index-future chart. The code already defers it to live management (`ScalperConfig` javadoc). |
| profit-protection exit (≥80-90% recovered) | **exit-side**, acts on an open position's peak-vs-current (no position state in the entry seam). |
| trailing-prev-candle SL mode | **exit-side** trailing mode; needs cross-bar held-position state (`ExitEvaluator.trailing`). |
| #6 ST↔VWAP no-trade zone | needs the SuperTrend **price level**; the indicator emits only ±1 (`Ta4jIndicators.supertrendDirection` discards the band value). |
| #11 VWAP-anchor-switch ~10:30 | needs a **yesterday-VWAP** level (only today's session VWAP exists, reset daily) + it is an SL/exit concern. The before-10:30 *today*-VWAP suppression is already built via `opening-tick`. |
| recovery-candle-count (≥50K × 3 candles) | needs a **per-strike OI/premium recovery sequence** across 3 prior bars; the seam has a single point-in-time per-strike footprint. |
| Day-20 directional-change-**quadrant** | needs a session-scoped **quadrant-transition** tracker (cross-bar state); the seam has only the current quadrant + `crossedThisWindow` (a different signal — already used by `directional-change-gate`). |
| Sensex-participation gate | needs a **SENSEX-vs-NIFTY participation/turnover** comparator (exchange aggregates) — a new read; routed to FU1 as a manual check. |

## 5. CONFIG_NOT_CODE (3)

| Candidate | Disposition |
|---|---|
| #1 daily-loss cap 10-12% | `RiskService` is a **global, DB-row, pct-capable** gate (already pct-capable since W4-PR1). Arm the 10-12% cap by setting the `daily_loss_limit` row to `{enabled:true, mode:"pct", value:11}` — **no code**. Only the owner ruling on the number is open (a risk decision). |
| FII-LSR-primary (drop participant matrix) | the seam has **no participant matrix** to drop — `Macro.fiiLongPct` is a single scalar consumed by no gate. A data-sourcing choice (which FII feed drives the scalar), not a new gate. |
| 15-strike chain-read width | parameterizes the **upstream market-data** chain sampling, not a confluence gate; the seam receives already-collapsed OI primitives. A market-data endpoint param if ever wanted. |

---

## 6. Arming guide — go-live is the owner's call, after forward-paper

Every BUILT gate + the 6c surfacing is **INERT in production** until the owner arms it. To go live, add
the tag to a strategy YAML's `tags:` array (the 6c surfacing auto-applies to any `open-high-low`
strategy as soon as the PR ships — it changes no gate, only adds `{oh_tier, oh_prob_pct, badge}`):

```
tags: [ scalper, options_of_underlying, …,
        indicator-distance-veto,      # #5  overextension veto
        divergence-vol-gate,          # #10 125k counter-trend confirm
        herozero-side-oi,             # #3  PUT-side 70% OI floor (hero-zero only)
        overbought-defer,             # RSI>85 / <15 stand-aside
        directional-change-gate,      # require an OI tilt cross
        s24-trade-window,             # 09:45-14:30, no midday block
        gap-size-side-gate ]          # ≥300pt gap-down ⇒ no-put
```

**Validate each armed tag on FORWARD PAPER with real captured OI**, never a derived-history backtest
(the scalper-tuning findings: backtests overfit; derived-history mutes the OI/Dow factors). The
strike/premium/SL-style tags have **zero backtest effect** by construction (live StrikePicker only).

This closes the S24-incorporation chain end-to-end: **COMPARISON → RATIFICATION-PACK → W1 operative doc
+ OIP-AI model → W2 backlog prune → W3 (6 engine drifts) → W4 (95-add triage → 7 gates built + 6c
surfaced + the rest dispositioned).**
