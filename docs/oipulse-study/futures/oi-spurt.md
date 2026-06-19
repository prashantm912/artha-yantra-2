# Futures OI Spurt — `/app/futures-analysis/oi-spurt`

**Purpose:** market-wide F&O **OI scanner**. Scans all F&O stocks and buckets each into one of the
four OI-interpretation categories, ranked by OI change — surfacing where fresh longs/shorts are
building or unwinding across the market. Sub-tabs: `Oi Spurt | Futures Analysis`.

## Layout
```
sub-tabs: [ Oi Spurt ] [ Futures Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)  Asset[All F&O Stocks▾]  Expiry[Current Month▾]  Date[📅]  Search[…]  [Go]
                                                                          Data last Updated At: -
┌ Long Build Up ───────────────────────────┐ ┌ Short Build Up ──────────────────────────┐
│ (price↑ OI↑)                              │ │ (price↓ OI↑)                              │
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
┌ Short Covering ──────────────────────────┐ ┌ Long Unwinding ──────────────────────────┐
│ (price↑ OI↓)                              │ │ (price↓ OI↓)                              │
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
```
**2×2 grid of four ranked tables**, one per OI interpretation (see matrix in `oi-analysis.md`).

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live data / Historical |
| Asset | select | `All F&O Stocks` / index / sector groups (from `getlistofassetforheatmap`) |
| Expiry | select | Current Month / … |
| Date | date picker | trading day |
| Search | text input | "Search for particular symbol" — filter rows |
| Go | button (red) | scan |

**Historical mode** shows the previous ~2 months (date dropdown).

## Per-quadrant table columns
| Column | Source | Render |
|---|---|---|
| Name | `stSymbolName` (+ mini-chart icon) | text |
| LTP | `inNewClose` | |
| Prev. Close | `inOldClose` | |
| LTP Chg % | computed `(new−old)/old` | green if +, red if − |
| OI Chg % | computed `(newOi−oldOi)/oldOi` | green/red |
| New OI | `inNewOi` | |
| Old OI | `inOldOi` | |
| OI Chg. | computed `newOi−oldOi` | green/red |

- All columns sortable (⇅). Each table independently paginated (`Rows per page 8`, `1-8 of N`, Prev/Next).
- Counts seen: Long Build Up 69 · Short Build Up 27 · Short Covering 104 · Long Unwinding 19.
- Bucketing rule = OI Interpretation matrix: sign(LTP Chg) × sign(OI Chg).

## Vue component state (confirmed)
```
selectedModeOfData, selectedAvailableDate, selectedAvailableAsset (null = all),
selectedExpiry, availableAsset, availableDate, availableExpiryData, availableModeOfData,
RiseInOiRiseInPriceData,    // Long Build Up — 92 rows today
SlideInOiSlideInPriceData,  // Long Unwinding — 22 rows
RiseInOiSlideInPriceData,   // Short Build Up — 64 rows
SlideInOiRiseInPriceData,   // Short Covering — 40 rows
tempRiseInOiRiseInPriceData, tempSlideInOiSlideInPriceData, ... (backup for search filter)
searchSymbol, doneTypingInterval,
socketSubscribedEvents,     // ["FD_OIS"]
stLastUpdatedAt, socketDataUpdateTimeoutId
```

Vue enriches each raw row with computed fields:
```json
{
  "stSymbolName": "360ONE",
  "inNewLtp": "1140.3",  "inOldLtp": "1133.5",
  "inLtpChangeInPercentage": 0.6,  "inLtpChange": 6.8,
  "inNewOi": "5939500",  "inOldOi": "5792500",
  "inOiChangeInPercentage": 2.54,  "inOiChange": 147000
}
```

## Socket subscriptions
- `FD_OIS` — futures OI spurt live feed (no symbol suffix — all-market feed)

**Socket payload ([Phase B confirmed](../PHASE-B-FINDINGS.md))** — single channel `FD_OIS` streams
~320 futures, one frame each per interval. Each live frame is an **array[4]** `[symbol, LTP, volume, OI]`;
the spurt ranking / bucketing is computed **client-side**:
```
FD_OIS   ["RECLTD-I",358.7,2800,57766800]
```

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/heatmap/getlistofassetforheatmap` | `{}` | `data:[{text:"Nifty 50",value:"Nifty 50"},{text:"Nifty Bank",...}]` |
| `/api/futures/getfuturesoispurtdate` | `{stSelectedModeOfData}` | `data:[{text,value}]` dates |
| `/api/futures/getfuturesoispurtdata` | `{stSelectedAsset, stSelectedExpiry, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[ row ]` |

Raw API row (confirmed):
```json
{ "stSymbolName": "360ONE", "stFetchTime": "14:02:00", "stFetchDate": "2026-06-16",
  "inOldOi": "5792500", "inOldClose": "1133.5",
  "inNewOi": "5939500", "inNewClose": "1140.3" }
```
`stSelectedAsset: null` = all instruments. All 4 quadrants come from ONE response; client computes %changes and buckets.

## Interpretation (how to trade)
- Per-quadrant read: **Short Build-Up** ⇒ likely resistance zone; **Short Covering** ⇒ limited/weak
  rally; **Long Build-Up** ⇒ fresh longs / bullish; **Long Unwinding** ⇒ profit-booking (price↓ +
  OI↓ ⇒ book profits). Apply the 50% strength filter, read all four quadrants together, and remember
  Calls vs Puts in the same quadrant imply opposite direction (method doc §5).

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- One scan endpoint returning {symbol, oldOi, oldClose, newOi, newClose} for the asset universe.
- Client: compute LTP%/OI%; partition into 4 buckets; render 4 sortable `p-table`s, each paginated; Search filters symbol.

## Screenshot
ss_372655whl (4 quadrants: Long/Short Build Up, Short Covering, Long Unwinding).
