# Open & High Strategy — `/app/options-analysis/open-high-strategy`

**Purpose:** surface options strikes where **Open = High** (bullish CE signal) or **Open = Low** (bearish PE signal)
fired today, with triggered time and historical probability of the condition triggering.
Listed under Strategies menu. Sub-tabs: `Open & High Strategy | Options Analysis`.

## Layout
```
sub-tabs: [ Open & High Strategy ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Date[30-Jun-2026▾]
        Action: ● Open = High  ○ Open = Low   [Go]
        Underlying: NIFTY BANK at 57203.4 …

Symmetric table — CE side (left) | Strike | PE side (right):
┌ Call (CE) ──────────────────────────────────────── Strike ──────────────────── Put (PE) ─────────────────────────────────────── ┐
│ Day Open │ Day High │ New D.High │ New D.Low │ O=H/O=L │ Triggered Time │ Probability │ Call LTP │ STRIKE │ Put LTP │ Probability │ Triggered Time │ O=H/O=L │ New D.Low │ New D.High │ Day High │ Day Open │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

Rows per page: [30▾]    1 - 19 of 19
```

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | `selectedModeOfData` |
| Select Name | 9 indices + 211 stocks | `selectedOptions` / `stSelectedOptions` |
| Select Date | date picker | `selectedAvailableDate` |
| Select Expiry Date | YYMMDD | `selectedAvailableExpiryDate` |
| Action | radio: `openHigh` / `openLow` | `selectedTypeOfData`; "Open = High" or "Open = Low" |
| Go | button | triggers fetch |

## Table columns
Symmetric: left half = CE, right half = PE, center = Strike.

| Column | Source | Notes |
|---|---|---|
| Day Open | `inOldDayOpen` (opening LTP) | option's day-open premium |
| Day High | `inOldDayHigh` | option's day-high at open-bar |
| New D.High | `inNewDayHigh` | current day high |
| New D.Low | `inNewDayLow` | current day low |
| O=H / O=L | text badge `O = H` / `O = L` | confirms which condition applies |
| Triggered Time | `stOldDayHighBreakTime` / `stOldDayLowBreakTime` | "Open = High Triggered" label + `HH:MM` time |
| Probability | client-side computed | discrete % (60/80/90/95); shown only when NOT yet triggered today |
| Call/Put LTP | `inNewLtp` | current premium |
| Strike | `inStrikePrice` | center column |

**Triggered row**: shows "Open = High Triggered" text + `HH:MM` time in Triggered Time column; Probability hidden.
**Not-yet-triggered row**: shows Probability % (historical odds); Triggered Time empty.

## Vue component state
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedModeOfData, selectedTypeOfData,   // "openHigh" | "openLow"
selectedOptions, selectedAvailableDate, selectedAvailableExpiryDate,
availableOptionsData, availableDate, availableExpiryDate, availableModeOfData,
underLyingAssetData, inAtmStrikePrice,
fullTableData, tableData,                  // fullTableData usually empty (historical mode only)
doneTypingInterval                         // polling timer handle
```

Vue **pairs** raw CE+PE rows by `inStrikePrice` into one combined `tableData` row:
```json
{
  "inStrikePrice": "57200",
  "inCallOldDayOpen": 638.5, "inCallOldDayHigh": 638.5, "inCallOldDayLow": 638.5,
  "inCallOldDayHighBreakTime": "09:42:00", "inCallOldDayLowBreakTime": null,
  "inCallNewDayHigh": 702.6, "inCallNewDayLow": 591.65, "inCallNewLtp": 749.1,
  "inCallOpenHighProbability": null,   // null when triggered (shows text instead)
  "inCallOpenLowProbability": null,
  "inPutOldDayOpen": 682.5, ...same pattern...
  "inPutOpenHighProbability": null, "inPutOpenLowProbability": null
}
```

## Socket subscriptions
**None** — no `socketSubscribedEvents` in Vue state. Page uses `doneTypingInterval` timer for auto-poll.

## Data source / API (`open-high-strategy`)
| Call | Response |
|---|---|
| `/api/options/getavailableoptionsdata` | underlyings (shared namespace) |
| `/api/open-high-strategy/getselectedoptionsdate` | dates |
| `/api/open-high-strategy/getselectedoptionsdataexpirydate` | expiries |
| `/api/open-high-strategy/getoptionsopenhighstrategydata` | main |

Main request + confirmed row schema:
```
POST /api/open-high-strategy/getoptionsopenhighstrategydata
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "stSelectedModeOfData": "live"
}
Response: {
  "status": "success",
  "data": {
    "data": [
      {
        "inStrikePrice": "57200",
        "stOptionsType": "CE",
        "inOldLtp": 638.5,
        "inOldDayOpen": 638.5,
        "inOldDayHigh": 638.5,
        "inOldDayLow": 638.5,
        "inNewLtp": 749.1,
        "inNewDayHigh": 702.6,
        "inNewDayLow": 591.65,
        "stOldDayHighBreakTime": "09:42:00",
        "stOldDayLowBreakTime": null
      },
      ...
    ],
    "underLyingAssetData": {...}
  }
}
```
- Flat array of CE + PE rows (one entry per strike per type)
- **No probability in API response** — probability is computed client-side by Vue (discrete: 60/80/90/95%)
- `stOldDayHighBreakTime` = time Open=High was broken; null = not triggered
- `stOldDayLowBreakTime` = time Open=Low was broken; null = not triggered

## Replication notes (→ ArthaYantra)
- Symmetric table: pair CE/PE rows by strike, display both halves mirrored around center Strike column.
- "Triggered" detection: `stOldDayHighBreakTime != null` → show triggered label + time, suppress probability.
- Probability column: client-computed; exact formula requires Vue source inspection (discrete 60/80/90/95 tiers).
- Auto-poll interval (no socket); `selectedTypeOfData` toggles Open=High vs Open=Low view.

## Screenshot
ss_71353v83g (BANKNIFTY O=H scan, green O=H badges, Hit/Triggered columns).
