# FII Derivative Stats — `/app/fii-dii/fii-derivative-stats`

**Purpose:** FII net activity across the four derivative segments (Index Futures/Options, Stock
Futures/Options), daily — charted + tabulated. Tracks FII derivative positioning. Sub-tabs: `FII Derivative Stats | FII & DII Activity`.

## Layout
```
sub-tabs: [ FII Derivative Stats ] [ FII & DII Activity ]   ;  ticker strip
        FII derivative stats (Values in Crores)   (title)
┌ bar chart — Index Futures ───────────────────────────────────────────────────────────────────────┐ (green +/red −, daily)
┌ bar chart — Index Options ───────────────────────────────────────────────────────────────────────┐ (range ~ −120k…+90k)
┌ bar chart — Stock Futures ───────────────────────────────────────────────────────────────────────┐
┌ bar chart — Stock Options ───────────────────────────────────────────────────────────────────────┐
        Detailed FII derivative stats (Values in Crores)   (table)
┌ Date | Index Futures | Index Options | Stock Futures | Stock Options ──────────────────────────────┐
```
**4 stacked net-value bar charts** + a detailed table. x = dates (≈365 days), y = Crore.

## Charts / columns
| Segment | Chart / column | Source key |
|---|---|---|
| Index Futures | bar / col | `statsObj.idx_fut` |
| Index Options | bar / col | `statsObj.idx_opt` |
| Stock Futures | bar / col | `statsObj.stk_fut` |
| Stock Options | bar / col | `statsObj.stk_opt` |

Bars/cells green if net positive (long), red if negative (short). Index Options has the widest range
(FII hedging dominates). ECharts bars, toolbox, watermark.

## Data source / API
`POST /api/fii-dii/getfiiderivativestatsdata` →
```json
{ "data":[ { "dtDate":"2024-12-19",
             "statsObj": { "idx_fut":-1519.35, "idx_opt":-78122.37, "stk_fut":-2974.21, "stk_opt":-195.15 } } ] }   // 365 days
```
One `statsObj` per day → four series.

## Replication notes (→ ArthaYantra)
- One daily endpoint with 4 net segment values → four `ay-echart` net-value bar charts + a 4-column table.
- Green/red by sign. (Index Options net is the headline FII sentiment proxy.)

## Screenshot
ss_8313wueqk (Index Futures/Options + Stock Futures/Options net bars).
