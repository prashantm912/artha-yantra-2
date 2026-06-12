-- Phase 9 (B-7): instrument master. PK is the STABLE key (exchange,
-- tradingsymbol) — numeric Kite tokens are refreshed every sync and are
-- never identity (they are reused across days after contract expiry).
-- Vanished rows are tombstoned (is_active=false), never deleted.

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE TABLE instruments (
    exchange                 TEXT        NOT NULL,
    tradingsymbol            TEXT        NOT NULL,
    instrument_token         BIGINT,
    exchange_token           BIGINT,
    name                     TEXT,
    segment                  TEXT,
    instrument_type          TEXT,
    underlying_exchange      TEXT,
    underlying_tradingsymbol TEXT,
    expiry                   DATE,
    strike                   NUMERIC,
    tick_size                NUMERIC,
    lot_size                 INT,
    is_active                BOOLEAN     NOT NULL DEFAULT TRUE,
    first_seen_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, tradingsymbol)
);

-- B-7 indexing: tick token->symbol resolution (active rows only)
CREATE INDEX instruments_token_active_idx
    ON instruments (instrument_token) WHERE is_active;

-- chain strike/expiry enumeration for derivatives
CREATE INDEX instruments_underlying_idx
    ON instruments (underlying_exchange, underlying_tradingsymbol, expiry);

-- typeahead search (addresses chronic v1 search pain)
CREATE INDEX instruments_trgm_idx
    ON instruments USING GIN (tradingsymbol public.gin_trgm_ops, name public.gin_trgm_ops);

-- staging table for the JDBC-batched dump sync (B-3 steps 3-4): truncated
-- per sync; UNLOGGED — its contents are always reproducible from the dump
CREATE UNLOGGED TABLE instruments_staging (
    LIKE instruments INCLUDING DEFAULTS
);
