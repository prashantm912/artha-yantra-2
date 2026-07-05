-- F7 strategy graduation (2026-07-05, owner-approved auto-promotion): when a published strategy's
-- CLOSED paper trades clear a stricter bar than TAKE_ELIGIBLE (≥50 trades + positive expectancy +
-- a Sharpe floor + max-drawdown no worse), the daily promotion evaluator marks it GRADUATED and
-- fires an ntfy. Purely a stage marker + alert — it NEVER arms, rewrites gate config, or places a
-- live order (owner ruling: promotion changes no live signal gating; the owner decides any real
-- live change). One row per graduated strategy (presence = graduated); upsert re-stamps the metrics
-- snapshot if it re-qualifies. Flag-gated at the app layer (artha.graduation.promotion-enabled).

CREATE TABLE strategy_graduations (
    strategy_id      UUID        PRIMARY KEY REFERENCES strategies(id) ON DELETE CASCADE,
    graduated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    trades           INTEGER     NOT NULL,
    expectancy       NUMERIC(18,4),
    sharpe           NUMERIC(10,4),
    max_drawdown_pct NUMERIC(8,4),
    metrics          JSONB       NOT NULL   -- full metrics snapshot at graduation (audit)
);

COMMENT ON TABLE strategy_graduations IS
  'F7: the set of strategies the daily promotion evaluator has marked GRADUATED (a measurement stage + ntfy, never a live action). Presence of a row = graduated; the row is upserted with the latest qualifying metrics snapshot.';
