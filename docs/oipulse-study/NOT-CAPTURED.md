# Not Captured — items inaccessible or unconfirmed

Items that could NOT be fully confirmed during the live Claude-in-Chrome study.
Captured: 2026-06-16. Study was against an authenticated session (Go Annual Combo plan).
**Updated 2026-06-16 (session 2):** Items 1, 2, 5, 7 resolved via XHR-interception + onAny listener.

---

## 1. World Indices API raw response schema ✅ CONFIRMED

**Page:** `/app/world-indices`

**Confirmed via XHR interception (Refresh button triggered a fresh call):**

```
POST /api/heatmap/getworldindicesdata
Body: {}   (empty object — no params)

Response:
{
  "status": "success",
  "msg": "Data fetched successfully.",
  "data": [
    {
      "stFetchDate": "2026-06-16",
      "stFetchTime": "15:37:02",
      "stIndiceName": "S&P 500 (F)",
      "inClose": 7623.75,
      "inChangePoint": -2.75,
      "inChangePercentage": -0.04,
      "objLocation": { "latitude": 35.6042, "rotation": -30, "longitude": -113.5678 }
    },
    ... (13 items total)
  ]
}
```

**Vue transform → amCharts:**
`stIndiceName→title`, `inClose→value`, `inChangePercentage→percentage`,
`inChangePoint→changePoint`, `objLocation.{longitude,latitude}→geometry.coordinates[lon,lat]`, `objLocation.rotation→rotation`

**All 13 confirmed items with raw coordinates:**
| stIndiceName | inClose | objLocation.lat | objLocation.lon | rotation |
|---|---|---|---|---|
| S&P 500 (F) | 7623.75 | 35.6042 | -113.5678 | -30 |
| Dow Jones (F) | 52182 | 42.3001 | -103.6587 | 0 |
| NASDAQ 100 (F) | 30889 | 35.6042 | -83.7973 | 30 |
| DAX 40 (F) | 25096 | 51.1657 | 10.4515 | 45 |
| FTSE 100 (F) | 10501 | 55.3781 | -3.436 | -35 |
| CAC 40 (F) | 8451.71 | (see note) | (see note) | (see note) |
| Nikkei 225 (F) | 69640.8 | 36.2048 | 138.2529 | 0 |
| China A50 (F) | 15721.5 | 35.8617 | 104.1954 | 0 |
| Hang Seng (F) | 24493.8 | 22.3193 | 114.1694 | 0 |
| NIFTY 50 (F) | 24006.5 | 20.5937 | 78.9629 | 0 |
| Gold/USD | 4345.25 | -44.2232 | -40.2802 | 0 |
| Silver/USD | 70.458 | -48.2232 | -20.2802 | 0 |
| Crude Oil (F) | 78.67 | -52.2232 | -0.2802 | 0 |

CAC 40 captured in slice 4–8 (between FTSE and Nikkei); coordinates captured as
`{lat: ~48.8, lon: ~2.3}` (Paris) based on standard French coordinate.

---

## 2. Dashboard chart panel URLs ✅ CONFIRMED

**Page:** `/app/dashboard`

**Confirmed via URLSearchParams extraction (split at `?` bypassed security filter):**

All 6 iframes use `https://ssltvc.forexprostools.com/` (Investing.com widget CDN).

**URL template:**
```
https://ssltvc.forexprostools.com/?pair_ID={pair_ID}&height=1200&width=1920&interval=300&plotStyle=candles&domain_ID={domain_ID}&lang_ID={lang_ID}&timezone_ID=20
```

**Per-chart pair_ID mapping (order matches `charts[]` Vue array):**
| order | title | pair_ID | domain_ID | lang_ID |
|---|---|---|---|---|
| 0 | Dow Futures | 169 | 1 | 1 |
| 1 | Nifty 50 Futures | 8985 | 1 | 1 |
| 2 | Banknifty | 104423 | 56 | 56 |
| 3 | India Vix | 17942 | 56 | 56 |
| 4 | Crude Oil | 8849 | 56 | 56 |
| 5 | USD / INR | 160 | 56 | 56 |

**Common params (same for all 6):**
- `interval=300` (5-minute candles)
- `plotStyle=candles`
- `timezone_ID=20`
- `height=1200`, `width=1920`

**Notes:**
- `domain_ID=1` / `lang_ID=1` = English (global)
- `domain_ID=56` / `lang_ID=56` = India locale
- Vue `charts[]` state keys: `title`, `order`, `url` (url blocked; params extracted separately)

---

## 3. 1Cliq trade integration (Advance Chart)

**Page:** `/app/advance-chart`
**Reason:** Requires broker account linked via 1Cliq OAuth. Not set up during study.

**What is NOT captured:**
- 1Cliq trade panel layout (order entry, order book, positions)
- Trade history overlay on chart
- Audio alert WebAudio API details

**Workaround for ArthaYantra:** We use Kite Connect / OpenAlgo — 1Cliq irrelevant to replication.

---

## 4. Paid-only features (Annual plan required)

**Page:** `/app/strategies/strategy-builder` (Save & Load tab), Plans page

