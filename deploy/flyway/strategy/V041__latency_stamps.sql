-- Research-fidelity audit L3 / D4 P2-5: live-only latency stamps. `signals.generated_at`
-- remains the deterministic bar-bucket instant; emitted_at is the live emit wall-clock and
-- emit_latency_ms measures bar publish -> signal emit. `paper_orders.tick_to_fill_ms` honestly
-- measures signal bar-close -> persisted fill wall-clock. Replay and legacy rows remain NULL.
-- Parity-safe additive: no deterministic record, score_breakdown, or golden vector is changed.
ALTER TABLE signals      ADD COLUMN emitted_at      TIMESTAMPTZ;
ALTER TABLE signals      ADD COLUMN emit_latency_ms BIGINT;
ALTER TABLE paper_orders ADD COLUMN tick_to_fill_ms BIGINT;
