# Announcement — `/app/equity/announcement`

**Purpose:** corporate announcements / exchange filings feed — searchable by symbol and date range,
with the full text and a PDF attachment link. No sub-tabs.

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

## Vue component state (confirmed)
```
maxAvailableDate ("2026-06-16"),
stSymbolNameString (null),       // null = all symbols; set to a symbol for per-stock filter
disableRefreshDataButton,
suggestionList,                   // autocomplete symbol list
columns ([]),                     // vue-good-table column defs
pagination,                       // server-side pagination object (see below)
totalRecords,                     // total matching records (~2601 for full date range)
items ([]),                       // 10 rows per page
doneTypingInterval
```

Pagination object (confirmed):
```json
{
  "columnFilters": {
    "stExchange": "NSE",
    "stSymbolName": null,
    "stStartDate": "2026-06-09",
    "stEndDate": "2026-06-16"
  },
  "search": "",
  "sort": "stFetchDate desc",
  "status": null,
  "page": 1,
  "perPage": 10
}
```
Server-side pagination — each page change sends the `pagination` object to the API.

## Columns (vue-good-table — confirmed)
```json
[
  {"label":"Date",       "field":"stFetchDate", "sortable":false, "width":"70px"},
  {"label":"Time",       "field":"stFetchTime", "sortable":false, "width":"70px"},
  {"label":"Name",       "field":"stSymbolName","sortable":false, "width":"70px"},
  {"label":"Subject",    "field":"stSubject",   "sortable":false, "width":"100px"},
  {"label":"Detail",     "field":"stFileText",  "sortable":false, "width":"350px"},
  {"label":"Attachment", "field":"stFileLink",  "sortable":false, "width":"60px"}
]
```

## Data source / API
Namespace: `equity/announcement`

`POST /api/equity/announcement/getlistofannouncementforsymbol` — request = `pagination` object:
```json
{
  "columnFilters": {"stExchange":"NSE","stSymbolName":null,"stStartDate":"2026-06-09","stEndDate":"2026-06-16"},
  "search": "",
  "sort": "stFetchDate desc",
  "page": 1,
  "perPage": 10
}
```

Confirmed row (BIRLACORPN):
```json
{
  "stFetchDate": "2026-06-16",
  "stFetchTime": "14:48:50",
  "stSymbolName": "BIRLACORPN",
  "stSubject": "Copy of Newspaper Publication",
  "stFileLink": "https://nsearchives.nseindia.com/corporate/BIRLACORP1_16062026144822_SE_Newspaper_Advertisement_16062026.pdf",
  "stFileText": "Birla Corporation Limited has informed the Exchange about Copy of Newspaper Publication regarding opening of Special Window for re-lodgement of transfer and dematerialisation of physical shares."
}
```

## Replication notes (→ ArthaYantra)
- Server-side pagination — send full `pagination` object as request body; API returns page of results.
- `stExchange:"NSE"` hardcoded in `columnFilters`; `stSymbolName:null` = all stocks.
- `p-table` with wide Detail column; Attachment = external PDF link (open in new tab).
- Default date range: last 7 days (stStartDate = today−7, stEndDate = today).

## Screenshot
ss_068666sgq (announcements feed, Subject categories, Open File links).
