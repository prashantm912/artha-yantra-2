# CLAUDE.md

Single-owner algorithmic-trading research platform (multi-module Maven + a Python
optimizer, Dockerized, loopback-only gateway). Before non-trivial work read
`README.md`, `PHASE_GATES.md` (current phase + parking list), and the frozen design
set under `docs/design/` (COMMON_REFERENCE + stage files A–G — the design authority).

## Working principles
Behavioral guardrails (adapted from [Karpathy's coding guidelines](https://github.com/multica-ai/andrej-karpathy-skills))
to cut the common LLM coding mistakes. They bias toward caution over speed — use judgment on trivial tasks.

1. **Think before coding.** State assumptions explicitly; if uncertain, ask. If multiple
   interpretations exist, surface them — don't pick one silently. If a simpler approach exists,
   say so and push back when warranted. If something is unclear, stop and name what's confusing.
2. **Simplicity first.** The minimum code that solves the problem, nothing speculative — no
   unrequested features, no abstractions for single-use code, no error handling for impossible
   cases. If 200 lines could be 50, rewrite it. Ask: "would a senior engineer call this
   overcomplicated?"
3. **Surgical changes.** Touch only what the request needs — every changed line should trace
   to it. Don't refactor working code or "improve" adjacent formatting; match existing style
   even if you'd do it differently. Remove orphans *your* change created, but leave pre-existing
   dead code (mention it, don't delete it).
4. **Goal-driven execution.** Turn tasks into verifiable goals ("fix the bug" → "write a test
   that reproduces it, then make it pass") and loop until they pass. For multi-step work, state
   a brief plan with a verify check per step.

## Build & test
- **Integration tests must be named `*IntegrationTest` or `*Test`** — there is **no
  failsafe** plugin configured; `*IT` classes are silently skipped (never run).
- **Build services with the full reactor + `-am`**
  (`-pl services/<svc> -am package -DskipTests`), never a bare `-pl` on a leaf lib —
  a `-pl` install skips parent POMs and nested lib submodules
  (`libs/common-web/servlet`, `libs/black76-math`), so the compose fat JAR silently
  embeds a stale lib.
- **CI `build-test` is sharded per-service** (`.github/workflows/ci-java.yml`): a 3-leg
  matrix (`market-data` / `backtest` / `strategy-gateway` = strategy-signal + edge-gateway),
  each runs `mvnw -pl <svc> -am verify` on its own runner (Testcontainers ITs are the
  2-core bottleneck; serial reactor was ~23m, sharded ~5m). Safe because `jacoco-check`
  binds PER MODULE, not an aggregate root goal. **Adding a new service?** Add a matrix
  shard or its tests NEVER run in CI. Libs ride upstream via `-am` (covered in ≥1 shard).
- IT harness: singleton Testcontainers (Timescale 2.17.2-pg17 + redis 7.4), real
  Flyway lineages, `@DynamicPropertySource` for `currentSchema`. Services connect to
  Postgres as `artha` (D10 single-writer by convention); per-schema roles like
  `ay_backtest` are read-only, asserted via SET ROLE grant tests.
- **ITs share the singleton DB with NO per-method cleanup** — each test method needs a
  unique slug+name; `RegistryService.create` 409s on a duplicate slug OR name. State
  persists across methods *and* across surefire reruns.
- JaCoCo gate ≥ 60% line on services; Modulith `verify` runs in CI.
- **Extend engine records parity-safely:** golden vectors compare byte-string (signals)
  + record-equality (trades); `GoldenSignalsJson.write()` is FROZEN, so new
  `SignalEvent`/`Trade` fields ride as a NON-serialized side-channel — golden stays
  byte-identical and parity holds *iff* both deterministic replays compute the same value
  (compute at entry, e.g. `ExitEvaluator.entryLevels`, never per-run random). Verify with
  GoldenDeterminismTest + BacktestParityTest.
- **Contract spec drift (springdoc):** `ContractCaptureTest` snapshots `/v3/api-docs`;
  re-capture with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7` →
  `contracts/gen/*.d.ts`. Generic `Map<String,Object>` returns are NOT enumerated, so adding
  response keys does NOT drift the spec; new query params + new `@*Mapping` paths DO.
  ci-contracts fails on BREAKING spec diffs, warns on gen drift, requires `tsc --strict`.
- **Mock-stack backtest testing:** candle data is real-time/rolling (accrues from
  boot) — derive a recent covered window, never hardcode dates; every windowed run's
  regime pre-flight needs ~272 daily benchmark sessions, so backfill `NIFTY 50` 1d
  via cache-first GET `/api/v1/market/candles` first. Results/trades/folds/montecarlo
  are keyed by the **run id** (the job's `resultRef`), not the jobId. Submission + the
  worker now **auto-warm** the primary 1m + benchmark (+ contexts) via market-data's
  cache-first GET before the pre-flight, so a fresh window no longer 422s — but
  `libs/market-calendar` covers only the CURRENT year, so a window outside it (2024/2025)
  500s with "NSE holiday calendar covers years [...]".
- **Candle sources split by interval:** `CandleReader.read()` serves the `candles_<iv>`
  caggs (5m/15m/1h/1d/1w), **sparse on a fresh boot**; native daily lives in `candles`@1d
  (dense — `readDailyWithWarmup`). The two diverge for 1d (chart overlays hit this).
- **Kite REST → full-mirror DTOs (`kite/wire/`):** one record per endpoint (quote / historical /
  session / profile / instrument-CSV) mirrors **every** documented Kite field, each
  `@JsonIgnoreProperties(ignoreUnknown=true)` so a field Kite ADDS can never crash the live feed.
  Gateways `.body(KiteXxx.class)` then map to the domain port records (`Quote`, `Candle`,
  `InstrumentRecord`, `TokenSession`); DTOs stay domain-free. **NEVER** enable global
  `FAIL_ON_UNKNOWN_PROPERTIES` on the live mapper (couples the live path to Kite's exact shape).
  Drift (rename/remove/retype) is caught OFF the critical path by the daily `ContractCanary`
  (raw-JSON vs `kite-contract-manifest.json` — sentinels for CONSUMED fields, not a full mirror) +
  `KiteWireContractTest`. The WS ticker uses the javakiteconnect SDK; REST is hand-rolled
  `RestClient` because the SDK pins `Routes._rootUrl` (no setter) → unstubbable by WireMock. EVERY
  new Kite call follows this — see `kite/wire/package-info.java`.
- **Run the Playwright e2e vs a running mock stack:** `cd e2e &&
  E2E_OWNER_PASSWORD=<your .env owner pw> npx playwright test` — global-setup reuses a
  healthy stack and won't overwrite an existing `.env`; the helper password defaults to
  `e2e-owner-password`, so override it to match your hash.
- **Drive the gateway API from PowerShell** (live/mock verification): `Invoke-WebRequest
  -UseBasicParsing` (PS5.1's IE engine prompts otherwise); POST `/api/v1/auth/login`
  `{"password":...}`, then a GET to seed the `XSRF-TOKEN` cookie, echoed as the
  `X-XSRF-TOKEN` header on mutating calls. In-container SQL: DB is `artha`/`artha_mock`
  (not `arthayantra`).
- **optimizer-service is Python (FastAPI), not Java** — `/api/v1/optimizations/*` lives there
  (backtest-service owns `/api/v1/backtests/*`). Tests: `(cd services/optimizer-service && python -m
  pytest tests/ -q)` + `python -m ruff check app tests` (Python 3.14 global, no venv). A sweep needs
  the strategy to carry a `backtest.optimize` block (`method`+`max_trials`+`objective`+`parameters`,
  all required) else 422 "no tunable parameters".
- **Rebuild + redeploy ONE service (no `ay` build verb):** build the artifact (`(cd frontend-ui &&
  npm run build)` or the service JAR), set `$env:ARTHA_DB_NAME`/`$env:ARTHA_REDIS_DB` to the LIVE
  values (`artha`/`0`, mock `artha_mock`/`1`), then `docker compose -f deploy/docker-compose.yml
  --env-file .env build <svc> && up -d <svc>` — recreates only `<svc>`; unset vars drift the others.
- **Thread-dump a stalled JVM service:** `docker exec ay-<svc> sh -c 'kill -3 1'` → dump lands in
  `docker logs` (jstack/jcmd absent in the slim image).

## Frontend (Angular 21 zoneless + PrimeNG 21)
- **Zoneless (D1) breaks several libs** — verify in a prod build, not just dev:
  - PrimeNG 21 `[virtualScroll]` collapses its viewport → **renders 0 rows**; use a plain
    `[scrollable]` `p-table` with `scrollHeight`, no virtualization.
  - `lightweight-charts` paints blank unless `createChart(el,{autoSize:true})` (the
    afterNextRender width/height measure misses first paint).
  - `monaco-editor`/`monaco-yaml` workers fail to register → editor/diff blank; the repo
    uses a `<textarea>` editor + a plain LCS diff (`monaco-diff.ts`) instead.
- **PrimeNG `darkModeSelector` must be a plain class** (`.ay-dark`) — a `:root`-anchored selector
  (`:root:not(.ay-light)`) collides with @primeuix's `:root,:host` colour-scheme wrapper, emits a
  dead `& :root` rule, and the WHOLE app renders the LIGHT PrimeNG scheme on the dark shell (axe +
  e2e pass it). `SessionStore.applyTheme` toggles `.ay-dark`/`.ay-light` on `<html>`; echarts is
  themed ONLY via the shared `ay-echart` wrapper (transparent bg). After a rebuild HARD-reload — a
  stale cached chunk renders the old UI/white charts.
- **PrimeNG 21 API:** `p-autocomplete` uses `optionLabel`, not `field` (a `field` binding
  silently renders `[object Object]`).
- **PrimeNG 21 does NOT `aria-hidden` button icon spans** — bundling `primeicons.css`
  (angular.json `styles`) makes `icon="pi ..."` glyphs render, but the `::before` PUA glyph
  then leaks into every icon+label button's *accessible name* (breaks axe + Playwright
  `getByRole({name,exact:true})`; an icon-only button with `ariaLabel` is immune). Stamp it
  globally: `providePrimeNG({pt:{button:{icon:{'aria-hidden':'true'}}}})`. All app `pi-*`
  icons ride `p-button`; other components use built-in SVG icons (no `::before` text, no leak).
- **List endpoints return an `{items:[...]}` envelope** (signals/paper/journal/screener/
  watchlists); only `instruments/search` + `instruments/underlyings` return bare arrays.
- **Verify trio** (PowerShell `Push-Location frontend-ui`): `npm run lint` +
  `npm run test:ci` + `npm run build`.

## Database / migrations
- **Applied Flyway migrations are checksum-locked** in the dev stack and CI — editing
  an applied migration (even a comment) fails `flyway validate` / flyway-init.
  Corrections go in a **new suffix-versioned migration**, never an in-place edit.
- `ay reset-db` drops volumes and rebuilds all four schema lineages from empty.

## Docker / compose
- **Never invoke `docker compose` directly without `--env-file .env`** — compose
  resolves `.env` relative to `deploy/` and silently blanks vars (e.g. the owner
  password hash → gateway 401). Use the **`ay` / `ay.ps1` CLI**, which always passes
  it. Project-scoped compose only — **never `docker kill`**.
- Mock vs live is `SPRING_PROFILES_ACTIVE` in `.env`, orthogonal to compose profiles;
  mock needs zero secrets. PHC password hashes in `.env` need every `$` escaped `$$`.
- **Mock and live use SEPARATE databases + Redis logical DBs** — live → `artha`/db0,
  mock → `artha_mock`/db1 — derived from the profile by `ay.ps1` (exports
  `ARTHA_DB_NAME`/`ARTHA_REDIS_DB` into compose env); the `db-create` one-shot makes both,
  flyway-init migrates only the active `${ARTHA_DB_NAME}`. ALWAYS switch profiles via `ay`
  (raw compose leaves the vars unset → mock writes to `artha`); `ay reset-db` wipes BOTH
  (one shared volume).
- **Image build context differs per service** — `market-data-service` and
  `optimizer-service` Dockerfiles COPY repo-root paths (`deploy/dev-certs/`,
  `services/*/target/`) so they build with **repo-root context + `-f <dockerfile>`**
  (compose `context: ..`); edge-gateway/strategy-signal/backtest use a service-dir
  context. Keep CI image-build context in lockstep with compose. `deploy/dev-certs/`
  holds the AV CA (keytool/pip trust it); empty in CI/prod so the layer is a no-op.

## Git & line endings
- `.gitattributes` pins **`*.json eol=lf`** — byte-identical schema/golden-vector
  tests fail if JSON checks out CRLF on Windows. After adding an eol rule,
  `git add --renormalize`.
- Trunk-based: short-lived `feat/|fix/|chore/|docs/` branches, **Conventional Commits**
  (scope = service/lib name), **squash-merge only**, never push to `main`. A stage =
  one branch, one commit per phase, single final PR.
- The **Bash tool is bash, not PowerShell** — PS here-strings (`@'…'@`) are taken
  literally and corrupt commit subjects; pass multi-line commit messages via
  `git commit -F -` with a heredoc.
- The **`guard-paths.py` PreToolUse hook resolves its path relative to the Bash cwd** —
  a persisted `cd <subdir>` makes every later Edit/Write fail (`can't open
  .../<subdir>/tools/claude/guard-paths.py`). Keep the Bash cwd at repo root, or subshell.
- CI runs on a **fresh compose stack + 2-core runner** — code green locally can still
  fail several CI iterations (cold start, constrained cores). Gate e2e readiness on
  container healthchecks, not gateway HTTP (a 401 is the gateway auth filter, not
  upstream readiness).

## Where things live
- `services/` services · `libs/` shared libs · `deploy/` compose + flyway · `e2e/`
  Playwright · `contracts/` OpenAPI/schema · `docs/design/` design authority.
- `docs/superpowers/plans/` = active forward-work plans (NOT frozen design). The
  OpenAlgo-ecosystem + React-migration + strategy re-platform authority is
  `docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md` —
  read §17 (Errata) + §18 (Gap Addendum) FIRST; they override §1–§16 on conflict.
