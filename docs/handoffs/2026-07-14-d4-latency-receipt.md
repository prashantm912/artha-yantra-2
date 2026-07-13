# Receipt: d4-latency (P2-5 latency instrumentation)

Date: 2026-07-14
Branch: `feat/d4-latency`
Base: `origin/main` at `f60d6d3f2387b469eca8f2dca9204a73bdaf6ac9`
Tier: **PARITY-SENSITIVE / HOLD** — Architect review and owner presentation remain required.
PR URL: https://github.com/prashantm912/artha-yantra-2/pull/829

## Outcome

Backend-only latency instrumentation is implemented. Live signal rows receive `emitted_at` and
`emit_latency_ms`; signal-linked paper fills receive `tick_to_fill_ms`; Micrometer publishes p50/p95
for `ay_signal_bar_to_emit_seconds` and `ay_signal_to_fill_seconds`. No REST endpoint was added.

The parity firewall held: `libs/strategy-engine/**` is untouched (0 files / 0 lines), both frozen
parity suites passed 9/9, and no golden vector was edited. The tree object for
`libs/strategy-engine` is `6b2d18677a585df71d790891fb4750e58a2b6f68` at both `origin/main` and `HEAD`.

## Diff summary

Line counts are `added / deleted` against `origin/main` before this receipt's final PR-URL update.

| File | Added | Deleted |
|---|---:|---:|
| `deploy/flyway/strategy/V041__latency_stamps.sql` | 8 | 0 |
| `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/signals/SignalRepository.java` | 13 | 0 |
| `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/signals/SignalEngine.java` | 59 | 7 |
| `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/paper/PaperService.java` | 4 | 2 |
| `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/paper/PaperOrderRepository.java` | 56 | 11 |
| `services/strategy-signal-service/src/test/java/in/arthayantra/strategysignal/signals/SignalEngineIntegrationTest.java` | 27 | 0 |
| `services/strategy-signal-service/src/test/java/in/arthayantra/strategysignal/signals/SignalEngineLatencyTest.java` | 139 | 0 |
| `services/strategy-signal-service/src/test/java/in/arthayantra/strategysignal/paper/PaperLedgerIntegrationTest.java` | 139 | 3 |
| `docs/handoffs/2026-07-14-d4-latency-receipt.md` | 231 | 0 |
| **Implementation/test subtotal (excluding receipt)** | **445** | **23** |
| **`libs/strategy-engine/**`** | **0** | **0** |

## What changed

- V041 adds three nullable, live-only columns without a new grant or any backfill.
- The signals module queues the causal Redis receipt time with each bar, scopes it to evaluation,
  stamps the inserted live signal after commit, and records its timer. Scheduled/no-receipt emissions
  still get `emitted_at` but leave `emit_latency_ms` null.
- Signal latency persistence and meter recording are fail-soft: neither telemetry failure can strand
  a committed ENTRY before publish nor a committed EXIT before `SignalExited`.
- The paper module reads the linked signal's persisted `generated_at`, computes the exact difference
  between PostgreSQL's persisted `filled_at=now()` and that anchor, and writes it in the same INSERT.
  Its timer sample is registered only after transaction commit and is fail-soft.
- Integration/unit tests cover live stamps, unchanged `generated_at`, exact persisted paper arithmetic,
  p50/p95 publication, no-receipt behavior, causal receipt scoping, rollback suppression, and
  persistence/meter failure isolation.

## Verification evidence

All commands used the brief's extracted Maven 3.9.16 binary directly, Java 21, Windows ROOT trust
store, full reactor `-am`, and offline mode. No wrapper output was piped.

### 1. Package

Command:

```text
mvn.cmd -pl services/strategy-signal-service -am -q -DskipTests package -o
```

Output:

```text
PACKAGE_EXIT=0
```

### 2. Frozen golden determinism

Command:

```text
mvn.cmd -pl libs/strategy-engine -am test -Dtest=GoldenDeterminismTest -o
```

