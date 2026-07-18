-- PF-03: durable record of ENTRY signals CENSORED by the per-book paper RISK GOVERNOR.
-- When SignalEngine.emitEntry reaches the emission stage and the book's risk gate vetoes
-- (kill switch / MAX_OPEN concurrency cap / daily-loss trip / daily-profit target / max
-- deployment / F9 heat cap), the entry used to vanish leaving only a transient log line —
-- retroactive incidence was unknowable once logs rotated. One row here per vetoed entry.
--
-- This is a DISTINCT event class from signal_rejections: that table records WHY the scalper
-- §12.3 CONFLUENCE GATE blocked a chart-entry (gate returned no Decision) and its consumers
-- (DotHealthCanary liveness window, RejectionReader rail-trend, StrategyEvidenceReader) assume
-- every row carries the confluence diagnostic. A governor veto happens AFTER the gate APPROVED,
-- at the book-level emission gate, and applies to EVERY strategy type (scalper / swing / btst) —
-- so it gets its own table rather than polluting the confluence-rejection analytics.
--
-- OBSERVABILITY ONLY — recording this never changes the emission/veto decision (the gate is
-- consulted exactly as before). LIVE path only: the golden replay injects no EmissionGuard, so
-- the risk branch is never reached on backtest → no rows on replay → parity-safe. Plain OLTP
-- table (not a hypertable) — bounded volume (only fires when a book is at a governor limit).

CREATE TABLE risk_suppressions (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  strategy_version_id   UUID NOT NULL REFERENCES strategy_versions(id),
  strategy_slug         TEXT NOT NULL,
  book                  TEXT NOT NULL,        -- the risk book whose governor vetoed (scalper/swing/...)
  rail                  TEXT NOT NULL,        -- governor rail: kill_switch / max_open_paper_positions /
                                              -- daily_loss_limit / daily_profit_target / max_deployment_pct / heat_cap_pct
  exchange              TEXT NOT NULL,        -- the SIGNAL instrument (index future) — answers SENSEX-vs-NIFTY
  tradingsymbol         TEXT NOT NULL,
  "interval"            TEXT NOT NULL,
  side                  TEXT,                 -- would-have-been order side BUY/SELL (NULL only if unresolved)
  option_type           TEXT,                 -- would-have-been option leg CE/PE (scalper only; NULL for swing / neutral straddle)
  option_tradingsymbol  TEXT,                 -- would-have-been option leg symbol (scalper only)
  bar_time              TIMESTAMPTZ NOT NULL, -- the evaluated trigger bar's bucket instant (IST offset)
  generated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- feed (newest first) + per-strategy history + per-rail and per-book aggregation
CREATE INDEX idx_risk_suppressions_generated ON risk_suppressions (generated_at DESC);
CREATE INDEX idx_risk_suppressions_version ON risk_suppressions (strategy_version_id, generated_at DESC);
CREATE INDEX idx_risk_suppressions_rail ON risk_suppressions (rail, generated_at DESC);
CREATE INDEX idx_risk_suppressions_book ON risk_suppressions (book, generated_at DESC);

-- Append-only by GRANT for the read-only per-schema role (mirrors V015/V027/V038): SELECT + INSERT
-- only, no UPDATE/DELETE. GENERATED ALWAYS AS IDENTITY needs no explicit sequence grant.
GRANT SELECT, INSERT ON risk_suppressions TO ay_strategy;
