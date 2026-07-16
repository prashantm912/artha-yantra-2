# Brief: d4-data-quality (P2-4 backend)
Date: 2026-07-13 · Architect: Claude · Builder: Codex (unsandboxed worktree)
Ledger: D4 P2-4 (data-quality artifact, audit D8) · reserved migration **marketdata V047** · Tier: clean (additive, market-data)
Branch: `feat/d4-data-quality` (you are already ON it, in a worktree off origin/main)

## Goal
There is NO automated per-symbol data-quality artifact (audit D8): pieces exist (canaries, ingest ledger, coverage summary) but nothing persists a nightly per-symbol completeness report, so a symbol silently missing from a day's bhavcopy, a 1m capture hole, or a thin chain-capture day is invisible after the fact. Build, **backend-only** (market-data-service):
1. A **nightly EOD job** that computes per-symbol completeness for the latest settled trading day across three scopes: **chain-capture**, **intraday 1m**, **bhavcopy-EQ presence** (incl. day-over-day drop-outs).
2. **Persist** it (V047 `data_quality_days`).
3. A **typed read API** so a dashboard (a later FE brief) can show it.

**Acceptance (the bar that defines done):** a seeded gap appears in the next report — an IT seeds a missing 1m bucket AND a dropped bhavcopy symbol, runs the job, and asserts the persisted rows reflect both.

## Design (decided — do NOT re-derive; reuse the cited infra)

### 1. Migration `deploy/flyway/marketdata/V047__data_quality_days.sql`
One generic per-(day, scope, symbol) row so all three scopes share a shape:
```sql
CREATE TABLE data_quality_days (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trading_date  DATE        NOT NULL,
  scope         TEXT        NOT NULL,   -- 'chain_capture' | 'intraday_1m' | 'bhavcopy_eq'
  symbol        TEXT        NOT NULL,   -- underlying / instrument / equity symbol ('__SUMMARY__' for the bhavcopy rollup row)
  expected      BIGINT      NOT NULL DEFAULT 0,
  present       BIGINT      NOT NULL DEFAULT 0,
  coverage_pct  NUMERIC(5,2),           -- present/expected*100, null when expected=0
  ok            BOOLEAN     NOT NULL,
  detail        TEXT,                   -- human note (e.g. 'absent vs prior day 2026-07-10')
  computed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (trading_date, scope, symbol)
);
CREATE INDEX ix_data_quality_days_date ON data_quality_days (trading_date DESC, scope);
GRANT SELECT, INSERT, UPDATE, DELETE ON data_quality_days TO <the same market-data DML role the sibling tables grant — copy from V044__equity_breadth_daily.sql's GRANT line; if V044 grants to a role, match it; if it grants nothing, add none>.
```
Header comment in the V040/V044 style. **Match the exact GRANT idiom of a recent sibling migration (read V044/V045 first).**

