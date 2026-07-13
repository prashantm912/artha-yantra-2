# D4 data-quality receipt

Date: 2026-07-13
Branch: `feat/d4-data-quality`
Base: `origin/main` at `45c94faa`
PR: https://github.com/prashantm912/artha-yantra-2/pull/823

## Outcome

Implemented the nightly D4 completeness report for `chain_capture`, `intraday_1m`, and
`bhavcopy_eq`, backed by the new V047 table and exposed through the typed
`GET /api/v1/market/health/completeness` endpoint. The seeded-gap acceptance integration test,
full market-data reactor verification, contract capture/regeneration, and strict TypeScript check
all pass.

## Diff summary

Implementation diff before this receipt: **10 files, +798/-0 lines**.

| File | Line change |
|---|---:|
| `deploy/flyway/marketdata/V047__data_quality_days.sql` | +21/-0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityRepository.java` | +107/-0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityEodJob.java` | +240/-0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityController.java` | +64/-0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/ingest/IngestRunLedger.java` | +2/-0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/nse/NseEodBhavcopyRepository.java` | +19/-0 |
| `services/market-data-service/src/main/resources/application.yml` | +9/-0 |
| `services/market-data-service/src/test/java/in/arthayantra/marketdata/dataquality/DataQualityReportIntegrationTest.java` | +188/-0 |
| `contracts/market-data-service.openapi.json` | +83/-0 |
| `contracts/gen/market-data-service.d.ts` | +65/-0 |
| `docs/handoffs/2026-07-13-d4-data-quality-receipt.md` | +227/-0 |

## Verification evidence

The brief's direct-Maven workaround was used with JDK 21 and the already extracted Maven 3.9.16
binary. No Maven output was piped, so each recorded exit is the Maven process exit.

### Focused seeded-gap integration test

Command:

```powershell
& $mvn -pl services/market-data-service -am `
  '-Dtest=DataQualityReportIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test -o
```

Real Surefire report:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.244 s
BUILD SUCCESS
MAVEN_EXIT=0
```

### Package

Command:

```powershell
& $mvn -pl services/market-data-service -am -q '-DskipTests' package -o
```

Real output:

```text
PACKAGE_EXIT=0
```

### Full reactor verify

Command:

```powershell
& $mvn -pl services/market-data-service -am verify -o
```

Real output excerpts:

```text
Tests run: 816, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
market-data-service ........................ SUCCESS [02:56 min]
BUILD SUCCESS
Total time:  03:07 min
VERIFY_EXIT=0
```

This includes the seeded-gap integration test, JaCoCo enforcement, architecture/modularity tests,
and the service's full test suite.

### Contract capture and generated type

Capture command:

```powershell
& $mvn -pl services/market-data-service -am `
  '-Dtest=ContractCaptureTest' '-Dcontracts.capture=true' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test -o
```

