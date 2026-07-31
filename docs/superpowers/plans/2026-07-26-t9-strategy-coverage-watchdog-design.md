# T9 — strategy-coverage watchdog (design, rev 3)

**Status: BUILT + SHIPPED; kept ACTIVE pending the owner's arming decision (corrected 2026-07-29).** The build merged as [#1035](https://github.com/prashantm912/artha-yantra-2/pull/1035) and is DEPLOYED **disabled**; arming is a standing owner decision (ledger row **G2**). This document is the as-designed record, kept for the reasoning — in particular why the filed emitting/published-ratio one-liner was REFUSED as structurally confounded and replaced by the per-strategy `lastSeenPerSlug` stamp. The remaining forward work is the ARMING decision only (channel / re-page cadence / OBSERVE_ONLY duration); the ledger row is authoritative for status.

*(Original header: DESIGN ONLY — no code written. Build is a separate PR.)*
**Date:** 2026-07-26. **Author:** main loop (Opus 5), grounded against the tree at `034110ef`.
**Review history:** rev 1 `NEEDS_REWORK`, rev 2 `REQUEST_CHANGES` — both from cross-vendor review
(Codex/gpt-5.6-sol, xhigh). **Every finding in both rounds was verified against the code by the
Architect and accepted; none was pushed back on.** §9 records the dead ideas so they are not
re-proposed.

---

## 1. Verdict first

**T9 is a LOAD-coverage detector keyed on a TOTAL, per-slug, per-generation classification snapshot
that `reload()` computes and currently throws away.** Not the emitting/published ratio it was filed
as (confounded), not a per-bar visit stamp (cannot go stale), and not gated on retry-chain
exhaustion (a state the most important failure never reaches).

The failure class this covers — **F10's partial load, 32 of 39 strategies dark** — is fully
determined at load time, before any bar arrives. Three separate designs died by trying to observe it
at runtime.

## 2. Why every runtime signal is confounded (settled; do not re-litigate)

| candidate | confounded by | evidence |
|---|---|---|
| `strategy.signal_rejections` | market direction | `recordRejection` sits inside the chart-gate-passed branch; a chart-stage no-entry writes nothing. |
| `strategy.signals` | direction + engine-mixing | `00:00:00`-stamped rows are the swing BATCH engine; fakes liveness on a dead tick engine. |
| gate output (`lastGateOutputAtMs`) | direction | the retired `SignalStarvationCanary`'s defect. Class deleted 2026-07-26. |
| `ay_signal_eval_outcome_total` | window + position + universe | increments below `!inUniverse` (`:1081`) and `!withinSessionWindow` (`:1084`); btst never evaluates from that path (`:1090`). |
| emitting/published **ratio** (T9 as filed) | all of the above | it is a function of them. |
| per-bar visit stamp (**rev 1**) | nothing — it is simply always fresh | `onClosedBar` takes ONE symbol's bar (`:1068`) but iterates the whole `loaded` set (`:1075`) *above* the `inUniverse` check (`:1081`). |

