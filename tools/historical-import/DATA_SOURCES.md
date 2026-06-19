# Historical Data Sources — Field Survey

Surveyed 2026-06-18. Two Google Drive folders mapped locally.

---

## Source A — Historical Options (NIFTY + BANKNIFTY, 2014–2024)

**Drive path:** `G:\.shortcut-targets-by-id\1kYQ0DHIDSk2_eYxaZ5Em-aHeP0h_lZef\options\`

### Folder tree

```
options/
└── 2014-2024/
    ├── nifty_spot.csv          ← 63 MB, minute NIFTY spot, Apr 2015 – May 2026
    ├── banknifty_spot.csv      ← 64 MB, minute BANKNIFTY spot, Apr 2015 – May 2026
    ├── banknifty (1).zip       ← duplicate/compressed, SKIP
    ├── banknifty.zip           ← duplicate/compressed, SKIP
    ├── nifty.zip               ← duplicate/compressed, SKIP
    ├── nifty/
    │   ├── 2014/
    │   │   ├── 2014-01-30/
    │   │   │   ├── NIFTY_6200_CE_30_JAN_14.csv
    │   │   │   ├── NIFTY_6200_PE_30_JAN_14.csv
    │   │   │   └── ...  (all strikes for that expiry)
    │   │   ├── 2014-02-26/
    │   │   └── ... (12 monthly expiries/year)
    │   ├── 2015/ ... 2024/     ← 11 years total
    └── banknifty/
        ├── 2018/               ← starts 2018 (no earlier data)
        ├── 2019/ ... 2024/     ← 7 years total
```

### File counts

| Folder          | Files   |
|-----------------|---------|
| nifty/          | 46,865  |
| banknifty/      | 51,186  |
| **Total**       | **98,051** |

### Filename convention

```
{SYMBOL}_{strike}_{CE|PE}_{DD_MMM_YY}.csv
Examples:
  NIFTY_6200_CE_30_JAN_14.csv
  BANKNIFTY_50400_PE_27_FEB_25.csv
```

Folder path encodes expiry: `{symbol}/{YYYY}/{YYYY-MM-DD}/`

### CSV schema — options files

**Detect schema by reading the header row and first data row. Never infer from path, year, or symbol.**

Two schema variants exist — either can appear in any file:

**Variant 1 — no OI:**
```csv
date,time,open,high,low,close,volume
2019-07-09,09:17,123.45,134.25,123.2,134.25,450
```

**Variant 2 — with OI:**
```csv
date,time,open,high,low,close,volume,oi
28-12-2020,09:15,3.0,3.0,2.25,2.3,1450,19975.0
```

- **Separator:** comma
- **Timestamp:** always split `date` + `time` columns (IST implied, no TZ offset)
- **time format:** `HH:MM`
- **Interval:** 1-minute bars
- **OI:** float (e.g. `185250.0`); store NULL when column absent
- **Two date formats — detect per file from first data row:**
  - `YYYY-MM-DD` → `strptime('%Y-%m-%d')`
  - `DD-MM-YYYY` → `strptime('%d-%m-%Y')`

### CSV schema — spot files

```csv
Date,Open,High,Low,Close,Volume
2015-04-01T09:15:00+0530,8483.7,8483.7,8466.7,8468.8,0
```

- ISO 8601 timestamps with `+0530` offset
- 1-minute bars
- Volume always 0 (spot index, no real volume)
- NIFTY: Apr 2015 – May 2026
- BANKNIFTY: Apr 2015 – May 2026

---

## Source B — Multi-Asset Dataset (Equity, Fundamentals, IV, Options)

**Drive path:** `G:\.shortcut-targets-by-id\1ZlIf7B87n9b-pwXPp-rrJ7IJk1Uh9CyU\data\`

### Top-level structure

```
data/
├── equity/
│   ├── day/       ← 1,848 CSV files (daily OHLCV, ~2015 onwards)
│   └── minute/    ← 1,919 CSV files (minute OHLCV, ~2019 onwards)
├── fundamentals/  ← 2,540 company symbol folders
├── implied volatility/  ← 219 CSV files (daily IV per symbol)
├── options/
│   ├── stocks/    ← 206 stock symbols
│   └── index/     ← 5 index symbols
└── compressed/    ← SKIP (compressed duplicates)
```

---

### B.1 — equity/

#### Filename pattern

```
{numeric_id}_{SYMBOL}.csv
Examples:
  0031_BAJAJFINSV.csv   (149 KB  — daily)
  0975_VIDHIING.csv     (37 MB   — minute)
