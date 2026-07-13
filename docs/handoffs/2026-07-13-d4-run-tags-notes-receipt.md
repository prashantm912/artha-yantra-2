# Receipt: d4-run-tags-notes (P2-2 backend)
Date: 2026-07-13 · Builder: Codex (gpt-5.6-sol, xhigh) · Finished by: Architect (Claude)
Brief: `docs/handoffs/2026-07-13-d4-run-tags-notes-brief.md`

## Handoff note (why this receipt is Architect-written)
Codex built the full change unsandboxed in the worktree but the model hit **"Selected model is at capacity"** at the very end (after writing all code + tests, mid-way through the CounterfactualReplayTest arity edit) and exited 1 **before** committing/pushing/opening the PR or running the verify ladder. All files were on disk (unsandboxed = real files as `prash`). The Architect audited the diff directly (stronger than a self-reported receipt), ran the entire verify ladder, did the contract recapture + TS regen Codex never reached, then committed/pushed/opened the PR. No code was changed during salvage — only built, tested, and captured.

## Diff summary
- **NEW** `deploy/flyway/backtest/V020__run_tags_and_saved_views.sql` — `jobs.tags text[] NOT NULL DEFAULT '{}'` + `jobs.note text` + GIN `idx_jobs_tags`; `saved_views(id,owner,kind,name,filter jsonb,created_at, UNIQUE(owner,kind,name))` + grant to `ay_backtest`.
- **NEW** `SavedView.java` (record), `SavedViewRepository.java` (JdbcTemplate CRUD, owner-scoped, JSONB filter opaque), `RunTagsSavedViewsIntegrationTest.java` (4 ITs).
- **MOD** `Job.java` (+`tags`,+`note`), `JobRepository.java` (mapRow +2, 6-arg `insertQueued` overload preserves non-interactive callers, `updateAnnotations` leaves `request` JSONB untouched, GIN `tags @>` filter in `list`), `BacktestRunRequest.java` (+`tags`,+`note` optional), `JobsService.java` (shared `validateAnnotations` — trim/distinct/cap 20 tags·40 chars·2000 note; `annotate` 404s first; saved-view create/list/delete; dup→409), `JobsController.java` (`PATCH /jobs/{id}/annotations`, `GET/POST/DELETE /saved-views`, all TYPED records; `tag` query param + `tags`/`note` in the `/jobs` summary Map), `QueueCapIntegrationTest.java` + `CounterfactualReplayTest.java` (constructor arity).
- **MOD (Architect capture)** `contracts/backtest-service.openapi.json` + `contracts/gen/backtest-service.d.ts` — the 3 new paths + `tag` param.

## Verify ladder (real outputs, direct-mvn — the worktree `mvnw` can't re-download maven under AV TLS interception; ran the already-extracted `apache-maven-3.9.16/bin/mvn` offline)
1. `mvn -pl services/backtest-service -am test-compile -o` -> **COMPILE_EXIT=0**.
2. `mvn -pl services/backtest-service -am verify -o` -> **`Tests run: 348, Failures: 0, Errors: 0, Skipped: 0` · BUILD SUCCESS** (incl. the 4 new tags/note + saved-view ITs, JaCoCo, ModularityTest).
3. `mvn ... -Dtest=ContractCaptureTest -Dcontracts.capture=true -o` -> **CAPTURE_EXIT=0**; openapi now carries `/jobs/{jobId}/annotations`, `/saved-views`, `/saved-views/{id}`.
4. `openapi-typescript 7.13.0` regen -> **REGEN_EXIT=0**; `tsc --strict --noEmit --skipLibCheck contracts/gen/backtest-service.d.ts` -> **TSC_EXIT=0**.

## Claims (evidence-labelled)
- Ratchet-safe: every NEW endpoint returns a typed record (`JobAnnotationResponse`/`SavedView`/`SavedViewsResponse`), never `Map` — *sourced* JobsController.java:129-157,225-228.
- `request` JSONB never rewritten on tag/note edit — *sourced* JobRepository.updateAnnotations (single `UPDATE jobs SET tags=?,note=?`), verified by IT `patchReplacesAnnotationsWithoutRewritingSubmissionProvenance` — *computed* (IT green).
- Non-interactive callers unbroken: 6-arg `insertQueued` overload retained -> `List.of()`,`null` — *sourced* JobRepository.java:86-95.
- `jobs.tags @> ARRAY[?]::text[]` uses the new GIN index — *sourced* JobRepository.java:307-310 + V020 `idx_jobs_tags`.
- 348 backend tests pass offline against the live Testcontainers DB — *computed* (verify log).

## Open-doubts
- (a) **PATCH vs POST**: used `@PatchMapping /jobs/{jobId}/annotations` (correct REST verb; the IT drives it green through MockMvc). If the edge-gateway's live method policy rejects PATCH on this route (untested live), fall back to POST — flag on live-verify. *assumed* the gateway passes PATCH under the existing `/api/v1/backtests/**` allowlist.
- (b) **Validation limits** chosen by the brief's suggestion: <=20 tags, <=40 chars/tag, <=2000 char note; blanks dropped, trimmed, de-duped. No owner sign-off on the exact numbers — trivially tunable.
- (c) **saved-views `kind`** is free-text (not enum-constrained) so the FE can add surfaces without a migration; the UNIQUE is `(owner,kind,name)`.
- (d) **Duplicate-name 409** reuses `ErrorCodes.CONFLICT_WATCHLIST_NAME` (no saved-view-specific code) — semantically loose but a correct 409; a dedicated code is a later nicety.
- (e) Actor hardcoded `"owner"` (single-owner platform, same convention as `JobsService.submit`) — revisit when multi-actor/autonomous writes land (audit T3).

## FE follow-up (OUT of scope here)
Tag chips + tag-filter + note edit on the jobs list, and saved-view save/list/delete UI — a separate FE brief consuming these endpoints.

Co-Authored-By: OpenAI Codex <noreply@openai.com>
