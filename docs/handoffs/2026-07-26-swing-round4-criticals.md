# Swing catch-up — round-3 review findings (2 Criticals, both money)

Branch `feat/swing-batch-catchup` at `a4f37356`. Round 3 confirmed all three previous findings fixed.
It then found two NEW Criticals — **again created by the fixes that closed the previous two.**

Read that pattern before you write code. Three rounds running, the recovery machinery has generated
the next round's failures. Both findings below are the same root shape: **a durable commit happens
BEFORE the thing that makes the effect recoverable is known.** Fix that ordering, not the symptom.

⚠️ **BOTH ARE IN THIS BATCH. Nothing deferred.** Say so loudly under open-doubts if you cannot finish.

## CRITICAL 1 — the exit target is bound AFTER the durable crash boundary

`SwingBatchEngine.java:940-959` commits the EXIT effect and expires every anchor. But exact target
discovery only happens AFTER that commit, when `SignalExited` is published (`:968-973`).

Crash before listener delivery and you get an UNDECIDED effect with no bound IDs. Recovery filters it
out (`SwingPaperEffectRepository.java:298-311`), and rerunning the engine cannot recreate the event
because the existing effect makes `expectExit` fail (`SwingBatchEngine.java:940-943`).

**The paper position then stays open permanently.** There is a second, narrower window between binding
the IDs and separately marking REQUIRED (`EngineExitListener.java:127-139`).

REQUIRED: bind the exact position IDs **and** the REQUIRED decision **atomically, BEFORE** committing
the anchor expiry. Discovery must precede the durable boundary, not follow it. If the target cannot be
determined before commit, the effect must not be committed as expirable.

## CRITICAL 2 — a shared pyramid position owned only by a later lot is missed

The effect belongs to the OLDEST governing anchor, and discovery queries only
`openForSignal(anchorSignalId)` (`EngineExitListener.java:118-122`).

If the first lot was auto-paper SKIPPED but a LATER add opened the shared paper row, that query
returns empty and the exit is marked SKIPPED/CONFIRMED (`:123-125`). The later lot's `SignalExited`
cannot rescue it, because no effect exists under that sibling anchor (`:105-107`).

**The session reaches DONE while the real paper position stays open.**

REQUIRED: bind the UNION of exact position ids associated with EVERY exited lot. Never infer "there is
no position" from the primary anchor alone — a shared pyramid row can be owned by any lot in the
group.

## Tests

Mutation-test each, as last round: neuter the one guard, keep everything compiling, show exactly one
test flips. Report the numbers.

- a crash between the anchor-expiry commit and listener delivery leaves the effect RECOVERABLE, and a
  rerun either replays it or refuses — it must never strand the position open
- an exit whose shared pyramid row was opened only by a LATER lot still finds and closes it, and the
  session does NOT reach DONE while that position is open

EDIT-ONLY: no commit, branch, push, PR, deploy or arming.
