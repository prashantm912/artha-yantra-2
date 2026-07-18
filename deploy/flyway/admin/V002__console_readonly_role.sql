-- SEC-02 (2026-07-18 comprehensive platform audit): the B5 read-only SQL console
-- (market-data-service, POST /api/v1/market/admin/query) ran arbitrary operator SQL
-- as the artha SUPERUSER behind a VERB-ONLY blocklist, so a statement the parser
-- allowed — `SELECT pg_read_file('/run/secrets/...')` — read container secrets. This
-- dedicated least-privilege LOGIN role runs the console's queries instead:
-- NOSUPERUSER, no CREATE, no pg_read_server_files membership, SELECT-only on the
-- marketdata schema the console legitimately serves (its search_path is
-- `marketdata, pg_catalog`). The verb blocklist + READ ONLY transaction stay as
-- defense-in-depth. See services/market-data-service ConsoleDataSource + AdminQueryService.
--
-- NO credential lives in a migration (A.9 / D13): the role is created with
-- PASSWORD NULL (login is disabled until a password is set — fail-closed), and the
-- `console-role-init` compose one-shot sets the real password every boot from
-- ARTHA_CONSOLE_DB_PASSWORD. Market-data ITs set the mock default themselves (they
-- have no one-shot). Roles are cluster-global while grants are per-database, so the
-- create is existence-guarded (CREATE ROLE is not idempotent) and the grants re-run
-- harmlessly per database. Mirrors the ay_backtest machinery in V001.

DO $$
BEGIN
  CREATE ROLE ay_console LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD NULL;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- Re-assert the non-privilege attributes should the role pre-exist (e.g. this
-- lineage running in a second database of the same cluster). NEVER sets a password.
ALTER ROLE ay_console LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- SELECT-only on the ONE schema the console serves. No USAGE on strategy/backtest —
-- the console can no longer read those schemas, a deliberate tightening from the
-- superuser it replaced. GRANT ON ALL TABLES covers tables that already exist when
-- this runs on an established database (live); ALTER DEFAULT PRIVILEGES covers tables
-- created afterwards (a fresh volume runs the admin lineage before marketdata's).
GRANT USAGE ON SCHEMA marketdata TO ay_console;
GRANT SELECT ON ALL TABLES IN SCHEMA marketdata TO ay_console;

ALTER DEFAULT PRIVILEGES FOR ROLE artha IN SCHEMA marketdata
  GRANT SELECT ON TABLES TO ay_console;
ALTER DEFAULT PRIVILEGES FOR ROLE ay_marketdata IN SCHEMA marketdata
  GRANT SELECT ON TABLES TO ay_console;
