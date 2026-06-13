# Stage D — Backtest + Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans / subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. The authoritative spec is [ARTHAYANTRA_2_STAGE_D_BACKTEST_OPTIMIZER.md](../../design/ARTHAYANTRA_2_STAGE_D_BACKTEST_OPTIMIZER.md); this plan sequences it into executable tasks. Read the matching design §/Phase before each task.

**Goal:** Turn the frozen Stage-C `strategy-engine` JAR into a full backtesting + optimization platform — an authoritative Postgres `jobs` spine with crash-safe Redis-Streams dispatch, a bar-by-bar replay engine proven byte-identical to live, anti-overfitting controls (walk-forward folds, computed regime attribution, stress guard), run analytics (benchmark-relative metrics + Monte Carlo), and an Optuna-driven optimizer that sweeps `optimize.parameters` and promotes winners to drafts.

**Architecture:** Two new services — `backtest-service` (Java 21 / Boot 3.5.15, no Modulith, port 8083) and `optimizer-service` (Python 3.12 / FastAPI 0.115 + Optuna 4.x, port 8084) — plus a new dependency-free `libs/black76-math` and a `FillSimulator` port added to `libs/strategy-engine`. backtest-service owns PG schema `backtest` and reads `marketdata` read-only (CD-1 grant, already provisioned). Redis Streams are transport-only; the Postgres `jobs` table is the single source of truth (ADR D12). The optimizer never evaluates a strategy itself — it only proposes parameter vectors that backtest-service evaluates through the *same* engine JAR as live (D6).

**Tech Stack:** Java 21, Spring Boot 3.5.15, ta4j 0.22.0, springdoc 2.8.9, Testcontainers (timescale/timescaledb:2.17.2-pg17 + redis:7.4-alpine), Flyway 11; Python 3.12, FastAPI 0.115, Optuna 4.x, pytest 8, respx, ruff; Redis Streams; TimescaleDB.

**Execution parameters (owner-confirmed 2026-06-13):**
- **Branch:** single `feat/stage-d-backtest-optimizer`; one commit per phase; one Stage-D PR at the end (matches Stage A/B/C). Do **not** merge — owner merges.
- **S3 pruner spike:** RUN the pure-Python calibration study, record a dated ADR amendment, ship Phase 34 with fold-fed MedianPruner **enabled**.
- **Cadence:** run end-to-end, pause only on a genuine blocker.
- **Testing:** mock profile (`SPRING_PROFILES_ACTIVE=mock`, zero Kite creds). Live mode is deployed; use the mock stack for Stage-D testing.
- **Deliverable:** a Stage-D manual testing guide at the end.

---

## Shared conventions (apply to every phase)

- **Module/package:** parent `arthayantra-parent 2.0.0-SNAPSHOT`; Java services package `in.arthayantra.<service>` (`backtest`); add `<module>` to root `pom.xml`.
- **Java service deps (template = `services/strategy-signal-service`):** `common-web-core`, `common-web-servlet`, `strategy-schema`, `strategy-engine`, `market-calendar`, `spring-boot-starter-web`, `-data-redis`, `-jdbc`, `postgresql` (runtime), `springdoc-openapi-starter-webmvc-api:2.8.9`, `-actuator`, `micrometer-registry-prometheus`; tests: Testcontainers (postgresql, junit-jupiter), `flyway-core`, `flyway-database-postgresql`, awaitility, assertj. **No Spring Modulith** for backtest-service (CD-17 / D7 — single-purpose engine).
- **Jackson (from `common-web-core`):** `BigDecimal` serializes as JSON string; `OffsetDateTime` at `+05:30`. All money math `BigDecimal`/NUMERIC; all timestamps `TIMESTAMPTZ` normalized to `Asia/Kolkata`.
- **Error envelope (from `common-web`):** `ErrorResponse{code,message,details}` via `ApiException`/`NotFoundException`/`ConflictException` + `ErrorCodes`. Stage-D adds codes: `DATA_GAP` (422), `WINDOW_CONTAMINATED` (422), `CONFLICT_JOB_TERMINAL` (409), `INVALID_PARAMETER_PATH` (400), `VALIDATION_*`. Confirm exact `ErrorCodes` constants when implementing.
- **Datasource (template app.yml):** `jdbc:postgresql://${DB_HOST:timescaledb}:${DB_PORT:5432}/artha?currentSchema=backtest`; user `artha`; password from file `${ARTHA_DB_PASSWORD_FILE:/run/secrets/postgres_password}` via `SecretFilePasswordPostProcessor` (copy from strategy-signal-service); default profile `mock`; `dev` profile overrides to `127.0.0.1`. **Phase-28 verification item:** confirm how the single-writer/read-only-on-marketdata rule is enforced at connection time (login as `artha` vs `SET ROLE ay_backtest`) — the D10 IT asserts backtest can SELECT but not INSERT into `marketdata`. Read strategy-signal-service's datasource wiring + the admin migration before finalizing.
- **Migrations:** `deploy/flyway/backtest/V00x__*.sql` (lineage already wired into `deploy/flyway/flyway-run.sh`; admin lineage already creates `ay_backtest` role + `backtest` schema + marketdata read grant). Grant `SELECT,INSERT,UPDATE` (+ sequences) on new tables to `ay_backtest`. Migrations applied by the `flyway-init` one-shot in compose and by Testcontainers in ITs; the service does **not** run Flyway at boot.
- **Compose:** `deploy/docker-compose.yml`; add `backtest-service` (8083, mem_limit 896m) in Phase 28 and `optimizer-service` (8084, mem_limit 256m) in Phase 33; both `depends_on` flyway-init `service_completed_successfully` + redis healthy; secrets `postgres_password`; `SPRING_PROFILES_ACTIVE` from `.env`. Dockerfile pattern from strategy-signal-service (temurin:21-jre-alpine, non-root `artha`, actuator healthcheck, `-Xmx` cap under mem_limit).
- **Gateway routes:** `/api/v1/backtests/**` → backtest-service:8083 and `/api/v1/optimizations/**` → optimizer-service:8084 already exist in `edge-gateway` application.yml. Progress: publish `{jobId,...}` to Redis; gateway relays to STOMP `/topic/...` over `/ws`. **Phase-28 wiring item:** read `RedisTopicHub`/`StompWebSocketHandler` to confirm the exact topic↔channel mapping; prefer per-job channel `jobs.<jobId>` (reuses the generic relay) and also honor the design's `jobs.progress` intent — resolve concretely against gateway code.
- **Contracts (CD-8):** commit `contracts/backtest-service.openapi.json` (springdoc, captured by a `ContractCaptureTest` on the mock profile — copy the Stage-C Phase-24 pattern) and `contracts/optimizer-service.openapi.json` (FastAPI-native dump). Extend the `ci-contracts` matrix; rerun TS client gen (`contracts/gen`).
- **CI:** extend `.github/workflows/ci-java.yml` build-test + build-images matrix for backtest-service; add `.github/workflows/ci-optimizer.yml` (ruff + pytest ≥75% line, path-filtered, gitleaks, image→GHCR) in Phase 33. `ci-migrations.yml` already runs the backtest lineage; it asserts the CD-1 grant.
- **Coverage gates:** `libs/strategy-engine` ≥70% branch (existing), Java services ≥60% line (jacoco-check, opt-in per module), optimizer ≥75% line.
- **Commit style:** Conventional Commits; use the exact `Commit message` string from each phase spec. End every commit message with the Co-Authored-By trailer. Commit per phase (section-per-commit), after `./mvnw verify` (and `pytest`) are green.
- **Build/run:** `./mvnw -pl <module> -am verify`; `./ay.sh up` (or `.\ay.ps1 up`) for the mock stack. On this Windows machine see the machine-quirks memory (compose `--env-file .env`, PS5.1 UTF-8-BOM checkstyle trap, `$$` PHC escaping, gitleaks/Smart-App-Control, applied-migration checksum lock).
- **Golden/parity discipline:** committed fixtures are frozen; any engine change altering output requires an explicit golden update in the same commit. NUMERIC string-compare (exact decimals, no epsilon). The Stage-C golden fixtures live in `libs/strategy-engine/src/test/resources/golden/` (NIFTY 50 + INDIA VIX 1m, 5 days, strategy YAMLs, expected signals JSON).

