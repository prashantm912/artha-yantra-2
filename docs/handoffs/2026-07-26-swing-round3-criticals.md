# Swing catch-up — round-2 review findings (2 Criticals + 1 Major)

Branch `feat/swing-batch-catchup` at `ca315528`. Round 2 confirmed 3 of 5 prior findings fully fixed
(entry as-of partition, seeding-before-mutex, deadline-per-money-effect). Two are only PARTIALLY fixed
because of the two new Criticals below — **both of which were introduced by the round-1 fixes
themselves.** That is worth understanding before you touch anything: the recovery machinery is what
created these, so a fix that adds more recovery surface needs to be weighed carefully.

⚠️ **ALL THREE ARE IN THIS BATCH. Nothing is deferred.** If you cannot complete one, say so LOUDLY
under open-doubts.

## CRITICAL 1 — repair can manufacture a paper entry that was never supposed to happen

Every emitted entry creates an EXPECTED effect **unconditionally** (`SwingBatchEngine.java:674-697`),
but the auto-paper decision — is auto-paper even enabled, does the book/risk lookup succeed — is only
made LATER, in `AutoPaperListener.java:57-65`.

So if the process dies before event delivery, or the book/risk lookup throws before its `try` block,
the effect row exists with **no durable record of whether the effect was REQUIRED or SKIPPED**. Repair
then publishes `SignalTaken` directly (`SwingBatchCatchUp.java:467-483`) and
`PaperSignalListener.java:102-131` opens the position **without rechecking the toggle**.

Net: **an auto-paper-OFF signal can become a real paper position after a restart.** That is real money
created by the recovery path.

REQUIRED: persist a REQUIRED/SKIPPED decision. An UNDECIDED effect must fail CLOSED — never replayed.
Replay only effects explicitly marked REQUIRED.

## CRITICAL 2 — an old EXIT-effect retry can close a NEWER position on the same key

Reconciliation defines "this exit was applied" as *"there is no currently open position with this
book/symbol/side"* (`SwingPaperEffectRepository.java:336-345`).

If the original close COMMITTED but its confirmation was lost, and the symbol was later reopened, the
old effect looks unapplied. `EngineExitListener.java:125-152` then discovers and closes the CURRENT
position — including exposure opened AFTER the pinned session, and post-session signal IDs.

That violates as-of settlement, which is the entire point of C1/C2, and it is the mirror image of the
averaging trap on the close side. Note this came in with the round-1 "shared-key exit reconciliation"
addition.

REQUIRED: bind the effect to the EXACT paper-position id(s) before closing, and reconcile/CAS on those
ids. Never re-discover the target by the reusable (book, symbol, side) key — that key is reused by
design, so it can never identify a specific historical position.

## MAJOR — a refused run still writes the canonical completion marker

A mixed/post-session refusal leaves `exitSkipped == 0`, and marker eligibility
(`SwingBatchRecorder.java:194-204`) ignores `refusalReasons`. Catch-up marks the row PENDING
(`:394-401`), but the next sweep sees `runs.hasRun` and marks it DONE without rerunning (`:271-283`).

So the refusal is recorded and then silently overridden — the run completes anyway.

REQUIRED: require `result.refusalReasons().isEmpty()` before recording the marker.

## Tests

Each needs a test that fails without its fix. A wholesale revert will NOT compile (the fixes changed
the API surface), so use a targeted MUTATION — neuter the one guard, keep everything compiling, prove
exactly one test flips. That is the method that worked last round.

- an UNDECIDED effect is never replayed, and an auto-paper-OFF signal never becomes a position
- an exit effect whose symbol was REOPENED does not close the newer position
- a refused run does NOT become DONE on the next sweep

EDIT-ONLY: no commit, branch, push, PR, deploy or arming.
