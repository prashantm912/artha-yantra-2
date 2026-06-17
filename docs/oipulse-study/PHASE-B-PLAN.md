# Phase B — Socket Payload Capture (Market Hours)

**Goal:** Confirm live Socket.IO push payload schemas for all live-data pages.
Phase A confirms REST API schemas; Phase B completes the picture with real-time socket frames.

**When:** MUST run during NSE market hours — **09:15–15:30 IST** on a trading day.
Earliest: tomorrow (next trading day after 2026-06-16).
**Duration:** ~2 hours (12 pages × ~10 min each including wait for ticks).
**Tool chain:** Claude-in-Chrome `javascript_tool` (socket interception in authenticated session).
Playwright cannot intercept Socket.IO frames at the frame level — Chrome MCP is superior here
because the page's own socket connection carries auth credentials.

---

## Auth

No new auth needed — reuses user's existing Chrome OiPulse session (same as Phase A via Chrome MCP).

---

## Socket Interception Script (inject once per page)

```javascript
// 1. Find Vue root with socket
(function() {
  const el = document.querySelector('[class*="container"], main, #app');
  const key = Object.keys(el).find(k => k.startsWith('__vue'));
  let vm = el[key];
  while (vm && !vm.$socket) vm = vm.$parent;
  if (!vm || !vm.$socket) return 'NO SOCKET';
  
  const client = vm.$socket.client;
  window.__socketCapture = [];
  
  // Intercept all future events
  client.onAny((event, ...args) => {
    window.__socketCapture.push({ event, payload: args[0], ts: Date.now() });
  });
  
  // Report registered channels
  const channels = Object.keys(client._callbacks || {}).map(k => k.replace(/^\$/, ''));
  return { status: 'listening', channels, capturedCount: 0 };
})()

// 2. After 2-3 minutes of market activity, retrieve captured payloads:
// JSON.stringify(window.__socketCapture.slice(0, 5)) — first 5 events
// Or by channel: window.__socketCapture.filter(e => e.event.startsWith('FD_OIA'))
```

---

## Pages — Priority Order

### Priority 1: Socket payloads NOT yet confirmed

| # | Page | URL | Socket channels to capture | Current status |
|---|---|---|---|---|
| 1 | Vix & Index | `/app/vix-price` | `EQ_VPD_NIFTY 50`, `EQ_VPD_NIFTY BANK`, `EQ_VPD_INDIA VIX` | INFERRED |
| 2 | Options OI Analysis | `/app/options-analysis` | Options-specific live channels | UNKNOWN |
| 3 | Options Chain | `/app/options-analysis/options-chain` | Per-strike CE/PE tick channels | UNKNOWN |
| 4 | Straddle Chart | `/app/strategies/straddle-chart` | `OD_SSC_{SYMBOL}_{EXPIRY}_{STRIKE}_{CE\|PE}` | INFERRED |
| 5 | Strangle Chart | `/app/strategies/strangle-chart` | Strangle tick channels | UNKNOWN |
| 6 | Connecting Dots | `/app/connecting-dots` | Any live score/signal channels | UNKNOWN |
| 7 | Index Contribution | `/app/index-contribution` | `EQUITY_UNDERLYING_DATA_{NAME}` | INFERRED |
| 8 | Interval Wise OI | `/app/options-analysis/interval-wise-oi` | Interval OI tick channels | UNKNOWN |
| 9 | Multiple OI Chart | `/app/options-analysis/multiple-oi-chart` | Multi-symbol OI channels | UNKNOWN |
| 10 | Banks Analysis | `/app/futures-analysis/banks-analysis` | Bank futures live tick channels | UNKNOWN |
| 11 | Futures OI Spurt | `/app/futures-analysis/oi-spurt` | OI spurt tick channels | UNKNOWN |
| 12 | Options OI Spurt | `/app/options-analysis/oi-spurt` | OI spurt tick channels | UNKNOWN |

### Priority 2: Socket payloads partially confirmed — verify only

| # | Page | URL | Channel | Current status |
|---|---|---|---|---|
| 13 | Futures OI Chart | `/app/futures-analysis/oi-chart` | `FD_OIA_BANKNIFTY-I` | CONFIRMED via REST proxy — verify live tick matches |
| 14 | Global ticker | any page | `TICKER_DATA`, `TICKER_RESET_DATA` | CONFIRMED via Vue state — verify live payload |

---

## Per-Page Process

```
1. Navigate to page via Claude-in-Chrome
2. Wait for page to fully load (observe network quiet)
3. Inject socket interception script → confirms channels registered
4. Wait 3–5 minutes for live ticks (market must be in session)
5. Retrieve: window.__socketCapture.slice(0, 10)
   - Extract unique event types
   - Record full payload JSON for each event type (one sample per channel)
6. Record: channel name, payload field names + types, update cadence (how often per minute)
7. Update the page's .md "Socket subscriptions" section with confirmed payload schema
```

---

## Target payload schemas to confirm

For each channel, we need:
```
Channel name: EQ_VPD_NIFTY 50
Payload: {
  stTime: "09:16:00",    // HH:MM:SS
  inLtp: 24100.5,        // last traded price
  stSymbol: "NIFTY 50",  // or inferred differently?
  // any other fields?
}
Cadence: every ~3 seconds during market hours
```

The exact field names are critical — `inLtp` vs `inClose` vs `price` can differ per channel.

---

## NOT-CAPTURED.md updates after Phase B

