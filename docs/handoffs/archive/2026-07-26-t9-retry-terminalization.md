# T9 — intermediate retry attempts must not publish as terminal

Cross-vendor review Major on PR #1035, deferred there because it is a change to the RELOAD
LIFECYCLE rather than to the watchdog, and the feature ships DISABLED so it cannot fire today.

## The defect

`SignalEngine.reload()` calls `beginCoverageReload()` once at the top and
`completeCoverageReload(...)` on every exit path. `runKiteConnectedReloadAttempt` drives a bounded
retry chain that calls `reload(true)` up to `KITE_CONNECTED_RELOAD_MAX_ATTEMPTS` times, converging on
`ReloadOutcome.healthy()`.

So every intermediate attempt publishes a TERMINAL `DEGRADED_TERMINAL` snapshot even while the chain
can still converge. A watchdog sweep landing between attempts reads that terminal snapshot and pages
a transient condition that is about to fix itself.

## Required behaviour

- While a retry chain is still able to continue, an unhealthy attempt must leave the snapshot
  `IN_FLIGHT` — not terminal — WITHOUT losing the generation bookkeeping that `SNAPSHOT_MISSING`
  (predicate B) depends on.
- Terminalize only on: the chain converging healthy, or the chain exhausting its attempts.
- A DIRECT, non-chain `reload()` (morning reload, hot-swap, the 20 s reconcile) must STILL terminalize
  immediately, exactly as today. Do not make every reload provisional — that would blind predicate A.

## Do not break these — they are the reasons the design is shaped this way

- **`SNAPSHOT_MISSING` must still fire** when the requested generation outruns the completed one past
  grace. Holding a chain `IN_FLIGHT` for a long time must NOT accidentally suppress it — that predicate
  exists precisely because "the reload never completed" is a real failure mode. Verify the interaction
  explicitly: a chain that never converges must eventually be visible, not silently pending forever.
- **Staleness is by GENERATION, never wall-clock.** Do not introduce a time-based terminalization.
- The `IN_FLIGHT` marker is published BEFORE the reload body starts so an aborted reload cannot be
  mistaken for the last healthy snapshot still in memory. Preserve that property.
- `reload()` is on the single `signal-eval` thread and the live engine depends on it. Keep the change
  surgical; do not restructure the retry chain.

## Tests

- An intermediate unhealthy attempt inside a chain leaves the snapshot NON-terminal, and a sweep at
  that moment produces NO page.
- The chain converging healthy terminalizes exactly once, healthy.
- The chain exhausting terminalizes exactly once, `DEGRADED_TERMINAL`, and the slug pages.
- A direct non-chain reload still terminalizes immediately (regression guard for the above).
- A chain that never completes still trips `SNAPSHOT_MISSING` past grace.

Parity ladder is MANDATORY — this edits the live `SignalEngine`. `GoldenDeterminismTest` lives in
`libs/strategy-engine` (NOT backtest-service) and `BacktestParityTest` must both be byte-identical,
plus a full `-pl services/strategy-signal-service -am verify`. Instrumentation is not an exemption.

EDIT-ONLY: no commit, branch, push, PR, deploy or arming.
