# Brief: d4-export-fe-buttons
Date: 2026-07-13 · Architect: Claude · Builder: Codex (UNSANDBOXED worktree mode — first run)
Ledger chip: task_16f16578 (P2-3 FE half) · Tier: clean (FE-only, additive)
Branch: `feat/d4-export-fe-buttons` (you are already ON it, in a worktree off origin/main)

## Goal
The backend export endpoints shipped in #810 but have NO UI. Add **CSV + JSON download buttons** for the four core artifacts of a backtest run: trades, folds, equity (on `BacktestResultsPage`) and compare (on `BacktestComparePage`). Each button downloads the file via the existing helper. When done, a user viewing a run can download each artifact as CSV or JSON.

## Endpoints (already live)
`GET /api/v1/backtests/{runId}/export/{trades|folds|equity|compare}?format=csv|json` — file download with `Content-Disposition` + `X-Result-Truncated`/`X-Result-Rows` headers. `compare` additionally takes `strategyVersionIds=<comma-list>`.

## Design decisions already made (exploration done — do NOT re-derive)
1. **Reuse the existing helper `downloadFile(path)` in `frontend-react/src/api/client.ts`** (~line 144). It does GET → blob → honours the server filename → and ALREADY calls `warnIfExportTruncated(res)` (the truncation toast). You do NOT need to touch client.ts or add a new helper. `BASE` is `/api/v1`, so call:
   `downloadFile(\`/backtests/${runId}/export/trades?format=csv\`)` etc.
2. **Add a small export API module** `frontend-react/src/api/backtestExport.ts` with 4 thin functions
   (`exportTrades(runId, format)`, `exportFolds`, `exportEquity`, `exportCompare(strategyVersionIds, format)`)
   each delegating to `downloadFile(...)`. Keep the URL building in one place; pages call these.
3. **`BacktestResultsPage.tsx`** — the run id is `useParams()` `id` (see line ~89). Place a compact
   CSV/JSON control: (a) near the trades table (the Trades tab, tabs defined ~line 218), (b) near the
   folds tab (~line 219, only shown when `foldRows.length > 0`), (c) near the equity chart (~line 284).
   A tiny two-button pair ("CSV" / "JSON") or a small menu per artifact — match the page's existing
   button/Card idiom; find the Button component the page (or a sibling page) already imports.
4. **`BacktestComparePage.tsx`** — the compare export needs the `strategyVersionIds`. READ that page to
   find where it holds the selected version ids (it assembles the matrix client-side). Wire a CSV/JSON
   compare-download using those ids. If the ids aren't cleanly available in page state, add ONE
   download control fed by whatever the page already uses to fetch the matrix — do not invent a new
   selection mechanism; if genuinely blocked, scope compare OUT and write a doubt.
5. **a11y** (gated by axe + Playwright role/name): each control is a real `<button>` with an accessible
   name (e.g. `aria-label="Download trades as CSV"`); no icon-only-without-name. Keyboard reachable.

## Constraints & traps (pasted)
- **Verify trio** (PowerShell, from repo root): `Push-Location frontend-react; npm run lint; npm run test:ci; npm run build; Pop-Location`. ALL must pass.
- **Tailwind v4 / theme:** colour only from `--ay-*` tokens; never re-alias `--color-accent`. Match existing components; don't hand-roll new styles if a Button/DropdownMenu exists.
- **A spec test is required:** add/extend a `*.spec.tsx` (vitest + Testing Library) that mocks `downloadFile` (or `./api/backtestExport`) and asserts clicking each button calls it with the correct path + format. Follow `BacktestResultsPage.spec.tsx` as the harness template.
- Vitest config includes `src/**/*.{test,spec}.{ts,tsx}` (jsdom).
- FE-only: do NOT touch any backend/Java, any migration, or the ledger.

## Mode & boundaries (UNSANDBOXED — read carefully)
You run as the real user with full machine access. **HARD NEVER LIST — do none of these:** deploy or `docker`/`docker compose` anything; edit `.env` or any secret; `rm -rf` / `git clean -fdx` / `git reset --hard`; push to `main`; merge any PR; force-push; edit an applied migration; change backend code. Touch ONLY files under `frontend-react/`. Stay in this worktree. If a step needs anything on the NEVER list, STOP and write a doubt.
You MAY: run npm, commit, push THIS branch, and `gh pr create` (leave the PR OPEN). The Architect audits + merges + deploys.

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `npm run lint` (from frontend-react) — clean.
2. `npm run test:ci` — green, incl. your new spec.
3. `npm run build` — succeeds (tsc + vite).
4. `gh pr create --base main --head feat/d4-export-fe-buttons --title "feat(frontend): backtest export download buttons (P2-3 FE)" --body "<what/why + which pages/artifacts + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-13-d4-export-fe-buttons-receipt.md`)
- Diff summary (files + line counts) + PR URL
- Real outputs of lint / test:ci (the pass line) / build
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed
- **Open-doubts** (mandatory). Address: (a) whether compare export was wired or scoped out and why, (b) where you placed each control + why it matches the page idiom, (c) any a11y concern.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`

## Stop conditions
- Any verify-trio step fails for a reason outside this change.
- Compare `strategyVersionIds` not cleanly available (scope compare out + doubt).
- Anything on the NEVER list would be required.
- Two failures of the same approach.
