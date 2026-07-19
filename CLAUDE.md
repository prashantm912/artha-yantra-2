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

## Delegation model (owner standing rule 2026-07-10, until Fable 5 is generally available)
Applies to the MAIN session loop only — if you were spawned as a subagent, ignore this section
and just execute your brief. The main loop delegates ALL substantive execution (builds,
investigations, audits) to **Opus 4.8 subagents** (Agent tool, `model: "opus"`; parallel code work
gets `isolation: "worktree"` + rebase-before-push) and itself only orchestrates, audits, fixes,
merges, deploys, and talks to the owner. Rules proven over the first runs (#675–#680):
- The brief must be self-contained: goal, constraints, **relevant memory-trap content pasted in**
  (subagents get this file but never the memory files), and a required receipt shape — diff, test
  output, claims WITH evidence (file:line / SQL+result / log line) **each labeled
  computed / sourced / recalled / assumed** (recalled + load-bearing belongs in open-doubts,
  not the claims list), and a mandatory **open-doubts**
  section (builders' self-flagged doubts have caught real regressions).
- Audit the RECEIPT against the real artifact (read the actual diff, spot-rerun tests, verify
  citations); depth tiered by risk — docs/mechanical = diff read, engine/money/parity =
  verify-ladder rerun. Small audit fixes land directly; big ones go back to the same agent via
  SendMessage.
- The main loop keeps: merge decisions, live deploys + anything touching secrets/.env, ledger and
  memory writes, owner communication, and tiny fixes where delegation overhead exceeds the work.
Detailed playbook + outcome log: memory topic `opus-delegation-standard`.

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
- IT harness: singleton Testcontainers (Timescale 2.18.2-pg17 + redis 7.4), real
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
  GoldenDeterminismTest + BacktestParityTest. **Premium-exit semantics are pinned by a shared
  fixture** (`contracts/fixtures/exit-equivalence.json`, #505): backtest's PremiumExitEvaluator
  and the live bracket chain (PremiumBracketRules + PaperBracketEvaluator) both test against it —
  change exit semantics only by updating the fixture + BOTH suites in one PR. **Paper tick-freshness
  doctrine (#694):** fills reject a stale live tick (>15s, `artha.paper.tick-max-age-seconds`) with
  422 DATA_STALE; settles use the last REAL tick at ANY age (stale = counted+alerted, never refused)
  and refuse only when NO tick was ever seen — "entries need fresh truth (you can always NOT enter),
  exits need the best available truth (you cannot refuse to leave forever)". Never reintroduce an
  `avgEntryPrice` breakeven fallback on a close path.
- **Contract spec drift (springdoc):** `ContractCaptureTest` snapshots `/v3/api-docs`;
  re-capture with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7` →
  `contracts/gen/*.d.ts`. Generic `Map<String,Object>` returns are NOT enumerated, so adding
  response keys does NOT drift the spec; new query params + new `@*Mapping` paths DO.
  ci-contracts warns on gen drift and requires `tsc --strict`. Its breaking gate diffs the
  **MERGE BASE**'s committed spec vs THIS branch's code spec (task_b3b59719 — it used to diff the
  branch against itself, so re-capturing the spec, which we mandate, silently blinded it; both
  sides were the branch and it never saw main). Re-capturing no longer silences it. **It catches
  only removed endpoints / removed response codes / newly-required request params** — NOT any
  schema-shape change: openapi-diff 2.1.7 does not diff `openapi: 3.1.0` schemas (a response field
  retyped string→integer reads "No differences"; the same bytes relabelled 3.0.1 correctly fail),
  and springdoc emits no `required` for record responses, so a removed/renamed OPTIONAL response
  key is "backward compatible" by construction (openapi-diff has no response-property-removed rule,
  only `incompatible.response.required.decreased`). So a renamed response key still reaches live
  un-caught — the `UniverseResolver` wire-read class of break. Intentional breaks: a
  `Contract break: APPROVED (<reason>)` line in the PR body; `hotfix/*` exempt.
- **EVERY new endpoint returns a typed record, never `Map<String,Object>`** — edge-gateway's
  `MapReturnRatchetTest` freezes the Map-returning handler COUNT per service (Maps are invisible
  to the contract gate); a new Map endpoint fails the strategy-gateway CI shard. Cost 2 CI cycles
  on 2026-07-03 (both new endpoints caught). Records also enumerate into the spec — strictly better.
- **Modulith module cycles (strategy-signal):** `notifier` imports `signals` (`SignalEmitted`), so
  signals code must NEVER import notifier — alert via an in-process event record published from
  signals + an `@EventListener` in notifier (`DotInputAlert`/`DotAlertListener` is the template).
  Same class of rule: signals cannot import paper (forced the `PremiumBracketRules` copy).
  `ModularityTest` (per service) catches violations locally only with a full `-am verify`.
- **Mock-stack backtest testing:** candle data is real-time/rolling (accrues from
  boot) — derive a recent covered window, never hardcode dates; every windowed run's
  regime pre-flight needs ~272 daily benchmark sessions, so backfill `NIFTY 50` 1d
  via cache-first GET `/api/v1/market/candles` first. Results/trades/folds/montecarlo
  are keyed by the **run id** (the job's `resultRef`), not the jobId. Submission + the
  worker now **auto-warm** the primary 1m + benchmark (+ contexts) via market-data's
  cache-first GET before the pre-flight, so a fresh window no longer 422s — but
  `libs/market-calendar` covers a FIXED bundled set (currently **2024–2026**; NSE Tuesday +
  BSE Thursday weekly expiries via `MarketCalendar.nse()`/`.bse()`), so a window outside it
  (e.g. 2023 or 2027) 500s with "NSE holiday calendar covers years [...]". A horizon-canary
  test goes red ~45 days before the max covered year ends — the CD-2 yearly-CSV-refresh reminder.
- **Candle sources split by interval:** `CandleReader.read()` serves the `candles_<iv>`
  caggs (5m/15m/1h/1d/1w), **sparse on a fresh boot**; native daily lives in `candles`@1d
  (dense — `readDailyWithWarmup`). The two diverge for 1d (chart overlays hit this).
- **Cache-first candle reads re-fetch only a 10-min trailing tail** (B-4 recency, NARROWED from
  2 h in #490 — the old window made every `to`≈now read re-fetch 2 h of covered bars from Kite all
  session). `GapDetector` marks a bucket missing iff absent OR its END clears now−10m, so the
  in-progress bucket (today's 1d especially) always refreshes. **Candle writes are TWO upserts**
  (#507): fetched history rides `upsertAuthoritativeAll` (REPLACES o/h/l/c/volume/oi — a poisoned
  tick-agg spike is correctable by re-fetch), the GREATEST/LEAST merge is tick-agg-only
  (`BarWriter`), bhavcopy stays DO-NOTHING; BOTH keep the original `source` on a value-identical
  write (provenance stays diagnosable), and the fetch `source` label comes from
  `HistoricalCandleGateway.sourceLabel()`, never the Spring profile. Live watchers:
  `GET /api/v1/market/health/data` (per-token tick/bar divergence + capture freshness),
  `GET /api/v1/signal-rejections/dot-health` (per-dot gate-input liveness),
  `GET /api/v1/market/health/ingest` + the `/data-ops/ingest-health` page (per-source EOD ingest
  coverage over `marketdata.ingest_runs`, #686/#699), and the scheduled canaries (ingest-coverage
  08:45, notifier-health 08:30, paper reconcilers 21:15, PartialBucketCanary every 60s — all IST)
  — check these BEFORE hand-digging a "feed looks dead" / "batch missed" report.
- **3m reads are a read-time 1m→3m rollup** (`CandleRepository.rangeRolledFromOneMinute`, #365): the
  live SignalEngine 3m-primary depends on this rollup. The unused `candles_3m` cagg + its refresh
  policy were DROPPED (V027, #427) — 3m has no materialized view; only the 1m base feeds it.
- **Historical OI is VIRTUAL (read-time derived), never a snapshot backfill:** there are no real
  `options_chain_snapshots` rows before ~2026-06-15 (live capture start). `CandleDerivedChainReader`
  pivots the per-contract `candles` + `expired_contracts` into the StrikePoint shape on the fly
  (bucketed `last(oi)`, `oi_change` = bucket-lag, coverage-gated on `complete`); a `HistoricalOiReader`
  facade (snapshots-first, candle-derived fallback for fully-PAST empties — **LIVE/today never
  derives**) is swapped into `OptionsAnalyticsController`'s one `reader` field so the whole OI-page
  suite + Connecting-Dots + OI-attribution work on history with ZERO new rows/migration (materializing
  ~1.12B snapshot rows would re-OOM the compressed hypertable). Derived rows carry `derived`/`oiDerived`
  provenance; iv/greeks are null (ATM-band IV is recomputed via Black-76, the future-close `spot` proxy
  IS the forward), and **Dow + IV factors degrade to NEUTRAL on history** → the composite rarely reaches
  strong confluence on backtests, so the OI edge reads MUTED on derived history (it's a data-fidelity
  artifact, not a strategy verdict — judge OI-led strategies on FORWARD paper with real captured OI,
  not a weak historical backtest). **Timestamp-key trap (root cause of #214):** an `OffsetDateTime`
  map key SILENTLY misses across data sources with different UTC offsets — the futures-spine bars carry
  `+05:30` but JDBC `time_bucket` returns `+00`, so `map.get(bar.bucket)` missed EVERY lookup and 3
  Connecting-Dots factors (activeStrikeOi/IV/VIX) read NEUTRAL on every history session for months. Key
  cross-source time maps by `.toInstant()`, never the offset-bearing `OffsetDateTime`.
- **SPAN margin = Upstox server-side, NO `.spn` file (F9 source, #510):** `UpstoxMarginClient`
  POSTs a ≤20-leg basket to Upstox `POST /v2/charges/margin` on the login-free analytics token and
  gets `span_margin`/`exposure_margin`/`total_margin` + basket `required_margin`/`final_margin`
  back — Upstox loads the NSCCL file, we never do. `POST /api/v1/market/margin` (typed record,
  fail-soft: `unpriced` reason on any gap, never a 5xx) takes structured legs
  `(exchange, underlying, optionType, expiry, strike, quantity, side, product)`, resolves each to
  the Upstox `instrument_key` via `UpstoxFnoMasterClient.keyFor`, defaults `product=D` (NRML, full
  SPAN — conservative). Bound only when `artha.upstox.analytics.enabled=true` (ObjectProvider → mock
  stack returns `unpriced`). **`quantity` MUST be a lot multiple** or Upstox 400s `UDAPI1104`
  (surfaced as the unpriced reason) — the scalper already emits lot-aligned qty. The marginism
  appliance (#126) stays the offline/backtest fallback. Verified live 2026-07-04 (1-lot short →
  span 337004.85 / final 188604.45). The path is `/v2/charges/margin`; the doc's `/charges/margin`
  404s.
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
- **Cross-source symbol normalization (Kite / Upstox / OpenAlgo):** ONE canonical key — the `Instrument`
  record keyed by **`(exchange, tradingsymbol)` in Kite's grammar**, stored in `marketdata.instruments`
  (PK same). Numeric tokens (Kite `instrument_token`, Upstox `instrument_key`) are source-local session
  handles, **resolved through the master, never stored as identity / never compared across sources**. Each
  source has an edge mapper: Kite tradingsymbol IS canonical (token→key via `InstrumentRegistry`); Upstox
  index/equity via static maps (`NSE_INDEX|Nifty 50` / `NSE_EQ|<ISIN>`) but **F&O key = an opaque numeric
  token NOT derivable from the symbol** → tuple-looked-up `(segment,underlying,type,expiry,strike)` against
  Upstox's public instrument-master JSON (`UpstoxFnoMasterClient`); OpenAlgo requests are **built from the
  structured leg fields, never the Kite symbol** (`OpenAlgoSymbols`, its `DDMMMYY` token differs). Load-bearing
  reconcilers: `normalizeStrike()` (strip trailing zeros so `18000`≡`18000.00`), per-source expiry conversion
  (Kite `DDMMMYY` / Upstox epoch-millis / canonical `LocalDate`), `UnderlyingRef` (`NIFTY`→`NIFTY 50`). **Kite
  is the always-on fallback** (Upstox/OpenAlgo mapping is additive, must never break the feed). ⚠️ **But this
  is DEFAULT-only, NOT a runtime composite (audit EXT-04, 2026-07-18):** the alternate-source flags select
  MUTUALLY-EXCLUSIVE beans (`LiveKiteConfig:241`) — flipping the quote/ticker source to Upstox/OpenAlgo REMOVES
  the Kite bean entirely (`UpstoxQuoteGateway:75` drops unmapped keys with no Kite delegate), so a live miss
  does NOT fall through to Kite. Kite-as-fallback holds only while the source flag stays Kite (the current
  default, W-U4 declined). Composite primary+fallback is unbuilt. Drift caught by
  3 contract canaries (Kite/Upstox/OpenAlgo, CONSUMED-field sentinels). Full map: `docs/symbol-normalization.md`.
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
  all required) else 422 "no tunable parameters in the optimize block". **ci-optimizer / ci-margin are
  SEPARATE path-filtered workflows** (`.github/workflows/ci-optimizer.yml`, trigger `paths:
  services/optimizer-service/**`) — NOT in the default `gh pr checks` rollup; a Python PR's ruff+pytest
  gate shows under `gh run list --workflow ci-optimizer.yml`, so don't read its absence as "skipped".
- **Backtest/optimizer submission identity + precedence (2b):** `strategyId` is the registry **UUID**
  (NOT the slug); omit `strategyVersion` → the optimizer/runner pins the latest published, else latest
  draft. Terminal job status string is `completed`; results are keyed by `resultRef` (the run id), read
  from `backtest_runs` (`total_return`, `trade_count`) + `backtest_trades`. **The optimizer reads
  `optimize.parameters` FROM the YAML but takes `walkForward` + `objective` + `maxTrials` FROM the
  REQUEST** (`service.py`), not the YAML — so OOS fold tuning needs `objective:{metric: oos_fold_mean}`
  + a `walkForward:{train_days,test_days,step_days}` block in the `POST /optimizations/run` body (a
  YAML-only `walk_forward` runs as a plain sweep with empty OOS folds).
- **Scalper three-way instrument decoupling (ADR-0003, #224/#225):** `universe.signal_underlying` /
  `universe.strike_reference` / `universe.underlying` are 3 independent optional instrumentRefs — the
  signal/indicator series, the ATM-strike anchor spot, and the option-execution root — each defaulting
  to the prior so existing configs stay byte-identical. Backtest option legs resolve via
  `expired_contracts.underlying_symbol` (`"NIFTY"`/`"SENSEX"`); `OptionsPremiumReplay.registryUnderlying`
  strips at the first space (`"NIFTY 50"`→`"NIFTY"`), so `universe.underlying` carries the INDEX exchange
  (`NSE` for NIFTY 50, `BSE` for SENSEX) while options resolve to NFO/BFO. The OI-confluence gate index
  defaults to the option-root, overridable via `oi_confluence_gate.index`; it is MUTED on derived history
  (Dow+IV → NEUTRAL) so the niftyoi-vs-sensexoi A/B is a FORWARD-paper discriminator (identical on backtests).
- **3m primary in the tick-wise runner (#228, same parity-safe-additive pattern as above):**
  `TickwiseGoldenRunner.intervalDuration` rolls a 1m stream up to a coarse primary; it had 5m/15m/1h
  only, so EVERY 3m-primary scalper backtest failed at submission (`"rolls up 5m/15m/1h primaries; got
  3m"`). Fixed with an additive `case "3m" -> Duration.ofMinutes(3)` — 3m is a valid aggregate, the
  bucketing is generic (epoch floor mod interval-seconds) and 09:15 IST aligns to a 3m boundary, so
  5m/15m/1h goldens stay byte-identical.
- **Live scalper signal series = the DATED front contract, NOT `NIFTY-FUT-CONT`:** the continuous
  future (NFO `NIFTY-FUT-CONT`) is BACKTEST/replay-only and intentionally stale (max bar = backfill
  end). LIVE, `FuturesUniverseResolver` maps `universe.signal_underlying` → the live dated front/next
  contract (e.g. `NIFTY26JULFUT`, roll re-resolves daily ~08:40 IST). Don't alarm that CONT is stale —
  that's by design ("the continuous-series counterpart is replay territory").
- **Published versions + signals persistence:** `strategy_versions.status='published'` (LOWERCASE —
  `'PUBLISHED'` queries return empty) + the strategy's `published_version_id` pointer; the live engine
  loads `enabled && publishedVersionId != null`. `strategy.signals` holds only FIRING bars (gate pass
  AND composite≥threshold); rejected evals return a breakdown but never a row. `score_breakdown` =
  frozen ScoreBreakdownDto (`composite = Σ(w·s)/Σw`; an optional activates iff score≥optionalMinScore
  AND required-only composite≥threshold−optionalGateMargin). Scalper enrichment rides the
  `scalper_detail` V009 side-channel (option leg + confluence dots + `manual_checks`).
- **In-container `now()`/`::date` is UTC, not IST:** a 02:xx-IST row is the *previous* calendar day in
  the DB (e.g. 02:xx IST on 06-29 stores as `2026-06-28` UTC). Filter `signals.generated_at` / candle
  `bucket` by explicit `+05:30` ISO bounds, never `::date = CURRENT_DATE` (off-by-one across IST midnight).
  **But `+05:30` is for BOUNDS, not DISPLAY:** `AT TIME ZONE '+05:30'` **INVERTS** (POSIX sign convention) — it renders
  14:20 IST as 03:20. Bound literals (`timestamptz '2026-07-17T09:15:00+05:30'`) are correct; to RENDER use
  `AT TIME ZONE 'Asia/Kolkata'`. Cost a live investigation a false path 2026-07-17. Same class on the host:
  **Git Bash ignores `TZ=`** — `TZ=Asia/Kolkata date` prints UTC. Use python `zoneinfo` for wall-clock IST.
- **Rebuild + redeploy ONE service (no `ay` build verb):** build the artifact (`(cd frontend-react &&
  npm run build)` or the service JAR), set `$env:ARTHA_DB_NAME`/`$env:ARTHA_REDIS_DB` to the LIVE
  values (`artha`/`0`, mock `artha_mock`/`1`), then `docker compose -f deploy/docker-compose.yml
  --env-file .env build <svc> && up -d <svc>` — recreates only `<svc>`; unset vars drift the others.
- **A deploy carrying a NEW migration needs flyway-init FORCED:** `up -d <svc>` treats the exited
  `flyway-init` one-shot as satisfied and may NOT re-run it — `up -d --force-recreate flyway-init`
  first, then ALWAYS DB-probe the new object (`to_regclass`/information_schema). A healthy container
  + an "up to date" flyway log do NOT prove the migration applied (a stale checkout deployed
  "healthy" without its migration once, 2026-07-11; only the probe caught it).
- **Thread-dump a stalled JVM service:** `docker exec ay-<svc> sh -c 'kill -3 1'` → dump lands in
  `docker logs` (jstack/jcmd absent in the slim image).

## Frontend (`frontend-react` — React 19 + Vite 6 + Tailwind v4 + shadcn)
The app is `frontend-react` (the Angular `frontend-ui` was removed after the React cutover, PR #104).
Stack: React 19, Vite 6, **Tailwind v4 CSS-first** (`@import 'tailwindcss'` + `@theme inline`, NO
`tailwind.config.js`), Zustand, TanStack Query v5, react-router 7, echarts 5 + lightweight-charts 5,
shadcn/ui (controls/overlays only). 5 swappable themes via `data-theme` on `<html>`; all colour from
per-theme `--ay-*` CSS vars. Mobile target S24 Ultra ~480px. a11y gated by axe + Playwright role/name.
- **Tailwind v4 traps** (each cost a build cycle): `font-size` goes on `<body>` only (on `<html>` it
  rescales every `rem`); type-ramp vars live in a plain `:root`, NOT `@theme` (else the auto-generated
  `text-*` utilities collide with the `@utility text-h1` classes); never leave a `*/` substring inside
  a CSS comment (lightningcss closes the comment early). See [[frontend-revamp-state]].
- **shadcn bridge:** `accent` is the ONE token name that collides — never re-alias `--color-accent` in
  the shadcn `@theme inline` bridge (it clobbers the app's brand accent app-wide; axe/e2e miss it, a
  live old-vs-new screenshot catches it).
- **lightweight-charts** paints blank unless `createChart(el,{autoSize:true})`; **monaco** workers
  don't register → the repo uses a `<textarea>` editor + a plain LCS diff instead.
- **The shared `DataTable` paints TWICE in jsdom** — the desktop `<table>` AND an `md:hidden` card list — so every
  cell's text exists twice and a bare `getByText` on a converted page fails with "multiple elements". **Scope specs to
  the desktop table** (`GraduationPage.spec.tsx:82-86` is the convention). Bit two builders on the #890/wave-2 slices.
  Its `header` is typed **`string`** (`DataTable.tsx:43`): a JSX header (`<Link>`, a chip, a select-all checkbox) cannot
  convert — but a *hidden text* header can and must (`header: 'Actions'` + `headerClassName: 'ay-sr-only'`, the shipped
  pattern at `JobsPage.tsx:707-712`; a builder wrongly called that a blocker and skipped 2 pages over it). `max-h-[68vh]`
  is **hardcoded** (`:289`) with no override prop and no adopter bounding it — a bounded panel is a genuine SKIP (don't
  nest scroll containers: two scrollbars + a sticky header stranded against the inner one). **Adoption is a REFACTOR:**
  prove it by dumping every header + cell `textContent` before/after and diffing (empty or it didn't happen) — "the spec
  passes before and after" proves nothing, since an unconverted table has no accessible name and `within(table)` throws
  for unrelated reasons. Honest claim is *same content/order/formatting*, **not pixel-identical** (adoption intentionally
  normalizes density, drops UPPERCASE headers, adds zebra + sticky, and switches mobile to cards).
- **List endpoints return an `{items:[...]}` envelope** (signals/paper/journal/screener/
  watchlists); only `instruments/search` + `instruments/underlyings` return bare arrays.
- **Section nav** (`MegaMenu.tsx`): each oipulse section is its own top-level menu-bar trigger (the
  old single "All Menu" mega-dropdown was split, PR #177).
- **Verify trio** (PowerShell `Push-Location frontend-react`): `npm run lint` +
  `npm run test:ci` + `npm run build`. After a rebuild HARD-reload (Ctrl+Shift+R) — a stale cached
  chunk renders the old UI. **Deploy gotcha:** the Dockerfile COPYs the HOST-built `dist/`, so
  `npm run build` on the main checkout FIRST, then `docker compose build frontend-react`.

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
- **An `application.yml` `${ENV_NAME}` placeholder must match the compose + `.env` passthrough name
  EXACTLY** — a mismatch silently swallows the `.env` override (falls to the YAML default), with no error
  (e.g. `ARTHA_PAPER_RISK_PER_TRADE_PCT` vs `..._PER_TRADE_RISK_PCT`, #653). Grep both sides when adding a knob.
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
- **`git reset --hard origin/main` wipes uncommitted TRACKED edits** (untracked new files survive) —
  commit/stash a DIFFERENT in-flight feature BEFORE resetting to land another (lost the #2 edits once).
- **`gh pr merge && git pull` RACES the remote** — the pull can complete before the squash-merge
  lands, silently leaving main one commit stale; verify `git log origin/main -1` equals the PR's
  mergeCommit BEFORE building/deploying. And **never pipe a git command whose failure must stop a
  chain** (`git rebase 2>&1 | tail` exits with tail's 0 — a conflicted rebase then push ships a
  mid-rebase branch); check `git status -sb` for `## HEAD (no branch)` after any scripted rebase.
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
- **The `e2e` job's two former 2-core flakes are FIXED (#903, 2026-07-18)** — `tests/signals.spec.ts` +
  `tests/ws-reconnect.spec.ts` intermittently timed out on the cold-stack `/signals` table-settle for two
  reasons: while `GET /api/v1/signals` was pending the page showed a `qs-loading` skeleton the locator
  didn't match, and a stale `tr[role="button"]` locator matched NO real row (rows became plain `<tr>` in
  `<tbody>` after the M23 audit). Fixed by a `global-setup` readiness gate (warm `GET /api/v1/signals` to
  200, authenticated + best-effort, before any test), correct `table tbody tr` locators, and CI retries
  1→2. **If `e2e` reds now, INVESTIGATE — do not reflexively admin-merge it as a "known flake".** (Proven
  green 4× consecutively at merge.)
- **`build-images` is skipped on `pull_request` (#903)** — on a PR it only VALIDATED that the Dockerfiles
  build (the push is main-only), yet `needs: build-test` made it wait out the ~8 m market-data shard first;
  ~2.5 m of serial tail off every PR. The Dockerfile build still runs on the main-push (pre-deploy); it is
  NOT a required check. **The ~8 m `build-test (market-data)` shard is now the PR floor** — a source-level
  module split (breaks the per-module JaCoCo BUNDLE gate as one shard) or a larger runner is the only way
  under it, both owner decisions.

## Where things live
- `services/` services · `libs/` shared libs · `deploy/` compose + flyway · `e2e/`
  Playwright · `contracts/` OpenAPI/schema · `docs/design/` design authority.
- `docs/superpowers/plans/` = active forward-work plans (NOT frozen design). The
  OpenAlgo-ecosystem + React-migration + strategy re-platform authority is
  `docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md` —
  read §17 (Errata) + §18 (Gap Addendum) FIRST; they override §1–§16 on conflict.
- `.claude/skills/` = executable runbooks. **Start every non-trivial task with
  `fable-method`** (decompose / verify / decide-next), then the matching routine skill —
  `ship-a-change` (single-session), `delegated-ship` (Opus-builder pipeline for queue
  items / autonomous runs — the delegation model's executable form), `build-service`,
  `adversarial-review`, `swing-backtest`, `scalper-backtest`, `live-verify`, `arm-flag`,
  `daily-ops`, `session-analysis`, `run-artha-yantra`, `mock-walk`, `new-migration`,
  `research` (no-code spike → findings + BUILD/DEFER verdict), `write-tests` (author tests in
  our harness — naming/Testcontainers/parity/seam-ladder/coverage-debt), `hotfix` (live-incident
  fast-lane: snapshot-first, minimal fix, admin-merge, deploy + canary), `comprehensive-audit`
  (owner-triggered 360° platform audit — tiered sharded Codex-Sol convergence → one dual-signed
  doc in `docs/audits/`) — instead of improvising inline.
- **Codex skill suite** (`.claude/skills/codex-*`, shared harness `.claude/skills/codex/`) =
  the skill-based, templated form of the codex-builder-lane — persistent-thread Codex sessions
  instead of on-the-fly `codex exec` strings: `codex-build` (delegate a build in a worktree,
  `--bypass` — **luna DRAFTS fast/cheap → sol REVIEWS+FIXES on a fresh thread → then Opus cross-vendor
  review**, three perspectives, receipt contract baked in; **a >~4-checkbox plan uses BATCHED mode** —
  delegate risk-sized batches on one thread, review each batch's git-index delta, feed fixes forward as
  binding `--notes`, cross-vendor + audit run ONCE at the end; same analog in `delegated-ship` for Opus
  builders), `codex-code-review` + `codex-plan-review`
  (threaded read-only review against `.claude/skills/codex/checklist.md` = our invariants,
  `APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK` convergence), `codex-ask` (advisory second
  opinion, no gate). Model/effort in one file (`codex/scripts/_common.sh`); state is
  per-target + gitignored. Codex never merges/deploys — Architect keeps that. Two builder
  modes (D1): the `docs/handoffs/` brief lane = SHIP mode (Codex commits + opens the PR, per
  AGENTS.md); `codex-build` = EDIT-ONLY mode (Codex edits the tree, Architect commits —
  AGENTS.md carries the matching exception). **Model routing + availability fallback:
  `.claude/skills/codex/ROUTING.md`** — the harness auto-retries the codex chain on
  at-capacity errors; codex-down → Opus subagent per the table, same receipt contract.
  **Review router (ROUTING.md): the reviewer is the opposite vendor of the builder** (normal path;
  during an outage fall back to same-vendor and record the loss) — Codex-built → `claude-review`
  (Opus subagent); Claude/Opus-built → `codex-code-review` (Codex); both judge the same
  `checklist.md`. Plan review is already cross-vendor (Claude writes, `codex-plan-review` = Codex).
  Canonical order: testing gate → cross-vendor review → Architect audit (final gate) → tiered promotion.
