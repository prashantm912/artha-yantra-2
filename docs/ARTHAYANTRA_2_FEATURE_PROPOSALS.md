# ArthaYantra 2.0 — Functionality Gap Analysis & Feature Proposals

**Date:** 2026-06-12
**Status:** Owner selection ratified 2026-06-12 and incorporated into the design docs (`docs/phases/`, content tagged `[FP-N, owner selection 2026-06-12]`; ADR amendments A7–A12; new phases 9A, 15A, 15B, 16A, 30A, 32A, 40A, 42A, 42B, 43A, 43B, 44A — 60 phases total). *(Amendment A13, also 2026-06-12, subsequently switched the main-chart renderer to lightweight-charts, added Phases 40B/40C and re-scoped 40/40A — 62 phases total; see COMMON §6.)*
**Selection:**
- **Mandatory (incorporated):** 1, 2, 3, 4, 5, 6, 7, 8, 19
- **Recommended (incorporated):** 9, 10, 11, 12, 14, 18, 20, 31, 32, 41, 42, 43, 66, 67
- **Future (listed in COMMON §21.1, no design detail — separate plan later):** 13, 15, 16, 17, 21–30, 33–40, 44–65, 68–81
**Provenance:** Produced from a full read of the 8 design docs in `docs/phases/` plus a 21-agent analysis (9 trading/architecture lenses → merge/dedupe → adversarial verification of every suggestion against the docs → completeness critic). The result: **81 proposed additions/modifications**, every one verified as *not already in the plan*, checked against the deliberately-deferred list (COMMON §21), and checked for feasibility under the hard constraints (Kite-only data, 8 GB RAM, signals-only posture). None requires a new always-on container.

**The headline finding:** the plan is excellent at its core loop (YAML strategy → live signals → backtest/optimize → paper), but it has one *structural* style gap — **futures are data-plumbed but have zero analytics, costs, or rollover handling** — and a handful of items that are really **correctness defects, not optional features** (Tier 1 below). Also, a few items **must be decided before the Phase 18 schema freeze** or they'll cost an ADR amendment later.

What was checked and **not** re-suggested (already well covered by the plan): BTST/intraday/expiry-day session styles, 5 position-sizing methods, momentum/long-term screener, watchlists, walk-forward + regime attribution + stress-test guards, Indian statutory cost model, paper/backtest fill parity, ntfy/Telegram notifier, Tailscale phone access, backup/restore.

**Legend:** priority (High/Med/Low) · complexity (S = days, M = ~one phase, L = multiple phases). ⚠ = scope change (breaks a stated constraint; owner decision required).

---

## ⏰ Decide-before-Phase-18 (schema freeze deadline)

These touch `strategy-schema/v1`, which freezes at the Stage C exit. Adopting them later means a recorded ADR amendment; deciding now is nearly free since the project is still in design planning:

- **#19 Cross-instrument context indicators** (the load-bearing one — enables NIFTY-trend / VIX gates and #20)
- **#6 BTST `fill_timing` knob** (at-close fills)
- **#8 Weekly (1w) timeframe** in the `timeframes` enum
- **#81 Two-leg pair-signal extensibility checkpoint** — don't build pairs trading, just ensure the signal/session shapes don't make two-leg signals a breaking v2 later (Low · S as a design check)

---

## Tier 1 — Correctness fixes (treat as plan amendments, not optional features)

