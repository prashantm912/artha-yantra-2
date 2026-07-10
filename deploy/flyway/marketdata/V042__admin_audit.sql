-- Append-only audit trail for privileged operator actions on the market-data admin surface
-- (app-platform audit 2026-07-10 §8 V14 + §7.2/§10 Phase-1: "Export/query-console audit | zero trace").
-- Two admin surfaces ran arbitrary/bulk data reads with NO durable trace before this:
--   1. the B5 read-only SQL console (POST /api/v1/market/admin/query + /query/export), and
--   2. the B6 backfilled-candle export (POST /api/v1/market/admin/export + /export/bulk).
-- One row lands here per execution: WHAT ran (sql_text + sql_hash for the console; the request's
-- contract-set in `detail` for exports), how many rows it returned, whether the result was TRUNCATED
-- by a row cap, and (for queries) how long it took. V14's exact prescription: "sql-hash/params/rows/
-- duration for queries; contract-set/row-counts for exports."
--
-- Append-only: the writer (AdminAuditLedger) only ever INSERTs — there is no UPDATE/DELETE path in code.
-- Every write is fail-soft (try/catch + log.warn): an audit-DB hiccup must never break the read it
-- audits (mirrors the A4 ingest_runs ledger). Plain OLTP table (a handful of rows per operator session),
-- NOT a hypertable.
--
-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here
-- (mirrors V030 backfill_jobs / V040 ingest_runs).
CREATE TABLE admin_audit (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action      TEXT         NOT NULL,               -- QUERY | QUERY_EXPORT | CANDLE_EXPORT | CANDLE_EXPORT_BULK
    sql_text    TEXT,                                -- the executed SELECT/WITH (console actions only)
    sql_hash    TEXT,                                -- SHA-256 hex of sql_text (console actions only)
    detail      JSONB,                               -- query: {rowLimit}; export: the request's contract-set/params
    row_count   BIGINT,                              -- rows returned (query) / rows exported (export)
    truncated   BOOLEAN      NOT NULL DEFAULT false, -- true when a row cap clipped the result
    duration_ms BIGINT,                              -- console execution wall time (null for candle exports)
    error       TEXT,                                -- populated when the audited action itself threw
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()  -- timestamptz — in-container now() is UTC, never ::date
);

-- Newest-first-per-action lookup (the forensics read pattern). A plain (action, created_at DESC)
-- index — never a CURRENT_DATE-baked predicate (in-container now() is UTC).
CREATE INDEX ix_admin_audit_action_created_at ON admin_audit (action, created_at DESC);
