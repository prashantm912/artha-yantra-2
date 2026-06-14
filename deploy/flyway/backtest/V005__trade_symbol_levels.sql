-- Stage F follow-on: denormalize the run's instrument onto each trade row and persist the
-- entry-time protective levels. A backtest run is single-instrument, so exchange/tradingsymbol
-- echo the parent run (lets chart trade-marks + the results "View on chart" deep-link carry the
-- right symbol without a join, and supports the trades-endpoint `symbol` filter). stop_loss /
-- take_profit are the absolute entry-time levels (NULL when the strategy declares no such rule or
-- for trades written before this migration). All NULL-able — pre-existing rows keep validating and
-- the replay writes them as a pure side-channel (no parity impact).

ALTER TABLE backtest_trades
  ADD COLUMN exchange      TEXT,
  ADD COLUMN tradingsymbol TEXT,
  ADD COLUMN stop_loss     NUMERIC(18,4),
  ADD COLUMN take_profit   NUMERIC(18,4);
