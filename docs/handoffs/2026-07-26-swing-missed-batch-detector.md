# Swing missed-batch DETECTOR (replaces auto-replay)

Owner decision 2026-07-26: the auto-replay catch-up (`feat/swing-batch-catchup`, PR #1036) is PARKED
after three review rounds found **seven Criticals, every one of them in the replay machinery**. The
detection half came through all three rounds clean. Ship detection + a page instead.

**The original problem, and the whole job here:** a missed 08:35 swing batch went unnoticed and unrun.
Detection plus an alert solves that. Re-running is then a deliberate human action with full context.

## Source of truth for the reusable parts

Branch `feat/swing-batch-catchup` (PR #1036) already contains reviewed-clean versions of everything
below. **Port them, do not reinvent them**, and read that branch first:

- `V047__swing_catchup_runs.sql` — durable per-session run rows
- `V048__swing_schedule_intents.sql` — per-session ARMING INTENT, persisted at schedule time
- `SwingBatchRunRepository`, `SwingCatchUpStateRepository`, `SwingBatchIntentRepository`
- their three existing `*IntegrationTest`s
- `MonitorSchedulingConfig.swingCatchUpTaskScheduler` — the dedicated single-thread pool

⚠️ **DO NOT port**: `SwingPaperEffectRepository`, `SwingPaperEffectRetry`, V049, V050, the as-of lot
filtering, or ANY replay/repair path. Those are the parked half. **This service must never
automatically re-run a batch or touch a paper position.** If you find yourself writing recovery code,
stop — that is the thing being removed.

## What to build

A scheduled detector that answers one question: *did a scheduled swing batch fail to run, and was it
armed at the time?*

1. **Record intent at schedule time.** V048's per-session arming intent, exactly as on the parked
   branch — persisted when the session is scheduled, not inferred later from today's flag.
2. **Record runs.** V047's durable run rows, so "did it run" is a fact, not an inference.
3. **Sweep** on the dedicated scheduler: find sessions that were ARMED but have no successful run.
   **Fail closed** — a session with NO intent row (anything predating the migration) is NOT reported
   as missed, because we cannot know it was armed. Say so in the log rather than guessing.
4. **Page once per missed session**, with an episode latch so a persistent gap does not re-page every
   sweep. Include the session date, the batch/family name, and what the owner needs in order to
   re-run it by hand.

## Constraints

- **Modulith:** `notifier` imports `signals`, so signals-side code must NEVER import notifier. Alert
  via an in-process event record + an `@EventListener` in notifier — `DotInputAlert`/`DotAlertListener`
  is the template. `ModularityTest` only catches violations under a full `-am verify`.
- Migrations keep their numbers if ported unchanged (V047/V048); they are NOT applied anywhere yet.
  Version order matters and applied migrations are checksum-locked.
- `${ENV}` placeholders must match the compose passthrough and `.env.example` **exactly** (#653).
- Tests `*Test`/`*IntegrationTest` only — no failsafe plugin. ITs share ONE singleton Testcontainers DB
  with NO per-method cleanup; unique names per method.
- Ship the pager DISABLED or at a default the owner can arm, matching how T9 ships.
- Build with the FULL reactor and `-am`.

## Tests

- an ARMED session with no successful run IS reported
- a session that ran successfully is NOT reported
- a session with NO intent row is NOT reported (fail closed), and logs why
- one page per episode — a persistent gap does not re-page every sweep
- Modulith direction holds

EDIT-ONLY: no commit, branch, push, PR, deploy or arming.