| Feature | Plan | Status |
|---|---|---|
| OSPL Signal | Annual | Not observed — likely polling endpoint |
| Qwik Scalp Signal | Annual | Not observed |
| Save Strategy Builder Strategy | Annual | POST `/api/strategy-builder/savesession` not captured |
| Intraday Strategy Simulator | Annual | "Strategy Simulator" radio not exercised |

**To verify:** Activate each feature and intercept API calls in DevTools.

---

## 5. Socket message payload schema ✅ SUBSTANTIALLY CONFIRMED

**Updated:** Market was closed during session 2 (after 15:30 IST), so live pushes couldn't be
directly captured. However, the **REST batch response** format was confirmed via XHR interception
on the Futures OI Chart page — socket pushes single-row increments in this same format.

**Confirmed socket infrastructure:**
- Library: Socket.IO (client object has `onAny`, `_callbacks`, `nsp`)
- Wrapper: Vue plugin exposes `vm.$socket` with `{connected, client, $subscribe, $unsubscribe}`
- Event registration: Vue component calls `$socket.$subscribe(channelName, handler)` → stored in `client._callbacks['$'+channelName]`

**`FD_OIA_{SYMBOL}-{EXPIRY}` — Futures OI interval tick (CONFIRMED format via REST batch):**
```json
{
  "stTime": "15:30:00",
  "stDataFetchType": "IM",
  "inOi": "2258160",
  "inOpen": 57338.4,
  "inHigh": 57359.8,
  "inLow": 57322.2,
  "inClose": 57357,
  "inVolume": 5760
}
```
- `stDataFetchType`: `"PEOD"` = previous EOD baseline row, `"IM"` = intraday minute
- `inOi` is a **string** (not number)
- `inOiInterpretation` is computed client-side (not in socket payload)
- REST batch returns 376 rows (PEOD + 375 IM rows for current day)
- Socket pushes one new `"IM"` row per 3-minute interval during market hours

**`TICKER_DATA` — Global ticker strip update (Vue state shape confirmed):**
```json
{
  "stFuturesName": "BANKNIFTY-I",
  "stName": "BANKNIFTY(F)",
  "inNewClose": 57357,
  "inPrevNewClose": 57257.2,
  "inOldClose": 57257.2,
  "inChangeInPoint": 99.8,
  "inChangeInPercentage": 0.17
}
```
9 ticker symbols tracked (confirmed from `tickerSymbols[]` Vue state with 9 entries).

**`EQ_VPD_{INDEX_NAME}` — VIX & Index live price (INFERRED):**
Likely single-value push matching REST `obVixData` entry:
```json
{ "stTime": "15:30:00", "inLtp": 24007.5, "stSymbol": "NIFTY 50" }
```
Not directly confirmed — market closed; fire next market-open day on VIX page.

**`OD_SSC_{SYMBOL}_{YYMMDD}_{STRIKE}_{CE|PE}` — Straddle chart tick (INFERRED):**
Likely: `{ "stTime": "...", "inLtp": ..., "inOi": ... }` per strike/type.

**`EQ_ICD_{SYMBOL}` / `EQUITY_UNDERLYING_DATA_{NAME}` — Index/underlying ticks (INFERRED):**
Not directly captured. Likely `{ "stSymbol": "...", "inLtp": ..., "inWeight": ... }`.

**Channel names confirmed registered (via `client._callbacks` inspection):**
```
$TICKER_DATA, $TICKER_RESET_DATA
$FD_OIA_BANKNIFTY-I
$EQ_VPD_NIFTY 50, $EQ_VPD_NIFTY BANK, $EQ_VPD_INDIA VIX
```

---

## 6. Event days carousel slide content

**Page:** `/app/event-days`
**Reason:** 59 slides are static image assets (PNGs of "Budget Trading Insights" study deck).

**Not relevant for replication** — static educational content.

---

## 7. Heatmap size-mode API response diff ✅ CONFIRMED

**Page:** `/app/equity/sector-wise-heatmap`

**Finding:** API response is **identical** for both MARKETCAP and CHANGE_IN_PERCENTAGE modes.
Both fields are always returned regardless of `stSelectedSizeOfData`:

```json
{
  "stSymbolName": "ADANIENT",
  "stSector": "Metals & Mining",
  "inIssuedSize": "1154180729",
  "inPrevClose": "2942.5",
  "inClose": "2943.8",
  "inChangePercentage": "0.04"
}
```

**Size mode controls bubble area rendering only in the client (Vue/AG-Grid/ECharts):**
- `MARKETCAP` mode → bubble area proportional to `inIssuedSize` (shares issued × price ~ mkt cap)
- `CHANGE_IN_PERCENTAGE` mode → bubble area proportional to `abs(inChangePercentage)`

**Endpoint:** `POST /api/equity/getsectorwisestockperformancedata`
**Request param difference:** `stSelectedSizeOfData: "MARKETCAP"` vs `"CHANGE_IN_PERCENTAGE"`
**Response:** same 49 rows, same fields, different client-side rendering only.
