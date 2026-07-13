# Receipt: d4-decision-traces

Date: 2026-07-13

Branch: `feat/d4-decision-traces` from `origin/main@ac8b11b49f1ef8feef811162291d67d83dddcc6c`

PR: https://github.com/prashantm912/artha-yantra-2/pull/807 (OPEN)

## Diff summary

The implementation adds an opt-in engine decision side-channel, bounded daily aggregation, V019 persistence, a typed read endpoint, request/job plumbing, tests, and regenerated backtest contracts.

| File | + | - |
| --- | ---: | ---: |
| `contracts/backtest-service.openapi.json` | 77 | 0 |
| `contracts/gen/backtest-service.d.ts` | 62 | 0 |
| `deploy/flyway/backtest/V019__backtest_decision_traces.sql` | 17 | 0 |
| `docs/handoffs/2026-07-13-d4-decision-traces-receipt.md` | 121 | 0 |
| `libs/strategy-engine/src/main/java/in/arthayantra/strategyengine/golden/TickwiseGoldenRunner.java` | 96 | 3 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/jobs/BacktestRunRequest.java` | 2 | 1 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/jobs/JobsService.java` | 3 | 0 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/BacktestRunner.java` | 21 | 2 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/ReplayEngine.java` | 27 | 1 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/ResultsController.java` | 19 | 1 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/DecisionTraceCollector.java` | 99 | 0 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/DecisionTraceRepository.java` | 102 | 0 |
| `services/backtest-service/src/test/java/in/arthayantra/backtest/jobs/QueueCapIntegrationTest.java` | 1 | 1 |
| `services/backtest-service/src/test/java/in/arthayantra/backtest/replay/BacktestReplayIntegrationTest.java` | 56 | 0 |
| `services/backtest-service/src/test/java/in/arthayantra/backtest/replay/DecisionTraceCollectorTest.java` | 116 | 0 |
| `services/backtest-service/src/test/java/in/arthayantra/backtest/replay/DecisionTraceRepositoryIntegrationTest.java` | 109 | 0 |

The pre-existing untracked `.claude/settings.local.json` was not read, edited, staged, or included.

## Verification evidence

### 1. Strategy-engine full reactor

Command:

```text
& $mvn -pl libs/strategy-engine -am test
```

Real output:

```text
Tests run: 162, Failures: 0, Errors: 0, Skipped: 0
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.726 s -- in in.arthayantra.strategyengine.golden.GoldenDeterminismTest
strategy-engine .................................... SUCCESS [  5.739 s]
BUILD SUCCESS
```

### 2. Backtest-service full reactor

Command:

```text
& $mvn -pl services/backtest-service -am test
```

Real output:

```text
Tests run: 339, Failures: 0, Errors: 0, Skipped: 0
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.104 s -- in in.arthayantra.backtest.replay.BacktestParityTest
backtest-service ................................... SUCCESS [01:35 min]
BUILD SUCCESS
```

### 3. New trace coverage

The following lines are from the full ladder run's Surefire reports:

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.156 s -- in in.arthayantra.backtest.replay.DecisionTraceCollectorTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.163 s -- in in.arthayantra.backtest.replay.DecisionTraceRepositoryIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.814 s -- in in.arthayantra.backtest.replay.BacktestReplayIntegrationTest
```

The collector test pins all-primary-bar classification, `entered`, `position_open`, `not_evaluable`, gate/composite rejection, `session_window`, and equality of traced versus untraced signals and trades. The repository IT pins V019 application, ordered reads, unknown-run behavior, and the unique key. The replay IT pins request flag persistence plus endpoint 200 shape and unknown-run 404.

### 4. Contract capture and TypeScript generation

The workflow in `.github/workflows/ci-contracts.yml` runs `ContractCaptureTest`, regenerates with `openapi-typescript@7`, and compiles generated declarations under TypeScript 5.9 strict mode. This branch ran the backtest-only equivalent because it is the only changed API contract:

```text
& $mvn -pl services/backtest-service -am test -Dtest=ContractCaptureTest -Dsurefire.failIfNoSpecifiedTests=false -Dcontracts.capture=true
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 21.02 s -- in in.arthayantra.backtest.ContractCaptureTest
BUILD SUCCESS

openapi-typescript 7.13.0
contracts/backtest-service.openapi.json -> contracts/gen/backtest-service.d.ts [62.1ms]

TypeScript 5.9.3: tsc --strict --noEmit --skipLibCheck false contracts/gen/*.d.ts
Exit code: 0
```

