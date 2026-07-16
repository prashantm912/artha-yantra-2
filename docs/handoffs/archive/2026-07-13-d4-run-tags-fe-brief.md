# Brief: d4-run-tags-fe (P2-2 FE)
Date: 2026-07-13 · Architect: Claude · Builder: Opus 4.8 subagent (worktree)
Ledger: D4 P2-2 FE half · Tier: clean (FE-only, additive) · Branch: `feat/d4-run-tags-fe`

## Goal
The P2-2 **backend** shipped (#817, live): backtest jobs now carry `tags[]` + `note`, are filterable by a single tag, and there is a per-owner saved-view store. **NO UI consumes any of it yet.** Add, FE-only:
1. **Per-job tags + note** on the Jobs monitor: show each job's tags (as chips) + a note indicator, edit them inline, and filter the list by a job tag.
2. **Saved views**: save the current Jobs-page filter set under a name, then load/delete saved views.

When done: on the Jobs monitor a user can tag/annotate a run, filter by a job tag, and save+reload named filter sets.

## Live endpoints (backend shipped #817 — do NOT re-derive)
- `PATCH /api/v1/backtests/jobs/{jobId}/annotations` — body `{tags: string[], note: string|null}` -> `{jobId, tags, note}`. Replaces both (send the full desired set). Validation: <=20 tags, <=40 chars/tag, <=2000 char note (server 422s past that).
- `GET /api/v1/backtests/jobs?tag=<one-tag>` — job-tag contains filter (single tag). The `/jobs` summary now also returns `tags` + `note` per row.
- `GET /api/v1/backtests/saved-views?kind=<k>` -> `{items: [{id, kind, name, filter, createdAt}]}`.
- `POST /api/v1/backtests/saved-views` — body `{kind, name, filter}` (filter = opaque JSON you define) -> the created view (201); duplicate `(owner,kind,name)` -> 409.
- `DELETE /api/v1/backtests/saved-views/{id}` -> 204.
- The generated TS types for all of these are already in `contracts/gen/backtest-service.d.ts` (regenerated in #817) — you may reference them but the app uses hand-written DTOs in `src/api/*` (match that style).

## CRITICAL naming trap — read twice
`src/pages/backtests/JobsPage.tsx` **already has a `tags` state variable (~line 137)** — that is the **STRATEGY-tag filter**: a multi-select of *strategy* tags that maps (via `strategies.data`) to a set of `strategyIds` sent as the `strategyIds` server param (~lines 154-176). It has **nothing to do** with the new per-job tags. Do NOT reuse, rename, or fold into it. The new per-job tag filter is a SEPARATE control that sends the backend's `?tag=` param (single job tag). Name your new state unambiguously (e.g. `jobTagFilter`, and render job tags from `JobDto.tags`). Conflating the two will silently break the existing strategy-tag filter.

## The deliverable

### 1. `src/api/backtests.ts`
- `JobDto` (~line 15): add `tags?: string[]` + `note?: string | null`.
- `useJobs(...)` (~line 74): add a `jobTag?: string | null` parameter; when set, `params.set('tag', jobTag)`. Thread it through the `queryKey` too. **Also update `useJobsLive` identically** (it mirrors the same args ~line 99) so the live socket subscription keys match — otherwise the filtered list and the live patch diverge. Update EVERY `useJobs`/`useJobsLive` call site to pass the new arg (JobsPage; note `dashboard.ts` uses a raw `apiFetch`, not `useJobs` — leave it).
- New `useAnnotateJob()` mutation -> `PATCH /backtests/jobs/{jobId}/annotations` with `{tags, note}`; on success invalidate the `['jobs','list',...]` query so the row refreshes.
- Saved views: add `useSavedViews(kind)`, `useCreateSavedView()`, `useDeleteSavedView()` (or a small new `src/api/savedViews.ts` — your call, match the file idiom). Use `apiFetch` (it handles the XSRF header on mutating calls).

### 2. `src/pages/backtests/JobsPage.tsx`
- **Tags column**: add a `DataColumn` rendering `job.tags` as chips (reuse whatever chip/badge idiom the app has — search `components/atoms` for a Badge/Chip; if none, a small `<span>` with `--ay-*` token styling). Show a note indicator when `job.note` is non-empty; the full note can show on hover/title or in the editor.
- **Inline tag/note editor**: a per-row action (a row menu item or a small pencil button with an accessible name like `aria-label="Edit tags and note for job {id}"`) opening a popover/dialog with a tag input (add/remove chips) + a note textarea + Save -> `useAnnotateJob`. Reuse an existing overlay/popover component (shadcn Dialog/Popover already in the repo — search `components/`). Show the server's 422 message on validation failure (the app's toast/error idiom).
- **Job-tag filter**: a text input (or select of known tags if cheap) that sets `jobTagFilter` -> passed to `useJobs`/`useJobsLive` as `jobTag`. Reset `offset` to 0 on change (the page already resets offset on filter change ~line 206 — extend that effect).
- **Saved views control**: a small toolbar control near the existing filters: (a) "Save view" -> prompts for a name -> `useCreateSavedView({kind:'backtest_jobs', name, filter})` where `filter` = a JSON object of the CURRENT filter state (status, strategyId, jobTagFilter, latestOnly, sort); (b) a dropdown listing `useSavedViews('backtest_jobs')` -> selecting one applies its `filter` back onto the page state; (c) a delete affordance per saved view -> `useDeleteSavedView`. Handle the 409 (duplicate name) with the app's error toast.

### 3. Runner submit-tags — SCOPE OUT (write as a doubt)
Do NOT add tags-at-submit on `BacktestRunnerPage` in this brief; the PATCH-after path covers annotation. Note it as a follow-up in open-doubts.

## Constraints & traps (pasted)
- **Verify trio** (PowerShell, from `frontend-react`): `npm run lint` + `npm run test:ci` + `npm run build`. ALL green.
- **Tailwind v4**: colour only from `--ay-*` tokens; NEVER re-alias `--color-accent` in any `@theme` bridge. `font-size` only on `<body>`. Match existing components — don't hand-roll styles if a Button/Dialog/Badge exists.
- **a11y** (axe + Playwright role/name gated): every new control is a real button/input with an accessible name; the editor dialog is keyboard-reachable + focus-trapped (use the existing Dialog component, which handles this).
- **List envelopes**: `/jobs` and `/saved-views` both return `{items:[...]}` — unwrap accordingly.
- **Specs required**: extend `JobsPage.spec.tsx` (+ a small api spec) — mock the new hooks/`apiFetch`, assert: the tag filter sends `?tag=`, the editor calls `PATCH .../annotations` with the right body, save-view calls POST with the current filter, applying a view restores state. Follow the existing spec harness.
- **FE ONLY**: touch only `frontend-react/**`. No backend/Java, no migration, no ledger.
- **Vitest** globs `src/**/*.{test,spec}.{ts,tsx}` (jsdom).

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `npm run lint` — clean.
2. `npm run test:ci` — green incl. your new specs.
3. `npm run build` — succeeds (tsc + vite).
4. Commit (Conventional Commit, scope `frontend`), push `feat/d4-run-tags-fe`, `gh pr create --base main` — leave PR OPEN.

## Receipt (return in your FINAL message AND write to `docs/handoffs/2026-07-13-d4-run-tags-fe-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real outputs of lint / test:ci / build (the pass lines).
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory)**: (a) confirm you did NOT touch the existing strategy-tag `tags` state + how you kept them separate; (b) where/how the editor + saved-view controls are placed + which existing components you reused; (c) runner submit-tags scoped out; (d) any a11y concern.
- End commits with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## Stop conditions
- Verify-trio step fails for a reason outside this change.
- The saved-views control balloons the diff past a clean review -> land the tags/note + job-tag-filter half first, scope saved-views OUT with a doubt.
- No suitable existing Dialog/Popover/Badge -> say so (don't hand-roll a fragile one); land the simplest accessible fallback + flag it.
