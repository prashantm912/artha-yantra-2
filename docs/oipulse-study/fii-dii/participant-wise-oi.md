# Participant wise OI — `/app/fii-dii/participant-wise-oi`

**Purpose:** SEBI **participant-wise open interest** (No. of Contracts) — long/short positions of each
participant class (FII, Pro, DII, Client) across all F&O segments, with changes and a bullish/bearish
interpretation per row. The key institutional-positioning report. No sub-tabs.

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

## Vue component state (confirmed)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedAvailableDate ("2026-06-15"),
availableDate,
tableData ([]),      // 4 grouped participant rows (mode:"span") for vue-good-table expandable
oiData ([]),         // raw API rows
totalOiData ([]),    // total/summary rows
columns ([]),        // vue-good-table column defs
pagination,
totalRecords
```

## tableData structure (confirmed)
Vue-good-table `mode:"span"` grouped rows — 4 parent rows (FII/Pro/DII/Client), each with `children`:
```json
{
  "mode": "span",
  "label": "FII",
  "html": false,
  "children": [
    {"stSegment":"Future Index",      "inLong":"41074",    "inShort":"282315",  "inTotal":-241241,"inLongChng":"1103",   "inShortChng":"-1279", "inTotalChng":2382,   "stInterpretation":"..."},
    {"stSegment":"Future Stock",      "inLong":"4135474",  "inShort":"3344880", "inTotal":790594, "inLongChng":"12483",  "inShortChng":"5789",  "inTotalChng":6694},
    {"stSegment":"Option Index Call", "inLong":"672396",   "inShort":"924723",  "inTotal":-252327,"inLongChng":"48978",  "inShortChng":"92285", "inTotalChng":-43307},
    {"stSegment":"Option Index Put",  "inLong":"1145247",  "inShort":"600061",  "inTotal":545186, "inLongChng":"-48817","inShortChng":"-51391","inTotalChng":2574},
    {"stSegment":"Option Stock Call", "inLong":"236813",   "inShort":"378538",  "inTotal":-141725,"inLongChng":"25161",  "inShortChng":"19195", "inTotalChng":5966},
    {"stSegment":"Option Stock Put",  ...}
  ]
}
```

## Columns (vue-good-table — confirmed)
```
Type (stSegment) · Long (inLong) · Short (inShort) · Total Diff. (inTotal) ·
Chng. In Long (inLongChng) · Chng. In Short (inShortChng) · Chng. In Total (inTotalChng) ·
Interpretation (stInterpretation)
```
`inLong`/`inShort`/`inLongChng`/`inShortChng` are STRINGS in the children rows; `inTotal`/`inTotalChng` are numbers.

## Data source / API
| Call | Namespace | Request | Response |
|---|---|---|---|
| `getparticipantwiseoidate` | `fii-dii` | `{}` | available dates |
| `getparticipantwiseoidata` | `fii-dii` | `{stSelectedAvailableDate}` | raw participant rows |

API returns 4 wide rows (one per participant: FII/Pro/DII/Client). Vue component transforms them into `tableData` (span-grouped) + `oiData` + `totalOiData`. Segment breakdown and `stInterpretation` may be computed client-side from the raw long/short values.

## Interpretation (how to trade)
- Net OI = Long − Short per participant; total longs always equal total shorts, so read who is net-long vs net-short.
- Smart-money vs retail: FII / DII / Pro are smart money, Client is retail. Align with institutions and fade clients; participant importance order is FII > Pro > DII > Client. Valid mainly for the next morning and can flip on global cues.
- Four change-in-OI regimes from ΔLong / ΔShort: Aggressively Bullish (Long↑ & Short↓), Cautiously Bullish, Aggressively Bearish (Short↑ & Long↓), Cautiously Bearish.
- The FII Index-Future Long% / Short% is the headline bias; writer rules: more Put selling = bullish, more Call selling = bearish; tally the per-segment Bullish/Bearish (majority wins).

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- API returns wide rows per participant; pivot into grouped table: 4 parent rows × 6 segment children each.
- Interpretation: net long & rising long ⇒ Bullish; net short & rising short ⇒ Bearish (per segment).
- `inTotal = inLong - inShort` (signed); `inTotalChng = inLongChng - inShortChng`.

## Screenshot
ss_0353sjq0s (FII/Pro/DII/Client × 6 segments, Long/Short, Bullish/Bearish interpretation).
