# Stock universe + Market-Movers per-stock track (foundational)

Status: PLAN (implementation-ready). Owner: single-owner. Date: 2026-06-27.
Target services: `market-data-service` (equity-futures capture + screener + per-stock series),
`strategy-signal-service` (the Market-Movers per-stock seam path).

> Read order for the executor: this plan is self-contained but assumes the CLAUDE.md
> "parity-safe-additive" convention and the `oi-cross-filter` (#5) / `open-high-low` (#2)
> hard-gate insertion shape in `ScalperConfluenceGate` are the load-bearing precedents.
> The audit source rows are `docs/strategy-audit/market-movers.md` (25 rows, 3 review
> passes) and its disposition `docs/strategy-audit/disposition/market-movers.md` (23 gap
> rows). Every gap in this stream is `[S]` safe by design — there is **no existing
> Market-Movers golden** (the strategy publishes nothing today; the seeded
> `scalp-market-movers-*.yaml` is an index surrogate), so the whole per-stock path is a
> **brand-new sell/variant path** that cannot drift an existing golden. The ONE place this
> stream touches the shared seam (the per-stock fan-out hook) is tag-gated default-OFF.

---

## 1. Goal & the packages this stream closes

The Market-Movers strategy (Siva #3) trades the day-LEADING **F&O EQUITY** universe (stock
futures / cash, *"no stock options"*), picked off an OI-Pulse "Market Movers" screener — NOT
the index. The shipped `scalp-market-movers-*.yaml` triplet is an honest deferral: it ports the
deterministic trend-continuation CORE on a **NIFTY-50 index-future surrogate** and explicitly
defers the entire equity screener (`scalp-market-movers-nifty.yaml:1-46`). Every per-stock rule
therefore reads / gates the index, not the picked stock. This stream builds the foundational
equity-universe capture + screener + per-stock series, then wires the Market-Movers seam to
consume the picked stock so the per-stock packages become real.

The 11 packages in this stream and the gaps they close (all from
`docs/strategy-audit/disposition/market-movers.md`, doc-§ = `docs §3.3` narrative / `§6.3`
machine-readable `market_movers`):

| # | Package | gaps | doc-§ (market-movers.md rows) | P/S |
|---|---|---:|---|:--:|
| 1 | `equity-fno-universe-screener` | 2 | §3.3 Instruments / §6.3 `instruments`; §3.3 Filters "New High/Low Maker" / §6.3 `filters` | [S] |
| 2 | `nday-breakout-extremes` | 2 (mid) | §3.3 Entry Bull/Bear 2 (Min. B.O. Days) / §6.3 `entry_conditions`; §3.3 Setup 7 radar-building / §6.3 `setup_preconditions` | [S] |
| 3 | `per-stock-intraday-series` | 2 (mid) | §3.3 Entry Bull 4/5 (RSI 5m + VWAP-reclaim) / §6.3 `entry_conditions` | [S] |
| 4 | `per-stock-liquidity-ranking` | 2 (mid) | §3.3 Setup 4 / Filters (Volume); §3.3 S22 (a)/(b) large-cap + operator-trap / §5.3 | [S] |
| 5 | `per-stock-ohlc-flags` | 1 | §3.3 Entry Bull/Bear 3(b) (OH/OL flag) / §6.3 `filters` | [S] |
| 6 | `per-stock-oi-interpretation` | 1 | §3.3 Entry Bull/Bear 3(c) (LB/SC long; SB/LU short) / §6.3 `filters` | [S] |
| 7 | `per-stock-daily-rsi` | 1 | §3.3 Setup 6 / Filters (Daily-RSI < 75 bull / > 40 bear) / §6.3 `filters`,`setup_preconditions` | [S] |
| 8 | `per-stock-strike-iv-direction` | 1 | §3.3 Filters/Desirables (IV rising bull / falling bear on the strike) / §6.3 `indicators` | [S] |
| 9 | `per-stock-oi-spurt` | 1 | §3.3 Filters (OI-Spurt 4-quadrant cue) / §6.3 `indicators`,`filters` | [S] |
| 10 | `per-stock-chain-both-sides-oi` | 1 | §3.3 Execution/Edge (avoid both-sides-loaded OI) / §6.3 `edge_cases` | [S] |
| 11 | `short-side-mirror` | 1 | §3.3 Entry — Bearish (8/9-day low + OH + SB/LU) / §6.3 `entry_conditions.bearish` | [S] |

Plus 3 single-gap packages that ride the same foundation and are folded into the sequencing
below (they are listed in this stream's title set):

| Package | gaps | doc-§ | rides on |
|---|---:|---|---|
| `pct-price-move-gate` (>1% price-move alternative entry) | 1 | §3.3 Entry Bull/Bear 5 / §6.3 `entry_conditions` | per-stock-intraday-series + per-stock-oi |
| `volume-ma-indicator` (Volume-20 MA) | 1 | §3.3 Filters / §6.3 `indicators` | per-stock-intraday-series |
| `volume-conditional-exit` (adverse-move → check volume) | 1 | §3.3 Execution/Edge / §6.3 `edge_cases` | per-stock-intraday-series (management leg) |
| `trade-management-targets-trailing` (1–2% percent-target exit) | 1 | §3.3 Exit (Target) / §6.3 `exit_conditions.target` | per-stock-intraday-series (management leg) — **ADDED in audit pass 1** |

**Total: 12 named packages closing ~18 disposition gap rows** (the 11 rows above + the 4
folded single-gap rows + the EOD-OI overnight-carry leg of `intraday-positional-oi` that the
STBT-short row in this stream shares — counted under that cross-stream package, see §6). The
audit's per-stock sub-epic note (`disposition/market-movers.md` §"AUTOMATE_PKG themes" + the
GAP-DISPOSITION sub-epic note) confirms all of these are gated on package 1 and form a
self-contained sub-epic.

> **Audit-pass-1 correction:** `trade-management-targets-trailing` (the 1–2% percent-target exit,
> `disposition/market-movers.md:26` — the "Target = 1–2%" gap row; source `market-movers.md:32`) is dispositioned
> **AUTOMATE_PKG** ("a percent-target exit rule is a standard primitive") — the v1 plan silently
> dropped it (it is NOT `KEEP_MANUAL`, NOT `COVERED_*`). It is a management-leg gap that rides
> `per-stock-intraday-series` exactly like `volume-conditional-exit`, so it is added here. It is a
> YAML `exit_rules` percent-target on the picked-stock signal; if the engine's exit grammar already
> has a percent-target primitive, reuse it (verify — see Open Points 11). `[S]` (management-only,
> on the new tagged path).

**Out of scope (recorded, not closed here):** the discretionary risk-appetite stop sizing
(`KEEP_MANUAL_NEW`, market-movers.md row 12), the `constituent_contribution` cue
(`COVERED_FU1`), the S/R-line breakout (discretionary), and the SPAN-gated short *sell* leg
(this stream's `short-side-mirror` is a PE-BUY mirror, not a short-sell — see §3.11 / Open
Points).

---

## 2. Current state — verified file:line

Every line below was opened and confirmed against the working tree on 2026-06-27.

### 2.1 The equity-futures capture is index-only TODAY
- `services/market-data-service/.../futures/FuturesOiSnapshotService.java` — the live
  snapshotter. Underlyings come from `@Value("${artha.futures.oi-snapshot-underlyings:NIFTY
  50,NIFTY BANK}")` (L51-52); `snapshotNow()` (L75) resolves each via
  `contracts.monthlyFutures(underlying, today)` (L88), batch-quotes ALL contracts in one
  `quoteGateway.quotes(keys)` (L102), and persists `Row(ts, underlying, symbol, expiry, ltp,
  volume, oi, oiChange, ohlc.open/high/low/close)` (L116-122). **Already multi-underlying-
  capable** — the only thing index-only is the *config default*.
- `services/market-data-service/.../kite/FuturesContractSource.java` —
  `List<FutContract> monthlyFutures(String underlying, LocalDate onOrAfter)` (L17). The
  contract source takes ANY underlying string; resolving `HDFCBANK` futures is the same call as
  `NIFTY 50`. No equity-specific code needed in the resolver.
- `services/market-data-service/.../futures/analytics/FuturesSnapshotReader.java` — already has
  the **multi-underlying** reads the screener needs: `seriesAll(List<String> underlyings, ...)`
  (L60), `latestPairAll(List<String> underlyings, ...)` (L169), and a per-day EOD rollup
  `eod(underlying, from, to)` (L201) returning `EodRow(symbol, tradeDate, open/high/low/close,
  oiClose, oiChange, volume)` (L39-48). The `FutPoint` already carries `dayOpen/dayHigh/dayLow/
  prevClose/volume/expiry` (L24-36).
- `services/market-data-service/.../futures/analytics/FuturesMoversService.java` — the existing
  gainers/losers ranker. `movers(pair)` (L55) folds the latest two buckets into `MoverRow(
  tradingsymbol, ltp, pricePct, oiPct, dayOpen, dayHigh, dayLow, interpretation)` (L28-36),
  ranks gainers by `pricePct` desc / losers asc (L87-96), and classifies each via
  `OiInterpretation.classify(ltpDelta, oiDelta)` (L85). Its javadoc (L20-23) is explicit: *"only
  index futures … captured; a bank-stock futures grid needs a capture expansion."* The ranker is
  generic over the captured set — feed it equity contracts and it ranks equities.
- `services/market-data-service/.../futures/analytics/FuturesAnalyticsController.java` — already
  has `/api/v1/market/futures/movers` (L184), `/banks-grid` over a config bank-stock list
  (`bankStocks` L46-49 default = 17 bank stocks!) via `latestPairAll(bankStocks, ...)` (L231),
  and `/eod` (L301). So a multi-stock futures grid already exists for the bank sector; this
  stream generalizes the stock list and adds the screener semantics.

### 2.2 The scalper engine evaluates ONE fixed index future — no per-stock loop
- `services/strategy-signal-service/.../scalper/ScalperConfluenceGate.java` — `evaluate(cfg,
  bank, future, index, ...)` (L100) is called once per bar for ONE series. It decides the side
  from `chart.close` vs `chart.vwap` on the index future (L149-152), runs the §0B rails, scores
  Connect-the-Dots, and picks a strike. There is **no notion of "scan a universe and pick the
  leading stock"** — the strategy IS its single configured future.
- `ScalperConfig.from(config, tags)` (L101) reads `universe.underlying` /
  `universe.signal_underlying` and maps tags to `requireXxx` booleans (L119-153). There is NO
  equity-screener mode and NO `requireMarketMovers` flag today.
- `ScalperGates.java` — the §0B pure gates. `rsiBand` (L76) is on the 3m index value; `volume`
  (L64) is a static floor (NIFTY 125k / 50k other), NOT a per-stock liquidity rank;
  `oiQuadrant` (L121) reads the index futures quadrant; `indicatorAlignment` (L102) reads the
  index chart. None is per-stock.
- `ConnectTheDotsScorer.score(...)` (L63) reads `ctx.oi()` / `ctx.macro()` which
  `MarketOiClient` fills from the **option-root index** chain/spurt/active-strikes endpoints
  (L267-398). The `oi_spurt` dot (L90) and futures-OI quadrant (L80) are index-level.
- The `OpenHighLow` / `OpenHighLowGate` primitives (`OpenHighLow.java:48`, `OpenHighLowGate.java:56`)
  ARE the OH/OL detector this stream needs — but they read the front-future series + the
  `/options/strike-session-stats` endpoint, and are armed only by the `open-high-low` tag, which
  the market-movers YAML does NOT carry (`scalp-market-movers-nifty.yaml:54` tags =
  `[scalper, options, intraday, nifty, entry-candle-stop]`).
- `MarketOiClient.java` — the OI/macro assembler. `oi(underlying, expiry, tradeDate)` (L267) and
  `macro(underlying, tradeDate)` (L351) key off the index `underlying`. Per-stock equity OI
  would need analogous reads against an equity-futures OI source (there is none yet) — the
  `spurt` / `active-strikes` / `chain` endpoints are options-chain endpoints, absent for single
  stocks under the *"no stock options"* doc rule.

### 2.3 The YAML surrogate
- `scalp-market-movers-nifty.yaml` — `universe.mode: options_of_underlying`,
  `underlying: NIFTY 50`, `signal_underlying: NIFTY-FUT-CONT`, `option_types: [CE]` (L56-63);
  `direction: long` (L79); gate `close > vwap` AND `close > vwma20` (L81-83); RSI 14 @ 3m (L72);
  exits `close < vwap` + `time_stop max_bars 20` (L88-89); `09:45`–`15:00` window (L98). It is a
  faithful momentum surrogate, NOT the equity screener. The `*-sensex-niftyoi` /
  `*-sensex-sensexoi` siblings differ only in option root + OI-confluence index.

### 2.4 What this means architecturally
The **capture + reader + ranker layer is 80% present** (multi-underlying capture, `seriesAll`,
`movers`, `banks-grid`, `eod`, `OiInterpretation`). The two genuinely-new pieces are:
1. **An equity-futures universe + a screener service** in market-data that applies the
   Market-Movers selection rules (8/9-day breakout, OH/OL, OI-interpretation, daily-RSI,
   liquidity) on top of the existing `movers` ranking, exposed as ONE screener endpoint.
2. **A per-stock seam path** in strategy-signal so the Market-Movers strategy can pick the
   leading stock from the screener and evaluate the trade on THAT stock's series (not the index
   surrogate). This is the architecturally-new "scan-then-trade" loop the engine does not have.

