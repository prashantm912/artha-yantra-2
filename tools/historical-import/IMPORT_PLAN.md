# Historical Data Import — Design & Plan

Companion to [`DATA_SOURCES.md`](DATA_SOURCES.md) (the field survey). This doc is the
*how*: target schema, loader architecture, ordering, and time estimate.

Scope decided 2026-06-18:
- **OHLCV** (options A+B, spot, equity day/minute) → existing `marketdata.candles`.
- **IV CSVs** → new `marketdata.iv_history` table.
- **Fundamentals** → new `marketdata.fundamentals` table (included in this pass).

> **Status: IMPLEMENTED** (2026-06-18). `ingest.py` rewritten; migrations
> `V016__iv_history.sql` + `V017__fundamentals.sql` added; `test_ingest.py` (27 tests)
> passing; dry-run validated on real options/spot/equity/IV/fundamentals files. Not yet
> run against a live DB at full scale.

**No futures data present.** Both sources contain only `CE`/`PE` options (verified — a
full scan of nifty/2024 and a stock symbol shows zero `FUT` files), plus spot, equity, IV,
fundamentals. The loader's filename regex (`ingest.py` `_OPT_RE`) already matches `FUT`, so
if a futures capture is added to these trees later, the **incremental load (§2.6)** picks
the new files up automatically — no code change needed.

---

## 0. Pre-flight — get data off Google Drive first

The source paths are Google Drive File Stream mounts
(`G:\.shortcut-targets-by-id\...`). ~453K files. Every uncached file triggers a
network fetch on `open()`. **This, not the database, is the dominant cost if read
directly.**

