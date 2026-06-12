-- Phase 12 (B-2 / D13): the single-row AES-GCM-encrypted Kite access token.
-- The token NEVER leaves market-data-service: no other schema role has any
-- grant here, the plaintext exists only in process memory, and the row
-- survives container restarts within the trading day (decrypt-and-resume,
-- no re-login). 96-bit nonce stored beside the ciphertext; key material
-- (ARTHA_MASTER_KEY) is a Docker secret file, never in the database.

CREATE TABLE kite_session (
    id                SMALLINT    PRIMARY KEY DEFAULT 1,
    token_ciphertext  BYTEA       NOT NULL,
    nonce             BYTEA       NOT NULL,
    kite_user_id      TEXT,
    encrypted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_validated_at TIMESTAMPTZ,
    CONSTRAINT kite_session_singleton CHECK (id = 1)
);
