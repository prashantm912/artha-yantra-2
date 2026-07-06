# Equity Market-Data Pages — Data-Correctness Audit

**Date:** 2026-07-06 (Monday), audited ~10:45–10:50 IST with the **market LIVE** (all exchanges `NORMAL_OPEN`).
**Scope:** 10 equity pages under `frontend-react/src/pages/equity/`, read-only.
**Method:** read each page + its API hook → hit the live `market-data-service` (port 8081, path `/api/v1/...`) → reconcile against the live `artha` TimescaleDB.

## Environment facts (load-bearing)
- DB `now()`: IST 2026-07-06 10:44, UTC 05:14. In-container `::date` is UTC (accounted for below).
- **Latest FULL EQ bhavcopy session = 2026-07-03 (Friday), 3283 rows** (2384 in `series='EQ'`). Today (07-06) has **no** bhavcopy yet (EOD source) — every bhavcopy-backed page correctly serves Friday.
- **2026-07-02 is a PARTIAL/corrupt bhavcopy: only 266 total rows (167 `EQ`)** vs a normal ~2384 EQ. This is an ingestion gap, not a page bug, but it leaks into the `r1d` (rn=2) window of Equity Returns and creates a hole in per-stock delivery series for stocks that traded that day.
- Index selector (`/market/futures/oi-buzz-indices`) returns `["NIFTY 50","NIFTY BANK","NIFTY 200"]`; pages default to NIFTY 50.
- Base path: FE `/market/...` → BE `/api/v1/market/...` (client `BASE='/api/v1'`).

---

## 1. AnnouncementPage
- **Endpoint:** `GET /api/v1/market/equity/announcements?from&to[&symbol]` → `{items:[]}` (announcements.ts). Backend `AnnouncementController` (Upstox/NSE-sourced).
- **Data date/freshness:** **LIVE, real-time.** Top row = `UCAL` filed **2026-07-06 10:46:06** (during the audit), real NSE PDF links (`nsearchives.nseindia.com/...`).
- **DB reconciliation:** N/A (external NSE feed, no local table). Rows carry valid symbol/company/subject/detail/fileLink.
- **VERDICT: CORRECT / FRESH.**

## 2. BreadthPage
- **Endpoint:** `GET /api/v1/market/breadth?date=YYYY-MM-DD` (`useBreadth`). BE `BreadthService`: `count(*) FILTER (close>prev)` etc. over `nse_eod_bhavcopy WHERE trade_date=? AND series='EQ'`.
- **Data date/freshness:** Defaults to last weekday = **2026-07-03**. Today (07-06) correctly **422s** (no bhavcopy) → page shows empty state.
- **DB reconciliation (2026-07-03):** endpoint `advances=1227, declines=1123, unchanged=34, total=2384, avgDeliveryPct=55.7767…`. DB SQL returns **1227 / 1123 / 34 / 2384 / 55.7768** — EXACT. **1227+1123+34 = 2384 = total** (reconciles to the universe).
- **VERDICT: CORRECT.** (Delivery-% leaders board correctly filters ETFs client-side.)

## 3. DeliveryDataPage
- **Endpoint:** `GET /api/v1/market/equity/delivery?symbol&days` (`useEquityDelivery`). BE `EquityDeliveryService`, per-symbol daily EQ bhavcopy series.
- **Data date/freshness:** newest row = **2026-07-03**, walking back N sessions.
- **DB reconciliation (AXISBANK, 07-03):** endpoint `deliveryPct 58.50, deliveryQty 2047608, totalTradedQty 3500465, close 1342.1, ltpChangePct -1.50`. DB: `deliv_per 58.50, deliv_qty 2047608, ttl_trd_qnty 3500465` — EXACT.
- **Note:** AXISBANK series jumps 07-03 → **07-01 (07-02 missing)** because AXISBANK isn't in the 167-row partial 07-02 bhavcopy. Correct given the source; the underlying gap is the 07-02 ingestion issue.
- **VERDICT: CORRECT** (with the upstream 07-02 data-gap caveat).

## 4. EquityReturnsPage
- **Endpoint:** `GET /api/v1/market/equity/returns` (no params, `useEquityReturns`). BE `EquityReturnsService`.
- **Data date/freshness:** `asOf=2026-07-03`. All windows (r1d/r1w/r1m/r6m/r1y) populated — history has accrued (~1yr deep).
- **DB reconciliation (360ONE r1d):** endpoint `r1d=3.69`. DB: rn1(07-03)=1109.5, rn2(07-02)=1070.0 → (1109.5−1070)/1070×100 = **3.69** — EXACT.
- **⚠ By-design caveats (NOT wrong numbers, but worth knowing):**
  1. Windows are ranked by **row-recency (rn 2/6/22/127/253), not calendar dates** — documented in the service Javadoc. For a stock with trading gaps, "253 rows back" ≠ ~1 calendar year. Faithful to the oipulse design but not a true calendar-anchored return.
  2. **`r1d` base = rn=2 = the 07-02 PARTIAL day** for stocks that traded then. 360ONE's r1d is thus measured vs a Wednesday-partial close, not the prior full session (07-01). Slightly distorts today's r1d for names present in the 167-row 07-02 set.
  3. **Latest-per-symbol staleness (see Sector Stats):** `c0` (rn=1) can be a pre-07-03 close for thin names, so a few rows' "current" return is off a stale session under a single "07-03" badge.
