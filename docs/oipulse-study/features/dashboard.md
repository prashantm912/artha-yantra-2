# Dashboard — `/app/dashboard`

**Purpose:** at-a-glance multi-market chart wall — 6 customizable **TradingView Advanced Chart**
panels in a 2-column grid. User-stated optional to replicate; captured for reference.
(Shared header/menu/ticker/theme: see `../00-global-shell.md`.)

## Layout
```
sub-tabs: [ Dashboard ] [ Tool ]
ticker strip
┌──────────────────────────────┬──────────────────────────────┐
│  Dow Futures        (DJI)    │  Nifty 50 Futures   (IND50)  │  row 1
├──────────────────────────────┼──────────────────────────────┤
│  Banknifty          (NBNc1)  │  India Vix          (NIFVIX) │  row 2
├──────────────────────────────┼──────────────────────────────┤
│  Crude Oil          (CL)     │  USD/INR                     │  row 3
└──────────────────────────────┴──────────────────────────────┘
```
2-col grid × 3 rows = 6 equal panels (vertical scroll for rows 2–3). Default symbols per panel.

## Components
| Component | Type | Position | Values / contents | Behavior | Visual cues |
|---|---|---|---|---|---|
| Sub-tabs | tab bar | below header | `Dashboard`, `Tool` | switch view | active highlighted |
| Ticker strip | quote marquee | below sub-tabs | `SYMBOL(F): price ±chg (±%)` | auto-refresh `/api/gettickerdata` | green ▲ / red ▼ |
| Chart panel ×6 | TradingView Advanced Chart | grid | one symbol each | full TV interactivity | header title + `✕` (configure/remove) |
| Symbol box | TV input | panel toolbar L | DJI / IND50 / NBNc1 / NIFVIX / CL / USDINR | type to change | — |
| Interval buttons | TV toolbar | toolbar | `S 30 1h 4h 1D 1W 1M` + `[5▾]` | switch TF | active = blue |
| TV tools | TV toolbar | toolbar R | chart-type, indicators(fx), settings⚙, compare, undo/redo, 📷 snapshot | standard TV | — |
| Left drawing rail | TV vertical bar | panel L edge | cursor, trendline, fib, text, ruler, magnet, zoom, lock, eye… | drawing | icon column |
| OHLC legend | TV overlay | top-left in chart | `O H L C` + symbol descr + "Market Closed" | live | red/green |
| Price/time scales | TV axes | right / bottom | prices, last-price tag; time `UTC+5:30` | — | last-price tag colored |
| Volume sub-pane | TV study | below price | `Volume (20)` histogram | — | green/red bars |
| Range bar | TV footer | panel bottom | `10y 3y 1y 3m 1m 7d 1d` `Go to` · `% log auto` | quick range | — |

## Charts
All 6 = **TradingView Advanced Charts** (full charting library), **Investing.com datafeed**
(watermark on each). Candlestick + volume, default 5m. Default symbols:
Dow=`DJI`(NYSE), Nifty50=`IND50`(NSE), Banknifty=`NBNc1`(NSE), India VIX=`NIFVIX`(NSE),
Crude=`CL`(WTI), USD/INR(currencies). **USD/INR panel overlays B / S / C circular markers** on candles (buy/sell/cover signal annotations).

## Vue component state (confirmed)
```
showSideBanner,
showSuccessInfoModal,
showRearrangeModal,        // toggles "Rearrange Charts" drag-to-reorder modal
tempCharts,               // staging list while rearranging
charts ([]),              // current ordered list of 6 chart configs
defaultCharts ([])        // default/fallback order (same 6 entries)
```

Each chart entry: `{order: 0..5, title: "Dow Futures"|"Nifty 50 Futures"|..., url: <iframe src>}`

Default chart list (confirmed, in order):
1. `Dow Futures` (order 0)
2. `Nifty 50 Futures` (order 1)
3. `Banknifty` (order 2)
4. `India Vix` (order 3)
5. `Crude Oil` (order 4)
6. `USD / INR` (order 5)

Rearrange: drag-and-drop modal updates `tempCharts` → confirmed → saved to `charts`. No OiPulse API call for chart data.

## Data source
- Charts: 6 × **`ssltvc.forexprostools.com`** iframes (Investing.com widget — NOT TradingView embed directly; Investing.com wraps TradingView).
- Ticker strip: `POST /api/gettickerdata`.
- No other OiPulse API calls on Dashboard.

## Replication notes (→ ArthaYantra)
- We already use **lightweight-charts**. Dashboard = a configurable grid of N panels, each bound to symbol+interval, with a TV-style toolbar (symbol, interval, indicators) and remove icon.
- "Customizable" ⇒ user adds/removes/swaps panels; persist layout per user.
- Ticker strip = horizontal auto-refresh quote marquee on our quote feed.
- Signal markers (B/S/C) = series markers on the candle series.

## Screenshots
ss_8268y3vcn (rows 1–2), ss_4159s9wxx (Crude Oil + USD/INR).
