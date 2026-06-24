# Expired-backfill auto-resume — design

| | |
|---|---|
| **Date** | 2026-06-25 |
| **Status** | Approved (owner picked "Startup + hourly self-heal"). Implementing. |
| **Scope** | `market-data-service` — make the expired-instruments backfill survive restarts + stalls WITHOUT a manual re-POST. Backend-only; no frontend, no migration, no new endpoint. |

## Problem

The expired-instruments backfill (`ExpiredBackfillService`) runs on an **in-memory daemon thread**,
kicked by a fire-and-forget `POST /api/v1/market/admin/expired-backfill`. Any `market-data-service` /
Docker / host restart kills the thread and it does **not** auto-resume — its in-memory `status` resets
to `NEVER_RUN`. Today resuming means an authenticated re-POST (owner or operator). This recurred on a
2026-06-25 system reboot. The owner wants it self-driving from the backend.

## Approach (owner-chosen: startup + hourly self-heal)

One new component drives the existing, already-idempotent `triggerAsync`:

- **On boot** — `@EventListener(ApplicationReadyEvent)` fires the coverage-aware resume. The reboot fix.
- **Hourly** — `@Scheduled(cron)` re-fires when the job is idle **and** work remains. Recovers a mid-run
  crash/DNS-blip stall and picks up newly-expired contracts.

Both call `triggerAsync(NIFTY+SENSEX, now−365d, now, 1m, force=false)` — coverage-aware (skips
already-complete contracts via the `expired_contracts` registry) and lock-guarded (`running`
`AtomicBoolean`; a redundant trigger 409s harmlessly and is swallowed).

## Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `ExpiredBackfillProperties` (`@ConfigurationProperties("artha.marketdata.expired-backfill")`) | `autoResume` / `selfHealCron` / `catchupHours` | — |
| `ExpiredBackfillAutoResume` (`@Component`) | the boot hook + hourly tick; decides when to resume | `ExpiredBackfillService`, `ExpiredBackfillRepository`, `ObjectProvider<UpstoxExpiredInstrumentsClient>`, props |
| `ExpiredBackfillRepository.hasIncompleteCoverage()` | cheap `EXISTS(… WHERE NOT complete)` on the registry — lock-safe, no candle scan | `JdbcTemplate` |

**`shouldResume(status)`** (self-heal, when not `RUNNING`): true if `state ∈ {NEVER_RUN, FAILED}`, OR
`hasIncompleteCoverage()`, OR (`state==OK` and `lastRun` older than `catchupHours`≈20h — daily catch-up
for new expiries). This stops pointless full passes once everything is complete and fresh.

**Gating:** `autoResume` (default `true` live) **AND** the analytics client is present
(`clientProvider.getIfAvailable() != null`). The mock profile has no Upstox client → no-op. Config:
`ARTHA_EXPIRED_BACKFILL_AUTORESUME` / `_SELFHEAL_CRON` / `_CATCHUP_HOURS`.

## What does NOT change

No new endpoint (no springdoc/contract drift). No Flyway migration (reuses `expired_contracts.complete`).
No golden/parity surface. `triggerAsync` + `running` lock + `status()` untouched. The existing
**Data-Ops → Run Backfill** wizard + **B1 Collection Status** page stay as the manual override/visibility.

## Testing

- **Unit** (`ExpiredBackfillAutoResumeTest`, Mockito): boot fires when enabled+client-present, no-op when
  disabled / client absent; self-heal no-op while `RUNNING`, fires on `NEVER_RUN`/`FAILED`/incomplete/
  stale-OK, no-op on fresh-complete-OK; a `triggerAsync` throw is swallowed (boot never crashes).
- **IT** (`ExpiredBackfillCoverageIntegrationTest`, Testcontainers, mock profile): an incomplete contract
  makes `hasIncompleteCoverage()` true.
- Full `market-data` `verify` shard + checkstyle in CI.

## Risk

Low — flag-gated, mock-safe, idempotent, no schema/contract/parity surface. Worst case a redundant
coverage-aware pass (fast all-skip), bounded by the `shouldResume` guard + the service's own
429-backoff/throttle.
