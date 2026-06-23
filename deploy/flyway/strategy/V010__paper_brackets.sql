-- Phase 4b / scalper cockpit: optional stop-loss + take-profit bracket levels on a paper position.
-- PaperBracketEvaluator auto-closes the position (close_reason STOP_LOSS / TAKE_PROFIT) when the live
-- LTP breaches a level. Both nullable — a position without brackets behaves exactly as before.
ALTER TABLE paper_positions ADD COLUMN stop_loss   NUMERIC(18,4);
ALTER TABLE paper_positions ADD COLUMN take_profit NUMERIC(18,4);
