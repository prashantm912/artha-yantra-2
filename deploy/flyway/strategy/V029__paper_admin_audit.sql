-- Append-only audit trail for the two DESTRUCTIVE paper-book admin actions that left ZERO trace before
-- this (app-platform audit 2026-07-10 §7.1 "Paper reset / capital edit | nothing | destructive action
-- with zero audit" + §7.2.5 "Paper reset / capital-change audit rows (extend risk_audit or a small
-- paper_admin_audit)"). We take the dedicated-table option (risk_audit carries a different shape — risk
-- setting flips/trips keyed by cap): cleaner append-only semantics, no overloading of the risk model.
--
-- One row per action:
--   RESET          a book wipe (POST /api/v1/paper/reset) — book = target (null = all books);
--                  detail = {"positionsDeleted": n, "ordersDeleted": m}.
--   CAPITAL_CHANGE an owner edit of a book's starting capital (PUT /api/v1/paper/account) —
--                  detail = {"previous": "<inr>", "new": "<inr>"} (money-as-string convention).
--
-- Append-only: the writer (PaperAdminAuditLedger) only ever INSERTs — no UPDATE/DELETE path in code.
-- Every write is fail-soft (try/catch + log.warn): an audit-DB hiccup must never break the action it
-- audits (mirrors the A4 ingest_runs ledger). Plain OLTP table (a handful of rows), NOT a hypertable.
CREATE TABLE paper_admin_audit (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action      TEXT         NOT NULL,               -- RESET | CAPITAL_CHANGE
    book        TEXT,                                -- target book (null = all books, RESET only)
    detail      JSONB,                               -- RESET: {positionsDeleted, ordersDeleted}; CAPITAL_CHANGE: {previous, new}
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()  -- timestamptz — in-container now() is UTC, never ::date
);

-- Newest-first-per-action lookup (the forensics read pattern). A plain (action, created_at DESC)
-- index — never a CURRENT_DATE-baked predicate (in-container now() is UTC).
CREATE INDEX ix_paper_admin_audit_action_created_at ON paper_admin_audit (action, created_at DESC);

-- Append-only by GRANT for the read-only per-schema role (mirrors V027 subscriber_health_events):
-- SELECT + INSERT only, never UPDATE/DELETE. The service itself connects as `artha` (D10 single-writer).
GRANT SELECT, INSERT ON paper_admin_audit TO ay_strategy;
