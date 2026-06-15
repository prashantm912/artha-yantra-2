# Announcement — `/app/equity/announcement`

**Purpose:** corporate announcements / exchange filings feed — searchable by symbol and date range,
with the full text and a PDF attachment link. Sub-tabs: `Announcement | Equity`.

## Layout
```
sub-tabs: [ Announcement ] [ Equity ]   ;  ticker strip
filter: Search[Please write name of equity symbol…]  Start Date[Tue, Jun 09 2026 📅]  End Date[Tue, Jun 16 2026 📅]  [Go]
                              Company Announcement Data   (centered)
┌ table ──────────────────────────────────────────────────────────────────────────────────────────────┐
│ Date | Time | Name | Subject | Detail | Attachment                                                    │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[10▾]                                    1 - 10 of 2601
```

## Filter
| Control | Type | Notes |
|---|---|---|
| Search | text | equity symbol (filter announcements to one company) |
| Start Date / End Date | date pickers | date-range window |
| Go | button (red) | fetch |

## Columns
| Column | Source | Render |
|---|---|---|
| Date | `stFetchDate` | |
| Time | `stFetchTime` | HH:MM:SS |
| Name | `stSymbolName` | company |
| Subject | `stSubject` | category (Press Release, Resignation of Director/KMP/SMP, Acquisition, Analysts/Institutional Investor Meet/Con. Call Updates, General Updates, Updates, …) |
| Detail | `stFileText` | full announcement text (wraps) |
| Attachment | `stFileLink` | **`Open File`** link → PDF |

Newest first; ~2601 rows over the range; paginated 10.

## Data source / API
`POST /api/equity/announcement/getlistofannouncementforsymbol` (req: symbol + start/end date + paging) →
```json
{ "data":[ { "stFetchDate":"15-Jun-2026","stFetchTime":"23:43:44","stSymbolName":"ARVSMART",
             "stSubject":"Press Release","stFileText":"Arvind SmartSpaces Limited has informed …",
             "stFileLink":"https://…pdf" } ] }
```

## Replication notes (→ ArthaYantra)
- Corporate-filings feed (NSE/BSE) with symbol + date-range filter; `p-table` with text Detail + external PDF link.
- Links to Delivery Data's announcement count. Treat `stFileLink` as an external resource (open in new tab).

## Screenshot
ss_068666sgq (announcements feed, Subject categories, Open File links).
