# Multiple OI Chart — `/app/options-analysis/multiple-oi-chart`

**Purpose:** overlay the OI of several user-selected strikes on one chart (with the underlying price line)
to compare how different strikes' OI evolve together. Sub-tabs: `Multiple Oi Chart | Options Analysis`.

## Layout
```
sub-tabs: [ Multiple Oi Chart ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Expiry Date[30-Jun-2026▾]  Select Strike Price[multi-select ▾]  [Go]
                          Individual OI   (centered)
┌ dual-axis line chart ─────────────────────────────────────────────────────────────────────────────┐
│ left axis: Price (57,250–57,550)   right axis: OI (0–25,000)   x: time                               │
│ blue dotted = NIFTY BANK (underlying); one colored line per selected strike (green 45000 CE, yellow 44000 CE) │
│ legend: ● NIFTY BANK  ● 45000 CE  ● 44000 CE  …                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
empty ("No data available") until ≥1 strike is selected.
```

## Components
| Control | Type | Notes |
|---|---|---|
| Select Strike Price | **multi-select autocomplete** | options = `<strike> <CE/PE>` each with an "Add" action; "N options selected" |
| Go | button (red) | fetch overlay |
| Individual OI chart | ECharts dual-axis line | underlying price (left, blue dotted) + one OI line per selected strike (right axis) |

Each selected strike → one colored OI line; underlying price overlaid for context. Toolbox + legend (toggle lines).

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Select Name | underlyings | uses `stSelectedOptions` |
| Select Date | date picker | |
| Expiry Date | YYMMDD | |
| Select Strike Price | **multi-select** (not plain `<select>`) | format: `"57200 CE"` / `"57200 PE"` strings |
| Go | button (red) | disabled until ≥1 strike selected |

## Vue component state
```
avaiableStrikePriceData  // NOTE: typo in source (not "available") — array of all strikes as "57200 CE" strings
selectedStrikePrices: [] // starts empty; user must pick ≥1 before Go works
preFinalDataSet          // built client-side from response
socketSubscribedEvents: [] // always empty — no live socket subscriptions
```
`preFinalDataSet.legend.data`: `["NIFTY BANK", "57200 PE", "57200 CE", "57100 PE", "57100 CE"]` — underlying always first, then selected strikes.

## Socket subscriptions
**None** — `socketSubscribedEvents` is always `[]`. Page is request/response only, no live updates.

## Data source / API
| Call | Response |
|---|---|
| `/api/options/getavailableoptionsdata` | instruments |
| `/api/options/getselectedoptionsdate` | dates |
| `/api/options/getoptionsdataexpirydate` | expiries |
| `/api/options/getselectedoptionsstrikepricewithtypedata` | available strikes for multi-select |
| `/api/options/getoptionsoidataformultipleoichart` | main OI series |

Strike-list endpoint:
```
POST /api/options/getselectedoptionsstrikepricewithtypedata
Body: {"stSelectedOptions":"BANKNIFTY","stSelectedAvailableDate":"2026-06-16","stSelectedAvailableExpiryDate":"260630","stSelectedModeOfData":"live"}
Response: {"status":"success","data":[{"text":"43000 PE","value":"43000 PE"},{"text":"57200 CE","value":"57200 CE"},...]}
```
Returns `{text, value}` pairs (both same string e.g. `"57200 CE"`). ~250+ strikes for BANKNIFTY, sorted by ascending strike, PE-before-CE at deep OTM/ITM and interleaved near ATM.

Main request + confirmed schema:
```
POST /api/options/getoptionsoidataformultipleoichart
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "stSelectedStrikePrices": ["57200 CE", "57200 PE", "57100 CE", "57100 PE"],
  "stSelectedModeOfData": "live"
}
Response: {
  "status": "success",
  "data": {
    "strikePriceData": [
      {
        "stTime": "09:16:00",
        "obOiData": [ { "57200 PE": 90450 }, { "57200 CE": 134820 }, ... ]
      },
      ...
    ]
  }
}
```
`strikePriceData` is a flat array of time-rows. Each row's `obOiData` is an array of single-key objects keyed by the strike string (e.g. `"57200 PE"`). Underlying price comes from the separate `EQUITY_UNDERLYING_DATA` socket/store (not in this endpoint).

## Replication notes (→ ArthaYantra)
- Multi-select strike picker (PrimeNG `p-multiSelect`/autocomplete) → fetch per-strike OI series + underlying price → `ay-echart` dual-axis multi-line.
- Color per strike; underlying as a reference line.

## Screenshot
ss_9909q6m9o (45000 CE + 44000 CE OI overlaid with NIFTY BANK price).
