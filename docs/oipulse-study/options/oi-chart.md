# Options OI Chart — `/app/options-analysis/chart`

**Purpose:** chart view of the per-strike Call/Put OI data (chart counterpart of Options OI Analysis).
Compares Call vs Put OI and each side's OI against its premium, intraday. Sub-tabs: `Oi Chart | Options Analysis`.
Same filter bar as Options OI Analysis (Name, Date, Expiry, Strike, Time Interval, Go).

## Layout
```
sub-tabs: [ Oi Chart ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry[30-Jun-2026▾]  Strike[57100▾]  Time Interval[3 min▾]  [Go]
                          Call Vs. Put Oi Analysis   (centered)
┌ line chart (full width) ──────────────────────────────────────────────────────────────────────────┐
│ y: OI (0–70,000)   x: time 09:18–15:27   green line = Call OI, red line = Put OI                     │
│ watermark "Oi Pulse / BANKNIFTY 57100"   toolbox   legend: ● Call OI  ● Put OI                       │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
        Call Oi Analysis                              Put Oi Analysis
┌ dual-axis line ───────────────────┐   ┌ dual-axis line ───────────────────┐
│ left: OI  right: Price            │   │ left: OI  right: Price            │
│ green OI line + orange Price line │   │ red OI line + orange Price line   │
│ legend: ● OI  ● Price             │   │ legend: ● OI  ● Price             │
└────────────────────────────────────┘   └────────────────────────────────────┘
```

## Components
| Chart | Type | Series | Axes |
|---|---|---|---|
| Call Vs. Put Oi Analysis | ECharts line (full width) | Call OI (green), Put OI (red) | single y = OI, x = time |
| Call Oi Analysis | ECharts dual-axis line | OI (green, left), Price (orange, right) | left OI / right premium |
| Put Oi Analysis | ECharts dual-axis line | OI (red, left), Price (orange, right) | left OI / right premium |

Each chart: ECharts toolbox (zoom/restore/line-bar/refresh/save PNG), "Oi Pulse / BANKNIFTY 57100" watermark, bottom legend (clickable to toggle series).

## Data source / API
Same discovery chain as Options OI Analysis, plus the chart endpoint:
`POST /api/options/getselectedoptionsalldataforchart`
(req: `{stSelectedOptions, stSelectedAvailableDate, stSelectedModeOfData, stSelectedAvailableExpiryDate, …strike, …interval}`) →
`data:[{ stOptionsType:"CE"/"PE", inStrikePrice, stTime, inOi, inOpen,inHigh,inLow,inClose, inDayHigh,inDayLow, inTradedVolume }]`
(identical shape to `getselectedoptionsalldata`; split CE/PE for the three charts).

## Replication notes (→ ArthaYantra)
- Three `ay-echart` line charts off ONE CE/PE strike series:
  - top: Call OI vs Put OI (single axis);
  - bottom-left: Call OI vs Call premium (dual axis);
  - bottom-right: Put OI vs Put premium (dual axis).
- Toggle Call/Put by `stOptionsType`; premium = `inClose`.

## Screenshot
ss_0318y6owy (Call vs Put OI + Call/Put OI-vs-Price, BANKNIFTY 57100).
