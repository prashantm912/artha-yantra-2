# Minervini SEPA — autonomous build log

**Status:** ACTIVE (overnight autonomous build, 2026-07-04). Companion to the
[implementation plan](2026-07-04-minervini-sepa-implementation-plan.md) — this log is the
per-PR record; the plan's item Status/Evidence cells are the canonical tracker.

**Guardrails in force:** real Upstox analytics token via app config (never printed); no live
trades; test data loads only (~2–3 months), no unsupervised heavy backfill; deploy + off-hours
live-verify where possible; branch + PR per batch; **auto-merge only on CI-green (admin)**; never
push `main` directly.

**Key recon (2026-07-04, live stack up, profile=live, analytics enabled):**
- **Screener source = `nse_eod_bhavcopy`** (master-plan §13.2; corrected during PR-A live-verify).
  The BROAD equity universe with a full recent year lives here (**2,224 EQ/BE names ≥252 sessions;
  1,590 scanned / 210 pass all 8 gates on 2026-07-03**). Native `candles`@1d recent-year is only
  ~106 names (bhavcopy is `DO-NOTHING` on the candle PK so it never fills the year there; the deep
  candle history is source=KITE/BACKFILL for subscribed/backfilled names only). candles@1d keeps its
  **11y depth** for those ~1,671 names → the *backtest* uses candles@1d; the *screener* uses bhavcopy.
- Upstox **Fundamentals API is real** (analytics token): Share-Holdings → free-float%; Key-Ratios →
  P/E, P/B, ROE; Income-Statement → sales/margins. **No company market cap** field → derive
  `mcap = P/E × net_income`. Company Profile market cap is *sector-level* only.
- `BhavcopyBackfillService` already applies **NSE/BSE corporate-action split/bonus** adjustment.

---

## PR-A — Phase 2 core: the price-only Trend-Template screener  ✅ MERGED-PENDING-CI

**Delivers:** the load-bearing 80/20 — a daily Minervini screener over the dense native daily store,
the 8 Trend-Template gates + cross-sectional IBD RS-rank + Stage label + owner price/liquidity gates,
persisted + served, with an integration test.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-0.1 | Flyway heads confirmed | DONE | marketdata V030→**V031** allocated |
| MV-0.2 | Canonical dense source picked | DONE | **`nse_eod_bhavcopy`** (2,224 EQ/BE ≥252-sess); live screen 1,590 scanned / 210 pass |
| MV-2.1 | `TrendTemplateService` — 8 gates SQL over `nse_eod_bhavcopy` + price/liquidity/session pre-filters | DONE | IT (hand-computed, 0 gateway ports) + **live-verified: real passers STLTECH/HFCL/VENUSREM…** |
| MV-2.2 | Minervini cross-sectional RS-rank (0.4/0.2/0.2/0.2 @ 63/126/189/252, percentile 1–99) | DONE | IT asserts 100/50/0 percentiles |
| MV-2.3 | Liquidity turnover gate (avg-50d `close×volume` ≥ capital×maxNamePct×100) | DONE | in `TrendTemplateService` (replaces raw vol_ratio) |
| MV-2.4 | `V031__minervini_screen_results` + `MinerviniScreenRepository` | DONE | migration applies (35→v031); upsert/latest round-trip |
| MV-2.5 | `MinerviniScheduler` (boot one-shot + 19:30 IST cron, fail-soft) | DONE | boot log "skipped — no data yet" (fail-soft verified) |
| MV-2.6 | `MinerviniController` GET + POST /run (typed record, {items} envelope) | DONE | IT endpoint asserts |
| MV-2.7 | Gateway allowlist | DONE (N/A) | `/api/v1/market/**` prefix already covers it |
| MV-2.8 | Contract recapture + TS regen | DONE | `ContractCaptureTest -Dcontracts.capture=true`; `openapi-typescript@7`; 2 minervini paths in snapshot+gen |
| MV-2.9 | Stage 1–4 derived label | DONE | IT: winner=2, loser=4, flat=1 |

**Config (all `artha.minervini.*`, tunable):** capital 150000, max-name-pct 0.25, liquidity-multiple
100, min-price 30, rs-min 70, pct-above-52w-low 25, within-52w-high 25, sma200-rising-sessions 21,
min-sessions 252, cron `0 30 19 * * MON-FRI`.

**Deferred to PR-B (fundamentals):** low-cap gates (free-float mcap <₹5,000cr, free-float% <35%,
exclude-F&O) — columns exist in V031 (null), gated on the Upstox fundamentals feed.

**Next:** deploy market-data + live-verify the endpoint on real data → then PR-B (Upstox fundamentals
client + market-cap/free-float low-cap gate + 2–3mo fundamentals load).

---

## PR-B — Phase 1/3: Upstox fundamentals feed + low-cap gate  (verified, awaiting CI)

The fundamentals data pipeline that was the plan's "biggest gap" — now a first-class Upstox feed on
the analytics token (ADR-0004), plus the low-cap universe gate inputs (ADR-0005).

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-0.6 | Upstox fundamentals fields verified | DONE | share-holdings->free-float%; key-ratios->P/E,ROE; income-statement->net-profit/revenue (crore); company mcap derived (P/E x net-profit) |
| MV-1.2 | `FundamentalsService` derivation | DONE | `FundamentalsServiceTest` (pure math, 2/2) |
| MV-1.3 | `UpstoxFundamentalsClient` (replaces the openscreener scraper) | DONE | 3 wire DTOs; bean gated on `analytics.enabled` |
| MV-3.1 | Low-cap gate inputs + ROE, V032 `equity_fundamentals` | DONE | **live-verified: RELIANCE mcap Rs 16.68L cr / promoter 50.07% / PE 22.5 / ROE 10.94 / NP 74,088cr — all real** |
| screener | LEFT JOIN `equity_fundamentals` + optional low-cap gate (`artha.minervini.lowcap-gate.enabled`, default off) | DONE | `TrendTemplateService`; `MinerviniScreenerIntegrationTest` still green |

**Live-probe (real Upstox, 4 symbols):** RELIANCE/TCS/STLTECH/HFCL fetched + derived correctly; all 4
correctly FAIL the low-cap gate (ff% > 35% and ff-mcap > Rs 5,000 cr) — large/mid-caps, exactly what
the owner's gate excludes. Derivations crore-consistent: free-float% = 100 - promoter%; market_cap =
P/E x net-profit; free-float mcap = market_cap x free-float%/100.

**Config:** `artha.minervini.lowcap-gate.enabled` (false until loaded), `.max-free-float-mcap-cr`
(5000), `.max-free-float-pct` (35). Endpoints: `POST /api/v1/market/fundamentals/refresh?symbols=CSV`
+ `GET /api/v1/market/fundamentals/{symbol}`.

**Deferred:** Code-33 quarterly EPS/sales/margin accel (§4.8) — the hard low-cap gate (this PR) was the
owner's priority. Full-universe fundamentals load = later with owner (this PR loaded 4 test symbols).

**Next:** PR-C — React screener + analyzer page (Phase 4), consuming the live endpoint.
