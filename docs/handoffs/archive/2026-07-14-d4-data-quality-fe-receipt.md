# Receipt: d4-data-quality-fe (P2-4 FE dashboard)

- Date: 2026-07-14
- Branch: `feat/d4-data-quality-fe`
- Feature commit: `6f607ca846fcee9c7321424f0122840ade4d7f50`
- PR: https://github.com/prashantm912/artha-yantra-2/pull/826 (**OPEN**)
- Tier: clean (FE-only, read-only, additive)

## Verdict

- **[computed]** The requested Data Quality page, API hook, date selection, three scope sections,
  distinct Bhavcopy EQ summary, route, and Data Ops navigation entry are implemented. Evidence:
  `frontend-react/src/api/dataQuality.ts:5-30`,
  `frontend-react/src/pages/dataops/DataQualityPage.tsx:14-179`,
  `frontend-react/src/App.tsx:47,337`, and
  `frontend-react/src/components/MegaMenu.tsx:152`.
- **[computed]** The fresh final frontend verify trio is green: ESLint exit 0; Vitest 109/109 files
  and 361/361 tests; TypeScript + Vite build exit 0 with 3,176 modules transformed and
  `✓ built in 9.10s`.
- **[computed]** The PR is open and was not merged or deployed. Evidence: `gh pr create` returned
  `https://github.com/prashantm912/artha-yantra-2/pull/826`; no merge/deploy command was run.

## Diff summary

| File | Line delta | Purpose |
|---|---:|---|
| `frontend-react/src/App.tsx` | +2 | Import and register `/data-ops/data-quality`. |
| `frontend-react/src/api/dataQuality.ts` | +32 | Typed report DTOs and 60-second TanStack Query hook. |
| `frontend-react/src/components/MegaMenu.tsx` | +1 | Add Data Quality beside Ingest Health in Data Ops. |
| `frontend-react/src/pages/dataops/DataQualityPage.spec.tsx` | +113 | Scope/status/coverage/rollup, date-query, and empty-state coverage. |
| `frontend-react/src/pages/dataops/DataQualityPage.tsx` | +183 | Read-only grouped completeness dashboard. |
| `docs/handoffs/2026-07-14-d4-data-quality-fe-receipt.md` | +192 | This receipt. |

Frontend implementation commit total: 5 files, 331 insertions, 0 deletions.

## Verification evidence

All npm commands ran from `frontend-react`. Native PowerShell blocked the `npm.ps1` wrapper before
npm started, so the equivalent executable `npm.cmd` was used for every npm invocation.

### Dependency install

- Command: `npm.cmd ci`
- Exit: `0`

```text
added 526 packages, and audited 527 packages in 17s
3 moderate severity vulnerabilities
```

The audit finding was reported by npm and was not mutated with `npm audit fix` because dependency
updates are outside this brief.

### TDD RED

- Command: `npm.cmd test -- --run src/pages/dataops/DataQualityPage.spec.tsx`
- Exit: `1` before implementation, for the intended missing feature.

```text
Test Files  1 failed (1)
Tests  no tests
Error: Failed to resolve import "./DataQualityPage.tsx"
```

### Focused GREEN (final)

- Command: `npm.cmd test -- --run src/pages/dataops/DataQualityPage.spec.tsx`
- Exit: `0`

```text
Test Files  1 passed (1)
Tests  3 passed (3)
Duration  2.40s
```

### Lint (fresh final run)

- Command: `npm.cmd run lint`
- Exit: `0`

```text
> frontend-react@0.0.0 lint
> eslint src
```

### Full test suite with coverage (fresh final run)

- Command: `npm.cmd run test:ci`
- Exit: `0`

```text
Test Files  109 passed (109)
Tests  361 passed (361)
Duration  36.49s
Statements   : 54.31% (1076/1981)
Branches     : 53.94% (698/1294)
Functions    : 38.23% (278/727)
Lines        : 54.29% (936/1724)
```

### Production build (fresh final run)

- Command: `npm.cmd run build`
- Exit: `0`

```text
> tsc -b && vite build
✓ 3176 modules transformed.
✓ built in 9.10s
```

Vite repeated the repository's existing mixed static/dynamic-import warnings for `session.store.ts`
and several Multiple Window pages; none names or is introduced by the Data Quality files.

### Review

- **[computed]** An independent read-only reviewer found no Critical or Important issues. Its Minor
  observation that FAIL/coverage assertions were not tied to a specific row was addressed by scoping
  them to the NIFTY 50 row in the Intraday 1m table
  (`frontend-react/src/pages/dataops/DataQualityPage.spec.tsx:81-86`).
