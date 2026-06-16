# Market Holidays — `/app/market-holidays`

**Purpose:** reference list of NSE trading holidays for the year. Simple sortable table.
Sub-tabs: `Market Holidays | Tool`.

## Layout
```
sub-tabs: [ Market Holidays ] [ Tool ]   ;  ticker strip
                         Market Holidays            (centered title)
┌ table ─────────────────────────────────────────────────────────────────────────┐
│ # | Date ⇅ | Day ⇅ | Description ⇅ | Validity ⇅                                 │
│ 1   15-Jan-2026  Thursday  Municipal Corporation Election - Maharashtra  [Passed]│
│ …                                                                                 │
│ 20  25-Dec-2026  Friday    Christmas                                     [Coming] │
└──────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[20▾]                                  1 - 20 of 20   ‹ Previous  Next ›
```

## Components
| Column | Header | API field | Render |
|---|---|---|---|
| # | (row no.) | — | index |
| Date | `Date` (sortable ⇅) | `stDate` (e.g. "2026-01-15", ISO format) | text |
| Day | `Day` (sortable ⇅) | `stDay` (e.g. "Thursday   " — trailing spaces in API response; trim before display) | text |
| Description | `Description` (sortable ⇅) | `stDescription` (e.g. "Republic Day") | text |
| Validity | `Validity` (sortable ⇅) | *(computed)* | **badge: red `Passed`** if date < today, **green `Coming`** if date ≥ today |

- All header columns sortable (⇅ icon).
- Pagination: Rows per page (20) · `1 - 20 of 20` · Previous / Next.
- Validity badge is the only color cue: `badge-danger` (Passed) / `badge-success` (Coming).

## Data source
`POST /api/market-view/getmarketholidays` (empty body) →
```json
{ "status":"success","msg":"Market holidays fetched successfully.",
  "data":[ {"stDate":"2026-01-26","stDay":"Monday   ","stDescription":"Republic Day"} ] }
```
`stDate` is ISO `YYYY-MM-DD`. `stDay` has trailing spaces (pad to 9 chars) — trim client-side.
`Validity` is derived client-side (compare `stDate` to current date).

## Replication notes (→ ArthaYantra)
- We already have `libs/market-calendar` (NSE holiday calendar, current year). Surface it as a
  PrimeNG `p-table` (sortable) with a computed Passed/Coming `p-tag`.
- Note: oipulse's calendar covers the full year incl future; our `market-calendar` covers CURRENT year only.

## Screenshot
ss_2640juqbr (2026 holiday list, Passed/Coming badges).
