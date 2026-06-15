# NSE EOD Bhavcopy + Delivery Ingestion Implementation Plan

> **For agentic workers:** the last Phase-1 EOD source, a fast-follow to participant-OI (#23).
> Reuses `NseHttpClient.getAbsolute()` (archive host) + the walk-back-to-latest pattern.

**Goal:** Capture NSE's daily security-wise bhavcopy + delivery (`sec_bhavdata_full`) — per-symbol
EOD OHLCV + delivery qty/% — for the positional/breadth analytics track.

**Architecture:** A `BhavcopyFetcher` port with a `@Profile("live")` impl that walks back from today
(IST) to the most recent published `sec_bhavdata_full_DDMMYYYY.csv` on `nsearchives.nseindia.com`,
parses the wide CSV (trade date per-row from `DATE1`), and upserts by `(trade_date, symbol, series)`
into a Timescale hypertable. Wired into the existing `NseEodScheduler` with its own try/catch.

**Tech Stack:** Spring Boot, JdbcTemplate batch upsert, Flyway (marketdata lineage, hypertable +
compression), RestClient.

---

## Data source (spike-verified 2026-06-15)

`GET https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_12062026.csv` → 200
(browser UA + referer, no cookie). 3247 data rows. Shape:

```
SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE, LOW_PRICE, LAST_PRICE, CLOSE_PRICE, AVG_PRICE, TTL_TRD_QNTY, TURNOVER_LACS, NO_OF_TRADES, DELIV_QTY, DELIV_PER
360ONE, EQ, 12-Jun-2026, 1064.10, 1079.70, 1099.00, 1058.00, 1094.00, 1096.80, 1082.08, 696601, 7537.75, 33351, 336020, 48.24
AAKASH, BE, 12-Jun-2026, 10.00, ... , 444, -, -
```

- Header line 0; lines 1.. = one row per symbol+series. Every cell carries a **leading space** → trim.
- `DATE1` = `dd-MMM-yyyy` (per-row, authoritative).
- `DELIV_QTY`/`DELIV_PER` are `-` for non-deliverable rows (e.g. BE series) → null. (`num`/`lng`
  helpers treat `-`/blank as null for every numeric cell.)
- Note `LAST_PRICE` (c[7]) precedes `CLOSE_PRICE` (c[8]) — do not conflate.

## Tasks (implemented)

- **Table** `deploy/flyway/marketdata/V014__nse_eod_bhavcopy.sql` — hypertable on `trade_date`
  (monthly chunks), `compress_segmentby = series`, compress after 7d, A2 no-retention. PK
  `(trade_date, symbol, series)`.
- **Port** `BhavcopyFetcher` + `BhavcopyRow` (date, symbol, series, 7 prices, 3 counts, delivery).
- **Live impl** `LiveBhavcopyFetcher` (`@Profile("live")`) — walk-back fetch (`/products/content/`)
  + CSV parse, per-row date, `-`→null.
- **Mock impl** `MockBhavcopyFetcher` (`@Profile("!live")`) — empty (bean presence only).
- **Repo** `NseEodBhavcopyRepository` — idempotent batch upsert (~3.2k rows/run).
- **Wiring** `NseEodScheduler.pullBhavcopy()` — alongside FII/DII + participant-OI, isolated try/catch.

## Test

`LiveBhavcopyFetcherTest` (TDD, RED→GREEN) parses the spike-captured CSV via a `StubClient` →
asserts an EQ row's OHLCV/delivery and a BE row's null delivery columns.

## Verify

Build (`-am package`), redeploy market-data to live, confirm `NSE bhavcopy EOD upserted N rows` in
logs and rows in `nse_eod_bhavcopy`.
