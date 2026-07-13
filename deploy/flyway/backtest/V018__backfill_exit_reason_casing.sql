-- Backfill backtest_trades.exit_reason to the canonical UPPERCASE vocabulary (chip task_cf5d58c8).
--
-- The candle-path replay historically persisted lowercase reasons (signal_exit / end_of_data, and
-- post-P1-3 stop_loss / trailing_stop / take_profit / square_off / time_stop) while the
-- options-premium, deep-swing and live-paper (paper_positions.close_reason) paths use UPPERCASE
-- (STOP_LOSS / …). backtest_trades is a SHARED table (candle + options + deep-swing all write it via
-- TradeRepository.insertAll), so the two casings mixed within one table.
--
-- TradeRepository.insertAll now upper-cases every NEW write; this one-shot backfills the pre-existing
-- lowercase rows so the table is uniform going backward too. Idempotent (the WHERE clause skips rows
-- already UPPERCASE, and upper() of an UPPERCASE value is itself) and a no-op on fresh / CI databases
-- where backtest_trades is empty. Data-only — no schema change.
UPDATE backtest_trades
   SET exit_reason = upper(exit_reason)
 WHERE exit_reason IS NOT NULL
   AND exit_reason <> upper(exit_reason);
