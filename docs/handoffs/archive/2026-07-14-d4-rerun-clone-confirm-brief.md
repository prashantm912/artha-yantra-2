# Brief: d4-rerun-clone-confirm (#26 — rerun/clone + confirm-before-run + sweeps page)
Date: 2026-07-14 · Architect: Claude · Builder: Codex (unsandboxed worktree)
Ledger: D4 #26 (F2/A1/F8 — "clean") · Tier: clean (mostly FE + a trivial additive backend echo) · NO migration
Branch: `feat/d4-rerun-clone-confirm` (you are already ON it, in a worktree off origin/main)

## Goal
Three research-ergonomics gaps on the backtest surface (audit #26, tagged clean):
1. **Confirm-before-run dialog** (F8) — submitting a backtest/sweep fires immediately with no summary; add a confirm dialog showing the input summary.
2. **Rerun / clone controls** (F2) — no way to re-run a job or duplicate it with edited params.
3. **Sweeps list page** (A1) — optimizer sweeps have a backend listing (`GET /api/v1/optimizations/jobs`) but no dedicated FE page.

All backends exist EXCEPT a tiny param-echo needed for faithful rerun/clone (below). No migration.

## The deliverable

### 1. Tiny backend echo — `services/backtest-service/.../jobs/JobsController.java` `summary(...)`
The jobs list row already echoes `strategyId`/`strategyVersion`/`testFrom`/`testTo` from the `request` JSONB. Add the remaining submit params the runner needs to faithfully rerun/clone: `interval`, `initialCapital`, `seed` (read `job.request().path("interval").asText(null)`, etc., same pattern as the existing keys ~JobsController.java:151-159). These are ADDED KEYS to the already-`Map`-returning `summary(...)` handler — this does NOT add a ratchet-counted Map handler and does NOT drift the springdoc spec (generic Map values aren't enumerated). No new endpoint, no typed record, no migration. (If the jobs list `JobDto` in `frontend-react/src/api/backtests.ts` needs the fields typed, add `interval?/initialCapital?/seed?` there.)

### 2. FE — confirm-before-run dialog (`frontend-react/src/pages/backtests/BacktestRunnerPage.tsx`)
- The page submits via `useSubmitRun()` / `useSubmitSweep()` (~:39-40). Intercept BOTH submit paths: on submit, open a confirm Dialog summarising the inputs — strategy (name), window `from → to`, `interval`, `initialCapital`, `seed`; for a SWEEP also `method`, `maxTrials`, `objective`. Confirm → fire the existing mutation; Cancel → close, no submit.
- Reuse the existing shadcn `Dialog` (search `components/` — the same one JobsPage/other pages use); accessible name + focus-trap come free. Buttons are real `<button>`s with clear labels ("Confirm & run" / "Cancel").

### 3. FE — rerun / clone controls (`frontend-react/src/pages/backtests/JobsPage.tsx`)
- Add two per-row actions (a row menu or small buttons, accessible-named):
  - **Rerun** — resubmit the SAME params: build a `RunRequest` from the row (`strategyId`, `strategyVersion`, `testFrom`→from, `testTo`→to, `interval`, `initialCapital`, `seed`) and call `useSubmitRun()` (routed through the SAME confirm dialog from #2 if cheap — else submit directly with a toast). Only for `kind === 'BACKTEST'` rows (a sweep rerun is out of scope — note it).
  - **Clone** — navigate to the runner (`/backtests` runner route — find it in `App.tsx`) pre-filled with the row's params for editing. Pass the params via router state or query params; the runner reads them on mount to seed its form. "Duplicate run with edited params."
- If a field is missing on older rows (null), the clone/rerun degrades gracefully (pre-fill what's present; the runner's own validation catches the rest).

### 4. FE — sweeps list page
- New `src/pages/backtests/SweepsPage.tsx` consuming `GET /api/v1/optimizations/jobs?limit&offset` (the optimizer sweep-native list; extend/`src/api/optimizations.ts` which already exists — add a `useSweeps()` hook mirroring the `useJobs` idiom). Show newest-first: sweep id/strategy, status, trials-completed/progress, created, and the failure `reason` when present (the endpoint projects it). Link a row to its existing sweep detail if one exists (check `SweepDetailPage`/`SweepExplorer`).
- Wire a route in `App.tsx` + a nav entry in `MegaMenu.tsx` next to the Jobs/Runner links.

## Constraints & traps (pasted)
- **Verify trio** (from `frontend-react`, worktree has no node_modules → `npm ci` first): `npm run lint` + `npm run test:ci` + `npm run build`. ALL green.
- **Backend**: build `-pl services/backtest-service -am` with the direct-mvn (worktree `mvnw` can't download maven under AV TLS): `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" /c/Users/prash/.m2/wrapper/dists/apache-maven-3.9.16-bin/*/apache-maven-3.9.16/bin/mvn ... -o`; NEVER `mvnw | tail`. Run `-pl services/backtest-service -am verify -o` for the backend change (a Map-key add shouldn't break tests; confirm the `MapReturnRatchetTest` + `ContractCaptureTest` still pass — adding Map keys must NOT change the spec).
- **Tailwind v4**: colour only from `--ay-*` tokens; never re-alias `--color-accent`. Reuse existing Dialog/Button/DataTable; don't hand-roll.
- **a11y** (axe + role/name): dialogs focus-trapped, controls accessible-named.
- **List envelopes**: `/optimizations/jobs` returns its own shape (check it — `{items}` or a bare list); `/backtests/jobs` returns `{items}`.
- **Specs required**: extend `JobsPage.spec.tsx` (rerun/clone actions call submit / navigate with the right params), add `SweepsPage.spec.tsx` + a runner confirm-dialog test. Follow the existing spec harness.

## Mode & boundaries (UNSANDBOXED)
Run as the real user in THIS worktree. **HARD NEVER LIST:** deploy / docker / flyway-migrate / edit `.env`/secrets / `rm -rf` / `git reset --hard` / `git clean -fdx` / push to `main` / merge / force-push / edit an applied migration / edit the ledger or `docs/superpowers/plans/*`. Touch ONLY `frontend-react/**` + `services/backtest-service/src/main/java/in/arthayantra/backtest/jobs/JobsController.java` (the tiny echo) + this brief's receipt. STOP + doubt if anything on the NEVER list is needed.
You MAY: npm, direct-mvn, commit, push THIS branch, `gh pr create` (leave OPEN).

## Verify ladder (run ALL; paste real outputs)
1. Backend: `-pl services/backtest-service -am verify -o` — green (esp. `MapReturnRatchetTest` + `ContractCaptureTest` — the Map-key echo must NOT drift the spec; if it somehow does, STOP + doubt).
2. FE: `npm ci` → `npm run lint` → `npm run test:ci` → `npm run build` — all green incl. new specs.
3. `gh pr create --base main --head feat/d4-rerun-clone-confirm --title "feat(frontend): rerun/clone + confirm-before-run + sweeps page (#26)" --body "<what/why + the 4 parts + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-14-d4-rerun-clone-confirm-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real backend verify (`Tests run:` + confirm MapRatchet/ContractCapture green) + FE lint/test/build pass lines.
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory):** (a) whether rerun routes through the confirm dialog or submits directly; (b) how clone passes params to the runner (router state vs query) + how the runner seeds them; (c) sweep rerun scoped out; (d) the `/optimizations/jobs` response shape you consumed; (e) any a11y concern.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- Any verify step fails for a reason outside this change.
- The Map-key echo drifts the contract spec (it shouldn't) → STOP + doubt.
- The scope balloons → land confirm-dialog + sweeps-page first (pure FE), scope rerun/clone OUT + doubt.
- Anything on the NEVER list would be required.
