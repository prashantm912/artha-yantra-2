# Swing catch-up — first-review findings (ALL FIVE, nothing deferred)

Branch `feat/swing-batch-catchup` (PR #1036), already carrying C1/C2/C3/C5/C6 + M1/M2 and V047/V048.
The first cross-vendor review of the whole branch returned **3 Criticals + 2 Majors**. Fix all five in
this batch.

⚠️ **DO NOT DEFER ANY ITEM.** The previous run lost C4 by deferring it between batches and never
re-requesting it. If something here cannot be done, say so LOUDLY in the receipt under open-doubts —
do not silently drop it.

Note the reviewer's framing: the branch's 1,366 passing tests do **not** exercise any of these paths.
A green suite here is not evidence.

## CRITICAL 1 — C4 was never built at all: no durable paper-effect gate

`SwingBatchCatchUp` marks a session DONE from `exitSkipped == 0` plus the batch marker, and
`SwingBatchRecorder` verifies only that marker. But:
- entry transitions the signal to TAKEN **before** publishing the paper-open event
  (`AutoPaperListener`), and paper-open failures are **swallowed** (`PaperSignalListener`);
- exits expire anchors **before** publishing (`SwingBatchEngine`), and close failures are **swallowed**
  (`EngineExitListener`).

Either leaves irreparable signal/paper divergence while the session becomes terminal.

REQUIRED: an idempotent effect/outbox ledger. A session may only reach DONE after its expected paper
effects are CONFIRMED and repairable. An unconfirmed effect leaves the session non-terminal for the
next sweep. New migration is **V049** (V047 and V048 already exist on this branch) — migrations deploy
in version order and a gap fails flyway validation for every future migration.

⚠️ **A 2nd `PaperService.openPosition` on the same `(book,exchange,tradingsymbol,side)` AVERAGES into
the open position (`newQty = qty + qty`) — it does NOT reject.** `uq_paper_positions_open` guards the
ROW, never the qty. So the ledger must CLAIM BEFORE the effect; a retry that re-opens silently doubles
size. This is the single most dangerous path in the change.

## CRITICAL 2 — the as-of partition exists only in the EXIT pass

Entry processing still uses every currently-active lot for `hasRoom` and `qualifiesForAdd`, then emits.
A later lot can therefore qualify against the older pinned close and average another entry into a
position that did not exist as-of that session; the exit pass only notices the mixed position AFTER the
money effect.

REQUIRED: filter/refuse post-session lots before EVERY entry and pyramid decision, not just at exit,
and persist the refusal.

## CRITICAL 3 — unclaimed sessions can still scroll out silently

The durable union contains only rows that already EXIST, but a rolling-window session gets no row until
`claim()`. A busy mutex skips the family; a deadline return or a pre-claim exception does the same. The
oldest session then falls outside the next day's window without ever becoming retryable — the exact
loss C3 exists to prevent.

REQUIRED: durably SEED all missing window sessions BEFORE any lock or slow work, then sweep exclusively
from non-terminal rows.

## MAJOR 1 — the market-open abort admits an entire session

The deadline is checked only between sessions, so snapshot, candle reads, entries, exits and DONE can
all continue past 09:15 once a session has started.

REQUIRED: propagate the deadline into the per-session engine and check it before every money effect,
leaving the current claim retryable when crossed.

## MAJOR 2 — the mixed-lot refusal is not reported accurately

The condition collapses to `exitSkipped++`, is then reported as a missing daily bar, and the reason is
cleared on retry — so after exhaustion only a generic "attempts exhausted" survives.

REQUIRED: a structured `MIXED_PRE_POST_LOTS:<symbol>` cause, persisted and alerted. A refusal nobody can
read is barely better than a silent approximation.

## Tests

Each of the five needs a test that FAILS against the current code. In particular:
- a post-session lot cannot influence an ENTRY or pyramid decision (Critical 2)
- a session whose paper effect is unconfirmed does NOT reach DONE, and IS retried (Critical 1)
- a retry after a partial effect does not double position size (the averaging trap)
- a window session never claimed still becomes retryable and reaches a terminal state (Critical 3)
- a deadline crossed mid-session stops before the next money effect and leaves the claim retryable
- the mixed-lot refusal survives retries with its structured reason intact (Major 2)

EDIT-ONLY: no commit, branch, push, PR, deploy or arming.
