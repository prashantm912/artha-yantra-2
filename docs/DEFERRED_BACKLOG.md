# Deferred / Pending Backlog — Phases 0 → 3.5 (as of 2026-06-21)

Single source of truth for everything NOT yet done across the OpenAlgo/React master-plan phases 0–3.5.
The forward-work authority is `superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md`
(§16.1 phase map); current phase + the running checklist is `PHASE_GATES.md`. This file consolidates the
deferrals so a fresh session has one place to look.

**Merge state at writing:** Phase 0–3 + Phase 3.5 Tier-1/Tier-2 are on `main`
(PR #39/#40/#41/#42/#43). The **#2 Open=High per-strike faithful grading is in PR #44 (OPEN, pending
merge)** — items marked `(via #44)` land when it merges.

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
| §5 intraday-OI backfill (ExpiryTrack historical OI) | DEFERRED | Phase-1 completion, when funded | Needs Upstox Plus subscription + an always-on host; intraday OI history is bought, not free. |
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
| Strategy **#7 Hero-Zero** | GATED | after SPAN (#47) + manual confirm | Short-premium / deep-OTM lottery bet; needs SPAN margin + tiny-profit-slice sizing. |
| Strategy **#11-short Straddle** | GATED | after SPAN (#47) | Short-premium; needs SPAN margin. |
| Strategy **#8 BTST/STBT** | DEFERRED | after an overnight position lifecycle + SPAN | Needs **overnight carry** (paper layer force-squares-off 15:45 IST) + the short-PE/CE leg needs SPAN. |
| **#47 SPAN appliance** (§8 marginism) | DEFERRED | when short-premium #7/#11 land | Index-option core (#1/#5/#6/#10/#2/#4/#9/#12) is CE/PE-**buying** = defined risk (premium paid) → needs no SPAN; SPAN only for short-premium. |
| **OpenAlgoOrderGateway** (live broker order impl) | DEFERRED (gated) | a live-cutover slice | Needs the OpenAlgo order-API verified vs the local checkout + the §17.3 place-ack **latency gate** before any real order routes. The execution BOUNDARY (`OrderGateway` port + `DisabledOrderGateway` fail-safe + semi-auto `LiveOrderService`) is shipped. |
| **§18.1 order read endpoints** (orderbook/positions/tradebook/funds) + React `/orders` page | DEFERRED | Phase 4b | Sequenced to the React scalper-cockpit split. |
| **Full-auto execution** (no human "Take") | DEFERRED | a later flag | Semi-auto (human "Take") is the v1 safety boundary. |
| **Manual-verification checklist UI** (verify + confirm panel) | DEFERRED | Phase 4 (React) | Backend done (7 human checks ride the V009 side-channel); UI is built once in React (Angular `frontend-ui` is throwaway). |
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

## Phases ahead (NOT STARTED — the roadmap, for context)

| Phase | Status | Needs | Notes |
|---|---|---|---|
| 4 — React migration (§10/§11) | NOT STARTED | — (owner-deferred sequencing) | Angular `frontend-ui` is throwaway; consumes the manual-checklist + OI/strike-session contracts. Includes the OiPulse-parity OI pages + the `/orders` page (3.5/3 deferrals above). |
| 5 — Minervini Track-1 screener (§13) | NOT STARTED | Phase-1 §15 200-day history | Daily 8-gate Trend Template + RS rank; VCP/pivot/Cheat/Power-Play deferred (owner accepts manual chart-reading of entries). |
| 6 — Backtest + forward wiring (§14) | NOT STARTED | Phases 3 + 5 (+ Phase-1 §5 OI) | Scalp historical-backtest fidelity is directional, not P&L-exact (R4); raptorbt cross-check oracle DEFERRED. |

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
  Redis key `kite:contract:check`; the ~1.1k-row mock dump fixture; Caffeine (not Redis) indicator cache;
  optimizer `/best` guard columns surfaced in the fold drill-down. See the `PHASE_GATES.md` stage parking
  lists for the full rationale.