---

## Phase sequence (one commit each)

| # | Phase | Module(s) | Migration | Commit subject |
|---|---|---|---|---|
| 1 | 28 — jobs spine | services/backtest-service (new) | `backtest/V002__jobs.sql` | `feat(backtest): job spine with authoritative jobs table, redis streams dispatch and crash-safe workers` |
| 2 | 29 — FillSimulator + costs | libs/strategy-engine | — | `feat(strategy-engine): fillsimulator port with ltp_slippage/v1 and full statutory cost model` |
| 3 | 30 — replay + parity | services/backtest-service, libs/strategy-engine | `backtest/V003__runs_trades.sql` | `feat(backtest): replay engine with full metrics catalog, reproducibility triple and live/replay parity goldens` |
| 4 | 30A — options fidelity | libs/black76-math (new), services/market-data-service, services/backtest-service | — | `feat(backtest): options replay fidelity contract with snapshot-granularity replay and black-76 synthetic premium mode` |
| 5 | 31 — walk-forward | services/backtest-service | — | `feat(backtest): walk-forward folds with persisted fold metrics and sharpe-degradation diagnostic` |
| 6 | 32 — regime + stress | services/backtest-service | — | `feat(backtest): computed t-1 regime attribution, fold-aggregation objective and stress-test contamination guard` |
| 7 | 32A — benchmark + MC | services/backtest-service | — | `feat(backtest): benchmark-relative metrics, buy-and-hold curve and seeded monte carlo run analytics` |
| — | S3 spike | tools/ or services/optimizer-service/spikes | — | `chore(optimizer): S3 pruner-calibration spike + recorded ADR amendment` |
| 8 | 33 — optimizer core | services/optimizer-service (new) | `backtest/V004__optimization_trials.sql` | `feat(optimizer): fastapi + optuna ask/tell core with grid/random sweeps over redis streams` |
| 9 | 34 — TPE/NSGA-II + promote | services/optimizer-service | — | `feat(optimizer): tpe/nsga2 samplers, fold-fed median pruning, plateau leaderboard and draft promotion` |
| — | manual guide | docs/manual-testing | — | `docs(stage-d): manual testing guide` |

Each phase: write failing tests first (TDD) → minimal implementation → `./mvnw -pl <mod> -am verify` (and/or `pytest`) green → mock-stack smoke check where the phase spec calls for it → commit with the exact subject. If a phase exceeds its token target, split per the design's "If phase too big" sub-units (a)/(b)/...

---

### Task 1 — Phase 28: backtest-service jobs spine

**Design refs:** §D.1, §D.2, §D.3 (`jobs` table), §D.5 (endpoints + state machine), §D.14, Phase 28 spec.

