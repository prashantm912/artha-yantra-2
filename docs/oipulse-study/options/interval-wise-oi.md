# Interval wise OI — `/app/options-analysis/interval-wise-oi`

**Purpose:** top OI gainers / losers strikes across three lookbacks (15 min, 60 min, daily), as bar charts
colored by OI interpretation. Quick scan of where OI is moving most, by timeframe. Sub-tabs: `Interval Wise Oi | Options Analysis`.

## Layout
```
sub-tabs: [ Interval Wise Oi ] [ Options Analysis ]   ;  ticker strip
filter: Name[BANKNIFTY▾]  Date[📅]  Select Expiry Date[30-Jun-2026▾]  [Go]      Underlying: NIFTY BANK …
   Oi Gainer 15 min          Oi Gainer 60 min          Oi Gainer Daily
┌ bar ──────────────┐  ┌ bar ──────────────┐  ┌ bar ──────────────┐
│ top OI-rise strikes│  │ top OI-rise strikes│  │ top OI-rise strikes│
└────────────────────┘  └────────────────────┘  └────────────────────┘
   Oi Loser 15 min           Oi Loser 60 min           Oi Loser Daily
┌ bar (downward) ────┐  ┌ bar (downward) ────┐  ┌ bar (downward) ────┐
└────────────────────┘  └────────────────────┘  └────────────────────┘
legend: Green - Long Buildup / Red - Short Buildup / Yellow - Long Unwinding / Blue - Short Covering
```
**6 bar charts** (gainers row + losers row × 15min/60min/daily).

## Charts
| Chart | Bars | Direction |
|---|---|---|
| Oi Gainer 15/60 min, Daily | top strikes by OI increase | up bars |
| Oi Loser 15/60 min, Daily | top strikes by OI decrease | down (negative) bars |

- Each bar = a strike+type (e.g. "57400 PE"); height = OI change magnitude.
- **Bar color = OI interpretation** (confirms enum): green Long Buildup · red Short Buildup · yellow Long Unwinding · blue Short Covering.
- ECharts bar, toolbox, "Oi Pulse / 15-06-2026" watermark. Bottom global legend.

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Name | underlyings | uses `stSelectedOptions` |
| Date | date picker | |
| Select Expiry Date | YYMMDD | |
| Go | button | |
**No Mode filter** — always live data only.

## Vue component state
```
oiRise15min, oiLose15min, oiRise60min, oiLose60min, oiRiseDaily, oiLoseDaily
```
Each is a chart-data object:
```json
{
  "xAxisData": ["57300 CE", "57400 CE", ...],
  "yAxisData": [
    { "value": "27840", "itemStyle": { "color": "#f44336" } },
    ...
  ],
  "tooltipLabel": ["Short Build Up", "Long Buildup", ...]
}
```
Color: **`#f44336`** = red = bearish (Short Build Up) · **`#4caf50`** = green = bullish (Long Buildup) · yellow = Long Unwinding · blue = Short Covering.

## Socket subscriptions
**None** — no socket; page is batch/snapshot only.

> **Phase B (2026-06-18) — CONFIRMED:** this page is **REST-only — subscribes NO socket channels**
> (verified after a Go on the live capture run). See [Phase B findings](../PHASE-B-FINDINGS.md).

## Data source / API (`interval-wise-oi`)
| Call | Response |
|---|---|
| `/api/interval-wise-oi/getselectedoptionsdate` | dates |
| `/api/interval-wise-oi/getselectedoptionsdataexpirydate` | expiries |
| `/api/interval-wise-oi/getintervalwiseoidata` | main |

Main request + confirmed row schema:
```
POST /api/interval-wise-oi/getintervalwiseoidata
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "stSelectedModeOfData": "live"
}
Response: { "status": "success", "data": { "data": [ row, ... ], "underLyingAssetData": {...} } }
```

Row schema (confirmed):
```json
{ "stOptionName": "57000 CE", "inOiDiff": "190440", "inLtpDiff": "-77.75", "stChartType": "oi_rise_daily" }
```
- `stChartType` values confirmed (12 total — 6 regular + 6 `_cd_data` variants):
  - Regular (6 charts): `oi_rise_15_min`, `oi_rise_60_min`, `oi_rise_daily`, `oi_lose_15_min`, `oi_lose_60_min`, `oi_lose_daily`
  - `_cd_data` (overlay): `oi_rise_15_min_cd_data`, `oi_rise_60_min_cd_data`, `oi_rise_daily_cd_data`, `oi_lose_15_min_cd_data`, `oi_lose_60_min_cd_data`, `oi_lose_daily_cd_data`
  - **Note:** "lose" not "fall"; `_cd_data` rows are the same strike repeated across all 3 intervals (change-direction overlay markers)
- `stChartType` buckets each row into one of the 6 primary charts; `_cd_data` rows are secondary overlays.
- Bar value = `inOiDiff`; color = interpretation from sign(`inOiDiff`)×sign(`inLtpDiff`).

## Replication notes (→ ArthaYantra)
- One endpoint returns all rows tagged by `stChartType`; group into 6 `ay-echart` bars.
- Color bars via the OI-interpretation enum (price `inLtpDiff` × OI `inOiDiff`).

## Screenshot
ss_1447giahk (6 bar charts, gainers/losers × 15m/60m/daily, interpretation colors).