1. **Corporate-action detection + candle-cache re-adjustment** (High · M) — The plan's "closed bars immutable, re-fetch only trailing 2h" rule means a split/bonus silently corrupts every cached candle vs Kite's back-adjusted history — poisoning multi-year backtests and indicators. Add an EOD anchor-close diff job that detects ratio divergence, purges/re-backfills the symbol, refreshes aggregates, and bumps `dataHash` so pre-event runs are flagged not-like-for-like.
2. **Derivative paper-position expiry settlement + rollover prompts** (High · M) — The paper ledger's only lifecycle sweep is the 15:45 intraday mark-to-close; a positional paper option held through Tuesday expiry stays OPEN forever with frozen P&L. Add expiry-day cash settlement at intrinsic, expiry STT leg, physical-settlement warning for stock F&O, and a "expires tomorrow — roll or close?" push.
3. **F&O contract-spec (lot size) history** (Med · M) — The 08:30 instrument sync is overwrite-only; NIFTY's lot size has changed repeatedly, so multi-year F&O backtests size 2022 trades with today's lot. Accrue lot/tick-size history by diffing daily syncs; replay resolves lot size as-of trade date.
4. **Options-backtest fidelity contract** (High · M) — "Options strategies replay `options_chain_snapshots`" is one load-bearing sentence: 5-min snapshots can't serve a 1m scalper and the archive yields nothing for years. Specify snapshot-granularity replay + `DATA_GAP`-vs-archive semantics; optionally add a clearly-tagged approximate mode (Black-76 premium reconstruction from underlying candles) that can never masquerade as snapshot-grade.
5. **Intra-bar stop/target touch detection in replay** (High · M) — Exits evaluate only at bar close, so on 1h/1d bars a stop blown intra-bar fills a full bar late. Evaluate SL/TP/trailing against bar high/low (worst-of rule on gaps; optional 1m drill-down), as a second deterministic rule inside the engine JAR.
6. **BTST pre-close evaluation clock + at-close fills** (High · M) — Today a BTST strategy on 1d bars evaluates after close (16:00 cagg) and fills next-bar open — capturing *zero* overnight gap, the very edge BTST trades. Add a 15:15–15:25 pre-close evaluation trigger + `fill_timing: at_close` (schema-freeze item).
7. **Futures cost legs in the FillSimulator** (High · S) — The cost model covers equities and options only; futures brokerage/STT/txn-charge legs are absent. Pure engine-JAR constants + fixtures, slots inside Phase 29. Cheapest defensible item in the whole list.
8. **Weekly (1w) timeframe end-to-end** (High · S) — A `candles_1w` continuous aggregate (IST trading-week buckets) + interval enums + datafeed resolution. Required for positional/investing strategies; Kite has no weekly API so rolling from 1d is the only correct source.

## Futures analytics (the biggest style gap — futures are in scope; the plan has none)

9. **Futures basis & term-structure workbench** (High · M) — Near/next/far LTPs, basis vs spot (absolute + annualized), contango/backwardation state, calendar-spread rollover cost, basis history chart. One endpoint over existing quote/candle machinery + a `/futures` page.
10. **Futures OI buildup classification + screener preset** (High · M) — Long buildup / short buildup / long unwinding / short covering from cached close+OI, as a screener preset and heat-tile view. (Verify the 1m candle builder actually persists OI per bar — one open design check.)
11. **Continuous futures: `futures_of_underlying` universe mode + stitched series** (High · L) — Without rollover-stitched series, multi-expiry futures backtests are structurally broken. Split: (a) front-month universe mode + roll re-subscribe (ships first), (b) synthetic continuous series + `roll_events` table serving adjusted/unadjusted reads.

## Options analytics (beyond the planned chain)

