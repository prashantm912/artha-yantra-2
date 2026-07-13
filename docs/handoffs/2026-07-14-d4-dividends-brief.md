# Brief: d4-dividends (P2-6 D5 — dividend cash-flow ingestion)
Date: 2026-07-14 · Architect: Claude · Builder: Codex (unsandboxed worktree)
Ledger: D4 P2-6 **D5 half only** (D6 PIT constituents is DEFERRED — owner-gated, decisions-log §4.2) · reserved migration **marketdata V048** · Tier: clean (additive, market-data)
Branch: `feat/d4-dividends` (you are already ON it, in a worktree off origin/main)

## Goal
The NSE/BSE corporate-action feed already returns dividend rows, but the pipeline DISCARDS them (audit D5: "Dividend blindness" — returns are price-only). Ingest dividend cash flows into a NEW separate table, **never mutating prices**. When done: every dividend CA row (symbol, ex-date, amount when parseable, subject) is persisted, so a future total-return overlay has the cash-flow history.

**Scope = INGESTION ONLY.** Do NOT build a total-return overlay/consumer (no consumer exists yet; that's a separate follow-up). Do NOT touch index constituents (D6 — deferred, owner-gated).

## The discard site (recon — do NOT re-derive)
`bhavcopy/BhavcopyBackfillService.java` — `runNseRatios` (~:470-492) and `runBseRatios` (~:499-523). Each loops the fetched CA records:
```java
for (CaRecord a : actions) {
  Optional<CorporateActionSubjectParser.Parsed> parsed = CorporateActionSubjectParser.parse(a.subject());
  if (parsed.isEmpty()) {
    continue; // dividend / buyback / AGM — not a price adjustment   <-- DIVIDENDS DIE HERE
  }
  ... caRepo.upsert(...)  // split/bonus price ratio
}
```
The `CaRecord` (`nse/NseCorporateActionFetcher.java:10`: `record CaRecord(String symbol, String isin, LocalDate exDate, String subject)`; BSE twin `BseCaRecord(scrip, shortName, exDate, purpose)`) already carries symbol + ex-date + the dividend subject text. **No new external fetch is needed** — just parse the dividend out of the subject and persist BEFORE the `continue`.

## The deliverable

### 1. Migration `deploy/flyway/marketdata/V048__dividends.sql`
Mirror the `V022__eod_corporate_actions.sql` DDL (read it first). Additive, no price mutation:
```sql
CREATE TABLE dividends (
  exchange      TEXT NOT NULL,
  tradingsymbol TEXT NOT NULL,
  ex_date       DATE NOT NULL,
  amount        NUMERIC(18,4),          -- per-share cash amount, NULL when the subject has no parseable amount
  subject       TEXT NOT NULL,          -- the raw CA subject text (provenance / re-parse)
  isin          TEXT,
  source        TEXT NOT NULL,          -- 'NSE' | 'BSE'
  detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (exchange, tradingsymbol, ex_date)
);
CREATE INDEX idx_dividends_symbol ON dividends (tradingsymbol, ex_date DESC);
```
Header comment in the V022/V047 style (marketdata schema owned by `artha` → no GRANT, match the sibling ALTER/CREATE migrations — verify against V022/V047). Note explicitly in the comment: dividends are a SEPARATE cash-flow record and do NOT adjust price (unlike split/bonus).

### 2. `bhavcopy/DividendSubjectParser.java` (mirror `CorporateActionSubjectParser` style)
- `static Optional<BigDecimal> parseAmount(String subject)` — recognise a dividend subject and extract the per-share rupee amount. Handle the common NSE/BSE shapes, case-insensitive:
  - `"DIVIDEND - RS 5 PER SHARE"`, `"FINAL DIVIDEND RS 2.50 PER SHARE"`, `"INTERIM DIVIDEND - RE 1 PER SHARE"`, `"DIVIDEND RS.1.20"`, `"SPECIAL DIVIDEND RS 3/- PER SHARE"`.
  - Only treat it as a dividend if the subject contains `DIVIDEND` (word-boundary, case-insensitive). Extract the first `RS|RE` + number (allow `.`, optional `/-`). Return `Optional.of(amount)`; if it's a dividend subject but no amount is parseable, that's handled at the call site (record the row with NULL amount — see below), so `parseAmount` may return empty while a separate `isDividend(subject)` predicate returns true.
- Add `static boolean isDividend(String subject)` — true iff the subject names a dividend (so a dividend with an unparseable amount is still recorded, ex-date + subject captured, amount NULL). Do NOT classify buyback/AGM/rights as dividends.
- Percent-of-face-value dividends (`"DIVIDEND 50%"`) — if you can't resolve to a rupee amount without face value, record with NULL amount + the subject (do NOT guess). Note this in the receipt.

### 3. `dividends/DividendRepository.java` (mirror `candles/EodCorporateActionRepository.java:27-48`)
- `@Repository` JdbcTemplate; `void upsert(String exchange, String tradingsymbol, LocalDate exDate, BigDecimal amount, String subject, String isin, String source)` — idempotent `INSERT ... ON CONFLICT (exchange, tradingsymbol, ex_date) DO UPDATE SET amount=EXCLUDED.amount, subject=EXCLUDED.subject, ...`.
- (Optional, only if trivial) a `List<Dividend> findBySymbol(exchange, tradingsymbol)` read for tests.

### 4. Wire into `BhavcopyBackfillService` (both NSE ~:475 and BSE ~:507)
- Inject `DividendRepository`.
- Replace the bare `continue` with: on empty split/bonus parse, `if (DividendSubjectParser.isDividend(a.subject())) { dividendRepo.upsert(exchange, a.symbol(), a.exDate(), DividendSubjectParser.parseAmount(a.subject()).orElse(null), a.subject(), a.isin(), "NSE"|"BSE"); } continue;`
- **NEVER call the price-ratio `caRepo.upsert` for a dividend, and NEVER touch candles/prices.** The split/bonus path is byte-unchanged.
- BSE `CaRecord` field names differ (`scrip`/`shortName`/`purpose`) — map the tradingsymbol/subject appropriately (use the BSE short name as the symbol as the existing BSE ratio path does; match how `runBseRatios` currently sources the symbol for `caRepo.upsert`).

### 5. Tests
- `DividendSubjectParserTest` — a table of real-ish subjects → expected amount / isDividend (incl. an unparseable-amount dividend → isDividend true, amount empty; a split/bonus/buyback → isDividend false).
- An IT (`*IntegrationTest`) proving a dividend CA record lands a `dividends` row (amount + subject) AND that a split/bonus record still lands in `eod_corporate_actions` (not dividends) — i.e. the two paths don't cross. Assert NO price/candle row is written by the dividend path.

## Constraints & traps (pasted)
- **Direct-mvn** (worktree `mvnw` can't download maven under AV TLS): `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" /c/Users/prash/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/apache-maven-3.9.16/bin/mvn -pl services/market-data-service -am ... -o`. NEVER `mvnw | tail` (masks failure; use `; echo EXIT=$?`).
- **IT naming** `*IntegrationTest`/`*Test`; singleton Testcontainers, unique symbols/dates per method; connect as `artha`.
- **JaCoCo ≥ 60%**, ModularityTest in `-am verify`.
- **NO new REST endpoint** (ingestion only) ⇒ no contract capture / TS regen. If you think a read endpoint is needed, STOP + doubt (default: none).
- **NEVER mutate prices / candles / the split-bonus path** — dividends are a pure additive side record. This is the one correctness invariant.

## Mode & boundaries (UNSANDBOXED)
Run as the real user in THIS worktree. **HARD NEVER LIST:** deploy / docker / flyway-migrate / edit `.env`/secrets / `rm -rf` / `git reset --hard` / `git clean -fdx` / push to `main` / merge / force-push / edit an applied migration / edit the ledger or `docs/superpowers/plans/*` / touch index-constituents (D6 is deferred). Touch ONLY `services/market-data-service/**` + `deploy/flyway/marketdata/V048__*.sql` + this brief's receipt. STOP + doubt if anything on the NEVER list is needed.
You MAY: direct-mvn, commit, push THIS branch, `gh pr create` (leave OPEN).

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `... -pl services/market-data-service -am -q -DskipTests package -o` — compiles.
2. `... -pl services/market-data-service -am verify -o` — ITs green (incl. the dividend IT) + ModularityTest + JaCoCo. Paste `Tests run:`.
3. `gh pr create --base main --head feat/d4-dividends --title "feat(market-data): dividend cash-flow ingestion (P2-6 D5)" --body "<what/why + the discard-site fix + V048 + price-untouched invariant + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-14-d4-dividends-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real outputs of package / verify (`Tests run:`).
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory):** (a) which dividend subject shapes you parse vs record-with-NULL-amount (percent-of-face-value especially); (b) confirm the split/bonus price path is byte-unchanged + no candle/price write on the dividend path; (c) BSE symbol/subject field mapping; (d) any dividend row that would collide on the `(exchange,tradingsymbol,ex_date)` PK (multiple dividends same ex-date?).
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- Any verify step fails for a reason outside this change.
- The dividend parse can't cleanly separate from buyback/AGM/rights after two attempts → record only clearly-`DIVIDEND`-worded rows + doubt.
- Anything on the NEVER list would be required.