- **VERDICT: CORRECT** for the reconciled math; **degraded** by the rn-vs-calendar design + 07-02 partial-day base.

## 5. IndexContributionPage
- **Endpoint:** `GET /api/v1/market/equity/index-contribution?name=<index>[&mode=live]` (`useIndexContribution`). BE `EquityIndexContributionService`: contribution = free-float weight × %chg / 100; points = contribution × indexLevel / 100.
- **Data date/freshness:** **LIVE fold served** (`live:true`, `asOf` today, `indexLevel≈24407`). Live %chg = (ltp − prevClose)/prevClose off the venue quote; falls back to EOD off-hours.
- **DB / math reconciliation (NIFTY 50, live):**
  - `advanceTotal 0.76 + declineTotal −0.22 = 0.54 = indexChangePct` — **sums exactly**.
  - **34 advances + 16 declines = 50** = full NIFTY 50 (no missing/dup constituents).
  - Per-row points sum to `advancePoints`/`declinePoints` exactly; net ≈ +135 pts → implied prevClose ≈ **24274.9 ≈ Friday's NIFTY 50 close** (real).
  - HDFCBANK: points 71.15 = 0.2915 × 24407.1 / 100 ✓; contribution/weight math consistent.
- **VERDICT: CORRECT.** (Best-behaved page — live fold, sums reconcile, points sane.)

## 6. NewsPage
- **Endpoint:** `GET /api/v1/market/equity/news?symbol=<sym>` (`useEquityNews`). BE `EquityNewsController` → Upstox `/v2/news`.
- **Data date/freshness:** **LIVE.** RELIANCE → `available:true`, real Upstox articles, `publishedTime` epochs decode to **2026-07-03 / 07-02** (recent). `available:false` empty-state wired for mock.
- **DB reconciliation:** N/A (external feed).
- **Minor note:** copy says "last 7 days"; the feed returned articles up to 3 days old (feed freshness, not a bug).
- **VERDICT: CORRECT / FRESH.**

## 7. OpenHighLowPage
- **Endpoint:** `GET /api/v1/market/equity/open-high-low?name=<index>` (`useOpenHighLow`). Computed **live** from near-month future / constituent quotes (day open/high/low + LTP).
- **Data date/freshness:** **LIVE**, `asOf` today.
- **Reconciliation (NIFTY 50, live):** GRASIM openHigh (open=high 3220.3, ltp 3213.2) → farPct = (3213.2−3220.3)/3220.3×100 = **−0.22** ✓. openLow TRENT (open=low 3317.4, ltp 3341.1) → +0.71 ✓. openHigh=7 names, openLow=2 names (plausible mid-morning).
- **VERDICT: CORRECT.**

