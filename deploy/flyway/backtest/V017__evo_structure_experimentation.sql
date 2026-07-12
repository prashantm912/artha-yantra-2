-- Strategy-evolution engine — E5 structure experimentation (design §5 / §5.2 / §8.3, roadmap §12
-- items 14-15). Two `evo_*` tables that carry the STRUCTURE-mutation stream — kept SEPARATE from
-- the knob-sweep stream (design §5: "structure changes get their own stream with stricter rules").
-- Written by optimizer-service (its owned `backtest` schema), the same writer + conventions as the
-- V011 evo experiment model.
--
--   1. evo_ablations — the PRE-REGISTERED ablation protocol ledger (§5.2). One row per structure
--      mutation hypothesis: its parent candidate, the mutation itself, its canonical `fingerprint`
--      (the graveyard / re-proposal key), an IMMUTABLE `pre_registered_at` (the §3.1 anti-snooping
--      ledger — the hypothesis provably predates its measured evidence), and — once the paired
--      evaluation runs — the verdict + the paired-lift evaluation JSONB. A candidate whose lift
--      exists ONLY in-sample is auto-rejected with verdict REJECTED_IS_ONLY (§5.2 institutional
--      memory against snooping).
--   2. evo_graveyard — the persistent GRAVEYARD (§8.3 "rejection ≠ silence"): the dedup index of
--      rejected structures so a buried structure is NEVER re-proposed silently. The
--      UNIQUE(campaign_id, fingerprint) is the load-bearing "never re-proposed" guarantee — the
--      gate-candidate suggesters (§5.1.2) query it by fingerprint and SUPPRESS any suggestion that
--      is already buried. A structure buried once stays buried campaign-wide (any parent).
--
-- Type discipline mirrors V011 (D10 soft-ref convention): campaign_id / parent_candidate_id are
-- FKs WITHIN the evo_* family (the within-family FK discipline V011 uses for
-- generation->campaign / candidate->generation); parent_candidate_id is NULLable (a campaign-level
-- structural hypothesis need not name a specific parent). No cross-schema FK. The REVIEW_GATE
-- proposals the protocol + suggesters emit ride the EXISTING evo_proposals table (kind REVIEW_GATE
-- is already in its V011 CHECK, candidate_id already NULLable for campaign-level suggestions) — no
-- schema change there.
--
-- Grants mirror the lineage (V011:104-107): table-level DML to the read-only-by-convention
-- per-schema role `ay_backtest`. Both tables are append-first — evo_ablations is UPDATE-once
-- (PRE_REGISTERED -> EVALUATED stamps the verdict), evo_graveyard is insert-only (a grave is never
-- exhumed here); neither is granted DELETE (mirrors evo_proposals' append-only grant).

CREATE TABLE evo_ablations (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id         UUID NOT NULL REFERENCES evo_campaigns(id),
  parent_candidate_id UUID REFERENCES evo_candidates(id),  -- the base the gate is added to / removed from
  mutation            JSONB NOT NULL,                      -- {op, gateType, operand/regime/indicator, source}
  fingerprint         TEXT NOT NULL,                       -- canonical sha256 of the mutation (graveyard key)
  status              TEXT NOT NULL DEFAULT 'PRE_REGISTERED'
                        CHECK (status IN ('PRE_REGISTERED', 'EVALUATED')),
  verdict             TEXT                                 -- NULL until evaluated; then one of:
                        CHECK (verdict IN ('ACCEPTED', 'REJECTED_IS_ONLY', 'REJECTED_NO_LIFT',
                                           'REJECTED_TRADE_STARVATION', 'REJECTED_REGIME',
                                           'REJECTED_DOF')),
  evaluation          JSONB,                               -- §5.2 paired-lift result (perFoldLift, pairedP,
                                                           --   isLift/oosLift, regimeLift, tradeRetention, gates)
  created_by          TEXT,                                -- audit actor (T3)
  pre_registered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),  -- IMMUTABLE: the §3.1 "registered before
                                                           --   evaluated" anti-snooping proof
  evaluated_at        TIMESTAMPTZ                          -- app-stamped on the PRE_REGISTERED -> EVALUATED write
);

CREATE TABLE evo_graveyard (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id  UUID NOT NULL REFERENCES evo_campaigns(id),
  ablation_id  UUID REFERENCES evo_ablations(id),  -- the rejection that buried it (NULL for a direct bury)
  fingerprint  TEXT NOT NULL,                      -- the mutation fingerprint (the re-proposal detection key)
  mutation     JSONB NOT NULL,
  verdict      TEXT NOT NULL,                      -- why it was buried (the REJECTED_* code)
  evidence     JSONB,                              -- the paired-lift snapshot at burial (auditability)
  buried_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Pre-registration idempotency: one live PRE_REGISTERED ablation per (campaign, parent, fingerprint).
-- NULL parent_candidate_id rows are distinct under Postgres NULL semantics (a campaign-level
-- hypothesis) — the graveyard's own UNIQUE is the campaign-wide dedup that matters.
CREATE UNIQUE INDEX uq_evo_ablations_campaign_parent_fp
  ON evo_ablations (campaign_id, parent_candidate_id, fingerprint);
CREATE INDEX idx_evo_ablations_campaign ON evo_ablations (campaign_id);

-- The load-bearing "never re-proposed silently" guarantee (§8.3): one grave per structure per
-- campaign. The suggesters' graveyard check + the ablation's bury both key on it.
CREATE UNIQUE INDEX uq_evo_graveyard_campaign_fp ON evo_graveyard (campaign_id, fingerprint);

GRANT SELECT, INSERT, UPDATE ON evo_ablations TO ay_backtest;  -- UPDATE-once (verdict stamp); no DELETE
GRANT SELECT, INSERT ON evo_graveyard TO ay_backtest;          -- insert-only; a grave is never exhumed
