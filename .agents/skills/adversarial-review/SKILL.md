---
name: adversarial-review
description: Use BEFORE opening a PR that touches a parity surface, money math, exit doctrine, migrations, golden vectors, or any HOLD-tier change in ArthaYantra — the multi-agent adversarial review routine that has repeatedly caught real defects the test suite missed.
---

# adversarial-review

Independent reviewer agents, each with a distinct lens, each prompted to REFUTE the
change. This routine has a track record the test suite doesn't: the #544 scaled-exit
review found a **real parity edge BacktestParityTest cannot catch** (a colliding
close-fill-bar closing the wrong position — the parity test checks determinism +
signal-JSON but never pins the trade list); the #528 VCP keystone review found 6; the
#550 swing keystone review found 4 (one HIGH that would have left auto-paper inert).

## When it is mandatory

- Golden vectors / replay engines / `SignalEvent`/`Trade`/`ExitDecision` shapes (parity).
- Exit doctrine or anything altering entries/exits/sizing on a live book.
- Money math (rounding, averaging, PnL, margin), Black-76, IST/UTC time keys.
- Flyway migrations on hypertables / compressed tables.
- Any HOLD-tier change. Skippable only for docs, tests-only, and trivial mechanical edits.

## How

1. **Scale to risk**: 2 reviewers for a typical Medium; 4–6 for parity-touching; 8–12
   lens-diverse for keystones (new engine, new executor, new migration on live data).
   Proven 2026-07-10/11 (21-item run): 4 lenses for parity-adjacent live-eval changes,
   2 for scoped HOLD money paths, 1 domain reviewer for a plain migration, 0 extra for
   alert-only ops code. **HOLD reviews must trace OPERATIONAL LOOPS** — who retries,
   what state is already committed before the changed code runs, which cron fires
   once — line-diff review missed 3 state-stranding paths (the A3 settle refusal,
   #694) that loop-tracing found.
2. **Spawn in ONE message** (parallel `Agent` calls). Give each: the diff or branch, the
   doctrine/design doc it must be faithful to, and ONE lens:
   - parity/golden byte-identity (does any serialized shape change?)
   - money/rounding/averaging (worst-case paisa drift; overflow; negative qty)
   - IST/UTC + time-keying (`OffsetDateTime` map keys, `::date` traps)
   - DB/Timescale (use the `timescale-domain-reviewer` agent for this lens)
   - concurrency/idempotency (retries, CAS, duplicate events, crash mid-pass)
   - doctrine faithfulness (does the code do what the strategy doc says, cite §)
   - test-gap (what's untested — especially the HAPPY path; reviews that find "no bug"
     often still find the missing positive test, as F2's did)
   - blast radius / rollback (what breaks if this is wrong live; how to un-arm)
3. **Verify every finding yourself against the code before acting.** Reviewers produce
   plausible-but-wrong findings; the standard is CONFIRMED-in-code, not "sounds right".
   Fix confirmed ones; record rejected ones with the reason.
4. **Re-run the full ladder after fixes**: `-am` build+tests, goldens byte-identical,
   then the PR. Put the review outcome (N found / M fixed / K rejected) in the PR body.

## Frontend variant

Use the `ui-a11y-reviewer` agent for React/Tailwind/shadcn changes (contrast, roles,
theme-token collisions — the `accent` clobber was invisible to axe and e2e).