Because the per-stock path is a **brand-new variant with no existing golden**, the whole stream
is `[S]` — see §4.

---

## 3. Design — per package

Conventions: market-data classes live under
`services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/screener/` (a NEW
package) and `.../futures/analytics/` (existing). Strategy-signal classes live under
`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/scalper/`. The
new strategy-signal HTTP reads ride `MarketOiClient`'s existing isolated-`get(...)` pattern
(L647), degrading every miss to an inert default that never falsely confirms.

### 3.0 Foundation: equity-futures capture config (gates everything)

The capture loop already works for any underlying. Expand the config-driven set.

- **File:** `FuturesOiSnapshotService.java` L51-52 — extend the default underlyings property to
  include the F&O-equity radar set, OR (preferred, see Open Points) leave the default and add a
  **separate** equity property + a second scheduled pass so the index cadence (3-min, used by the
  live OI pages) is not slowed by ~190 equities.
- **New property:** `artha.futures.equity-universe` — the F&O equity radar list (the ~190-stock
  F&O cash/futures universe, or a curated large-cap subset to start; see Open Points on size).
- **Data flow:** scheduler → `monthlyFutures(stock, today)` per equity → one batched
  `quotes(keys)` (Kite accepts ≤250 instruments/call, L80-81 comment — a ~190 equity-front-month
  set is ONE call; if next/far months are also captured, shard across passes) →
  `futures_oi_snapshots` rows tagged with each `underlying`. No schema change — the table already
  keys on `(underlying, tradingsymbol)`.

> This is the single most load-bearing dependency: until equity futures are in
> `futures_oi_snapshots`, every per-stock read returns empty. It is also the only piece with a
> live-data-volume risk (the expired-backfill OOM incident — see Open Points on capture sizing).

> **Audit-pass-2 prerequisite (state it explicitly):** the "`monthlyFutures(stock)` is the same call as
> `NIFTY 50`" claim holds ONLY because `FuturesSourceAdapter.monthlyFutures` (`FuturesSourceAdapter.java:21`)
> delegates to `InstrumentRepository.futures(underlying)`, which queries `instruments WHERE
> is_active AND underlying_tradingsymbol = ? AND instrument_type = 'FUT'`
> (`InstrumentRepository.java:275-285`). So equity-futures resolution returns rows **iff the instrument
> master already carries the NFO equity-future contracts** for that `underlying_tradingsymbol` (e.g.
> `HDFCBANK`). The Kite instrument-CSV sync pulls the whole NFO segment (stock futures included), so this
> is almost certainly already populated — but PR-1 MUST confirm (a one-line query: do `instrument_type =
> 'FUT'` rows exist for the radar stocks?) before relying on the capture; an unsynced master makes the
> capture silently no-op (`monthlyFutures` returns empty → the pinner skips → no snapshot rows). This is a
> read-only check, folds into Open Point 9.

### 3.1 `equity-fno-universe-screener` (2 gaps) — the foundational screener

A NEW market-data service that ranks the captured equity movers and surfaces the radar +
New-High/Low-Maker feed.

- **New file:** `futures/screener/MarketMoversScreener.java`. Reuses
  `FuturesMoversService.movers(pair)` over the equity set (via `reader.latestPairAll(equityList,
  interval, date)`), then enriches each `MoverRow` with the per-stock breakout / OH-OL / OI /
  liquidity facets (computed by the per-package services below) into a NEW record:

  ```java
  public record ScreenerRow(
      String tradingsymbol,
      BigDecimal ltp,
      BigDecimal pricePct,        // from MoverRow (prevClose-based)
      BigDecimal oiPct,           // from MoverRow
      OiInterpretation interpretation, // LB/SC/SB/LU from MoverRow
      int breakoutDays,           // §3.2 nday-breakout-extremes (0 = no N-day extreme)
      boolean openHigh,           // §3.5 per-stock-ohlc-flags
      boolean openLow,
      boolean dailyRsiOk,         // §3.7 per-stock-daily-rsi (< 75 bull / > 40 bear)
      long advTurnover,           // §3.4 per-stock-liquidity-ranking (ADV rank input)
      boolean newHighMaker,       // §3.1 live intraday new-high (Gainers) panel
      boolean newLowMaker) {}     // live intraday new-low (Losers) panel

  public record Screen(List<ScreenerRow> longCandidates, List<ScreenerRow> shortCandidates,
                       OffsetDateTime asOf) {}
  ```

  `longCandidates` = gainers passing (8/9-day HIGH ∧ OL ∧ LB/SC ∧ dailyRsiOk), ranked by a
  composite (pricePct × liquidity); `shortCandidates` = the mirror (`short-side-mirror`, §3.11).
  The "New High/Low Maker" gap = `newHighMaker`/`newLowMaker` derived from the current bucket's
  LTP equalling the running session high/low (the same `latestPairAll` window the movers use; the
  "live new intraday high" is the captured `dayHigh == ltp` within tolerance).
- **New endpoint:** `GET /api/v1/market/futures/movers-screen` on
  `FuturesAnalyticsController` (sibling to `/movers` L184) — params `mode/date/interval`, no
  `name` (sector-wide like `/banks-grid`). Map envelope `{longCandidates, shortCandidates,
  asOf}` (a `Map<String,Object>` return → does NOT drift the springdoc contract per CLAUDE.md).
  422 until ≥1 equity snapshot bucket has accrued (matches `/banks-grid` L233).
