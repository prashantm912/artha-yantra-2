# Global shell & tech stack (every `/app` page)

## Tech stack (detected)
| Layer | Tech | Evidence / notes |
|---|---|---|
| SPA framework | **Vue 2** | `data-v-*` scoped attrs, `el.__vue__` instances, client-side routing |
| CSS framework | **Bootstrap 4** | `--breakpoint-*` (sm576/md768/lg992/xl1200), `badge badge-success/danger/info`, grid |
| Icon font | **Iconsmind / "custom-icon"** (Gull admin template lineage) | `<i class="custom-icon i-Triangle-Arrow-Up">`, `i-Triangle-Arrow-Down`, `i-Left---Right` |
| Charts (price) | **TradingView Advanced Charts** | Investing.com datafeed; used on Dashboard, Advance Chart |
| Charts (custom OI/data) | (to confirm per page — likely ECharts/Highcharts) | captured per page |
| Data API | **`https://api.oipulse.com/api/*`** | all POST, JSON `{status,msg,data}` envelope |
| Analytics | Google Analytics (G-TKMQEZY08L) | ignorable |

## Theme / design tokens (CSS custom properties on `:root`)
Dark shell. Bootstrap palette overridden to a **red/brown brand**:

| Token | Value | Use |
|---|---|---|
| `--primary` | `#c42b1e` | brand red (buttons, active, headings, "Go"/"Refresh") |
| `--secondary` | `#7c423e` | brown-grey |
| `--success` / `--green` | `#4caf50` | bullish / up |
| `--danger` / `--red` | `#f44336` | bearish / down |
| `--info` / `--blue` | `#003473` | neutral / info |
| `--warning` / `--yellow` | `#ffc107` | caution |
| `--orange` | `#e97d23` | accent |
| grays | `--gray-100 #f8f9fa` … `--gray-900 #6e3b37` (brown-tinted) | surfaces/borders |
| font | `-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial` | system sans |

Page background ≈ near-black `#0c0c0c`/`#111`; panels/cards slightly lighter dark; thin borders; white/light-grey text.

## Semantic signal system (reused across the app)
Bootstrap badge + Iconsmind arrow:
| State | Badge class | Color | Icon |
|---|---|---|---|
| Bullish / Up | `badge-success` | green `#4caf50` | `i-Triangle-Arrow-Up` (↑) |
| Bearish / Down | `badge-danger` | red `#f44336` | `i-Triangle-Arrow-Down` (↓) |
| Neutral | `badge-info` | blue `#003473` | `i-Left---Right` (↔) |

Numeric change values: green text if positive, red if negative, with ▲/▼ arrow prefix.

### Two distinct signal enums (CONFIRMED, reused app-wide)
**(a) 3-state sentiment** (Connecting Dots factor cells) — field `in*` per factor:
| int | meaning | badge | icon |
|---|---|---|---|
| `0` | Neutral | `badge-info` (blue) | `i-Left---Right` ↔ |
| `1` | Bullish | `badge-success` (green) | `i-Triangle-Arrow-Up` ↑ |
| `2` | Bearish | `badge-danger` (red) | `i-Triangle-Arrow-Down` ↓ |
(composite `inTrend`: 0 Neutral · 1 Bullish · 2 Ext.Bullish · 3 Bearish · 4 Ext.Bearish)

**(b) 4-state OI interpretation** (`inOiInterpretation`) — Futures/Options OI pages:
| int | label | abbrev | badge | color | price×OI |
|---|---|---|---|---|---|
| `1` | Long Buildup | L.B | `badge-success` | green | price↑ OI↑ |
| `2` | Long Unwinding | L.U | `badge-warning` | yellow | price↓ OI↓ |
| `3` | Short Buildup | S.B | `badge-danger` | red | price↓ OI↑ |
| `4` | Short Covering | S.C | `badge-info` | blue | price↑ OI↓ |

## Header (fixed top bar)
| Region | Contents | Behavior |
|---|---|---|
| Left | OiPulse logo (red waveform) | → `/app/dashboard` |
| Center-left nav | `All Menu ▾` · `Dashboard` · `Connecting Dots` · `Advance Chart` | text links; All Menu opens mega-dropdown |
| Right | red **`IC | 1Cliq`** badge · circular user avatar | avatar → account menu |

The account/avatar menu lists: Profile settings · Plan billings · Contact Us · Sign out. The top-right also carries a full-screen toggle, and the left sidebar has a minimise/collapse control.

### All Menu mega-dropdown
Full-width dark panel. Columns by section: **Features** (icon grid, 10 items) · **Advance Chart** (2) · **Futures** (8) · **Options** (14) · **Strategies** (7) · **Equity** (8) · **FII/DII Activity** (4). Each item = router link (`<a href="/app/...">`). Opens on click, closes on outside-click/navigation (NOT Escape). Links remain in DOM when closed (SPA-clickable).

## Sub-tab row (per page, below header)
Left-aligned local tabs. Pattern: `<PageName> | Tool` (e.g. `Dashboard | Tool`, `Connecting Dots | Tool`, `World Indices | Tool`). "Tool" = secondary view/utility for that page.

## Live ticker strip (below sub-tabs, most pages)
Horizontal auto-scrolling quotes. Format per item: `SYMBOL(F): price ±chg (±%)`, green ▲ up / red ▼ down. Source: `POST /api/gettickerdata`. Instruments: BANKNIFTY, AXISBANK, HDFCBANK, ICICIBANK, INFY, KOTAKBANK, RELIANCE, TCS, NIFTY, … (F)=futures.

**Live socket (confirmed 2026-06-18):** the strip updates via `TICKER_DATA` — frame ARRAY[2] `[symbol, ltp]`
(e.g. `["HDFCBANK-I",789.3]`), the highest-frequency channel on the socket (~2000 frames/hour); the ±chg/±%
are derived client-side. `TICKER_RESET_DATA` is also registered (fires on a reset/day rollover). The strip is
toggleable in the owner's profile — when disabled, neither channel subscribes. See [Phase B findings](PHASE-B-FINDINGS.md).

## Footer (some pages)
`Oi Pulse - Feel the pulse of our market` + social icons (YouTube/Twitter/Telegram, red) bottom-right.

## API envelope (universal)
All data calls: `POST https://api.oipulse.com/api/<area>/<action>` with a JSON body of `st*`/`in*`-prefixed params, returning:
```json
{ "status": "success", "msg": "Data fetched successfully.", "data": <array|object> }
```
Field naming convention: `st*` = string, `in*` = number/int (also used for signal enums), `obj*` = nested object, `dt*` = date.

## Replication mapping (→ ArthaYantra Angular 21 + PrimeNG 21)
- Vue2+Bootstrap badges → PrimeNG `p-tag`/`p-badge` + our `.ay-dark` theme tokens; map their semantic colors to our success/danger/info.
- Iconsmind arrows → PrimeIcons (`pi-arrow-up`/`pi-arrow-down`/`pi-arrows-h`) or inline SVG.
- TradingView panels → our lightweight-charts wrapper.
- `{status,msg,data}` envelope → our gateway already uses `{items:[...]}`; map accordingly.
- Per-page tables (mostly plain scrollable, paginated) → PrimeNG `p-table` (NON-virtual; zoneless caveat).
