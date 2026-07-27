-- 2026-07-26 swing catch-up criticals: durable paper-effect claims and refusal evidence.
--
-- The catch-up claim ledger serializes a session, but it cannot prove that the synchronous
-- SignalTaken/SignalExited listener actually changed the paper ledger. These rows are claimed before
-- the paper effect and remain repairable when a listener fails after the signal/anchor transition.
CREATE TABLE swing_paper_effects (
    id                BIGSERIAL    PRIMARY KEY,
    batch             TEXT        NOT NULL,
    session_date      DATE        NOT NULL,
    effect_key        TEXT        NOT NULL,
    effect_type       TEXT        NOT NULL CHECK (effect_type IN ('ENTRY', 'EXIT')),
    tradingsymbol     TEXT,
    signal_id         BIGINT,
    anchor_signal_id  BIGINT,
    exit_signal_id    BIGINT,
    close_reason      TEXT,
    close_price       NUMERIC,
    expected_qty      BIGINT       NOT NULL DEFAULT 0,
    quantity_before   BIGINT       NOT NULL DEFAULT 0,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    status            TEXT        NOT NULL CHECK (status IN ('EXPECTED', 'CLAIMED', 'CONFIRMED')),
    claimed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (batch, session_date, effect_key)
);

CREATE INDEX ix_swing_paper_effects_pending
    ON swing_paper_effects (batch, session_date, status);

COMMENT ON TABLE swing_paper_effects IS
  'Idempotent swing paper-effect outbox. A CLAIMED row is repairable; only CONFIRMED effects permit a catch-up session to reach DONE.';

GRANT SELECT, INSERT, UPDATE ON swing_paper_effects TO ay_strategy;
GRANT USAGE, SELECT ON SEQUENCE swing_paper_effects_id_seq TO ay_strategy;

-- A refused as-of evaluation is not a missing candle. Keep one durable, symbol-level fact per session
-- so retries cannot erase the reason and the alert can name the exact money path that was refused.
CREATE TABLE swing_batch_refusals (
    batch         TEXT        NOT NULL,
    session_date  DATE        NOT NULL,
    tradingsymbol TEXT        NOT NULL,
    reason        TEXT        NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch, session_date, tradingsymbol, reason)
);

COMMENT ON TABLE swing_batch_refusals IS
  'Durable structured refusals from a pinned swing replay, including MIXED_PRE_POST_LOTS:<symbol>.';

GRANT SELECT, INSERT ON swing_batch_refusals TO ay_strategy;
