-- Data-foundation milestone / market-data: provenance on the OI snapshot tables.
-- Distinguishes BACKFILL rows (the Oi-backfill importer, OpenAlgo /history) from live-captured rows.
--
-- These are COMPRESSED hypertables (compress-after-7d: V006 options, V011 futures). The compressed-
-- safe pattern — matching the V007_1 / V015 ADD-COLUMN precedent — is a NULLABLE add (no NOT-NULL,
-- no inline DEFAULT, both of which would rewrite compressed chunks) plus a catalog-only SET DEFAULT
-- for future inserts. So: existing (pre-migration) rows stay NULL and are read as live by convention;
-- new live inserts get 'LIVE' via the default; the backfill importer writes 'BACKFILL' explicitly.
-- No CHECK constraint — a new constraint on a compressed hypertable needs the decompress-dance (V018);
-- the application enforces the {LIVE, BACKFILL} values.

ALTER TABLE options_chain_snapshots ADD COLUMN source TEXT;
ALTER TABLE options_chain_snapshots ALTER COLUMN source SET DEFAULT 'LIVE';

ALTER TABLE futures_oi_snapshots ADD COLUMN source TEXT;
ALTER TABLE futures_oi_snapshots ALTER COLUMN source SET DEFAULT 'LIVE';
