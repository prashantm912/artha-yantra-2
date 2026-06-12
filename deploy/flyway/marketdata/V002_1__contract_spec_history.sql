-- Phase 9A (B-20): lot/tick spec history accrued by diffing each day's sync
-- staging output against the current master (suffix-versioned so Flyway
-- order matches the 9A build position - B-8). Written ONLY from the sync
-- pipeline (single-writer D10).

CREATE TABLE contract_spec_history (
    exchange       TEXT        NOT NULL,
    tradingsymbol  TEXT        NOT NULL,
    as_of_date     DATE        NOT NULL,
    lot_size       INT,
    tick_size      NUMERIC,
    change_type    TEXT        NOT NULL CHECK (change_type IN ('FIRST_SEEN', 'CHANGED')),
    prev_lot_size  INT,
    prev_tick_size NUMERIC,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, tradingsymbol, as_of_date)
);
