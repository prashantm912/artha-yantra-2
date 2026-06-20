# Manual test — EOD bhavcopy universe candles + split/bonus adjustment

Verifies Phase A (NSE) + B (BSE) + C (adjustment). Run against a **live** stack (mock returns no
bhavcopy — the Mock fetchers are empty by design). Market can be closed; the loader is EOD/historical.

Prereqs: live stack up (`./ay.ps1 up` with the live profile), instruments synced, and the new
migrations applied (V020/V021/V022 — `flyway-init` runs them on boot). Drive the gateway from
PowerShell with `Invoke-WebRequest -UseBasicParsing` (see CLAUDE.md); log in + seed the XSRF cookie
first.

## 1. Trigger the backfill on demand

```powershell
# POST returns 202 + jobId; a second POST while running returns 409 CONFLICT_BACKFILL_RUNNING
Invoke-WebRequest -UseBasicParsing -Method POST `
  -Uri https://127.0.0.1:8443/api/v1/market/eod-backfill `
  -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $s
```

Poll status until `state` leaves `RUNNING`:

```powershell
(Invoke-WebRequest -UseBasicParsing -Uri https://127.0.0.1:8443/api/v1/market/eod-backfill/status `
  -WebSession $s).Content | ConvertFrom-Json
```

**Expect:** `state=OK`; `nse.days/bhavRows/candleRows` and `bse.*` non-zero on a trading-day window;
`ratiosDetected` ≥ 0. (On startup the same run fires automatically via `ApplicationReadyEvent`.)

## 2. Raw bhavcopy landed (in-container SQL; DB = `artha`)

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c `
  "SELECT (SELECT count(*) FROM marketdata.nse_eod_bhavcopy) nse, (SELECT count(*) FROM marketdata.bse_eod_bhavcopy) bse, (SELECT max(trade_date) FROM marketdata.nse_eod_bhavcopy) nse_wm;"
```

**Expect:** ~3.2k NSE + ~4.8k BSE rows per trading day; `nse_wm` = last trading day.

## 3. Projected into candles as 1d BHAVCOPY

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c `
  "SELECT exchange, source, count(*) FROM marketdata.candles WHERE \"interval\"='1d' AND source='BHAVCOPY' GROUP BY 1,2;"
```

**Expect:** BHAVCOPY 1d rows for NSE and BSE. Pick a penny/illiquid stock and chart it:

```powershell
# raw bars present without any Kite call
Invoke-WebRequest -UseBasicParsing -WebSession $s -Uri `
  "https://127.0.0.1:8443/api/v1/market/candles?exchange=NSE&tradingsymbol=<PENNY>&interval=1d&from=2026-01-01T00:00:00%2B05:30&to=2026-06-30T00:00:00%2B05:30"
```

## 4. DO-NOTHING never clobbers Kite

Pick a symbol you've charted live (so it has Kite 1d bars). After a backfill pass, its Kite bars must
remain `source=KITE` (bhavcopy fills only the dates Kite doesn't own):

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c `
  "SELECT bucket::date, source, close FROM marketdata.candles WHERE exchange='NSE' AND tradingsymbol='<KITE_SYMBOL>' AND \"interval\"='1d' ORDER BY bucket DESC LIMIT 10;"
```

**Expect:** existing rows keep `source=KITE`; only previously-empty dates show `BHAVCOPY`.

## 5. Split/bonus ratios + read-time adjustment

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c `
  "SELECT exchange, tradingsymbol, ex_date, ratio, kind, subject FROM marketdata.eod_corporate_actions ORDER BY ex_date DESC LIMIT 20;"
```

**Expect:** recent splits/bonuses with a ratio < 1 (e.g. a 1:1 bonus → 0.5, a Rs10→Re1 split → 0.1).
NSE listings come from the NSE corporate-actions API; BSE listings come from the BSE feed directly
(BSE-only scrips) and via ISIN cross-map from the NSE feed (dual-listed). Both exchanges should show
ratio rows; a BSE-only scrip with a split should have a `BSE` row with `source=NSE_CA_API`.

Adjustment is applied on read for `interval=1d&adjust=back` (the default). For a stock that had a
split in the window, compare `adjust=back` (default) vs `adjust=none`: pre-ex-date BHAVCOPY bars are
scaled by the cumulative ratio under `back`, raw under `none`. There should be **no price cliff** at
the ex-date under `back`.

```powershell
$base = "https://127.0.0.1:8443/api/v1/market/candles?exchange=NSE&tradingsymbol=<SPLIT_STOCK>&interval=1d&from=...&to=..."
(Invoke-WebRequest -UseBasicParsing -WebSession $s -Uri "$base&adjust=back").Content | ConvertFrom-Json
(Invoke-WebRequest -UseBasicParsing -WebSession $s -Uri "$base&adjust=none").Content | ConvertFrom-Json
```

## 6. Catch-up self-heals a gap

Stop the service for ≥1 trading day (or delete the latest few `nse_eod_bhavcopy` dates), restart,
and confirm step 2's `nse_wm` advances to today and step 3's counts grow — the catch-up downloads
**every** missing day from the watermark, bounded by `artha.bhavcopy.catchup-max-days` (90).

## Notes

- BSE returns the SPA homepage (HTTP 200, HTML) on non-trading days; the loader content-sniffs and
  skips — a `state=OK` with `bse.days=0` on a holiday/weekend window is correct, not a failure.
- The NSE corporate-actions API is anti-bot; a failed CA fetch is logged and non-fatal
  (`ratiosDetected=0`), candles still serve raw.
