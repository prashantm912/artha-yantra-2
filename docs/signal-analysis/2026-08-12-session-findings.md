# Session findings — 2026-08-12

## Scheduled verification: first complete cycle of #1333 two-phase swing schedule

**VERDICT: PASS — the two-phase mechanism worked end-to-end on its first real cycle.** The
2026-08-11 session was settled exits-only at 16:00, seeded by the morning catch-up (because
`entries_enabled = false`), re-run WITH entries at 08:35 IST on 2026-08-12, and the monotone OR
flipped `entries_enabled` to `t`. Zero entries were opened, but every one of the 12 would-enter
candidates was refused by an ADMISSION rail (risk cap / book governor), not by the forfeiture
defect #1333 fixed. The 2026-08-11 "entries permanently broken" conclusion is disproven by
this cycle.

Read-only run (scheduled task `verify-swing-entry-leg-20260812`); nothing restarted, published,
or written to the live DB.

### 1. Entry leg ran for session 2026-08-11 — PASS [computed]

`strategy.swing_batch_runs` (rendered `Asia/Kolkata`):

| batch | run_date | ran_at IST | candidates | would_enter | entries | exits | open_at_start | entries_enabled |
|---|---|---|---|---|---|---|---|---|
| manas-arora | 2026-08-10 | 08-10 20:05:30 | 98 | 9 | 0 | 0 | 6 | NULL |
| minervini | 2026-08-10 | 08-10 20:00:54 | 0 | 21 | 0 | 0 | 15 | NULL |
| manas-arora | 2026-08-11 | **08-12 08:35:34** | 103 | 4 | 0 | 0 | 6 | **t** |
| minervini | 2026-08-11 | **08-12 08:36:15** | 109 | 8 | 0 | 0 | 15 | **t** |

Both 2026-08-11 rows now read `entries_enabled = t` with plausible candidate counts (103/109).
`ran_at` shows 08-12 08:35 because `SwingBatchRunRepository.record` upserts on `(batch, run_date)` —
the morning entry run overwrote the 16:00 settle row's `ran_at`; the monotone OR preserved the flip
[computed from the row; upsert semantics sourced from #1333 background]. The 2026-08-10 rows carry
NULL `entries_enabled` — they predate the column being populated (pre-#1333-deploy runs) [assumed
from timing; harmless either way, NULL satisfies neither seed-skip nor DONE semantics incorrectly
since COALESCE(entries_enabled,true) treats NULL as entries-ran].

### 2. Catch-up state — PASS [computed]

`strategy.swing_catchup_runs`: both `manas-arora 2026-08-11` and `minervini 2026-08-11` reached
**DONE, attempts = 1**, claimed 08:34:59 / 08:35:36 IST, no reason. (The two 2026-07-17 ABANDONED
`NO_SCHEDULE_INTENT` rows are historical.) The seeding predicate — `WHERE NOT EXISTS (...
AND COALESCE(entries_enabled,true))` — did exactly what the pre-mistake verification said it would:
the `entries_enabled = f` settle rows made the session eligible and it was seeded, claimed, and run.

### 3. Logs — entry pass ran; admission rails refused everything [sourced, decisive lines quoted]

`docker logs ay-strategy-signal-service`, thread `swing-catchup-sched-1`, 03:04:59–03:06:20 UTC:

- manas-arora scanned entries for real: `"manas-arora swing: fresh entry for PANACHE would breach
  the open-risk cap — skipped"` — same for SAKAR, SHAILY, ARVIND, each followed by
  `"risk pyramid-cap manas-arora tripped ... blocked by the 6.0% portfolio open-risk cap"`.
- Batch summary: `"manas-arora swing batch: 2 strategies, 103 candidates, 0 entries, 0 exits,
  0 exit-skipped (would-enter 4, admitted 0, cap-exceedance 4)"`.
- minervini: `"entry pass skipped — the minervini book gate blocks entry at run start; 109 funnel
  candidate(s) not scanned"` — the 12/12 governor, which the task brief pins as CORRECT behavior.
  Its batch summary still tallies `would-enter 8, admitted 0, cap-exceedance 8` across the batch's
  4 strategies.
- Completion: `"swing catch-up: manas-arora caught up 2026-08-11 — 103 candidates, 0 entries,
  0 exits, 0 exit-skipped, 0 refusal(s)"` and the same for minervini (109 candidates).

No arming-unknown, screen-date-mismatch, market-open-deadline, or fresh-marker-table skip fired.
Many `candle response STALE ... visibility only` warnings during the run — data used unchanged,
not a refusal path [sourced].

### 4. Did an entry actually fire? No — and that is consistent, not a failure [computed]

`strategy.signals` for minervini/manas strategies in the last 3 h: **0 rows**. New
`strategy.paper_positions` opened since 08:00 IST: **0 rows**. Consistent with `admitted 0`:
minervini refused by the book governor (correct at 12/12), manas-arora's 4 candidates all refused
by the 6.0% portfolio open-risk cap (book already carries 6 open positions, `open_at_start = 6`).
So the brief's "manas-arora has capacity" held only at the SLOT rail — the RISK rail was already
saturated [computed]. **A real admitted entry has therefore still never been observed under the
new schedule**; the mechanism is proven up to admission, and the first admission remains to be
seen on a morning where the risk cap has headroom. Not a defect — but worth knowing what has and
hasn't been demonstrated.

### 5. `open_at_start` — PASS [computed]

The morning entry runs recorded real values: manas-arora **6**, minervini **15** (vs the known
`AdmissionProbe.empty()` → 0 defect on entries-disabled settles). The upsert means the visible
2026-08-11 rows now carry the morning probe's values; whether the 16:00 settle wrote 0 first is
no longer observable in the table [computed; prior-value unobservability noted].

### Open doubts

- minervini `would_enter = 8` alongside "entry pass skipped ... not scanned" for the book-gated
  pass: the 8 presumably come from the batch's other strategies' passes; not traced to code
  [assumed].
- 2026-08-10 NULL `entries_enabled` interpretation is [assumed] from deploy timing, not verified
  against the deploy log.
