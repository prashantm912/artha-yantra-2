# Advance Chart — `/app/advance-chart`

**Purpose:** the pro charting page. A full-screen **TradingView lightweight charting library** (in an iframe)
wired to **OiPulse's OWN BSE/NSE F&O datafeed** (not Investing.com), with custom OiPulse indicators, an OI
overlay, trade-history/audio-alert tooling, and 1Cliq trade integration. Top nav link (always visible).
**Default indicators (confirmed live 2026-06-18):** VWAP, **VWMA(20)** (a volume-weighted MA, *not* a plain
20-EMA), SuperTrend(10,2), OSPL Volume(20), RSI(14, SMA 14).

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

## Vue component state (confirmed)
```
websiteUrl, enabledNotification, notificationAudioFile,
tvWidget,                     // TradingView IChartingLibraryWidget instance
showTradeHistoryFlag,
latestActiveSymbol ("BANKNIFTY-I"),
prevPaneHeight, resizeTimeout,
stCvsId, mouseMoveHandler,
showOiBars (false),           // "Show OI Bar (Beta)" toggle
socketReconnecting,
socketSubscribedEvents ([]),  // live price subscription (empty on initial load)
latestPriceRangeIntervalId, latestPriceRange,
chartEle, chartCvs, chartCvsCtx, oiBarChart,   // canvas overlay for OI bars
widgetOptions                 // TV widget config (see below)
```

## TradingView widgetOptions (confirmed)
```json
{
  "symbol": "BANKNIFTY-I",
  "interval": "3",
  "container": "chart_1",
  "library_path": "/charting_library/",
  "datafeed": "<OiPulse UDF adapter>",
  "timezone": "Asia/Kolkata",
  "locale": "en",
  "theme": "Dark",
  "enabled_features": ["countdown","study_templates","side_toolbar_in_fullscreen_mode","header_in_fullscreen_mode","timezone_menu"],
  "disabled_features": ["create_volume_indicator_by_default","popup_hints","go_to_date","symbol_info","header_compare","volume_force_overlay","source_selection_markers","use_localstorage_for_settings"],
  "fullscreen": false,
  "autosize": true,
  "load_last_chart": true,
  "auto_save_delay": <N>,
  "custom_indicators_getter": "<function>",
  "save_load_adapter": "<object>"
}
```

## Chart library
**TradingView Charting Library** (self-hosted at `/charting_library/`) with a **UDF-style datafeed adapter** on OiPulse's API.
Custom studies (OSPL Volume, SuperTrend defaults, Oi Bar). Note: this differs from the Dashboard
panels (which use the Investing.com `ssltvc.forexprostools.com` iframes); Advance Chart uses NSE F&O native data.

"Open Interest" is a named live sub-pane study (futures OI + change, with a live label like
"Open Interest 2.516M"), distinct from any OI-bar toggle. The **OSPL Volume** indicator IS one of the
**default** indicators (confirmed live 2026-06-18 — coloured volume bars, green/red). Its only **Inputs**
are **MA Length = 20** + a "Color based on previous close" toggle (confirmed live 2026-06-18). It colours a
volume candle dark when volume is above a threshold (manual: >50K BankNifty / >125K Nifty futures);
that dark-bar threshold is **hardcoded in the Pine script — not a user input, not exposed in the legend**,
so it stays **manual-sourced** (not user-configurable; not readable off the live UI). See
[PHASE-B-FINDINGS.md](../PHASE-B-FINDINGS.md) §6.
The chart supports multiple saved indicator templates (the Save dialog has Remember Symbol /
Remember Interval; a "MY TEMPLATES" switcher; example `INTRADAY_SCALPING` = VWAP + SuperTrend +
VWMA + OSPL Volume + RSI + Open Interest) and an unlimited indicator count. Clarification on the
Audio-Alerts toolbar button: it is specifically the OSPL-Signal sound alert (Yes/No enable dialog),
not a generic price alert — see [ospl-signal.md](ospl-signal.md).

## Data source / API (TradingView datafeed adapter, namespace `trading-view`)
| Call | Request | Purpose |
|---|---|---|
| `getservertime` | — | server clock |
| `getlistofsymbols` | `{stUserInput, stSymbolType}` | symbol search/resolve |
| `getallstudytemplates` | — | saved indicator templates |
| `getcandledata` | `{ex, symbol, fromTs, toTs, resolution, countBack, limit, type}` | OHLCV history bars (the TV datafeed `getBars`) |

`getcandledata` is the bar feed: exchange + symbol + time range + resolution → candles. Called multiple times per page load (initial bars + pagination).

## Interpretation (how to trade)
- Top-down workflow: analyse Weekly → Daily → 3-min, each saved as a template; a Double-SMA(100,200) overlay is the recommended daily preset.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.
Default-indicator set + VWMA (not 20-EMA) + OSPL-Volume verified live —
see [PHASE-B-FINDINGS.md](../PHASE-B-FINDINGS.md) (V16).

## Replication notes (→ ArthaYantra)
- We have lightweight-charts + a candle store. Advance Chart = our chart bound to our `/api/v1/market/candles` with a symbol search, interval, indicator overlays (VWAP/VWMA/SuperTrend/RSI/volume), and OI-bar overlay.
- The `{ex,symbol,fromTs,toTs,resolution,countBack,limit}` request maps cleanly to a getBars-style endpoint.
- Custom toggles (Trade history, Audio alerts, Oi Bar) are overlay layers on the chart.

## Screenshot
ss_6302qudsr (BANKNIFTY 3m + VWAP/VWMA/SuperTrend, OSPL Volume + RSI sub-panes).
