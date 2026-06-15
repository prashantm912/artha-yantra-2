# Index Contribution — `/app/index-contribution`

**Purpose:** how much each constituent contributes to the index's point move (weighted). Which stocks
are pushing the index up vs dragging it down, in index points. Sub-tabs: `Index Contribution | Tool`.

## Layout
```
sub-tabs: [ Index Contribution ] [ Tool ]   ;  ticker strip
filter: Mode(Live/Hist)  Index[Nifty 50▾]  Date[📅]  Search[…]  [Go]  ☐ Show Graph View
        Underlying: NIFTY 50 at 23853.9, Chg 231.00 (0.98%) …
┌ Advances: 34 / Points: 275.11 ────────────┐ ┌ Decline: 16 / Points: -42.49 ────────────┐
│ Name | Point | LTP                         │ │ Name | Point | LTP                        │
│ LT     +31.19   4169.80 (2.98%)            │ │ ICICIBANK  -19.26   1327.60 (-0.98%)      │
│ …                                          │ │ …                                          │
└─────────────────────────────────────────────┘ └────────────────────────────────────────────┘
Note: Index contribution calculation involves complex calculations … approximate values depending on … weightage.
```

## Filter / header
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Index | select | `Nifty 50` / Bank Nifty / others (`getlistofindexes`) |
| Date | date picker | day |
| Search | text | locate constituent |
| Go | button (red) | fetch |
| Show Graph View | checkbox | bar-chart version of contributions |
| Header | text | index LTP + net change |

## Tables (Advances | Declines)
| Column | Source / computed | Render |
|---|---|---|
| Name | `stSymbolName` (constituent) | |
| Point | computed: `inWeightage × (inClose − inPrevDayClose)` normalized to index points | green (+) advances / red (−) declines |
| LTP | `inClose` + `(price %chg)` | green/red % |

Header per table: count + summed points (Advances 34 / +275.11 ; Decline 16 / −42.49).

## Data source / API (`index-contribution`)
| Call | Response |
|---|---|
| `/api/index-contribution/getlistofindexes` | index list |
| `/api/index-contribution/getselectedindexdate` | dates |
| `/api/index-contribution/getselectedindexalldata` | `data:[ row ]` (index row first, then constituents) |

Row:
```json
{ "stSymbolName":"NIFTY 50", "inPrevDayClose":23622.9, "inDayOpen":23984.85,
  "inDayHigh":24011.4,"inDayLow":23817.8,"inClose":23853.9, "inWeightage":null, "stDateTime":"2026-06-15T23:45:00" }
```
First row = the index (weightage null); constituents carry `inWeightage`. Point contribution = weightage × constituent move; split into Advances/Declines by sign.

## Replication notes (→ ArthaYantra)
- Need constituent weightages per index; compute point contribution = weight × (close − prevClose) scaled to index.
- Two ranked `p-table`s (advances/declines) + optional bar-chart view; header totals.

## Screenshot
ss_52248r77w (NIFTY 50 contributions: Advances 34 / Decline 16).
