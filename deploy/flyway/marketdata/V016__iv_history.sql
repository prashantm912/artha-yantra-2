-- Historical IV backfill (tools/historical-import): daily implied-volatility series
-- per underlying, imported from external IV CSVs (underlying spot OHLC + IV + rank +
-- percentile). DISTINCT from iv_daily_summary (V009), which is a computed Black-76
-- rollup over options_chain_snapshots written by market-data-service. This table is a
-- raw external archive (source='BACKFILL'); low volume (~200K rows), plain table — not
-- a hypertable. Backtest reads it via the CD-1 marketdata SELECT grant (admin V001).

CREATE TABLE iv_history (
  underlying     TEXT          NOT NULL,
  trade_date     DATE          NOT NULL,
  open           NUMERIC(18,4),   -- underlying spot OHLC (NOT option price)
  high           NUMERIC(18,4),
  low            NUMERIC(18,4),
  close          NUMERIC(18,4),
  iv             NUMERIC(12,6),   -- implied volatility %, e.g. 28.746000
  iv_rank        NUMERIC(8,4),    -- "Rank" column
  iv_percentile  NUMERIC(8,4),    -- "IV Percentile" column (0-100)
  source         TEXT          NOT NULL DEFAULT 'BACKFILL'
                                 CHECK (source IN ('BACKFILL', 'KITE', 'MOCK')),
  fetched_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  PRIMARY KEY (underlying, trade_date)
);