- **Data flow:** controller → `reader.latestPairAll(equityList, iv, date)` →
  `FuturesMoversService.movers` → enrich each row via the §3.2-3.7 helpers → rank → JSON.

### 3.2 `nday-breakout-extremes` (mid, 2 gaps) — rolling N-day high/low + radar staging

- **New file:** `futures/screener/NDayExtremes.java` (pure). Over `reader.eod(stock, today-N,
  today-1)` (the EOD rollup, L201) compute the rolling high/low and the breakout-day count:

  ```java
  /** The count of days the stock's CURRENT-day high exceeds the prior N daily highs (0 = none).
   *  An 8 means today prints an 8-day high; the gate wants >= 8 (9 better). Mirror for lows. */
  static int breakoutHighDays(List<EodRow> priorDays, BigDecimal todayHigh);
  static int breakoutLowDays(List<EodRow> priorDays, BigDecimal todayLow);
  ```

  Radar-building (§3.3 Setup 7, the second gap) is the SAME machinery surfaced as a staging
  label: `1-2d` / `3-4d` / `8-9d` derived from `breakoutHighDays` thresholds — a derived field on
  `ScreenerRow.breakoutDays`, no separate code.
- **`reader.eod` returns ALL of an underlying's contracts, not just the front.**
  `FuturesSnapshotReader.eod(underlying, from, to)` groups by `tradingsymbol` (`FuturesSnapshotReader.java:201,211`),
  so it yields one `EodRow` per contract per day (front + next + far). `NDayExtremes` must first reduce
  to the **front** contract per day (the most-captured / nearest-expiry, the same `pickContract`
  heuristic `FuturesAnalyticsController.pickContract` L118 uses, or pick by expiry) before computing the
  rolling N-day high/low — otherwise the high/low mixes contracts across roll boundaries. State the
  front-selection rule in `NDayExtremes`.
- **Data flow:** screener → `reader.eod` → front-contract reduction → rolling N-day extreme
  (needs ≥8 prior IST sessions of equity capture before the count is meaningful — a warm-up
  dependency, see §6).

### 3.3 `per-stock-intraday-series` (mid, 2 gaps) + `volume-ma-indicator` + `volume-conditional-exit`

The picked stock's own 5m VWAP / VWMA / SuperTrend / RSI series — the gap is that today these run
on the index surrogate at 3m.

- **Approach (reuse the engine, do NOT re-implement indicators):** the engine
  `IndicatorBank` already computes VWAP/VWMA/PSAR/SuperTrend/RSI on any series. The per-stock
  series source is the stock's **futures candles** (`candles` for the equity-future tradingsymbol,
  the same store the index future reads). The seam (§3.12) builds an `EngineSeries` for the picked
  stock's front-future and runs the existing `chart(bank, index)` (`ScalperConfluenceGate.java:304`)
  against it instead of the index bank.
- **`volume-ma-indicator`:** declare a `Volume`-20 MA indicator alias in the market-movers YAML
  (`indicators: - {name: VOLUME_MA, alias: vol20, timeframe: 5m, params:{period:20}}`) and a new
  `volumeAboveMa` dot/gate. The §0B static floor stays for the index; the per-stock path uses the
  20-MA. Requires a `VOLUME_MA` indicator in the engine `Ta4jIndicators` IF one does not exist
  (verify; if absent it is a small engine addition — Open Points).
- **`volume-conditional-exit`:** an adverse-move exit leg — on an adverse bar, exit if bar volume
  ≥ the 20-MA (heavy = real opposite player), hold if below. This is a **management/exit** leg
  (no entry-signal change) → expressed as an `exit_rules` entry in the YAML +, if the rule
  grammar cannot express "volume vs its MA", a new `ScalperRisk`-side check. Management-only,
  `[S]`.
- **Data flow:** seam picks the stock → fetch stock-future candles → engine `IndicatorBank` →
  per-stock `Chart` → existing gate/scorer.

### 3.4 `per-stock-liquidity-ranking` (mid, 2 gaps)

- **New file:** `futures/screener/LiquidityRanker.java` (pure). ADV (average daily turnover =
  mean of `EodRow.close × EodRow.volume` over the trailing window) from `reader.eod`, plus a
  **large-cap classifier** from a config large-cap list (`artha.futures.large-cap-stocks`).
  `ScreenerRow.advTurnover` + a `largeCap` boolean feed the screener rank and the §3.3 S22
  large-cap-only / operator-trap filter (hold long only while above the stock's VWAP — the
  per-stock VWAP from §3.3).
- **Data flow:** screener enrichment → `reader.eod` window.

### 3.5 `per-stock-ohlc-flags` (1 gap) — wire the OH/OL primitive per stock

The `OpenHighLow.marks(EngineSeries future, int index)` primitive (`OpenHighLow.java:78`) already
computes OH/OL on ANY series. The gap is it is armed only by the `open-high-low` tag and reads
the index future.

- **Reuse, do not rewrite:** in the per-stock seam (§3.12), call `OpenHighLow.marks(stockFuture,
  index)` on the PICKED STOCK's series. Long wants `openLow` (OL), short wants `openHigh` (OH) —
  per the doc (OL for longs / OH for shorts). The screener computes the cheap mark
  (`ScreenerRow.openHigh/openLow`) from the captured `dayOpen` vs running `dayHigh/dayLow`
  (`FutPoint`, no series needed) for the radar; the seam re-confirms on the live stock series at
  entry.
- **Data flow:** screener mark (cheap, from `FutPoint`) for the radar; seam re-mark (from the
  stock `EngineSeries`) at entry.

### 3.6 `per-stock-oi-interpretation` (1 gap) — LB/SC/SB/LU on the picked stock

`OiInterpretation.classify(ltpDelta, oiDelta)` already classifies the 4 quadrants and is used by
`FuturesMoversService.movers` (L85) — so the equity `MoverRow.interpretation` IS the picked
stock's quadrant ONCE equity futures are captured.

- **Gate:** the Market-Movers screener filters `longCandidates` to LB (best) or SC, and
  `shortCandidates` to SB (best) or LU. No new classifier — the existing one runs over the
  equity capture. The seam reads `ScreenerRow.interpretation` for the picked stock (no second
  fetch).
- **Data flow:** capture → `movers` → `interpretation` field, already wired.

### 3.7 `per-stock-daily-rsi` (1 gap)

- **New file:** `futures/screener/DailyRsi.java` (pure) — RSI(14) over the trailing daily closes
  from `reader.eod` (the same EOD rollup). `ScreenerRow.dailyRsiOk` = (long: dailyRsi < 75; short:
  dailyRsi > 40), the §3.3 Setup-6 screen. Reuse the engine RSI math if exposed as a static; else
  a 12-line Wilder RSI in this helper.
- **Data flow:** screener enrichment → `reader.eod` daily closes.

### 3.8 `per-stock-strike-iv-direction` (1 gap)

- **The hard part:** the doc instrument is *"no stock options"*, yet the IV-rising/falling cue is
  on "the relevant strike." Equity-option per-strike IV requires an equity-options chain feed,
  which the platform does not capture (only index chains). **Two honest options:**
  (a) **Defer** the IV-direction cue to a manual check (it is a soft "desirable", not a hard
  entry condition — the audit marks it PARTIAL/desirable), OR
  (b) Wire an equity-options chain read IF an equity-options source is added later.
- **Recommendation:** ship as a **read-only screener column stub** (`ivDirection` = `null`/UNKNOWN
  today) + a manual-check reminder, and gate the real automation behind a future equity-options
  feed. Recorded in Open Points. This package is the lowest-value, highest-data-cost gap in the
  stream.

### 3.9 `per-stock-oi-spurt` (1 gap)

The `oi_spurt` confluence dot (`ConnectTheDotsScorer.java:90`, `oiSpurt` L159) is computed at the
**option-root index**. For Market-Movers it should read the PICKED STOCK's futures OI spurt.

- **Reuse:** the equity-futures spurt is `FuturesSpurtService` (the `/futures/spurt` endpoint,
  controller L168) over the equity contract. Surface the picked stock's spurt quadrant + ΔOI%/
  price% magnitudes into the seam's per-stock `Oi` context (replacing the index spurt for this
  strategy only). The `oiSpurt(oi, ce, props)` math (`ConnectTheDotsScorer.java:159`) is unchanged.
- **NOT just "swap operands" — a NEW derivation+mapping is required.** Today `Oi.spurtOiPct` /
  `spurtPricePct` / `underlying()` quadrant are produced by `MarketOiClient.deriveSpurt`
  (`MarketOiClient.java:550`) over the **`/options/spurt`** chain endpoint — there is no `/futures/spurt`
  read in `MarketOiClient` at all. `/futures/spurt` returns a *different* DTO
  (`FuturesSpurtService.FutSpurt{tradingsymbol, spurtPct (an OI%), pricePct, interpretation, …}`,
  per contract; `FuturesSpurtService.java:23-31`). So the seam must (a) add an isolated `get(...)`
  call to `/futures/spurt?name=<stock>` in the per-stock OI assembler, (b) pick the **front** contract
  from `items` (the chain returns every captured contract — front/next/far — sorted by tradingsymbol,
  NOT filtered to front), and (c) map `FutSpurt.spurtPct → Oi.spurtOiPct`, `pricePct → Oi.spurtPricePct`,
  `OiInterpretation.classify(...) → Oi.underlying()` quadrant. The scorer math is unchanged; the
  *source and mapping* are new. (For the equity case there is no `/options/spurt`, so this is the ONLY
  spurt source.)