- **[computed]** The reviewer noted route/nav have no dedicated integration test. They are line-audited
  and production-build-gated; this is retained as an open doubt below rather than expanding scope.

## Evidence-labelled claims

- **[sourced]** `CompletenessRow` and `CompletenessReport` match the brief's hand-written DTO shape.
  Evidence: `frontend-react/src/api/dataQuality.ts:5-20`.
- **[sourced]** `useDataQuality` keys latest/date queries separately, calls the exact completeness path
  with an optional `?date=`, and slow-polls every 60 seconds. Evidence:
  `frontend-react/src/api/dataQuality.ts:23-30`.
- **[computed]** The page always presents the humane fixed scope order Chain capture, Intraday 1m,
  Bhavcopy EQ, and filters the endpoint's `.items` envelope into those sections. Evidence:
  `frontend-react/src/pages/dataops/DataQualityPage.tsx:14-18,128-139`.
- **[computed]** Status is conveyed with visible `OK`/`FAIL` text as well as bull/bear token tones;
  coverage and present/expected counts are numeric text. Evidence:
  `frontend-react/src/pages/dataops/DataQualityPage.tsx:20-37,54-81`.
- **[computed]** The Bhavcopy `__SUMMARY__` row is removed from the symbol table and rendered as a
  separate EQ-symbol rollup with count, coverage, status, and detail. Evidence:
  `frontend-react/src/pages/dataops/DataQualityPage.tsx:84-93,106-123` and the regression assertion at
  `frontend-react/src/pages/dataops/DataQualityPage.spec.tsx:86-87`.
- **[computed]** The accessible Report date input changes the real hook URL, and an empty `items` list
  produces a friendly state. Evidence: `frontend-react/src/pages/dataops/DataQualityPage.tsx:143-179`
  and `frontend-react/src/pages/dataops/DataQualityPage.spec.tsx:91-113`.
- **[sourced]** The route and nav entry are siblings of Ingest Health in their existing Data Ops
  locations. Evidence: `frontend-react/src/App.tsx:336-337` and
  `frontend-react/src/components/MegaMenu.tsx:151-152`.
- **[assumed]** The live endpoint continues to honor the response contract supplied in the brief and
  generated declarations. This builder did not call the authenticated live endpoint; UI behavior is
  verified against the supplied shape and mocked fetch boundary.
- **[recalled]** None. No load-bearing claim relies on unverified memory.

## Open doubts (mandatory)

### (a) `__SUMMARY__` rollup versus dropout rows

The `bhavcopy_eq` `__SUMMARY__` row is a distinct token-only stat immediately below the Bhavcopy EQ
heading: `present/expected EQ symbols`, coverage, OK/FAIL, and detail. It is excluded from the table;
every non-summary row is listed beneath it. The table copy says “No individual symbol dropouts” when
the summary is the only Bhavcopy row. If the backend later emits non-summary rows that are not
dropouts, this page will still list them because the contract identifies only `__SUMMARY__` specially.

### (b) Reused Ingest Health components and idioms

The page reuses `LoadBeat`, `PageHeader`, `BeatBlock`, `QueryState`, and `DataTable`, plus the same
`text-bull ring-bull/40` / `text-bear ring-bear/40` text-pill idiom from Ingest Health. It also reuses
the existing native `DateInput` atom. `FreshnessBadge` was not used: `computedAt` is report computation
time, but the brief supplies no freshness threshold, so labeling it fresh/stale would invent policy.

### (c) Route and nav placement

The route is directly after the Ingest Health route and the nav item directly after Ingest Health in
the same Data Ops group. The brief mentions a “lazy-import + path idiom,” but the actual
`IngestHealthPage` uses a direct import and direct route element; Data Quality mirrors that current
code. There is no dedicated App/MegaMenu integration test; TypeScript/Vite build and line review are
the available gates for these two mechanical entries.

### (d) Accessibility

The date control uses the shared native input with `aria-label="Report date"`; each table/scroll region
has a scope-specific accessible name; headings label their sections; statuses say `OK`/`FAIL` and do
not rely on color. The requested verify ladder did not include a running-stack Playwright/axe pass, and
the NEVER-list forbids bringing up Docker, so this receipt cannot claim runtime axe verification for
the new route. The unit spec does exercise heading/table/input names through Testing Library roles.

### Other observations

- `npm ci` reported three moderate dependency vulnerabilities. They pre-exist the locked install and
  were not changed in this FE-only feature.
- The page was not deployed and was not exercised against the live authenticated endpoint, by design;
  the Architect owns merge/deploy/live verification.
- No stop condition triggered: all verify steps passed, the mirror components were reusable, and no
  NEVER-list operation was required.
