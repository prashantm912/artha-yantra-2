# Trending OI - PA — `/app/options-analysis/trending-oi-with-pa`

**Purpose:** Trending OI **with Price Action** — same aggregated Call/Put OI trend across a strike band,
plus the premium (LTP) columns so you see whether OI shifts are confirmed by premium expansion/contraction.
Sub-tabs: `Trending OI - PA | Options Analysis`. (See `trending-oi.md` for shared filter/structure.)

## Layout / columns (adds premium columns vs Trending OI)
```
filter: same as Trending OI (Mode, Name, Date, Expiry, Interval, Go, Change Strike Prices, Show Graph View, Show positional Data)
Selected Strike Prices: 57100 … 58500
table columns:
 Date | Time | Day H/L Break | Chng. In Call OI | Chng. In Put OI | Diff. in OI | Direction of chng.(% in badge) |
 Chng. In Direction | Total Call Ltp | Call Ltp chng. | CE + PE Ltp Chng. | Put Ltp chng. | Total Put Ltp |
 Net PCR | Day High/Low Diff. in OI | Sentiment
```

| Column | Source / computed | Notes |
|---|---|---|
| (OI columns) | as Trending OI | Chng In Call/Put OI, Diff in OI, Direction, Chng In Direction, Net PCR, Sentiment |
| Direction of chng. | sign + % | badge: green ↑ / red ↓ with % inside |
| **Total Call Ltp** | sum CE premium (`CE_CLOSE`) across band | |
| **Call Ltp chng.** | Δ total CE premium | green/red |
| **CE + PE Ltp Chng.** | Δ (CE+PE) premium = **straddle** premium change | green/red — key combined PA signal |
| **Put Ltp chng.** | Δ total PE premium | green/red |
| **Total Put Ltp** | sum PE premium (`PE_CLOSE`) | |
| Sentiment | derived | Bearish (red) / Bullish (green) |

## Data source / API
Same `trending-oi-static` discovery chain; main endpoint is the **`…withclose`** variant:
`POST /api/trending-oi-static/gettrendingoiforselectedstrikepriceswithclose` →
```json
{ "data":[ { "stFetchDate":"2026-06-12","stTime":"23:50:00",
             "inClose":0,"inHigh":0,"inLow":0,
             "objOiData":[ {"CE":2059260,"CE_CLOSE":6905.5}, {"PE":476130,"PE_CLOSE":19049.95} ],
             "totalCeOi":…, "totalPeOi":… } ],
  "listOfStrikePrice":[…], "underLyingAssetData":{…}, "oiSnapshotData":{…} }
```
Difference vs Trending OI: `objOiData` carries `CE_CLOSE`/`PE_CLOSE` (premium totals) → the LTP columns;
all premium chng + straddle (CE+PE) chng computed over consecutive rows.

## Replication notes (→ ArthaYantra)
- Extend Trending OI aggregation to also sum CE/PE premium per interval; add Total/Δ premium + straddle-change columns.
- Same sentiment logic; PA columns add confirmation (OI up + premium up vs OI up + premium down).

## Screenshot
ss_2114vqqfz (BANKNIFTY band, OI + premium columns, CE+PE Ltp Chng, Bearish).
