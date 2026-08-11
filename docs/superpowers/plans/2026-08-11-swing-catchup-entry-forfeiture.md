# Swing catch-up: entry forfeiture, the attempt budget, and the 19:00 schedule move

**Status:** PLAN. Not built. Written 2026-08-11 ~23:00 IST after cross-vendor review round 2 on
PR #1351 returned three unaddressed Criticals plus a new one.

**Verdict first:** two of the four findings are **confirmed against the code by reading it**, not
taken on the reviewer's word, and one of those two is **live in production right now** and has
nothing to do with the schedule change. The third is real but pre-existing and wider than this work.
The fourth was introduced by the morning poll in #1351 and is **not deployed**, so it is a design
defect to remove rather than an incident.

---

## §1 — Evidence

### 1.1 CONFIRMED, LIVE TODAY · the catch-up marks a session DONE with entries never taken

`SwingBatchCatchUp` (the terminal branch of the outcome ladder):

```java
} else if (run.exitSkipped() == 0 && outcome.markerRecorded()) {
  state.markDone(batch, session);
  alert(doctrine, "catch-up ran for " + session,
      "... " + (entriesReady ? "" : " Entries were SKIPPED (the funnel is not as-of " + session
          + " — its screen never landed); the held stops WERE evaluated."));
}
```

`markDone` is reached with `entriesReady == false`. **The alert text on that very line says entries
were skipped.** `SwingCatchUpStateRepository.claim` returns empty for a terminal row, so a screen
landing later can never recover that session's entries.

**The codebase already knows this rule and applies it one branch earlier.** The `armingUnknown`
branch keeps the session PENDING, commented:

> *"Exits ran, entries deliberately did not... The session is NOT done — it still owes its entries...
> `markDone` here would have forfeited the very pass this run deferred."*

The identical argument holds for `!entriesReady`. The two reasons entries can be withheld were
treated differently for no stated reason.

- **Claim label:** sourced (read at `SwingBatchCatchUp` outcome ladder + `SwingCatchUpStateRepository.claim`).
- **Live exposure:** real but not currently firing. `strategy.swing_catchup_runs` holds only two
  rows, both `ABANDONED` from 2026-07-17 — measured tonight. The trigger is a session whose screen
  never landed, which is exactly the 2026-08-10 host-outage shape.
- **Not introduced by #1351.** The schedule move raises the *frequency* of the trigger (more
  late-NSE nights where the screen has not landed by the morning pass); it does not create it.

### 1.2 CONFIRMED · the morning poll in #1351 burns the attempt budget before it is useful

`SwingCatchUpStateRepository.claim` does `attempts = swing_catchup_runs.attempts + 1` on **every**
successful claim of a PENDING row. `artha.swing.catchup-max-attempts` defaults to **5**, and:

```java
if (claim.get().attempts() > maxAttempts) { state.markAbandoned(...); }
```

`markAbandoned`'s own alert says *"its stop for that session is UNRECOVERABLE"*.

#1351 polls `0 5,15,25,35,45,55 8-9` — 08:05, 08:15, 08:25, 08:35, 08:45, 08:55. On a session that
stays PENDING (precisely the case the poll exists for), attempt 6 lands at **08:55**, before the
09:00–09:14 window that was the entire reason for polling. **The poll converts "recoverable" into
"ABANDONED" fifty minutes early.**

⚠️ **This interacts with §1.1's fix and gets worse.** Making `!entriesReady` keep the session PENDING
adds a new way to consume attempts, so fixing forfeiture without fixing the budget accelerates
abandonment.

- **Claim label:** computed from the SQL + the default + the cron.
- **Not deployed.** #1351 has not merged. Nothing to hotfix.

### 1.3 Pre-existing, NOT verified here · the 09:15 deadline is not atomic with entry emission

Reviewer's claim: the final deadline check happens before `emitEntry`, while sizing, signal
insertion, publication and synchronous event handling happen after it with no second authoritative
check, so a 09:05 pass can cross 09:15 in flight and still create an entry.

**Labelled `recalled` — I have not read that path this session.** It must be verified before any fix
is designed. It is pre-existing and independent of the schedule.

### 1.4 Pre-existing · NSE pull success is not destination-trade-date-aware