- **Data flow:** seam → `/futures/spurt?name=<stock>` → front-contract `FutSpurt` → mapped
  per-stock `Oi.spurtOiPct/spurtPricePct/underlying-quadrant`.

### 3.10 `per-stock-chain-both-sides-oi` (1 gap)

"Avoid names with OI heavily on BOTH call and put sides (ambiguous)." This is an
**equity-options chain** read (both-sides OI), which — like §3.8 — has no feed under *"no stock
options."*

- **Recommendation:** approximate with the **futures-OI** ambiguity available today: skip a name
  whose futures OI-interpretation is neutral/conflicted (e.g. the screener already classifies
  LB/SC/SB/LU; a `null`/`NEUTRAL` interpretation = ambiguous → drop). The literal CE/PE both-sides
  chain check is deferred with §3.8 behind a future equity-options feed. Recorded in Open Points.

### 3.11 `short-side-mirror` (1 gap) + `pct-price-move-gate`

- **`short-side-mirror`:** a NEW `scalp-market-movers-*-short.yaml` variant set (or a `direction:
  short` switch on the per-stock seam) selecting `shortCandidates` (8/9-day LOW ∧ OH ∧ SB/LU) and
  emitting a **PE-BUY** (long put) — NOT a futures short-sell (that is SPAN-gated, out of scope).
  The seam mirrors the long path with `side = PE`. Brand-new path, no existing golden → `[S]`.
- **`pct-price-move-gate`:** the ">1% intraday price change" alternative entry — a trivial gate
  `|pricePct| > 1.0` on the screener `ScreenerRow.pricePct` (already computed). The ΔOI leg rides
  the per-stock OI (§3.6/§3.9). Add as a screener filter + a seam pre-gate.

### 3.12 The per-stock seam — the architecturally-new "scan-then-trade" hook

This is the ONE place the shared confluence seam changes, and it is **tag-gated default-OFF**.

- **File:** `ScalperConfig.java` — add `boolean requireMarketMovers` + parse
  `tags.contains("market-movers")` (in `from(...)`, the L153 `oi-cross-filter` area), mirroring the
  `requireOpenHighLow` parse (L125). **CONSTRUCTOR COMPLETENESS (positional record):** `ScalperConfig`
  is a `record` (header L36-52, currently a **16-positional-arg** constructor); adding a field forces
  editing (a) the record header, (b) the single production `new ScalperConfig(...)` at L154-156, and
  (c) **every `new ScalperConfig(...)` test call site** — verified in audit pass 2 there are
  **8 such constructor calls in `ScalperConfluenceGateTest.java`** (CFG, TWO_CANDLE_CFG, OI_CROSS_CFG,
  GAP_CFG, TREND_CHANGE_CFG, + 3 more — it constructs the record directly, no builder). Every one needs
  the new trailing `false`; miss any and the build fails. (Grep confirmed those 8 + `ScalperConfig.java`
  are the ONLY `new ScalperConfig(` sites in the service.) The shipped `scalp-market-movers-*.yaml`
  triplet does NOT carry the tag today, so they stay the index surrogate, byte-identical.
- **ARCHITECTURE CORRECTION (critical — the seam CANNOT "rebuild the series inside `evaluate()`").**
  `ScalperConfluenceGate.evaluate(cfg, bank, future, index, …)` (L100) receives `bank` (`BarValues`)
  and `future` (`EngineSeries`) as **already-built parameters**. The caller `SignalEngine` builds them
  via `IndicatorBank.build(definition, InstrumentRef(exchange, tradingsymbol), seriesStore)`
  (`SignalEngine.java:390-394`) for the SIGNAL future the bar-close fired on, then calls
  `scalperGate.get().evaluate(strategy.scalper(), bank, future, index, …)` (`SignalEngine.java:459-462`).
  The gate holds **no** `seriesStore`, no `IndicatorBank`, and no candle reader — it CANNOT swap to a
  different instrument's series internally. So the per-stock pick + per-stock `EngineSeries`/`BarValues`
  build MUST happen **engine-side**, not in the gate. The realistic shape is:
  1. The picked stock's front future must be a **subscribed, captured 3m/5m series in `seriesStore`**
     (a NEW subscription path — `FuturesUniverseResolver`/series subscription, today only the strategy's
     own configured future is subscribed). Without this the per-stock `EngineSeries` is empty → no series
     to evaluate. This is a hard prerequisite the plan previously omitted (see Open Points 10).
  2. `SignalEngine.scalperEntry(...)` (L445) — when `cfg.requireMarketMovers()`, consult
     `MarketMoversSelector.pick(...)`, then **rebuild `bank`/`future` via `IndicatorBank.build(...,
     InstrumentRef(<pickedStockExchange>, <pickedStockFuture>), seriesStore)`** for the picked stock and
     call `evaluate(...)` with those. The selector also returns the per-stock `Oi` context (§3.6/§3.9)
     which the gate must accept as an override (a NEW optional param on `evaluate`, or a per-stock
     `MarketOiClient.context(...)` call keyed on the stock).
  3. The side-decision (`chart.close` vs `chart.vwap`, L149) then runs on the PICKED STOCK's chart
     unchanged; the existing confluence/strike logic follows. (The strike chain stays the index-option
     surrogate per Open Point 3 — the engine has no equity-future/cash execution leg.)

  `MarketMoversSelector` (NEW, strategy-signal side) calls
  `GET /api/v1/market/futures/movers-screen` via `MarketOiClient`'s isolated-`get` pattern (L647), takes
  the top passing `longCandidate` (or `shortCandidate` for the short variant), and returns the picked
  stock's `(exchange, tradingsymbol)` + a per-stock `Oi` context. **This makes PR-4 materially larger
  than "a tag + a selector"** — it adds an engine-side series-subscription + per-instrument
  `IndicatorBank` rebuild path. Re-scope PR-4 accordingly (still L effort, but the engine seam, not the
  gate, is the touch point).
- **Execution leg:** the doc says stock futures / cash, no stock options. The engine has no
  equity-future/cash execution path (the seam always picks an index option). **v1 paper
  decision:** keep the index-option execution surrogate for the picked-stock SIGNAL (document it
  loudly, like the current YAML header), OR add an equity-future paper-execution leg (larger;
  Open Points). The SIGNAL fidelity (per-stock OH/OL/OI/RSI/breakout) is the value; the execution
  surrogate is a known v1 simplification.
- **Why `[S]`:** the `market-movers` tag is absent from every shipped config, so the seam is
  byte-identical when unarmed. The new path emits only when the tag is added to a NEW variant →
  no existing golden exists for it → cannot drift one.

### 3.13 `trade-management-targets-trailing` (1 gap) — the 1–2% percent-target exit

The §3.3 "Target = 1–2%" exit (`market-movers.md:32`, AUTOMATE_PKG). Today the surrogate YAML exits on
`signal_exit: close < vwap` + `time_stop max_bars 20` only — no percent target.

- **Approach:** a percent-target `exit_rules` entry on the NEW `market-movers` variant YAML (booked at
  +1–2% on the picked-stock SIGNAL series / the option premium, per the v1 execution decision).
  **RESOLVED in audit pass 2 — the primitive EXISTS, so this is PURE YAML (zero Java):** the engine
  `ExitEvaluator` already supports a `take_profit` level rule with `basis: premium_pct`
  (`ExitEvaluator.java:98,107,181` evaluate `take_profit`; `levelDistance` L236-237 computes
  `entryPrice × value%` for `basis: premium_pct`). So §3.13 is just:
  `exit_rules: - {type: take_profit, params: {basis: premium_pct, value: 1.5}}` — no engine change.
  (The "small additive exit-rule type" fallback the v1 hedge mentioned is unnecessary; the rule is shipped.)
- **Parity:** management-only, on the new tagged variant → `[S]`. No existing golden carries it.
- **Data flow:** management leg on the per-stock signal (rides §3.3 `per-stock-intraday-series`).

---

## 4. PARITY classification

**Every change in this stream is `[S]` safe.** Rationale, per the FU2 plan + the CLAUDE.md
parity-safe-additive convention:

- **All market-data work (§3.0-3.10 screener/services/endpoints)** is read-only analytics +
  capture-config — it emits no signals and touches no golden. New `Map<String,Object>` /
  typed-record endpoints do not drift the springdoc contract for the existing keys (CLAUDE.md:
  generic-Map returns are not enumerated; a NEW `@GetMapping` path DOES add a path to the spec, so
  re-capture `ContractCaptureTest` + regen TS — that is a contract-gen step, not a parity break).
- **The `market-movers` tag + the per-stock seam (§3.12)** is the only scalper-seam change. It is
  **tag-gated default-OFF and absent from every shipped YAML**, so when unarmed the seam is
  byte-identical — exactly the FU2 pattern. The path it enables is a **brand-new variant with no
  existing golden** (the Market-Movers strategy publishes nothing today), so there is nothing to
  drift. This is `[S]` by the audit's own classification ("a brand-new sell/variant path with no
  existing golden").
- **`short-side-mirror` + `pct-price-move-gate` + `volume-conditional-exit`** are new variant /
  management / sizing legs on that same new path → `[S]`.

