-- Data-foundation hardening: coverage provenance on the expired-contract registry. The resume key is
-- registration, on the invariant "registered ⟹ candles fully written". That holds against crashes
-- (registration is AFTER the candle write, so a crashed contract stays unregistered → retried), but
-- NOT against a silent short fetch — a transient Upstox false-empty for a tradeable window leaves a
-- hole, the contract registers anyway, and the registration-based skip strands it forever. These
-- columns let a short contract be DETECTED (last_bucket short of expiry = missing the liquid tail) and
-- re-fetched. `complete` gates the skip; existing rows default to false so they are re-validated once.
ALTER TABLE expired_contracts
    ADD COLUMN first_bucket TIMESTAMPTZ,
    ADD COLUMN last_bucket  TIMESTAMPTZ,
    ADD COLUMN bar_count    INTEGER,
    ADD COLUMN complete     BOOLEAN NOT NULL DEFAULT FALSE;
