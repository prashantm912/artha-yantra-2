-- Roadmap F1 / signal-analysis §7: champion/challenger shadow variants.
-- Each shadow position is tagged with the virtual book it belongs to: 'champion' is the
-- existing rejected-entry book; challenger variants re-score the SAME rejection diagnostic
-- under overridden rail thresholds (e.g. a lower volume floor) and trade the entries their
-- config would have accepted — per-variant PnL labels the knob change with real outcomes.
ALTER TABLE shadow_positions
    ADD COLUMN variant TEXT NOT NULL DEFAULT 'champion';

CREATE INDEX idx_shadow_positions_variant_status
    ON shadow_positions (variant, status);
