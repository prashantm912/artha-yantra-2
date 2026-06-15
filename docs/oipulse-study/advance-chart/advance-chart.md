# Advance Chart — `/app/advance-chart`

**Purpose:** the pro charting page. A full-screen **TradingView charting library** wired to
**OiPulse's OWN NSE F&O datafeed** (not Investing.com), with custom OiPulse indicators, an OI
overlay, trade-history/audio-alert tooling, and 1Cliq trade integration. Top nav link (always visible).

## Layout
```
header (global)
┌ OiPulse chart toolbar ───────────────────────────────────────────────────────────────────────┐
│ [BANKNIFTY▾] [3m▾] [chart-type]  | Indicators  [layout]  | Show Trade history  Audio Alerts:Off │
│   Show Oi Bar (Beta)   ↶ ↷                         Save▾  ⚙  ⛶ fullscreen  📷  [Refresh](red)    │
├────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ▌drawing │  BANKNIFTY26JUNFUT · 3 · NSE   O H L C                                                │
│  rail    │  overlays: VWAP, VWMA 20, SuperTrend 10 2 (green/red bands)        price scale →      │
│          │  [candlesticks]                                                                        │
│          ├── OSPL Volume 20  (histogram, green/red)                                               │
│          ├── RSI 14 SMA 14   (purple line; 80/60/20 guides)                                       │
│          └ time axis (UTC+5:30)   5y 1y 6m 3m 1m 5d 1d   log auto                                 │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Components
| Component | Type | Position | Notes |
|---|---|---|---|
| Symbol selector | dropdown/search | toolbar L | NSE F&O symbols (BANKNIFTY → BANKNIFTY26JUNFUT) via `getlistofsymbols` |
| Interval | dropdown | toolbar | `3m` etc. |
| Chart type | icon | toolbar | candles/bars/line |
| Indicators | button | toolbar | opens TV indicator picker (+ OiPulse custom studies) |
| Layout grid | icon | toolbar | multi-pane layout |
| Show Trade history | toggle button | toolbar | overlays executed trades on chart |
| Audio Alerts | toggle | toolbar | `Off`/`On` price alerts |
| Show Oi Bar (Beta) | toggle | toolbar | overlays OI bar study |
| Undo/Redo | icons | toolbar | drawing history |
| Save | dropdown | toolbar R | save chart layout/template |
| Settings ⚙ / Fullscreen ⛶ / Snapshot 📷 | icons | toolbar R | TV chrome |
| Refresh | button (red) | toolbar R | reload data |
| Drawing rail | TV vertical toolbar | left | cursor, trendline, fib, text, ruler, magnet, zoom, fav, measure |
| Main pane | candlestick chart | center | with VWAP / VWMA20 / SuperTrend overlays + OHLC legend |
| OSPL Volume | sub-pane | below price | OiPulse volume histogram (green/red), MA |
| RSI | sub-pane | bottom | RSI 14 + SMA 14, purple, 80/60/20 levels |
| Range bar | TV footer | bottom | `5y 1y 6m 3m 1m 5d 1d`, time UTC+5:30, log/auto |

## Chart library
**TradingView Charting Library** (self-hosted) with a **UDF-style datafeed adapter** on OiPulse's API.
Custom studies (OSPL Volume, SuperTrend defaults, Oi Bar). Note: this differs from the Dashboard
panels (which use the Investing.com datafeed for global symbols); Advance Chart uses NSE F&O native data.

## Data source / API (TradingView datafeed adapter)
| Call | Request | Purpose |
|---|---|---|
| `/api/trading-view/getservertime` | — | server clock |
| `/api/trading-view/getlistofsymbols` | `{stUserInput, stSymbolType}` | symbol search/resolve |
| `/api/trading-view/getallstudytemplates` | — | saved indicator templates |
| `/api/trading-view/getcandledata` | `{ex, symbol, fromTs, toTs, resolution, countBack, limit, type}` | OHLCV history bars (the datafeed) |

`getcandledata` is the bar feed: exchange + symbol + time range + resolution → candles (standard TradingView `getBars`).

## Replication notes (→ ArthaYantra)
- We have lightweight-charts + a candle store. Advance Chart = our chart bound to our `/api/v1/market/candles` with a symbol search, interval, indicator overlays (VWAP/VWMA/SuperTrend/RSI/volume), and OI-bar overlay.
- The `{ex,symbol,fromTs,toTs,resolution,countBack,limit}` request maps cleanly to a getBars-style endpoint.
- Custom toggles (Trade history, Audio alerts, Oi Bar) are overlay layers on the chart.

## Screenshot
ss_6302qudsr (BANKNIFTY 3m + VWAP/VWMA/SuperTrend, OSPL Volume + RSI sub-panes).