**Files:**
- Create: `services/backtest-service/pom.xml`, `Dockerfile`, `src/main/resources/application.yml`
- Create: `src/main/java/in/arthayantra/backtest/BacktestServiceApplication.java`
- Create: config — `ClockConfig`, `OpenApiConfig`, `SecretFilePasswordPostProcessor` (copy from strategy-signal-service), `RedisStreamsConfig`
- Create: jobs spine — `jobs/JobsController.java`, `JobsService.java`, `JobRepository.java`, `Job.java` (record/enum), `JobKind`/`JobStatus` enums, `BacktestRunRequest.java`
- Create: dispatch — `dispatch/JobStreamDispatcher.java` (XADD), `JobStreamWorker.java` (XREADGROUP/XAUTOCLAIM consumer-group loop), `WorkerPool.java` (`max(1, cores-2)` platform threads), `ProgressPublisher.java`, `StreamBootstrap.java` (declare streams/groups + stale-`running` re-queue on startup), `ReplayStub.java` (no-op work for this phase)
- Create: strategy-version client — `client/StrategyVersionClient.java` (REST → strategy-signal `/versions/{v}`, Caffeine-cached)
- Create: `db/V002__jobs.sql` under `deploy/flyway/backtest/`
- Modify: root `pom.xml` (add `<module>services/backtest-service</module>`)
- Modify: `deploy/docker-compose.yml` (backtest-service entry)
- Modify: `services/market-data-service` system-status rollup consumer to surface `jobs:summary` (IT asserts the field appears in `GET /api/v1/system/status`) — confirm where the Stage-B Phase-17 rollup lives first.
- Create: `contracts/backtest-service.openapi.json` + extend `ci-contracts` matrix + `ci-java.yml` matrices.
- Test: `src/test/java/.../JobSpineIT.java` (Testcontainers), `JobsControllerTest`, `StreamReconciliationIT`, `ContractCaptureTest`.

**`backtest/V002__jobs.sql` (authoritative — from §D.3, NOT the explore-agent guess):**
```sql
-- Stage D Phase 28: authoritative jobs table (ADR D12 — PG is truth, Redis is transport).
CREATE TABLE jobs (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  kind                TEXT NOT NULL CHECK (kind IN ('BACKTEST','OPTIMIZATION','TRIAL')),
  parent_job_id       UUID NULL REFERENCES jobs(id),
  status              TEXT NOT NULL DEFAULT 'queued'
                        CHECK (status IN ('queued','running','completed','failed','cancelled')),
  progress            SMALLINT NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  strategy_version_id UUID NULL,               -- soft cross-schema ref (no FK)
  request             JSONB NOT NULL,          -- symbols, interval, range, method, overrides, resolved universe, purpose
  error               TEXT NULL,
  worker_id           TEXT NULL,               -- stale-running re-queue on startup
  correlation_id      TEXT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at          TIMESTAMPTZ NULL,
  finished_at         TIMESTAMPTZ NULL
);
-- live-status partial index for the claim/re-queue scans; parent index for sweep rollups
CREATE INDEX jobs_live_status_idx ON jobs (status, created_at) WHERE status IN ('queued','running');
CREATE INDEX jobs_parent_idx ON jobs (parent_job_id);

GRANT SELECT, INSERT, UPDATE ON jobs TO ay_backtest;
-- (gen_random_uuid from pgcrypto/pg17 builtin; no sequence needed for UUID PK)
```

**TDD focus (write these first):**
- `JobsControllerTest`: `POST /api/v1/backtests/run` with a valid body → 202 `{jobId,status:"queued"}`; unknown strategy → 404; (stress) overlapping window deferred to Phase 32. `DELETE /jobs/{id}` queued → 204; terminal → 409 `CONFLICT_JOB_TERMINAL`.
- `JobSpineIT` (Timescale+Redis containers): submit → worker claims (conditional `UPDATE … WHERE status='queued'`) → stub runs → progress 0→100 observed on the progress channel → completed exactly once. Duplicate stream delivery is harmless (loser of the claim race XACKs and drops).
- `StreamReconciliationIT`: a `running` row whose worker "vanished" (simulate by inserting a running row with a dead worker_id, or killing the worker bean) is re-queued on `StreamBootstrap` startup via `XAUTOCLAIM` + stale-`running` scan → completes exactly once. `XACK` happens only on terminal state.
- Cancel during run honored at a bar-batch checkpoint (stub loops with a cancel check).

**Implementation steps:**
- [ ] Add module to root pom; scaffold `pom.xml` (no modulith), `Dockerfile`, `application.yml` (mock+dev profiles), `BacktestServiceApplication` (`@SpringBootApplication @EnableScheduling`).
- [ ] Write `V002__jobs.sql`; wire a Testcontainers base that runs admin+marketdata(minimal)+backtest lineages (model on strategy-signal-service IT base — confirm how it seeds the marketdata grant in tests).
- [ ] `Job` domain + `JobRepository` (JdbcTemplate; insert queued, conditional claim, update progress/status/error, list/paged, find live, find stale-running).
- [ ] `RedisStreamsConfig` + `StreamBootstrap`: declare `jobs.backtest`/`cg-backtest` (and declare `jobs.backtest.trials`/`cg-trials` + `optimizations.results` for later); `XAUTOCLAIM` pending + stale-`running` re-queue `@PostConstruct`/`ApplicationRunner`.
- [ ] `WorkerPool` (`max(1,cores-2)` platform threads) + `JobStreamWorker` (XREADGROUP loop, conditional claim, run `ReplayStub`, progress publish every N, terminal update + XACK, drop-on-lost-claim).
- [ ] `ProgressPublisher` (write table + publish progress delta to the resolved channel) + `jobs:summary` Redis key (queued/running counts).
- [ ] `JobsController` + `JobsService` + `StrategyVersionClient` (validate version exists, Caffeine cache) + DELETE cancel (set a cancel flag the worker observes; 409 if terminal).
- [ ] Metrics: `ay_backtest_queue_depth`, `ay_backtest_workers_busy`, `ay_redis_stream_pending` (Micrometer gauges).
- [ ] Wire `jobs:summary` into the Stage-B system-status rollup (+ IT).
- [ ] Compose entry; `ContractCaptureTest` → commit `contracts/backtest-service.openapi.json`; extend ci-contracts + ci-java matrices; rerun `gen:api`.

