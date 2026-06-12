-- Phase 15 (B-7/B-10): the 5-min options-chain snapshot archive - the
-- platform's only irreplaceable dataset. Raw quote capture is UNCONDITIONAL;
-- IV/Greeks columns are populated only while the Phase-14 S1 golden suite is
-- green (S1-SEQ), and every stored IV is exactly recomputable from its own
-- row: price_source + forward_price + risk_free_rate + raw quotes + spot are
-- persisted per row. No retention policy (>= 5y floor, amendment A2).

CREATE TABLE options_chain_snapshots (
    ts              TIMESTAMPTZ   NOT NULL,
    underlying      TEXT          NOT NULL,
    expiry          DATE          NOT NULL,
    strike          NUMERIC(12,2) NOT NULL,
    option_type     TEXT          NOT NULL CHECK (option_type IN ('CE','PE')),
    tradingsymbol   TEXT          NOT NULL,
    ltp             NUMERIC(18,4),
    bid             NUMERIC(18,4),
    ask             NUMERIC(18,4),
    volume          BIGINT,
    oi              BIGINT,
    spot_price      NUMERIC(18,4),
    iv              NUMERIC(12,6),
    delta           NUMERIC(12,6),
    gamma           NUMERIC(12,6),
    theta           NUMERIC(12,6),
    vega            NUMERIC(12,6),
    rho             NUMERIC(12,6),
    iv_reason       TEXT,
    price_source    TEXT,
    forward_price   NUMERIC(18,4),
    risk_free_rate  NUMERIC(8,6),
    PRIMARY KEY (ts, underlying, expiry, strike, option_type)
);

SELECT public.create_hypertable('options_chain_snapshots', 'ts',
    chunk_time_interval => INTERVAL '1 day');

ALTER TABLE options_chain_snapshots SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'underlying, expiry, option_type',
    timescaledb.compress_orderby = 'ts'
);
SELECT public.add_compression_policy('options_chain_snapshots', INTERVAL '7 days');
-- NO retention policy: amendment A2 pins a >= 5 year floor, default no-drop.
