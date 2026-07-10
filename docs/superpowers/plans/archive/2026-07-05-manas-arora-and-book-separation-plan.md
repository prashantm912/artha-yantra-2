# Manas Arora strategy + per-strategy paper/signals books — implementation plan

**Status:** ARCHIVED 2026-07-10 (fully shipped + deployed live). · **Date:** 2026-07-05 · **Owner-approved, autonomous overnight build.** · Source strategy doc:
`strategy-documents/manas-arora-operative/MomentumTradingManasArora_Consolidated_Strategy.md` (merged #565).

> **STATUS 2026-07-05 — FULLY SHIPPED + DEPLOYED LIVE.** All PRs merged: books (#566/#568), Manas
> screener/geometry/funnel (#567), live engine + daily EOD (#570), 10-yr backtest (#569), FE pages
> (#571), go-live (#572). Doc-fidelity follow-ups: §3.5 ATR exit doctrine (#573), per-setup live pivots
> (#574). Backtest-review fix: stable `eqSymbols()` order (#569 fork + #575 Minervini original). Live =
> 3 separate ₹1.5 L books; screener 2,224→97 pass; auto-papers into the Manas book nightly (cron 20:05
> IST). **Remaining = owner-gated only:** the supervised forward-paper watch + the §0.5 #12 reliability
> sign-off. No buildable code left. Full record in the forward ledger's "Manas Arora" section.

This plan covers three owner requests, built one-by-one, each as its own PR (build → test →
adversarial review → CI-green → admin-merge):

1. **Task 2/3 — separate paper + signals books.** Three fully separate books: **Scalper**,
   **Minervini**, **Manas Arora** — each with its own signals page, paper page, capital, risk
   config, and auto-paper toggle. No cross-book mixing.
2. **Task 4 — Manas Arora strategy family.** A *separate* strategy family (simplified Minervini),
   built start-to-end like Minervini: screener, geometry, funnel, swing engine, pages, and a
   full phased 10-year backtest to find the best setup. **Must not interfere with Minervini.**

## Owner decisions (grill answers, 2026-07-05)

| # | Decision | Answer |
|---|----------|--------|
| 1 | Book model | **3 fully separate books** (Scalper / Minervini / Manas), each own signals+paper page, capital, risk config, auto-paper toggle |
| 2 | Capital per book | **₹1.5 L each** (Scalper shrinks 10L→1.5L; Minervini 1.5L; Manas 1.5L) |
| 3 | Existing open paper positions | **Wipe all — every book starts flat** |
| 4 | Manas float/low-cap gate | **Build it, default-OFF (data-gated)** — mirror Minervini's `lowcap-gate.enabled=false`; backtest runs a with-float variant where data exists |
| 5 | Manas setups + pyramiding | **Breakout + VCP + full exit doctrine + pyramiding ON** (live + backtest) |
| 6 | Manas go-live | **Build + enable live** — daily EOD batch, own cron ~20:05 IST, auto-paper into the ₹1.5L Manas book |
| 7 | Backtest grid | **I design the full phased grid** (technical → +RS → +liquidity/turnover → +float → pyramid on/off → slot-sweep → portfolio), report best |

## Architecture facts (from the 4-agent recon, 2026-07-05)

- **Family marking = `strategies.tags` TEXT[]** (no `category` column). First tag is the family:
  `[scalper,…]` / `[minervini,…]`. **Book is derivable from the strategy's tags.** New family →
  `[manas-arora, swing, equity, <setup>]`.
- **Paper is one global book today:** `paper_account` (single row, ₹10L), global `risk_settings`,
  `paper_positions` has NO strategy column (only `subaccount_idx` 1–5 for scalper). Position→strategy
  is only traceable via `paper_orders.signal_id → signals.strategy_version_id`.
- **Signals is one unified `/signals` page**; rows carry `strategyId/strategyName`; scalper is
  flagged by `scalper_detail` presence. No book/family filter today.
- **Screeners are per-family** (Minervini pattern is the template: api layer + screener page +
  candidate page + `App.tsx` routes + `MegaMenu` section).
- **Sizing:** `PositionSizer` already has `atr_risk` (`risk_pct_equity` ÷ stopDistance) = Manas's
  risk-based sizing. No new method needed. Stop distance is fed from the `stop_loss` exit rule.
- **Universe modes** are enumerated in `UniverseResolver.resolve()` + validated by strategy-schema
  (`reject/bad-universe-mode.yaml`). Adding `manas_arora_funnel` = resolver case + schema allow-list.
- **Minervini reuse for Manas backtest:** `SwingPortfolio`, `SwingRotationPortfolio`, `VcpDetector`,
  the weekly cross-sectional RS-rank math, and the `V035` table pattern are all reusable. Fork only
  the entry/exit + pyramiding into `ManasAroraSwingBacktest` + `ManasAroraBacktestService`.

## The book concept (design)

A **book** is a string tag on money-bearing rows, one per strategy family:
`scalper` · `minervini` · `manas-arora` (+ `manual` for hand orders). Resolution: a signal's
`strategy_version_id → strategies.tags` → first family tag → book. Cached in a `BookResolver`.

- `paper_positions` / `paper_orders` gain a `book TEXT NOT NULL DEFAULT 'manual'` column.
- `paper_account` becomes **per-book** (one row per book, each `starting_capital`, `cash`).
- `risk_settings` becomes **per-book** (composite `(book, key)`); each book has its own
  `kill_switch`, `max_open_paper_positions`, `max_deployment_pct`, `daily_loss_limit`,
  `auto_paper_trade`.
- `signals` gains a denormalized `book TEXT` (stamped at insert from strategy tags) for fast
  per-page filtering.
- **Wipe:** the migration truncates `paper_positions` + `paper_orders` (owner-approved); every book
  is seeded flat at ₹1.5 L.

## PR sequence

### PR-A — Book separation, backend + migration (strategy-signal-service)
- Migration (strategy lineage): add `book` to `paper_positions`, `paper_orders`, `signals`;
  per-book `paper_account` (seed 3 books @ ₹1.5 L + `manual`); per-book `risk_settings` (seed
  defaults per book); **TRUNCATE** existing paper positions/orders (wipe).
- `BookResolver` (tags → book, cached via registry).
- `PaperService.openOrder` stamps `book`; `PaperAccountService` + `RiskService` keyed by book;
  `AutoPaperListener` gate per book; endpoints gain optional `?book=` (positions/trades/pnl) and
  required `book` for account/risk/reset (default `scalper` for back-compat where unset).
- Tests: paper + risk integration tests updated for the book dimension.
- **Parity-sensitive** (paper ledger) → adversarial review before merge.

### PR-B — Book separation, frontend (frontend-react)
- Per-book paper pages `/paper/:book` (scalper default) + per-book signals `/signals/:book` (or a
  book tab on the existing pages). Wire `?book=` through the api layer.
- `MegaMenu`: Scalper / Minervini / Manas each get their Signals + Paper entries.
- Keep `/paper` + `/signals` as the scalper default (back-compat).

### PR-C — Manas screener + geometry + funnel (market-data-service)
- `ManasArora` 6-criteria selection screen (within 25% of 52wk-high, ≥100% up from 52wk-low,
  price ≥ ₹30, 200-MA rising ≥3m, 50>200, price>200 pref>50, new-high cadence) + **liquidity gate**
  (20-day avg vol veto, 10-week avg-vol multiple) + **float/low-cap gate default-OFF**.
- Geometry: reuse `VcpDetector`; add a `ConsolidationBreakout` detector (4–8wk range + swing-high
  pivot) for the breakout setup.
- Migration (marketdata): `manas_arora_screen_results` + `manas_arora_setups`.
- Config namespace `artha.manas-arora.*`. Endpoints `/api/v1/market/screener/manas-arora/*`
  (screen, run, funnel, candidate) — typed records, `{items:[…]}` envelope.

### PR-D — Manas strategy family + engine (strategy-signal-service + schema/engine libs)
- Schema: register `manas_arora_funnel` universe mode; add an `atr` stop_loss basis + a
  `square_off`/parabolic exit rule type if absent (else approximate with percent + indicator).
- YAMLs `manas-arora-{breakout,vcp}.yaml`: `atr_risk` sizing (`risk_pct_equity: 1.0`), 2×ATR(20)
  stop (cap ~10%), trail after +8–10%, square-off triggers; `universe.mode: manas_arora_funnel`;
  tags `[manas-arora, swing, equity, <setup>]`; `session.style: swing`.
- `UniverseResolver.resolveManasAroraFunnel`; `ManasAroraSwingEngine` + `ManasAroraSwingScheduler`
  (cron `0 5 20 * * MON-FRI`, gated `artha.manas-arora.swing.enabled`) + controller + seeder +
  funnel client. **Pyramiding** (add-to-winner, each add own position/stop, open-risk ≤5–6%).
- `manas_arora_detail` JSONB side-channel on `signals` (setup, pivot, ATR, base geometry).

### PR-E — Manas 10-year backtest + run + results doc (market-data-service)
- `ManasAroraSwingBacktest` (breakout + VCP entries, 2×ATR stop / trail / square-off, **pyramiding**)
  + `ManasAroraBacktestService` (phased variant grid) — reuse `SwingPortfolio`/RS-math/costs.
- Migration `manas_arora_backtest_runs`; controller `/swing-backtest` + `/compare`.
- **Variant grid:** v1 technical-only · v2 +RS-rank · v3 +liquidity/turnover floor · v4 +float (data)
  · v5 pyramiding on/off A/B · v6 slot-sweep · v7 portfolio (FIFO vs RS-priority, costs).
- **Run it**, write `docs/strategies/manas-arora-swing-backtest-results.md` (§1–7 like Minervini),
  identify the best setup.

### PR-F — Manas frontend pages (frontend-react)
- Screener page + candidate analyzer (Minervini pattern) + backtest-results view; Manas signals +
  paper pages (from PR-A/B book plumbing); MegaMenu + routes.

### PR-G — Deploy + go-live + docs/memory
- Build all services + FE, deploy, seed+publish the Manas strategies, enable flags, smoke-test
  live (screener → funnel → engine dry-run → auto-paper into the ₹1.5L book).
- Update the forward ledger + memory; the batch then fires nightly.

## Guardrails
- Parity firewall intact: the backtest is a market-data analytics read; the live engine reuses the
  FROZEN `EntryEvaluator`/`ExitEvaluator`. New `SignalEvent` fields ride non-serialized side-channels
  → goldens stay byte-identical. Verify with GoldenDeterminism + BacktestParity.
- Every new endpoint returns a typed record (MapReturnRatchet). New migrations are new files (never
  edit applied ones). Full-reactor `-am` builds. Admin-merge only on CI-green.
- **Paper only. No live trades.** Manas auto-paper writes to its ₹1.5L paper book.
