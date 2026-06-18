# Phase B — Socket Payload Capture (Market Hours)

> **✅ EXECUTED 2026-06-18 (SENSEX expiry day).** Results in
> [PHASE-B-FINDINGS.md](PHASE-B-FINDINGS.md): 9 socket channel families decoded, REST-only pages
> identified, and all 17 Manual-V10 verify items (V1–V17) resolved. Progress table at the bottom
> is ticked. The plan below is retained as the method record.

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

> **✅ ALL 17 RESOLVED on 2026-06-18** — verdicts + evidence in
> [PHASE-B-FINDINGS.md §5](PHASE-B-FINDINGS.md#5-manual-v10-verify-items-v1v17--verdicts). Summary:
> manual was right on V2 (ΔPut−ΔCall), V9 (extrinsic premium), V11 (double-arrow ATM), V5/V12;
> study was right on V1, V3, V4, V8, V14; V6 (no Pattern col) + V17 (no Morning/3:20 route) were
> stale in the manual; V7/V16 thresholds stay manual-sourced (not exposed in the live UI).

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

## Execution model — user-paced, NOT unattended

**This is NOT a 6.5-hour unattended run.** Claude has no background clock — it acts only
when the user sends a message (or a scheduled wakeup fires). It cannot "wait until 09:15
then capture" on its own. Capture is also interactive (inject → wait for ticks → harvest
via Chrome MCP), so it needs Claude actively driving each burst.

**Model:** the **user is the clock**; Claude is the worker. The user pings Claude in a few
short bursts across the morning; each burst Claude does ~15–90 min of active work. Total
**active** time ≈ 2 hours, spread across ~09:00–12:00.

| Burst | User pings ~ | Claude does (active) |
|---|---|---|
| 1 — Setup | 09:00 IST | Open all 12 pages, inject socket interceptor on each, confirm channels registered (~15 min) |
| 2 — Open-burst harvest | 09:15 IST (at open) | Ride the open burst, work pages 1→12, record payload schemas (~60–90 min → ends ~10:45, mostly waiting on ticks between pages) |
| 3 — Mop-up + verify | ~11:30 IST | Re-capture thin channels; clear V1–V17 visual/REST checks (any time market open) |

> Note: the burst-1 interceptors keep buffering ticks into `window.__socketCapture` from 09:15
> onward, so even a slightly late burst-2 ping still has open-burst frames in the buffer. Pinging
> right at 09:15 just lets Claude harvest live per page rather than draining a backlog.

Do **not** rely on `ScheduleWakeup` to chain a full session — context compaction + cache
expiry across 6.5 hrs is fragile. A single reminder for burst 1 is fine; the user drives the rest.

### Day / token preconditions
- **Arrive 08:55** to inject before the 09:15 open (pre-09:15 = no ticks yet, setup only).
- **V10 needs a weekly-expiry day** (options-chain IV only shows on expiry days). The 2026-06-18
  run used **SENSEX (Thursday = BSE SENSEX weekly expiry)** and captured V10. (Index expiry weekdays
  drift with exchange circulars — confirm the live name dropdown rather than assuming a fixed day.)
- **Let the open settle ~30s** (skip 09:15:00–09:15:30) for clean schema reads — some pages
  re-subscribe right at open.
- **Kite token expires 06:00 IST** — irrelevant to oipulse (separate site, user's own Chrome
  session), but if also live-comparing ArthaYantra, re-arm Kite first.

## Timing guidance

| IST | Market state | What to do |
|---|---|---|
| 08:55–09:14 | Pre-market | Navigate to pages, inject interceptors, wait (setup only — no ticks) |
| 09:15–09:30 | Market open | First ticks arrive — highest volume of socket events |
| 09:30–12:00 | Normal session | Steady ticks — capture remaining pages + V1–V17 checks |
| 12:00–15:30 | Normal session | Can re-capture any missed pages |
| 15:30+ | Market closed | Ticks stop — no point continuing |

**Optimal:** Start at 08:55, inject on all 12 pages before 09:15, then harvest ticks from 09:15.
Sequence: VIX page first (most important), then Options Chain, then remaining in order.

---

## Progress Tracking

Status as of the 2026-06-18 run (schemas in [PHASE-B-FINDINGS.md](PHASE-B-FINDINGS.md)):

| Page | Channels confirmed | Payload schema | Doc updated |
|---|---|---|---|
| Vix & Index | ✅ `EQ_VPD_{name}` | ✅ `{stName,stDateTime,inLtp}` | ✅ |
| Options OI Analysis | ✅ `OD_OIA_{sym}_{exp}_{strike}` | ✅ `[time,side,O,H,L,C,vol,OI]` | ✅ |
| Options Chain | ✅ `OD_OC_{sym}_{exp}` + underlying | ✅ `[strike,side,LTP,vol,OI]` (IV via REST) | ✅ |
| Straddle Chart | ✅ `OD_SSC_..._{CE\|PE}` | ✅ `[time,instrId,O,H,L,C,vol]` | ✅ |
| Strangle Chart | ✅ `OD_SSC_..._{CE\|PE}` ×2 strikes | ✅ same as straddle | ✅ |
| Connecting Dots | — REST-only (no socket) | n/a | ✅ |
| Index Contribution | ✅ `EQ_ICD_{stock}` ×50 | ✅ `[symbol, ltp]` | ✅ |
| Interval Wise OI | — REST-only (no socket) | n/a | ✅ |
| Multiple OI Chart | ✅ `OD_OPT_CHART_{sym}_{exp}_{strike}` (after strike pick) | ✅ `[time,strike,side,O,H,L,C,vol,OI,0]` | ✅ |
| Banks Analysis | — REST-only (no socket) | n/a | ✅ |
| Futures OI Spurt | ✅ `FD_OIS` | ✅ `[symbol,LTP,vol,OI]` | ✅ |
| Options OI Spurt | ✅ `OD_OI_SPURT_{sym}_{exp}` | ✅ `[strike,side,LTP,vol,OI]` | ✅ |
| Futures OI Chart | ✅ `FD_OIA_{sym}-I` | ✅ `[symbol,time,O,H,L,C,vol,OI]` | ✅ |
| Global ticker | ✅ `TICKER_DATA` + `TICKER_RESET_DATA` (after re-enable) | ✅ `[symbol, ltp]` | ✅ |
