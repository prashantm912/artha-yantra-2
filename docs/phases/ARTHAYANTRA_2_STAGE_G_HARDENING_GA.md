# ArthaYantra 2.0 — Stage G: Hardening + GA

**Stage letter / name:** G — Hardening + GA
**Plan macro-phase:** Phase 6 ("Observability, polish, hardening")
**Phases covered:** 45, 46, 47, 48
**Prerequisite stages:** A (foundations: compose, `ay` CLI, CI, gateway), B (market-data spine: Kite canary, snapshot job, schedulers), C (strategy engine + signals MVP), D (backtest + optimizer), E (frontend UX + notifier), F (options + paper + universe). Stage G is **strictly last** — RAM tuning and k6 numbers are meaningless before the system is feature-complete (plan §15.3: critical path Phase 0→1→2→3→6).
**Common reference:** [ARTHAYANTRA_2_COMMON_REFERENCE.md](ARTHAYANTRA_2_COMMON_REFERENCE.md) — cite for ADR decision tables (D14 observability, D16 CI/CD, D7 service/RAM table), the canonical stack-version table, per-container RAM budget, error-code taxonomy, the phase index, the §16 timeline, the deliberately-deferred appendix (COMMON §21), the CORS/TLS posture (COMMON §12.6), and the Q3 Tailscale / S2 Tuesday-expiry resolutions (COMMON §18.1/§18.2).

**Stage goal.** Take the feature-complete stack to General Availability: ship the opt-in `obs` observability profile (Prometheus/Grafana/Loki + cAdvisor) with five provisioned dashboards, the full alert catalog (ntfy primary), and the two S3B Redis bus-health metrics; encode the performance contract as a k6 load suite with nightly perf gates; bring every container to the §11.9/§9.5 hardening bar (non-root, read-only, cap-drop, digest-pinned, AOT + AppCDS images at the D7 RAM caps) with supply-chain scanning in CI; and close out with the operational runbook, an executed-and-scheduled restore drill, finalized docs, the `release.yml` pipeline, and the GA gate walk that tags `v2.0.0`. The whole stack must stay **≤ ~6 GB worst-case RSS with `obs` on**, comfortably under 8 GB (ADR RAM budget — see COMMON §6 RAM table).

---

# Part 1 — Design reference (inlined source material)

This part inlines the plan/review content the four Stage-G phases need at implementation time. Each section keeps its source breadcrumb. App-wide material (ADR decision text, stack versions, RAM table, error taxonomy, repo layout, timeline) lives in [COMMON](ARTHAYANTRA_2_COMMON_REFERENCE.md) and is cited rather than duplicated.

## G-D1. Monitoring & observability — the two-tier model [plan §12 — inlined in full]

V1 shipped with a single unauthenticated Actuator endpoint and a `logs/java-backend.log` file — no metrics history, no alerting, no way to answer "why did the ticker stop at 11:40?". ArthaYantra 2.0 adopts ADR D14 (see COMMON §6 D14): a thin **always-on instrumentation layer** baked into every service, plus an **opt-in `obs` compose profile** carrying the heavyweight collectors. The owner pays the observability RAM tax only while actually investigating something or during market hours when alert coverage matters.

### G-D1.1 Two-tier model and RAM budget [plan §12.1]

| Tier | Components | When running | RAM cost |
|---|---|---|---|
| Always-on | Spring Boot Actuator + Micrometer (Java services), `prometheus-fastapi-instrumentator` (optimizer-service), structured JSON logs to stdout, compose healthchecks, first-party ntfy push for Kite-critical alerts | Every `docker compose up` | ~0 incremental (in-process) |
| `--profile obs` | Prometheus 3.x (256 MB), Grafana 11 (160 MB), Loki 3 (96 MB), Promtail (48 MB), cAdvisor v0.49 (48 MB) | `docker compose --profile obs up -d` | **~550–600 MB** total (`mem_limit` on each, per D16) |

cAdvisor is the one addition beyond the ADR's named trio: it supplies the per-container RSS/CPU panels D14 itself calls for. Whether `obs` is up or not, every service always exposes `/actuator/prometheus` (Java) or `/metrics` (Python) on the private compose network, so Prometheus simply starts scraping history the moment the profile comes up — **no service restarts, no config changes**.

Topology (always-on core vs `obs` profile): Prometheus scrapes every Java service `/actuator/prometheus` + the optimizer `/metrics` + cAdvisor on a 15 s interval; Promtail tails the Docker `json-file` logs and ships to Loki; Grafana reads Prometheus + Loki and pushes alerts to the ntfy/Telegram webhook contact point. Separately, market-data-service does a **first-party push** directly to ntfy that needs no `obs` profile (the two alerts that cannot wait — see G-D1.7).

### G-D1.2 Metric catalog (Micrometer → Prometheus) — naming authority [plan §12.2]

JVM/runtime basics come free from Micrometer (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `hikaricp_connections_active`, `http_server_requests_seconds`, `executor_queued_tasks`) and from `prometheus-fastapi-instrumentator` on the Python side. Domain metrics use an `ay_` prefix; histograms use Micrometer percentile-histogram buckets so Grafana can compute p50/p99. **This table is the metric-naming authority** (the always-on §5.12 observability hooks defer to it).

| Metric | Type | Service | Meaning / target |
|---|---|---|---|
| `ay_ticks_ingested_total{exchange}` | counter | market-data-service | Kite WS ticks consumed; rate panel detects feed stalls |
| `ay_tick_publish_latency_seconds` | histogram | market-data-service | Kite callback → Redis pub/sub publish |
| `ay_ws_fanout_latency_seconds{topic}` | histogram | edge-gateway | Redis message → STOMP frame written; combined with the above approximates tick-to-browser (full path validated by k6 ≤ 150 ms p99, G-D2) |
| `ay_kite_ws_reconnects_total` / `ay_kite_circuit_state` | counter / gauge | market-data-service | Reconnect churn; circuit 0=closed 1=open |
| `ay_kite_rate_limiter_saturation` | gauge | market-data-service | In-flight permits / 3 req-s budget; sustained ≥ 0.9 means historical fetches are queuing |
| `ay_kite_session_valid` | gauge | market-data-service | 1 = access token valid (5-min check, D13) |
| `ay_candle_builder_lag_seconds{interval}` | gauge | market-data-service | now − last closed 1m bucket; > 120 s during market hours = pipeline stall |
| `ay_options_snapshot_duration_seconds` / `ay_options_snapshot_rows_total` | histogram / counter | market-data-service | 5-min chain snapshot job health (D12) |
| `ay_signal_eval_duration_seconds{strategy}` | histogram | strategy-signal-service | Composite-score evaluation per tick batch |
| `ay_signals_emitted_total{strategy,direction}` | counter | strategy-signal-service | Output rate per published strategy version |
| `ay_backtest_queue_depth` / `ay_backtest_workers_busy` | gauge | backtest-service | `jobs` table queued count; busy vs pool size (cores − 2) |
| `ay_backtest_job_duration_seconds{type}` | histogram | backtest-service | Single run vs sweep trial |
| `ay_redis_stream_pending{stream}` | gauge | backtest-service / optimizer-service | Consumer-group backlog (at-least-once lag, D9) |
| `ay_optuna_trials_total{study,state}` / `ay_optuna_trial_throughput` | counter / gauge | optimizer-service | Trials completed/pruned/failed; trials per minute |
| `cache_gets_total{cache,result}` | counter | all Java services | Caffeine hit ratio per named cache (instruments, kite-status, expiries — D11) |
| `ay_hypertable_bytes{table}` | gauge | market-data-service | `hypertable_size()` sampled every 15 min — feeds the disk alert without a postgres_exporter container |
| `ay_redis_pubsub_resubscribes_total{service}` | counter | edge-gateway, strategy-signal-service | **(S3B)** `MessageListenerContainer` reconnect/resubscribe events on the pub/sub consumers; a rising rate means the bus is flapping while publisher-side ingest metrics still look healthy — the one tick-path failure the first-party no-tick alert cannot see |
| `ay_redis_memory_used_ratio` | gauge | market-data-service | **(S3B)** `INFO memory` `used_memory / maxmemory`, sampled every 60 s over the existing Lettuce connection (same no-exporter pattern as `ay_hypertable_bytes`); eviction is instance-wide, so sustained pressure under the 64 MB `allkeys-lru` cap can silently evict session or Streams keys |

