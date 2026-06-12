-- backtest lineage baseline (A.8): schema created by the admin lineage
-- (CD-1). Tables (jobs, runs, folds, metrics) arrive in Stage D as V002+.
-- This baseline only anchors an independent flyway_schema_history for the
-- lineage.
COMMENT ON SCHEMA backtest IS
  'Owned by backtest-service - single writer (D10); reads marketdata via the CD-1 grant';
