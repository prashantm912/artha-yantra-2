# Delivery Data — `/app/equity/delivery-data`

**Purpose:** daily delivery percentage & quantity for a stock over a period (spot accumulation/distribution),
with OHLC, day range and announcement count. No sub-tabs.

## Layout
```
sub-tabs: [ Delivery data ] [ Equity ]   ;  ticker strip
filter: Search Type: (•)Only F&O Stocks ( )All Stocks   Name[AXISBANK▾]   Period[Last 15 Days▾]   [Go]
                              Company delivery info table   (centered)
┌ table ──────────────────────────────────────────────────────────────────────────────────────────────┐
│ Date | Open | High | Low | Close | % LTP Change | % Delivery | Day Range | Delivery Quantity |        │
│   Total Traded Quantity | Announcement                                                                 │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[15▾]                                    1 - 15 of 15
```

## Filter
| Control | Type | Values |
|---|---|---|
| Search Type | radio | `Only F&O Stocks` (`FNO_ONLY`) / `All Stocks` (`ALL`) |
| Name | autocomplete/select | stock symbol (from `getlistoffnosymbolnames`; 211 F&O stocks) |
| Period | select | Last 7 Days / **Last 15 Days** (default) / Last 30 Days / Last 2 months / Last 3 months / Last 6 months |
| Go | button (red) | fetch |

No date picker — period is relative (`selectedNoOfDays`). `selectedAvailableDate: null`.

## Columns
| Column | Source | Render |
|---|---|---|
| Date | `stFetchDate` | daily |
| Open/High/Low/Close | `inOpen`/`inHigh`/`inLow`/`inClose` | |
| % LTP Change | `inCloseChange` | green/red |
| % Delivery | `inDeliveryPercentage` | delivery qty / total traded |
| Day Range | computed `inHigh − inLow` + `(range %)` | "17.40 (1.27 %)" |
| Delivery Quantity | `inDeliveryQuantity` | |
| Total Traded Quantity | `inTotalTradedQuantity` | |
| Announcement | `inNoOfAnnouncement` | **red count badge** (1/2/…) or `-` if none |

## Vue component state (confirmed)
```
stStockSearchType ("FNO_ONLY"),   // radio: FNO_ONLY | ALL
stSymbolNameString ("AXISBANK"),  // selected symbol
selectedAvailableDate (null),     // no date picker on this page
selectedNoOfDays (15),            // from Period dropdown
availableNoOfDays,                // [{value:7,"Last 7 Days"},{value:15,...},{value:30,...},{value:60,"Last 2 months"},{value:90,"Last 3 months"},{value:180,"Last 6 months"}]
doneTypingInterval,
listOfFnoStocks ([]),             // 211 F&O symbols for autocomplete
deliveryData ([]),                // 15 rows (one per day)
suggestionList, defaultSuggestionList
```

## Data source / API
| Call | Request | Response |
|---|---|---|
| `getlistoffnosymbolnames` | `{stSelectedModeOfData}` | `[{text,value}]` — 211 F&O symbol names |
| `getequitydeliverydata` | `{stSelectedModeOfData, stSymbolNameString:"AXISBANK", inSelectedNoOfDays:15}` | `data:[ row ]` |

Namespace: `equity` for both endpoints.

Confirmed row schema (AXISBANK):
```json
{
  "stFetchDate": "2026-06-15",
  "inOpen": 1372,
  "inHigh": 1378,
  "inLow": 1360.6,
  "inClose": 1368.3,
  "inDeliveryPercentage": 69.39,
  "inDeliveryQuantity": 5220325,
  "inTotalTradedQuantity": 7522828,
  "inCloseChange": "0.88",
  "inNoOfAnnouncement": null,
  "inDayRange": 17.4,
  "inDayRangePercentage": 1.27
}
```
Note: `inCloseChange` is a STRING (`"0.88"` not `0.88`). `inNoOfAnnouncement` can be null. `inDayRange`/`inDayRangePercentage` are server-computed.

## Replication notes (→ ArthaYantra)
- No date picker — only a period (# of days). Request: `{stSymbolNameString, inSelectedNoOfDays}`.
- `p-table` sorted by date desc; % Delivery is the key column (high = institutional conviction).
- Announcement count → red badge; null shows as `-`.

## Screenshot
ss_5305o4vez (AXISBANK 15-day delivery table, announcement badges).
