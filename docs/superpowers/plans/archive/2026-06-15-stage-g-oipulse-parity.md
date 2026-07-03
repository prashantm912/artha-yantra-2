# Stage G — oipulse-parity completion Implementation Plan

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the oipulse-parity OI-analytics suite — the remaining options endpoints, the full futures family (incl. OHLC capture), FII/DII + breadth EOD reads, history-mode wiring, and the frontend pages/charts that consume them.

**Architecture:** One stage = one branch `feat/stage-g-oipulse-parity`, **one commit per phase**, one final PR. Backend reuses the established query-time `time_bucket('Asia/Kolkata') + last()` read layer (NO continuous aggregates), the `OiQuery`/`OiInterval`/`OiInterpretation` control-bar contract, the `DATA_GAP`-422 / `VALIDATION_*`-400 error pattern, and the `MarketDataIntegrationTestBase` Testcontainers harness. Frontend reuses NgRx signalStore + `SymbolContextStore`, decimal-string handling, generation tokens, `ay-data-bar`, `ay-echart` (the boundary-free chart wrapper), and the hardcoded `app-shell` nav.

**Tech Stack:** Java 21 / Spring Boot (market-data-service, JdbcTemplate), TimescaleDB, Flyway; Angular 21 zoneless + PrimeNG 21 + NgRx Signals + ECharts (`ay-echart`); vitest; springdoc + openapi-typescript@7.

**Scope decisions (owner, 2026-06-15):**
- **Option-strategies (straddle/strangle/multi-leg/payoff + Greeks/POP) is CUT** from oipulse parity (undefined schema, Greeks/POP/payoff — out of scope, not deferred-within). (Note: the lightweight read-only `/premium` straddle/strangle *premium* metric IS in Stage G — it is a pure LTP read, no Greeks/POP/payoff.)
- **Futures OHLC capture is EXTENDED** (V015 + thread Kite quote `ohlc`). Forward-only data; ITs seed rows. Unblocks `/buzz`, `/movers`, `/eod`.
- **`/banks`** (oipulse "bank-stock futures grid") is scoped to **NIFTY BANK *index* futures buildup** (front/next/far month + basis) because only index futures are captured today; a bank-*stock* futures grid needs a capture expansion and is flagged as a follow-on (like OHLC was).

**Data-source ground truth (from grounding exploration):**
- `options_chain_snapshots` (V006, 21 cols) has ltp/oi/oi_change/iv/spot_price + greeks delta/gamma/theta/vega/rho.
- `futures_oi_snapshots` (V011, 8 cols: ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) — **NO day high/low** → G0 adds them.
- `nse_eod_fii_dii` (V012, 6 cols), `nse_eod_participant_oi` (V013, 16 cols), `nse_eod_bhavcopy` (V014, 16 cols incl OHLC + delivery%) — **write repos only, no readers** → G9/G10 add readers.
- Futures capture = NIFTY 50 + NIFTY BANK monthly futures only (no bank stocks).
- Highest migration = V014; **next free = V015**. Applied migrations are checksum-locked (corrections use a new suffix, never in-place).

---

## Shared spine (the single source of truth — every phase references these verbatim)

### New / extended Java types

