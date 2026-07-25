# Swing batch catch-up — fix the 6 Criticals + 2 Majors

The branch `feat/swing-batch-catchup` (already checked out in this worktree) builds a durable
catch-up path so a MISSED 08:35 swing batch is replayed on a later trading day. A cross-vendor review
returned 6 Criticals + 2 Majors and the owner has decided to KEEP auto-replay and fix all of them
(the alternative, detect-and-page-only, was explicitly rejected). This is a MONEY-ADJACENT path — it
opens and closes real paper positions — so correctness beats cleverness everywhere.

The Architect has already settled every design fork below. Implement these decisions; do not
re-litigate them. If the CODE contradicts a premise here, say so in your BRIEF verdict and implement
against the code.

## C1+C2 — evaluate only lots that existed as of the pinned session
`SwingBatchEngine.java:575-585,605,623-624,699-716` evaluates ALL currently-active lots without
filtering them as of `requiredBarDate`. Replaying a Friday anchor can therefore evaluate and settle a
lot using Thursday's truncated bar, and Manas can close an averaged position containing lots opened
AFTER the replayed session.

DECISION: filter to lots whose entry is on or before `requiredBarDate`. A lot opened after the pinned
session is NOT evaluated. If an averaged/pyramided position mixes pre- and post-session lots so the
position cannot be cleanly evaluated as-of, REFUSE that lot, record it, and alert — never evaluate it
approximately. Silent partial evaluation of a money path is the worst outcome available.

## C3 — durable PENDING rows must never fall out of the window
`SwingBatchCatchUp.java:183-203,260-269` rebuilds only the latest `maxAttempts + 2` sessions, so in a
7-session outage the oldest session gets one attempt, drops out of the rolling window, and never
reaches a terminal state.

DECISION: every sweep UNIONs all non-terminal rows (PENDING + stale RUNNING) with the rolling window,
and keeps sweeping them until each reaches DONE or ABANDONED. A row leaves the sweep only by becoming
terminal, never by scrolling out of a window. Add an explicit ABANDONED terminal state with a reason.

## C4 — the claim is not atomic with the paper money effect
Entry state commits at `SwingBatchEngine.java:532-544` BEFORE `SignalEmitted` at `551-558`; exit state
and anchor expiry commit at `691-706` before `SignalExited` at `707-717`. Paper failures are SWALLOWED
at `PaperSignalListener.java:61-63` and `EngineExitListener.java:41-44`, while
`SwingBatchCatchUp.java:237-242` can still declare the session DONE. So a crash or a swallowed failure
leaves an active signal with no paper position, or an open position with no active anchor, and
stale-lease replay cannot reconstruct the lost event.

DECISION, two parts:
(a) CLAIM BEFORE THE EFFECT. Per the house rule: "Idempotency for anything that opens positions must
    claim BEFORE the open (atomic marker/lock), never rely on the unique index." A 2nd
    `PaperService.openPosition` on the same `(book,exchange,tradingsymbol,side)` AVERAGES into the open
    position (pyramiding, `newQty = qty + qty`) — it does NOT reject — so `uq_paper_positions_open`
    guards the ROW, never the qty, and a naive retry silently doubles size. Write the durable claim
    row first, then perform the effect.
(b) GATE COMPLETION ON THE DURABLE EFFECT. A session may only be marked DONE after verifying the
    expected paper effect actually exists (query it back). If it does not, the session stays PENDING
    for the next sweep. Do NOT trust the event having been published.

## C5 — DISARMED records current intent, not session-time intent
`SwingBatchCatchUp.java:139-145,170-176` infers every historical session's armed state from the family
flag as read at 08:35 TODAY. Re-enabling a family before the sweep replays a session that was
deliberately disabled; disabling it suppresses a session that was armed and genuinely missed.

DECISION: persist the effective arming state per scheduled session, at the moment that session is
scheduled. The catch-up reads THAT row. **Fail closed**: if no arming row exists for a session (e.g.
sessions from before this migration), the catch-up REFUSES to replay it and records the reason. Never
infer historical intent from a current flag.

## C6 — readiness and candidates come from different HTTP snapshots
`SwingBatchCatchUp.java:230-232` checks `inputsAsOf()`, then `SwingBatchEngine.java:195` separately
calls `candidates()`; both funnel clients fetch the endpoint independently
(`ManasFunnelClient.java:58-95`, `MinerviniFunnelClient.java:53-90`). A screen transition between the
two reads can validate session X while supplying session Y's names, and a transient second-read
failure becomes an empty candidate list while the session is still marked complete.

DECISION: fetch ONE immutable `{screenDate, candidates}` snapshot and pass that exact object into the
engine. The engine must not re-fetch. An empty candidate list from a FAILED fetch must be
distinguishable from a legitimately empty screen — the failed case must not complete the session.

## M1 — the catch-up can starve the 15-second SL/TP scheduler
The unqualified `@Scheduled` at `SwingBatchCatchUp.java:120` uses the single default scheduler shared
with `PaperScheduler.java:32`. The sweep does synchronous multi-session DB + HTTP work, the funnel
requests have no explicit timeout, and the start-only market-hours check does not stop a run that
crosses 09:15.

DECISION: bind the catch-up to its own dedicated single-thread scheduler (this service's analogue of
market-data's `MonitorSchedulingConfig` pools), give the funnel clients explicit connect+read
timeouts, and abort the sweep at the market-open deadline mid-run, not only at entry. Keep
`SwingRunMutex` for run serialisation.

## M2 — manual close does unrelated enrichment before exiting
`PaperController.java:271-274` calls the full `positionDetail()` merely to obtain the book, and that
path does instrument-metadata HTTP and other reads at `PaperService.java:809-834`. An emergency
explicit-price close can therefore hang or fail before ever reaching `closePosition`.

DECISION: get the book from a lightweight local position query; keep the audit enrichment AFTER the
close.

## Migration
The branch already carries V047. Any new table is **V048** — migrations deploy IN VERSION ORDER and a
gap makes flyway-init fail validation and block every future migration. Applied migrations are
checksum-locked: never edit one in place.
