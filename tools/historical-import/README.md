# Historical CSV Ingest

Bulk + incremental loader for the historical market-data archive (options, spot, equity,
implied volatility, fundamentals) into Postgres/TimescaleDB.

- **What's in the data:** [`DATA_SOURCES.md`](DATA_SOURCES.md) (field survey).
- **How the import is designed:** [`IMPORT_PLAN.md`](IMPORT_PLAN.md) (schema, ordering, time estimate).

## Prerequisites

```bash
pip install -r tools/historical-import/requirements.txt   # psycopg, tqdm
```

Python 3.11+ (uses `zoneinfo`, `datetime.fromisoformat` with offset). Migrations
`V016__iv_history.sql` + `V017__fundamentals.sql` must be applied (run the stack /
flyway-init) before loading IV or fundamentals.

## Copy off Google Drive first

The source trees are Drive File Stream mounts (~453K files). Reading them directly fetches
each file over the network. **Robocopy both trees to a local NVMe first**, then point
`--root` at the local copy. Skip the `*.zip` files and `compressed/` folder (duplicates).

## Usage

> **Always use `127.0.0.1` in the DSN, never `localhost`.** On Windows + Docker, `localhost`
> resolves to IPv6 `::1` first; libpq stalls ~130 s on the TCP SYN timeout before falling back
> to IPv4. With `127.0.0.1` the connect is instant (a 48-file load went 132 s → 2.1 s).

```bash
cd tools/historical-import

# Dry run — parse + classify only, no DB, no manifest writes
python ingest.py --root "D:/market-import/options" --dry-run

# First full backfill — replace mode (fast: bucket-range DELETE + direct COPY, no upsert)
python ingest.py --root "D:/market-import/options" \
  --dsn "postgresql://artha:artha@127.0.0.1:5432/artha" \
  --load-mode replace --workers 8

# Incremental top-ups (default upsert mode; re-runnable, only NEW/MODIFIED files load)
python ingest.py --root "D:/market-import/options" \
  --dsn "postgresql://artha:artha@127.0.0.1:5432/artha" --workers 8

# Mock rehearsal — same flags, mock DSN + a separate manifest
python ingest.py --root "D:/market-import/options" \
  --dsn "postgresql://artha:artha@127.0.0.1:5432/artha_mock" \
  --manifest manifest_mock.sqlite --load-mode replace --workers 8
```

`--load-mode replace` is the fast first-load path (per-row `ON CONFLICT` is ~60× slower);
`upsert` (default) is incremental-safe for top-ups. Both reuse one DB connection per worker,
so the connect cost is paid once, not per file.

Run once per source root (the two Drive folders), sharing one `--manifest` if desired
(it's keyed by absolute path).

### Incremental / delta loads

The loader fingerprints every file in a SQLite `--manifest` (default
`historical_manifest.sqlite`). On each run it walks the tree and `stat()`s each file:

- **NEW** (not in manifest) or **MODIFIED** (size or mtime changed) → loaded (upsert).
- **UNCHANGED** (size + mtime match) → skipped without opening.
- **DELETED** (in manifest, gone from disk) → reported; with `--mark-deleted`, the manifest
  row is flagged (candle rows are **not** purged).

`--verify-checksum` adds an sha256 confirm when mtime moved but size is unchanged (Google
Drive re-sync rewrites mtime without changing content) — avoids needless reloads.

Safe to Ctrl-C and resume; the manifest checkpoints every 500 files.

## Load order

Cheap → validate → giant (see IMPORT_PLAN §3): spot → equity-day → IV → fundamentals →
options → equity-minute (biggest row volume, run last, lower `--workers`).

## Tests

```bash
cd tools/historical-import && python -m pytest test_ingest.py -q
```

Covers the pure logic: path classification, instrument parsing, per-file OI/date-format
detection, IST→UTC, IV parsing, fundamentals wide→long transpose. No DB needed.

## Speed tips

Disable the TimescaleDB compression policy before a big load, re-enable + compress after:

```sql
-- before
SELECT alter_job(job_id, scheduled => false)
FROM timescaledb_information.jobs
WHERE proc_name = 'policy_compression' AND hypertable_name = 'candles';

-- after
SELECT alter_job(job_id, scheduled => true)
FROM timescaledb_information.jobs
WHERE proc_name = 'policy_compression' AND hypertable_name = 'candles';
SELECT compress_chunk(c) FROM show_chunks('marketdata.candles', older_than => INTERVAL '7 days') c;
```

The loader already sets `synchronous_commit = off` per session and COPYs into a temp
staging table before the `ON CONFLICT` upsert.

## Verify after load

```sql
-- candles: rows + date range per symbol
SELECT tradingsymbol, count(*), min(bucket), max(bucket)
FROM marketdata.candles WHERE source = 'BACKFILL'
GROUP BY tradingsymbol ORDER BY count(*) DESC LIMIT 20;

-- IV + fundamentals landed
SELECT count(*), count(DISTINCT underlying) FROM marketdata.iv_history;
SELECT count(*), count(DISTINCT symbol)     FROM marketdata.fundamentals;
```
