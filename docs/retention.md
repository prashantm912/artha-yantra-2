# Data retention & disk budget (Q4 record — Phase 16 / B-7)

**Policy: default no-drop.** Nothing in the marketdata schema carries a
TimescaleDB retention policy. The triggers below are REVIEW triggers, not
auto-deletes.

| Dataset | Policy | Rationale |
|---|---|---|
| `options_chain_snapshots` | **≥ 5-year floor (amendment A2)** — never dropped | The only irreplaceable dataset: Kite serves no historical options chains. Every row carries full solver provenance (`price_source`, `forward_price`, `risk_free_rate`), so a solver defect is a backfill migration, not data loss. |
| `candles` (1m/1d + CONT stitch) | Uncapped — DB-as-cache, re-fetchable | Kite serves ~10 y of minute history; a purge is a rate-limited re-backfill (~61 pages ≈ 21 s of 3/s budget per symbol). Compressed after 7 days. |
| Continuous aggregates (5m/15m/1h/1d/1w) | Derived — rebuildable by `refresh_continuous_aggregate` | Never the source of truth. |
| `roll_events`, `contract_spec_history`, `corporate_action_events` | Append-only, tiny | Audit trails; cost is rounding error. |
| Redis | Ephemeral by design (TTLs ≤ 60 s on chain keys; last-tick map bounded by instrument count) | D11: shared state only, DB is truth. |

## Disk budget (B-7)

Rough steady-state estimates at the default scope (NIFTY 50 + NIFTY BANK
chains, ~200 active equities, pinned FUT/VIX):

| Source | Growth | 1 year |
|---|---|---|
| Options snapshots (962 rows × ~75 passes/day × 2 underlyings, compressed) | ~25–40 MB/day raw → ~4–8 MB/day compressed | ~1.5–3 GB |
| 1m candles (~250 instruments × 375 bars/day, compressed) | ~2–4 MB/day | ~0.7–1.5 GB |
| Everything else | noise | < 0.5 GB |

**Review trigger: 50 GB total volume.** This is a **whole-database** volume
(`pg_database_size`), not any single hypertable, and a **live-`artha` budget**.
`ay status` is the input: on the live profile it prints the DB total vs the 50 GB
trigger (`DB 'artha' = 46.1 GB / 50 GB review trigger (92%)`) and warns at ≥ 80 %;
on a mock profile it reports the `artha_mock` DB size only (the trigger is not
evaluated). Either way it lists the largest hypertables, continuous aggregates and
tables (`hypertable_size` / `pg_total_relation_size`). At the trigger, review
compression ratios and snapshot scope — the answer is widening disk or
narrowing scope, never silent retention.

> Metric caveat (audit AYDB-03): the `ay_hypertable_bytes` Micrometer gauge
> samples **only `marketdata.candles`** (~half the DB), so it under-reports the
> whole-DB trigger by ~2×. The companion `ay_database_size_bytes` gauge (added
> alongside it) samples the same `pg_database_size` total `ay status` shows, so
> the CLI and the metric agree. Neither is scraped yet — the `obs` profile
> (Prometheus/Grafana) never shipped — so **`ay status` is the operative input.**

## Backups & restore (whole-database)

The `db-backup` sidecar (`deploy/backup/backup.sh`) takes a **whole-database**
`pg_dump -Fc` plus a `pg_dumpall --globals-only` (roles/grants) at 00:30 IST into the
host bind-mount `./backups/<mode>/<stamp>/` (`<db>-full.dump` + `globals.sql`). `ay backup`
runs it on demand. **Retention: the 3 NEWEST backups across `manual/` + `nightly/` +
`weekly/` COMBINED** — a 4th evicts the oldest (each whole-db dump is ~3 GB ⇒ ~9 GB cap).
`weekly/` is no longer written (the Sunday-copy tier is obsolete under a 3-total cap).

> **Why whole-database (a per-schema dump is a data-loss trap).** TimescaleDB stores
> hypertable rows in `_timescaledb_internal` chunks — *outside* the table's own schema —
> so a `pg_dump -n marketdata` captures the empty hypertable parents and **silently omits
> every `candles` / `options_chain_snapshots` row** (200M+ rows; the bulk that took weeks
> to backfill/capture). The pre-2026-07-01 per-schema sidecar had exactly this hole: its
> `marketdata.dump` was ~25 MB for a 31 GB database. A whole-db dump (~3 GB `-Fc`, ~12 min)
> captures all four schemas + chunk data and is the only dump that round-trips a rebuild.

**Restore (`ay restore <backup-dir-or-full-dump>`)** — DESTRUCTIVE, drops + recreates the
active-profile DB: stops the stack → brings up only `timescaledb` → `dropdb`/`createdb` →
loads `globals.sql` → `CREATE EXTENSION timescaledb` → `timescaledb_pre_restore()` →
`pg_restore --no-owner` (full, NOT `--data-only`) → `timescaledb_post_restore()` → `ANALYZE`
→ `ay up` (flyway-init validates the restored history head, a no-op). Use this — not
`ay reset-db` — when you want a rebuild that **keeps** the data. `ay reset-db` is the empty
(Flyway-only) rebuild. Restore drill quarterly.