`lastBarReceivedAtMs` / `lastBarEvaluatedAtMs` remain the only safe runtime liveness signals, and the
subscriber watchdog + `SessionLivenessHeartbeat` (#941) already own them. **T9 adds no third runtime
detector.**

## 3. The signal: a total per-slug classification snapshot

### 3.1 Why the obvious version is not enough

`reload()` classifies universes into `UniverseResolutionStatus` (`SignalEngine.java:129-146`):
`RESOLVED` / `RESOLVED_EMPTY` / `UNRESOLVED` / `NOT_LIVE_RESOLVABLE`. But **four paths `continue`
BEFORE universe classification is ever reached** (`:561`–`:600`, verified this session):

1. `!strategy.enabled() || strategy.publishedVersionId() == null`
2. `versionRow.isEmpty()` — the published version row is missing
3. `scalper != null && !ScalperRisk.hasBoundingExit(...)` — policy rejection, logs a warning
4. `"swing".equals(definition.session().style())` — owned by the batch engine, healthy
5. a non-live-rollable primary (`'1d'` on a non-btst strategy) — logs a warning

A map built only from `UniverseResolutionStatus` is therefore **not total**: a scalper rejected for a
missing bounding exit vanishes from `loaded` while the map itself is present and healthy-looking, so
a "missing map" alarm cannot see it. **The snapshot must be total over the intended registry set.**

### 3.2 The snapshot

Per reload, build `Map<slug, Classification>` covering **every enabled + published strategy in the
registry**, each getting exactly one terminal classification:

| classification | source | verdict |
|---|---|---|
| `RESOLVED` | universe resolved to ≥1 instrument | healthy |
| `RESOLVED_EMPTY` | legitimate stand-aside (screener picked nobody) — **and the slug is NOT loaded**: `:627` `continue`s before `fresh.add` | healthy |
| `NOT_APPLICABLE_SWING` | swing style, owned by the batch engine | healthy |
| `UNRESOLVED` | upstream Kite/market-data failure, retryable | **abnormal** |
| `NOT_LIVE_RESOLVABLE` | permanent config/capability error | **abnormal** |
| `MISSING_VERSION_ROW` | published pointer with no version row | **abnormal** |
| `NO_BOUNDING_EXIT` | scalper policy rejection | **abnormal** |
| `NOT_ROLLABLE_PRIMARY` | primary the live engine cannot roll up | **abnormal** |
| `LOAD_ERROR` | exception thrown while loading (`loadErrors`) | **abnormal** |

Plus a snapshot-level `terminalState ∈ { IN_FLIGHT, HEALTHY, DEGRADED_TERMINAL }` and a monotonic
`generation`.

Written once per reload on the reload thread, published `volatile`. **No new classification logic** —
every branch above already exists and already logs; T9 retains the identity instead of discarding it.

### 3.3 The alarm predicates — TWO, not one

**Predicate A — dark strategies (`STRATEGY_DARK`).** Fire when **all** hold:

1. a snapshot for the current generation has **completed**;
2. `snapshot.terminalState == DEGRADED_TERMINAL` — **not** "the CONNECTED retry chain exhausted";
3. `registry.countEnabledPublished() > 0` — the gate-3 shape #886 corrected; **never** `loaded > 0`;
4. ≥1 slug carries an **abnormal** classification;
5. `inSession(now)`, calendar-gated;
6. that slug's episode latch is not already set (§3.5).

Alert **per slug, named, with its classification** — never a ratio, never a bare count.

**Predicate B — no snapshot at all (`SNAPSHOT_MISSING`).** Predicate A is unsatisfiable when a reload
never publishes: with no completed snapshot there is no `terminalState`, no classification and no
latch to test. That is the *most* suspicious state, and rev 3 could not express it. It needs its own
predicate on independently observable state:

1. `requestedGeneration > completedGeneration` — a reload was requested and never published its
   terminal snapshot. `requestedGeneration` is incremented and an `IN_FLIGHT` marker published
   **before** the reload body runs, so an abort mid-reload leaves the mismatch visible;
2. that mismatch has persisted past the startup/reload grace;
3. `registry.countEnabledPublished() > 0`;
4. `inSession(now)`.

`SNAPSHOT_MISSING` is a single snapshot-level alert (no per-slug detail exists to report) and carries
`requestedGeneration`/`completedGeneration` in its body. **Missing state is suspicious, never
silently healthy** — #886's gate-4 lesson, now actually expressible.

### 3.4 Why `terminalState`, not chain exhaustion — the rev-2 Critical

Rev 2 gated on the bounded CONNECTED retry chain reaching EXHAUSTED. **That predicate can never fire
for the status that most needs naming.** `NOT_LIVE_RESOLVABLE` is explicitly *not counted*
(`SignalEngine:618-626`, comment: "NOT counted: a config/capability error is not an upstream fault"),
so `ReloadOutcome.healthy()` — `unresolvedDrops == 0 && loadErrors == 0` (`:191`) — returns **true**,
the chain exits successfully, and EXHAUSTED never happens. A permanently mis-configured strategy would
stay dark and silent forever.

Compounding it: the CONNECTED chain is only one of several reload paths. `morningReload` (`:2133`,
cron `0 40 8 * * MON-FRI`), the hot-swap at a bar boundary (`:1052`), and the per-minute reconcile
(`:1421`) can each produce an abnormal reload without any chain running at all.

`terminalState` is therefore computed **per reload, on every path**: `IN_FLIGHT` while a retry chain
is still converging, else `HEALTHY` if no slug is abnormal, else `DEGRADED_TERMINAL`. The alarm keys
on the snapshot, not on one path's retry bookkeeping.

### 3.5 Deduplication — the rev-2 Major

An unlatched detector pages on **every sweep** for a persistent condition.

⚠️ **Rev 3 keyed the latch on `(generation, slug, classification)` and, in the same paragraph,
required that a new generation alone must not re-page. Those contradict:** a new generation makes a
new tuple, so the set reads unlatched and pages. Caught in round 3; the generation must NOT be part
of the key.

**An alert episode is keyed `(slug, classification)`** and models a *continuous* condition:

- set when a page is emitted, and it **survives generation changes** — every 08:40 reload re-derives
  the same broken config, and that must stay silent;
- **cleared** when the slug becomes healthy, changes classification, or leaves the intended registry
  set — so a genuine new or different failure re-alerts immediately;
- the optional once-per-IST-day reminder for `NOT_LIVE_RESOLVABLE` is a documented owner-controlled
  exception to the latch (owner question 2), not a default.

`SNAPSHOT_MISSING` (predicate B) latches on its own single key with the same clear-on-recovery rule.

### 3.6 Staleness by GENERATION, never wall-clock age — the rev-2 Major

Rev 2 alarmed when the snapshot was "older than one reload interval". **There is no uniform reload
interval.** Reloads are daily at 08:40, event-driven, or reconcile-triggered only when registry state
actually drifts — a healthy morning snapshot legitimately sits unchanged for the whole session. An
age-based rule pages every afternoon; it is the same mistake as the eval-outcome-counter idea.

Missingness is defined by **generation**: `requestedGeneration > completedGeneration` past the
startup/reload grace. Absent-past-grace is suspicious, not silently healthy (#886's gate-4 lesson);
merely *old* is not. This is **predicate B** (§3.3) — it deliberately does NOT route through the
per-slug predicate, because with no completed snapshot there are no slugs to classify.

### 3.7 Explicit non-goals

- `RESOLVED_EMPTY` never alarms — it is the correct stand-aside; alarming would page every day a
  screener picks nobody. (Whether an empty `contracts` array on an INDEX ladder should be a FAULT is
  chip `task_f624fca7`'s question, owned by the resolver, untouched here.)
- Runtime silence never alarms — §2's territory.
- btst needs no special case: classification precedes every session-style branch.

## 4. Split by counter (T9's second half)

Per dark slug the alert carries: slug, classification, snapshot `generation`, reload timestamp, and
`terminalState`. **All are reload-thread snapshot state.** Rev 1's promise of
`inUniverse`/`withinSessionWindow`/`activeEntry` is withdrawn — those are per-bar locals an async
sweep cannot legally read.

## 5. Lifecycle

| state | behaviour | default |
|---|---|---|
| `DISABLED` | no sweep | ✅ |
| `OBSERVE_ONLY` | sweep runs, logs `would-page: <slug> <classification>` at INFO, never pages | the arming-evidence state |
| `ARMED` | sweep runs, pages ntfy through the latch | owner flips after reading a week of OBSERVE_ONLY |

**Startup/reload grace is ASYMMETRIC — the two predicates wait on opposite conditions.** Rev 4 wrote
a single rule ("no evaluation until the first snapshot for the current generation completes") which
makes `SNAPSHOT_MISSING` **unreachable**: a reload that never completes suppresses evaluation forever,
so the one predicate whose whole job is to report non-completion could never run. Caught in round 4.

| predicate | waits for | rationale |
|---|---|---|
| `STRATEGY_DARK` (A) | a **completed** current-generation snapshot | it reads per-slug classifications, which only exist once the snapshot completes |
| `SNAPSHOT_MISSING` (B) | the generation mismatch to **exceed** grace | it fires *because* completion has not happened — waiting for completion would be circular |

Within grace, both are silent; past grace, B is exactly the predicate that speaks.

## 6. Wiring constraints (non-negotiable)

- **`signals` must never import `notifier`** — a signals-owned event record + an `@EventListener` in
  `notifier`, the `DotInputAlert`/`DotAlertListener` template. (Reuse the pattern, not the just-deleted
  `StarvationAlert` class.)
- Any component injecting `SignalEngine` **must** carry `artha.signals.engine-enabled` — the defect
  cross-vendor review caught on #941.
- Sweep on `monitorTaskScheduler`, not the default pool (BEJ-01/#919).
- Every `application.yml` `${ENV}` name matches its compose passthrough **exactly** (#653).

## 7. Verification plan — production paths only

1. **Partial reload alarms:** one slug `UNRESOLVED`, snapshot `DEGRADED_TERMINAL`, bars flowing ⇒
   exactly one alarm naming that slug + status. **Prove RED against unfixed code before keeping it.**
2. **`NOT_LIVE_RESOLVABLE` alarms** — the rev-2 Critical, asserted directly: a permanent config error
   pages even though no retry chain ever exhausts and `ReloadOutcome.healthy()` is true.
3. **Abnormal non-CONNECTED reload alarms:** a failing `morningReload` and a failing hot-swap each
   produce `DEGRADED_TERMINAL` and page — no CONNECTED chain involved.
4. **Totality:** every enabled+published slug receives **exactly one** terminal classification,
   including the five pre-classification skip paths (§3.1). A scalper rejected for a missing bounding
   exit must appear as `NO_BOUNDING_EXIT`, not vanish.
5. **Two distinct quiet shapes, tested separately** (the rev-2 Minor — rev 2 conflated them):
   - `RESOLVED_EMPTY` ⇒ slug is **not loaded and not visited** (`:627` `continue`s before
     `fresh.add`) ⇒ zero alarms.
   - The 2026-07-24 hero-zero pair were **`RESOLVED`** — loaded and visited, they simply emitted
     nothing downstream on a non-expiry Friday ⇒ zero alarms. This is the shape the filed ratio
     design would have paged on.
6. **Healthy snapshot ages all session** ⇒ never becomes suspicious (§3.6 generation rule).
7. **Dedup:** one page per episode; status change or recovery re-arms; **an unchanged abnormal status
   across a NEW generation does not re-page** — the case rev 3's key got backwards, asserted directly
   by driving two consecutive reloads that both classify the same slug `NOT_LIVE_RESOLVABLE`.
7a. **`SNAPSHOT_MISSING` — predicate B, both shapes** (rev 2 had a missing-state test; rev 3 dropped
   it, caught in round 3):
   - the **first** reload never completes ⇒ `requestedGeneration > completedGeneration` past grace ⇒
     exactly one `SNAPSHOT_MISSING` alert, and **no** `STRATEGY_DARK` alert (there is nothing to
     classify);
   - a **newer registry generation's** reload aborts before publishing while a healthy older snapshot
     is still in memory ⇒ `SNAPSHOT_MISSING` fires (the stale-but-healthy snapshot must not mask it);
   - reload completes normally ⇒ silent, including while `IN_FLIGHT` within grace.
8. **Bearish leg + closed windows:** healthy load, no strategy passes the chart gate, 15:00–15:30 IST
   ⇒ zero alarms.
9. **Parity ladder — mandatory:** this edits live `SignalEngine`, so `GoldenDeterminismTest` (in
   `libs/strategy-engine`, **not** backtest-service) and `BacktestParityTest` must both run and be
   byte-identical, plus `-pl services/strategy-signal-service -am verify`. Instrumentation-only is not
   an exemption.
10. **Live:** ship `DISABLED`, flip to `OBSERVE_ONLY` in the same deploy window, read one week, then
    arm. Do not arm in the PR that builds it (#941 precedent).

## 7b. Corrections folded in from the build (2026-07-26)

- **There are FIVE pre-classification skip paths, not four.** The builder verified this against
  `SignalEngine.java:604-659` while implementing §3.1; the design's count was wrong and the line
  citations had drifted. §7's totality test (item 4) covers all five.
- The missing-version branch (`:604-608`) **does not currently log** anything, so a slug lost there is
  invisible today — which is exactly the hole `MISSING_VERSION_ROW` closes.

## 8b. Owner questions — SETTLED 2026-07-26 (Architect, on the design's own recommendations)

All three were pre-arming decisions, so none blocked the build:

1. **Alert channel = ntfy.** This is a named-detail alarm about a live process; the external
   dead-man's-switch is an absence-is-the-alarm mechanism and does not fit.
2. **`NOT_LIVE_RESOLVABLE` re-pages once per (slug, IST day)** — a deliberate, commented exception to
   the §3.5 episode latch, because it is a permanent config error that will otherwise recur silently
   every reload until someone fixes it.
3. **OBSERVE_ONLY runs one full trading week before arming.** The build ships `DISABLED` and does not
   arm anything; arming stays the owner's call (#941 precedent).

## 8. Open questions for the owner

1. **Alert channel** — ntfy (per-slug detail) vs the external dead-man's-switch. Recommendation:
   **ntfy**; this is a named-detail alarm about a live process, and absence-is-the-alarm does not fit.
2. **Should `NOT_LIVE_RESOLVABLE` re-page daily?** It is a permanent config error — loud is right, but
   it will recur every reload until fixed. Recommendation: **once per (slug, IST day)**, as a
   deliberate exception to §3.5's latch.
3. **OBSERVE_ONLY duration** before arming. Recommendation: one full trading week.

## 9. Dead ideas — recorded so they are not re-proposed

- **Rev 1 — per-bar visit stamp.** `lastSeenPerSlug.put(...)` at the top of the `loaded` loop. It is
  independent of direction, window and position — and also of the failure it was meant to detect,
  because the loop visits every loaded slug on every symbol's bar. It would have shipped green,
  dormant and incapable of firing. **Lesson: check what makes a liveness signal MOVE as carefully as
  what makes it STALL.**
- **Rev 2 — gating on retry-chain exhaustion.** `NOT_LIVE_RESOLVABLE` is uncounted, so `healthy()` is
  true and the chain exits before exhausting; and three reload paths never start a chain at all.
  **Lesson: a predicate built on one path's bookkeeping silently excludes every other path.**
- **Rev 2 — wall-clock snapshot staleness.** No uniform reload interval exists; healthy snapshots
  legitimately age for hours.
- **Rev 3 — a latch keyed on `(generation, slug, classification)`.** It contradicted its own stated
  requirement: a new generation mints a new tuple, so the latch reads unset and the same broken
  config re-pages after every 08:40 reload. **Lesson: a dedup key must be keyed on the CONDITION, not
  on the observation that discovered it.**
- **Rev 3 — folding "no snapshot" into the per-slug predicate.** Every clause of that predicate
  (`terminalState`, an abnormal classification, a latch) requires a snapshot to exist, so the most
  suspicious state was structurally unreportable. **Lesson: an absence alarm cannot be a special case
  of a presence alarm.**
- **Rev 4 — one symmetric grace rule for both predicates.** "No evaluation until the current
  generation's snapshot completes" gagged `SNAPSHOT_MISSING` in precisely the scenario it exists for.
  **Lesson: when an alarm's trigger is the absence of an event, never gate it on that same event.**

## 10. Claim labels

- `SignalEngine` line numbers, the `UniverseResolutionStatus` enum, the five pre-classification skip
  paths, `NOT_LIVE_RESOLVABLE`'s "NOT counted" comment, `ReloadOutcome.healthy()`, the `RESOLVED_EMPTY`
  `continue`-before-`fresh.add`, and the `morningReload`/hot-swap/reconcile entry points — **sourced**,
  read this session at `034110ef`.
- The rev-1 vacuity and rev-2 unreachability refutations — **computed** this session; both were raised
  by cross-vendor review first and then verified independently before acceptance.
- F10's 32-of-39 — **recalled**; motivation only, no design decision depends on the number.