12. **IV rank / IV percentile from the snapshot archive** (High · M) — The highest-leverage use of the platform's *only irreplaceable dataset*: daily ATM-IV rollup + 1-year rank/percentile badge and IV time-series tab. Value compounds automatically as the archive accrues.
13. **Max pain + OI-change heatmap tabs** (Med · S) — Cleanest small extension; the plan already names max pain as computable but never surfaces it.
14. **India VIX capture + volatility context** (High · M) — Pin INDIA VIX in the ticker registry, backfill history (it's an ordinary Kite index), dashboard card + chain chip; later a `VIX_LEVEL` gate indicator (depends on #19).
15. **Multi-leg option strategy builder + paper baskets** (High · L) — Pick legs from the chain (straddle/vertical/iron-condor templates), payoff diagram, breakevens, combined Greeks, margin estimate via Kite's read-only basket-margins API; "take as paper basket" with basket-level P&L. The paper ledger is currently strictly single-leg.
16. **Intraday OI/IV shift alerts** (Med · M) — Diff successive 5-min snapshots; push unusual ATM OI build/IV spikes (on its own notification channel, not the ops topic).
17. **Expiry-day (Tuesday) dashboard mode** (Low · M) — Max-pain drift, OI unwind vs morning, ATM straddle decay tracker. Depends on #13.

## Indicators & strategy expressiveness (all additive vocabulary — no schema v2)

18. **Opening-range + previous-day-level family** (High · S) — `ORB_HIGH/LOW`, `PREV_DAY_H/L/C`, `DAY_HIGH/LOW`, `GAP_PCT` → gates like `close > orb_high`. Strongest indicator item; pure registry work.
19. **Cross-instrument market-context indicators** (High · M, **freeze item**) — Let an indicator declare an instrument override so rules like "BTST longs only when NIFTY > 200-DMA and VIX < 20" are expressible. Architecturally load-bearing (#14, #20 depend on it).
20. **Relative strength vs index** (High · M) — `rs_rank` screener preset over constituents + `RS_VS_INDEX` engine indicator (needs #19's plumbing).
21. **Breakout family + screener preset** (Med · S) — Donchian channels, n-day/52-week high crossings with volume confirmation.
22. **Candlestick pattern indicators** (Med · S) — ta4j-backed `PATTERN_*` (engulfing, doji, hammer…) through the direction normalizer.
23. **Pivot/swing support-resistance levels** (Low · S) — `PIVOT_*` engine indicators + screener proximity columns (chart overlay rendered from a ta4j engine-computed series per the A13 chart architecture [A13, 2026-06-12]).
24. **Volume profile / prior-day value area** (Med · M) — POC/VAH/VAL from 1m candles (documented as an approximation — no tick history exists).

## Screeners, scanners, alerts & breadth

25. **Live intraday screener presets** (High · M) — Gap %, % from open, distance from day-H/L and VWAP, relative volume over real-time 1m/5m aggregates; the Phase 17 screener is effectively EOD-only today.
26. **Pre-market gap scanner + morning-prep board** (High · M) — ~09:08–09:14 batched-quote capture → ranked gappers board on the dashboard before the bell.
27. **User-defined price/indicator alerts** (High · M) — Ad-hoc alerts ("alert at this level", RSI crossing on 5m) independent of full strategies, reusing the Phase 41 notifier's channels/dedup/audit. Currently the only way to get any alert is authoring a whole strategy.
28. **NSE market breadth analytics** (Med · M) — Advance/decline, % above 20/50/200-DMA computed from the constituents the app already accrues + subscribes.
29. **Sector tagging + rotation heatmap** (Med · M) — Capture the Industry column already inside the NSE constituents CSV; sectoral-index returns vs NIFTY heatmap.

## Backtesting & optimization upgrades

30. **Portfolio-level backtest (shared capital pool)** (High · L) — The largest genuine Stage D gap: today every run is single-instrument with no shared-equity semantics, despite `max_positions`/`max_daily_loss_pct` existing in the schema. Deterministic slot-competition rule keeps reproducibility.
31. **Monte Carlo trade-sequence resampling** (High · S) — Seeded bootstrap of persisted trades → drawdown distribution, 5/50/95 equity bands, risk-of-ruin. Pure post-processing; one of the cheapest high-value items anywhere.
32. **Benchmark-relative metrics + buy-and-hold overlay** (High · M) — Alpha, beta, information ratio, excess CAGR vs NIFTY (the benchmark series is *already* read for regime labels), benchmark curve overlaid on equity curves. Every current metric is absolute.
33. **Gap statistics + overnight P&L attribution** (High · M) — Per-instrument gap distributions; split every overnight trade's P&L into gap vs intraday components — the BTST diagnostic.
34. **MAE/MFE excursion analytics** (Med · S) — Two columns captured during replay + scatter panel; tells you if stops/targets are placed sanely.
35. **Time-of-day / day-of-week / expiry-day attribution** (Med · S) — Build alongside Phase 32's regime tagging; same mechanics.
36. **Cost & slippage sensitivity sweep** (Med · S) — `purpose: cost_sensitivity` fans out K replays at cost multipliers → break-even slippage curve.
37. **Deflated Sharpe + PBO on sweep leaderboards** (Med · M) — Multiple-testing honesty in optimizer-service (pure numpy over existing fold matrices).
38. **Batch backtest a watchlist** (Med · M) — One submission → per-symbol children + sortable symbol×metric matrix (design jointly with #30).
39. **SIP/DCA backtests + XIRR** (Med · M) — Contribution schedules injected at replay (request-body only — schema untouched), with an auto "vs SIPing NIFTY" baseline.
40. **Scheduled strategy re-validation** (Med · M) — Monthly walk-forward re-run per published strategy → decay alert + optional mini-sweep pushing "better params found" into the existing promote flow. Automates the "improve and optimize" loop that's currently 100% manual.

## Risk & capital management

41. **Paper account & capital model with F&O margin approximation** (High · M) — Without a capital base, `percent_equity`/`atr_risk`/`kelly_fraction` sizing and `max_daily_loss_pct` are undefined live, and paper P&L %s are meaningless. Foundational for #42/#43.
42. **Global risk limits + one-click kill switch** (High · M) — Cross-strategy max open positions, global daily-loss stop, "pause all signals" toggle.
43. **Engine-computed suggested quantity on every signal** (High · S) — Run the already-built sizing math in the live path; signals currently carry entry/SL/target but no qty.
44. **R-multiple tracking on the paper ledger** (Med · S) — Backtests already report R-expectancy; paper doesn't. Closes the unit mismatch.
45. **Exposure dashboard** (Med · M) — Open exposure by underlying/sector + net delta/theta/vega from archived Greeks.
46. **Drawdown & risk-event alerts** (Med · S) — Paper-equity drawdown thresholds, kill-switch trips → existing notifier.
47. **Correlation/concentration warnings** (Med · M) — Advisory badge when a new signal correlates highly with open positions (60-session correlations from the app's own candles).
48. **Risk-state visibility** (Med · S) — "Why is the engine not emitting?" chips: daily-loss hit, outside session window, Kite stale, kill switch.
49. **Bid-ask spread / liquidity guard** (Med · S) — 5-level depth already rides every tick DTO *unconsumed*; badge/suppress signals whose live spread exceeds a per-style threshold. Live-only — no parity impact.

## Signal-to-action workflow

50. **Tick-level stop/target touch monitor** (High · M) — Push SL/target-touched the moment the level trades instead of at next bar close (entries stay bar-close, preserving parity).
51. **One-click Send-to-Kite basket order intent** (High · S) — Render a signal into a Kite Publisher/basket form-POST so Kite opens pre-filled and the owner reviews+submits there. No order API is called — R12's read-only property holds (clarify R10 wording). Removes the biggest friction in the whole product without touching the threat model.
52. **GTT/OCO ticket formatter** (Med · S) — Copyable two-leg GTT ticket (trigger+limit for SL and target) for positional signals; verifier reclassified this as *no* scope change (it's text).
53. **Push deep-link + one-tap take/dismiss** (High · S) — ntfy Click header / Telegram inline link → `/signals/:id` over Tailscale; mobile action row. Smallest delta, highest workflow payoff.
54. **Signal invalidation & staleness lifecycle** (High · M) — Mark ACTIVE signals INVALIDATED (stop breached pre-entry, gate no longer true) or STALE (price drifted), per-style TTLs, follow-up push — prevents chasing dead signals and gives honest denominators for #59/#61.
55. **Per-style notification routing + digests** (Med · S) — Scalps push instantly; positional/investing batch into EOD/weekly digests (one global hourly cap across both is a real E-14 tension).
56. **Pre-trade cost & breakeven ticket** (Med · S) — Same engine cost model → round-trip costs, breakeven move, net R:R after costs on each signal.
57. **Forming-bar score preview ("setup radar")** (Med · M) — Advisory composite score against the in-progress bar for watched pairs; never persisted/notified, so determinism is untouched.
58. **Live regime context** (Med · M) — Run the Stage D regime labeler daily and annotate signals: "NIFTY regime: high-vol chop — this strategy's OOS Sharpe here: −0.3 (vs +1.4 in trend)". Stage D computes all of this and never lets it inform a live decision.

## Performance feedback & calibration (closing the loop the plan half-builds)

59. **Signal outcome tracking + live-vs-backtest drift page** (High · L) — Shadow-evaluate *every* emitted signal (taken or not) through its own exit rules → win rate/expectancy of all signals vs the taken subset, regret line for dismissed signals, and a per-version "live vs backtest expectation" page with guard-7-style divergence bands. The plan builds byte-identical parity and then never uses it to score live signals.
60. **Per-strategy paper attribution scorecard** (High · S) — `GET /paper/pnl?groupBy=strategy` + backtest-vs-paper panel; pure query over existing FKs.
61. **Signal-score calibration analytics** (High · M) — Did 0.8-composite signals outperform 0.65s? Backtest side is statistically meaningful immediately; live side matures with #59.
62. **Auto-paper incubation mode** (Med · M) — Toggle: every signal of a strategy auto-paper-trades → hands-off forward test vs its OOS fold distribution (shares #59's exit-following harness).

## Portfolio & investing

63. **Read-only Kite holdings/positions import** (High · M) — A sixth Kite port (`getHoldings`/`getPositions` — read scope, zero order capability), `/portfolio` page with live mark-to-market. The investing styles in scope have no surface at all today.
64. **LTCG/STCG tagging + FY post-tax P&L** (Med · S) — FIFO lot matching, Apr–Mar grouping, "days to LTCG" countdown; rates pinned like the fee schedule.
65. **Rebalancing helper** (Low · M) — Target weights → drift → suggested trades booked as paper orders (sequence behind #63).

## UX & daily workflow

66. **Trade journal** (High · M) — Notes/tags/discipline rating linked to signals, paper trades, and backtest trades, with a weekly-review route. Universally considered essential by traders; entirely absent.
67. **Trades & signals rendered on the price chart** (High · M) — Entry/exit/SL/target marks on the chart via lightweight-charts series markers + price lines; deep links from trade tables via route params + `setVisibleRange` [A13, 2026-06-12]. Today the chart page and the strategy surfaces never meet — yet "replay my losers on the chart" is *the* improvement workflow.
68. **EOD scan + morning briefing / evening digest** (High · M) — 16:15 candidate scan, one consolidated evening digest, 08:45 morning briefing (token health, expiry flag, gap movers, candidates).
69. **Market calendar surface** (Med · S) — Holidays + per-underlying expiries + owner-entered events (RBI/Fed) as a dashboard widget.
70. **Data-coverage & quality explorer** (High · M) — Per-instrument coverage %, gap list, source mix (KITE/TICK_AGG/BACKFILL), one-click backfill. Data quality is currently visible only in Grafana.
71. **CSV/JSON exports everywhere** (Med · S) — Trades, leaderboards, ledger, signals, raw candles/snapshots, with the reproducibility triple in headers.
72. **Strategy template gallery** (Med · S) — 8–12 commented YAML templates keyed to the owner's styles (ORB intraday, BTST close-strength, 52w-high positional…); the plan seeds only 2.
73. **Bar-replay practice mode** (Med · M) — Play/pause/step through cached history on the chart; practice entries log to the journal. (Under the A13 lightweight-charts architecture this gets easier: the app owns `setData`/`update`, so stepping bars is app-controlled [A13, 2026-06-12].)
74. **Command palette** (Low · S) — Ctrl+K instrument jump / quick actions.
75. **Daily circuit-limit capture + unfillable-signal guard** (Med · S) — Tag signals near/at circuit bands (LTP pinned = unfillable); one batched quote call per morning.

## ⚠ Scope-change decisions (owner's call — each breaks a stated constraint)

76. **NSE delivery-percentage data** ⚠ (Med · M) — A second NSE CSV feed (bhavcopy); `DELIVERY_PCT`/`DELIVERY_SPIKE` indicators + screener filter. Genuinely valuable for BTST/swing; needs its own ToS/URL verification like the constituents feed.
77. **F&O ban-list (MWPL) awareness** ⚠ (Med · S) — Another NSE CSV; "in ban" chips on chain/signals/screener.
78. **Earnings/results-date calendar + event-risk gating** ⚠ (High · M) — A *narrow, dates-only* pull-forward of the deferred Q2 fundamentals decision (no P/E, no EPS — just board-meeting dates from NSE's CSV): screener filter, signal badge, optional `DAYS_TO_EARNINGS` gate. A swing entry one day before results is a coin flip — the most common avoidable blow-up for days-to-weeks holding periods. This one likely earns its exception.
79. **Kite trade read-back + execution-quality report** ⚠ (High · L) — Read-only `GET /orders`/`GET /trades` → actual fill vs signal vs paper, realized slippage vs the model. Conflicts with the *letter* of T1/R12 ("read-only **market** scope") but not its spirit (still zero order placement); needs a one-line ADR amendment rewording "never calls order APIs" → "never calls order *placement* APIs". The only way to truly close the backtest→paper→real loop.
80. **Semi-automated execution with per-order confirmation** ⚠ (Low · L) — Full break of the signals-only fence (order placement, threat-model rework, separate execution-scoped key). **Recommendation: explicitly defer to post-GA**; #51+#52 capture ~80% of the value at zero risk.

## Schema-extensibility checkpoint

81. **Two-leg pair/spread strategy extensibility checkpoint** (Low · S now, L if ever built) — strategy-schema/v1 evaluates one instrument per context; a true pairs trade (long A / short B on a ratio z-score, e.g. NIFTY/BANKNIFTY futures spread) cannot be expressed. Do **not** build it now — just verify at the Phase 18/27 freeze that the signal and session shapes don't make two-leg signals a breaking schema-v2 later. Costs days now vs a v2 migration later.

---

## Recommended shortlist (curated starting point)

- **Non-negotiable correctness:** #1, #2, #3, #5, #6, #7 (+ freeze items #8, #19).
- **Highest value-per-effort:** #12 (IV rank), #18 (ORB levels), #31 (Monte Carlo), #32 (benchmark metrics), #41–43 (capital model + kill switch + suggested qty), #51+#53 (Send-to-Kite + push deep-link), #54 (signal invalidation), #59–60 (outcome tracking + scorecard), #63 (holdings import), #66–67 (journal + trades-on-chart), #78 (earnings dates).
- **Biggest strategic bets (L-sized):** #11 (continuous futures), #15 (multi-leg builder), #30 (portfolio backtest).
