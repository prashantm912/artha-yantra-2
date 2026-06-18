# Phase B — Findings (live capture 2026-06-18, SENSEX expiry day)

Live Socket.IO + visual/REST capture run on **Thu 2026-06-18** (BSE **SENSEX weekly expiry**),
NSE/BSE market open, via Claude-in-Chrome `javascript_tool` on the owner's authenticated
oipulse session. Method: hook the SPA's single shared `$socket.client` with `onAny`, buffer
frames into `window.__cap`, SPA-navigate page→page (the hook survives route changes + socket
reconnects; a full reload loses it). This doc is the source of truth; per-page docs link here.

See [PHASE-B-PLAN.md](PHASE-B-PLAN.md) for the plan and [MANUAL-V10-GAP-ANALYSIS.md](MANUAL-V10-GAP-ANALYSIS.md)
for the audit that raised V1–V17.

---

## 1. Socket transport — confirmed behaviour

- **One shared socket.io (engine.io) connection** for the whole SPA. Channels are SUBSCRIBED on
  page mount and UNSUBSCRIBED on leave — you only receive a channel while its page is mounted.
- **Reconnects are frequent** (engine id changes) but the `Socket` object is reused, so an
  `onAny` hook persists across reconnects.
- **Push cadence:** OI candle/stream channels push on **interval boundaries** (3-min default) —
  a fresh subscribe waits up to one interval for the first frame. Price channels
  (`EQ_VPD`, `EQUITY_UNDERLYING_DATA`) also push roughly interval-aligned (~1/min) and additionally
  emit **one snapshot frame on subscribe** (visible even pre-open).
- **Two payload encodings:** index/underlying price channels are **keyed objects**; all OI / candle /
  chain / spurt channels are **compact positional arrays** (decoded below).

## 2. Socket channel families — payload layouts (all confirmed live)

| Family | Channel name pattern | Encoding | Layout | Page(s) |
|---|---|---|---|---|
| VIX/index price | `EQ_VPD_{NAME}` | object | `{stName, stDateTime, inLtp}` | features/vix-index |
| Underlying | `EQUITY_UNDERLYING_DATA_{NAME}` | object | `{stName, stDateTime, inLtp, inHigh, inLow}` | options-chain, oi-spurt, straddle, strangle |
| Index contribution | `EQ_ICD_{STOCK}` | array[2] | `[symbol, ltp]` | equity/index-contribution |
| Option OI candle | `OD_OIA_{SYM}_{EXP}_{STRIKE}` | array[8] | `[time, side(CE\|PE), O, H, L, C, volume, OI]` | options/oi-analysis |
| Option chain | `OD_OC_{SYM}_{EXP}` | array[5] (per-strike stream) | `[strike, side, LTP, volume, OI]` | options/options-chain |
| Option OI spurt | `OD_OI_SPURT_{SYM}_{EXP}` | array[5] (stream) | `[strike, side, LTP, volume, OI]` | options/oi-spurt |
| Straddle/strangle leg | `OD_SSC_{SYM}_{EXP}_{STRIKE}_{CE\|PE}` | array[7] | `[time, instrumentId, O, H, L, C, volume]` (no OI) | strategies/straddle-chart, strangle-chart |
| Futures OI candle | `FD_OIA_{SYM}-I` | array[8] | `[symbol, time, O, H, L, C, volume, OI]` | futures/oi-analysis, oi-chart |
| Futures OI spurt | `FD_OIS` | array[4] (stream) | `[symbol, LTP, volume, OI]` | futures/oi-spurt |
| Option chart (multi-OI) | `OD_OPT_CHART_{SYM}_{EXP}_{STRIKE}` | array[10] | `[time, strike, side, O, H, L, C, volume, OI, (0/unused)]` | options/multiple-oi-chart |
| Calendar-spread leg | `CALENDAR_SPREAD_OPT_{SYM}_{EXP}_{STRIKE}_{CE\|PE}` | array[9] | `[time, expiry, strike, side, O, H, L, C, volume]` (no OI) | strategies/calendar-spread |
| Ticker strip | `TICKER_DATA` (+ `TICKER_RESET_DATA`) | array[2] | `[symbol, ltp]` (very high-freq; RESET fires on rollover, not seen) | global top strip |