The ordinary PowerShell `npx` shim was blocked by local execution policy, and the `.cmd` wrapper then timed out while resolving. The successful generation used the already-cached `openapi-typescript` 7.13.0 executable; no generated-content edits were made by hand.

`git diff --check` exited 0. Maven checkstyle reported zero violations in the affected reactors.

## Claims and evidence

- **computed** — Existing runner overloads pass a null listener, while the new interface and classification calls are guarded by `decisionListener != null`: `TickwiseGoldenRunner.java:47, 205-233, 278-305, 326-370`. Evidence: 9/9 `GoldenDeterminismTest`, 9/9 `BacktestParityTest`, and the traced/untraced equality assertion at `DecisionTraceCollectorTest.java:32-40`.
- **computed** — Entry decisions split into `entered`, `session_window`, `gate_fail`, `composite_below_threshold`, `not_evaluable`, and `position_open`; session dates are taken from `EngineSeries.sessionDate(decisionBar)`: `TickwiseGoldenRunner.java:205-233, 278-305, 326-370`.
- **computed** — Aggregation is bounded by `(sessionDate, reason)`, ordered, counts bars, retains the maximum composite sample, and keeps the first bucket for compositeless reasons: `DecisionTraceCollector.java:18-61, 85-98`.
- **computed** — `sample_breakdown` uses the brief's permitted simple shape `{composite, indicators:{alias:{score,weight,activated}}}` rather than reusing per-trade contributions: `DecisionTraceCollector.java:64-76`.
- **sourced** — V019 uses the reserved filename and the exact additive table/index shape from the brief, plus DML and identity-sequence grants: `deploy/flyway/backtest/V019__backtest_decision_traces.sql:1-17`. The repository IT confirms Flyway applied it.
- **computed** — Trace rows are inserted in one JDBC batch and returned ordered by `session_date, reason`; run existence distinguishes `{items:[]}` from 404: `DecisionTraceRepository.java:29-70`, `ResultsController.java:67-79`.
- **computed** — Only explicit `traceDecisions: true` is pinned into job JSON and standard replay allocates the collector only when enabled: `JobsService.java:140-142`, `BacktestRunner.java:253-256`.
- **computed** — Trace persistence happens after the run and trade rows exist, and a runtime persistence failure is logged and swallowed: `BacktestRunner.java:429-435`.
- **sourced** — The captured OpenAPI adds the typed decision-traces path and schema, and the regenerated frontend declaration contains the matching operation: `contracts/backtest-service.openapi.json:644-680,1438-1474`; `contracts/gen/backtest-service.d.ts:215-230,482-497,1118-1150`.
- **assumed** — The simple JSON breakdown is sufficient for diagnostic consumers because the brief explicitly permits it when per-trade contributions are not reused; no frontend consumer was in this builder brief.

## Open doubts

1. **Gate split:** `gate_fail` versus `composite_below_threshold` is cleanly distinguishable. `ScoreBreakdown.gate().passed()` is the direct required-gate result, so a non-entry with a failed gate maps to `gate_fail`; a non-entry with a passed gate maps to `composite_below_threshold`. This is not inferred from score magnitude.
2. **Listener perturbation:** Existing overloads supply null and therefore execute no callback or collector allocation. The only deliberate observable behavior on an opted-in run is synchronous collector mutation; a throwing third-party listener could abort an engine run because the interface does not swallow listener exceptions, but the sole production listener in this change does not throw during collection. Golden and backtest parity plus explicit traced/untraced equality are green.
3. **Persistence failure handling:** `BacktestRunner.java:430-435` wraps only `decisionTraces.insertAll(...)` in `catch (RuntimeException)`, logs the run ID and exception, and continues. Run/trade persistence remains outside that catch because those are the product, not diagnostics. A failed JDBC batch could theoretically leave partial diagnostic rows depending on driver transaction behavior; the run still succeeds by design.
4. **Specialized options-premium replay:** `OptionsPremiumReplay` invokes `TickwiseGoldenRunner` directly on its specialized path. That file was outside the brief's explicit files-in-play list, whose rule says touching anything outside the list requires stopping and recording a doubt. Consequently this branch does not wire decision collection through the options-premium path; a traced options-premium run returns a known run with `{items:[]}`. The Architect should decide whether a follow-up brief should add that pass-through or explicitly document standard candle replay as the initial supported scope.
5. **Architect review:** The brief marks the change clean/default-off but engine-adjacent and assigns adversarial review to the Architect before merge. The builder did not substitute its own review for that required handoff.
