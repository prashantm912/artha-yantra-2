> **ARCHIVED 2026-07-02 — SHIPPED.** B1–B6 merged (#121), DEPLOYED + live (#219 route fix), value-verified.
> Parked v2 decisions (audit table, bulk export, STOMP status) stay in `docs/DEFERRED_BACKLOG.md`.

# Data Ops Console — wave feature spec (B1–B6)

- **Status:** **MERGED 2026-06-24 (PR #121, squash 5e4d5c9)** — all of B1–B6 built + CI-green. NOT yet
  deployed live (a market-data restart kills the in-flight expired backfill → deploy + rebuild
  `ay-frontend-react` once the pull finishes). Kept here until deployed + value-verified, then archive.
  Authored 2026-06-24.
- **Milestone:** Data Ops Console — operator tooling for the Upstox expired-instruments / OI
  backfill pipeline. PRs `PR-DO1`…`PR-DO6` (one per feature B1–B6).
- **Route prefix:** `/data-ops/*` — a NEW top-level **Data Ops** section in the React mega-menu
  (`frontend-react/src/components/MegaMenu.tsx` `SECTIONS`, routes in `App.tsx` under `AppShell`).
- **Fidelity oracle:** ExpiryTrack (`C:\Trading\ExpiryTrack`, marketcalls) for UX patterns ONLY —
  this is an **ArthaYantra-native** surface (no oipulse equivalent), so acceptance criteria come
  from this spec, not an oipulse side-by-side gate.
- **Sequencing:** independent of the oipulse analytics waves (W1–W4). May land parallel to W3/W4 —
  it is backend-additive (new read-only endpoints) + a new menu section, with near-zero coupling to
  the oipulse fidelity work. *Enabled by* the running expired-backfill; *serves* the Part-2
  premium-replay value-verify and the data-foundation milestone.

---

## 1. Why

The expired-instruments backfill (PR #112–#116, merged, **running now**) and the OI backfill
(`OiBackfillService`) are **headless**: triggered by a fire-and-forget `POST` that returns only a
`jobId`, with progress visible **only in `docker logs` + ad-hoc SQL**. Every "how much loaded / how
much remaining / is it throttling?" question today requires the operator (or Claude) to hand-run
`psql` counts and `grep` the container logs.

ExpiryTrack solves exactly this with an operator console (collection wizard + live status + coverage
dashboard + query/export). We already own — and exceed — ExpiryTrack's **collector** (Timescale vs
DuckDB/SQLite, bounded strikes, sliding-window limiter, coverage flags, resume). The gap is purely
the **operator UI**. This wave wraps our existing backend in that console.

Concrete motivations already hit this session:
- "How much backfill is loaded / remaining / ETA?" → answered today by manual SQL. **B2** makes it a page.
- "Resume the backfill" / quota exhausted → blind to remaining quota. **B4** surfaces per-window burn.
- Part-2 premium-replay **value-verify** needs to inspect the premium series of a backfilled
  contract. **B5** (read-only SQL console) is the inspection tool.

---

## 2. What already exists vs the gap

### Backend we keep (no rebuild)
| Capability | Where |
|---|---|
| Expired collection (expiries→contracts→1m OHLCV+OI, bounded strikes, walk tuning) | `services/market-data-service/.../upstox/ExpiredBackfillService.java` |
| Sliding 3-window rate limiter (45/s · 450/min · 1800/30min) | `.../upstox/UpstoxRateLimiter.java` |
| Coverage flags (`complete`, `first_bucket`, `last_bucket`, `bar_count`) | `expired_contracts` (V025/V026) |
| OI backfill (per underlying/expiry/session) | `.../backfill/OiBackfillService.java` |
| Token entitlement probe | `GET /api/v1/market/admin/upstox-entitlement` (`UpstoxEntitlementController`) |
| **Status-endpoint precedent to copy** | `EodBackfillController` + `BhavcopyBackfillService.Status` (`AtomicReference<Status>`, state `NEVER_RUN\|RUNNING\|OK\|FAILED`, `lastRun`, `durationMs`, per-exchange `ExchangeResult`) |

### React conventions we build on (real components)
- **Routing/nav:** `App.tsx` routes under `RequireAuth → AppShell`; `MegaMenu.tsx` `SECTIONS` array.
- **`DataTable<Row>`** (`components/DataTable.tsx`) — config-driven, client-sorted, paginated,
  desktop sticky-table + mobile card list; `compareDecimal` for decimal-string sorts (never `parseFloat`).
- **Atoms** (`components/atoms/`): `Metric` (label+value pill), `OiBadge4` (WCAG-safe colour ring),
  `Select`, `DateInput`, `GoButton`, `SignedCount`.
- **Live progress pattern:** `useJobsLive` (`api/backtests.ts`) subscribes STOMP `/topic/jobs/stream`,
  merges `{jobId,status,progress}` frames into the React-Query cache via `setQueryData`; progress bar =
  `h-1.5 rounded-full bg-surface-2` + inner `bg-accent` width-%. `wsClient.topic(dest, onMsg)`.
- **API client:** `apiFetch<T>` (`api/client.ts`), base `/api/v1`, XSRF cookie→header on mutations,
  `listItems({items})` envelope helper, decimal-as-string.
- **Styling:** Tailwind 4 + hand-rolled `--ay-*` tokens (NOT shadcn); `applyTheme` data-attr; ECharts
  via lazy `EChart` wrapper.
- **State:** React-Query v5 + Zustand; forms = `useState` (no form lib).

### Backend gaps this wave fills (from recon)
1. **No `GET …/expired-backfill/status`** — no running flag, progress, or summary exposed (`running`
   is a private `AtomicBoolean`; `BackfillSummary` is logged post-completion only).
2. **No `GET …/oi-backfill/status`** — same.
3. **No coverage-summary endpoint** — `expired_contracts` coverage readable only per-symbol
   (`ExpiredBackfillRepository.coverage()`); no group-by-underlying/expiry aggregate; no `candles`
   `source='BACKFILL'` count-by-exchange method on `CandleRepository`.
4. **No quota-status endpoint** — `UpstoxRateLimiter` window state is private with no getter; the
   limiter is a private field inside `UpstoxExpiredInstrumentsClient`, not an injectable bean.
5. **No read-only SQL endpoint** for B5; **no historical-bar export endpoint** for B6.

### React primitives to add (shared, build once)
- **`Stepper`** (numbered-circle multi-step wrapper) — B3, B6.
- **`LogFeed`** (scrollable, sticky-tail, colour-coded line list) — B1.
- **`SqlEditor`** (textarea + monospace + Ctrl/Cmd-Enter; **no Monaco** — Monaco workers are broken
  under zoneless and the repo already bans them, use a styled `<textarea>`) — B5.
- **`Modal`** (overlay + focus-trap) — B1 task-detail, B5/B6 confirms.
- **`MultiCheckboxGroup`** (array select) — B3, B6.
- **`QuotaGauge`** (3 stacked window bars used/limit) — B4.

---

## 3. Features

Priority/effort: **HIGH/MED/LOW**, effort **S/M/L**.

### B1 — Collection Status page  ·  `/data-ops/status`  ·  HIGH · M
Live operator view of the running/last backfill job + history.

- **UI:** stat cards (`Metric`) for expiries / contracts / candles / errors; live **progress bar**
  (reuse the `useJobsLive` pattern); **current-action** text (e.g. "NIFTY 2025-07-24 → 176 contracts
  in band"); **status badge** (running/ok/failed via `OiBadge4` tones); recent-jobs **`DataTable`**;
  **task-detail `Modal`** with a **`LogFeed`** (last N lines, colour-coded error/warn/success/info).
- **Backend (new):** mirror the **`EodBackfillController` / `BhavcopyBackfillService.Status`
  precedent** — add an `AtomicReference<Status>` to `ExpiredBackfillService` **and**
  `OiBackfillService`, updated *during* `run()` (set `RUNNING`, increment expiries/contracts/candles,
  stamp `currentExpiry`, finalize `OK`/`FAILED` with `durationMs`). Expose:
  - `GET /api/v1/market/admin/expired-backfill/status`
  - `GET /api/v1/market/admin/oi-backfill/status`
  Return a `Status` record (state, startedAt, lastRun, durationMs, progress counts, currentExpiry,
  error, last ~50 log lines). Live updates: **poll** the status endpoint every 2 s on the page (the
  STOMP jobs stream is backtest-scoped; a small poll is simpler than wiring a new WS topic — can
  upgrade to WS later).
- **Acceptance:** with a backfill running, the page shows non-zero progress climbing, the current
  expiry advancing, badge = running; after completion badge = ok with final counts; a failed run
  shows the error + last logs.

### B2 — Coverage / Data dashboard  ·  `/data-ops/coverage`  ·  HIGH · S–M
"What do we actually have on disk?" — answers the loaded/remaining question natively.

- **UI:** top stat cards (total expired contracts, complete vs partial, 1m candle rows, hypertable
  bytes, newest bucket); per-underlying `DataTable` (NIFTY/SENSEX → expiries loaded, contracts,
  complete %, partial count, row count, date span); coverage `OiBadge4` per row.
- **Backend (new):** `GET /api/v1/market/admin/coverage-summary` returning a `Map`/record:
  per-underlying + per-exchange rollup from `expired_contracts` (group-by `underlying_symbol`,
  `exchange`; count, `count(*) filter (where complete)`, `min/max(expiry)`) joined with `candles`
  `source='BACKFILL'` counts. Add `CandleRepository.countBackfillByExchange()` +
  `ExpiredBackfillRepository.coverageSummary()` aggregate queries (read-only).
- **Acceptance:** numbers reconcile with hand-run `psql` (e.g. NFO complete/partial counts match);
  SENSEX shows 0 until its backfill runs, then climbs.

### B3 — Backfill Collection Wizard  ·  `/data-ops/collection`  ·  HIGH · M
Turn the blind `POST` into a guided form.

- **UI (`Stepper`, 4 steps):** (1) underlyings (`MultiCheckboxGroup`: NIFTY/SENSEX); (2) contract
  types (CE/PE/FUT — radio Options/Futures/Both); (3) date range (`DateInput` from/to, default
  trailing 365 d) + **strike-band** note (bounded ±20% — read-only display, our server enforces it);
  (4) interval `Select` (1m default) + **force** toggle (re-verify complete) + **Start**. On submit →
  jump to **B1 status** page.
- **Backend:** **none new** — wires the existing `POST /api/v1/market/admin/expired-backfill` with
  the `ExpiredBackfillRequest{underlyings,from,to,interval,force}` body. Handle 202 (→ status), 409
  (already running — show toast), 503 (analytics not configured — show config hint).
- **Acceptance:** wizard reproduces the current default resume (empty body) AND a scoped run (e.g.
  SENSEX only, last 90 d, force); 409 surfaces cleanly when a run is in flight.

### B4 — Quota / rate-limit widget  ·  embedded on B1 + B2  ·  MED · S
See quota burn before it stalls (this session paused on quota exhaustion, blind).

- **UI:** `QuotaGauge` — 3 horizontal bars (1 s / 1 min / 30 min) used/limit with %, amber ≥80%,
  red ≥95%; "configured ✓ / entitlement verified at …" line (read-only).
- **Backend (new):** promote `UpstoxRateLimiter` to an injectable `@Bean` (currently a private field
  in `UpstoxExpiredInstrumentsClient`); add `getUsageStats()` exposing each window's `used/max/
  remaining` + window reset time. Endpoint `GET /api/v1/market/admin/upstox-quota-status`. Token
  status reuses the existing `upstox-entitlement` probe + a cached `verifiedAt`. **Conditional on
  `artha.upstox.analytics.enabled`** — mock profile returns 503/empty (gauge shows "not configured").
- **Acceptance:** during a backfill the 30-min bar fills toward 1800 and recovers; never shows
  used>max; mock stack shows "not configured" without erroring.

### B5 — Query / Scan console  ·  `/data-ops/query`  ·  MED · M
Read-only SQL over the candle/contract store; pairs with the Part-2 value-verify.

- **UI:** `SqlEditor` (`<textarea>`, Cmd/Ctrl-Enter to run) + **preset sidebar** (e.g. "recent NIFTY
  1m premium for a strike", "contracts by expiry", "coverage holes"); results `DataTable` (column
  auto-detect, row-cap notice); **Download CSV** (Parquet optional/deferred).
- **Backend (new) — security-critical:** `POST /api/v1/market/admin/query` `{sql,row_limit}`:
  - **Allowlist `SELECT`/`WITH` only**; reject `;`-multi-statements, DDL/DML, and any token outside
    an allowlist; **bind to the read-only per-schema role** (`ay_*` read-only roles already exist by
    convention) — do NOT run as `artha` (the single-writer).
  - Enforce `statement_timeout`, a hard **row cap** (e.g. 1000, `truncated` flag), and restrict to
    `marketdata.*` (candles, caggs, expired_contracts).
  - Export: `POST /api/v1/market/admin/query/export` `{sql,format:csv}` → file stream.
- **Acceptance:** a `SELECT` over `candles` returns rows; `INSERT`/`UPDATE`/`DROP`/`;` and
  cross-schema reads are rejected at the boundary; row cap + truncation flag work; CSV downloads.

### B6 — Export wizard  ·  `/data-ops/export`  ·  LOW · M
Historical-bar export with OpenAlgo symbols (we backtest in-platform, so low priority).

- **UI (`Stepper`, 4 steps):** underlyings → expiries (loaded from coverage) → format (CSV/JSON/ZIP;
  Parquet optional) + options (OpenAlgo symbol col, metadata, separate-files, time-window
  N-days-before-expiry) → review + progress + download.
- **Backend (new):** `POST …/export/start` (async, returns jobId) + `GET …/export/status/{id}` +
  `GET …/export/download/{id}`, streaming from `candles` (`source='BACKFILL'`) joined with
  `expired_contracts` metadata; OpenAlgo symbol = the stored `tradingsymbol`. Reuse the B1 `Status`
  pattern for progress.
- **Acceptance:** CSV for one underlying+expiry has `openalgo_symbol,date,time,timestamp,o,h,l,c,v,oi`;
  ZIP = per-contract CSV + metadata.

---

## 4. Backend work — consolidated

| Item | New endpoint(s) | Code | Migration? |
|---|---|---|---|
| B1 status (expired + OI) | `GET …/expired-backfill/status`, `GET …/oi-backfill/status` | add `AtomicReference<Status>` to both services (copy `BhavcopyBackfillService.Status`), update during `run()` | **none** (in-memory) |
| B2 coverage | `GET …/coverage-summary` | `ExpiredBackfillRepository.coverageSummary()` + `CandleRepository.countBackfillByExchange()` aggregates | none |
| B3 wizard | — (reuses existing POST) | FE only | none |
| B4 quota | `GET …/upstox-quota-status` | `UpstoxRateLimiter` → `@Bean` + `getUsageStats()`; reuse entitlement probe | none |
| B5 query | `POST …/query`, `POST …/query/export` | new read-only query service bound to read-only role + allowlist | none |
| B6 export | `POST …/export/start`, `GET …/export/status/{id}`, `GET …/export/download/{id}` | exporter over `candles`+`expired_contracts`; B1 `Status` pattern | none |

**No Flyway migrations** are required for B1–B5 (status is in-memory, queries are read-only).
An optional **job-history table** (audit of past runs) is **deferred** — B1 in-memory status covers
the live need; persistence only matters if the owner wants run history across restarts.

**Contract drift:** B1/B2/B4/B5/B6 add new `@*Mapping` paths and query params → these **DO** drift the
springdoc snapshot. Each PR must re-capture `ContractCaptureTest` (`-Dcontracts.capture=true`),
regen TS (`npx openapi-typescript@7 → contracts/gen/*.d.ts`), and pass `tsc --strict`. (Generic
`Map<String,Object>` returns are not enumerated, so prefer typed records where the FE needs fields.)

---

## 5. PR breakdown & sequencing

1. **PR-DO0 — primitives + section shell:** `Stepper`, `LogFeed`, `SqlEditor`, `Modal`,
   `MultiCheckboxGroup`, `QuotaGauge` + the `/data-ops` menu section and empty routed pages. (Unblocks the rest.)
2. **PR-DO1 — B1 status** (backend Status + 2 endpoints + page). *Highest value — surfaces the run in flight.*
3. **PR-DO2 — B2 coverage** (aggregate endpoints + dashboard).
4. **PR-DO4 — B4 quota** (limiter bean + endpoint + gauge; small, can fold into DO1/DO2).
5. **PR-DO3 — B3 wizard** (FE-only over existing POST).
6. **PR-DO5 — B5 query console** (security review required on the query endpoint).
7. **PR-DO6 — B6 export** (last; lowest value for us).

DO1+DO2+DO4 are the MVP (read-only, surfaces today's running backfill). DO3 next. DO5/DO6 optional.

---

## 6. Verify gates (per PR)

- **Backend:** `*Test` unit + IT; `ContractCaptureTest` re-capture when paths/params change; JaCoCo ≥60%.
- **Frontend (`frontend-react`):** `npm run lint` + `npm run test:ci` + `npm run build`; new
  components get RTL specs (mirror the `DataTable` spec).
- **E2E/axe:** desktop + 480 px (S24-Ultra baseline) for each new page; icon-button a11y
  (`aria-hidden` on glyphs) per the PrimeNG/React conventions.
- **ci-contracts:** must pass on the regenerated spec (breaking diff = fail).

---

## 7. Security & guardrails

- **No credential-entry UI.** ExpiryTrack lets users type API key/secret into a Settings form — we
  **do not replicate this**. The Upstox analytics token stays in `deploy/secrets/upstox_analytics_token`,
  placed by the owner. B4 surfaces only **read-only status** ("configured ✓ / entitlement verified").
- **No OAuth UI** (our auth is Kite-live + file secret, not browser OAuth).
- **B5 query endpoint is the sensitive surface:** SELECT/WITH allowlist, single-statement, bound to a
  **read-only DB role** (never `artha`), `statement_timeout`, hard row cap, `marketdata`-schema-only.
  Treat the query-service review as a gate on PR-DO5.
- All `/api/v1/market/admin/*` endpoints stay behind the gateway auth filter (loopback-only deploy).

---

## 8. Divergences from ExpiryTrack (intentional)

| ExpiryTrack | Here | Why |
|---|---|---|
| DuckDB + SQLite | TimescaleDB | already our store; caggs give multi-timeframe |
| Settings credential form | file secret + read-only status | security rule: never enter tokens into a form |
| Browser OAuth login | Kite-live + file token | different auth model |
| In-process per-job dict | in-memory `AtomicReference<Status>` | matches our `EodBackfill` precedent |
| Charts: none | none here either | analytics lives in the oipulse waves, not here |

---

## 9. Open decisions for owner

1. **MVP scope** — ship DO1+DO2+DO4 (read-only monitor) first and defer the wizard/query/export? (recommended)
2. **B5 query console** — worth the security review now (helps the Part-2 value-verify), or defer until after value-verify?
3. **Job history persistence** — in-memory status only (current plan), or add a `backfill_jobs`
   audit table so run history survives a market-data restart?
4. **Live updates on B1** — poll (simple, planned) vs a new STOMP topic (consistent with backtests)?
