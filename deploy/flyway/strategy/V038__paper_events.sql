-- App-platform audit 2026-07-10 §7.2.2: the paper-position lifecycle had a durable row (paper_positions)
-- and in-process Spring events (PaperPositionOpened/Closed) but NO serialised event stream — so nothing
-- downstream (position-detail / trade-chain builders, the INT-I3 intelligence layer) could react to an
-- open/close, and MTM was tick-derived client-side only. This append-only ledger records ONE row per
-- lifecycle event; PaperEventPublisher writes it AFTER_COMMIT (off the trade transaction) and mirrors it
-- onto the paper.events Redis channel for the live FE append.
--
-- kind:
--   OPENED       a position opened or averaged into (PaperPositionOpened) — reason/realized_pnl null.
--   CLOSED       a manual / engine-exit / 15:45 mark-to-close settle (reason = close_reason).
--   BRACKET_HIT  a stop-loss / take-profit bracket breach (reason = STOP_LOSS | TAKE_PROFIT).
--   SETTLED      an expiry settlement (reason = EXPIRY_SETTLEMENT).
-- The kind is DERIVED from the close reason at write time (PaperEventPublisher.kindFor) so a reader
-- never has to re-classify; the raw close_reason still rides `reason` for full fidelity.
--
-- No FK on position_id: this is an append-only history that must OUTLIVE a book reset (POST /paper/reset
-- hard-deletes paper_positions rows) — the event trail is the durable record of what happened, so it must
-- not cascade-vanish with the position it describes.
CREATE TABLE paper_events (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    position_id   BIGINT       NOT NULL,               -- the paper_positions row (no FK: survives a reset)
    kind          TEXT         NOT NULL,               -- OPENED | CLOSED | BRACKET_HIT | SETTLED
    book          TEXT         NOT NULL,               -- the paper book the position is charged to
    exchange      TEXT         NOT NULL,
    tradingsymbol TEXT         NOT NULL,
    side          TEXT         NOT NULL,               -- BUY | SELL
    qty           BIGINT       NOT NULL,
    reason        TEXT,                                -- close_reason for closes; null on OPENED
    realized_pnl  NUMERIC,                             -- signed realized cash for closes; null on OPENED
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()  -- timestamptz — in-container now() is UTC, never ::date
);

-- The two read patterns: newest-first per position (position-detail / trade-chain) and newest-first per
-- book/day (the live feed + day filter). Plain (col, created_at DESC) indexes — never a CURRENT_DATE-baked
-- predicate (in-container now() is UTC; the day filter bounds created_at by explicit +05:30 ISO instants).
CREATE INDEX ix_paper_events_position ON paper_events (position_id, created_at DESC);
CREATE INDEX ix_paper_events_book_created ON paper_events (book, created_at DESC);

-- Append-only by GRANT for the read-only per-schema role (mirrors V027/V029): SELECT + INSERT only,
-- never UPDATE/DELETE. The service itself connects as `artha` (D10 single-writer).
GRANT SELECT, INSERT ON paper_events TO ay_strategy;