### 2. `DataQualityEodJob` — template = `nse/analytics/EquityBreadthEodJob.java`
- `@Scheduled(cron="${artha.data-quality.eod-cron:0 50 19 * * MON-FRI}", zone="Asia/Kolkata")` + a boot one-shot `@EventListener(ApplicationReadyEvent.class)` + `@Value("${artha.data-quality.enabled:true}")` gate — exactly the EquityBreadthEodJob shape.
- Wrap the run in the ledger: add a new source constant `DATA_QUALITY` to `ingest/IngestRunLedger.java:30-41`, and use the manual `ledger.start(DATA_QUALITY)` / `succeed(id, rowsWritten)` / `fail(id, msg)` pattern (open the ledger AFTER the dedup skip, like EquityBreadthEodJob:94-102).
- **Watermark / dedup:** the target trading day = `NseEodBhavcopyRepository.maxTradeDate()` (the latest settled EOD). Dedup: if `data_quality_days` already has rows for that `trading_date`, skip (idempotent nightly + boot one-shot). Re-run replaces via upsert.
- **Compute the three scopes for that day D:**
  - **chain_capture** — one row per configured underlying (`artha.options.snapshot-underlyings`, read the same list `OptionsSnapshotService` binds; inject via `@Value` or a small config). `present = IvDailySummaryRepository.snapshotCountOn(underlying, D)`; `expected = ${artha.data-quality.chain-expected-passes:187}` (config knob — ~in-session 2-min passes); `coverage = present/expected*100`; `ok = coverage >= ${artha.data-quality.chain-min-coverage-pct:80}`.
  - **intraday_1m** — one row per instrument in a NEW config list `artha.data-quality.intraday-1m-instruments` (default: the primary indices, e.g. `NSE:NIFTY 50,NSE:NIFTY BANK,BSE:SENSEX` — `exchange:tradingsymbol` pairs). `expected = TradingBuckets.minuteBuckets(D 09:15 IST, D 15:30 IST).size()`; `present = CandleRepository.presentBuckets(exchange, sym, "1m", from, to).size()`; `coverage = present/expected*100`; `ok = coverage >= ${artha.data-quality.intraday-min-coverage-pct:95}`. **Do NOT use `GapDetector.gaps()` for coverage — its 10-min recency window mis-flags; compute present-vs-expected directly (recon Q3).**
  - **bhavcopy_eq** — day-over-day EQ drop detection. `today = NseEodBhavcopyRepository.presentTradeDates` won't give symbols; add a repo method `Set<String> eqSymbolsOn(LocalDate)` (`SELECT symbol FROM nse_eod_bhavcopy WHERE trade_date=? AND series='EQ'`). Let `prior` = the previous settled trade date (max trade_date < D — add `prevTradeDate(D)` or reuse `presentTradeDates` over a small window). For each symbol in `prior \ today` (present prior, ABSENT today) write a row `scope='bhavcopy_eq', symbol=<sym>, expected=1, present=0, ok=false, detail='absent vs prior day '||prior`. Also write ONE rollup row `symbol='__SUMMARY__', expected=|prior|, present=|today|, coverage=|today|/|prior|*100, ok = (|today| >= |prior| * ${artha.data-quality.bhavcopy-min-ratio:0.98})`. (If prior is empty — cold start — write only the summary with expected=0/ok=true.)

### 3. `DataQualityRepository` (JdbcTemplate, mirror `MarketContextDayRepository`/`EquityBreadthDailyRepository`)
- `upsertAll(LocalDate day, List<DataQualityRow> rows)` — batch upsert on the UNIQUE key (`ON CONFLICT (trading_date, scope, symbol) DO UPDATE`). Returns count.
- `deleteDay(LocalDate)` is NOT needed if you upsert; but the dedup-skip reads existence — add `boolean hasRowsFor(LocalDate)`.
- `List<DataQualityRow> findByDate(LocalDate)` + `Optional<LocalDate> latestDate()` for the read API.
- `record DataQualityRow(LocalDate tradingDate, String scope, String symbol, long expected, long present, BigDecimal coveragePct, boolean ok, String detail, OffsetDateTime computedAt)`.

### 4. `DataQualityController` — template = `canary/IngestHealthController.java`
- `@RestController @RequestMapping("/api/v1/market/health")`.
- `@GetMapping("/completeness")` — optional `@RequestParam(required=false) String date` (default `latestDate()`); returns a TYPED record `CompletenessReport(LocalDate date, List<CompletenessRow> items)` (NEVER a Map — the ratchet). `CompletenessRow` = the row projected to the API shape.
- `/api/v1/market/**` is already gateway-allowlisted (recon Q7) — NO gateway change.

### 5. `application.yml` (market-data-service)
Add the new knobs explicitly (recon caveat — the sibling EOD crons live only as `@Value` defaults; make ours discoverable): `artha.data-quality.eod-cron`, `.enabled`, `.chain-expected-passes`, `.chain-min-coverage-pct`, `.intraday-1m-instruments`, `.intraday-min-coverage-pct`, `.bhavcopy-min-ratio`. Put them near the `artha.options.snapshot-underlyings` block (~line 200).