**Build & verify:** `./mvnw -pl services/backtest-service -am verify`; then mock stack: `./ay.ps1 up` and `curl -X POST 127.0.0.1:8080/api/v1/backtests/run -b cookies.txt -d '{...}'` → 202; observe progress on `/topic/jobs.<jobId>`.

**Acceptance:** full D12 state machine incl. crash recovery; STOMP progress reaches a probe; jobs survive restart (Redis never source of truth).

**Commit:** `feat(backtest): job spine with authoritative jobs table, redis streams dispatch and crash-safe workers`

**If too big:** (a) service + migration + submit/status endpoints; (b) Streams workers + progress + recovery.

---

### Task 2 — Phase 29: FillSimulator port + full cost model (engine JAR)

**Design refs:** §D.11 (full), Phase 29 spec, schema `costs`/`fees`/`risk.session` (already frozen).

**Files (all under `libs/strategy-engine/src/main/java/in/arthayantra/strategyengine/fills/`):**
- Create: `FillSimulator.java` (port interface), `LtpSlippageV1.java` (impl), `FillRequest.java`/`Fill.java` (records — side, qty, reference price, instrument class, touch_basis), `ReferencePrice.java` (next_open | at_close | next_tick), `InstrumentClass.java` enum (EQUITY, OPTION, FUTURE, INDEX), `SlippageModel.java`, `CostModel.java`, `FeeSchedule.java`, `FeeConstants.java` (the one constants file — Zerodha/NSE rates with `// source: …, date: 2026-06-12` comment), `TouchBasis.java` enum (CLOSE_EVAL, INTRABAR_1M, BAR_HL_WORSTOF), `PreCloseBarView.java`.
- Test: `fills/FillVectorTest.java` + committed fixtures under `src/test/resources/fills/` (per asset class × slippage form × fee combination; futures; at_close; intra-bar touch; pre-close view).

**TDD focus (committed vectors, NUMERIC string-compare, exact to the paisa):**
- Slippage XOR: `slippage_ticks` only; `slippage_bps` only; neither → per-class fallback (equities 5 bps; options `max(1 tick, half spread)` with quote, else 1 tick; futures fallback).
- **The decisions-log case:** ₹0.05 tick on ₹10 OTM premium = 50 bps (assert flat-bps would be wrong).
- Cost legs: brokerage `per_lot_inr` (options) / `pct_per_side` (equities) / min-of-pct/flat (futures); `fees{}` — STT on sell-side option premium; exchange txn on premium; GST on (brokerage+txn); stamp on buy side; SEBI fee. Sell-side-only futures STT; buy-side-only stamp.
- A9: `at_close` → reference = signal-bar close. Intra-bar touch on closed 1m bars → `INTRABAR_1M`; missing 1m → primary-bar high/low worst-of with gap-through-at-open → `BAR_HL_WORSTOF`; close-eval → `CLOSE_EVAL`. Pre-close bar view assembled from 1m up to `pre_close_at` is byte-identical across two assemblies.
- Determinism: identical inputs ⇒ identical fills across runs.

**Implementation steps:**
- [ ] Define the port + records; `FeeConstants` with pinned current Zerodha/NSE/SEBI/GST/STT/stamp rates + source+date comment + refresh note.
- [ ] `SlippageModel` (ticks ⊕ bps + per-class fallbacks; needs tick size + optional quote). `CostModel`/`FeeSchedule` (side-aware legs; BigDecimal, round to paisa).
- [ ] `LtpSlippageV1` ties reference-price selection (next_open/at_close/next_tick) + slippage + costs → `Fill`. No partial fills.
- [ ] A9: `PreCloseBarView` assembler; `touch_basis` classification helper (the actual 1m-drill *wiring* into replay is Phase 30 — here define the JAR-level rule + worst-of fallback semantics and vectors).
- [ ] Commit fixtures + `FillVectorTest`; ensure ≥70% branch coverage holds.

**Build & verify:** `./mvnw -pl libs/strategy-engine -am verify` (vectors green; A9 vectors green; paisa-exact).

**Acceptance:** identical inputs ⇒ identical fills; fee math exact to the paisa incl. futures legs. FAIL: a second fill impl outside this JAR; any sub-1m exit evaluation.

**Commit:** `feat(strategy-engine): fillsimulator port with ltp_slippage/v1 and full statutory cost model`

**If too big:** (a) port + cost model + base vectors; (b) A9 extensions + vectors.

---

### Task 3 — Phase 30: replay engine + metrics + parity golden test

**Design refs:** §D.6, §D.9 (metrics), §D.8 (parity), §D.3 (`backtest_runs`/`backtest_trades`), Phase 30 spec. **This is the D15 headline gate.**

