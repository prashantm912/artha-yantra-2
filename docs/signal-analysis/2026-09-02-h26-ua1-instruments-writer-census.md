# Receipt — who actually writes `marketdata.instruments` (H26 U-A1 backfill scoping)

**Date measured: 2026-09-02.** Companion to `deploy/flyway/marketdata/V060__instruments_upstox_identity_columns.sql`
and PR #1553.

This document exists because the migration deliberately carries **no counts**. A Flyway migration
comment is checksum-locked forever, so a number frozen there can never be corrected — and this
population moves with every import, expiry roll and instrument sync. The migration keeps the
timeless writer-shape rationale; the measurements live here, dated, where they can be re-derived and
superseded.

⚠️ **Re-derive these before relying on them. They are a snapshot, not a constant.**

## Why this was measured at all

The first cut of V060 asserted, in the migration comment and in the PR body, that

> `instruments` is written only by the Kite instrument-dump sync

and backfilled `kite_last_seen_at = last_seen_at` unconditionally on that basis.

**The premise is false.** Cross-vendor review caught it. Three writers exist:

| writer | shape it leaves | Kite-asserted? |
|---|---|---|
| Kite instrument-dump sync (`InstrumentRepository.stageDump` → swap) | token + name + segment | **yes** |
| `tools/historical-import/ingest.py` (`_UPSERT_INSTRUMENT`) | inactive, no token, no name, no segment | no |
| `InstrumentRepository.upsertSyntheticCont` | `segment = 'SYN-CONT'`, no token; its javadoc says such rows can "never reach a Kite port" | no |

## The census — `computed` 2026-09-02

```sql
SELECT CASE
         WHEN segment = 'SYN-CONT' THEN 'SYN-CONT (synthetic)'
         WHEN instrument_token IS NULL AND name IS NULL AND segment IS NULL
              THEN 'no token/name/segment (import placeholder)'
         WHEN instrument_token IS NULL THEN 'tokenless but has name/segment'
         ELSE 'Kite dump row' END AS bucket,
       count(*), count(*) FILTER (WHERE is_active) AS active
FROM marketdata.instruments GROUP BY 1 ORDER BY 2 DESC;
```

| bucket | rows | active |
|---|---|---|
| import placeholder (no token/name/segment) | **182,487** | 0 |
| Kite dump row | 134,435 | 59,017 |
| SYN-CONT (synthetic) | 6 | 6 |
| tokenless but has name/segment | **0** | 0 |

**The placeholders are ~58% of the table.** An unconditional backfill would therefore have stamped
"Kite asserted this" on the majority of rows, corrupting the exact provenance U-A2 uses to decide
tombstone ownership — a rule whose failure mode is deactivating every Kite-only row.

## Why the predicate is safe for the H29/H36 population

The plan warns that per-source tombstone scoping must not strand the `-BE` twins. Checked
explicitly, because those are the rows most likely to be mis-scoped:

| symbol | token | name | segment | active |
|---|---|---|---|---|
| `DIACABS` / `DIACABS-BE` | present | present | `NSE` | f / t |
| `MENONBE` / `MENONBE-BE` | present | present | `NSE` | f / t |
| `SABEVENTS` / `SABEVENTS-BE` | present | present | `NSE` | f / f |

All carry token + name + segment, so all are **included** by the backfill. And the
`tokenless but has name/segment` bucket is **empty**, so the metadata test cannot strand a tokenless
Kite row — the H29 shape is covered by construction, not by luck.

⚠️ That empty bucket is the load-bearing observation here, and it is the one most likely to change.
If a future Kite dump ever emits a row without `name` and without `segment`, the predicate would
start excluding a genuinely Kite-asserted row. **Re-run the census before trusting the predicate
again.**

## What is NOT claimed

- These counts are not a stable property of the schema. They move daily.
- The census does not prove the three writers are the only ones that will ever exist — it proves
  they are the ones present in the working tree on this date. A fourth writer added later would
  need this scoping revisited.