After Phase B, update `NOT-CAPTURED.md` item 5:
- Replace all INFERRED socket payloads with CONFIRMED schemas
- Move remaining unconfirmable items (1Cliq, Annual-only features) to a separate section

---

## Manual V10 audit — interpretation verify items

The Manual-V10 audit ([MANUAL-V10-GAP-ANALYSIS.md](MANUAL-V10-GAP-ANALYSIS.md)) folded its trading-interpretation findings into the study docs (additive). The items below were **NOT** folded in because the older manual may differ from the live site — confirm each during this live session (mostly a quick visual / REST-payload check, done alongside the socket capture). Where the live site differs, correct the named doc; where it confirms the manual, drop the caveat.

| # | Doc to correct | What to verify | How |
|---|---|---|---|
| V1 | `futures/banks-analysis.md` | cell %s baseline: prev-day **adjusted close** / prev-day **OI** (manual) vs **day-open** (study); interpretation badge is per-interval | inspect live `inLtpDiffInPercentage` / `inOiDiffInPercentage` |
| V2 | `options/trending-oi.md` | Difference-in-OI sign: **ΔPut − ΔCall** (manual, positive=bullish) vs study's "Call − Put" | live `inDifferenceInOi` sign when calls dominate |
| V3 | `fii-dii/capital-market.md` | "In Market" column = cash-market net per row (manual) vs FII Net + DII Net (study) | live payload + arithmetic |
| V4 | `options/active-strikes-oi.md` | Call line colour: green (study) vs blue/dark (manual); Put=red stable. Also page scope (Nifty/BankNifty present-month?) | visual |
| V5 | `strategies/open-high-strategy.md` | menu placement (Options vs Strategies); Probability = AI % + >90% gate + "Red Dot" vs discrete 60/80/90/95 tiers | visual + live values |
| V6 | `futures/oi-analysis.md` | does a **"Pattern"** column exist between Level Break and Volume | visual / live columns |
| V7 | `options/active-strikes-iv.md` + `oi-interpretation-method.md` | exact IV band bounds; the price-action "~50K candle" figure; Trending-OI RSI thresholds (kept qualitative in docs) | in-app guidance / observed behaviour (may remain manual-sourced) |
| V8 | `futures/eod-oi-analyzer.md` | a **"Show Detail View"** button (→ ~2-month detail) vs the current 3 checkboxes | visual |
| V9 | `options/options-premium.md` | premium bar basis: **extrinsic** (LTP − intrinsic) vs raw LTP | compare a known ITM strike's bar to LTP − intrinsic |
| V10 | `options/options-chain.md` | IV present on **weekly-expiry days**; capture the cell-highlight tint/colour | visual on an expiry day |
| V11 | `options/oi-statistics.md` | ATM marker: **double-arrow** (manual) vs single ▲ (study) | visual |
| V12 | `strategies/oi-expiry-strategy.md` | data window (5-day + AI-highlight, manual) vs ~31-session (study); menu (Options vs Strategies) | visual |
| V13 | `options/multiple-oi-chart.md` | does the live chart have the underlying-price overlay (manual lacks it) | visual |
| V14 | `futures/pre-open-market.md` + `equity/pre-open-market.md` | one combined "Pre open market" page + tab (manual) vs split futures/equity routes (study) | visual / routing |
| V15 | `strategies/calendar-spread.md` | chart overlays = VWAP + 20-EMA + volume | visual |
| V16 | `advance-chart/advance-chart.md` | OSPL-Volume dark-candle thresholds (manual: 50K BankNifty / 125K Nifty) — current values | observe |
| V17 | `README.md` | exact routes for **Morning Trade** and **3:20 Strategy** (added to menu map as unconfirmed) | navigate Strategies menu |

> These are additive corrections only — none removes existing study content. Items that stay unconfirmable (paid/Annual-only: OSPL Signal, Morning Trade, 3:20 Strategy outputs) remain manual-sourced and tagged as such in their docs.

---

## Timing guidance

| IST | Market state | What to do |
|---|---|---|
| 09:00–09:14 | Pre-market | Navigate to pages, inject interceptors, wait |
| 09:15–09:30 | Market open | First ticks arrive — highest volume of socket events |
| 09:30–12:00 | Normal session | Steady ticks — capture remaining pages |
| 12:00–15:30 | Normal session | Can re-capture any missed pages |
| 15:30+ | Market closed | Ticks stop — no point continuing |

**Optimal:** Start at 09:00, inject on all 12 pages before 09:15, then harvest ticks from 09:15.
Sequence: VIX page first (most important), then Options Chain, then remaining in order.

---

## Progress Tracking

| Page | Channels confirmed | Payload schema | Doc updated |
|---|---|---|---|
| Vix & Index | ☐ | ☐ | ☐ |
| Options OI Analysis | ☐ | ☐ | ☐ |
| Options Chain | ☐ | ☐ | ☐ |
| Straddle Chart | ☐ | ☐ | ☐ |
| Strangle Chart | ☐ | ☐ | ☐ |
| Connecting Dots | ☐ | ☐ | ☐ |
| Index Contribution | ☐ | ☐ | ☐ |
| Interval Wise OI | ☐ | ☐ | ☐ |
| Multiple OI Chart | ☐ | ☐ | ☐ |
| Banks Analysis | ☐ | ☐ | ☐ |
| Futures OI Spurt | ☐ | ☐ | ☐ |
| Options OI Spurt | ☐ | ☐ | ☐ |
| Futures OI Chart | ☐ (verify) | ☐ | ☐ |
| Global ticker | ☐ (verify) | ☐ | ☐ |