**Files:**
- Create: `db/V003__runs_trades.sql` (full §D.3 column set, additive-first — all later-phase columns created NULL-able now).
- Create: replay — `replay/ReplayEngine.java` (bar-by-bar), `CandleReader.java` (read-only SQL from `marketdata` 1m + caggs), `PreflightCoverage.java` (expected vs present per `MarketCalendar` → `DATA_GAP`), `MetricsCalculator.java` (§D.9 catalog), `DataHash.java` (SHA-256 over ordered tuple set), `EquityCurveDownsampler.java` (~500 pts), `TradeRecorder.java`, `IntrabarExitResolver.java` (1m drill + worst-of fallback → `touch_basis`), `RunRepository.java`, `TradeRepository.java`, `ResultsController.java` (results + trades endpoints).
- Replace: `ReplayStub` → real replay invoked by `JobStreamWorker`; handle TRIAL-kind jobs (apply `params_override` via closed path grammar, emit metrics onto `optimizations.results`).
- Create: extended pre-flight — context-instrument coverage, corporate-action warning, lot-size as-of (`marketdata.contract_spec_history`).
- Test: `replay/ParityGoldenIT.java` (live signal engine vs replay byte-identity), `MetricsGoldenTest`, `PreflightCoverageIT` (422 + windows), `TrialJobRoundtripIT`, `IntrabarExitTest`, `MarketdataReadOnlyIT` (backtest can SELECT not INSERT into marketdata).

**`backtest/V003__runs_trades.sql`** — create `backtest_runs` with the **full** §D.3 column set (incl. `seed`, `data_hash`, and the NULL-able `sharpe_degradation`, `fold_metrics`, `oos_fold_mean/std`, `universe_checksum`, `premium_source`, `alpha`, `beta`, `information_ratio`, `excess_cagr`, `benchmark_curve`, `montecarlo_summary`, downsampled `equity_curve`, `engine_version`); `backtest_trades` (incl. `touch_basis` NULL-able). Indexes: runs `(job_id)`, `(strategy_version_id, completed_at DESC)`, `(sharpe DESC)`; trades `(run_id, seq)`. Grants to `ay_backtest`. (Writers for the NULL-able analytics columns land in Phases 31/32/32A; `touch_basis` written here.)

**TDD focus:**
- **Parity (headline):** the Stage-C golden fixture candles pushed tick-wise through the live signal engine vs replayed here ⇒ byte-identical signal lists incl. per-indicator breakdowns (`ScoreBreakdown`). Reuse `TickwiseGoldenRunner` / golden fixtures.
- Metric exactness: fixed dataset + strategy → `total_return/sharpe/maxDrawdown/winRate/tradeCount` as exact decimal strings.
- Reproducibility triple: same `(strategyChecksum, seed, dataHash)` ⇒ byte-identical trade list across two runs.
- Pre-flight: coverage gap → 422 `DATA_GAP` with missing windows; context-instrument gap names the context series; benchmark warm-up covered (depth ~200d trend / ~1y+20d vol — full consumer in Phase 32, but coverage check lands here for primary+context).
- Intra-bar: `INTRABAR_1M` when 1m present; `BAR_HL_WORSTOF` (gap-through at open) when 1m withheld — both deterministic; every closed trade has non-NULL `touch_basis`.
- Read-only: backtest role SELECTs but cannot INSERT into `marketdata`.

**Implementation steps:**
- [ ] `V003` migration; extend IT base to seed `marketdata` candles/caggs fixtures (reuse Stage-B/C seed data or load golden CSVs into a test `marketdata` schema).
- [ ] `CandleReader` (direct SQL, read-only) → `EngineSeries`; `PreflightCoverage` via `MarketCalendar.expectedMinuteBuckets`.
- [ ] `ReplayEngine`: BarSeries → indicators → gates → composite (engine JAR) → entries; exits per rules; fills via `LtpSlippageV1` (next_open) with full `costs`; single-threaded; cancel checkpoint every N bars; `IntrabarExitResolver` for `exit_intrabar`; btst pre-close + at_close.
- [ ] `MetricsCalculator` (full §D.9 catalog; rf 6.5% default; √252 / √(252×375) scaling) + `DataHash` + `EquityCurveDownsampler` + persistence (`RunRepository`/`TradeRepository`).
- [ ] TRIAL-kind handling (path-grammar overrides applied to pinned JSONB, never persisted; emit onto `optimizations.results`).
- [ ] Extended pre-flight (context instruments, corporate-action warning, lot-size as-of with pre-accrual honesty flag).
- [ ] `ResultsController` (`/results`, `/trades`); metrics `ay_backtest_job_duration_seconds{type}`, `ay_bars_replayed_total`; parity + metric goldens in CI.

**Build & verify:** `./mvnw -pl services/backtest-service,libs/strategy-engine -am verify`; mock stack — run a backtest on seeded candles, fetch results.

**Acceptance:** parity green (D15); byte-identical trade list on rerun; metrics NUMERIC; gap-through at bar open (never inside the gap); every closed trade has `touch_basis`.

**Commit:** `feat(backtest): replay engine with full metrics catalog, reproducibility triple and live/replay parity goldens`

**If too big:** (a) replay+fills+persistence; (b) metrics+endpoints; (c) parity+TRIAL handling; (d) A9 exec-semantics + extended pre-flight.

---

### Task 4 — Phase 30A: options fidelity contract + synthetic premium

**Design refs:** §D.15, Phase 30A spec, ADR A10.

