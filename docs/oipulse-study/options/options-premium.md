# Options Premium — `/app/options-analysis/option-premium`

**Purpose:** compare Call vs Put premium across strikes (the premium "smile"/decay profile around ATM).
Sub-tabs: `Options Premium | Options Analysis`.

## Layout
```
sub-tabs: [ Options Premium ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Date[30-Jun-2026▾]  Show Strikes[Near ATM 10 Strikes▾]  [Go]  ☐ Show LTP
        Underlying: NIFTY BANK at 57198.80 …
                     Individual Options Premium Left   (centered)
┌ grouped bar chart ────────────────────────────────────────────────────────────────────────────────┐
│ x: strikes 56100 … 57100(ATM) … 58100   y: premium (0–800)                                           │
│ green bar = Call Premium, red bar = Put Premium (paired per strike)                                  │
│ ATM marker (green ▲ + red ▲)   legend: ● Call Premium  ● Put Premium   watermark OiPulse             │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | 9 Index + 211 Stocks | |
| Date | date picker | |
| Expiry Date | YYMMDD | |
| Show Strikes | `10`(Near ATM 10 Strikes), `15`(Near ATM 15 Strikes), `25`(Near ATM 25 Strikes), `"All"`(All Strikes) | default 10 |
| Go | button | sends `stSelectedNoOfData` as int (or "All") |
| Show LTP | checkbox | `showLtp` state; annotates bars with premium LTP values |

## Vue component state
```
selectedOptions, selectedAvailableDate, selectedAvailableExpiryDate, selectedNoOfData,
availableDate, availableExpiryDate, availableNoOfData, availableModeOfData,
underLyingAssetData, oiData, showLtp, tempChartData, chartData, strikePriceIndex, socketSubscribedEvents
```

`chartData` structure:
```json
{
  "xAxisData": ["56200","56300",...,"58100"],
  "xAxisCallData": [1385.0, ...],
  "xAxisPutData": [323.05, ...],
  "xAxisMarkLine": {"xAxis":"57200"},
  "xAxisCallMarkPoint": [...],
  "xAxisPutMarkPoint": [...]
}
```

## Socket subscriptions
- `OD_PREMIUM_BANKNIFTY_260630` — live premium updates for all strikes in expiry
- `EQUITY_UNDERLYING_DATA_NIFTY BANK` — underlying LTP

## Chart
ECharts grouped bar: per strike a Call bar (green) + Put bar (red); x = strike ladder centered on ATM
(marker at spot); y = premium. Reveals where calls vs puts are richer and the ATM crossover.

## Data source / API
```
POST /api/options/getoptionspremiumdataforselectedoptions
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "stSelectedNoOfData": 10,
  "stSelectedModeOfData": "live"
}
Response: {
  "status": "success",
  "data": {
    "data": [
      { "stFetchTime": "13:40:00", "inStrikePrice": "56200", "stOptionsType": "PE", "inNewClose": "323.05" },
      { "stFetchTime": "13:29:00", "inStrikePrice": "56200", "stOptionsType": "CE", "inNewClose": "1385" },
      ...
    ],
    "underLyingAssetData": { "stUnderLyingAsset": "NIFTY BANK", "inLtp": 57200, ... }
  }
}
```
`inNewClose` = option current close/LTP. 4 fields per row. No OHLC. CE/PE interleaved per strike.

## Interpretation (how to trade)
- **Confirmed live 2026-06-18 (V9):** the premium bars are **extrinsic value (LTP − intrinsic), NOT raw LTP**.
  Evidence: the bars peak at ATM and fall to near-zero at the wings, and the **deep-ITM 76200 CE bar goes slightly
  NEGATIVE** — impossible for raw LTP. The **"Show LTP" toggle switches the bars to raw LTP**. (An earlier draft
  treated the bars as raw LTP — that is wrong.) Call Premium = green, Put Premium = red. See
  [Phase B findings](../PHASE-B-FINDINGS.md) (V9).
- LTP = intrinsic + extrinsic; OTM options are pure extrinsic; the chart plots extrinsic value (premium/discount).
  Extrinsic ≈ the market-priced probability ("Risk Value").
- Strike selection (for buying): avoid OTM (fragile premium), prefer ATM/ITM. Use this chart to find an ITM strike that is relatively *cheap* vs its neighbours (higher leverage). The "Show LTP" toggle overlays each strike's LTP for the comparison.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- `ay-echart` grouped bar: Call vs Put premium per strike, ATM markLine, strike-window selector.
- One fetch (CE+PE close per strike); ATM = nearest strike to underlying.

## Screenshot
ss_0066t58v8 (BANKNIFTY premium bars 56100–58100, ATM 57100, Call green / Put red).
