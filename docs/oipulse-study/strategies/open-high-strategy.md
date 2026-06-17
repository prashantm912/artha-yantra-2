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

## Interpretation (how to trade) — the Open=High method
- Definition: a contract is Open=High (or Open=Low) when its Open price exactly equals its High (or Low) at the open — strict equality; the Day-High equals the Day-Open at formation.
- Polarity: the option-premium trade is contrarian to the usual cash "O=H ⇒ bearish" intuition — an OH on a Call (with an OL on the Put) is read bullish for that Call (its premium is expected to revisit the high).
- Why OH forms: a big player's aggressive pre-open bid sweeps the book so the open prints at the top, then reverts.
- Confluence (Table 1): Futures OH + Call OH + Put OL = High probability; an option-only signal = Mild; the bearish twin mirrors it.
- Confirmation (Table 2): wait for follow-up candles judged against a volume threshold — for a Call OH, a pullback on low volume raises the odds, while a pullback on heavy volume lowers them.
- Extra filters: global markets and India VIX must not be moving against the position.
- Entry is a momentum scalp (price turning back toward the OH level with volume), not a level-cross; treat it as a scalp, not a positional trade, and pyramid modestly.
- Strike selection: only ATM ±3–4 strikes with premium ≈ ₹200–300; ignore deep ITM/OTM strikes even if they show OH.
- Validity: the odds decay exponentially after roughly 11:00 AM.
- Size by confidence; if OH appears on both the CE and the PE, reduce size.
- Exit: trail the position; do not wait for price to cross the OH level to book.
- LTP-distance gate: the smaller the gap of the current LTP below the OH level, the higher the reversion odds — this is the interpretation behind the existing "Far from High?" % column.
- Probability framing: the manual treats Probability as an AI output — treat >90% as a *prepare* signal (not an entry), and a "Red Dot" on the O=H badge as a stronger composite trigger.
- Failure modes: reversion fails when a bigger player enters later (a heavy-volume move against it); OH does not predict the day's direction — it is one scalp, not a trend tool.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Symmetric table: pair CE/PE rows by strike, display both halves mirrored around center Strike column.
- "Triggered" detection: `stOldDayHighBreakTime != null` → show triggered label + time, suppress probability.
- Probability column: client-computed; exact formula requires Vue source inspection (discrete 60/80/90/95 tiers).
- Auto-poll interval (no socket); `selectedTypeOfData` toggles Open=High vs Open=Low view.

## Screenshot
ss_71353v83g (BANKNIFTY O=H scan, green O=H badges, Hit/Triggered columns).
