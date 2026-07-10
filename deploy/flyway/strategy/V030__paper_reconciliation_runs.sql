-- Nightly paper-ledger reconciliation output (app-platform audit 2026-07-10 §8, findings V5 + V16).
--
--   V5  Position <-> order-leg reconciliation: none today. Every CLOSED paper position's lifecycle
--       should be explicable by its order rows — an entry leg (same side, Σqty == position qty) and an
--       exit leg (opposite side). Orphans (a closed position with no entry orders, an entry-qty
--       mismatch, or a closed position with no exit order) are integrity drift.
--   V16 TAKEN-signal <-> position reconciliation: none today — an auto-paper open that throws AFTER the
--       CAS ACTIVE->TAKEN transition (AutoPaperListener) leaves a TAKEN signal with NO paper_orders.signal_id
--       row, invisibly (the A1 residual). The check: every TAKEN signal that was expected to open a
--       position (suggested_qty > 0) has >= 1 paper_orders.signal_id row; plus the inverse — a position
--       on an auto-paper book with no opening_signal_id where a signal linkage is expected.
--
-- The audit says "report to the ingest health board" — but that board lives in the MARKETDATA schema
-- (A11), and a strategy-schema job writing marketdata rows would violate D10 (single-writer per schema).
-- So the reconciler reports IN ITS OWN schema: one append-only row per nightly run here, plus
-- ay_paper_recon_* Micrometer counters and a once-per-run fail-soft ntfy when discrepancies > 0.
--
-- One row per run: the checked-counts + per-check discrepancy tallies, with the actual orphan ids in
-- `detail` JSONB for forensics. Append-only (PaperReconciliationRepository only ever INSERTs). Plain
-- OLTP table (one row per evening), NOT a hypertable.
CREATE TABLE paper_reconciliation_runs (
    id                     BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ran_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- timestamptz — in-container now() is UTC, never ::date
    window_start           TIMESTAMPTZ  NOT NULL,                 -- explicit IST-instant lower bound of the checked window
    window_end             TIMESTAMPTZ  NOT NULL,                 -- explicit IST-instant upper bound (the run instant)
    positions_checked      INTEGER      NOT NULL,                 -- CLOSED positions closed within the window (V5 scope)
    taken_signals_checked  INTEGER      NOT NULL,                 -- TAKEN signals generated within the window, suggested_qty>0 (V16 scope)
    v5_discrepancies       INTEGER      NOT NULL,                 -- missingEntryOrder + entryQtyMismatch + missingExitOrder
    v16_discrepancies      INTEGER      NOT NULL,                 -- takenWithoutOrder + positionWithoutSignal
    total_discrepancies    INTEGER      NOT NULL,                 -- v5_discrepancies + v16_discrepancies
    detail                 JSONB        NOT NULL DEFAULT '{}'::jsonb  -- {"v5":{missingEntryOrder:[..],entryQtyMismatch:[..],missingExitOrder:[..]},"v16":{takenWithoutOrder:[..],positionWithoutSignal:[..]}}
);

-- Newest-first lookup (the forensics / health-read pattern). A plain (ran_at DESC) index — never a
-- CURRENT_DATE-baked predicate (in-container now() is UTC, off-by-one across IST midnight).
CREATE INDEX ix_paper_reconciliation_runs_ran_at ON paper_reconciliation_runs (ran_at DESC);

-- Append-only by GRANT for the read-only per-schema role (mirrors V027/V029): SELECT + INSERT only,
-- never UPDATE/DELETE. The service itself connects as `artha` (D10 single-writer).
GRANT SELECT, INSERT ON paper_reconciliation_runs TO ay_strategy;
