-- EOD/ingest run ledger (app-platform audit 2026-07-10 §7.2.3 / §9.1) — the batch-source trust
-- oracle. One row per scheduled or triggered ingest run: WHICH source ran, its data window, terminal
-- outcome, rows written, error, and timings. The ingest-health board (audit item A11) reads per-source
-- last-run from here; Prompt-2's data-trust decisions consume it (esp. the silently-holey FII/DII
-- family). Plain table — a small ledger (a handful of rows/day), NOT a hypertable.
--
-- Writers (audit §7.2.3): NseEodScheduler (FII/DII cash, participant-OI, FII-derivative), the EOD
-- bhavcopy backfill, the Minervini + Manas screen schedulers, the daily instrument sync, and the
-- options snapshot capture (the one daily-summary source). Every write is fail-soft: a ledger DB
-- hiccup must never break the ingest it audits.
--
-- status vocabulary:
--   RUNNING  a start/finish batch run in flight — a row stuck RUNNING == the run crashed mid-flight
--            (a stall signal for the health board / T+1 canary), it does not vanish.
--   SUCCESS  the batch run completed, OR a capture pass persisted rows (OPTIONS_SNAPSHOT_CAPTURE).
--   FAILURE  the run threw; `error` carries the message. The job's own exception is still rethrown to
--            the caller — the ledger records the failure, it does not swallow it.
--
-- window_start / window_end are the DATA window the run covers, where meaningful. Latest-only pulls
-- (FII/DII, participant-OI, FII-derivative) and full dumps (instrument sync) leave them null — the
-- run time lives in started_at/finished_at. OPTIONS_SNAPSHOT_CAPTURE is the lone daily-summary source
-- (§7.2.3 "1 row/day"): window_start is the IST session day and rows_written accumulates across the
-- day's 2-min capture passes (see the partial unique index below).
--
-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here
-- (mirrors V030 backfill_jobs / V031 / V035).
CREATE TABLE ingest_runs (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source       TEXT         NOT NULL,                       -- NSE_FII_DII / BHAVCOPY / MINERVINI_SCREEN / ...
    window_start TIMESTAMPTZ,                                 -- data window covered (null where not meaningful)
    window_end   TIMESTAMPTZ,
    status       TEXT         NOT NULL,                       -- RUNNING / SUCCESS / FAILURE
    rows_written BIGINT,
    error        TEXT,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

-- The A11 health-board query pattern: newest run per source (per-source last-run lookup). A plain
-- (source, started_at DESC) index — never a CURRENT_DATE-baked predicate (in-container now() is UTC).
CREATE INDEX ix_ingest_runs_source_started_at ON ingest_runs (source, started_at DESC);

-- The daily-summary source keeps exactly one row per IST session day (accumulating upsert). This
-- partial unique index scopes the one-per-(source, window_start) constraint to capture rows only, so
-- batch runs (which may share a null/repeat window) are never constrained.
CREATE UNIQUE INDEX ux_ingest_runs_capture_session
    ON ingest_runs (source, window_start)
    WHERE source = 'OPTIONS_SNAPSHOT_CAPTURE';