> **Follow-up pass (2026-06-18, after the owner re-enabled the ticker setting):** three more channels
> found, bringing the total to **12 families**. `OD_OPT_CHART` and `CALENDAR_SPREAD_OPT` only subscribe
> **after** the user completes the page's selection (multiple-oi-chart needs ≥1 strike picked + Go;
> calendar-spread needs "Add Position") — so the initial run's "REST-only" call for those two pages was
> **wrong; both are socket-driven**. `TICKER_DATA` now confirmed (it was disabled in the owner's profile
> during the first pass).

`{EXP}` = expiry `YYMMDD`. `instrumentId` = `{SYM}{YYMMDD}{STRIKE}{CE|PE}` (e.g. `SENSEX26061877100CE`).

### Confirmed samples (raw frames)
```
EQ_VPD_INDIA VIX        {"stName":"INDIA VIX","stDateTime":"2026-06-18T09:10:00","inLtp":13.1875}
EQ_VPD_NIFTY 50         {"stName":"NIFTY 50","stDateTime":"2026-06-18T09:10:00","inLtp":24073.8}
EQUITY_UNDERLYING_DATA_SENSEX
                        {"stName":"SENSEX","stDateTime":"2026-06-18T09:12:00","inLtp":77131.66,"inHigh":77131.66,"inLow":77131.66}
EQ_ICD_TRENT            ["TRENT",3142.2]
OD_OIA_SENSEX_260618_77100   ["09:25:00","PE",138.3,141.2,130.15,138.7,705720,3386780]
OD_OC_SENSEX_260618          ["77100","CE",179.95,598240,3356180]   (ATM CE: vol 598240, OI 3356180)
                             ["77100","PE",135.9,697720,3386780]    (cross-checked vs OD_OIA → [3]=volume, [4]=OI)
OD_OI_SPURT_SENSEX_260618    [80000,"PE",2875,20,12320]
OD_SSC_SENSEX_260618_77100_CE ["09:28:00","SENSEX26061877100CE",180.9,189.9,178.4,181.45,655400]
OD_SSC_SENSEX_260618_77100_PE ["09:28:00","SENSEX26061877100PE",135.2,139,129,138.2,717820]
FD_OIA_NIFTY-I               ["NIFTY-I","09:22:00",24097.5,24102.8,24094,24094,8125,16826875]
FD_OIS                       ["RECLTD-I",358.7,2800,57766800]
OD_OPT_CHART_SENSEX_260618_77200      ["10:23:00",77200,"PE",120.8,126.1,118.8,120.3,658620,6113540,0]
CALENDAR_SPREAD_OPT_SENSEX_260618_77200_CE  ["10:23:00","260618",77200,"CE",152.45,154.85,146.05,152.55,415260]
TICKER_DATA                  ["HDFCBANK-I",789.3]   (n≈2000 in ~1h — highest-frequency channel)
```

### Notes
- **`OD_OC` carries NO IV** (5 elements). The chain's IV column is **REST-served**, not pushed.
- `OD_OIA` pushes both a CE frame and a PE frame under the **same** channel key each interval.
- `OD_OC` / `OD_OI_SPURT` stream **every strike** as separate frames (~190–200/interval); the
  spurt ranking is computed client-side. `FD_OIS` streams **every future** (~320/interval).
- Index/underlying price channels emit a snapshot on subscribe → schema capturable pre-open.

## 3. REST-only pages (no live socket subscription)

`options/interval-wise-oi` (OI Gainer/Loser charts ×15m/60m/Daily), `futures/banks-analysis`,
`features/connecting-dots` (signal matrix — **loads ~5-10 s after open**, then renders), `options/active-strikes-oi`,
`options/active-strikes-iv` — data loads via REST (several auto-refresh, shown as "Data Auto-updated At: …");
no socket channels are registered. All verified rendering on screen 2026-06-18.

