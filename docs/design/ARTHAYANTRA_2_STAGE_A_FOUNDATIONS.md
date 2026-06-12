# ArthaYantra 2.0 — Stage A: Foundations

**Stage letter / name:** A — Foundations
**Plan macro-phase:** Phase 0 (the plan's "Foundations" row, §15.2 — inlined as this stage's exit gate at the end).
**Phases covered:** 1–8.
**Prerequisite stages:** none — Stage A is the first build stage. Depends only on the **Owner Day-0 actions** (see [COMMON §2](ARTHAYANTRA_2_COMMON_REFERENCE.md#2-owner-day-0-actions-before-phase-1)); none of those are coding work.
**Common reference:** [ARTHAYANTRA_2_COMMON_REFERENCE.md](ARTHAYANTRA_2_COMMON_REFERENCE.md) — read its execution protocol (§1), global conventions (§3), CD-1..CD-17 defaults (§4), the ADR decision tables (§6), the stack-version + RAM tables (§6), the error-code taxonomy (§10 / §8.3), and the repo layout (§10.1) before implementing any phase here. This stage file **inlines fully** every spec its eight phases need at build time; COMMON is cited only for app-wide tables that every stage shares.

**Stage goal (one paragraph).** Stand up the credential-free substrate that every later phase runs on: the monorepo skeleton with git/secret hygiene and the process artifacts (Phase 1); the two stateful containers (TimescaleDB + Redis), the always-on nightly backup sidecar, the dev-tools profile, and the `ay` operator CLI (Phase 2); the Flyway one-shot init job that builds all schemas/roles/grants from an empty volume (Phase 3); the Maven reactor and the two foundation libraries — `common-web` (error envelope + masking logging) and `market-calendar` (IST hours + NSE holidays + Tuesday expiries) (Phase 4); the single front door — edge-gateway with Argon2id form login, Redis sessions, the route table, and security headers (Phase 5); GitHub Actions CI as the mechanical reviewer (Phase 6); market-data-service as a Spring Modulith skeleton with the five Kite ports and a deterministic mock tick feed (Phase 7); and the gateway WS bridge that relays Redis pub/sub to the browser over STOMP-on-native-WebSocket with 20 Hz conflation (Phase 8 — the Stage-A exit gate). At the end of Stage A, `ay up` is green on a clean machine with **no Kite credentials**, login works at `127.0.0.1:8080`, and mock ticks are observable end-to-end via the STOMP probe.

---

# Part 1 — Design reference (inlined sources this stage needs)

Each subsection is tagged with its source breadcrumb so verifiers can trace provenance. Where an ADR amendment (A1–A13) governs, the amended form is stated inline.

## A.1 Compose topology, profiles, RAM budget, startup ordering [plan §3.5.1–§3.5.3, §9.2]

### A.1.1 Compose profiles and one-command startup [plan §3.5.1 + §9.2]

One `deploy/docker-compose.yml` with three activation tiers (ADR D14/D16):

| Profile | Command | Contents | When | RAM delta |
|---|---|---|---|---|
| *(default — "core")* | `docker compose up -d` | 8 D7 containers + `flyway-init` (one-shot, exits) + `db-backup` sidecar (always on) | Daily operation | ~3.9 GB |
| `obs` | `docker compose --profile obs up -d` | Prometheus 3.x, Grafana 11, Loki 3 + Promtail | Opt-in observability (Stage G) | +~550 MB |
| `dev-tools` | `docker compose --profile dev-tools up -d` | Adminer (DB UI) + RedisInsight + standalone WireMock 3.x Kite stub, bound to `127.0.0.1`; publishes `redis` 6379 and the internal service ports on `127.0.0.1` | Ad-hoc inspection + host hot-reload (T2) + stub-driven dev | +~250 MB |

Mock vs live is **orthogonal to profiles**: `SPRING_PROFILES_ACTIVE=mock` in `.env` flips the whole stack to the credential-free path (D13). Every container carries a **pinned image tag, a `mem_limit` per the ADR RAM table, and a healthcheck**; app services declare `depends_on: { flyway-init: service_completed_successfully, timescaledb: service_healthy, redis: service_healthy }`, eliminating v1's blind `sleep 5` startup.

**One-command startup** replaces the v1 `start.bat`/`start.sh`/`start_all.py`/`stop.bat` quartet — notably `stop.bat`, which force-killed *every* `java.exe` on the machine. A thin wrapper (`ay.ps1` on Windows, `ay.sh` on Linux) exposes verbs that map 1:1 to **project-scoped** compose (never raw `docker kill`):

| Verb | Effect |
|---|---|
| `ay up [obs] [dev-tools]` | `docker compose --env-file .env [--profile …] up -d` |
| `ay down` | `compose down` (project-scoped only) |
| `ay logs <svc>` | `compose logs -f <svc>` |
| `ay status` | Healthcheck summary of all containers |
| `ay backup` | Manual `pg_dump` (§9.10 / see A.5) |
| `ay restore <file>` | Documented restore from a dump file (§9.10) |
| `ay reset-db` | Down, drop volume, re-up → Flyway rebuilds schemas (fixes v1's broken fresh-volume init) |

### A.1.2 Published ports and remote access (resolves Q3) [plan §3.5.1 + §11.6 + decisions S6/Q3]

- The gateway's single published port stays **hardcoded as `127.0.0.1:8080:8080`** in the default compose file — the D13 binding is deliberate and is **not parameterized** via environment variables. Host exposure is decided solely by the compose `ports:` mapping, never by an in-container `server.address`; inside its network namespace the gateway listens on all interfaces, and loopback-binding the process would break the published port.
- **`timescaledb` `127.0.0.1:5432`** is the only other published port (dev tooling only).
- Dev UIs in the `dev-tools` profile publish on loopback at non-conflicting ports (**S6 corrections**): **Adminer `127.0.0.1:8085`** (moved off host 8080 — the edge-gateway's sole published app port), **RedisInsight `127.0.0.1:5540`** (its current default). Host 8080 stays reserved for the gateway; 8081–8084 are taken by the dev-tools-published internal service ports. **There is no "full Linux VPS" tier — the Tier-4 row is deleted** (the single-Windows-machine constraint is binding).
- **Phone/tablet access is Tailscale-first (Q3 final design):** `tailscale serve` on the Windows host reverse-proxies the tailnet to `http://127.0.0.1:8080`, so the phone reaches `https://<machine>.<tailnet>.ts.net` with automatically issued/renewed certificates and working WebSocket proxying — no rebind, no certificates to manage, nothing exposed beyond the WireGuard mesh. The gateway honors `X-Forwarded-Proto: https` from the proxy to mark the session cookie `Secure`.
- **LAN exposure only via an explicit `compose.lan.yaml` override file** that **atomically couples the LAN-IP publish with a mounted mkcert certificate** — a deliberate, owner-acknowledged threat-model change requiring a Section-11 review before use; never an env knob in the default file (where a stale `.env` line would silently rebind on every `ay up`). **`0.0.0.0` is never a sanctioned value.** mkcert/TLS-at-the-gateway as a default is dropped from scope. CORS stays disabled in every variant — the SPA is served through the gateway, so any-hostname access remains same-origin.

### A.1.3 Per-container RAM budget [plan §3.5.2 / ADR RAM table — see COMMON §6]

Every container declares `mem_limit`, a pinned image tag, and a healthcheck (D16). Java images are built with Spring AOT + AppCDS and explicit `-Xmx` caps; small services use SerialGC. The caps Stage A introduces:

| Container | mem_limit | Notes |
|---|---|---|
| edge-gateway | 384 MB | Reactive, low heap |
| market-data-service | 640 MB | Ticker + chain + cache hot path |
| timescaledb | 1,024 MB | `shared_buffers=512MB` |
| redis | 64 MB | `maxmemory` + eviction (CD-16: `volatile-lru` instance-wide) |
| flyway-init | 256 MB (transient) | Exits before app start; zero steady-state cost |
| db-backup sidecar | 64 MB | Alpine + cron + pg_dump; 14 daily + 8 weekly rotation |

(Full eight-container table + obs/overhead totals: [COMMON §6 — Per-container RAM budget](ARTHAYANTRA_2_COMMON_REFERENCE.md#per-container-ram-budget-compose-mem_limit).)

### A.1.4 Startup ordering and healthchecks [plan §3.5.3]

Compose `depends_on` conditions encode the boot DAG; **no fixed sleeps** (the v1 anti-pattern):

1. `timescaledb` — healthcheck `pg_isready`; `redis` — healthcheck `redis-cli ping` (run in parallel).
2. `flyway-init` — `depends_on: timescaledb: service_healthy`; runs all `V###__*.sql` across the schemas, then **exits 0** (D17).
3. `market-data-service`, `strategy-signal-service`, `backtest-service`, `optimizer-service` — `depends_on: flyway-init: service_completed_successfully` + `redis: service_healthy`; each exposes `/actuator/health` (Java) or `/health` (FastAPI) readiness used as its own healthcheck. *(In Stage A only market-data-service exists; the others land in later stages.)*
4. `edge-gateway` — `depends_on: redis: service_healthy`; routes return **503 with the standard error envelope** until upstreams are ready (routing is dynamic, so it does not hard-depend on every service).
5. `frontend-ui` — no dependencies (static); `db-backup` — depends on `timescaledb: service_healthy`.

Restart policy is `unless-stopped` for the long-running containers. Because the Kite token is persisted encrypted (Stage B Flow 1) and stale `running` jobs re-queue from the `jobs` table (Stage D), a full `docker compose restart` mid-day recovers to a working state with no manual steps.

**Reusable healthcheck/limit pattern** (repeated for every later container):

```yaml
healthcheck: { test: ["CMD", "pg_isready", "-U", "artha"], interval: 5s, retries: 10 }
mem_limit: 1024m
```

## A.2 edge-gateway service spec [plan §5.2.1, §5.3, §11.3, §11.6, §11.8]

### A.2.1 Responsibilities and endpoints [plan §5.2.1]

edge-gateway (Java 21, Spring Cloud Gateway 4.3, 384 MB) is the **sole ingress**: route table below; Spring Security form login (D13); Spring Session in Redis; security headers (CSP, X-Frame-Options, HSTS-off-localhost); STOMP-over-native-WebSocket endpoint `/ws` bridging Redis pub/sub channels to per-symbol browser topics (see A.7); coarse anti-accident rate limit. **Owned data:** none persistent — sessions in Redis only. **Events:** consumes `ticks.{exchange}.{tradingsymbol}`, `candles.1m.*`, `signals`, `options.chain`, `jobs.progress` from Redis and relays to `/topic/**`; publishes nothing.

| Method | Path | Purpose | Request / Response |
|---|---|---|---|
| POST | `/api/v1/auth/login` | Owner login | Body: password. **204 + `HttpOnly; SameSite=Strict` session cookie**; 401 envelope on bad password (Argon2id verify against `ARTHA_OWNER_PASSWORD_HASH`) |
| POST | `/api/v1/auth/logout` | End session | 204; session removed from Redis |
| GET | `/api/v1/auth/session` | Session probe for SPA boot | 200 with authenticated flag, login time, mock/live profile indicator |
| GET | `/ws` | WebSocket upgrade | Native WS handshake (no SockJS); rejected **401** without a session |

### A.2.2 Route table (D8) [plan §5.2.1]

`/api/v1/market/**`, `/api/v1/instruments/**`, `/api/v1/watchlists/**`, `/api/v1/auth/kite/**` → **market-data-service (:8081)**; `/api/v1/strategies/**`, `/api/v1/signals/**`, `/api/v1/paper/**` → **strategy-signal-service (:8082)**; `/api/v1/backtests/**` → **backtest-service (:8083)**; `/api/v1/optimizations/**` → **optimizer-service (:8084)**; everything else → **frontend-ui static files**. Downstream services trust the gateway-injected identity header on the private compose network and are unreachable from the host. In Stage A every target except market-data-service is absent → routes return **503 + the standard error envelope** (never a stack trace).

One thin Spring Cloud Gateway (ADR D7: "Gateway decision: YES") buys: a single localhost origin (CORS deleted, fixing v1's `allowedOriginPatterns("*")`-with-credentials hole), one place for session auth + security headers, one WS endpoint multiplexing all topics, and stable SPA URLs while internal ports stay private.

### A.2.3 Authentication & session design [plan §11.3 + decisions]

One human, one credential: Spring Security form login at the gateway only (D13).

- **Password hash:** verified against an **Argon2id** hash via `Argon2PasswordEncoder` with parameters **m = 19456 KiB, t = 2, p = 1**. The exact constructor is `new Argon2PasswordEncoder(16, 32, 1, 19456, 2)` — saltLength=16, hashLength=32, parallelism (p)=1, memory (m)=19456 KiB, iterations (t)=2. No user table exists; the hash is supplied via `ARTHA_OWNER_PASSWORD_HASH`.
- **Sessions:** Spring Session in **Redis** with an **`HttpOnly; SameSite=Strict; Path=/`** cookie (add `Secure` when TLS is on / when `X-Forwarded-Proto: https` is present), **12-hour idle timeout** covering a full trading day.
- **Internal trust:** internal services never see the password — they sit on the private compose network with **no published ports** and trust a gateway-injected **`X-Artha-User`** header; the gateway **strips any inbound copy** of that header and injects its own.
- **Defense-in-depth ordering:** even though `127.0.0.1` binding makes the login screen unreachable from the LAN, authentication stays on so that a single compose typo (`8080:8080` instead of `127.0.0.1:8080:8080`) degrades to "password required," not "open broker dashboard."
- **Security headers** the gateway sets on every response: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and a **self-only CSP** — no vendored-bundle exception; the chart renderer is the npm-pinned `lightweight-charts`, compiled into the Angular `dist/` [A13, 2026-06-12].
- **CSRF** is enforced for mutating calls.

### A.2.4 Reactive common-web equivalents [plan §5.2.1, §12.4]

The gateway is WebFlux/reactive and **cannot host spring-webmvc**, so it provides reactive equivalents of the `common-web` servlet adapter (see A.3):

- an **`ErrorWebExceptionHandler`** emitting the `{ code, message, details }` envelope;
- a **`GlobalFilter`** that injects `X-Artha-User` (stripping any inbound copy) **and** generates/forwards the **`X-Request-Id` correlation id** (§12.4 — see A.6).

### A.2.5 CORS / TLS posture [plan §11.6]

Because the SPA is served *through* the gateway from the same origin (`127.0.0.1:8080`), production has **no cross-origin traffic and CORS stays disabled** — the strongest possible policy and a deliberate D7 payoff over v1's `allowedOriginPatterns("*")` with credentials. The only CORS configuration that exists is in the Angular dev proxy (D3), which rewrites `http://localhost:4200 → gateway`, again avoiding browser-level CORS. **TLS:** plain HTTP on localhost is acceptable — traffic never leaves loopback. Phone access keeps D13 intact via Tailscale-serve (A.1.2). A LAN bind without Tailscale is possible only through the explicit `compose.lan.yaml` override and is a deliberate threat-model change; `0.0.0.0` is never sanctioned.

### A.2.6 Rate limiting [plan §11.8]

Single-user, so this is **abuse protection, not capacity management**. Spring Cloud Gateway's Redis `RequestRateLimiter` applies:

- **`POST /login` — 5 attempts/min per source IP**, then `429` with a **15-minute cooldown** counter in Redis (anti-brute-force if ever LAN/cloud exposed);
- **all other `/api/v1/**` routes — a generous 50 req/s safety valve** that catches runaway frontend loops (a real v1 bug class) rather than humans.

Kite-facing rate limiting (3 req/s historical, ticker budget) is a separate reliability concern owned by market-data-service (Stage B).

### A.2.7 Password-hash helper (CD-13) [plan §5.2.1 / COMMON CD-13]

`tools/hash-password/` — a tiny Java `main` that emits an Argon2id **PHC string** for `ARTHA_OWNER_PASSWORD_HASH`, invoked via `mvnw exec:java`, documented in README. Uses the same m/t/p parameters as A.2.3.

## A.3 common-web library spec [plan §5.4, §11.7, §12.4; ADR D8]

`libs/common-web/` is split so the reactive gateway can depend on it **without spring-webmvc on its classpath**:

- **core** module:
  - `ErrorResponse(code, message, details)` record — the D8 error envelope. Minimal form:
    ```java
    public record ErrorResponse(String code, String message, Map<String, Object> details) {}
    ```
  - **SCREAMING_SNAKE code constants** per the error-code taxonomy (A.4 below);
  - **Jackson config** serializing `BigDecimal` as **strings** and `OffsetDateTime` with the **`+05:30`** offset;
  - **shared ECS JSON-logging config** + a **`MaskingMessageConverter`** for token/PHC patterns (see A.6).
- **servlet adapter** module:
  - `GlobalExceptionHandler` mapping validation / not-found / conflict to the envelope;
  - identity-header (**`X-Artha-User`**) filter.

The reactive gateway uses its own reactive equivalents (A.2.4) of the servlet pieces, depending only on the core module.

## A.4 Error envelope & error-code taxonomy [plan §5.4 / ADR D8 — see COMMON §10/§8.3]

Every non-2xx response from every service — Java via the shared `GlobalExceptionHandler` (or the gateway's `ErrorWebExceptionHandler`), Python via a FastAPI exception handler — returns **exactly** the `{ code, message, details }` envelope. No more `{"success": false}` bodies with HTTP 200 (v1 anti-pattern). Example:

```json
{
  "code": "KITE_RATE_LIMITED",
  "message": "Kite historical API budget exhausted; retried 3 times",
  "details": { "retryAfterMs": 1200, "endpoint": "historical", "jobId": null }
}
```

Codes are SCREAMING_SNAKE strings grouped by prefix (the families Stage A touches in bold):

| Family | HTTP | Example codes |
|---|---|---|
| **`VALIDATION_*`** | 400 | `VALIDATION_FAILED` (field map in details), `VALIDATION_INTERVAL_UNSUPPORTED`, `VALIDATION_RANGE_TOO_LARGE` |
| **`AUTH_*`** | 401/403 | `AUTH_REQUIRED`, `AUTH_BAD_CREDENTIALS`, `AUTH_SESSION_EXPIRED` |
| `KITE_*` | 401/429/502/503 | `KITE_TOKEN_EXPIRED`, `KITE_RATE_LIMITED`, `KITE_CIRCUIT_OPEN`, `KITE_UPSTREAM_ERROR` |
| `NOT_FOUND_*` | 404 | `NOT_FOUND_INSTRUMENT`, `NOT_FOUND_SIGNAL`, `NOT_FOUND_JOB`, `NOT_FOUND_WATCHLIST` |
| `CONFLICT_*` | 409 | `CONFLICT_JOB_TERMINAL`, `CONFLICT_VERSION_IMMUTABLE`, `CONFLICT_SYNC_RUNNING`, `CONFLICT_WATCHLIST_NAME` |
| `STRATEGY_*` | 400/422 | `STRATEGY_SCHEMA_INVALID`, `STRATEGY_NOT_PUBLISHED` |
| `DATA_*` | 422/503 | `DATA_GAP`, `DATA_STALE` |
| **`INTERNAL_*`** | 500 | `INTERNAL_ERROR` (correlation id in details, never a stack trace) |

The full taxonomy is the single source in [COMMON §10/§8.3](ARTHAYANTRA_2_COMMON_REFERENCE.md#83-error-envelope--error-code-taxonomy); Stage A endpoints (auth, 503 routing, NOT_CONFIGURED port stubs) use `AUTH_*`, `VALIDATION_*`, and `INTERNAL_*` plus the gateway's 503 envelope.

## A.5 market-calendar library spec [plan §9.9 seed; ADR D12; CD-2]

`libs/market-calendar/` provides the shared IST `MarketCalendar` (consumed by every service that schedules or bounds session times):

- **Session window:** 09:15–15:30 IST, Mon–Fri.
- **NSE holiday list** as a **versioned resource file inside the library** for 2026 (CD-2 — yearly refresh by PR, *not* a DB seed).
- **Helpers:** `isOpen(Instant)`, `sessionBounds(LocalDate)`, `nextTradingDay`, `expectedMinuteBuckets(range)`.
- **Expiry-day helpers reflect Tuesday weekly index expiries** — NSE index weekly expiries moved to **Tuesday effective September 2025** under SEBI's single-expiry-day rule (the plan's older "Expiry Thursday" wording is superseded — see decisions §SPIKES). Helpers compute the Tuesday weekly index expiry.

## A.6 Sensitive-data logging, JSON logging & correlation IDs [plan §11.7, §12.4]

### A.6.1 Sensitive-data logging rules [plan §11.7]

- **Never log:** `api_secret`, access token, `request_token` (OAuth callback), password, session cookie values, `ARTHA_MASTER_KEY`.
- `KITE_API_KEY` appears only **masked** (`tdwk…t2` style, first 4 / last 2) in the startup config echo.
- No full request/response dumps at INFO; wire-level Kite logging is permitted only at DEBUG **in mock mode**.
- A shared Logback **`MaskingMessageConverter`** (regexes for token-like and **PHC-format** strings) is part of the common logging config every Java service imports (it lives in `common-web` core, A.3); the FastAPI service applies an equivalent filter. JSON logs make masking testable — a unit test asserts a fake token never survives into log output.
- OAuth callback URLs are logged with the `request_token` query parameter **stripped**.

### A.6.2 JSON logging + log caps [plan §12.4]

All services log **structured JSON to stdout** — Java via Spring Boot 3.5's native `logging.structured.format.console=ecs` (no extra encoder dependency), Python via `structlog` 24.x. Docker's `json-file` driver, **capped `max-size: 10m`, `max-file: 3`** on every service, is the buffer. (Loki/Promtail shipping is an `obs`-profile concern that lands in Stage G.)

### A.6.3 Correlation IDs [plan §12.4]

The **edge-gateway generates an `X-Request-Id` UUID per inbound request** and forwards it (the gateway `GlobalFilter`, A.2.4); every Java service binds it to the MDC. For async flows the id travels with the work (persisted on the `jobs` row, copied into Redis Streams messages — Stage D). **The per-tick hot path is deliberately not correlated** (per-message UUIDs would dominate the 150 ms budget); tick issues are diagnosed via metrics and the candle-lag gauge instead.

## A.7 Internal tick pipeline & gateway WS bridge [plan §8.5, §8.6, §8.7]

### A.7.1 Internal tick pipeline — the Phase 7 normalizer/queue side [plan §8.5]

Pipeline stages (mock feed feeds the same path as the live Kite feed):

```
Kite WebSocket (3000 instr/conn)  ─┐
                                   ├─► Bounded ingress queue (cap 10k, drop-oldest + counter) ─► Normalizer (single writer) ─► Redis pub/sub  ticks.{exchange}.{tradingsymbol}
Mock feed (profile=mock)         ─┘                                                                                          └─► Candle builder 1m (idempotent upsert) ─► candles.1m.* + TimescaleDB
```

- **Ingress queue:** Kite callback threads (or the mock feed) only *enqueue* raw packets into a **bounded ingress queue (capacity 10k, drop-oldest with a counter metric)** — latest price beats a stalled backlog.
- **Single-writer normalizer** then: resolves token → stable **`(exchange, tradingsymbol)`** key; converts prices to **exact decimals (`BigDecimal`, serialized as strings, never float)**; normalizes exchange timestamps to **`Asia/Kolkata` (IST, `+05:30`)**; assigns a **per-instrument monotonic sequence number (`seq`)**; and publishes compact JSON to Redis.
- **Channel naming (D9, verbatim):** `ticks.{exchange}.{tradingsymbol}`, `candles.1m.{exchange}.{tradingsymbol}`, `signals`, `options.chain`, `jobs.progress`. Higher intervals (5m/15m/1h/1d) are **not** bus traffic — they come from TimescaleDB continuous aggregates on read (Stage B).
- **Last-tick / status keys:** the publisher also writes a last-tick `HSET` and the canonical Redis key **`kite:session:status`** = MOCK/LIVE state (COMMON §3 canonical name; the candle builder + signal engine + options snapshotter consumers arrive in Stages B/C).

In **Stage A (Phase 7)** the consumers built are: the **Redis publisher** (`ticks.*` channels + last-tick `HSET` + `kite:session:status`). The candle builder, signal engine, and options snapshotter are later-stage consumers — but subscribing to their topics on the gateway is already legal (silence is fine) per A.7.2.

### A.7.2 Backpressure, conflation, ordering, duplicates [plan §8.6]

Redis pub/sub is fire-and-forget, so backpressure is handled at the edges:

- **Slow service consumers:** Redis `client-output-buffer-limit pubsub 16mb 8mb 60` disconnects pathological subscribers; each consumer drains into a **latest-value-wins conflation map keyed by instrument** and processes from there, so a busy evaluation **skips intermediate ticks, never queues them** (decoupling evaluation from the receive thread — v1 ran signals synchronously on the WS callback).
- **Browser (the gateway WS bridge):** the gateway keeps a **per-session conflation buffer** and flushes **batched STOMP frames at 20 Hz (50 ms; configurable 10–20 Hz via env `GATEWAY_WS_FLUSH_HZ`)** containing only instruments that changed since the last frame — bounded render work regardless of tick rate. The flush interval is part of the measured latency budget: the k6 gate (Stage G) clocks **tick-to-browser ≤ 150 ms p99 through this conflated path**. At the 20 Hz default the added wait is ≤ 50 ms; the **10 Hz floor (100 ms interval) is the slowest setting that can still pass the 150 ms gate** — the gate and the Stage G fan-out alert assume the 20 Hz default. Clients subscribe to **per-symbol topics** (`/topic/ticks.{exchange}.{tradingsymbol}`), so a 3-instrument watchlist receives 3 instruments, not the firehose.
- **Never conflated:** `signals`, `jobs.progress`, `candles.1m.*` — low-rate, every message matters; signals are additionally persisted in Postgres and re-synced over REST on WS reconnect (bus delivery is best-effort; the DB is truth).
- **Ordering & duplicates:** the single-writer normalizer makes per-instrument sequence numbers monotonic; consumers **drop `seq ≤ lastSeen`** (catches reconnect-overlap duplicates). Candle writes are idempotent upserts on PK `(exchange, tradingsymbol, interval, bucket)` with `high=GREATEST`, `low=LEAST`, close = highest-seq tick; volume derives from Kite's *cumulative* day volume (bucket-end minus bucket-start), so replayed ticks cannot double-count. *(Candle write detail is Stage B; the ordering/dup contract is pinned here because the Phase 7 normalizer assigns the `seq` the WS bridge relies on.)*

### A.7.3 Improvements over the v1 STOMP fan-out [plan §8.7]

| Aspect | v1 | 2.0 |
|---|---|---|
| Topic granularity | One `/topic/ticks` firehose; every client gets every symbol | Per-symbol topics; subscribe = what's on screen |
| Frame rate | One STOMP frame per tick | Conflated batched frames, 20 Hz default (configurable 10–20 Hz), latest-value-wins |
| Transport | SockJS (fallback overhead, extra frames) | **Native WebSocket STOMP via gateway** (D9; SockJS dropped) |
| Producer/consumer coupling | In-process `ApplicationEvent`, synchronous on the WS receive thread | Redis pub/sub across services; conflation maps isolate slow consumers |
| Resilience | Hand-rolled breaker, thread-safety bugs, no resubscribe discipline | Resilience4j breakers, registry-driven resubscribe, gap backfill |
| Verification | Manual HTML/SockJS eyeball page | k6 WS load test gating **tick-to-browser ≤ 150 ms p99** (Stage G) |

Trade-off acknowledged: Redis pub/sub adds one ~sub-millisecond localhost hop versus in-process events, and conflation adds up to one frame interval (≤ 50 ms at 20 Hz) of display latency — both deliberate buys of cross-service decoupling and bounded browser load. Conflation latency **counts against** the ≤ 150 ms p99 budget. The signal engine is unaffected by the flush rate: it consumes unconflated per-tick streams directly off Redis and never traverses the gateway bridge.

**Gateway STOMP-subset codec (CD-3):** a minimal STOMP-subset codec (CONNECT/CONNECTED, SUBSCRIBE/UNSUBSCRIBE, MESSAGE, heartbeats 10s/10s) over a WebFlux `WebSocketHandler` in edge-gateway — sufficient for `@stomp/stompjs`; classic `@EnableWebSocketMessageBroker` is servlet-only and is **not** used.

## A.8 Migrations & Flyway design [plan §6.10, §9.9; ADR D17; CD-1, CD-7]

**Flyway 11** runs as a **one-shot compose init job** (`flyway-init`) before any app service starts (`depends_on: flyway-init: condition: service_completed_successfully`); every service runs `ddl-auto=none` / no SQLAlchemy DDL. This kills the v1 failure class outright: the broken initdb.d-vs-manual-psql split, the V001 seed referencing V002 columns, and undetectable volume drift.

**Directory layout (CD-7 — `deploy/flyway/{admin,marketdata,strategy,backtest}/`; the plan §9.1 layout wins over §6.10's `db/` sketch):**

```
deploy/flyway/
├── admin/        V001__roles_and_schemas.sql   (runs FIRST)
├── marketdata/   V001__baseline.sql  (then V002+ in Stage B …)
├── strategy/     V001__baseline.sql  (then V002+ in Stage C …)
└── backtest/     V001__baseline.sql  (then V002+ in Stage D …)
```

- **One Flyway invocation per lineage** (four `-schemas=` / `-locations=` configs in the same container run, **admin first**), giving **independent `flyway_schema_history` tables per lineage** — each service owns its migration lineage, matching the schema-per-service boundary (single-writer rule, D10).
- **Roles/schemas live in a fourth tiny `admin/` lineage (CD-1)** that runs first: it creates the 3 schemas (`marketdata`, `strategy`, `backtest`), the 3 service roles (`ay_marketdata`, `ay_strategy`, `ay_backtest`), and default privileges — including **the single cross-schema grant: backtest read-only on `marketdata` via `ALTER DEFAULT PRIVILEGES`**. `strategy` and `marketdata` roles receive **no** cross-schema grants. The three per-schema lineages own only their own tables.
- **Rules:** applied migrations are **immutable (checksum-enforced)**; changes are **additive-first** (new column → backfill → switch → drop in a later version); hypertable creation, compression/retention policies, and continuous aggregates are themselves versioned migrations, so a fresh volume reproduces the *entire* physical design from `V001`.
- **Verification:** CI boots the pinned `timescale/timescaledb:2.17.x-pg17` image, runs all lineages from empty, then executes `flyway validate` — fresh-volume initialization is tested on every commit (A.10 / Phase 6).
- **Destructive operations** (drops, retention tightening) require a same-PR note in the migration header documenting what the preceding night's `pg_dump` covers — lightweight single-owner change control.
- **Seed data** (Stage B onward): repeatable `R__seed_*.sql` migrations load the mock-mode instrument subset and sample draft strategies; all idempotent (`ON CONFLICT DO NOTHING`). *(The NSE holiday calendar is **not** a DB seed — it lives in `libs/market-calendar` per CD-2.)*

## A.9 Secrets management [plan §9.7, §11.2; ADR D13; A6]

v2 placement (D13) — all secrets live **outside the repo and outside images**:

| Secret | Where it lives | Consumed by |
|---|---|---|
| `KITE_API_KEY`, `KITE_API_SECRET` | Docker secret files in `deploy/secrets/` (gitignored), mounted at `/run/secrets/` | market-data-service only |
| Kite `access_token` (daily, ~6 AM IST expiry) | **Never in env or files** — AES-GCM-encrypted row in Postgres `marketdata` schema (Stage B) | market-data-service |
| `ARTHA_MASTER_KEY` (AES-GCM key, 256-bit base64) | Docker secret file | market-data-service |
| `ARTHA_OWNER_PASSWORD_HASH` (Argon2id PHC) | `.env` | edge-gateway |
| `POSTGRES_PASSWORD` | **Docker secret file (`POSTGRES_PASSWORD_FILE`)** — never a plain env var | timescaledb + services |

- **Never committed:** `.env`, `deploy/secrets/`, any `application*.properties` containing credentials, `./backups/` dumps. Enforced by `.gitignore`, a **gitleaks pre-commit hook**, and the CI scan. `.env.example` documents every variable with placeholders.
- **In `mock` mode the stack boots with zero secrets present** (D13).
- **Day-zero credential rotation / leaked-credential tripwire — SUPERSEDED by ADR amendment A6 (owner decision 2026-06-12):** the 2.0 stack is provisioned with a **brand-new Kite API key pair**; the v1 pair is never configured anywhere in 2.0. D13's "the leaked v1 credentials are rotated day zero" is **no longer a 2.0 build gate**, and the P1-4 leaked-credential digest tripwire is **dropped as moot** — no digests are recorded and there is nothing to compare at startup. Deleting/rotating the old v1 key in the Zerodha console remains recommended housekeeping (its secret is public in v1 git history; blast radius is **read-only market access, no order placement** — threat T1), but it gates nothing. All other D13 mechanics (Argon2id login, AES-GCM token at rest, `.env` + Docker secrets, mock mode) stand unchanged. **Stage A records the A6 decision in `docs/LEGAL.md`/process docs; it builds no tripwire.**

**Threat model — what matters (plan §11.1, the entries Stage A controls close):**

| # | Threat | Impact | Primary controls |
|---|---|---|---|
| T1 | Kite credential / access-token theft | Read access only — **no order placement** (no execution scope; app never calls order APIs) | A6 fresh keys, `.env` + Docker secrets, AES-GCM at rest, log masking (A.6) |
| T2 | Accidental LAN exposure | Anyone on home Wi-Fi reads signals/P&L/token | Gateway binds `127.0.0.1:8080` (D7); Argon2id login even on localhost; no other published app port |
| T4 | Database exposure (`5432` for dev tooling) | Direct read/write of strategies, encrypted token blob | Bind `127.0.0.1:5432` only, strong generated password (file-mounted), per-schema roles |

## A.10 CI / GitHub Actions design [plan §9.6, §10.4]

v1 has **zero CI**. v2 uses **path-filtered** workflows so a frontend commit never rebuilds four JVM images. The two workflows Stage A creates (Phase 6):

| Workflow | Trigger paths | Stages (described) |
|---|---|---|
| `ci-java.yml` (matrix over existing Java modules) | `services/**`, `libs/**`, root `pom.xml` | ① Checkstyle/Error Prone lint → ② JUnit 5 unit + golden-vector engine tests (JaCoCo **services ≥ 60 % line**) → ③ Testcontainers IT with **service containers pinned to production tags** (`timescale/timescaledb:2.17.x-pg17` + `redis:7.4-alpine`) → ④ Modulith verification → ⑤ image build (AOT/AppCDS) → ⑥ **push to GHCR on `main`/tags only** |
| `ci-migrations.yml` | `deploy/flyway/**` | all four Flyway lineages against a fresh pinned TimescaleDB container + `flyway validate` |

- A **gitleaks** secret-scan step runs in **every** workflow; Maven/npm/pip dependency caches; **`concurrency: cancel-in-progress`**.
- **Checksum-drift detection is two-step** in `ci-migrations.yml`: **migrate with the merge-base's migration files first, then run `flyway validate` with the PR's files against that populated container** — a fresh-volume-only run can never detect an edited migration. (Editing an applied migration in a scratch PR must turn the check red.)
- **Testcontainers IT conventions (plan §10.4):** each Java service ships an integration suite using `@ServiceConnection` containers with **pinned images identical to production compose tags** (D16): `timescale/timescaledb:2.17.x-pg17`, `redis:7.4-alpine`. Stage-A-relevant ITs: the schema-per-service **grant test** (the `backtest` role can `SELECT` but not `INSERT` into `marketdata`); the **migration gate** (full Flyway chain on an empty container); **Redis bus semantics** (pub/sub topic naming `ticks.{exchange}.{tradingsymbol}`); **Modulith verification** (`ApplicationModules.verify()` in market-data-service).
- **Branch-protection note in README** (PRs required, all path-filtered checks green, force-push and direct push disabled — yes, even for the owner; the owner clicks this in GitHub settings). The git workflow is **trunk-based** with short-lived `feat/|fix/|chore/|docs/` branches, **Conventional Commits 1.0** (scope = service/lib name), **squash-merge only**.

## A.11 Backup automation [plan §9.10, §12.7]

The `db-backup` sidecar is **always on** (D16) — v1 had zero backups for its only non-cache data. Nightly at **00:30 IST** (post-EOD snapshots) it runs **`pg_dump -Fc` per schema** into the bind-mounted `./backups` on the **Windows filesystem** (outside the WSL2 VM and Docker volumes by design — write-once nightly traffic where 9P slowness is irrelevant and survival outside the VM is the point). **Rotation: 14 daily + 8 weekly.** RPO 24 h is acceptable: candles are refetchable from Kite; slow-changing owned data and the irreplaceable `options_chain_snapshots` are covered nightly. `ay restore <file>` is a documented, quarterly-drilled procedure; the owner may sync `./backups` to OneDrive for an off-machine copy.

**On dump failure the script `curl`s the ops ntfy topic directly** (a no-op when `ARTHA_NTFY_TOPIC` is unset) — the plan §12.7 first-party critical alert, **shell-side, no shared code** (`deploy/backup/backup.sh`).

## A.12 PR / self-review checklist [plan §9.12]

Code review is the **solo-developer variant**: every change lands via PR; the diff is read in the GitHub PR view; the CI tiers are the non-negotiable mechanical reviewer (a red tier blocks merge). A **self-review checklist** lives in `.github/PULL_REQUEST_TEMPLATE.md` (the Stage A deliverable in Phase 1):

- BigDecimal for all prices (no float/double)?
- IST-normalized times?
- Stable `exchange + tradingsymbol` keys (no numeric tokens)?
- Works under `SPRING_PROFILES_ACTIVE=mock` with zero credentials?
- Flyway migration (never an edit to an applied `V###` file)?
- Golden-vector tests updated if engine behavior changed?
- Error envelope (D8) on new endpoints?
- `mem_limit` impact considered?

(Changes touching `libs/strategy-engine/` get the extra golden-vector parity gate — relevant from Stage C onward, not in Stage A.)

## A.13 Golden-vector fixture-format freeze [plan §10.7; P4 pre-build checklist]

**This is the P4 Day-0–1 pre-build checklist item**, materialized in Stage A as `docs/golden-vectors.md`. Freeze **now** so the Stage C live signal engine (Phase 23) and the Stage D replay engine (Phase 30) consume **one harness**. The freeze fixes only the **fixture format** — *not* `strategy-schema/v1` (that is designed in Stage C / Phase 18 and frozen at the Stage C exit gate; D18 versioning covers later evolution).

The format to freeze (from plan §10.7 — the golden-vector suite that pins "same YAML + same candles → identical signals and metrics, live and backtest"):

- **Fixture directory layout** for the committed fixtures: five trading days of synthetic 1m NIFTY candles (generated once by the seeded mock generator and then **frozen**), plus one strategy YAML per schema feature.
- **Candle encoding:** prices as **exact decimal strings**, IST `TIMESTAMPTZ`, the OHLCV columns and bucket key.
- **Expected-signal / expected-metrics encoding:** the metrics file pins `returns`, `sharpe`, `maxDrawdown`, `winRate`, `tradeCount` as **exact decimal strings**; the parity family pins signal lists (timestamps, scores, per-indicator breakdowns) as **byte-identical** between the tick-wise live path and the replayed backtest path; version-immutability re-derives the stored SHA-256 checksum.

*(The composite formula those breakdowns serialize is pinned by ADR amendment A1 — composite = (Σ required w·s + Σ activated-optional w·s) / (Σ required w + Σ activated-optional w); an optional indicator activates only when its score ≥ `optional_min_score` (default 0.6) AND the required-only composite ≥ threshold − `optional_gate_margin` (default 0.15). Stage A only freezes the *format*; the formula lands with the engine in Stage C / Phase 20.)*

## A.14 Developer setup — `docs/dev-setup.md` tier table (S6) [plan §9.3, §9.12]

`docs/dev-setup.md` is a Phase 0 deliverable (S6, **+0.5 d**, folded alongside the README). Stage A creates the **stub with the §9.3 tier-table headings** (Phase 1) and fills the working verbs + port map as Phase 2 lands. The corrected tier table (S6 fixes applied):

| Tier | Service under iteration | Rest of stack | Reload | Use when |
|---|---|---|---|---|
| **T1 — UI inner loop** | `ng serve` on :4200 with dev proxy → `127.0.0.1:8080` (D3) | default compose profile (containerized) | <1 s HMR | Frontend work |
| **T2 — backend inner loop** | `mvnw spring-boot:run -Dspring-boot.run.profiles=dev,mock` on host (devtools restart); `uvicorn --reload` for optimizer-service | **`ay up dev-tools` — required**: this profile is what publishes Redis 6379 and the internal service ports on `127.0.0.1` (Postgres 5432 is always published, D7) | 2–4 s | Single Java/Python service work |
| **T3 — full containerized** | in container | `docker compose watch` (`sync` + `rebuild`) | 10–30 s | Pre-merge parity check — matches what CI and Playwright E2E run |

Host-run tiers (T1/T2) connect to the **same Docker Postgres/Redis published on loopback**, so compose-network parity holds — nothing runs against a second local database. Switch tiers with `ay down` / `ay up [dev-tools]` — **never raw `docker kill`**. Dev UIs publish on loopback at non-conflicting ports: **Adminer `127.0.0.1:8085`, RedisInsight `127.0.0.1:5540`**; host **8080 stays reserved for the gateway**; 8081–8084 are the dev-tools-published internal service ports. **There is no Tier-4 "Full Linux VPS" row** (deleted — single-Windows-machine constraint is binding; the optional cloud exit ramp is documentation only).

`docs/dev-setup.md` also records: the **single-clone rule** (repo lives in WSL2 ext4; Windows-native frontend work accesses it via `\\wsl.localhost` or runs Node inside WSL2 — never a second checkout), and the `ay` verb reference. PR-checklist item whenever ports or compose profiles change.

## A.15 PHASE_GATES.md + the Friday gate ritual (S5) [plan §15.6; decisions S5]

**Final design (S5 — the bespoke GitHub Action is rejected as CI theatre).** A one-page **`PHASE_GATES.md`** holds **only**: the **current-phase marker**, a **checkbox copy of the current phase's §15.2 acceptance criteria**, and a **"deferred to Phase N+1" parking list** — §15.2 (and these stage files) stay the single source of truth. A standing **Friday gate ritual** walks the checklist **against the running mock stack** at each phase boundary; an unchecked box **extends the phase**. Hard calendar freeze dates are deleted. No `verify_phase_gates.py`, no on-push gate enforcement (end-of-phase gates are red for the whole phase by construction; the machine-checkable subset is already CI-enforced).

In Stage A, **Phase 1** creates `PHASE_GATES.md` (current-phase marker = Phase 1, its acceptance criteria as checkboxes, empty parking list); each subsequent phase updates the marker and parking list; **Phase 8** records the Stage-A exit-gate checklist (A.17 below).

## A.16 Chart-library license posture — `docs/LEGAL.md` [A13, 2026-06-12; supersedes the Q5 TV-license checklist]

**lightweight-charts (pinned `>=5.2 <6`) is the PRIMARY main-chart renderer per ADR amendment A13; TradingView Advanced Charts is dropped entirely** — TradingView's published eligibility excludes this project (no licenses for personal/private use), so the old Q5 "verify the signed Advanced Charts agreement" checklist has no object and is **deleted**. `docs/LEGAL.md` changes scope from a signed-agreement record to an **attribution record** [A13, 2026-06-12]:

1. **Record the lightweight-charts license posture:** Apache-2.0 + NOTICE attribution + a tradingview.com link; the built-in `attributionLogo` chart option stays **ON** (satisfies the link requirement).
2. **Private repo + private GHCR remain BY CHOICE** — trading-strategy IP, Kite-credential hygiene, single-user posture — **not** a redistribution mandate (no vendored proprietary bundle exists; the `frontend-ui` image ships Angular `dist/` only).
3. **No second main-chart renderer** — lightweight-charts is the sole main-chart renderer; reintroducing TradingView (or any renderer swap) requires a new ADR amendment (CD-9 as redefined by A13).

~1 hour of attribution recording, not a week of abstraction code. *(The old Q5 Phase 0 verification task and its +0.25 d are returned to the ledger; the attribution record costs ~0.1 d, carried in the A13 chart-surface ledger — COMMON §16.2 Stage E row, not the Phase-0 review-additions total — A13 effort accounting.)*

## A.17 Stage-A exit gate input — plan §15.2 Phase 0 row [plan §15.2]

The plan's Phase-0 row, inlined here so it can be copied into `PHASE_GATES.md` at Phase 8 and walked at the Friday ritual (see Part 3 — Stage exit gate at the end of this file):

> **Phase 0 — Foundations.** Key deliverables: monorepo layout; compose with all 8 D7 containers stubbed + `mem_limit` + healthchecks; Flyway 11 init job (3 schemas); GitHub Actions build/test/GHCR pipeline; edge-gateway with Argon2id form login + Spring Session in Redis; mock Kite simulator publishing synthetic ticks (D13 mock profile). **Review additions:** `PHASE_GATES.md` + pre-build checklist (S5+P4, +1 d); `docs/dev-setup.md` (S6, +0.5 d); lightweight-charts Apache-2.0 + NOTICE attribution record in LEGAL/README, `attributionLogo` on, private repo + private GHCR by choice ([A13, 2026-06-12] — replaces the Q5 TV-license verification task; Q5's +0.25 d returned, attribution ~0.1 d); Tailscale-first remote-access decision documented (Q3, +0.5 d). *(Phase-0 "day-zero rotation of leaked v1 credentials" is superseded by A6 — fresh keys, no rotation gate.)*
>
> **Acceptance (demo-able):** `docker compose up` is green on a clean machine with **no Kite credentials**; login at `127.0.0.1:8080` works; CI builds every service image; mock ticks visible on Redis `ticks.*` channels; following `docs/dev-setup.md` Tier 2 verbatim, a host-run service connects to compose Redis/Postgres in mock mode.

**Phase-0 review additions total +2.0 d** (decisions §5 ledger: S5 0.5 · P4 0.5 · S6 0.5 · Q3 0.5 — the Q5 0.25 d TV-license verification task is deleted and its effort returned per A13 [2026-06-12]; the ~0.1 d `docs/LEGAL.md` attribution record is carried in the A13 chart-surface ledger, COMMON §16.2 Stage E row, not here).

---

# Part 2 — Phase specs (Phases 1–8)

Each phase below is copied near-verbatim from the implementation-phases doc, with cross-references rewritten to point at Part 1 of this file or at COMMON. Source breadcrumbs name the original `plan §`, `Dn`, `Sx`, `Qx`, `Px`, `CD-n`, `BPx` tags.

## Phase 1 — Repo scaffold, hygiene & process docs

**Objective.** Create the monorepo skeleton, git hygiene, and the process artifacts that every later phase depends on — with **zero application code**.

**Why this phase is independent.** First phase; depends only on the Owner Day-0 actions ([COMMON §2](ARTHAYANTRA_2_COMMON_REFERENCE.md#2-owner-day-0-actions-before-phase-1)). Verifiable without any toolchain beyond git + pre-commit.

**Deliverables.**
- `README.md` — onboarding contract stub: prerequisites, clone→green path placeholder, `ay` verb table (filled in Phase 2 — see A.1.1), profile matrix (A.1.1 / [COMMON §3 Ports + §10.3](ARTHAYANTRA_2_COMMON_REFERENCE.md#103-environment-configuration-strategy-profile-matrix)).
- `.gitignore` — `.env`, `deploy/secrets/`, `backups/`, `node_modules/`, `target/`, `dist/`, `.venv/`.
- `.env.example` — **every variable with placeholders, no values** (later phases append theirs as they land):
  - `SPRING_PROFILES_ACTIVE=mock`
  - `KITE_API_KEY`
  - `KITE_API_SECRET`
  - `ARTHA_OWNER_PASSWORD_HASH`
  - `ARTHA_MASTER_KEY`
  - `POSTGRES_PASSWORD`
  - `GATEWAY_WS_FLUSH_HZ=20`
  - `KITE_QUOTE_BATCH_SIZE=250`
  - `MOCK_TICKS_PER_SEC=1`
  - `MOCK_SCENARIO=trend-up`
  - `ARTHA_NTFY_TOPIC` (optional)
- `docs/golden-vectors.md` — **golden-vector fixture-format freeze** (the P4 Day-0–1 checklist item): fixture directory layout, candle encoding, expected-signal encoding — frozen now so the Phase 23 live half and Phase 30 replay half consume one harness (full spec inlined at **A.13**).
- `docs/remote-access.md` — Q3 record: Tailscale-serve-first phone path proxying to `127.0.0.1:8080`; LAN exposure only via an explicit `compose.lan.yaml` override that atomically couples the LAN publish with a mounted cert (threat-model change); `0.0.0.0` never sanctioned (full spec at **A.1.2**).
- `.pre-commit-config.yaml` — gitleaks hook (lint hooks added as stacks appear).
- `PHASE_GATES.md` — current-phase marker, checkbox copy of **this phase's** acceptance criteria, empty "deferred to next phase" parking list (S5 ritual — see **A.15**).
- `docs/design/` — **ADAPTATION (per the prompt's SPECIAL instruction):** the original phases doc says "copy in the three frozen design docs (redesign plan Rev 1.1, ADR-001, review decisions) + this phases document." Those four originals are being **deleted**. Instead, **copy in THE NEW 8-FILE SET** — `docs/phases/*.md` (this `ARTHAYANTRA_2_STAGE_A_FOUNDATIONS.md`, the other six stage files, and `ARTHAYANTRA_2_COMMON_REFERENCE.md`) — as the per-session reference set. Mark this clearly as the adaptation in the README/`PHASE_GATES.md`.
- `docs/dev-setup.md` — stub with the A.14 (§9.3) tier-table headings (filled as tiers become real).
- `docs/LEGAL.md` — lightweight-charts Apache-2.0 + NOTICE attribution record (tradingview.com link, `attributionLogo` on) + private-repo / private-GHCR-by-choice posture ([A13, 2026-06-12] — see **A.16**).
- Empty directory markers for `services/ libs/ frontend-ui/ deploy/flyway deploy/secrets e2e/ load/ tools/ .github/workflows/` (the monorepo tree — [COMMON §10.1](ARTHAYANTRA_2_COMMON_REFERENCE.md#101-monorepo-layout)).
- `.github/PULL_REQUEST_TEMPLATE.md` — self-review checklist (the items in **A.12**).

**Minimal code/config.** None beyond the files above; no build runs yet.

**DB changes.** none

**Build & Run.**
```
git init && pre-commit install
pre-commit run --all-files          # gitleaks passes on a clean tree
```

**Tests & Verification.**
- `pre-commit run --all-files` exits 0.
- Manually drop a fake `api_secret=xyz…` line into a scratch file → gitleaks blocks the commit → remove.
- `PHASE_GATES.md` shows Phase 1 checked.

**Acceptance criteria.**
- **PASS:** repo pushed; gitleaks hook demonstrably blocks a planted secret; `.env.example` contains every variable listed in this phase's deliverables; design docs (the 8-file set) present under `docs/design/`; fixture-format freeze committed.
- **FAIL:** any real credential anywhere; missing `.gitignore` entries.

**Commit message.** `chore(repo): scaffold monorepo layout, hygiene hooks, and process docs`
**PR title.** `Phase 1: repo scaffold, hygiene & process docs`
**Time estimate.** 45–60 min.
**Token size target.** ≤ 15k output tokens for the implementing session.
**If phase too big.** Not applicable — already minimal.

---

## Phase 2 — Core infra compose: TimescaleDB + Redis + backup sidecar + `ay` CLI

**Objective.** Stand up the two stateful containers (pinned, healthchecked, memory-capped, loopback-bound), the always-on nightly pg_dump sidecar, the dev-tools profile, and the `ay` operator wrapper.

**Why this phase is independent.** Pure infrastructure; needs only Docker Desktop. No app images exist yet — compose contains exactly what can run today.

**Deliverables.**
- `deploy/docker-compose.yml`:
  - **`timescaledb`** — pinned `timescale/timescaledb:2.17.x-pg17`, healthcheck `pg_isready -U artha`, `mem_limit: 1024m`, `shared_buffers=512MB`, port `127.0.0.1:5432:5432`, named volume, **password via compose `secrets:` file mount — `POSTGRES_PASSWORD_FILE`, never a plain env var** (A.9 / plan §9.7).
  - **`redis`** — `redis:7.4-alpine`, healthcheck `redis-cli ping`, `mem_limit: 64m`, `maxmemory` + **`volatile-lru`** — eviction is instance-wide in Redis, so only TTL'd cache keys are evictable and session/Streams keys are safe (**CD-16**).
  - **`db-backup`** sidecar — postgres:17-alpine + cron, **00:30 IST `pg_dump -Fc` per schema** to bind-mounted `./backups`, **14 daily + 8 weekly** rotation, `mem_limit: 64m` (A.11).
  - **`dev-tools` profile** — Adminer `127.0.0.1:8085`, RedisInsight `127.0.0.1:5540`, publishes redis `127.0.0.1:6379` (S6 ports — A.1.2/A.14).
  - **`json-file` log caps** (`max-size: 10m`, `max-file: 3`) on every service (A.6.2 / plan §12.4).
- `deploy/backup/backup.sh` + rotation logic; **on dump failure it `curl`s the ops ntfy topic directly** (no-op when `ARTHA_NTFY_TOPIC` unset) — the plan §12.7 first-party critical, shell-side, no shared code (A.11).
- `ay.ps1` / `ay.sh` — verbs `up [obs] [dev-tools]`, `down`, `logs <svc>`, `status`, `backup`, `restore <file>`, `reset-db` (A.1.1; **project-scoped compose only**).
- README + `docs/dev-setup.md` updated with the working verbs and port map.

**Minimal code/config.** Healthcheck/limit pattern (repeated for every later container — A.1.4):
```yaml
healthcheck: { test: ["CMD", "pg_isready", "-U", "artha"], interval: 5s, retries: 10 }
mem_limit: 1024m
```

**DB changes.** none (DB container only; schemas come in Phase 3).

**Build & Run.**
```
cp .env.example .env
./ay.sh up dev-tools     # or .\ay.ps1 up dev-tools
./ay.sh status
```

**Tests & Verification.**
- `ay status` shows timescaledb + redis healthy; Adminer reachable at `127.0.0.1:8085`.
- `ay backup` produces a dump file in `./backups`; `ay down` stops only project containers.
- `docker inspect` confirms loopback-only port bindings and `mem_limit`s.

**Acceptance criteria.**
- **PASS:** clean-machine `ay up` reaches healthy with no credentials; backup file appears on demand; no port bound to `0.0.0.0`; `docker inspect timescaledb` shows **no password value in env** (file-mounted secret); a forced dump failure produces the ntfy POST (captured against a local stub).
- **FAIL:** unpinned image tags; missing healthcheck or `mem_limit` on any container.

**Commit message.** `feat(deploy): core compose with timescaledb, redis, backup sidecar, dev-tools profile and ay CLI`
**PR title.** `Phase 2: core infra compose + ay operator CLI`
**Time estimate.** 60–90 min.
**Token size target.** ≤ 20k output tokens.
**If phase too big.** (a) timescaledb+redis+ay up/down/status; (b) backup sidecar + restore/reset verbs + dev-tools profile.

---

## Phase 3 — Flyway init job + schemas/roles baseline

**Objective.** Add the one-shot `flyway-init` compose job that builds the three service schemas, roles, and grants from empty on every fresh volume (D17), killing the v1 init-drift bug class from day one.

**Why this phase is independent.** Depends only on Phase 2 containers. Verifiable by `ay reset-db` + psql assertions; no app code involved.

**Deliverables.**
- `deploy/flyway/admin/V001__roles_and_schemas.sql` — creates schemas `marketdata`, `strategy`, `backtest`; roles `ay_marketdata`, `ay_strategy`, `ay_backtest` (idempotent `DO $$` guards); grants: **the single cross-schema grant — backtest read-only on `marketdata` via `ALTER DEFAULT PRIVILEGES`** (single-writer rule, D10); `strategy` and `marketdata` roles receive **no** cross-schema grants (**CD-1**; A.8).
- `deploy/flyway/{marketdata,strategy,backtest}/V001__baseline.sql` — empty-but-valid baseline per lineage (comment header only) so each lineage owns an independent `flyway_schema_history`.
- compose service `flyway-init` (`flyway/flyway:11-alpine`, `mem_limit: 256m`, `depends_on: timescaledb: service_healthy`), entry script running the four lineages in order (**admin first**) with `-schemas`/`-locations` per invocation, then exiting 0.
- `ay reset-db` verified against it.

**Minimal code/config.**
```yaml
flyway-init:
  image: flyway/flyway:11-alpine
  depends_on: { timescaledb: { condition: service_healthy } }
  entrypoint: ["/flyway-run.sh"]   # runs admin → marketdata → strategy → backtest
```

**DB changes.** `admin/V001__roles_and_schemas.sql`, three `V001__baseline.sql` files (as above).

**Build & Run.**
```
./ay.sh reset-db     # down, drop volume, up → flyway runs all lineages, exits 0
```

**Tests & Verification.**
- Fresh volume: `docker compose logs flyway-init` shows 4 successful lineage runs, container exits 0.
- psql (via Adminer or `docker exec`): schemas exist; `SET ROLE ay_backtest; INSERT INTO marketdata…` fails, `SELECT` allowed (grant test — automated later in the Phase 9 ITs, see A.10).
- Second `ay up`: flyway is a no-op and exits 0 (idempotence).

**Acceptance criteria.**
- **PASS:** `ay reset-db` → healthy stack with all schemas/roles from empty, **twice in a row**.
- **FAIL:** any app-managed DDL path; flyway exit ≠ 0 on fresh volume.

**Commit message.** `feat(db): flyway one-shot init job with admin/marketdata/strategy/backtest lineages and role grants`
**PR title.** `Phase 3: Flyway init job + schema/role baseline`
**Time estimate.** 60–90 min.
**Token size target.** ≤ 20k output tokens.
**If phase too big.** Not applicable.

---

## Phase 4 — Maven reactor + shared libs (common-web, market-calendar)

**Objective.** Establish the root Maven reactor and the two foundation libraries: the D8 error envelope / `GlobalExceptionHandler` and the shared IST `MarketCalendar` (D12).

**Why this phase is independent.** Pure Java libraries with unit tests; no containers, no services. Everything later imports these.

**Deliverables.**
- Root `pom.xml` — Boot 3.5.x parent BOM, Java 21, modules list (grows per phase); `mvnw` wrapper.
- `libs/common-web/` — **split so the reactive gateway can depend on it safely (no spring-webmvc on its classpath)** — full spec at **A.3**:
  - **core** — `ErrorResponse(code, message, details)` record; SCREAMING_SNAKE code constants per the taxonomy (**A.4**); Jackson config serializing `BigDecimal` as strings and `OffsetDateTime` with `+05:30`; shared ECS JSON-logging config + `MaskingMessageConverter` for token/PHC patterns (**A.6** / plan §12.4/§11.7).
  - **servlet adapter** — `GlobalExceptionHandler` mapping validation/not-found/conflict to the envelope; identity-header (`X-Artha-User`) filter.
- `libs/market-calendar/` — `MarketCalendar` (full spec at **A.5**): 09:15–15:30 IST Mon–Fri, NSE holiday list as a **versioned resource for 2026 (CD-2)**, helpers `isOpen(Instant)`, `sessionBounds(LocalDate)`, `nextTradingDay`, `expectedMinuteBuckets(range)`; **expiry-day helpers reflect Tuesday weekly index expiries** (SEBI single-expiry-day rule, Sep 2025).
- Unit tests for both (holiday edges, session boundaries, envelope mapping).
- Checkstyle (Google style, 120-col) + Error Prone wired into the reactor; pre-commit hook entry added.

**Minimal code/config.**
```java
public record ErrorResponse(String code, String message, Map<String, Object> details) {}
```

**DB changes.** none

**Build & Run.**
```
./mvnw -pl libs/common-web,libs/market-calendar -am verify
```

**Tests & Verification.**
- Unit suites green; Checkstyle/Error Prone clean.
- `MarketCalendarTest` covers: holiday, weekend, 09:14/09:15/15:30/15:31 boundaries, **Tuesday expiry helper**.

**Acceptance criteria.**
- **PASS:** `mvnw verify` green from a clean checkout; calendar boundary table asserted.
- **FAIL:** any float/double in money-typed test fixtures; missing holiday resource.

**Commit message.** `feat(libs): maven reactor with common-web error envelope and market-calendar`
**PR title.** `Phase 4: Maven reactor + common-web + market-calendar`
**Time estimate.** 60–90 min.
**Token size target.** ≤ 25k output tokens.
**If phase too big.** (a) reactor + common-web; (b) market-calendar.

---

## Phase 5 — edge-gateway: form login, sessions, routing skeleton

**Objective.** Ship the single front door: Spring Cloud Gateway 4.3 on `127.0.0.1:8080` with Argon2id form login, Spring Session in Redis, security headers, the D8 route table (targets may 503 until services exist), and the auth endpoints.

**Why this phase is independent.** Needs only Phases 2–4. Login + session + static 404/503 routing are fully testable with curl and integration tests; downstream services are not required.

**Deliverables.**
- `services/edge-gateway/` — Boot app (reactive); routes per **A.2.1/A.2.2** (`/api/v1/market|instruments|watchlists|auth/kite/** → :8081`, `strategies|signals|paper → :8082`, `backtests → :8083`, `optimizations → :8084`, fallback → frontend-ui); **503 + envelope when upstream absent**.
- Endpoints: `POST /api/v1/auth/login` (Argon2id verify vs `ARTHA_OWNER_PASSWORD_HASH`, **204 + `HttpOnly; SameSite=Strict` cookie**), `POST /api/v1/auth/logout`, `GET /api/v1/auth/session` (A.2.1).
- Spring Session Redis (**12 h idle**); CSRF for mutating calls; headers `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, **self-only CSP** (no vendored-bundle exception [A13, 2026-06-12]); gateway **strips inbound `X-Artha-User` and injects its own**; **login rate limit 5/min/IP** (A.2.6) + coarse **50 req/s valve**.
- **Reactive equivalents** of the common-web servlet adapter (the gateway cannot host spring-webmvc — A.2.4): `ErrorWebExceptionHandler` emitting the envelope + a `GlobalFilter` injecting `X-Artha-User` and generating/forwarding the `X-Request-Id` correlation id (A.6.3 / plan §12.4).
- `tools/hash-password/` Argon2id PHC helper (**CD-13** — A.2.7).
- Dockerfile (multi-stage, non-root, wget healthcheck) + compose entry (`127.0.0.1:8080:8080`, `mem_limit: 384m`, `depends_on: redis: service_healthy`).

**Minimal code/config.** Argon2: `Argon2PasswordEncoder(16, 32, 1, 19456, 2)` matching **A.2.3** (m=19456 KiB, t=2, p=1).

**DB changes.** none (sessions live in Redis).

**Build & Run.**
```
./mvnw -pl services/edge-gateway -am verify
./ay.sh up      # gateway now in compose
curl -i -X POST 127.0.0.1:8080/api/v1/auth/login -d 'password=test'
```

**Tests & Verification.**
- Unit: Argon2 verify, header filter, route predicates.
- IT (Testcontainers Redis — A.10): login sets cookie; `GET /auth/session` authenticated; logout invalidates; **6th login attempt in a minute → 429**; unauthenticated `/api/v1/strategies` → 401 envelope.

**Acceptance criteria.**
- **PASS:** login round-trip works against the compose stack; all security headers present; unknown routes return the envelope, never a stack trace.
- **FAIL:** any port beyond 8080/5432 published **under the default profile** (dev-tools/obs loopback publishes are exempt, A.1.1/A.1.2); session cookie missing HttpOnly/SameSite.

**Commit message.** `feat(gateway): edge-gateway with argon2id form login, redis sessions, route table and security headers`
**PR title.** `Phase 5: edge-gateway login + routing skeleton`
**Time estimate.** 90–120 min.
**Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) gateway app + routes + Dockerfile/compose; (b) login/session/CSRF + rate limits; (c) hash-password tool.

---

## Phase 6 — GitHub Actions CI (Java, migrations, gitleaks)

**Objective.** Make CI the mechanical reviewer before more code lands: path-filtered Java build/test, fresh-volume Flyway validation, and a secret scan on every workflow.

**Why this phase is independent.** Everything it gates (Phases 3–5) already exists. Verifiable by pushing a branch and watching checks.

**Deliverables.**
- `.github/workflows/ci-java.yml` — matrix over existing Java modules: Checkstyle/Error Prone → JUnit (unit, **JaCoCo services ≥ 60 % line**) → Testcontainers IT (pinned **TimescaleDB 2.17-pg17 + Redis 7.4-alpine** service containers) → image build (**GHCR push on `main` only**); Maven cache; `concurrency: cancel-in-progress` (A.10).
- `.github/workflows/ci-migrations.yml` — all four Flyway lineages against a fresh pinned TimescaleDB container + `flyway validate`.
- **gitleaks step in every workflow.**
- Branch protection note in README (PRs required, checks green, no direct push — owner clicks this in GitHub settings).

**Minimal code/config.** Path filters: `services/**`, `libs/**`, root `pom.xml` (java); `deploy/flyway/**` (migrations).

**DB changes.** none

**Build & Run.**
```
git push origin feat/ci   # open PR, observe checks
```

**Tests & Verification.**
- PR with a deliberately failing unit test → red check; revert → green.
- **Checksum-drift check is two-step** in `ci-migrations.yml`: migrate with the merge-base's migration files first, then run `flyway validate` with the PR's files against that populated container — a fresh-volume-only run can never detect an edited migration (A.10). Verify by editing an applied migration in a scratch PR → red.

**Acceptance criteria.**
- **PASS:** both workflows green on main; failing test demonstrably blocks; gitleaks runs in every workflow.
- **FAIL:** workflows not path-filtered (frontend changes would rebuild JVM images later).

**Commit message.** `ci: java build/test matrix, fresh-volume flyway validation, gitleaks scan`
**PR title.** `Phase 6: CI pipelines (java, migrations, secrets)`
**Time estimate.** 60–90 min.
**Token size target.** ≤ 15k output tokens.
**If phase too big.** Not applicable.

---

## Phase 7 — market-data-service skeleton + mock tick feed (5 ports)

**Objective.** Create market-data-service as a Spring Modulith with the five Kite ports (A.7.1 / plan §8.1) and a deterministic mock `MarketFeed` publishing normalized ticks onto `ticks.{exchange}.{tradingsymbol}` — the **credential-free substrate every later phase runs on**.

**Why this phase is independent.** Needs Phases 2–4 (+5 for routing, optional). Ticks are observable directly on Redis; no UI or other service required.

**Deliverables.**
- `services/market-data-service/` — Modulith app, modules `kite`, `instruments`, `candles`, `options`, `mockfeed`; **ports**: `SessionGateway`, `MarketFeed`, `QuoteGateway`, `HistoricalCandleGateway`, `InstrumentDumpGateway` (**interfaces only**; live impls stubbed `@Profile("live")` throwing **`NOT_CONFIGURED`**).
- **Mock impls under `@Profile("mock")`:** seeded random-walk `MarketFeed` over the fixture subset — default **1 tick/s/instrument** with a rate knob **`MOCK_TICKS_PER_SEC`** and shape knob **`MOCK_SCENARIO`** (`trend-up | trend-down | chop | gap-open`; **CD-10** — Phase 46's k6 run needs ~500–1,500 ticks/s aggregate); always-valid `SessionGateway`; fixture `InstrumentDumpGateway` (**CD-14** CSV — ~5k-row fixture: indices, top stocks, one NFO expiry ladder).
- **Tick normalizer** (A.7.1): token→`(exchange, tradingsymbol)` map, **`BigDecimal` prices serialized as strings**, IST timestamps, per-instrument monotonic `seq`; **bounded ingress queue (cap 10k, drop-oldest + counter metric)**.
- **Redis publisher** (`ticks.*` channels) + last-tick `HSET`; **`kite:session:status`** key = MOCK/LIVE state ([COMMON §3](ARTHAYANTRA_2_COMMON_REFERENCE.md#3-global-conventions-apply-to-every-phase) canonical name).
- Actuator health/readiness; Micrometer **`ay_ticks_ingested_total`**, **`ay_tick_publish_latency_seconds`**; Modulith **`ApplicationModules.verify()`** test.
- Dockerfile + compose entry (internal 8081, `mem_limit: 640m`, `depends_on` flyway-init completed + redis healthy).

**Minimal code/config.** Profile rule fixing the v1 startup trap: **exactly one impl per port is always bound — `mock` xor `live`**, asserted by a context test for both profiles.

**DB changes.** none yet (instruments table arrives in Phase 9, Stage B).

**Build & Run.**
```
./mvnw -pl services/market-data-service -am verify
./ay.sh up
docker exec ay-redis redis-cli psubscribe 'ticks.*'    # ticks visible ~1/s
```

**Tests & Verification.**
- Unit: normalizer (decimal/IST/seq), queue drop-oldest.
- IT (Testcontainers Redis): mock feed publishes on **correctly named channels**; **same seed ⇒ identical first N ticks** (determinism).
- Modulith verification green; context loads under `mock` and **fails fast under `live`** without secrets.

**Acceptance criteria.**
- **PASS:** `psubscribe 'ticks.*'` shows normalized JSON ticks with **string decimals + `+05:30` timestamps**; deterministic under fixed seed.
- **FAIL:** ticks carry float prices or numeric-token identity; service starts under `live` with no credentials.

**Commit message.** `feat(market-data): modulith skeleton with five kite ports and deterministic mock tick feed`
**PR title.** `Phase 7: market-data-service skeleton + mock feed`
**Time estimate.** 90–120 min.
**Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) skeleton + ports + Dockerfile/compose; (b) mock feed + normalizer + Redis publish.

---

## Phase 8 — Gateway WS bridge (STOMP over native WS) — Stage-A exit gate

**Objective.** Bridge Redis pub/sub to the browser: `/ws` STOMP-over-native-WebSocket endpoint on the gateway with per-symbol subscription mapping and **20 Hz conflated flush** (A.7.2) — completing the plan's Phase-0 acceptance gate.

**Why this phase is independent.** Consumes Phase 7's live mock ticks; verified with a scripted STOMP client — no frontend needed.

**Deliverables.**
- Gateway `/ws` endpoint (**session-authenticated; 401 close otherwise**) with the **STOMP-subset codec (CD-3)**: CONNECT/CONNECTED, SUBSCRIBE/UNSUBSCRIBE, MESSAGE, heartbeats 10s/10s (A.7.3).
- **Subscription map:** `/topic/ticks.{ex}.{ts}` ↔ Redis (P)SUBSCRIBE; `/topic/candles.1m.{ex}.{ts}`, `/topic/signals`, `/topic/jobs/{jobId}`, `/topic/system`, `/topic/options.chain` forms registered (producers may not exist yet — **subscribing is legal, silence is fine**).
- **Conflation:** per-session latest-value map for tick topics flushed at **`GATEWAY_WS_FLUSH_HZ`** (default 20 Hz); **`signals`/`jobs.progress`/`candles.1m.*` never conflated** (A.7.2).
- Metrics: **`ay_ws_sessions`**, **`ay_ws_fanout_latency_seconds`**.
- `e2e/tools/stomp-probe.mjs` + a minimal `e2e/package.json` (`@stomp/stompjs`) — the scripted probe used here and reused by later backend phases (the full Playwright project arrives in Phase 27, Stage C).
- **`PHASE_GATES.md` Stage-A gate checklist** (mirrors the plan §15.2 Phase 0 row — A.17 / Part 3 below).

**Minimal code/config.** Topic→channel mapping is mechanical: `/topic/ticks.NSE.RELIANCE` → `SUBSCRIBE ticks.NSE.RELIANCE`.

**DB changes.** none

**Build & Run.**
```
./mvnw -pl services/edge-gateway -am verify
./ay.sh up
node e2e/tools/stomp-probe.mjs   # tiny @stomp/stompjs script: login, subscribe, print 10 frames
```

**Tests & Verification.**
- IT (Redis container): publish synthetic ticks → subscribed test client receives **only its symbol**; unsubscribe stops delivery; **burst of 1,000 ticks in 1 s yields ≤ ~25 frames (conflation)** with the latest price last.
- Unauthenticated WS upgrade rejected.

**Acceptance criteria.**
- **PASS (Stage-A exit):** clean machine, no credentials → `ay up` green; login works; mock ticks observable end-to-end via the STOMP probe; CI green.
- **FAIL:** firehose behavior (frames for unsubscribed symbols); conflation dropping `signals` frames.

**Commit message.** `feat(gateway): stomp-over-native-ws bridge with per-symbol subscriptions and 20hz conflation`
**PR title.** `Phase 8: gateway WS bridge (Stage A exit)`
**Time estimate.** 90–120 min.
**Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) STOMP codec + auth handshake; (b) Redis mapping + conflation + probe script.

---

# Part 3 — Stage exit gate (plan §15.2 Phase 0 row, as a checklist)

This is the **S5 Friday gate ritual input** for Stage A. At the Phase 8 boundary, copy this checklist into `PHASE_GATES.md` and walk it **against the running mock stack**; an unchecked box extends the stage (§15.6 ritual — A.15). Source: plan §15.2 Phase-0 row (A.17), with the A6 supersession noted.

**Deliverables present (Phases 1–8):**

- [ ] Monorepo layout scaffolded ([COMMON §10.1](ARTHAYANTRA_2_COMMON_REFERENCE.md#101-monorepo-layout)); process docs committed (`README.md`, `PHASE_GATES.md`, `docs/golden-vectors.md`, `docs/remote-access.md`, `docs/dev-setup.md`, `docs/LEGAL.md`, `.github/PULL_REQUEST_TEMPLATE.md`, the 8-file design set under `docs/design/`).
- [ ] Compose has the stateful + infra containers with **`mem_limit` + healthchecks + pinned tags + loopback binds** (timescaledb, redis, flyway-init, db-backup, dev-tools profile, edge-gateway, market-data-service). *(The remaining D7 app containers are stubbed/added in later stages; Stage A delivers the ones that can run.)*
- [ ] **Flyway 11 init job** builds the 3 schemas + 3 roles + the single backtest→marketdata read-only grant from an empty volume (admin lineage first), idempotent on re-up.
- [ ] **GitHub Actions** build/test/GHCR pipeline green (`ci-java.yml`, `ci-migrations.yml`); gitleaks in every workflow; branch protection enabled.
- [ ] **edge-gateway** with Argon2id (m=19456/t=2/p=1) form login + Spring Session in Redis + route table + security headers + 5/min login limit + 50 req/s valve.
- [ ] **Mock Kite feed** (D13 mock profile) publishing deterministic synthetic ticks; gateway WS bridge relays them over STOMP-on-native-WS with 20 Hz conflation.
- [ ] **Review additions present:** `PHASE_GATES.md` + thin pre-build checklist (S5+P4); `docs/dev-setup.md` tier table with corrected ports — Adminer 8085, RedisInsight 5540, no Tier-4 VPS (S6); lightweight-charts attribution record in `docs/LEGAL.md`/README — Apache-2.0 + NOTICE + tradingview.com link, `attributionLogo` on; **private repo, private GHCR by choice** ([A13, 2026-06-12] — replaces the Q5 TV-license verification); Tailscale-first remote-access decision documented in `docs/remote-access.md` (Q3). *(Phase-0 "day-zero rotation of leaked credentials" is **superseded by A6** — fresh Kite key pair, no rotation gate, tripwire dropped.)*

**Acceptance (demo-able):**

- [ ] `docker compose up` (`ay up`) is **green on a clean machine with no Kite credentials**.
- [ ] **Login at `127.0.0.1:8080` works** (Argon2id verify; HttpOnly SameSite=Strict session cookie; `GET /auth/session` authenticated).
- [ ] **CI builds every service image** (the Java modules that exist).
- [ ] **Mock ticks visible on Redis `ticks.*` channels** (`psubscribe 'ticks.*'`), with string decimals + `+05:30` timestamps, deterministic under fixed seed; observable end-to-end via `e2e/tools/stomp-probe.mjs`.
- [ ] Following `docs/dev-setup.md` **Tier 2 verbatim**, a host-run service connects to compose Redis/Postgres in mock mode (`ay up dev-tools` publishes Redis 6379 + internal ports on loopback).

**Stage-end notes.**
- Phase-0 review-addition effort total: **+2.0 d** (S5 0.5 · P4 0.5 · S6 0.5 · Q3 0.5 — Q5's 0.25 d returned per A13 [2026-06-12]; the ~0.1 d `docs/LEGAL.md` attribution record is carried in the A13 chart-surface ledger, COMMON §16.2 Stage E row, not here; decisions §5 ledger).
- Plan FT/PT baseline for Phase 0: **2–3 FT weeks / 7–11 PT weeks** (not compressible to days; research spikes are §15.3/§17.4 filler, not week-1 blockers).
- **Carried into Stage B (parking list seeds):** instruments table + candles hypertable + Kite OAuth/AES-GCM token store + live ticker + options snapshots all land in Stage B (Phases 9–17); the Kite **minute-depth probe** (A3 open item) and the **NSE index-constituents CSV source verification** (open item, before Phase 22) are owner/early-stage to-dos tracked in `PHASE_GATES.md`.









