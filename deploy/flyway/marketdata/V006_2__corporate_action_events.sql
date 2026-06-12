-- Phase 16A (B-17 / amendment A8): the audit row for the ONE sanctioned
-- exception to the closed-bars-immutable rule. Kite-diff is the sole
-- detection input; per-anchor evidence is stored as JSONB so every detection
-- is fully reviewable.

CREATE TABLE corporate_action_events (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange            TEXT          NOT NULL,
    tradingsymbol       TEXT          NOT NULL,
    detected_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    effective_boundary  DATE,
    ratio               NUMERIC(18,8) NOT NULL,
    anchors_checked     INT           NOT NULL,
    anchors_diverged    INT           NOT NULL,
    details             JSONB         NOT NULL,
    status              TEXT          NOT NULL DEFAULT 'DETECTED'
        CHECK (status IN ('DETECTED','REBACKFILL_RUNNING','RESOLVED','FAILED')),
    resolved_at         TIMESTAMPTZ
);

CREATE INDEX idx_cae_symbol ON corporate_action_events (exchange, tradingsymbol, detected_at DESC);
