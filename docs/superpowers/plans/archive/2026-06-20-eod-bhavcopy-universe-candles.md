# EOD bhavcopy → universe daily candles + split/bonus adjustment

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


**Branch:** `feat/eod-bhavcopy-candles` · **Status:** built + verified (299-test module `verify` green) · **Date:** 2026-06-20

Bulk daily-EOD loader so **every** NSE + BSE cash equity (~22k) has a 1d candle for
charting/backtesting — without a per-stock Kite call (~14h, impractical) — plus read-time
split/bonus adjustment so a daily series spanning a corporate action has no false price cliff.

Owner goal is options scalping (intraday, recent) where adjustment is irrelevant; this universe
layer is for browsing any stock (incl. penny stocks) and future universe-wide daily backtests.

## Phases (all on this branch)

- **A — NSE (extend).** NSE bhavcopy capture already existed (`LiveBhavcopyFetcher` →
  `nse_eod_bhavcopy`, cron `0 0 19`) but only walked back 5 days and never reached `candles`.
  Added: date-addressable `fetchForDate`, watermark self-healing catch-up (every missing trading
  day from `max(trade_date)` → today; 404 = skip), and projection of EQ/BE rows into `candles` as
  1d `source=BHAVCOPY`.
- **B — BSE (new).** BSE UDiFF bhavcopy (`BhavCopy_BSE_CM_0_0_0_YYYYMMDD_F_0000.CSV`, plain CSV,
  `FinInstrmTp=STK`), `bse_eod_bhavcopy` table, same catch-up + projection (keyed by `TckrSymb` —
  our BSE `tradingsymbol` is the ticker, verified against the live instruments table).
- **C — split/bonus adjustment.** Ratios from the **NSE corporate-actions feed** (`subject` text →
  ratio), read-time multiplicative adjuster (`EquitySplitBonusAdjuster`, sibling of
  `ContBackAdjuster`), source-aware so Kite bars aren't double-adjusted.

## Key design decisions

1. **Projection is `INSERT … ON CONFLICT DO NOTHING`.** The `candles` PK is
   `(exchange, tradingsymbol, "interval", bucket)` — **`source` is not in the key**. A BHAVCOPY 1d
   row and a Kite 1d row collide on the same bucket (1d bars are bucketed at IST midnight). DO
   NOTHING lets bhavcopy fill the long tail but never clobber a Kite/live-owned bar.
2. **CorporateActionJob is gated to skip BHAVCOPY-only series.** It sweeps `activeEquities()` and
   Kite-fetches any symbol with cached 1d candles to detect splits, then *purges + re-fetches from
   Kite*. Once bhavcopy fills 1d candles for all ~22k equities that would be one Kite fetch per
   symbol **daily**. `CandleRepository.hasNonBhavcopyDaily` gates it: only Kite-touched symbols are
   swept; the bhavcopy universe is adjusted on read instead. (Safe — only *reduces* Kite load.)
3. **Phase-C ratio source — the prev_close premise was DISPROVEN.** Initial idea was to derive
   ratios from bhavcopy's `PREV_CLOSE`. Verified empirically (CUB 1:3 ex-12-Jun, TRENT 1:2
   ex-04-Jun) that **NSE/BSE `PREV_CLOSE` is NOT corporate-action-adjusted** (`close[t-1] ==
   prev_close[t]`), so that diff is always ≈1.0 and reveals nothing. The authoritative Kite-free
   source is the **NSE corporate-actions API** (`/api/corporates-corporateActions?index=equities`,
   cookie-seeded); the `subject` text is parsed: `Bonus a:b → b/(a+b)`, `Face Value Split From RsX
   To RsY → Y/X`, dividends ignored. `ratio` is the pre-ex multiplier (apply to bars before ex-date).
4. **Both exchanges get ratios, two ways.** The NSE CA feed adjusts NSE listings directly and the
   BSE listing of the same company via ISIN cross-map (`bse_eod_bhavcopy.isin → ticker`). A **direct
   BSE corporate-actions feed** (`api.bseindia.com/.../DefaultData/w?strSearch=S&ddlcategorys=E&segment=0`,
   JSON; `Purpose` text parsed by the same `CorporateActionSubjectParser`; keyed by `scrip_code →
   ticker`, short-name fallback) closes the remaining gap — **BSE-only scrips with no NSE twin**.
5. **BSE non-trading-day = HTTP 200 + HTML homepage (not 404).** The BSE fetcher content-sniffs the
   body (HTML / wrong header line → "not published"), never the status code. NSE archives DO 404.
6. **Catch-up is bounded + self-healing (anti-join).** `catchup-max-days` (default 90) caps the
   walk. Crucially, the walk **anti-joins against the trade-dates already stored** over a trailing
   `reconcile-lookback-days` (default 7) window, rather than only marching forward from
   `max(trade_date)`. A transient fetch error reads identically to a holiday (both yield an empty
   list), so without the anti-join a day missed transiently would be skipped forever once the
   watermark advanced past it — a permanent silent hole. The anti-join leaves a missing day MISSING
   and re-probes it on a later run, so it heals. (Found by the adversarial review; regression-tested.)

