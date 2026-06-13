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
