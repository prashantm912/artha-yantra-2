# Performance baselines — dated rows, one per comprehensive audit

Created by the 2026-07 comprehensive audit (first run). Each audit appends ONE row per table;
regression vs a prior row is a finding class. Measurement method notes below each table —
future runs must measure the SAME way or note the change.

## Query latency (single-shot EXPLAIN ANALYZE on the live `artha` DB, bounded windows)

| Date | 1m candle range 2d (NIFTY 50) | 5m cagg 7d | options chain 1 ts-bucket | signals 7d | rejections 7d |
|---|---|---|---|---|---|
| 2026-07-18 | 7.74 ms (2/1048 chunks) | 68.14 ms (cold, 420 disk blocks) | 0.77 ms (ts-index, 82% filtered) | 0.084 ms | 0.27 ms |

Method: single EXPLAIN ANALYZE per shape, off-market ~05:00 IST, cold-ish cache. Not a true
p95 (no pg_stat_statements). Options query note: composite `idx_ocs_underlying_expiry_ts` is
NOT used for underlying+ts-only predicates — ts-DESC index + filter (5192/6330 rows removed).

## Storage

| Date | DB total | candles total (heap/index/toast) | candles chunks (compressed) | candles heap compression | options total | options compression | top non-hypertable |
|---|---|---|---|---|---|---|---|
| 2026-07-18 | 46 GB | 23.27 GB (4.54/15.58/3.14) | 1048 (1046) | 2.64x | 2.29 GB | 7.19x | fundamentals 373 MB |

Finding class to watch: candles INDEX footprint = 3.4× heap (wide composite pkey × 1048
chunks, uncompressed). Cagg materialized hypertables: 60–61 chunks each, 0 compressed.

## Cache / index hit (cumulative since last stats reset — epoch not pinned)

| Date | heap hit% | index hit% |
|---|---|---|
| 2026-07-18 | 90.74% | 99.13% |

## Backtest throughput (last ~10 completed BACKTEST jobs at audit time)

| Date | workload | fast cohort | slow cohort | note |
|---|---|---|---|---|
| 2026-07-18 | 45d/3m scalper | 8.9–10.8 s (×5) ≈ ~430 bars/s | 159.8–187.0 s (×4) ≈ ~22 bars/s | ~18x variance, same workload; newest run 2026-07-11 (stale) |

## Tick→signal latency (#829 `signals.emit_latency_ms`, eval→emit)

| Date | n | min | p50 | p95 | max | note |
|---|---|---|---|---|---|---|
| 2026-07-18 | 6 | 145 ms | 5428 ms | 14857 ms | 14857 ms | DIRECTIONAL ONLY — column live since 07-14, sample-starved |

## Frontend bundle (raw, `frontend-react/dist/assets`; serve-time compression, no .gz on disk)

| Date | all JS | critical path (entry+react+css) | largest route chunk | largest vendor |
|---|---|---|---|---|
| 2026-07-18 | 3.48 MB (73 files) | ~935 KB | WorldIndicesPage 1,017 KB | vendor-echarts 1,035 KB |

## Container memory (docker stats one-shot, idle off-market)

| Date | timescale | edge-gateway | market-data | strategy-signal | backtest | optimizer | wiremock |
|---|---|---|---|---|---|---|---|
| 2026-07-18 | 792 MiB/4 GiB (19%) | 352/512 (69%) | 391/640 (61%) | 313/640 (49%) | 333/896 (37%) | 85/256 (33%) | 97/128 (76%) |

## Page load / web-vitals

