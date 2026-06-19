-- Historical fundamentals backfill (tools/historical-import): company financial
-- statements imported from external wide-format CSVs (Screener.in export) and
-- transposed wide->long on load. One row per (symbol, statement, period_end, metric).
-- A single tall table beats 7 typed tables: metrics drift over time, values are all
-- numeric, and the backtest read pattern is "metric X for symbol Y as of date Z".
-- Lives in marketdata so backtest inherits the CD-1 SELECT grant (admin V001). Plain
-- table (~150K rows). Latest-restatement, NOT as-reported point-in-time (acceptable v1).

CREATE TABLE fundamentals (
  symbol       TEXT          NOT NULL,   -- NSE symbol = source folder name
  statement    TEXT          NOT NULL    -- which statement file the row came from
                             CHECK (statement IN (
                               'quarterly_results', 'profit_and_loss', 'balance_sheet',
                               'cash_flows', 'ratios',
                               'shareholding_yearly', 'shareholding_quarterly')),
  period_end   DATE          NOT NULL,   -- 'Mar 2023' -> 2023-03-31 (month-end)
  granularity  TEXT          NOT NULL CHECK (granularity IN ('Q', 'A')),
  metric       TEXT          NOT NULL,   -- cleaned metric name, e.g. 'Sales', 'ROCE %'
  value        NUMERIC(20,4),            -- thousands-commas + '%' stripped
  is_percent   BOOLEAN       NOT NULL DEFAULT false,
  source       TEXT          NOT NULL DEFAULT 'BACKFILL',
  fetched_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
  PRIMARY KEY (symbol, statement, period_end, metric)
);

CREATE INDEX idx_fundamentals_symbol_metric ON fundamentals (symbol, metric, period_end);
