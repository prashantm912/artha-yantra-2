-- Strategy-evolution engine — E1 experiment model (design §2.2 / roadmap §12 E1 item 1).
-- The four `evo_*` tables that hold the evolution program's structure: campaigns (one
-- long-lived program per base strategy), generations (one propose→evaluate→select
-- iteration), candidates (one concrete version under evaluation), and proposals (the
-- owner-inbox actions). Written by optimizer-service (its owned `backtest` schema); this
-- migration is READ-ONLY-first — the typed `/api/v1/evolution/*` read endpoints land in
-- the same PR, the write/scoring/recorder path in later PRs. The FULL column set is
-- created here additive-first (D17, mirroring V003) so later writers churn no schema.
--
-- Type discipline (design §2.3, D10 soft-ref convention): version_id / strategy_id /
-- champion_version_id are UUID soft refs into the STRATEGY schema — no cross-schema FK.
-- sweep_job_id / holdout_run_id are UUID soft links into this schema's jobs /
-- backtest_runs — no FK (cross-table lifecycle), mirroring optimization_trials
-- .backtest_run_id (V004:13) and jobs.id being UUID (V002:9). The design sketch wrote
-- these two as `text`; the instruction to mirror the actual jobs PK type + the V004
-- precedent make them UUID. FKs are declared only WITHIN the evo_* family
-- (generation→campaign, candidate→generation, candidate→candidate lineage,
-- proposal→campaign/candidate). Nullable-lean on config/lifecycle columns whose writer
-- lands in a later PR (D17); NOT NULL only on identity, enums, and timestamps.
--
-- Grants mirror the lineage (V002:30 / V003:78 / V004:22): table-level DML to the
-- read-only-by-convention per-schema role `ay_backtest`. No sequence grant — every PK is
-- a uuid gen_random_uuid() default, so no sequence is created (as in V004).

CREATE TABLE evo_campaigns (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  strategy_id         UUID NOT NULL,                  -- base strategy (strategy schema; soft ref, no FK)
  family              TEXT NOT NULL,
  evidence_policy     TEXT NOT NULL
                        CHECK (evidence_policy IN ('SIM_FIRST', 'LIVE_FIRST', 'SIM_BLOCKED')),
  objective_spec      JSONB,                          -- §6 weights/gates overrides (defaults per family)
  search_space        JSONB,                          -- frozen YAML optimize block + evo caps
  budget              JSONB,                          -- {maxGenerations, maxTrialsPerGen, holdoutTouches, ...}
  status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'PAUSED', 'CLOSED')),
  champion_version_id UUID,                           -- current incumbent (strategy schema; soft ref, no FK)
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evo_generations (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id       UUID NOT NULL REFERENCES evo_campaigns(id),
  n                 INT NOT NULL,
  proposal          JSONB,                            -- pre-registered: sampler, seed, structure mutations
  search_space_hash TEXT,
  engine_sha        TEXT,
  data_epoch        JSONB,
  stress_touches    INT NOT NULL DEFAULT 0,
  status            TEXT,                             -- generation lifecycle (no closed enum in the design)
  started_at        TIMESTAMPTZ,
  finished_at       TIMESTAMPTZ
);

CREATE TABLE evo_candidates (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  generation_id       UUID NOT NULL REFERENCES evo_generations(id),
  version_id          UUID,                           -- strategy_versions row (soft ref, no FK, D10)
  parent_candidate_id UUID REFERENCES evo_candidates(id),  -- lineage (self-FK within the evo family)
  mutation_kind       TEXT CHECK (mutation_kind IN ('PARAMS', 'STRUCTURE', 'SEED')),
  params              JSONB,
  structure_diff      JSONB,
  sweep_job_id        UUID,                           -- jobs row (soft link, no FK; cross-table lifecycle)
  holdout_run_id      UUID,                           -- backtest_runs row (soft link, no FK)
  scorecard           JSONB,                          -- §6 output: gates, components, RobustScore, caveats
  state               TEXT NOT NULL DEFAULT 'PROPOSED'
                        CHECK (state IN ('PROPOSED', 'EVALUATING', 'SCORED', 'SURVIVOR', 'RETIRED',
                                         'PAPER', 'TAKE_ELIGIBLE', 'PROMOTED', 'ROLLED_BACK')),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evo_proposals (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id  UUID NOT NULL REFERENCES evo_campaigns(id),
  candidate_id UUID REFERENCES evo_candidates(id),    -- NULL for campaign-level REVIEW_GATE suggestions
  kind         TEXT NOT NULL
                 CHECK (kind IN ('PUBLISH_PAPER', 'PROMOTE', 'RETIRE', 'ROLLBACK', 'REVIEW_GATE')),
  evidence     JSONB,                                 -- the proposal card payload (§10)
  status       TEXT NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
  actor        TEXT,
  decided_at   TIMESTAMPTZ,
  expires_at   TIMESTAMPTZ,                           -- app sets default 7-day expiry (§8.2 policy)
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes on the FK columns (the campaign/generation/candidate drill-down reads).
-- The generation number is unique per campaign (mirrors uq_trials_sweep_number, V004:19);
-- this unique index also serves as the campaign_id FK index.
CREATE UNIQUE INDEX uq_evo_generations_campaign_n ON evo_generations (campaign_id, n);
CREATE INDEX idx_evo_candidates_generation ON evo_candidates (generation_id);
CREATE INDEX idx_evo_candidates_parent ON evo_candidates (parent_candidate_id);
CREATE INDEX idx_evo_candidates_version ON evo_candidates (version_id);
CREATE INDEX idx_evo_proposals_campaign ON evo_proposals (campaign_id);
CREATE INDEX idx_evo_proposals_candidate ON evo_proposals (candidate_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON evo_campaigns   TO ay_backtest;
GRANT SELECT, INSERT, UPDATE, DELETE ON evo_generations TO ay_backtest;
GRANT SELECT, INSERT, UPDATE, DELETE ON evo_candidates  TO ay_backtest;
GRANT SELECT, INSERT, UPDATE, DELETE ON evo_proposals   TO ay_backtest;