> The S3B contract is pinned in detail below at **G-D1.9**; CD-16 (the Redis eviction policy this gauge guards) is at **G-D1.10**.

### G-D1.3 Grafana dashboards (provisioned as code) [plan §12.3]

Five provisioned-as-code dashboards (JSON in `infrastructure/grafana/dashboards/`, mounted read-only):

1. **Market Data Health** — Kite WS state timeline, reconnect count, tick ingest rate by exchange, last-tick age, rate-limiter saturation gauge, candle builder lag, options snapshot duration/row count, Kite session validity, daily API budget burn-down.
2. **Trading & Signals** — signal evaluation latency p50/p99 per strategy, signals emitted by strategy/direction, per-indicator contribution error counts, paper-trading open positions and day P&L, active per-symbol subscriptions.
3. **Jobs & Backtests** — queue depth vs worker occupancy, job duration histogram, failure rate, Redis Streams pending count, Optuna trial throughput and best-value progression per study, sweep completion ETA.
4. **System & Containers** — per-container RSS vs `mem_limit` (cAdvisor), CPU, JVM heap and GC pause per service, Postgres connections, Redis memory, hypertable sizes, host disk free.
5. **Gateway & Sessions** — HTTP latency by route prefix, active WS/STOMP sessions, fan-out latency, auth failures, Spring Session count.

### G-D1.4 Centralized logging: Loki 3 + Promtail [plan §12.4]

All services log **structured JSON to stdout** — Java via Spring Boot 3.5's native `logging.structured.format.console=ecs` (no extra encoder dependency), Python via `structlog` 24.x. Docker's `json-file` driver (capped `max-size: 10m`, `max-file: 3`) is the buffer; Promtail tails the container log directories and ships to Loki. **Loki labels stay low-cardinality (`service`, `level`)**; everything else — `correlationId`, `jobId`, `strategyId`, `symbol` — is a JSON field queried with LogQL filters. (High-cardinality Loki labels are an explicit Phase-45 FAIL condition.)

**Correlation IDs.** The edge-gateway generates an `X-Request-Id` UUID per inbound request and forwards it; every Java service binds it to the MDC. For async flows the ID travels with the work: it is persisted on the `jobs` row and copied into every Redis Streams trial message, so backtest-service and optimizer-service restore it into their logging context. One Grafana Explore query — `{service=~".+"} | json | correlationId="..."` — reconstructs an entire optimization sweep across four services. The per-tick hot path is deliberately *not* correlated (per-message UUIDs would dominate the 150 ms budget); tick issues are diagnosed via metrics and the candle-lag gauge instead.

### G-D1.5 Tracing: explicitly deferred + OTel flip-on [plan §12.5]

**The call: no OpenTelemetry by default.** With one user, one machine, and five always-on services, the latency-attribution questions tracing answers are already answered by the two-segment latency histograms (G-D1.2) plus correlation-ID log stitching — at zero RAM, versus an OTel collector + Tempo/Jaeger backend costing 300–500 MB and a new query UI. **The flip-on path is documented, not designed away:** Spring Boot's Micrometer Tracing bridge and FastAPI's OTel instrumentation can be enabled by dependency + env var, exporting OTLP to a future `tracing` compose profile if a genuine cross-service mystery ever demands it. **Revisit trigger:** a latency regression that the histograms cannot localize within one debugging session (logged as an open item).

### G-D1.6 Health checks and the aggregated status endpoint [plan §12.6]

Every Java service enables Spring Boot health groups: `/actuator/health/liveness` (process up) and `/actuator/health/readiness` (DB + Redis + critical caches warm); optimizer-service exposes `/health`. Compose healthchecks (D16) drive `depends_on: condition: service_healthy` ordering after the Flyway init job (D17):

| Container | Healthcheck command | Interval / retries |
|---|---|---|
| Java services | `curl -fs localhost:808x/actuator/health/readiness` | 10 s / 5 |
| optimizer-service | `curl -fs localhost:8084/health` | 10 s / 5 |
| timescaledb | `pg_isready -U artha` | 5 s / 10 |
| redis | `redis-cli ping` | 5 s / 5 |
| frontend-ui | `wget -qO- localhost/healthz` | 30 s / 3 |

The UI status bar is fed by **one aggregated endpoint** on the gateway — `GET /api/v1/system/status` — which treats Kite state as a first-class health signal. market-data-service continuously writes ticker/session state into Redis shared keys (D11), so the gateway aggregates without fan-out REST calls; results are Caffeine-cached 5 s, and the UI polls every 10 s plus listens on a `/topic/system` STOMP topic for push deltas.

| Field | Example | Source |
|---|---|---|
| `overall` | `UP \| DEGRADED \| DOWN` | worst-of rollup |
| `services[]` | `{name, status, latencyMs}` | readiness probes |
| `kite.session` | `VALID \| EXPIRED \| ABSENT` | Redis key from 5-min token check |
| `kite.ticker` | `CONNECTED \| DISCONNECTED \| CIRCUIT_OPEN` | Redis key |
| `kite.lastTickAgeMs` / `kite.rateBudget` | `850` / `0.42` | Redis last-tick map |
| `market.phase` | `OPEN \| CLOSED \| PRE_OPEN` | shared MarketCalendar (D12) |
| `jobs` | `{queued: 3, running: 6}` | backtest-service summary |

> This endpoint is built in Stage B (Phase 17, aggregated system status) and Stage A (health groups). Stage G consumes it for the Grafana **Gateway & Sessions** dashboard and the alert sources; the readiness/healthcheck table above is the input to the Phase-45 dashboards and the Phase-47 hardened-image boot verification.

### G-D1.7 Alerting for a personal app — FULL catalog [plan §12.7]

Alert delivery is **push to a phone**, not a pager rotation: an **ntfy** topic (random-suffixed topic on ntfy.sh, or self-hosted ntfy container at ~16 MB) is the **primary** channel, with a Telegram bot as the documented alternative (Grafana 11 has a native Telegram contact point; ntfy uses the webhook contact point).