```
// G0 — futures OHLC
QuoteGateway.Quote                 // ADD nested: Ohlc ohlc  (record Ohlc(BigDecimal open, high, low, close))
FuturesSnapshotReader.FutPoint     // ADD: BigDecimal dayOpen, dayHigh, dayLow, prevClose

// G2 options /big-oi
options.analytics.OiBigOiService
  record BigOiRow(BigDecimal strike, String optionType, long oi, long oiChange, BigDecimal ltp)
  record BigOi(List<BigOiRow> items, OffsetDateTime asOf)
  BigOi bigOi(List<StrikePoint> latest, int topN)

// G3 options /premium
options.analytics.OiPremiumService
  record PremiumRow(BigDecimal strike, BigDecimal straddle, BigDecimal ce, BigDecimal pe)
  record PremiumChain(List<PremiumRow> items, BigDecimal atmStrike, BigDecimal atmStraddle, BigDecimal spot, OffsetDateTime asOf)
  PremiumChain premium(List<StrikePoint> latest)

// G4 options /trending
options.analytics.OiTrendingService
  enum Trend { UP, DOWN, FLAT }
  record TrendPoint(OffsetDateTime bucket, long totalOi, long ceOi, long peOi, Trend trend)
  record TrendSeries(List<TrendPoint> items, OffsetDateTime asOf)
  TrendSeries trending(List<StrikePoint> series)   // series already bucket-ordered

// G5 futures /spurt
FuturesSnapshotReader                // ADD latestPair(name, interval, date)
futures.analytics.FuturesSpurtService
  record FutSpurt(String tradingsymbol, BigDecimal ltp, long oi, long oiChange, BigDecimal spurtPct, OiInterpretation interpretation)
  record FutSpurtChain(List<FutSpurt> items, OffsetDateTime asOf)
  FutSpurtChain spurts(List<FutPoint> pair)

// G6 futures /movers + /banks
futures.analytics.FuturesMoversService
  record MoverRow(String tradingsymbol, BigDecimal ltp, BigDecimal pricePct, BigDecimal oiPct, OiInterpretation interpretation)
  record Movers(List<MoverRow> gainers, List<MoverRow> losers, OffsetDateTime asOf)
  Movers movers(List<FutPoint> pair)
  record BankRow(String tradingsymbol, LocalDate expiry, BigDecimal ltp, long oi, long oiChange, BigDecimal basis, OiInterpretation interpretation)
  record Banks(List<BankRow> items, OffsetDateTime asOf)   // NIFTY BANK term structure
  Banks banks(List<FutPoint> pair, BigDecimal spotProxy)

// G7 futures /buzz
futures.analytics.FuturesBuzzService
  record BuzzMatrix(List<String> contracts, List<OffsetDateTime> buckets, List<List<OiInterpretation>> cells, OffsetDateTime asOf)
  BuzzMatrix buzz(List<FutPoint> series)

// G8 futures /eod
FuturesSnapshotReader                // ADD eod(name, from, to) -> List<EodRow>
  record EodRow(String tradingsymbol, LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long oiClose, long oiChange, long volume)

// G9 FII/DII (new pkg in.arthayantra.marketdata.nse.read OR nse.analytics)
nse.analytics.NseEodReaders
  record FiiDiiRow(LocalDate tradeDate, String category, BigDecimal buyValue, BigDecimal sellValue, BigDecimal netValue)
  record ParticipantOiRow(LocalDate tradeDate, String clientType, long futIdxLong, long futIdxShort, long futStkLong, long futStkShort, long optIdxCallLong, long optIdxPutLong, long optStkCallLong, long optStkPutLong, long totalLong, long totalShort)  // map ACTUAL V013 cols at impl
  List<FiiDiiRow> fiiDii(LocalDate from, LocalDate to)
  List<ParticipantOiRow> participantOi(LocalDate from, LocalDate to)

// G10 breadth
nse.analytics.BreadthService
  record BreadthSummary(LocalDate tradeDate, int advances, int declines, int unchanged, int total, BigDecimal avgDeliveryPct)
  record DeliveryRow(String symbol, BigDecimal deliveryPct, BigDecimal close, BigDecimal pctChange)
  record Breadth(BreadthSummary summary, List<DeliveryRow> topDelivery, OffsetDateTime asOf)
  Breadth breadth(LocalDate date)
```

### New endpoints (all under edge-gateway's generic `/api/v1/market/**` proxy — no gateway change)

| Method+Path | Controller | Params | Empty → |
|---|---|---|---|
| GET `/api/v1/market/options/big-oi` | OptionsAnalyticsController | mode,name*,date,interval,expiry* | 422 DATA_GAP |
| GET `/api/v1/market/options/premium` | OptionsAnalyticsController | mode,name*,date,interval,expiry* | 422 DATA_GAP |
| GET `/api/v1/market/options/trending` | OptionsAnalyticsController | mode,name*,date,interval,expiry* | 422 DATA_GAP |
| GET `/api/v1/market/futures/spurt` | FuturesAnalyticsController | mode,name*,date,interval | 422 DATA_GAP |
| GET `/api/v1/market/futures/movers` | FuturesAnalyticsController | mode,name*,date,interval | 422 DATA_GAP |
| GET `/api/v1/market/futures/banks` | FuturesAnalyticsController | mode,name*,date,interval | 422 DATA_GAP |
| GET `/api/v1/market/futures/buzz` | FuturesAnalyticsController | mode,name*,date,interval | `{cells:[]}` 200 |
| GET `/api/v1/market/futures/eod` | FuturesAnalyticsController | name*,from*,to | `{items:[]}` 200 |
| GET `/api/v1/market/fii-dii/cash` | FiiDiiController (new) | from*,to | `{items:[]}` 200 |
| GET `/api/v1/market/fii-dii/participant-oi` | FiiDiiController | from*,to | `{items:[]}` 200 |
| GET `/api/v1/market/fii-dii/long-short` | FiiDiiController | from*,to | `{items:[]}` 200 |
| GET `/api/v1/market/breadth` | BreadthController (new) | date* | 422 DATA_GAP |

