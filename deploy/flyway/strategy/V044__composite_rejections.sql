-- Durable record of an entry evaluation that ended COMPOSITE_BELOW_THRESHOLD: every chart gate
-- passed, but the A1 composite came in under entry_rules.scoring.threshold, so no signal was
-- emitted. EntryEvaluator ALREADY builds the full ScoreBreakdown on that path and returns it;
-- SignalEngine.decideEntry used to discard it, leaving the outcome visible only as a Micrometer
-- counter. All 38 surviving published scalpers share ONE composite (`rsi14` rsi_momentum w1 +
-- `supertrend` direction w1 at threshold 0.2 on one 3m NIFTY-future series), so this outcome is
-- effectively "RSI did not clear" — and without the per-evaluation row the RSI operand
-- distribution vs the ~58 it needs cannot be measured at all.
--
-- This is a DISTINCT event class from signal_rejections, and gets its own table for exactly the
-- reason V042 gave risk_suppressions one: that table records why the scalper §12.3 CONFLUENCE GATE
-- blocked a chart-entry, and ALL of its consumers assume every row carries the confluence
-- diagnostic (`checks[]` + `confluence{dots[]}` + `context{}`). A composite block happens at the
-- CHART stage, BEFORE the confluence gate is ever consulted, so such a row has a scoreBreakdown and
-- none of that. Sharing the table was built and rejected: it forced a filter into DotHealthCanary's
-- query (its 40-row window would be flooded by the ~38 rows a single SuperTrend-DOWN bar writes at
-- once, paging every required dot as DEAD), two more into RejectionReader, and a crash-guard into
-- RejectionsPage. An observability addition must never force a live health detector to change its
-- query to avoid false-paging.
--
-- OBSERVABILITY ONLY — recording this never changes what fires. The evaluation is complete and its
-- Outcome already decided before a row is written; the write is a bounded async enqueue off the
-- sole signal-eval thread. LIVE path only: the deterministic golden replay drives
-- TickwiseGoldenRunner, never SignalEngine, so no rows exist on backtest -> parity-safe. Plain OLTP
-- table (not a hypertable).
--
-- RETENTION: ~192 rows/session measured, each carrying a ~650-byte canonical ScoreBreakdown, so
-- ~141 KB/day. Pruned to 180 days by CompositeRejectionPruneJob (daily 02:30 IST, live-only) — the
-- same shape as the PF-03 risk_suppressions prune. Every row here is created by this feature, so
-- the prune can never touch pre-existing history.

CREATE TABLE composite_rejections (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  strategy_version_id  UUID NOT NULL REFERENCES strategy_versions(id),
  strategy_slug        TEXT NOT NULL,
  exchange             TEXT NOT NULL,        -- the SIGNAL instrument (index future)
  tradingsymbol        TEXT NOT NULL,
  "interval"           TEXT NOT NULL,        -- the primary timeframe evaluated (3m for scalpers)
  composite            NUMERIC(20,6) NOT NULL, -- the A1 composite this bar scored
  threshold            NUMERIC(20,6) NOT NULL, -- entry_rules.scoring.threshold it had to clear
  margin               NUMERIC(20,6) NOT NULL, -- composite - threshold (signed; negative = how far short)
  score_breakdown      JSONB NOT NULL,       -- the FROZEN A1 ScoreBreakdown: per-factor score,
                                             -- weight, contribution, rawValue + the gate tree
  bar_time             TIMESTAMPTZ NOT NULL, -- the evaluated bar's bucket instant (IST offset)
  generated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- feed (newest first) + per-strategy history; the prune rides the generated_at index
CREATE INDEX idx_composite_rejections_generated ON composite_rejections (generated_at DESC);
CREATE INDEX idx_composite_rejections_version ON composite_rejections (strategy_version_id, generated_at DESC);

-- Append-only by GRANT for the read-only per-schema role (mirrors V015/V038/V042): SELECT + INSERT
-- only, no UPDATE/DELETE. GENERATED ALWAYS AS IDENTITY needs no explicit sequence grant. The prune
-- runs as `artha` (D10 single-writer), which is why no DELETE grant is issued here.
GRANT SELECT, INSERT ON composite_rejections TO ay_strategy;
