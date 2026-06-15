# Participant wise OI — `/app/fii-dii/participant-wise-oi`

**Purpose:** SEBI **participant-wise open interest** (No. of Contracts) — long/short positions of each
participant class (FII, Pro, DII, Client) across all F&O segments, with changes and a bullish/bearish
interpretation per row. The key institutional-positioning report. Sub-tabs: `Participant Wise Oi | FII & DII Activity`.

## Layout
```
sub-tabs: [ Participant Wise Oi ] [ FII & DII Activity ]   ;  ticker strip
filter: Date[Mon, Jun 15 2026 📅]
        Participant Wise OI (No. Of Contracts)   (title)
┌ grouped table ────────────────────────────────────────────────────────────────────────────────────┐
│ Type | Long | Short | Total Diff. | Chng. In Long | Chng. In Short | Chng. In Total | Interpretation │
│ ── FII ──    Future Index | Future Stock | Option Index Call | Option Index Put | Option Stock Call | Option Stock Put │
│ ── Pro ──    (same 6 rows)                                                                            │
│ ── DII ──    (same 6 rows)                                                                            │
│ ── Client ── (same 6 rows)                                                                            │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
Grouped by participant (FII/Pro/DII/Client); 6 instrument rows each.

## Columns
| Column | Source / computed | Render |
|---|---|---|
| Type | segment label (Future Index, Future Stock, Option Index Call/Put, Option Stock Call/Put) | row label under group |
| Long | `in<Seg>Long` (+ % of total) | count + (%) |
| Short | `in<Seg>Short` (+ %) | count + (%) |
| Total Diff. | computed `Long − Short` | green (+net long) / red (−net short) |
| Chng. In Long | `in<Seg>LongChng` | |
| Chng. In Short | `in<Seg>ShortChng` | |
| Chng. In Total | computed `ΔLong − ΔShort` | green/red **badge** |
| Interpretation | derived (net + change bias) | **`Bullish` (green) / `Bearish` (red) badge** |

## Data source / API
| Call | Response |
|---|---|
| `/api/fii-dii/getparticipantwiseoidate` | dates (90 days) |
| `/api/fii-dii/getparticipantwiseoidata` | `data:[ <category row> ]` (5 categories) |

Category row (one per FII/Pro/DII/Client/…):
```json
{ "stCategoryType":"FII","dtDate":"…",
  "inFutIndexLong":…,"inFutIndexShort":…, "inFutStockLong":…,"inFutStockShort":…,
  "inOptIndexCallLong":…,"inOptIndexCallShort":…, "inOptIndexPutLong":…,"inOptIndexPutShort":…,
  "inOptStockCallLong":…,"inOptStockCallShort":…, "inOptStockPutLong":…,"inOptStockPutShort":…,
  "inFutIndexLongChng":…, …Chng for every segment… }
```
UI pivots each category into 6 segment rows; %/Total Diff/Chng Total/Interpretation computed client-side.

## Replication notes (→ ArthaYantra)
- One endpoint = wide row per participant with long/short(+chng) for 6 segments. Pivot to grouped table (participant → 6 rows).
- Interpretation: net long & rising long ⇒ Bullish; net short & rising short ⇒ Bearish (per segment).

## Screenshot
ss_0353sjq0s (FII/Pro/DII/Client × 6 segments, Long/Short, Bullish/Bearish interpretation).