Reviewer's claim: participant OI can fall back to an older file, FII/DII rows are not validated
against the intended trade date, and `IngestCoverageCanary` treats any same-day successful run as
GREEN. **Not verified this session.** Wider than the schedule work — own item.

---

## §2 — Units

Each unit names its own verify check. The check must go red before the fix and green after, and the
red-proof must restore the **literal** pre-fix body, assert `compile-errors: 0`, and produce a
failure message naming the unit's own assertion.

### U1 — never mark a session DONE while entries are owed · **money path**

Add `!entriesReady` to the terminal ladder as its own PENDING branch, symmetrical with
`ARMING_UNKNOWN_EXITS_ONLY`, with reason `SCREEN_NOT_AS_OF_SESSION` and an alert saying the entries
are still owed.

**Verify:** a test driving the catch-up with `entriesReady == false`, `exitSkipped == 0` and
`markerRecorded == true` asserts `markPending` (not `markDone`) and that the reason is recorded.
Red-proof by restoring the single `markDone` call.

**Failure direction to preserve:** the session now stays retryable, so a screen that NEVER lands
consumes attempts until `ABANDONED` — a **loud** failure with an owner alert, replacing a **silent**
forfeit. That is the intended trade and it must be stated in the PR body, not discovered.

### U2 — one durable attempt per (batch, session, IST day)

The attempt counter exists to bound *days* of retrying, not passes within a morning. Options, to be
decided with evidence rather than taste:

- **(a)** add `last_attempt_day date` and only increment when it differs from today IST;
- **(b)** separate "poll pass" from "durable attempt" — increment only when the pass did work and
  failed, never when it no-ops on an unready screen;
- **(c)** revert the poll to a single shot and solve the owner's late-start problem another way.

**(b) is the shape I currently favour** because it keeps the counter meaning what its alert claims
("could not complete after N attempts"), and a no-op pass has not attempted anything. Needs a
migration if (a).

**Verify:** a repository-level test asserting six claims in one IST day leave `attempts` at 1 while
six claims across six days reach the budget. Red-proof by restoring the unconditional increment.

### U3 — the 19:00 schedule move, reduced

Owner decision 2026-08-11: **hard 19:00**, and the tail moves to the **morning** rather than being
squeezed against the boundary. So:

- market-data chain: single shot, distinct minutes, ending by ~18:53.
- The two paper reconcilers move to **morning (~08:10)**, not 18:58/18:59. They read settled state;
  `PaperReconciliationService` contains no market-data SQL, and past-expiry settles from the expiry
  session's own history. Morning is arguably more correct than a boundary-hugging evening slot, and
  it removes the reviewer's "no credible shutdown headroom" Major outright.
- Everything else in #1351's compose diff is superseded by PR #1352, which landed the passthroughs
  at their **current** values; U3 rebases onto that and changes only the times.

**Verify:** `EveningScheduleWindowTest` — already written on #1351 — plus the two fixes the reviewer
asked for and I have not yet made: require **exactly one** parsed firing per evening job, and derive
the collision check from those parsed firings rather than a numeric-only regex.

### U4 — one structural compose parse

`composeDefault` scans globally while the service-block check is separate. Extract exactly one
`services.<service>.environment.<key>` and use that same value for every assertion. Raised twice by
the reviewer; I judged the pair adequate twice. **Stop arguing and just make it structural** — the
cost is an hour and the disagreement is not worth carrying into a third round.

---

## §3 — Open questions the plan could NOT settle from code

1. **§1.3 and §1.4 are unverified.** Both need a read before they can be scoped. Neither blocks U1 or
   U2.
2. **U2's option (a) needs a migration**; (b) does not. The choice depends on whether "a pass that
   no-opped on an unready screen" is cleanly distinguishable at the claim site, which I have not
   established.
3. **Does anything else read `swing_catchup_runs.attempts`** as a health signal? If a dashboard or
   canary reads it, changing what it counts changes what that reads. Not checked.

---

## §4 — Sequencing

U1 and U2 are independent of the schedule and fix a **live** money-path hole; they should land first
and separately from the times, whatever the owner's earlier "fold it into the schedule PR" call —
worth re-raising, because that call was made before U2 was known to interact with U1. U3 and U4 then
rebase onto a smaller, calmer diff.
