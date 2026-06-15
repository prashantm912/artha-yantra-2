# Sector Stats — `/app/equity/sector-stats`

**Purpose:** sector performance overview — a bar chart of every sector index's % change plus per-sector
constituent tables with breadth (Up/Total/Down). Sub-tabs: `Sector Stats | Equity`.

## Layout
```
sub-tabs: [ Sector Stats ] [ Equity ]   ;  ticker strip
filter: Mode(Live/Hist)  Select Date[📅]  [Go]                         Data Auto-updated At: 15-06-2026 15:30:00
                              Sector Stats   (centered bar chart)
┌ bar chart ─────────────────────────────────────────────────────────────────────────────────────────┐
│ one bar per sector (NIFTY PHARMA … NIFTY AUTO, NIFTY CONSR DURBL), value = % change                   │
│ green = up / red = down, sorted ascending; legend ● Change %                                          │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
┌ NIFTY 50  23856.55 (+224.80/0.95%) ───────┐ ┌ NIFTY MID SELECT  … ──────────────────────┐
│ Up:34  Total:50  Down:16  [green/red bar]  │ │ Up:19 Total:25 Down:6  [breadth bar]      │
│ Name | Chart | Chng % | Close | Prev. Close│ │ …                                          │
├ NIFTY BANK  57163.70 (+358.20/0.63%) ──────┤ │                                            │
│ Up:13 Total:14 Down:1  …                    │ │                                            │
└─────────────────────────────────────────────┘ └────────────────────────────────────────────┘
```

## Components
| Component | Type | Detail |
|---|---|---|
| Sector Stats bar chart | ECharts bar | one bar/sector index; value `inChngPerc`; green up / red down; sorted; toolbox |
| Per-sector tables | cards w/ table | one per sector index (NIFTY 50, NIFTY BANK, NIFTY MID SELECT, …) |
| Table header | text + **breadth bar** | `<index> <value> (chg/%)` + `Up:N Total:T Down:D` with a green/red proportion bar |
| Table columns | — | Name · Chart (icon) · Chng % (green/red) · Close · Prev. Close — sortable, sorted by Chng% desc |

## Data source / API
`POST /api/equity/getindexconstituentswithdata` →
```json
{ "data":[ { "stIndexName":"NIFTY 50", "inClose":23856.55, "inPrevClose":23631.75, "inChngPerc":"0.95",
             "objconstituents": { "LT": {"inClose":4171,"inWeight":4.x, …}, … } } ] }
```
One entry per sector index with `inChngPerc` (→ bar chart) and `objconstituents` map (→ that sector's table + Up/Total/Down breadth by constituent sign).

## Replication notes (→ ArthaYantra)
- One endpoint → all sector indices + their constituents. Bar chart of `inChngPerc`; a card+table per sector with breadth bar.
- Breadth = count constituents with chng>0 vs <0.

## Screenshot
ss_5994c46qf (sector bar chart + NIFTY 50 / BANK / MID SELECT constituent tables with breadth).