Because the `obs` profile may be down, the two alerts that genuinely cannot wait are **first-party**: market-data-service itself POSTs to ntfy (a 5-line HTTP call, no Grafana dependency). Everything else is a Grafana unified-alerting rule.

| Alert | Condition | Source | Severity |
|---|---|---|---|
| Kite ticker disconnected in market hours | `ay_kite_circuit_state=1` or no tick > 60 s while MarketCalendar says OPEN | first-party | critical |
| Kite token expired / login missing | `ay_kite_session_valid=0` after 08:45 IST on a trading day | first-party | critical |
| Kite contract drift — missing/type-changed response fields vs recorded fixture manifests, or unparsed WS frames in market hours | daily contract canary (G-D4.1) / `ay_kite_unparsed_frames_total` > 0 while MarketCalendar says OPEN | first-party | critical (missing/changed) / warning (new fields) |
| Backtest/optimization job failed | `jobs` row → failed (also surfaced as UI toast) | Grafana (+ UI) | warning |
| Optuna study stalled | trial throughput = 0 for 10 min with queued trials | Grafana | warning |
| Disk filling from hypertables | host disk > 80 % or `ay_hypertable_bytes` weekly growth projects full < 30 d | Grafana | warning |
| Redis memory pressure | `ay_redis_memory_used_ratio` ≥ 0.85 for 10 min | Grafana | warning |
| Container unhealthy / restart loop | cAdvisor restart count > 3 in 10 min | Grafana | warning |
| Fan-out latency degraded | `ay_ws_fanout_latency_seconds` p99 > 100 ms for 5 min | Grafana | info |
| Nightly pg_dump failed | backup sidecar exits non-zero → POSTs ntfy directly | first-party | critical |
| Corporate action detected / candle history rebuilt | corporate-action integrity job (Stage B Phase 16A) flags + rebuilds a symbol | first-party | warning [FP-1, owner selection 2026-06-12] |

**Deliberate absence of a stream-lag alert (recorded).** There is deliberately **no per-entry stream-lag alert**: `ay_redis_stream_pending` plus the "Optuna study stalled" rule already separate a wedged consumer group from a busy sweep — multi-second trial backlog behind the cores−2 pool is the **designed steady state** of every optimization run, not an incident. This absence is documented in the Grafana folder README (Phase-45 deliverable).

**Published-signal pushes are a separate concern** from the ops alerts above: the **notifier** module in strategy-signal-service (built in Stage E, Phase 41) pushes opted-in strategies' signals — ntfy primary on its **own** random-suffixed topic (distinct from the ops topic, so signals can be muted without muting ops alerts), Telegram Bot API as the authenticated alternative (one plain HTTPS POST to `sendMessage`; no third-party bot library). The first-party ops alerts above and the backup sidecar keep their own 5-line plain HTTP POSTs — the notifier is its own module, **not** a shared library. (Q6 resolution — see COMMON §18.1.) Stage G owns only the ops alert catalog above; it does not re-implement the notifier.

### G-D1.8 Deliberately not included [plan §12.8]

- **Paid APM (Datadog, New Relic, Dynatrace):** per-host pricing and cloud data egress for a localhost app with one user; Micrometer + Grafana covers the need at zero cost and zero data leaving the machine.
- **Tracing backends (Tempo, Jaeger, Zipkin) and an always-on OTel collector:** 300–500 MB of RAM against a 16–32 GB shared desktop for questions the latency histograms already answer (G-D1.5).
- **ELK/Elasticsearch:** Loki's label-index model needs ~100 MB where Elasticsearch wants 1–2 GB of heap before indexing a single log line.
- **Dedicated Alertmanager and postgres_exporter/node-exporter containers:** Grafana 11's unified alerting replaces Alertmanager; hypertable and JVM gauges come from in-process Micrometer, and cAdvisor covers container/host basics — every avoided container is RAM returned to backtest sweeps.

Actuator and `/metrics` endpoints are reachable only on the private compose network; the **sole** observability surface routed through the gateway is the aggregated status endpoint (G-D1.6). Operational risks that monitoring mitigates — silent feed death, disk exhaustion, missed daily login — are catalogued in the risk register (G-D5).

### G-D1.9 S3B — Redis bus-health monitors (final design) [review S3B — inlined]

The concern (silent Redis degradation under a 64 MB cap) is valid; of four originally-proposed metrics, two duplicated existing coverage and one was unobtainable as specified. **Final design — exactly two metrics and one alert, no more:**

- **`ay_redis_pubsub_resubscribes_total{service}`** — a **consumer-side** `MessageListenerContainer` resubscribe counter, incremented whenever the pub/sub listener reconnects/resubscribes. Exposed on the two pub/sub *consumers*: **edge-gateway** (tick fan-out listener) and **strategy-signal-service** (the strategy-signal listener). This is the one tick-path failure the publisher-side first-party "no tick" alert **cannot** see: the bus can flap while ingest metrics still look healthy.
- **`ay_redis_memory_used_ratio`** — an **in-process gauge** read from Redis `INFO memory` (`used_memory / maxmemory`) over the existing Lettuce connection, sampled every **60 s**, exposed on **market-data-service** (same no-exporter pattern as `ay_hypertable_bytes`). Eviction under the 64 MB cap is **instance-wide**, so sustained pressure can silently evict session or Streams keys.
- **One Grafana WARNING** rule: `ay_redis_memory_used_ratio ≥ 0.85 for 10 min`, delivered via **ntfy** at personal-app severity.

**Rejected:** "page the owner at > 1000 ms stream lag." Multi-second trial backlog is the **designed steady state** of every sweep on a cores−2 pool, and the genuine wedge case is already caught by the **Optuna-stalled** rule (G-D1.7). The deliberate absence of a stream-lag alert is recorded in the alert catalog above.

*Rejected as specified (do not re-introduce):* `ay_redis_subscriber_loss{topic}` is not a Redis-exposed metric (disconnects appear only in server logs / `CLIENT LIST`); a `_ms`-suffixed lag metric would violate the catalog's base-unit convention; the ">20 connections abnormal" threshold is unsubstantiated for this topology.

### G-D1.10 CD-16 — Redis eviction policy (the control S3B compensates for)

