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

## Chart library
**amCharts 5** — `am5map.MapChart` with `am5map.MapPointSeries`. NOT ECharts. World map base layer
from amCharts geodata (`am5geodata_worldLow`). Each index = one `MapPoint` pinned by GeoJSON coordinates.

## Vue component state (confirmed — 3 keys only)
```
root          // amCharts5 Root instance (am5.Root)
pointSeries   // amCharts5 MapPointSeries (am5map.MapPointSeries)
stLastUpdatedAt  // "16-06-2026 15:05:01"
```
No socket subscription — data loaded once on mount, manual Refresh re-fetches.

## Components
| Component | Type | Position | Contents | Behavior / cues |
|---|---|---|---|---|
| Title | text | header left | "World Map with Indices" | static |
| Last Updated At | text + datetime | header center | `DD-MM-YYYY HH:MM:SS` | from `stLastUpdatedAt` (API timestamp) |
| Refresh | link/button | header right | red text `Refresh` | re-fetches API; updates map + timestamp |
| World map | amCharts MapChart | full body | grayscale continents on near-black sea | static base layer |
| Index bubble ×13 | amCharts MapPoint | geo-pinned by `geometry.coordinates` | 3 lines: **value**, **±%**, **±pts** | green fill ≥0 / red fill <0; white text |
| Bubble label pill | grey tag | below each bubble | index name e.g. "Dow Jones (F)" | rounded grey tag |

### Confirmed bubbles — live 16-06-2026 (title / value / %chg / pts)
| Title | Value | % | Pts | Region |
|---|---|---|---|---|
| S&P 500 (F) | 7624.25 | -0.03 | -2.25 | USA |
| Dow Jones (F) | 52166 | +0.07 | +37 | USA |
| NASDAQ 100 (F) | 30903.5 | +0.13 | +39.25 | USA |
| DAX 40 (F) | 25095 | +0.71 | +176 | Germany |
| FTSE 100 (F) | 10496.4 | +0.82 | +85 | UK |
| CAC 40 (F) | 8453.96 | +0.86 | +72.04 | France |
| Nikkei 225 (F) | 69648.3 | -0.13 | -92.5 | Japan |
| China A50 (F) | 15728.5 | -0.76 | -121 | China |
| Hang Seng (F) | 24501.3 | -1.26 | -312 | HK |
| NIFTY 50 (F) | 23999.5 | +0.45 | +108.5 | India |
| Gold/USD | 4344.61 | +0.83 | +35.76 | (S-Atlantic placeholder) |
| Silver/USD | 70.493 | +0.725 | +0.508 | (S-Atlantic placeholder) |
| Crude Oil (F) | 78.85 | -2.35 | -1.9 | (S-Atlantic placeholder) |

Color rule: fill green if `percentage ≥ 0`, red if `< 0`. Text inside bubble white.

## amCharts data item schema (confirmed — after server→amCharts transform)
Each MapPoint has these settings:
```json
{
  "title": "Dow Jones (F)",
  "value": 52166,
  "percentage": 0.07,
  "changePoint": 37,
  "rotation": 0,
  "geometry": { "type": "Point", "coordinates": [-103.6587, 42.3001] }
}
```
`geometry.coordinates` = [longitude, latitude] (GeoJSON order). `rotation` = label-arrow rotation angle (hint for crowded positions). Commodities (Gold/Silver/Crude) use placeholder coordinates in the S-Atlantic (~-40 to 0 lon, -44 to -52 lat) since they have no country.

## Data source / API
`POST /api/heatmap/getworldindicesdata` — request body likely `{}` or `{stSelectedModeOfData:"live"}` (direct fetch blocked by CORS in JS context; inferred from API namespace pattern).

Server response (inferred from field naming convention + prior study):
```json
{ "status":"success","msg":"Data fetched successfully.",
  "data":[ {
    "stFetchDate":"16-06-2026", "stFetchTime":"15:05:01",
    "stIndiceName":"Dow Jones (F)",
    "inClose": 52166, "inChangePoint": 37, "inChangePercentage": 0.07,
    "objLocation": { "latitude": 42.3001, "longitude": -103.6587, "rotation": 0 }
  }, ... ]
}
```
Vue component transforms server fields → amCharts: `stIndiceName→title`, `inClose→value`, `inChangePercentage→percentage`, `inChangePoint→changePoint`, `objLocation→geometry(GeoJSON)`.

## Replication notes (→ ArthaYantra)
- Use amCharts 5 (`am5map.MapChart` + `am5map.MapPointSeries`) or ECharts geo map.
- 13 fixed data points; coordinates are static (hardcoded or from server `objLocation`).
- Bubble = amCharts MapPoint bullet: circle colored by sign of `percentage`, label pill below.
- No socket — manual Refresh re-calls the API; update `stLastUpdatedAt` on response.
- Commodities rendered at S-Atlantic placeholder coords (no real country).

## Screenshot
ss_1498whgzz (full global map, mostly green risk-on, Crude red).