Output:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.555 s -- in in.arthayantra.strategyengine.golden.GoldenDeterminismTest
BUILD SUCCESS
GOLDEN_EXIT=0
```

Result: byte-identical; no stop condition triggered.

### 3. Frozen backtest parity

Command:

```text
mvn.cmd -pl services/backtest-service -am test -Dtest=BacktestParityTest -o
```

Output:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.478 s -- in in.arthayantra.backtest.replay.BacktestParityTest
BUILD SUCCESS
PARITY_EXIT=0
```

Result: byte-identical; no stop condition triggered.

### 4. Full service reactor verify

Command:

```text
mvn.cmd -pl services/strategy-signal-service -am verify -o
```

Output:

```text
Tests run: 770, Failures: 0, Errors: 0, Skipped: 0
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.560 s -- in in.arthayantra.strategysignal.signals.SignalEngineIntegrationTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.382 s -- in in.arthayantra.strategysignal.signals.SignalEngineLatencyTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.131 s -- in in.arthayantra.strategysignal.paper.PaperLedgerIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.309 s -- in in.arthayantra.strategysignal.ModularityTest
All coverage checks have been met.
BUILD SUCCESS
VERIFY_EXIT=0
```

Result: the new latency tests, Spring Modulith cycle check, full service suite, and JaCoCo gate passed.

### Focused red/green evidence

- Initial feature red: compilation failed because `SignalEngine.emitLatencyMs` did not yet exist.
- Adversarial regression red: compilation failed because causal scoping/fail-soft methods did not yet
  exist; after implementation, the focused suite passed 13/13.
- Meter-failure red: two injected throwing-meter tests failed; after fail-soft wrappers, the focused
  suite passed 15/15 (`PaperLedgerIntegrationTest` 9/9, `SignalEngineLatencyTest` 6/6).
- Two intermediate harness-only corrections were made before the first green: PostgreSQL
  `TIMESTAMPTZ` was asserted as `java.sql.Timestamp`, and a timer count assertion was made long-typed.

## Adversarial review

Four independent read-only lenses covered parity/module boundaries, database/time exactness,
concurrency/operational loops, and tests/Prometheus behavior. Consolidated result: **10 unique findings:
7 fixed, 3 rejected with evidence**.

Fixed:

1. Made post-commit signal-stamp failure unable to suppress publish/events.
2. Preserved each bar's causal receipt stamp instead of the latest unrelated channel heartbeat.
3. Made scheduled/no-receipt emissions exercise the real null guard.
4. Deferred the paper timer until after commit, with an explicit rollback test.
5. Protected p50/p95 configuration with a Prometheus quantile assertion/snapshot assertion.
6. Reordered the two newly introduced imports flagged by Checkstyle.
7. Made both timer-record paths fail-soft, with injected throwing-meter tests.

Rejected:

1. Replacing PostgreSQL `now()` with statement/clock time: the brief explicitly anchors the
   calculation to the already-persisted `filled_at=now()`; changing it would alter existing fill-time
   semantics beyond this item.
2. Adding `publishPercentileHistogram()`: the brief explicitly requires
   `publishPercentiles(0.5, 0.95)`, which Micrometer exposes as the requested Prometheus p50/p95
   quantiles; bucket histograms were not requested.
3. Clamping/nulling negative wall-clock deltas: the brief specifies raw subtraction and only the
   unset guard. Negative samples remain visible in the DB while Micrometer Timers omit them; this is
   documented below rather than silently changing policy.

Final post-fix verdict: **READY for the parity/verify ladder; no remaining Critical or Important
finding.**

## Evidence-labelled claims

- **[computed]** V041 adds only the three nullable columns at
  `deploy/flyway/strategy/V041__latency_stamps.sql:6-8`; no deterministic record or old migration is
  changed.
- **[computed]** `SignalRepository.stampEmittedAt` updates only `emitted_at` and
  `emit_latency_ms` (`SignalRepository.java:327-333`); `generated_at` is absent from the UPDATE.
