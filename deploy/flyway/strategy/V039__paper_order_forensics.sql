-- Research-fidelity audit 2026-07-10 §10 P1-4 (order-event model) + P1-5 (quote/tick capture at fill).
--
-- P1-5 — fill-reference provenance. `paper_orders` already carries the fill-audit trail
-- (fill_price / fill_simulator / slippage_applied) and the always-null quote_bid/quote_ask columns
-- (there is no bid/ask depth in the tick feed — only an LTP), but it NEVER recorded WHICH price the
-- fill was struck against or how fresh it was, so a fill was not forensically reconstructible (audit
-- L4). Two additive columns close that:
--   ref_source     how the fill reference was obtained:
--                    CALLER       an explicit price the caller supplied (a manual ticket's price, or a
--                                 scalper auto-take's gate-captured option premium / a swing daily close)
--                    LIVE_TICK    the Redis last-tick LTP (the null-price fill fallback)
--                    SIGNAL_ENTRY the signal's own entry price (last-resort when no tick has ticked)
--   ref_tick_age_ms  the LIVE_TICK's wall-clock age in ms at fill time (NULL for CALLER/SIGNAL_ENTRY, or
--                    when the tick carried no timestamp) — lets analysis reconstruct the tick-freshness
--                    that produced each fill WITHOUT re-deriving it. This RECORDS the #694 tick-freshness
--                    doctrine; it never changes it (entries still reject a >15s stale tick DATA_STALE;
--                    settles still use the last real tick at any age).
ALTER TABLE paper_orders ADD COLUMN ref_source     TEXT;
ALTER TABLE paper_orders ADD COLUMN ref_tick_age_ms BIGINT;

-- P1-4 — order-lifecycle REJECT capture. The SUCCESSFUL order lifecycle is already durable: a fill
-- opens/averages a position (paper_orders FILLED row + paper_events OPENED, V005/V038), and closes /
-- bracket-hits / expiry-settles land as paper_events CLOSED/BRACKET_HIT/SETTLED. The ONE transition with
-- no durable trace was a REFUSED entry: openOrder throws DATA_STALE (stale last tick) or "no price"
-- BEFORE any row is written, and the auto-take path (PaperSignalListener) swallows that throw as a lone
-- log line (a manual ticket surfaces a 422 but persists nothing; a governor veto is already in
-- risk_audit). This append-only ledger records each such refused order attempt so "why didn't this take
-- fill" is answerable from stored data and Prompt-2 fill-realism calibration has the reject counterfactual.
-- Written REQUIRES_NEW + fail-soft (PaperOrderRejectionRecorder) so the row survives the reject rollback
-- and a ledger hiccup never changes the rejection the caller sees.
CREATE TABLE paper_order_rejections (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signal_id     BIGINT,                                  -- the take's signal (NULL for a manual ticket)
    book          TEXT        NOT NULL,                    -- the paper book the order would have charged
    exchange      TEXT        NOT NULL,
    tradingsymbol TEXT        NOT NULL,
    side          TEXT        NOT NULL,                    -- BUY | SELL
    qty           BIGINT      NOT NULL,
    reason        TEXT        NOT NULL,                    -- DATA_STALE_TICK | NO_PRICE
    detail        TEXT,                                    -- human context (e.g. "tick 27s old > 15s")
    tick_age_ms   BIGINT,                                  -- the stale tick's age in ms (DATA_STALE_TICK only)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()       -- timestamptz — in-container now() is UTC, never ::date
);

-- The read patterns: newest-first per book/day (a rejects feed / day filter). Never a CURRENT_DATE-baked
-- predicate (in-container now() is UTC; a day filter bounds created_at by explicit +05:30 ISO instants).
CREATE INDEX ix_paper_order_rejections_book_created ON paper_order_rejections (book, created_at DESC);

-- Append-only for the read-only per-schema role (mirrors V038): SELECT + INSERT only, never UPDATE/DELETE.
-- The service itself connects as `artha` (D10 single-writer).
GRANT SELECT, INSERT ON paper_order_rejections TO ay_strategy;
