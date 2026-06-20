-- Phase B (EOD bhavcopy → candles): BSE daily equity bhavcopy in the UDiFF format
-- (BhavCopy_BSE_CM_0_0_0_YYYYMMDD_F_0000.CSV). Only cash-equity rows (FinInstrmTp=STK, Sgmt=CM) are
-- stored. Keyed by the BSE scrip code (FinInstrmId); the ticker (TckrSymb) is what our instruments
-- table uses as the BSE tradingsymbol, and ISIN is kept so a corporate-action keyed on the NSE
-- listing can be cross-applied to the BSE listing (Phase C). ~4.8k rows/day. Monthly chunks,
-- compress after 7d, A2 keep-history (no auto-retention).
CREATE TABLE bse_eod_bhavcopy (
    trade_date     DATE          NOT NULL,
    scrip_code     TEXT          NOT NULL,   -- FinInstrmId (numeric BSE scrip code, e.g. 500325)
    ticker         TEXT,                      -- TckrSymb (e.g. RELIANCE) — the BSE tradingsymbol
    isin           TEXT,                      -- ISIN (e.g. INE002A01018)
    series         TEXT,                      -- SctySrs ('A' | 'B' | 'E' | 'F' | 'M' | 'MT' | 'P' | ...)
    prev_close     NUMERIC(18,4),             -- PrvsClsgPric (NOT corporate-action-adjusted)
    open_price     NUMERIC(18,4),
    high_price     NUMERIC(18,4),
    low_price      NUMERIC(18,4),
    last_price     NUMERIC(18,4),
    close_price    NUMERIC(18,4),
    ttl_trd_qnty   BIGINT,                    -- TtlTradgVol (shares)
    turnover_rs    NUMERIC(22,2),             -- TtlTrfVal (rupees)
    no_of_trades   BIGINT,                    -- TtlNbOfTxsExctd
    fetched_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, scrip_code)
);

SELECT public.create_hypertable('bse_eod_bhavcopy', 'trade_date',
    chunk_time_interval => INTERVAL '1 month');

ALTER TABLE bse_eod_bhavcopy SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'series',
    timescaledb.compress_orderby = 'trade_date, scrip_code'
);
SELECT public.add_compression_policy('bse_eod_bhavcopy', INTERVAL '7 days');
-- NO retention policy (A2): >= 5y floor, default no-drop.

-- Resolve a recent ISIN -> BSE ticker (the corporate-action cross-map, Phase C).
CREATE INDEX idx_bse_bhavcopy_isin ON bse_eod_bhavcopy (isin, trade_date DESC);