```

Numeric ID appears to be an internal serial; SYMBOL is the NSE trading symbol.

#### CSV schema (both day and minute — identical columns)

```csv
Date,Open,High,Low,Close,Volume
2015-04-01T00:00:00+0530,141.2,144.75,139.6,142.75,448110   ← daily
2019-01-01T09:15:00+0530,67.8,67.8,67.8,67.8,0              ← minute
```

- ISO 8601 with `+0530`
- Daily: `T00:00:00+0530` (midnight)
- Minute: session time (`09:15` to `15:29`)
- Volume present (real equity volume)
- **No OI** (equity, not derivatives)

#### Scale

| Folder         | Files | Typical size       |
|----------------|-------|--------------------|
| equity/day     | 1,848 | 100–160 KB         |
| equity/minute  | 1,919 | 8–54 MB (large!)   |

---

### B.2 — fundamentals/

One folder per company (NSE symbol). 2,540 companies total.

```
fundamentals/
├── ASTRAL/
│   ├── quarterly_results.csv
│   ├── balance_sheet.csv
│   ├── profit_and_loss.csv
│   ├── cash_flows.csv
│   ├── ratios.csv
│   ├── shareholding_pattern_yearly.csv
│   └── shareholding_pattern_quarterly.csv
├── TATAMOTORS/
│   └── ...
└── ...  (2,540 companies × 7 files = ~17,508 files)
```

#### Data format — WIDE (transposed)

All fundamentals files use the same layout:
- **Row 0 (header):** empty label, then date/period columns (`Mar 2023`, `Jun 2023`, …)
- **Rows 1–N:** metric name in column 0, values in subsequent columns

Example (`quarterly_results.csv`):
```csv
,Mar 2023,Jun 2023,Sep 2023,...
Sales +,"1,361","1,149","1,223",...
Expenses +,"1,065",963,"1,016",...
Operating Profit,296,186,207,...
```

⚠️ **Encoding artefact:** `Â +` appears for metric names that have a `+` footnote marker (Screener.in export). Strip `Â ` prefix before storing. Real name is e.g. `Sales +` → `Sales`.

#### Metrics per file

| File | Metrics (rows) | Period granularity |
|------|---------------|-------------------|
| `quarterly_results.csv` | Sales, Expenses, Operating Profit, OPM%, Other Income, Interest, Depreciation, PBT, Tax%, Net Profit, EPS, Raw PDF | Quarterly (MMM YYYY) |
| `profit_and_loss.csv` | Sales, Expenses, Operating Profit, OPM%, Other Income, Interest, Depreciation, PBT, Tax%, Net Profit, EPS, Dividend Payout% | Annual (Mar YYYY) |
| `balance_sheet.csv` | Equity Capital, Reserves, Borrowings, Other Liabilities, Total Liabilities, Fixed Assets, CWIP, Investments, Other Assets, Total Assets | Annual (Mar YYYY) |
| `cash_flows.csv` | Cash from Operating, Cash from Investing, Cash from Financing, Net Cash Flow, Free Cash Flow, CFO/OP | Annual (Mar YYYY) |
| `ratios.csv` | Debtor Days, Inventory Days, Days Payable, Cash Conversion Cycle, Working Capital Days, ROCE% | Annual (Mar YYYY) |
| `shareholding_pattern_yearly.csv` | Promoters, FIIs, DIIs, Government, Public, No. of Shareholders | Annual (Mar YYYY) |
| `shareholding_pattern_quarterly.csv` | Same metrics as yearly | Quarterly (MMM YYYY) |

Values use comma-thousands separators (`"1,361"`) and percent suffixes (`59.34%`).

---

### B.3 — implied volatility/

219 CSV files, one per F&O-eligible stock symbol at root of folder (flat, no subfolders).

```
implied volatility/
├── SBIN.csv
├── WIPRO.csv
├── KOTAKBANK.csv
└── ...  (219 files)
```

#### CSV schema

```csv
Date,Open,High,Low,Close,IV,Rank,IV Percentile
2024-11-29,330.0,334.5,327.2,330.6,28.746,1,100.0
2024-12-02,328.1,332.7,327.2,331.9,26.9389,2,50.0
```

- **Date:** `YYYY-MM-DD`
- **Open/High/Low/Close:** underlying stock price (not option price)
- **IV:** implied volatility as percentage (e.g. 28.746 = 28.7%)
- **Rank:** ordinal rank of current IV in lookback window
- **IV Percentile:** percentile of current IV (0–100)
- **Interval:** daily
- **Coverage:** recent data (2024–2026, varies by symbol)

---

### B.4 — options/

Recent options data. Source B always has OI; Source A may or may not — detect from header.

```
options/
├── stocks/    ← 206 F&O-eligible stock symbols
│   ├── NHPC/
│   │   ├── 2024-12-26/
│   │   │   ├── NHPC_85_PE_28_OCT_25.csv
│   │   │   ├── NHPC_86_CE_28_OCT_25.csv
│   │   │   └── ...
│   │   ├── 2025-01-30/
│   │   └── ...
│   └── BIOCON/, GLENMARK/, ...
└── index/     ← 5 indexes
    ├── banknifty/
    ├── finnifty/
    ├── midcpnifty/
    ├── niftynxt50/
    └── sensex/
