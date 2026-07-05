-- Audit P0-4/H10 (2026-07-06): per-batch daily run marker — the did-not-run canary's dead-man
-- switch. Each swing batch upserts one row per run date after it completes; the 08:30 IST
-- SwingBatchCanary alerts when an ARMED batch has no row for the last NSE trading day (a container
-- down at cron time is a SILENT no-run with no catch-up — open positions' stops simply are not
-- evaluated that evening, which corrupts the forward-paper evidence the reliability bar reads).
CREATE TABLE swing_batch_runs (
    batch        TEXT        NOT NULL,   -- 'minervini' | 'manas-arora'
    run_date     DATE        NOT NULL,   -- IST calendar date the batch ran for
    ran_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    strategies   INTEGER     NOT NULL,
    candidates   INTEGER     NOT NULL,
    entries      INTEGER     NOT NULL,
    exits        INTEGER     NOT NULL,
    exit_skipped INTEGER     NOT NULL,   -- held anchors whose stop could NOT be evaluated (P0-3)
    PRIMARY KEY (batch, run_date)
);

COMMENT ON TABLE swing_batch_runs IS
  'One row per swing-batch run per IST date (upserted by the batch schedulers). The 08:30 IST canary alerts when an armed batch has no row for the last NSE trading day.';

-- Match the lineage's least-privilege convention (every prior CREATE TABLE grants to ay_strategy).
GRANT SELECT, INSERT, UPDATE ON swing_batch_runs TO ay_strategy;
