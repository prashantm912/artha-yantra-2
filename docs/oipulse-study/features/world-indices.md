# World Indices — `/app/world-indices`

**Purpose:** single-glance global market map. A dark world map with a colored bubble on each
major economy showing that index/commodity's latest level and change. Quick read of global risk-on/off.

## Layout
```
sub-tabs: [ World Indices ] [ Tool ]
ticker strip
┌ page header bar ────────────────────────────────────────────────────────────────────┐
│ "World Map with Indices"            "Last Updated At: 16-06-2026 03:19:02"   Refresh  │ (Refresh = red link, right)
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                        │
│            [ grayscale world map, black background, country bubbles ]                   │
│                                                                                        │
└──────────────────────────────────────────────────────────────────────────────────────┘
footer: "Oi Pulse - Feel the pulse of our market"          [YT][X][Telegram]
```

## Components
| Component | Type | Position | Contents | Behavior / cues |
|---|---|---|---|---|
| Title | text | header left | "World Map with Indices" | static |
| Last Updated At | text + datetime | header center | `DD-MM-YYYY HH:MM:SS` | from API `stFetchDate`/`stFetchTime` |
| Refresh | link/button | header right | red text `Refresh` | re-fetches map data |
| World map | SVG/canvas map | full body | grayscale continents (`#ccc`-ish land on near-black sea) | static base layer |
| Index bubble ×N | circular marker | geo-positioned (lat/long) | 3 stacked lines: **value**, **±change%**, **±change points** | green circle = up, red circle = down |
| Bubble label | grey pill | directly under each bubble | index name (e.g. "Dow Jones (F)") | rounded grey tag |

### Bubbles observed (name → sample value / %chg / ptschg, color)
- S&P 500 (F) — 7626.5 / +2.58% / 191.5 — green (USA)
- Dow Jones (F) — 52129 / +1.76% / 902 — green (USA)
- NASDAQ 100 (F) — 30864.25 / +4.05% / 1202.25 — green (USA)
- (Iceland/N-Atlantic) — 10471.4 / -0.5% / -51.8 — **red**
- FTSE — 8381.92 / +0.35% / 28.88 — green (UK)
- DAX 40 (F) — 24919 / +1.17% / 288 — green (Germany)
- CAC 40 (F) — green (France)
- China A50 (F) — 15849.5 / 0% / -0.5 — green (China)
- Nikkei 225 (F) — 69740.8 / +3.57% / 2401.3 — green (Japan)
- Hang Seng (F) — 24813.3 / +0.37% / 90.5 — green (HK)
- NIFTY 50 (F) — 23930 / +0.99% / 235 — green (India)
- Gold/USD — 4308.85 / +2.14% / 90.29 — green (bottom band = commodities)
- Silver/USD — 69.985 / +2.909% / 1.978 — green
- Crude Oil (F) — 80.75 / -4.87% / -4.13 — **red**

Color rule: bubble fill = `--success` green if `inChangePoint`/`%` ≥ 0 else `--danger` red. Text inside bubble white.

## Data source / API
`POST /api/heatmap/getworldindicesdata` — empty request body `{}`.
```json
{ "status":"success","msg":"Data fetched successfully.",
  "data":[ {
    "stFetchDate":"16-06-2026", "stFetchTime":"03:19:02",
    "stIndiceName":"Dow Jones (F)",
    "inClose": 52129, "inChangePoint": 902, "inChangePercentage": 1.76,
    "objLocation": { "latitude": <num>, "longitude": <num>, "rotation": <num> }
  } ] }
```
`objLocation.latitude/longitude` position the bubble on the map; `rotation` for label offset.

## Replication notes (→ ArthaYantra)
- Base map: an SVG world map (e.g. ECharts geo / amCharts / svg-world-map). Plot one marker per index at its lat/long.
- Marker = circle colored by sign of change, with value/%/points stacked, name pill below.
- Bind to our own "world indices" feed mirroring `{stIndiceName, inClose, inChangePoint, inChangePercentage, objLocation}`.
- "Last Updated At" from server timestamp; Refresh re-pulls.

## Screenshot
ss_1498whgzz (full global map, mostly green risk-on, Crude red).
