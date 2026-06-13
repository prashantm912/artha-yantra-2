-- Phase 21 (D18 / C-2.12): the versioned strategy registry.
-- strategies = identity + lifecycle head; strategy_versions = IMMUTABLE
-- content rows (UPDATE only ever touches status/published_at - enforced in
-- code, guarded by the checksum); strategy_audit_log = append-only by
-- convention AND by grant (ay_strategy gets INSERT+SELECT, no UPDATE/DELETE).
-- notifications_* columns land NOW to avoid a later checksum-adjacent
-- migration (feature lands Stage E Phase 41).

CREATE TABLE strategies (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug                  TEXT NOT NULL UNIQUE,          -- the YAML id; never changes across versions
  name                  TEXT NOT NULL UNIQUE,
  description           TEXT,
  author                TEXT,
  tags                  TEXT[] NOT NULL DEFAULT '{}',
  enabled               BOOLEAN NOT NULL DEFAULT TRUE, -- master kill-switch for the signal engine
  notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  notification_channel  TEXT CHECK (notification_channel IN ('NTFY', 'TELEGRAM')),
  published_version_id  UUID,                          -- FK added below (table order)
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_strategies_tags ON strategies USING gin (tags);

CREATE TABLE strategy_versions (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  strategy_id    UUID NOT NULL REFERENCES strategies(id) ON DELETE CASCADE,
  version        TEXT NOT NULL,                        -- registry-owned semver
  config_yaml    TEXT NOT NULL,                        -- author's original, byte-preserved for diff
  config         JSONB NOT NULL,                       -- canonicalized (D18)
  schema_version TEXT NOT NULL,
  checksum       TEXT NOT NULL,                        -- SHA-256 of canonical JSONB
  status         TEXT NOT NULL CHECK (status IN ('draft', 'published', 'archived')),
  notes          TEXT,
  created_by     TEXT NOT NULL DEFAULT 'owner',        -- optimizer writes optimizer:{jobId}
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ,
  UNIQUE (strategy_id, version)
);

CREATE INDEX idx_strategy_versions_history ON strategy_versions (strategy_id, created_at DESC);

ALTER TABLE strategies
  ADD CONSTRAINT fk_strategies_published_version
  FOREIGN KEY (published_version_id) REFERENCES strategy_versions(id);

CREATE TABLE strategy_audit_log (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  strategy_id  UUID NOT NULL REFERENCES strategies(id) ON DELETE CASCADE,
  action       TEXT NOT NULL CHECK (action IN ('CREATE', 'UPDATE_DRAFT', 'PUBLISH', 'ROLLBACK', 'ARCHIVE')),
  from_version TEXT,
  to_version   TEXT,
  diff_summary TEXT,
  actor        TEXT NOT NULL DEFAULT 'owner',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_strategy_audit_timeline ON strategy_audit_log (strategy_id, created_at DESC);

-- single-writer grants (D10): full DML on registry tables for the service
-- role; the audit log is APPEND-ONLY BY GRANT - INSERT+SELECT, nothing else.
GRANT SELECT, INSERT, UPDATE, DELETE ON strategies, strategy_versions TO ay_strategy;
GRANT SELECT, INSERT ON strategy_audit_log TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
