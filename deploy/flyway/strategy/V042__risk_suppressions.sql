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
-- at the book-level emission gate — so it gets its own table rather than polluting the
-- confluence-rejection analytics.
--
-- SCOPE: the tick/paper-engine ENTRY path only (SignalEngine.emitEntry) — scalper, non-scalper
-- intraday, and the btst carry. The daily SwingBatchEngine (session.style=swing) has its OWN
-- per-book veto and stays LOG-ONLY; extending this record to the swing batch is a separate
-- follow-up, NOT covered here.
--
-- OBSERVABILITY ONLY — recording this never changes the emission/veto decision (the gate is
-- consulted exactly as before, and the write is a bounded async enqueue off the eval thread).
-- LIVE path only: the golden replay injects no EmissionGuard, so the risk branch is never reached
-- on backtest → no rows on replay → parity-safe. Plain OLTP table (not a hypertable).
--
-- RETENTION: append-only, currently UNBOUNDED. Row rate is low — a row lands only when a book is
-- AT a governor limit AND a would-be entry fires; most sessions write zero. Worst case (a cap
-- pinned all day across ~21 scalpers on a 3m cadence) is ~2-3k rows/day, so this stays small for a
-- long time and is retained indefinitely for incidence history. A prune job (the A10
-- options-snapshot pattern) is chipped as a follow-up if the rate ever warrants it.

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
