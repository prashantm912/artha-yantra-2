-- N2 / #1285: symbol continuity across an exchange ticker change, recorded as DATA.
--
-- WHY. Both equity screens require 252 sessions counted over a trailing 420-day window
-- (AdjustedEquityDailySql). When NSE renames a listing the successor starts accruing from zero and
-- cannot re-enter either screen for ~a year — 61 symbols were invisible for that reason on
-- 2026-08-03, and for 57 of them predecessor + successor sessions ALREADY exceed 252. The history
-- exists; nothing joins to it. Rate ~59/yr over the 13.5 months of bhavcopy that exist.
--
-- ⚠️ THIS IS NOT IDENTITY. The canonical instrument key stays `(exchange, tradingsymbol)` exactly as
-- docs/symbol-normalization.md describes — nothing here is stored as a key, compared across sources,
-- or consulted by the live feed, the ticker, the order path or any cross-source mapper. It is a
-- VIEW-TIME join for readers that explicitly opt into deeper history. A reader that does not join it
-- must behave byte-identically to today; that is what keeps this change away from the identity model
-- (the owner's stated reason for measuring before building).
--
-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here
-- (mirrors V022 eod_corporate_actions / V048 dividends).

-- ---------------------------------------------------------------------------------------------
-- symbol_lineage — the map. One row per PREDECESSOR: a retired ticker resolves to at most one
-- successor, which is the functional dependency a lineage walk needs. Several predecessors MAY
-- point at one successor (a chain, or a merge), so the successor side is indexed, not unique.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE symbol_lineage (
    exchange            TEXT        NOT NULL,
    predecessor_symbol  TEXT        NOT NULL,
    successor_symbol    TEXT        NOT NULL,

    -- Detector output. NULLABLE on purpose: an owner-seeded POLICY row (see the WITHHELD seed at the
    -- bottom) legitimately exists before any detector run has attached evidence to it. The detector
    -- fills these on its next pass.
    switch_date         DATE,
    gap_sessions        INTEGER,
    boundary_price      NUMERIC(18,4),
    confidence          TEXT,
    evidence            TEXT,

    -- Owner control. The detector NEVER writes these after the row exists (its upsert omits them
    -- from the DO UPDATE SET list), so a withheld pair stays withheld across every re-detection.
    status              TEXT        NOT NULL DEFAULT 'ACTIVE',
    status_reason       TEXT,

    source              TEXT        NOT NULL,
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    refreshed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (exchange, predecessor_symbol),
    CONSTRAINT symbol_lineage_not_self CHECK (predecessor_symbol <> successor_symbol),
    CONSTRAINT symbol_lineage_confidence_known
        CHECK (confidence IS NULL OR confidence IN ('confirmed', 'inferred')),
    CONSTRAINT symbol_lineage_status_known
        CHECK (status IN ('ACTIVE', 'WITHHELD')),
    CONSTRAINT symbol_lineage_gap_positive
        CHECK (gap_sessions IS NULL OR gap_sessions >= 1)
);

-- The lineage-expanded read walks predecessor -> successor, so the successor side needs its own
-- index for the reverse question ("what fed into this symbol?") the API answers.
CREATE INDEX idx_symbol_lineage_successor ON symbol_lineage (exchange, successor_symbol);

COMMENT ON TABLE symbol_lineage IS
    'Ticker-change continuity as DATA, never identity. Canonical key remains (exchange, tradingsymbol).';
COMMENT ON COLUMN symbol_lineage.confidence IS
    'confirmed = one BSE scrip_code carried both tickers (independent of the NSE price rule); inferred = NSE price continuity alone.';
COMMENT ON COLUMN symbol_lineage.status IS
    'ACTIVE = lineage-expanded readers may stitch this pair. WITHHELD = owner judgement that the pre-switch series is a different asset (demerger/amalgamation). Detector never overwrites this.';

-- ---------------------------------------------------------------------------------------------
-- symbol_rename_events — the PRIMARY-SOURCE signal, previously thrown away.
--
-- BhavcopyBackfillService.runNseRatios() fetches the NSE corporate-actions feed with no purpose
-- filter, so "Change in Name" rows arrive WITH their ISIN attached and were then dropped on the
-- `continue` that skips non-price-adjusting actions — not logged, not counted, not stored. This is
-- the highest-quality rename signal available and we were already paying to fetch it. Capturing it
-- is what keeps symbol_lineage current instead of going stale at ~59 renames/year.
--
-- The feed names the COMPANY, not the predecessor TICKER, so an event alone does not make a pair —
-- it corroborates one the price rule found, and it is the audit trail for renames the price rule is
-- structurally blind to (a switch across a suspension longer than the gap cap).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE symbol_rename_events (
    exchange     TEXT        NOT NULL,
    symbol       TEXT        NOT NULL,
    ex_date      DATE        NOT NULL,
    isin         TEXT,
    from_name    TEXT,
    to_name      TEXT,
    subject      TEXT        NOT NULL,
    source       TEXT        NOT NULL,
    detected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, symbol, ex_date)
);

CREATE INDEX idx_symbol_rename_events_isin ON symbol_rename_events (isin, ex_date DESC);

COMMENT ON TABLE symbol_rename_events IS
    'Raw "Change in Name" corporate actions, kept as evidence. Not a lineage pair on its own — the feed carries company names, not the predecessor ticker.';

-- ---------------------------------------------------------------------------------------------
-- The one policy row. TATAMOTORS -> TMPV (2025-10-24) is the Tata Motors DEMERGER, not a rename:
-- NSE carried prev_close across it and BSE carried the scrip_code, so it is indistinguishable from
-- a rename by every signal this platform can observe — the detector WILL find it. Stitching it
-- would compute SMA200 / 52-week extremes / a 12-month RS leg over a business that included the
-- commercial-vehicle arm the listing no longer owns. That is a judgement, so it is recorded as a
-- judgement: a data row with a reason, not a branch in code.
--
-- Derived columns are left NULL deliberately — the detector attaches its own evidence on the next
-- pass and leaves `status` alone, which is the mechanism that makes this decision durable.
INSERT INTO symbol_lineage
    (exchange, predecessor_symbol, successor_symbol, status, status_reason, source)
VALUES
    ('NSE', 'TATAMOTORS', 'TMPV', 'WITHHELD',
     'Demerger 2025-10-24, not a rename: the pre-switch series is a different asset mix (CV arm spun out). Price/scrip continuity cannot distinguish the two, so this is an owner judgement.',
     'owner-policy');