Redis runs under a **64 MB `mem_limit`** with **`maxmemory-policy volatile-lru`**, and **TTLs are set only on cache keys** (last-tick map, kite-status, expiries — D11). Session keys and Redis Streams entries carry **no TTL**, so `volatile-lru` will never evict them; only genuinely expendable cache keys are eligible for eviction under pressure. The **S3B `ay_redis_memory_used_ratio` ≥ 0.85 warning is the compensating control** that surfaces sustained pressure before it can matter. (This refines the plan's looser `allkeys-lru` mention — under `allkeys-lru` eviction is instance-wide; `volatile-lru` + cache-only TTLs is the governing CD-16 choice, and the S3B gauge guards it either way.)

## G-D2. Load testing — Grafana k6 [plan §10.8 — inlined in full]

k6 scenarios run nightly against the full mock compose stack (with profile `obs` enabled so Grafana 11 captures the run), driven by the **mock feed at accelerated rates** (the kite-sim container is deferred beyond this plan — see COMMON §21 — so the load is shaped via the Phase-7 `MOCK_TICKS_PER_SEC` knob raised to ~500–1,500 ticks/s aggregate across 300 instruments, reproducing §10.8's shape without the deferred container). Thresholds bind to the §5.10 performance targets (G-D2.1).

| Scenario | Shape | Pass thresholds |
|---|---|---|
| `ws-fanout` | 300 subscribed instruments at realistic burst rates (~500–1,500 ticks/s aggregate via `trend` scenario), 3 concurrent browser sessions over STOMP/native WS through the gateway | **Tick-to-browser ≤ 150 ms p99** (ADR D15); zero dropped frames; gateway RSS within 384 MB `mem_limit` |
| `chain-refresh` | Options chain (40+ strikes, full Greeks) fetched every 5 s for 30 min while WS fan-out runs | Chain REST p95 within the §5.10 target; snapshot writes never delay tick path |
| `backtest-saturation` | Submit 4× (cores−2) one-year-1m backtest jobs plus a 200-trial sweep | Pool never exceeds cores−2; queue drains with **zero lost jobs** (Postgres↔Streams reconciliation); interactive REST p95 unchanged during sweep; backtest-service stays under 896 MB |

### G-D2.1 Performance targets — the contract k6 thresholds bind to [plan §5.10]

| Metric | Target | Notes |
|---|---|---|
| Tick fan-out, Kite → browser | **≤ 150 ms p99** (≤ 60 ms p50) | k6-verified (G-D2); Redis hop ≤ 5 ms |
| Tick fan-out, Kite → signal engine | ≤ 20 ms p99 | In-host Redis pub/sub |
| Options chain full refresh (~200 strikes ×2) | ≤ 3 s | Rate-limit bound: ~2 quote calls at the default 250-instrument batch, 1 call/s |
| Historical fetch, cold (1 y of 1m bars) | ≤ 45 s | ~6 Kite calls at 3 req/s + batched insert |
| Historical read, warm (1 y of 1m bars) | ≤ 300 ms p95 | Hypertable range scan |
| Backtest throughput | ≥ 50k bars/s per worker; 1-y 1m single run ≤ 10 s | ta4j replay, no per-bar IO |
| Optimization sweep, 200 trials × 1 y 1m | ≤ 30 min wall clock | cores−2 workers + Optuna pruning |
| REST reads p95 / job submissions p95 | ≤ 100 ms / ≤ 50 ms | Gateway overhead ≤ 5 ms |
| Instrument sync (~100k rows) | ≤ 60 s | JDBC batch, chunked transactions |
| Service idle RSS | Within D7 `mem_limit` table (core total ~3.9 GB) | AOT + AppCDS + SerialGC on small services |

### G-D2.2 The binding-numbers-on-owner-machine rule [plan §15.6 exit ritual / §15.2 Phase-6 note]

The performance gates (50 ms tick→Redis, **150 ms tick→browser p99**) are measured on the **target Windows/Docker Desktop machine, never on GitHub-hosted runners**. On GitHub-hosted runners the k6 thresholds are **informational only**; treating them as binding on GitHub runners is an explicit Phase-46 FAIL condition. The nightly `ci-load.yml` workflow exists to catch gross regressions and keep the scenarios runnable, but the authoritative pass/fail is the owner's local run. **No bespoke gate automation** beyond CI — the machine-checkable subset is already CI-enforced; performance numbers are a local Friday-ritual check.

### G-D2.3 E2E suite the hardened images must keep green [plan §10.9 — relevant context for Phase 47]

Phase 47's read-only roots are "where this usually breaks," so the full Playwright journey suite (built in Stage C, Phase 27) must stay green on hardened images. The journeys (mirroring the §4 routes, all credential-free on the mock stack): **Mock login** (Argon2id test hash, HttpOnly/SameSite=Strict cookie, deep-link redirect, logout); **Strategy lifecycle** (create → Monaco YAML edit with schema validation error → save draft → quick backtest 202+jobId with live `jobs.progress` WS → results → tune sweep with ECharts heatmap → promote → publish → diff → rollback); **Live signals** (mock feed fires a known signal, asserted with strategy name + per-indicator score breakdown); **Options chain** (non-zero IV/Greeks, PCR refresh, strike/expiry filters, off-hours degradation); **Charts + resilience** (lightweight-charts page renders candles from network-stubbed candle endpoints + WS ticks; trade/signal marks render; `ws_disconnect` fault → UI reconnect-and-recover without reload — unconditional, like every journey) [A13, 2026-06-12]. The journey suite now also covers the owner-selection surfaces — futures workbench (Phase 42A), paper account (Phase 43A), trade journal (Phase 44A), and chart marks (Phase 40A) — as specified in their owning stages, not re-specced here [FP-9, FP-10, FP-41, FP-42, FP-43, FP-66, FP-67, owner selection 2026-06-12].

## G-D3. Image strategy + container hardening + supply-chain [plan §9.5, §11.9, §11.10 — inlined in full]

### G-D3.1 Image strategy — multi-stage, AOT + AppCDS [plan §9.5]

All images are **multi-stage**. Java services (per D16): **stage 1** builds with `maven:3.9-eclipse-temurin-21` and runs **Spring AOT processing**; **stage 2** extracts Spring Boot layers and performs an **AppCDS training run** (boot under the `mock` profile, dump the CDS archive); **stage 3** is the runtime layer on a JRE base with a non-root user, `wget`-based healthcheck, pinned `-Xmx` matching the compose `mem_limit`, and `-XX:+UseSerialGC` on the small services (edge-gateway, strategy-signal-service); **backtest-service keeps G1** for sweep bursts. Result: **1.5–3 s cold starts** and stable RSS.

| Image | Base | Size target | Notes |
|---|---|---|---|
| edge-gateway | `eclipse-temurin:21-jre-alpine` | ≤ 250 MB | AOT + AppCDS |
| market-data / strategy-signal / backtest | `eclipse-temurin:21-jre-alpine` | ≤ 300 MB | Layered jar caching: deps layer rebuilt only on `pom.xml` change |
| optimizer-service | `python:3.12-slim` | ≤ 500 MB | `pip install --no-cache-dir`; Optuna 4.x pulls numpy/sqlalchemy |
| frontend-ui | `nginx:1.27-alpine` | ≤ 60 MB | Angular `dist/` only [A13, 2026-06-12] — vendored TradingView bundle removed, image shrinks accordingly |
| flyway-init | `flyway/flyway:11-alpine` | ≤ 120 MB | One-shot (D17) |

**GC + `-Xmx` mapping (binding for Phase 47):** SerialGC on **edge-gateway** and **strategy-signal-service**; **G1** on **backtest-service** (sweep bursts). Each `-Xmx` matches the compose `mem_limit` from the RAM budget (COMMON §6): edge-gateway 384 MB, market-data 640 MB, strategy-signal 640 MB, backtest 896 MB burst (~400 MB idle), optimizer 256 MB, nginx 32 MB.

### G-D3.2 Container hardening [plan §11.9]

Every image in the compose file: runs as a **non-root** user (distroless-style Java base or `USER` directive; nginx 1.27-alpine unprivileged variant); **`read_only: true`** root filesystem with explicit **`tmpfs`** for `/tmp` where the JVM needs scratch; **`cap_drop: [ALL]`**, **`security_opt: [no-new-privileges:true]`**; **pinned tags *and* digests** (`postgres@sha256:…`) per D16; `mem_limit` per the ADR RAM budget; internal-only compose network with **exactly two published ports, both loopback-bound** (`127.0.0.1:8080` gateway, `127.0.0.1:5432` dev tooling).

### G-D3.3 Dependency & supply-chain scanning in CI [plan §11.10]

GitHub Actions (D16) runs on every PR and weekly on schedule: **Dependabot** for Maven, npm, pip, Dockerfiles, and Actions versions; **OWASP Dependency-Check 12.x** failing the build on **CVSS ≥ 7** (with a reviewed suppression file); **`npm audit --audit-level=high`** for the Angular workspace and **`pip-audit`** for optimizer-service; **Trivy** scanning the built images **before GHCR push**. Lockfiles (`package-lock.json`, `requirements.txt` with hashes, Maven version pinning) are mandatory so builds are reproducible and a tampered upstream cannot drift in silently. Findings feed the risk register (G-D5). *(Phase-47 acceptance: a planted CVSS-9 dependency must fail CI.)*

### G-D3.4 Always-on observability hooks every service already exposes [plan §5.12]

Always-on (D14): every Java service exposes Actuator `health` (compose healthcheck + `depends_on: service_healthy`), `info`, and Micrometer `prometheus` endpoints on the internal port; optimizer-service uses `prometheus-fastapi-instrumentator` plus a `/health` route; all services emit structured JSON logs to stdout with a gateway-assigned correlation id propagated via header and MDC. Named metric families per service follow the **canonical catalog (G-D1.2 — naming authority)**: edge-gateway — `ay_ws_sessions`, `ay_ws_fanout_latency_seconds`, route timers; market-data — `ay_ticks_ingested_total`, `ay_kite_rate_limiter_saturation`, `ay_kite_circuit_state`, candle-cache hit ratio, `ay_options_snapshot_duration_seconds`; strategy-signal — `ay_signal_eval_duration_seconds`, `ay_signals_emitted_total`, indicator-cache stats; backtest — `ay_backtest_queue_depth`, `ay_backtest_workers_busy`, bars-replayed; optimizer — `ay_optuna_trials_total`, sweep-best-value. **Nothing in the hot path depends on the `obs` profile being active** — this is why Phase 45 can add collectors with no service restart, and why an `obs`-up failure never affects the core stack (Phase-45 acceptance).

## G-D4. Database & backup operations [plan §9.9, §6.9 — inlined in full]

### G-D4.1 Database operations [plan §9.9]

- **Migrations:** Flyway 11 runs as the one-shot `flyway-init` compose job applying every `V###__*.sql` across `marketdata`/`strategy`/`backtest` schemas before any app service starts (`service_completed_successfully`); `ddl-auto=none` everywhere (D17). Each start re-runs `migrate` (no-op when current) and **validates checksums** — schema drift becomes a hard failure. This is the `flyway validate` step the restore drill (Phase 48) re-uses.
- **In CI:** `ci-migrations.yml` proves a fresh database builds cleanly; Testcontainers integration tests apply the *same* migrations, so test and runtime schemas can never diverge.
- **Seed data:** repeatable `R__seed_*.sql` migrations load two sample draft strategies (EMA-crossover demo conforming to `strategy-schema/v1`) and a small mock-mode instrument subset. All idempotent (`ON CONFLICT DO NOTHING`). *(Plan §9.9 also listed the NSE holiday calendar as a seed; superseded by CD-2 — the holiday calendar is a versioned resource file inside `libs/market-calendar`, refreshed yearly by PR, never a DB seed — see Stage A A.5/A.8.)*

> *Daily contract canary (referenced by the G-D1.7 "Kite contract drift" alert and the Phase-48 runbook):* market-data-service's kite module runs a **post-login** contract canary once per trading day, the first time the session transitions to LIVE after the morning OAuth ritual (a fixed pre-dawn cron is impossible — the day's token does not exist before login). It issues 3–4 direct `RestClient` probes **bypassing the SDK's Gson→POJO mapping** and diffs recursive field sets/types against manifests derived from the recorded WireMock fixtures (one source of truth). Drift → first-party ntfy (critical for missing/changed fields, warning for new). The binary WS guard counts frames matching no known packet size from **{8, 28, 32, 44, 184} B** (including the 28/32 B *index* packet layouts), evaluated after the count/length header split. *(Full design built in Stage B, Phase 16; restated here because Phase 45 wires its alert and Phase 48 documents it in the runbook — S2B final design.)*

### G-D4.2 Backup & disaster recovery — including the restore drill [plan §6.9, §9.10]

Data classes drive the policy — **most of this database is a cache**:

| Data class | Tables | Replaceability | RPO | RTO |
|---|---|---|---|---|
| Kite-refetchable cache | `instruments`, `candles` | Full re-fetch (rate-limited 3 req/s) | N/A (loss acceptable) | Hours of background backfill; app usable immediately |
| Self-archived market data | `options_chain_snapshots` | **Irreplaceable** (no Kite history for IV/OI) | ≤ 24 h (one trading day of snapshots) | < 30 min restore |
| Owner's intellectual property | `strategies`, `strategy_versions`, `strategy_audit_log`, `signals`, `backtest_runs/trades`, `optimization_trials`, paper ledger, `watchlists` | **Irreplaceable** | ≤ 24 h | < 30 min restore |

**Mechanism (D16).** An always-on **nightly `pg_dump` sidecar** (`postgres:17-alpine` image, cron at **00:30 IST** — after the trading day and the 16:00 aggregate refresh) writes **`pg_dump -Fc` per schema** to a **bind-mounted host folder outside the Docker volume** (on the Windows filesystem, outside the WSL2 VM by design). Rotation: **14 dailies + 8 weeklies** (~a few GB compressed). That host folder must sit inside a synced directory (OneDrive/Backblaze) so a disk failure doesn't take the only copy — Docker named volumes on WSL2 are **not** durable, and live volume snapshots of a running Postgres are explicitly **rejected** as a primary mechanism (crash-consistent at best). RPO 24 h is acceptable: candles are refetchable; slow-changing owned data and the irreplaceable `options_chain_snapshots` (≥ 5-year retention floor per amendment A2 — see COMMON §6 amendments) are covered nightly.

**Two cheap supplements:** strategy YAML is additionally exportable to a git repo via `GET /api/v1/strategies/{id}/versions`, making the highest-value asset effectively RPO≈0; and the **restore drill** is scripted and exercised **quarterly**.

**Restore drill (the Phase-48 deliverable):** `ay restore <file>` → restore into a **scratch container** → run **`flyway validate`** → **count rows**. WAL archiving/PITR is documented as a flip-on if a ≤ 24 h RPO ever feels insufficient, but is not part of the default footprint. If a backup ever exits non-zero, the sidecar POSTs ntfy directly (the "Nightly pg_dump failed" critical alert, G-D1.7).

## G-D5. Risk register — the ops risks observability + backup mitigate [plan §13 — inlined where Stage G is the control]

Severity/likelihood: **H**igh / **M**edium / **L**ow. These are the risks whose controls land in Stage G (monitoring, backup, hardening); they are the "why" behind the Phase-45/48 deliverables and are referenced by the runbook incident playbooks (G-D6).

| # | Risk | Sev | Lik | Mitigation (Stage-G-relevant) | Residual |
|---|------|-----|-----|------------------------------|----------|
| R1 | Kite API contract / ToS changes break ingestion | H | M | Single-adapter rule; WireMock contract tests; **daily contract canary → first-party ntfy** (G-D4.1); DB-as-cache survives outages | Days of degraded live data until adapter patched; cached data usable |
| R2 | Daily Kite token expiry/revocation kills live feed | M | H | Token AES-GCM in PG (restarts need no re-login); 5-min health check; **`ay_kite_session_valid=0` after 08:45 IST critical alert** (G-D1.7); one-click re-login; signal engine pauses cleanly | Live signals pause until re-auth (minutes); no corruption |
| R3 | Kite WebSocket instability → tick gaps | H | M | Resilience4j circuit breaker + backoff reconnect; gap detection + backfill; staleness watermark; **k6-verified ≤150 ms p99 + heartbeat metrics catch silent stalls** | Brief blind spots during reconnect |
| R7 | Single-machine resource exhaustion (RAM, disk, CPU) | M | M | Per-container `mem_limit` (core ≈3.9 GB); pool capped cores−2; candle compress 7 d, snapshot ≥5-y retention; **Grafana per-container RSS + disk dashboards under `obs`**; **disk/hypertable alert** (G-D1.7); nightly hypertable-size monitor | Sweeps queue; desktop stays usable; disk growth forecastable |
| R8 | Loss of irreplaceable artifacts | H | L | **Nightly `pg_dump` sidecar always on** with rotation to a host folder outside the Docker volume (G-D4.2); off-machine copy is an owner checklist item; strategy YAML also in git; **quarterly restore drill** | With backups ≤24 h lost; options snapshots the only truly unrecoverable stream |
| R12 | Broker credential compromise | H | M | Fresh Kite key pair (amendment A6 — see COMMON §6); secrets via git-ignored `.env` + Docker secrets; token AES-GCM at rest; gateway binds 127.0.0.1; **no secrets in logs**; secret-scanning hook; **read-only market scope means no order placement possible by design** | On compromise: read-only data access only |
| R13 | Kite contract drift discovered only at runtime | M | M | **Daily live contract canary field-diffs real responses vs recorded manifests + ntfy** (G-D4.1); `ay_kite_unparsed_frames_total` with registry-derived expected packet sizes guards the binary WS path | Drift detected, not prevented; cached data keeps backtests usable |

**Top-risk narratives relevant to the runbook incident playbooks** [plan §13.2]:

- **R8 (loss of irreplaceable artifacts).** Most data is a cache — instruments and candles re-fetch from Kite. Three things cannot: authored strategy versions, accumulated backtest/optimization results, and the self-archived `options_chain_snapshots` stream (Kite offers no historical options-chain/IV API; v1 even auto-deleted snapshots after 90 days). Hence ≥5-year snapshot retention (A2), the always-on nightly `pg_dump` sidecar, and git-exported strategy YAML as a second, diffable copy. Remaining exposure: the ≤24 h window between backups plus owner discipline on off-machine copies — the precise reason the restore drill is **executed once at GA and scheduled quarterly**.
- **R1 (Kite contract / ToS changes).** Only market-data-service speaks Kite, so a contract change is a one-service patch; WireMock fixtures turn upstream drift into failing CI, and the daily canary catches drift the **same trading day** even when CI fixtures are stale. DB-as-cache means a multi-day Kite outage degrades only *live* features. Mock mode doubles as a full offline fallback.

## G-D6. Runbook source material [plan §13 incident playbooks, §6.3 replay caveat, review S3A — inlined for Phase 48]

### G-D6.1 Daily Kite login ritual (Flow 1 summary)

market-data-service owns the daily OAuth ritual. The owner logs in once per trading day at the gateway; market-data-service exchanges the request token, **AES-GCM-encrypts the access token into Postgres** under `ARTHA_MASTER_KEY`, and runs a 5-min token-health check. Restarts before the ~6 AM IST expiry need no re-login (the encrypted token survives). `SPRING_PROFILES_ACTIVE=mock` gives a credential-free path with identical topics/flows. The `GET /api/v1/auth/kite/status` endpoint surfaces session validity and the day's canary result.

### G-D6.2 Incident playbooks (from the risk narratives, G-D5)

- **Feed loss / WS instability (R3):** Resilience4j circuit breaker + bounded exponential backoff reconnect; gap detection on 1m candle assembly with **backfill from the Historical API after reconnect**; staleness watermark on every tick. Alert: ticker-disconnected critical (G-D1.7).
- **Token expiry (R2):** surfaced on dashboard + `/topic/system`; one-click re-login at the gateway; signal engine **pauses cleanly** instead of emitting on stale data. Alert: token-expired critical after 08:45 IST.
- **Circuit open (R1/R3):** `ay_kite_circuit_state=1`; live features degrade to cached data; patch the single market-data adapter; mock mode is the offline fallback.
- **Job wedge:** stale `running` jobs **re-queue on startup** (D12); the Optuna-stalled rule (G-D1.7) catches a wedged consumer group; multi-second trial backlog is normal steady state, not an incident (G-D1.9).

### G-D6.3 Incident signal-replay procedure + the TICK_AGG-vs-KITE caveat [review S3A — runbook paragraph]

"Re-run all signals from last Friday" is **a backtest-service replay over the stored window with the pinned strategy version** (the §7.4 reproducibility triple: same YAML version + same candles + same engine JAR) — **not** a separate tick/event journal (S3A rejected: every event class is already persisted authoritatively, and pushing journal events through the *live* engine would re-emit onto the live signals channel and duplicate live artifacts; tick-fidelity persistence was already deliberately rejected in §6.3 — disk, Kite ToS, no consumer).

**The caveat the runbook must state (Phase 48 deliverable):** the replay verifies engine logic against **post-reconciliation `KITE` bars**, which can differ from the **`TICK_AGG` bars the live engine actually saw**. Therefore incident replay must **diff the candle streams FIRST** — compare the `TICK_AGG` vs reconciled `KITE` candle streams — and **screen out bar-divergence-driven signal deltas before attributing the remainder to the engine**. Only the residual, after bar divergence is removed, is a genuine engine question.

### G-D6.4 Other runbook contents [plan §13, §9.7, §15.2 Phase-6 row, COMMON §18.1]

- **Credential rotation procedure + cadence:** secrets only via git-ignored `.env` + Docker secrets; Kite key pair is fresh for 2.0 (amendment A6); rotating/deleting the old v1 key in the Zerodha console remains recommended housekeeping (its secret is in v1 git history; blast radius is read-only market access, no order placement) but **gates nothing**.
- **Live-mode owner tasks:** the **Kite minute-depth probe** (one historical-API call against a 2015 window — confirms whether minute history is policy-bound rather than API-bound, amendment A3; an open item — COMMON §18.4) and the **S2 weekly-expiry-day observation** (run on the first weekly index expiry after the ticker is live; **NSE index weeklies are on Tuesday effective Sep 2025** under SEBI's single-expiry-day rule — COMMON §18.2, correcting any stale "Thursday" text).
- **Tailscale phone-access setup (Q3):** the documented phone path is **Tailscale-first** — `tailscale serve` proxying the tailnet to `127.0.0.1:8080` (automatic ts.net TLS, WS included, zero LAN exposure, zero rebind). The no-Tailscale **LAN fallback ships only as an explicit `compose.lan.yaml` override file** that atomically couples the LAN-IP publish with a mounted mkcert certificate — **never** an env knob in the default compose file. The override is documented as a **threat-model change** (it moves the gateway off loopback). See COMMON §18.1 (Q3 resolution) and §12.6 (same-origin CORS posture preserved either way).

---

# Part 2 — Phase specs

Phases 45–48, near-verbatim from the implementation-phases doc. Cross-references rewritten to point at Part 1 above or [COMMON](ARTHAYANTRA_2_COMMON_REFERENCE.md). The phase numbering and stage placement match the COMMON §5 phase index.

## Phase 45 — obs profile: Prometheus/Grafana/Loki + alerts + bus health (S3B)

**Objective.** Ship the opt-in observability profile with the five provisioned dashboards, the alert catalog (ntfy contact point), and the S3B Redis bus-health metrics. *(Alert catalog → G-D1.7; dashboards → G-D1.3; S3B → G-D1.9.)*

**Why this phase is independent.** Every service already exposes metrics/logs (G-D3.4); this phase only adds collectors + config.

**Deliverables.**
- compose `--profile obs`: **Prometheus 3.x (256 MB), Grafana 11 (160 MB), Loki 3 (96 MB), Promtail (48 MB), cAdvisor v0.49 (48 MB)** — pinned, capped (G-D1.1); **Grafana published on `127.0.0.1:3000` under this profile only** (loopback posture preserved — COMMON §12.6), everything else internal.
- **Five dashboards as code** (G-D1.3): Market Data Health, Trading & Signals, Jobs & Backtests, System & Containers, Gateway & Sessions.
- **Alert rules** (G-D1.7 table) with **ntfy webhook + optional Telegram contact point**; deliberately **no stream-lag alert** (documented in the Grafana folder README — G-D1.7/G-D1.9).
- **S3B metrics** (G-D1.9): `ay_redis_pubsub_resubscribes_total{service}` (gateway + strategy-signal listener containers) and `ay_redis_memory_used_ratio` (market-data INFO-memory gauge, 60 s) + the **≥ 0.85 / 10 min warning rule**.
- Promtail/Loki labels **low-cardinality** (`service`, `level`); LogQL correlation-id query documented (G-D1.4).

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
./ay.sh up obs    # Grafana at 127.0.0.1:3000 (obs profile only)
```

**Tests & Verification.**
- All five dashboards render data on the mock stack; a forced condition (stop the market-data container) fires the container-unhealthy rule to the stubbed ntfy; the resubscribe counter increments across a Redis restart.

**Acceptance criteria.**
- **PASS:** `obs` profile adds **≤ ~600 MB** (caps enforced); the core stack runs **identically with the profile down** (G-D3.4 — nothing in the hot path depends on `obs`).
- **FAIL:** any service depending on the `obs` profile at runtime; high-cardinality Loki labels (G-D1.4).

**Commit message.** `feat(obs): opt-in prometheus/grafana/loki profile with provisioned dashboards, alert catalog and redis bus-health metrics`

**PR title.** `Phase 45: observability profile + alerting`

**Time estimate.** 90–120 min.

**Token size target.** ≤ 35k output tokens.

**If phase too big.** (a) collectors + dashboards; (b) alert rules + S3B metrics.

## Phase 46 — k6 load suite + nightly perf gates

**Objective.** Encode the performance contract: the three k6 scenarios with thresholds bound to the §5.10 targets (G-D2.1), runnable locally and nightly in CI.

**Why this phase is independent.** Runs against the complete mock stack; the only consumers are thresholds.

**Deliverables.**
- `load/ws-fanout.js` — **300 subscribed instruments**, burst rates, **3 STOMP sessions** via gateway: **tick-to-browser ≤ 150 ms p99**, **zero dropped frames**, gateway RSS within **384 MB** (G-D2).
- `load/chain-refresh.js` — chain fetch **every 5 s for 30 min** during fan-out: REST p95 per §5.10 (G-D2.1); snapshot writes never delay ticks.
- `load/backtest-saturation.js` — **4×(cores−2) jobs + a 200-trial sweep**: pool cap respected, **zero lost jobs**, interactive REST p95 unchanged, backtest-service **≤ 896 MB**.
- **Mock-feed load profile:** `MOCK_TICKS_PER_SEC` raised to reach **~500–1,500 ticks/s aggregate** across 300 instruments (the Phase-7 knob) — §10.8's shape **without the deferred kite-sim container** (G-D2; the kite-sim container is in COMMON §21 deferred-beyond-plan).
- `.github/workflows/ci-load.yml` — nightly + release tag (**thresholds informational on GitHub runners; binding numbers are measured on the owner's machine** — G-D2.2); `ay load` verb.

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
./ay.sh up obs && k6 run load/ws-fanout.js
```

**Tests & Verification.**
- All three scenarios pass thresholds locally on the target machine; results visible in Grafana during runs.

**Acceptance criteria.**
- **PASS:** ws-fanout **p99 ≤ 150 ms locally** at the **20 Hz flush default**; saturation run loses **zero jobs** (Postgres↔Streams reconciliation proven under load).
- **FAIL:** gates treated as **binding on GitHub-hosted runners** (G-D2.2).

**Commit message.** `test(load): k6 ws-fanout, chain-refresh and backtest-saturation scenarios with nightly workflow`

**PR title.** `Phase 46: k6 load suite + perf gates`

**Time estimate.** 60–90 min.

**Token size target.** ≤ 25k output tokens.

**If phase too big.** Not applicable.

---

## Phase 47 — Image/container hardening + supply-chain CI + AOT/AppCDS

**Objective.** Bring every container to the §11.9/§9.5 hardening bar (G-D3.2/G-D3.1) and the D7 RAM caps (COMMON §6): AOT + AppCDS images, non-root/read-only/cap-drop, digest pins, and supply-chain scanning in CI (G-D3.3).

**Why this phase is independent.** Pure build/config work over the finished stack; verified by boot + RSS measurements + CI.

**Deliverables.**
- **Java Dockerfiles → 3-stage:** build + Spring AOT → AppCDS training run (mock profile) → JRE runtime; `-Xmx` matching `mem_limit`; **SerialGC on edge-gateway + strategy-signal-service; G1 on backtest-service** (G-D3.1).
- **compose hardening:** every container **`read_only: true`** (+ tmpfs), **`cap_drop: [ALL]`**, **`no-new-privileges`**, non-root users, **pinned digests** alongside tags (G-D3.2).
- **CI additions:** **Trivy** image scan pre-push, **OWASP Dependency-Check** (CVSS ≥ 7 fail + suppression file), **`npm audit --audit-level=high`**, **`pip-audit`**, **Dependabot** config for all five ecosystems (G-D3.3).
- **Startup-time + RSS check:** documented measurements vs the D7 RAM table (COMMON §6; targets in G-D2.1: cold start ≤ ~3 s per Java service, RSS within caps).

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
./ay.sh up && ./ay.sh status     # cold start ≤ ~3 s per Java service; RSS within caps
```

**Tests & Verification.**
- Full **E2E + k6 suites still green on hardened images** (read-only roots are where this usually breaks — G-D2.3); CI scans green or suppressed-with-review.

**Acceptance criteria.**
- **PASS:** worst-case stack RSS **≈ ≤ 6 GB with `obs` on**; all images **non-root + digest-pinned**; a **planted CVSS-9 dependency fails CI** (G-D3.3).
- **FAIL:** hardening flags dropped to make tests pass without a documented exception.

**Commit message.** `build(images): aot/appcds runtime images, container hardening and supply-chain scanning`

**PR title.** `Phase 47: image hardening + supply chain`

**Time estimate.** 90–120 min.

**Token size target.** ≤ 30k output tokens.

**If phase too big.** (a) AOT/AppCDS images + RAM caps; (b) hardening flags + CI scans.

---

## Phase 48 — Runbook, restore drill, GA gate

**Objective.** Close out: the operational runbook, a scripted-and-executed backup restore drill, final docs, and the GA checklist walk.

**Why this phase is independent.** Documents and drills the finished system; the drill is executable against the running stack.

**Deliverables.**
- **`docs/runbook.md`** (sources in G-D6): daily Kite login ritual (G-D6.1); incident playbooks (feed loss, token expiry, circuit open, job wedge — G-D6.2; plus **corporate-action rebuild verification** — confirming the Stage B Phase 16A integrity job's purge/re-backfill completed for a flagged symbol [FP-1, owner selection 2026-06-12]); credential rotation procedure + cadence (G-D6.4); **incident signal-replay procedure incl. the TICK_AGG-vs-KITE candle-diff caveat** (G-D6.3); **minute-depth probe + S2 weekly-expiry-day observation as live-mode owner tasks** (G-D6.4), plus **expiry-settlement day checks for open paper derivatives** as a live-mode owner task (Stage F Phase 43B) [FP-2, owner selection 2026-06-12]; **Tailscale phone-access setup** (Q3); **`compose.lan.yaml` override documented as a threat-model change** (G-D6.4).
- **Restore drill:** `ay restore` → **scratch container** → **`flyway validate`** → **row counts** — scripted, **executed once now**, scheduled **quarterly** in the runbook (G-D4.2).
- **README finalized** (clone→green **≤ 15 min** path verified end-to-end on a clean machine); **per-service READMEs** (one page each); **`docs/dev-setup.md`** completed.
- **`release.yml`** workflow (tag → rebuild with digests → full E2E → GHCR `:vX.Y.Z` + compose lockfile of digests).
- **GA checklist in `PHASE_GATES.md`:** all suites green; restore drill passed; **parallel-run/cutover plan referenced** for the owner's live adoption (see COMMON §15 migration path / §14 risk).
- **Tag `v2.0.0`.**

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
./ay.sh backup && ./ay.sh restore <latest>    # drill
git tag v2.0.0 && git push --tags             # release.yml runs
```

**Tests & Verification.**
- Restore drill green; release workflow produces pinned images + lockfile; clean-machine onboarding timed **≤ ~15 min**.

**Acceptance criteria.**
- **PASS:** every `PHASE_GATES.md` box **across all phases (62 — see COMMON §5) [A13, 2026-06-12] checked**; **v2.0.0 images on GHCR (private)**.
- **FAIL:** any unchecked gate carried into the tag.

**Commit message.** `docs(release): operational runbook, restore drill and v2.0.0 ga gate`

**PR title.** `Phase 48: runbook + restore drill + GA`

**Time estimate.** 60–90 min.

**Token size target.** ≤ 25k output tokens.

**If phase too big.** Not applicable.

---

# Part 3 — Stage exit gate (GA gate)

This is the matching acceptance row from plan §15.2 for **macro-Phase 6 ("Observability, polish, hardening")**, inlined as the Friday-gate checklist (the S5 gate ritual input). At a phase boundary, any unchecked box **extends the phase — gates not green means never advance** (§15.6 exit ritual). Stage G is the **last** stage, so this gate **is** the GA gate; passing it tags `v2.0.0`.

### Macro-Phase 6 acceptance (plan §15.2 row — checklist)

**Key deliverables (all must be present):**
- [ ] `obs` compose profile (Prometheus 3.x, Grafana 11, Loki 3 + Promtail + cAdvisor) with the D14/five provisioned dashboards (Phase 45).
- [ ] **k6 WS fan-out test with the ≤ 150 ms p99 gate** (Phase 46).
- [ ] Nightly `pg_dump` sidecar **verified via restore drill** (Phase 48).
- [ ] **AOT/AppCDS image tuning to the D7 RAM caps** (Phase 47).
- [ ] Security pass (container hardening + supply-chain scanning, §11 — Phase 47).
- [ ] `docs/runbook` (Phase 48).
- [ ] **Review addition (S3B, +1 d):** Redis bus-health metrics + memory-pressure alert row (G-D1.9 — Phase 45).

**Acceptance criteria (demo-able — all must pass):**
- [ ] **k6 gate passes** — ws-fanout p99 ≤ 150 ms **on the owner's machine** at the 20 Hz flush default (G-D2.2: never binding on GitHub-hosted runners); zero dropped frames; saturation run loses zero jobs.
- [ ] **Restore drill from backup succeeds on a clean volume** — `ay restore` → scratch container → `flyway validate` → row counts (G-D4.2).
- [ ] **Worst-case RSS ≤ ~6 GB with `obs` on** (Phase 47; ADR RAM budget — COMMON §6).
- [ ] **All §10 test suites green** — unit + golden, Testcontainers integration, contract, Playwright E2E, and all k6 scenarios (including on the hardened read-only images, G-D2.3).

**GA-specific close-out (Phase 48):**
- [ ] Every `PHASE_GATES.md` box **across all phases (62 — see COMMON §5) [A13, 2026-06-12]** checked; no unchecked gate carried into the tag.
- [ ] `release.yml` produces digest-pinned `:v2.0.0` images on GHCR (**private** — by posture choice: trading-strategy IP + Kite-credential hygiene, single-user posture; no redistribution constraint applies [A13, 2026-06-12]).
- [ ] Clean-machine onboarding timed **≤ ~15 min** (clone→green).
- [ ] Parallel-run / cutover plan referenced for the owner's live adoption (COMMON §15 / §14).
- [ ] Tag **`v2.0.0`**.

### Stage-end notes

- **Binding-numbers rule (G-D2.2):** the 50 ms tick→Redis and 150 ms tick→browser p99 gates are measured on the target Windows/Docker Desktop machine, never on GitHub-hosted runners. No bespoke gate automation beyond CI — the machine-checkable subset is already CI-enforced (§9.6), and performance numbers are a local Friday-ritual check.
- **Deferred-beyond-plan items** (the kite-sim container, OTel/`tracing` profile flip-on, WAL/PITR, LAN `compose.lan.yaml` as default, etc.) live in the **deferred appendix — COMMON §21**; they are referenced, not re-scoped here. The OTel flip-on revisit trigger is a latency regression the histograms cannot localize in one debugging session (G-D1.5).
- **Open items at GA** (owner actions, not build gates): the Kite minute-depth probe (amendment A3), NSE index-constituents CSV source verification, statutory fee-schedule values pinned at implementation, and the S2 weekly-expiry observation — all carried in COMMON §18.4 and surfaced in the runbook as live-mode owner tasks.



