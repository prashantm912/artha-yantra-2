-- A.8 / CD-1: the admin lineage runs FIRST and owns roles, schemas and
-- grants. The three per-schema lineages own only their own tables.
-- Applied migrations are immutable (checksum-enforced); changes are
-- additive-first (D17).

-- ---- service roles (idempotent; NOLOGIN until services get DB access in
-- ---- Stage B — credentials never live in migrations) ----
DO $$ BEGIN CREATE ROLE ay_marketdata NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE ay_strategy   NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE ay_backtest   NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ---- one schema per service, owned by its role (single-writer rule, D10) ----
CREATE SCHEMA IF NOT EXISTS marketdata AUTHORIZATION ay_marketdata;
CREATE SCHEMA IF NOT EXISTS strategy   AUTHORIZATION ay_strategy;
CREATE SCHEMA IF NOT EXISTS backtest   AUTHORIZATION ay_backtest;

-- ---- THE single cross-schema grant: backtest reads marketdata (CD-1).
-- ---- ay_strategy and ay_marketdata receive NO cross-schema grants. ----
GRANT USAGE ON SCHEMA marketdata TO ay_backtest;
GRANT SELECT ON ALL TABLES IN SCHEMA marketdata TO ay_backtest;

-- future tables keep the grant regardless of which user runs the marketdata
-- lineage: today migrations connect as the admin user (artha), later they may
-- run as the owning service role — cover both creators.
ALTER DEFAULT PRIVILEGES FOR ROLE artha IN SCHEMA marketdata
  GRANT SELECT ON TABLES TO ay_backtest;
ALTER DEFAULT PRIVILEGES FOR ROLE ay_marketdata IN SCHEMA marketdata
  GRANT SELECT ON TABLES TO ay_backtest;
