# Connecting Dots — `/app/connecting-dots`

**Purpose:** multi-factor sentiment matrix. For a chosen instrument, each 3-min (configurable) interval
gets a row; each column is a market factor rated Bullish / Bearish / Neutral, plus a composite **Trend**.
Lets a trader "connect the dots" across factors per snapshot to read intraday bias.

## Layout (top → bottom)
```
sub-tabs: [ Connecting Dots ] [ Tool ]
ticker strip
┌ filter bar ───────────────────────────────────────────────────────────────────────┐
│ Mode: (•)Live data ( )Historical   Name:[BANKNIFTY ▾]   Date:[Mon, Jun 15 2026 📅]  │
│                                     Time Interval:[3 min ▾]   Actions:[ Go ](red)    │
└─────────────────────────────────────────────────────────────────────────────────────┘
┌ data table (scrollable, paginated) ─────────────────────────────────────────────────┐
│ Date Time | Trend | Dow Jones | Vix | Volume | Active Strike IV | Active Strike OI |  │
│           | OI Inter. | VWAP | Supertrend | RSI | Price | Daily Trend                │
│ rows: latest interval first (desc)                                                    │
└─────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[25▾]                       1 - 25 of 126        ‹ Previous   Next ›
┌ Signals (legend) ───────────────────────────────────────────────────────────────────┐
│ [Ext. Bullish ↑] = Extreme Bullish  [Ext. Bearish ↓] = Extreme Bearish               │
│ [↑] = Bullish   [↓] = Bearish   [↔] = Neutral                                         │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

## Filter bar components
| Control | Type | Values | Notes |
|---|---|---|---|
| Mode | radio pair | `Live data` (default) / `Historical` | Historical enables past-date study |
| Name | select dropdown | instrument (BANKNIFTY, NIFTY, …) | drives `getselectedassetdate` |
| Date | date picker | e.g. `Mon, Jun 15, 2026` | available dates from API per asset |
| Time Interval | select | `3 min` (and other intervals) | bucket size for rows |
| Go | button (red `--primary`) | — | triggers `getselectedassetalldata` |

## Data table
Latest interval at top (descending). Left has a row-number column (1..25).

| Column | Header | API field | Cell render |
|---|---|---|---|
| Date Time | `Date Time` | `stTimeInterval` (e.g. "15:27-15:30") | plain text |
| Trend (composite) | `Trend` | `inTrend` | text badge + arrow: `badge-trend-label` colored |
| Dow Jones | `Dow Jones` | `inDow` | arrow badge |
| Vix | `Vix` | `inVix` | arrow badge |
| Volume | `Volume` | `inVolume` | arrow badge |
| Active Strike IV | `Active Strike IV` | `inActiveStrikeIv` | arrow badge |
| Active Strike OI | `Active Strike OI` | `inActiveStrikeOi` | arrow badge |
| OI Inter. | `OI Inter.` | `inSelectedFutOi` | arrow badge (OI interpretation) |
| VWAP | `VWAP` | `inVwap` | arrow badge |
| Supertrend | `Supertrend` | `inSupertrend` | arrow badge |
| RSI | `RSI` | `inRsi` | arrow badge |
| Price | `Price` | `inSelectedFutPrice` | arrow badge |
| Daily Trend | `Daily Trend` | `inDailyTrend` | arrow badge |

### Cell encoding (CONFIRMED by matching live Vue `tableData` ints → rendered icons)
**Factor columns** (3-state):
| int | meaning | badge | icon | color |
|---|---|---|---|---|
| `0` | Neutral | `badge-interpretation badge-info` | `i-Left---Right` (↔) | blue `#003473` |
| `1` | Bullish | `badge-interpretation badge-success` | `i-Triangle-Arrow-Up` (↑) | green `#4caf50` |
| `2` | Bearish | `badge-interpretation badge-danger` | `i-Triangle-Arrow-Down` (↓) | red `#f44336` |

**Composite `inTrend`** (5-state badge `badge-trend-label`):
| int | label | confidence |
|---|---|---|
| `4` | Ext. Bearish (↓, red) | CONFIRMED |
| `3` | Bearish (↓, red) | CONFIRMED |
| `2` | Bullish (↑, green) | inferred |
| `1` | Ext. Bullish (↑, green) | inferred |
| `0` | Neutral (↔, blue) | inferred |
> Verify 0/1/2 during build by matching a Bullish-labeled rendered row to its `inTrend` value.

Row striping: extreme-trend rows get a faint maroon row background tint.

## Pagination
`Rows per page` select (25 default) · `N - M of TOTAL` · `Previous` / `Next`. Client-side over the full day's intervals (126 for 3-min).

## Signals legend (bottom card titled "Signals")
Five pills: `Ext. Bullish ↑` (green), `Ext. Bearish ↓` (red), `↑ = Bullish` (green), `↓ = Bearish` (red), `↔ = Neutral` (blue).

## Data source / API
| Call | Method | Request body | Response |
|---|---|---|---|
| `/api/connecting-dots/getselectedassetdate` | POST | `{stSelectedAsset, stSelectedModeOfData}` | `{status,msg,data:[{text,value}]}` (available dates) |
| `/api/connecting-dots/getselectedassetalldata` | POST | `{stSelectedAsset, stSelectedAvailableDate, stSelectedTimeInterval, stSelectedModeOfData}` | `{status,msg,data:[ <row> ]}` |

Row object:
```json
{ "stTimeInterval":"09:18-09:21",
  "inDow":1,"inVix":1,"inVolume":2,"inActiveStrikeIv":2,"inActiveStrikeOi":1,
  "inSelectedFutOi":2,"inVwap":2,"inSupertrend":1,"inRsi":1,"inSelectedFutPrice":2,
  "inDailyTrend":2,"inTrend":2 }
```

## Replication notes (→ ArthaYantra)
- Inputs: instrument select + mode toggle + date + interval + Go → POST to our equivalent signal-matrix endpoint.
- Each factor is a precomputed per-interval signal int; we compute these server-side (Supertrend, RSI, VWAP, VIX comparison, OI interpretation, active-strike IV/OI, volume, Dow correlation, daily trend) and the composite Trend.
- Render: PrimeNG `p-table`, scrollable + paginator(25), each factor cell a `p-tag` with arrow icon by the 0/1/2 enum; composite Trend a wider tag with 5-state enum.
- Legend card below table. Extreme rows: subtle row class.

## Screenshot
ss_533127vwh (live BANKNIFTY 3-min matrix, 126 rows).
