# Brief: d4-order-timeline (#28 Part B — order-event timeline)
Date: 2026-07-14 · Architect: Claude · Builder: Codex (unsandboxed worktree)
Ledger: D4 #28 Part B (audit L2) · Tier: clean (FE-only, additive, uses existing endpoint+hooks) · NO backend, NO migration
Branch: `feat/d4-order-timeline` (you are already ON it, in a worktree off origin/main)

## Goal
A paper position has no visible event lifecycle (audit L2). The backend + FE data layer already exist but nothing renders them. Add a **per-position order-event timeline** (OPENED → BRACKET_HIT → CLOSED → SETTLED) inside the existing position detail drawer. When done: opening a paper position's detail shows its event chain with timestamps, live-updating.

**Scope = Part B ONLY.** #28 Part A (mobile card-mode for the 4 dense tables) is DEFERRED — do NOT touch BacktestResultsPage/SweepDetailPage/StrategyVersionsPage/RejectionsPage tables; that's an involved separate refactor.

## Everything you need already exists (do NOT build backend/hooks)
- **Backend endpoint (live):** `GET /api/v1/paper/events?positionId=&book=&day=&limit=&offset=` → newest-first `PaperEventsResponse` (`PaperController.java:187-201`). Table `paper_events` (V038; kinds `OPENED|CLOSED|BRACKET_HIT|SETTLED`).
- **FE hooks (already written, currently UNUSED):** `frontend-react/src/api/paper.ts` — `PaperEvent` type (`:334-346`), `usePaperEvents({ positionId })` (`:362-373`), `usePaperEventsLive` WS appender (`:381-407`). **Reuse these — do NOT add new API code** (unless the live hook needs a tiny wiring tweak).

## The deliverable

### 1. A timeline component
- Add an `OrderEventTimeline` (new file `frontend-react/src/components/paper/OrderEventTimeline.tsx`, or inline in the drawer if small) that takes a `positionId`, calls `usePaperEvents({ positionId })` (+ `usePaperEventsLive` for live append if cheap), and renders the events as a vertical timeline: each node = the event kind (humane label: Opened / Bracket hit / Closed / Settled), its timestamp (IST, match the app's time formatting), and any detail the `PaperEvent` carries (price/reason). Newest-first or oldest-first — pick chronological (oldest→newest top-to-bottom reads as a lifecycle) and note the choice.
- Empty/loading/error states (no events yet → a friendly "no events recorded" line, not a crash).

### 2. Slot it into the drawer
- `frontend-react/src/components/paper/PositionDetailDrawer.tsx` — add the timeline as a new `<section>` in the existing `flex flex-col gap-5` stack (near the "Trade chain" section ~`:252-315`). Give it a heading + `aria-labelledby` mirroring the sibling sections.
- Match the drawer's card idiom: `rounded-md border border-ay-border bg-surface-1 p-2 text-xs` (`:83,:259,:301`); colour only from `--ay-*` tokens.
- **Fix the now-stale comment** at `PositionDetailDrawer.tsx:30-31` ("Bracket-HIT events aren't separately recorded (paper.events is a later slice)") — that slice shipped (V038); update/remove the outdated honest-degrade note so it doesn't mislead.

### 3. Spec
- Add/extend `PositionDetailDrawer.spec.tsx` (or a new `OrderEventTimeline.spec.tsx`): mock `usePaperEvents` to return a few events (OPENED, BRACKET_HIT, CLOSED), render, assert the timeline shows each kind + timestamp in order, and the empty state renders when no events. Follow the existing spec harness (`PaperPage.spec.tsx` / co-located `*.spec.tsx`).

## Constraints & traps (pasted)
- **Verify trio** (from `frontend-react`, worktree has no node_modules → `npm ci` first): `npm run lint` + `npm run test:ci` + `npm run build`. ALL green.
- **Tailwind v4**: colour only from `--ay-*` tokens; never re-alias `--color-accent`. Reuse the drawer's card idiom + any existing timeline/list pattern; don't hand-roll heavy styles.
- **a11y** (axe + role/name): the timeline section is `aria-labelledby` a heading; event kinds conveyed by TEXT (not colour/icon alone); keyboard-reachable if interactive.
- **List envelope:** `/paper/events` returns a `PaperEventsResponse` (check its exact shape in `paper.ts` — it's already typed; read `.items` or the field the hook exposes).
- FE-ONLY: touch only `frontend-react/**`. No backend/Java, no migration, no ledger. Do NOT touch the 4 dense tables (Part A deferred).
- Vitest globs `src/**/*.{test,spec}.{ts,tsx}` (jsdom).

## Mode & boundaries (UNSANDBOXED)
Run as the real user in THIS worktree. **HARD NEVER LIST:** deploy / docker / edit `.env`/secrets / `rm -rf` / `git reset --hard` / `git clean -fdx` / push to `main` / merge / force-push / edit an applied migration / edit the ledger or `docs/superpowers/plans/*`. Touch ONLY `frontend-react/**` + this brief's receipt. STOP + doubt if anything on the NEVER list is needed.
You MAY: npm, commit, push THIS branch, `gh pr create` (leave OPEN).

## Verify ladder (run ALL; paste real outputs)
1. `npm ci` → `npm run lint` — clean.
2. `npm run test:ci` — green incl. your new spec.
3. `npm run build` — succeeds.
4. `gh pr create --base main --head feat/d4-order-timeline --title "feat(frontend): paper order-event timeline (#28 Part B)" --body "<what/why + drawer slot + uses existing usePaperEvents + test evidence + receipt path>"` — leave OPEN.

## Receipt (write to `docs/handoffs/2026-07-14-d4-order-timeline-receipt.md`)
- Diff summary (files + line counts) + PR URL.
- Real lint / test:ci / build pass lines.
- Claims WITH evidence (file:line), labeled computed|sourced|recalled|assumed.
- **Open-doubts (mandatory):** (a) chronological order choice + why; (b) whether you wired the live `usePaperEventsLive` appender or just the polled `usePaperEvents`; (c) that you did NOT touch the Part-A tables; (d) the stale-comment fix; (e) any a11y concern.
- End commits with `Co-Authored-By: OpenAI Codex <noreply@openai.com>`.

## Stop conditions
- Any verify step fails for a reason outside this change.
- `usePaperEvents`/`PaperEvent` isn't shaped as the recon says (re-read `paper.ts:334-407`) — adapt or doubt.
- Anything on the NEVER list would be required.