## 8. PreOpenMarketPage
- **Endpoints:** `/market/market-status`, `/market/pre-open`, `/market/equity/pre-open-scan[?date]` (preOpen.ts).
- **Data date/freshness:** all **LIVE.**
  - market-status: NSE/BSE/MCX all `NORMAL_OPEN`, asOf today (market open, past 09:15 — pre-open window has passed, correctly shows Normal Open).
  - pre-open snapshot: Nifty 50 24410.55 (+0.58%), Nifty Bank, Fin Service, Sensex — asOf 10:47 today.
  - pre-open-scan: **date 2026-07-06, 210 F&O stocks**, `dates=[2026-07-06, 2026-07-03]` (today's ~09:09:30 capture preserved).
- **Reconciliation:** Nifty netChange 139.7 = 24410.55 − 24270.85 ✓; changePct 0.58 = 139.7/24270.85 ✓. Scan GODREJCP change 18.1 = 1095.0−1076.9 ✓, chg% 1.68 ✓. prevDayBreak "H" for OBEROIRLTY: preOpen 1960.5 > prev-day (07-03) high 1944.3 ✓.
- **VERDICT: CORRECT / FRESH.**

## 9. SectorHeatmapPage
- **Endpoint:** `GET /api/v1/market/equity/sector-heatmap?name=<index>` (`useSectorHeatmap`). BE `EquitySectorService.sectorHeatmap` — index constituents, %chg = close vs prev_close from the **latest bhavcopy row per symbol**.
- **Data date/freshness:** EOD, `asOf=2026-07-03` (EodBadge shown). NIFTY 50 constituents are all daily-liquid → all on 07-03.
- **DB reconciliation (NIFTY 50):** ADANIENT %chg **1.09** (close 3212.1 / prev 3177.5 ✓), AXISBANK **−1.50** ✓, APOLLOHOSP **2.27** ✓ — EXACT.
- **Aggregation label check:** page/comment say sized by **|% change|** (feed carries no market cap), coloured by %chg green→red. Code does exactly that (`value = |pct|+0.3`, not cap-weighted). **Labels match behavior** — honest.
- **⚠ Latent staleness bug (low severity for this page):** `latestMapped()` picks `ROW_NUMBER() PARTITION BY symbol ORDER BY trade_date DESC` = **most-recent row per symbol across ALL dates**, so a thin name's "latest" can be an older session. For NIFTY 50/BANK/200 constituents this never bites (they trade daily → all 07-03), so the heatmap is correct today. It WOULD bite an illiquid custom index.
- **VERDICT: CORRECT** for the index heatmaps (constituents all fresh 07-03).

## 10. SectorStatsPage
- **Endpoint:** `GET /api/v1/market/equity/sector-stats` (no params, `useSectorStats`). BE `EquitySectorService.sectorStats`.
- **Data date/freshness — MIXED, and labeled as such:**
  - `sectorIndices` cards (NIFTY 50/AUTO/BANK/…): **LIVE** — asOf "06-Jul-2026 10:44", NIFTY 50 last 24402.3 (+0.54%). Page labels this block "Sector Indices · live" ✓.
  - Per-sector aggregate cards + the per-stock factor table + top-level `asOf`: **EOD 2026-07-03** (EodBadge shown). Aggregation = **simple average of constituent %chg** (`sum/counted`), advancer/decliner by sign — matches the page copy ("per-sector average change (from constituents)"). **Not cap-weighted, and the page says so.** Honest.
- **⚠ Latent staleness bug (medium — this page shows the WHOLE mapped universe, not just index members):** the same `latestMapped()` cross-date "latest per symbol" feeds the full stock table. **357 sector-mapped symbols have a `latest` row NOT on 2026-07-03** (mostly 07-01 for illiquid small-caps + AXIS* ETFs). Their `Chg. %` is computed off an **older session's close/prev_close** but the table sits under a single "as of 2026-07-03" EodBadge. So a minority of the ~2000-row table shows a stale, mislabeled % change. Sector-average cards inherit a small distortion from these rows. Major constituents are unaffected.
- **DB reconciliation:** the per-stock %chg for names present on 07-03 matches (same formula as Breadth/Heatmap, verified there). Sector-index live cards match the live index quotes (cross-checked vs pre-open/contribution index levels).
- **VERDICT: CORRECT numbers for fresh names; DEGRADED — 357 stale-session rows in the full stock table are presented under a single "07-03" badge (mislabeled freshness).**

---

## Cross-cutting findings
1. **`latestMapped()` cross-date staleness** (shared by SectorStats, SectorHeatmap, IndexContribution EOD-fold, and structurally by EquityReturns): `ROW_NUMBER() PARTITION BY symbol ORDER BY trade_date DESC` returns the newest row **per symbol**, which can straddle multiple trade dates. 357 EQ symbols today resolve to a pre-07-03 "latest". Effect: a few % changes are off an older session but shown under one "as of 2026-07-03" label. **Low impact on index-scoped views** (constituents trade daily); **medium on the full-universe SectorStats table.** Not a wrong-number bug in the reconciled math — a freshness-labeling / mixed-vintage issue. A `WHERE trade_date = (SELECT max(trade_date) …)` guard would pin every row to one session.
2. **2026-07-02 partial bhavcopy (167 EQ rows vs ~2384).** Upstream ingestion gap. Leaks into Equity Returns' `r1d` base (rn=2) and punches holes in per-stock Delivery series. Worth a backfill/re-fetch of the 07-02 NSE EQ bhavcopy.

## Summary verdicts
| Page | Endpoint | Freshness | Verdict |
|---|---|---|---|
| Announcement | /market/equity/announcements | LIVE (07-06 10:46) | CORRECT |
| Breadth | /market/breadth | EOD 07-03 (today 422s) | CORRECT (reconciles exactly) |
| Delivery Data | /market/equity/delivery | EOD 07-03 | CORRECT (07-02 gap caveat) |
| Equity Returns | /market/equity/returns | EOD 07-03 | CORRECT math; DEGRADED (rn-not-calendar + 07-02 base) |
| Index Contribution | /market/equity/index-contribution | LIVE | CORRECT (sums + points reconcile) |
| News | /market/equity/news | LIVE (Upstox) | CORRECT |
| Open=High/Low | /market/equity/open-high-low | LIVE | CORRECT |
| Pre-Open Market | /market/{market-status,pre-open,equity/pre-open-scan} | LIVE (210 scan rows today) | CORRECT |
| Sector Heatmap | /market/equity/sector-heatmap | EOD 07-03 | CORRECT (index constituents all fresh) |
| Sector Stats | /market/equity/sector-stats | LIVE indices + EOD 07-03 | DEGRADED (357 stale rows under one badge) |

**No confirmed wrong-number bugs.** All reconciled figures matched the DB to the decimal. The two real issues are freshness/vintage hygiene (cross-date `latestMapped`) and an upstream 07-02 partial-bhavcopy ingestion gap.