| Date | value |
|---|---|
| 2026-07-18 | _authenticated browser sweep pending — needs owner sign-in (password-entry is agent-prohibited); mock-stack e2e blocked (no safe stack-drive path: pwsh absent + ay.ps1 won't parse under PS5.1 per OPS-R04). Bundle sizes captured (above)._ |

## Phase C empirical corroboration (read-only, 2026-07-18 Sat — live DB + live browser sweep)

| finding | evidence | verdict |
|---|---|---|
| AY-SL-01 | 265 intraday `backtest_runs`: equity_curve capped 501 pts (1m 352–501, 3m 495–501); 166 runs `|sharpe|>3` (max 5.85 / −80.09) | CONFIRMED-active on persisted data (curve-length + implausible magnitude); ≤500-pt runs correctly untouched |
| FE-01 | cold-load of the CHARTLESS `/settings` fetches `vendor-echarts-Dcw_aRuq.js` 394 KB, initiator `"other"` (index.html module-preload, not a route chunk) | CONFIRMED-active via live browser: echarts is in the eager initial graph, loads on a page with no chart |

**Live a11y spot-sweep** (own scan — imgs-no-alt / buttons-no-name / inputs-no-label / h1 / lang;
contrast still needs axe-in-CI): `/settings`, `/dashboard`, `/orders`, `/options/trending-oi` all
CLEAN (0/0/0, 1 h1, lang=en) — no regression vs the #476 closure. `/orders` renders real data (3
tables, WS connected). FE-04 (500→false-empty) NOT browser-reproduced — inducing a backend 500 is
out of read-only scope; source-confirmed only.

**Web-vitals (loopback, warm cache):** cold `/settings` load ~117 ms; SPA route-changes 40–130 ms;
FCP not meaningfully captured on client-side route changes. Cold cross-page web-vitals + true 480 px
mobile reflow need Playwright device emulation (the mock-stack e2e path, blocked — see below).

**Mobile 480 px:** responsive structure present (3 `md:hidden` DataTable mobile-card blocks on
`/orders`); true reflow UNVERIFIED — `resize_window` left `innerWidth=1920` (no render-viewport
shrink); needs Playwright device emulation.

**Stack-path UNBLOCKED + mock battery run (2026-07-18, owner-authorized):** proved the OPS-R04 fix
(a UTF-8 BOM makes `ay.ps1` parse under PS5.1/CP1252 — verified 0 parse errors, `help` exit 0), then
flipped the stack to mock via a byte-safe `.env` profile swap (`live`↔`mock`, 4-byte same-length,
backup MD5-verified) driven by a BOM-fixed temp copy. Mock stack came up healthy on `artha_mock`
(live `artha` untouched). Delivered on mock:
- **`{items}` envelope contracts CONFIRMED** — signals `{"items":[],"limit":1,"offset":0}`, paper
  `{"items":[]}`, backtest-jobs `{"offset":0,"limit":1,"items":[]}`.
- **Mock backtest END-TO-END** — submit → `completed` → result persisted (engineSha stamped in
  provenance, B2 working). 0 trades / empty curve = the expected strict-AND-gate behaviour.
- **Flip-back verified**: `.env` restored byte-identical to the pre-audit backup (MD5 match); live
  stack recreated; registry 73/69, engine **63 loaded / 0 unresolved / 0 failed**, 18 open paper
  positions intact — identical to the pre-audit state.

**Full Playwright e2e suite RUN (2026-07-18, owner-authorized throwaway mock password):** used the
harness's own `E2E_FORCE_ENV=1` path — it writes a fresh mock `.env` with the committed `E2E_HASH`
(`hash('e2e-owner-password')`), rebuilds + brings up the mock stack, runs the suite. **17/17 spec
files PASSED (2.3m), exit 0** — backtest-results, charts (×3), dashboard, ingest-health, login,
minervini-screener, notifier, paper-books, signal-rejections, signals, smoke, strategy-editor,
strategy-versions, sweep-explorer, ws-reconnect. axe-core a11y runs clean on every route + the 480px
mobile project by design (the suite's standing invariant). `.env` restored byte-identical from backup
(md5 match); live stack recreated + verified (73/69, engine 63 loaded / 0 unresolved, 18 positions).

**Still deferred (not blocked):** service unit/IT tests (heavy Maven reactor + AV-TLS build quirk) —
run separately via `build-service` when needed. Everything in the Phase C functional/e2e/a11y mandate
is now covered.