**Action before any load:**
1. `robocopy` both trees to local NVMe (e.g. `D:\market-import\`), `/MIR /MT:16`.
2. While copying, capture total bytes per category (drives the real time estimate —
   currently unknown).
3. Loader reads the **local** copy only. Drive stays untouched.

Skip the `*.zip` files and the `compressed/` folder — confirmed duplicates.

---

## 1. Target schema

### 1.1 Candles — `marketdata.candles` (exists, no change)

`V003__candles_hypertable.sql`. PK `(exchange, tradingsymbol, "interval", bucket)`,
`oi BIGINT` nullable, `source` CHECK allows `'BACKFILL'`, NUMERIC(18,4) prices.

All OHLCV lands here:

| Source | exchange | tradingsymbol | interval | oi |
|--------|----------|---------------|----------|----|
| Options A/B (NIFTY/BANKNIFTY/stocks/index) | NFO / BFO | Kite-style `NIFTY24DEC24500CE` | `1m` | from file if present, else NULL |
| Spot (nifty_spot/banknifty_spot) | NSE | `NIFTY 50` / `NIFTY BANK` | `1m` | NULL |
| Equity day | NSE | `<SYMBOL>` | `1d` | NULL |
| Equity minute | NSE | `<SYMBOL>` | `1m` | NULL |

`source = 'BACKFILL'` for everything here. Reuse `ingest.py`'s `parse_instrument()` +
`_UPSERT_CANDLES` / `_UPSERT_INSTRUMENT` SQL verbatim — the instrument-naming +
exchange-mapping logic already matches the filename convention.

### 1.2 IV history — `marketdata.iv_history` (NEW migration)

> **Note:** `iv_daily_summary` (V009) is a *computed rollup* over
> `options_chain_snapshots` (cols `atm_iv`, `iv_30d`, written only by
> market-data-service). It is **not** a dump target for these external IV CSVs —
> different columns, different provenance. Hence a separate table.

New migration `marketdata/V016__iv_history.sql` (V015 is taken; V016 is next free —
re-verify at write time):

```sql
CREATE TABLE iv_history (
  underlying     TEXT          NOT NULL,
  trade_date     DATE          NOT NULL,
  open           NUMERIC(18,4),   -- underlying spot OHLC (not option price)
  high           NUMERIC(18,4),
  low            NUMERIC(18,4),
  close          NUMERIC(18,4),
  iv             NUMERIC(12,6),   -- implied volatility %, e.g. 28.746
  iv_rank        NUMERIC(8,4),    -- "Rank" column
  iv_percentile  NUMERIC(8,4),    -- "IV Percentile" column
  source         TEXT NOT NULL DEFAULT 'BACKFILL',
  PRIMARY KEY (underlying, trade_date)
);
```

Daily granularity, low volume (~200K rows total) — plain table, **not** a hypertable.

### 1.3 Fundamentals — `marketdata.fundamentals` (NEW)

New schema + migration lineage. Source files are **wide** (periods as columns, metrics
as rows) — transpose to **long** on import so new periods append as rows, not columns.

One row = one (symbol, period, metric, value). A single tall table beats 7 typed tables
here: metrics drift over time, values are all numeric-ish, and backtest reads are
"give me metric X for symbol Y as of date Z".

**Placement decision:** table lives in the **`marketdata` schema** (`marketdata.fundamentals`),
NOT a separate `fundamental` schema. Reason: admin `V001` auto-grants `ay_backtest` SELECT on
all `marketdata` tables, so backtest reads fundamentals + IV for free — a separate schema would
need its own grant wiring + a `flyway-run.sh` lineage edit + a CI shard. Implemented as
`marketdata/V017__fundamentals.sql`:

```sql
CREATE TABLE fundamentals (              -- marketdata.fundamentals
  symbol       TEXT NOT NULL,            -- NSE symbol = folder name
  statement    TEXT NOT NULL,            -- 'quarterly_results' | 'profit_and_loss' |
                                         -- 'balance_sheet' | 'cash_flows' | 'ratios' |
                                         -- 'shareholding_yearly' | 'shareholding_quarterly'
  period_end   DATE NOT NULL,            -- 'Mar 2023' -> 2023-03-31 (last day of month)
  granularity  TEXT NOT NULL,            -- 'Q' | 'A'
  metric       TEXT NOT NULL,            -- 'Sales','Operating Profit','ROCE %',... (cleaned)
  value        NUMERIC(20,4),            -- commas stripped, % stripped
  is_percent   BOOLEAN NOT NULL DEFAULT false,
  source       TEXT NOT NULL DEFAULT 'BACKFILL',
  fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (symbol, statement, period_end, metric)
);
```

Transpose rules (from DATA_SOURCES.md §B.2):
- Strip `Â ` mojibake prefix from metric names (`Sales Â +` → `Sales`).
- Strip thousands commas: `"1,361"` → `1361`.
- `%` suffix → set `is_percent=true`, store numeric (`59.34%` → `59.34`).
- Column header `Mar 2023` → `period_end = 2023-03-31` (month-end);
  yearly = fiscal year-end (Mar) → `granularity='A'`, quarterly → `'Q'`.
- `Raw PDF` row in quarterly_results → drop (not a metric).

> Fundamentals is point-in-time-naive here: Screener.in reports the *latest* restatement,
> not what was known on the date. Acceptable for a first pass; flag if backtest needs
> true PIT (would require as-reported vintages we don't have).

---

## 2. Loader architecture

Adapt `ingest.py` — keep instrument parsing + upsert SQL, replace the read path.

### 2.1 Per-file format detection (NEVER infer from path/year)

```
read header line
  ├─ has 'oi' column?         → OI present vs absent
  ├─ has 'timestamp' column?  → Source B: ISO 8601, single col, +05:30
  └─ else 'date'+'time' cols  → Source A: split cols, IST implied
read first data row
  └─ date token:
       YYYY-MM-DD   → strptime('%Y-%m-%d')
       DD-MM-YYYY   → strptime('%d-%m-%Y')
       ISO w/ T     → fromisoformat()
```

This handles the two Source-A variants (OI present/absent, both date formats
intermixed across years — confirmed e.g. `banknifty/2020/2020-12-31` has OI) and the
Source-B ISO form.

### 2.2 Layout handlers (route file → loader by tree)

| Layout | Path shape | Handler |
|--------|-----------|---------|
| Source A options | `<sym>/<YYYY>/<YYYY-MM-DD>/SYM_strike_CE_DD_MON_YY.csv` | options parser |
| Source B options | `options/<stocks\|index>/<sym>/<YYYY-MM-DD>/SYM_strike_CE_DD_MON_YY.csv` | options parser (same filename regex) |
| Spot | `<...>/nifty_spot.csv`, `banknifty_spot.csv` | index parser, hardcode tradingsymbol |
| Equity | `equity/<day\|minute>/NNNN_SYMBOL.csv` | equity parser, interval from folder, strip `NNNN_` prefix |
| IV | `implied volatility/SYMBOL.csv` | iv parser → `iv_history` |
| Fundamentals | `fundamentals/SYMBOL/<statement>.csv` | transpose parser → `fundamental.financials` |

Filename regex `_OPT_RE` in ingest.py already matches both option trees.

### 2.3 Skip empties cheaply

Many option files are **41 bytes = header only** (no trades that strike). `stat()` size
≤ ~60 bytes → skip before `open()`. Saves hundreds of thousands of opens.

### 2.4 Throughput: COPY, not executemany

Current `ingest.py` uses `cursor.executemany(_UPSERT_CANDLES, ...)` — slow
(~5–20K rows/s). For ~3B rows that is **days**.

Replace with:
1. `COPY` rows into an **UNLOGGED** staging table (`candles_stage`) via
   `psycopg` `cursor.copy()` — 100–500K rows/s.
2. Periodic flush: `INSERT INTO candles SELECT ... FROM candles_stage
   ON CONFLICT DO UPDATE`, then `TRUNCATE candles_stage`.

Dup risk is low (one file = one instrument/expiry/strike, distinct buckets), so the
ON CONFLICT path rarely fires — but keep it for re-runs / resume safety.

### 2.5 Bulk-load tuning (revert after)

- Disable compression policy during load; compress chunks after (README §Speed tips).
- Optionally drop the continuous-aggregate refresh until done.
- `SET synchronous_commit = off` on the load session.
- Workers: 8 (matches README default; the box is the constraint, not Postgres).

### 2.6 Resume & incremental / delta loading

**The dataset is live-growing.** New expiry folders + new files appear as days pass
(daily option/equity captures; futures too, if that capture is ever added). A file
already loaded can also **grow** — e.g. an in-progress trading day appends minutes to an
existing CSV. The loader must therefore be re-runnable any time and load only what
changed.

ingest.py's current `--state-file` is a flat **done-set of paths** — it detects *new*
files but is **blind to modified** ones (a path already in the set is skipped even if its
contents changed). Replace it with a **manifest** that fingerprints each file.

**Manifest = SQLite** (not JSON — 453K rows rewritten on every checkpoint is too slow):

```sql
CREATE TABLE manifest (
  path       TEXT PRIMARY KEY,
  size       INTEGER NOT NULL,   -- bytes (st_size)
  mtime_ns   INTEGER NOT NULL,   -- st_mtime_ns
  sha256     TEXT,               -- optional, only when size+mtime ambiguous (see below)
  rows       INTEGER,            -- rows loaded (sanity)
  status     TEXT NOT NULL,      -- 'loaded' | 'failed' | 'skipped_empty' | 'deleted'
  loaded_at  TEXT NOT NULL
);
```

**Per-run classification** (walk tree, `stat()` each file — no `open()` yet):

| Condition | Class | Action |
|-----------|-------|--------|
| path not in manifest | **NEW** | load → upsert |
| size **or** mtime_ns differs from manifest | **MODIFIED** | reload → upsert (handles conflict) |
| size & mtime_ns match | **UNCHANGED** | skip (no read) |
| in manifest, absent on disk | **DELETED** | mark `status='deleted'`; do **not** purge candles by default (flag) |

`stat()`-only classification keeps a re-run over an unchanged tree cheap — no file is
opened, no row is parsed. Only NEW/MODIFIED files are read.

**Checksum policy.** Primary signal = `(size, mtime_ns)` — cheap, no read. Caveat: Google
Drive re-sync can rewrite `mtime` without a content change → spurious MODIFIED. Mitigation:
when **mtime moved but size is identical**, optionally compute `sha256` and compare to the
stored hash; reload only if the hash differs. Make this a `--verify-checksum` flag (off by
default — size+mtime is enough for the append-only growth case, which is the norm).

**Conflict handling on reload (MODIFIED files):**
- **Candles** — `_UPSERT_CANDLES` already does `ON CONFLICT (...) DO UPDATE`. For the
  common case (a file *grew*: new minutes appended), per-row upsert is correct — existing
  buckets update, new buckets insert.
- **Edge case — rows *removed* from a file** (rewrite/correction that deletes buckets):
  pure upsert leaves the old rows orphaned. If that case is real, switch MODIFIED files to
  **delete-then-insert within the file's (instrument, interval, bucket-range)** instead of
  upsert. Default to upsert; enable range-replace only if corrections are observed.
- **IV / fundamentals** — both keyed by natural PK
  (`(underlying, trade_date)` / `(symbol, statement, period_end, metric)`); reload =
  `ON CONFLICT DO UPDATE`. Idempotent.

**Checkpointing.** Update the manifest row (status + size + mtime + rows) inside the same
transaction batch as the data flush, so a crash never marks a file loaded whose rows
didn't commit. SQLite WAL mode for concurrent worker writes.

**Operational model:** schedule the loader to re-run (cron / manual). Each run: walk →
classify → load deltas → done. First run = full backfill; subsequent runs = minutes.

---

## 3. Load order (cheap → validate → giant)

1. **Spot** (2 files) — smoke-test the pipeline end to end.
2. **Equity day** (1,848 small files) — validate equity path, low memory.
3. **IV** (219 files) — validate new `iv_history` table.
4. **Fundamentals** (17,508 files) — validate transpose + `fundamental.financials`.
5. **Options A** (~98K files) — large but uniform schema.
6. **Options B stocks** (254K files) — largest file count.
7. **Options B index** (79K files).
8. **Equity minute** (1,919 files, 38–54 MB each) — **biggest row volume**, run last
   with workers dialled down (memory per file is high).

Verify after each stage with the row-count/date-range SQL in README §Verify.

---

## 4. Volume & time estimate

| Category | Files | Est. rows | Notes |
|----------|-------|-----------|-------|
| Equity minute | 1,919 | **~2–2.5B** | 47 MB/file ÷ ~40 B/row ≈ 1.2M rows/file — the giant |
| Options A (NIFTY+BANKNIFTY) | ~98K | ~0.2–0.5B | liquid strikes ~10K rows, many ~0 |
| Options B (stocks+index) | ~334K | ~0.2–0.5B | many 41-byte skips |
| Equity day | 1,848 | ~3M | |
| Spot | 2 | ~2.5M | |
| IV | 219 | ~0.2M | |
| Fundamentals | 17,508 | ~0.15M | wide→long |
| **Total** | **~453K** | **~3B rows** | equity-minute dominates |

**Wall-clock (NVMe, COPY + compression off, 8 workers):**

| Phase | Estimate | Driver |
|-------|----------|--------|
| Drive → local copy | hours (TBD) | total GB unknown until measured; likely 50–150 GB |
| Load everything except equity-minute | ~1–2 h | ~0.5–1B rows |
| Equity minute | ~3–6 h | ~2.5B rows is the wall |
| Compress chunks after | ~1 h | background |
| **End-to-end** | **~1 day** from Drive, **~4–8 h** if already local | |

> If the loader keeps `executemany` instead of COPY, multiply load phases by **10–30×**
> → multiple days. COPY is the single most important decision.

These are order-of-magnitude. Confirm after step 0 measures real bytes + after the
spot/equity-day smoke runs give a real rows/sec on this box.

---

## 5. Open items / flags

- **Total GB unknown** — measure during the Drive→local copy; it sets the real estimate.
- **Equity numeric ID** (`0031_` in `0031_BAJAJFINSV.csv`) — internal serial, dropped;
  confirm no symbol collision after stripping (two IDs → same symbol?).
- **Fundamentals PIT** — latest-restatement only, not as-reported. Fine for v1; revisit
  if backtest needs true point-in-time.
- **Spot vs index naming** — map `nifty_spot`→`NIFTY 50`, `banknifty_spot`→`NIFTY BANK`
  (ingest.py `INDEX_MAP` already has these).
- **interval correctness** — equity/day must write `'1d'`, everything else `'1m'`;
  drive off the folder, not a guess.
- **New migrations are append-only** — `iv_history` (V016) + `fundamentals` (V017) are new
  suffix-versioned files in the marketdata lineage; never edit applied migrations
  (CLAUDE.md / Flyway lock).
