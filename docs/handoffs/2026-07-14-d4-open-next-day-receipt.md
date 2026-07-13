# Receipt: d4-open-next-day (#15)

**Verdict — computed:** the two swing deep-sims now expose one opt-in `next_open` variant each while every existing variant continues through the byte-identical `at_close` fill path. The required direct-Maven verification ladder is green. PR: https://github.com/prashantm912/artha-yantra-2/pull/843 (`OPEN`, base `main`, head `feat/d4-open-next-day-v2`).

## Diff summary

- `services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/SwingFillPrice.java`: +25 / -0 — shared `at_close` / `next_open` fill selector and final-bar sentinel.
- `services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/manas/ManasAroraSwingBacktest.java`: +39 / -7 — default-preserving Variant component plus four variant-aware fill sites.
- `services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/minervini/MinerviniSwingBacktest.java`: +26 / -6 — default-preserving Variant component plus two variant-aware fill sites.
- `services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/manas/ManasAroraBacktestService.java`: +3 / -1 — one `rs-turnover-nopyramid-nextopen` grid row.
- `services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/minervini/MinerviniBacktestService.java`: +2 / -1 — one `rs-turnover-nextopen` grid row.
- `services/market-data-service/src/test/java/in/arthayantra/marketdata/screener/manas/ManasAroraSwingBacktestTest.java`: +124 / -0 — next-open entry, stop exit, pyramid add, whole-position exit, and final-bar coverage.
- `services/market-data-service/src/test/java/in/arthayantra/marketdata/screener/minervini/MinerviniSwingBacktestTest.java`: +77 / -0 — next-open entry/exit and final-bar coverage.
- `services/market-data-service/src/test/java/in/arthayantra/marketdata/screener/DeepSwingEndpointIntegrationTest.java`: +27 / -0 — both new variants traverse the existing endpoint.
- `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/deepswing/DeepSwingService.java`: +4 / -2 — the frictionless-fill caveat identifies next-open variants.
- `docs/handoffs/2026-07-14-d4-open-next-day-receipt.md`: +118 / -0 — this execution receipt.
- Implementation subtotal before this receipt: 9 files, +327 / -17.
- PR: https://github.com/prashantm912/artha-yantra-2/pull/843 (`OPEN`, base `main`, head `feat/d4-open-next-day-v2`).

## Verification evidence

Direct Maven used throughout:

```text
C:\Users\prash\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT
```

### TDD and focused verification

Before implementation, the focused engine command reported 11 tests with four expected failures because the Variant records did not yet carry `fillTiming`. No expected-number assertion was edited. After implementation, the same focused engine suite was green. Removing the two new grid rows temporarily made the endpoint test fail with expected HTTP 200 versus actual 422; restoring them made the same endpoint test green:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Adversarial review identified missing direct coverage for the Manas pyramid-add and whole-position fill sites. The added focused regression test then produced:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The reviewer rechecked the resolution and marked the finding closed with no residual issue.

### Required package — market-data

Command: `mvn.cmd -pl services/market-data-service -am -q -DskipTests package -o`

```text
COMMAND_EXIT=0
Wall time: 43.6 seconds
```

### Required verify — market-data

Command: `mvn.cmd -pl services/market-data-service -am verify -o`

```text
Tests run: 825, Failures: 0, Errors: 0, Skipped: 0
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- ManasAroraSwingBacktestTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- MinerviniSwingBacktestTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- DeepSwingEndpointIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- ModularityTest
Analyzed bundle 'market-data-service' with 848 classes
All coverage checks have been met.
BUILD SUCCESS
Total time: 03:10 min
COMMAND_EXIT=0
```

**Existing-tests-unchanged statement — computed:** all pre-existing Manas and Minervini deep-sim tests passed with their original expected numbers. Their diffs contain additions only (`+124/-0` and `+77/-0`); no pre-existing assertion or expected number was edited. The legacy `195.0` Manas and `185.0` Minervini entry-price assertions remain unchanged and green.

### Required package — backtest-service item-4 caveat

Command: `mvn.cmd -pl services/backtest-service -am -q -DskipTests package -o`

```text
COMMAND_EXIT=0
Wall time: 35.5 seconds
```

The allowed backtest-service change is only string construction in the report caveat, so the brief specifies compile as sufficient.

### Scope and review

```text
BRANCH=feat/d4-open-next-day-v2
BASE_HEAD=e5d0b1b32d9b3aa65f16401f8a1b18ba40dd2a9e
OUTSIDE_SCOPE=<none>
FORBIDDEN_DIFFS=<none>
git diff --check: exit 0
```

