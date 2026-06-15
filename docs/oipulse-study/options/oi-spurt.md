# Options OI Spurt — `/app/options-analysis/oi-spurt`

**Purpose:** option-strike OI scanner. Buckets every strike (CE/PE) of an expiry into the four
OI-action categories ranked by OI change — surfaces which strikes are seeing fresh writing/unwinding.
Sub-tabs: `OI Spurt | Options Analysis`.

## Layout
```
sub-tabs: [ OI Spurt ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Date[30-Jun-2026▾]  Search[…]  [Go]
        Underlying: NIFTY BANK at 57198.8, Chg 384.00 (0.68%) as on 15 Jun 2026 23:45 IST
┌ Long Build Up ───────────────────────────┐ ┌ Short Build Up ──────────────────────────┐
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
┌ Short Unwinding ─────────────────────────┐ ┌ Long Unwinding ──────────────────────────┐
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
```
2×2 grid; four categories (note options labels: **Long Build Up / Short Build Up / Short Unwinding / Long Unwinding** — "Short Unwinding" = short covering).

## Per-quadrant table columns
| Column | Source | Render |
|---|---|---|
| Strike | `inStrikePrice` | |
| Type | `stOptionsType` (`CE`/`PE`) | |
| LTP | `inNewClose` | |
| Prev. Close | `inOldClose` | |
| % Chng. LTP | computed | green/red |
| % Chng. OI | computed | green/red |
| New OI | `inNewOi` | |
| Old OI | `inOldOi` | |
| OI Chng. | `inNewOi − inOldOi` | green/red |
| Volume | `inTradedVolume` | |

Sortable; each table paginated (7 rows). Counts seen: Long Build Up 31 · Short Build Up 65 · Short Unwinding 58 · Long Unwinding 132. Search filters by strike.

## Data source / API
`POST /api/options/getoispurtdataforselectedoptions` →
```json
{ "data": {
   "data": [ { "inStrikePrice":"43000","stOptionsType":"PE","stExpiryDate":"260630",
               "inOldOi":"115770","inOldClose":"3.5","inNewOi":"114480","inNewClose":"3.6",
               "inTradedVolume":"23340","stFetchTime":"23:45:00","stFetchDate":"2026-06-15T..." } ],  // 286 strikes (CE+PE)
   "underLyingAssetData": {"stUnderLyingAsset":"NIFTY BANK","inLtp":57198.8, ...} } }
```
Client computes %ΔLTP, %ΔOI, ΔOI; buckets by sign(LTP)×sign(OI) (same matrix, options labels).

## Replication notes (→ ArthaYantra)
- Same as Futures OI Spurt but the universe = all strikes (CE+PE) of one expiry; row carries old/new OI+close.
- 4 sortable `p-table`s; Search filters strike; underlying header from `underLyingAssetData`.

## Screenshot
ss_5965jlxgl (BANKNIFTY 30-Jun strikes: Long/Short Build Up, Short/Long Unwinding).
