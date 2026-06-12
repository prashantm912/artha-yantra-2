-- Phase 15B (B-19): one row per continuous-futures roll. The CONT series in
-- the candles hypertable stores the UNADJUSTED stitch; back-adjustment is
-- read-time arithmetic over these gaps, so the adjustment policy can change
-- without rewriting data. price_gap = incoming close - outgoing close on the
-- roll date.

CREATE TABLE roll_events (
    underlying          TEXT          NOT NULL,
    roll_date           DATE          NOT NULL,
    from_tradingsymbol  TEXT          NOT NULL,
    to_tradingsymbol    TEXT          NOT NULL,
    price_gap           NUMERIC(18,4) NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (underlying, roll_date)
);
