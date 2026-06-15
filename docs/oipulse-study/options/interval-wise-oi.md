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

## Data source / API (`interval-wise-oi`)
| Call | Response |
|---|---|
| `/api/interval-wise-oi/getselectedoptionsdate` | dates |
| `/api/interval-wise-oi/getselectedoptionsdataexpirydate` | expiries |
| `/api/interval-wise-oi/getintervalwiseoidata` | `{ data:[ row ], underLyingAssetData }` |

Row:
```json
{ "stOptionName":"57500 CE", "inOiDiff":"296790", "inLtpDiff":"-283.95", "stChartType":"oi_rise_60_min_cd_data" }
```
- `stChartType` buckets the row into one of the 6 charts (`oi_rise_15_min` / `oi_rise_60_min` / `oi_rise_daily` / `oi_fall_*`).
- Bar value = `inOiDiff`; color = interpretation from sign(`inOiDiff`)×sign(`inLtpDiff`).

## Replication notes (→ ArthaYantra)
- One endpoint returns all rows tagged by `stChartType`; group into 6 `ay-echart` bars.
- Color bars via the OI-interpretation enum (price `inLtpDiff` × OI `inOiDiff`).

## Screenshot
ss_1447giahk (6 bar charts, gainers/losers × 15m/60m/daily, interpretation colors).
