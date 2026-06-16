# Not Captured — items inaccessible or unconfirmed

Items that could NOT be fully confirmed during the live Claude-in-Chrome study.
Captured: 2026-06-16. Study was against an authenticated session (Go Annual Combo plan).

---

## 1. World Indices API raw response schema

**Page:** `/app/world-indices`
**Reason:** `fetch()` and `XMLHttpRequest` to `api.oipulse.com` from within the browser JS context
  failed with `TypeError: Failed to fetch` (CORS / network restriction in the extension sandbox).

**What IS confirmed** (from amCharts `MapPointSeries._data` state):
- 13 data points with fields: `{title, value, percentage, changePoint, rotation, geometry:{type:"Point",coordinates:[lon,lat]}}`

**What is INFERRED** (from naming convention + `stLastUpdatedAt` field on Vue component):
- API endpoint: `POST /api/heatmap/getworldindicesdata`
- Request body: likely `{}` or `{stSelectedModeOfData:"live"}`
- Server fields: `stFetchDate`, `stFetchTime`, `stIndiceName`, `inClose`, `inChangePoint`, `inChangePercentage`, `objLocation:{latitude,longitude,rotation}`
- Vue transforms server fields → amCharts: `stIndiceName→title`, `inClose→value`, etc.

**To verify:** intercept XHR on the actual network tab in Chrome DevTools.

---

## 2. Dashboard chart panel URLs

**Page:** `/app/dashboard`
**Reason:** Accessing `.url` on Vue `charts[]` entries triggered the security filter
  (`[BLOCKED: Cookie/query string data]`) — the URLs contain Investing.com widget configuration
  query parameters that pattern-match the cookie/token filter.

**What IS confirmed:**
- 6 charts in order: Dow Futures, Nifty 50 Futures, Banknifty, India Vix, Crude Oil, USD/INR
- Chart data source: `ssltvc.forexprostools.com` iframes (Investing.com widget)
- The iframe `src` parameters include the symbol, interval, theme, etc.

**What is NOT confirmed:** exact iframe URL template / query parameter names for each symbol.

**To verify:** In DevTools Network tab, filter by `forexprostools.com` to see the iframe src URLs.

---

## 3. 1Cliq trade integration (Advance Chart)

**Page:** `/app/advance-chart`
**Reason:** 1Cliq is a third-party broker integration (connects via OAuth/API key to brokers like
  SS Corporate, SW Capital, etc.). The "IC | 1Cliq" button in the global header opens a trade window.
  It requires a separate broker account connection not set up in the study session.

**What is NOT captured:**
- 1Cliq trade panel layout (order entry, order book, positions)
- Trade history overlay on chart (how executed trades are plotted)
- Audio alert WebAudio API details
- OI Bar (Beta) canvas overlay drawing code

**Workaround for ArthaYantra:** We use Kite Connect / OpenAlgo for trade execution — 1Cliq is irrelevant to replication.

---

## 4. Paid-only features (Annual plan required)

**Page:** `/app/strategies/strategy-builder` (Save & Load tab), Plans page
**Reason:** The following features are behind the Annual plan paywall and were either
  unavailable or not exercised during the study:

| Feature | Plan | Status |
|---|---|---|
| OSPL Signal | Annual | Not observed — signal feed unknown; likely a push event or polling endpoint |
| Qwik Scalp Signal | Annual | Not observed |
| Save Strategy Builder Strategy | Annual | Save/Load tab visible but POST `/api/strategy-builder/savesession` not captured |
| Intraday Strategy Simulator | Annual | "Strategy Simulator" radio in Strategy Builder; transitions to a replay mode — not exercised |

**To verify:** Activate each feature and intercept the API calls.

---

## 5. Socket message payload schema

**Reason:** Socket subscriptions were confirmed (channel names like `FD_OIA_BANKNIFTY-I`,
  `OD_SSC_BANKNIFTY_260630_57200_CE`, `EQ_VPD_NIFTY 50`) via `socketSubscribedEvents[]` in
  Vue state. However, the **message payload format** (what the server PUSHes on each event)
  was NOT directly captured — it was inferred from the Vue component's state shape at rest.

**Channel name patterns confirmed:**
```
FD_OIA_{SYMBOL}-{EXPIRY}         Futures OI Analysis interval tick
OD_SSC_{SYMBOL}_{YYMMDD}_{STRIKE}_{CE|PE}   Straddle chart live tick
EQ_VPD_{INDEX_NAME}              Vix & Index live price
EQ_ICD_{SYMBOL}                  Index Contribution constituent tick
EQUITY_UNDERLYING_DATA_{NAME}    Underlying LTP strip (options pages)
```

**What is NOT confirmed:** JSON field names of the push payloads from the socket server.
The socket likely uses Socket.IO (the OiPulse JS bundle imports it). Message envelope
likely: `{stSymbol, inLtp, inOi, ...}` matching the REST field naming convention.

---

## 6. Event days carousel slide content

**Page:** `/app/event-days`
**Reason:** The 59 carousel slides are image assets (PNGs/JPGs of a "Budget Trading Insights"
  study deck). Their actual content (statistics, analysis from the 10-year study) was not read —
  only the slide count (59) and category (Union Budget) were confirmed.

**Not relevant for replication** — this is static educational content.

---

## 7. OpenHigh / Sector Heatmap size selection in equity-returns / heatmap

**Page:** `/app/equity/sector-wise-heatmap`
**Reason:** The `Size` dropdown (MARKETCAP / CHANGE_IN_PERCENTAGE) was confirmed from Vue state but
  the API response difference between the two size modes was not captured — same endpoint, different
  `stSelectedSizeOfData` param value.

**To verify:** Select each size option and compare `inIssuedSize` vs `inChangePercentage` as the
  bubble area driver.
