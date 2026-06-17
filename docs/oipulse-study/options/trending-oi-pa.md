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

## Data source / API (CONFIRMED live)
Same request body as Trending OI; only the endpoint changes:
```
POST /api/trending-oi-static/gettrendingoiforselectedstrikepriceswithclose
Body: {
  "stSelectedAsset": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "selectedStrikePrices": [],  // [] on initial load → server auto-selects 15 near-ATM strikes
  "stSelectedModeOfData": "live"
}
```
Table row schema (confirmed from live data):
```json
{
  "stFetchDate": "2026-06-16", "stTime": "13:43:00", "stNewTime": "16-06-2026 13:45",
  "stDataFetchType": "IM",
  "inClose": 57219.15, "inHigh": 57233.4, "inLow": 57191.2,
  "CE": 893460, "PE": 500550,
  "CE_CLOSE": 9511.2, "PE_CLOSE": 11944.6,
  "CE_CLOSE_CHANGE": -1150, "PE_CLOSE_CHANGE": -758.7, "CE_PE_CLOSE_CHANGE": -1908.7,
  "inDifferenceInOi": -392910, "inDifferenceInOiDirection": 1,
  "inChangeInDifferenceInOi": 6750, "inChangeInDifferenceInOiPerc": 1.69,
  "inNetPcr": "0.56", "inSentimentPercentage": "-78.50",
  "inDayHighLowBreak": 0, "isDayHighDiffInOi": 0, "isDayLowDiffInOi": 0,
  "inBrokenDayHigh": 0, "inBrokenDayLow": 0
}
```
`CE_CLOSE` = sum of CE premiums across selected strikes. No `LTP` column (unlike Trending OI which has `inClose` as LTP column).

## Interpretation (how to trade)
- Same 5-level sentiment as Trending OI, plus a "premium confirms OI" rule: OI up + premium up confirms the writing; OI up + premium down diverges (read it cautiously).

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Extend Trending OI aggregation to also sum CE/PE premium per interval; add Total/Δ premium + straddle-change columns.
- Same sentiment logic; PA columns add confirmation (OI up + premium up vs OI up + premium down).

## Screenshot
ss_2114vqqfz (BANKNIFTY band, OI + premium columns, CE+PE Ltp Chng, Bearish).
