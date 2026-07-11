---
name: new-migration
description: Use when adding or changing database schema (table, column, index, hypertable, continuous aggregate, role, or grant) in any ArthaYantra Flyway lineage — admin, marketdata, strategy, or backtest.
---

# new-migration

Flyway runs **four independent lineages** under `deploy/flyway/`, each with its own
version sequence:

| Lineage | Path | Owns |
|---|---|---|
| admin | `deploy/flyway/admin/` | roles, schemas, cross-schema grants |
| marketdata | `deploy/flyway/marketdata/` | instruments, candle hypertables, continuous aggregates, options snapshots |
| strategy | `deploy/flyway/strategy/` | strategies, versions, signals, audit |
| backtest | `deploy/flyway/backtest/` | jobs, runs, trades, optimization trials |

## The rule (non-negotiable)

**Never edit an already-applied migration — even a comment.** Applied migrations are
checksum-validated in the dev stack and in CI; editing one makes flyway-init /
`flyway validate` fail. Every schema change is a **new** file. (A PreToolUse hook will
ask you to confirm if you try to edit an existing `V*.sql`.)

## Steps
1. Pick the lineage that owns the table you're touching.
2. Find the highest existing `Vxxx` in that folder; the new file is the next integer:
   `Vxxx__short_snake_description.sql`. To fix a not-yet-released migration in the same
   PR, use a minor suffix instead: `Vxxx_1__fix_thing.sql`.
3. Write **forward-only** DDL. Timescale: create the table first, then
   `SELECT create_hypertable(...)`; continuous aggregates need their own
   `CREATE MATERIALIZED VIEW … WITH (timescaledb.continuous)` plus a refresh policy.
4. Add a header comment: phase/ticket + one-line intent.
5. Apply locally: `./ay.ps1 reset-db` rebuilds all lineages from empty, or the running
   flyway-init picks it up on the next `ay up`.
6. **Deploying it live:** `up -d <svc>` treats the exited flyway-init one-shot as
   satisfied and may NOT re-run it — `docker compose … up -d --force-recreate flyway-init`
   first, then ALWAYS DB-probe the new object (`SELECT to_regclass('schema.obj')` /
   information_schema). A healthy container + an "up to date" flyway log do NOT prove
   the migration applied (a stale checkout once deployed "healthy" without its
   migration, 2026-07-11 — only the probe caught it).

## Before a risky migration on the LIVE stack (audit M30)

A destructive or data-mutating migration on the live DB warrants a safety net. The nightly
3-slot retention can evict an ordinary `ay backup` before you've confirmed the migration is
good, so take a **rotation-exempt** dump instead:

```powershell
./ay.ps1 backup keep    # whole-db pg_dump into backups/pinned/ — the retention never evicts it
```

Roll back with `ay restore backups/pinned/<stamp>`; delete the pinned dir yourself once the
migration is proven good. (Never migrate an `ALTER` on the live DB while the nightly pg_dump
is running — it can deadlock; see [[owner-gated-f9-f7-sensexpe]].)

## Example
`deploy/flyway/backtest/V004__optimization_trials.sql`:
```sql
-- Phase 33 / optimizer-service: per-trial results for Optuna studies
CREATE TABLE backtest.optimization_trials (
    trial_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    study_id    UUID        NOT NULL,
    params      JSONB       NOT NULL,
    objective   NUMERIC,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
