# Brief: d4-export-endpoints
Date: 2026-07-13 · Architect: Claude · Builder: Codex (autonomous exec-loop, first unattended run)
Ledger row: D4 remainder P2-3 (FID audit A4/F3) — BACKEND slice · Tier: clean (additive, read-only, no migration, no parity surface)
Branch: `feat/d4-export-endpoints` (from fresh `origin/main`)
Reserved migration number: **NONE — this item needs NO migration.** Export reads existing rows. If you think you need a migration, STOP and write a doubt.

## Goal (one paragraph)
Backtest research surfaces have NO export anywhere (audit F3/A4 — CSV/JSON download exists only in market-data Data-Ops). Add per-run **backend export endpoints** for the four core artifacts of one backtest run — trades, folds, equity curve, and the compare matrix — each downloadable as CSV and JSON. This is the BACKEND slice only; the FE download buttons are a separate follow-up (do NOT build FE here). When done, a caller can `GET` each artifact for a run id and receive a well-formed CSV or JSON file download.

## Scope — files in play
- NEW: an export controller in backtest-service, e.g. `services/backtest-service/.../replay/ExportController.java` (or extend the existing results controller area — your call, but keep export in its own controller class for clarity)
- You MAY add small read helpers to the existing repositories (`TradeRepository`, `RunRepository`, and the folds source) if a needed read isn't already exposed — additive methods only, do not change existing signatures.
- Tests (see verify ladder)
- `contracts/` regen if the OpenAPI captures the new paths (see traps)
Anything else (FE, migrations, new tables) = stop and write a doubt.

## Design decisions already made (do not relitigate)
1. **Reuse the existing export pattern.** market-data-service already has an `ExportController` doing CSV/JSON downloads (audit A4 cites it). READ it first (`grep -rl ExportController services/market-data-service`) and mirror its response style (content-type, `Content-Disposition: attachment; filename=...`, streaming/string body). Consistency with the existing export beats inventing a new shape.
2. **Read existing data — no new persistence.** Trades come from `TradeRepository` (`findByRun`/`loadByRun`), run + equity/drawdown curves + metrics from `RunRepository.findResult`, folds from wherever `/folds` is served today (find the folds controller/repo). Compare-matrix = the same data `GET /backtests/summary?strategyVersionIds` assembles (or the existing compare read) — if a clean server-side compare source does not already exist, scope compare-matrix OUT and write a doubt (do NOT build a new compare engine here).
3. **Endpoints** (mirror the sibling results routes' path style under the backtest results controller base):
   - `GET /api/v1/backtests/{runId}/export/trades?format=csv|json`
   - `GET /api/v1/backtests/{runId}/export/folds?format=csv|json`
   - `GET /api/v1/backtests/{runId}/export/equity?format=csv|json`
   - `GET /api/v1/backtests/{runId}/export/compare?strategyVersionIds=...&format=csv|json` (ONLY if a server-side compare source exists — else omit + doubt)
   - `format` defaults to `csv`. Unknown format → 400. Unknown runId → 404 (match the sibling endpoints' `NotFoundException`/`ErrorCodes` usage).
4. **CSV shape:** a header row + one row per record; quote fields containing comma/quote/newline per RFC-4180; timestamps as ISO-8601 with the `+05:30` offset exactly as stored (do NOT reformat to UTC — IST trap). Numbers as their plain decimal string (never a locale-formatted or scientific form). **JSON shape:** reuse the EXISTING typed read shape for that artifact (the same records `/trades`, `/folds`, `/results` already return) so the JSON export == the API payload.
5. **Response typing:** CSV/JSON export handlers return `ResponseEntity<String>` (or a streaming body) with explicit content-type — they are file downloads, NOT the typed-record API surface, so they do NOT count against `MapReturnRatchetTest` and do NOT need to be typed records. Do NOT return `Map<String,Object>`.
6. **Bounded size:** cap rows to a sane maximum (reuse the existing trades cap of 1000 if present, or a constant like 100_000 for CSV) and set a truncation header (`X-Result-Truncated: true`) when capped — there is an existing truncation-header convention (`X-Result-Truncated`, task_f12c165f) — grep for it and match it.

## Constraints & memory traps (pasted — read even if you read CLAUDE.md)
- **Build full-reactor with `-am`.** PowerShell:
  ```powershell
  $mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
  $env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
  & $mvn -pl services/backtest-service -am test
  ```
- **Tests named `*Test`/`*IntegrationTest`** — `*IT` is silently skipped.
- **ITs share one singleton Testcontainers DB, no cleanup** — unique ids per method; follow `TradeRepositoryIntegrationTest`/`RunRepositoryIntegrationTest` (raw JDBC via `BacktestIntegrationTestBase`, hang rows off a fresh jobId/runId).
- **EVERY new `@*Mapping` path drifts springdoc.** After it compiles, follow `.github/workflows/ci-contracts.yml`'s steps: capture run with `-Dcontracts.capture=true`, regen TS via `npx openapi-typescript@7` into `contracts/gen/`, keep `tsc` green. Commit the regenerated artifacts. If the CSV endpoints don't enumerate into the spec (string returns often don't), note that in the receipt.
- **IST trap:** timestamps stored with `+05:30`; never `::date`/UTC-reformat. Emit ISO strings verbatim.
- **`*.json` = eol=lf.** `git add --renormalize` if you touch a `.json` and it flips.
- **PowerShell 5.1:** no `&&` chains, no here-strings into git; multi-line commit via a temp file + `git commit -F`.
- **This is the autonomous lane:** branch from fresh `origin/main`, build, test, commit, **push, and open the PR OPEN** (`gh pr create`, leave OPEN — the Architect merges). End commit messages with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `& $mvn -pl services/backtest-service -am test` — full module green (incl. Testcontainers ITs; Docker is up). No golden/parity risk here (read-only endpoints), but the suite must stay green.
2. New tests (minimum):
   - An IT that inserts a run + trades/folds via the existing harness, hits each export endpoint, and asserts: CSV has the right header + row count + a spot-checked cell; JSON parses to the expected shape; `format=json` vs `csv` content-types differ; unknown `format` → 400; unknown runId → 404.
   - A CSV-quoting unit test (a field with a comma/quote/newline is escaped per RFC-4180).
3. Contract capture + TS regen per the workflow (trap above); `tsc` green.
4. `gh pr create --base main --head feat/d4-export-endpoints --title "feat(backtest): per-run CSV/JSON export endpoints (P2-3/A4/F3, backend)" --body "<what/why/how + test evidence + receipt path>"` — leave OPEN.

## Receipt shape (mandatory — write to `docs/handoffs/2026-07-13-d4-export-endpoints-receipt.md`)
- Diff summary (files + line counts) + PR URL
- Real `Tests run:` lines for ladder steps 1–2; what step 3 did
- Claims WITH evidence (file:line / command+output), each labeled `computed | sourced | recalled | assumed`
- **Open-doubts** (mandatory). Specifically address: (a) whether a server-side compare source existed (so whether `/export/compare` was built or omitted), (b) whether the CSV endpoints enumerated into the OpenAPI spec, (c) the truncation-cap value you chose and why.
- End commit messages with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`

## Stop conditions (write a doubt and halt instead of improvising)
- You find yourself needing a migration or a new table (this item must not need one).
- No clean server-side compare source exists for `/export/compare` (omit it, don't build a compare engine).
- Any full-suite test fails for a reason outside this change.
- You need to touch `.env`, deploy, FE, or the ledger.
- Two failures of the same approach (two-strikes rule).
