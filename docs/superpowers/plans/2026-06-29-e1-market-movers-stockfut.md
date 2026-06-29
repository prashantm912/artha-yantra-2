# E1 — faithful stock-futures Market-Movers (#3) build plan

**Status:** ACTIVE (2026-06-29). Owner: "build both" (E12 done #339; this is E1). Authority for the
strategy = operative `strategy-documents/.../Options_Scalper_Siva_Operative_Strategy.md` §3.3 + the Day-10
deck ("Market Movers Strategy.pdf"). Design = a 4-agent investigation workflow (run `wf_92835239-f33`).

## DATA SOURCE = UPSTOX (owner-chosen 2026-06-29, supersedes the NSE-bhavcopy ingest below)
Use the existing Upstox integration for the per-stock-future data instead of parsing the NSE F&O UDiFF
bhavcopy CSV — cleaner (no external-CSV schema risk), and **read ON-DEMAND → no migration, no bulk
storage, NO OOM**:
- **Per-stock-future daily OHLC + OI** (8/9-day breakout, daily RSI, OI%/quadrant) → an **active**
  Upstox `GET /v2/historical-candle/{key}/day/{to}/{from}` (OHLCV+OI bars). The platform already parses
  this shape for EXPIRED contracts (`UpstoxExpiredInstrumentsClient` + `UpstoxExpiredCandles`,
  `b.oi()`); the ACTIVE endpoint is the one small new client (clone, drop `expired-instruments/`).
- **Live LTP / LTP% / OH-OL / volume** mover-rank → existing `GET /v2/market-quote/quotes`
  (`source.quotes=upstox`, W-U2).
- **Stock-future Upstox key resolution** → the V2 F&O instrument-key map (#58) for the radar stocks'
  NFO front futures (`StockUpstoxKeyMap` carries the EQ keys; the F&O map carries the FUT keys).
This COLLAPSES Stages 1-2 (no `nse_eod_fo_bhavcopy` table, no NSE CSV fetcher, no bulk equity-radar
capture): **Stage 1 (revised) = a thin active Upstox historical-candle client (OHLCV+OI) + an
`UpstoxFnoDailyReader` over the radar set, read on-demand/lightly-cached.** Stages 3-5 unchanged
(screener reads the Upstox reader; the strategy + dynamic universe + parity + tests stay as below).
The NSE-bhavcopy stages below are kept as the FALLBACK design only.

## STAGE 4a — DONE (2026-06-29): `futures_screener` universe plumbing (parity-safe)
The dynamic universe rides the EXISTING submission-pin parity mechanism — NO V009 side-channel, NO
migration, NO replay/golden change (`resolveUniverse` is LIVE-only; the engine lib + `TickwiseGoldenRunner`
are untouched). Investigation: workflow `wf_8c8e0d72-75f` + 2 follow-ups. Built:
- **schema**: 5th `universe.mode` oneOf branch `futures_screener` (`side` long/short, `max_picks` 1-20) +
  the `accept/futures-screener.yaml` corpus fixture (CorpusTest 33/33).
- **registry `UniverseResolver.resolveFuturesScreener`**: submission-pin — GET `/movers-screen` → top
  `max_picks` conviction underlyings → `/term-structure` per pick → front contract → pinned `items`.
- **engine `FuturesUniverseResolver.resolveScreener` + `SignalEngine.resolveUniverse` case**: live reload
  re-screens (08:40 + hot-swap); each picked mover → front contract via the SHARED `resolve()` (roll
  handling reused) → auto-subscribed by the existing universe→ensureWarm loop (multi-instrument is fully
  wired: `onClosedBar` iterates `strategy.universe()`, no size>1 guard).
- **backtest `JobsService`**: `futures_screener` added to the submission-pin condition (else replay gets an
  empty universe). Phase-44 lifted the publish guard → the mode publishes freely.
- Pure `screenerPicks` parse unit-tested both sides (5/5).
- **KNOWN 4b GAP (adversarial-review caught):** `BacktestRunner.signalInstrument` (backtest replay) reads
  the strategy config's `universe.instruments` / `signal_underlying` / `underlying` to pick the single
  signal series — a `futures_screener` config has NONE of those (its instruments live in the pinned request
  array), so a functional backtest currently throws "needs an explicit single-instrument universe". NOT a
  parity defect (every existing mode is byte-identical; this is a missing CONSUMPTION arm, not a regression)
  and nothing references the mode yet. **4b must wire the runner to read the pinned `futures_screener`
  universe** (top pick for the v1 functional backtest, or a per-constituent fold for full N-instrument).
- REMAINING = **4b**: the `BacktestRunner` consumption arm + the `mm-stockfut-bank` YAML + register +
  functional backtest + multi-instrument smoke (full-N50 "nifty" variant = v2, Upstox-key-gated).

## STAGE 4b — CODE DONE (2026-06-29): BacktestRunner consumption arm + the strategy YAML
Built per the recipe below. `BacktestRunner.signalInstrument(config, request)` gained a GATED
`futures_screener` arm that signals on the TOP pinned pick (`request.universe[0]`) — existing modes
byte-identical (`BacktestRunnerSignalInstrumentTest` 9/9, incl. the futures_screener pick + empty-pin
throw). `docs/strategies/mm-stockfut-bank.yaml` = the long-only NIFTY-Bank momentum strategy (schema-valid;
NOT scalper-seeded — it has no scalper gate, so it stays out of `ScalperStrategySeeder`/its load tripwire).
REMAINING = the OPS step only: register `mm-stockfut-bank` via `POST /api/v1/strategies` on the live/mock
stack + one functional backtest (≈0 trades on muted history expected; judge live). v2 = full-N50 radar
(Upstox active F&O key) + the SPAN-gated short side.

## STAGE 4b — VERIFIED RECIPE (2026-06-29, one-shot; root-caused, no more investigation)
The replay seam is fully mapped. `BacktestRunner.run` (`BacktestRunner.java:108-128`): `config = resolved.config()`
is the ORIGINAL strategy version config (universe.mode + fields); `request = job.request()` carries the
SUBMISSION-PINNED `universe` ARRAY (`{exchange,tradingsymbol}` items, from `JobsService:115`) + `universeChecksum`.
`signalInstrument(config)` (`:436`) reads the ORIGINAL config — `futures_of_underlying` resolves the
`universe.underlying` OBJECT → signals on the **underlying SPOT** (NOT the pinned contract); `futures_screener`
config has no `underlying`/`instruments` → throws "needs an explicit single-instrument universe".
**The runner is SINGLE-signal-instrument** (`primary1m = read(signal,...)`, one fold) — so v1 = signal on the
TOP pick.
**FIX (gated, additive, parity-safe):**
1. Change `signalInstrument(JsonNode config)` → `signalInstrument(JsonNode config, JsonNode request)`; update
   the call at `:128` + `BacktestRunnerSignalInstrumentTest`.
2. As the FIRST arm: `if ("futures_screener".equals(config.path("universe").path("mode").asText())) {` read
   `request.path("universe")` (the pinned array); if non-empty array → `SeriesKey(first.exchange, first.tradingsymbol, "1m")`;
   else throw "futures_screener backtest needs a pinned universe (submission produced no movers)". **MUST stay
   gated to futures_screener** — a global pinned-array-first would flip `futures_of_underlying` spot→contract and
   break existing-mode parity. Existing modes untouched → goldens/parity byte-identical.
3. `mm-stockfut-bank.yaml` (futures_screener, side=long, max_picks=5, RSI+VWAP+SUPERTREND momentum gate,
   stop_loss+signal_exit, fixed_quantity, intraday) — clone the `accept/futures-screener.yaml` shape (already
   schema-valid). Seed it where the scalper YAMLs are seeded; register the variant.
4. Functional backtest: submit when the radar HAS captured movers — runs on the TOP pick (≈0 trades on muted
   history = expected — judge LIVE). The pin captures TODAY's movers; an EMPTY pin (fresh/off-hours radar)
   THROWS "needs a pinned universe" (consistent with the `index` mode throw — you cannot backtest an empty
   universe), so run it against a stack whose screener returns ≥1 mover.
5. Multi-instrument smoke = the engine LIVE path (resolveScreener → N InstrumentRefs → onClosedBar iterates);
   covered by `FuturesUniverseResolverTest.screenerPicks` + the existing no-size>1-guard. The single-instrument
   BACKTEST runner does NOT need N (top-pick is the functional proof).

## STAGE 3b — DONE (2026-06-29): `MarketMoversScreenService` + `GET /api/v1/market/futures/movers-screen`
Built exactly per the recipe below: front-pick per radar underlying from the newest captured bucket +
`reader.eod` history → reuse `MarketMoversScreener.classify`/`screen`. Map envelope `{longCandidates,
shortCandidates, asOf}`, 422 until ≥1 radar bucket; contract spec + gen TS re-captured (new path drift).
Tests: `MarketMoversScreenServiceTest` (2) + the controller IT (12) green. REMAINING = Stage 4 (the
`mm-stockfut-{nifty,bank}` strategy + the parity-sensitive dynamic-universe engine arm) + Stage 5.

## STAGE 3b — VERIFIED ASSEMBLY RECIPE (2026-06-29, one-shot build, no more investigation)
The capture is config-driven (`FuturesOiSnapshotService.snapshotNow` iterates `oi-snapshot-underlyings`
incl. the ~17 bank stocks, resolving each via `monthlyFutures` — the "indices-only" `FuturesMoversService`
javadoc is STALE). So the bank-stock futures ARE captured (when the master is synced). Build
`futures/screener/MarketMoversScreenService` (a @Service) thus — it REUSES the stage-3a `MarketMoversScreener.classify`:
1. `List<FutPoint> pair = reader.latestPairAll(RADAR_UNDERLYINGS, interval, date)` (`FuturesSnapshotReader:169`)
   — every radar stock's contracts, latest 2 buckets.
2. Fold to the FRONT future per underlying (nearest expiry; mirror `/banks-grid`'s front-pick). Each front
   `FutPoint` carries `dayOpen/dayHigh/dayLow` + `ltp` + `oi` + `volume` → build TODAY's `MarketMoversScreener.DailyBar`.
3. `List<EodRow> hist = reader.eod(stock, today.minusDays(~25), today.minusDays(1))` (`FuturesSnapshotReader:201`)
   → map each `EodRow` → a historical `DailyBar`.
4. `bars = hist DailyBars ++ [today DailyBar]` → `MarketMoversScreener.classify(stock, bars)` → `ScreenerRow`.
   (REUSE — no second grading path; OI-quadrant, OH/OL, breakout, daily-RSI all fall out of `classify`.)
5. Collect → `MarketMoversScreener.screen`-style long/short ranking → return.
6. `GET /api/v1/market/futures/movers-screen?mode&date&interval` on `FuturesAnalyticsController` (sibling
   `/movers`:184), Map envelope `{longCandidates, shortCandidates, asOf}`, 422 until ≥1 radar bucket. New
   `@GetMapping` path → re-capture `ContractCaptureTest`.
RADAR_UNDERLYINGS = the bank stocks already in `oi-snapshot-underlyings` (HDFCBANK, ICICIBANK, SBIN, …).
Verify FutPoint/EodRow exact field names before mapping (read `FuturesSnapshotReader:24` FutPoint + the
EodRow record). Pre-deploy: `SELECT count(*) FROM instruments WHERE instrument_type='FUT' AND
underlying_tradingsymbol IN (radar)` > 0 (else the capture silently no-ops).

## STAGE 3b DECISION (2026-06-29, build-time finding)
Resolving an ARBITRARY stock → its ACTIVE NFO front-future Upstox `instrument_key` is NOT in the codebase
today: `FuturesContractSource.monthlyFutures` returns `FutContract(InstrumentKey(exchange,tradingsymbol),
expiry)` — no Upstox key; only the 2 indices carry hardcoded Upstox keys (`ExpiredBackfillService.UNDERLYING_KEYS`);
`StockUpstoxKeyMap` gives the EQ key (`NSE_EQ|ISIN`), not the FUT key. A faithful Upstox-on-demand path
needs a NEW Upstox F&O instrument-master lookup (segment=NSE_FO, underlying, FUT, front-expiry → key) — a
real component (silent-no-op if the master is unsynced, per the risks).
**DECISION (v1): reuse the EXISTING captured bank-stock futures infra instead.** The `oi-snapshot-underlyings`
config (application.yml:189) ALREADY captures ~17 NIFTY-Bank-constituent stock futures (HDFCBANK, ICICIBANK,
SBIN, …) with full key resolution, and `FuturesMoversService.movers` already ranks them by price%/OI%/quadrant.
So Stage 3b v1 = a NIFTY-Bank radar screener over the captured snapshots (live price%/OI%/quadrant/OH-OL) +
`candles` 1d (the 8/9-day breakout + daily-RSI), reusing `NDayExtremes`/`DailyRsi` + the captured movers — NO
Upstox-key wall, NO new feed. **Widen to the full NIFTY-50 (RELIANCE/TCS/INFY/…) via the Upstox active F&O
master lookup as a v2** (the `activeCandles` client #341 is ready for it; only the key resolution is the gap).
Faithful: the deck explicitly filters Market-Movers to "Nifty 50 AND Nifty Bank"; a Nifty-Bank v1 is a true
subset, not a degradation.

## Verdict — buildable, NO missing feed, NO OOM
Every primitive exists or 1:1-mirrors the equity-bhavcopy stack:
- per-stock **daily OHLCV** (8/9-day breakout + daily RSI) → already ingested for the ~22k universe via
  `candles` 1d `source=BHAVCOPY` (`BhavcopyBackfillService.projectNse`).
- live **LTP/LTP%/OH-OL/volume mover-rank** → `FuturesMoversService.movers` + `OiBuzzService` batched quote.
- **4-quadrant OI interpretation** → `OiInterpretation.classify` (`FuturesMoversService:85`).
- **stock-future paper execution** → `InstrumentClass.FUTURE` + `futureMarginPct` (SPAN-aware).
- the **ONLY net-new feed** = daily per-stock-FUTURE OI from the NSE F&O **UDiFF bhavcopy** (~few hundred
  rows/day, a clone of the V014 equity stack). The prior OOM was the multi-year OPTIONS backfill (~350M
  rows) — NOT this.

## Architecture (locked decisions)
- **Execution = a STANDARD futures-momentum engine strategy** (`futures_of_underlying`), NOT a scalper-gate
  variant. The deck says "trade FUTURES, not stock options" — a plain momentum entry on the picked stock
  future (RSI>60, above VWAP/SuperTrend/WMA, rising volume, entry-candle-low stop) = exactly the corpus
  `futures-universe.yaml` shape. The scalper gate exists only to pick an OPTION via StrikePicker → would
  force a spurious index-option leg → wrong. No StrikePicker, no option leg.
- **NEW id family `mm-stockfut-nifty` / `mm-stockfut-bank` (long-only v1)** — do NOT re-platform the
  existing `scalp-market-movers-*` triplet (a universe-mode change breaks checksum + golden; they stay the
  documented honest index-option momentum surrogate).
- **Screener lives in MARKET-DATA** (D7/D10 — strategy-signal has no marketdata DB grant). New
  `GET /api/v1/market/futures/movers-screen` (Map envelope `{longCandidates, shortCandidates, asOf}`; new
  `@GetMapping` path DRIFTS the springdoc spec → re-capture `ContractCaptureTest` in-PR).
- **Dynamic per-day universe = the one net-new engine hook**: a `MarketMoversSelector` (strategy-signal)
  reads the screener via `MarketOiClient.get`, and a new resolver arm in `SignalEngine.resolveUniverse`
  feeds the picked-mover front futures into the EXISTING `futures_of_underlying` subscribe/tick path,
  refreshed daily at `morningReload(08:40)`/reconcile. **Partition: screener = WHICH stock (market-data),
  engine entry_rules = WHEN on its future (strategy-signal).**
- **Parity:** the live screen is non-deterministic → persist the picked stock(s) in the V009 side-channel
  at entry + replay (never re-screen on replay) — mirrors the index-option/Hero-Zero pattern.

## Stages (one PR each)
1. **F&O UDiFF bhavcopy ingest** (market-data) — clone the V014 equity stack: new migration
   `nse_eod_fo_bhavcopy` (trade_date, instrument_type FUTSTK|FUTIDX, symbol, expiry_date, OHLC, settle,
   contracts, val, open_int, chg_in_oi; PK (trade_date,symbol,expiry); hypertable, compress 7d, NO
   retention) + `FoBhavcopyFetcher`/`LiveFoBhavcopyFetcher` (UDiFF CSV: FinInstrmTp STF/IDF,
   Opn/Hgh/Lw/Cls/Sttlm/OpnIntrst/ChngInOpnIntrst; header-sniff; empty-on-404) + `FoBhavcopyRepository`
   (clone NseEodBhavcopyRepository) + a `runFo()` leg in `BhavcopyBackfillService`. **RAW-TABLE-ONLY** —
   never project FUT rows into `candles` (collides on (exch,sym,1d,bucket) with live FUT bars + the
   continuous-future stitch). OI% = chg_in_oi / NULLIF(open_int − chg_in_oi, 0).
2. **Curated equity-futures live snapshot subset** (market-data, OOM-bounded) — new property
   `artha.futures.equity-radar-stocks` (~50 large-cap N50/NBANK constituents, **front-month only**) + a
   SEPARATE slower-cadence pass in `FuturesOiSnapshotService` (the index 3-min cadence UNTOUCHED). Resolve
   via `FuturesContractSource.monthlyFutures`. Pre-deploy guard: `SELECT count(*) FROM instruments WHERE
   instrument_type='FUT' AND underlying_tradingsymbol IN (radar)`. Raise DB memory before any broader run.
3. **MarketMoversScreener + endpoint** (market-data) — `MarketMoversScreener` reusing
   `FuturesMoversService.movers` over the radar set, enriched into `ScreenerRow{symbol, ltp, pricePct,
   oiPct, interpretation, breakoutDays, openHigh/Low, dailyRsiOk, advTurnover, newHigh/Low}`; pure helpers
   `NDayExtremes` (8/9-day high/low, **front-contract-reduced per day** before the rolling extreme),
   `LiquidityRanker`, per-stock daily-RSI over candles 1d, OH/OL via `OpenHighLow.marks`. longCandidates =
   8/9-day-HIGH ∧ OL ∧ LB/SC ∧ dailyRsiOk, ranked by pricePct×liquidity. 422 until ≥1 radar bucket. New
   `/movers-screen` on `FuturesAnalyticsController` (sibling /movers). Re-capture contracts.
4. **Stock-futures strategy + dynamic universe** (strategy-signal + engine) — new
   `mm-stockfut-{nifty,bank}.yaml` (`futures_of_underlying`, long-only, momentum: RSI/VWAP/SUPERTREND/VWMA/
   VOLUME_RATIO; gate close>vwap ∧ close>supertrend ∧ rsi>60 ∧ rising-volume; exit signal_exit + entry-
   candle-low stop + time_stop; sizing `fixed_quantity`/future-notional, NOT premium_budget) +
   `MarketMoversSelector` (reads /movers-screen) + the `SignalEngine.resolveUniverse` resolver arm
   (pre-subscribe the radar front futures in seriesStore — without a subscribed EngineSeries the picked
   mover silently has no series) + V009 pick-persistence for parity.
5. **Tests + golden/parity + register + functional backtest** — screener unit tests (NDayExtremes
   roll-boundary, quadrant, ranking), selector failure-isolation (empty→block), resolver-arm subscription
   test, persisted-pick parity replay; GoldenDeterminismTest + BacktestParityTest byte-identical (no
   scalper-gate touched; mm-stockfut have no prior golden); register the 2 variants + a functional backtest
   each (~0 historical trades on muted history = expected; judge on FORWARD live paper).

## Key decisions (recommended, locked)
- Long-only v1 (SHORT = futures short-sell, SPAN-gated → v2; the screener already computes shortCandidates).
- Sizing = `fixed_quantity` / future-notional risk vs `futureMarginPct` (NOT premium_budget = option-only);
  size off the PICKED stock future's price/lot (RestInstrumentMetaClient resolves FUTURE lotSize).
- Backtest = LIVE-PAPER-ONLY for the verdict (functional backtest for plumbing); persist the pick for
  parity but judge edge forward (consistent with the 12 owner-LIVE-validated scalpers).

## Risks / traps
- The equity-futures live capture is the ONE OOM risk → front-month + ~50-name radar + separate slower pass
  + leave the index OI cadence untouched + raise DB memory first.
- HARD prereq often missed: pre-SUBSCRIBE the picked stock's front-future candle series in the engine
  seriesStore (today only the strategy's own signal future is subscribed) — else the picked mover has no
  EngineSeries and silently blocks every bar.
- Instrument-master: equity-future resolution needs the NFO FUT rows synced (`instrument_type='FUT' AND
  underlying_tradingsymbol`) — unsynced → both capture + resolver silently no-op. Verify with a SELECT.
- UDiFF schema not verified in-repo — confirm the URL + column order + mirror the header-sniff guard.
- Candle-collision: keep F&O bhavcopy raw-table-only.

## Descope (NOT built)
per-stock OPTION IV/greeks · bulk per-stock intraday capture · the full ~190-stock live universe (radar ~50)
· stock-BTST overnight carry · the SHORT side (v2) · re-platforming the existing triplet · a deterministic
screener-replay backtest engine · equity-CASH execution (route to the future).