`*` = required (400 VALIDATION_FAILED if absent). List endpoints return `{items:[...]}` or the record envelope; scalars return the record directly (CLAUDE.md convention; `Map<String,Object>` returns do NOT drift the springdoc spec).

### History-mode wiring (G1) — reader signature convention

Every options/futures reader read method gains an optional `LocalDate date` (null = live/newest-overall; non-null = newest bucket *within that IST day*). Existing no-`date` callers delegate with `null`. Controllers pass `q.date()`; when `!q.live()` and `q.date()==null` → `requireDate(q)` throws 400.

```java
// OptionsSnapshotReader (and mirror in FuturesSnapshotReader)
public List<StrikePoint> latest(String u, LocalDate exp, OiInterval iv) { return latest(u, exp, iv, null); }
public List<StrikePoint> latest(String u, LocalDate exp, OiInterval iv, LocalDate date) { ... }
public List<StrikePoint> latestPair(String u, LocalDate exp, OiInterval iv) { return latestPair(u, exp, iv, null); }
public List<StrikePoint> latestPair(String u, LocalDate exp, OiInterval iv, LocalDate date) { ... }
// date != null → WHERE ts >= <date 00:00 IST> AND ts < <date+1 00:00 IST>, then anchor on max(ts) within
```

### Frontend wire types & stores

- **`OiAnalyticsStore`** (`stores/oi-analytics.store.ts`): add interfaces `BigOiRow/BigOi`, `PremiumRow/PremiumChain`, `TrendPoint/TrendSeries`, `FutSpurt/FutSpurtChain`, `MoverRow/Movers`, `BankRow/Banks`, `BuzzMatrix`, `EodRow`; state slots + loading flags + generation tokens (`bigOiGen`, `premiumGen`, `trendGen`, `futSpurtGen`, `moversGen`, `banksGen`, `buzzGen`, `eodGen`); loaders mirroring `loadSpurt()` (decimal-string safe, SILENT for 422-normal, `unsatisfiable()` guard, history-without-date guard).
- **New `FiiDiiStore`** (`stores/fii-dii.store.ts`) + **`BreadthStore`** (`stores/breadth.store.ts`): index-level (date-driven, no name/expiry); own date state (default = today IST), `loadCash/loadParticipant/loadLongShort` and `loadBreadth`.
- Decimals are JSON **strings** at runtime → hand-type as `string`, format via `core/decimal.ts`, never `parseFloat`.

### New frontend routes + nav (`app.routes.ts`, `shell/app-shell.ts`)

```
/oi/spurt        -> OiSpurtPage      (Options OI Spurt detail — per-strike buildup grid)
/oi/big-oi       -> OiBigOiPage      (top |ΔOI| movers + premium + trend)
/oi/futures      -> OiFuturesPage    (EXTEND: add spurt/movers/banks/buzz/eod sections/tabs)
/market/fii-dii  -> FiiDiiPage       (cash / participant-oi / long-short tables + net-flow chart)
/market/breadth  -> BreadthPage      (advances/declines + delivery% leaders + breadth bar chart)
```
Nav: add hardcoded `<a routerLink>` entries in `app-shell.ts` (no menu-config mechanism).

### Charts (G15) — `ay-echart` only (boundary-free; LWC is lint-locked to `/charts`)

- Options trend line (`/trending` series), premium curve.
- Futures buzz **heatmap** (`series: type:'heatmap'`, `visualMap`) — already registered in `echarts-bootstrap.ts`.
- Breadth bar (advances vs declines), FII/DII net-flow bar.
- Pass `EChartsCoreOption` via `[option]` computed; colours via `--ay-*` tokens; transparent bg (wrapper handles).

---

## Phase G0 — Futures OHLC capture foundation

**Why first:** unblocks `/buzz`, `/movers`, `/eod`. Forward-only data; safe because columns are nullable.

