# Brief: d4-run-tags-notes (P2-2 backend)
Date: 2026-07-13 · Architect: Claude · Builder: Codex (UNSANDBOXED worktree mode)
Ledger: D4 P2-2 (run tags/notes + saved views), reserved migration **backtest V020** · Tier: clean (additive backend)
Branch: `feat/d4-run-tags-notes` (you are already ON it, in a worktree off origin/main)

## Goal
Backtest **jobs have no tags, no note, and there is no saved-view persistence** (research-fidelity audit gaps A2 + F5). Add, backend-only:
1. **Per-job `tags` (string array) + `note` (free text)** — settable at submit AND editable after, filterable in the jobs list.
2. **Saved views** — a per-owner table storing named filter-sets (opaque JSONB `filter`) so the FE can persist research filter sets. CRUD API.

When done: a run is taggable/annotatable at submit and after; the jobs list can filter by tag; the FE (a later brief) can save/list/delete named filter views. **FE is OUT of scope — this brief is API + migration + tests only.**

## Authoritative spec (research-fidelity audit, do NOT re-derive)
`docs/audits/2026-07-10-research-fidelity-audit.md` line 510:
> **P2-2** | A2/F5 run tags/notes/saved views | `jobs.tags[]`, `jobs.note`; saved-view table keyed to owner | FE filter sets persist; runs taggable at submit + after

## The exact deliverable

### 1. Migration `deploy/flyway/backtest/V020__run_tags_and_saved_views.sql`
- `ALTER TABLE jobs ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';`
- `ALTER TABLE jobs ADD COLUMN note TEXT;`
- `CREATE INDEX idx_jobs_tags ON jobs USING gin (tags);` (tag-contains filter)
- New table (keyed to owner, opaque FE filter):
  ```sql
  CREATE TABLE saved_views (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner      TEXT NOT NULL,
    kind       TEXT NOT NULL,            -- which surface the view belongs to, e.g. 'backtest_jobs'
    name       TEXT NOT NULL,
    filter     JSONB NOT NULL,           -- opaque FE filter-set (query params); backend does not interpret it
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner, kind, name)
  );
  ```
- `GRANT SELECT, INSERT, UPDATE, DELETE ON saved_views TO ay_backtest;` (jobs already granted in V002).
- Header comment in the V002/V019 style (one short paragraph of WHY).

### 2. `Job` record + `JobRepository`
- Add `List<String> tags` + `String note` to the `Job` record (append at the end — readers are positional-safe; the one construction site is `JobRepository.mapRow`).
- `mapRow`: read `note` (`rs.getString`) and `tags` (JDBC array — `Array a = rs.getArray("tags"); List<String> tags = a == null ? List.of() : Arrays.asList((String[]) a.getArray());`).
- Add a writer `void updateAnnotations(UUID id, List<String> tags, String note)` — a single `UPDATE jobs SET tags = ?, note = ? WHERE id = ?` binding the array via `connection.createArrayOf("text", tags.toArray())` (use a `PreparedStatementSetter`/`SqlParameterValue` — the JdbcTemplate array-bind idiom). **Do NOT touch the `request` JSONB** — it is pinned submission provenance; tags/note live in their own columns.
- `list(...)`: add a `String tag` parameter; when non-blank, append `AND jobs.tags @> ARRAY[?]::text[]` (GIN-indexed contains). Keep the existing `strategyIds`/`currentVersions`/sort logic byte-identical.
- `insertQueued`: extend to accept `List<String> tags, String note` and include them in the INSERT column list + values (so submit-time tags persist in one write). Its only caller is `JobsService.submit`.

### 3. `BacktestRunRequest`
- Add two optional fields: `List<String> tags` and `String note`. Both nullable/absent-safe (existing submissions omit them → tags default `{}`, note null).