**Files:**
- Create: `libs/black76-math/` (new module: `pom.xml` — dependency-free; `src/main/java/in/arthayantra/black76/Black76Math.java`) hoisting the pure pricing/IV math out of `services/market-data-service/.../options/Black76.java`.
- Modify: market-data-service `Black76` → delegate to `libs/black76-math` (behavior unchanged; existing Phase-14 Greeks goldens prove byte-identity); add module dep.
- Create (backtest-service): `replay/options/SnapshotPremiumReader.java` (native 5-min from `options_chain_snapshots`; refuse sub-5m snapshot-grade), `SyntheticPremiumMode.java` (Black-76 from underlying 1m; IV from nearest snapshot; flat-IV pre-archive caveats), `OptionsPreflight.java` (archive-coverage → `DATA_GAP`), `PremiumSource.java` enum (SNAPSHOT|SYNTHETIC_B76|NA).
- Modify: run persistence to write `premium_source` before results persist; results payload echoes `premiumSource` + caveats; leaderboard/compare flag mixed-source. Regenerate contract.
- Test: `libs/black76-math` goldens (exact decimal strings); market-data Greeks goldens unchanged; backtest options ITs (SNAPSHOT; snapshot gap→422; SYNTHETIC_B76 + flat-IV caveat; sub-5m refused; mixed-source flagged; synthetic determinism).

**Implementation steps:**
- [ ] Create `libs/black76-math` module (add to root pom); move pure math; re-point market-data-service; verify Greeks goldens byte-identical.
- [ ] backtest-service consumes `libs/black76-math`; snapshot reader (native 5-min) + options pre-flight; synthetic mode + caveats; `premium_source` provenance written-before-persist.
- [ ] Contract regen + `gen:api`.

**Build & verify:** `./mvnw -pl libs/black76-math,services/market-data-service,services/backtest-service -am verify`; mock stack — options backtest → `premiumSource=SNAPSHOT`.

**Acceptance:** Greeks byte-identical post-hoist; every options run non-NULL `premium_source`; synthetic reruns reproduce identical premium series. FAIL: synthetic rendered without `SYNTHETIC_B76`; a second Black-76 left in market-data-service; sub-5-min interpolation.

**Commit:** `feat(backtest): options replay fidelity contract with snapshot-granularity replay and black-76 synthetic premium mode`

**If too big:** (a) black76-math hoist + snapshot replay + archive pre-flight; (b) synthetic mode + provenance + flagging.

---

### Task 5 — Phase 31: walk-forward folds + degradation

**Design refs:** §D.4 guards 1–3 & 7, Phase 31 spec.

**Files (backtest-service):** `folds/WalkForwardExpander.java` (rolling/anchored fold windows via `MarketCalendar`), `FoldEvaluator.java` (train-params/test-eval per fold), `FoldMetrics.java` (JSONB shape `{fold,train{from,to},test{from,to},trainMetrics,oosMetrics,regimeMix:null}`), `ObjectiveAggregator.java` (mean default), `Degradation.java` (`train_sharpe − oos_sharpe`), `MinTradesValidity.java`; `FoldsController.java` (`GET /backtests/{id}/folds`). Modify `ReplayEngine`/worker to expand folds for optimization/TRIAL-context + stress runs (plain runs stay full-window). TRIAL workers emit per-fold OOS on `optimizations.results` + honor cancel at fold boundaries.
- Test: `WalkForwardExpanderTest` (anchored+rolling, step alignment, partial tail), `DegradationTest` (sign conventions incl. negative-Sharpe stability), `FoldsIT` (persist N folds both metric sets; `/folds` matches fixture; min_trades exclusion flagged not silent).

**Key invariants:** plain `POST /backtests/run` → full-window, `sharpe_degradation`/`fold_metrics` NULL. Implicit 70/30 split only for optimization/TRIAL-context + stress. `min_trades` default 30 → under-trading invalid, never ranked. Degradation is a **difference, not ratio**; n/a-suppress when train Sharpe < 0.5 or invalid.

**Build & verify:** `./mvnw -pl services/backtest-service -am verify`.
**Acceptance:** fold ranges reproduce hand-computed fixture; degradation NULL for plain runs. FAIL: ratio-based overfit score; silent min_trades inclusion.
**Commit:** `feat(backtest): walk-forward folds with persisted fold metrics and sharpe-degradation diagnostic`
**If too big:** (a) fold engine + persistence; (b) endpoints + degradation + min_trades.

---

### Task 6 — Phase 32: regime attribution + fold_aggregation + stress guard

**Design refs:** §D.4 guard 6 + S1C, §D.6 stress runs, Phase 32 spec.

**Files (backtest-service):** `regime/RegimeLabeler.java` (trend = close vs 200d SMA × vol = 20d realized vs trailing 1y median → 4 labels; **day T uses data through T−1 close only**), `BenchmarkSeriesReader.java` (read-only from `marketdata`), `regime/RegimeMix.java`, extend pre-flight for benchmark warm-up depth; `ObjectiveAggregator` consumes `fold_aggregation` (mean|min|mean_minus_std; exclude min_trades-failing folds from `min` with explicit flag; exclude pruned trials); `stress/StressGuard.java` (validate `purpose:stress_test` window vs ALL prior lineage jobs → `WINDOW_CONTAMINATED` listing intersecting jobs), `StressWindowController.java` (`GET /backtests/stress-window`), holdout-reuse counter.
- Test: `RegimeLabelerTest` (label math incl. **T−1 boundary** — entry-day close never consulted; deterministic), `FoldAggregationTest` (truth table; exclusion flags), `StressGuardIT` (overlap with a prior quick-backtest → 422 with offending jobIds; clean-window suggestion; reuse counter increments).

