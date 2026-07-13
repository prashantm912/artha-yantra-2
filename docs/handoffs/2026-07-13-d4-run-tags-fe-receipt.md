# Receipt: d4-run-tags-fe (P2-2 FE)
Date: 2026-07-13 · Builder: Opus 4.8 subagent (worktree) · Branch: `feat/d4-run-tags-fe`
Brief: `docs/handoffs/2026-07-13-d4-run-tags-fe-brief.md`

## Diff summary
FE-only; 4 files (1 new). `git show --stat HEAD`:

| File | Δ |
|---|---|
| `frontend-react/src/api/backtests.ts` | +69 / −12 (JobDto tags/note; `jobTag` on useJobs+useJobsLive; useAnnotateJob; SavedView + useSavedViews/useCreateSavedView/useDeleteSavedView) |
| `frontend-react/src/pages/backtests/JobsPage.tsx` | +338 (Tags column + inline annotate Dialog, job-tag filter input, Save-view Dialog + Views dropdown) |
| `frontend-react/src/pages/backtests/JobsPage.spec.tsx` | +93 (tag-filter arg, annotate PATCH body, save-view POST, apply-view restores state) |
| `frontend-react/src/api/backtests.spec.ts` | +88 NEW (URL/body wiring for `?tag=`, PATCH, GET list, POST, DELETE) |

Total: 576 insertions, 12 deletions. Commit `a24d7e46`. Branch is exactly 1 commit ahead of `origin/main` (based on current main).

PR: <FILLED BELOW AFTER PUSH>

## Verify ladder (real outputs, from `frontend-react`)
1. `npm run lint` — clean (no output; eslint exited 0).
2. `npm run test:ci` —
   ```
    Test Files  108 passed (108)
         Tests  358 passed (358)
    Coverage summary: Lines 54.23% (934/1722)   [floor: 50% on src/core + src/api]
   ```
   (JobsPage.spec.tsx: 7 passed incl. 4 new; backtests.spec.ts: 6 passed new.)
3. `npm run build` — `✓ built in 9.52s` (tsc --strict + vite, no errors).

## Claims (with evidence)
- **`?tag=` is threaded through both jobs hooks.** `useJobs` sets `params.set('tag', jobTag)` and adds `jobTag ?? null` to the queryKey; `useJobsLive` mirrors the key + effect dep. `frontend-react/src/api/backtests.ts:74-99` (useJobs), `:106-146` (useJobsLive). — computed (asserted green: `backtests.spec.ts` "useJobs threads the per-job tag filter as ?tag=").
- **Annotate PATCHes the full set.** `useAnnotateJob` → `apiFetch('/backtests/jobs/{jobId}/annotations', {method:'PATCH', json:{tags,note}})`, invalidates `['jobs','list']` on success. `frontend-react/src/api/backtests.ts` (useAnnotateJob). — computed (asserted: `backtests.spec.ts` PATCH test + `JobsPage.spec.tsx` "edits a run’s tags + note…").
- **Saved-view hooks match the contract shapes** (`SavedViewRequest{kind,name,filter}`, `SavedViewsResponse{items}`, DELETE→204). — sourced (`contracts/gen/backtest-service.d.ts:492-504,632-634`) + computed (asserted: `backtests.spec.ts` GET/POST/DELETE tests).
- **`?tag=` query param + jobs-summary tags/note exist server-side.** — sourced (`contracts/gen/backtest-service.d.ts:1655` `tag?` on the `jobs` op; jobs summary returns a generic Map so `tags`/`note` aren't enumerated — added to the hand-written `JobDto` per the brief).
- **Global mutation-error toast surfaces 422/409.** The app's `MutationCache.onError → reportError → toast.error(error.message)` fires for any un-silenced `ApiError`; my mutations use plain `apiFetch` (not silenced), so the server's validation/duplicate message shows without bespoke handling. `frontend-react/src/main.tsx:22-31`. — sourced.
- **Editor + Save-view reuse the existing accessible Radix `Dialog`; Views uses the existing `DropdownMenu`; chips/badges reuse the app's `--ay-*` token idiom** (same class strings as the version column's latest/old badges). — sourced (`components/ui/dialog.tsx`, `components/ui/dropdown-menu.tsx`; `JobsPage.tsx` version column) + computed.

