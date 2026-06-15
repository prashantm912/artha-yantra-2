# Options Chain — `/app/options-analysis/options-chain`

**Purpose:** the full option chain — every strike's Call and Put OI / IV / LTP / changes / interpretation
with per-strike PCR, ATM-centered. The dense master view of positioning across all strikes.
Sub-tabs: `Options Chain | Options Analysis`.

## Layout
```
sub-tabs: [ Options Chain ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Expiry Date[30-Jun-2026▾]  Interval[Full Day▾]  [Go]  [Column Setting]
info: INDIA VIX: 14.35, Chg -0.37 (-2.48%)   |   Total PCR: 1.036 (-0.060)   |   Underlying: NIFTY BANK at 57198.8, Chg 384.00 (0.68%) as on 15 Jun 2026 23:45 IST
┌ chain table (all strikes as rows; CALL | STRIKE | PUT) ───────────────────────────────────────────────┐
│  ◄──────── CALL ────────►                         ◄──────── PUT ────────►                              │
│ OI Int | OI% | OI | OI Chng | IV | LTP | LTP% | LTP Chg | STRIKE | LTP Chg | LTP% | LTP | IV |         │
│   OI Chng | OI | OI% | OI Int | PCR Ratio                                                              │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Select Name | select | underlying |
| Select Date | date picker | day |
| Expiry Date | select | expiry |
| Interval | select | `Full Day` (or intraday snapshots) |
| Go | button (red) | fetch |
| Column Setting | button (red outline) | show/hide chain columns |

## Header strip
- **INDIA VIX**: `inLtp` + change (from `indiaVixData`).
- **Total PCR**: total put OI / total call OI (+ change).
- **Underlying**: `underLyingAssetData` (NIFTY BANK spot, change, timestamp).

## Columns (mirrored Call | Strike | Put)
| Side | Column | Source / computed |
|---|---|---|
| Call | OI Int. | computed `inOiInterpretation` → badge (S.C/L.B/…) |
| Call | OI % | computed (call OI share / change %) — green/red |
| Call | OI | `inNewOi` — **inline horizontal data-bar** (length ∝ OI; red on call side) |
| Call | OI Chng. | `inNewOi − inOldOi` — large adds highlighted (red box) |
| Call | IV | `inNewIv` |
| Call | LTP | `inNewClose` |
| Call | LTP % | computed |
| Call | LTP Chg | `inNewClose − inOldClose` (green/red) |
| — | **Strike** | `inStrikePrice` (centered; ATM band highlighted cream/yellow) |
| Put | LTP Chg / LTP% / LTP / IV / OI Chng / OI / OI% / OI Int. | mirror of Call (PE rows); OI data-bar green on put side |
| Put | **PCR Ratio** | put OI / call OI for that strike |

### Visual cues
- **ATM band**: strikes around spot highlighted with cream/yellow row background.
- **ITM tint**: in-the-money call rows (low strikes) and ITM put rows lightly tinted.
- **Inline OI data-bars** inside OI cells (red=call, green=put), length ∝ magnitude → instant OI profile.
- OI Chng. big additions boxed (green = put writing, red = call writing).
- OI Int. badges: 4-state enum; OI% green/red.

## Data source / API
Discovery: `getavailableoptionsdata`, `getselectedoptionsdate`, `getoptionsdataexpirydate` (as elsewhere).
Main: `POST /api/options/getoptionschaindataforselectedoptions` →
```json
{ "data": {
   "data": [ {
      "stOptionsType":"CE", "inStrikePrice":54700,
      "inNewClose":"2679","inNewDayHigh":"3421","inNewDayLow":"2650","inNewDayOpen":"3099.95",
      "inNewIv":"0","inNewOi":"49380","inTradedVolume":"18360",
      "inOldClose":"2392.4","inOldOi":"54480","inOldIv":"12.89","inOldTradedVolume":"27360" } ],   // 311 CE+PE rows
   "indiaVixData": {"stUnderLyingAsset":"INDIA VIX","stDateTime":"...","inLtp":14.3525,"inDayHigh":..,"inDayLow":..,"inDayOpen":..},
   "underLyingAssetData": {"stUnderLyingAsset":"NIFTY BANK","inLtp":57198.8,"inDayHigh":..,"inDayLow":..,"inDayOpen":..}
} }
```
Each strike has **New** (current) + **Old** (reference) close/OI/IV/volume → client computes all Chng/%/interpretation/PCR. CE & PE rows pivoted by `inStrikePrice`.

## Replication notes (→ ArthaYantra)
- One endpoint → strikes (CE+PE New/Old) + VIX + underlying. Pivot CE/PE by strike into the mirrored chain.
- Compute OI%/LTP%/chng/interp/PCR client-side; inline OI bars via `p-table` cell template (CSS width ∝ OI/maxOI).
- ATM highlight = nearest strike to underlying `inLtp`; ITM tint by moneyness; Column Setting = column toggle.

## Screenshot
ss_5693wz43d (BANKNIFTY 30-Jun chain, ATM band, OI data-bars, PCR column).