**Files:**
- Create: `deploy/flyway/marketdata/V015__futures_oi_snapshots_ohlc.sql`
- Modify: `services/market-data-service/.../kite/QuoteGateway.java` (Quote record — add `Ohlc ohlc`)
- Modify: the Kite live mapper (`KiteQuoteGateway`/equivalent) + the mock quote source (map/synthesize `ohlc`)
- Modify: `services/market-data-service/.../futures/FuturesOiSnapshotService.java` (write day_open/high/low/prev_close)
- Modify: `services/market-data-service/.../futures/analytics/FuturesSnapshotReader.java` (`FutPoint` + `last()` of new cols)
- Test: `.../futures/FuturesOhlcSnapshotIntegrationTest.java`

- [ ] **Step 1: Write V015 migration**
```sql
-- V015__futures_oi_snapshots_ohlc.sql
-- Forward-only day-range capture (Kite quote.ohlc). Nullable: pre-V015 rows stay valid.
ALTER TABLE futures_oi_snapshots
  ADD COLUMN day_open  NUMERIC(18,4),
  ADD COLUMN day_high  NUMERIC(18,4),
  ADD COLUMN day_low   NUMERIC(18,4),
  ADD COLUMN prev_close NUMERIC(18,4);
```
- [ ] **Step 2: Run flyway-init in the dev stack** — `./ay reset-db` is too heavy; instead verify the migration validates: `./ay up -d` then check `docker logs ay-flyway-init` shows V015 applied. Expected: `Successfully applied 1 migration` (V015).
- [ ] **Step 3: Extend `QuoteGateway.Quote`** — add `public record Ohlc(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}` and an `Ohlc ohlc` component (nullable). Keep existing components. Update the Kite mapper to read Kite's `ohlc` block; update the mock source to synthesize (`open=ltp`, `high=ltp*1.01`, `low=ltp*0.99`, `close=prevLtp`). Compile: `./mvnw.cmd -pl services/market-data-service -am -o compile -Dsurefire.failIfNoSpecifiedTests=false` (PowerShell, `$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'`).
- [ ] **Step 4: Write the failing IT** `FuturesOhlcSnapshotIntegrationTest` — drives one capture pass against the mock source, asserts the persisted row has non-null `day_high`/`day_low`. Run: expect FAIL (writer not yet writing the cols).
- [ ] **Step 5: Extend `FuturesOiSnapshotService`** — bind `q.ohlc().open()/high()/low()/close()` into the INSERT (null-safe). Re-run IT → PASS.
- [ ] **Step 6: Extend `FutPoint` + reader** — add `dayOpen/dayHigh/dayLow/prevClose` with `last(day_high, ts)` etc. in `series()`/`latest()`. Add a reader IT asserting `latest()` surfaces the new fields.
- [ ] **Step 7: Commit** — `git commit -m "feat(market-data): capture futures day OHLC (V015) for buzz/movers/eod"`

## Phase G1 — History-mode wiring (cross-cutting unblocker)

**Files:**
- Modify: `OptionsSnapshotReader.java`, `FuturesSnapshotReader.java` (add `LocalDate date` overloads)
- Modify: `OptionsAnalyticsController.java`, `FuturesAnalyticsController.java` (`requireDate(q)`, pass `q.date()`)
- Test: extend `OptionsSnapshotReaderIntegrationTest`, controller ITs

- [ ] **Step 1: Failing reader IT** — seed two IST days; assert `latest(u,exp,iv, day1)` returns ONLY day1's newest bucket (not day2's). Run → FAIL (date ignored).
- [ ] **Step 2: Add date-scoped SQL** — `date != null` adds `AND ts >= ? AND ts < ?` (IST day bounds via `Ist.OFFSET`), then the existing max(ts)-anchor runs *within* the day. No-arg overloads delegate `null`. Re-run → PASS.
- [ ] **Step 3: Mirror in `FuturesSnapshotReader`** + IT.
- [ ] **Step 4: Wire controllers** — add `private LocalDate requireDate(OiQuery q){ if(!q.live() && q.date()==null) throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "date is required in history mode"); return q.date(); }`; pass `q.date()` to every reader call.
- [ ] **Step 5: Controller IT** — history mode + date returns that day's data; history mode w/o date → 400.
- [ ] **Step 6: Commit** — `git commit -m "feat(market-data): honor date in history mode across options/futures readers"`

## Phase G2 — options `/big-oi`

**Files:** Create `OiBigOiService.java` + `OiBigOiServiceTest.java`; Modify `OptionsAnalyticsController.java`; extend `OptionsAnalyticsControllerIntegrationTest.java`.

- [ ] **Step 1: Failing unit test** — `bigOiSortsByAbsOiChangeDescAndCapsTopN`: feed 5 StrikePoints with mixed ±oiChange; assert items ordered by `|oiChange|` desc, length ≤ topN.
- [ ] **Step 2: Implement** `bigOi(List<StrikePoint> latest, int topN)` — map → `BigOiRow`, sort `Comparator.comparingLong(r -> Math.abs(r.oiChange())).reversed()`, limit topN; `asOf = latest.get(0).bucket()`. → PASS.
- [ ] **Step 3: Controller endpoint** `GET /big-oi` — `reader.latest(name,exp,iv,q.date())`; empty → 422 DATA_GAP; `topN` from `@Value("${artha.options.big-oi-top-n:10}")`. Return `bigOi(...)`.
- [ ] **Step 4: Controller IT** — seed a bucket; assert `$.items[0].optionType`, ordering, `$.items.length()`.
- [ ] **Step 5: Commit** — `feat(market-data): options /big-oi top |ΔOI| movers`

## Phase G3 — options `/premium`

**Files:** Create `OiPremiumService.java` + test; Modify controller + IT.

- [ ] **Step 1: Failing unit test** — `premiumFoldsStraddlePerStrikeAndPicksAtm`: CE+PE legs across 3 strikes, spot between; assert per-strike `straddle = ce.ltp + pe.ltp` (exact `BigDecimal.add`), and `atmStrike` = nearest to spot, `atmStraddle` matches.
- [ ] **Step 2: Implement** — fold by strike (reuse the spot-carry pattern from `OiSpurtService`), `straddle = ce.add(pe)` (skip strikes missing a leg), ATM = `min |strike - spot|`. → PASS.
- [ ] **Step 3: Controller `/premium`** — `reader.latest(...)`; empty → 422.
- [ ] **Step 4: IT** — assert `$.atmStraddle` (decimal STRING) + `$.items[0].straddle`.
- [ ] **Step 5: Commit** — `feat(market-data): options /premium straddle premium chain`

## Phase G4 — options `/trending`

**Files:** Create `OiTrendingService.java` + test; Modify controller + IT.

- [ ] **Step 1: Failing unit test** — `trendingMarksUpDownFlatVsPriorBucketTotalOi`: 3 buckets with rising then flat totalOi; assert `Trend.UP` then `Trend.FLAT`; first bucket = `FLAT` (no prior).
- [ ] **Step 2: Implement** — group `series()` rows by bucket → sum oi (and ce/pe split by optionType); per bucket compare totalOi to prior (`>` UP, `<` DOWN, `==` FLAT).
- [ ] **Step 3: Controller `/trending`** — needs a window: `reader.series(name,exp,iv, from, to)` where `to = now` (or end-of-date in history), `from = to - interval*N` (N from `${artha.options.trend-buckets:20}`); empty → 422.
- [ ] **Step 4: IT** — seed multiple buckets; assert `$.items.length()` and a `trend` value.
- [ ] **Step 5: Commit** — `feat(market-data): options /trending OI-trend series`

## Phase G5 — futures `/spurt`

**Files:** Modify `FuturesSnapshotReader.java` (`latestPair`); Create `FuturesSpurtService.java` + test; Modify `FuturesAnalyticsController.java` + IT.

- [ ] **Step 1: Failing reader IT** — `latestPairReturnsTwoMostRecentBucketsPerContract`: seed 3 buckets one contract; assert two returned (gap-robust DISTINCT bucket DESC LIMIT 2, mirroring options).
- [ ] **Step 2: Implement `latestPair`** (date-aware overload per G1). → PASS.
- [ ] **Step 3: Failing service test** — `spurtsDiffsLatestTwoBucketsPerContract`: feed FutPoint pair, assert per-contract `oiChange`, `spurtPct = oiDelta/priorOi*100`, `interpretation = OiInterpretation.classify(ltpDelta, oiDelta)`.
- [ ] **Step 4: Implement `spurts(pair)`** — mirror `OiSpurtService.spurts`: newest bucket + prior, fold by tradingsymbol, skip contracts only in newest, `OiInterpretation.classify`. asOf = newest bucket.
- [ ] **Step 5: Controller `/spurt`** — `reader.latestPair(name,iv,q.date())`; empty → 422.
- [ ] **Step 6: IT** — assert `$.items[0].interpretation`, `$.items[0].spurtPct` (string).
- [ ] **Step 7: Commit** — `feat(market-data): futures /spurt interval buildup`

## Phase G6 — futures `/movers` + `/banks`

**Files:** Create `FuturesMoversService.java` + test; Modify controller + IT.

- [ ] **Step 1: Failing test `movers`** — feed FutPoint pair (now with `prevClose`): assert `pricePct = (ltp - prevClose)/prevClose*100` (fallback to ltp-delta vs prior bucket when prevClose null), `oiPct = oiDelta/priorOi*100`, gainers sorted pricePct desc, losers asc.
- [ ] **Step 2: Implement `movers(pair)`** — per-contract compute, split into gainers (pricePct≥0) / losers (<0), sort, `interpretation = classify`. → PASS.
- [ ] **Step 3: Failing test `banks`** — feed NIFTY BANK front/next/far FutPoints + a spot proxy; assert `basis = ltp - spotProxy`, ordered by expiry asc, `interpretation` set.
- [ ] **Step 4: Implement `banks(pair, spotProxy)`** — filter to the configured bank-index underlying, one row per contract (term structure), `basis`, classify. spotProxy = nearest-month ltp when no index spot available.
- [ ] **Step 5: Controllers `/movers`, `/banks`** — `reader.latestPair(...)`; empty → 422. `/banks` note in javadoc: NIFTY BANK index term structure; bank-*stock* grid deferred (no bank-stock futures captured).
- [ ] **Step 6: ITs** for both.
- [ ] **Step 7: Commit** — `feat(market-data): futures /movers gainers-losers + /banks term structure`

## Phase G7 — futures `/buzz`

**Files:** Create `FuturesBuzzService.java` + test; Modify controller + IT.

- [ ] **Step 1: Failing test** — `buzzBuildsTimeByContractMatrixOf4State`: feed a `series()` (multiple buckets × 2 contracts); assert `contracts` list, `buckets` list, `cells[b][c] = classify(ltpΔ, oiΔ vs prior bucket of same contract)`, first bucket per contract → FLAT/null handling.
- [ ] **Step 2: Implement `buzz(series)`** — pivot series into a `bucket × contract` grid; per cell diff against the prior bucket of the same contract; emit `OiInterpretation`. Empty series → empty matrix (200, not 422).
- [ ] **Step 3: Controller `/buzz`** — `reader.series(name,iv, from, to)` window (N buckets like /trending); returns `BuzzMatrix` (empty cells when no data).
- [ ] **Step 4: IT** — assert matrix dimensions + a cell value.
- [ ] **Step 5: Commit** — `feat(market-data): futures /buzz 4-state OI heatmap matrix`

## Phase G8 — futures `/eod`

**Files:** Modify `FuturesSnapshotReader.java` (`eod`); Modify controller + IT.

- [ ] **Step 1: Failing reader IT** — seed 2 days × 1 contract with OHLC; assert `eod(name, day1, day2)` yields 2 `EodRow`s, each `close = last ltp of day`, `high/low` = max/min of `day_high`/`day_low`, `oiClose = last oi`.
- [ ] **Step 2: Implement `eod(name, from, to)`** — group by `(tradingsymbol, date_trunc('day', ts AT TIME ZONE 'Asia/Kolkata'))`; `last(ltp,ts) close`, `max(day_high) high`, `min(day_low) low`, `last(day_open ... first by ts) open`, `last(oi,ts) oiClose`, `sum`/`last` oi_change, `last(volume,ts)`.
- [ ] **Step 3: Controller `/eod`** — params `name*, from* (date), to (date, default=from)`; `{items:[...]}`; empty → 200 empty.
- [ ] **Step 4: IT** — assert `$.items[0].high`/`.close`.
- [ ] **Step 5: Commit** — `feat(market-data): futures /eod daily OHLC+OI rollup`

## Phase G9 — FII/DII readers + endpoints

**Files:** Create `nse/analytics/NseEodReaders.java` (or split per-table) + test; Create `nse/analytics/FiiDiiController.java` + IT.

- [ ] **Step 1: Confirm V012/V013 columns** — Read the two migrations; map record fields to ACTUAL column names (do not guess — the spine `ParticipantOiRow` is a placeholder shape).
- [ ] **Step 2: Failing reader IT** — seed `nse_eod_fii_dii` + `nse_eod_participant_oi` rows; assert `fiiDii(from,to)` and `participantOi(from,to)` return them ordered by date.
- [ ] **Step 3: Implement readers** — JdbcTemplate `SELECT ... WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date, category`. → PASS.
- [ ] **Step 4: Controller** — `/fii-dii/cash` (→ fiiDii rows), `/participant-oi` (→ participantOi), `/long-short` (→ derive FII fut long/short ratio from participant_oi where client_type='FII'). Params `from*` (date), `to` (default today). `{items:[...]}`.
- [ ] **Step 5: Controller IT** — assert `$.items[0].netValue` etc. (decimal strings).
- [ ] **Step 6: Commit** — `feat(market-data): FII/DII cash + participant-OI + long-short read endpoints`

## Phase G10 — breadth

**Files:** Create `nse/analytics/BreadthService.java` + test; Create `nse/analytics/BreadthController.java` + IT.

- [ ] **Step 1: Failing test** — `breadthCountsAdvancesDeclinesAndAvgDelivery`: seed bhavcopy rows (EQ series) with up/down/flat pct change; assert `advances`/`declines`/`unchanged`/`total`, `avgDeliveryPct`, `topDelivery` sorted desc.
- [ ] **Step 2: Implement** — read `nse_eod_bhavcopy WHERE trade_date=? AND series='EQ'`; advance = `close > prev_close` (or `pctChange>0` if column exists — confirm V014), `avgDeliveryPct = avg(delivery_pct)`, topDelivery = top-N by delivery_pct.
- [ ] **Step 3: Controller `/breadth`** — `date*`; empty (no rows that day) → 422 DATA_GAP.
- [ ] **Step 4: IT** — assert `$.summary.advances`, `$.topDelivery[0].deliveryPct`.
- [ ] **Step 5: Commit** — `feat(market-data): market /breadth advance-decline + delivery leaders`

## Phase G11 — Contract recapture + TS regen

- [ ] **Step 1:** `./mvnw.cmd -pl services/market-data-service -am verify -Dcontracts.capture=true` (recaptures `contracts/market-data-service.openapi.json`). New `@GetMapping` paths drift the spec; `Map<String,Object>` returns do not.
- [ ] **Step 2:** `cd frontend-ui && npm run gen:api` → regenerates `contracts/gen/market-data-service.d.ts`.
- [ ] **Step 3:** Isolated `tsc --strict` on the gen file (the `target/tscheck/tsconfig.json` `{strict,noEmit,lib:["es2022"],types:[]}` pattern from the /spurt slice) → exit 0.
- [ ] **Step 4: Commit** — `chore(contracts): recapture market-data OpenAPI for Stage-G endpoints`

## Phase G12 — Backend full verify

- [ ] **Step 1:** `./mvnw.cmd -pl services/market-data-service -am verify` (PowerShell, Windows-ROOT trust). Expected: all ITs + JaCoCo ≥60% + `ModularityTest` green. Capture to a var (never pipe mvnw to `Select-Object -First`).
- [ ] **Step 2:** If JaCoCo dips <60% on a new analytics class, add the missing unit test (services are pure → easy to cover).
- [ ] **Step 3: Commit** (only if fixes needed) — `test(market-data): cover Stage-G analytics services`

## Phase G13 — Frontend store + wire types

**Files:** Modify `stores/oi-analytics.store.ts`; Create `stores/fii-dii.store.ts`, `stores/breadth.store.ts`; extend `oi-analytics.store.spec.ts`; new store specs.

- [ ] **Step 1: Failing store specs** — for each new loader: `loadBigOi maps {items}`, `loadPremium maps atmStraddle`, `loadTrending`, `loadFutSpurt`, `loadMovers`, `loadBanks`, `loadBuzz`, `loadEod`; `FiiDiiStore.loadCash`; `BreadthStore.loadBreadth`. Assert decimal strings preserved, 422→null, stale-drop.
- [ ] **Step 2: Implement** loaders mirroring `loadSpurt()` (generation token, SILENT context, `unsatisfiable`/date guards). FII/DII + breadth stores are date-driven (no name/expiry).
- [ ] **Step 3:** `cd frontend-ui && npm run test:ci` → new specs green.
- [ ] **Step 4: Commit** — `feat(ui): OiAnalyticsStore Stage-G loaders + FiiDii/Breadth stores`

## Phase G14 — Frontend pages + routing + nav

**Files:** Create `pages/oi/oi-spurt-page.ts`, `pages/oi/oi-big-oi-page.ts`, `pages/fii-dii/fii-dii-page.ts`, `pages/breadth/breadth-page.ts`; extend `pages/oi/oi-futures-page.ts` (spurt/movers/banks/buzz/eod sections); Modify `app.routes.ts`, `shell/app-shell.ts`; page specs (Host pattern).

- [ ] **Step 1: Failing page specs** (Host component, drain bg HTTP, flush endpoint, assert rendered text) per new page.
- [ ] **Step 2: Implement pages** — reuse `OiControlBar`, `p-table` scrollable (NOT virtualScroll), `ay-data-bar`, `ay-oi-int-badge`; effect-driven loader calls; decimal `formatDecimal`. FII/DII + breadth pages use a date picker (their stores), no symbol control bar.
- [ ] **Step 3: Routes + nav** — add lazy routes + `<a routerLink>` entries.
- [ ] **Step 4:** `npm run test:ci` green.
- [ ] **Step 5: Commit** — `feat(ui): Stage-G pages (spurt, big-oi, fii-dii, breadth, futures sections) + nav`

## Phase G15 — Charts (`ay-echart`)

**Files:** Modify the new pages to add chart panels; reuse `shared/echarts-chart.ts`.

- [ ] **Step 1:** Options `/trending` line chart (totalOi vs bucket) on big-oi/trending page; premium curve.
- [ ] **Step 2:** Futures `/buzz` **heatmap** (`type:'heatmap'` + `visualMap`, 4-state colour scale via `--ay-*`).
- [ ] **Step 3:** Breadth bar (advances vs declines); FII/DII net-flow bar.
- [ ] **Step 4:** Spec the chart-bearing pages still render (axe-clean: charts have `aria` table fallback per `ay-echart`).
- [ ] **Step 5: Commit** — `feat(ui): Stage-G ECharts panels (trend line, buzz heatmap, breadth/fii-dii bars)`

## Phase G16 — Frontend verify trio

- [ ] **Step 1:** `cd frontend-ui && npm run lint` (eslint + stylelint — watch the E-9 LWC boundary; we use `ay-echart` only, so no boundary risk).
- [ ] **Step 2:** `npm run test:ci`.
- [ ] **Step 3:** `npm run build` (enforces tsconfig strict + strictTemplates).
- [ ] **Step 4: Commit** (only if fixes) — `fix(ui): Stage-G lint/build corrections`

## Phase G17 — Adversarial review + PR

- [ ] **Step 1:** Dispatch `timescale-domain-reviewer` (date-window/IST/bucket/decimal/anti-double-count over G0–G10) + `ui-a11y-reviewer` (G13–G15 colour-only/contrast/sr/heatmap-legend). Fix confirmed defects.
- [ ] **Step 2:** `gh pr create --base main --head feat/stage-g-oipulse-parity` with a body enumerating all 12 items + the 2 scope decisions + the `/banks` data caveat.
- [ ] **Step 3:** Update memory `scalping-goal-and-data-architecture.md` with the Stage-G record.

---

## Self-review

**Spec coverage (13 original items):**
1. `/big-oi` → G2 ✓ · 2. `/premium` → G3 ✓ · 3. `/trending` → G4 ✓ · 4. option-strategies → **DEFERRED** (owner decision) ✓ · 5. futures `/spurt` → G5 ✓ · 6. `/buzz`·`/movers`·`/banks` → G6+G7 ✓ (banks scoped + flagged) · 7. futures `/eod` → G8 (+G0 OHLC) ✓ · 8. `/fii-dii/*` → G9 ✓ · 9. breadth → G10 ✓ · 10. Spurt page → G14 ✓ · 11. FE pages → G14 ✓ · 12. charts → G15 ✓ · 13. history-mode → G1 ✓. Plus G11 contracts, G12/G16 verify, G17 PR.

**Type consistency:** `OiInterpretation.classify(priceDelta, oiDelta)` reused in G5/G6/G7. `FutPoint` extended in G0 used by G5–G8. `latest`/`latestPair`/`series` date-overloads (G1) used by all reader callers. Decimal-string wire convention uniform.

**Known data caveats (documented, not gaps):** `/banks` = index-fut term structure (bank-stock futures not captured); `/buzz`/`/movers`/`/eod` OHLC is forward-only (no backfill); FII/DII + breadth depend on the daily NSE EOD ingest having run.