```

Folder hierarchy: `{category}/{symbol}/{expiry_date}/{strike_file}.csv`
- `expiry_date` format: `YYYY-MM-DD`
- File naming same convention as Source A

#### CSV schema (WITH OI)

```csv
timestamp,open,high,low,close,volume,oi
2025-09-12T12:57:00+05:30,3.7,3.7,3.7,3.7,12800,38400
```

- **Separator:** comma
- **timestamp:** ISO 8601 with `+05:30` (note colon in offset, unlike Source A's `+0530`)
- **oi:** open interest (contracts outstanding)
- **Interval:** 1-minute bars
- **Coverage:** Dec 2024 – Apr 2026 (expiry folders range)

#### Scale

| Folder           | Symbol count | Total files |
|------------------|-------------|-------------|
| options/stocks   | 206         | 254,527     |
| options/index    | 5           | 79,256      |
| **Total**        | **211**     | **333,783** |

---

## Grand Scale Summary

| Data type                    | Files    | Est. rows          |
|------------------------------|----------|--------------------|
| Source A: NIFTY options      | 46,865   | ~200M              |
| Source A: BANKNIFTY options  | 51,186   | ~200M              |
| Source A: spot (NIFTY+BN)    | 2        | ~2.5M              |
| Equity daily                 | 1,848    | ~3M                |
| Equity minute                | 1,919    | ~800M+             |
| Fundamentals                 | 17,508   | ~150K (wide)       |
| Implied Volatility           | 219      | ~200K              |
| Options stocks (recent+OI)   | 254,527  | ~500M+             |
| Options index (recent+OI)    | 79,256   | ~200M+             |
| **Total files**              | **~453K**|                    |

---

## Schema Differences — Options Data (Critical)

Two schemas exist for options candles — must handle both in importer:

| Field       | Source A (historical 2014–2024)          | Source B (recent 2024–2026) |
|-------------|------------------------------------------|-----------------------------|
| date col    | `date` + `time` (split)                  | `timestamp` (combined ISO)  |
| date format | `YYYY-MM-DD` or `DD-MM-YYYY` — **detect per file** | ISO 8601 `YYYY-MM-DDTHH:MM:SS+05:30` |
| TZ          | IST implied (no offset in file)          | Explicit `+05:30`           |
| OI          | present or absent — **detect from header**| always present              |
| Separator   | comma                                    | comma                       |
| Instruments | NIFTY + BANKNIFTY only                   | 206 stocks + 5 indexes      |
| **Schema detect** | **read header row — never infer from path** | header always same |

---

## Proposed DB Table Mapping

| Source data                  | Target table / hypertable               | Notes |
|------------------------------|-----------------------------------------|-------|
| Source A nifty/banknifty options | `marketdata.candles` (instrument_type=OPTION) | OI NULL when column absent in file; detect per-file |
| Source A spot CSVs           | `marketdata.candles` (instrument_type=INDEX) | NIFTY + BANKNIFTY spot, 1m |
| Equity daily                 | `marketdata.candles` (instrument_type=EQ, interval=1d) | Or separate cagg |
| Equity minute                | `marketdata.candles` (instrument_type=EQ, interval=1m) | Very large files — batch carefully |
| Recent options (stocks)      | `marketdata.candles` (instrument_type=OPTION, oi present) | 254K files, ~500M rows |
| Recent options (index)       | `marketdata.candles` (instrument_type=OPTION) | 79K files |
| Implied Volatility           | `marketdata.iv_daily` (new table)       | symbol, date, iv, rank, percentile; also holds underlying OHLC |
| Fundamentals — P&L           | `fundamental.profit_and_loss` (new)     | Transpose wide→long on import |
| Fundamentals — balance sheet | `fundamental.balance_sheet` (new)       | Annual, needs FY-end date |
| Fundamentals — cash flows    | `fundamental.cash_flows` (new)          | Annual |
| Fundamentals — ratios        | `fundamental.ratios` (new)              | Annual |
| Fundamentals — quarterly     | `fundamental.quarterly_results` (new)   | Quarterly P&L |
| Fundamentals — shareholding  | `fundamental.shareholding` (new)        | Yearly + quarterly; merge into one with `granularity` col |

---

## Import Implementation Notes

### Parsing gotchas

1. **Date/time join for Source A options:** concatenate `date + " " + time` → parse as IST, convert to UTC
2. **Timezone offset format mismatch:** Source A spot = `+0530` (no colon), Source B options = `+05:30` (with colon) — use `fromisoformat()` on Python 3.11+ (handles both) or normalize
3. **Fundamentals wide→long:** transpose each file; column header (e.g. `Mar 2023`) → parse to `date` (last day of month / fiscal year-end); row index → `metric` name
4. **Encoding artefact:** strip `Â ` from metric names in fundamentals (UTF-8 mojibake of `\xC2\xA0` non-breaking space)
5. **Comma-thousands in fundamentals:** `"1,361"` — strip commas before float conversion
6. **Percent suffix in fundamentals:** `59.34%` — strip `%` and store as decimal (0.5934) or as-is
7. **OI null for Source A:** insert OI as NULL (not 0) to signal absent, not zero-OI

### Ordering for import

1. Start with spot CSVs (2 files, fast) → validates pipeline
2. Equity daily (1,848 small files) → low memory pressure
3. Source A options (98K files) → large but simple schema
4. Source B options stocks (254K files) → largest single category
5. Source B options index (79K files)
6. IV daily (219 small files)
7. Equity minute (1,919 large files — 38-54 MB each, keep workers low)
8. Fundamentals last (wide format, separate code path)

### Volume / performance flags

- Equity minute files are 38–54 MB each (~800M+ rows total) — most expensive single category per file
- Source B options: 333K files but typically small (41 bytes = header-only/no data = skip these)
- Many options files contain only a header line (41 bytes) — detect and skip early
- Source A options: ~200 rows/file average (minute data per trading day until expiry)
- Disable TimescaleDB compression policy during bulk import (see README.md)
