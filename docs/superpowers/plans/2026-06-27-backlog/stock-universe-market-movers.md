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

**Total: 11 named packages closing ~17 disposition gap rows** (the 11 rows above + the 3
folded single-gap rows + the EOD-OI overnight-carry leg of `intraday-positional-oi` that the
STBT-short row in this stream shares — counted under that cross-stream package, see §6). The
audit's per-stock sub-epic note (`GAP-DISPOSITION.md` §3c) confirms all of these are gated on
package 1 and form a self-contained sub-epic.

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
- **Data flow:** screener → `reader.eod` (needs ≥8 prior IST sessions of equity capture before
  the count is meaningful — a warm-up dependency, see §6).

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
  strategy only). The `oiSpurt(oi, ce, props)` math is unchanged — only its operands become
  per-stock.
- **Data flow:** seam → `/futures/spurt?name=<stock>` → per-stock `Oi.spurtOiPct/spurtPricePct/
  underlying-quadrant`.

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
  `tags.contains("market-movers")` (L153 area), mirroring `requireOpenHighLow` (L125). The shipped
  `scalp-market-movers-*.yaml` triplet does NOT carry the tag today, so they stay the index
  surrogate, byte-identical.
- **File:** `ScalperConfluenceGate.java` — when `cfg.requireMarketMovers()`, BEFORE the index
  side-decision (L149), call a NEW `MarketMoversSelector`:

  ```java
  if (cfg.requireMarketMovers()) {
    Optional<PickedStock> stock = moversSelector.pick(cfg.signalIndex(), istTime, eodDate, side?);
    if (stock.isEmpty()) return Optional.empty();           // no leading stock passes → block
    // rebuild `future`, `bank`, `chart` against the PICKED STOCK's front-future series,
    // and `ctx.oi()` against the stock's /futures/spurt — then fall through to the existing
    // confluence/strike logic UNCHANGED.
  }
  ```

  `MarketMoversSelector` (NEW, strategy-signal side) calls
  `GET /api/v1/market/futures/movers-screen` via `MarketOiClient`'s isolated-`get` pattern, takes
  the top passing `longCandidate` (or `shortCandidate` for the short variant), and returns the
  picked stock's tradingsymbol + a per-stock `Oi` context. The per-stock series fetch reuses the
  existing candle-reading path (the same one that builds the index `EngineSeries`).
- **Execution leg:** the doc says stock futures / cash, no stock options. The engine has no
  equity-future/cash execution path (the seam always picks an index option). **v1 paper
  decision:** keep the index-option execution surrogate for the picked-stock SIGNAL (document it
  loudly, like the current YAML header), OR add an equity-future paper-execution leg (larger;
  Open Points). The SIGNAL fidelity (per-stock OH/OL/OI/RSI/breakout) is the value; the execution
  surrogate is a known v1 simplification.
- **Why `[S]`:** the `market-movers` tag is absent from every shipped config, so the seam is
  byte-identical when unarmed. The new path emits only when the tag is added to a NEW variant →
  no existing golden exists for it → cannot drift one.

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
| **PR-4** | `feat(strategy-signal): market-movers tag + MarketMoversSelector seam (default-OFF)` | §3.12, §3.3 per-stock series, `volume-ma-indicator` | L |
| **PR-5** | `feat(strategy-signal): market-movers per-stock YAML variants (long + short paper drafts)` | §3.11 `short-side-mirror`, `pct-price-move-gate`, `volume-conditional-exit` | M |
| **PR-6** | `chore(contracts): recapture spec + regen TS for /movers-screen` | §5.6 | S |

(PR-3 depends on PR-1+PR-2; PR-4 depends on PR-3; PR-5 depends on PR-4. PR-6 can fold into PR-3.)
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

5. **`VOLUME_MA` engine indicator existence.** `volume-ma-indicator` needs a 20-period volume MA.
   Verify whether `Ta4jIndicators` exposes a volume MA; if not, it is a small engine addition (an
   indicator factory entry). Options: (a) reuse if present, (b) add a `VOLUME_MA` factory.
   **Recommend: verify first; add only if absent.** (Pure indicator addition is parity-safe — it
   only appears in the new per-stock path.)

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
   **Recommend (a) for v1.**