**Build & verify:** `./mvnw -pl services/backtest-service -am verify`.
**Acceptance:** identical labels across runs; contamination guard catches manual-backtest leaks (not only sweeps). FAIL: hand-labeled regime ranges; same-session look-ahead.
**Commit:** `feat(backtest): computed t-1 regime attribution, fold-aggregation objective and stress-test contamination guard`
**If too big:** (a) regime labeler + regimeMix + aggregation knob; (b) stress endpoints + lineage validation.

---

### Task 7 — Phase 32A: benchmark-relative metrics + Monte Carlo

**Design refs:** §D.16, §D.9 (benchmark rows), Phase 32A spec.

**Files (backtest-service):** `analytics/BenchmarkMetrics.java` (alpha/beta/information_ratio/excess_cagr vs `backtest.defaults.benchmark`; up/down capture in payload only; NULL-flagged when coverage absent; price-index-not-TRI caveat), `BenchmarkCurve.java` (normalized buy-and-hold, same ~500-pt downsampler → `benchmark_curve` beside `equityCurve`), `montecarlo/MonteCarlo.java` (seeded bootstrap of persisted `backtest_trades`, resample w/ replacement, N=1000 default; drawdown dist, 5/50/95 bands, risk-of-ruin, CIs CAGR/Sharpe; MC seed recorded), `MonteCarloController.java` (`GET /backtests/{id}/montecarlo?n=&seed=`; persist `montecarlo_summary` first call, serve from there; 422 no closed trades). Wire benchmark metrics into run completion. Regenerate contract.
- Test: `BenchmarkMetricsTest` (alpha/beta/IR/excess-CAGR on hand-computed series as exact decimal strings; up/down capture edge cases; coverage-absent → NULL+flag), `MonteCarloTest` (same `(trades,mcSeed,N)` ⇒ byte-identical bands; different seed → recompute-replace), `MonteCarloIT` (compute-once-persist-serve; zero-trade → 422; `benchmark_curve` timestamps align with `equity_curve`).

**Build & verify:** `./mvnw -pl services/backtest-service -am verify`; mock stack — `GET …/montecarlo` twice → identical summaries.
**Acceptance:** fixed MC seed reproduces identical summary; benchmark metrics match fixtures; curve overlays `equityCurve`. FAIL: MC mutating any replay result; benchmark metrics 0 instead of NULL when missing; MC as replay rerun.
**Commit:** `feat(backtest): benchmark-relative metrics, buy-and-hold curve and seeded monte carlo run analytics`
**If too big:** (a) benchmark metrics + buy-and-hold curve; (b) Monte Carlo endpoint + persisted summary.

---

### S3 spike — optimizer pruner calibration (entry gate to Phase 33/34)

**Design refs:** §D.13, §D.7 (CD-12 fold-fed pruning).

**Files:** `services/optimizer-service/spikes/s3_pruner_calibration.py` (pure-Python; synthetic strategy with known optimum + bootstrapped windows; grid vs TPE vs NSGA-II; sweep `n_startup_trials` / warm-up-fold counts; measure prune-on-noise vs early-regime bias vs convergence). Record outputs as a dated ADR amendment in the decisions log (§4.5) — create/update `docs/design/DECISIONS_LOG.md` (or the established location — find it first) — and tick the gate checkbox in `PHASE_GATES.md`.

**Output → Phase 34 config:** recorded `n_startup_trials`, warm-up-fold count, default `max_trials`, sampler/pruner defaults. (No backtest-service dependency; runs standalone before Phase 33.)

**Commit:** `chore(optimizer): S3 pruner-calibration spike with recorded pruner-default ADR amendment` (may fold into Phase 33 commit if small).

---

### Task 8 — Phase 33: optimizer-service core (grid/random + trial loop)

**Design refs:** §D.7, §D.1 (optimizer spec), §D.12 (path grammar), Phase 33 spec.

**Files (`services/optimizer-service/`):** `pyproject`/`requirements.txt` (hashes), `ruff.toml`, `Dockerfile` (python:3.12-slim), `app/main.py` (FastAPI + `/health` + prometheus-instrumentator + structlog JSON), `app/api/optimizations.py` (run/status/trials/cancel), `app/optuna_runner.py` (in-memory study per sweep; Grid/RandomSampler; ask/tell), `app/config_patch.py` (materialize patched config per trial; never persisted), `app/path_grammar.py` (mirror of §D.12 closed grammar; `INVALID_PARAMETER_PATH`), `app/streams.py` (XADD `jobs.backtest.trials`; consume `optimizations.results` via `cg-optuna`), `app/jobs_repo.py` (INSERT queued TRIAL jobs row before XADD; OPTIMIZATION parent row), `app/trials_repo.py` (`optimization_trials`), `app/strategy_client.py` (validate optimize block via strategy-signal).
- Create: `deploy/flyway/backtest/V004__optimization_trials.sql`.
- Create: `.github/workflows/ci-optimizer.yml`; `contracts/optimizer-service.openapi.json` (FastAPI-native) + ci-contracts diff-gate.
- Modify: root compose (optimizer-service:8084, mem_limit 256m); gateway route already exists.
- Test: `tests/test_expansion.py` (grid/random expansion math), `tests/test_dispatch.py` (ask/tell loop with respx-stubbed backtest + fakeredis streams; cancellation), `tests/test_path_grammar.py`, `tests/test_consumer_contract.py` (respx stubs derived from `contracts/backtest-service.openapi.json`).

**`backtest/V004__optimization_trials.sql`:** `optimization_trials(id BIGINT PK GENERATED, sweep_job_id UUID FK→jobs, trial_number INT, params JSONB, objective_values JSONB, state TEXT CHECK COMPLETE|PRUNED|FAILED, backtest_run_id UUID NULL, started_at, completed_at)`; unique `(sweep_job_id, trial_number)`; index `(sweep_job_id, state)`; grants to `ay_backtest`.

