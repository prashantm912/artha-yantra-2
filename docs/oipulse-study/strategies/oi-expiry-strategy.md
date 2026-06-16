# OI Expiry Strategy — `/app/options-analysis/oi-expiry-strategy`

**Real tab title:** "Options EOD Oi Analysis"
**Purpose:** EOD OHLC + OI + Volume history for each selected strike (CE and PE) across the current expiry cycle
(~31 trading sessions). Lets you see OI build/unwind pattern per strike across all sessions till expiry.
Sub-tabs: `Oi Expiry Strategy | Options Analysis`. Listed under Strategies menu.

## Layout
```
sub-tabs: [ Oi Expiry Strategy ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]  [Go]  [Change Strike Prices]
Selected Strike Prices: 56500, 57000, 57500, 58000, 58500
Underlying: NIFTY BANK at 57196 …

For EACH selected strike — two stacked tables:

┌ 56500 CE ────────────────────────────────────────────────────────── ┐
│ # │ Date  │ Open   │ High   │ Low    │ Close  │ Volume  │ % Chg Close │ % Chg OI │ OI     │ OI Interpretation │
│ 1 │ 16-Jun│ 1199.10│1299.90 │1032.60 │ 1145.00│1,56,690 │ -6.19%      │  2.23%   │2,56,170│ Short Build Up    │
│ 2 │ 15-Jun│ 1500.05│1611.35★│1175.10 │ 1220.50│5,17,410 │ 16.2%       │ -34.2%   │2,50,590│ Short Covering    │
│...│       │        │        │        │        │         │             │          │        │                   │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page: [7▾ 15 25 50 75 All]    1 - 7 of 31

┌ 56500 PE ────────────────────────────────────────────────────────── ┐
│ Same columns ...                                                     │
└─────────────────────────────────────────────────────────────────────┘

[repeat for 57000 CE / PE, 57500 CE / PE, ...]
```
★ `isAllDayHigh` / `isAllDayLow` rows are highlighted.

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | `selectedModeOfData` |
| Name | 9 indices + ~180 stocks | `selectedOptions` |
| Date | date picker | `selectedAvailableDate` |
| Expiry Date | YYMMDD | `selectedAvailableExpiryDate` |
| Go | button | re-fetches |
| Change Strike Prices | button (outline) | opens `showStrikePriceListModal` |

**Default selected strikes**: 5 near-ATM strikes (56500, 57000, 57500, 58000, 58500 for BANKNIFTY ~57200).

## Table columns (same for CE and PE)
| Column | Source | Render |
|---|---|---|
| # | row index | 1-based |
| Date | `stDate` | `DD-Mon` |
| Open | `inDayOpen` | premium day open |
| High | `inDayHigh` | premium day high; ★ if `isAllDayHigh:true` |
| Low | `inDayLow` | premium day low; ★ if `isAllDayLow:true` |
| Close | `inDayClose` | premium day close |
| Volume | `inVolume` | formatted with commas |
| % Chg Close | `inChangeInClose` | green/red % |
| % Chg OI | `inChangeInOi` | green/red % |
| OI | `inOi` | absolute OI |
| OI Interpretation | `stOiInterpretation` | badge: Long Build Up / Long Unwinding / Short Build Up / Short Covering |

Pagination per table: 7 rows default; 7/15/25/50/75/All options. ~31 rows = one full expiry cycle.

## Vue component state
```
minAvailableDate, maxAvailableDate, showStrikePriceListModal,
disableRefreshDataButton,
selectedModeOfData, selectedOptions,
selectedAvailableDate, selectedAvailableExpiryDate,
availableOptionsData, availableDate, availableExpiryDate, availableModeOfData,
defaultSelectedStrikePrices,   // [56500, 57000, 57500, 58000, 58500]
selectedStrikePrices,          // user-chosen subset
selectedStrikePricesString,    // comma-separated display string
availableStrikePrices,
underLyingAssetData, inAtmStrikePrice,
columns,                       // 7-column def (used by a sub-component)
arTableData                    // main: [{inStrikePrice, objStrikePriceData:[{CE:[...]},{PE:[...]}]}]
```

`arTableData` structure:
```json
[
  {
    "inStrikePrice": "56500",
    "objStrikePriceData": [
      { "CE": [ ...31 daily rows ] },
      { "PE": [ ...31 daily rows ] }
    ]
  },
  ...
]
```

## Socket subscriptions
**None confirmed** — EOD page, no live socket subscriptions.

## Data source / API (`opt-eod-oi-analysis`)
| Call | Response |
|---|---|
| `/api/options/getavailableoptionsdata` | underlyings (shared) |
| `/api/opt-eod-oi-analysis/getselectedoptionsdate` | dates |
| `/api/opt-eod-oi-analysis/getselectedoptionsdataexpirydate` | expiries |
| `/api/opt-eod-oi-analysis/getselectedoptionseoddata` | main |

Main request + confirmed row schema:
```
POST /api/opt-eod-oi-analysis/getselectedoptionseoddata
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "stSelectedStrikePrices": [56500, 57000, 57500, 58000, 58500],
  "stSelectedModeOfData": "live"
}
Response: {
  "status": "success",
  "data": {
    "data": [
      {
        "inStrikePrice": "56500",
        "objStrikePriceData": [
          { "CE": [
            {
              "stDate": "2026-06-16",
              "inDayOpen": 1199.1, "inDayHigh": 1299.9, "inDayLow": 1032.6, "inDayClose": 1145,
              "inOi": 256170, "inVolume": 156690,
              "inChangeInClose": -6.19, "inChangeInOi": 2.23,
              "inOiInterpretation": 3, "stOiInterpretation": "Short Build Up"
            },
            {
              "stDate": "2026-06-15",
              "inDayOpen": 1500.05, "inDayHigh": 1611.35, "inDayLow": 1175.1, "inDayClose": 1220.5,
              "inOi": 250590, "inVolume": 517410,
              "inChangeInClose": 16.2, "inChangeInOi": -34.2,
              "inOiInterpretation": 4, "stOiInterpretation": "Short Covering",
              "isAllDayHigh": true
            },
            ...
          ]},
          { "PE": [ ...same schema... ] }
        ]
      }
    ],
    "underLyingAssetData": {...}
  }
}
```

**Special flags** on daily rows:
- `isAllDayHigh: true` — that date's High was the expiry-cycle high for the option
- `isAllDayLow: true` — that date's Low was the expiry-cycle low for the option

**OI Interpretation enum** (same as all other pages): 1=Long Build Up · 2=Long Unwinding · 3=Short Build Up · 4=Short Covering.

## Replication notes (→ ArthaYantra)
- Strike selector (multi/default set of 5) → one CE + one PE table per strike.
- Each table: paginated (7 rows default), columns as above, `isAllDayHigh`/`isAllDayLow` row highlight.
- OI Interpretation badge uses shared 4-state enum.
- No socket; batch load, possibly auto-poll with `disableRefreshDataButton`.

## Screenshot
ss_9039i7nwg (BANKNIFTY 56500 CE+PE, EOD daily OHLC+OI history, OI Interpretation badges).
