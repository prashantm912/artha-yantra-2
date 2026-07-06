-- Audit H5: attribute a CLOSED paper position to the ONE strategy that opened it.
-- Before this, GraduationService.closedPnls matched positions to orders by
-- (book, exchange, tradingsymbol, side), so two strategies that ever traded the same key both
-- claimed a position's realized P&L → F7 graduation evidence was cross-attributed (a position could
-- count toward BOTH strategies' win-rate / drawdown stats). Record the opening signal directly on the
-- position; graduation then joins position → opening signal → strategy_version → strategy, so each
-- CLOSED position counts for EXACTLY one strategy (or none, for a hand order).

ALTER TABLE paper_positions
  ADD COLUMN opening_signal_id BIGINT REFERENCES signals(id) ON DELETE SET NULL;

-- Best-effort backfill of positions that predate this column: the earliest signal-carrying order for
-- the position's key whose fill falls within the position's own lifetime [opened_at, closed_at].
-- Time-scoping by the order's timestamp (not just min(id)) keeps re-opened keys attributed to the
-- right lifetime. A hand order, or a position whose opening order truly cannot be disambiguated,
-- stays NULL — it then counts toward no strategy's graduation stats, which is the correct default
-- (the old cross-attributed number was wrong, not merely imprecise).
UPDATE paper_positions p
SET opening_signal_id = (
  SELECT o.signal_id
  FROM paper_orders o
  WHERE o.book = p.book
    AND o.exchange = p.exchange
    AND o.tradingsymbol = p.tradingsymbol
    AND o.side = p.side
    AND o.signal_id IS NOT NULL
    AND COALESCE(o.filled_at, o.placed_at) >= p.opened_at
    AND COALESCE(o.filled_at, o.placed_at) <= COALESCE(p.closed_at, now())
  ORDER BY COALESCE(o.filled_at, o.placed_at) ASC, o.id ASC
  LIMIT 1
)
WHERE p.opening_signal_id IS NULL;

CREATE INDEX idx_paper_positions_opening_signal ON paper_positions (opening_signal_id);