**Implementation steps:**
- [ ] Scaffold FastAPI app + health + instrumentator + structlog; requirements with hashes; ruff; Dockerfile; compose entry.
- [ ] `POST /api/v1/optimizations/run`: validate optimize block + path grammar → INSERT parent OPTIMIZATION job → 202 `{jobId}`.
- [ ] Ask/tell loop: in-memory study (grid/random); per trial materialize patched config → INSERT queued TRIAL jobs row (kind=TRIAL, parent=sweep) → XADD `jobs.backtest.trials`; consume `optimizations.results` via `cg-optuna` → `study.tell`; progress + bestSoFar on `jobs.progress`.
- [ ] `optimization_trials` persistence (resumable via `study.add_trial` replay; seeded-sampler reproducibility).
- [ ] `GET /jobs/{id}`, `GET /{sweepId}/trials` (paged/sorted), `DELETE /jobs/{id}` (cancel).
- [ ] pytest ≥75%; respx consumer-contract; ci-optimizer.yml; commit FastAPI-native contract + diff-gate.

**Build & verify:** `cd services/optimizer-service && pip install -r requirements.txt && pytest`; mock stack — POST a 10-trial grid sweep → all trials COMPLETE, progress 100.
**Acceptance:** fixed sampler seed ⇒ identical trial sequence; sweep survives optimizer restart (re-queue + add_trial replay); sweep's TRIAL `jobs` count == dispatched stream entries. FAIL: optimizer writing strategy versions to DB.
**Commit:** `feat(optimizer): fastapi + optuna ask/tell core with grid/random sweeps over redis streams`
**If too big:** (a) skeleton + run endpoint + grid expansion; (b) ask/tell loop + persistence + cancel.

---

### Task 9 — Phase 34: optimizer TPE/NSGA-II + pruning + leaderboard + promote

**Design refs:** §D.9 (leaderboard/promote), §D.7 (CD-12 pruning), §D.13 (S3 gate), Phase 34 spec.

**Files (optimizer-service):** extend `optuna_runner.py` (TPESampler constant-liar; NSGAIISampler multi-objective Pareto front; fold-fed `MedianPruner` from per-fold OOS via `optimizations.results` intermediate messages; pruned→cancel via job-cancel path; pruning defaults from S3 ADR); `app/leaderboard.py` (`GET /{sweepId}/best?top=N` metric matrix + plateau-adjusted default sort — median of already-computed neighbor trials within ±1 step/ε-ball; raw sort one param away; dataHash flags; pruned flagged partial-coverage + excluded from min/mean_minus_std); `app/folds.py` (`GET /{sweepId}/trials/{trialId}/folds` via `backtest_run_id`); `app/promote.py` (`POST /{sweepId}/promote {trialId}` → apply params onto source version → POST new draft via strategy-signal REST, `created_by=optimizer:{jobId}`; 409 invalid/failed); early stopping.
- Modify: `PHASE_GATES.md` — copy the §15.2 Phase-3 exit checklist + tick the S3 gate.
- Test: `tests/test_tpe_nsga2.py` (expansion), `tests/test_pruning.py` (decision table; OOS-fold-median pruning, never train/OOS divergence), `tests/test_plateau.py` (plateau-sort math), `tests/test_promote.py` (promote payload + 409). Compose check: promote creates a draft whose diff shows exactly the trial's parameter deltas; audit row records optimizer provenance; NSGA-II returns a Pareto front; 200-trial sweep ranks configs.

**Build & verify:** `pytest`; mock stack — 30-trial TPE sweep + promote → new draft exists; 200-trial sweep ranks configs.
**Acceptance:** NSGA-II Pareto front; pruned trials never in `min`; promoted winner is a draft; S3 gate satisfied; Stage-D exit checklist mirrored in PHASE_GATES.md. FAIL: pruning fed by train/OOS divergence.
**Commit:** `feat(optimizer): tpe/nsga2 samplers, fold-fed median pruning, plateau leaderboard and draft promotion`
**If too big:** (a) samplers + pruning; (b) leaderboard + folds route + promote.

---

### Manual testing guide + final verification

- Write `docs/manual-testing/STAGE_D_MANUAL_TESTING.md` (find prior stages' guide style first) covering every deliverable on the mock stack.
- Full-stack verification against the Stage-D exit checklist (PHASE_GATES.md Phase-3 row). `./mvnw verify` across modules + optimizer `pytest`.
- Delete `.codebase-map-stage-d.tmp.md`. Open one Stage-D PR to `main` (do not merge).

---

## Self-review notes (spec coverage)

- Phases 28/29/30/30A/31/32/32A/33/34 each map to a task above. ✅
- S3 spike (entry gate) sequenced before Phase 33; outputs feed Phase 34 pruning. ✅
- Schema already frozen (no strategy-schema change) — confirmed `optimize`, `walk_forward`, `costs/fees`, `objective.fold_aggregation`, `risk.session.*` present. ✅
- Admin role/schema/grant + flyway runner + gateway routes already provisioned — confirmed. ✅
- Open wiring items flagged for Phase 28: (1) datasource role enforcement for the D10 read-only IT; (2) exact progress topic↔channel mapping in edge-gateway; (3) where the Stage-B system-status rollup lives (for `jobs:summary`). Resolve by reading actual code at implementation time.
- Cross-stage seams left NULL-able/deferred per design: `universe_checksum` (Stage F resolve-at-submission), paper fill-audit columns (Stage F), Stage-E UI consumers.
