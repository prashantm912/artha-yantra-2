-- marketdata lineage baseline (A.8): the schema itself is created by the
-- admin lineage (CD-1). Tables (instruments, candles hypertable, token
-- store, options snapshots) arrive in Stage B as V002+. This baseline only
-- anchors an independent flyway_schema_history for the lineage.
COMMENT ON SCHEMA marketdata IS
  'Owned by market-data-service - single writer (D10); backtest has read-only access (CD-1)';
