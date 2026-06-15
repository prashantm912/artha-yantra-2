# Delivery Data — `/app/equity/delivery-data`

**Purpose:** daily delivery percentage & quantity for a stock over a period (spot accumulation/distribution),
with OHLC, day range and announcement count. Sub-tabs: `Delivery data | Equity`.

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
| Search Type | radio | `Only F&O Stocks` / `All Stocks` |
| Name | select | stock (from `getlistoffnosymbolnames`, 211 F&O names) |
| Period | select | `Last 15 Days` (and other ranges) |
| Go | button (red) | fetch |

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

## Data source / API
| Call | Response |
|---|---|
| `/api/equity/getlistoffnosymbolnames` | `[{text,value}]` symbol list (211 F&O) |
| `/api/equity/getequitydeliverydata` | `data:[ row ]` |

Row:
```json
{ "stFetchDate":"15-06-2026", "inOpen":1372,"inHigh":1378,"inLow":1360.6,"inClose":1368.3,
  "inDeliveryPercentage":69.39, "inDeliveryQuantity":5220325, "inTotalTradedQuantity":7522828,
  "inCloseChange":0.88, "inNoOfAnnouncement":0 }
```

## Replication notes (→ ArthaYantra)
- Per stock+period: daily OHLC + delivery%/qty + traded qty + announcement count.
- `p-table`; % Delivery is the key column (high = conviction); announcement count links to the Announcement page.

## Screenshot
ss_5305o4vez (AXISBANK 15-day delivery table, announcement badges).
