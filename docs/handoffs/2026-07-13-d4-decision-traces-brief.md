# Brief: d4-decision-traces
Date: 2026-07-13 · Architect: Claude · Builder: Codex
Ledger row: D4 remainder P2-8 (FID audit B12) · Tier: clean (default-OFF, additive; engine-adjacent so the Architect runs adversarial review before merge)
Branch: `feat/d4-decision-traces` (from fresh `origin/main`)
Reserved migration number: **backtest V019 — use EXACTLY this** (`deploy/flyway/backtest/V019__backtest_decision_traces.sql`; V018 is taken)

## Goal (one paragraph)
Backtests currently persist nothing about bars where the strategy evaluated an entry and did
NOT trade — "why did the backtest not trade on date X" is unanswerable from stored data (audit
B12; live trading has `strategy.signal_rejections`, backtests have no twin). Build the
backtest twin as a **per-day rollup**: when a run is submitted with a new optional request flag
`traceDecisions: true`, every entry-decision bar is classified and aggregated per
(session-date, reason), and after the replay the rollup is persisted to a new
`backtest_decision_days` table and served by a typed GET endpoint. Flag absent/false ⇒
byte-identical zero-cost run (nothing collected, nothing written).

## Scope — files in play
- `libs/strategy-engine/.../golden/TickwiseGoldenRunner.java` — add the OPTIONAL decision-listener side-channel (details below)
- `services/backtest-service/.../replay/ReplayEngine.java` — pass-through overload for the listener (nullable)
- `services/backtest-service/.../jobs/BacktestRunRequest.java` — add `Boolean traceDecisions` (nullable)
- `services/backtest-service/.../jobs/JobsService.java` — carry the flag into the job request JSONB (mirror how `stressOverrides` rides)
- `services/backtest-service/.../replay/BacktestRunner.java` — when the flag is on: wire the collector, aggregate, persist after the run
- NEW: `services/backtest-service/.../replay/DecisionTraceCollector.java` (or similar) — the aggregation
- NEW: `services/backtest-service/.../replay/DecisionTraceRepository.java` — insert + read
- NEW: endpoint on the existing results controller (`services/backtest-service/.../replay/ResultsController.java`): `GET /api/v1/backtests/{runId}/decision-traces`
- NEW: `deploy/flyway/backtest/V019__backtest_decision_traces.sql`
- Tests (see verify ladder)
Anything outside this list = stop and write a doubt.

## Design decisions already made (do not relitigate)
1. **Listener side-channel, not re-evaluation.** `TickwiseGoldenRunner` is the live-parity
   engine. Add an optional listener exactly like the existing `IntConsumer onBar` progress
   side-channel (D17b precedent — read how `onBar` is threaded through the `run(...)`
   overloads and mirror it): a new functional interface, e.g.
   `DecisionListener { void onDecision(OffsetDateTime bucketStart, String reason, ScoreBreakdown breakdownOrNull); }`,
   default `null` on every existing overload. All golden/live paths pass null ⇒ the emitted
   event list is untouched and golden vectors stay byte-identical.
2. **Classification (the `reason` vocabulary), emitted at the three entry-evaluation sites**
   (coarse-primary bucket close, btst pre-close, 1m primary — find them where
   `EntryEvaluator.evaluate` is called):
   - `entered` — evaluation fired an entry (event emitted). Count only; breakdown optional (pass it).
   - `session_window` — evaluation wanted entry (`evaluation.get().entry()` true) but
     `gate.entryAllowed(...)` returned false. Pass the breakdown.
   - `gate_fail` vs `composite_below_threshold` — evaluation present but `entry()` false.
     Inspect `EntryEvaluator.Evaluation` / `ScoreBreakdown` to see whether a failed required
     gate is distinguishable from a below-threshold composite; if it is, use both reasons; if
     NOT cleanly distinguishable, use the single reason `not_triggered` and say so in the
     receipt (do not guess at fields).
   - `not_evaluable` — `EntryEvaluator.evaluate` returned empty (warming indicators etc.).
     No breakdown.
   - `position_open` — a primary-bar close where no entry evaluation ran because a position
     was open. Count only, no breakdown. (This disambiguates "no rows for date X" — with the
     flag on, every primary decision bar lands in exactly one bucket.)
   Emit the listener call ONLY when the listener is non-null; zero allocations on the null path.
