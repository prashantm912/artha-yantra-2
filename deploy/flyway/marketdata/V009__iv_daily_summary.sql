-- Phase 42B (F-42B / FP-12): daily IV rollup over options_chain_snapshots.
-- One row per (underlying, IST trading day), rolled up at 16:00 IST. Because every
-- snapshot row carries its Black-76 inputs (forward_price, risk_free_rate,
-- price_source), this rollup is RECOMPUTABLE retroactively over the whole archive -
-- a solver fix or method change re-derives history instead of losing it. The
-- retroactive recompute is idempotent: identical inputs reproduce identical rows,
-- only computed_at is bumped. Soft reference to instruments(underlying); no
-- cross-schema FK (D10). Written ONLY by market-data-service (single writer, D10).

CREATE TABLE iv_daily_summary (
  underlying     TEXT NOT NULL,
  summary_date   DATE NOT NULL,
  atm_iv         NUMERIC(12,6),
  iv_30d         NUMERIC(12,6),
  spot_price     NUMERIC(18,4),
  snapshot_count INT NOT NULL DEFAULT 0,
  computed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (underlying, summary_date)
);