## Open-doubts (mandatory)
**(a) The existing strategy-tag `tags` state was NOT touched — how they stay separate.**
The old `const [tags, setTags]` (JobsPage.tsx ~line 137) is the STRATEGY-tag multiselect that maps via `strategies.data` → `strategyIds` (the `strategyIds` server param). I added a distinct `const [jobTagFilter, setJobTagFilter]` that feeds the NEW `?tag=` param through `useJobs`/`useJobsLive` arg 8. The two are separate state, separate params, separate controls (strategy-tag = the `Tags`-icon dropdown; job-tag = the "Job tag…" text input). Verified by `JobsPage.spec.tsx` "…feeds useJobs its own `tag` arg" which asserts arg 7 = the job tag AND arg 5 (strategyIds) stays null. `tagStrategyIds` / `allTags` / the strategy-tag dropdown are byte-unchanged.

**(b) Where the editor + saved-view controls are placed + components reused.**
- *Editor:* a per-row pencil button in a new "Tags" column (before Actions), `aria-label="Edit tags and note for job {id}"`, opens `JobAnnotateDialog` (a new local component on the existing `Dialog`/`DialogFooter`, `Button` atom, `X` remove-chip buttons). Focus returns to the pencil on close (same `onCloseAutoFocus` pattern as the existing `JobErrorDialog`).
- *Job-tag filter:* a plain text `<input aria-label="Filter by job tag">` in the toolbar, next to the strategy-tag dropdown; also added to the Clear button + offset-reset effect.
- *Saved views:* a "Save view" button (opens `SaveViewDialog`, a `Dialog` with a name input) + a "Views" `DropdownMenu` listing `useSavedViews('backtest_jobs')`; each item applies the stored filter (`applyView`) and carries a `Trash2` delete item (`onSelect` preventDefault so the menu stays open). Placed after the Reload button.
- The persisted `filter` = `{status, strategyId, jobTag, latestOnly, sort}` — it deliberately does NOT include the strategy-tag multiselect (the brief's enumerated fields), keeping the two tag concepts separate. A loaded view therefore does not alter the strategy-tag selection.

**(c) Runner submit-tags scoped OUT** (per the brief). `BacktestRunnerPage` was not touched; the PATCH-after-submit path covers annotation. `BacktestRunRequest` already carries `tags`/`note` in the generated contract (`backtest-service.d.ts:520-521`), so a future "tag at submit" is a small additive follow-up (add the fields to the runner's `RunRequest` + a tag/note input on the runner form).

**(d) a11y notes.**
- Every new control is a real `<button>`/`<input>`/`<textarea>` with an accessible name; both dialogs are the focus-trapped, titled+described Radix `Dialog`, and restore focus to their invoker on close.
- The note indicator is a `StickyNote` icon with `role="img" aria-label="This run has a note"` (+ `title` = the note text on hover); the full note is editable in the dialog.
- **One thing to verify on the live axe/e2e shard:** the "Views" dropdown renders each saved view as a flex row containing an apply menu-item + a delete menu-item (both real `DropdownMenuItem`s, so Radix roving-tabindex keeps them keyboard-reachable). This is a new interaction pattern for this repo (no prior DropdownMenu-with-per-row-delete existed); the unit spec drives it via `pointerDown` with jsdom Popper polyfills, but the axe/Playwright role-name gate on a real browser is the authoritative check. If the delete item's icon-only content trips an axe "button name" rule, it already has an `aria-label="Delete saved view {name}"` — no change expected, but flag for the live pass.
- Per-keystroke `?tag=` refetch: the job-tag input fires a query per character (matches the app's other immediate filters + TanStack `staleTime`/single-owner usage). Not debounced — acceptable, noted in case a debounce is later preferred.

## Stop conditions
None triggered. Saved-views stayed a clean two-dialog + one-dropdown addition (did not balloon); suitable `Dialog`/`DropdownMenu`/badge idioms all existed and were reused.