3. **Per-day aggregation in the collector, NOT per-bar rows.** Key = (sessionDate, reason)
   where sessionDate comes from the existing `EngineSeries.sessionDate(bar)` helper (NEVER
   `bucket::date` — IST/UTC trap). Per key keep: `bars` count, `maxComposite`
   (null-safe; only breakdowns carry composites), and the SAMPLE = the breakdown +
   bucketStart of the max-composite bar seen (for compositeless reasons keep the first bar's
   bucketStart, breakdown null). Memory stays bounded (~days × ≤6 reasons).
4. **V019 schema** (unqualified table names — the lineage runs under `currentSchema=backtest`;
   mirror V013's style for grants/comments):
   ```sql
   CREATE TABLE backtest_decision_days (
     id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     run_id       UUID NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
     session_date DATE NOT NULL,
     reason       TEXT NOT NULL,
     bars         INTEGER NOT NULL,
     max_composite NUMERIC,
     sample_bucket TIMESTAMPTZ,
     sample_breakdown JSONB,
     UNIQUE (run_id, session_date, reason)
   );
   CREATE INDEX idx_bdd_run_date ON backtest_decision_days (run_id, session_date);
   ```
   plus the same GRANT lines V013 uses for its new table. `sample_breakdown` JSON shape =
   the same structure the existing per-trade `contributions` uses if reusable; otherwise a
   simple `{composite, indicators:{alias:{score,weight,activated}}}` — state which in the receipt.
5. **Persistence timing:** collector fills during replay; `BacktestRunner` inserts ALL rows in
   one batch AFTER the run row exists (FK), same transaction pattern as `trades.insertAll`.
   Failure to persist traces must NOT fail the run — catch, log, and continue (traces are
   diagnostics, the run result is the product). Say in the receipt where that catch is.
6. **Endpoint:** `GET /api/v1/backtests/{runId}/decision-traces` returning a TYPED record
   (NEVER `Map<String,Object>` — `MapReturnRatchetTest` will fail the CI shard), shape:
   `{ items: [ { sessionDate, reason, bars, maxComposite, sampleBucket, sampleBreakdown } ] }`
   ordered by sessionDate, reason. `{items:[...]}` envelope is the repo FE convention.
   404 on unknown runId, matching the sibling results endpoints' behavior.
7. **Flag plumbing:** `BacktestRunRequest.traceDecisions` (Boolean, nullable). JobsService
   records it in the job request JSONB (mirror `stressOverrides` handling); BacktestRunner
   reads it from the job request when executing. Optimizer/EVO submissions never set it ⇒
   zero new rows from sweeps.

## Constraints & memory traps (pasted — read even if you read CLAUDE.md)
- **Build full-reactor with `-am` always**; a bare `-pl` embeds stale libs. Use the cached
  Maven and the AV truststore. PowerShell form:
  ```powershell
  $mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
  $env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
  & $mvn -pl services/backtest-service -am test
  ```
- **Tests must be named `*Test` or `*IntegrationTest`** — `*IT` classes are SILENTLY skipped.
- **Goldens must stay byte-identical**: `GoldenDeterminismTest` (strategy-engine) and
  `BacktestParityTest` (backtest-service) must pass unchanged. Your listener is null on all
  their paths — if either fails, your change leaked into the event stream: stop and doubt.
- **ITs share one singleton Testcontainers DB with NO cleanup** — unique identifiers per test
  method. Follow `TradeRepositoryIntegrationTest` / `RunRepositoryIntegrationTest` as the
  harness template (raw JDBC via `BacktestIntegrationTestBase`, hang rows off a fresh
  jobId/runId).
- **New `@GetMapping` path = springdoc contract drift.** After the endpoint compiles, check
  `.github/workflows/ci-contracts.yml` for the exact re-capture + TS-regen steps
  (`-Dcontracts.capture=true` capture run, then `npx openapi-typescript@7` into
  `contracts/gen/`); commit the regenerated artifacts. If the workflow's steps differ from
  this sketch, follow the workflow and note it in the receipt.
- **`*.json` is eol=lf** (`.gitattributes`) — don't fight it.
- **IST trap:** in-DB `now()`/`::date` is UTC. Session dates come from
  `EngineSeries.sessionDate(...)` in engine code; in SQL tests filter by explicit `+05:30`
  ISO bounds.
- **PowerShell 5.1:** no `&&` chains, no bash here-strings. Multi-line commit messages via a
  temp file + `git commit -F <file>`.
- Branch from **fresh** `origin/main` (`git fetch origin; git checkout -b feat/d4-decision-traces origin/main`).

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `libs/strategy-engine` module tests green, incl. `GoldenDeterminismTest` (byte-identity proof):
   `& $mvn -pl libs/strategy-engine -am test`
2. `services/backtest-service` full module tests green (includes Testcontainers ITs — Docker
   Desktop is running), incl. `BacktestParityTest` and your new tests:
   `& $mvn -pl services/backtest-service -am test`
3. New tests you must write (minimum):
   - Unit: collector classification — feed the golden fixture strategies/candles through
     `ReplayEngine.replay` (or the runner directly) WITH a listener and assert the per-day
     buckets: an `ema-crossover`-style fixture yields `entered` + `position_open` +
     `not_triggered`(or gate/composite variants) days; a strategy with a session window
     yields `session_window`. Assert bars-sum ≈ primary decision bars.
   - Byte-identity: same fixture run twice, listener null vs non-null → `ReplayResult`
     trades + signals lists EQUAL (the listener never perturbs results).
   - IT: `DecisionTraceRepositoryIntegrationTest` — V019 applied by the harness; insert a
     run + rows; read back; UNIQUE(run_id, session_date, reason) enforced.
   - Endpoint test: 200 with `{items:[...]}` shape + 404 unknown run (mirror the sibling
     results-endpoint tests' style).
4. Contract re-capture + TS regen (see trap above) — `tsc` must stay green if the workflow checks it.
5. `git status` clean except intended files; then:
   `gh pr create --base main --head feat/d4-decision-traces --title "feat(backtest): decision traces — per-day rejected-entry rollup (P2-8/B12)" --body "<what/why/how + test evidence + receipt path>"`
   **Leave the PR OPEN — the Architect merges.**

## Receipt shape (mandatory — write to `docs/handoffs/2026-07-13-d4-decision-traces-receipt.md`)
- Diff summary (files + line counts) and the PR URL
- Real test output lines (`Tests run: …`) for ladder steps 1–3, and what step 4 did
- Claims WITH evidence (file:line / command+output), each labeled
  `computed | sourced | recalled | assumed`
- **Open-doubts** (mandatory; if none, say why none). Specifically address: (a) whether
  gate_fail vs composite_below_threshold was distinguishable, (b) any place the listener
  could observably perturb the run, (c) trace-persistence failure handling.
- End commit messages with: `Co-Authored-By: OpenAI Codex <noreply@openai.com>`

## Stop conditions (write a doubt and halt instead of improvising)
- `GoldenDeterminismTest` or `BacktestParityTest` fails for ANY reason.
- The V019 number is already taken when you branch (someone landed first — do NOT renumber
  on your own).
- You need to touch `.env`, deploy anything, edit an applied migration, or edit
  `docs/superpowers/plans/2026-07-02-remaining-items.md`.
- Two failures of the same approach (two-strikes rule).