## Files

- Migrations: `V020__candles_source_bhavcopy.sql` (+`.conf`, decompress-dance like V018),
  `V021__bse_eod_bhavcopy.sql`, `V022__eod_corporate_actions.sql`.
- `bhavcopy/`: `BhavcopyBackfillService` (orchestrator + schedule + async trigger), `BhavcopyCandles`
  (projection builder), `CorporateActionSubjectParser` (pure), `EodBackfillController`.
- `bse/`: `BseHttpClient`, `BseBhavcopyFetcher` (+Live/Mock), `BseEodBhavcopyRepository`
  (+`presentTradeDates`/`tickerForScrip`), `BseCorporateActionFetcher` (+Live/Mock).
- `nse/`: `BhavcopyFetcher.fetchForDate` + `LiveBhavcopyFetcher`/`Mock`, `NseEodBhavcopyRepository.maxTradeDate`,
  `NseHttpClient.getWithCookieSeed`, `NseCorporateActionFetcher` (+Live/Mock). `NseEodScheduler` no
  longer owns bhavcopy.
- `candles/`: `CandleRepository.insertIgnoreAll` + `hasNonBhavcopyDaily`, `EquitySplitBonusAdjuster`,
  `EodCorporateActionRepository`, `CandlesController` (1d back-adjust hook).
- `corporateactions/CorporateActionJob` — BHAVCOPY-only gate.
- `common-web` `ErrorCodes.CONFLICT_BACKFILL_RUNNING`.

## Config (`application.yml`, live profile; all have @Value defaults)

```
artha.nse.bhavcopy.candle-series: EQ,BE      # NSE series projected to candles
artha.bse.base-url: https://www.bseindia.com
artha.bse.api-url: https://api.bseindia.com  # BSE corporate-actions JSON (DefaultData/w)
artha.bhavcopy.eod-cron: "0 30 19 * * MON-FRI"  # after NSE ~19:00 + BSE ~18:00
artha.bhavcopy.catchup-floor-days: 365       # first-boot depth (empty table) — ~1y of history
artha.bhavcopy.catchup-max-days: 365         # hard cap on the catch-up span
artha.bhavcopy.reconcile-lookback-days: 365  # full-window anti-join re-scan (heals deep holes)
artha.bhavcopy.ca-lookback-days: 420         # CA-ratio refresh window (>1y)
```

On-demand: `POST /api/v1/market/eod-backfill` (202 + jobId; 409 `CONFLICT_BACKFILL_RUNNING`),
`GET /api/v1/market/eod-backfill/status`. Auto-proxied by the edge-gateway `/api/v1/market/**` route.

## Deferred — frontend buttons (post-React migration, NOT in this branch)

Per the "no Angular changes until React" constraint, the backend endpoints are built; the UI
controls land with the React migration. Mirror the Settings-page instrument-sync pattern (POST →
poll `…/status` → toast):

1. **"Run EOD backfill now"** → `POST /api/v1/market/eod-backfill`, poll
   `GET /api/v1/market/eod-backfill/status` until `state` leaves `RUNNING`, toast OK/FAILED. The
   status payload carries per-exchange `days/bhavRows/candleRows` + `ratiosDetected`.
2. **"Refresh split/bonus adjustments"** — the ratio refresh currently runs *inside* the combined
   backfill (`runRatios`). For a dedicated button, add a small `POST /api/v1/market/eod-backfill/ratios`
   endpoint calling only `runRatios()` (one cheap CA API call); until then button (1) covers it.

## Known gaps / follow-ons

- **Volume is not split-adjusted** (OHLC only, matching `ContBackAdjuster`); price continuity is the
  goal. Add `volume × 1/ratio` if a volume-accurate study needs it.
- The corporate-action feeds are anti-bot/undocumented contracts (NSE cookie-seeded; BSE's
  `strSearch=S` is a discovered, undocumented mode), best-effort and **non-fatal** — if a feed
  breaks, those ratios just don't populate and candles serve raw, exactly as today. Worth a canary
  asserting non-empty results.
- (Closed) BSE-only listings now auto-populate from the direct BSE corporate-actions feed.

## Live deploy (when greenlit — market closed)

1. Build the JAR (reactor + `-am`), `docker compose … build market-data-service && up -d` with the
   LIVE env (`ARTHA_DB_NAME=artha`, `ARTHA_REDIS_DB=0`). flyway-init applies **V020 (decompress-dance
   on live candles — needs disk headroom; bounded, lossless), V021, V022**.
2. First boot's `ApplicationReadyEvent` auto-runs the catch-up. With the ~1-year window this first
   backfill fetches ~250 trading days × 2 exchanges (serial) — **~30–60 min** — plus a CA refresh.
   Watch `/api/v1/market/eod-backfill/status` and the `market-data` logs. Subsequent daily runs are
   seconds (anti-join skips the stored dates). It runs on its own executor, off the live OI path.
3. Spot-check: `GET /api/v1/market/candles?exchange=NSE&tradingsymbol=<pennystock>&interval=1d&...`
   returns BHAVCOPY bars; a known recent split (e.g. via the manual-test guide) adjusts on read.
