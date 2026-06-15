-- Stage G / oipulse: day-range capture for futures (Kite quote.ohlc) — unblocks /buzz, /movers, /eod.
-- Forward-only: nullable, so every pre-V015 row stays valid. close = PREVIOUS day's close (Kite ohlc).
ALTER TABLE futures_oi_snapshots
    ADD COLUMN day_open   NUMERIC(18,4),
    ADD COLUMN day_high   NUMERIC(18,4),
    ADD COLUMN day_low    NUMERIC(18,4),
    ADD COLUMN prev_close NUMERIC(18,4);