## Constraints & traps (pasted)
- **Build the FULL reactor + `-am`**: `-pl services/market-data-service -am`. The market-data image builds with **repo-root context** (`context: ..` in compose) — but you only build/test, the Architect deploys.
- **The worktree `mvnw` CANNOT re-download maven under this machine's AV TLS interception** — run the already-extracted binary directly with `JAVA_HOME` set + `-o` (offline; deps are cached from the main repo): `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" /c/Users/prash/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/apache-maven-3.9.16/bin/mvn -pl services/market-data-service -am ...`. And **NEVER `mvnw ... | tail`** — tail's exit 0 masks a maven failure; capture the real exit (`; echo EXIT=$?`).
- **IT naming:** `*IntegrationTest`/`*Test` ONLY (no failsafe; `*IT` silently skipped). New ITs share the singleton Testcontainers DB with NO per-method cleanup → unique dates/symbols per test; connect as `artha`.
- **Every NEW endpoint returns a typed record**, never `Map<String,Object>` (MapReturnRatchetTest freezes the count per service).
- **Contract spec DOES drift** (new `@GetMapping` path + `date` param) → recapture + regen TS: `mvn -pl services/market-data-service -am test -Dtest=ContractCaptureTest -Dcontracts.capture=true -o`, then `cd frontend-react && npx openapi-typescript@7 ../contracts/market-data-service.openapi.json -o ../contracts/gen/market-data-service.d.ts` (confirm the exact openapi/gen filenames for market-data first), then `./node_modules/.bin/tsc --strict --noEmit --skipLibCheck <the gen file>`.
- **JaCoCo ≥ 60% line** on the service; ModularityTest runs in a full `-am verify`.
- **IST/UTC:** trading-day math is IST — a bucket's calendar day = `(bucket AT TIME ZONE 'Asia/Kolkata')::date` (recon Q4 BhavcopyCloseCanary join). `TradingBuckets.minuteBuckets` already works in IST session bounds.
- Market-data owns data quality (separation of concerns) — do NOT reach into strategy/backtest schemas.

## Mode & boundaries (UNSANDBOXED — read carefully)
Run as the real user in THIS worktree. **HARD NEVER LIST:** deploy / `docker` / run flyway/migrate against any DB (the Architect owns flyway-init + deploy); edit `.env`/secrets; `rm -rf` / `git reset --hard` / `git clean -fdx`; push to `main`; merge; force-push; edit an applied migration (V001–V046); edit the ledger or `docs/superpowers/plans/*`. Touch ONLY `services/market-data-service/**`, `deploy/flyway/marketdata/V047__*.sql`, `contracts/**` (regen), and this brief's receipt. If a step needs anything on the NEVER list, STOP + write a doubt.
You MAY: run the direct-mvn, run Node/npx (contract regen), commit, push THIS branch, `gh pr create` (leave OPEN).

## Verify ladder (run ALL, paste real outputs into the receipt; use the direct-mvn)
1. `... -pl services/market-data-service -am -q -DskipTests package -o` → compiles.
2. `... -pl services/market-data-service -am verify -o` → ITs green (incl. the seeded-gap acceptance IT), JaCoCo + Modularity. Paste the `Tests run:` line.
3. ContractCapture recapture (`-Dcontracts.capture=true`) + openapi-typescript regen + `tsc --strict` on the gen file — all clean; commit the updated openapi + gen.
4. `gh pr create --base main --head feat/d4-data-quality --title "feat(market-data): nightly per-symbol data-quality report (P2-4)" --body "<what/why + the 3 scopes + V047 + endpoint + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-13-d4-data-quality-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real outputs of package / verify (`Tests run:`) / contract-capture / tsc.
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory):** (a) the acceptance IT — how you seeded the 1m gap + the dropped bhavcopy symbol and what you asserted; (b) the expected-passes / coverage-threshold defaults you chose; (c) the intraday-1m instrument default list + whether it should be config-driven off snapshot-underlyings instead; (d) any per-symbol scale concern (equity count) you bounded; (e) whether the `__SUMMARY__` sentinel row is the right rollup shape.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- Any verify-ladder step fails for a reason outside this change.
- The bhavcopy day-over-day diff needs a repo method that fights the existing `NseEodBhavcopyRepository` shape after two attempts → land chain_capture + intraday_1m first, scope bhavcopy_eq OUT + doubt.
- Anything on the NEVER list would be required.
