# Connecting Dots — `/app/connecting-dots`

**Purpose:** Multi-factor sentiment matrix. For a chosen index instrument, each interval gets a row;
each column is a market factor rated Bullish/Bearish/Neutral (0/1/2), plus a composite **Trend**.
Lets a trader "connect the dots" across factors per snapshot to read intraday bias.
Sub-tabs: `Connecting Dots | Tool`.

## Layout (top → bottom)
```
sub-tabs: [ Connecting Dots ] [ Tool ]
ticker strip
┌ filter bar ──────────────────────────────────────────────────────────────────────────┐
│ Mode: (•)Live data ( )Historical   Name:[BANKNIFTY ▾]   Date:[Tue, Jun 16, 2026 📅] │
│                                    Time Interval:[3 min ▾]   Actions:[ Go ](red)     │
└──────────────────────────────────────────────────────────────────────────────────────┘
┌ data table (vue-good-table, scrollable, paginated) ──────────────────────────────────┐
│ #  Date Time | Trend | Dow Jones | Vix | Volume | Active Strike IV | Active Strike OI │
│              | OI Inter. | VWAP | Supertrend | RSI | Price | Daily Trend              │
│ rows: latest interval first (descending)                                               │
└──────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[25▾]          1 - 25 of 82        ‹ Previous   Next ›
┌ Signals legend ──────────────────────────────────────────────────────────────────────┐
│ [Ext. Bullish ↑] [Ext. Bearish ↓] [↑ = Bullish] [↓ = Bearish] [↔ = Neutral]        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

## Filter bar — exact controls
| Control | Type | Values | Notes |
|---|---|---|---|
| Mode | radio | `live` / `historical` | |
| Name | select | **BANKNIFTY** (value:`BANKNIFTY-I`), **NIFTY** (value:`NIFTY-I`), **FINNIFTY** (value:`FINNIFTY-I`), **MIDCPNIFTY** (value:`MIDCPNIFTY-I`) | **INDICES ONLY** — no stocks, only 4 options; values use "-I" suffix |
| Date | date picker | dates from `getselectedassetdate` (min 2019-01-01, max today; only 2 dates available in live mode: null placeholder + today) | |
| Time Interval | select | `3`(3 min), `5`(5 min), `10`(10 min), `15`(15 min), `30`(30 min), `60`(60 min) | no null/placeholder, starts at 3 min |
| Go | button (red) | triggers `getselectedassetalldata` | |

**Important:** Asset values sent to API include "-I" suffix (e.g. `stSelectedAsset:"BANKNIFTY-I"`), not bare "BANKNIFTY".

## Data table — 13 columns (confirmed exact order)
| # | Header | API field | Cell render |
|---|---|---|---|
| 1 | Date Time | `stTimeInterval` | "13:18-13:20"; width 70px |
| 2 | **Trend** | `inTrend` | 5-state composite badge; width 85px |
| 3 | Dow Jones | `inDow` | 3-state arrow badge; width 70px |
| 4 | Vix | `inVix` | 3-state arrow badge; width 70px |
| 5 | Volume | `inVolume` | 3-state arrow badge; width 70px |
| 6 | Active Strike IV | `inActiveStrikeIv` | 3-state arrow badge; width 70px |
| 7 | Active Strike OI | `inActiveStrikeOi` | 3-state arrow badge; width 70px |
| 8 | OI Inter. | `inSelectedFutOi` | 3-state arrow badge; width 70px |
| 9 | VWAP | `inVwap` | 3-state arrow badge; width 70px |
| 10 | Supertrend | `inSupertrend` | 3-state arrow badge; width 70px |
| 11 | RSI | `inRsi` | 3-state arrow badge; width 70px |
| 12 | Price | `inSelectedFutPrice` | 3-state arrow badge; width 70px |
| 13 | Daily Trend | `inDailyTrend` | 3-state arrow badge; width 70px |

## Cell encoding (CONFIRMED from live Vue data)

**Factor columns** (3-state, all factor columns #3-13):
| int | meaning | badge class | icon class | color |
|---|---|---|---|---|
| `0` | Neutral | `badge-interpretation badge-info` | `i-Left---Right` (↔) | blue |
| `1` | Bullish | `badge-interpretation badge-success` | `i-Triangle-Arrow-Up` (↑) | green |
| `2` | Bearish | `badge-interpretation badge-danger` | `i-Triangle-Arrow-Down` (↓) | red |

**Composite `inTrend`** (5-state `badge-trend-label`):
| int | label | color |
|---|---|---|
| `4` | Ext. Bearish ↓ | red (CONFIRMED from prior study) |
| `3` | Bearish ↓ | red (CONFIRMED from prior study) |
| `2` | Bullish ↑ | green (inferred — live row 13:15-13:18 shows mix of bullish/bearish→inTrend=2 with slightly more bullish factors) |
| `1` | Ext. Bullish ↑ | green (inferred) |
| `0` | Neutral ↔ | blue (inferred) |

Row striping: extreme-trend rows get faint maroon background tint.

## Live data sample (2026-06-16 BANKNIFTY 3-min):
```json
[
  { "stTimeInterval":"13:18-13:20", "inDow":0, "inVolume":0, "inDailyTrend":2,
    "inSelectedFutPrice":2, "inSelectedFutOi":2, "inVix":1,
    "inActiveStrikeOi":2, "inActiveStrikeIv":0, "inVwap":2, "inRsi":1,
    "inSupertrend":2, "inTrend":3 },
  { "stTimeInterval":"13:15-13:18", "inDow":2, "inVolume":0, "inDailyTrend":2,
    "inSelectedFutPrice":1, "inSelectedFutOi":1, "inVix":1,
    "inActiveStrikeOi":2, "inActiveStrikeIv":1, "inVwap":2, "inRsi":1,
    "inSupertrend":1, "inTrend":2 }
]
```
82 total rows for the session (3-min intervals from 09:15–13:20 IST).
`inDow:0` = Neutral (when Dow data not applicable/unavailable during Indian market hours).

## Data source / API
| Endpoint | Request | Response |
|---|---|---|
| `POST /api/connecting-dots/getselectedassetdate` | `{stSelectedAsset:"BANKNIFTY-I", stSelectedModeOfData:"live"}` | `{data:[{text:"2026-06-16",value:"2026-06-16"}]}` — API returns dates only; null placeholder is added client-side by Vue |
| `POST /api/connecting-dots/getselectedassetalldata` | `{stSelectedAsset:"BANKNIFTY-I", stSelectedAvailableDate:"2026-06-16", stSelectedTimeInterval:3, stSelectedModeOfData:"live"}` | `{data:[<row>×82]}` |

Row schema (13 fields all integers except `stTimeInterval`):
```json
{
  "stTimeInterval": "09:18-09:21",
  "inDow": 1, "inVix": 1, "inVolume": 2,
  "inActiveStrikeIv": 2, "inActiveStrikeOi": 1,
  "inSelectedFutOi": 2, "inVwap": 2, "inSupertrend": 1,
  "inRsi": 1, "inSelectedFutPrice": 2, "inDailyTrend": 2, "inTrend": 2
}
```

## Socket subscriptions ([Phase B confirmed](../PHASE-B-FINDINGS.md))
**None.** Live Phase-B capture found connecting-dots is **REST-only — it subscribes NO socket
channels** (verified after a Go click). Data loads and refreshes via the REST endpoints above.

## Pagination
25 rows per page (default); `N - M of TOTAL` counter; Previous / Next buttons. Client-side over full session's intervals (~82 for 3-min on a live day).

## Signals legend (bottom card)
Five pills: `Ext. Bullish ↑` (green), `Ext. Bearish ↓` (red), `↑ = Bullish` (green), `↓ = Bearish` (red), `↔ = Neutral` (blue).

## Interpretation (how to trade)
- The "connect the dots" framework combines six inputs — Global Markets, Futures OI, Options OI, India VIX, Implied Volatility, and Price Action. Take a trade when the majority of inputs align; discount the dissenters.
- Global-input priority order: Dow Jones → Crude → USD-INR → SGX/GIFT Nifty → Europe. USD-INR is inverse; SGX/GIFT Nifty leads the pre-open; discount global markets entirely on big domestic-news days.
- The page name derives from this thesis — "connect all the dots".

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- **4 index instruments only** (BANKNIFTY-I, NIFTY-I, FINNIFTY-I, MIDCPNIFTY-I) — not stocks
- Asset select value has "-I" suffix; display text is bare name (e.g. `value:"BANKNIFTY-I"` displayed as "BANKNIFTY")
- 13 columns: stTimeInterval + inTrend (5-state) + 11 factor columns (3-state 0/1/2)
- Each factor is a precomputed server-side signal; we compute: Supertrend, RSI, VWAP, VIX comparison, OI interpretation (from futures OI), active-strike IV/OI, volume, Dow correlation, daily trend
- Composite Trend = weighted/majority-vote over factors; 5-state output
- Render: PrimeNG `p-table`, paginator(25), each factor cell = `p-tag` with arrow icon; composite Trend = wider tag
- Legend card below table
- Extreme rows: faint maroon row-class tint
- ~125 rows/session at 3-min for a full day (9:15–15:30 IST = 375 min / 3 = 125 intervals). Partial-day reads return fewer rows.

## Screenshot
ss_533127vwh (live BANKNIFTY 3-min matrix, 82 rows).
