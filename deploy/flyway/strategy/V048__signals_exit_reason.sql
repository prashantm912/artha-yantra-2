-- 2026-07-27: persist WHY an EXIT signal fired.
--
-- `SignalEngine.emit(...)` already computes an `exitReason` (STRUCTURAL_STOP / CONFLUENCE_FLIP /
-- the ExitEvaluator reasons) and sends it to exactly three places: a log line, a `SignalExited`
-- event, and — via that event — the paper close, which stores it in `paper_events.reason`. It was
-- never written to the signal row.
--
-- That is only adequate while every exiting strategy holds a paper position. SCALPERS DO NOT: as of
-- today all 30 paper positions sit on the minervini/manas-arora swing books, while scalpers have
-- emitted 10 EXIT signals. Those reasons reached the log alone and are gone with container restarts,
-- so "why did this scalper exit?" is unanswerable from the database.
--
-- It blocks a specific open question (docs/signal-analysis/2026-07-27-confluence-flip-rail-audit.md
-- §6): whether the `oi-confluence-exit` flip oracle fires when it should. That cannot be settled by
-- reasoning — only by observing which reason actually fired — so the reason has to be durable first.
--
-- NULL for every ENTRY row and for historical EXITs (not backfillable: the log lines are gone).
ALTER TABLE signals ADD COLUMN exit_reason TEXT;

COMMENT ON COLUMN signals.exit_reason IS
  'Why an EXIT signal fired (STRUCTURAL_STOP / CONFLUENCE_FLIP / ExitEvaluator reason). NULL on ENTRY rows and on EXITs predating 2026-07-27, whose reasons were only ever logged.';

-- The question this exists to answer is "which reason fired, how often" over a recent window, so the
-- index is on the shape that query takes rather than on the column alone. Partial: ENTRY rows are the
-- large majority and carry no reason, so they are excluded from the index entirely.
CREATE INDEX idx_signals_exit_reason
    ON signals (exit_reason, generated_at DESC)
    WHERE exit_reason IS NOT NULL;
