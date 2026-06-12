-- strategy lineage baseline (A.8): schema created by the admin lineage
-- (CD-1). Tables (strategies, versions, signals, paper trading) arrive in
-- Stage C as V002+. This baseline only anchors an independent
-- flyway_schema_history for the lineage.
COMMENT ON SCHEMA strategy IS
  'Owned by strategy-signal-service - single writer (D10); no cross-schema grants';
