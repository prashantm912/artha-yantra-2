-- Roadmap F6: Telegram command-bot audit trail. Every ACCEPTED command (from an allowlisted chat)
-- lands one row with its outcome — the mutating levers (/pause /resume /flatten) must be
-- reconstructable after the fact. Silently-ignored messages (unknown chat ids) are never logged
-- (logging them would let a stranger fill the table).
CREATE TABLE bot_commands (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_id     TEXT        NOT NULL,
    command     TEXT        NOT NULL,
    outcome     TEXT        NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bot_commands_issued ON bot_commands (issued_at DESC);

GRANT SELECT, INSERT ON bot_commands TO ay_strategy;