The parity/default reviewer reported no findings. The coverage reviewer raised one medium test gap, which was fixed and independently re-reviewed as closed. No implementation finding remained open before the full verification ladder.

## Evidence-labelled claims

- **Computed:** `fillPrice` returns `bars[i].close()` for `at_close`, `bars[i+1].open()` for `next_open`, and `NaN` when no following bar exists (`SwingFillPrice.java:18-22`).
- **Computed:** all legacy constructors route to `AT_CLOSE` (`ManasAroraSwingBacktest.java:82-87`; `MinerviniSwingBacktest.java:64-67`), while exactly one new grid row per engine opts into `next_open` (`ManasAroraBacktestService.java:385`; `MinerviniBacktestService.java:386`).
- **Computed:** exactly six production fill calls exist: Manas entry, square-off, per-lot stop, and pyramid add (`ManasAroraSwingBacktest.java:281,313,329,355`) plus Minervini entry and exit (`MinerviniSwingBacktest.java:185,198`).
- **Sourced:** the brief requires fill price alone to move while the signal and stop levels remain on bar `i` (`docs/handoffs/2026-07-14-d4-open-next-day-brief.md`, items 1-2).
- **Computed:** Manas stop and trail anchors remain `close[i]` (`ManasAroraSwingBacktest.java:286,288,357`), and Minervini preserves `close[i]` separately as `entrySignalClose` for its protective stop (`MinerviniSwingBacktest.java:193`).
- **Computed:** the endpoint integration test returns both requested next-open variant names through the existing discriminator (`DeepSwingEndpointIntegrationTest.java:181-204`); no endpoint or migration was added.
- **Computed:** variant identity already continues into the job strategy tag, source tag, and metrics (`DeepSwingService.java:171,188,210`), and the caveat now labels `-nextopen` as next-session-open (`DeepSwingService.java:226-229`).
- **Computed:** the final path audit contains only the nine implementation/test files above plus this receipt. `SwingBatchEngine`, `libs/strategy-engine`, migrations, `.env`, the ledger, and `docs/superpowers/plans/**` are absent from the diff.
- **Assumed:** no load-bearing recalled or assumed claim was used; behavior claims are sourced from the brief/current files or computed by commands and tests in this session.

## Open doubts (mandatory)

- **(a) New-test price distinction — resolved/computed:** the engine tests keep signal dates equal, assert legacy fills against `bars[i].close()`, and assert next-open fills against `bars[i+1].open()` using deliberately distinct opens (`ManasAroraSwingBacktestTest.java:88-106`; `MinerviniSwingBacktestTest.java:91-109`). The Manas pyramid case separately pins level-2 entry and both whole-position exits to the following open (`ManasAroraSwingBacktestTest.java:110-136`).
- **(b) Existing variants byte-unchanged — resolved/computed:** legacy Variant overloads select the exact `at_close -> bars[i].close()` path, every old grid row still calls those overloads, the two existing engine test files have zero deleted lines, and the full original expected-number suite passed. No existing expected number was changed.
- **(c) Stop level stays on `close[i]` — resolved/sourced+computed:** comments and code retain Manas initial stops/basis on signal close and Minervini's stop anchor in `entrySignalClose`; next-open affects execution prices only (`ManasAroraSwingBacktest.java:284-288,356-358`; `MinerviniSwingBacktest.java:191-193,241-244`).
- **(d) Final-bar bounds guard — resolved/computed:** the helper returns `NaN` without bar `i+1`; entry signals are skipped and exit signals leave the existing open-at-end state to be dropped (`SwingFillPrice.java:21`; `ManasAroraSwingBacktest.java:282-283,314-315,330-331,358,363`; `MinerviniSwingBacktest.java:186-187,199-200,211`). Both engines have explicit last-bar tests (`ManasAroraSwingBacktestTest.java:139`; `MinerviniSwingBacktestTest.java:113`).
- **(e) Live/parity surfaces untouched — resolved/computed:** `git diff --name-only origin/main -- :(glob)**/SwingBatchEngine.java libs/**` returned no paths. The live batch, `libs/strategy-engine`, and golden vectors are untouched.
- **Residual doubt — disclosed:** the test suite proves source-level default routing and all current expected deep-sim numbers, but no production-data deep-sim rerun was required by the brief. This is not used as a substitute for the green required ladder.
