# Minervini SEPA — autonomous build log

**Status:** ACTIVE (overnight autonomous build, 2026-07-04). Companion to the
[implementation plan](2026-07-04-minervini-sepa-implementation-plan.md) — this log is the
per-PR record; the plan's item Status/Evidence cells are the canonical tracker.

**Guardrails in force:** real Upstox analytics token via app config (never printed); no live
trades; test data loads only (~2–3 months), no unsupervised heavy backfill; deploy + off-hours
live-verify where possible; branch + PR per batch; **auto-merge only on CI-green (admin)**; never
push `main` directly.

**Key recon (2026-07-04, live stack up, profile=live, analytics enabled):**
- Dense daily source = **native `candles` interval='1d'** (NOT the sparse `candles_1d` cagg). NSE
  ≥252-session universe ≈ **1,773 names** today; native store has **11y depth** (2015→2026) for
  ~1,671 backfilled names + ~1y broad (bhavcopy, 8,899 syms). → the backtest already has depth.
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
| MV-0.2 | Canonical dense source picked | DONE | live query: native `candles`@1d (1,773 NSE ≥252-sess); screener reads it |
| MV-2.1 | `TrendTemplateService` — 8 gates SQL over native `candles`@1d + price/liquidity/session pre-filters | DONE | `MinerviniScreenerIntegrationTest` (hand-computed, 0 gateway ports) |
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
