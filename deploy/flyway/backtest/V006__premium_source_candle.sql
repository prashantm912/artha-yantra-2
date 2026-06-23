-- Part 2 (premium-as-primary replay): a backtest can now trade an option's OWN 1m candle series
-- (the backfilled expired-option premiums, source=BACKFILL in marketdata.candles) as the tradeable
-- price instead of the underlying candle close. CANDLE_1M is the highest-fidelity premium source —
-- finer than the 5-minute SNAPSHOT path. Extend the §D.15 provenance CHECK (forward-only; the inline
-- CHECK from V003 is replaced, never edited in place).
ALTER TABLE backtest_runs DROP CONSTRAINT backtest_runs_premium_source_check;
ALTER TABLE backtest_runs ADD CONSTRAINT backtest_runs_premium_source_check
    CHECK (premium_source IN ('SNAPSHOT', 'SYNTHETIC_B76', 'NA', 'CANDLE_1M'));
