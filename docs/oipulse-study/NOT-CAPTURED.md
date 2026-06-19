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

## 4. Paid-only features (Annual plan) ✅ CAPTURED 2026-06-18

Owner's session is on the Annual (Go Combo) plan, so these were **accessible** — captured live. See
[PHASE-B-FINDINGS.md §6](PHASE-B-FINDINGS.md#6-follow-up-captures-2026-06-18-pm--paid-features--reverse-engineered-formulas).

| Feature | Status |
|---|---|
| OSPL Signal | ✅ TradingView Pine study on Advance Chart (Indicators → "OSPL Signal"); params (10,2) = SuperTrend-derived directional signal. Pine source server-protected. |
| Qwik Scalp Signal | ✅ Pine study "OSPL Qwik scalp" on Advance Chart (faster scalp variant). Source protected. |
| OSPL Volume | ✅ Pine study; Inputs = MA Length 20 + color-by-prev-close; dark-bar threshold internal (not exposed). |
| Strategy Builder | ✅ Payoff builder + per-strike Greeks chain (Δ/Θ/V/IV); Stats (MaxP/MaxL/RR/Breakeven/POP/DaysLeft); client-side BS. |
| Strategy Simulator | ✅ A mode-radio inside Strategy Builder (autoplay time-walk), not a separate route. |
| Save Strategy Builder Strategy | "Save & Load" tab present (CRUD save-session); endpoint not separately intercepted — low replication value. |

The only thing NOT extractable is the **Pine source** of the OSPL studies (server-protected) — but params + visual behaviour are captured.

---

## 5. Socket message payload schema ✅ CONFIRMED LIVE (2026-06-18)

**Resolved by the live Phase-B run** — see [PHASE-B-FINDINGS.md](PHASE-B-FINDINGS.md) for the
full table, raw samples, and capture method. The session-2 INFERRED guesses below were **object
shapes; the actual live frames for OI/candle/chain/spurt channels are POSITIONAL ARRAYS** — the
REST-batch object format (still valid for REST) is NOT the socket frame shape.

**Confirmed socket infrastructure:** Socket.IO (one shared `$socket.client` for the SPA; `onAny`,
`_callbacks`, `nsp`); channels subscribe on page mount, unsubscribe on leave; OI channels push on
3-min interval boundaries; price channels also emit a snapshot frame on subscribe.

**Live socket frame layouts (CONFIRMED):**
```
EQ_VPD_{name}                 {stName, stDateTime(ISO), inLtp}                         # object
EQUITY_UNDERLYING_DATA_{name} {stName, stDateTime, inLtp, inHigh, inLow}              # object
EQ_ICD_{stock}                [symbol, ltp]                                            # array[2]
OD_OIA_{sym}_{exp}_{strike}   [time, side, O, H, L, C, volume, OI]                     # array[8]
OD_OC_{sym}_{exp}             [strike, side, LTP, volume, OI]  (per-strike stream)     # array[5]
OD_OI_SPURT_{sym}_{exp}       [strike, side, LTP, volume, OI]  (stream)               # array[5]
OD_SSC_{sym}_{exp}_{strike}_{CE|PE}  [time, instrumentId, O, H, L, C, volume]         # array[7]
FD_OIA_{sym}-I                [symbol, time, O, H, L, C, volume, OI]                   # array[8]
FD_OIS                        [symbol, LTP, volume, OI]  (stream)                      # array[4]
OD_OPT_CHART_{sym}_{exp}_{strike}    [time, strike, side, O, H, L, C, volume, OI, 0]   # array[10] (multiple-oi-chart)
CALENDAR_SPREAD_OPT_{sym}_{exp}_{strike}_{CE|PE}  [time, expiry, strike, side, O,H,L,C, volume]  # array[9], no OI
TICKER_DATA                   [symbol, ltp]   (highest-freq; + TICKER_RESET_DATA on rollover)    # array[2]
```
- **`OD_OC` carries no IV** — the chain's IV column is REST-served, not pushed.
- `FD_OIA` REST batch still returns the object form (`stTime/stDataFetchType/inOi(string)/inOpen…`,
  376 rows = PEOD + intraday); the **socket** frame is the positional array above.

**`TICKER_DATA` ✅ confirmed (follow-up):** absent in the first pass only because the ticker was
**disabled in the owner's profile**; after re-enabling, the strip subscribes `TICKER_DATA`
(`[symbol, ltp]`, highest-frequency channel) + `TICKER_RESET_DATA` (registered; fires on rollover).
Replaces the session-2 INFERRED rich-object guess with the array form above. The dashboard panels
still use the external Investing.com feed (item 2) — no oipulse socket of its own.

**REST-only pages (no socket at all):** interval-wise-oi, banks-analysis, connecting-dots,
active-strikes-oi, active-strikes-iv. (`multiple-oi-chart` and `calendar-spread` are NOT REST-only —
they subscribe `OD_OPT_CHART` / `CALENDAR_SPREAD_OPT` once a strike/position is selected.)

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
