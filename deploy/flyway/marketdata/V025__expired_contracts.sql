-- Data-foundation / Upstox expired-instruments: a registry of EXPIRED F&O contracts backfilled for
-- backtesting. The live `instruments` master is Kite-dump synced + active-only (is_active), so an
-- expired NIFTY/SENSEX weekly is absent from it. This table resolves an expired tradingsymbol back to
-- its strike/expiry/lot — what the backtest engine (and the OI pages) need to consume the backfilled
-- candles. tradingsymbol is the canonical OpenAlgo grammar (BASE+DDMMMYY+STRIKE+CE/PE, FUT for
-- futures) — unambiguous across weekly/monthly, the SAME key written into the candles hypertable.
CREATE TABLE expired_contracts (
    exchange          TEXT          NOT NULL,                       -- NFO / BFO
    tradingsymbol     TEXT          NOT NULL,                       -- canonical, matches candles.tradingsymbol
    instrument_type   TEXT          NOT NULL CHECK (instrument_type IN ('CE', 'PE', 'FUT')),
    underlying_symbol TEXT          NOT NULL,                       -- NIFTY / SENSEX
    expiry            DATE          NOT NULL,
    strike            NUMERIC(18,4),                                -- NULL for FUT
    lot_size          INTEGER       NOT NULL,
    tick_size         NUMERIC(18,4),
    weekly            BOOLEAN       NOT NULL DEFAULT FALSE,
    upstox_key        TEXT          NOT NULL,                       -- expired_instrument_key (the candle-fetch key)
    underlying_key    TEXT          NOT NULL,                       -- e.g. NSE_INDEX|Nifty 50
    first_seen        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, tradingsymbol)
);

-- Backtest/OI resolution is by (underlying, expiry, type) — chain enumeration per past expiry.
CREATE INDEX ix_expired_contracts_underlying_expiry
    ON expired_contracts (underlying_symbol, expiry, instrument_type);
