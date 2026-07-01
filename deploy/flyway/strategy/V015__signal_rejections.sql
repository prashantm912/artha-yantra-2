-- Signal-rejection diagnostics: WHY a scalper chart-entry was blocked by the live
-- §12.3 confluence gate. One row per blocked entry attempt (the chart EntryEvaluator
-- fired but the confluence gate returned no Decision) — LIVE path only (the golden
-- runner injects no ScalperConfluenceGate, so replay never reaches here → parity-safe,
-- no new rows on backtest). The `diagnostic` JSONB carries the FULL breakdown: every
-- rail evaluated up to the block (pass + operand + threshold + margin), the confluence
-- dot-by-dot score (when the composite was reached), and the raw OI/macro/chart context.
-- Plain OLTP table (not a hypertable) — bounded volume (only chart-entry-fired bars).

CREATE TABLE signal_rejections (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  strategy_version_id  UUID NOT NULL REFERENCES strategy_versions(id),
  strategy_slug        TEXT NOT NULL,
  exchange             TEXT NOT NULL,
  tradingsymbol        TEXT NOT NULL,
  "interval"           TEXT NOT NULL,
  side                 TEXT,                 -- CE/PE resolved; NULL if blocked before side / neutral straddle
  blocking_rail        TEXT NOT NULL,        -- the FIRST failing condition (e.g. time-window, flat-oi-stand-aside, confluence-composite)
  blocking_operand     NUMERIC(20,6),        -- the actual value the blocking rail tested
  blocking_threshold   NUMERIC(20,6),        -- the value it needed to clear
  blocking_margin      NUMERIC(20,6),        -- operand - threshold (signed; how far short)
  blocking_reason      TEXT,                 -- the rail's human reason string
  composite_score      NUMERIC(8,4),         -- the confluence aggregate (0..1) when the composite was reached
  composite_threshold  NUMERIC(8,4),         -- the aggregate a valid signal needed
  diagnostic           JSONB NOT NULL,       -- full breakdown: checks[], confluence{dots[]}, context{oi,macro,chart}
  bar_time             TIMESTAMPTZ NOT NULL, -- the evaluated bar's bucket instant (IST offset)
  generated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- feed (newest first) + per-strategy history + per-rail aggregation
CREATE INDEX idx_signal_rejections_generated ON signal_rejections (generated_at DESC);
CREATE INDEX idx_signal_rejections_version ON signal_rejections (strategy_version_id, generated_at DESC);
CREATE INDEX idx_signal_rejections_rail ON signal_rejections (blocking_rail, generated_at DESC);

GRANT SELECT, INSERT ON signal_rejections TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
