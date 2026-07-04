-- Phase 9 (MV-6.8): the Minervini swing side-channel. A fired minervini setup signal records its
-- setup detail (setup type, stage, VCP footprint, pivot, gate booleans) here — OUTSIDE the frozen
-- C-2.6 score_breakdown JSONB (a strategy-specific key inside score_breakdown is a parity FAIL, the
-- same rule as scalper_detail V009 and suggested_qty). NULL for every non-minervini signal.
ALTER TABLE signals ADD COLUMN minervini_detail JSONB;
