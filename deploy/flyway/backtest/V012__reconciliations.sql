-- Strategy-evolution engine — E3 backtest-vs-live reconciliation (design §7 / roadmap §12 E3 item 7).
-- One row per (strategy version, matched live window): the gap between a version's LIVE evidence and
-- a re-simulation of the EXACT version over the EXACT lived window (purpose: reconcile). Written by
-- optimizer-service (its owned `backtest` schema, the evo writer §2.2); read by
-- GET /api/v1/evolution/reconciliations?versionId=. Nothing here feeds scoring — the §7.2 live-gap
-- GATE inside RobustScore is a SEPARATE later PR (§12 item 10). This migration only persists the
-- computed gap vector + gapZ + verdict + (when DIVERGENT) the diagnosis checklist.
--
-- Type discipline (design §2.3, D10 soft-ref convention, mirroring V011): version_id / strategy_id
-- are UUID soft refs into the STRATEGY schema (no cross-schema FK); sim_job_id / sim_run_id are UUID
-- soft links into this schema's jobs / backtest_runs (no FK — cross-table lifecycle, as
-- evo_candidates.sweep_job_id / holdout_run_id, V011:68-69). window_from / window_to are TIMESTAMPTZ:
-- the live window is expressed as explicit IST (+05:30) bounds (never a UTC ::date, which is off-by-one
-- across IST midnight for a 00:00-02:xx-IST row), matching how backtest windows parse (JobsService
-- toDateTime → +05:30) and how paper entry timestamps are stored.
--
-- gap_z is NULLABLE: the divergence z-score is returnGap / σ(the version's walk-forward fold returns),
-- and a version with NO fold history in its sweep/run lineage has no σ → gap_z NULL + verdict
-- INSUFFICIENT (design §7.2, "when no fold history exists"). diagnosis is NULLABLE and populated ONLY
-- for a DIVERGENT verdict (the §7.2.1-5 checklist payload) — the other verdicts carry none.
--
-- Grants mirror the lineage (V003:78 / V011:104-107): table-level DML to the read-only-by-convention
-- per-schema role `ay_backtest`. No sequence grant — the PK is a gen_random_uuid() default.

CREATE TABLE reconciliations (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  version_id         UUID NOT NULL,                  -- strategy_versions row (strategy schema; soft ref, no FK)
  strategy_id        UUID,                           -- owning base strategy (soft ref, no FK)
  window_from        TIMESTAMPTZ NOT NULL,           -- the lived window start (explicit IST bound)
  window_to          TIMESTAMPTZ NOT NULL,           -- the lived window end (explicit IST bound)
  sim_job_id         UUID,                           -- the reconcile re-sim's jobs row (soft link, no FK)
  sim_run_id         UUID,                           -- its backtest_runs row (soft link, no FK)
  gap                JSONB NOT NULL,                 -- the §7.1.3 gap vector (returnGap/tradeSetOverlap/…)
  gap_z              NUMERIC(18,6),                  -- √t-normalized returnGap / σ(fold returns); NULL when no fold history
  paired_trades      INT NOT NULL DEFAULT 0,         -- UNIQUE paired live↔sim keys (first fill per (symbol, IST entry
                                                     -- date), not per-fill multiplicity) — the §7.2 evidence-floor input
  evidence_floor_met BOOLEAN NOT NULL DEFAULT FALSE, -- ≥ 20 paired trades OR ≥ 4 weeks lived (§7.2)
  verdict            TEXT NOT NULL
                       CHECK (verdict IN ('ALIGNED', 'PENALIZED', 'DIVERGENT', 'INSUFFICIENT')),
  diagnosis          JSONB,                          -- §7.2.1-5 checklist; NULL unless verdict = DIVERGENT
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One reconciliation per (version, window): a re-POST for an already-computed window 409s (the
-- computer checks this first; the constraint is the hard backstop against a concurrent double-submit).
-- This unique index also serves the versionId FK-style lookup (GET ?versionId=), so no separate index
-- on version_id is needed (mirrors V011:96-97's uq index doubling as the campaign_id index).
CREATE UNIQUE INDEX uq_reconciliations_version_window
  ON reconciliations (version_id, window_from, window_to);

GRANT SELECT, INSERT, UPDATE, DELETE ON reconciliations TO ay_backtest;
