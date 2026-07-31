# Swing catch-up: first ARMED sweep (2026-07-28 08:35 IST)

**Verdict (computed 2026-07-28 from live logs + live DB + code):** the first armed sweep of the
swing catch-up ([#1036](https://github.com/prashantm912/artha-yantra-2/pull/1036) `2e4ea6f0`,
armed 2026-07-27 ~22:45 IST) was **NOT the expected clean no-op** — it found one genuinely missed
session (**2026-07-17**) and **refused to replay it**, writing two terminal `ABANDONED` rows and
logging at `ERROR`. **Zero money effect: no paper position was opened, closed, or touched.**

The refusal is the designed fail-closed guard working on its first real encounter, not a defect.
This document records what fired, why the "expect zero rows" prediction was wrong, and what the
next sweeps will do.

## What fired

Thread `swing-catchup-sched-1`, 2026-07-28 **03:04:59Z = 08:34:59 IST** (sourced, `docker logs
ay-strategy-signal-service`):

```
ERROR SwingBatchCatchUp: swing catch-up: manas-arora 2026-07-17 refused - no schedule-time arming row
ERROR SwingBatchCatchUp: swing catch-up: minervini  2026-07-17 refused - no schedule-time arming row
```

`strategy.swing_catchup_runs` — 2 rows, both terminal, `attempts = 0` (never claimed, so the money
path was never entered):

```
    batch    | session_date |  status   | attempts | claimed_at |       reason
-------------+--------------+-----------+----------+------------+--------------------
 manas-arora | 2026-07-17   | ABANDONED |        0 |            | NO_SCHEDULE_INTENT
 minervini   | 2026-07-17   | ABANDONED |        0 |            | NO_SCHEDULE_INTENT
```

## Why 2026-07-17, and why refused

Three facts compose, each independently verified:

1. **07-17 is a real historical hole.** `strategy.swing_batch_runs` jumps `2026-07-16` →
   `2026-07-20` for *both* families — the 07-17 stack outage that motivated the whole catch-up
   branch (the incident where `paper_positions id=28` OMAXAUTO sat 3 days past its stop). Every
   other session in the window has a marker.
2. **07-17 sits in the sweep window.** The window is the `max-attempts + 2` = **7** most-recent NSE
   trading sessions strictly before today
   ([`SwingBatchCatchUp.sessionWindow`](../../services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/swing/SwingBatchCatchUp.java), `catchup-max-attempts` default `5`):
   `07-17, 20, 21, 22, 23, 24, 27`. 07-17 is the **oldest slot**. Only sessions with no run marker
   are seeded (`seedMissing`'s `WHERE NOT EXISTS (SELECT 1 FROM swing_batch_runs …)`), which is why
   exactly 2 rows exist rather than 14.
3. **07-17 predates the intent ledger.** `strategy.swing_batch_schedule_intents` holds
   `count = 2, min = max = 2026-07-27` — the intent machinery only started recording on its first
   weekday evening after deploy. So `intents.find(batch, 2026-07-17)` is empty, and the code
   refuses rather than inferring today's flag
   (`SwingBatchCatchUp:286-296` → `markAbandoned(…, "NO_SCHEDULE_INTENT")`).

That refusal is exactly what review round 5's Critical demanded: **never replay a session whose
arming state at schedule time is unknown.** Reading the *current* flag would have replayed a
session the owner may have deliberately disarmed — the original canary's root-cause defect.

## Why the "expect ZERO rows" prediction was wrong

The verification brief predicted zero rows on the premise that both 07-27 batches ran, so nothing
was missed. True for 07-27 — but the window reaches back **7 sessions**, into history that
predates the ledger the guard consults. The correct general expectation:

> **The first armed sweeps of any as-of replay reach back before its own intent ledger exists.
> Expect one fail-closed terminal row per genuinely-missing pre-ledger session in the window —
> that is the guard passing its first real test, not a fault.**

Here that is exactly one session × two families. After **2026-07-29** the window becomes
`07-20 … 07-28` and 07-17 falls out permanently.

## No repeat, no residue

- `ABANDONED` is **terminal**: `retryableSessions` selects only `PENDING` or stale-`RUNNING`
  (`SwingCatchUpStateRepository:110-123`), and `markAbandoned`'s upsert refuses to reopen a row
  already `DONE`/`ABANDONED`/`DISARMED` (`:207-211`). The 07-17 rows will never be re-swept, even
  if the window somehow reached them again.
- No further pre-ledger holes exist in the remaining window, so **the next sweep (07-29) should be
  the true silent no-op** the brief expected.

## Money safety — nothing moved

All four checks over the 12 hours spanning the sweep (computed, live DB):

| Check | Result |
|---|---|
| `paper_positions` opened | **0** |
| `paper_positions` closed | **0** |
| `paper_orders` placed | **0** |
| `paper_events` | **0** |

`attempts = 0` on both rows independently confirms the atomic claim was never taken, so the sweep
returned before any emission path.

## Detector (08:30 IST) — silent, as designed

The missed-batch detector ran 5 minutes earlier (`swing-detector-sched-1`, 02:59:59Z =
**08:29:59 IST**) and issued **no page**; `strategy.swing_missed_batch_alerts` is still empty.
It emitted only INFO skips for 07-14…07-24 (`predates any recorded arming intent — skipping (fail
closed)`) — including 07-17, consistent with the catch-up's refusal on the same session for the
same reason.

**2026-07-27 is deliberately absent from those skips**: it has an intent row, so the bounded
intent-driven check owns it (`SwingBatchCanary:167-169`), and its run marker exists, so that check
passed silently. That is the intent-present silent sweep the recurring 08:41 verify task was
waiting for — its exit condition is met.

Intent rows, for the record:

```
    batch    | session_date | armed |         scheduled_at
-------------+--------------+-------+-------------------------------
 manas-arora | 2026-07-27   | t     | 2026-07-27 14:34:59+00  (20:04:59 IST)
 minervini   | 2026-07-27   | t     | 2026-07-27 14:29:59+00  (19:59:59 IST)
```

## Residual note (not a defect, no action taken)

The refusal logs at `ERROR` **and** raises an operator alert for a session nothing can act on — the
07-17 batch can never acquire a run marker retroactively, and its arming state is unrecoverable.
For a pre-ledger session that is one-time noise by construction (terminal row, window rolls off),
so it needs no fix. It would only become a real annoyance if the ledger were ever reset while
marker history survived — worth remembering before any such reset.

## Open doubts

- The catch-up's alert delivery was not independently confirmed. `strategy.notification_events` is
  signal-scoped (`signal_id`/`strategy_id`/`insight_id`), so a `SwingBatchAlert` does not land
  there; no notifier error appeared in the log window, but "the owner's ntfy actually received it"
  is **assumed**, not verified.
- A genuine successful catch-up replay — an armed session that misses and is then replayed to
  `DONE` — remains **unobserved live**. Today exercised only the refusal branch. The replay path
  stays test-covered only.