(NOTE: `options/multiple-oi-chart` and `strategies/calendar-spread` were initially mis-classified here;
they ARE socket-driven once the user completes the selection — see the follow-up note in §2.)

## 4. Ticker — resolved in follow-up
- **`TICKER_DATA`** was absent in the first pass because the **ticker was disabled in the owner's
  profile**. After re-enabling it, the strip subscribes `TICKER_DATA` (array[2] `[symbol, ltp]`, the
  highest-frequency channel — ~2000 frames/hour) + `TICKER_RESET_DATA` (registered; fires only on a
  reset/rollover, not seen intraday). Confirmed against the visible strip (`HDFCBANK(F): 789.70` ≈ frame `["HDFCBANK-I",789.3]`).
- The dashboard's six chart panels are external **Investing.com** iframes (item 2 of NOT-CAPTURED) —
  the dashboard subscribes no oipulse data socket of its own.

---

## 5. Manual-V10 verify items (V1–V17) — verdicts

Evidence captured live on 2026-06-18. "→ study"/"→ manual" = which existing source the live site matched.

| # | Doc | Verdict (live) |
|---|---|---|
| V1 | futures/banks-analysis | Banks × time-interval matrix; each cell `(LTP% / OI%) + 4-state badge`; **badge is per-interval**; values rise with later intervals ⇒ baseline ≈ **day-open cumulative** → **study**. |
| V2 | options/trending-oi | `Diff. in OI = ΔPut OI − ΔCall OI`, **positive = bullish** (row1 26,111,280−21,478,740 = +46,32,540 Bullish; row2 = −42,28,120 Bearish) → **manual** (study's "Call − Put" is wrong). |
| V3 | fii-dii/capital-market | `In Market = FII Net + DII Net` (101.59+1561.4 = 1662.99; 200.05+3189.26 = 3389.31) → **study**. |
| V4 | options/active-strikes-oi | "Change in OI" chart: **Call OI = green, Put OI = red** → **study**. The blue line is a *separate* "Sentiment %" chart (what the manual conflated). Scope: NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY only (no SENSEX). REST auto-refresh. |
| V5 | strategies/open-high-strategy (+ equity/open-high-low) | Menu = **Options + Equity** (not Strategies). Split CALL/PUT layout. Cols: Day Open, Day High, New D.High, New D.Low, O=H/O=L, Triggered Time, **Probability**, Call/Put LTP, Strike. **Probability = a % (saw 10/20/40/95%)** + amber **"Hit ✔"** when triggered + **Red Dot ●** on 95% O=H badges → **manual** closer; neither "fixed 60/80/90/95 tiers" (study) nor a single AI%. |
| V6 | futures/oi-analysis | Columns: …`Level Break \| Volume \| LTP`… — **no "Pattern" column** (manual stale). Full cols: Date Time, Total OI, Total Chng. In OI, Day High, Day Low, Level Break, Volume, LTP, LTP Change, OI Change, OI Interpretation. |
| V7 | options/active-strikes-iv (+ oi-interpretation-method) | Page = time-series of **Call IV (green) / Put IV (red) / Price (orange dotted, right axis)**; NSE-only; REST auto-refresh. Numeric IV-band bounds / ~50K-candle / RSI thresholds are **not readable off the UI** → stay manual-sourced/qualitative. |
| V8 | futures/eod-oi-analyzer | Three checkboxes: **Show chart data / Show Cumulative Oi / Show Range Data** — **no "Show Detail View"** → **study**. |
| V9 | options/options-premium | Bars = **extrinsic value (LTP − intrinsic)**: peaked at ATM, near-zero at wings, and **deep-ITM 76200 CE bar goes negative** (impossible for raw LTP). A **"Show LTP"** toggle switches to raw LTP → **manual** (study's "raw LTP" wrong). |
| V10 | options/options-chain | On SENSEX expiry: **IV column present** both sides; vol-smile (PUT IV 10.7→16 OTM); **IV = 0 for deep-ITM calls** (premium all-intrinsic). Highlights: ATM row cream/yellow; **OI Chng.** green bars (additions); **OI** red data-bars (magnitude); **OI Int.** badges colour-coded `L.U.`(amber)/`S.B.`(red↓)/`L.B.`(green↑). Column order — CALL: `OI Int. \| OI % \| OI \| OI Chng. \| IV \| LTP \| LTP% \| LTP Chg \| Strike`; PUT mirrors + trailing `PCR Ratio`. |
| V11 | options/oi-statistics | ATM marker = **double arrow** (green Call ▲ + red Put ▲) → **manual** (study's "single ▲" wrong). Page = Cumulative OI + Individual OI (per-strike) + PCR chart; Call green / Put red. |
| V12 | strategies/oi-expiry-strategy | Default window ≈ **7 sessions** (pager "1 - 7 of 7") — closer to the manual's short window than the study's "~31-session". Table: Date, O, H, L, C, Volume, % Chg Close, % Chg OI, OI, OI Interpretation; 5 ATM strikes. |
| V13 | options/multiple-oi-chart | **Socket-driven** (`OD_OPT_CHART_{SYM}_{EXP}_{STRIKE}` per picked strike). Verified live with 77200 CE+PE: dual-axis chart — **blue dotted = SENSEX price (left axis), green = CE OI, yellow = PE OI (right axis)** ⇒ underlying/price overlay **is present** (manual lacked it). Selector = one symbol + expiry + a searchable multi-**strike** picker (not multi-symbol) + Go. |
| V14 | futures/pre-open-market + equity/pre-open-market | **Two separate routes** (`/app/futures-analysis/pre-open-market`, `/app/equity/pre-open-market`) → **split** (study), not one combined page. |
| V15 | strategies/calendar-spread | Position builder ("Add Position" → Positions table). **Socket-driven** (`CALENDAR_SPREAD_OPT_{SYM}_{EXP}_{STRIKE}_{CE\|PE}` array[9] `[time,expiry,strike,side,O,H,L,C,vol]`, no OI — one per added leg). Per-leg premium chart has **candles + two overlay lines (VWAP/EMA) + volume histogram + day H/L pins + dataZoom** — overlay set present. (Deep-ITM/illiquid strikes show "No data available"; pick a liquid strike.) |
| V16 | advance-chart/advance-chart | TradingView-lightweight in an iframe (oipulse BSE/NSE feed). Default indicators: **VWAP, VWMA(20)** (not plain 20-EMA), **SuperTrend(10,2), OSPL Volume(20), RSI(14)**. The **OSPL Volume** indicator IS a default (coloured bars) but its dark-bar threshold (manual: 50K BankNifty / 125K Nifty) is **internal to the indicator, not exposed in the legend** → stays manual-sourced. |
| V17 | README | **No "Morning Trade" / "3:20 Strategy" route exists** (122 app routes, 0 hits). Strategies menu = calender-spread, iv-strategy, multi-leg-price, straddle-chart, strangle-chart, strategy-builder. These remain paid/AI manual-sourced features, not navigable routes. |

### Cross-cutting confirmations
- **Colour convention (app-wide):** Call = green, Put = red (active-strikes-oi, oi-stats, options-premium, active-strikes-iv all agree).
- **OI Interpretation badge enum** confirmed live on the chain: `L.U.` Long Unwinding (amber), `S.B.` Short Buildup (red ↓), `L.B.` Long Buildup (green ↑), `S.C.` Short Covering (seen on banks-analysis).
- **oipulse lists SENSEX/BANKEX** (BSE indices) in the options/futures name dropdowns; some pages (active-strikes-oi/iv) are NSE-index-only.
- Route spellings: `calender-spread` (sic), `option-premium` (singular), `oi-stats`, `oi-expiry-strategy`.

---

## 6. Follow-up captures (2026-06-18 PM) — paid features + reverse-engineered formulas

### Paid / AI features (owner on Annual plan — accessible, not locked)
- **Strategy Builder** (`/app/strategies/strategy-builder`) — options payoff builder. "Add Positions" opens a
  **full Greeks chain** (per CE/PE strike: `OI | Vega | Theta | Delta | IV | Price` + Spot/Fut price). Tabs:
  Strategy Positions / Greeks / P&L / Save & Load. Stats: Max Profit, Max Loss, Risk:Reward, Breakeven, Days Left,
  POP. Settings: Spot %-move slider (±7) recomputes the payoff. **Strategy Simulator** is a mode-radio on the same
  page (autoplay time-walk: Start Autoplay / Reset Time / Min Gap), NOT a separate route. Payoff + Greeks are
  client-side Black-Scholes → replicable with our black76-math.
- **OSPL Signal / OSPL Qwik scalp / OSPL Volume** — these are **TradingView Pine custom studies on Advance Chart**
  (added via the Indicators dialog), not separate pages. Pine source is server-protected, but:
  - **OSPL Signal** params **(10, 2)** — identical to SuperTrend(10,2); renders a SuperTrend-style trend line +
    stop level (signal = trend flip). I.e. a SuperTrend-derived directional buy/sell.
  - **OSPL Qwik scalp** — faster scalp variant (same study family).
  - **OSPL Volume** (V16) — Inputs are only **MA Length = 20** + "Color based on previous close" toggle. There is
    **no threshold input** → the dark-bar threshold (manual: 50K BankNifty / 125K Nifty) is hardcoded in the Pine
    script, **not user-configurable / not readable from the UI**. Stays manual-sourced.
- "Morning Trade" / "3:20 Strategy" remain non-existent as routes/menu/indicators (confirmed again).

### Reverse-engineered formulas (fitted from live data)
- **Active-Strike Sentiment %** = **`(ΣPut OI − ΣCall OI) / ΣPut OI × 100`** — EXACT match on 5 consecutive
  points (e.g. 10:43: (7,052,711 − 16,052,253)/7,052,711 = −127.60 %). Negative ⇒ calls dominate ⇒ bearish.
  (Inputs are the cumulative active-strike Call/Put OI; `activeStrikeOiData.{yAxisCallData,yAxisPutData}`.)
- **banks-analysis cell** = `inLtpDiffInPercentage_{BANK}` / `inOiDiffInPercentage_{BANK}` + `inOiInterpretation_{BANK}`
  (1-4 enum badge), per-interval row × per-bank columns. The %s are **cumulative from the day-open baseline** —
  proven: `inOiDiffInPercentage` rises monotonically through the session (0.54 → 0.78 over 10:05→10:40). Confirms V1 (study).
- **Level Break** (futures oi-analysis / trending-oi "Day H/L Break") = fires when LTP breaks the **session high**
  (`D.H.B.` Day High Break, shows the level) or **session low** (`Day Low Break`).
- **Connecting-Dots** — `tableData` rows carry **pre-classified enum codes** per factor (0=Neutral/blue↔,
  1=Bullish/green↑, 2=Bearish/red↓), NOT raw values, so per-factor raw→enum cutoffs are server-side. The **12 factors**:
  `inDow, inVolume, inDailyTrend, inSelectedFutPrice, inSelectedFutOi, inVix, inActiveStrikeOi, inActiveStrikeIv,
  inVwap, inRsi, inSupertrend` → composite **`inTrend`** (1=Ext.Bullish, 2=Bullish, 3=Bearish, 4=Ext.Bearish).
  Composite rule fitted from net = (#bull − #bear) across the 11 factors: **net ≥ 8 → 1; +2..+7 → 2; −4..+1 → 3;
  ≤ −6 → 4**. The asymmetry (net +1 → Bearish) implies the factors are **weighted**, not equal-vote — exact weights
  are server-side.

### Minor
- `OD_OPT_CHART` 10th element is consistently **0** (reserved/unused, or an always-0 ΔOI slot).
- Historical mode not separately re-captured this session — Phase A assumes the same endpoints with a date param + same response shape.
