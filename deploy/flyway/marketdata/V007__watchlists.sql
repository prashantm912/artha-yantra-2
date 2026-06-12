-- Phase 17 (B-7): named watchlists. Items soft-reference the instrument
-- master by stable key (validated at the API layer - no cross-table FK so a
-- tombstoned instrument never breaks a watchlist read); deleting a watchlist
-- cascades its items.

CREATE TABLE watchlists (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE watchlist_items (
    watchlist_id  UUID NOT NULL REFERENCES watchlists (id) ON DELETE CASCADE,
    exchange      TEXT NOT NULL,
    tradingsymbol TEXT NOT NULL,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watchlist_id, exchange, tradingsymbol)
);
