# NSE EOD Participant-wise OI Ingestion Implementation Plan

> **For agentic workers:** fast-follow to the FII/DII ingest (PR #22). Reuses the proven
> `NseHttpClient` (browser-UA, fail-fast timeouts) + adds a CSV parser, a table, and a
> scheduler hook.

**Goal:** Capture NSE's daily participant-wise OI (Client/DII/FII/Pro/TOTAL × futures+options
long/short) into TimescaleDB for the oipulse-parity sentiment analytics.

**Architecture:** A `ParticipantOiFetcher` port with a `@Profile("live")` impl that walks back
from today (IST) to the most recent published `fao_participant_oi_DDMMYYYY.csv` on the
`nsearchives.nseindia.com` archive host, parses the wide CSV, and upserts by
`(trade_date, client_type)`. Wired into the existing `NseEodScheduler` (startup + 19:00 IST cron)
with its own try/catch so an NSE outage never blocks FII/DII.

**Tech Stack:** Spring Boot, JdbcTemplate batch upsert, Flyway (marketdata lineage), RestClient.

---

## Data source (spike-verified 2026-06-15)

`GET https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_12062026.csv` → 200 with
browser UA + `Referer: https://www.nseindia.com/` (no cookie priming). Shape:

```
""Participant wise Open Interest (no. of contracts) ... as on Jun 12, 2026"",,,, …
Client Type,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short, … ,Total Long Contracts,Total Short Contracts
Client,258005,77605, … ,12013543,8891488
DII,75317,13508, … ,530934,5116834
FII,39971,283594, … ,6506758,5668207
Pro,56144,54730, … ,5628357,5003062
TOTAL,429437,429437, … ,24679592,24679592
```

- Line 0 = title carrying the authoritative trade date (`MMM d, yyyy`).
- Line 1 = header (skipped). Lines 2.. = `clientType` + 14 contract counts (source order).
- The file is URL-date-stamped, so "latest" = walk back ≤5 days; weekends/holidays 404 and skip.

## Tasks (implemented)

- **Table** `deploy/flyway/marketdata/V013__nse_eod_participant_oi.sql` — plain table (~5 rows/day,
  A2 keep-history), PK `(trade_date, client_type)`, 14 `BIGINT` columns mirroring the CSV.
- **Port** `ParticipantOiFetcher` + `ParticipantOiRow` (date, clientType, 14 longs).
- **Live impl** `LiveParticipantOiFetcher` (`@Profile("live")`) — walk-back fetch + CSV parse;
  trade date from the title line via regex `as on (MMM d, yyyy)`.
- **Mock impl** `MockParticipantOiFetcher` (`@Profile("!live")`) — empty (bean presence only).
- **`NseHttpClient.getAbsolute(url)`** — reuses the anti-bot headers for the archive host.
- **Repo** `NseEodParticipantOiRepository` — idempotent batch upsert.
- **Wiring** `NseEodScheduler` — `pullParticipantOi()` alongside `pullFiiDii()`, isolated try/catch.
- **Config** `application.yml` live block — `artha.nse.archives-url`.

## Test

`LiveParticipantOiFetcherTest` (TDD, RED→GREEN) parses the spike-captured CSV via a `StubClient`
returning canned content → asserts 5 rows, FII contract counts, and the title-line trade date.

## Verify

Build (`-am package`), redeploy market-data to live, confirm
`NSE participant-OI EOD upserted N rows` in logs and rows in `nse_eod_participant_oi`.