- **[computed]** Each queued live bar carries its own receipt time (`SignalEngine.java:121-128,523`),
  the eval thread scopes and clears it (`SignalEngine.java:537-543,1395-1400`), and both live emit
  sites stamp through the signals module (`SignalEngine.java:1087,1360,1374-1391`).
- **[computed]** The paper module reads the linked signal's persisted anchor
  (`PaperService.java:406-419`) and supplies it only to the entry fill
  (`PaperService.java:469-472`); manual/exit paths pass null.
- **[computed]** The paper INSERT computes/returns `tick_to_fill_ms` beside persisted `filled_at`
  (`PaperOrderRepository.java:122-152`) and registers its metric after commit
  (`PaperOrderRepository.java:157-174`).
- **[computed]** The live integration tests assert the emitted stamp and deterministic bar-bucket
  anchor (`SignalEngineIntegrationTest.java:229-242,268-275`); the paper IT asserts the exact stored
  `filled_at - generated_at` value (`PaperLedgerIntegrationTest.java:211-222`).
- **[computed]** `git diff --check` is clean, `git diff origin/main -- libs/strategy-engine` is empty,
  no added mapping annotation exists, and `signals` has no import of `paper` or `notifier`.
- **[sourced]** The signals schema declares `signals.id` as BIGINT
  (`deploy/flyway/strategy/V003__signals.sql:7-8`), so the repository method uses the existing `long`
  identity type rather than the brief's inconsistent illustrative UUID signature.
- **[sourced]** The brief assigns emit latency to `signals`, fill latency to `paper`, and requires
  metrics-only exposure; the implementation follows those module and endpoint constraints.
- **[assumed]** The Architect will independently rerun HOLD-tier parity/audit checks before any owner
  presentation or deployment; this builder did not deploy.

## Open doubts (mandatory)

1. **`generated_at` untouched:** confirmed. No production UPDATE writes it. The live signal IT checks
   it remains the emitted bar bucket and matches the published payload; the paper IT seeds a known
   value, fills through the real path, then asserts the stored value is unchanged and the latency is
   exactly the persisted `filled_at - generated_at` difference.
2. **Module split:** signal emit latency is wholly in `signals`; fill latency is wholly in `paper`,
   which already depends on `signals`. No `signals -> paper`/`notifier` import was added. The
   `ModularityTest` passed 1/1.
3. **Unset guard:** the subscriber heartbeat keeps its boot-grace value, but latency uses a separate
   per-evaluation receipt stamp whose ThreadLocal default is zero and is always cleared in `finally`.
   Therefore scheduled/pre-close emissions stamp `emitted_at` and persist null latency; unrelated
   later channel messages cannot overwrite the causal bar stamp.
4. **Exposure surface:** histograms/percentile timers only. No controller, mapping, read endpoint, or
   contract capture was added or considered necessary.
5. **Parity doubt:** no observed parity change: both frozen suites passed 9/9 and the forbidden library
   tree hash is identical. Residual doubt is below 1% and limited to the normal gap between local
   vector coverage and the Architect's independent HOLD-tier rerun; no golden file was changed to
   obtain green.
6. **Timestamp semantics:** PostgreSQL `now()` is transaction-start time. This exactly preserves the
   existing persisted `filled_at` semantics and the brief's required arithmetic, but excludes work
   performed later within the transaction. Changing that semantic should be a separate architecture
   decision.
7. **Negative deltas:** a backward wall-clock adjustment or future-dated signal can persist a negative
   raw delta; Micrometer Timer samples are recorded only when non-negative. The brief specified no
   clamp/anomaly counter, so this remains an explicit operational doubt rather than an invented policy.

## Boundary confirmation

No deploy, Docker command, Flyway migrate command, `.env`/secret edit, destructive Git/filesystem
command, ledger/plan edit, force-push, main push, merge, strategy-engine edit, or golden-vector edit was
performed. The PR is to be left open for the Architect.
