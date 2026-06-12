-- Stage B audit gap fixes (suffix-versioned, B-8).
-- Record correction (V005 is applied and immutable): its header claims "no
-- other schema role has any grant" on kite_session - in fact the blanket CD-1
-- read grant lets ay_backtest SELECT the row, which holds only AES-256-GCM
-- ciphertext + nonce; ARTHA_MASTER_KEY never reaches the database, so the
-- grant exposes nothing usable.
--
-- 1) B-7 pins sort_order on both watchlist tables (Phase 17 rename/reorder).
-- 2) B-7 pins oi_change on options_chain_snapshots (delta vs the previous
--    snapshot pass - null on the first pass of a day).
-- 3) B-7 indexing strategy: (underlying, expiry, ts DESC) for chain-over-time
--    scans and the history endpoint.

ALTER TABLE watchlists ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
ALTER TABLE watchlist_items ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE options_chain_snapshots ADD COLUMN oi_change BIGINT;

CREATE INDEX idx_ocs_underlying_expiry_ts
    ON options_chain_snapshots (underlying, expiry, ts DESC);