**Therefore: NO new opt-in golden variant is strictly required to protect existing goldens** —
the 5 engine goldens carry no scalper strategy (FU2 §1 "What does NOT change") and are invisible
to this work. HOWEVER, to lock the NEW per-stock path deterministically, this stream **adds a
fresh golden fixture for the per-stock seam** (a new fixture, not a variant of an existing one) —
see §5. There is no `[P]` change and thus no parity-sensitive tag to default-OFF beyond the
`market-movers` arming tag itself (which is the safety mechanism, not a parity risk).

> One caveat to state for the executor: IF a future decision makes the index-surrogate
> market-movers YAML *itself* start consuming the screener (rather than a new variant), that WOULD
> alter an emitting config and become `[P]` — requiring the FU2 default-OFF tag + a new golden.
> The recommended design (a NEW tagged variant, shipped configs untouched) avoids this. Recorded
> in Open Points.

---

## 5. Tests

### 5.1 Unit (market-data, `services/market-data-service/src/test/java/.../futures/`)
- `screener/MarketMoversScreenerTest.java` — given a synthetic equity `FutPoint` pair set, assert
  `longCandidates` = gainers passing (breakout ∧ OL ∧ LB/SC ∧ dailyRsiOk), `shortCandidates` the
  mirror, ranking order, and the New-High/Low-Maker flags.
- `screener/NDayExtremesTest.java` — `breakoutHighDays`/`breakoutLowDays` over crafted `EodRow`
  lists (exact 8-day / 9-day boundary cases; an inside day = 0).
- `screener/LiquidityRankerTest.java` — ADV turnover ordering + large-cap classification.
- `screener/DailyRsiTest.java` — Wilder RSI on a known series (golden RSI values), the < 75 / > 40
  band booleans.
- Extend `FuturesMoversServiceTest.java` — confirm the ranker is correct over a multi-EQUITY set
  (not just index), since the screener leans on it.

### 5.2 Integration (market-data, Testcontainers `*IntegrationTest`)
- `FuturesAnalyticsControllerIntegrationTest` (existing) — add a `/futures/movers-screen` case:
  seed `futures_oi_snapshots` with ≥2 buckets across ≥3 equity underlyings + an `eod`-spanning
  history, assert the screened envelope + the 422-before-data path (mirror the `/banks-grid`
  test). Naming MUST be `*IntegrationTest` (no failsafe — CLAUDE.md).
- A capture smoke: `FuturesOiSnapshotService` resolves an equity underlying's `monthlyFutures`
  and persists a tagged row (can ride an existing snapshot IT with an equity symbol).

### 5.3 Unit + seam (strategy-signal, `.../scalper/`)
- `MarketMoversSelectorTest.java` — given a stubbed screener envelope, assert it picks the top
  passing long/short candidate and returns the per-stock `Oi`; empty screen → empty pick.
- `ScalperConfigTest` (or extend `ScalperStrategyLoadTest`) — `tags.contains("market-movers")` →
  `requireMarketMovers=true`; absence → false (the shipped triplet stays false).
- `ScalperConfluenceGateTest.java` — the seam triple, mirroring the #5/#2 gate tests:
  (a) `market-movers` armed + a passing screened stock → emits on the STOCK's series;
  (b) armed + empty screen → blocks;
  (c) **unarmed (the shipped market-movers YAML) → byte-identical to today** (the critical
  no-regression assertion — run the existing surrogate path and assert the decision is unchanged).

### 5.4 Golden / parity
- **No existing golden regenerates** (no scalper golden exists; the 5 engine goldens are
  untouched — assert by running `GoldenDeterminismTest` + `BacktestParityTest` green, unchanged).
- **NEW fixture** `market-movers-perstock` (a fresh golden for the new path, NOT a variant of an
  existing one): a deterministic per-stock replay over a fixed screened-stock series, asserting
  the per-stock decision is byte-stable across two replays. This locks the new path without
  touching the frozen set. (If the seam stays LIVE-only like the rest of `ScalperConfluenceGate`
  — class javadoc L29-33 — the parity is preserved by persisting the picked stock + confluence at
  entry via the V009 side-channel, exactly as the index path does; the golden then asserts the
  side-channel round-trips.)

### 5.5 e2e (`e2e/`)
- A read-only Playwright check that the screener endpoint renders if a Market-Movers radar page is
  added to the React app (OPTIONAL — only if a UI surface is in scope; the data endpoint is the
  deliverable). Gate on container health, not gateway HTTP (CLAUDE.md CI note).

### 5.6 Contracts
- The new `/futures/movers-screen` path drifts the springdoc spec (a new path). Re-capture
  `ContractCaptureTest` (`-Dcontracts.capture=true`), regen TS (`npx openapi-typescript@7` →
  `contracts/gen/*.d.ts`), `tsc --strict`. ci-contracts warns on gen drift, fails on breaking —
  this is additive, non-breaking.

---

## 6. Dependencies & sequencing

```
§3.0 equity-futures capture config  ── gates EVERYTHING ─────────────────────────┐
   (and a ≥8-session warm-up before nday-breakout / daily-RSI are meaningful)    │
        │                                                                        │
        ▼                                                                        ▼
§3.1 MarketMoversScreener  ◄── §3.2 NDayExtremes, §3.4 LiquidityRanker,   §3.6 OiInterpretation
        │                       §3.5 OH/OL marks, §3.7 DailyRsi            (free once captured)
        │                       (all enrich the screener row)
        ▼
§3.12 MarketMoversSelector + the `market-movers` seam hook (strategy-signal)
        │   reuses §3.3 per-stock series + §3.9 per-stock spurt + §3.11 short/pct gates
        ▼
NEW `scalp-market-movers-*` variant(s) carrying the `market-movers` tag (paper drafts)
```

Hard ordering constraints:
1. **§3.0 capture gates all per-stock packages** — no equity rows = every read empty. Build +
   deploy the capture FIRST and let it accrue ≥8 IST sessions before the breakout/daily-RSI
   filters return non-degenerate values (the radar warm-up). This is a calendar dependency, not
   just a code one.
2. **The screener (§3.1) must exist before the seam (§3.12) can consume it.**
3. **Per-stock series (§3.3) gates the OH/OL re-mark, the indicator dots, and the
   volume-conditional exit** — the seam needs the stock's `EngineSeries`.
4. **The equity-universe gates all per-stock packages** (the audit's explicit statement,
   GAP-DISPOSITION §3c) — this whole stream is one sub-epic behind §3.0.
5. **SPAN gates only the short *sell* legs** — NOT this stream's `short-side-mirror`, which is a
   PE-BUY (long put). The futures-short / STBT-sell carry (the cross-stream
   `intraday-positional-oi` EOD-OI overnight leg) IS SPAN-gated and deferred (Open Points).
6. **§3.8 / §3.10 (equity-option per-strike IV / both-sides chain OI)** are gated on a future
   equity-options chain feed that does not exist — ship as stubs/manual-checks (Open Points).

Cross-stream: the EOD-OI overnight-carry-on-LB and the STBT close-OI-extreme legs belong to the
`intraday-positional-oi` package (a different stream, market-movers.md rows 13 & 21). They reuse
this stream's equity capture + `short-side-mirror`; sequence them AFTER §3.0 lands.

---

## 7. Effort + suggested PR breakdown

Overall: **L** (the capture + a full screener service + a new per-stock seam loop + the calendar
warm-up). Suggested PRs (each green-on-merge, conventional commits, scope = service):

| PR | Scope | Packages | Effort |
|---|---|---|:--:|
| **PR-1** | `feat(market-data): equity-futures capture config + warm-up` | §3.0 | S |
| **PR-2** | `feat(market-data): N-day extremes + daily-RSI + liquidity helpers` | §3.2, §3.4, §3.7 | M |
| **PR-3** | `feat(market-data): MarketMoversScreener + /futures/movers-screen + OH/OL/OI enrich` | §3.1, §3.5, §3.6, §3.9 (futures-spurt reuse) | M |
| **PR-4** | `feat(strategy-signal): market-movers tag + MarketMoversSelector seam + engine-side per-stock series subscription + per-instrument IndicatorBank rebuild (default-OFF)` | §3.12 (incl. the engine-side subscription/rebuild per the §3.12 Architecture Correction), §3.3 per-stock series, `volume-ma-indicator` (NEW `VOLUME_MA` factory — absent today) | L (larger than v1 estimated — the touch point is `SignalEngine`, not just the gate) |
| **PR-5** | `feat(strategy-signal): market-movers per-stock YAML variants (long + short paper drafts)` | §3.11 `short-side-mirror`, `pct-price-move-gate`, `volume-conditional-exit`, §3.13 `trade-management-targets-trailing` | M |
| **PR-6** | `chore(contracts): recapture spec + regen TS for /movers-screen` | §5.6 | S |

(PR-3 depends on PR-1+PR-2; PR-4 depends on PR-3; PR-5 depends on PR-4.) **Audit-pass-2 sequencing
correction:** the `/movers-screen` path is ADDED in PR-3, so the springdoc spec drifts AT PR-3 — the
contract recapture (`ContractCaptureTest -Dcontracts.capture=true` + TS regen) **must land WITHIN PR-3,
not as a later standalone PR-6**; otherwise PR-3's own `ContractCaptureTest` is red on merge (ci-contracts
is a per-PR gate). **Fold PR-6 into PR-3** (it is listed separately only for visibility).
The §3.8 / §3.10 equity-options IV / both-sides packages are NOT in the PR list — they are
deferred stubs/manual-checks pending an equity-options feed (Open Points).

