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
