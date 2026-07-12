-- APP Phase 2 / audit §7.2.4 + §10 (2026-07-12): durable swing sell-decision history. Until now the
-- daily HOLD/SELL triad (SwingSellDecisionService) was RECOMPUTED on every page read with nothing
-- persisted, so there was no history of what the page advised and no way to acknowledge a verdict
-- (audit §2.3/§4.6 "zero actionability"). The 20:05 IST swing batch now writes one row here per open
-- holding it evaluates (fail-soft, via SwingBatchRecorder), giving an append-per-day snapshot the owner
-- can acknowledge and the INT SELL_DECISION generator (intelligence-layer §13 row 8) can consume.
--
-- Shape mirrors §7.2.4 (family/book, date, symbol, verdict, reason, entry/stop/trail snapshot) plus the
-- deep-link key (signal_id → the held ENTRY anchor, == paper_positions.opening_signal_id) and the
-- acknowledge column. One row per (run_date, signal_id): a same-day catch-up re-run re-stamps the
-- computed verdict and CLEARS the ack only when the verdict itself changed (a fresh day is a new key).
CREATE TABLE sell_decisions (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    book            TEXT        NOT NULL,   -- family book: 'minervini' | 'manas-arora'
    run_date        DATE        NOT NULL,   -- IST calendar date the batch evaluated for
    evaluated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    signal_id       BIGINT      NOT NULL,   -- the held ENTRY anchor (deep-link: paper_positions.opening_signal_id)
    exchange        TEXT        NOT NULL,
    symbol          TEXT        NOT NULL,
    setup           TEXT,                   -- the emitting strategy slug
    stage           INTEGER,                -- Minervini stage 1-4 (null for Manas)
    setup_type      TEXT,                   -- Manas setup ('vcp' | 'breakout') (null for Minervini)
    footprint       TEXT,
    entry_price     NUMERIC,
    current_price   NUMERIC,
    unrealized_pct  NUMERIC,                -- already ×100 (e.g. 12.34 = +12.34%)
    stop_level      NUMERIC,
    trail_level     NUMERIC,                -- null until the family trail arms
    still_buyable   BOOLEAN     NOT NULL,   -- the entry gate re-run on today's bar
    selling_now     BOOLEAN     NOT NULL,   -- the frozen exit doctrine fires today
    sell_reason     TEXT,
    verdict         TEXT        NOT NULL,   -- 'HOLD' | 'SELL (<reason>)'
    acknowledged_at TIMESTAMPTZ,            -- null = not yet acknowledged by the owner
    UNIQUE (run_date, signal_id)
);

CREATE INDEX idx_sell_decisions_book_date ON sell_decisions (book, run_date DESC, id DESC);

COMMENT ON TABLE sell_decisions IS
  'One durable swing sell-decision row per open holding per IST run date (written fail-soft by the 20:05 swing batch). The recompute-on-read /sell-decisions triad stays the live view; this is the acknowledgeable history + the INT SELL_DECISION substrate.';

-- Match the lineage's least-privilege convention (SELECT/INSERT/UPDATE — UPDATE for the ack + same-day
-- re-run upsert; the IDENTITY sequence rides the column, no separate sequence grant, cf. V027).
GRANT SELECT, INSERT, UPDATE ON sell_decisions TO ay_strategy;