Real output:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time:  29.726 s
CONTRACT_CAPTURE_EXIT=0
```

Regeneration command and real output:

```text
npx.cmd openapi-typescript@7 ..\contracts\market-data-service.openapi.json -o ..\contracts\gen\market-data-service.d.ts
✨ openapi-typescript 7.13.0
🚀 ..\contracts\market-data-service.openapi.json → ..\contracts\gen\market-data-service.d.ts [180.4ms]
OPENAPI_TYPESCRIPT_EXIT=0
```

The linked worktree does not contain `frontend-react/node_modules`, so the strict check used the
repository's already installed TypeScript executable from the main checkout read-only:

```text
C:\Trading\ArthaYantra\artha-yantra-2\frontend-react\node_modules\.bin\tsc.cmd --strict --noEmit --skipLibCheck ..\contracts\gen\market-data-service.d.ts
Version 5.9.3
TSC_VERSION_EXIT=0
TSC_EXIT=0
```

### Diff hygiene

```text
git diff --check
EXIT=0
```

Only the brief-authorized market-data service, V047 migration, generated contracts, and this
receipt are changed.

## Evidence-labelled claims

- **[sourced]** V047 creates the brief-specified durable row shape, uniqueness key, and
  date/scope read index: `deploy/flyway/marketdata/V047__data_quality_days.sql:7` and
  `deploy/flyway/marketdata/V047__data_quality_days.sql:21`.
- **[computed]** The job runs at application readiness and 19:50 IST, takes the latest bhavcopy
  date as its watermark, skips an already-materialized date, and records a `DATA_QUALITY` ledger
  run: `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityEodJob.java:86`,
  `:92`, `:102`, and `:108`; ledger source at
  `services/market-data-service/src/main/java/in/arthayantra/marketdata/ingest/IngestRunLedger.java:43`.
- **[computed]** Chain completeness reuses `snapshotCountOn`, intraday completeness reuses
  `TradingBuckets` plus `presentBuckets`, and bhavcopy completeness compares distinct EQ symbols
  with the previous settled trade date:
  `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityEodJob.java:136`,
  `:156`, and `:180`; supporting bhavcopy queries are at
  `services/market-data-service/src/main/java/in/arthayantra/marketdata/nse/NseEodBhavcopyRepository.java:40`
  and `:49`.
- **[computed]** Persistence is typed, rerunnable through an upsert, and exposed through a typed
  API response with latest-date fallback:
  `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityRepository.java:16`,
  `:30`, and `:86`; `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityController.java:19`,
  `:30`, `:40`, and `:44`.
- **[computed]** The acceptance IT seeds all 375 expected one-minute buckets except one, seeds a
  prior-day EQ symbol absent today, then asserts 374/375 intraday coverage, the dropout row,
  summary and chain rows, successful ledger accounting, both endpoint date modes, and boot-style
  deduplication:
  `services/market-data-service/src/test/java/in/arthayantra/marketdata/dataquality/DataQualityReportIntegrationTest.java:56`,
  `:66`, `:110`, `:113`, `:118`, `:124`, `:131`, `:136`, `:145`, and `:157`.
- **[sourced]** Runtime defaults are explicit in configuration rather than inferred from a live
  environment: `services/market-data-service/src/main/resources/application.yml:207`.
- **[recalled]** No deployment, Docker command, Flyway execution, database migration execution,
  secret or `.env` change, destructive Git/filesystem command, main-branch push, merge, or
  force-push was performed during this builder task.
- **[assumed]** The existing repository's generated OpenAPI JSON and declaration-file workflow is
  the intended contract publication surface; both were recaptured and passed the required strict
  compiler check.

## Adversarial review

- Migration: V047 is a new plain table, does not alter V001-V046, and uses a uniqueness constraint
  matching the upsert conflict key. No migration was executed.
- Money/parity: this change performs count/percentage health reporting only; it does not touch
  order, position, P&L, exit, engine, or golden-vector paths.
- Boundaries: trading buckets are generated in `Asia/Kolkata`; the schedule is explicitly IST;
  the report date is anchored to the latest settled bhavcopy date rather than wall-clock date.
- Idempotency/failure: date-level dedup precedes ledger creation, persistence is an upsert, and a
  failure is recorded in the ledger without making the application-ready path fatal.
- Test isolation: the acceptance IT uses a far-future Monday and unique `DQ*2198` symbols; cleanup
  is limited to its own rows and symbols.

No confirmed blocking defect remained after this review. Per the lane protocol, the Architect
still owns the independent adversarial/migration review before merge.

## Open doubts (mandatory)

1. **Acceptance seeding and assertions.** The IT materializes the expected IST 1m bucket list for
   2198-07-16, inserts every bucket except 11:32, inserts prior-day `DQDROP2198` but omits it on the
   report day, and asserts 374/375 plus a `present=0` dropout row. It also asserts the bhavcopy
   summary, chain shortfall, ledger success, endpoint response, and date-level dedup. This meets the
   stated acceptance bar; the remaining doubt is only that H2/PostgreSQL compatibility cannot
   reproduce every production Timescale behavior. **[computed]** Evidence:
   `services/market-data-service/src/test/java/in/arthayantra/marketdata/dataquality/DataQualityReportIntegrationTest.java:56-158`.
2. **Defaults.** Chosen exactly as briefed: 187 expected chain passes, 80% minimum chain coverage,
   95% minimum intraday coverage, and 0.98 bhavcopy ratio. The open operational question is whether
   187 remains representative when the snapshot cadence or session availability changes; every
   value is independently configurable. **[sourced]** Evidence:
   `services/market-data-service/src/main/resources/application.yml:211-215`.
3. **Intraday instruments.** The default is `NSE:NIFTY 50,NSE:NIFTY BANK,BSE:SENSEX`. It remains a
   dedicated data-quality setting rather than deriving from `snapshot-underlyings`: snapshot
   underlyings and candle instruments have different identifier grammars and the former includes
   instruments not promised as 1m feed baselines. I recommend keeping the explicit list unless an
   authoritative shared instrument registry becomes the source. **[sourced + assumed]** Evidence:
   `services/market-data-service/src/main/resources/application.yml:204` and `:213`.
4. **Per-symbol scale.** Chain and intraday emit only their configured symbols. Bhavcopy emits one
   row per prior-day symbol missing today plus one summary, not one row for every roughly 3,000+
   healthy EQ symbol; this bounds ordinary daily writes and response size. A broad upstream outage
   can still deliberately produce thousands of dropout rows, which is useful evidence but should
   be watched operationally. **[computed]** Evidence:
   `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityEodJob.java:180-218`.
5. **`__SUMMARY__` rollup.** The sentinel implements the brief's single daily bhavcopy rollup and
   keeps the schema uniform. The open model question is whether a future first-class `row_kind` or
   separate summary table would be clearer if more rollups are added; for this bounded scope the
   sentinel is unambiguous and covered by the uniqueness key. **[sourced + assumed]** Evidence:
   `services/market-data-service/src/main/java/in/arthayantra/marketdata/dataquality/DataQualityEodJob.java:206-218`.
6. **TypeScript executable location.** The required strict check passed with TypeScript 5.9.3, but
   this linked worktree had no local `node_modules`. Reusing the main checkout's installed compiler
   avoided an unrelated dependency install and did not modify that checkout. **[computed]**