---

## Open Points

Every unresolved decision, with options + a recommended default. Do NOT guess — resolve before
implementing the affected PR.

1. **Equity-futures capture sizing / cadence (HIGH RISK).** The full F&O universe is ~190 stocks;
   capturing front+next+far monthlies for all on the 3-min index cadence risks the live-DB OOM the
   `expired-backfill-live-db-incident` memory documents (the live 1GB Timescale crashed twice on a
   bulk equity backfill). Options: (a) **front-month only, a curated ~50 large-cap radar subset,
   separate slower cadence** [recommended default — bounds the row volume, matches the
   liquid-large-cap doc rule §3.3 S22], (b) full ~190 front-month on a 5-min cadence, (c) full
   ladder (rejected — OOM risk). **Recommend (a); confirm the subset list + cadence + raise DB mem
   before any full run** (the memory's mandatory pre-conditions).

2. **One capture pass or two?** Folding equities into `artha.futures.oi-snapshot-underlyings`
   slows the index OI pages' 3-min cadence (the live OI suite depends on it). Options: (a) a
   **separate `artha.futures.equity-universe` property + a second `@Scheduled` pass** [recommended
   — isolates the index cadence], (b) one merged list. **Recommend (a).**

3. **Per-stock execution leg — index-option surrogate vs equity-future/cash paper execution.** The
   doc is stock futures / cash, no stock options; the engine has only index-option execution.
   Options: (a) **keep the index-option execution surrogate for the per-stock SIGNAL, documented
   loudly** [recommended for v1 paper — the signal fidelity is the value; matches the current YAML
   header's honesty], (b) build an equity-future paper-execution leg (larger; needs an
   equity-future fill model + lot sizes). **Recommend (a) for v1, (b) as a follow-on.**

4. **`per-stock-strike-iv-direction` (§3.8) + `per-stock-chain-both-sides-oi` (§3.10) — no
   equity-options feed.** The platform captures only INDEX option chains; the doc forbids stock
   options anyway, yet both cues reference an option strike/chain. Options: (a) **ship as
   read-only stubs (`null`/UNKNOWN) + a manual-check reminder, defer real automation behind a
   future equity-options feed** [recommended — both are soft "desirables", low value, high data
   cost], (b) approximate from equity-FUTURES OI only (the both-sides check → drop ambiguous
   `NEUTRAL`-interpretation names; the IV-direction → drop entirely). **Recommend (a) for both,
   with (b)'s futures-OI ambiguity drop as the §3.10 approximation already in the screener.**

5. **`VOLUME_MA` engine indicator existence — RESOLVED in audit pass 1: it is ABSENT.**
   `IndicatorRegistry` (`libs/strategy-engine/.../indicators/IndicatorRegistry.java`) registers
   `VOLUME_RATIO` (L67, "Volume vs mean of prior lookback bars") and `VWMA` (L79) but **no plain
   `VOLUME_MA`**. So `volume-ma-indicator` REQUIRES adding a `VOLUME_MA` factory entry to
   `IndicatorRegistry` + `Ta4jIndicators` (a small additive indicator). Decide: (a) add a true
   `VOLUME_MA` (SMA of volume, period N), or (b) reuse `VOLUME_RATIO` and express the "above the
   20-MA" rule as ratio ≥ 1.0 (no new indicator). **Recommend (b) if the semantics match the doc's
   "Volume 20" intent (above-average volume), else (a).** Either is parity-safe — it only appears in
   the new per-stock path.

6. **Short side — PE-BUY mirror vs futures short-sell / STBT carry.** This stream's
   `short-side-mirror` is a **long-put (PE-BUY)** mirror (no margin). The doc's STBT overnight
   carry (futures short held overnight on a close-OI extreme) is a real SHORT-SELL → SPAN-gated
   (#47) and out of scope. **Confirm: `short-side-mirror` = PE-BUY only; the futures-short / STBT
   carry stays deferred under SPAN + the `intraday-positional-oi` stream.**

7. **Screener selection determinism for parity.** If the seam (§3.12) stays LIVE-only like the rest
   of `ScalperConfluenceGate` (the parity firewall, class javadoc L29-33), the PICKED STOCK + its
   confluence MUST be persisted at entry (V009 side-channel) so a replay reads them back rather
   than re-screening (a live screen is non-deterministic). Options: (a) **persist the picked stock
   in the side-channel, replay reads it** [recommended — matches the existing index-option +
   Hero-Zero-strike pattern exactly], (b) make the screen deterministic over fixed snapshots
   (harder; the radar is intrinsically live). **Recommend (a).**

8. **Index-surrogate YAML — keep, retag, or replace.** The shipped `scalp-market-movers-*` triplet
   is the index surrogate. Adding the per-stock path as a NEW variant keeps them byte-identical
   (`[S]`). If instead the owner wants the existing triplet to BECOME per-stock, that alters an
   emitting config → `[P]`, requiring the FU2 default-OFF tag + a new golden. Options: (a) **new
   `*-mm-perstock` variants, shipped triplet untouched** [recommended — stays `[S]`], (b) retag the
   triplet (becomes `[P]`). **Recommend (a).**

9. **Equity universe membership source.** Where does the F&O-equity list come from — a static
   config list, the instrument master (filter F&O-eligible equities), or an external radar?
   Options: (a) **static config `artha.futures.equity-universe` (curated large-cap subset)**
   [recommended for v1, aligns with Open Point 1], (b) derive from the synced instrument master
   (`FuturesContractSource` could list all equity FUT underlyings — larger, ties to OP-1 sizing).
   **Recommend (a) for v1.** **Audit-pass-2 prerequisite (whichever option):** the capture resolves
   contracts via `InstrumentRepository.futures(underlying)` filtering `instrument_type='FUT' AND
   underlying_tradingsymbol = <stock>` (`:275-285`) — so **the instrument master must already contain the
   NFO equity-future rows** for every radar stock, else `monthlyFutures` returns empty and the capture
   silently no-ops. Kite's NFO instrument-CSV sync includes stock futures, so this is expected to be
   satisfied; PR-1 must verify with a one-line `SELECT count(*) ... WHERE instrument_type='FUT' AND
   underlying_tradingsymbol IN (<radar>)` before deploy. (Option (b) is just this query promoted to the
   membership source.)

10. **Per-stock series subscription into `seriesStore` (HARD prerequisite — added in audit pass 1).**
    The §3.12 seam can only evaluate the picked stock if that stock's front-future 3m/5m candles are a
    **subscribed series in the engine `seriesStore`** — today `SignalEngine` subscribes only the
    strategy's own configured signal future (via `FuturesUniverseResolver`), so a freshly-picked mover
    has NO `EngineSeries` to build an `IndicatorBank` from. This is distinct from §3.0 (the
    market-data `futures_oi_snapshots` capture): the engine reads *candles* (`seriesStore`/caggs), not
    the snapshot table. Options: (a) **pre-subscribe the whole equity radar's front futures as engine
    candle series at load** (bounded by the Open-Point-1 curated subset — recommended; mirrors how the
    index future is subscribed), (b) lazily subscribe + REST-warm the picked stock on first pick
    (latency on the first bar; risks an empty series). **Recommend (a); confirm the candle series for
    each radar stock exists (or is REST-warmable) before PR-4.** Without this, the seam blocks on every
    bar (empty series) — silently, looking like "no candidate passed."

11. **Percent-target exit primitive — RESOLVED in audit pass 2: it EXISTS.** The engine `ExitEvaluator`
    already exposes a `take_profit` level rule with `basis: premium_pct` (`ExitEvaluator.java:98,107,181`
    evaluate `take_profit`; `levelDistance` L236-237 computes `entryPrice × value%` for the `premium_pct`
    basis — the same primitive the existing scalper SL/TP uses). So `trade-management-targets-trailing`
    (§3.13) is **YAML-only, zero Java**: `exit_rules: - {type: take_profit, params: {basis: premium_pct,
    value: 1.5}}` on the NEW tagged variant. Parity-safe (new variant, no existing golden). **No
    verification or engine work remains — declare the rule and ship.** (Note: the engine also has a
    `trailing_stop` with `basis: premium_pct` + `activate_at`/`trail_by` at `ExitEvaluator.java:261-274`,
    so the "trailing" half of the package name is also a YAML-only primitive if a trailing variant is
    wanted later.)

---

## Audit pass 1 findings

Reviewer opened every cited file on the working tree (2026-06-27). Verdict: **sound-with-open-points**
— the parity reasoning is correct and the cites are overwhelmingly accurate, but the §3.12 seam
architecture was unsound as written (corrected in place) and one automatable package was dropped (added).

### Citations — checked, all accurate (no stale/wrong cites found)
- `FuturesOiSnapshotService.java`: config default `@Value` L51-52 ✓, `snapshotNow()` L75 ✓,
  `monthlyFutures` L88 ✓, `quotes(keys)` L102 ✓, `Row(...)` L116-122 ✓, Kite-250 comment L80-81 ✓.
- `FuturesSnapshotReader.java`: `seriesAll` L60 ✓, `latestPairAll` L169 ✓, `eod` L201 ✓,
  `EodRow` L39-48 ✓, `FutPoint` L24-36 ✓.
- `FuturesMoversService.java`: `movers` L55 ✓, `MoverRow` L28-36 ✓, `classify` L85 ✓, javadoc L20-23 ✓.
- `FuturesAnalyticsController.java`: `/movers` L184 ✓, `bankStocks` @Value L46-49 (17 stocks) ✓,
  `/banks-grid` + `latestPairAll(bankStocks…)` L231 + 422-path L233 ✓, `/eod` L301 ✓, `/spurt` L168 ✓.
- `ScalperConfluenceGate.java`: `evaluate` L100 ✓, side-decision L149-152 ✓, LIVE-only/V009 javadoc
  L29-33 ✓ (V009 migration `V009__scalper_signal_detail.sql` confirmed).
- `ScalperConfig.java`: `requireOpenHighLow` parse L125 ✓, `oi-cross-filter` parse L153 ✓.
- `ScalperGates.java`: `rsiBand` L76 ✓, `volume` L64 (125k/50k) ✓, `oiQuadrant` L121 ✓,
  `indicatorAlignment` L102 ✓.
- `ConnectTheDotsScorer.java`: `oi_spurt` dot L90 ✓, `oiSpurt(...)` L159 ✓, `futures_oi` dot L80 ✓.
- `MarketOiClient.java`: isolated `get(...)` L647 ✓, `oi` L267 ✓, `macro` L351 ✓, options endpoints
  L267-398 ✓.
- `OpenHighLow.java`: class L48 ✓, `marks` L78 ✓. `OpenHighLowGate.java` class L56 ✓.
- `scalp-market-movers-nifty.yaml`: deferral header L1-46 ✓, tags L54 ✓, universe L56-63 ✓,
  RSI@3m L72 ✓, gate L81-83 ✓, `direction: long` L79 ✓, exits L88-89 ✓, window L98 ✓.
- `disposition/market-movers.md` (23 gap rows) + `market-movers.md` (25 rows) ✓ — all package→gap
  mappings trace, except the one dropped package below.

### Soundness issues found + corrected in place
1. **(MAJOR) §3.12 seam could not "rebuild future/bank/chart inside `evaluate()`."** The gate receives
   `bank`/`future` as pre-built params from `SignalEngine` (`IndicatorBank.build(…, InstrumentRef(exchange,
   tradingsymbol), seriesStore)` `SignalEngine.java:390-394`, then `evaluate(...)` `:459-462`). The gate
   holds no `seriesStore`/`IndicatorBank`/candle reader, so it cannot swap instruments internally. The
   per-stock pick + per-instrument `IndicatorBank` rebuild MUST happen engine-side (in `scalperEntry`,
   `:445`). **§3.12 rewritten** with the correct touch point; PR-4 re-scoped (materially larger) and a new
   HARD prerequisite (Open Point 10: the picked stock's candle series must be subscribed in `seriesStore`).
2. **(MAJOR) §3.9 per-stock OI-spurt is NOT just "swap operands."** `Oi.spurtOiPct/spurtPricePct/
   underlying()` are produced by `MarketOiClient.deriveSpurt` over **`/options/spurt`** (`MarketOiClient.java:550`)
   — `MarketOiClient` has no `/futures/spurt` read. `/futures/spurt` returns a different DTO
   (`FutSpurtChain{items:[FutSpurt{spurtPct, pricePct, interpretation}]}`, every contract). **§3.9
   corrected:** a NEW isolated `get` + front-contract selection + field-mapping (FutSpurt→Oi spurt fields)
   is required; the scorer math (`oiSpurt` L159) is unchanged.
3. **(MINOR) §3.2 `reader.eod` returns ALL contracts (front+next+far)**, grouped by tradingsymbol
   (`FuturesSnapshotReader.java:211`). `NDayExtremes` must reduce to the front contract before the rolling
   high/low. **Corrected** with a front-selection note (reuse the `pickContract` L118 heuristic).
4. **(MINOR) `ScalperConfig` is a positional record** — adding `requireMarketMovers` forces editing the
   header (L36-52), the `new ScalperConfig(...)` at L154-156, AND `ScalperConfluenceGateTest.java` (which
   constructs it directly). **Added to §3.12.**
5. **(RESOLVED) `VOLUME_MA` is ABSENT** from `IndicatorRegistry` (only `VOLUME_RATIO` L67 + `VWMA` L79).
   Open Point 5 updated from "verify" to "it is absent — add a factory or reuse `VOLUME_RATIO`."

### Completeness gaps found + corrected
- **Dropped package `trade-management-targets-trailing`** (1–2% percent-target exit, `disposition` row 26 /
  `market-movers.md:32`) — dispositioned **AUTOMATE_PKG**, silently omitted by v1 (it is not KEEP_MANUAL,
  not COVERED_*). **Added as §3.13**, folded into PR-5, total bumped 11→12 packages. New Open Point 11
  (verify the engine percent-target exit primitive).
- **No per-stock candle-series subscription path** captured. **Added as Open Point 10** (HARD prerequisite
  for §3.12 — distinct from the §3.0 snapshot capture; the engine reads caggs, not the snapshot table).

### Parity — confirmed correct, no `[P]` change is mis-marked `[S]`
- Every signal-affecting change rides the NEW default-OFF `market-movers` tag on a NEW variant with **no
  existing golden** — the FU2/CLAUDE.md parity-safe-additive pattern. When the tag is absent (every shipped
  config), the seam is byte-identical: the `from(...)` parse only flips a new boolean, and `evaluate(...)`
  only branches when `cfg.requireMarketMovers()`. **`GoldenDeterminismTest` (libs/strategy-engine — engine
  goldens, scalper-free) + `BacktestParityTest` (backtest-service) stay green, unchanged** (confirmed both
  exist and carry no scalper strategy). The market-data work (§3.0-3.10) emits no signals and touches no
  golden. The §4 caveat (if the shipped triplet itself starts consuming the screener it becomes `[P]`) is
  correctly captured (Open Point 8). **No parity defect found.** The one tightening: §3.13's percent-target
  and the §3.12 engine-side rebuild must also live ONLY behind the tag — stated in those sections.

### Dependency sequencing — correct, with one addition
- feeds-before-gates (§3.0 capture gates all) ✓; equity universe before per-stock ✓; SPAN before sell legs
  (and this stream's `short-side-mirror` is PE-BUY, correctly NOT SPAN-gated) ✓. **Addition:** the per-stock
  *candle* subscription (Open Point 10) is a second feed-before-gate edge that sits beside §3.0 — both must
  land before §3.12. The §3.0 snapshot capture alone is NOT sufficient for the engine seam.

### Open points added
- Open Point 10 (per-stock candle-series subscription into `seriesStore` — HARD prerequisite).
- Open Point 11 (percent-target exit primitive existence for §3.13).
- Open Point 5 upgraded from a question to a resolved fact (`VOLUME_MA` absent).

**Net verdict:** sound-with-open-points. After these corrections the plan is implementation-ready PROVIDED
the executor resolves Open Points 1 (capture sizing), 3 (execution leg), 10 (candle subscription) before
the affected PRs — those three are genuine architectural decisions, not detail. The cites are trustworthy.

---

## Audit pass 2 findings

Independent skeptical re-review (2026-06-27). A fresh reviewer re-opened a SAMPLE of the cited files on the
working tree (not trusting pass-1's checkmarks), re-derived the parity argument end-to-end, re-verified
each pass-1 correction, and hunted for what both the author and pass-1 missed. **Verdict: sound-with-open-points
— implementation-ready.** Pass-1's two MAJOR corrections are confirmed dead-on; pass-2 RESOLVED one open
point pass-1 left dangling (the percent-target primitive — it exists), tightened two precision claims, and
surfaced one previously-unstated hard prerequisite (the instrument-master sync). No new soundness defect; no
parity defect; no mis-marked `[P]`.

### Citations independently re-verified (sample) — all accurate
- `FuturesOiSnapshotService.java`: `@Value` default L51-52 ✓, `snapshotNow()` L75 ✓, `monthlyFutures` L88 ✓,
  `quotes(keys)` L102 ✓, `Row(...)` L116-122 ✓, Kite-250 comment L80-81 ✓.
- `FuturesContractSource.java`: `monthlyFutures(String, LocalDate)` L17 ✓ (interface; impl is
  `FuturesSourceAdapter.java:21` — genuinely generic over underlying).
- `FuturesSnapshotReader.java`: `eod` L201 groups `BY tradingsymbol, d` L211 ✓ (confirms the §3.2 front-reduction
  correction).
- `FuturesSpurtService.java`: `FutSpurt{tradingsymbol, ltp, prevClose, pricePct, oi, oiChange, spurtPct,
  interpretation}` L23-31, sorted by `tradingsymbol` L92, ALL contracts ✓ (confirms the §3.9 correction — the
  plan's shorthand omits a couple of fields but the load-bearing ones are right).
- `FuturesAnalyticsController.java`: `/spurt` L168 ✓, `/movers` L184 ✓, `/banks-grid` L219 + `latestPairAll(bankStocks…)`
  L231 ✓, `bankStocks` default = **17** stocks L46-49 ✓, `/eod` L300-301 ✓, `pickContract` L118 ✓.
- `SignalEngine.java`: `IndicatorBank.build(…, InstrumentRef(exchange, tradingsymbol), seriesStore)` L390-394 ✓,
  `scalperEntry(...)` L445 ✓, `evaluate(...)` call L459-462 ✓ — the gate receives `bank`/`future` PRE-BUILT (the
  pass-1 §3.12 MAJOR correction is exactly right).
- `MarketOiClient.java`: `deriveSpurt` reads `/api/v1/market/options/spurt` L275-285 + `summary.interpretation/
  oiChangePct/priceChangePct` L550-554 ✓; isolated `get(...)` L647 ✓ (confirms §3.9 — there is NO `/futures/spurt`
  read in `MarketOiClient`).
- `ConnectTheDotsScorer.java`: `oi_spurt` dot L90, `oiSpurt(oi,ce,props)` L159 reads `oi.underlying()` quadrant +
  `oi.spurtOiPct()/spurtPricePct()` ✓ (scorer math unchanged — the §3.9 "source+mapping new, math same" framing holds).
- `OpenHighLow.java`: class L48, `marks(EngineSeries, int)` L78 ✓.
- `ScalperConfig.java`: `requireOpenHighLow` parse L125 ✓, `oi-cross-filter` parse L153 ✓, positional record header L36-52 ✓.
- `IndicatorRegistry.java`: `VOLUME_RATIO` L67 + `VWMA` L79, NO `VOLUME_MA` ✓ (pass-1 Open Point 5 "absent" confirmed).
- `GoldenDeterminismTest.java` (libs/strategy-engine) + `BacktestParityTest.java` (backtest-service) both exist ✓.
- `scalp-market-movers-nifty.yaml`: tags L54, universe L56-63, RSI@3m L72, gate L81-83, `direction: long` L79,
  exits = `signal_exit` + `time_stop max_bars 20` ONLY (no take_profit) L88-89, window L98 ✓.
- `disposition/market-movers.md`: 23 gap rows (L5, L41); the "Target = 1–2%" gap is the table row at file
  line 26 (NOT a numbered "row 26" — corrected the §1 citation to `:26` to avoid the row-index misread).

### Soundness re-check — pass-1's MAJOR corrections are correct, no new defect
- **§3.12 engine-side rebuild (pass-1 #1): CONFIRMED.** `scalperEntry` (`SignalEngine.java:445`) is handed
  `bank`/`future`/`index` already built by `evaluateAtBarClose` (`:390-394`) and forwards them to
  `scalperGate.get().evaluate(...)` (`:459-462`). The gate has no `seriesStore`/`IndicatorBank`/reader, so the
  per-stock instrument SWAP cannot happen inside `evaluate()` — it must be an engine-side rebuild in `scalperEntry`.
  Sound.
- **§3.9 per-stock spurt source+mapping (pass-1 #2): CONFIRMED.** `MarketOiClient` has no `/futures/spurt` read;
  the per-stock spurt genuinely needs a NEW isolated `get` + front-contract pick from the sorted `items` +
  field-map into `Oi.spurtOiPct/spurtPricePct/underlying()`. Sound.
- **§3.2 front-contract reduction (pass-1 #3): CONFIRMED** by the `GROUP BY tradingsymbol, d` SQL (`:211`).

### What pass-2 found that pass-1 missed / left open
1. **(RESOLVED — Open Point 11, §3.13) The percent-target/take-profit exit primitive ALREADY EXISTS.** Pass-1 left
   this as "verify; reuse if present, else add." Pass-2 verified: `ExitEvaluator` evaluates a `take_profit` level
   rule (`:98,107,181`) and `levelDistance` computes `entryPrice × value%` for `basis: premium_pct` (`:236-237`);
   `entryLevels` persists it deterministically at entry and "never affects emitted signals" (`:42-48`). So §3.13 is
   **pure YAML, zero Java** — `exit_rules: - {type: take_profit, params:{basis: premium_pct, value: 1.5}}`. The
   engine ALSO has a `premium_pct` `trailing_stop` (`:261-274`), so the package's "trailing" half is likewise
   YAML-only. **Corrected §3.13 + Open Point 11 in place** (removed the "else add an exit-rule type" hedge).
2. **(PRECISION — §3.12) Constructor-completeness understated.** Pass-1 said the other `new ScalperConfig(...)` site
   "is `ScalperConfluenceGateTest.java`" (singular). Grep confirms that file holds **8** positional `new
   ScalperConfig(...)` calls (CFG/TWO_CANDLE_CFG/OI_CROSS_CFG/GAP_CFG/TREND_CHANGE_CFG + 3), each a 16-arg
   constructor needing the new trailing `false`. Mechanically larger than "a call site." **Corrected §3.12.**
3. **(NEW HARD PREREQUISITE — §3.0 / Open Point 9) The instrument-master sync is an unstated dependency.** The §3.0
   claim "`monthlyFutures(stock)` is the same call as `NIFTY 50`" is true ONLY because `FuturesSourceAdapter`
   (`:21`) → `InstrumentRepository.futures` queries `instruments WHERE instrument_type='FUT' AND
   underlying_tradingsymbol = <stock>` (`:275-285`). So the capture returns rows **iff the master already carries
   the NFO equity-FUT contracts** for each radar stock. Kite's NFO CSV sync includes stock futures (so this is
   expected-satisfied), but an unsynced/partial master makes the whole capture SILENTLY no-op (empty ladder → no
   snapshot rows → every per-stock read empty — indistinguishable from "no candidate passed"). **Added a PR-1
   verify-query gate to §3.0 + Open Point 9.** This is the equity-side mirror of Open-Point-10's candle-subscription
   prerequisite: two distinct "is the data actually there" pre-flights, both fail-silent if skipped.
4. **(SEQUENCING — §7) Contract recapture is mis-ordered.** PR-3 ADDS `/futures/movers-screen` (a new
   `@GetMapping` path → springdoc spec DRIFT). The standalone PR-6 "recapture spec" sits AFTER PR-3, but PR-3's own
   `ContractCaptureTest` would be red on merge without the recapture. **The recapture must land WITHIN PR-3.**
   Corrected §7 (fold PR-6 into PR-3; the table keeps the row for visibility only). Note this also matches the §4
   point that a new path DOES drift the spec (CLAUDE.md) — pass-1 read the rule right but didn't catch the PR
   ordering.

### Parity re-derived end-to-end — confirmed `[S]`, no `[P]` mis-marked
- The ONLY signal-affecting change is the `market-movers` tag → `requireMarketMovers` boolean → the §3.12
  engine-side branch. The tag is **absent from every shipped YAML** (verified: `scalp-market-movers-nifty.yaml:54`
  tags = `[scalper, options, intraday, nifty, entry-candle-stop]`, no `market-movers`), so `from(...)` only flips a
  new `false` and `scalperEntry` only branches when armed → unarmed = byte-identical. The new path is a brand-new
  variant with **no existing golden** → nothing to drift. `GoldenDeterminismTest` (engine goldens, scalper-free) +
  `BacktestParityTest` are invisible to this work and stay green unchanged.
- **§3.13 `take_profit` parity (pass-2 re-check):** the rule lives ONLY on the NEW tagged variant; `entryLevels`
  computes the level at entry deterministically and never alters emitted signals (`:48`). On the index-option
  execution surrogate (Open Point 3 v1 decision) the `premium_pct` basis books off `entryPrice` (the option
  premium) — consistent with the existing scalper SL/TP path. Parity-clean.
- **§4 caveat intact:** if a future decision makes the SHIPPED triplet consume the screener it becomes `[P]` (needs
  the default-OFF tag + a new golden) — correctly captured in Open Point 8. The recommended NEW-variant design stays `[S]`.

### Residual risks the executor still owns (unchanged from pass-1, re-affirmed)
- **Open Point 1 (capture sizing / live-DB OOM)** — the single highest risk; the `expired-backfill-live-db-incident`
  memory makes the curated-subset + slower-cadence + raised-DB-mem pre-conditions MANDATORY, not optional. Pass-2
  did not re-litigate the recommendation (front-month, ~50 large-caps, separate cadence) — it is sound.
- **Open Point 10 (per-stock candle subscription into `seriesStore`)** — confirmed still a real, distinct
  prerequisite from §3.0/the new master-sync check: the engine reads candle series (caggs), the capture writes the
  snapshot table; both must be populated before the seam evaluates. Now there are effectively THREE fail-silent
  data pre-flights gating §3.12 (snapshot capture, master sync, candle subscription) — all three must be asserted in
  PR-1/PR-4, or the seam looks like "no candidate ever passes."
- **Open Point 3 (execution leg)** — v1 index-option surrogate is the honest, documented choice; unchanged.

### Pass-2 corrections applied in place
- §3.13 + Open Point 11: percent-target primitive RESOLVED (exists; pure-YAML `take_profit`/`premium_pct`).
- §3.12: constructor-completeness corrected to **8** `ScalperConfluenceGateTest` sites (16-arg record).
- §3.0 + Open Point 9: added the instrument-master-sync prerequisite + a PR-1 verify-query gate.
- §7: contract recapture folded INTO PR-3 (was mis-ordered as a later PR-6).
- §1: `disposition/market-movers.md` Target-row citation tightened to `:26` (file line, not a row index).

**Net pass-2 verdict: sound-with-open-points — implementation-ready.** The plan's architecture (especially the
hard-won §3.12 engine-side seam and the §3.9 spurt source+mapping) is now correct and well-cited; the parity
firewall is intact and re-derived clean; the remaining work is the executor's three architectural decisions
(Open Points 1, 3, 10) plus the three fail-silent data pre-flights. Every cite pass-2 sampled was accurate.
