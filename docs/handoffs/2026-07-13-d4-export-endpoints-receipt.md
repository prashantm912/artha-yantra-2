# Receipt: d4-export-endpoints

Date: 2026-07-13

Branch: `feat/d4-export-endpoints` from `origin/main@2e5ef47cf5360929d5a9a2cded1e437bebeb34f1`

PR: PENDING — populated immediately after `gh pr create`.

## Diff summary

The backend slice adds bounded per-run CSV/JSON downloads for trades, folds, equity, and the existing strategy-version comparison read. It adds no persistence, migration, frontend, deployment, environment, or ledger change.

| File | + | - |
| --- | ---: | ---: |
| `contracts/backtest-service.openapi.json` | 194 | 3 |
| `docs/handoffs/2026-07-13-d4-export-endpoints-receipt.md` | 101 | 0 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/CsvEncoder.java` | 52 | 0 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/ExportController.java` | 280 | 0 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/RunRepository.java` | 8 | 1 |
| `services/backtest-service/src/main/java/in/arthayantra/backtest/replay/TradeRepository.java` | 9 | 2 |
| `services/backtest-service/src/test/java/in/arthayantra/backtest/replay/CsvEncoderTest.java` | 28 | 0 |
| `services/backtest-service/src/test/java/in/arthayantra/backtest/replay/ExportControllerTest.java` | 89 | 0 |
| `services/backtest-service/src/test/java/in/arthayantra/backtest/replay/ExportIntegrationTest.java` | 275 | 0 |

`contracts/gen/backtest-service.d.ts` is deliberately not listed: the Node runtime was inaccessible in the managed sandbox, so no generated declaration was hand-edited or represented as generator output.

## Verification evidence

### 1. Focused red/green coverage

The CSV test first failed compilation because `CsvEncoder` did not exist. After implementation, the focused full-reactor command was green:

```text
& $mvn -pl services/backtest-service -am test -Dtest=CsvEncoderTest,ExportControllerTest -Dsurefire.failIfNoSpecifiedTests=false
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in in.arthayantra.backtest.replay.CsvEncoderTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.104 s -- in in.arthayantra.backtest.replay.ExportControllerTest
BUILD SUCCESS
```

### 2. Backtest-service full reactor

Command from the brief, run with the wrapper-cached Maven binary and the Windows root truststore:

```text
& $mvn -pl services/backtest-service -am test
Tests run: 344, Failures: 0, Errors: 0, Skipped: 0
backtest-service ................................... SUCCESS
BUILD SUCCESS
```

The required new Surefire reports from that green run contained:

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.100 s -- in in.arthayantra.backtest.replay.ExportIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in in.arthayantra.backtest.replay.CsvEncoderTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.104 s -- in in.arthayantra.backtest.replay.ExportControllerTest
```

The IT inserts unique job/run/trade/fold/equity rows in the shared Testcontainers lineage; exercises all four endpoints as CSV and JSON; compares JSON with the existing trades, folds, results-equity, and summary payloads; and checks default CSV, content types, download/truncation headers, 400, and 404 behavior.

### 3. Contract capture and TypeScript generation

The green full-reactor run executed `ContractCaptureTest` and produced `services/backtest-service/target/contracts/backtest-service.openapi.json` at `2026-07-13T20:04:56+05:30`. That fresh artifact was copied to the committed contract with `-Dcontracts.capture=true` semantics and renormalized to LF. It enumerates all four new paths at `contracts/backtest-service.openapi.json:310,355,400,445`.

The dedicated capture rerun was not green because Testcontainers lost access to the Docker named pipe after the full run:

```text
& $mvn -pl services/backtest-service -am test -Dtest=ContractCaptureTest -Dsurefire.failIfNoSpecifiedTests=false -Dcontracts.capture=true
Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
java.nio.file.AccessDeniedException: \\.\pipe\docker_engine
BUILD FAILURE
```

TypeScript regeneration/checking was blocked by the managed environment. `npx` was absent from the executable PATH, its absolute launcher and Node binary returned `Access is denied`, and the one bounded official portable-runtime download failed at the network TLS boundary. Under the two-strikes rule no further Node attempt was made. Therefore:

```text
openapi-typescript@7 regeneration: FAIL (environment — Node execution denied)
tsc --strict: NOT RUN (environment — Node execution denied)
```

`git diff --cached --check` exited 0. Maven checkstyle reported zero violations throughout the full reactor.

## Claims and evidence

- **computed** — Four `ResponseEntity<String>` handlers implement the exact `/export/trades`, `/export/folds`, `/export/equity`, and `/export/compare` routes, default to CSV, reject other formats, validate the run, and attach explicit content/disposition/row/truncation headers: `ExportController.java:85-165,174-190,258-283`.
- **sourced** — The response style mirrors market-data's existing export convention for attachment, `X-Result-Truncated`, `X-Result-Rows`, and explicit content type: `services/market-data-service/.../backfill/ExportController.java:101-109`.
- **computed** — CSV rows use CRLF, RFC-4180 escaping for comma/quote/newline, doubled quotes, and `BigDecimal.toPlainString()`: `CsvEncoder.java:14-18,27,34,42-50`; pinned by `CsvEncoderTest` 1/1 green.
- **computed** — JSON exports equal the existing artifact reads rather than introducing new shapes: `ExportIntegrationTest.java:43-86`; the new IT is 2/2 green.
- **computed** — JDBC timestamps used by trades/compare are normalized to explicit ISO `+05:30` through the shared IST formatter: `TradeRepository.java:193`; `RunRepository.java:359`. The IT spot-checks `2026-07-13T09:15:00+05:30`.
- **sourced** — A clean server-side compare source existed: `ResultsController.java:105` already delegates `/backtests/summary` to `RunRepository.findLatestSummaries` at `RunRepository.java:325`; `/export/compare` reuses that method at `ExportController.java:153`.
- **sourced** — The cap is 1,000 because the sibling trades read bounds `limit` to 1,000 at `ResultsController.java:59`; exports apply that same `MAX_ROWS` at `ExportController.java:31,90,151-153,251-252`.
- **computed** — The fresh OpenAPI capture enumerates all four routes: `contracts/backtest-service.openapi.json:310,355,400,445`.
- **assumed** — No frontend-specific export filename contract exists in this backend-only brief; filenames follow the stable `backtest-{runId}-{artifact}.{format}` convention and the market-data attachment pattern.

## Open doubts

1. **Compare source:** A clean server-side source did exist (`/backtests/summary` → `RunRepository.findLatestSummaries`), so `/export/compare` was built. It requires the same optional `strategyVersionIds`; no new compare engine or persistence was added.
2. **OpenAPI enumeration:** All four string-returning download endpoints did enumerate into the captured OpenAPI (`contracts/backtest-service.openapi.json:310,355,400,445`). Springdoc describes the success body as `*/*`/string rather than separate CSV and JSON media variants; runtime content types are explicit and covered by the IT.
3. **Truncation cap:** The chosen cap is 1,000 rows, matching the existing trades endpoint's hard maximum. Every export sends `X-Result-Truncated` and `X-Result-Rows`; the controller test proves the 1,001-row trade probe truncates to 1,000.
4. **Environment stop:** The required full reactor was green before Docker pipe access became unavailable. The later dedicated contract capture failed for that external reason, while TypeScript regeneration and `tsc` were blocked by Node execution policy/TLS. The committed OpenAPI came from the earlier green full run; the generated TypeScript declaration remains stale and CI is expected to surface its documented drift warning.
5. **Scope:** No migration number was used and no `.env`, deployment, frontend, ledger, or applied Flyway file was touched.
