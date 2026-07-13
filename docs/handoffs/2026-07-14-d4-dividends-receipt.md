# Receipt: d4-dividends (P2-6 D5 dividend ingestion)

Date: 2026-07-14
Branch: `feat/d4-dividends`
Base: `origin/main` at `7d1b102625aba033358691f7c05af938cc20fa40`
Feature commit: `beade6c189c7e742134fce376850d528f99bbd51`
Tier: **clean** (additive market-data ingestion; no owner-facing return calculation)
PR URL: https://github.com/prashantm912/artha-yantra-2/pull/832 (**OPEN**)

## Outcome

Dividend corporate-action rows are now retained as separate cash-flow records in
`marketdata.dividends`. NSE and BSE corporate-action records that do not parse as split/bonus but
explicitly contain the word `DIVIDEND` are upserted with symbol, ex-date, raw subject, ISIN when
available, source, and the first parseable `RS|RE` amount. A dividend with no parseable rupee amount
is still stored with `amount=NULL`.

This is ingestion only. No total-return overlay, read endpoint, price/candle write, index-constituent
work, or contract surface was added.

## Diff summary

Line counts are added/deleted from the feature commit against `origin/main`; the receipt is listed
separately because it is the closeout commit.

| File | Added | Deleted |
|---|---:|---:|
| `deploy/flyway/marketdata/V048__dividends.sql` | 19 | 0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/bhavcopy/BhavcopyBackfillService.java` | 30 | 0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/bhavcopy/DividendSubjectParser.java` | 30 | 0 |
| `services/market-data-service/src/main/java/in/arthayantra/marketdata/dividends/DividendRepository.java` | 40 | 0 |
| `services/market-data-service/src/test/java/in/arthayantra/marketdata/bhavcopy/BhavcopyBackfillIntegrationTest.java` | 94 | 10 |
| `services/market-data-service/src/test/java/in/arthayantra/marketdata/bhavcopy/DividendSubjectParserTest.java` | 51 | 0 |
| `docs/handoffs/2026-07-14-d4-dividends-receipt.md` | 200 | 0 |
| **Implementation/test subtotal** | **264** | **10** |

The production `BhavcopyBackfillService` diff is additive-only (`30` additions, `0` deletions).
No existing split/bonus statement was edited.

## What changed

- V048 creates the plain `dividends` table with the brief-mandated
  `(exchange, tradingsymbol, ex_date)` primary key and symbol/date index. It explicitly documents
  that dividend cash flows do not adjust prices.
- `DividendSubjectParser` classifies only word-boundary `DIVIDEND` subjects and extracts the first
  case-insensitive `RS|RE` numeric amount, supporting optional dot and `/-` suffix.
- `DividendRepository` performs the mandated idempotent upsert. `amount` is nullable; raw `subject`
  is always retained for provenance and future re-parsing.
- The NSE parser-empty branch writes the NSE symbol/ISIN/subject. The BSE parser-empty branch uses
  `tickerForScrip` first and `shortName` as the fallback, matching the existing BSE ratio path.
- The existing split/bonus parser and `caRepo.upsert` blocks are unchanged. Dividend branches call
  only `DividendRepository` and then continue; they do not call `CandleRepository`.
- Tests cover the five requested common amount shapes, percentage/no-amount capture, non-dividend
  exclusions, both exchange mappings, replay idempotency, split/bonus separation, and absence of
  dividend-path candle writes.

## Verification evidence

All Maven commands used the already-extracted Maven 3.9.16 binary directly, Java 21, full reactor
`-am`, and offline mode. No Maven output was piped. The package and verify outputs below are the
fresh post-review reruns.

### 1. Package

Command:

```text
mvn.cmd -pl services/market-data-service -am -q -DskipTests package -o
```

Real output:

```text
EXIT=0
```

### 2. Full service reactor verify

Command:

```text
mvn.cmd -pl services/market-data-service -am verify -o
```

