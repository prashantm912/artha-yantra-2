# Deferred / Pending Backlog — Phases 0 → 6 (as of 2026-06-24)

Single source of truth for everything NOT yet done across the OpenAlgo/React master-plan phases 0–6.
The forward-work authority is `superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md`
(§16.1 phase map); current phase + the running checklist is `PHASE_GATES.md`. This file consolidates the
deferrals so a fresh session has one place to look.

**Merge state at writing (2026-06-24):** Phase 0–3 + Phase 3.5 are on `main` (#39–#44). **Phase 4
React is substantially built** — the cockpit pages (Signals/Paper/Dashboard/Strategies/Backtests/
Journal/Watchlists/Settings/Charts, #94–#103), the **React cutover** (#104, Angular `frontend-ui`
parked), the scalper cockpit (#105–#110), and oipulse Waves **W1/W2/W3** (most depth + breadth + chart
pages, through #93/#109) are all merged. **Expired-instruments OHLCV+OI backfill** (the §5 / Bucket-5 /
data-foundation Part-B archive) is **BUILT + RUNNING** (#112–#116). **Part 2 premium-as-primary
backtest replay** (options trade their own premium) is merged (#114–#119). The **Data Ops Console**
(operator UI over the backfill, B1–B6) is merged (#121, deploy pending). What remains is below.

## Legend
- **Status:** DONE / PARTIAL / DEFERRED / GATED / NOT STARTED.
- **Target:** the phase (or condition) the work is deferred to.

---

## Phase 0 — OpenAlgo spine (MERGED, PR #39)

| Item | Status | Target | Reason |
|---|---|---|---|
| WS ticker via OpenAlgo SDK (capability F) | DEFERRED | a separate latency-budgeted task / live cutover | Don't route scalp execution through OpenAlgo until place-ack latency is measured; Kite WS ticker works today. |
| 20-level market depth flattening | DEFERRED | a future order-microstructure feature | Snapshot/chain path only reads best bid/ask (immune to depth-level diffs); >5 levels only needed by a future scalp-microstructure use. |
| Instruments / symbols via OpenAlgo (capability B) | DEFERRED | only if a broker swap forces it | Symbol-format mapping cost; the Kite instrument dump works. |

## Phase 1 — Data inflow (PARTIAL, PR #40/#41)

| Item | Status | Target | Reason |
|---|---|---|---|
| §5 expired-instrument OHLCV+OI backfill (ExpiryTrack historical) | **DONE / RUNNING** | — (#112–#116) | Upstox Plus funded; the `ExpiredBackfillService` ingester (bounded strikes + sliding-window limiter + resume) loads NIFTY/SENSEX expired CE/PE/FUT per-min OHLCV+OI into `candles` (`source='BACKFILL'`). First full pull in progress 2026-06-24. See [[upstox-expired-instruments]]. |
| Native (live) intraday-OI snapshot history depth | PARTIAL | ongoing forward-capture | Live 3-min full-chain OI capture has run since 2026-06-15 (forward-accruing); deep past OI for stocks (non-expired) still bought-only. |
| §15 200-day daily history (openchart) | DEFERRED | before Phase 5 | Needed by the Minervini screener (N-day high / RS rank); not needed earlier. |
| Live OI cutover (route live OI through OpenAlgo) | DEFERRED | live bring-up / manual guide | Needs live verification + the OI-coverage contract canary (`chain[].ce.oi/pe.oi`). Offline routing slice is merged. |
| §6.3 BSM-on-spot seam (stock options) | DEFERRED | future stock-options work | Index path uses Black-76-on-the-forward; no stock-options consumer yet. |

## Phase 2 — Quant libs (MOSTLY, PR #40)

| Item | Status | Target | Reason |
|---|---|---|---|
| §6 higher-order greeks (vanna/charm/etc., ~10) | DEFERRED | §17.6 — when a named consumer exists | The first-order set (delta/gamma/theta/vega/rho/IV) ships + is golden-tested; nothing consumes the higher-order greeks yet. |

## Phase 3 — Scalper engine (MERGED, PR #42)

| Item | Status | Target | Reason |
|---|---|---|---|
| Strategy **#3 Market Movers** | DEFERRED | Track-1 / Phase-5-adjacent (a screener, not a live signal) | Trades F&O **stocks**; the scalper engine is index-option only; overlaps the Minervini screener + needs N-day-high + daily RSI. |
| Strategy **#7 Hero-Zero** | **DONE (paper, #130)** | — | **CORRECTION:** #7 is BUY-side (long premium, defined risk) per the Siva cheat sheet ("buy side only"), NOT short-premium — so it needs NEITHER SPAN NOR live orders. Wired as a paper scalper via `HeroZeroGate` + `scalp-hero-zero-nifty.yaml` (expiry-day, 14:30–15:20, >50% OI+price sync, one-away strike via side+delta band). Golden/parity byte-identical. Live-order routing is the only remaining gate for trading it LIVE (shared with the cluster below). |
| Strategy **#11-short Straddle** | GATED | live orders + the `.spn` verify | Short-premium; SPAN appliance built; needs hedge logic + live orders. |
| Strategy **#8 BTST/STBT** | DEFERRED | after an overnight position lifecycle + SPAN | Needs **overnight carry** (paper layer force-squares-off 15:45 IST) + the short-PE/CE leg needs SPAN. |
| **#47 SPAN appliance** (§8 marginism) | **BUILT (dormant)** | live-verify (#126) | `services/margin-service/` (marginism 0.1.1, FastAPI :8086) + advisory paper sizing wire-in, shipped default-off (`artha.margin.span-enabled=false`). VERIFY-pending: a real-`.spn` broker-parity golden + the NSE `.spn` download URL (CI golden tests the real algo vs a synthetic `.spn`). |
| **OpenAlgoOrderGateway** (live broker order impl) | DEFERRED (gated) | a live-cutover slice | Needs the OpenAlgo order-API verified vs the local checkout + the §17.3 place-ack **latency gate** before any real order routes. The execution BOUNDARY (`OrderGateway` port + `DisabledOrderGateway` fail-safe + semi-auto `LiveOrderService`) is shipped. |
| **§18.1 order read endpoints** (orderbook/positions/tradebook/funds) + React `/orders` page | **BUILT (dormant, #131)** | live-verify | OpenAlgo `openalgo/wire/` anti-corruption DTOs + gated `RestOpenAlgoOrderReadGateway` + `GET /api/v1/orders/*` + read-only `/orders` page, WireMock-tested, ships off (`artha.openalgo.order-read-enabled=false`). Live-broker verify deferred. |
| **Full-auto execution** (no human "Take") | DEFERRED | a later flag | Semi-auto (human "Take") is the v1 safety boundary. |
| **Manual-verification checklist UI** (verify + confirm panel) | **DONE** (#125) | — | React `ManualVerifyChecklist` on `/signals` + `/scalper` (soft-warning + override gating, the 7 V009 checks + confluence dots, client-only). |
| **Per-check server audit** (which boxes ticked) | DEFERRED | only if an override/exception trail is needed | Would add a `TakenRequest` field (request-schema drift + TS regen). |
| **Historical scalp backtests** | DEFERRED | Phase 6 | Need Phase-1 §5 intraday-OI data + the §17.5 calendar extension; Phase 3 validates via unit-fired signals + live paper only. |

## Phase 3.5 — OI-analytics fidelity + faithful strategies (PR #43 done; #44 OPEN)

| Item | Status | Target | Reason |
|---|---|---|---|
| Tier-2 OI fidelity T2.1–T2.8 (18 dots, #5 ≥50% ΔOI pre-gate) | **DONE** (#43) | — | — |
| Monthly-expiry OI suppression (S24 caveat) | **DONE** (#43) | — | — |
| Strategies #4 Gap / #12 Trend-Change / #9 Morning-Trade | **DONE** (#43) | — | — |
| #2 Open=High **front-Future v1 proxy** | superseded (#43) | — | Replaced by the per-strike faithful grading below. |
| #2 Open=High **per-strike Table-1/Table-2 faithful grading** | **DONE** (via #44, pending merge) | — | New `/options/strike-session-stats` endpoint derives per-strike session OHLC+volume from `options_chain_snapshots`. |
| **OiPulse ≥90% AI badge** (#2) | DEFERRED | Phase 4 (OiPulse-parity) | External proprietary oipulse.com AI model; not ours; the faithful Table-1/Table-2 HIGH tier is our equivalent. |
| **drasticFloor per-index tuning** (#6 `drastic_oi`) | DEFERRED | live calibration | The source gives no number; default `50000` is a placeholder; tune per index (NIFTY vs BANKNIFTY) once live OI magnitudes are observed. |
| **Native 3-min option-snapshot capture** | DEFERRED (config) | when 3-min Table-2 volume fidelity is wanted | 5-min snapshots → 5-min volume candles; native 3-min needs `artha.options.snapshot-interval-ms`=180000 (more storage). The endpoint already serves both via the `interval` param; Table-1 OH/OL is resolution-robust. |

## Phase 4 — React migration (§10/§11) — IN PROGRESS (substantially built)

Cockpit + React cutover + oipulse Waves W1/W2/W3 merged (see the merge-state note above). Remaining:

| Item | Status | Target | Reason |
|---|---|---|---|
| **Data-foundation value-verify** — render every OI/data page in History mode on a REAL session + compare value-for-value vs oipulse | GATED | the expired/OI backfill data (NOW loading, #112–#116) | The big open Phase-4 item: pages are structure-QA'd, not value-verified. Authority: `superpowers/plans/2026-06-21-data-foundation-milestone.md` + [[oipulse-live-qa-method]]. |
| **Data Ops Console deploy** (B1–B6 merged #121) | GATED | after the running backfill finishes | A market-data redeploy restarts it → kills the in-flight backfill job. Deploy + rebuild `ay-frontend-react` once the pull completes. See [[data-ops-console]]. |
| **`/orders` page** + §18.1 order read endpoints (orderbook/positions/tradebook/funds) | **BUILT (dormant, #131)** | live-verify | See the Phase-4 table — scaffolded + WireMock-tested; live-broker verify deferred. |
| **Manual-verification checklist UI** (verify + confirm panel) | **DONE** (#125) | — | Built (see the Phase-4 table); the `2026-06-20-scalper-manual-verification-checklist.md` contract is fulfilled. |
| **OiPulse ≥90% AI badge** (#2) + any tail oipulse-parity polish | DEFERRED | post value-verify | Proprietary oipulse model; our faithful Table-1/2 HIGH tier is the equivalent. |

## Phases ahead (NOT STARTED — the roadmap, for context)

| Phase | Status | Needs | Notes |
|---|---|---|---|
| 5 — Minervini Track-1 screener (§13) | NOT STARTED | Phase-1 §15 200-day history | Daily 8-gate Trend Template + RS rank; VCP/pivot/Cheat/Power-Play deferred (owner accepts manual chart-reading of entries). |
| 6 — Backtest + forward wiring (§14) | **PARTIAL** | Phases 3 + 5 (+ the §5 OI data now loading) | **Part 2 premium-as-primary replay LANDED** (#114–#119): an options backtest now trades the option's own 1m premium series (`CANDLE_1M`), not the index close — golden-pinned. The v1 simplifications are now CLOSED (#123): per-bar mark-to-market, FillSimulator slippage+costs on the premium leg, and a 422 DATA_GAP coverage pre-flight. Remaining: the value-verify on real backfilled premium (gated on the backfill), forward-test wiring. Scalp historical-backtest fidelity is directional, not P&L-exact (R4); raptorbt cross-check oracle DEFERRED. |

## Data Ops Console — parked decisions (from #121, B1–B6)

| Item | Status | Target | Reason |
|---|---|---|---|
| `backfill_jobs` audit table (run history surviving a restart) | DEFERRED | if run history is wanted | B1 status is in-memory (resets on restart); covers the live need today. |
| B6 per-expiry bulk export + ZIP/Parquet (async streaming) | DEFERRED | when bulk export is needed | v1 is per-contract CSV/JSON (≤100k rows, sync); per-expiry is ~1.3M rows. B5 query console covers arbitrary slices meanwhile. |
| Contract-type selector in the collection wizard | DEFERRED | only if a CE/PE/FUT filter is wanted | The `expired-backfill` trigger has no contract-type field — it pulls options + futures together. |
| B1 live updates via STOMP (vs the 2s poll) | DEFERRED | consistency polish | A small poll is simpler; the jobs WS topic is backtest-scoped. |

## Cross-cutting / legacy parking (lower-priority hardening, from Stage A–G)

| Item | Status | Target | Reason |
|---|---|---|---|
| B-9 binary-frame guard production wiring | DEFERRED | when Kite changes its wire format / a first-party WS client | javakiteconnect exposes no raw-frame hook; today's coverage = the daily contract canary + fixture-pinned envelope tests. |
| `instruments.exchange_token` population | DEFERRED | when a consumer needs it | Column exists, never written; nothing in Phases B–D consumes it. |
| `candles_1h` IST alignment (buckets to UTC = :30 IST) | DEFERRED | before a 1h chart/overlay consumer | Re-anchoring means dropping/recreating the cagg. |
| Options fidelity live walk (SNAPSHOT / SYNTHETIC_B76) | DEFERRED | first live-mode options session | IT-green; needs a real options archive + a multi-month window the mock can't supply. |
| Walk-forward folds + fold-fed MedianPruner live walk | DEFERRED | a real multi-month dataset | Can't be shown on the ~3-day rolling mock window. |
| `requirements.txt` hash-pinning (optimizer) | DEFERRED | a CI hardening pass | Version-pinned but not hash-locked; add `pip-compile --generate-hashes`. |
| Recorded Kite binary-frame capture | DEFERRED | first live session | The mixed-frame fixture is synthesized from the documented envelope; commit one real capture. |
| Upstox `/pcr` live-freshness test → `source.pcr=upstox` switch | DEFERRED | next market-hours session | Upstox `/pcr` gives full-chain, 1-min intraday PCR (verified `bucket_interval=1` → 376 buckets) + history from 1 Apr 2026 → more accurate + parity-clean than native (native is band-biased ~0.1–1.5% low: ATM band misses deep-OTM strikes). Native max-pain is EXACT, keep it. **Gate:** confirm `/pcr` returns FRESH buckets mid-session (call `date=<today>&bucket_interval=1`, last bucket ≈ now, value moves on re-poll). If fresh → add a dormant `artha.marketdata.source.pcr=upstox\|native` flag (mirror `source.fiidii`) and switch live+backtest to Upstox; native stays as free cross-check/fallback. If EOD-only → native for live, Upstox for EOD+pre-capture backtests. PCR feeds NO strategy yet (display-only), so no urgency. |

---

## NOT pending (resolved this cycle, recorded so they aren't re-listed)
- Branch protection on `main` — configured (the 7 CI checks are required; `enforce_admins` deliberately
  OFF so the solo owner can admin-merge — was a solo-owner deadlock).
- Accepted documented DEVIATIONS (not deferred work): Monaco editor-route chunk ~562 KB gz; the canary
  Redis key `kite:contract:check`; the ~1.1k-row mock dump fixture; Caffeine (not Redis) indicator cache.
  See the `PHASE_GATES.md` stage parking lists for the full rationale.
- **Optimizer `/best` guard columns** — RESOLVED (#129): per-regime OOS Sharpe (min/mean/max), regimes-covered,
  folds-excluded, dataHash now surface as a compact `guardMetrics` on `/best` + the React sweep leaderboard
  (read from the persisted fold/results data, no recompute; legacy full-window trials show "no fold guards").
- **e2e coverage** for the Data Ops console + scalper checklist — ADDED (#128, Playwright + axe, desktop + 480px).