### 4. `JobsService`
- `submit`: pass `req.tags()` (null → `List.of()`) + `req.note()` into `insertQueued`. Trim/normalize: drop blank tags, cap tag length (e.g. 40 chars) and count (e.g. 20) — 422 `VALIDATION_FAILED` on violation, mirroring the existing validators' style. Note length cap e.g. 2000 chars.
- New `void annotate(UUID id, List<String> tags, String note)` → `get(id)` (404s if missing) then `repository.updateAnnotations`. Same tag/note validation as submit (factor a private helper so submit + annotate share it).
- New saved-view methods: `create(owner, kind, name, JsonNode filter)`, `list(owner, kind)`, `delete(owner, id)` delegating to a new `SavedViewRepository` (mirror `JobRepository`'s JdbcTemplate style; connects as `artha`, `backtest` schema on the search path). A duplicate `(owner,kind,name)` → 409 `ConflictException` (reuse `ErrorCodes.CONFLICT_*` or add one in the existing style).

### 5. `JobsController` (all NEW endpoints return TYPED RECORDS — see ratchet trap)
- `PATCH /jobs/{jobId}/annotations` — body `record JobAnnotationRequest(List<String> tags, String note)`; returns `record JobAnnotationResponse(String jobId, List<String> tags, String note)`. (POST is acceptable if PATCH complicates the gateway/CSRF path — but PATCH is the correct verb; verify PATCH is allowed by the edge-gateway method policy, else fall back to POST and note it.)
- `GET /jobs` — add optional `@RequestParam(required=false) String tag`, thread into `service.list(...)`; **also add `tags` + `note` to the existing `summary(...)` Map** (adding keys to an already-Map handler does NOT drift the spec and does NOT add a ratchet-counted Map handler).
- Saved views under the already-allowlisted `/api/v1/backtests` prefix:
  - `GET /saved-views?kind=` → `record SavedViewsResponse(List<SavedView> items)` where `record SavedView(String id, String kind, String name, JsonNode filter, OffsetDateTime createdAt)`.
  - `POST /saved-views` — body `record SavedViewRequest(String kind, String name, JsonNode filter)` → returns the created `SavedView` (201).
  - `DELETE /saved-views/{id}` → 204.
  - Actor = `"owner"` (single-owner; same convention as `JobsService.submit`'s `createdBy`).

## Constraints & traps (pasted — read before coding)
- **MapReturnRatchetTest freezes the Map-return handler COUNT per service.** Every NEW endpoint here (`/jobs/{id}/annotations`, all `/saved-views`) MUST return a typed record, never `Map<String,Object>`, or the CI shard fails. The EXISTING `/jobs` + `/jobs/{jobId}` stay Map — adding keys to them is fine (Maps are invisible to the springdoc spec).
- **Applied migrations are checksum-locked.** V020 is a NEW file; never edit V001–V019. Build with the FULL reactor + `-am` (`-pl services/backtest-service -am`), never a bare `-pl` leaf.
- **Contract spec DOES drift here:** a new `@PatchMapping`/`@*Mapping` path + a new `tag` query param both drift `/v3/api-docs`. Re-capture: `mvnw -pl services/backtest-service -am test -Dtest=ContractCaptureTest -Dcontracts.capture=true`, then regen TS: `cd frontend-react && npx openapi-typescript@7 ../contracts/backtest-service.openapi.json -o ../contracts/gen/backtest-service.d.ts` (Node works in unsandboxed mode). Commit the updated snapshot + gen file.
- **`request` JSONB is pinned provenance** — never rewrite it on a tags/note edit.
- **IT naming:** `*IntegrationTest` or `*Test` ONLY (no failsafe; `*IT` is silently skipped). New ITs share the singleton Testcontainers DB with NO per-method cleanup → every test row needs a unique id/name.
- **JaCoCo ≥ 60% line** on the service; ModularityTest runs in a full `-am verify`.
- **Gateway allowlist:** `/api/v1/backtests/**` is already allowlisted (the #810 export endpoints serve under it live) → `/saved-views` + `/jobs/{id}/annotations` need NO gateway change. Verify only; do not edit edge-gateway.
- IST/UTC: `created_at` maps via the existing `offset(...)` helper (UTC) — leave that convention as-is; do not add IST normalization (that was a separate, flagged #810 change — do not repeat or extend it).

## Mode & boundaries (UNSANDBOXED — read carefully)
You run as the real user with full machine access. **HARD NEVER LIST — do none of these:** deploy or `docker`/`docker compose` anything; run flyway/migrate against any DB (the Architect owns flyway-init + the deploy); edit `.env` or any secret; `rm -rf` / `git clean -fdx` / `git reset --hard`; push to `main`; merge any PR; force-push; edit an applied migration (V001–V019); edit the ledger or any `docs/superpowers/plans/*`. Touch ONLY `services/backtest-service/**`, `deploy/flyway/backtest/V020__*.sql`, `contracts/**` (regen), and this brief's receipt. Stay in this worktree. If a step needs anything on the NEVER list, STOP and write a doubt.
You MAY: run mvnw, run Node/npx (contract regen), commit, push THIS branch, `gh pr create` (leave the PR OPEN). The Architect audits + merges + deploys (with forced flyway-init + a V020 DB-probe).

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `./mvnw -pl services/backtest-service -am -q -DskipTests package` — compiles (full reactor).
2. `./mvnw -pl services/backtest-service -am verify` — ITs green (incl. your new tags/note + saved-view tests), JaCoCo + Modularity pass. Paste the `Tests run:` line(s).
3. Contract re-capture + TS regen (commands above) — commit the updated `contracts/backtest-service.openapi.json` + `contracts/gen/backtest-service.d.ts`; run `cd frontend-react && ./node_modules/.bin/tsc --strict --noEmit --skipLibCheck` (or `npm run build`) to prove the gen types compile.
4. `gh pr create --base main --head feat/d4-run-tags-notes --title "feat(backtest): run tags/notes + saved views (P2-2 backend)" --body "<what/why + endpoints + migration V020 + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-13-d4-run-tags-notes-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real outputs of package / verify (the `Tests run:` line) / contract-capture / tsc.
- Claims WITH evidence (file:line), each labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory).** Address at minimum: (a) PATCH vs POST for annotations + why; (b) tag/note validation limits you chose; (c) whether saved-views `kind` is constrained or free-text and why; (d) any Testcontainers array-binding surprise.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- Any verify-ladder step fails for a reason outside this change.
- The array-bind (`text[]`) fights the JdbcTemplate idiom after two attempts → stop + doubt (fallback: store tags as a comma-joined text column is NOT acceptable; ask the Architect).
- Anything on the NEVER list would be required.
- The saved-views sub-feature balloons the diff past a clean review → land jobs tags/note first, scope saved-views OUT with a doubt, and the Architect re-briefs it.