Real output:

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0  (common-web/core)
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0   (common-web/servlet)
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0  (market-calendar)
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0  (black76-math)
Tests run: 819, Failures: 0, Errors: 0, Skipped: 0 (market-data-service)
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.908 s -- in in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in in.arthayantra.marketdata.bhavcopy.DividendSubjectParserTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.563 s -- in in.arthayantra.marketdata.ModularityTest
All coverage checks have been met.
BUILD SUCCESS
Total time: 03:04 min
EXIT=0
```

The five module totals were independently summed from the final Surefire XML reports; the three
named suite lines were read from their final Surefire text reports.

### 3. Focused red/green evidence

- Initial RED: the focused full-reactor command failed with 9 expected compile errors because
  `DividendSubjectParser` and `DividendRepository` did not exist.
- One test-harness correction followed: AssertJ's generic `OptionalAssert.get()` did not expose
  `BigDecimal.isEqualByComparingTo`; the test was corrected to unwrap the value first.
- Pre-review GREEN: the focused parser + backfill suite passed 8/8.
- Post-review GREEN: after removing per-method singleton-DB cleanup and adding replay/NULL coverage,
  the same focused suite passed 8/8 with `BUILD SUCCESS`, `EXIT=0`.

## Adversarial review

One read-only migration/domain reviewer checked schema safety, idempotency, data loss, parser
ambiguity, both exchange mappings, test gaps, scope, and the no-price-mutation invariant.

Initial outcome: 3 findings; 2 fixed, 1 rejected against the brief and retained as an open doubt.

Fixed:

1. Removed the new test's three per-method `DELETE`s; unique `DIVIT*` symbols plus idempotent upserts
   now comply with the singleton Testcontainers rule.
2. Added a second ingestion pass, an exact row-count assertion, and a percent-only dividend assertion
   proving replay idempotency and `amount=NULL` persistence.

Rejected with evidence:

1. Moving dividend persistence ahead of split/bonus parsing to support a hypothetical combined
   `DIVIDEND + BONUS` subject. Deliverable #4 explicitly says to persist only on an **empty
   split/bonus parse** and mandates the split/bonus path remain unchanged. Widening recognized
   split/bonus rows would alter that prescribed operational path. The unverified feed-shape risk is
   documented below instead of silently expanding scope.

Final re-review: no remaining Critical or Important implementation defect. Its only closeout finding
was the then-pending mandatory receipt; this file satisfies it. The full package/verify ladder was
rerun after the review fixes.

## Evidence-labelled claims

- **[computed]** V048 is a new additive migration and declares a separate nullable-amount table;
  evidence: `deploy/flyway/marketdata/V048__dividends.sql:1-19` and the successful real Flyway-backed
  integration suite.
- **[computed]** The parser requires a word-boundary dividend label and returns no guessed value for
  percentage-only text; evidence: `DividendSubjectParser.java:10-27` and
  `DividendSubjectParserTest.java:11-49`.
- **[computed]** NSE/BSE dividend writes occur only inside parser-empty branches and call only
  `dividendRepo.upsert`; evidence: `BhavcopyBackfillService.java:480-493,519-538`.
- **[computed]** Existing ratio writes remain present and unchanged at
  `BhavcopyBackfillService.java:496-500,540-548`; that production file's staged diff was 30 additions
  and 0 deletions.
- **[computed]** The real-DB IT proves parseable and NULL amounts, NSE/BSE provenance, two-pass
  idempotency, split/bonus-only ratios, and zero matching candles; evidence:
  `BhavcopyBackfillIntegrationTest.java:266-343` and its 6/6 Surefire result.
- **[sourced]** The new table PK and last-write upsert semantics follow the Architect brief exactly;
  evidence: `docs/handoffs/2026-07-14-d4-dividends-brief.md:20-64`.
- **[assumed]** Each fetched CA record has one primary action category. Mixed dividend plus
  split/bonus subjects were not found in repository fixtures and were not externally/live queried;
  per the explicit discard-site deliverable, a mixed subject recognized by the ratio parser would
  remain ratio-only.
- **[computed]** PR #832 is open and unmerged. No deploy, Docker, or Flyway-migrate command was run.

## Open doubts (mandatory)

1. **Parsed vs NULL amount shapes.** Parsed, case-insensitively: `DIVIDEND - RS 5 PER SHARE`,
   `FINAL DIVIDEND RS 2.50 PER SHARE`, `INTERIM DIVIDEND - RE 1 PER SHARE`,
   `DIVIDEND RS.1.20`, and `SPECIAL DIVIDEND RS 3/- PER SHARE`. Any explicitly dividend-labelled
   subject without the first `RS|RE + number` match is recorded with `amount=NULL`; `DIVIDEND 50%`
   is integration-tested. Face value is not available, so percentages are never guessed.
2. **First-amount ambiguity.** The brief requires the first `RS|RE` amount. If a real subject states
   face value before cash amount, the parser will retain that first amount; raw subject provenance
   permits a later re-parser. `NO DIVIDEND` or `DIVIDEND CANCELLED` also contains the explicit word and
   would be retained (normally with NULL) unless the feed contract proves such cancellation shapes.
3. **Split/bonus and candle firewall.** Confirmed: production service diff is additive-only;
   `caRepo.upsert` statements are unchanged; pure dividend branches never call them; no candle/price
   production file changed; the real-DB IT finds zero `DIVIT%` candles.
4. **Mixed-purpose subjects.** A subject containing both `DIVIDEND` and a parseable split/bonus is
   handled only by the existing split/bonus path because the brief explicitly places dividend
   persistence inside `parsed.isEmpty()`. No repository fixture establishes whether NSE/BSE emits
   such combined rows. Widening this requires Architect confirmation because it would change the
   recognized split/bonus operational path.
5. **BSE mapping.** The dividend branch uses `bseRepo.tickerForScrip(scripCode)` first and
   `shortName` only when absent, exactly matching the current BSE ratio path. BSE CA records carry no
   ISIN, so BSE dividend rows persist `isin=NULL`.
6. **Primary-key collisions.** The mandated PK permits one row per
   `(exchange, tradingsymbol, ex_date)`. Multiple declared dividends for one symbol/ex-date collide;
   the last fetched row overwrites amount/subject/ISIN/source, including a possible parseable-to-NULL
   overwrite. The raw subject is retained only for the winning row. A multi-event key would require a
   new Architect-approved schema because the brief explicitly mandates this PK/upsert.
7. **Failure isolation.** A dividend upsert failure is caught at the existing per-exchange ratio-sync
   boundary, so later CA rows from that exchange wait for the next idempotent retry. No price is
   mutated, but a DB failure can delay subsequent split/bonus ingestion in that batch.

## Boundary confirmation

No deploy, Docker command, Flyway migrate, `.env`/secret edit, destructive filesystem/Git command,
ledger/plan edit, applied-migration edit, main push, merge, force-push, total-return overlay, candle/
price mutation, or index-constituent work was performed. The PR is left open for the Architect.
