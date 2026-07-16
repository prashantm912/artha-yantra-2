# Brief: d4-data-quality-fe (P2-4 FE dashboard)
Date: 2026-07-14 · Architect: Claude · Builder: Codex (unsandboxed worktree)
Ledger: D4 P2-4 FE half (data-quality dashboard, audit §9.6) · Tier: clean (FE-only, read-only, additive)
Branch: `feat/d4-data-quality-fe` (you are already ON it, in a worktree off origin/main)

## Goal
The P2-4 **backend** shipped (#823, live): a nightly per-symbol completeness report at `GET /api/v1/market/health/completeness?date=YYYY-MM-DD`. There is NO UI for it. Add a **read-only Data-Quality dashboard page** (audit item — "data-quality dashboard, §9.6") that shows the latest report grouped by scope with coverage + pass/fail. Mirror the existing **Ingest-Health page** exactly in structure and idiom.

When done: a data-ops user opens the Data-Quality page and sees, for the latest settled trading day, the completeness of chain-capture / intraday-1m / bhavcopy-EQ, with coverage % and OK/FAIL, and can pick a past date.

## The endpoint (live, #823)
`GET /api/v1/market/health/completeness?date=YYYY-MM-DD` (date optional → latest materialized day) →
```
{ "date": "2026-07-11", "items": [
  { "scope": "chain_capture"|"intraday_1m"|"bhavcopy_eq", "symbol": "NIFTY 50"|"__SUMMARY__"|...,
    "expected": 187, "present": 180, "coveragePct": 96.26, "ok": true, "detail": null|"absent vs prior day ...",
    "computedAt": "..." } ] }
```
The generated TS types are already in `contracts/gen/market-data-service.d.ts` (search `completeness`) — you may reference them, but the app uses hand-written DTOs in `src/api/*` (match that style).

## The deliverable (mirror the Ingest-Health page throughout)
### 1. `src/api/dataQuality.ts` — mirror `src/api/ingestHealth.ts` EXACTLY in shape
- Interfaces `CompletenessRow` (scope, symbol, expected:number, present:number, coveragePct:number|null, ok:boolean, detail:string|null, computedAt:string) + `CompletenessReport` (date:string|null, items:CompletenessRow[]).
- Hook `useDataQuality(date?: string | null)` → `useQuery({ queryKey:['data-quality', date ?? 'latest'], queryFn: () => apiFetch<CompletenessReport>(\`/market/health/completeness${date ? \`?date=${date}\` : ''}\`), refetchInterval: 60_000 })`. (Same `apiFetch` + slow-poll idiom as `useIngestHealth`.)

### 2. `src/pages/dataops/DataQualityPage.tsx` — mirror `IngestHealthPage.tsx`
- READ `IngestHealthPage.tsx` first and follow its layout/heading/loading/error/empty idioms + component reuse (DataTable or its table idiom, badges, `--ay-*` tokens, the FreshnessBadge if apt).
- Header shows the report `date` + a plain date `<input type="date">` (accessible-named) to view a past day (drives `useDataQuality(date)`).
- Group the `items` by `scope` (three sections: Chain capture / Intraday 1m / Bhavcopy EQ — humane labels). Within each: a row per symbol showing symbol, present/expected, a coverage bar or `coveragePct`%, and an OK/FAIL status pill (green/red from `--ay-*` tokens, mirroring the IngestHealth GREEN/RED verdict pills). Show `detail` when present (e.g. the dropout reason) — e.g. as a title/subtext.
- The `bhavcopy_eq` `__SUMMARY__` row is the rollup — render it distinctly (e.g. a section header stat: "present/expected EQ symbols"), with the individual dropout rows listed under it.
- Empty state (no report yet / `items: []`): a friendly "no report for this day" message, not a crash.

### 3. Wire the route + nav (mirror how IngestHealthPage is wired)
- Add the route in `src/App.tsx` next to the ingest-health route (find `IngestHealthPage`'s route — reuse its lazy-import + path idiom; suggest path `/data-ops/data-quality`).
- Add a nav entry in `src/components/MegaMenu.tsx` in the SAME section/group as the ingest-health link (find "ingest-health" there and add a sibling "Data Quality" item).

### 4. Spec — mirror `IngestHealthPage.spec.tsx`
- Add `DataQualityPage.spec.tsx`: mock `useDataQuality` (or `apiFetch`), render, assert the three scope sections appear, a FAIL row shows FAIL, the coverage % renders, and the date input drives a refetch with `?date=`. Follow the ingest-health spec harness.

## Constraints & traps (pasted)
- **Verify trio** (PowerShell, from `frontend-react`): `npm run lint` + `npm run test:ci` + `npm run build`. ALL green. (The worktree has no node_modules — run `npm ci` first.)
- **Tailwind v4**: colour ONLY from `--ay-*` tokens; NEVER re-alias `--color-accent`. Match existing components; do not hand-roll styles if a Badge/Table/Card exists — reuse what IngestHealthPage uses.
- **a11y** (axe + Playwright role/name gated): the date input + any control has an accessible name; status pills convey state by text, not colour alone (put "OK"/"FAIL" text, mirroring IngestHealth).
- **List envelope**: the endpoint returns `{date, items:[...]}` — not an `{items}`-only envelope; read `.items`.
- FE-ONLY: touch only `frontend-react/**`. No backend/Java, no migration, no ledger.
- Vitest globs `src/**/*.{test,spec}.{ts,tsx}` (jsdom).

## Mode & boundaries (UNSANDBOXED)
Run as the real user in THIS worktree. **HARD NEVER LIST:** deploy / docker / edit `.env`/secrets / `rm -rf` / `git reset --hard` / `git clean -fdx` / push to `main` / merge / force-push / edit an applied migration / edit the ledger or `docs/superpowers/plans/*`. Touch ONLY `frontend-react/**` + this brief's receipt. You MAY: npm, commit, push THIS branch, `gh pr create` (leave OPEN). The Architect audits + merges + deploys.

## Verify ladder (run ALL, paste real outputs into the receipt)
1. `npm ci` (fresh worktree) then `npm run lint` — clean.
2. `npm run test:ci` — green incl. your new spec.
3. `npm run build` — succeeds (tsc + vite).
4. `gh pr create --base main --head feat/d4-data-quality-fe --title "feat(frontend): data-quality dashboard page (P2-4 FE)" --body "<what/why + which page mirrored + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-14-d4-data-quality-fe-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real lint / test:ci / build pass lines.
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory)**: (a) how you rendered the `__SUMMARY__` rollup vs the individual dropout rows; (b) which existing components/idioms you reused from IngestHealthPage; (c) route + nav placement; (d) any a11y concern.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- Any verify-trio step fails for a reason outside this change.
- `IngestHealthPage` uses a component/pattern you cannot cleanly reuse → say so, land the simplest accessible table + flag it.
- Anything on the NEVER list would be required.
