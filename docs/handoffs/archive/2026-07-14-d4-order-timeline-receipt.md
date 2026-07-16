# Receipt: d4-order-timeline (#28 Part B)

**Verdict — computed:** the per-position order-event timeline is implemented, the required frontend verification ladder is green, and PR [#839](https://github.com/prashantm912/artha-yantra-2/pull/839) is OPEN for Architect review.

## Diff summary

- `frontend-react/src/components/paper/OrderEventTimeline.tsx`: +94 / -0 — new chronological lifecycle timeline with live hook wiring and loading/error/empty states.
- `frontend-react/src/components/paper/PositionDetailDrawer.spec.tsx`: +170 / -0 — drawer-level lifecycle, ordering, timestamp, detail, and state coverage.
- `frontend-react/src/components/paper/PositionDetailDrawer.tsx`: +16 / -2 — labelled drawer slot plus stale-comment correction.
- `docs/handoffs/2026-07-14-d4-order-timeline-receipt.md`: +102 / -0 — this execution receipt.
- Implementation subtotal before this receipt: 3 files, +280 / -2.
- PR: https://github.com/prashantm912/artha-yantra-2/pull/839 (`OPEN`, base `main`, head `feat/d4-order-timeline`; `gh pr view 839 --json ...`).
- Implementation commit: `9273dc0d3a93a0b060052b7496dc4fa5cab0a896`.

## Verification evidence

### Dependency install

Command (PowerShell-safe equivalent of `npm ci`): `npm.cmd ci`

```text
added 526 packages, and audited 527 packages in 12s
3 moderate severity vulnerabilities
Exit code: 0
```

The first `npm ci` spelling was intercepted by the disabled `npm.ps1` execution-policy shim before npm started; switching to `npm.cmd ci` ran the same npm operation successfully. No audit-fix mutation was attempted.

### TDD red → green

Red command: `npm.cmd test -- --run src/components/paper/PositionDetailDrawer.spec.tsx`

```text
Test Files  1 failed (1)
Tests  4 failed (4)
```

All four failures were the expected missing timeline/list/empty/loading/error UI. After implementation, the same command produced:

```text
Test Files  1 passed (1)
Tests  4 passed (4)
Duration  1.88s
```

### Required verify trio (fresh final run)

`npm.cmd run lint`

```text
> eslint src
Exit code: 0
```

`npm.cmd run test:ci`

```text
Test Files  111 passed (111)
Tests  371 passed (371)
Duration  29.82s
Statements 54.28% (1077/1984); Branches 53.94% (698/1294)
Functions 38.13% (278/729); Lines 54.25% (937/1727)
Exit code: 0
```

`npm.cmd run build`

```text
> tsc -b && vite build
✓ 3178 modules transformed.
✓ built in 7.39s
Exit code: 0
```

Vite also emitted the existing dynamic/static-import chunking warnings; they did not fail the build and this paper-only diff does not touch the named modules.

### Review

**Computed:** the independent read-only review reported no Critical, Important, or Minor issues and assessed the diff `Ready to merge: Yes`.

## Evidence-labelled claims

- **Computed:** REST events are copied and sorted oldest-to-newest by `createdAt`, with `id` as a deterministic tie-breaker (`frontend-react/src/components/paper/OrderEventTimeline.tsx:53`).
- **Sourced:** all four backend kinds have humane text labels — Opened, Bracket hit, Closed, Settled (`frontend-react/src/components/paper/OrderEventTimeline.tsx:8`).
- **Sourced:** the component reuses `usePaperEvents({ positionId })` and wires `usePaperEventsLive({ positionId })`; no API layer was added (`frontend-react/src/components/paper/OrderEventTimeline.tsx:27-29`).
- **Sourced:** IST rendering matches the drawer's `en-GB` / `Asia/Kolkata` date-time idiom (`frontend-react/src/components/paper/OrderEventTimeline.tsx:15-23`; `frontend-react/src/components/paper/PositionDetailDrawer.tsx:39-47`).
- **Sourced:** the event payload has no price field, so the UI renders the details it actually carries: `reason` and `realizedPnl` (`frontend-react/src/api/paper.ts:334`; `frontend-react/src/components/paper/OrderEventTimeline.tsx:79`).
- **Computed:** loading uses `role=status`, errors use `role=alert`, the list has an accessible name, and event kinds are text rather than colour-only (`frontend-react/src/components/paper/OrderEventTimeline.tsx:34,45,67,76`).
- **Sourced:** the new drawer section is heading-labelled and lives beside the Trade chain in the existing `gap-5` stack (`frontend-react/src/components/paper/PositionDetailDrawer.tsx:318-328`).
- **Computed:** the co-located spec proves chronology, IST timestamps, optional details, empty, loading, and error behavior (`frontend-react/src/components/paper/PositionDetailDrawer.spec.tsx:117-169`; final targeted output: 4/4 passed).
- **Computed:** final scope inspection lists only the three frontend paper files above plus this receipt; no backend, migration, environment, ledger, or dense-table file changed.
- **Assumed:** no load-bearing recalled or assumed claim was used; behavior claims above are sourced from current files or computed by this session's commands.

## Open doubts (mandatory)

- **Chronological choice:** oldest → newest, top-to-bottom. The endpoint/cache are newest-first, but lifecycle reading is clearer from Opened toward Closed/Settled; the component copy-sorts so it never mutates the query cache (`OrderEventTimeline.tsx:53`).
- **Live updates:** wired. `usePaperEventsLive({ positionId })` subscribes to `/topic/paper.events`; a matching frame updates the existing query cache and the chronological copy-sort repositions it on render. This drawer does not pass a historical `day`, so live mode is appropriate.
- **Part A:** not touched. `BacktestResultsPage`, `SweepDetailPage`, `StrategyVersionsPage`, and `RejectionsPage` are absent from the final diff.
- **Stale comment:** fixed. The drawer comment now says the adjacent timeline consumes the shipped append-only `paper.events` stream and explicitly recognizes `BRACKET_HIT` / `SETTLED` (`PositionDetailDrawer.tsx:31-32`).
- **Accessibility:** no interactive controls were added, so there is no new keyboard focus target. The section uses `aria-labelledby`, the list is named, states are announced, and kinds are textual. A dedicated axe/Playwright browser walk was not in the brief and was not run; role/name behavior is covered by Vitest.
- **Event detail shape:** `PaperEvent` carries no event price despite the brief's generic “price/reason” example. The timeline displays `reason` and realized P&L when present and does not invent a price.
- **Dependency audit:** `npm ci` reported three moderate vulnerabilities in the locked dependency tree. They pre-existed this no-dependency-change feature; no `npm audit fix` was run because it would be out of scope.
