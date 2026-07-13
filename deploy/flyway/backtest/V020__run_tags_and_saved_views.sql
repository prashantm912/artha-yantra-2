-- D4 P2-2 / research-fidelity A2+F5: keep run annotations queryable outside immutable
-- submission provenance, and persist the owner's named backtest filter sets for later reuse.
ALTER TABLE jobs ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE jobs ADD COLUMN note TEXT;

CREATE INDEX idx_jobs_tags ON jobs USING gin (tags);

CREATE TABLE saved_views (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner      TEXT NOT NULL,
  kind       TEXT NOT NULL,
  name       TEXT NOT NULL,
  filter     JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (owner, kind, name)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON saved_views TO ay_backtest;
